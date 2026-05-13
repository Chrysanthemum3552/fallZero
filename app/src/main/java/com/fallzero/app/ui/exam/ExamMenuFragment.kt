package com.fallzero.app.ui.exam

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentExamMenuBinding

/**
 * 사용자 명시 8번: 메인 → "낙상 위험 검사" 클릭 시 진입.
 * 전체 검사 / 균형 1·2·3·4단계 / 의자 일어서기 — 사용자가 항목 선택하여 시작.
 */
class ExamMenuFragment : Fragment() {

    private var _binding: FragmentExamMenuBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentExamMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.btnFullExam.setOnClickListener {
            SessionFlow.startExamSession()
            findNavController().navigate(R.id.action_exam_menu_to_exam)
        }
        binding.btnBalance1.setOnClickListener { startSingleStage(1) }
        binding.btnBalance2.setOnClickListener { startSingleStage(2) }
        binding.btnBalance3.setOnClickListener { startSingleStage(3) }
        binding.btnBalance4.setOnClickListener { startSingleStage(4) }
        binding.btnChairOnly.setOnClickListener {
            SessionFlow.startExamChairStandOnly()
            findNavController().navigate(R.id.action_exam_menu_to_exam)
        }
    }

    private fun startSingleStage(stage: Int) {
        SessionFlow.startExamSingleBalanceStage(stage)
        findNavController().navigate(R.id.action_exam_menu_to_exam)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
