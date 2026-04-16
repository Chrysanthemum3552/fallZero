package com.fallzero.app.ui.report

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
import com.fallzero.app.R
import com.fallzero.app.databinding.FragmentReportBinding
import com.fallzero.app.viewmodel.ReportViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 보고서 Page 1 — 검사 결과 요약
 */
class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ReportViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnNextPage.setOnClickListener {
            findNavController().navigate(R.id.action_report_to_page2)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.page1.collect { data ->
                    val b = _binding ?: return@collect
                    data ?: return@collect
                    val latest = data.latestResult
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)

                    if (latest == null) {
                        b.tvLatestRisk.text = getString(R.string.report_no_data)
                        return@collect
                    }
                    val isHighRisk = latest.finalRiskLevel == "high"
                    b.tvLatestRisk.text = if (isHighRisk) "낙상 위험군" else "낙상 안전군"
                    b.tvLatestRisk.setBackgroundResource(
                        if (isHighRisk) R.drawable.bg_risk_level else R.drawable.bg_risk_low
                    )
                    b.tvChairDetail.text =
                        "의자 일어서기: ${latest.chairStandCount}회 (기준 ${latest.chairStandNorm}회)" +
                        "\n검사일: ${dateFormat.format(Date(latest.performedAt))}"
                    b.tvBalanceDetail.text =
                        "균형 검사: ${latest.balanceStageReached}단계 · 탠덤 ${latest.tandemTimeSec.toInt()}초"
                    val first = data.firstResult
                    b.tvImprovement.text = when {
                        first == null || first.id == latest.id -> "첫 번째 검사 결과입니다."
                        data.isImproved -> "이전 검사 대비 위험군에서 안전군으로 개선되었습니다!"
                        latest.finalRiskLevel == first.finalRiskLevel -> "이전 검사와 동일한 판정입니다."
                        else -> "이전 검사 대비 지속적으로 모니터링이 필요합니다."
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
