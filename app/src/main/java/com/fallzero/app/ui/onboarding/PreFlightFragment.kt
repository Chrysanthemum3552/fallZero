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
import com.fallzero.app.databinding.FragmentPreFlightBinding
import com.fallzero.app.pose.PoseLandmarkerHelper
import com.fallzero.app.util.TTSManager
import com.fallzero.app.util.TiltSensorHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 운동/검사 시작 전 사전 점검 단계.
 *
 * Phase 1: 핸드폰 직립 체크 (수평계 버블, 5° 이내 2초 지속 → 통과)
 * Phase 2: 카메라 켜고 전신 감지 (33 랜드마크 + 필수부위 visibility, 2초 지속 → 통과)
 *
 * 통과 시 자동으로 SessionFlow.advance() → 다음 단계로 네비게이션.
 * 사용자는 한 번도 화면을 터치하지 않습니다.
 */
class PreFlightFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentPreFlightBinding? = null
    private val binding get() = _binding!!

    private var tilt: TiltSensorHelper? = null
    private var ttsManager: TTSManager? = null
    private var poseHelper: PoseLandmarkerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null

    private enum class Phase { PHONE_LEVEL, BODY_DETECT }
    private var phase: Phase = Phase.PHONE_LEVEL

    private var phoneStableSinceMs = 0L
    private var bodyStableSinceMs = 0L
    private var hasAdvanced = false
    private var phoneOkAnnounced = false
    private var bodyOkAnnounced = false

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
        _binding = FragmentPreFlightBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hasAdvanced = false
        phase = Phase.PHONE_LEVEL

        // 설정의 "안내 스킵" ON이면 사전 점검 건너뛰고 즉시 다음 단계로 (테스트용)
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("skip_guidance", false)) {
            view.postDelayed({ if (_binding != null && !hasAdvanced) advanceToNextStep() }, 100L)
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        ttsManager = TTSManager.getInstance(requireContext())
        tilt = TiltSensorHelper(requireContext())
        // 캘리브레이션 카운터 리셋 (fragment 재진입 대비)
        phoneStableSinceMs = 0L
        bodyStableSinceMs = 0L
        phoneOkAnnounced = false
        bodyOkAnnounced = false

        val step = SessionFlow.current()
        binding.tvTitle.text = step.title.ifEmpty { "준비 단계" }

        // 의자 안내 (필요한 경우 onceshot)
        val subtitle = buildString {
            append(step.subtitle.ifEmpty { "핸드폰을 수직으로 세워주세요." })
            if (SessionFlow.requiresChair()) {
                append("\n\n이번 루틴은 의자가 필요합니다.\n카메라 앞에 의자를 두고 시작해주세요.\n의자가 다리를 일부 가려도 괜찮습니다.")
            }
        }
        binding.tvSubtitle.text = subtitle

        ttsManager?.speak(if (SessionFlow.requiresChair())
            "이번 루틴은 의자가 필요합니다. 카메라 앞에 의자를 두고 핸드폰을 수직으로 세워주세요."
        else "핸드폰을 수직으로 세워주세요.")

        // 사용자 이전 설정 복원
        isFrontCamera = com.fallzero.app.util.CameraFacingPref.isFrontCamera(requireContext())
        binding.btnCameraFlip.setOnClickListener { toggleCameraFacing() }

        startPhoneLevelPhase()
    }

    // ────────── Phase 1: Phone level ──────────
    private fun startPhoneLevelPhase() {
        binding.sectionPhone.visibility = View.VISIBLE
        binding.sectionCamera.visibility = View.GONE
        showStatus("핸드폰을 수직으로 세워주세요")

        tilt?.startListening { tiltDeg, isOk ->
            val b = _binding ?: return@startListening
            if (phase != Phase.PHONE_LEVEL) return@startListening
            b.phoneLevel.setTilt(tiltDeg, isOk)

            if (isOk) {
                if (phoneStableSinceMs == 0L) phoneStableSinceMs = System.currentTimeMillis()
                val held = System.currentTimeMillis() - phoneStableSinceMs
                if (held >= STABLE_DURATION_MS) {
                    // 안내 + 다음 단계 스케줄은 1회만
                    if (!phoneOkAnnounced) {
                        phoneOkAnnounced = true
                        ttsManager?.speak("좋아요! 핸드폰이 잘 세팅되었어요.")
                        b.tvPhoneHint.text = "좋아요! 잘 세팅되었어요"
                        b.tvPhoneHint.setTextColor(0xFF4CAF50.toInt())
                        showStatus("좋아요! 잘 세팅되었어요")
                        b.root.postDelayed({
                            if (_binding != null && phase == Phase.PHONE_LEVEL) advanceToBodyPhase()
                        }, 1000L)
                    }
                } else {
                    val remain = ((STABLE_DURATION_MS - held) / 1000) + 1
                    showStatus("좋아요! ${remain}초만 유지해주세요")
                }
            } else {
                // 안정 상태에서 다시 흔들렸으면 카운터 리셋 (안내는 아직 안 했을 때만)
                phoneStableSinceMs = 0L
                if (!phoneOkAnnounced) {
                    b.tvPhoneHint.text = "핸드폰을 수직으로 세워주세요 (현재 ${tiltDeg.toInt()}°)"
                    b.tvPhoneHint.setTextColor(0xFFFF9800.toInt())
                    showStatus("핸드폰을 수직으로 세워주세요")
                }
            }
        }
    }

    // ────────── Phase 2: Body detection ──────────
    private fun advanceToBodyPhase() {
        if (phase != Phase.PHONE_LEVEL) return
        phase = Phase.BODY_DETECT
        tilt?.stopListening()
        binding.sectionPhone.visibility = View.GONE
        binding.sectionCamera.visibility = View.VISIBLE
        showStatus("카메라 앞에 전신이 보이도록 서주세요")
        ttsManager?.speak("이제 카메라 앞에 전신이 보이도록 서주세요.")

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
        catch (e: Exception) { Log.e(TAG, "pose helper init failed", e); return }

        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            if (_binding == null) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val analyzer = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
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
                Log.e(TAG, "camera bind failed", e)
                showStatus("카메라 오류: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        val result = resultBundle.results.firstOrNull() ?: return
        val landmarks = result.landmarks().firstOrNull()
        activity?.runOnUiThread {
            val b = _binding ?: return@runOnUiThread
            if (!isAdded || hasAdvanced || phase != Phase.BODY_DETECT) return@runOnUiThread

            b.poseOverlay.setResults(result, resultBundle.inputImageHeight, resultBundle.inputImageWidth)
            b.poseOverlay.invalidate()

            val ok = isFullBodyVisible(landmarks)
            if (ok) {
                if (bodyStableSinceMs == 0L) bodyStableSinceMs = System.currentTimeMillis()
                val held = System.currentTimeMillis() - bodyStableSinceMs
                if (held >= STABLE_DURATION_MS) {
                    // 안내 + 다음 단계 스케줄은 1회만
                    if (!bodyOkAnnounced) {
                        bodyOkAnnounced = true
                        showStatus("좋아요! 잘 감지되었어요")
                        // TTS 콜백으로 정확한 완료 시점에 다음 단계 진행
                        ttsManager?.speak("좋아요! 전신이 잘 보입니다. 곧 검사를 시작합니다.") {
                            if (_binding != null && !hasAdvanced) advanceToNextStep()
                        }
                    }
                } else {
                    val remain = ((STABLE_DURATION_MS - held) / 1000) + 1
                    showStatus("좋아요! ${remain}초만 그대로 있어주세요")
                }
            } else {
                bodyStableSinceMs = 0L
                if (!bodyOkAnnounced) {
                    showStatus("전신이 보이도록 카메라 앞에 서주세요")
                }
            }
        }
    }

    private fun isFullBodyVisible(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?
    ): Boolean {
        if (landmarks == null || landmarks.size < 33) return false

        // ─── 상반신 5개 (nose, shoulders, hips) — 의자가 가리면 안 되는 부위. 엄격하게. ───
        val upperKeyIdx = intArrayOf(0, 11, 12, 23, 24)
        val upperVisible = upperKeyIdx.count { landmarks[it].visibility().orElse(0f) > 0.3f }
        if (upperVisible < 5) return false

        // ─── 하반신 (knees, ankles) — visibility 검증 생략. ───
        // 의자 등받이가 무릎/발목을 가려 visibility가 매우 낮아져도 MediaPipe는 추정 좌표를 제공.
        // 그 추정 좌표로 span 검증만 수행. 의자 활용 시나리오 지원의 핵심.
        val noseY = landmarks[0].y()
        val ankleY = maxOf(landmarks[27].y(), landmarks[28].y())
        val span = ankleY - noseY
        // 전신이 화면의 30~97% (의자에 가려진 ankle 추정값은 약간 위쪽일 수 있어 30%까지 허용)
        if (span < 0.30f || span > 0.97f) return false
        if (noseY < 0.02f) return false
        return true
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread { showStatus("카메라 오류: $error") }
    }

    private fun advanceToNextStep() {
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

    /** @deprecated TTSManager.speak(text, onDone) callback 사용 권장 */
    @Suppress("unused")
    private fun waitForTtsFinishPre(onDone: () -> Unit) {
        _binding?.root?.postDelayed({
            if (_binding == null || hasAdvanced) return@postDelayed
            @Suppress("DEPRECATION")
            if (ttsManager?.isSpeaking() == true) {
                waitForTtsFinishPre(onDone)
            } else {
                onDone()
            }
        }, 500L)
    }

    private fun showStatus(msg: String) {
        _binding?.tvStatus?.text = msg
    }

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
        try { tilt?.stopListening() } catch (_: Exception) {}
        tilt = null
        cleanupCamera()
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
        _binding = null
    }

    companion object {
        private const val TAG = "PreFlightFragment"
        private const val STABLE_DURATION_MS = 2000L
    }
}
