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
        val riskText = if (isHigh) "낙상 위험군" else "낙상 안전군"
        val chairStatus = if (r.isHighRiskChairStand) "주의 필요" else "정상"
        val balanceStatus = if (r.isHighRiskBalance) "주의 필요" else "정상"
        val surveyStatus = if (r.isHighRiskSurvey) "해당 있음 (주의)" else "해당 없음 (정상)"
        val recommendation = if (isHigh)
            "낙상 위험이 감지되었습니다. 꾸준한 OEP 운동과 의사 상담을 권장합니다."
        else
            "현재 낙상 위험이 낮습니다. 예방 운동을 꾸준히 이어나가세요."

        val report = buildString {
            appendLine("[낙상제로] 보호자 알림")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("최종 판정: $riskText")
            if (isHigh) appendLine("위험 항목: ${riskCount}가지")
            appendLine()
            appendLine("[세부 검사 결과]")
            appendLine("설문: $surveyStatus")
            appendLine("의자 일어서기: ${r.chairStandCount}회 (기준 ${r.chairStandNorm}회) - $chairStatus")
            appendLine("균형 검사: ${r.balanceStageReached}단계 / 탠덤 ${r.tandemTimeSec.toInt()}초 - $balanceStatus")
            appendLine()
            appendLine("[안내]")
            appendLine(recommendation)
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━")
            append("낙상제로 앱에서 전송됨")
        }
        ShareHelper.shareText(requireActivity(), "낙상제로 보호자 알림", report)
    }


    private fun buildShareText(r: com.fallzero.app.data.db.entity.ExamResult, riskCount: Int): String {
        val dateStr = java.text.SimpleDateFormat("yyyy년 MM월 dd일", java.util.Locale.KOREAN)
            .format(java.util.Date(r.performedAt))
        val riskText = if (r.finalRiskLevel == "high") "낙상 위험군" else "낙상 안전군"
        val chairStatus = if (r.isHighRiskChairStand) "주의 필요" else "정상"
        val balanceStatus = if (r.isHighRiskBalance) "주의 필요" else "정상"
        val surveyStatus = if (r.isHighRiskSurvey) "1개 이상 해당 (주의)" else "해당 없음 (정상)"
        val recommendation = if (r.finalRiskLevel == "high")
            "낙상 위험이 감지되었습니다. 꾸준한 OEP 운동과 의사 상담을 권장합니다."
        else
            "현재 낙상 위험이 낮습니다. 예방 운동을 꾸준히 이어나가세요."
        return buildString {
            appendLine("[낙상제로] 보호자 알림")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("검사일: $dateStr")
            appendLine("최종 판정: $riskText")
            appendLine()
            appendLine("[세부 검사 결과]")
            appendLine("의자 일어서기: ${r.chairStandCount}회 (기준 ${r.chairStandNorm}회 이상) - $chairStatus")
            appendLine("균형 검사: ${r.balanceStageReached}단계 통과 / 탠덤 ${r.tandemTimeSec.toInt()}초 - $balanceStatus")
            appendLine("낙상 위험 설문: $surveyStatus")
            appendLine()
            appendLine("[안내]")
            appendLine(recommendation)
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━")
            append("낙상제로 앱에서 전송됨")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null
        _binding = null
    }
}