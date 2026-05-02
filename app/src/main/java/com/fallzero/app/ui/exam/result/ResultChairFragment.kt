package com.fallzero.app.ui.exam.result

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.databinding.FragmentResultChairBinding
import com.fallzero.app.util.TTSManager
import com.fallzero.app.viewmodel.ExamViewModel

/**
 * 검사 결과 3/4: 의자 일어서기 — 연령·성별 기준 vs 사용자 카운트 비교 + TTS.
 */
class ResultChairFragment : Fragment() {

    private var _binding: FragmentResultChairBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExamViewModel by activityViewModels()
    private var ttsManager: TTSManager? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentResultChairBinding.inflate(inflater, container, false)
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
        val norm = r.chairStandNorm
        val user = r.chairStandCount
        // norm을 max로 두면 norm 막대가 우측 끝 = 가득 참. user는 0~norm으로 cap.
        // user가 norm 초과해도 시각적으로는 max 도달 (안전군 통과 의미)
        val displayUser = user.coerceIn(0, norm)

        binding.pbNorm.max = norm
        binding.pbNorm.progress = norm
        binding.tvNormValue.text = "${norm}회"

        binding.pbUser.max = norm
        binding.pbUser.progress = displayUser
        binding.tvUserValue.text = "${user}회"

        val (msg, narration) = if (r.isHighRiskChairStand)
            "⚠ 의자 일어서기에서 위험 신호가 있어요" to
            "의자 일어서기 결과를 알려드릴게요. 안전 기준은 ${norm}회인데, ${user}회를 하셨어요. 위험 신호가 있어요."
        else
            "✓ 의자 일어서기는 안전해요" to
            "의자 일어서기 결과를 알려드릴게요. 안전 기준은 ${norm}회인데, ${user}회를 하셨어요. 안전합니다."
        binding.tvJudgement.text = msg
        binding.tvJudgement.setTextColor(if (r.isHighRiskChairStand) 0xFFFF9800.toInt() else 0xFF4CAF50.toInt())

        binding.btnNext.isEnabled = false
        ttsManager?.speak(narration) {
            if (_binding != null) binding.btnNext.isEnabled = true
        }
        binding.btnNext.setOnClickListener {
            ttsManager?.stop()
            findNavController().navigate(R.id.action_result_chair_to_final)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ttsManager?.shutdown()
        ttsManager = null
        _binding = null
    }
}
