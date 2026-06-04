package com.fallzero.app.ui.onboarding

import android.content.Context
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

        // 직전 운동에서 진급이 발생했으면 ExerciseFragment가 prefs에 저장. 한 번만 안내하고 flag 클리어.
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val pendingProgressionMsg = prefs.getString("pending_progression_msg", null)
        if (pendingProgressionMsg != null) {
            prefs.edit().remove("pending_progression_msg").apply()
            binding.tvTitle.text = "축하해요!"
            // 메시지는 "축하해요!\n…진급했어요." 형식 — title이 이미 "축하해요!"라 subtitle은 개행 뒷부분만.
            binding.tvSubtitle.text = pendingProgressionMsg.substringAfter("\n")
        } else {
            binding.tvTitle.text = step.title.ifEmpty { "고생하셨어요!" }
            binding.tvSubtitle.text = step.subtitle.ifEmpty { "잠시 쉬었다 갈까요?" }
        }

        // 다음 단계 미리 표시
        val nextIndex = peekNextLabel()
        binding.tvNext.text = nextIndex

        // 진급 안내는 휴식 멘트보다 앞에 — 한 호흡에 발화.
        val openingTts = if (pendingProgressionMsg != null) {
            "${pendingProgressionMsg.replace("\n", " ")} ${seconds}초만 쉬었다 갈게요."
        } else {
            "고생하셨어요. ${seconds}초만 쉬었다 갈게요."
        }
        ttsManager?.speak(openingTts)

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

    private fun peekNextLabel(): String {
        // SessionFlow는 큐 기반 — 다음 step을 미리 알려면 advance하기 전에 뭔지 봐야 함
        // 단순화: 우리는 호출 직전 advance하지 않고, 다음 카운트다운 종료 시 advance한다.
        // 따라서 여기서는 그냥 안내 텍스트만 표시.
        return "다음 운동을 준비해주세요"
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