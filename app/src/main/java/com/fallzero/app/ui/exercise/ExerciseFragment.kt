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

    private var userAwayCheckJob: Job? = null
    private var pauseAnnounceJob: Job? = null
    private val USER_AWAY_MSG = "카메라 앞으로 와주세요"
    private val OCCLUSION_MSG = "신체의 일부가 보이지 않습니다. 조금 더 뒤로 가주세요"
    private val FRONT_FACING_MSG = "옆으로 돌아주세요"
    private val SIDE_VIEW_THRESHOLD = 0.50f

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
            binding.root.postDelayed({
                if (_binding != null && !hasNavigated) {
                    lastSpokenCount = -1; viewModel.startMeasurement(); startUserAwayMonitor()
                }
            }, 800L)
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

    // pause 지점 정의 (수정된 영상 기준)
    // 각 freeze 구간 시작점 = pause 지점
    // freeze 길이 = TTS 발화 시간에 맞게 조정됨
    // TTS 완료 -> resume -> 즉시 동작 시작
    private fun getPausePoints(exerciseId: Int): List<Long> = when (exerciseId) {
        1 -> listOf(0L, 5057L, 15523L)
        2 -> listOf(0L, 8567L, 21267L)
        3 -> listOf(0L, 4600L, 21495L)
        4 -> listOf(0L, 5500L)
        5 -> listOf(0L, 5167L)
        6 -> listOf(0L, 8285L)
        7 -> listOf(0L, 10275L)
        else -> listOf(0L)
    }

    // pause 지점 폴링 (50ms 간격)
    private val pauseCheckRunnable = object : Runnable {
        override fun run() {
            val player = guidancePlayer ?: return
            if (isGuidancePaused) return
            if (!player.isPlaying) { pauseCheckHandler.postDelayed(this, 50L); return }
            val pos = player.currentPosition.toLong()
            val idx = guidanceLineIndex
            if (idx < guidancePausePoints.size && pos >= guidancePausePoints[idx]) {
                player.pause()
                isGuidancePaused = true
                showSubtitleAndSpeak(idx)
                return
            }
            pauseCheckHandler.postDelayed(this, 50L)
        }
    }

    private fun showSubtitleAndSpeak(idx: Int) {
        if (idx >= guidanceLines.size) { resumeGuidanceVideo(); return }
        val text = guidanceLines[idx]
        _binding?.tvGuideLineText?.text = text
        _binding?.guideTextOverlay?.visibility = View.VISIBLE
        ttsManager?.speak(text.replace("\n", " ")) {
            if (_binding == null || hasNavigated) return@speak
            _binding?.guideTextOverlay?.visibility = View.GONE
            guidanceLineIndex++
            resumeGuidanceVideo()
        }
    }

    private fun resumeGuidanceVideo() {
        val player = guidancePlayer ?: return
        isGuidancePaused = false
        if (!player.isPlaying) player.start()
        if (guidanceLineIndex < guidancePausePoints.size) {
            pauseCheckHandler.post(pauseCheckRunnable)
        }
    }

    // [수정] ExamFragment의 showBalanceGuidance 패턴과 동일하게:
    //        TTS("안내 영상을 보여드릴게요") 완료 후 영상 시작
    //        -> 나레이션이 끝난 뒤 영상이 시작되므로 동시 재생 문제 없음
    private fun showStartGuidance(exerciseId: Int) {
        val b = _binding ?: return
        isInGuidancePhase = true
        b.guidanceOverlay.visibility = View.VISIBLE
        b.guideTextOverlay.visibility = View.GONE
        b.videoExerciseGuide.visibility = View.GONE  // 줄1 TTS 중에는 영상 숨김

        val allLines = getInstructionLines(exerciseId)
        guidanceLines = if (allLines.size > 1) allLines.subList(1, allLines.size) else emptyList()
        guidancePausePoints = getPausePoints(exerciseId)
        guidanceLineIndex = 0
        isGuidancePaused = false

        // 줄1: 영상 없이 TTS만 먼저 발화
        val firstLine = allLines.getOrNull(0) ?: ""
        ttsManager?.speak(firstLine.replace("\n", " ")) {
            if (_binding == null || hasNavigated) return@speak
            _binding?.videoExerciseGuide?.visibility = View.VISIBLE
            startGuidanceVideo(exerciseId, guidanceLines)
        }
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
                mp.setOnPreparedListener { player ->
                    player.start()
                    pauseCheckHandler.post(pauseCheckRunnable)
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
        val script = if (exerciseId == 8) {
            val prefs = requireActivity()
                .getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
            balanceGuidanceText(prefs.getInt("current_set_level", 1))
        } else {
            getString(guidanceScriptRes(exerciseId))
        }
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
        8 -> R.raw.balance_guide_4
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
        ttsManager?.speak("좋아요! 이제 본격적으로 시작할게요.") {
            if (_binding == null || hasNavigated) return@speak
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
                                val transientMsg = state.errorMessage ?: state.coachingCueMessage
                                if (transientMsg != null) showTransientMessage(transientMsg)
                                else if (b.tvErrorMessage.visibility == View.GONE) {
                                    b.tvErrorMessage.text = "✓ 잘 하고 있어요!"
                                    b.tvErrorMessage.setTextColor(0xFF4CAF50.toInt())
                                    b.tvErrorMessage.visibility = View.VISIBLE
                                }
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
        val occlusionCheckEnabled = getCurrentExerciseId() != 1
        val sideFacingCheckEnabled = getCurrentExerciseId() !in SessionFlow.FRONT_EXERCISES
        userAwayCheckJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isAdded && !hasNavigated) {
                delay(500)
                if (isInGuidancePhase) continue
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
        val selector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            val preview = cameraPreview ?: return; val analyzer = cameraAnalyzer ?: return
            provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analyzer)
        } catch (e: Exception) { Log.e(TAG, "bindCameraToSelector failed", e) }
    }

    private fun toggleCameraFacing() {
        isFrontCamera = !isFrontCamera
        com.fallzero.app.util.CameraFacingPref.setFrontCamera(requireContext(), isFrontCamera)
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
        val fullBody = isFullBodyVisible(landmarks); if (fullBody) lastFullBodyMs = now
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
        when (val guide = viewModel.getGuide(landmarks)) {
            is com.fallzero.app.ui.overlay.ExerciseGuide.Bar -> {
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