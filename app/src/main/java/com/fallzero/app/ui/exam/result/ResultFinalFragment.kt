package com.fallzero.app.ui.exam.result

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.fallzero.app.util.ShareHelper
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentResultFinalBinding
import com.fallzero.app.util.TTSManager
import com.fallzero.app.viewmodel.ExamViewModel

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
            binding.tvRiskBadge.text = "낙상 위험군"
            binding.tvRiskBadge.setTextColor(0xFFFF5252.toInt())
            binding.tvRecommendation.text =
                "3가지 항목 중 ${riskCount}개에서 위험 신호가 있어요.\n낙상 예방 운동을 시작해 보세요."
        } else {
            binding.tvRiskBadge.text = "낙상 안전군"
            binding.tvRiskBadge.setTextColor(0xFF4CAF50.toInt())
            binding.tvRecommendation.text =
                "현재 낙상 위험 신호가 없어요.\n꾸준한 운동으로 건강을 유지하세요."
        }

        val narration = if (isHigh)
            "종합 결과를 알려드릴게요. 낙상 위험군이에요. ${riskCount}가지 항목에서 위험 신호가 있어요. 낙상 예방 운동을 시작해 보세요."
        else
            "종합 결과를 알려드릴게요. 낙상 안전군이에요. 현재 낙상 위험 신호가 없습니다."

        // TTS 재생 + 버튼은 바로 활성화 (TTS 실패해도 이동 가능)
        ttsManager?.speak(narration)
        binding.btnHome.isEnabled = true
        binding.btnHome.setOnClickListener {
            ttsManager?.stop()
            SessionFlow.reset()
            viewModel.resetForNewSession()
            findNavController().navigate(R.id.action_result_final_to_home)
        }
        binding.btnShareGuardian.setOnClickListener {
            shareGuardianReport(r, isHigh, riskCount)
        }
    }


    private fun shareGuardianReport(r: com.fallzero.app.data.db.entity.ExamResult, isHigh: Boolean, riskCount: Int) {
        // 상세 보호자 보고서는 공용 헬퍼로 통일 (검사 결과 화면과 동일 문장). isHigh/riskCount는 헬퍼가 내부 계산.
        ShareHelper.shareText(
            requireActivity(),
            com.fallzero.app.util.GuardianTextReport.SHARE_TITLE,
            com.fallzero.app.util.GuardianTextReport.build(r)
        )
    }


    override fun onDestroyView() {
        super.onDestroyView()
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null
        _binding = null
    }
}