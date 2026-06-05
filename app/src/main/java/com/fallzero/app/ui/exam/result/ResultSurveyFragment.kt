package com.fallzero.app.ui.exam.result

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.databinding.FragmentResultSurveyBinding
import com.fallzero.app.util.TTSManager
import com.fallzero.app.viewmodel.ExamViewModel
import kotlinx.coroutines.launch

class ResultSurveyFragment : Fragment() {

    private var _binding: FragmentResultSurveyBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExamViewModel by activityViewModels()
    private var ttsManager: TTSManager? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentResultSurveyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ttsManager = TTSManager.getInstance(requireContext())

        val phase = viewModel.getCompletedResult() ?: run {
            findNavController().navigate(R.id.action_global_home)
            return
        }
        val r = phase.result

        // DB에서 실제 설문 답변 불러오기 (OnboardingViewModel.saveAll()이 DB에 저장)
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val user = db.userDao().getUserById(userId)
            val b = _binding ?: return@launch

            if (user != null) {
                b.tvQ1.text = "• 지난 1년 넘어진 적: ${if (user.steadiQ1) "있음" else "없음"}"
                b.tvQ2.text = "• 서거나 걸을 때 불안정: ${if (user.steadiQ2) "있음" else "없음"}"
                b.tvQ3.text = "• 넘어질까봐 두려움: ${if (user.steadiQ3) "있음" else "없음"}"
            } else {
                b.tvQ1.text = "• 지난 1년 넘어진 적: 기록 없음"
                b.tvQ2.text = "• 서거나 걸을 때 불안정: 기록 없음"
                b.tvQ3.text = "• 넘어질까봐 두려움: 기록 없음"
            }
        }

        val (msg, narration) = if (r.isHighRiskSurvey)
            "설문 차원에서 위험 신호가 \n있어요" to
                    "설문 결과를 알려드릴게요. 설문 답변에서 낙상 위험 신호가 있어요."
        else
            "설문 차원은 안전해요" to
                    "설문 결과를 알려드릴게요. 설문 답변에서 낙상 위험 신호가 없어요."

        binding.tvJudgement.text = msg
        binding.tvJudgement.setTextColor(
            if (r.isHighRiskSurvey) 0xFFFF9800.toInt() else 0xFF4CAF50.toInt()
        )

        // TTS 재생 + 버튼은 바로 활성화 (TTS 실패해도 이동 가능)
        ttsManager?.speak(narration)
        binding.btnNext.isEnabled = true
        binding.btnNext.setOnClickListener {
            ttsManager?.stop()
            findNavController().navigate(R.id.action_result_survey_to_balance)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null
        _binding = null
    }
}