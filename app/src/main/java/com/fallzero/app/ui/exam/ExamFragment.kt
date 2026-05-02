package com.fallzero.app.ui.exam

import android.Manifest
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
import com.fallzero.app.databinding.FragmentExamBinding
import com.fallzero.app.pose.PoseLandmarkerHelper
import com.fallzero.app.util.TTSManager
import com.fallzero.app.viewmodel.ExamViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 검사 세션 화면. SessionFlow에 따라 균형 또는 의자 일어서기 자동 실행.
 * 사용자 조작 없이 진행 — 시작 버튼 없음.
 */
class ExamFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentExamBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExamViewModel by activityViewModels()

    private var poseLandmarkerHelper: PoseLandmarkerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var ttsManager: TTSManager? = null
    private var hasNavigated = false
    private var lastSpokenSecond = -1      // TTS 초 카운트 중복 방지
    private var lastHintSpokenMs = 0L      // 에러 힌트 TTS 쿨다운 (3초 간격)
    private var lastHintText: String? = null // 같은 힌트 반복 방지
    private var chairPrepareMode = false       // 의자 준비 대기 중
    private var chairPreparePhase = 0          // 0=TTS재생중, 1=가만히서기감지, 2=안내완료
    private var bodyStillSinceMs = 0L
    private var lastShoulderY = 0f
    private val standingMSamples = mutableListOf<Float>()  // 서있는 동안 M 값 수집 (동적 임계값)
    private enum class Mode { BALANCE, CHAIR_STAND }
    private var mode: Mode = Mode.BALANCE

    // 카메라 전후면 전환 — 초기값은 SharedPreferences에서 읽음 (영구 설정)
    @Volatile private var isFrontCamera: Boolean = false
    private var cameraPreview: Preview? = null
    private var cameraAnalyzer: ImageAnalysis? = null

    // ─── 사용자 카메라 이탈 / 부분 가림 감지 (2초 임계값) ───
    @Volatile private var lastValidFrameMs = 0L
    @Volatile private var lastFullBodyMs = 0L
    @Volatile private var isPausedForUserAway = false
    @Volatile private var isPausedForOcclusion = false
    @Volatile private var userReturnInProgress = false
    private var userAwayCheckJob: Job? = null
    private var pauseAnnounceJob: Job? = null
    private val USER_AWAY_TIMEOUT_MS = 2000L
    private val PAUSE_RESUME_BUFFER_MS = 1500L
    private val PAUSE_TTS_LOOP_MS = 5000L
    private val EXAM_USER_AWAY_MSG = "카메라 앞으로 와주세요"
    private val EXAM_OCCLUSION_MSG = "신체의 일부가 보이지 않습니다. 조금 더 뒤로 가주세요"

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else navigateNext()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hasNavigated = false
        cameraExecutor = Executors.newSingleThreadExecutor()
        ttsManager = TTSManager.getInstance(requireContext())
        // 사용자가 이전에 전환한 카메라 facing 복원
        isFrontCamera = com.fallzero.app.util.CameraFacingPref.isFrontCamera(requireContext())

        // SessionFlow에서 현재 단계 확인
        val step = SessionFlow.current()
        mode = when (step.type) {
            SessionFlow.StepType.EXAM_CHAIR_STAND -> Mode.CHAIR_STAND
            else -> Mode.BALANCE
        }

        // UI 초기화: 자동 진행이므로 시작 버튼 숨김
        binding.btnStartExam.visibility = View.GONE
        binding.btnBalanceFail.visibility = View.GONE
        binding.tvCount.visibility = View.VISIBLE
        binding.tvTimer.visibility = View.VISIBLE

        // Chair stand mode: 이전 균형 검사의 stale BalanceComplete 상태를 클리어
        // (observer가 BalanceComplete를 보고 즉시 navigateNext 하는 버그 방지)
        if (mode == Mode.CHAIR_STAND) {
            viewModel.resetPhaseToIdle()
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnCameraFlip.setOnClickListener { toggleCameraFacing() }

        observeViewModel()
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
                val rs = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val analyzer = ImageAnalysis.Builder()
                    .setResolutionSelector(rs)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build().also { ia ->
                        ia.setAnalyzer(cameraExecutor!!) { proxy ->
                            // 매 frame isFrontCamera 필드 읽기 (전환 즉시 반영)
                            poseLandmarkerHelper?.detectLiveStream(proxy, isFrontCamera = isFrontCamera)
                                ?: proxy.close()
                        }
                    }
                cameraPreview = preview
                cameraAnalyzer = analyzer
                bindCameraToSelector(provider)
                // 카메라 준비 완료 → 검사 자동 시작
                autoStartExam()
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                _binding?.tvExamPhase?.text = "카메라 오류: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun autoStartExam() {
        when (mode) {
            Mode.BALANCE -> {
                // 첫 균형 단계 진입은 BalancePrepare phase observer에서 overlay로 처리
                viewModel.startBalanceFlow()
            }
            Mode.CHAIR_STAND -> {
                // 의자 준비: 안내 overlay → 사용자가 의자 가져오는 동안 멘트 발화
                //          → overlay 닫기 → 가만히 서기 감지 3초 → 설명 + 카운트다운 → 시작
                chairPrepareMode = true
                chairPreparePhase = 0  // 0=TTS재생중 + overlay 표시
                bodyStillSinceMs = 0L
                lastShoulderY = 0f
                standingMSamples.clear()
                val b = _binding ?: return
                b.tvExamPhase.text = "의자 일어서기 검사"
                b.tvTimer.text = ""
                b.tvCount.text = "의자를 카메라 앞에 가져와서\n놓아주세요.\n\n의자 앞에 가만히 서면\n자동으로 시작됩니다."
                b.tvCount.setTextColor(0xFF1565C0.toInt())
                // textSize는 XML에 28sp 고정 — 동적 변경 제거 (전신 가림 방지)
                showExamGuidance(
                    titleRes = R.string.exam_guide_chair_title,
                    scriptRes = R.string.exam_guide_chair
                ) {
                    // 안내 멘트 끝 → overlay 닫고 가만히 서기 감지(Phase 1)로 전환
                    if (_binding != null && !hasNavigated && chairPrepareMode) {
                        chairPreparePhase = 1
                        _binding?.tvTimer?.text = "의자 앞에 가만히 서주세요"
                    }
                }
            }
        }
    }

    /**
     * 검사 단계 시작 전 안내 overlay 표시:
     *  1. overlay 표시 (제목 + 동영상 placeholder + 안내 멘트)
     *  2. 안내 멘트 TTS 발화
     *  3. waitForTtsFinish → overlay 닫기 → onAfterScript() 호출
     * 균형 4단계는 onAfterScript에서 카운트다운 후 측정 시작 (별도 함수 처리).
     * 의자 검사는 onAfterScript에서 가만히 서기 감지 phase로 전환.
     */
    private fun showExamGuidance(titleRes: Int, scriptRes: Int, onAfterScript: () -> Unit) {
        val b = _binding ?: return
        b.tvGuidanceTitle.text = getString(titleRes)
        b.tvGuidanceText.text = getString(scriptRes)
        b.tvGuidanceCountdown.visibility = View.GONE
        b.guidanceOverlay.visibility = View.VISIBLE
        // TTS 콜백 — polling 없이 정확히 발화 종료 시점에 다음 단계
        ttsManager?.speak(getString(scriptRes).replace("\n", " ")) {
            if (_binding == null || hasNavigated) return@speak
            _binding?.guidanceOverlay?.visibility = View.GONE
            onAfterScript()
        }
    }

    private fun balanceTitleRes(stage: Int): Int = when (stage) {
        1 -> R.string.exam_guide_balance_1_title
        2 -> R.string.exam_guide_balance_2_title
        3 -> R.string.exam_guide_balance_3_title
        4 -> R.string.exam_guide_balance_4_title
        else -> R.string.exam_guide_balance_1_title
    }

    private fun balanceScriptRes(stage: Int): Int = when (stage) {
        1 -> R.string.exam_guide_balance_1
        2 -> R.string.exam_guide_balance_2
        3 -> R.string.exam_guide_balance_3
        4 -> R.string.exam_guide_balance_4
        else -> R.string.exam_guide_balance_1
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.phase.collect { phase ->
                    val b = _binding ?: return@collect
                    when (phase) {
                        is ExamViewModel.ExamPhase.ChairStand -> {
                            b.tvExamPhase.text = if (phase.errorHint != null)
                                phase.errorHint else getString(R.string.exam_phase_chair)
                            b.tvExamPhase.setTextColor(
                                if (phase.errorHint != null) 0xFFFF9800.toInt() else 0xFFFFFFFF.toInt()
                            )
                            b.tvTimer.text = "${phase.remainingSec}초"
                            b.tvCount.text = "${phase.count}회"
                            b.tvCount.setTextColor(0xFFFFFFFF.toInt())
                            // 횟수 음성 카운트
                            if (phase.count > 0 && phase.count != lastSpokenSecond) {
                                lastSpokenSecond = phase.count
                                if (ttsManager?.isSpeaking() != true) {
                                    ttsManager?.speak("${phase.count}")
                                }
                            }
                            // 얕은 앉기 피드백 TTS (4초 쿨다운)
                            if (phase.errorHint != null) {
                                val now = System.currentTimeMillis()
                                if (now - lastHintSpokenMs > 4000L) {
                                    lastHintSpokenMs = now
                                    ttsManager?.speak(phase.errorHint)
                                }
                            }
                        }
                        is ExamViewModel.ExamPhase.ChairStandComplete -> {
                            // TTS 끝까지 들리고 나서 navigate
                            ttsManager?.speak("의자 일어서기 검사가 끝났어요.") {
                                if (_binding != null && !hasNavigated) navigateNext()
                            }
                        }
                        is ExamViewModel.ExamPhase.BalancePrepare -> {
                            // 균형 4단계 각각: 안내 overlay 표시 → 멘트 TTS → overlay 닫기 →
                            //                "이제 곧 시작합니다" → 3,2,1 → "시작!" → 1.5초 → 측정 시작.
                            // 화면 텍스트(tvExamPhase/tvCount)는 overlay 닫힌 직후 짧게 보였다가 Balance phase로 갱신됨.
                            b.tvExamPhase.text = "${phase.stage}단계: ${phase.stageName}"
                            b.tvTimer.text = "준비하세요"
                            b.tvCount.text = phase.stageInstruction
                            b.tvCount.setTextColor(0xFF1565C0.toInt())
                            // textSize는 XML에 28sp 고정 — 동적 변경 제거 (전신 가림 방지)
                            lastSpokenSecond = -1
                            lastHintText = null
                            showExamGuidance(
                                titleRes = balanceTitleRes(phase.stage),
                                scriptRes = balanceScriptRes(phase.stage)
                            ) {
                                // overlay 닫힌 후: "이제 곧 시작합니다" callback → 1초 buffer → 카운트다운 → 측정
                                ttsManager?.speak(getString(R.string.guidance_starting_soon)) {
                                    if (_binding == null || hasNavigated) return@speak
                                    _binding?.root?.postDelayed({
                                        if (_binding != null && !hasNavigated) startCountdown321 {
                                            _binding?.root?.postDelayed({
                                                if (_binding != null && !hasNavigated) {
                                                    lastHintSpokenMs = 0L
                                                    lastSpokenSecond = -1
                                                    viewModel.startBalanceMeasurementNow()
                                                    startUserAwayMonitor()
                                                }
                                            }, 1500L)
                                        }
                                    }, 1000L)
                                }
                            }
                        }
                        is ExamViewModel.ExamPhase.Balance -> {
                            b.tvExamPhase.text = "${phase.stage}단계: ${viewModel.getBalanceStageName(phase.stage)}"
                            val elapsed = phase.elapsedSec.toInt()
                            // 0초 flicker 방지: stage 전환 직후 첫 frame은 elapsed=0이라 화면 갱신 안 함
                            if (elapsed > 0) {
                                b.tvTimer.text = "$elapsed / ${phase.targetSec.toInt()}초"
                            }

                            if (phase.isStable) {
                                b.tvCount.text = "잘하고 있어요!"
                                b.tvCount.setTextColor(0xFF4CAF50.toInt())
                                // 초 단위 음성 카운트 — TTS가 안내음성 재생 중이면 건너뜀
                                if (elapsed > 0 && elapsed != lastSpokenSecond) {
                                    lastSpokenSecond = elapsed
                                    if (ttsManager?.isSpeaking() != true) {
                                        val korNum = when (elapsed) {
                                            1 -> "일"; 2 -> "이"; 3 -> "삼"; 4 -> "사"; 5 -> "오"
                                            6 -> "육"; 7 -> "칠"; 8 -> "팔"; 9 -> "구"; 10 -> "십"
                                            else -> elapsed.toString()
                                        }
                                        ttsManager?.speak(korNum)
                                    }
                                }
                            } else {
                                // 왜 안 되는지 피드백 표시 (화면에만 — 자세 힌트만 음성)
                                val hint = phase.errorHint
                                b.tvCount.text = hint ?: "자세를 유지하세요"
                                b.tvCount.setTextColor(0xFFFF9800.toInt())
                                lastSpokenSecond = -1
                                // 자세 교정 힌트만 TTS (자세 관련: "발을 모아주세요" 등)
                                // "균형을 잡아주세요"는 숫자 카운트와 겹치므로 음성 안 냄
                                if (hint != null && hint != "균형을 잡아주세요") {
                                    val now = System.currentTimeMillis()
                                    if (now - lastHintSpokenMs > 4000L) {
                                        lastHintSpokenMs = now
                                        lastHintText = hint
                                        ttsManager?.speak(hint)
                                    }
                                }
                            }
                        }
                        is ExamViewModel.ExamPhase.BalanceStagePassed -> {
                            // 단계 통과 — "십!" + "좋아요!" 시퀀스 후 다음 stage 진행 (음성 cut off 방지)
                            // 0초 flicker 방지를 위해 화면 텍스트 즉시 비움
                            b.tvTimer.text = ""
                            b.tvCount.text = "✓"
                            b.tvCount.setTextColor(0xFF4CAF50.toInt())
                            lastSpokenSecond = -1
                            ttsManager?.speakSequence("십!", "좋아요!") {
                                if (_binding != null && !hasNavigated) {
                                    viewModel.advanceFromStagePassed()
                                }
                            }
                        }
                        is ExamViewModel.ExamPhase.BalanceComplete -> {
                            b.tvExamPhase.text = ""
                            b.tvTimer.text = ""
                            b.tvCount.text = "✔ 끝!"
                            b.tvCount.setTextColor(0xFF4CAF50.toInt())
                            b.poseOverlay.clear()
                            // TTS 콜백 — 발화 완료 후 정확히 navigate (cut off 없음)
                            ttsManager?.speak("균형 검사가 끝났습니다! 수고하셨어요.") {
                                if (_binding != null && !hasNavigated) navigateNext()
                            }
                        }
                        is ExamViewModel.ExamPhase.Completed,
                        ExamViewModel.ExamPhase.Idle -> {}
                    }
                }
            }
        }
    }

    private fun navigateNext() {
        if (hasNavigated) return
        hasNavigated = true
        cleanupCamera()
        val next = SessionFlow.advance()
        if (next.type == SessionFlow.StepType.DONE) {
            // 검사 완료 화면 표시 → 3초 후 결과 화면
            viewModel.finalizeAndSave()
            val b = _binding ?: run {
                findNavController().navigate(R.id.action_global_exam_result)
                return
            }
            b.tvExamPhase.text = ""
            b.tvTimer.text = ""
            b.tvExamPhase.text = ""
            b.tvTimer.text = ""
            b.tvCount.text = "✔ 끝!"
            b.tvCount.setTextColor(0xFF4CAF50.toInt())
            b.poseOverlay.clear()
            // TTS 콜백 — 발화 완료 후 정확히 결과 화면으로
            ttsManager?.speak("모든 검사가 끝났습니다.") {
                if (_binding != null) {
                    findNavController().navigate(R.id.action_global_exam_result)
                }
            }
            return
        }
        navigateTo(next)
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
                viewModel.finalizeAndSave()
                nav.navigate(R.id.action_global_exam_result)
            }
            SessionFlow.StepType.PRE_FLIGHT -> nav.navigate(R.id.action_global_preflight)
        }
    }

    /** 현재 isFrontCamera 값으로 카메라 selector를 바꿔서 rebind */
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

    /** 우상단 flip 버튼 클릭 시 호출 — 변경된 설정을 영구 저장 */
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
        val landmarks = result.landmarks().firstOrNull()
        // 유효한 landmarks 도착 시 이탈/가림 timestamp 갱신 + 복귀 처리
        if (landmarks != null) {
            val now = System.currentTimeMillis()
            lastValidFrameMs = now
            val fullBody = isFullBodyVisible(landmarks)
            if (fullBody) lastFullBodyMs = now
            if (isPausedForUserAway) onUserReturned()
            else if (isPausedForOcclusion && fullBody) onOcclusionCleared()
        }
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            if (!isAdded || hasNavigated) return@runOnUiThread

            if (landmarks != null) {
                b.poseOverlay.setResults(result, resultBundle.inputImageHeight, resultBundle.inputImageWidth)
                b.poseOverlay.invalidate()
            }

            // 의자 준비: Phase 0=TTS재생중, 1=가만히서기감지, 2=완료
            if (chairPrepareMode) {
                // Phase 0: TTS 재생 중 — 아무것도 안 함 (waitForTtsFinish가 phase를 1로 바꿔줌)
                if (chairPreparePhase == 0) return@runOnUiThread

                // Phase 1: 가만히 서기 감지 (어깨 Y 변화 < 0.015 for 3초)
                if (chairPreparePhase == 1 && landmarks != null && landmarks.size >= 33) {
                    val shoulderY = (landmarks[11].y() + landmarks[12].y()) / 2
                    val ankleY = maxOf(landmarks[27].y(), landmarks[28].y())
                    val m = (ankleY - shoulderY) * 100f

                    // 어깨 Y 변화로 "가만히 서있음" 판단
                    val yChange = if (lastShoulderY > 0f) abs(shoulderY - lastShoulderY) else 1f
                    lastShoulderY = shoulderY

                    // 정면 감지: 어깨 너비가 SBU의 40% 이상이면 정면
                    val sbu = com.fallzero.app.pose.SBUCalculator.calculate(landmarks)
                    val shoulderWidth = abs(landmarks[11].x() - landmarks[12].x())
                    val isFacingFront = sbu > 0f && (shoulderWidth / sbu) > 0.40f

                    val isStill = yChange < 0.008f && isFullBodyVisible(landmarks) && isFacingFront

                    if (isStill) {
                        if (bodyStillSinceMs == 0L) bodyStillSinceMs = System.currentTimeMillis()
                        standingMSamples.add(m)
                        val held = System.currentTimeMillis() - bodyStillSinceMs
                        if (held >= 3000L) {
                            // 3초 가만히 섬 → 동적 임계값 설정 + 안내
                            chairPreparePhase = 2
                            val avgM = if (standingMSamples.isNotEmpty()) standingMSamples.average().toFloat() else 50f
                            (viewModel as? com.fallzero.app.viewmodel.ExamViewModel)?.let {
                                // chairStandEngine에 calibration 전달
                            }
                            // ExamViewModel의 chairStandEngine에 접근할 수 없으므로
                            // ViewModel에 메서드 추가하여 전달
                            viewModel.calibrateChairStand(avgM)

                            // 긴 안내는 음성으로 — 화면 tvCount는 작아서 짧게만
                            b.tvCount.text = "준비 완료"
                            b.tvCount.setTextColor(0xFF4CAF50.toInt())
                            b.tvTimer.text = ""
                            // TTS 콜백 → 1초 buffer → 카운트다운 → 1.5초 → 측정 시작
                            ttsManager?.speak(
                                "좋아요! 30초 내에 최대한 많이, 의자에 앉았다가 일어나시면 됩니다. " +
                                "팔은 가슴에 교차해주세요. 곧 시작합니다."
                            ) {
                                if (_binding == null || hasNavigated) return@speak
                                _binding?.root?.postDelayed({
                                    if (_binding != null && !hasNavigated) startCountdown321 {
                                        _binding?.root?.postDelayed({
                                            if (_binding != null && !hasNavigated && chairPrepareMode) {
                                                chairPrepareMode = false
                                                lastSpokenSecond = -1
                                                lastHintSpokenMs = 0L
                                                viewModel.startChairStand()
                                                startUserAwayMonitor()
                                            }
                                        }, 1500L)
                                    }
                                }, 1000L)
                            }
                        } else {
                            val remain = ((3000L - held) / 1000) + 1
                            b.tvTimer.text = "감지 중... ${remain}초"
                        }
                    } else {
                        bodyStillSinceMs = 0L
                        standingMSamples.clear()
                        b.tvTimer.text = "의자 앞에 가만히 서주세요"
                    }
                }
                return@runOnUiThread
            }

            // 일반 모드: 엔진에 랜드마크 전달
            if (landmarks != null) {
                viewModel.processLandmarks(landmarks)
            }
        }
    }

    // ─── 폐기: ttsManager.speak(text, onDone) callback 패턴으로 모두 마이그레이션됨 ───

    /** 3, 2, 1 → "시작!" 카운트다운. 화면 가운데 floating overlay(tv_big_countdown)에 큰 글씨로. */
    private fun startCountdown321(onReady: () -> Unit) {
        val b = _binding ?: return
        b.tvBigCountdown.visibility = View.VISIBLE
        b.tvBigCountdown.setTextColor(0xFFFFFF00.toInt())
        b.tvBigCountdown.text = "3"
        ttsManager?.speak("삼")
        b.root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            _binding?.tvBigCountdown?.text = "2"
            ttsManager?.speak("이")

            _binding?.root?.postDelayed({
                if (_binding == null || hasNavigated) return@postDelayed
                _binding?.tvBigCountdown?.text = "1"
                ttsManager?.speak("일")

                _binding?.root?.postDelayed({
                    if (_binding == null || hasNavigated) return@postDelayed
                    _binding?.tvBigCountdown?.text = "시작!"
                    _binding?.tvBigCountdown?.setTextColor(0xFF4CAF50.toInt())
                    // 마지막 발화 callback에서 onReady — polling 없이 정확
                    ttsManager?.speak("시작!") {
                        // big countdown 닫고 onReady
                        _binding?.tvBigCountdown?.visibility = View.GONE
                        if (_binding != null && !hasNavigated) onReady()
                    }
                }, 1000L)
            }, 1000L)
        }, 1000L)
    }

    /** 사용자 이탈/부분 가림 감지 timer 시작 — 측정 시작 시점에 호출. */
    private fun startUserAwayMonitor() {
        userAwayCheckJob?.cancel()
        val nowInit = System.currentTimeMillis()
        lastValidFrameMs = nowInit
        lastFullBodyMs = nowInit
        userAwayCheckJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isAdded && !hasNavigated) {
                delay(500)
                if (isPausedForUserAway || isPausedForOcclusion) continue
                val now = System.currentTimeMillis()
                if (lastValidFrameMs > 0L && now - lastValidFrameMs > USER_AWAY_TIMEOUT_MS) {
                    onUserAway()
                } else if (lastFullBodyMs > 0L && now - lastFullBodyMs > USER_AWAY_TIMEOUT_MS) {
                    onPartialOcclusion()
                }
            }
        }
    }

    private fun onUserAway() {
        if (isPausedForUserAway || isPausedForOcclusion) return
        val gap = System.currentTimeMillis() - lastValidFrameMs
        val currentPhase = viewModel.phase.value::class.simpleName ?: "?"
        Log.d(TAG, "★ onUserAway TRIGGERED — gap=${gap}ms, phase=$currentPhase")
        isPausedForUserAway = true
        viewModel.pauseForUserAway()
        showExamPauseOverlay(EXAM_USER_AWAY_MSG)
    }

    private fun onPartialOcclusion() {
        if (isPausedForUserAway || isPausedForOcclusion) return
        val gap = System.currentTimeMillis() - lastFullBodyMs
        Log.d(TAG, "★ onPartialOcclusion TRIGGERED — gap=${gap}ms")
        isPausedForOcclusion = true
        viewModel.pauseForUserAway()
        showExamPauseOverlay(EXAM_OCCLUSION_MSG)
    }

    /** pause overlay 표시 + TTS 진입 발화 + 5초마다 반복. 메시지 종류는 호출자가 결정. */
    private fun showExamPauseOverlay(msg: String) {
        val b = _binding ?: return
        b.pauseOverlay.visibility = View.VISIBLE
        b.tvPauseMessage.text = msg
        ttsManager?.speak(msg)
        pauseAnnounceJob?.cancel()
        pauseAnnounceJob = viewLifecycleOwner.lifecycleScope.launch {
            while ((isPausedForUserAway || isPausedForOcclusion) && isAdded && !hasNavigated) {
                delay(PAUSE_TTS_LOOP_MS)
                if (isPausedForUserAway || isPausedForOcclusion) {
                    ttsManager?.speak(msg)
                }
            }
        }
    }

    private fun onOcclusionCleared() {
        if (!isPausedForOcclusion || userReturnInProgress) return
        Log.d(TAG, "★ onOcclusionCleared — buffer ${PAUSE_RESUME_BUFFER_MS}ms 시작")
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

    private fun onUserReturned() {
        if (!isPausedForUserAway || userReturnInProgress) return
        Log.d(TAG, "★ onUserReturned — buffer ${PAUSE_RESUME_BUFFER_MS}ms 시작")
        userReturnInProgress = true
        pauseAnnounceJob?.cancel()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PAUSE_RESUME_BUFFER_MS)
            userReturnInProgress = false
            if (!isAdded || hasNavigated) return@launch
            _binding?.pauseOverlay?.visibility = View.GONE
            isPausedForUserAway = false
            Log.d(TAG, "★ resume complete — viewModel.resumeFromUserAway() 호출")
            viewModel.resumeFromUserAway()
            ttsManager?.speak("다시 시작합니다.")
        }
    }

    /** 전신이 보이는지 확인 (PreFlightFragment와 동일 로직 — 의자 시나리오 호환).
     *  상반신 5/5 visibility ≥ 0.3 + ankleY span 0.30~0.97 검증. 하반신 visibility는 검사 안 함
     *  (의자 등받이가 무릎/발목을 가려도 MediaPipe 추정 좌표로 span만 검증). */
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
        activity?.runOnUiThread { _binding?.tvExamPhase?.text = "오류: $error" }
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
        private const val TAG = "ExamFragment"
    }
}
