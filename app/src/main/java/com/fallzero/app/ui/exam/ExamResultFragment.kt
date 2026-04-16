package com.fallzero.app.ui.exam

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentExamResultBinding
import com.fallzero.app.util.ShareHelper
import com.fallzero.app.util.TTSManager
import com.fallzero.app.viewmodel.ExamViewModel

class ExamResultFragment : Fragment() {

    private var _binding: FragmentExamResultBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExamViewModel by activityViewModels()
    private var ttsManager: TTSManager? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExamResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ttsManager = TTSManager(requireContext())

        // finalizeAndSave는 비동기 — phase StateFlow를 관찰하여 Completed 도착 시 표시
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.phase.collect { phase ->
                    if (phase is ExamViewModel.ExamPhase.Completed) {
                        displayResult(phase)
                    }
                }
            }
        }
        // 이미 완료된 경우 즉시 표시
        viewModel.getCompletedResult()?.let { displayResult(it) }

        binding.btnGoHome.setOnClickListener {
            SessionFlow.reset()
            viewModel.resetForNewSession()
            findNavController().navigate(R.id.action_result_to_home)
        }

        binding.btnShare.setOnClickListener {
            val phase2 = viewModel.phase.value
            if (phase2 is ExamViewModel.ExamPhase.Completed) {
                val result = phase2.result
                val text = buildShareText(phase2)
                ShareHelper.shareText(requireContext(), "낙상제로 검사 결과", text)
            }
        }
    }

    private fun displayResult(phase: ExamViewModel.ExamPhase.Completed) {
        val result = phase.result
        val risk = phase.riskAssessment

        // 최종 판정
        val isHighRisk = result.finalRiskLevel == "high"
        binding.tvRiskLevel.text = if (isHighRisk) getString(R.string.exam_high_risk) else getString(R.string.exam_low_risk)
        binding.tvRiskLevel.setBackgroundResource(
            if (isHighRisk) R.drawable.bg_risk_level else R.drawable.bg_risk_low
        )

        // 의자 일어서기
        binding.tvChairStandResult.text =
            "의자 일어서기: ${result.chairStandCount}회 (기준: ${result.chairStandNorm}회)" +
            (if (result.isHighRiskChairStand) " — 주의" else " — 정상")

        // 균형 검사
        binding.tvBalanceResult.text =
            "균형 검사: ${result.balanceStageReached}단계 통과" +
            " · 탠덤 유지 ${result.tandemTimeSec.toInt()}초" +
            (if (result.isHighRiskBalance) " — 주의" else " — 정상")

        // STEADI 설문
        binding.tvSurveyResult.text =
            "낙상 위험 설문: " + (if (result.isHighRiskSurvey) "1개 이상 해당 — 주의" else "해당 없음 — 정상")

        // 안내
        val recommendation = if (isHighRisk) {
            "낙상 위험이 감지되었습니다. 지속적인 OEP 운동과 의사 상담을 권장합니다."
        } else {
            "현재 낙상 위험이 낮습니다. 예방 운동을 꾸준히 이어나가세요!"
        }
        binding.tvRecommendation.text = recommendation

        // 결과 음성 안내
        val riskText = if (isHighRisk) "낙상 주의군" else "낙상 안전군"
        ttsManager?.speak(
            "검사 결과, ${riskText}에 해당합니다. " +
            "의자 일어서기 ${result.chairStandCount}회, " +
            "균형 검사 ${result.balanceStageReached}단계 통과입니다. " +
            recommendation
        )
    }

    private fun buildShareText(phase: ExamViewModel.ExamPhase.Completed): String {
        val r = phase.result
        return "[낙상제로 검사 결과]\n" +
            "판정: ${if (r.finalRiskLevel == "high") "위험군" else "안전군"}\n" +
            "의자 일어서기: ${r.chairStandCount}회 (기준 ${r.chairStandNorm}회)\n" +
            "균형 ${r.balanceStageReached}단계 통과 · 탠덤 ${r.tandemTimeSec.toInt()}초\n" +
            "검사일: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREAN).format(r.performedAt)}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null
        _binding = null
    }
}
