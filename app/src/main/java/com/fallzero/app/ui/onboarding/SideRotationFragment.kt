package com.fallzero.app.ui.onboarding

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
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentSideRotationBinding
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.PoseLandmarkerHelper
import com.fallzero.app.pose.SBUCalculator
import com.fallzero.app.util.TTSManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 정면 → 측면 전환 검사. 사용자가 옆으로 90° 돌면 자동으로 다음 단계.
 *
 * 측면 감지 로직: 어깨 너비(|x_LSh - x_RSh|)와 골반 너비(|x_LHip - x_RHip|)가
 * SBU의 30% 이하면 옆으로 돌아선 것으로 판단. 1.5초 지속 시 통과.
 */
class SideRotationFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentSideRotationBinding? = null
    private val binding get() = _binding!!

    private var poseHelper: PoseLandmarkerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var ttsManager: TTSManager? = null
    private var hasAdvanced = false
    private var sideStableSinceMs = 0L
    private var announced = false

    @Volatile private var isFrontCamera: Boolean = false
    private var cameraPreview: Preview? = null
    private var cameraAnalyzer: ImageAnalysis? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else showStatus("카메라 권한이 필요합니다") }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSideRotationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hasAdvanced = false
        sideStableSinceMs = 0L
        announced = false

        // 설정의 "안내 스킵" ON이면 옆돌기 검사 건너뛰고 즉시 다음 단계로 (테스트용)
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("skip_guidance", false)) {
            view.postDelayed({ if (_binding != null && !hasAdvanced) advanceNext() }, 100L)
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        ttsManager = TTSManager.getInstance(requireContext())

        val step = SessionFlow.current()
        binding.tvTitle.text = step.title.ifEmpty { "옆으로 돌아주세요" }
        binding.tvStatus.text = step.subtitle.ifEmpty {
            "카메라가 옆모습을 볼 수 있게 옆으로 90도 돌아주세요"
        }

        // SessionFlow의 subtitle을 TTS로 읽기 (검사 시 의자 안내 포함)
        val ttsText = step.subtitle.replace("\n", " ").ifEmpty {
            "이제 측면 운동입니다. 옆으로 90도 돌아주세요."
        }
        ttsManager?.speak(ttsText)

        // 사용자 이전 설정 복원
        isFrontCamera = com.fallzero.app.util.CameraFacingPref.isFrontCamera(requireContext())
        binding.btnCameraFlip.setOnClickListener { toggleCameraFacing() }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        if (_binding == null) return
        try { poseHelper = PoseLandmarkerHelper(requireContext(), this) }
        catch (e: Exception) { Log.e(TAG, "init failed", e); return }

        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            if (_binding == null) return@addListener
            try {
                val provider = future.get()
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
                            poseHelper?.detectLiveStream(proxy, isFrontCamera = isFrontCamera)
                                ?: proxy.close()
                        }
                    }
                cameraPreview = preview
                cameraAnalyzer = analyzer
                bindCameraToSelector(provider)
            } catch (e: Exception) {
                Log.e(TAG, "camera bind", e)
                showStatus("카메라 오류")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        val result = resultBundle.results.firstOrNull() ?: return
        val landmarks = result.landmarks().firstOrNull()
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            if (!isAdded || hasAdvanced) return@runOnUiThread
            b.poseOverlay.setResults(result, resultBundle.inputImageHeight, resultBundle.inputImageWidth)
            b.poseOverlay.invalidate()

            if (landmarks == null || landmarks.size < 33) {
                sideStableSinceMs = 0L
                showStatus("카메라 앞에 서주세요")
                return@runOnUiThread
            }

            val sbu = SBUCalculator.calculate(landmarks)
            if (sbu <= 0f) return@runOnUiThread

            val shoulderWidth = abs(
                landmarks[LandmarkIndex.LEFT_SHOULDER].x() -
                landmarks[LandmarkIndex.RIGHT_SHOULDER].x()
            )
            val hipWidth = abs(
                landmarks[LandmarkIndex.LEFT_HIP].x() -
                landmarks[LandmarkIndex.RIGHT_HIP].x()
            )
            val maxWidth = maxOf(shoulderWidth, hipWidth)
            val widthRatio = maxWidth / sbu

            // 정면일 때 어깨너비 ≈ SBU와 비슷, 옆으로 돌면 0.3 이하
            val isSide = widthRatio <= SIDE_THRESHOLD

            if (isSide) {
                if (sideStableSinceMs == 0L) sideStableSinceMs = System.currentTimeMillis()
                val held = System.currentTimeMillis() - sideStableSinceMs
                if (held >= STABLE_DURATION_MS) {
                    // 안내 + 다음 단계 스케줄은 1회만
                    if (!announced) {
                        announced = true
                        // TTS 짧게 ("좋아요") + 700ms postDelayed에 cut off 안 되도록.
                        // 화면 텍스트는 더 자세히 표시 가능 (음성과 별개).
                        ttsManager?.speak("좋아요.")
                        showStatus("좋아요! 잘 돌았어요")
                        b.root.postDelayed({
                            if (_binding != null && !hasAdvanced) advanceNext()
                        }, 700L)
                    }
                } else {
                    val remain = ((STABLE_DURATION_MS - held) / 1000) + 1
                    showStatus("${remain}초만 그대로 있어주세요")
                }
            } else {
                sideStableSinceMs = 0L
                if (!announced) {
                    showStatus("옆으로 90도 돌아주세요")
                }
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread { showStatus("카메라 오류") }
    }

    private fun advanceNext() {
        if (hasAdvanced) return
        hasAdvanced = true
        cleanupCamera()
        val next = SessionFlow.advance()
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
            SessionFlow.StepType.DONE -> nav.navigate(R.id.action_global_home)
            SessionFlow.StepType.PRE_FLIGHT -> nav.navigate(R.id.action_global_preflight)
        }
    }

    private fun showStatus(msg: String) { _binding?.tvStatus?.text = msg }

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
            poseHelper?.clearPoseLandmarker()
            poseHelper = null
            cameraProvider?.unbindAll()
            cameraProvider = null
        } catch (e: Exception) { Log.e(TAG, "cleanup", e) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleanupCamera()
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
        _binding = null
    }

    companion object {
        private const val TAG = "SideRotationFragment"
        private const val SIDE_THRESHOLD = 0.30f
        private const val STABLE_DURATION_MS = 1500L
    }
}
