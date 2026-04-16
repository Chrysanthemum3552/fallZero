package com.fallzero.app.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentSurveyBinding
import com.fallzero.app.viewmodel.OnboardingViewModel
import kotlinx.coroutines.launch

/**
 * 온보딩 설문 Fragment
 * Step 1: 성별 + 나이 입력
 * Step 2: CDC STEADI 3문항
 * 완료 후 → ExamFragment (최초 낙상 위험 검사)
 */
class SurveyFragment : Fragment() {

    private var _binding: FragmentSurveyBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSurveyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Step 1 → Step 2
        binding.btnNext.setOnClickListener {
            // 성별 선택 검증
            if (!binding.rbMale.isChecked && !binding.rbFemale.isChecked) {
                Toast.makeText(requireContext(), "성별을 선택해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val gender = if (binding.rbMale.isChecked) "male" else "female"
            val age = binding.etAge.text?.toString()?.toIntOrNull()
            if (age == null || age < 60 || age > 110) {
                Toast.makeText(requireContext(), "나이를 올바르게 입력해주세요 (60~110)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.saveGenderAndAge(gender, age)
        }

        // Step 2 완료 → Exam
        binding.btnComplete.setOnClickListener {
            // STEADI 3문항 모두 응답 검증
            val q1Set = binding.rbQ1Yes.isChecked || binding.rbQ1No.isChecked
            val q2Set = binding.rbQ2Yes.isChecked || binding.rbQ2No.isChecked
            val q3Set = binding.rbQ3Yes.isChecked || binding.rbQ3No.isChecked
            if (!q1Set || !q2Set || !q3Set) {
                Toast.makeText(requireContext(), "모든 문항에 답해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val q1 = binding.rbQ1Yes.isChecked
            val q2 = binding.rbQ2Yes.isChecked
            val q3 = binding.rbQ3Yes.isChecked
            viewModel.saveSteadiAndComplete(q1, q2, q3)
        }

        // ViewModel 상태 관찰 (view lifecycle 기준 — onDestroyView 후 자동 취소)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val b = _binding ?: return@collect
                    when (state) {
                        OnboardingViewModel.OnboardingState.Step1Gender -> {
                            b.sectionStep1.visibility = View.VISIBLE
                            b.sectionStep2.visibility = View.GONE
                        }
                        OnboardingViewModel.OnboardingState.Step2Steadi -> {
                            b.sectionStep1.visibility = View.GONE
                            b.sectionStep2.visibility = View.VISIBLE
                        }
                        is OnboardingViewModel.OnboardingState.Complete -> {
                            // 설문 완료 → 검사 세션 큐 빌드 후 홈으로 (백스택 root 확보) →
                            // pendingAutoForward 플래그로 HomeFragment가 1회만 자동 forward
                            SessionFlow.startExamSession()
                            SessionFlow.pendingAutoForward = true
                            findNavController().navigate(R.id.action_survey_to_home)
                        }
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
