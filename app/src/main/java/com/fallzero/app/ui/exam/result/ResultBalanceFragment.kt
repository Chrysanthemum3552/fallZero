package com.fallzero.app.ui.exam.result

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.databinding.FragmentResultBalanceBinding
import com.fallzero.app.util.TTSManager
import com.fallzero.app.viewmodel.ExamViewModel

/**
 * 검사 결과 2/4: 균형 검사 결과 — 기준(10초) vs 사용자 막대 비교 + TTS.
 */
class ResultBalanceFragment : Fragment() {

    private var _binding: FragmentResultBalanceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExamViewModel by activityViewModels()
    private var ttsManager: TTSManager? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentResultBalanceBinding.inflate(inflater, container, false)
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
        // CDC STEADI 통과 기준 10초가 그래프 우측 끝 = 가득 참. 사용자는 0~10 범위.
        val normSec = 10
        val userSec = r.tandemTimeSec.toInt().coerceIn(0, normSec)

        binding.pbNorm.max = normSec
        binding.pbNorm.progress = normSec
        binding.tvNormValue.text = "${normSec}초"

        binding.pbUser.max = normSec
        binding.pbUser.progress = userSec
        binding.tvUserValue.text = "${userSec}초"

        val (msg, narration) = if (r.isHighRiskBalance)
            "⚠ 균형 검사에서 위험 신호가 있어요" to
            "균형 검사 결과를 알려드릴게요. 안전 기준은 10초이고, ${userSec}초를 유지하셨어요. 위험 신호가 있어요."
        else
            "✓ 균형 검사는 안전해요" to
            "균형 검사 결과를 알려드릴게요. 안전 기준은 10초이고, ${userSec}초를 유지하셨어요. 안전합니다."
        binding.tvJudgement.text = msg
        binding.tvJudgement.setTextColor(if (r.isHighRiskBalance) 0xFFFF9800.toInt() else 0xFF4CAF50.toInt())

        binding.btnNext.isEnabled = false
        ttsManager?.speak(narration) {
            if (_binding != null) binding.btnNext.isEnabled = true
        }
        binding.btnNext.setOnClickListener {
            ttsManager?.stop()
            findNavController().navigate(R.id.action_result_balance_to_chair)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ttsManager?.shutdown()
        ttsManager = null
        _binding = null
    }
}
