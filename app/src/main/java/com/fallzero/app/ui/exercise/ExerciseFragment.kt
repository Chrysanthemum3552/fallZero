package com.fallzero.app.ui.exercise

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
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
    private var sideSwitchTimer: CountDownTimer? = null
    // 카운트 음성 중복 발화 방지 (검사세션 패턴: 같은 카운트는 한 번만 발화)
    private var lastSpokenCount = -1
    // 양측 전환 안내 진행 중 = SideSwitch state observer가 한 번만 트리거되도록 보장
    private var sideSwitchInProgress = false
    // 코칭 큐 / 자세 오류 메시지 발화 쿨다운 (4초) — 같은 메시지가 너무 빈번하지 않게
    private var lastTransientMsgMs = 0L
    private var transientMsgHideRunnable: Runnable? = null
    private val TRANSIENT_MSG_COOLDOWN_MS = 4000L
    private val TRANSIENT_MSG_DISPLAY_MS = 2500L

    // ─── 사용자 카메라 이탈 감지 (2초간 valid landmarks 없으면 일시정지) ───
    @Volatile private var lastValidFrameMs = 0L
    @Volatile private var isPausedForUserAway = false
    @Volatile private var userReturnInProgress = false
    private var userAwayCheckJob: Job? = null
    private var pauseAnnounceJob: Job? = null
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
        ttsManager = TTSManager(requireContext())

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

        if (isDebugMode) {
            binding.btnDebugCount.visibility = View.VISIBLE
            binding.btnDebugCount.setOnClickListener { viewModel.debugIncrementCount() }
        } else {
            binding.btnDebugCount.visibility = View.GONE
        }

        // 안내 overlay 표시 (운동 시작 전 멘트 + 동영상 placeholder + 카운트다운).
        // 의자 안내는 멘트에 통합되어 있으므로 별도 TTS 호출 안 함.
        showStartGuidance(exerciseId)

        observeViewModel()
    }

    /**
     * 운동 시작 전 안내 overlay 표시 흐름:
     *  1. 운동 제목 + 동영상 placeholder + 안내 멘트 표시
     *  2. 안내 멘트 TTS 발화
     *  3. waitForTtsThenCountdown → 3..2..1 카운트다운 → "시작!"
     *  4. "시작!" 후 1.5초 → overlay 닫고 viewModel.startMeasurement() 호출
     */
    private fun showStartGuidance(exerciseId: Int) {
        val b = _binding ?: return
        val titleRes = guidanceTitleRes(exerciseId)
        val scriptRes = guidanceScriptRes(exerciseId)
        b.tvGuidanceTitle.text = getString(titleRes)
        b.tvGuidanceText.text = getString(scriptRes)
        b.tvGuidanceCountdown.visibility = View.GONE
        b.guidanceOverlay.visibility = View.VISIBLE

        // 의자 운동(#1, #7)은 자리 안내가 멘트에 포함되어 있어 의자 별도 안내 불필요.
        // 안내 멘트 → "이제 곧 시작합니다 / (의자 운동이면) 앉아주세요" → 카운트다운
        ttsManager?.speak(getString(scriptRes).replace("\n", " "))
        waitForTtsFinish {
            if (_binding == null || hasNavigated) return@waitForTtsFinish
            val startingMsgRes = if (exerciseId in SessionFlow.CHAIR_REQUIRED_EXERCISES)
                R.string.guidance_starting_soon_chair
            else
                R.string.guidance_starting_soon
            ttsManager?.speak(getString(startingMsgRes))
            waitForTtsThenCountdown(insideOverlay = true) {
                // "시작!" 후 1.5초 여유 → overlay 닫고 측정 시작 + 이탈 감지 monitor 시작
                _binding?.root?.postDelayed({
                    if (_binding != null && !hasNavigated) {
                        _binding?.guidanceOverlay?.visibility = View.GONE
                        _binding?.tvGuidanceCountdown?.visibility = View.GONE
                        lastSpokenCount = -1
                        viewModel.startMeasurement()
                        startUserAwayMonitor()
                    }
                }, 1500L)
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
            8 -> BalanceEngine(targetCount = 1, stage = setLevel.coerceIn(1, 5))
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
                            poseLandmarkerHelper?.detectLiveStream(imageProxy, isFrontCamera = false)
                                ?: imageProxy.close()
                        }
                    }
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
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
                                // 캘리브레이션 중 — 카운트 대신 안내 표시
                                b.tvCount.text = "준비"
                                b.tvErrorMessage.text = getString(R.string.exercise_calibrating)
                                b.tvErrorMessage.visibility = View.VISIBLE
                            } else if (isBalance) {
                                // 균형 운동: BalanceEngine이 currentMetric을 elapsed seconds로 사용
                                b.tvCount.text = when {
                                    state.count >= 1 -> "완료"
                                    state.engineState == EngineState.IN_MOTION ->
                                        "${state.currentMetric.toInt()}초"
                                    else -> "측정 중"
                                }
                                when {
                                    state.errorMessage != null -> {
                                        b.tvErrorMessage.text = state.errorMessage
                                        b.tvErrorMessage.visibility = View.VISIBLE
                                        ttsManager?.speak(state.errorMessage)
                                    }
                                    else -> b.tvErrorMessage.visibility = View.GONE
                                }
                            } else {
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
                            // 양측 전환 카운트다운 동안 사용자 이탈 감지 비활성 (사용자 자세 바꾸는 중)
                            userAwayCheckJob?.cancel()
                            pauseAnnounceJob?.cancel()
                            isPausedForUserAway = false
                            _binding?.pauseOverlay?.visibility = View.GONE
                            lastSpokenCount = -1
                            handleSideSwitch(state.seconds)
                        }
                        is ExerciseViewModel.ExerciseUiState.Completed -> {
                            if (hasNavigated) return@collect
                            val praise = when {
                                state.qualityScore >= 90 -> "아주 잘하셨어요!"
                                state.qualityScore >= 70 -> "잘하셨어요!"
                                else -> "고생하셨어요!"
                            }
                            ttsManager?.speak("$praise ${state.qualityScore}점.")
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
            SessionFlow.StepType.DONE -> {
                SessionFlow.reset()
                nav.navigate(R.id.action_global_home)
            }
            SessionFlow.StepType.PRE_FLIGHT -> nav.navigate(R.id.action_global_preflight)
        }
    }

    private fun abortToHome() {
        if (hasNavigated) return
        hasNavigated = true
        cleanupCamera()
        SessionFlow.reset()
        findNavController().navigate(R.id.action_global_home)
    }

    /**
     * 양측 운동 좌→우 전환 안내:
     *  "이번엔 반대쪽 다리로 해주세요" TTS → seconds초 카운트다운(화면 텍스트만)
     *  → "이제 곧 시작합니다" → 3..2..1 → "시작!" → 1.5초 후 startRightSide().
     * 정면 운동(#2): 자세 그대로 다리만 바꿈 안내.
     * 측면 운동(#1, #3): 사용자가 카메라 반대쪽으로 자세 변경 필요 — 멘트 강화.
     */
    private fun handleSideSwitch(seconds: Int) {
        val b = _binding ?: return
        val exerciseId = getCurrentExerciseId()
        val isSideView = exerciseId !in SessionFlow.FRONT_EXERCISES
        val msg = if (isSideView)
            "왼쪽이 끝났어요. 반대쪽으로 돌아 오른쪽 다리로 해주세요."
        else
            "왼쪽이 끝났어요. 이번엔 오른쪽 다리로 해주세요."
        b.tvErrorMessage.visibility = View.VISIBLE
        b.tvErrorMessage.text = msg
        ttsManager?.speak(msg)

        sideSwitchTimer?.cancel()
        sideSwitchTimer = object : CountDownTimer((seconds * 1000L) + 100L, 1000L) {
            override fun onTick(msLeft: Long) {
                val s = (msLeft / 1000L).toInt().coerceAtLeast(0)
                _binding?.tvErrorMessage?.text = "오른쪽으로 준비해주세요 ($s)"
            }
            override fun onFinish() {
                if (_binding == null || hasNavigated) return
                ttsManager?.speak(getString(R.string.guidance_starting_soon))
                waitForTtsThenCountdown(insideOverlay = false) {
                    _binding?.root?.postDelayed({
                        if (_binding != null && !hasNavigated) {
                            _binding?.tvErrorMessage?.visibility = View.GONE
                            sideSwitchInProgress = false
                            viewModel.startRightSide()
                            // 우측 측정 시작 — 이탈 감지 monitor 재시작
                            startUserAwayMonitor()
                        }
                    }, 1500L)
                }
            }
        }.start()
    }

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

    /** TTS 끝날 때까지 대기 → callback (검사세션과 동일 패턴) */
    private fun waitForTtsFinish(onDone: () -> Unit) {
        _binding?.root?.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            if (ttsManager?.isSpeaking() == true) {
                waitForTtsFinish(onDone)
            } else {
                onDone()
            }
        }, 500L)
    }

    /** TTS 끝날 때까지 대기 → 1초 여유 → 3,2,1 카운트다운 → onReady. */
    private fun waitForTtsThenCountdown(insideOverlay: Boolean, onReady: () -> Unit) {
        _binding?.root?.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            if (ttsManager?.isSpeaking() == true) {
                waitForTtsThenCountdown(insideOverlay, onReady)
            } else {
                _binding?.root?.postDelayed({
                    if (_binding != null && !hasNavigated) startCountdown321(insideOverlay, onReady)
                }, 1000L)
            }
        }, 500L)
    }

    /** 3, 2, 1 카운트다운 → "시작!" TTS 완료 후 onReady.
     *  insideOverlay=true: overlay 안의 tv_guidance_countdown에 표시.
     *  insideOverlay=false: 양측 전환처럼 카메라 위 일반 카운트 영역에 표시. */
    private fun startCountdown321(insideOverlay: Boolean, onReady: () -> Unit) {
        val b = _binding ?: return
        val countdownView = if (insideOverlay) b.tvGuidanceCountdown else b.tvCount

        if (insideOverlay) {
            b.tvGuidanceCountdown.visibility = View.VISIBLE
        } else {
            countdownView.textSize = 80f
        }

        countdownView.text = "3"
        ttsManager?.speak("삼")
        b.root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            (if (insideOverlay) _binding?.tvGuidanceCountdown else _binding?.tvCount)?.text = "2"
            ttsManager?.speak("이")

            _binding?.root?.postDelayed({
                if (_binding == null || hasNavigated) return@postDelayed
                (if (insideOverlay) _binding?.tvGuidanceCountdown else _binding?.tvCount)?.text = "1"
                ttsManager?.speak("일")

                _binding?.root?.postDelayed({
                    if (_binding == null || hasNavigated) return@postDelayed
                    (if (insideOverlay) _binding?.tvGuidanceCountdown else _binding?.tvCount)?.text = "시작!"
                    ttsManager?.speak("시작!")

                    waitForTtsFinish {
                        if (_binding != null && !hasNavigated) onReady()
                    }
                }, 1000L)
            }, 1000L)
        }, 1000L)
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
        // 유효한 landmarks 도착 시 이탈 감지 timestamp 갱신 + (이탈 중이었으면) 복귀 처리
        lastValidFrameMs = System.currentTimeMillis()
        if (isPausedForUserAway) onUserReturned()
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            if (!isAdded || hasNavigated) return@runOnUiThread
            b.poseOverlay.setResults(result, resultBundle.inputImageHeight, resultBundle.inputImageWidth)
            b.poseOverlay.invalidate()
            viewModel.processLandmarks(landmarks)
        }
    }

    /** 사용자 이탈 감지 timer 시작 — startMeasurement 호출 직후 시작 (안내 화면 동안은 비활성). */
    private fun startUserAwayMonitor() {
        userAwayCheckJob?.cancel()
        lastValidFrameMs = System.currentTimeMillis()
        userAwayCheckJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isAdded && !hasNavigated) {
                delay(500)
                if (isPausedForUserAway) continue
                val now = System.currentTimeMillis()
                if (lastValidFrameMs > 0L && now - lastValidFrameMs > USER_AWAY_TIMEOUT_MS) {
                    onUserAway()
                }
            }
        }
    }

    private fun onUserAway() {
        if (isPausedForUserAway) return
        isPausedForUserAway = true
        viewModel.pauseForUserAway()
        _binding?.pauseOverlay?.visibility = View.VISIBLE
        // TTS 진입 발화 + 5초마다 반복 (노년층이 한 번 놓쳐도 다시 안내)
        ttsManager?.speak("카메라 앞으로 와주세요.")
        pauseAnnounceJob?.cancel()
        pauseAnnounceJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isPausedForUserAway && isAdded && !hasNavigated) {
                delay(PAUSE_TTS_LOOP_MS)
                if (isPausedForUserAway) {
                    ttsManager?.speak("카메라 앞으로 와주세요.")
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
        sideSwitchTimer?.cancel(); sideSwitchTimer = null
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
