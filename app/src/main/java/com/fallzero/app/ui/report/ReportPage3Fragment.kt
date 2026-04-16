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
import com.fallzero.app.databinding.FragmentReportPage3Binding
import com.fallzero.app.viewmodel.ReportViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 보고서 Page 3 — 운동별 PRB(개인 최대 가동범위) 달성 현황
 */
class ReportPage3Fragment : Fragment() {

    private var _binding: FragmentReportPage3Binding? = null
    private val binding get() = _binding!!
    private val viewModel: ReportViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportPage3Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPrevPage.setOnClickListener {
            findNavController().navigate(R.id.action_report3_to_page2)
        }
        binding.btnHome.setOnClickListener {
            findNavController().navigate(R.id.action_report3_to_home)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.page3.collect { data ->
                    val b = _binding ?: return@collect
                    data ?: return@collect

                    if (data.prbEntries.isEmpty()) {
                        b.tvPrbList.text = getString(R.string.report_no_data) +
                            "\n\n운동을 시작하면 PRB 데이터가 측정됩니다."
                        return@collect
                    }

                    val dateFormat = SimpleDateFormat("MM/dd", Locale.KOREAN)
                    val sb = StringBuilder()
                    data.prbEntries.forEach { entry ->
                        val unit = if (entry.exerciseId in 1..6) "°" else if (entry.exerciseId == 7) "%" else "초"
                        sb.append("${entry.exerciseName}: %.1f$unit".format(entry.prbValue))
                        sb.append(" (${dateFormat.format(Date(entry.measuredAt))} 측정)\n\n")
                    }
                    b.tvPrbList.text = sb.toString().trimEnd()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
