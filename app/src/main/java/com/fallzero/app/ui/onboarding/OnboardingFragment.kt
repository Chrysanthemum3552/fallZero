package com.fallzero.app.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.databinding.FragmentOnboardingBinding
import com.fallzero.app.util.TTSManager

class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!
    private var ttsManager: TTSManager? = null
    private var navigated = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ttsManager = TTSManager.getInstance(requireContext())
        // 사용자 명시 1번: 첫 화면에서 한 번만 안내 TTS — 다음 입력 화면에서 다시 발화 X.
        binding.root.postDelayed({
            if (_binding != null && !navigated) {
                ttsManager?.speak(
                    "낙상 위험인지 판별하기 위해 몇 가지 질문을 드리겠습니다. " +
                    "화면을 직접 터치해서 답변하거나, 마이크가 표시되면 말로도 답변할 수 있어요."
                )
            }
        }, 500L)
        binding.btnStart.setOnClickListener {
            if (navigated) return@setOnClickListener
            navigated = true
            ttsManager?.stop()
            findNavController().navigate(R.id.action_onboarding_to_gender)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ttsManager = null
        _binding = null
    }
}
