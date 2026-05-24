package com.fallzero.app.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
 * 온보딩 2단계: 나이. 60 미만도 받음 (사용자 명시).
 *  - STEADI 점수 계산은 STEADIScorer에서 max(age, 60)으로 변환 — 여기선 실제 나이 그대로 저장.
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

        navigated = false
        ttsManager = TTSManager.getInstance(requireContext())
        voiceHelper = VoiceInputHelper(requireContext())

        // 이전 답변 복원
        if (viewModel.tempAge in MIN_AGE..MAX_AGE) {
            currentAge = viewModel.tempAge
            binding.tvAgeDisplay.text = "${viewModel.tempAge}"
        } else {
            binding.tvAgeDisplay.text = "—"
        }

        binding.tvVoiceStatus.visibility = View.INVISIBLE
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        // 숫자 칸을 터치하면 키패드로 직접 입력 (값만 채움, 진행은 "입력" 버튼)
        binding.tvAgeDisplay.setOnClickListener { showManualInputDialog() }
        // 최종 확정 버튼 — 현재 표시된 나이로 다음 화면 진행
        binding.btnManualInput.setOnClickListener { confirmCurrentAge() }

        viewLifecycleOwner.lifecycleScope.launch {
            delay(600)
            if (_binding == null) return@launch
            ttsManager?.speak("연령을 말씀하세요") {
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
        val anim = android.animation.ObjectAnimator.ofFloat(view, "alpha", 1f, 0.55f).apply {
            duration = 500L
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
            ttsManager?.speak("연령을 다시 말씀하세요") {
                if (_binding != null && !navigated) startVoiceListening()
            }
        }
    }

    /** 한국어 숫자 → 정수 변환 (예: "칠십" → 70, "여든다섯" → 85).
     *  사용자 명시: 60 미만도 허용 → 20~50대 추가. */
    private fun parseKoreanAge(text: String): Int? {
        val tens = mapOf(
            "스물" to 20, "이십" to 20,
            "서른" to 30, "삼십" to 30,
            "마흔" to 40, "사십" to 40,
            "쉰" to 50, "오십" to 50,
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

    /** 숫자 칸 터치 시 키패드 입력 — 값만 채우고 진행은 "입력" 버튼에서 한다. */
    private fun showManualInputDialog() {
        voiceHelper?.stop()
        stopBlinking()
        val dialogView = layoutInflater.inflate(R.layout.dialog_age_input, null)
        val edit = dialogView.findViewById<EditText>(R.id.et_age).apply {
            setText(currentAge?.toString() ?: "")
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_FallZero_Dialog)
            .setView(dialogView)
            .setPositiveButton("확인") { _, _ ->
                val age = edit.text.toString().toIntOrNull()
                if (age != null && age in MIN_AGE..MAX_AGE) {
                    setAge(age)  // 화면에 표시만 — 확정은 "입력" 버튼
                } else {
                    Toast.makeText(requireContext(),
                        "1세에서 ${MAX_AGE}세 사이로 입력해주세요",
                        Toast.LENGTH_SHORT).show()
                    if (_binding != null) startVoiceListening()
                }
            }
            .setNegativeButton("취소") { _, _ ->
                if (_binding != null) startVoiceListening()
            }
            .show()
    }

    /** 입력된 나이를 화면에 표시만 (진행 X). 음성/터치 입력 공통. */
    private fun setAge(age: Int) {
        currentAge = age
        viewModel.tempAge = age
        _binding?.tvAgeDisplay?.text = "$age"
    }

    /** "입력" 버튼 — 현재 표시된 나이로 최종 확정 후 다음 화면. */
    private fun confirmCurrentAge() {
        val age = currentAge
        if (age != null && age in MIN_AGE..MAX_AGE) {
            confirmAge(age)
        } else {
            Toast.makeText(requireContext(),
                "나이를 먼저 입력해주세요",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmAge(age: Int) {
        if (navigated) return
        navigated = true
        voiceHelper?.stop()
        stopBlinking()
        currentAge = age
        viewModel.tempAge = age
        _binding?.tvAgeDisplay?.text = "$age"
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
        // 사용자 명시: 60 미만도 허용. STEADI 점수 계산은 STEADIScorer에서 max(age, 60)으로 처리.
        // 비현실적 값(0세, 200세 등)만 막기 위해 1~110 범위.
        private const val MIN_AGE = 1
        private const val MAX_AGE = 110
    }
}