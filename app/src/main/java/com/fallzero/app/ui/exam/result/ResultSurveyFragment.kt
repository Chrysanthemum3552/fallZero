package com.fallzero.app.ui.exam.result

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.databinding.FragmentResultSurveyBinding
import com.fallzero.app.util.TTSManager
import com.fallzero.app.viewmodel.ExamViewModel

/**
 * 검사 결과 1/4: 설문 결과.
 * 답변 표시 + 설문 차원 위험 신호 여부 + TTS 안내. "다음" 버튼은 TTS 끝나야 활성.
 */
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
        binding.tvQ1.text = if (r.isHighRiskSurvey && r.let { /* survey 위험 = 셋 중 하나 */ true })
            "• 지난 1년 넘어진 적: 답변 기록됨"
        else "• 지난 1년 넘어진 적: 답변 기록됨"
        binding.tvQ2.text = "• 서거나 걸을 때 불안정: 답변 기록됨"
        binding.tvQ3.text = "• 넘어질까봐 두려움: 답변 기록됨"

        val (msg, narration) = if (r.isHighRiskSurvey)
            "⚠ 설문 차원에서 위험 신호가 있어요" to
            "설문 결과를 알려드릴게요. 설문 답변에서 낙상 위험 신호가 있어요."
        else
            "✓ 설문 차원은 안전해요" to
            "설문 결과를 알려드릴게요. 설문 답변에서 낙상 위험 신호가 없어요."
        binding.tvJudgement.text = msg
        binding.tvJudgement.setTextColor(if (r.isHighRiskSurvey) 0xFFFF9800.toInt() else 0xFF4CAF50.toInt())

        // TTS 끝까지 들리면 다음 버튼 활성
        binding.btnNext.isEnabled = false
        ttsManager?.speak(narration) {
            if (_binding != null) binding.btnNext.isEnabled = true
        }
        binding.btnNext.setOnClickListener {
            // 사용자가 누르면 TTS 끊고 즉시 navigate
            ttsManager?.stop()
            findNavController().navigate(R.id.action_result_survey_to_balance)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ttsManager?.shutdown()
        ttsManager = null
        _binding = null
    }
}
