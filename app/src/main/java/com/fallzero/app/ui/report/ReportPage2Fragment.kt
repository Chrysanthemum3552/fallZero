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
import com.fallzero.app.databinding.FragmentReportPage2Binding
import com.fallzero.app.viewmodel.ReportViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 보고서 Page 2 — 훈련 이행률
 */
class ReportPage2Fragment : Fragment() {

    private var _binding: FragmentReportPage2Binding? = null
    private val binding get() = _binding!!
    private val viewModel: ReportViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportPage2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPrevPage.setOnClickListener {
            findNavController().navigate(R.id.action_report2_to_page1)
        }
        binding.btnNextPage.setOnClickListener {
            findNavController().navigate(R.id.action_report2_to_page3)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.page2.collect { data ->
                    val b = _binding ?: return@collect
                    data ?: return@collect

                    b.tvWeeklyAdherence.text = data.weeklyAdherenceText
                    b.tvTotalSessions.text = "최근 28일 총 ${data.totalSessions}회 완료"

                    if (data.recentSessions.isEmpty()) {
                        b.tvRecentSessions.text = getString(R.string.report_no_data)
                        return@collect
                    }

                    val dateFormat = SimpleDateFormat("MM/dd (E)", Locale.KOREAN)
                    val sb = StringBuilder("최근 세션 기록:\n")
                    data.recentSessions.take(10).forEach { session ->
                        sb.append("• ${dateFormat.format(Date(session.startedAt))}\n")
                    }
                    b.tvRecentSessions.text = sb.toString().trimEnd()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
