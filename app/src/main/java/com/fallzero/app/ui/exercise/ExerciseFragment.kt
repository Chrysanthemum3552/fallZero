package com.fallzero.app.ui.exercise

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.media.MediaPlayer
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
    private var showGuide: Boolean = true
    private var lastSpokenCount = -1
    private var sideSwitchInProgress = false
    private var lastTransientMsgMs = 0L
    private var transientMsgHideRunnable: Runnable? = null
    private val TRANSIENT_MSG_COOLDOWN_MS = 4000L
    private val TRANSIENT_MSG_DISPLAY_MS = 2500L

    private var calibrationIntroSpoken = false
    private var lastCalibrationRepsSpoken = 0
    private var calibrationCompleteSpoken = false

    @Volatile private var lastValidFrameMs = 0L
    @Volatile private var lastFullBodyMs = 0L
    @Volatile private var lastSideViewMs = 0L
    @Volatile private var isPausedForUserAway = false
    @Volatile private var isPausedForOcclusion = false
    @Volatile private var isPausedForFrontFacing = false
    @Volatile private var userReturnInProgress = false
    @Volatile private var isInGuidancePhase = false
    // 전신이 카메라에 최초로 잡혔는지. 잡히기 전(운동 본격 시작 전)에는 이탈/가림 경고를 발동하지 않음.
    @Volatile private var bodyEverDetected = false

    private var userAwayCheckJob: Job? = null
    private var pauseAnnounceJob: Job? = null
    private val USER_AWAY_MSG = "카메라 앞으로 와주세요"
    private val OCCLUSION_MSG = "신체의 일부가 보이지 않습니다. 조금 더 뒤로 가주세요"
    private val FRONT_FACING_MSG = "옆으로 돌아주세요"
    private val SIDE_VIEW_THRESHOLD = 0.60f   // 0.50 → 0.60 (사용자 요청: 옆모습 인정 각도 완화)

    @Volatile private var isFrontCamera: Boolean = false
    private var cameraPreview: Preview? = null
    private var cameraAnalyzer: ImageAnalysis? = null
    private val USER_AWAY_TIMEOUT_MS = 2000L
    private val FRONT_FACING_TIMEOUT_MS = 4000L
    private val PAUSE_RESUME_BUFFER_MS = 1500L
    private val PAUSE_TTS_LOOP_MS = 5000L
    private val POST_START_DELAY_MS = 750L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else { ttsManager?.speak("카메라 권한이 필요합니다."); abortToHome() }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExerciseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hasNavigated = false
        cameraExecutor = Executors.newSingleThreadExecutor()
        ttsManager = TTSManager.getInstance(requireContext())
        isFrontCamera = com.fallzero.app.util.CameraFacingPref.isFrontCamera(requireContext())
        binding.poseOverlay.setShowSkeleton(com.fallzero.app.util.DisplayPrefs.showSkeleton(requireContext()))
        // 전면 카메라는 랜드마크가 이미 좌우반전(셀피)이라 오버레이는 반전 안 함(+1), 후면은 -1 (프리뷰와 일치)
        binding.poseOverlay.scaleX = if (isFrontCamera) 1f else -1f
        showGuide = com.fallzero.app.util.DisplayPrefs.showGuide(requireContext())

        val step = SessionFlow.current()
        val exerciseId = if (step.type == SessionFlow.StepType.EXERCISE && step.exerciseId > 0)
            step.exerciseId
        else requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
            .getInt("selected_exercise_id", 1)

        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val setLevel = prefs.getInt("current_set_level", 1)

        viewModel.reset()
        viewModel.initExercise(createEngine(exerciseId, setLevel), exerciseId, setLevel)
        binding.tvExerciseName.text = SessionFlow.exerciseName(exerciseId)

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)

        binding.btnStop.setOnClickListener { abortToHome() }
        binding.btnCameraFlip.setOnClickListener { toggleCameraFacing() }

        if (prefs.getBoolean("debug_mode", false)) {
            binding.btnDebugCount.visibility = View.VISIBLE
            binding.btnDebugCount.setOnClickListener { viewModel.debugIncrementCount() }
        } else binding.btnDebugCount.visibility = View.GONE

        if (prefs.getBoolean("skip_guidance", false)) {
            binding.guidanceOverlay.visibility = View.GONE
            // 안내 영상을 건너뛰어도 "사람 확인 → 3,2,1 → 측정" 순서는 동일하게 거친다
            binding.root.postDelayed({
                if (_binding != null && !hasNavigated) startExerciseAfterGuidance()
            }, 300L)
        } else if (!com.fallzero.app.data.SessionFlow.exerciseChairPromptDone) {
            // 운동 세션 시작 시 1번만: "의자를 가져와 주세요" 안내 → 의자 앞에 서서 가만히 있는지 확인(검사세션처럼) → 진행.
            // 이 확인이 세션 전신확인을 겸하므로(사용자 요청), 운동 안내 뒤 별도 전신확인은 생략한다.
            com.fallzero.app.data.SessionFlow.exerciseChairPromptDone = true
            showChairBringPrompt {
                com.fallzero.app.data.SessionFlow.exerciseBodyCheckDone = true
                if (_binding != null && !hasNavigated) showStartGuidance(exerciseId)
            }
        } else {
            showStartGuidance(exerciseId)
        }
        observeViewModel()
    }

    // -----------------------------------------------
    // 안내 영상 시스템
    // -----------------------------------------------

    private var guidancePlayer: MediaPlayer? = null
    private var guidanceLines: List<String> = emptyList()
    private var guidancePausePoints: List<Long> = emptyList()
    private var guidanceLineIndex = 0
    private var isGuidancePaused = false
    private val pauseCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // pause 지점 정의
    // pause[0] = 0L (영상 시작 즉시 pause, 줄2 TTS 발화)
    // pause[1] = 동작1 끝 (freeze 시작점, 줄3 TTS 발화)
    // pause[2] = 동작2 끝 (freeze 시작점, 줄4 TTS 발화) - 4줄 운동만
    private fun getPausePoints(exerciseId: Int): List<Long> = when (exerciseId) {
        1 -> listOf(0L, 5890L, 19513L)
        2 -> listOf(0L, 7000L, 19763L)
        3 -> listOf(0L, 6000L, 21733L)
        4 -> listOf(0L, 6000L)
        5 -> listOf(0L, 6000L)
        6 -> listOf(0L, 7390L)
        7 -> listOf(0L, 9533L)
        8 -> listOf(0L, 6500L, 15423L)
        else -> listOf(0L)
    }

    // pause 지점 폴링 (50ms 간격)
    private val pauseCheckRunnable = object : Runnable {
        override fun run() {
            val player = guidancePlayer ?: return
            if (!player.isPlaying) { pauseCheckHandler.postDelayed(this, 50L); return }
            val pos = player.currentPosition.toLong()
            val idx = guidanceLineIndex
            if (idx < guidancePausePoints.size && pos >= guidancePausePoints[idx]) {
                // pause 없이 TTS만 발화 (영상은 계속 재생)
                guidanceLineIndex++
                showSubtitleAndSpeak(idx)
                return
            }
            pauseCheckHandler.postDelayed(this, 50L)
        }
    }

    private fun showSubtitleAndSpeak(idx: Int) {
        if (idx >= guidanceLines.size) return
        val raw = guidanceLines[idx]
        // "짧은자막|전체 나레이션" 형식 — 화면엔 짧은 자막, 음성은 전체 문장(없으면 동일)
        val subtitle = raw.substringBefore("|")
        val speak = if (raw.contains("|")) raw.substringAfter("|") else raw
        _binding?.tvGuideLineText?.text = subtitle
        _binding?.guideTextOverlay?.visibility = View.VISIBLE
        ttsManager?.speak(speak.replace("\n", " ")) {
            if (_binding == null || hasNavigated) return@speak
            _binding?.guideTextOverlay?.visibility = View.GONE
            // TTS 완료 후 다음 폴링 재개 (영상은 이미 재생 중)
            if (guidanceLineIndex < guidancePausePoints.size) {
                pauseCheckHandler.post(pauseCheckRunnable)
            }
        }
    }

    private fun resumeGuidanceVideo() {
        val player = guidancePlayer ?: return
        if (!player.isPlaying) player.start()
        if (guidanceLineIndex < guidancePausePoints.size) {
            pauseCheckHandler.post(pauseCheckRunnable)
        }
    }

    /** 운동 세션 시작 시 1번만: 의자 안내 이미지(의자 앞 서있는 사람) + 음성 → 안내가 끝나면
     *  의자 앞에 서서 가만히 있는지 확인(검사세션 방식, 카메라 보이게) → 확인되면 onDone. (사용자 요청) */
    private fun showChairBringPrompt(onDone: () -> Unit) {
        val b = _binding ?: return
        isInGuidancePhase = true
        b.guidanceOverlay.visibility = View.VISIBLE
        b.videoExerciseGuide.visibility = View.GONE
        b.guideTextOverlay.visibility = View.GONE
        b.tvVideoPlaceholder.visibility = View.GONE
        b.tvGuidanceTitle.text = "의자를 가져와 주세요"
        b.tvGuidanceTitle.visibility = View.VISIBLE
        b.ivExerciseGuideImage.setImageResource(R.drawable.chair_front_pose)
        b.ivExerciseGuideImage.visibility = View.VISIBLE
        var proceeded = false
        val proceed = {
            if (!proceeded) {
                proceeded = true
                _binding?.ivExerciseGuideImage?.visibility = View.GONE
                _binding?.tvGuidanceTitle?.visibility = View.GONE
                _binding?.guidanceOverlay?.visibility = View.GONE   // 카메라가 보이게 (검사처럼)
                if (_binding != null && !hasNavigated) awaitPersonStillInFront(onDone)
            }
        }
        ttsManager?.speak("이번 운동 중에는 의자가 필요한 동작이 있어요. 의자를 가져와 의자 앞에 서주세요.") {
            activity?.runOnUiThread { proceed() }
        }
        // 안전장치: TTS 콜백 누락 대비 (정상 시엔 콜백이 먼저)
        b.root.postDelayed({ proceed() }, 12000L)
    }

    /** 의자 앞에 서서 가만히 있는지 확인 (카메라 보이게, 검사세션 방식). 전신이 2초 연속 잡히면 "시작할게요" 후 진행.
     *  ※ 전신 판별 isFullBodyVisible 등 감지 로직은 손대지 않고 "진행 시점"만 제어. */
    private fun awaitPersonStillInFront(onReady: () -> Unit) {
        val b = _binding ?: return
        b.tvErrorMessage.text = "의자 앞에 서서 가만히 계세요"
        b.tvErrorMessage.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            var detectedSinceMs = 0L
            var confirmed = false
            while (isAdded && !hasNavigated) {
                delay(120)
                val now = System.currentTimeMillis()
                val fullBodyFresh = lastFullBodyMs > 0L && now - lastFullBodyMs < 400L
                if (fullBodyFresh) {
                    if (detectedSinceMs == 0L) { detectedSinceMs = now; _binding?.tvErrorMessage?.text = "✓ 잘 보입니다. 잠시만요…" }
                    if (now - detectedSinceMs >= 2000L) { confirmed = true; break }   // 2초 연속 → 확인
                } else {
                    detectedSinceMs = 0L
                    _binding?.tvErrorMessage?.text = "의자 앞에 서서 가만히 계세요"
                }
                if (now - startMs > 60_000L) break
            }
            if (!isAdded || hasNavigated) return@launch
            _binding?.tvErrorMessage?.visibility = View.GONE
            if (!confirmed) { onReady(); return@launch }
            // 확인되면 "좋아요 시작할게요" 후 진행 (음성 끝난 직후 — 사용자 요청)
            var done = false
            val finish = { if (!done) { done = true; if (isAdded && !hasNavigated) onReady() } }
            ttsManager?.speak("좋아요. 시작할게요.") { activity?.runOnUiThread { finish() } }
            _binding?.root?.postDelayed({ finish() }, 3500L)
        }
    }

    // [수정] ExamFragment의 showBalanceGuidance 패턴과 동일하게:
    //        TTS("안내 영상을 보여드릴게요") 완료 후 영상 시작
    //        -> 나레이션이 끝난 뒤 영상이 시작되므로 동시 재생 문제 없음
    private fun showStartGuidance(exerciseId: Int) {
        val b = _binding ?: return
        isInGuidancePhase = true
        b.guidanceOverlay.visibility = View.VISIBLE
        b.videoExerciseGuide.visibility = View.GONE
        b.guideTextOverlay.visibility = View.GONE
        // 운동설명 영상이 나오는 동안에는 상단 글씨(운동명·카운트·피드백)·진행도 막대를 숨긴다 (영상 가림 방지 — 사용자 요청)
        b.tvExerciseName.visibility = View.GONE
        b.tvCount.visibility = View.GONE
        b.tvErrorMessage.visibility = View.GONE
        b.btnCameraFlip.visibility = View.GONE
        b.guideBar.visibility = View.GONE

        val allLines = getInstructionLines(exerciseId)
        guidanceLines = if (allLines.size > 1) allLines.subList(1, allLines.size) else emptyList()
        guidancePausePoints = getPausePoints(exerciseId)
        guidanceLineIndex = 0
        isGuidancePaused = false

        // 줄1: 검사 세션과 동일하게 상단 운동 이름 + 중앙 "안내 영상" 표시
        b.tvGuidanceTitle.text = SessionFlow.exerciseName(exerciseId)
        b.tvGuidanceTitle.visibility = View.VISIBLE
        b.tvVideoPlaceholder.visibility = View.VISIBLE

        val firstLine = allLines.getOrNull(0) ?: ""
        ttsManager?.speak(firstLine.replace("\n", " ")) {
            if (_binding == null || hasNavigated) return@speak
            // 모든 운동 동일: 곧바로 안내영상 재생 (운동6의 '어깨 너비' 이미지 가이드 제거 — 사용자 요청)
            _binding?.tvGuidanceTitle?.visibility = View.GONE
            _binding?.tvVideoPlaceholder?.visibility = View.GONE
            _binding?.videoExerciseGuide?.visibility = View.VISIBLE
            startGuidanceVideo(exerciseId, guidanceLines)
        }
    }

    /** 안내 영상의 원본 종횡비를 TextureView 안에 맞춰 letterbox(fit-center) 변환.
     *  기본 TextureView는 surface를 뷰 크기로 늘려 채워 찌그러지므로, 비율 보존을 위해 한 축을 축소. */
    private fun applyVideoAspect(tv: android.view.TextureView, videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        val viewW = tv.width.toFloat(); val viewH = tv.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val viewAspect = viewW / viewH
        val sx: Float; val sy: Float
        // center-crop: 비율을 유지한 채 뷰를 꽉 채우고(여백 0) 넘치는 가장자리는 잘라낸다.
        if (videoAspect > viewAspect) { sx = videoAspect / viewAspect; sy = 1f }  // 영상이 더 넓음 → 높이 채우고 좌우 크롭
        else { sx = 1f; sy = viewAspect / videoAspect }                            // 영상이 더 높음 → 너비 채우고 상하 크롭
        val m = android.graphics.Matrix()
        m.setScale(sx, sy, viewW / 2f, viewH / 2f)
        tv.setTransform(m)
        tv.invalidate()
    }

    private fun startGuidanceVideo(exerciseId: Int, lines: List<String>) {
        val b = _binding ?: return

        fun initPlayer(st: android.graphics.SurfaceTexture) {
            val mp = MediaPlayer(); guidancePlayer = mp
            try {
                val uri = android.net.Uri.parse(
                    "android.resource://${requireContext().packageName}/${guideVideoRes(exerciseId)}"
                )
                mp.setDataSource(requireContext(), uri)
                mp.setSurface(android.view.Surface(st))
                mp.isLooping = false; mp.setVolume(0f, 0f)
                // 영상 원본 종횡비 보존 — 키오스크 세로 화면에 늘어나/찌그러지지 않도록 letterbox 변환
                mp.setOnVideoSizeChangedListener { _, vw, vh ->
                    _binding?.videoExerciseGuide?.let { applyVideoAspect(it, vw, vh) }
                }
                mp.setOnPreparedListener { player ->
                    player.start()
                    // 영상 시작과 동시에 첫 TTS 발화 (pause 없이)
                    if (guidancePausePoints.isNotEmpty() && guidancePausePoints[0] == 0L) {
                        guidanceLineIndex++
                        showSubtitleAndSpeak(0)
                    } else {
                        pauseCheckHandler.post(pauseCheckRunnable)
                    }
                }
                mp.setOnCompletionListener {
                    pauseCheckHandler.removeCallbacks(pauseCheckRunnable)
                    _binding?.guideTextOverlay?.visibility = View.GONE
                    _binding?.guidanceOverlay?.visibility = View.GONE
                    releaseGuidancePlayer()
                    if (_binding != null && !hasNavigated) startExerciseAfterGuidance()
                }
                mp.setOnErrorListener { _, _, _ ->
                    pauseCheckHandler.removeCallbacks(pauseCheckRunnable)
                    _binding?.guidanceOverlay?.visibility = View.GONE
                    releaseGuidancePlayer()
                    if (_binding != null && !hasNavigated) startExerciseAfterGuidance()
                    true
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "안내 영상 오류", e)
                releaseGuidancePlayer(); startExerciseAfterGuidance()
            }
        }

        if (b.videoExerciseGuide.isAvailable) {
            initPlayer(b.videoExerciseGuide.surfaceTexture!!)
        } else {
            b.videoExerciseGuide.surfaceTextureListener =
                object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        st: android.graphics.SurfaceTexture, w: Int, h: Int
                    ) = initPlayer(st)
                    override fun onSurfaceTextureSizeChanged(
                        st: android.graphics.SurfaceTexture, w: Int, h: Int
                    ) {}
                    override fun onSurfaceTextureDestroyed(
                        st: android.graphics.SurfaceTexture
                    ): Boolean = true
                    override fun onSurfaceTextureUpdated(
                        st: android.graphics.SurfaceTexture
                    ) {}
                }
        }

        // 안전장치: 120초 후에도 안내 중이면 강제 진행
        b.root.postDelayed({
            if (_binding != null && !hasNavigated && isInGuidancePhase) {
                pauseCheckHandler.removeCallbacks(pauseCheckRunnable)
                releaseGuidancePlayer()
                _binding?.guideTextOverlay?.visibility = View.GONE
                _binding?.guidanceOverlay?.visibility = View.GONE
                startExerciseAfterGuidance()
            }
        }, 120_000L)
    }

    private fun releaseGuidancePlayer() {
        pauseCheckHandler.removeCallbacks(pauseCheckRunnable)
        guidancePlayer?.release(); guidancePlayer = null
    }

    private fun startExerciseAfterGuidance() {
        lastSpokenCount = -1
        // ① 설명이 끝나면 먼저 "카메라 앞에 전신이 제대로 서 있는지"부터 확인 (건너뛰지 않음).
        //    감지(카운팅)는 여기서 시작하지 않음 — 사람 확인 → 3,2,1 → 그 다음에 켜진다.
        // 전신 확인은 세션에서 "처음 1번만" (사용자 요청). 이미 했으면 건너뛰고 바로 진행.
        if (com.fallzero.app.data.SessionFlow.exerciseBodyCheckDone) {
            proceedAfterBodyCheck()
        } else {
            awaitPersonInFront {
                com.fallzero.app.data.SessionFlow.exerciseBodyCheckDone = true
                proceedAfterBodyCheck()
            }
        }
    }

    /** 전신 확인 이후: 연습(캘리브레이션)이면 3,2,1 없이 바로 연습 시작,
     *  연습 없으면 본운동 직전에만 3,2,1. */
    private fun proceedAfterBodyCheck() {
        // 안내영상 종료 → 상단 글씨 다시 표시 (운동 중 상단 띠)
        _binding?.tvExerciseName?.visibility = View.VISIBLE
        _binding?.tvCount?.visibility = View.VISIBLE
        _binding?.btnCameraFlip?.visibility = View.VISIBLE
        if (viewModel.isCalibrationRequired()) {
            isInGuidancePhase = false
            viewModel.startMeasurement()
            startUserAwayMonitor()
        } else {
            postCalibrationStarted = true
            startCountdown321 {
                if (_binding == null || hasNavigated) return@startCountdown321
                _binding?.root?.postDelayed({
                    if (_binding != null && !hasNavigated) {
                        isInGuidancePhase = false
                        viewModel.startMeasurement()
                        startUserAwayMonitor()
                    }
                }, POST_START_DELAY_MS)
            }
        }
    }

    /**
     * 설명이 끝난 뒤, 카메라 앞에 전신이 제대로 잡힐 때까지 대기(절대 건너뛰지 않음).
     * - 전신이 안 잡히면 "카메라 앞에 서주세요"를 계속 표시하며 대기.
     * - 전신이 0.5초 연속 잡히면 통과 → 호출부에서 3,2,1 카운트다운 시작.
     * - 측정(카운팅)은 여기서 시작하지 않는다(요청: 3,2,1 "시작!" 이후에만 감지 on).
     * - 60초 안전장치(정상적으로는 사람이 서면 1~2초 내 통과; 데모 잠금 방지용).
     * ※ 전신 판별 isFullBodyVisible 등 감지 로직 자체는 손대지 않음 — 측정 "시작 시점"만 제어.
     */
    private fun awaitPersonInFront(onReady: () -> Unit) {
        val b = _binding ?: return
        // 전신 확인 단계: 사용자가 카메라(자기 모습)를 보며 위치를 잡아야 한다.
        // 화면을 가리는 일시정지 오버레이를 쓰지 않고(=어둡게 처리 X) 상단 안내 문구만 띄워 카메라가 또렷이 보이게 한다. (사용자 요청)
        b.tvErrorMessage.text = "카메라에 전신이 잘 보이는지 확인할게요"
        b.tvErrorMessage.visibility = View.VISIBLE
        ttsManager?.speak("카메라에 전신이 잘 보이는지 확인할게요. 머리부터 발끝까지 보이게 서주세요.")
        viewLifecycleOwner.lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            var detectedSinceMs = 0L
            var confirmedAtMs = 0L
            var confirmTtsDone = false
            while (isAdded && !hasNavigated) {
                delay(120)
                val now = System.currentTimeMillis()
                val fullBodyFresh = lastFullBodyMs > 0L && now - lastFullBodyMs < 400L
                if (fullBodyFresh) {
                    if (detectedSinceMs == 0L) detectedSinceMs = now
                    if (now - detectedSinceMs >= 1000L) {             // 전신 1초 연속 → 확인 완료
                        if (confirmedAtMs == 0L) {
                            confirmedAtMs = now
                            _binding?.tvErrorMessage?.text = "✓ 전신이 잘 보입니다"
                            // 안내 음성이 끝난 뒤에 진행 ("좋아요…"가 중간에 끊기지 않도록) — 사용자 요청
                            ttsManager?.speak("좋아요. 전신이 잘 보입니다.") { confirmTtsDone = true }
                        }
                        if (confirmTtsDone || now - confirmedAtMs > 4000L) break   // 음성 끝나면(또는 4초 안전장치) 진행
                    } else {
                        _binding?.tvErrorMessage?.text = "전신을 확인하는 중이에요…"
                    }
                } else {
                    detectedSinceMs = 0L; confirmedAtMs = 0L; confirmTtsDone = false
                    _binding?.tvErrorMessage?.text = "머리부터 발끝까지 보이게 서주세요"
                }
                if (now - startMs > 60_000L) break                    // 안전장치
            }
            if (!isAdded || hasNavigated) return@launch
            _binding?.tvErrorMessage?.visibility = View.GONE
            onReady()
        }
    }

    // -----------------------------------------------
    // 동작 전환 오버레이 (양측 운동 전환)
    // -----------------------------------------------

    private fun showSwitchOverlay(text: String, onDone: () -> Unit) {
        val b = _binding ?: return
        b.tvExerciseSubtitle.text = text
        b.exerciseInstructionOverlay.visibility = View.VISIBLE
        ttsManager?.speak(text) {
            if (_binding == null || hasNavigated) return@speak
            _binding?.exerciseInstructionOverlay?.visibility = View.GONE
            onDone()
        }
    }

    // -----------------------------------------------
    // 텍스트 리소스 헬퍼
    // -----------------------------------------------

    private fun getInstructionLines(exerciseId: Int): List<String> {
        val script = getString(guidanceScriptRes(exerciseId))
        return script.split("\n").filter { it.isNotBlank() }
    }

    // -----------------------------------------------
    // 리소스 매핑
    // -----------------------------------------------

    private fun guideVideoRes(exerciseId: Int): Int = when (exerciseId) {
        1 -> R.raw.knee_extension_guide
        2 -> R.raw.hip_abduction_guide
        3 -> R.raw.knee_flexion_guide
        4 -> R.raw.calf_raise_guide
        5 -> R.raw.toe_raise_guide
        6 -> R.raw.knee_bend_guide
        7 -> R.raw.chair_stand_ex_guide
        8 -> R.raw.balance_guide_exercise
        else -> R.raw.balance_guide_4
    }

    private fun guidanceScriptRes(id: Int): Int = when (id) {
        1 -> R.string.ex_guide_1; 2 -> R.string.ex_guide_2; 3 -> R.string.ex_guide_3
        4 -> R.string.ex_guide_4; 5 -> R.string.ex_guide_5; 6 -> R.string.ex_guide_6
        7 -> R.string.ex_guide_7; 8 -> R.string.ex_guide_8; else -> R.string.ex_guide_2
    }

    // -----------------------------------------------
    // Engine 생성
    // -----------------------------------------------

    private var postCalibrationStarted = false

    private fun handlePostCalibrationCountdown() {
        if (postCalibrationStarted || _binding == null || hasNavigated) return
        postCalibrationStarted = true
        viewModel.pauseMeasurementForSideSwitch()
        // 2번째 연습 완료 순간 isInCalibration이 바로 false가 돼 "2/2"가 안 보이던 문제 — 여기서 명시적으로 채워 보여줌 (사용자 요청)
        _binding?.tvCount?.text = "연습 2/2"
        // 연습 완료 — "좋아요!"를 화면 가운데 크게 표시 + 나레이션 (사용자 요청)
        _binding?.tvBigCountdown?.textSize = 72f
        _binding?.tvBigCountdown?.text = "좋아요!"
        _binding?.tvBigCountdown?.visibility = View.VISIBLE
        ttsManager?.speak("좋아요! 이제 본격적으로 시작할게요.") {
            if (_binding == null || hasNavigated) return@speak
            _binding?.tvBigCountdown?.visibility = View.GONE
            startCountdown321 {
                if (_binding == null || hasNavigated) return@startCountdown321
                _binding?.root?.postDelayed({
                    if (_binding != null && !hasNavigated) {
                        lastSpokenCount = -1; viewModel.startMeasurement(); startUserAwayMonitor()
                    }
                }, POST_START_DELAY_MS)
            }
        }
    }

    private fun createEngine(exerciseId: Int, setLevel: Int): ExerciseEngine {
        val targetCount = 10
        return when (exerciseId) {
            1 -> KneeExtensionEngine(targetCount); 2 -> HipAbductionEngine(targetCount)
            3 -> KneeFlexionEngine(targetCount); 4 -> CalfRaiseEngine(targetCount)
            5 -> ToeRaiseEngine(targetCount); 6 -> KneeBendEngine(targetCount)
            7 -> ChairStandEngine(targetCount, examMode = false)
            8 -> {
                BalanceEngine(targetCount = 1, stage = 4,
                    overrideTargetTimeSec = com.fallzero.app.data.algorithm.BalanceProgressionManager.getTargetTime(setLevel))
            }
            else -> KneeExtensionEngine(targetCount)
        }
    }

    private fun balanceGuidanceText(setLevel: Int): String {
        val stage = setLevel.coerceIn(1, 5)
        val level = com.fallzero.app.data.algorithm.BalanceProgressionManager.getLevel(stage)
        val timeSec = level.targetTimeSec.toInt()
        val support = when (level.handSupport) {
            com.fallzero.app.data.algorithm.BalanceProgressionManager.HandSupport.BOTH_HANDS ->
                "양손으로 의자나 벽을 잡고"
            com.fallzero.app.data.algorithm.BalanceProgressionManager.HandSupport.ONE_HAND ->
                "한 손으로 의자나 벽을 가볍게 잡고"
            com.fallzero.app.data.algorithm.BalanceProgressionManager.HandSupport.NO_SUPPORT ->
                "손 지지 없이"
        }
        return "한 발로 서서 균형 잡기 운동입니다.\n$support 서주세요.\n한쪽 발을 들어 ${timeSec}초 동안 균형을 유지하세요."
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
                val provider = cameraProviderFuture.get(); cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider) }
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build().also { analysis ->
                        analysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                            poseLandmarkerHelper?.detectLiveStream(imageProxy, isFrontCamera = isFrontCamera)
                                ?: imageProxy.close() } }
                cameraPreview = preview; cameraAnalyzer = imageAnalyzer
                bindCameraToSelector(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                _binding?.tvErrorMessage?.text = "카메라 오류: ${e.message}"
                _binding?.tvErrorMessage?.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // -----------------------------------------------
    // ViewModel observation
    // -----------------------------------------------

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val b = _binding ?: return@collect
                    when (state) {
                        ExerciseViewModel.ExerciseUiState.Idle -> {}
                        is ExerciseViewModel.ExerciseUiState.Ready -> {
                            b.tvExerciseName.text = SessionFlow.exerciseName(getCurrentExerciseId()) + (state.bilateralSide?.let { " — $it" } ?: "")
                            b.tvCount.text = if (getCurrentExerciseId() == 8) "0초"
                            else getString(R.string.exercise_count_format, 0, state.targetCount)
                            b.tvErrorMessage.visibility = View.GONE
                        }
                        is ExerciseViewModel.ExerciseUiState.Running -> {
                            val isBalance = getCurrentExerciseId() == 8
                            if (state.isCalibrating) {
                                b.tvCount.text = "연습 ${state.calibrationReps}/2"
                                b.tvErrorMessage.text = getString(R.string.exercise_calibrating)
                                b.tvErrorMessage.visibility = View.VISIBLE
                                if (!calibrationIntroSpoken) {
                                    calibrationIntroSpoken = true
                                    ttsManager?.speak("두 번 정도 연습해볼까요? 천천히 따라 해보세요.")
                                }
                                if (state.calibrationReps > lastCalibrationRepsSpoken) {
                                    lastCalibrationRepsSpoken = state.calibrationReps
                                    ttsManager?.speak("${state.calibrationReps}")
                                }
                                if (state.coachingCueMessage != null) showTransientMessage(state.coachingCueMessage)
                            } else if (isBalance) {
                                b.tvCount.text = when {
                                    state.count >= 1 -> "완료"
                                    state.engineState == EngineState.IN_MOTION && state.currentMetric > 0f ->
                                        "${state.currentMetric.toInt()}초"
                                    else -> "측정 중"
                                }
                                if (state.engineState == EngineState.IN_MOTION) {
                                    val sec = state.currentMetric.toInt()
                                    if (sec in 1..9 && sec != lastSpokenCount) {
                                        lastSpokenCount = sec
                                        if (ttsManager?.isSpeaking() != true) ttsManager?.speak("$sec")
                                    }
                                } else lastSpokenCount = -1
                                if (state.count >= 1 && lastSpokenCount != Int.MAX_VALUE) {
                                    lastSpokenCount = Int.MAX_VALUE; ttsManager?.speak("십!")
                                }
                                if (state.errorMessage != null) showTransientMessage(state.errorMessage)
                                else b.tvErrorMessage.visibility = View.GONE
                            } else {
                                if (!calibrationCompleteSpoken && !postCalibrationStarted) {
                                    calibrationCompleteSpoken = true
                                    handlePostCalibrationCountdown()
                                    return@collect
                                }
                                if (b.tvErrorMessage.text == getString(R.string.exercise_calibrating))
                                    b.tvErrorMessage.visibility = View.GONE
                                b.tvCount.text = getString(R.string.exercise_count_format, state.count, state.targetCount)
                                if (state.count > 0 && state.count != lastSpokenCount) {
                                    lastSpokenCount = state.count
                                    if (ttsManager?.isSpeaking() != true) ttsManager?.speak("${state.count}")
                                }
                                // 피드백/코칭 메시지가 있을 때만 잠깐 표시. 잘 하고 있으면 아무것도 표시하지 않음
                                // (사용자 요청: 상시 "잘 하고 있어요!" 제거)
                                val transientMsg = state.errorMessage ?: state.coachingCueMessage
                                if (transientMsg != null) showTransientMessage(transientMsg)
                            }
                            b.tvExerciseName.text = SessionFlow.exerciseName(getCurrentExerciseId()) +
                                    (state.bilateralSide?.let { " — $it" } ?: "")
                        }
                        is ExerciseViewModel.ExerciseUiState.SideSwitch -> {
                            if (sideSwitchInProgress) return@collect
                            sideSwitchInProgress = true
                            viewModel.pauseMeasurementForSideSwitch()
                            userAwayCheckJob?.cancel(); pauseAnnounceJob?.cancel()
                            isPausedForUserAway = false; isPausedForOcclusion = false; isPausedForFrontFacing = false
                            _binding?.pauseOverlay?.visibility = View.GONE
                            lastSpokenCount = -1
                            handleSideSwitch()
                        }
                        is ExerciseViewModel.ExerciseUiState.Completed -> {
                            if (hasNavigated) return@collect
                            if (state.autoEndedByInactivity) Toast.makeText(requireContext(),
                                "동작이 감지되지 않아 다음 단계로 넘어갑니다.", Toast.LENGTH_LONG).show()
                            state.progressionResult?.let { result ->
                                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                                requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
                                    .edit().putString("pending_progression_msg", result.message).apply()
                            }
                            navigateNext()
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------
    // 양측 운동 전환
    // -----------------------------------------------

    private fun handleSideSwitch() {
        val exerciseId = getCurrentExerciseId()
        val msg = when (exerciseId) {
            8 -> "다른 쪽 발로\n바꿔주세요"
            1 -> "다른 쪽 다리로\n바꿔주세요"
            else -> "다른 쪽 다리로\n바꿔주세요"
        }
        showSwitchOverlay(msg) {
            if (_binding == null || hasNavigated) return@showSwitchOverlay
            startCountdown321 {
                if (_binding == null || hasNavigated) return@startCountdown321
                _binding?.root?.postDelayed({
                    if (_binding != null && !hasNavigated) {
                        sideSwitchInProgress = false; viewModel.startRightSide(); startUserAwayMonitor()
                    }
                }, POST_START_DELAY_MS)
            }
        }
    }

    // -----------------------------------------------
    // Navigation
    // -----------------------------------------------

    private fun getCurrentExerciseId(): Int {
        val step = SessionFlow.current()
        return if (step.type == SessionFlow.StepType.EXERCISE) step.exerciseId
        else requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
            .getInt("selected_exercise_id", 1)
    }

    private fun navigateNext() {
        if (hasNavigated) return
        hasNavigated = true; cleanupCamera()
        if (SessionFlow.sessionType != SessionFlow.SessionType.NONE) navigateTo(SessionFlow.advance())
        else findNavController().navigate(R.id.action_global_home)
    }

    private fun navigateTo(step: SessionFlow.Step) {
        val nav = findNavController()
        when (step.type) {
            SessionFlow.StepType.EXERCISE -> nav.navigate(R.id.action_global_exercise)
            SessionFlow.StepType.EXAM_BALANCE, SessionFlow.StepType.EXAM_CHAIR_STAND -> nav.navigate(R.id.action_global_exam)
            SessionFlow.StepType.REST, SessionFlow.StepType.SIDE_REST -> nav.navigate(R.id.action_global_rest)
            SessionFlow.StepType.SIDE_ROTATION -> nav.navigate(R.id.action_global_rotation)
            SessionFlow.StepType.CHAIR_REPOSITION -> nav.navigate(R.id.action_global_chair_reposition)
            SessionFlow.StepType.DONE -> {
                if (SessionFlow.isFullExerciseSession) showSessionCompleteOverlay()
                else { SessionFlow.reset(); nav.navigate(R.id.action_global_home) }
            }
            SessionFlow.StepType.PRE_FLIGHT -> nav.navigate(R.id.action_global_preflight)
        }
    }

    private fun showSessionCompleteOverlay() {
        val b = _binding ?: return
        viewModel.markFullSessionComplete()
        b.sessionCompleteOverlay.visibility = View.VISIBLE
        b.guidanceOverlay.visibility = View.GONE; b.pauseOverlay.visibility = View.GONE
        var navigated = false
        val goHome = {
            if (!navigated) {
                navigated = true; SessionFlow.reset()
                if (_binding != null && isAdded) findNavController().navigate(R.id.action_global_home)
            }
        }
        ttsManager?.speak("오늘 운동을 모두 마치셨어요. 정말 수고하셨어요.") { goHome() }
        b.root.postDelayed({ goHome() }, 4500L)
    }

    private fun abortToHome() {
        if (hasNavigated) return
        hasNavigated = true; cleanupCamera(); ttsManager?.stop()
        SessionFlow.reset(); findNavController().navigate(R.id.action_global_home)
    }

    // -----------------------------------------------
    // Transient message / Countdown
    // -----------------------------------------------

    private fun showTransientMessage(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastTransientMsgMs < TRANSIENT_MSG_COOLDOWN_MS) return
        lastTransientMsgMs = now
        val b = _binding ?: return
        b.tvErrorMessage.text = msg; b.tvErrorMessage.visibility = View.VISIBLE; ttsManager?.speak(msg)
        transientMsgHideRunnable?.let { b.tvErrorMessage.removeCallbacks(it) }
        val runnable = Runnable { _binding?.tvErrorMessage?.visibility = View.GONE }
        transientMsgHideRunnable = runnable
        b.tvErrorMessage.postDelayed(runnable, TRANSIENT_MSG_DISPLAY_MS)
    }

    private fun startCountdown321(onReady: () -> Unit) {
        val b = _binding ?: return
        b.tvBigCountdown.textSize = 120f   // "좋아요!"용 72sp로 바뀌었을 수 있어 카운트다운 크기 복원
        b.tvBigCountdown.visibility = View.VISIBLE; b.tvBigCountdown.text = "3"; ttsManager?.speak("삼")
        b.root.postDelayed({
            if (_binding == null || hasNavigated) return@postDelayed
            _binding?.tvBigCountdown?.text = "2"; ttsManager?.speak("이")
            _binding?.root?.postDelayed({
                if (_binding == null || hasNavigated) return@postDelayed
                _binding?.tvBigCountdown?.text = "1"; ttsManager?.speak("일")
                _binding?.root?.postDelayed({
                    if (_binding == null || hasNavigated) return@postDelayed
                    _binding?.tvBigCountdown?.text = "시작!"
                    ttsManager?.speak("시작!") {
                        _binding?.tvBigCountdown?.visibility = View.GONE
                        if (_binding != null && !hasNavigated) onReady()
                    }
                }, 1000L)
            }, 1000L)
        }, 1000L)
    }

    // -----------------------------------------------
    // User away / occlusion / front-facing monitor
    // -----------------------------------------------

    private fun startUserAwayMonitor() {
        userAwayCheckJob?.cancel()
        val nowInit = System.currentTimeMillis()
        lastValidFrameMs = nowInit; lastFullBodyMs = nowInit; lastSideViewMs = nowInit
        // 운동 본격 시작 전(전신 최초 감지 전)에는 이탈/가림 경고를 발동하지 않음.
        // 그 동안에는 "카메라 앞에 서주세요" 준비 안내만 표시하고, 전신이 잡히면 정상 모니터링 시작.
        bodyEverDetected = false
        var readyPromptShown = false
        val occlusionCheckEnabled = getCurrentExerciseId() != 1
        val sideFacingCheckEnabled = getCurrentExerciseId() !in SessionFlow.FRONT_EXERCISES
        userAwayCheckJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isAdded && !hasNavigated) {
                delay(500)
                if (isInGuidancePhase) continue
                // 전신이 한 번도 안 잡힌 상태 — 경고 대신 준비 안내만 (요청: 운동 시작 전 오작동/멈춤 방지)
                if (!bodyEverDetected) {
                    if (!readyPromptShown && !isPausedForUserAway && !isPausedForOcclusion && !isPausedForFrontFacing) {
                        readyPromptShown = true
                        _binding?.pauseOverlay?.visibility = View.VISIBLE
                        _binding?.tvPauseMessage?.text = "카메라 앞에 서주세요"
                    }
                    continue
                }
                // 전신이 처음 잡힘 → 준비 안내 제거 후 정상 모니터링
                if (readyPromptShown) {
                    readyPromptShown = false
                    _binding?.pauseOverlay?.visibility = View.GONE
                }
                if (isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) continue
                val now = System.currentTimeMillis()
                if (lastValidFrameMs > 0L && now - lastValidFrameMs > USER_AWAY_TIMEOUT_MS) onUserAway()
                else if (occlusionCheckEnabled && lastFullBodyMs > 0L && now - lastFullBodyMs > USER_AWAY_TIMEOUT_MS) onPartialOcclusion()
                else if (sideFacingCheckEnabled && lastSideViewMs > 0L && now - lastSideViewMs > FRONT_FACING_TIMEOUT_MS) onFrontFacing()
            }
        }
    }

    private fun onUserAway() {
        if (isInGuidancePhase) return
        if (isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) return
        isPausedForUserAway = true; viewModel.pauseForUserAway(); showPauseOverlay(USER_AWAY_MSG)
    }
    private fun onPartialOcclusion() {
        if (isInGuidancePhase) return
        if (isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) return
        isPausedForOcclusion = true; viewModel.pauseForUserAway(); showPauseOverlay(OCCLUSION_MSG)
    }
    private fun onFrontFacing() {
        if (isInGuidancePhase) return
        if (isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) return
        isPausedForFrontFacing = true; viewModel.pauseForUserAway(); showPauseOverlay(FRONT_FACING_MSG)
    }

    private fun showPauseOverlay(msg: String) {
        val b = _binding ?: return
        b.pauseOverlay.visibility = View.VISIBLE; b.tvPauseMessage.text = msg; ttsManager?.speak(msg)
        pauseAnnounceJob?.cancel()
        pauseAnnounceJob = viewLifecycleOwner.lifecycleScope.launch {
            while ((isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) && isAdded && !hasNavigated) {
                delay(PAUSE_TTS_LOOP_MS)
                if (isPausedForUserAway || isPausedForOcclusion || isPausedForFrontFacing) ttsManager?.speak(msg)
            }
        }
    }

    private fun onUserReturned() {
        if (!isPausedForUserAway || userReturnInProgress) return
        userReturnInProgress = true; pauseAnnounceJob?.cancel()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PAUSE_RESUME_BUFFER_MS)
            if (!isAdded || hasNavigated) { userReturnInProgress = false; return@launch }
            _binding?.pauseOverlay?.visibility = View.GONE
            ttsManager?.speak("다시 자세를 잡아주세요"); resumeWithCountdown(PauseKind.USER_AWAY)
        }
    }
    private fun onOcclusionCleared() {
        if (!isPausedForOcclusion || userReturnInProgress) return
        userReturnInProgress = true; pauseAnnounceJob?.cancel()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PAUSE_RESUME_BUFFER_MS)
            if (!isAdded || hasNavigated) { userReturnInProgress = false; return@launch }
            _binding?.pauseOverlay?.visibility = View.GONE
            ttsManager?.speak("전신이 잘 보입니다. 다시 잡아주세요"); resumeWithCountdown(PauseKind.OCCLUSION)
        }
    }
    private fun onSideViewReturned() {
        if (!isPausedForFrontFacing || userReturnInProgress) return
        userReturnInProgress = true; pauseAnnounceJob?.cancel()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(PAUSE_RESUME_BUFFER_MS)
            if (!isAdded || hasNavigated) { userReturnInProgress = false; return@launch }
            _binding?.pauseOverlay?.visibility = View.GONE
            ttsManager?.speak("좋아요. 다시 잡아주세요"); resumeWithCountdown(PauseKind.FRONT_FACING)
        }
    }

    private enum class PauseKind { USER_AWAY, OCCLUSION, FRONT_FACING }
    private fun resumeWithCountdown(kind: PauseKind) {
        startCountdown321 {
            if (_binding == null || hasNavigated) { userReturnInProgress = false; return@startCountdown321 }
            when (kind) { PauseKind.USER_AWAY -> isPausedForUserAway = false
                PauseKind.OCCLUSION -> isPausedForOcclusion = false
                PauseKind.FRONT_FACING -> isPausedForFrontFacing = false }
            viewModel.resumeFromUserAway()
            val now = System.currentTimeMillis()
            lastValidFrameMs = now; lastFullBodyMs = now; lastSideViewMs = now; userReturnInProgress = false
        }
    }

    // -----------------------------------------------
    // Camera helpers
    // -----------------------------------------------

    private fun bindCameraToSelector(provider: ProcessCameraProvider) {
        // 검사 화면과 동일하게 KioskCameraSelector 사용 — USB 웹캠(EXTERNAL) 우선, 없으면 가용 카메라로 폴백.
        // (기존 DEFAULT_FRONT_CAMERA 직접 사용 시, 웹캠이 전면이 아니면 "No available camera"로 카메라가 안 켜졌음)
        val selector = com.fallzero.app.util.KioskCameraSelector.select(isFrontCamera)
        try {
            provider.unbindAll()
            val preview = cameraPreview ?: return; val analyzer = cameraAnalyzer ?: return
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
        try { poseLandmarkerHelper?.clearPoseLandmarker(); poseLandmarkerHelper = null
            cameraProvider?.unbindAll(); cameraProvider = null } catch (e: Exception) { Log.e(TAG, "cleanup", e) }
    }

    // -----------------------------------------------
    // PoseLandmarkerHelper.LandmarkerListener
    // -----------------------------------------------

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        val result = resultBundle.results.firstOrNull() ?: return
        val landmarks = result.landmarks().firstOrNull() ?: return
        val now = System.currentTimeMillis(); lastValidFrameMs = now
        val fullBody = isFullBodyVisible(landmarks); if (fullBody) { lastFullBodyMs = now; bodyEverDetected = true }
        val isSideView = isSideFacing(landmarks); if (isSideView) lastSideViewMs = now
        if (isPausedForUserAway) onUserReturned()
        else if (isPausedForOcclusion && fullBody) onOcclusionCleared()
        else if (isPausedForFrontFacing && isSideView) onSideViewReturned()
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            if (!isAdded || hasNavigated) return@runOnUiThread
            b.poseOverlay.setResults(result, resultBundle.inputImageHeight, resultBundle.inputImageWidth)
            viewModel.processLandmarks(landmarks)
            if (showGuide) updateGuide(landmarks) else hideGuides()
        }
    }

    private fun updateGuide(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>) {
        val b = _binding ?: return
        // 안내영상 재생 중에는 진행도 막대를 띄우지 않는다 (영상 가림 방지 — 사용자 요청)
        if (isInGuidancePhase) { hideGuides(); return }
        when (val guide = viewModel.getGuide(landmarks)) {
            is com.fallzero.app.ui.overlay.ExerciseGuide.Bar -> {
                // 엔진이 주는 막대(방향 포함) 그대로 표시 — 가로 변환 취소(사용자 요청 복구)
                b.guideBar.visibility = View.VISIBLE; b.guideBubble.visibility = View.GONE; b.guideBar.setGuide(guide) }
            is com.fallzero.app.ui.overlay.ExerciseGuide.Bubble -> {
                b.guideBar.visibility = View.GONE; b.guideBubble.visibility = View.VISIBLE; b.guideBubble.setGuide(guide) }
            null -> hideGuides()
        }
    }

    private fun hideGuides() { _binding?.guideBar?.visibility = View.GONE; _binding?.guideBubble?.visibility = View.GONE }

    private fun isSideFacing(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Boolean {
        val sbu = com.fallzero.app.pose.SBUCalculator.calculate(landmarks)
        if (sbu <= 0f) return false
        val l = landmarks[com.fallzero.app.pose.AngleCalculator.LandmarkIndex.LEFT_SHOULDER]
        val r = landmarks[com.fallzero.app.pose.AngleCalculator.LandmarkIndex.RIGHT_SHOULDER]
        return (kotlin.math.abs(l.x() - r.x()) / sbu) < SIDE_VIEW_THRESHOLD
    }

    private fun isFullBodyVisible(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?): Boolean {
        if (landmarks == null || landmarks.size < 33) return false
        if (intArrayOf(0, 11, 12, 23, 24).count { landmarks[it].visibility().orElse(0f) > 0.3f } < 5) return false
        val noseY = landmarks[0].y(); val ankleY = maxOf(landmarks[27].y(), landmarks[28].y())
        return !(ankleY - noseY < 0.30f || ankleY - noseY > 0.97f || noseY < 0.02f)
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread; if (!isAdded) return@runOnUiThread
            b.tvErrorMessage.text = "MediaPipe 오류: $error"; b.tvErrorMessage.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        userAwayCheckJob?.cancel(); userAwayCheckJob = null
        pauseAnnounceJob?.cancel(); pauseAnnounceJob = null
        cleanupCamera()
        releaseGuidancePlayer()
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null; cameraExecutor?.shutdown(); cameraExecutor = null; _binding = null
    }


    companion object { private const val TAG = "ExerciseFragment" }
}