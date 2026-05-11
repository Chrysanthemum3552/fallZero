package com.fallzero.app.ui.exercise

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentExerciseBinding
import com.fallzero.app.pose.PoseLandmarkerHelper
import com.fallzero.app.pose.engine.*
import com.fallzero.app.util.TTSManager
import com.fallzero.app.viewmodel.ExerciseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ExerciseFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentExerciseBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExerciseViewModel by activityViewModels()

    private var poseLandmarkerHelper: PoseLandmarkerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var ttsManager: TTSManager? = null
    private var hasNavigated = false
    // 카운트 음성 중복 발화 방지 (검사세션 패턴: 같은 카운트는 한 번만 발화)
    private var lastSpokenCount = -1
    // 양측 전환 안내 진행 중 = SideSwitch state observer가 한 번만 트리거되도록 보장
    private var sideSwitchInProgress = false
    // 코칭 큐 / 자세 오류 메시지 발화 쿨다운 (4초) — 같은 메시지가 너무 빈번하지 않게
    private var lastTransientMsgMs = 0L
    private var transientMsgHideRunnable: Runnable? = null
    private val TRANSIENT_MSG_COOLDOWN_MS = 4000L
    private val TRANSIENT_MSG_DISPLAY_MS = 2500L

    // 연습(calibration) TTS 상태 — 진입 발화/카운트 음성/완료 음성을 한 번씩만
    private var calibrationIntroSpoken = false
    private var lastCalibrationRepsSpoken = 0
    private var calibrationCompleteSpoken = false

    // ─── 사용자 카메라 이탈 / 부분 가림 / 정면 회피(측면 운동만) 감지 (2초 임계값) ───
    @Volatile private var lastValidFrameMs = 0L         // 어떤 landmark든 도착하면 갱신
    @Volatile private var lastFullBodyMs = 0L           // 전신이 보일 때만 갱신 — 부분 가림 감지용
    @Volatile private var lastSideViewMs = 0L           // 측면 자세일 때만 갱신 — 정면 회피 감지용
    @Volatile private var isPausedForUserAway = false   // 완전 이탈 (landmarks 없음)
    @Volatile private var isPausedForOcclusion = false  // 부분 가림 (landmarks 있지만 전신 안 보임)
    @Volatile private var isPausedForFrontFacing = false // 측면 운동 중 정면을 향함
    @Volatile private var userReturnInProgress = false
    private var userAwayCheckJob: Job? = null
    private var pauseAnnounceJob: Job? = null
    private val USER_AWAY_MSG = "카메라 앞으로 와주세요"
    private val OCCLUSION_MSG = "신체의 일부가 보이지 않습니다. 조금 더 뒤로 가주세요"
    private val FRONT_FACING_MSG = "옆으로 돌아주세요"
    // 어깨너비/SBU < 0.35 = 측면 자세 (SideRotationFragment의 SIDE_THRESHOLD와 동일)
    private val SIDE_VIEW_THRESHOLD = 0.35f

    // 카메라 전후면 전환
    @Volatile private var isFrontCamera: Boolean = false
    private var cameraPreview: Preview? = null
    private var cameraAnalyzer: ImageAnalysis? = null
    private val USER_AWAY_TIMEOUT_MS = 2000L
    private val PAUSE_RESUME_BUFFER_MS = 1500L
    private val PAUSE_TTS_LOOP_MS = 5000L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            // 카메라 권한 거부 시 세션 전체 abort (자동 forward 시 모든 운동을 0회로 skip하는 버그 방지)
            ttsManager?.speak("카메라 권한이 필요합니다. 설정에서 권한을 허용해주세요.")
            abortToHome()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExerciseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        hasNavigated = false
        cameraExecutor = Executors.newSingleThreadExecutor()
        ttsManager = TTSManager.getInstance(requireContext())
        // 사용자가 이전에 전환한 카메라 facing 복원 (영구 설정)
        isFrontCamera = com.fallzero.app.util.CameraFacingPref.isFrontCamera(requireContext())

        // SessionFlow에서 현재 운동 ID를 가져옴 (있으면), 없으면 prefs 폴백
        val step = SessionFlow.current()
        val exerciseId = if (step.type == SessionFlow.StepType.EXERCISE && step.exerciseId > 0) {
            step.exerciseId
        } else {
            requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
                .getInt("selected_exercise_id", 1)
        }
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val setLevel = prefs.getInt("current_set_level", 1)
        val isDebugMode = prefs.getBoolean("debug_mode", false)

        val engine = createEngine(exerciseId, setLevel)
        viewModel.reset()
        viewModel.initExercise(engine, exerciseId, setLevel)

        binding.tvExerciseName.text = SessionFlow.exerciseName(exerciseId)

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnStop.setOnClickListener { abortToHome() }
        binding.btnCameraFlip.setOnClickListener { toggleCameraFacing() }

        if (isDebugMode) {
            binding.btnDebugCount.visibility = View.VISIBLE
            binding.btnDebugCount.setOnClickListener { viewModel.debugIncrementCount() }
        } else {
            binding.btnDebugCount.visibility = View.GONE
        }

        // 안내 overlay 표시 (운동 시작 전 멘트 + 동영상 placeholder + 카운트다운).
        // 의자 안내는 멘트에 통합되어 있으므로 별도 TTS 호출 안 함.
        // 설정의 "안내 스킵" ON이면 overlay/멘트/카운트다운 전부 건너뛰고 즉시 측정 (테스트용).
        if (prefs.getBoolean("skip_guidance", false)) {
            binding.guidanceOverlay.visibility = View.GONE
            binding.tvGuidanceCountdown.visibility = View.GONE
            // 카메라 바인딩 시간 확보 위해 짧은 지연 후 시작 (안 그러면 첫 1~2초 frame 미도착으로 user-away 일시정지 발동)
            binding.root.postDelayed({
                if (_binding != null && !hasNavigated) {
                    lastSpokenCount = -1
                    viewModel.startMeasurement()
                    startUserAwayMonitor()
                }
            }, 800L)
        } else {
            showStartGuidance(exerciseId)
        }

        observeViewModel()
    }

    /**
     * 새 흐름 (Q1+Q12):
     *  1. 운동 안내 overlay + TTS 발화 (카운트다운 없음, 카메라 감지 모두 OFF)
     *  2. TTS 끝나면 overlay 닫고 → viewModel.startMeasurement() (연습 모드 시작)
     *  3. 사용자 2회 연습 후 isCalibrating: true→false 전환 감지 시 → handlePostCalibrationCountdown() 호출
     *  4. "좋아요" + "이제 본격적으로" + 3,2,1 + 시작! → startUserAwayMonitor (모든 카메라 감지 ON)
     */
    private fun showStartGuidance(exerciseId: Int) {
        val b = _binding ?: return
        val titleRes = guidanceTitleRes(exerciseId)
        val scriptRes = guidanceScriptRes(exerciseId)
        b.tvGuidanceTitle.text = getString(titleRes)
        b.tvGuidanceText.text = getString(scriptRes)
        b.tvGuidanceCountdown.visibility = View.GONE
        b.guidanceOverlay.visibility = View.VISIBLE

        // 안내 TTS만 발화 → 끝나면 바로 연습 모드 진입 (카운트다운 X, user-away monitor X).
        ttsManager?.speak(getString(scriptRes).replace("\n", " ")) {
            if (_binding == null || hasNavigated) return@speak
            // overlay 닫고 연습 모드 시작 — engine.processLandmarks ON, 다른 카메라 감지 OFF.
            _binding?.guidanceOverlay?.visibility = View.GONE
            _binding?.tvGuidanceCountdown?.visibility = View.GONE
            lastSpokenCount = -1
            viewModel.startMeasurement()
            // userAwayMonitor는 본 운동 시작 후에만 — handlePostCalibrationCountdown에서 호출.
        }
    }

    /**
     * 연습(calibration) 종료 후 본 운동 진입 시퀀스 (Q12).
     * "좋아요!" + "이제 본격적으로 시작할게요" + 3,2,1 + 시작! + 1.5초 → user-away monitor 시작.
     * 동안 measurement는 일시정지 (사용자가 자세 잡을 시간).
     */
    private var postCalibrationStarted = false
    private fun handlePostCalibrationCountdown() {
        if (postCalibrationStarted || _binding == null || hasNavigated) return
        postCalibrationStarted = true
        viewModel.pauseMeasurementForSideSwitch()  // measurement 일시정지

        ttsManager?.speak("좋아요! 이제 본격적으로 시작할게요.") {
            if (_binding == null || hasNavigated) return@speak
            startCountdown321(insideOverlay = false) {
                if (_binding == null || hasNavigated) return@startCountdown321
                _binding?.root?.postDelayed({
                    if (_binding != null && !hasNavigated) {
                        lastSpokenCount = -1
                        // 본 운동 시작: measurement 재개 + 모든 카메라 감지 활성
                        viewModel.startMeasurement()
                        startUserAwayMonitor()
                    }
                }, POST_START_DELAY_MS)
            }
        }
    }

    private fun guidanceTitleRes(id: Int): Int = when (id) {
        1 -> R.string.ex_guide_1_title
        2 -> R.string.ex_guide_2_title
        3 -> R.string.ex_guide_3_title
        4 -> R.string.ex_guide_4_title
        5 -> R.string.ex_guide_5_title
        6 -> R.string.ex_guide_6_title
        7 -> R.string.ex_guide_7_title
        8 -> R.string.ex_guide_8_title
        else -> R.string.ex_guide_2_title
    }

    private fun guidanceScriptRes(id: Int): Int = when (id) {
        1 -> R.string.ex_guide_1
        2 -> R.string.ex_guide_2
        3 -> R.string.ex_guide_3
        4 -> R.string.ex_guide_4
        5 -> R.string.ex_guide_5
        6 -> R.string.ex_guide_6
        7 -> R.string.ex_guide_7
        8 -> R.string.ex_guide_8
        else -> R.string.ex_guide_2
    }

    private fun createEngine(exerciseId: Int, setLevel: Int): ExerciseEngine {
        // 양측 운동: 한쪽당 10회 (양측 합산 20회는 ViewModel에서 처리)
        val targetCount = 10
        return when (exerciseId) {
            1 -> KneeExtensionEngine(targetCount)
            2 -> HipAbductionEngine(targetCount)
            3 -> KneeFlexionEngine(targetCount)
            4 -> CalfRaiseEngine(targetCount)
            5 -> ToeRaiseEngine(targetCount)
            6 -> KneeBendEngine(targetCount)
            7 -> ChairStandEngine(targetCount, examMode = false)
            // #8 한 발 서기: 자세는 항상 stage 4 (한 발), setLevel은 손 지지 안내(TTS)에만 영향.
            // 측정 시간은 10초 고정 (기준치 도달 시 즉시 통과 — 사용자 명시).
            // 좌→우 양측 흐름: BalanceEngine.lockedLiftSide가 첫 frame에서 자동 감지, onSideSwitch에서 flip.
            8 -> BalanceEngine(targetCount = 1, stage = 4, overrideTargetTimeSec = 10f)
            else -> KneeExtensionEngine(targetCount)
        }
    }

    private fun startCamera() {
        if (_binding == null) return
        try { poseLandmarkerHelper = PoseLandmarkerHelper(requireContext(), this) }
        catch (e: Exception) { Log.e(TAG, "init failed", e); return }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            if (_binding == null) return@addListener
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                            poseLandmarkerHelper?.detectLiveStream(imageProxy, isFrontCamera = isFrontCamera)
                                ?: imageProxy.close()
                        }
                    }
                cameraPreview = preview
                cameraAnalyzer = imageAnalyzer
                bindCameraToSelector(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                _binding?.tvErrorMessage?.text = "카메라 오류: ${e.message}"
                _binding?.tvErrorMessage?.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val b = _binding ?: return@collect
                    when (state) {
                        ExerciseViewModel.ExerciseUiState.Idle -> {}
                        is ExerciseViewModel.ExerciseUiState.Ready -> {
                            // 안내 overlay가 음성/카운트다운 담당. Ready는 화면 텍스트만 갱신.
                            b.tvExerciseName.text = state.exerciseName +
                                (state.bilateralSide?.let { " — $it" } ?: "")
                            b.tvCount.text = if (getCurrentExerciseId() == 8) "0초"
                                else getString(R.string.exercise_count_format, 0, state.targetCount)
                            b.tvErrorMessage.visibility = View.GONE
                        }
                        is ExerciseViewModel.ExerciseUiState.Running -> {
                            val isBalance = getCurrentExerciseId() == 8
                            if (state.isCalibrating) {
                                // 연습(캘리브레이션) 모드 — "연습 N/2" 표시 + 진입 멘트/카운트 음성
                                b.tvCount.text = "연습 ${state.calibrationReps}/2"
                                b.tvErrorMessage.text = getString(R.string.exercise_calibrating)
                                b.tvErrorMessage.visibility = View.VISIBLE
                                // 첫 진입 시 한 번만: "2회 정도 연습해볼까요?"
                                if (!calibrationIntroSpoken) {
                                    calibrationIntroSpoken = true
                                    ttsManager?.speak("두 번 정도 연습해볼까요? 천천히 따라 해보세요.")
                                }
                                // 연습 1회/2회 완료 시점에 카운트 음성 (state.calibrationReps가 증가했을 때만)
                                if (state.calibrationReps > lastCalibrationRepsSpoken) {
                                    lastCalibrationRepsSpoken = state.calibrationReps
                                    ttsManager?.speak("${state.calibrationReps}")
                                }
                                // 코칭 큐 ("더 ~ 해주세요") 4초 쿨다운으로 발화
                                if (state.coachingCueMessage != null) {
                                    showTransientMessage(state.coachingCueMessage)
                                }
                            } else if (isBalance) {
                                // 균형 운동(#8): BalanceEngine이 currentMetric을 elapsed seconds로 사용
                                // 발이 내려가면 BalanceEngine이 stableStartTimeMs=0L로 리셋 → currentMetric=0f
                                // → 화면에 "0초"가 아닌 "측정 중"으로 표시 (사용자 혼란 방지)
                                b.tvCount.text = when {
                                    state.count >= 1 -> "완료"
                                    state.engineState == EngineState.IN_MOTION && state.currentMetric > 0f ->
                                        "${state.currentMetric.toInt()}초"
                                    else -> "측정 중"
                                }
                                // 측정 중 카운트 음성: 1초마다 "1, 2, 3..." (검사 균형 검사와 동일 패턴은 아니지만,
                                // 운동에서는 매초 음성 발화로 사용자에게 진행 상황 알림). 단 같은 초는 한 번만.
                                if (state.engineState == EngineState.IN_MOTION) {
                                    val sec = state.currentMetric.toInt()
                                    if (sec in 1..9 && sec != lastSpokenCount) {
                                        lastSpokenCount = sec
                                        if (ttsManager?.isSpeaking() != true) {
                                            ttsManager?.speak("$sec")
                                        }
                                    }
                                } else {
                                    // IN_MOTION 아님 (발 내려감 또는 자세 오류) → 다음 진입 시 1초부터 다시 발화
                                    lastSpokenCount = -1
                                }
                                // 10초 도달 시점에 "십!" 발화 (count 1 = 목표 시간 충족) — 1.5초 후 SideSwitch/Completed가 다음 멘트
                                if (state.count >= 1 && lastSpokenCount != Int.MAX_VALUE) {
                                    lastSpokenCount = Int.MAX_VALUE
                                    ttsManager?.speak("십!")
                                }
                                // 자세 오류 hint — 4초 쿨다운으로 TTS 스팸 방지 ("반대쪽 발로 들어주세요" 등)
                                if (state.errorMessage != null) {
                                    showTransientMessage(state.errorMessage)
                                } else {
                                    b.tvErrorMessage.visibility = View.GONE
                                }
                            } else {
                                // 연습 → 본 운동 전환: 처음 entering Running with isCalibrating=false
                                // (Q12) "좋아요!" + 3,2,1 + 시작! → user-away monitor 시작
                                if (calibrationIntroSpoken && !calibrationCompleteSpoken) {
                                    calibrationCompleteSpoken = true
                                    handlePostCalibrationCountdown()
                                    return@collect  // 카운트다운 중이므로 이번 frame은 UI 업데이트 안 함
                                }
                                b.tvCount.text = getString(
                                    R.string.exercise_count_format, state.count, state.targetCount
                                )
                                // 카운트 음성 발화 (검사 의자 일어서기 패턴): 카운트 변할 때 한 번만
                                if (state.count > 0 && state.count != lastSpokenCount) {
                                    lastSpokenCount = state.count
                                    if (ttsManager?.isSpeaking() != true) {
                                        ttsManager?.speak("${state.count}")
                                    }
                                }
                                // transient 메시지 처리 (자세 오류 또는 코칭 큐 — 둘 다 한 frame에만 발생하는 이벤트).
                                // 우선순위: errorMessage > coachingCueMessage. 4초 쿨다운, 2.5초 화면 표시.
                                val transientMsg = state.errorMessage ?: state.coachingCueMessage
                                if (transientMsg != null) {
                                    showTransientMessage(transientMsg)
                                }
                            }
                            b.tvExerciseName.text = SessionFlow.exerciseName(getCurrentExerciseId()) +
                                (state.bilateralSide?.let { " — $it" } ?: "")
                        }
                        is ExerciseViewModel.ExerciseUiState.SideSwitch -> {
                            // 양측 운동 좌→우 전환: 짧은 안내 TTS + 5초 + 3..2..1 + "시작!"
                            // (안내 화면 재표시 없음 — 합의된 흐름)
                            if (sideSwitchInProgress) return@collect
                            sideSwitchInProgress = true
                            viewModel.pauseMeasurementForSideSwitch()
                            // 양측 전환 카운트다운 동안 사용자 이탈/가림/회전 감지 비활성 (사용자 자세 바꾸는 중)
                            userAwayCheckJob?.cancel()
                            pauseAnnounceJob?.cancel()
                            isPausedForUserAway = false
                            isPausedForOcclusion = false
                            isPausedForFrontFacing = false
                            _binding?.pauseOverlay?.visibility = View.GONE
                            // 연습 멘트는 좌측에서 이미 재생 — 우측에선 안 나오게 처리
                            // (calibrationIntroSpoken을 그대로 유지 → 우측 진입 시 첫 Running에서 "좋아요" 안내 안 띄우려면 calibrationCompleteSpoken도 true 유지)
                            lastSpokenCount = -1
                            handleSideSwitch(state.seconds)
                        }
                        is ExerciseViewModel.ExerciseUiState.Completed -> {
                            if (hasNavigated) return@collect
                            // 완료 멘트 제거 — 다음 단계의 "고생하셨어요. 15초 쉬었다 가요" 멘트와 겹쳐 묻혀버림.
                            // 점수 정보는 Records에 저장되므로 UI에서 확인 가능.
                            navigateNext()
                        }
                    }
                }
            }
        }
    }

    private fun getCurrentExerciseId(): Int {
        val step = SessionFlow.current()
        return if (step.type == SessionFlow.StepType.EXERCISE) step.exerciseId
        else requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
            .getInt("selected_exercise_id", 1)
    }

    private fun navigateNext() {
        if (hasNavigated) return
        hasNavigated = true
        cleanupCamera()
        // SessionFlow가 활성이면 다음 단계로
        if (SessionFlow.sessionType != SessionFlow.SessionType.NONE) {
            val next = SessionFlow.advance()
            navigateTo(next)
        } else {
            findNavController().navigate(R.id.action_global_home)
        }
    }

    private fun navigateTo(step: SessionFlow.Step) {
        val nav = findNavController()
        when (step.type) {
            SessionFlow.StepType.EXERCISE -> nav.navigate(R.id.action_global_exercise)
            SessionFlow.StepType.EXAM_BALANCE,
            SessionFlow.StepType.EXAM_CHAIR_STAND -> nav.navigate(R.id.action_global_exam)
            SessionFlow.StepType.REST,
            SessionFlow.StepType.SIDE_REST -> nav.navigate(R.id.action_global_rest)
            SessionFlow.StepType.SIDE_ROTATION -> nav.navigate(R.id.action_global_rotation)
            SessionFlow.StepType.CHAIR_REPOSITION -> nav.navigate(R.id.action_global_chair_reposition)
            SessionFlow.StepType.DONE -> {
                // 전체 루틴 완료(=오늘 운동 완료): 축하 overlay + TTS 후 홈 이동.
                // 단일 운동(individual)은 즉시 홈 이동 (current behavior).
                if (SessionFlow.isFullExerciseSession) {
                    showSessionCompleteOverlay()
                } else {
                    SessionFlow.reset()
                    nav.navigate(R.id.action_global_home)
                }
            }
            SessionFlow.StepType.PRE_FLIGHT -> nav.navigate(R.id.action_global_preflight)
        }
    }

    private fun showSessionCompleteOverlay() {
        val b = _binding ?: return
        // 풀 세션 완료 → DB에 isCompleted=1 표시 (대시보드 "오늘 운동: 완료!" 갱신)
        viewModel.markFullSessionComplete()
        b.sessionCompleteOverlay.visibility = View.VISIBLE
        // 다른 overlay 가려서 충돌 방지
        b.guidanceOverlay.visibility = View.GONE
        b.pauseOverlay.visibility = View.GONE
        // TTS 콜백으로 정확한 종료 시점에 홈으로 이동, 콜백이 안 떠도 4초 fallback.
        var navigated = false
        val goHome = {
            if (!navigated) {
                navigated = true
                SessionFlow.reset()
                if (_binding != null && isAdded) {
                    findNavController().navigate(R.id.action_global_home)
                }
            }
        }
        ttsManager?.speak("오늘 운동을 모두 마치셨어요. 정말 수고하셨어요.") { goHome() }
        b.root.postDelayed({ goHome() }, 4500L)
    }

    private fun abortToHome() {
        if (hasNavigated) return
        hasNavigated = true
        cleanupCamera()
        SessionFlow.reset()
        findNavController().navigate(R.id.action_global_home)
    }

    /**
     * 양측 운동 좌→우 전환 안내 (사용자 명시 흐름):
     *   "한쪽이 끝났어요. 이제 다른 쪽 발/다리로 [동작]. 3초 뒤 시작합니다." TTS 종료
     *   → 3-2-1 카운트다운 + "시작!" → 0.75초 buffer → startRightSide
     * 모든 양측 운동: 자세 그대로 유지 + 발만 바꿈 (방향 회전 안 함).
     */
    private fun handleSideSwitch(seconds: Int) {
        val b = _binding ?: return
        val exerciseId = getCurrentExerciseId()
        val msg = when (exerciseId) {
            8 -> "한쪽이 끝났어요. 이번엔 다른 쪽 발로 들어주세요. 3초 뒤 시작합니다."
            1 -> "한쪽이 끝났어요. 이번엔 다른 쪽 다리를 앞으로 펴주세요. 3초 뒤 시작합니다."
            else -> "한쪽이 끝났어요. 이번엔 다른 쪽 다리로 해주세요. 3초 뒤 시작합니다."
        }
        b.tvErrorMessage.visibility = View.VISIBLE
        b.tvErrorMessage.text = "다른 쪽 준비"

        // 안내 TTS 종료 → 3-2-1 카운트다운 → "시작!" → 0.75초 buffer → 측정 시작
        ttsManager?.speak(msg) {
            if (_binding == null || hasNavigated) return@speak
            startCountdown321(insideOverlay = false) {
                if (_binding == null || hasNavigated) return@startCountdown321
                _binding?.root?.postDelayed({
                    if (_binding != null && !hasNavigated) {
                        _binding?.tvErrorMessage?.visibility = View.GONE
                        sideSwitchInProgress = false
                        viewModel.startRightSide()
                        startUserAwayMonitor()
                    }
                }, POST_START_DELAY_MS)
            }
        }
    }

    /** 사용자 명시: 모든 카운트다운 후 "시작!" 발화 종료부터 측정 시작까지 0.75초 buffer */
    private val POST_START_DELAY_MS = 750L

    /**
     * 자세 오류 / 코칭 큐 같은 transient 메시지 표시:
     *  4초 쿨다운(같은/다른 메시지 모두 적용) + 2.5초 화면 표시 후 자동 해제.
     *  TTS는 flush=false로 큐잉 (카운트 음성과 충돌 방지).
     */
    private fun showTransientMessage(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastTransientMsgMs < TRANSIENT_MSG_COOLDOWN_MS) return
        lastTransientMsgMs = now
        val b = _binding ?: return
        b.tvErrorMessage.text = msg
        b.tvErrorMessage.visibility = View.VISIBLE
        ttsManager?.speak(msg)

        transientMsgHideRunnable?.let { b.tvErrorMessage.removeCallbacks(it) }
        val runnable = Runnable { _binding?.tvErrorMessage?.visibility = View.GONE }
        transientMsgHideRunnable = runnable
        b.tvErrorMessage.postDelayed(runnable, TRANSIENT_MSG_DISPLAY_MS)
    }

    // ─── 폐기: ttsManager.speak(text, onDone) callback 패턴으로 모두 마이그레이션됨 ───

    /** 3, 2, 1 카운트다운 → "시작!" TTS 완료 후 onReady.
     *  insideOverlay=true: overlay 안의 tv_guidance_countdown에 표시.
     *  insideOverlay=false: 양측 전환처럼 카메라 위 일반 카운트 영역에 표시. */
    private fun startCountdown321(insideOverlay: Boolean, onReady: () -> Unit) {
        val b = _binding ?: return
        // insideOverlay=false인 경우 tv_big_countdown(별도 큰 floating)을 사용해 전신 가림 방지
        val countdownView = if (insideOverlay) b.tvGuidanceCountdown else b.tvBigCountdown

        countdownView.visibility = View.VISIBLE
        countdownView.text = "3"
        ttsManager?.speak("삼")
        b.root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            (if (insideOverlay) _binding?.tvGuidanceCountdown else _binding?.tvBigCountdown)?.text = "2"
            ttsManager?.speak("이")

            _binding?.root?.postDelayed({
                if (_binding == null || hasNavigated) return@postDelayed
                (if (insideOverlay) _binding?.tvGuidanceCountdown else _binding?.tvBigCountdown)?.text = "1"
                ttsManager?.speak("일")

                _binding?.root?.postDelayed({
                    if (_binding == null || hasNavigated) return@postDelayed
                    (if (insideOverlay) _binding?.tvGuidanceCountdown else _binding?.tvBigCountdown)?.text = "시작!"
                    ttsManager?.speak("시작!") {
                        if (!insideOverlay) _binding?.tvBigCountdown?.visibility = View.GONE
                        if (_binding != null && !hasNavigated) onReady()
                    }
                }, 1000L)
            }, 1000L)
        }, 1000L)
    }

    private fun bindCameraToSelector(provider: ProcessCameraProvider) {
        val selector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                       else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            val preview = cameraPreview ?: return
            val analyzer = cameraAnalyzer ?: return
            provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analyzer)
        } catch (e: Exception) {
            Log.e(TAG, "bindCameraToSelector failed", e)
        }
    }

    private fun toggleCameraFacing() {
        isFrontCamera = !isFrontCamera
        com.fallzero.app.util.CameraFacingPref.setFrontCamera(requireContext(), isFrontCamera)
        cameraProvider?.let { bindCameraToSelector(it) }
    }

    private fun cleanupCamera() {
        try {
            poseLandmarkerHelper?.clearPoseLandmarker()
            poseLandmarkerHelper = null
            cameraProvider?.unbindAll()
            cameraProvider = null
        } catch (e: Exception) { Log.e(TAG, "cleanup", e) }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        val result = resultBundle.results.firstOrNull() ?: return
        val landmarks = result.landmarks().firstOrNull() ?: return
        // 유효한 landmarks 도착 시 이탈/가림/정면 timestamp 갱신
        val now = System.currentTimeMillis()
        lastValidFrameMs = now
        val fullBody = isFullBodyVisible(landmarks)
        if (fullBody) lastFullBodyMs = now
        // 측면 자세 판별: 어깨 너비 / SBU < 0.35 (정면이면 어깨가 넓게 보임 ≥ 0.40)
        val isSideView = isSideFacing(landmarks)
        if (isSideView) lastSideViewMs = now
        // 복귀 처리: 우선순위 = 완전 이탈 > 부분 가림 > 정면 회피
        if (isPausedForUserAway) onUserReturned()
        else if (isPausedForOcclusion && fullBody) onOcclusionCleared()
        else if (isPausedForFrontFacing && isSideView) onSideViewReturned()
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            if (!isAdded || hasNavigated) return@runOnUiThread
            // setResults 내부에서 invalidate() 호출 — 중복 호출 제거
            b.poseOverlay.setResults(result, resultBundle.inputImageHeight, resultBundle.inputImageWidth)
            viewModel.processLandmarks(landmarks)
        }
    }

    /** 측면 자세 판별: 어깨 너비/SBU < SIDE_VIEW_THRESHOLD = 측면. 정면일 땐 어깨 넓게 보임. */
    private fun isSideFacing(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): Boolean {
        val sbu = com.fallzero.app.pose.SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return false
        val l = landmarks[com.fallzero.app.pose.AngleCalculator.LandmarkIndex.LEFT_SHOULDER]
        val r = landmarks[com.fallzero.app.pose.AngleCalculator.LandmarkIndex.RIGHT_SHOULDER]
        val shoulderWidth = kotlin.math.abs(l.x() - r.x())
        return (shoulderWidth / sbu) < SIDE_VIEW_THRESHOLD
    }

    /** 사용자 이탈/가림/정면 회피 감지 timer 시작 — startMeasurement 호출 직후 시작 (안내 화면 동안은 비활성). */
    private fun startUserAwayMonitor() {
        userAwayCheckJob?.cancel()
        val nowInit = System.currentTimeMillis()
        lastValidFrameMs = nowInit
        lastFullBodyMs = nowInit
        lastSideViewMs = nowInit
        // 운동 #1(앉아서 무릎 펴기): 다리를 앞으로 펴면 발목이 카메라 밖으로 나갈 수 있어
        // 부분 가림 감지가 false-positive 발생 → 비활성. 사용자 명시 (#1만).
        val occlusionCheckEnabled = getCurrentExerciseId() != 1
        // 측면 운동(#1, #3, #4, #5, #6) 정면 회피 감지: FRONT_EXERCISES(#2, #7, #8) 제외
        val sideFacingCheckEnabled = getCurrentExerciseId() !in SessionFlow.FRONT_EXERCISES
        userAwayCheckJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isAdded && !hasNavigated) {
                delay(500)
                if (isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) continue
                val now = System.currentTimeMillis()
                // 우선순위: 완전 이탈 > 부분 가림 > 정면 회피
                if (lastValidFrameMs > 0L && now - lastValidFrameMs > USER_AWAY_TIMEOUT_MS) {
                    onUserAway()
                } else if (occlusionCheckEnabled && lastFullBodyMs > 0L && now - lastFullBodyMs > USER_AWAY_TIMEOUT_MS) {
                    onPartialOcclusion()
                } else if (sideFacingCheckEnabled && lastSideViewMs > 0L && now - lastSideViewMs > USER_AWAY_TIMEOUT_MS) {
                    onFrontFacing()
                }
            }
        }
    }

    private fun onUserAway() {
        if (isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) return
        isPausedForUserAway = true
        viewModel.pauseForUserAway()
        showPauseOverlay(USER_AWAY_MSG)
    }

    private fun onPartialOcclusion() {
        if (isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) return
        isPausedForOcclusion = true
        viewModel.pauseForUserAway()  // engine pause는 동일 — overlay 메시지만 다름
        showPauseOverlay(OCCLUSION_MSG)
    }

    /** 측면 운동 중 사용자가 정면을 향함 → 일시정지 + "옆으로 돌아주세요" 반복 안내. */
    private fun onFrontFacing() {
        if (isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) return
        isPausedForFrontFacing = true
        viewModel.pauseForUserAway()
        showPauseOverlay(FRONT_FACING_MSG)
    }

    /** pause overlay 표시 + TTS 진입 발화 + 5초마다 반복 안내 */
    private fun showPauseOverlay(msg: String) {
        val b = _binding ?: return
        b.pauseOverlay.visibility = View.VISIBLE
        b.tvPauseMessage.text = msg
        ttsManager?.speak(msg)
        pauseAnnounceJob?.cancel()
        pauseAnnounceJob = viewLifecycleOwner.lifecycleScope.launch {
            while ((isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) && isAdded && !hasNavigated) {
                delay(PAUSE_TTS_LOOP_MS)
                if (isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) {
                    ttsManager?.speak(msg)
                }
            }
        }
    }

    private fun onUserReturned() {
        if (!isPausedForUserAway || userReturnInProgress) return
        userReturnInProgress = true
        pauseAnnounceJob?.cancel()
        // 자세 잡을 시간(1.5초) 버퍼 후 측정 재개
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PAUSE_RESUME_BUFFER_MS)
            userReturnInProgress = false
            if (!isAdded || hasNavigated) return@launch
            _binding?.pauseOverlay?.visibility = View.GONE
            isPausedForUserAway = false
            viewModel.resumeFromUserAway()
            ttsManager?.speak("다시 시작합니다.")
        }
    }

    private fun onOcclusionCleared() {
        if (!isPausedForOcclusion || userReturnInProgress) return
        userReturnInProgress = true
        pauseAnnounceJob?.cancel()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PAUSE_RESUME_BUFFER_MS)
            userReturnInProgress = false
            if (!isAdded || hasNavigated) return@launch
            _binding?.pauseOverlay?.visibility = View.GONE
            isPausedForOcclusion = false
            viewModel.resumeFromUserAway()
            ttsManager?.speak("전신이 잘 보입니다. 다시 시작합니다.")
        }
    }

    private fun onSideViewReturned() {
        if (!isPausedForFrontFacing || userReturnInProgress) return
        userReturnInProgress = true
        pauseAnnounceJob?.cancel()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PAUSE_RESUME_BUFFER_MS)
            userReturnInProgress = false
            if (!isAdded || hasNavigated) return@launch
            _binding?.pauseOverlay?.visibility = View.GONE
            isPausedForFrontFacing = false
            viewModel.resumeFromUserAway()
            ttsManager?.speak("좋아요. 다시 시작합니다.")
        }
    }

    /** 전신 가시성 — PreFlightFragment와 같은 패턴: 상반신 5/5 + ankleY span 0.30~0.97 */
    private fun isFullBodyVisible(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?
    ): Boolean {
        if (landmarks == null || landmarks.size < 33) return false
        val upperKeyIdx = intArrayOf(0, 11, 12, 23, 24)
        val upperVisible = upperKeyIdx.count { landmarks[it].visibility().orElse(0f) > 0.3f }
        if (upperVisible < 5) return false
        val noseY = landmarks[0].y()
        val ankleY = maxOf(landmarks[27].y(), landmarks[28].y())
        val span = ankleY - noseY
        if (span < 0.30f || span > 0.97f) return false
        if (noseY < 0.02f) return false
        return true
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            if (!isAdded) return@runOnUiThread
            b.tvErrorMessage.text = "MediaPipe 오류: $error"
            b.tvErrorMessage.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        userAwayCheckJob?.cancel(); userAwayCheckJob = null
        pauseAnnounceJob?.cancel(); pauseAnnounceJob = null
        cleanupCamera()
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
        _binding = null
    }

    companion object {
        private const val TAG = "ExerciseFragment"
    }
}
