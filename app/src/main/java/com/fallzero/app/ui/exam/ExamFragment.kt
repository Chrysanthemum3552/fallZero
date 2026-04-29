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
        ttsManager = TTSManager(requireContext())

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
                            poseLandmarkerHelper?.detectLiveStream(proxy, isFrontCamera = false)
                                ?: proxy.close()
                        }
                    }
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
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
                b.tvCount.textSize = 20f
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
        ttsManager?.speak(getString(scriptRes).replace("\n", " "))
        waitForTtsFinish {
            if (_binding == null || hasNavigated) return@waitForTtsFinish
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
                            b.tvCount.textSize = 48f
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
                            ttsManager?.speak("의자 일어서기 검사가 끝났어요.")
                            navigateNext()
                        }
                        is ExamViewModel.ExamPhase.BalancePrepare -> {
                            // 균형 4단계 각각: 안내 overlay 표시 → 멘트 TTS → overlay 닫기 →
                            //                "이제 곧 시작합니다" → 3,2,1 → "시작!" → 1.5초 → 측정 시작.
                            // 화면 텍스트(tvExamPhase/tvCount)는 overlay 닫힌 직후 짧게 보였다가 Balance phase로 갱신됨.
                            b.tvExamPhase.text = "${phase.stage}단계: ${phase.stageName}"
                            b.tvTimer.text = "준비하세요"
                            b.tvCount.text = phase.stageInstruction
                            b.tvCount.setTextColor(0xFF1565C0.toInt())
                            b.tvCount.textSize = 20f
                            lastSpokenSecond = -1
                            lastHintText = null
                            showExamGuidance(
                                titleRes = balanceTitleRes(phase.stage),
                                scriptRes = balanceScriptRes(phase.stage)
                            ) {
                                // overlay 닫힌 후: "이제 곧 시작합니다" → 카운트다운 → 측정 시작
                                ttsManager?.speak(getString(R.string.guidance_starting_soon))
                                waitForTtsThenCountdown {
                                    _binding?.root?.postDelayed({
                                        if (_binding != null && !hasNavigated) {
                                            lastHintSpokenMs = 0L  // 잘못된 자세면 즉시 힌트 발화
                                            lastSpokenSecond = -1
                                            viewModel.startBalanceMeasurementNow()
                                            startUserAwayMonitor()
                                        }
                                    }, 1500L)
                                }
                            }
                        }
                        is ExamViewModel.ExamPhase.Balance -> {
                            b.tvExamPhase.text = "${phase.stage}단계: ${viewModel.getBalanceStageName(phase.stage)}"
                            val elapsed = phase.elapsedSec.toInt()
                            b.tvTimer.text = "$elapsed / ${phase.targetSec.toInt()}초"

                            if (phase.isStable) {
                                b.tvCount.text = "잘하고 있어요!"
                                b.tvCount.setTextColor(0xFF4CAF50.toInt())
                                b.tvCount.textSize = 36f
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
                                b.tvCount.textSize = 28f
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
                        is ExamViewModel.ExamPhase.BalanceComplete -> {
                            b.tvExamPhase.text = ""
                            b.tvTimer.text = ""
                            b.tvCount.text = "✔\n\n균형 검사가 끝났습니다!"
                            b.tvCount.setTextColor(0xFF4CAF50.toInt())
                            b.tvCount.textSize = 28f
                            b.poseOverlay.clear()
                            ttsManager?.speak("균형 검사가 끝났습니다! 수고하셨어요.")
                            val startMs = System.currentTimeMillis()
                            waitForTtsFinishSafe(startMs, 5000L) {
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
            b.tvCount.text = "✔\n\n모든 검사가 끝났습니다."
            b.tvCount.setTextColor(0xFF4CAF50.toInt())
            b.tvCount.textSize = 28f
            b.poseOverlay.clear()
            ttsManager?.speak("모든 검사가 끝났습니다.")
            // 3초 후 결과 화면으로 (단순 고정 대기 — 가장 안정적)
            b.root.postDelayed({
                if (_binding != null) {
                    findNavController().navigate(R.id.action_global_exam_result)
                }
            }, 3000L)
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
            SessionFlow.StepType.DONE -> {
                viewModel.finalizeAndSave()
                nav.navigate(R.id.action_global_exam_result)
            }
            SessionFlow.StepType.PRE_FLIGHT -> nav.navigate(R.id.action_global_preflight)
        }
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
        // 유효한 landmarks 도착 시 이탈 감지 timestamp 갱신 + (이탈 중이었으면) 복귀 처리
        if (landmarks != null) {
            lastValidFrameMs = System.currentTimeMillis()
            if (isPausedForUserAway) onUserReturned()
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

                            b.tvCount.text = "좋아요!\n\n30초 내에 최대한 많이\n의자에 앉았다가 일어나시면 됩니다.\n팔은 가슴에 교차해주세요."
                            b.tvCount.setTextColor(0xFF4CAF50.toInt())
                            b.tvCount.textSize = 18f
                            b.tvTimer.text = ""
                            ttsManager?.speak(
                                "좋아요! 30초 내에 최대한 많이, 의자에 앉았다가 일어나시면 됩니다. " +
                                "팔은 가슴에 교차해주세요. 곧 시작합니다."
                            )
                            waitForTtsThenCountdown {
                                // "시작!" 후 1.5초 여유 → 측정 시작 + 이탈 감지 monitor 시작
                                _binding?.root?.postDelayed({
                                    if (_binding != null && !hasNavigated && chairPrepareMode) {
                                        chairPrepareMode = false
                                        lastSpokenSecond = -1
                                        lastHintSpokenMs = 0L  // 힌트 즉시 허용
                                        viewModel.startChairStand()
                                        startUserAwayMonitor()
                                    }
                                }, 1500L)
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

    /** TTS 대기 + 최대 시간 안전장치 (무한 루프 방지) */
    private fun waitForTtsFinishSafe(startMs: Long, maxWaitMs: Long, onDone: () -> Unit) {
        _binding?.root?.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            val elapsed = System.currentTimeMillis() - startMs
            if (ttsManager?.isSpeaking() == true && elapsed < maxWaitMs) {
                waitForTtsFinishSafe(startMs, maxWaitMs, onDone)
            } else {
                onDone()
            }
        }, 500L)
    }

    /** TTS 끝날 때까지 대기 → callback */
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

    /** TTS 끝날 때까지 대기 → 1초 여유 → 3,2,1 카운트다운 → onReady */
    private fun waitForTtsThenCountdown(onReady: () -> Unit) {
        _binding?.root?.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            if (ttsManager?.isSpeaking() == true) {
                waitForTtsThenCountdown(onReady) // 아직 말하는 중 → 500ms 후 재확인
            } else {
                // TTS 끝남 → 1초 여유 후 카운트다운
                _binding?.root?.postDelayed({
                    if (_binding != null && !hasNavigated) startCountdown321(onReady)
                }, 1000L)
            }
        }, 500L)
    }

    /** 3, 2, 1 카운트다운 후 "시작!" TTS 완료 대기 → onReady 실행 */
    private fun startCountdown321(onReady: () -> Unit) {
        val b = _binding ?: return
        b.tvCount.textSize = 80f
        b.tvCount.setTextColor(0xFFFFFFFF.toInt())

        b.tvCount.text = "3"
        ttsManager?.speak("삼")
        b.root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            _binding?.tvCount?.text = "2"
            ttsManager?.speak("이")

            _binding?.root?.postDelayed({
                if (_binding == null || hasNavigated) return@postDelayed
                _binding?.tvCount?.text = "1"
                ttsManager?.speak("일")

                _binding?.root?.postDelayed({
                    if (_binding == null || hasNavigated) return@postDelayed
                    _binding?.tvCount?.text = "시작!"
                    _binding?.tvCount?.setTextColor(0xFF4CAF50.toInt())
                    ttsManager?.speak("시작!")

                    // "시작!" TTS 완전히 끝난 후 onReady (800ms 고정 대신 TTS 대기)
                    waitForTtsFinish {
                        if (_binding != null && !hasNavigated) onReady()
                    }
                }, 1000L)
            }, 1000L)
        }, 1000L)
    }

    /** 사용자 이탈 감지 timer 시작 — 측정 시작 시점에 호출. */
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

    /** 전신이 보이는지 확인 (PreFlightFragment와 동일 로직) */
    private fun isFullBodyVisible(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?
    ): Boolean {
        if (landmarks == null || landmarks.size < 33) return false
        val keyIdx = intArrayOf(0, 11, 12, 23, 24, 25, 26, 27, 28)
        val visible = keyIdx.count { landmarks[it].visibility().orElse(0f) > 0.5f }
        if (visible < 7) return false
        val noseY = landmarks[0].y()
        val ankleY = maxOf(landmarks[27].y(), landmarks[28].y())
        val span = ankleY - noseY
        if (span < 0.40f || span > 0.97f) return false
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
