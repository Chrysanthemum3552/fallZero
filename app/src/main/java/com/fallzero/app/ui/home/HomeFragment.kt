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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}