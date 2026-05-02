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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentSteadiQ3Binding
import com.fallzero.app.util.TTSManager
import com.fallzero.app.util.VoiceInputHelper
import com.fallzero.app.viewmodel.OnboardingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * STEADI Q3: "넘어질까봐 두려우신가요?" — 마지막 온보딩 화면.
 * 답변 후 ViewModel.saveAll()로 모든 답변 DB 저장 → completionEvent 관찰 → 검사 세션으로 자동 진행.
 */
class SteadiQ3Fragment : Fragment() {

    private var _binding: FragmentSteadiQ3Binding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by activityViewModels()

    private var ttsManager: TTSManager? = null
    private var voiceHelper: VoiceInputHelper? = null
    private var navigated = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startVoiceListening() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSteadiQ3Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // popBackStack 후 재진입 시에도 다시 답변 가능하도록 명시적 리셋
        // (Q3는 popUp inclusive로 navigate 후 인스턴스가 destroy되지만, Q2에서 뒤로갔다 다시 올 수 있음)
        navigated = false

        ttsManager = TTSManager.getInstance(requireContext())
        voiceHelper = VoiceInputHelper(requireContext())

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnYes.setOnClickListener { onAnswer(true) }
        binding.btnNo.setOnClickListener { onAnswer(false) }

        observeCompletion()

        binding.tvVoiceStatus.visibility = View.INVISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            delay(600)
            if (_binding == null) return@launch
            ttsManager?.speak("마지막 질문입니다. 넘어질까봐 두려우신가요?") {
                if (_binding != null && !navigated) requestVoiceInput()
            }
        }
    }

    private fun observeCompletion() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.completionEvent.collect { userId ->
                    if (userId != null && _binding != null && !navigated) {
                        navigated = true
                        // 검사 세션 자동 시작 — 기존 SurveyFragment의 Complete 처리와 동일
                        SessionFlow.startExamSession()
                        SessionFlow.pendingAutoForward = true
                        findNavController().navigate(R.id.action_q3_to_home)
                    }
                }
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
        if (viewModel.completionEvent.value != null) return
        voiceHelper?.stop()
        stopBlinking()
        viewModel.tempQ3 = isYes
        val msg = if (isYes) "예. 모든 질문이 끝났습니다. 검사를 시작합니다"
                  else "아니오. 모든 질문이 끝났습니다. 검사를 시작합니다"
        // TTS 끝나면 saveAll → completionEvent observer가 navigation 처리
        ttsManager?.speak(msg) {
            if (_binding != null && !navigated) viewModel.saveAll()
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
