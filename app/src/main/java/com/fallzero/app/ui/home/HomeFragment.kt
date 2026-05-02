package com.fallzero.app.ui.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

        // 탭 토글 — 기본 "오늘 운동" 탭 활성
        showTodayTab()
        binding.btnTabToday.setOnClickListener { showTodayTab() }
        binding.btnTabMenu.setOnClickListener { showMenuTab() }

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

    /** "오늘 운동" 탭 활성화 — 토글 버튼 색 변경 + 콘텐츠 visibility */
    private fun showTodayTab() {
        binding.tabTodayContent.visibility = View.VISIBLE
        binding.tabMenuContent.visibility = View.GONE
        applyActiveTabStyle(binding.btnTabToday, isActive = true)
        applyActiveTabStyle(binding.btnTabMenu, isActive = false)
    }

    /** "메뉴" 탭 활성화 */
    private fun showMenuTab() {
        binding.tabTodayContent.visibility = View.GONE
        binding.tabMenuContent.visibility = View.VISIBLE
        applyActiveTabStyle(binding.btnTabToday, isActive = false)
        applyActiveTabStyle(binding.btnTabMenu, isActive = true)
    }

    private fun applyActiveTabStyle(btn: com.google.android.material.button.MaterialButton, isActive: Boolean) {
        val ctx = requireContext()
        if (isActive) {
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ctx.resources.getColor(R.color.primary, null))
            btn.setTextColor(ctx.resources.getColor(R.color.on_primary, null))
            btn.strokeWidth = 0
        } else {
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ctx.resources.getColor(R.color.surface, null))
            btn.setTextColor(ctx.resources.getColor(R.color.text_secondary, null))
            btn.strokeWidth = (2 * ctx.resources.displayMetrics.density).toInt()
        }
    }

    private fun loadDashboard() {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())
        val todayStart = getTodayStartMillis()

        viewLifecycleOwner.lifecycleScope.launch {
            // 3개 DB 쿼리를 병렬 실행 — sequential 합산보다 ~3배 빠름.
            // 각 쿼리는 독립적이므로 안전하게 async 가능.
            val streakDeferred = async { calculateStreak(db, userId) }
            val examDeferred = async { db.examResultDao().getLatestResult(userId) }
            val completedIdsDeferred = async {
                db.sessionDao().getTodayCompletedExerciseIds(userId, todayStart).toSet()
            }

            val streak = streakDeferred.await()
            val latestExam = examDeferred.await()
            val completedIds = completedIdsDeferred.await()

            // UI 업데이트는 main 스레드 (lifecycleScope 기본은 Main)
            if (_binding == null) return@launch

            binding.tvStreak.text = "${streak}일"

            if (latestExam != null) {
                val isHighRisk = latestExam.finalRiskLevel == "high"
                binding.tvRiskLevel.text = if (isHighRisk) "위험군" else "비위험군"
                binding.tvRiskLevel.setBackgroundResource(
                    if (isHighRisk) R.drawable.bg_risk_level else R.drawable.bg_risk_low
                )
            } else {
                binding.tvRiskLevel.text = "검사 필요"
            }

            val doneCount = completedIds.size
            when {
                doneCount >= 8 -> {
                    binding.tvTodayStatus.text = "완료!"
                    binding.tvTodayStatus.setTextColor(resources.getColor(R.color.success, null))
                }
                doneCount > 0 -> {
                    binding.tvTodayStatus.text = "${doneCount}/8"
                    binding.tvTodayStatus.setTextColor(resources.getColor(R.color.warning, null))
                }
                else -> {
                    binding.tvTodayStatus.text = "0/8"
                    binding.tvTodayStatus.setTextColor(resources.getColor(R.color.warning, null))
                }
            }

            // 8개 운동 체크리스트 — 오늘 한 운동 ID는 ✓, 안 한 건 ○
            renderChecklist(completedIds)
        }
    }

    private fun renderChecklist(completedIds: Set<Int>) {
        val container = binding.checklistContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val successColor = resources.getColor(R.color.success, null)
        val warningColor = resources.getColor(R.color.warning, null)
        val primaryText = resources.getColor(R.color.text_primary, null)
        val secondaryText = resources.getColor(R.color.text_secondary, null)
        for (id in 1..8) {
            val row = inflater.inflate(R.layout.item_exercise_check, container, false)
            val icon = row.findViewById<TextView>(R.id.tv_check_icon)
            val name = row.findViewById<TextView>(R.id.tv_check_name)
            val status = row.findViewById<TextView>(R.id.tv_check_status)
            val isDone = id in completedIds
            icon.text = if (isDone) "✓" else "○"
            icon.setTextColor(if (isDone) successColor else warningColor)
            name.text = SessionFlow.exerciseName(id)
            name.setTextColor(if (isDone) secondaryText else primaryText)
            status.text = if (isDone) "완료" else ""
            container.addView(row)
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
