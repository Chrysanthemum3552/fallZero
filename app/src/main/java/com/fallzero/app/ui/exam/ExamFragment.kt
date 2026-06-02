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
                chairPrepareMode = true; chairPreparePhase = 0
                bodyStillSinceMs = 0L; lastShoulderY = 0f; standingMSamples.clear()
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
        b.tvGuidanceTitle.text = "의자 일어서기 검사"
        b.tvGuidanceText.text = "의자 앞에 정면을 바라보고 서주세요."
        b.tvGuidanceCountdown.visibility = View.GONE
        b.tvVideoPlaceholder.visibility = View.GONE
        b.videoChairGuidance.visibility = View.GONE
        b.ivChairFrontPose.setImageResource(R.drawable.chair_front_pose)
        b.ivChairFrontPose.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.VISIBLE
        ttsManager?.speak("이번 검사는 의자가 필요합니다. 의자를 가져오셔서 의자 앞에 정면을 바라보고 서주세요.")
        b.root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            _binding?.ivChairFrontPose?.visibility = View.GONE
            _binding?.guidanceOverlay?.visibility = View.GONE
            chairPreparePhase = 1
            showFloatingFeedback("의자 앞에 정면을 바라보고 가만히 서주세요", 0xFFFFEB3B.toInt())
        }, 5000L)
    }

    private fun showChairStandGuidance(onAfterScript: () -> Unit) {
        val b = _binding ?: return
        b.ivChairFrontPose.visibility = View.GONE; b.videoChairGuidance.visibility = View.GONE
        b.tvVideoPlaceholder.text = "안내 영상"; b.tvVideoPlaceholder.textSize = 64f
        b.tvVideoPlaceholder.setTextColor(0xFFFFFF00.toInt()); b.tvVideoPlaceholder.visibility = View.VISIBLE
        b.tvGuidanceTitle.text = "의자 일어서기 검사"; b.tvGuidanceText.text = ""
        b.guidanceOverlay.visibility = View.VISIBLE
        ttsManager?.speak("다음 운동은 의자 앉았다 일어서기 입니다. 우선 안내 영상을 시청하겠습니다.") {
            if (_binding == null || hasNavigated) return@speak
            _binding?.tvVideoPlaceholder?.visibility = View.GONE
            playChairGuidanceVideo(onAfterScript)
        }
    }

    private fun playChairGuidanceVideo(onAfterScript: () -> Unit) {
        val b = _binding ?: return
        b.tvGuidanceTitle.text = ""; b.tvGuidanceText.text = ""
        b.tvGuidanceCountdown.visibility = View.GONE; b.tvVideoPlaceholder.visibility = View.GONE
        b.tvChairSubtitle.visibility = View.GONE
        b.videoChairGuidance.visibility = View.VISIBLE

        val lines = listOf(
            "팔을 가슴 앞에\n교차하세요",
            "이제 천천히\n앉았다 일어서세요"
        )

        val uri = Uri.parse("android.resource://${requireContext().packageName}/${R.raw.chair_stand_guide}")
        startExamGuidanceVideo(uri, lines) {
            _binding?.guidanceOverlay?.visibility = View.GONE
            _binding?.vCountdownDim?.visibility = View.VISIBLE
            startBarSimulation(onAfterScript)
        }
    }

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
        b.guideBar.visibility = View.VISIBLE; b.guideBar.setGuide(barGuide(1f)); b.guideBar.bringToFront()
        ttsManager?.speak("화면 오른쪽에는 여러분의 자세를 감지하여 막대기가 올라가거나 내려갑니다") {
            if (_binding == null || hasNavigated) return@speak
            ttsManager?.speak("앉으면 막대기가 내려가고") {
                if (_binding == null || hasNavigated) return@speak
                animateBar(1f, 0.1f, 1500L, barGuide) {
                    if (_binding == null || hasNavigated) return@animateBar
                    _binding?.root?.postDelayed({
                        if (_binding == null || hasNavigated) return@postDelayed
                        ttsManager?.speak("일어서면 막대기가 올라갑니다") {
                            if (_binding == null || hasNavigated) return@speak
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

    private fun animateBar(from: Float, to: Float, durationMs: Long,
                           barGuide: (Float) -> com.fallzero.app.ui.overlay.ExerciseGuide.Bar, onEnd: () -> Unit) {
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            addUpdateListener { _binding?.guideBar?.setGuide(barGuide(it.animatedValue as Float)) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onEnd() }
            })
        }.start()
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
        val b = _binding ?: return
        val uri = Uri.parse("android.resource://${requireContext().packageName}/${R.raw.chair_stand_guide}")
        b.videoChairGuide.setZOrderOnTop(true)
        b.videoChairGuide.setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); mp.start() }
        b.videoChairGuide.setOnErrorListener { _, what, extra -> Log.e(TAG, "video error: what=$what extra=$extra"); true }
        b.videoChairGuide.setVideoURI(uri); b.videoChairGuide.visibility = View.VISIBLE
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
            "뒷꿈치를 모아주시고\n팔을 가슴 앞에 교차하세요",
            "자세를 유지해주세요"
        )
        2 -> listOf(
            "두 팔은 가슴에 교차해주세요",
            "한쪽 발 뒤꿈치를\n다른 발 엄지발가락 옆에 놓으세요",
            "자세를 유지해주세요",
            "다음 화면은 위에서 봤을 때\n발 모양입니다. 참고하세요"
        )
        3 -> listOf(
            "두 팔은 가슴에 교차해주세요",
            "한쪽 발 뒤꿈치를\n다른 발 발끝 바로 앞에 일렬로 놓으세요",
            "자세를 유지해주세요",
            "다음 화면은 위에서 봤을 때\n발 모양입니다. 참고하세요"
        )
        4 -> listOf(
            "두 팔은 가슴에 교차해주세요",
            "한쪽 발을 들어 올려\n한 발로만 서주세요",
            "자세를 유지해주세요"
        )
        else -> listOf("자세를 유지해주세요")
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
                _binding?.tvExamGuideLineText?.text = examSubtitleEntries[idx].third
                _binding?.examGuideTextOverlay?.visibility = View.VISIBLE
                if (idx != lastExamSubtitleIndex) {
                    lastExamSubtitleIndex = idx
                    ttsManager?.speak(examSubtitleEntries[idx].third.replace("\n", " "))
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
        if (videoAspect > viewAspect) { sx = 1f; sy = viewAspect / videoAspect }
        else { sx = videoAspect / viewAspect; sy = 1f }
        val m = android.graphics.Matrix()
        m.setScale(sx, sy, viewW / 2f, viewH / 2f)
        tv.setTransform(m)
        tv.invalidate()
    }

    private fun startExamGuidanceVideo(uri: android.net.Uri, lines: List<String>, onComplete: () -> Unit) {
        val b = _binding ?: return
        b.videoChairGuidance.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.VISIBLE

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
                            b.tvExamPhase.text = getString(R.string.exam_phase_chair)
                            b.tvExamPhase.setTextColor(0xFFFFFFFF.toInt())
                            b.tvTimer.text = ""; b.tvCount.text = ""
                            if (phase.isRunning) {
                                b.layoutCornerCount.visibility = View.VISIBLE
                                b.tvCornerCount.visibility = View.VISIBLE
                                b.tvCornerTimer.text = "${phase.remainingSec}"
                                b.progressCornerTimer.setProgressCompat(phase.remainingSec, true)
                                b.tvCornerCount.text = "${phase.count}회"
                                when {
                                    phase.errorHint != null -> showFloatingFeedback(phase.errorHint, 0xFFFFEB3B.toInt())
                                    phase.count >= 1 -> showFloatingFeedback("잘 하고 있어요!", 0xFF4CAF50.toInt())
                                    else -> hideFloatingFeedback()
                                }
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
                            b.layoutCornerCount.visibility = View.GONE
                            b.tvCornerCount.visibility = View.GONE
                            hideFloatingFeedback(); stopChairGuideVideo()
                            b.tvExamPhase.text = "${phase.stage}단계: ${phase.stageName}"
                            b.tvTimer.text = ""; b.tvCount.text = ""
                            lastSpokenSecond = -1; lastHintText = null
                            // [추가] 단계 시작마다 hasStagePassed 초기화
                            hasStagePassed = false
                            showBalanceGuidance(phase.stage, phase.stageName) {
                                if (_binding == null || hasNavigated) return@showBalanceGuidance
                                startCountdown321 {
                                    if (_binding != null && !hasNavigated) {
                                        lastHintSpokenMs = 0L; lastSpokenSecond = -1
                                        viewModel.startBalanceMeasurementNow()
                                        startUserAwayMonitor()
                                    }
                                }
                            }
                        }

                        is ExamViewModel.ExamPhase.Balance -> {
                            b.tvExamPhase.text = "${phase.stage}단계: ${viewModel.getBalanceStageName(phase.stage)}"
                            b.tvTimer.text = ""; b.tvTimerLabel.text = ""
                            if (phase.isStable) {
                                showFloatingFeedback("잘 하고 있어요!", 0xFF4CAF50.toInt())
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
                b.guideBar.visibility = View.VISIBLE; b.guideBubble.visibility = View.GONE; b.guideBar.setGuide(guide)
            }
            is com.fallzero.app.ui.overlay.ExerciseGuide.Bubble -> {
                b.guideBar.visibility = View.GONE; b.guideBubble.visibility = View.VISIBLE; b.guideBubble.setGuide(guide)
            }
            null -> hideExamGuides()
        }
    }

    private fun hideExamGuides() { _binding?.guideBar?.visibility = View.GONE; _binding?.guideBubble?.visibility = View.GONE }

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
                if (isPausedForUserAway || isPausedForOcclusion) continue
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
                    val isStill = yChange < 0.008f && isFullBodyVisible(landmarks) && isFacingFront

                    if (isStill) {
                        if (bodyStillSinceMs == 0L) bodyStillSinceMs = System.currentTimeMillis()
                        standingMSamples.add(m)
                        val held = System.currentTimeMillis() - bodyStillSinceMs
                        if (held >= 3000L) {
                            chairPreparePhase = 2
                            val avgM = if (standingMSamples.isNotEmpty()) standingMSamples.average().toFloat() else 50f
                            viewModel.calibrateChairStand(avgM)
                            showFloatingFeedback("준비 완료", 0xFF4CAF50.toInt())
                            b.tvCount.text = ""; b.tvTimer.text = ""
                            showChairStandGuidance {
                                if (_binding == null || hasNavigated) return@showChairStandGuidance
                                hideFloatingFeedback(); startChairGuideVideo()
                                showThirtySecCallout {
                                    if (_binding == null || hasNavigated) return@showThirtySecCallout
                                    ttsManager?.speak("이제 시작하겠습니다") {
                                        if (_binding == null || hasNavigated) return@speak
                                        startCountdown321 {
                                            if (_binding != null && !hasNavigated && chairPrepareMode) {
                                                chairPrepareMode = false; isBarSimulating = false
                                                lastSpokenSecond = -1; lastHintSpokenMs = 0L
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
                        bodyStillSinceMs = 0L; standingMSamples.clear()
                        b.tvTimer.text = "의자 앞에 가만히 서주세요"
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
        cleanupCamera()
        try { _binding?.videoChairGuide?.stopPlayback() } catch (_: Exception) {}
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null; cameraExecutor?.shutdown(); cameraExecutor = null; _binding = null
    }

    companion object { private const val TAG = "ExamFragment" }
}