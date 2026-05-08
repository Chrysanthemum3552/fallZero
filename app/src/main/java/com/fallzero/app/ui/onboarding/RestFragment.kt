package com.fallzero.app.ui.onboarding

import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentRestBinding
import com.fallzero.app.util.TTSManager

class RestFragment : Fragment() {

    private var _binding: FragmentRestBinding? = null
    private val binding get() = _binding!!

    private var timer: CountDownTimer? = null
    private var ttsManager: TTSManager? = null
    private var hasAdvanced = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hasAdvanced = false
        ttsManager = TTSManager.getInstance(requireContext())

        val step = SessionFlow.current()
        val seconds = step.restSeconds.coerceAtLeast(1)

        binding.tvTitle.text = step.title.ifEmpty { "고생하셨어요!" }
        binding.tvSubtitle.text = step.subtitle.ifEmpty { "잠시 쉬었다 갈까요?" }
        binding.tvNext.text = "다음 운동을 준비해주세요"

        ttsManager?.speak("고생하셨어요. ${seconds}초만 쉬었다 갈게요.")

        timer = object : CountDownTimer((seconds * 1000L) + 100L, 1000L) {
            override fun onTick(msLeft: Long) {
                val s = (msLeft / 1000L).toInt().coerceAtLeast(0)
                _binding?.tvCountdown?.text = s.toString()
            }
            override fun onFinish() {
                _binding?.tvCountdown?.text = "0"
                advanceNext()
            }
        }.start()
    }

    private fun advanceNext() {
        if (hasAdvanced) return
        hasAdvanced = true
        val next = SessionFlow.advance()
        navigateTo(next)
    }

    private fun navigateTo(step: SessionFlow.Step) {
        val nav = findNavController()
        when (step.type) {
            SessionFlow.StepType.EXERCISE         -> nav.navigate(R.id.action_global_exercise)
            SessionFlow.StepType.EXAM_BALANCE,
            SessionFlow.StepType.EXAM_CHAIR_STAND -> nav.navigate(R.id.action_global_exam)
            SessionFlow.StepType.REST,
            SessionFlow.StepType.SIDE_REST        -> nav.navigate(R.id.action_global_rest)
            SessionFlow.StepType.SIDE_ROTATION    -> nav.navigate(R.id.action_global_rotation)
            SessionFlow.StepType.CHAIR_REPOSITION -> nav.navigate(R.id.action_global_chair_reposition)
            SessionFlow.StepType.DONE             -> nav.navigate(R.id.action_global_home)
            SessionFlow.StepType.PRE_FLIGHT       -> nav.navigate(R.id.action_global_preflight)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timer?.cancel(); timer = null
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null
        _binding = null
    }
}