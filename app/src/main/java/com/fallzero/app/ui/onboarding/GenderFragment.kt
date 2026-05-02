package com.fallzero.app.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.databinding.FragmentGenderBinding
import com.fallzero.app.util.TTSManager
import com.fallzero.app.util.VoiceInputHelper
import com.fallzero.app.viewmodel.OnboardingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 온보딩 1단계: 성별. 음성 답변(남/여) 또는 버튼 클릭으로 응답.
 * 답변 후 자동으로 AgeFragment로 navigate.
 */
class GenderFragment : Fragment() {

    private var _binding: FragmentGenderBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by activityViewModels()

    private var ttsManager: TTSManager? = null
    private var voiceHelper: VoiceInputHelper? = null
    private var navigated = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startVoiceListening() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentGenderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // popBackStack 후 재진입 시에도 다시 답변 가능하도록 명시적 리셋
        navigated = false

        ttsManager = TTSManager.getInstance(requireContext())
        voiceHelper = VoiceInputHelper(requireContext())

        binding.tvVoiceStatus.visibility = View.INVISIBLE

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnMale.setOnClickListener { onAnswer("male") }
        binding.btnFemale.setOnClickListener { onAnswer("female") }

        // 화면 진입 → TTS 안내 + 음성 인식 자동 시작
        viewLifecycleOwner.lifecycleScope.launch {
            delay(600)
            if (_binding == null) return@launch
            ttsManager?.speak("성별을 말씀해 주세요. 남성 또는 여성") {
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
        when {
            text.contains("남") -> onAnswer("male")
            text.contains("여") -> onAnswer("female")
            else -> {
                stopBlinking()
                ttsManager?.speak("다시 말씀해 주세요") {
                    if (_binding != null && !navigated) startVoiceListening()
                }
            }
        }
    }

    private fun onAnswer(gender: String) {
        if (navigated) return
        navigated = true
        voiceHelper?.stop()
        stopBlinking()
        viewModel.tempGender = gender
        val msg = if (gender == "male") "남성으로 선택했습니다" else "여성으로 선택했습니다"
        // UtteranceProgressListener.onDone 콜백 — 발화 완료 후 정확히 navigate
        ttsManager?.speak(msg) {
            if (_binding != null) findNavController().navigate(R.id.action_gender_to_age)
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
}
