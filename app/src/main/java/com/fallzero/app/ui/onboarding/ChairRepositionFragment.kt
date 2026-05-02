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
import com.fallzero.app.databinding.FragmentChairRepositionBinding
import com.fallzero.app.pose.AngleCalculator
import com.fallzero.app.pose.AngleCalculator.LandmarkIndex
import com.fallzero.app.pose.PoseLandmarkerHelper
import com.fallzero.app.pose.SBUCalculator
import com.fallzero.app.util.TTSManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 측면 운동 도중 의자에 앉아야 하는 단계(#1 앉아서 무릎 펴기) 직전 자세 유도.
 *
 * 자동 감지 로직:
 *   1. 측면 자세: 어깨/골반 너비가 SBU의 30% 이하 (= 옆으로 돌아 있음)
 *   2. 앉은 자세: 가시성 높은 쪽 무릎 각도(hip-knee-ankle) ≤ 130° (= 무릎 굽혀 앉음)
 *   두 조건 모두 1.5초 지속 시 통과 → SessionFlow.advance().
 */
class ChairRepositionFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentChairRepositionBinding? = null
    private val binding get() = _binding!!

    private var poseHelper: PoseLandmarkerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var ttsManager: TTSManager? = null
    private var hasAdvanced = false
    private var stableSinceMs = 0L
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
        _binding = FragmentChairRepositionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hasAdvanced = false
        stableSinceMs = 0L
        announced = false

        // 설정의 "안내 스킵" ON이면 의자 자세 검사 건너뛰고 즉시 다음 단계로 (테스트용)
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("skip_guidance", false)) {
            view.postDelayed({ if (_binding != null && !hasAdvanced) advanceNext() }, 100L)
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        ttsManager = TTSManager.getInstance(requireContext())

        val step = SessionFlow.current()
        binding.tvTitle.text = step.title.ifEmpty { "의자에 앉아주세요" }
        binding.tvStatus.text = step.subtitle.ifEmpty {
            "옆모습으로 의자에 앉아주세요"
        }

        val ttsText = step.subtitle.replace("\n", " ").ifEmpty {
            "이번엔 옆모습으로 의자에 앉으셔야 합니다. 카메라 옆에 의자를 두고 앉아주세요."
        }
        ttsManager?.speak(ttsText)

        // 사용자 이전 카메라 설정 복원
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
            // setResults 내부에서 invalidate() 호출 — 중복 호출 제거
            b.poseOverlay.setResults(result, resultBundle.inputImageHeight, resultBundle.inputImageWidth)

            if (landmarks == null || landmarks.size < 33) {
                stableSinceMs = 0L
                showStatus("카메라 앞에 앉아주세요")
                return@runOnUiThread
            }

            val sbu = SBUCalculator.calculate(landmarks)
            if (sbu <= 0f) return@runOnUiThread

            // 1. 측면 자세 검증 (어깨/골반 너비 ≤ 30% SBU)
            val shoulderWidth = abs(
                landmarks[LandmarkIndex.LEFT_SHOULDER].x() -
                landmarks[LandmarkIndex.RIGHT_SHOULDER].x()
            )
            val hipWidth = abs(
                landmarks[LandmarkIndex.LEFT_HIP].x() -
                landmarks[LandmarkIndex.RIGHT_HIP].x()
            )
            val widthRatio = maxOf(shoulderWidth, hipWidth) / sbu
            val isSide = widthRatio <= SIDE_THRESHOLD

            // 2. 앉은 자세 검증 (무릎 각도 ≤ 130°)
            val side = AngleCalculator.pickVisibleSide(landmarks,
                LandmarkIndex.LEFT_KNEE, LandmarkIndex.RIGHT_KNEE)
            val kneeAngle = if (side != null) {
                val hipIdx = if (side == AngleCalculator.Side.LEFT) LandmarkIndex.LEFT_HIP else LandmarkIndex.RIGHT_HIP
                val kneeIdx = if (side == AngleCalculator.Side.LEFT) LandmarkIndex.LEFT_KNEE else LandmarkIndex.RIGHT_KNEE
                val ankleIdx = if (side == AngleCalculator.Side.LEFT) LandmarkIndex.LEFT_ANKLE else LandmarkIndex.RIGHT_ANKLE
                AngleCalculator.calculateAngle(landmarks[hipIdx], landmarks[kneeIdx], landmarks[ankleIdx])
            } else 180f
            val isSitting = kneeAngle <= SITTING_KNEE_ANGLE

            val ok = isSide && isSitting

            if (ok) {
                if (stableSinceMs == 0L) stableSinceMs = System.currentTimeMillis()
                val held = System.currentTimeMillis() - stableSinceMs
                if (held >= STABLE_DURATION_MS) {
                    if (!announced) {
                        announced = true
                        ttsManager?.speak("좋아요. 잘 앉으셨어요.")
                        showStatus("좋아요! 잘 앉으셨어요")
                        b.root.postDelayed({
                            if (_binding != null && !hasAdvanced) advanceNext()
                        }, 700L)
                    }
                } else {
                    val remain = ((STABLE_DURATION_MS - held) / 1000) + 1
                    showStatus("${remain}초만 그대로 있어주세요")
                }
            } else {
                stableSinceMs = 0L
                if (!announced) {
                    showStatus(when {
                        !isSide -> "옆모습이 보이도록 90도 돌아주세요"
                        !isSitting -> "의자에 앉아주세요"
                        else -> "옆으로 의자에 앉아주세요"
                    })
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
        private const val TAG = "ChairRepositionFragment"
        private const val SIDE_THRESHOLD = 0.30f
        private const val SITTING_KNEE_ANGLE = 130f
        private const val STABLE_DURATION_MS = 1500L
    }
}
