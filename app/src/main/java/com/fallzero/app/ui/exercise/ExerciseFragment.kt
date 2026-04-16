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

        // 의자 안내 (의자 사용 운동인 경우 1회 TTS)
        if (exerciseId in SessionFlow.CHAIR_REQUIRED_EXERCISES) {
            ttsManager?.speak("의자에 앉아주세요.")
        }

        observeViewModel()
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
                            b.tvExerciseName.text = state.exerciseName +
                                (state.bilateralSide?.let { " — $it" } ?: "")
                            // 카운트 디스플레이 리셋 (이전 좌측 카운트 잔상 제거)
                            b.tvCount.text = if (getCurrentExerciseId() == 8) "0초"
                                else getString(R.string.exercise_count_format, 0, state.targetCount)
                            b.tvErrorMessage.visibility = View.GONE
                            ttsManager?.speak(
                                if (state.bilateralSide == "오른쪽") "이제 오른쪽으로 해주세요."
                                else "운동을 시작합니다."
                            )
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
                                        ttsManager?.speak(state.errorMessage, flush = false)
                                    }
                                    else -> b.tvErrorMessage.visibility = View.GONE
                                }
                            } else {
                                b.tvCount.text = getString(
                                    R.string.exercise_count_format, state.count, state.targetCount
                                )
                                when {
                                    state.errorMessage != null -> {
                                        b.tvErrorMessage.text = state.errorMessage
                                        b.tvErrorMessage.visibility = View.VISIBLE
                                        ttsManager?.speak(state.errorMessage, flush = false)
                                    }
                                    state.isCoachingCue -> {
                                        b.tvErrorMessage.text = getString(R.string.exercise_coaching_cue)
                                        b.tvErrorMessage.visibility = View.VISIBLE
                                    }
                                    else -> b.tvErrorMessage.visibility = View.GONE
                                }
                            }
                            b.tvExerciseName.text = SessionFlow.exerciseName(getCurrentExerciseId()) +
                                (state.bilateralSide?.let { " — $it" } ?: "")
                        }
                        is ExerciseViewModel.ExerciseUiState.SideSwitch -> {
                            b.tvErrorMessage.visibility = View.VISIBLE
                            ttsManager?.speak("왼쪽 끝났어요. ${state.seconds}초 후 오른쪽으로 해주세요.")
                            sideSwitchTimer?.cancel()
                            sideSwitchTimer = object : CountDownTimer((state.seconds * 1000L) + 100L, 1000L) {
                                override fun onTick(msLeft: Long) {
                                    val s = (msLeft / 1000L).toInt().coerceAtLeast(0)
                                    _binding?.tvErrorMessage?.text = "오른쪽으로 준비해주세요 ($s)"
                                }
                                override fun onFinish() {
                                    _binding?.tvErrorMessage?.visibility = View.GONE
                                    viewModel.startRightSide()
                                }
                            }.start()
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
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            if (!isAdded || hasNavigated) return@runOnUiThread
            b.poseOverlay.setResults(result, resultBundle.inputImageHeight, resultBundle.inputImageWidth)
            b.poseOverlay.invalidate()
            viewModel.processLandmarks(landmarks)
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
