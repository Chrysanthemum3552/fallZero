package com.fallzero.app.ui.exam.result

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentResultFinalBinding
import com.fallzero.app.util.TTSManager
import com.fallzero.app.viewmodel.ExamViewModel

/**
 * 검사 결과 4/4: 종합 결과 — STEADI 셋(설문/균형/의자) 합산 → 위험군/안전군.
 */
class ResultFinalFragment : Fragment() {

    private var _binding: FragmentResultFinalBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExamViewModel by activityViewModels()
    private var ttsManager: TTSManager? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentResultFinalBinding.inflate(inflater, container, false)
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
        val isHigh = r.finalRiskLevel == "high"
        val riskCount = listOf(r.isHighRiskSurvey, r.isHighRiskBalance, r.isHighRiskChairStand).count { it }

        if (isHigh) {
            binding.tvRiskBadge.text = "⚠ 낙상 위험군"
            binding.tvRiskBadge.setTextColor(0xFFFF5252.toInt())
            binding.tvRecommendation.text =
                "3가지 차원 중 ${riskCount}개에서 위험 신호가 있어요.\n낙상 예방 운동을 시작해 보세요."
        } else {
            binding.tvRiskBadge.text = "✓ 안전군"
            binding.tvRiskBadge.setTextColor(0xFF4CAF50.toInt())
            binding.tvRecommendation.text =
                "현재 낙상 위험 신호가 없어요.\n꾸준한 운동으로 건강을 유지하세요."
        }

        val narration = if (isHigh)
            "종합 결과를 알려드릴게요. 낙상 위험군이에요. ${riskCount}가지 차원에서 위험 신호가 있어요. 낙상 예방 운동을 시작해 보세요."
        else
            "종합 결과를 알려드릴게요. 안전군이에요. 현재 낙상 위험 신호가 없습니다."

        binding.btnHome.isEnabled = false
        ttsManager?.speak(narration) {
            if (_binding != null) binding.btnHome.isEnabled = true
        }
        binding.btnHome.setOnClickListener {
            ttsManager?.stop()
            SessionFlow.reset()
            viewModel.resetForNewSession()
            findNavController().navigate(R.id.action_result_final_to_home)
        }

        // 보호자 공유 — 추후 논의: 검사 결과 + oneLegTimeSec 추이 + 균형/의자 그래프 등 자세한 자료 전송
        binding.btnShareGuardian.setOnClickListener {
            Toast.makeText(requireContext(), "구현 준비중입니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ttsManager?.shutdown()
        ttsManager = null
        _binding = null
    }
}
