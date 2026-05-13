package com.fallzero.app.ui.exam

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
    private var showGuide: Boolean = true
    private var lastSpokenSecond = -1      // TTS 초 카운트 중복 방지
    private var lastHintSpokenMs = 0L      // 에러 힌트 TTS 쿨다운 (3초 간격)
    private var lastHintText: String? = null // 같은 힌트 반복 방지
    private var chairPrepareMode = false       // 의자 준비 대기 중
    private var chairPreparePhase = 0          // 0=TTS재생중, 1=가만히서기감지, 2=안내완료
    // 사용자 명시 2번: bar 시뮬레이션 시점엔 hideExamGuides() 호출 안 함 (bar 유지)
    private var isBarSimulating = false
    // 사용자 명시: ring 설명 중에는 updateExamGuide의 null 반환에 의한 hideExamGuides() 차단 (ring 유지)
    private var isRingExplaining = false
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
        // 표시 옵션 — 관절 점 (default OFF) + 가이드 (default ON)
        binding.poseOverlay.setShowSkeleton(com.fallzero.app.util.DisplayPrefs.showSkeleton(requireContext()))
        showGuide = com.fallzero.app.util.DisplayPrefs.showGuide(requireContext())

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
                // 첫 균형 단계 진입은 BalancePrepare phase observer에서 overlay로 처리.
                // 사용자 명시 8번: SessionFlow.singleBalanceStage > 0이면 단일 stage만.
                val singleStage = SessionFlow.singleBalanceStage
                if (singleStage in 1..4) {
                    SessionFlow.singleBalanceStage = 0  // 1회용 — 다음 검사 영향 없게 리셋
                    viewModel.startSingleBalanceStage(singleStage)
                } else {
                    viewModel.startBalanceFlow()
                }
            }
            Mode.CHAIR_STAND -> {
                // 사용자 명시 흐름:
                //  1) 의자 앞 정면 이미지 + "의자가 필요합니다" TTS (phase=0)
                //  2) 가만히 3초 감지 + standing baseline 측정 (phase=1)
                //  3) 영상 안내 + bar 설명 (phase=2 후)
                //  4) 3,2,1 → 시작!
                chairPrepareMode = true
                chairPreparePhase = 0
                bodyStillSinceMs = 0L
                lastShoulderY = 0f
                standingMSamples.clear()
                val b = _binding ?: return
                b.tvExamPhase.text = "의자 일어서기 검사"
                b.tvTimer.text = ""
                b.tvCount.text = ""
                showChairPrepareImage()
            }
        }
    }

    /** 사용자 명시: 의자 앞 정면 이미지 + TTS → 5초 후 이미지/overlay 닫고 카메라 보임 → 가만히 감지. */
    private fun showChairPrepareImage() {
        val b = _binding ?: return
        b.tvGuidanceTitle.text = "의자 일어서기 검사"
        b.tvGuidanceText.text = "의자 앞에 정면을 바라보고 서주세요."
        b.tvGuidanceCountdown.visibility = View.GONE
        b.tvVideoPlaceholder.visibility = View.GONE
        b.videoChairGuidance.visibility = View.GONE
        b.ivChairFrontPose.setImageResource(R.drawable.chair_front_pose)
        b.ivChairFrontPose.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.VISIBLE
        ttsManager?.speak(
            "이번 검사는 의자가 필요합니다. 의자를 가져오셔서 의자 앞에 정면을 바라보고 서주세요."
        )
        // 사용자 명시 3번: 이미지 5초 후 → guidance_overlay/이미지 닫고 카메라 선명하게 보임 + 가만히 감지 시작
        //   가만히 감지 단계는 dim 없이 카메라 선명 (사용자가 자세 확인 가능)
        b.root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            _binding?.ivChairFrontPose?.visibility = View.GONE
            _binding?.guidanceOverlay?.visibility = View.GONE
            chairPreparePhase = 1
            showFloatingFeedback("의자 앞에 정면을 바라보고 가만히 서주세요", 0xFFFFEB3B.toInt())
        }, 5000L)
    }

    /**
     * 의자 일어서기 검사 안내:
     *  1) "다음 운동은... 우선 안내 영상을 시청하겠습니다" TTS + 화면 가운데 "안내 영상" 큰 텍스트
     *  2) TTS 끝 → 텍스트 hide + 영상 재생 + 자막 동기 TTS
     *  3) 영상 끝 → bar 시뮬레이션
     *  4) onAfterScript → 3,2,1 → 검사 시작
     */
    private fun showChairStandGuidance(onAfterScript: () -> Unit) {
        val b = _binding ?: return
        // 이미지 GONE — 영상 단계 진입. 안내 overlay 다시 표시 + "안내 영상" 큰 텍스트
        b.ivChairFrontPose.visibility = View.GONE
        b.videoChairGuidance.visibility = View.GONE
        b.tvVideoPlaceholder.text = "안내 영상"
        b.tvVideoPlaceholder.textSize = 64f
        b.tvVideoPlaceholder.setTextColor(0xFFFFFF00.toInt())  // 노란
        b.tvVideoPlaceholder.visibility = View.VISIBLE
        b.tvGuidanceTitle.text = "의자 일어서기 검사"
        b.tvGuidanceText.text = ""
        b.guidanceOverlay.visibility = View.VISIBLE
        ttsManager?.speak(
            "다음 운동은 의자 앉았다 일어서기 입니다. 우선 안내 영상을 시청하겠습니다."
        ) {
            if (_binding == null || hasNavigated) return@speak
            _binding?.tvVideoPlaceholder?.visibility = View.GONE
            playChairGuidanceVideo(onAfterScript)
        }
    }

    private fun playChairGuidanceVideo(onAfterScript: () -> Unit) {
        val b = _binding ?: return
        b.tvGuidanceTitle.text = "의자 일어서기 검사"
        b.tvGuidanceText.text = ""
        b.tvGuidanceCountdown.visibility = View.GONE
        b.tvVideoPlaceholder.visibility = View.GONE
        // 사용자 명시: 영상 아래 자막 — 검정 배경 + 노란 글자, 자막 타이밍 동기
        b.tvChairSubtitle.text = ""
        b.tvChairSubtitle.visibility = View.VISIBLE
        b.videoChairGuidance.setZOrderOnTop(true)
        b.videoChairGuidance.visibility = View.VISIBLE
        val uri = Uri.parse("android.resource://${requireContext().packageName}/${R.raw.chair_stand_guide}")
        b.videoChairGuidance.setOnPreparedListener { mp ->
            mp.isLooping = false
            mp.setVolume(0f, 0f)
            adjustVideoToAspectRatio(mp.videoWidth, mp.videoHeight)
            mp.start()
        }
        b.videoChairGuidance.setVideoURI(uri)
        b.guidanceOverlay.visibility = View.VISIBLE

        val root = b.root
        // 자막 + TTS 동기 (영상 자막 타이밍에 맞춤)
        root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            _binding?.tvChairSubtitle?.text = "팔을 가슴 앞에 교차하세요"
            ttsManager?.speak("팔을 교차하세요")
        }, 2000L)
        root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            _binding?.tvChairSubtitle?.text = "이제 천천히 앉았다 일어서세요"
            ttsManager?.speak("이제 천천히 앉았다 일어서세요")
        }, 4500L)
        root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            _binding?.tvChairSubtitle?.text = "이 동작을 반복합니다"
            ttsManager?.speak("반복합니다")
        }, 12500L)
        // 영상 ~14.7초 후 → 자막/영상 정리 + dim 유지하면서 bar 시뮬레이션
        root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            _binding?.videoChairGuidance?.stopPlayback()
            _binding?.videoChairGuidance?.visibility = View.GONE
            _binding?.tvChairSubtitle?.visibility = View.GONE
            _binding?.guidanceOverlay?.visibility = View.GONE
            _binding?.vCountdownDim?.visibility = View.VISIBLE
            startBarSimulation(onAfterScript)
        }, 15000L)
    }

    /** 사용자 명시 3-3: 실제 운동에서 표시되는 위치에 bar 표시 + 멘트별 애니메이션.
     *  - "화면 오른쪽에는 여러분의 자세를 감지하여 막대기가 올라가거나 내려갑니다"
     *  - "앉으면 막대기가 내려가고" + bar 위→아래 채움
     *  - "일어서면 막대기가 올라갑니다" + bar 아래→위 비움
     *  - "이 막대기가 완전히 내려갔다가 완전히 올라가야 한 번으로 인정됩니다" */
    private fun startBarSimulation(onAfterScript: () -> Unit) {
        val b = _binding ?: return
        isBarSimulating = true
        val barGuide = { p: Float ->
            com.fallzero.app.ui.overlay.ExerciseGuide.Bar(
                progress = p, vertical = true,
                fillDirection = com.fallzero.app.ui.overlay.ExerciseGuide.FillDirection.UP,
                label = "예시", justReached = p >= 0.99f
            )
        }
        b.guideBar.visibility = View.VISIBLE
        // 사용자 명시 3번: 처음 가득(일어선 자세) 상태에서 시작
        b.guideBar.setGuide(barGuide(1f))
        b.guideBar.bringToFront()

        ttsManager?.speak("화면 오른쪽에는 여러분의 자세를 감지하여 막대기가 올라가거나 내려갑니다") {
            if (_binding == null || hasNavigated) return@speak
            ttsManager?.speak("앉으면 막대기가 내려가고") {
                if (_binding == null || hasNavigated) return@speak
                // 1.0 → 0.1 (빨간색 약간 남는 정도까지 내려감)
                animateBar(1f, 0.1f, 1500L, barGuide) {
                    if (_binding == null || hasNavigated) return@animateBar
                    _binding?.root?.postDelayed({
                        if (_binding == null || hasNavigated) return@postDelayed
                        ttsManager?.speak("일어서면 막대기가 올라갑니다") {
                            if (_binding == null || hasNavigated) return@speak
                            // 0.1 → 1.0 (다시 끝까지)
                            animateBar(0.1f, 1f, 1500L, barGuide) {
                                if (_binding == null || hasNavigated) return@animateBar
                                _binding?.root?.postDelayed({
                                    if (_binding == null || hasNavigated) return@postDelayed
                                    ttsManager?.speak("이 막대기가 완전히 내려갔다가 완전히 올라가야 한 번으로 인정됩니다") {
                                        if (_binding == null || hasNavigated) return@speak
                                        _binding?.guideBar?.visibility = View.GONE
                                        onAfterScript()
                                    }
                                }, 500L)
                            }
                        }
                    }, 500L)
                }
            }
        }
    }

    private fun animateBar(
        from: Float, to: Float, durationMs: Long,
        barGuide: (Float) -> com.fallzero.app.ui.overlay.ExerciseGuide.Bar,
        onEnd: () -> Unit
    ) {
        val anim = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            addUpdateListener {
                val p = it.animatedValue as Float
                _binding?.guideBar?.setGuide(barGuide(p))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
        }
        anim.start()
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
                            b.tvExamPhase.text = getString(R.string.exam_phase_chair)
                            b.tvExamPhase.setTextColor(0xFFFFFFFF.toInt())
                            // 사용자 명시: 큰 숫자 제거 → 우상단 corner에 작게 표시
                            b.tvTimer.text = ""
                            b.tvCount.text = ""
                            if (phase.isRunning) {
                                b.layoutCornerCount.visibility = View.VISIBLE
                                b.tvCornerTimer.text = "${phase.remainingSec}초"
                                b.tvCornerCount.text = "${phase.count}회"
                                // 사용자 명시 1번: 첫 카운트 후 ✓ 유지, errorHint 시 노란 피드백
                                when {
                                    phase.errorHint != null -> {
                                        showFloatingFeedback(phase.errorHint, 0xFFFFEB3B.toInt())
                                    }
                                    phase.count >= 1 -> {
                                        showFloatingFeedback("✓ 잘 하고 있어요!", 0xFF4CAF50.toInt())
                                    }
                                    else -> {
                                        // 첫 카운트 전 — 아무 표시 X
                                        hideFloatingFeedback()
                                    }
                                }
                            }
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
                            // 검사 완료 → 영상 정지 + 우상단 카운트 GONE + floating 클리어
                            stopChairGuideVideo()
                            b.layoutCornerCount.visibility = View.GONE
                            hideFloatingFeedback()
                            // TTS 끝까지 들리고 나서 navigate
                            ttsManager?.speak("의자 일어서기 검사가 끝났어요.") {
                                if (_binding != null && !hasNavigated) navigateNext()
                            }
                        }
                        is ExamViewModel.ExamPhase.BalancePrepare -> {
                            // 균형 검사 진입 — 사용자 명시: 의자 일어서기 흐름과 유사 (영상+ring 시뮬+"이제 시작")
                            b.layoutCornerCount.visibility = View.GONE
                            hideFloatingFeedback()
                            stopChairGuideVideo()
                            b.tvExamPhase.text = "${phase.stage}단계: ${phase.stageName}"
                            b.tvTimer.text = ""
                            b.tvCount.text = ""
                            lastSpokenSecond = -1
                            lastHintText = null
                            showBalanceGuidance(phase.stage, phase.stageName) {
                                if (_binding == null || hasNavigated) return@showBalanceGuidance
                                startCountdown321 {
                                    if (_binding != null && !hasNavigated) {
                                        lastHintSpokenMs = 0L
                                        lastSpokenSecond = -1
                                        viewModel.startBalanceMeasurementNow()
                                        startUserAwayMonitor()
                                    }
                                }
                            }
                        }
                        is ExamViewModel.ExamPhase.Balance -> {
                            b.tvExamPhase.text = "${phase.stage}단계: ${viewModel.getBalanceStageName(phase.stage)}"
                            val elapsed = phase.elapsedSec.toInt()
                            // 사용자 명시: 균형 검사는 링 안에 큰 숫자가 보이므로 하단 큰 숫자는 빈 문자열로 (중복 제거 + 카메라 영역 확보)
                            b.tvTimer.text = ""
                            b.tvTimerLabel.text = ""

                            if (phase.isStable) {
                                // 사용자 명시 7번: 잘 하면 floating에 ✓ 초록 메시지
                                showFloatingFeedback("✓ 잘 하고 있어요!", 0xFF4CAF50.toInt())
                                b.tvCount.text = ""
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
                                // 자세 hint floating (노란색)
                                val hint = phase.errorHint
                                showFloatingFeedback(hint ?: "자세를 유지하세요", 0xFFFFEB3B.toInt())
                                b.tvCount.text = ""
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
                            b.tvCount.text = ""
                            showFloatingFeedback("✓ 통과!", 0xFF4CAF50.toInt())
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

    /** 검사 가이드 표시 — 균형 검사면 bubble, 의자 일어서기면 bar. */
    private fun updateExamGuide(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ) {
        val b = _binding ?: return
        val guide = viewModel.getGuide(landmarks)
        when (guide) {
            is com.fallzero.app.ui.overlay.ExerciseGuide.Bar -> {
                b.guideBar.visibility = View.VISIBLE
                b.guideBubble.visibility = View.GONE
                b.guideBar.setGuide(guide)
            }
            is com.fallzero.app.ui.overlay.ExerciseGuide.Bubble -> {
                b.guideBar.visibility = View.GONE
                b.guideBubble.visibility = View.VISIBLE
                b.guideBubble.setGuide(guide)
            }
            null -> hideExamGuides()
        }
    }

    private fun hideExamGuides() {
        _binding?.guideBar?.visibility = View.GONE
        _binding?.guideBubble?.visibility = View.GONE
    }

    /** 사용자 명시: 균형 검사 1~4단계 — 의자 일어서기 흐름과 유사.
     *  1) "안내 영상" 큰 텍스트 + TTS "N단계 ___ 균형 검사입니다. 우선 안내 영상을 시청하겠습니다"
     *  2) balance_guide.mp4 placeholder 영상 재생 (자막 없음)
     *  3) 영상 끝 → guidance_overlay 닫음 + dim VISIBLE → ring 시뮬레이션 (시계방향 채움 0→1)
     *  4) "10초간 자세 유지!" 큰 텍스트 + TTS
     *  5) onAfter → 3,2,1 → 검사 시작 */
    private fun showBalanceGuidance(stage: Int, stageName: String, onAfter: () -> Unit) {
        val b = _binding ?: return
        // 사용자 명시 1번: N단계만 쓰지 말고 자세 이름도 함께
        b.tvGuidanceTitle.text = "${stage}단계 · $stageName 균형 검사"
        b.tvGuidanceText.text = ""
        b.tvGuidanceCountdown.visibility = View.GONE
        b.videoChairGuidance.visibility = View.GONE
        b.ivChairFrontPose.visibility = View.GONE
        b.tvChairSubtitle.visibility = View.GONE
        b.tvVideoPlaceholder.text = "안내 영상"
        b.tvVideoPlaceholder.textSize = 64f
        b.tvVideoPlaceholder.setTextColor(0xFFFFFF00.toInt())
        b.tvVideoPlaceholder.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.VISIBLE
        ttsManager?.speak(
            "${stage}단계, $stageName 균형 검사입니다. 우선 안내 영상을 시청하겠습니다."
        ) {
            if (_binding == null || hasNavigated) return@speak
            _binding?.tvVideoPlaceholder?.visibility = View.GONE
            playBalanceGuidanceVideo(stage, stageName, onAfter)
        }
    }

    private fun playBalanceGuidanceVideo(stage: Int, stageName: String, onAfter: () -> Unit) {
        val b = _binding ?: return
        b.videoChairGuidance.setZOrderOnTop(true)
        b.videoChairGuidance.visibility = View.VISIBLE
        val uri = Uri.parse("android.resource://${requireContext().packageName}/${R.raw.balance_guide}")
        b.videoChairGuidance.setOnPreparedListener { mp ->
            mp.isLooping = false
            mp.setVolume(0f, 0f)
            adjustVideoToAspectRatio(mp.videoWidth, mp.videoHeight)
            mp.start()
        }
        b.videoChairGuidance.setOnCompletionListener {
            if (_binding == null || hasNavigated) return@setOnCompletionListener
            _binding?.videoChairGuidance?.visibility = View.GONE
            _binding?.guidanceOverlay?.visibility = View.GONE
            _binding?.vCountdownDim?.visibility = View.VISIBLE
            // 사용자 명시: 1단계 진입 시에만 ring 자세한 설명 시퀀스. 2~4단계는 곧장 "10초 유지!"
            if (stage == 1) {
                startRingExplanation(stage, stageName, onAfter)
            } else {
                showBalanceHoldCallout(stage, stageName, onAfter)
            }
        }
        b.videoChairGuidance.setVideoURI(uri)
    }

    /** 사용자 명시: 1단계 진입 시에만 — 부드러운 ring 설명.
     *  1) TTS "자세를 잘 잡으면 원이 시계방향으로 채워집니다" + ring 0→1 부드럽게 (점진 채움)
     *  2) ring 가득 도달 → TTS "자세가 틀리면 0초부터 다시 시작합니다" + ring 즉시 0으로 리셋
     *  3) showBalanceHoldCallout → onAfter */
    private fun startRingExplanation(stage: Int, stageName: String, onAfter: () -> Unit) {
        val b = _binding ?: return
        // ring 설명 중 onResults의 updateExamGuide가 ring을 GONE 처리하는 깜빡임 차단
        isRingExplaining = true
        b.guideBubble.visibility = View.VISIBLE
        b.guideBubble.bringToFront()
        // 사용자 명시: ring 위 작은 글씨(label) 제거 — 깨끗한 원만 보이게
        val mkBubble = { hold: Float ->
            com.fallzero.app.ui.overlay.ExerciseGuide.Bubble(
                swayRatio = 0f, holdProgress = hold, label = "",
                elapsedSec = hold * 10f, targetSec = 10f, poseValid = true
            )
        }
        b.guideBubble.setGuide(mkBubble(0f))
        b.guideBubble.invalidate()

        // 1) 첫 TTS 완전히 발화 후 ring 채움 시작 (순차 처리 — TTS QUEUE_FLUSH로 인한 cut 방지)
        ttsManager?.speak("자세를 잘 잡으면 원이 시계방향으로 부드럽게 채워집니다") {
            if (_binding == null || hasNavigated) return@speak
            animateRing(from = 0f, to = 1f, sway = 0f, durationMs = 3000L,
                mkBubble = { _: Float, hold: Float -> mkBubble(hold) }) {
                if (_binding == null || hasNavigated) return@animateRing
                // 2) ring 가득 → "자세 틀리면 0초부터" TTS + 발화 끝나면 ring 0 리셋 + 다음 단계
                ttsManager?.speak("자세가 틀리면 0초부터 다시 시작합니다") {
                    if (_binding == null || hasNavigated) return@speak
                    _binding?.guideBubble?.setGuide(mkBubble(0f))
                    _binding?.guideBubble?.invalidate()
                    _binding?.root?.postDelayed({
                        if (_binding == null || hasNavigated) return@postDelayed
                        isRingExplaining = false
                        _binding?.guideBubble?.visibility = View.GONE
                        showBalanceHoldCallout(stage, stageName, onAfter)
                    }, 1000L)
                }
            }
        }
    }

    private fun animateRing(
        from: Float, to: Float, sway: Float, durationMs: Long,
        mkBubble: (Float, Float) -> com.fallzero.app.ui.overlay.ExerciseGuide.Bubble,
        onEnd: () -> Unit
    ) {
        val anim = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            addUpdateListener {
                val p = it.animatedValue as Float
                _binding?.guideBubble?.setGuide(mkBubble(sway, p))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
        }
        anim.start()
    }

    /** 사용자 명시: ring 시계방향 채움 + 설명 TTS → "10초 유지!" 큰 글씨 + TTS. */
    private fun startRingSimulation(stage: Int, stageName: String, onAfter: () -> Unit) {
        val b = _binding ?: return
        // 깜빡임 방지 — onResults의 hideExamGuides() 호출 차단
        isRingExplaining = true
        b.guideBubble.visibility = View.VISIBLE
        b.guideBubble.bringToFront()
        b.guideBubble.setGuide(
            com.fallzero.app.ui.overlay.ExerciseGuide.Bubble(
                swayRatio = 0f, holdProgress = 0f, label = stageName,
                elapsedSec = 0f, targetSec = 10f, poseValid = true
            )
        )
        b.guideBubble.invalidate()
        // 시뮬 시작 시점에 설명 TTS 발화 (애니메이션과 동시에 진행)
        ttsManager?.speak(
            "제대로 된 자세를 하면 원의 테두리가 초록색이 되며 시계방향으로 채워집니다"
        )
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5000L  // TTS 길이에 맞춰 충분히 길게
            addUpdateListener {
                val p = it.animatedValue as Float
                _binding?.guideBubble?.setGuide(
                    com.fallzero.app.ui.overlay.ExerciseGuide.Bubble(
                        swayRatio = 0f, holdProgress = p, label = stageName,
                        elapsedSec = p * 10f, targetSec = 10f, poseValid = true
                    )
                )
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (_binding == null || hasNavigated) return
                    isRingExplaining = false  // ring 시뮬 종료
                    _binding?.guideBubble?.visibility = View.GONE
                    // "10초 유지!" 큰 글씨 + TTS "이 자세를 10초 유지해주세요"
                    showBalanceHoldCallout(stage, stageName, onAfter)
                }
            })
        }
        anim.start()
    }

    /** 사용자 명시: 균형 ring 시뮬 끝 → "10초 유지!" 큰 글씨 + TTS — 자세 이름 포함. */
    private fun showBalanceHoldCallout(stage: Int, stageName: String, onAfter: () -> Unit) {
        val b = _binding ?: return
        b.tvGuidanceTitle.text = "${stage}단계 · $stageName"
        b.tvGuidanceText.text = ""
        b.videoChairGuidance.visibility = View.GONE
        b.ivChairFrontPose.visibility = View.GONE
        b.tvChairSubtitle.visibility = View.GONE
        b.tvVideoPlaceholder.text = "10초간\n유지!"
        b.tvVideoPlaceholder.textSize = 64f
        b.tvVideoPlaceholder.setTextColor(0xFFFFFF00.toInt())
        b.tvVideoPlaceholder.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.VISIBLE
        ttsManager?.speak("$stageName 자세를 10초간 유지해주세요") {
            if (_binding == null || hasNavigated) return@speak
            _binding?.tvVideoPlaceholder?.visibility = View.GONE
            _binding?.guidanceOverlay?.visibility = View.GONE
            _binding?.vCountdownDim?.visibility = View.VISIBLE
            onAfter()
        }
    }

    /** 사용자 명시 1번: bar 설명 끝 → "30초간 최대한 많이!" 큰 텍스트(가운데, 64sp 노란) + TTS.
     *  guidance_overlay 완전 검정 배경 위에 표시 → TTS 끝나면 hide + 다음 흐름. */
    private fun showThirtySecCallout(onAfter: () -> Unit) {
        val b = _binding ?: return
        // bar 정리
        b.guideBar.visibility = View.GONE
        // guidance_overlay 다시 켜고 큰 텍스트만 표시
        b.tvGuidanceTitle.text = ""
        b.tvGuidanceText.text = ""
        b.videoChairGuidance.visibility = View.GONE
        b.ivChairFrontPose.visibility = View.GONE
        b.tvVideoPlaceholder.text = "30초간\n최대한 많이!"
        b.tvVideoPlaceholder.textSize = 56f
        b.tvVideoPlaceholder.setTextColor(0xFFFFFF00.toInt())
        b.tvVideoPlaceholder.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.VISIBLE
        ttsManager?.speak("이 자세를 30초간 최대한 많이 반복하시면 됩니다") {
            if (_binding == null || hasNavigated) return@speak
            _binding?.tvVideoPlaceholder?.visibility = View.GONE
            _binding?.guidanceOverlay?.visibility = View.GONE
            onAfter()
        }
    }

    /** 사용자 명시 3번: 영상 비율 보존 + 가용 공간 안에서 최대 사이즈로 표시.
     *  parent(ConstraintLayout) 측정 후 영상 비율 가지고 width/height 결정. */
    private fun adjustVideoToAspectRatio(videoW: Int, videoH: Int) {
        val view = _binding?.videoChairGuidance ?: return
        if (videoW <= 0 || videoH <= 0) return
        view.post {
            val v = _binding?.videoChairGuidance ?: return@post
            val parent = v.parent as? android.view.View ?: return@post
            val titleBottom = _binding?.tvGuidanceTitle?.bottom ?: 0
            val textTop = _binding?.layoutGuidanceText?.top ?: parent.height
            val pw = parent.width
            val ph = (textTop - titleBottom - 24).coerceAtLeast(0)  // top margin 12 + bottom margin 12
            if (pw <= 0 || ph <= 0) return@post
            val videoRatio = videoW.toFloat() / videoH
            val parentRatio = pw.toFloat() / ph
            val params = v.layoutParams
            if (videoRatio > parentRatio) {
                // 영상이 더 가로형 → 가로 가용공간 다 쓰고 세로 줄임
                params.width = pw
                params.height = (pw / videoRatio).toInt()
            } else {
                // 영상이 더 세로형 → 세로 가용공간 다 쓰고 가로 줄임
                params.height = ph
                params.width = (ph * videoRatio).toInt()
            }
            v.layoutParams = params
        }
    }

    /** 카메라 영역 floating 안내/피드백 표시 (사용자 명시 7번 + 6번).
     *  - 안내(chair 가만히 서기, '잘 하고 있어요'): textColor로 색 구분.
     *  - hideFloatingFeedback()으로 즉시 숨김. */
    private fun showFloatingFeedback(msg: String, colorArgb: Int) {
        val b = _binding ?: return
        b.tvFloatingFeedback.text = msg
        b.tvFloatingFeedback.setTextColor(colorArgb)
        b.tvFloatingFeedback.visibility = View.VISIBLE
    }

    private fun hideFloatingFeedback() {
        _binding?.tvFloatingFeedback?.visibility = View.GONE
    }

    /** ChairStand 검사 안내 영상 — 우하단 작게 무한 반복.
     *  음소거(setVolume 0,0) — TTS 나레이션이 별도로 영상 자막에 맞춰 발화. */
    private fun startChairGuideVideo() {
        val b = _binding ?: return
        val uri = Uri.parse("android.resource://${requireContext().packageName}/${R.raw.chair_stand_guide}")
        b.videoChairGuide.setZOrderOnTop(true)  // 우하단 corner 영상도 surface 최상단
        b.videoChairGuide.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.start()
        }
        b.videoChairGuide.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "video error: what=$what extra=$extra")
            true
        }
        b.videoChairGuide.setVideoURI(uri)
        b.videoChairGuide.visibility = View.VISIBLE
    }

    private fun stopChairGuideVideo() {
        val b = _binding ?: return
        if (b.videoChairGuide.isPlaying) b.videoChairGuide.stopPlayback()
        b.videoChairGuide.visibility = View.GONE
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
                // setResults 내부에서 invalidate() 호출 — 중복 호출 제거
                b.poseOverlay.setResults(result, resultBundle.inputImageHeight, resultBundle.inputImageWidth)
            }

            // 의자 준비: Phase 0=TTS재생중, 1=가만히서기감지, 2=완료
            if (chairPrepareMode) {
                // 사용자 명시 2번: bar 시뮬레이션 중일 땐 bar 유지. 그 외에만 hideExamGuides
                if (!isBarSimulating) hideExamGuides()
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
                            // 3초 가만히 섬 → 동적 임계값 설정 + 영상 안내 시퀀스 시작
                            chairPreparePhase = 2
                            val avgM = if (standingMSamples.isNotEmpty()) standingMSamples.average().toFloat() else 50f
                            viewModel.calibrateChairStand(avgM)

                            showFloatingFeedback("✓ 준비 완료", 0xFF4CAF50.toInt())
                            b.tvCount.text = ""
                            b.tvTimer.text = ""
                            // 사용자 명시 흐름: 가만히 감지 후 → 영상 안내 + bar 설명 → 3,2,1 → 시작
                            //   guidance_overlay 어두운 배경 유지 (showChairStandGuidance가 다시 VISIBLE 처리)
                            //   bar 시뮬레이션 시점부터 v_countdown_dim 켜서 dim 지속
                            showChairStandGuidance {
                                if (_binding == null || hasNavigated) return@showChairStandGuidance
                                hideFloatingFeedback()
                                startChairGuideVideo()  // 우하단 영상 시작
                                // 사용자 명시 1번: bar 설명 끝 → "30초간 최대한 많이!" 큰 텍스트 + TTS → 3,2,1
                                showThirtySecCallout {
                                    if (_binding == null || hasNavigated) return@showThirtySecCallout
                                    ttsManager?.speak("이제 시작하겠습니다") {
                                        if (_binding == null || hasNavigated) return@speak
                                        startCountdown321 {
                                            if (_binding != null && !hasNavigated && chairPrepareMode) {
                                                chairPrepareMode = false
                                                isBarSimulating = false
                                                lastSpokenSecond = -1
                                                lastHintSpokenMs = 0L
                                                viewModel.startChairStand()
                                                _binding?.guideBar?.bringToFront()
                                                startUserAwayMonitor()
                                            }
                                        }
                                    }
                                }
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

            // 일반 모드: 엔진 처리 후 가이드 표시 (순서 중요 — BalanceEngine 캐시는 processLandmarks 후 갱신됨)
            if (landmarks != null) {
                viewModel.processLandmarks(landmarks)
                // ring 설명 중에는 updateExamGuide 호출 X — getGuide가 null이라 hideExamGuides되어 깜빡임 발생
                if (!isRingExplaining) {
                    if (showGuide) updateExamGuide(landmarks) else hideExamGuides()
                }
            }
        }
    }

    // ─── 폐기: ttsManager.speak(text, onDone) callback 패턴으로 모두 마이그레이션됨 ───

    /** 3, 2, 1 → "시작!" 카운트다운. 화면 가운데 floating overlay(tv_big_countdown)에 큰 글씨로. */
    private fun startCountdown321(onReady: () -> Unit) {
        val b = _binding ?: return
        // 사용자 명시 5번: 3,2,1 동안 카메라 어둡게 (alpha 0.55 검정 overlay)
        b.vCountdownDim.visibility = View.VISIBLE
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
                    ttsManager?.speak("시작!") {
                        _binding?.tvBigCountdown?.visibility = View.GONE
                        // 사용자 명시 1번: '시작!' 끝나면 바로 dim 제거 + onReady (지연 X)
                        _binding?.vCountdownDim?.visibility = View.GONE
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
        // 영상 정지 (binding null 되기 전)
        try { _binding?.videoChairGuide?.stopPlayback() } catch (_: Exception) {}
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
