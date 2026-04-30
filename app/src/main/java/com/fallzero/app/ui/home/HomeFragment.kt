package com.fallzero.app.ui.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentHomeBinding
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.util.CastHelper
import com.fallzero.app.util.ShareHelper
import com.fallzero.app.viewmodel.ExamViewModel
import kotlinx.coroutines.launch
import java.util.Calendar

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // 임시 더미 결과용
    private val examViewModel: ExamViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sf = com.fallzero.app.data.SessionFlow
        if (sf.pendingAutoForward) {
            sf.pendingAutoForward = false
            view.post {
                if (isAdded) findNavController().navigate(R.id.action_home_to_exam)
            }
            return
        }
        if (sf.sessionType != com.fallzero.app.data.SessionFlow.SessionType.NONE) {
            sf.reset()
        }

        binding.btnStartExercise.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_exercise_guide)
        }

        binding.btnStartExam.setOnClickListener {
            // 임시: 더미 결과 주입 후 결과 화면 바로 이동
            // 나중에 실제 검사 연동 시 아래 두 줄 삭제하고 주석 해제
            examViewModel.injectDummyResult()
            findNavController().navigate(R.id.action_global_exam_result)

            // 실제 검사 코드 (나중에 활성화)
            // val isDebug = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
            //     .getBoolean("debug_mode", false)
            // if (isDebug) SessionFlow.startExamChairStandOnly()
            // else SessionFlow.startExamSession()
            // findNavController().navigate(R.id.action_home_to_exam)
        }

        binding.btnViewReport.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_report)
        }
        binding.btnSettings.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_settings)
        }
        binding.btnCastTv.setOnClickListener {
            CastHelper.openScreenCast(requireActivity())
        }
        binding.btnShareGuardian.setOnClickListener {
            shareGuardianReport()
        }

        loadDashboard()
    }

    private fun loadDashboard() {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val streak = calculateStreak(db, userId)
            binding.tvStreak.text = "${streak}일"

            val latestExam = db.examResultDao().getLatestResult(userId)
            if (latestExam != null) {
                val isHighRisk = latestExam.finalRiskLevel == "high"
                binding.tvRiskLevel.text = if (isHighRisk) "위험군" else "비위험군"
                binding.tvRiskLevel.setBackgroundResource(
                    if (isHighRisk) R.drawable.bg_risk_level else R.drawable.bg_risk_low
                )
            } else {
                binding.tvRiskLevel.text = "검사 필요"
            }

            val todayStart = getTodayStartMillis()
            val todayCount = db.sessionDao().getTodayCompletedCount(userId, todayStart)
            if (todayCount > 0) {
                binding.tvTodayStatus.text = "완료!"
                binding.tvTodayStatus.setTextColor(resources.getColor(R.color.success, null))
            } else {
                binding.tvTodayStatus.text = "아직 안 했어요"
                binding.tvTodayStatus.setTextColor(resources.getColor(R.color.warning, null))
            }
        }
    }

    private suspend fun calculateStreak(db: FallZeroDatabase, userId: Int): Int {
        val dayEpochs = db.sessionDao().getCompletedDayEpochs(userId)
        if (dayEpochs.isEmpty()) return 0
        val todayEpoch = System.currentTimeMillis() / 86400000
        val mostRecentDay = dayEpochs.first()
        if (mostRecentDay < todayEpoch - 1) return 0
        var streak = 1
        for (i in 1 until dayEpochs.size) {
            if (dayEpochs[i] == dayEpochs[i - 1] - 1) streak++
            else if (dayEpochs[i] == dayEpochs[i - 1]) continue
            else break
        }
        return streak
    }

    private fun getTodayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun shareGuardianReport() {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val latestExam = db.examResultDao().getLatestResult(userId)
            val streak = calculateStreak(db, userId)
            val todayStart = getTodayStartMillis()
            val todayCount = db.sessionDao().getTodayCompletedCount(userId, todayStart)

            val report = buildString {
                appendLine("[낙상제로] 보호자 알림")
                appendLine("━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("연속 운동: ${streak}일")
                appendLine("오늘 운동: ${if (todayCount > 0) "완료" else "미완료"}")
                if (latestExam != null) {
                    appendLine("위험 등급: ${if (latestExam.finalRiskLevel == "high") "위험군" else "비위험군"}")
                    appendLine("의자 일어서기: ${latestExam.chairStandCount}회")
                    appendLine("균형 검사: ${latestExam.balanceStageReached}단계")
                }
                appendLine()
                appendLine("━━━━━━━━━━━━━━━")
                appendLine("낙상제로 앱에서 전송됨")
            }

            ShareHelper.shareText(requireActivity(), "낙상제로 보호자 알림", report)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}