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
        val normSec = 10
        val userSec = r.tandemTimeSec.toInt().coerceIn(0, normSec)

        binding.pbNorm.max = normSec
        binding.pbNorm.progress = normSec
        binding.tvNormValue.text = "${normSec}초"

        binding.pbUser.max = normSec
        binding.pbUser.progress = userSec
        binding.tvUserValue.text = "${userSec}초"

        val (msg, narration) = if (r.isHighRiskBalance)
            "균형 검사에서 위험 신호가 있어요" to
                    "균형 검사 결과를 알려드릴게요. 한 발을 다른 발 앞에 일렬로 놓는 일렬 서기 자세를 10초 이상 유지해야 안전합니다. ${userSec}초를 유지하셨어요. 위험 신호가 있어요."
        else
            "균형 검사는 안전해요" to
                    "균형 검사 결과를 알려드릴게요. 한 발을 다른 발 앞에 일렬로 놓는 일렬 서기 자세를 10초 이상 유지해야 안전합니다. ${userSec}초를 유지하셨어요. 안전합니다."

        binding.tvJudgement.text = msg
        binding.tvJudgement.setTextColor(
            if (r.isHighRiskBalance) 0xFFFF9800.toInt() else 0xFF4CAF50.toInt()
        )

        // TTS 재생 + 버튼은 바로 활성화 (TTS 실패해도 이동 가능)
        ttsManager?.speak(narration)
        binding.btnNext.isEnabled = true
        binding.btnNext.setOnClickListener {
            ttsManager?.stop()
            findNavController().navigate(R.id.action_result_balance_to_chair)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { ttsManager?.shutdown() } catch (_: Exception) {}
        ttsManager = null
        _binding = null
    }
}