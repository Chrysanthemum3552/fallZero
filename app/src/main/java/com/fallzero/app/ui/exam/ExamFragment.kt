package com.fallzero.app.ui.exam

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
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
    private var lastSpokenSecond = -1
    private var lastHintSpokenMs = 0L
    private var lastHintText: String? = null
    // [추가] BalanceStagePassed 중복 호출 방지
    private var hasStagePassed = false
    private var examGuidancePlayer: MediaPlayer? = null

    private var chairPrepareMode = false
    private var chairPreparePhase = 0
    private var isBarSimulating = false
    private var isRingExplaining = false
    private var bodyStillSinceMs = 0L
    private var lastShoulderY = 0f
    private val standingMSamples = mutableListOf<Float>()
    private var chairDetectSpoken = false   // 의자 준비: "잘 감지됐어요" 1회 발화 여부
    private var chairSitSpoken = false       // 의자 준비: "일어나 주세요" 1회 발화 여부
    // 의자 준비→측정 전환 견고화 (사용자 승인): 재진입 세대 가드 + 무진행 워치독.
    //   준비 안내 체인(TTS·영상 콜백 다단계)에서 한 콜백이라도 누락되면 chairPrepareMode가 영원히 true로 남아
    //   프레임 처리가 스킵되어 "막대기·타이머 없이 굳는" 버그 → 단계마다 워치독을 재무장하고, 진행이 멈추면 측정을 강제 시작.
    private var chairPrepareGen = 0
    private var chairPrepareWatchdog: Runnable? = null
    private val CHAIR_PREPARE_STEP_TIMEOUT_MS = 30000L  // 한 단계 무진행 허용치(최장 단계 ~20초보다 길게)

    private enum class Mode { BALANCE, CHAIR_STAND }
    private var mode: Mode = Mode.BALANCE

    @Volatile private var isFrontCamera: Boolean = false
    private var cameraPreview: Preview? = null
    private var cameraAnalyzer: ImageAnalysis? = null

    @Volatile private var lastValidFrameMs = 0L
    @Volatile private var lastFullBodyMs = 0L
    @Volatile private var isPausedForUserAway = false
    @Volatile private var isPausedForOcclusion = false
    @Volatile private var userReturnInProgress = false
    // 안내영상/단계 준비 구간 표시. true면 away/가림 일시정지 로직을 멈춘다(사용자: 안내영상 중 일시정지 금지).
    @Volatile private var isExamGuidancePhase = false

    private var userAwayCheckJob: Job? = null
    private var pauseAnnounceJob: Job? = null

    private val USER_AWAY_TIMEOUT_MS = 2000L
    private val PAUSE_RESUME_BUFFER_MS = 1500L
    private val PAUSE_TTS_LOOP_MS = 5000L
    private val EXAM_USER_AWAY_MSG = "카메라 앞으로 와주세요"
    private val EXAM_OCCLUSION_MSG = "신체의 일부가 보이지 않습니다. 조금 더 뒤로 가주세요"

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else navigateNext() }

    // -----------------------------------------------
    // Lifecycle
    // -----------------------------------------------

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hasNavigated = false
        cameraExecutor = Executors.newSingleThreadExecutor()
        ttsManager = TTSManager.getInstance(requireContext())
        isFrontCamera = com.fallzero.app.util.CameraFacingPref.isFrontCamera(requireContext())

        binding.poseOverlay.setShowSkeleton(
            com.fallzero.app.util.DisplayPrefs.showSkeleton(requireContext())
        )
        // 전면 카메라는 PoseLandmarkerHelper가 랜드마크를 이미 좌우반전(셀피 공간)하므로 오버레이는 반전하지 않음(+1).
        // 후면은 반전해야 프리뷰와 일치(-1). 안 맞추면 스켈레톤이 몸과 반대로 움직임.
        binding.poseOverlay.scaleX = if (isFrontCamera) 1f else -1f
        showGuide = com.fallzero.app.util.DisplayPrefs.showGuide(requireContext())

        val step = SessionFlow.current()
        mode = when (step.type) {
            SessionFlow.StepType.EXAM_CHAIR_STAND -> Mode.CHAIR_STAND
            else -> Mode.BALANCE
        }

        binding.btnStartExam.visibility = View.GONE
        binding.btnBalanceFail.visibility = View.GONE
        binding.tvCount.visibility = View.VISIBLE
        binding.tvTimer.visibility = View.VISIBLE

        if (mode == Mode.CHAIR_STAND) viewModel.resetPhaseToIdle()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)

        binding.btnCameraFlip.setOnClickListener { toggleCameraFacing() }

        observeViewModel()
    }

    // -----------------------------------------------
    // Camera
    // -----------------------------------------------

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
                            poseLandmarkerHelper?.detectLiveStream(proxy, isFrontCamera = isFrontCamera)
                                ?: proxy.close()
                        }
                    }
                cameraPreview = preview; cameraAnalyzer = analyzer
                bindCameraToSelector(provider)
                autoStartExam()
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                _binding?.tvExamPhase?.text = "카메라 오류: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraToSelector(provider: ProcessCameraProvider) {
        val selector = com.fallzero.app.util.KioskCameraSelector.select(isFrontCamera)
        try {
            provider.unbindAll()
            val preview = cameraPreview ?: return
            val analyzer = cameraAnalyzer ?: return
            provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analyzer)
        } catch (e: Exception) { Log.e(TAG, "bindCameraToSelector failed", e) }
    }

    private fun toggleCameraFacing() {
        isFrontCamera = !isFrontCamera
        com.fallzero.app.util.CameraFacingPref.setFrontCamera(requireContext(), isFrontCamera)
        _binding?.poseOverlay?.scaleX = if (isFrontCamera) 1f else -1f
        cameraProvider?.let { bindCameraToSelector(it) }
    }

    private fun cleanupCamera() {
        try {
            poseLandmarkerHelper?.clearPoseLandmarker(); poseLandmarkerHelper = null
            cameraProvider?.unbindAll(); cameraProvider = null
        } catch (e: Exception) { Log.e(TAG, "cleanup", e) }
    }

    // -----------------------------------------------
    // Exam flow entry
    // -----------------------------------------------

    private fun autoStartExam() {
        when (mode) {
            Mode.BALANCE -> {
                val singleStage = SessionFlow.singleBalanceStage
                if (singleStage in 1..4) {
                    SessionFlow.singleBalanceStage = 0
                    viewModel.startSingleBalanceStage(singleStage)
                } else {
                    viewModel.startBalanceFlow()
                }
            }
            Mode.CHAIR_STAND -> {
                // 새 진입 세대 — 이전(굳었던) 진입의 떠도는 콜백·워치독·영상 플레이어를 무효화/정리
                chairPrepareGen++; cancelChairWatchdog(); releaseBarSimPlayer()
                chairPrepareMode = true; chairPreparePhase = 0; isBarSimulating = false
                bodyStillSinceMs = 0L; lastShoulderY = 0f; standingMSamples.clear()
                chairDetectSpoken = false; chairSitSpoken = false
                val b = _binding ?: return
                b.tvExamPhase.text = "의자 일어서기 검사"; b.tvTimer.text = ""; b.tvCount.text = ""
                showChairPrepareImage()
            }
        }
    }

    // -----------------------------------------------
    // Chair stand flow
    // -----------------------------------------------

    private fun showChairPrepareImage() {
        val b = _binding ?: return
        b.tvGuidanceTitle.visibility = View.VISIBLE; b.layoutGuidanceText.visibility = View.VISIBLE  // 영상에서 GONE 됐을 수 있어 복원
        b.tvGuidanceTitle.text = "의자 일어서기 검사"
        b.tvGuidanceText.text = "의자 앞에 정면을 바라보고 서주세요."
        b.tvGuidanceCountdown.visibility = View.GONE
        b.tvVideoPlaceholder.visibility = View.GONE
        b.videoChairGuidance.visibility = View.GONE
        b.ivChairFrontPose.setImageResource(R.drawable.chair_front_pose)
        b.ivChairFrontPose.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.VISIBLE
        // 가만히 서기 감지(phase 1) 진입 — 한 번만 실행 (TTS 콜백/안전장치 중복 방지)
        val enterStillness = {
            if (_binding != null && !hasNavigated && chairPreparePhase == 0) {
                _binding?.ivChairFrontPose?.visibility = View.GONE
                _binding?.guidanceOverlay?.visibility = View.GONE
                chairPreparePhase = 1
                showFloatingFeedback("의자 앞에 정면을 바라보고 가만히 서주세요", 0xFFFFEB3B.toInt())
            }
        }
        // 안내 음성이 '끝까지' 나온 뒤(잘리지 않게), 의자 가져와 앞에 서는 여유 2초를 주고 감지 시작 (사용자 요청)
        ttsManager?.speak("이번 검사는 의자가 필요합니다. 의자를 가져오셔서 의자 앞에 정면을 바라보고 서주세요.") {
            if (_binding == null || hasNavigated) return@speak
            _binding?.root?.postDelayed({ enterStillness() }, 2000L)
        }
        // 안전장치: TTS 콜백 누락 대비 (정상 시엔 콜백+2초가 먼저 호출됨)
        b.root.postDelayed({ enterStillness() }, 12000L)
    }

    private fun showChairStandGuidance(onAfterScript: () -> Unit) {
        val b = _binding ?: return
        b.ivChairFrontPose.visibility = View.GONE; b.videoChairGuidance.visibility = View.GONE
        b.tvVideoPlaceholder.text = "안내 영상"; b.tvVideoPlaceholder.textSize = 64f
        b.tvVideoPlaceholder.setTextColor(0xFFFFFF00.toInt()); b.tvVideoPlaceholder.visibility = View.VISIBLE
        b.tvGuidanceTitle.visibility = View.VISIBLE   // 인트로 제목 표시 (이전 영상에서 GONE 됐을 수 있음)
        b.tvGuidanceTitle.text = "의자 일어서기 검사"; b.tvGuidanceText.text = ""
        b.guidanceOverlay.visibility = View.VISIBLE
        ttsManager?.speak("좋아요, 시작할게요. 우선 안내 영상을 시청하겠습니다.") {
            if (_binding == null || hasNavigated) return@speak
            _binding?.tvVideoPlaceholder?.visibility = View.GONE
            playChairGuidanceVideo(onAfterScript)
        }
    }

    private fun playChairGuidanceVideo(onAfterScript: () -> Unit) {
        val b = _binding ?: return
        feedChairWatchdog()   // 안내영상 단계 진입 — 영상 동안 워치독 유지
        b.tvGuidanceTitle.text = ""; b.tvGuidanceText.text = ""
        b.tvGuidanceCountdown.visibility = View.GONE; b.tvVideoPlaceholder.visibility = View.GONE
        b.tvChairSubtitle.visibility = View.GONE
        b.videoChairGuidance.visibility = View.VISIBLE

        val lines = listOf(
            "팔 교차|팔을 가슴 앞에 교차하세요",
            "앉았다 일어서기|이제 천천히 앉았다 일어서세요"
        )

        val uri = Uri.parse("android.resource://${requireContext().packageName}/${R.raw.chair_stand_guide}")
        startExamGuidanceVideo(uri, lines) {
            _binding?.guidanceOverlay?.visibility = View.GONE
            _binding?.vCountdownDim?.visibility = View.VISIBLE
            startBarSimulation(onAfterScript)
        }
    }

    private var barSimPlayer: MediaPlayer? = null

    /** 검사 막대기 설명 — chair_stand_ex_guide 영상의 앉기(6~10초)·일어서기(18~20초) 구간을 전체화면으로 틀고,
     *  그 구간 속도에 맞춰 오른쪽 막대기를 내렸다(앉기) 올린다(일어서기). 사이 2초 대기. (사용자 요청 req2) */
    private fun startBarSimulation(onAfterScript: () -> Unit) {
        val b = _binding ?: return
        isBarSimulating = true
        feedChairWatchdog()   // 막대기 설명 단계 진입 — 시뮬레이션(~20초) 동안 워치독 유지
        val barGuide = { p: Float ->
            com.fallzero.app.ui.overlay.ExerciseGuide.Bar(
                progress = p, vertical = true,
                fillDirection = com.fallzero.app.ui.overlay.ExerciseGuide.FillDirection.UP,
                label = "예시", justReached = p >= 0.99f
            )
        }
        // 안내영상 전체화면 + 막대기를 그 위 오른쪽에 겹침
        b.tvGuidanceTitle.visibility = View.GONE
        b.tvVideoPlaceholder.visibility = View.GONE
        b.layoutGuidanceText.visibility = View.GONE
        b.videoChairGuidance.visibility = View.VISIBLE
        b.guideBarSim.visibility = View.VISIBLE; b.guideBarSim.setGuide(barGuide(1f)); b.guideBarSim.bringToFront()
        b.guidanceOverlay.visibility = View.VISIBLE

        val finish = {
            releaseBarSimPlayer()
            _binding?.guideBarSim?.visibility = View.GONE
            _binding?.videoChairGuidance?.visibility = View.GONE
            _binding?.guidanceOverlay?.visibility = View.GONE
            onAfterScript()
        }
        val uri = Uri.parse("android.resource://${requireContext().packageName}/${R.raw.chair_stand_ex_guide}")
        setupBarSimPlayer(uri) {
            if (_binding == null || hasNavigated) { finish(); return@setupBarSimPlayer }
            ttsManager?.speak("화면 오른쪽에는 여러분의 자세를 감지하여 막대기가 올라가거나 내려갑니다") {
                if (_binding == null || hasNavigated) return@speak
                ttsManager?.speak("앉으면 막대기가 내려가고") {
                    if (_binding == null || hasNavigated) return@speak
                    playBarSegment(6000, 10000, 1f, 0.1f, barGuide, preSeeked = true) {  // 앉기 구간(미리 seek됨) + 막대기 내림
                        if (_binding == null || hasNavigated) return@playBarSegment
                        _binding?.root?.postDelayed({                        // 잠시 대기 1초 (사용자 요청: 2초→1초)
                            if (_binding == null || hasNavigated) return@postDelayed
                            ttsManager?.speak("일어서면 막대기가 올라갑니다") {
                                if (_binding == null || hasNavigated) return@speak
                                playBarSegment(18000, 20000, 0.1f, 1f, barGuide) {   // 일어서기 구간 + 막대기 올림
                                    if (_binding == null || hasNavigated) return@playBarSegment
                                    ttsManager?.speak("이 막대기가 완전히 내려갔다가 완전히 올라가야 한 번으로 인정됩니다") {
                                        if (_binding == null || hasNavigated) return@speak
                                        finish()
                                    }
                                }
                            }
                        }, 1000L)
                    }
                }
            }
        }
    }

    private fun setupBarSimPlayer(uri: android.net.Uri, onReady: () -> Unit) {
        val b = _binding ?: return
        fun initPlayer(st: android.graphics.SurfaceTexture) {
            val mp = MediaPlayer(); barSimPlayer = mp
            try {
                mp.setDataSource(requireContext(), uri)
                mp.setSurface(android.view.Surface(st))
                mp.isLooping = false; mp.setVolume(0f, 0f)
                mp.setOnVideoSizeChangedListener { _, vw, vh ->
                    _binding?.videoChairGuidance?.let { applyExamVideoAspect(it, vw, vh) }
                }
                mp.setOnPreparedListener {
                    if (_binding != null && !hasNavigated) {
                        // 디코더 워밍업: 첫 구간(앉기)에서 seek 후 정지화면이 되던 문제 방지.
                        // 미리 start→pause로 디코더를 PREPARED→PAUSED로 만들어, 정상 재생되는 둘째 구간(서기)과 같은 상태에서 시작.
                        try { mp.start(); mp.pause() } catch (_: Exception) {}
                        // 앉기 구간(6초) 프레임을 미리 디코드 → 첫 재생 시 '약간의 딜레이' 최소화 (사용자 요청 확인).
                        // 인트로 음성이 나오는 동안 seek가 완료되어, 앉기 구간이 바로 재생됨.
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                                mp.seekTo(6000L, MediaPlayer.SEEK_CLOSEST)
                            else mp.seekTo(6000)
                        } catch (_: Exception) {}
                        onReady()
                    }
                }
                mp.setOnErrorListener { _, _, _ -> if (_binding != null && !hasNavigated) onReady(); true }
                mp.prepareAsync()
            } catch (e: Exception) { Log.e(TAG, "막대기 설명 영상 오류", e); onReady() }
        }
        if (b.videoChairGuidance.isAvailable) initPlayer(b.videoChairGuidance.surfaceTexture!!)
        else b.videoChairGuidance.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) { initPlayer(st) }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
    }

    /** [startMs,endMs] 구간을 재생하면서 막대기를 barFrom→barTo로 구간 길이만큼 애니메이션. 끝나면 일시정지 + onEnd.
     *  preSeeked=true: 워밍업에서 이미 startMs로 seek 해둔 구간 → seek 대기 없이 즉시 재생+애니메이션 (앞 딜레이 제거, 사용자 요청). */
    private fun playBarSegment(startMs: Int, endMs: Int, barFrom: Float, barTo: Float,
                               barGuide: (Float) -> com.fallzero.app.ui.overlay.ExerciseGuide.Bar,
                               preSeeked: Boolean = false, onEnd: () -> Unit) {
        val mp = barSimPlayer ?: run { onEnd(); return }
        val dur = (endMs - startMs).toLong()
        val startAnim = {
            try { if (!mp.isPlaying) mp.start() } catch (_: Exception) {}
            animateBarSim(barFrom, barTo, dur, barGuide) {
                try { if (mp.isPlaying) mp.pause() } catch (_: Exception) {}
                onEnd()
            }
        }
        try {
            if (preSeeked) {
                // 미리 seek 해둔 앉기 구간 — 음성이 끝나는 즉시 바로 재생되도록 추가 seek 없이 시작
                startAnim()
            } else {
                mp.setOnSeekCompleteListener {
                    mp.setOnSeekCompleteListener(null)
                    if (_binding == null || hasNavigated) { onEnd(); return@setOnSeekCompleteListener }
                    startAnim()
                }
                // 재생을 먼저 켠 뒤(Started 상태) 구간으로 seek — 에뮬레이터 'seek→start' 정지화면 방지.
                try { if (!mp.isPlaying) mp.start() } catch (_: Exception) {}
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    mp.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                else mp.seekTo(startMs)
            }
        } catch (e: Exception) { onEnd() }
    }

    private fun animateBarSim(from: Float, to: Float, durationMs: Long,
                              barGuide: (Float) -> com.fallzero.app.ui.overlay.ExerciseGuide.Bar, onEnd: () -> Unit) {
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            addUpdateListener { _binding?.guideBarSim?.setGuide(barGuide(it.animatedValue as Float)) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onEnd() }
            })
        }.start()
    }

    private fun releaseBarSimPlayer() {
        try { barSimPlayer?.release() } catch (_: Exception) {}; barSimPlayer = null
    }

    // ── 의자 준비→측정 전환 견고화 (사용자 승인) ──
    /** 워치독 재무장 — 현재 단계가 STEP_TIMEOUT 동안 다음 콜백으로 진행하지 못하면 측정을 강제 시작.
     *  각 단계 경계에서 호출 → 정상 진행 시 매번 갱신되어 발화하지 않고, 콜백 누락/정지 시에만 발동. */
    private fun feedChairWatchdog() {
        val root = _binding?.root ?: return
        chairPrepareWatchdog?.let { root.removeCallbacks(it) }
        val gen = chairPrepareGen
        val r = Runnable { enterChairMeasurement(gen) }
        chairPrepareWatchdog = r
        root.postDelayed(r, CHAIR_PREPARE_STEP_TIMEOUT_MS)
    }

    private fun cancelChairWatchdog() {
        chairPrepareWatchdog?.let { _binding?.root?.removeCallbacks(it) }
        chairPrepareWatchdog = null
    }

    /** 의자 준비 → 측정 전환 (멱등 + 세대 가드). 정상 체인 끝과 워치독이 둘 다 호출해도 한 번만 실행.
     *  세대 불일치(재진입) 또는 이미 측정 시작(chairPrepareMode=false)이면 무시 — 떠도는 콜백 무해화. */
    private fun enterChairMeasurement(gen: Int) {
        cancelChairWatchdog()
        if (_binding == null || hasNavigated) return
        if (!chairPrepareMode || gen != chairPrepareGen) return
        chairPrepareMode = false; isBarSimulating = false
        // 워치독 강제 진입(안내 도중 콜백 누락) 시 남아있을 수 있는 준비 UI/플레이어 정리.
        //   정상 체인 경로에선 이미 정리돼 있어 모두 no-op이라 안전.
        releaseBarSimPlayer()
        _binding?.guideBarSim?.visibility = View.GONE
        _binding?.videoChairGuidance?.visibility = View.GONE
        _binding?.guidanceOverlay?.visibility = View.GONE
        _binding?.vCountdownDim?.visibility = View.GONE
        hideFloatingFeedback()
        lastSpokenSecond = -1; lastHintSpokenMs = 0L
        viewModel.startChairStand()
        _binding?.guideBar?.bringToFront()
        startUserAwayMonitor()
    }

    private fun showThirtySecCallout(onAfter: () -> Unit) {
        val b = _binding ?: return
        b.guideBar.visibility = View.GONE; b.tvGuidanceTitle.text = ""; b.tvGuidanceText.text = ""
        b.videoChairGuidance.visibility = View.GONE; b.ivChairFrontPose.visibility = View.GONE
        b.tvVideoPlaceholder.text = "30초간\n최대한 많이!"; b.tvVideoPlaceholder.textSize = 56f
        b.tvVideoPlaceholder.setTextColor(0xFFFFFF00.toInt()); b.tvVideoPlaceholder.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.VISIBLE
        ttsManager?.speak("이 자세를 30초간 최대한 많이 반복하시면 됩니다") {
            if (_binding == null || hasNavigated) return@speak
            _binding?.tvVideoPlaceholder?.visibility = View.GONE
            _binding?.guidanceOverlay?.visibility = View.GONE
            onAfter()
        }
    }

    private fun startChairGuideVideo() {
        // 오른쪽 아래 무한반복 미니 영상 제거 (사용자 요청) — 표시하지 않음.
        _binding?.videoChairGuide?.visibility = View.GONE
    }

    private fun stopChairGuideVideo() {
        val b = _binding ?: return
        if (b.videoChairGuide.isPlaying) b.videoChairGuide.stopPlayback()
        b.videoChairGuide.visibility = View.GONE
    }

    // -----------------------------------------------
    // Balance flow
    // -----------------------------------------------

    private fun showBalanceGuidance(stage: Int, stageName: String, onAfter: () -> Unit) {
        val b = _binding ?: return
        b.tvGuidanceTitle.visibility = View.VISIBLE   // 인트로에서 단계 제목 표시 (이전 영상에서 GONE 됐을 수 있음)
        b.tvGuidanceTitle.text = "${stage}단계 · $stageName 균형 검사"; b.tvGuidanceText.text = ""
        b.tvGuidanceCountdown.visibility = View.GONE; b.videoChairGuidance.visibility = View.GONE
        b.ivChairFrontPose.visibility = View.GONE; b.tvChairSubtitle.visibility = View.GONE
        b.tvVideoPlaceholder.text = "안내 영상"; b.tvVideoPlaceholder.textSize = 64f
        b.tvVideoPlaceholder.setTextColor(0xFFFFFF00.toInt()); b.tvVideoPlaceholder.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.VISIBLE
        ttsManager?.speak("${stage}단계, $stageName 균형 검사입니다. 우선 안내 영상을 시청하겠습니다.") {
            if (_binding == null || hasNavigated) return@speak
            _binding?.tvVideoPlaceholder?.visibility = View.GONE
            playBalanceGuidanceVideo(stage, stageName, onAfter)
        }
    }

    // stage별 안내 텍스트 줄 목록 (각 줄 = 해당 영상 구간 + TTS)
    private fun getBalanceGuidanceLines(stage: Int): List<String> = when (stage) {
        1 -> listOf(
            "발 모아 팔 교차|뒷꿈치를 모아주시고 팔을 가슴 앞에 교차하세요",
            "자세 유지|자세를 유지해주세요"
        )
        2 -> listOf(
            "팔 교차|두 팔은 가슴에 교차해주세요",
            "발 반일렬|한쪽 발 뒤꿈치를 다른 발 엄지발가락 옆에 놓으세요",
            "자세 유지|자세를 유지해주세요",
            "발 모양 참고|다음 화면은 위에서 봤을 때 발 모양입니다. 참고하세요"
        )
        3 -> listOf(
            "팔 교차|두 팔은 가슴에 교차해주세요",
            "발 일렬|한쪽 발 뒤꿈치를 다른 발 발끝 바로 앞에 일렬로 놓으세요",
            "자세 유지|자세를 유지해주세요",
            "발 모양 참고|다음 화면은 위에서 봤을 때 발 모양입니다. 참고하세요"
        )
        4 -> listOf(
            "팔 교차|두 팔은 가슴에 교차해주세요",
            "한 발 서기|한쪽 발을 들어 올려 한 발로만 서주세요",
            "자세 유지|자세를 유지해주세요"
        )
        else -> listOf("자세 유지|자세를 유지해주세요")
    }

    // [수정] seekTo 기반 동기화 - TTS 발화 중 해당 구간 영상 재생
    private fun playBalanceGuidanceVideo(stage: Int, stageName: String, onAfter: () -> Unit) {
        val b = _binding ?: return
        b.videoChairGuidance.visibility = View.VISIBLE
        b.tvChairSubtitle.visibility = View.GONE; b.tvGuidanceTitle.text = ""; b.tvGuidanceText.text = ""

        val videoRes = when (stage) {
            1 -> R.raw.balance_guide_1; 2 -> R.raw.balance_guide_2
            3 -> R.raw.balance_guide_3; 4 -> R.raw.balance_guide_4
            else -> R.raw.balance_guide_1
        }
        val uri = Uri.parse("android.resource://${requireContext().packageName}/${videoRes}")
        val lines = getBalanceGuidanceLines(stage)

        startExamGuidanceVideo(uri, lines) {
            _binding?.footGuideOverlay?.visibility = View.GONE
            _binding?.examGuideTextOverlay?.visibility = View.GONE
            _binding?.guidanceOverlay?.visibility = View.GONE
            _binding?.vCountdownDim?.visibility = View.VISIBLE
            if (stage == 1) startRingExplanation(stage, stageName, onAfter)
            else showBalanceHoldCallout(stage, stageName, onAfter)
        }
    }

    // 검사 안내 자막 폴링 시스템
    private var examSubtitleEntries: List<Triple<Long, Long, String>> = emptyList()
    private var lastExamSubtitleIndex = -1
    private val examSubtitleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val examSubtitleRunnable = object : Runnable {
        override fun run() {
            val player = examGuidancePlayer ?: return
            if (!player.isPlaying) { examSubtitleHandler.postDelayed(this, 100L); return }
            val pos = player.currentPosition.toLong()
            val idx = examSubtitleEntries.indexOfFirst { pos >= it.first && pos < it.second }
            if (idx >= 0) {
                // "짧은자막|전체 나레이션" 형식 — 화면엔 짧은 자막, 음성은 전체 문장(없으면 동일)
                val raw = examSubtitleEntries[idx].third
                val subtitle = raw.substringBefore("|")
                val speak = if (raw.contains("|")) raw.substringAfter("|") else raw
                _binding?.tvExamGuideLineText?.text = subtitle
                _binding?.examGuideTextOverlay?.visibility = View.VISIBLE
                if (idx != lastExamSubtitleIndex) {
                    lastExamSubtitleIndex = idx
                    ttsManager?.speak(speak.replace("\n", " "))
                }
            } else {
                _binding?.examGuideTextOverlay?.visibility = View.GONE
            }
            if (examGuidancePlayer != null) examSubtitleHandler.postDelayed(this, 80L)
        }
    }

    private fun buildExamSubtitleTimings(totalMs: Long, lines: List<String>): List<Triple<Long, Long, String>> {
        val subtitleMs = 4000L
        val actionMs = (totalMs - lines.size * subtitleMs) / lines.size
        return lines.mapIndexed { i, text ->
            val start = i.toLong() * (subtitleMs + actionMs)
            Triple(start, start + subtitleMs, text)
        }
    }

    /** 안내 영상 원본 종횡비를 TextureView 안에 맞춰 letterbox 변환 (찌그러짐 방지). */
    private fun applyExamVideoAspect(tv: android.view.TextureView, videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        val viewW = tv.width.toFloat(); val viewH = tv.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val viewAspect = viewW / viewH
        val sx: Float; val sy: Float
        // center-crop: 비율을 유지한 채 뷰를 꽉 채우고(여백 0) 넘치는 가장자리는 잘라낸다.
        if (videoAspect > viewAspect) { sx = videoAspect / viewAspect; sy = 1f }  // 높이 채우고 좌우 크롭
        else { sx = 1f; sy = viewAspect / videoAspect }                            // 너비 채우고 상하 크롭
        val m = android.graphics.Matrix()
        m.setScale(sx, sy, viewW / 2f, viewH / 2f)
        tv.setTransform(m)
        tv.invalidate()
    }

    private fun startExamGuidanceVideo(uri: android.net.Uri, lines: List<String>, onComplete: () -> Unit) {
        val b = _binding ?: return
        b.videoChairGuidance.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.VISIBLE
        // 안내영상 중에는 상/하단 검은 패널 숨김 (자막은 중앙 examGuideTextOverlay에 나오므로 불필요) — req10
        b.tvGuidanceTitle.visibility = View.GONE
        b.layoutGuidanceText.visibility = View.GONE

        fun initPlayer(st: android.graphics.SurfaceTexture) {
            val mp = MediaPlayer()
            examGuidancePlayer = mp
            try {
                mp.setDataSource(requireContext(), uri)
                mp.setSurface(android.view.Surface(st))
                mp.isLooping = false; mp.setVolume(0f, 0f)
                // 영상 원본 종횡비 보존 — 키오스크 세로 화면에서 찌그러지지 않도록 letterbox 변환
                mp.setOnVideoSizeChangedListener { _, vw, vh ->
                    _binding?.videoChairGuidance?.let { applyExamVideoAspect(it, vw, vh) }
                }
                mp.setOnPreparedListener { player ->
                    val totalMs = if (player.duration > 0) player.duration.toLong() else 8000L
                    examSubtitleEntries = buildExamSubtitleTimings(totalMs, lines)
                    lastExamSubtitleIndex = -1
                    player.start()
                    examSubtitleHandler.post(examSubtitleRunnable)
                }
                mp.setOnCompletionListener {
                    examSubtitleHandler.removeCallbacks(examSubtitleRunnable)
                    _binding?.examGuideTextOverlay?.visibility = View.GONE
                    releaseExamGuidancePlayer()
                    if (_binding != null && !hasNavigated) onComplete()
                }
                mp.setOnErrorListener { _, _, _ ->
                    examSubtitleHandler.removeCallbacks(examSubtitleRunnable)
                    releaseExamGuidancePlayer()
                    if (_binding != null && !hasNavigated) onComplete()
                    true
                }
                mp.prepareAsync()
            } catch (e: Exception) { Log.e(TAG, "검사 안내 영상 오류", e); onComplete() }
        }

        if (b.videoChairGuidance.isAvailable) {
            initPlayer(b.videoChairGuidance.surfaceTexture!!)
        } else {
            b.videoChairGuidance.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) { initPlayer(st) }
                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
            }
        }
    }

    private fun releaseExamGuidancePlayer() {
        examSubtitleHandler.removeCallbacks(examSubtitleRunnable)
        examGuidancePlayer?.release()
        examGuidancePlayer = null
    }
    // [추가] 발 이미지 전환 오버레이 (반일렬/일렬서기 전용)
    private fun showFootGuideOverlay() {
        val b = _binding ?: return
        b.footGuideOverlay.visibility = View.VISIBLE
        b.footGuideOverlay.bringToFront()
        b.root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            _binding?.footGuideOverlay?.visibility = View.GONE
        }, 2500L)
    }

    private fun startRingExplanation(stage: Int, stageName: String, onAfter: () -> Unit) {
        val b = _binding ?: return
        isRingExplaining = true
        b.guideBubble.visibility = View.VISIBLE; b.guideBubble.bringToFront()
        val mkBubble = { hold: Float ->
            com.fallzero.app.ui.overlay.ExerciseGuide.Bubble(
                swayRatio = 0f, holdProgress = hold, label = "",
                elapsedSec = hold * 10f, targetSec = 10f, poseValid = true
            )
        }
        b.guideBubble.setGuide(mkBubble(0f)); b.guideBubble.invalidate()
        ttsManager?.speak("자세를 잘 잡으면 원이 시계방향으로 부드럽게 채워집니다") {
            if (_binding == null || hasNavigated) return@speak
            animateRing(0f, 1f, 0f, 3000L, { _, h -> mkBubble(h) }) {
                if (_binding == null || hasNavigated) return@animateRing
                ttsManager?.speak("자세가 틀리면 0초부터 다시 시작합니다") {
                    if (_binding == null || hasNavigated) return@speak
                    _binding?.guideBubble?.setGuide(mkBubble(0f)); _binding?.guideBubble?.invalidate()
                    _binding?.root?.postDelayed({
                        if (_binding == null || hasNavigated) return@postDelayed
                        isRingExplaining = false; _binding?.guideBubble?.visibility = View.GONE
                        showBalanceHoldCallout(stage, stageName, onAfter)
                    }, 1000L)
                }
            }
        }
    }

    private fun animateRing(from: Float, to: Float, sway: Float, durationMs: Long,
                            mkBubble: (Float, Float) -> com.fallzero.app.ui.overlay.ExerciseGuide.Bubble, onEnd: () -> Unit) {
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            addUpdateListener { _binding?.guideBubble?.setGuide(mkBubble(sway, it.animatedValue as Float)) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { onEnd() }
            })
        }.start()
    }

    private fun startRingSimulation(stage: Int, stageName: String, onAfter: () -> Unit) {
        val b = _binding ?: return
        isRingExplaining = true
        b.guideBubble.visibility = View.VISIBLE; b.guideBubble.bringToFront()
        b.guideBubble.setGuide(com.fallzero.app.ui.overlay.ExerciseGuide.Bubble(0f, 0f, stageName, 0f, 10f, true))
        b.guideBubble.invalidate()
        ttsManager?.speak("제대로 된 자세를 하면 원의 테두리가 초록색이 되며 시계방향으로 채워집니다")
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5000L
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                _binding?.guideBubble?.setGuide(
                    com.fallzero.app.ui.overlay.ExerciseGuide.Bubble(0f, p, stageName, p * 10f, 10f, true)
                )
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (_binding == null || hasNavigated) return
                    isRingExplaining = false; _binding?.guideBubble?.visibility = View.GONE
                    showBalanceHoldCallout(stage, stageName, onAfter)
                }
            })
        }.start()
    }

    private fun showBalanceHoldCallout(stage: Int, stageName: String, onAfter: () -> Unit) {
        val b = _binding ?: return
        b.tvGuidanceTitle.text = "${stage}단계 · $stageName"; b.tvGuidanceText.text = ""
        b.videoChairGuidance.visibility = View.GONE; b.ivChairFrontPose.visibility = View.GONE
        b.tvChairSubtitle.visibility = View.GONE
        b.tvVideoPlaceholder.text = "10초간\n유지!"; b.tvVideoPlaceholder.textSize = 64f
        b.tvVideoPlaceholder.setTextColor(0xFFFFFF00.toInt()); b.tvVideoPlaceholder.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.VISIBLE
        ttsManager?.speak("$stageName 자세를 10초간 유지해주세요") {
            if (_binding == null || hasNavigated) return@speak
            _binding?.tvVideoPlaceholder?.visibility = View.GONE
            _binding?.guidanceOverlay?.visibility = View.GONE
            _binding?.vCountdownDim?.visibility = View.VISIBLE
            onAfter()
        }
    }

    // -----------------------------------------------
    // ViewModel observation
    // -----------------------------------------------

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.phase.collect { phase ->
                    val b = _binding ?: return@collect
                    when (phase) {
                        is ExamViewModel.ExamPhase.ChairStand -> {
                            b.tvExamPhase.text = stripParens(getString(R.string.exam_phase_chair))
                            b.tvExamPhase.setTextColor(0xFFFFFFFF.toInt())
                            b.tvTimer.text = ""; b.tvCount.text = ""
                            if (phase.isRunning) {
                                b.layoutCornerCount.visibility = View.VISIBLE
                                b.tvCornerCount.visibility = View.VISIBLE
                                b.tvCornerTimer.text = "${phase.remainingSec}"
                                b.progressCornerTimer.setProgressCompat(phase.remainingSec, true)
                                b.tvCornerCount.text = "${phase.count}회"
                                // 지적(errorHint)할 때만 표시, 정상이면 숨김 — "잘 하고 있어요!" 제거 (사용자 요청)
                                if (phase.errorHint != null) showFloatingFeedback(phase.errorHint, 0xFFFFEB3B.toInt())
                                else hideFloatingFeedback()
                            }
                            if (phase.count > 0 && phase.count != lastSpokenSecond) {
                                lastSpokenSecond = phase.count
                                if (ttsManager?.isSpeaking() != true) ttsManager?.speak("${phase.count}")
                            }
                            if (phase.errorHint != null) {
                                val now = System.currentTimeMillis()
                                if (now - lastHintSpokenMs > 4000L) {
                                    lastHintSpokenMs = now; ttsManager?.speak(phase.errorHint)
                                }
                            }
                        }

                        is ExamViewModel.ExamPhase.ChairStandComplete -> {
                            stopChairGuideVideo(); b.layoutCornerCount.visibility = View.GONE
                            b.tvCornerCount.visibility = View.GONE; hideFloatingFeedback()
                            ttsManager?.speak("의자 일어서기 검사가 끝났어요.") {
                                if (_binding != null && !hasNavigated) navigateNext()
                            }
                            // 안전장치: TTS 콜백 실패 시 3초 후 강제 이동
                            b.root.postDelayed({
                                if (_binding != null && !hasNavigated) navigateNext()
                            }, 3000L)
                        }

                        is ExamViewModel.ExamPhase.BalancePrepare -> {
                            // 단계 전환 — 이전 단계 away-monitor 정지 + 일시정지 해제(안내영상 중 가림 일시정지 방지)
                            isExamGuidancePhase = true
                            userAwayCheckJob?.cancel()
                            isPausedForUserAway = false; isPausedForOcclusion = false
                            b.pauseOverlay.visibility = View.GONE
                            b.layoutCornerCount.visibility = View.GONE
                            b.tvCornerCount.visibility = View.GONE
                            hideFloatingFeedback(); stopChairGuideVideo()
                            b.tvExamPhase.text = "${phase.stage}단계: ${stripParens(phase.stageName)}"
                            b.tvTimer.text = ""; b.tvCount.text = ""
                            lastSpokenSecond = -1; lastHintText = null
                            // [추가] 단계 시작마다 hasStagePassed 초기화
                            hasStagePassed = false
                            showBalanceGuidance(phase.stage, phase.stageName) {
                                if (_binding == null || hasNavigated) return@showBalanceGuidance
                                val startAfterCheck = {
                                    startCountdown321 {
                                        if (_binding != null && !hasNavigated) {
                                            lastHintSpokenMs = 0L; lastSpokenSecond = -1
                                            isExamGuidancePhase = false   // 측정 시작 → 모니터 재가동 허용
                                            viewModel.startBalanceMeasurementNow()
                                            startUserAwayMonitor()
                                        }
                                    }
                                }
                                // 전신 확인은 1단계 시작 전 1번만 (사용자 요청) — 2~4단계는 바로 3,2,1
                                if (phase.stage == 1) awaitPersonInFrontExam { startAfterCheck() }
                                else startAfterCheck()
                            }
                        }

                        is ExamViewModel.ExamPhase.Balance -> {
                            b.tvExamPhase.text = "${phase.stage}단계: ${stripParens(viewModel.getBalanceStageName(phase.stage))}"
                            b.tvTimer.text = ""; b.tvTimerLabel.text = ""
                            if (phase.isStable) {
                                hideFloatingFeedback()  // 정상이면 자막 숨김 — "잘 하고 있어요!" 제거 (지적할 때만 표시, 사용자 요청)
                                b.tvCount.text = ""
                                val elapsed = phase.elapsedSec.toInt()
                                if (elapsed > 0 && elapsed != lastSpokenSecond) {
                                    lastSpokenSecond = elapsed
                                    if (ttsManager?.isSpeaking() != true) {
                                        ttsManager?.speak(when (elapsed) {
                                            1 -> "일"; 2 -> "이"; 3 -> "삼"; 4 -> "사"; 5 -> "오"
                                            6 -> "육"; 7 -> "칠"; 8 -> "팔"; 9 -> "구"; 10 -> "십"
                                            else -> elapsed.toString()
                                        })
                                    }
                                }
                            } else {
                                val hint = phase.errorHint
                                showFloatingFeedback(hint ?: "자세를 유지하세요", 0xFFFFEB3B.toInt())
                                b.tvCount.text = ""; lastSpokenSecond = -1
                                if (hint != null && hint != "균형을 잡아주세요") {
                                    val now = System.currentTimeMillis()
                                    if (now - lastHintSpokenMs > 4000L) {
                                        lastHintSpokenMs = now; lastHintText = hint; ttsManager?.speak(hint)
                                    }
                                }
                            }
                        }

                        is ExamViewModel.ExamPhase.BalanceStagePassed -> {
                            b.tvTimer.text = ""; b.tvCount.text = ""
                            showFloatingFeedback("통과!", 0xFF4CAF50.toInt()); lastSpokenSecond = -1
                            // [수정] hasStagePassed 로 중복 호출 방지 + postDelayed 안전장치
                            ttsManager?.speakSequence("십!", "좋아요!") {
                                if (_binding != null && !hasNavigated && !hasStagePassed) {
                                    hasStagePassed = true
                                    viewModel.advanceFromStagePassed()
                                }
                            }
                            b.root.postDelayed({
                                if (_binding != null && !hasNavigated && !hasStagePassed) {
                                    hasStagePassed = true
                                    viewModel.advanceFromStagePassed()
                                }
                            }, 2500L)
                        }

                        is ExamViewModel.ExamPhase.BalanceComplete -> {
                            b.tvExamPhase.text = ""; b.tvTimer.text = ""
                            b.tvCount.text = "끝!"; b.tvCount.setTextColor(0xFF4CAF50.toInt())
                            b.poseOverlay.clear()
                            userAwayCheckJob?.cancel(); userAwayCheckJob = null
                            pauseAnnounceJob?.cancel(); pauseAnnounceJob = null
                            ttsManager?.speak("균형 검사가 끝났습니다! 수고하셨어요.") {
                                if (_binding != null && !hasNavigated) navigateNext()
                            }
                            b.root.postDelayed({
                                if (_binding != null && !hasNavigated) navigateNext()
                            }, 3500L)
                        }

                        is ExamViewModel.ExamPhase.Completed,
                        ExamViewModel.ExamPhase.Idle -> { /* no-op */ }
                    }
                }
            }
        }
    }

    // -----------------------------------------------
    // Guide overlay helpers
    // -----------------------------------------------

    private fun updateExamGuide(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>) {
        val b = _binding ?: return
        when (val guide = viewModel.getGuide(landmarks)) {
            is com.fallzero.app.ui.overlay.ExerciseGuide.Bar -> {
                // 검사 의자 일어서기: 엔진이 주는 세로 막대(오른쪽, 앉으면↓·서면↑) 그대로 — 사용자 요청으로 가로 변환 취소
                b.guideBar.visibility = View.VISIBLE; b.guideBubble.visibility = View.GONE; b.guideBar.setGuide(guide)
            }
            is com.fallzero.app.ui.overlay.ExerciseGuide.Bubble -> {
                b.guideBar.visibility = View.GONE; b.guideBubble.visibility = View.VISIBLE; b.guideBubble.setGuide(guide)
            }
            null -> hideExamGuides()
        }
    }

    private fun hideExamGuides() { _binding?.guideBar?.visibility = View.GONE; _binding?.guideBubble?.visibility = View.GONE }

    /** 상단 표시용 — 이름의 괄호 보조설명 제거 (공간 절약, 위 가림 방지, 사용자 요청). 원본 문자열은 안내·결과에서 그대로 사용. */
    private fun stripParens(s: String): String = s.replace(Regex("\\s*\\([^)]*\\)"), "").trim()

    private fun showFloatingFeedback(msg: String, colorArgb: Int) {
        val b = _binding ?: return
        b.tvFloatingFeedback.text = msg; b.tvFloatingFeedback.setTextColor(colorArgb); b.tvFloatingFeedback.visibility = View.VISIBLE
    }

    private fun hideFloatingFeedback() { _binding?.tvFloatingFeedback?.visibility = View.GONE }

    private fun adjustVideoToAspectRatio(videoW: Int, videoH: Int) {
        val view = _binding?.videoChairGuidance ?: return
        if (videoW <= 0 || videoH <= 0) return
        view.post {
            val v = _binding?.videoChairGuidance ?: return@post
            val parent = v.parent as? android.view.View ?: return@post
            val pw = parent.width
            val ph = ((_binding?.layoutGuidanceText?.top ?: parent.height) - (_binding?.tvGuidanceTitle?.bottom ?: 0) - 24).coerceAtLeast(0)
            if (pw <= 0 || ph <= 0) return@post
            val vr = videoW.toFloat() / videoH; val pr = pw.toFloat() / ph
            val params = v.layoutParams
            if (vr > pr) { params.width = pw; params.height = (pw / vr).toInt() }
            else { params.height = ph; params.width = (ph * vr).toInt() }
            v.layoutParams = params
        }
    }

    // -----------------------------------------------
    // Countdown 3-2-1
    // -----------------------------------------------

    private fun startCountdown321(onReady: () -> Unit) {
        val b = _binding ?: return
        b.vCountdownDim.visibility = View.VISIBLE; b.tvBigCountdown.visibility = View.VISIBLE
        b.tvBigCountdown.setTextColor(0xFFFFFF00.toInt()); b.tvBigCountdown.text = "3"
        ttsManager?.speak("삼")
        b.root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            _binding?.tvBigCountdown?.text = "2"; ttsManager?.speak("이")
            _binding?.root?.postDelayed({
                if (_binding == null || hasNavigated) return@postDelayed
                _binding?.tvBigCountdown?.text = "1"; ttsManager?.speak("일")
                _binding?.root?.postDelayed({
                    if (_binding == null || hasNavigated) return@postDelayed
                    _binding?.tvBigCountdown?.text = "시작!"; _binding?.tvBigCountdown?.setTextColor(0xFF4CAF50.toInt())
                    ttsManager?.speak("시작!") {
                        _binding?.tvBigCountdown?.visibility = View.GONE
                        _binding?.vCountdownDim?.visibility = View.GONE
                        if (_binding != null && !hasNavigated) onReady()
                    }
                }, 1000L)
            }, 1000L)
        }, 1000L)
    }

    /**
     * 균형 검사: 설명이 끝난 뒤, 카메라 앞에 전신이 제대로 잡힐 때까지 대기(건너뛰지 않음).
     * 전신이 0.5초 연속 잡히면 통과 → 호출부에서 3,2,1 카운트다운. (측정은 카운트다운 이후)
     * 60초 안전장치(정상적으로는 사람이 서면 1~2초 내 통과).
     */
    private fun awaitPersonInFrontExam(onReady: () -> Unit) {
        val b = _binding ?: return
        // 전신 확인 단계: 사용자가 카메라 프리뷰로 자기 모습을 직접 보며 전신이 다 나오는지 확인해야 한다.
        // 따라서 화면을 가리는 일시정지 오버레이를 쓰지 않고(=화면 이탈 일시정지 로직과 다름) 상단 안내 문구만 띄운다. (사용자 요청)
        // 막대기 설명 직후 켜진 dim(vCountdownDim)을 끄고 상단 단계 제목 띠도 숨겨 카메라를 밝고 크게 보여준다.
        //   전신이 잘 보이는지 사용자가 직접 확인하려면 화면이 어두우면 안 됨 (사용자 요청). dim은 이후 3·2·1 카운트다운에서 다시 켜진다.
        b.vCountdownDim.visibility = View.GONE
        b.layoutTopBar.visibility = View.GONE
        showFloatingFeedback("카메라 앞에 전신이 잘 보이게 서주세요", 0xFFFFEB3B.toInt())
        viewLifecycleOwner.lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            var detectedSinceMs = 0L
            var confirmed = false
            while (isAdded && !hasNavigated) {
                delay(120)
                val now = System.currentTimeMillis()
                val fullBodyFresh = lastFullBodyMs > 0L && now - lastFullBodyMs < 400L
                if (fullBodyFresh) {
                    if (detectedSinceMs == 0L) {
                        detectedSinceMs = now
                        showFloatingFeedback("잘 보입니다. 잠시만요…", 0xFF4CAF50.toInt())
                    }
                    // 전신이 2초간 연속으로 잘 보이면 확인 완료 (사용자 요청)
                    if (now - detectedSinceMs >= 2000L) { confirmed = true; break }
                } else {
                    detectedSinceMs = 0L
                    showFloatingFeedback("카메라 앞에 전신이 잘 보이게 서주세요", 0xFFFFEB3B.toInt())
                }
                if (now - startMs > 60_000L) break
            }
            if (!isAdded || hasNavigated) return@launch
            hideFloatingFeedback()
            _binding?.layoutTopBar?.visibility = View.VISIBLE   // 전신 확인 종료 → 상단 단계 제목 띠 복원 (카운트다운/측정 화면용)
            if (!confirmed) { onReady(); return@launch }
            // 전신 확인됨 → "좋아요 전신이 잘 보입니다" 한 뒤 '바로' 다음으로.
            // 폴링 대기 없이 TTS 완료 콜백에서 즉시 진행 — 불필요한 delay로 "왜 안 돼?" 느낌 주지 않게 (사용자 요청).
            var proceeded = false
            val proceed = {
                if (!proceeded) { proceeded = true; if (isAdded && !hasNavigated) onReady() }
            }
            ttsManager?.speak("좋아요. 전신이 잘 보입니다.") { activity?.runOnUiThread { proceed() } }
            // 안전장치: TTS 콜백이 끝내 오지 않을 때만 진행 (정상 시엔 콜백이 먼저라 지연 없음)
            _binding?.root?.postDelayed({ proceed() }, 3500L)
        }
    }

    // -----------------------------------------------
    // User away / occlusion monitor
    // -----------------------------------------------

    private fun startUserAwayMonitor() {
        userAwayCheckJob?.cancel()
        val nowInit = System.currentTimeMillis()
        lastValidFrameMs = nowInit; lastFullBodyMs = nowInit
        userAwayCheckJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isAdded && !hasNavigated) {
                delay(500)
                // 안내영상/단계 준비/막대기 설명 중에는 away·가림 일시정지 발동 안 함 (사용자 요청)
                if (isPausedForUserAway || isPausedForOcclusion ||
                    isExamGuidancePhase || chairPrepareMode || isBarSimulating) continue
                val now = System.currentTimeMillis()
                when {
                    lastValidFrameMs > 0L && now - lastValidFrameMs > USER_AWAY_TIMEOUT_MS -> onUserAway()
                    lastFullBodyMs > 0L && now - lastFullBodyMs > USER_AWAY_TIMEOUT_MS -> onPartialOcclusion()
                }
            }
        }
    }

    private fun onUserAway() {
        if (isPausedForUserAway || isPausedForOcclusion) return
        Log.d(TAG, "onUserAway"); isPausedForUserAway = true; viewModel.pauseForUserAway()
        showExamPauseOverlay(EXAM_USER_AWAY_MSG)
    }

    private fun onPartialOcclusion() {
        if (isPausedForUserAway || isPausedForOcclusion) return
        Log.d(TAG, "onPartialOcclusion"); isPausedForOcclusion = true; viewModel.pauseForUserAway()
        showExamPauseOverlay(EXAM_OCCLUSION_MSG)
    }

    private fun showExamPauseOverlay(msg: String) {
        val b = _binding ?: return
        b.pauseOverlay.visibility = View.VISIBLE; b.tvPauseMessage.text = msg; ttsManager?.speak(msg)
        pauseAnnounceJob?.cancel()
        pauseAnnounceJob = viewLifecycleOwner.lifecycleScope.launch {
            while ((isPausedForUserAway || isPausedForOcclusion) && isAdded && !hasNavigated) {
                delay(PAUSE_TTS_LOOP_MS)
                if (isPausedForUserAway || isPausedForOcclusion) ttsManager?.speak(msg)
            }
        }
    }

    private fun onOcclusionCleared() {
        if (!isPausedForOcclusion || userReturnInProgress) return
        Log.d(TAG, "onOcclusionCleared"); userReturnInProgress = true; pauseAnnounceJob?.cancel()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PAUSE_RESUME_BUFFER_MS)
            if (!isAdded || hasNavigated) { userReturnInProgress = false; return@launch }
            _binding?.pauseOverlay?.visibility = View.GONE
            ttsManager?.speak("전신이 잘 보입니다. 자세를 다시 잡아주세요")
            resumeWithCountdown(isOcclusion = true)
        }
    }

    private fun onUserReturned() {
        if (!isPausedForUserAway || userReturnInProgress) return
        Log.d(TAG, "onUserReturned"); userReturnInProgress = true; pauseAnnounceJob?.cancel()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PAUSE_RESUME_BUFFER_MS)
            if (!isAdded || hasNavigated) { userReturnInProgress = false; return@launch }
            _binding?.pauseOverlay?.visibility = View.GONE
            ttsManager?.speak("다시 자세를 잡아주세요")
            resumeWithCountdown(isOcclusion = false)
        }
    }

    private fun resumeWithCountdown(isOcclusion: Boolean) {
        startCountdown321 {
            if (_binding == null || hasNavigated) { userReturnInProgress = false; return@startCountdown321 }
            Log.d(TAG, "resume countdown 종료")
            if (isOcclusion) isPausedForOcclusion = false else isPausedForUserAway = false
            viewModel.resumeFromUserAway()
            val now = System.currentTimeMillis(); lastValidFrameMs = now; lastFullBodyMs = now
            userReturnInProgress = false
        }
    }

    // -----------------------------------------------
    // Navigation
    // -----------------------------------------------

    private fun navigateNext() {
        if (hasNavigated) return
        hasNavigated = true; cleanupCamera()
        val next = SessionFlow.advance()
        if (next.type == SessionFlow.StepType.DONE) {
            viewModel.finalizeAndSave()
            val b = _binding ?: run { findNavController().navigate(R.id.action_global_exam_result); return }
            b.tvExamPhase.text = ""; b.tvTimer.text = ""
            b.tvCount.text = "끝!"; b.tvCount.setTextColor(0xFF4CAF50.toInt()); b.poseOverlay.clear()
            ttsManager?.speak("모든 검사가 끝났습니다.") {
                if (_binding != null) findNavController().navigate(R.id.action_global_exam_result)
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
            SessionFlow.StepType.DONE -> { viewModel.finalizeAndSave(); nav.navigate(R.id.action_global_exam_result) }
            SessionFlow.StepType.PRE_FLIGHT -> nav.navigate(R.id.action_global_preflight)
        }
    }

    // -----------------------------------------------
    // PoseLandmarkerHelper.LandmarkerListener
    // -----------------------------------------------

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        val result = resultBundle.results.firstOrNull() ?: return
        val landmarks = result.landmarks().firstOrNull()

        if (landmarks != null) {
            val now = System.currentTimeMillis(); lastValidFrameMs = now
            val fullBody = isFullBodyVisible(landmarks); if (fullBody) lastFullBodyMs = now
            if (isPausedForUserAway) onUserReturned()
            else if (isPausedForOcclusion && fullBody) onOcclusionCleared()
        }

        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            if (!isAdded || hasNavigated) return@runOnUiThread
            if (landmarks != null)
                b.poseOverlay.setResults(result, resultBundle.inputImageHeight, resultBundle.inputImageWidth)

            if (chairPrepareMode) {
                if (!isBarSimulating) hideExamGuides()
                if (chairPreparePhase == 0) return@runOnUiThread
                if (chairPreparePhase == 1 && landmarks != null && landmarks.size >= 33) {
                    val shoulderY = (landmarks[11].y() + landmarks[12].y()) / 2
                    val ankleY = maxOf(landmarks[27].y(), landmarks[28].y())
                    val m = (ankleY - shoulderY) * 100f
                    val yChange = if (lastShoulderY > 0f) abs(shoulderY - lastShoulderY) else 1f
                    lastShoulderY = shoulderY
                    val sbu = com.fallzero.app.pose.SBUCalculator.calculate(landmarks)
                    val shoulderWidth = abs(landmarks[11].x() - landmarks[12].x())
                    val isFacingFront = sbu > 0f && (shoulderWidth / sbu) > 0.40f
                    // 무릎 각도로 서있음/앉음 판별 (앉으면 무릎 ~90°, 서면 ~170°). 보이는 쪽 다리 사용. (임계값 150°)
                    val kvisL = landmarks[23].visibility().orElse(0f) + landmarks[25].visibility().orElse(0f) + landmarks[27].visibility().orElse(0f)
                    val kvisR = landmarks[24].visibility().orElse(0f) + landmarks[26].visibility().orElse(0f) + landmarks[28].visibility().orElse(0f)
                    val kneeAngle = if (kvisL >= kvisR)
                        com.fallzero.app.pose.AngleCalculator.calculateAngle(landmarks[23], landmarks[25], landmarks[27])
                    else com.fallzero.app.pose.AngleCalculator.calculateAngle(landmarks[24], landmarks[26], landmarks[28])
                    val isStanding = kneeAngle > 150f
                    val isStill = yChange < 0.008f && isFullBodyVisible(landmarks) && isFacingFront && isStanding

                    if (isStill) {
                        if (bodyStillSinceMs == 0L) {
                            bodyStillSinceMs = System.currentTimeMillis()
                            if (!chairDetectSpoken) {
                                chairDetectSpoken = true; chairSitSpoken = false
                                ttsManager?.speak("잘 감지됐어요. 그대로 서 계세요")
                            }
                        }
                        standingMSamples.add(m)
                        val held = System.currentTimeMillis() - bodyStillSinceMs
                        // 의자 가져와 앞에 서서 2초 이상 가만히 있으면 시작 (이전 방식으로 복원 — 사용자 요청)
                        if (held >= 2000L) {
                            chairPreparePhase = 2
                            val gen = chairPrepareGen            // 이 진입 세대 — 체인 끝 전환의 가드용
                            val avgM = if (standingMSamples.isNotEmpty()) standingMSamples.average().toFloat() else 50f
                            viewModel.calibrateChairStand(avgM)
                            showFloatingFeedback("준비 완료", 0xFF4CAF50.toInt())
                            b.tvCount.text = ""; b.tvTimer.text = ""
                            feedChairWatchdog()                  // 준비 안내 체인 시작 — 이후 단계 경계마다 재무장
                            showChairStandGuidance {
                                if (_binding == null || hasNavigated) return@showChairStandGuidance
                                feedChairWatchdog()              // 안내영상+막대기설명 끝 → 다음 단계 감시
                                hideFloatingFeedback(); startChairGuideVideo()
                                showThirtySecCallout {
                                    if (_binding == null || hasNavigated) return@showThirtySecCallout
                                    feedChairWatchdog()
                                    ttsManager?.speak("이제 시작하겠습니다") {
                                        if (_binding == null || hasNavigated) return@speak
                                        startCountdown321 {
                                            enterChairMeasurement(gen)   // 정상 체인 끝 (멱등 — 워치독과 중복 안전)
                                        }
                                    }
                                }
                            }
                        } else {
                            val remain = ((2000L - held) / 1000) + 1
                            b.tvTimer.text = "✓ 감지됨 — ${remain}초만 그대로!"
                        }
                    } else {
                        bodyStillSinceMs = 0L; standingMSamples.clear()
                        if (isFullBodyVisible(landmarks) && !isStanding) {
                            // 앉아있음 → 일어나라고 안내
                            b.tvTimer.text = "의자에서 일어나 주세요"
                            if (!chairSitSpoken) { chairSitSpoken = true; chairDetectSpoken = false; ttsManager?.speak("의자에서 일어나 주세요") }
                        } else {
                            b.tvTimer.text = "의자 앞에 가만히 서주세요"
                        }
                    }
                }
                return@runOnUiThread
            }

            if (landmarks != null) {
                viewModel.processLandmarks(landmarks)
                if (!isRingExplaining) {
                    if (showGuide) updateExamGuide(landmarks) else hideExamGuides()
                }
            }
        }
    }

    private fun isFullBodyVisible(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?): Boolean {
        if (landmarks == null || landmarks.size < 33) return false
        if (intArrayOf(0, 11, 12, 23, 24).count { landmarks[it].visibility().orElse(0f) > 0.3f } < 5) return false
        val noseY = landmarks[0].y(); val ankleY = maxOf(landmarks[27].y(), landmarks[28].y())
        val span = ankleY - noseY
        return !(span < 0.30f || span > 0.97f || noseY < 0.02f)
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread { _binding?.tvExamPhase?.text = "오류: $error" }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        userAwayCheckJob?.cancel(); userAwayCheckJob = null
        pauseAnnounceJob?.cancel(); pauseAnnounceJob = null
        // 안내 영상 도중 화면 이탈 시 MediaPlayer/Surface 누수 + 백그라운드 자막 TTS 재생 방지
        // (ExerciseFragment.onDestroyView의 releaseGuidancePlayer()와 동일 처리)
        releaseExamGuidancePlayer()
        cancelChairWatchdog()   // 의자 준비 워치독 정리 — 뷰 파괴 후 늦은 콜백 발화 방지
        releaseBarSimPlayer()
        cleanupCamera()
        try { _binding?.videoChairGuide?.stopPlayback() } catch (_: Exception) {}
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null; cameraExecutor?.shutdown(); cameraExecutor = null; _binding = null
    }

    companion object { private const val TAG = "ExamFragment" }
}