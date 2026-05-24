package com.fallzero.app.ui.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.data.algorithm.BalanceProgressionManager
import com.fallzero.app.databinding.FragmentHomeBinding
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.util.CastHelper
import com.fallzero.app.util.ShareHelper
import kotlinx.coroutines.async
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

        showTodayTab()
        binding.btnTabToday.setOnClickListener { showTodayTab() }
        binding.btnTabMenu.setOnClickListener { showMenuTab() }

        binding.btnStartExercise.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_exercise_guide)
        }

        binding.btnStartExam.setOnClickListener {
            // 사용자 명시 8번: 검사 메뉴 화면으로 (전체/단일 stage/의자 선택)
            findNavController().navigate(R.id.action_home_to_exam_menu)
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

    private fun showTodayTab() {
        binding.tabTodayContent.visibility = View.VISIBLE
        binding.tabMenuContent.visibility = View.GONE
        applyActiveTabStyle(binding.btnTabToday, isActive = true)
        applyActiveTabStyle(binding.btnTabMenu, isActive = false)
    }

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
        } else {
            // surface pill 안에서 비활성 탭은 투명 — pill 배경이 비치도록
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.TRANSPARENT)
            btn.setTextColor(ctx.resources.getColor(R.color.text_secondary, null))
        }
        btn.strokeWidth = 0
    }

    private fun loadDashboard() {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())
        val todayStart = getTodayStartMillis()

        viewLifecycleOwner.lifecycleScope.launch {
            val streakDeferred = async { calculateStreak(db, userId) }
            val examDeferred = async { db.examResultDao().getLatestResult(userId) }
            val completedIdsDeferred = async {
                db.sessionDao().getTodayCompletedExerciseIds(userId, todayStart).toSet()
            }

            val streak = streakDeferred.await()
            val latestExam = examDeferred.await()
            val completedIds = completedIdsDeferred.await()

            if (_binding == null) return@launch

            binding.tvStreak.text = streak.toString()

            val strokeWidthPx = (2 * resources.displayMetrics.density).toInt()
            if (latestExam != null) {
                val isHighRisk = latestExam.finalRiskLevel == "high"
                binding.tvRiskLevel.text = if (isHighRisk) "위험군" else "비위험군"
                // 검정 surface 카드 위에서 보이는 밝은 색으로 등급 구분 (알약 배경 제거됨)
                binding.tvRiskLevel.setTextColor(
                    resources.getColor(if (isHighRisk) R.color.error else R.color.success, null)
                )
                binding.cardRisk.strokeWidth = 0
            } else {
                binding.tvRiskLevel.text = "검사 필요"
                binding.tvRiskLevel.setTextColor(resources.getColor(R.color.warning, null))
                binding.cardRisk.strokeColor = resources.getColor(R.color.warning, null)
                binding.cardRisk.strokeWidth = strokeWidthPx
            }

            val doneCount = completedIds.size
            when {
                doneCount >= 8 -> {
                    binding.tvTodayStatus.text = "완료"
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

            binding.tvTodayProgressInline.text = "$doneCount / 8"
            binding.tvTodayProgressInline.setTextColor(
                resources.getColor(
                    if (doneCount >= 8) R.color.success else R.color.primary,
                    null
                )
            )
            binding.pbTodayProgress.progress = doneCount.coerceAtMost(8)

            if (doneCount >= 8) {
                binding.tvStartExerciseMain.text = "오늘 운동 다 했어요"
                binding.tvStartExerciseMeta.text = "한 번 더 하시려면 누르세요"
            } else if (doneCount > 0) {
                // 중간 중단 상태 — 남은 운동 이어서하기
                binding.tvStartExerciseMain.text = "▶ 남은 운동 이어서하기"
                binding.tvStartExerciseMeta.text = "남은 운동 ${8 - doneCount}가지"
            } else {
                binding.tvStartExerciseMain.text = "▶ 운동 시작하기"
                binding.tvStartExerciseMeta.text = "총 8가지 동작 · 약 15분"
            }

            renderChecklist(completedIds)
        }
    }

    private fun renderChecklist(completedIds: Set<Int>) {
        val container = binding.checklistContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val successColor = resources.getColor(R.color.success, null)
        val primaryColor = resources.getColor(R.color.primary, null)
        val primaryText = resources.getColor(R.color.text_primary, null)
        val secondaryText = resources.getColor(R.color.text_secondary, null)
        // 3상태: 완료(✓ success / 옅음) · 다음 차례(● primary / 강조) · 대기(○ secondary / 일반)
        var nextAssigned = false
        for (id in SessionFlow.EXERCISE_DISPLAY_ORDER) {
            val row = inflater.inflate(R.layout.item_exercise_check, container, false)
            val icon = row.findViewById<TextView>(R.id.tv_check_icon)
            val name = row.findViewById<TextView>(R.id.tv_check_name)
            val sublabel = row.findViewById<TextView>(R.id.tv_check_sublabel)
            val status = row.findViewById<TextView>(R.id.tv_check_status)

            val isDone = id in completedIds
            val isNext = !isDone && !nextAssigned
            if (isNext) nextAssigned = true

            when {
                isDone -> {
                    icon.text = "✓"
                    icon.setTextColor(successColor)
                    name.setTextColor(secondaryText)
                    status.text = "완료"
                    status.setTextColor(successColor)
                }
                isNext -> {
                    icon.text = "●"
                    icon.setTextColor(primaryColor)
                    name.setTextColor(primaryText)
                    status.text = "다음"
                    status.setTextColor(primaryColor)
                }
                else -> {
                    icon.text = "○"
                    icon.setTextColor(secondaryText)
                    name.setTextColor(primaryText)
                    status.text = ""
                }
            }
            name.text = SessionFlow.exerciseName(id)
            // 진급 상태 표시 — 균형: "양손 지지 10초", 근력: "1세트" / "2세트"
            sublabel.text = progressionLabelFor(id, prefs)
            container.addView(row)
        }
    }

    /** 운동별 현재 진급 상태 라벨. 균형 운동(#8)은 stage→손지지/시간, 근력은 set_level_ex_<id> 기반. */
    private fun progressionLabelFor(
        exerciseId: Int,
        prefs: android.content.SharedPreferences
    ): String {
        return if (exerciseId == 8) {
            val stage = prefs.getInt("current_set_level", 1).coerceIn(1, 5)
            val level = BalanceProgressionManager.getLevel(stage)
            "${level.description} ${level.targetTimeSec.toInt()}초"
        } else {
            val sets = prefs.getInt("set_level_ex_$exerciseId", 1).coerceIn(1, 2)
            "${sets}세트"
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
        val userName = prefs.getString("user_name", "사용자") ?: "사용자"
        val db = FallZeroDatabase.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val latestExam = db.examResultDao().getLatestResult(userId)
            val streak = calculateStreak(db, userId)
            val todayStart = getTodayStartMillis()
            val todayCompletedIds = db.sessionDao().getTodayCompletedExerciseIds(userId, todayStart).toSet()

            val sessionRepo = com.fallzero.app.data.repository.SessionRepository(db.sessionDao())
            val evals = com.fallzero.app.data.SessionFlow.EXERCISE_DISPLAY_ORDER.map { exId ->
                val records = sessionRepo.getRecentRecordsByExercise(userId, exId, limit = 30)
                val name = exerciseDisplayName(exId)
                if (exId == 8) {
                    val stage = prefs.getInt("current_set_level", 1).coerceIn(1, 5)
                    com.fallzero.app.data.algorithm.ProgressionEvaluator.evaluateBalance(stage, records)
                } else {
                    val setLevel = prefs.getInt("set_level_ex_$exId", 1).coerceIn(1, 2)
                    com.fallzero.app.data.algorithm.ProgressionEvaluator.evaluateStrength(exId, name, setLevel, records)
                }
            }

            val riskLevel = when {
                latestExam == null -> "검사 필요"
                latestExam.finalRiskLevel == "high" -> "위험군"
                else -> "비위험군"
            }
            val isHighRisk = latestExam?.let { it.finalRiskLevel == "high" }
            val today = java.text.SimpleDateFormat("yyyy년 M월 d일", java.util.Locale.KOREA)
                .format(java.util.Date())

            val header = com.fallzero.app.util.GuardianReportRenderer.HeaderInfo(
                userName = userName,
                date = today,
                riskLevel = riskLevel,
                isHighRisk = isHighRisk,
                streakDays = streak,
                todayCompleted = todayCompletedIds.size,
                todayTotal = 8
            )

            // 운동 이행 현황 — 캘린더(히트맵)용. 최근 4주를 덮도록 충분히 가져와 로컬 날짜로 변환.
            val calSessions = db.sessionDao().getRecentCompletedSessions(userId, 60)
            val weekStart = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000L
            val weekCount = calSessions.count { it.startedAt >= weekStart }
            val dayKeyFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA)
            val exercisedDays = calSessions
                .map { dayKeyFormat.format(java.util.Date(it.startedAt)) }
                .toSet()
            val adherence = com.fallzero.app.util.GuardianReportRenderer.AdherenceInfo(
                streakDays = streak,
                weekCount = weekCount,
                todayCompleted = todayCompletedIds.size,
                todayTotal = 8,
                exercisedDays = exercisedDays
            )

            // 훈련 진급 단계 — 운동별 현재 단계를 구체적으로 나열 (전체 루틴 순서)
            val progressionLines = SessionFlow.EXERCISE_DISPLAY_ORDER.map { id ->
                if (id == 8) {
                    val stage = prefs.getInt("current_set_level", 1).coerceIn(1, 5)
                    val lvl = BalanceProgressionManager.getLevel(stage)
                    "${SessionFlow.exerciseName(id)} — ${lvl.description} ${lvl.targetTimeSec.toInt()}초 (${stage}/5단계)"
                } else {
                    val set = prefs.getInt("set_level_ex_$id", 1).coerceIn(1, 2)
                    "${SessionFlow.exerciseName(id)} — ${set}세트 / 최종 2세트"
                }
            }

            // 1페이지: 이행 현황 + 진급 단계 + 최근 5회 추이 / 2페이지: 운동별 능력·진급 현황
            val page1 = com.fallzero.app.util.GuardianReportRenderer.renderPage1(
                header, adherence, progressionLines, evals
            )
            val page2 = com.fallzero.app.util.GuardianReportRenderer.renderPage2(header, evals)
            ShareHelper.shareBitmaps(
                requireActivity(), listOf(page1, page2),
                "fallzero_report_${System.currentTimeMillis()}"
            )
        }
    }

    private fun exerciseDisplayName(id: Int): String = when (id) {
        1 -> "앉아서 무릎 펴기"
        2 -> "옆으로 다리 들기"
        3 -> "뒤로 무릎 굽히기"
        4 -> "발뒤꿈치 들기"
        5 -> "발끝 들기"
        6 -> "무릎 살짝 굽히기"
        7 -> "의자에서 일어서기"
        8 -> "한 발로 서서 균형 잡기"
        else -> "운동 $id"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}