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
import com.fallzero.app.databinding.FragmentSteadiQ2Binding
import com.fallzero.app.util.TTSManager
import com.fallzero.app.util.VoiceInputHelper
import com.fallzero.app.viewmodel.OnboardingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** STEADI Q2: "서 있거나 걸을 때 불안정한 느낌이 드시나요?" */
class SteadiQ2Fragment : Fragment() {

    private var _binding: FragmentSteadiQ2Binding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by activityViewModels()

    private var ttsManager: TTSManager? = null
    private var voiceHelper: VoiceInputHelper? = null
    private var navigated = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startVoiceListening() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSteadiQ2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // popBackStack 후 재진입 시에도 다시 답변 가능하도록 명시적 리셋
        navigated = false

        ttsManager = TTSManager.getInstance(requireContext())
        voiceHelper = VoiceInputHelper(requireContext())

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnYes.setOnClickListener { onAnswer(true) }
        binding.btnNo.setOnClickListener { onAnswer(false) }

        binding.tvVoiceStatus.visibility = View.INVISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            delay(600)
            if (_binding == null) return@launch
            ttsManager?.speak("두 번째 질문입니다. 서 있거나 걸을 때 불안정한 느낌이 드시나요?") {
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
            onResult = { text -> handleYesNoResult(text) },
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

    private fun handleYesNoResult(text: String) {
        when {
            text.contains("예") || text.contains("네") || text.contains("응") ||
                text.contains("있") || text.contains("맞") -> onAnswer(true)
            text.contains("아니") || text.contains("없") -> onAnswer(false)
            else -> {
                stopBlinking()
                ttsManager?.speak("다시 말씀해 주세요") {
                    if (_binding != null && !navigated) startVoiceListening()
                }
            }
        }
    }

    private fun onAnswer(isYes: Boolean) {
        if (navigated) return
        navigated = true
        voiceHelper?.stop()
        stopBlinking()
        viewModel.tempQ2 = isYes
        ttsManager?.speak(if (isYes) "예" else "아니오") {
            if (_binding != null) findNavController().navigate(R.id.action_q2_to_q3)
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
