package com.fallzero.app.ui.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.databinding.FragmentHomeBinding
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.util.CastHelper
import com.fallzero.app.util.ShareHelper
import kotlinx.coroutines.launch
import java.util.Calendar

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 세션 상태 분기:
        // - pendingAutoForward 플래그가 켜져 있으면 (온보딩 설문 직후) 1회만 자동 forward
        // - 그 외 활성 세션이 남아 있으면 사용자가 세션을 뒤로 눌러 빠져나온 것 → reset
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

        // 네비게이션 버튼들
        binding.btnStartExercise.setOnClickListener {
            // 운동 가이드 목록으로 이동 (사용자가 개별 운동 또는 전체 루틴을 선택)
            findNavController().navigate(R.id.action_home_to_exercise_guide)
        }
        binding.btnStartExam.setOnClickListener {
            // 디버그 모드: 균형 건너뛰고 의자 직행
            val isDebug = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
                .getBoolean("debug_mode", false)
            if (isDebug) {
                SessionFlow.startExamChairStandOnly()
            } else {
                SessionFlow.startExamSession()
            }
            findNavController().navigate(R.id.action_home_to_exam)
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

        // 대시보드 데이터 로드
        loadDashboard()
    }

    private fun loadDashboard() {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())

        // view lifecycle 기준 — onDestroyView 후 자동 취소 (binding NPE 방지)
        viewLifecycleOwner.lifecycleScope.launch {
            // 연속 운동일 계산
            val streak = calculateStreak(db, userId)
            binding.tvStreak.text = "${streak}일"

            // 위험 등급
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

            // 오늘 운동 여부
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
        // dayEpochs: 운동한 날의 epoch day 목록 (내림차순 정렬)
        val dayEpochs = db.sessionDao().getCompletedDayEpochs(userId)
        if (dayEpochs.isEmpty()) return 0

        val todayEpoch = System.currentTimeMillis() / 86400000
        val mostRecentDay = dayEpochs.first()

        // 오늘이나 어제 운동하지 않았으면 streak = 0
        if (mostRecentDay < todayEpoch - 1) return 0

        // 가장 최근 날부터 연속일 세기
        var streak = 1
        for (i in 1 until dayEpochs.size) {
            if (dayEpochs[i] == dayEpochs[i - 1] - 1) {
                streak++
            } else if (dayEpochs[i] == dayEpochs[i - 1]) {
                // 같은 날 중복 → 무시
                continue
            } else {
                break
            }
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
