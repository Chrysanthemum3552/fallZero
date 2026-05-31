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
        val dateStr = java.text.SimpleDateFormat("yyyy년 M월 d일", java.util.Locale.KOREAN)
            .format(java.util.Date(r.performedAt))
        val riskText = if (isHigh) "낙상 위험군 (주의 필요)" else "낙상 저위험군 (양호)"

        // ── 각 검사 항목별 의학적 해석 (CDC STEADI 기준) ──
        // 1) 설문 — Stay Independent 핵심 문항
        val surveyLine = if (r.isHighRiskSurvey)
            "▶ 결과: 위험 신호 있음\n" +
            "   낙상력·불안정감·낙상 두려움 중 하나 이상에 해당합니다.\n" +
            "   이 세 가지는 향후 낙상을 예측하는 대표적 신호로, 해당될 경우\n" +
            "   실제 낙상 위험이 유의하게 높아지는 것으로 보고됩니다."
        else
            "▶ 결과: 위험 신호 없음 (정상)\n" +
            "   낙상력·불안정감·낙상 두려움 모두 해당 사항이 없습니다."

        // 2) 30초 의자 앉았다 일어서기 — 하지(다리) 근력
        val chairDiff = r.chairStandNorm - r.chairStandCount
        val chairLine = if (r.isHighRiskChairStand)
            "▶ 결과: 기준 미달 (주의 필요)\n" +
            "   측정 ${r.chairStandCount}회 / 같은 연령·성별 기준 ${r.chairStandNorm}회\n" +
            "   기준보다 ${chairDiff}회 부족합니다. 일어설 때 쓰는 다리(하지) 근력과\n" +
            "   순발력이 낮아진 상태로, 의자·변기에서 일어서기, 계단 오르내리기\n" +
            "   같은 동작에서 균형을 잃을 위험이 높아질 수 있습니다."
        else
            "▶ 결과: 기준 충족 (정상)\n" +
            "   측정 ${r.chairStandCount}회 / 같은 연령·성별 기준 ${r.chairStandNorm}회\n" +
            "   하지 근력이 연령대 기준 이상으로 유지되고 있습니다."

        // 3) 4단계 균형 검사 — 일렬(탠덤) 서기 중심
        val oneLegStr = if (r.oneLegTimeSec > 0f) "한 발 서기 ${r.oneLegTimeSec.toInt()}초, " else ""
        val balanceLine = if (r.isHighRiskBalance)
            "▶ 결과: 기준 미달 (주의 필요)\n" +
            "   통과 단계 ${r.balanceStageReached}/4단계, ${oneLegStr}일렬 서기 ${r.tandemTimeSec.toInt()}초\n" +
            "   (안전 기준: 일렬 서기 10초 이상 유지)\n" +
            "   좁은 지지면에서 몸의 중심을 잡는 정적 균형 능력이 저하된 상태로,\n" +
            "   방향 전환·좁은 공간 이동·야간 보행 시 넘어질 위험이 있습니다."
        else
            "▶ 결과: 기준 충족 (정상)\n" +
            "   통과 단계 ${r.balanceStageReached}/4단계, ${oneLegStr}일렬 서기 ${r.tandemTimeSec.toInt()}초\n" +
            "   (안전 기준: 일렬 서기 10초 이상) — 정적 균형 능력이 양호합니다."

        // ── 종합 권고 (STEADI 다요인 중재) ──
        val recommendation = if (isHigh) buildString {
            appendLine("이번 검사에서 3개 항목 중 ${riskCount}개 항목에 위험 신호가 확인되었습니다.")
            appendLine("미국 질병통제예방센터(CDC) STEADI 지침에서는 다음을 권장합니다.")
            appendLine()
            appendLine(" 1. 균형·근력 운동을 꾸준히: 오타고(Otago)/OEP 같은 검증된")
            appendLine("    낙상 예방 운동을 주 3회 이상 지속하면 낙상이 의미 있게 줄어듭니다.")
            appendLine("    (본 앱의 8가지 운동이 이에 해당합니다.)")
            appendLine(" 2. 의료진 상담: 복용 약물(어지럼·졸림 유발 약), 기립성 저혈압,")
            appendLine("    비타민 D 부족 여부를 점검받으시길 권합니다.")
            appendLine(" 3. 시력 검사: 1년 이상 안 받으셨다면 시력·안과 검진을 권장합니다.")
            appendLine(" 4. 주거 환경 개선: 미끄럼 방지 매트, 욕실·계단 손잡이, 야간 조명,")
            appendLine("    걸리기 쉬운 전선·문턱 정리로 가정 내 낙상 요인을 줄여주세요.")
            append(" 5. 보호자 관찰: 일어설 때 휘청임, 보행 중 벽 짚기 등이 잦아지면")
            append(" 의료진과 상의해 주세요.")
        } else buildString {
            appendLine("이번 검사에서는 세 가지 항목 모두 안전 기준을 충족했습니다.")
            appendLine("현재 낙상 위험은 낮은 편이나, 근력·균형은 사용하지 않으면")
            appendLine("나이가 들며 자연히 약해지므로 예방이 중요합니다.")
            appendLine()
            appendLine(" 1. 지금의 균형·근력을 유지하도록 예방 운동을 꾸준히 이어가세요.")
            appendLine(" 2. 정기적으로(예: 3~6개월마다) 재검사하여 변화를 확인하세요.")
            append(" 3. 시력 검진과 안전한 주거 환경도 함께 관리해 주세요.")
        }

        val report = buildString {
            appendLine("[낙상제로] 보호자 안내 — 낙상 위험 검사 결과")
            appendLine("(CDC STEADI 낙상 위험 평가 기준)")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("· 검사일: $dateStr")
            appendLine("· 종합 판정: $riskText")
            if (isHigh) appendLine("· 위험 신호 항목: 3개 중 ${riskCount}개")
            appendLine()
            appendLine("이 보고서는 어르신의 낙상 위험을 세 가지 측면(설문·하지 근력·")
            appendLine("균형)에서 평가한 결과입니다. 의학적 진단을 대체하지 않으며,")
            appendLine("위험 신호가 있을 경우 의료진 상담의 참고 자료로 활용하시기 바랍니다.")
            appendLine()
            appendLine("【1. 낙상 위험 설문】")
            appendLine("최근 1년 낙상 경험, 서거나 걸을 때 불안정감, 낙상에 대한 두려움을")
            appendLine("묻는 STEADI 핵심 문항입니다.")
            appendLine(surveyLine)
            appendLine()
            appendLine("【2. 30초 의자 앉았다 일어서기 (하지 근력)】")
            appendLine("팔을 가슴에 모은 채 30초간 일어섰다 앉기를 반복해 다리 근력과")
            appendLine("순발력을 측정하는 표준 검사입니다.")
            appendLine(chairLine)
            appendLine()
            appendLine("【3. 4단계 균형 검사 (정적 균형)】")
            appendLine("두 발 모으기→반일렬→일렬→한 발 서기로 난도를 높이며 균형을")
            appendLine("측정합니다. 특히 '일렬 서기 10초'가 핵심 지표입니다.")
            appendLine(balanceLine)
            appendLine()
            appendLine("【종합 안내 및 권고】")
            appendLine(recommendation)
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("· 평가 기준: CDC STEADI (Stopping Elderly Accidents,")
            appendLine("  Deaths & Injuries) — 30초 의자 일어서기 / 4단계 균형 검사 /")
            appendLine("  Stay Independent 설문")
            append("· 본 결과는 선별 목적이며 의학적 진단이 아닙니다. — 낙상제로 앱")
        }
        ShareHelper.shareText(requireActivity(), "낙상제로 보호자 안내 — 낙상 위험 검사 결과", report)
    }


    override fun onDestroyView() {
        super.onDestroyView()
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null
        _binding = null
    }
}