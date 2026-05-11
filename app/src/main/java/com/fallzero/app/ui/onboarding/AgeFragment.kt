package com.fallzero.app.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.databinding.FragmentAgeBinding
import com.fallzero.app.util.TTSManager
import com.fallzero.app.util.VoiceInputHelper
import com.fallzero.app.viewmodel.OnboardingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 온보딩 2단계: 나이. STEADI 기준 최소 60세.
 *  - 화면 진입 시 음성 인식 자동 시작 (예: "칠십" → 70)
 *  - "직접 입력" 버튼 → 숫자 키패드 다이얼로그
 *  - 큰 숫자 디스플레이로 현재 입력값 표시. 미입력 시 "—" placeholder.
 */
class AgeFragment : Fragment() {

    private var _binding: FragmentAgeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by activityViewModels()

    private var ttsManager: TTSManager? = null
    private var voiceHelper: VoiceInputHelper? = null
    private var navigated = false
    private var currentAge: Int? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startVoiceListening() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentAgeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // popBackStack 후 재진입 시에도 다시 답변 가능
        navigated = false

        ttsManager = TTSManager.getInstance(requireContext())
        voiceHelper = VoiceInputHelper(requireContext())

        // 이전 답변 복원
        if (viewModel.tempAge in MIN_AGE..MAX_AGE) {
            currentAge = viewModel.tempAge
            binding.tvAgeDisplay.text = "${viewModel.tempAge}"
            binding.btnNext.isEnabled = true
        } else {
            binding.tvAgeDisplay.text = "—"
            binding.btnNext.isEnabled = false
        }

        binding.tvVoiceStatus.visibility = View.INVISIBLE

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnManualInput.setOnClickListener { showManualInputDialog() }
        binding.btnNext.setOnClickListener {
            currentAge?.let { confirmAge(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            delay(600)
            if (_binding == null) return@launch
            ttsManager?.speak("나이를 말씀해 주세요. 만 ${MIN_AGE}세 이상") {
                if (_binding != null && !navigated) requestVoiceInput()
            }
        }
    }

    private fun requestVoiceInput() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceListening()
    }

    private fun startVoiceListening() {
        binding.tvVoiceStatus.visibility = View.INVISIBLE
        voiceHelper?.start(
            onResult = { text -> handleVoiceResult(text) },
            onError = {
                stopBlinking()
                _binding?.root?.postDelayed({
                    if (_binding != null && !navigated) startVoiceListening()
                }, 3000L)
            },
            onReady = {
                _binding?.tvVoiceStatus?.let {
                    it.visibility = View.VISIBLE
                    startBlinking(it)
                }
            }
        )
    }

    private fun startBlinking(view: View) {
        view.animate().cancel()
        val anim = android.animation.ObjectAnimator.ofFloat(view, "alpha", 1f, 0.3f).apply {
            duration = 800L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
        }
        view.tag = anim
        anim.start()
    }

    private fun stopBlinking() {
        val view = _binding?.tvVoiceStatus ?: return
        (view.tag as? android.animation.ObjectAnimator)?.cancel()
        view.tag = null
        view.alpha = 1f
        view.visibility = View.INVISIBLE
    }

    private fun handleVoiceResult(text: String) {
        val age = parseKoreanAge(text) ?: text.replace(Regex("[^0-9]"), "").toIntOrNull()
        if (age != null && age in MIN_AGE..MAX_AGE) {
            confirmAge(age)
        } else {
            ttsManager?.speak("나이를 다시 말씀해 주세요. 숫자로 ${MIN_AGE}세 이상") {
                if (_binding != null && !navigated) startVoiceListening()
            }
        }
    }

    /** 한국어 숫자 → 정수 변환 (예: "칠십" → 70, "여든다섯" → 85) */
    private fun parseKoreanAge(text: String): Int? {
        val tens = mapOf(
            "예순" to 60, "육십" to 60,
            "일흔" to 70, "칠십" to 70,
            "여든" to 80, "팔십" to 80,
            "아흔" to 90, "구십" to 90,
            "백" to 100
        )
        val ones = mapOf(
            "하나" to 1, "일" to 1,
            "둘" to 2, "이" to 2,
            "셋" to 3, "삼" to 3,
            "넷" to 4, "사" to 4,
            "다섯" to 5, "오" to 5,
            "여섯" to 6, "육" to 6,
            "일곱" to 7, "칠" to 7,
            "여덟" to 8, "팔" to 8,
            "아홉" to 9, "구" to 9
        )
        for ((tenWord, tenVal) in tens) {
            if (text.contains(tenWord)) {
                val rest = text.substringAfter(tenWord)
                val oneVal = ones.entries.firstOrNull { rest.contains(it.key) }?.value ?: 0
                return tenVal + oneVal
            }
        }
        return null
    }

    private fun showManualInputDialog() {
        voiceHelper?.stop()
        val edit = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "예: 70"
            textSize = 32f
            setText(currentAge?.toString() ?: "")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("나이 입력")
            .setMessage("만 ${MIN_AGE}세 이상 ${MAX_AGE}세 이하")
            .setView(edit)
            .setPositiveButton("확인") { _, _ ->
                val age = edit.text.toString().toIntOrNull()
                if (age != null && age in MIN_AGE..MAX_AGE) {
                    confirmAge(age)
                } else {
                    Toast.makeText(requireContext(),
                        "${MIN_AGE}세에서 ${MAX_AGE}세 사이로 입력해주세요",
                        Toast.LENGTH_SHORT).show()
                    if (_binding != null) startVoiceListening()
                }
            }
            .setNegativeButton("취소") { _, _ ->
                if (_binding != null) startVoiceListening()
            }
            .show()
    }

    private fun confirmAge(age: Int) {
        if (navigated) return
        navigated = true
        voiceHelper?.stop()
        stopBlinking()
        currentAge = age
        viewModel.tempAge = age
        _binding?.tvAgeDisplay?.text = "$age"
        // TTS 끝나면 navigate (cut off 방지)
        ttsManager?.speak("${age}세로 입력했습니다") {
            if (_binding != null) findNavController().navigate(R.id.action_age_to_q1)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceHelper?.destroy()
        voiceHelper = null
        ttsManager?.shutdown()
        ttsManager = null
        _binding = null
    }

    companion object {
        // STEADI 기준: chairStandNorms 60..94. 입력 범위 60~110.
        private const val MIN_AGE = 60
        private const val MAX_AGE = 110
    }
}
