package com.fallzero.app.ui.report

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fallzero.app.R
import com.fallzero.app.data.algorithm.BalanceProgressionManager
import com.fallzero.app.data.SessionFlow
import com.fallzero.app.data.db.FallZeroDatabase
import com.fallzero.app.data.db.entity.ExerciseRecord
import com.fallzero.app.data.repository.PRBRepository
import com.fallzero.app.databinding.FragmentReportBinding
import com.fallzero.app.ui.exam.SimpleChartView
import com.fallzero.app.util.ShareHelper
import com.fallzero.app.viewmodel.ReportViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ReportViewModel by activityViewModels()

    private val exerciseNames = mapOf(
        1 to "앉아서 무릎 펴기", 2 to "옆으로 다리 들기", 3 to "뒤로 무릎 굽히기",
        4 to "발뒤꿈치 들기", 5 to "발끝 들기", 6 to "무릎 살짝 굽히기",
        7 to "의자에서 일어서기", 8 to "한 발로 서기"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnShareGuardianReport.setOnClickListener {
            shareGuardianReport()
        }

        loadProgressionStatus()
        observePage2()
        observePage3()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.page1.collect { data ->
                    val b = _binding ?: return@collect
                    data ?: return@collect
                    val latest = data.latestResult
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)

                    if (latest == null) {
                        b.tvLatestRisk.text = getString(R.string.report_no_data)
                        b.tvLatestRisk.setTextColor(0xFF424242.toInt())
                        return@collect
                    }
                    val isHighRisk = latest.finalRiskLevel == "high"
                    b.tvLatestRisk.text = if (isHighRisk) "낙상 위험군" else "낙상 안전군"
                    b.tvLatestRisk.setBackgroundResource(
                        if (isHighRisk) R.drawable.bg_risk_level else R.drawable.bg_risk_low
                    )
                    // text_primary가 노란색이라 옅은 배경 위에서 안 보임 → 위험/안전별 진한 색으로 덮어쓰기.
                    b.tvLatestRisk.setTextColor(
                        if (isHighRisk) 0xFFC62828.toInt() else 0xFF2E7D32.toInt()
                    )
                    b.tvChairDetail.text =
                        "의자 일어서기: ${latest.chairStandCount}회 (기준 ${latest.chairStandNorm}회)" +
                                "\n검사일: ${dateFormat.format(Date(latest.performedAt))}"
                    b.tvBalanceDetail.text =
                        "균형 검사: ${latest.balanceStageReached}단계 · 일렬 ${latest.tandemTimeSec.toInt()}초"
                    val first = data.firstResult
                    b.tvImprovement.text = when {
                        first == null || first.id == latest.id -> "첫 번째 검사 결과입니다."
                        data.isImproved -> "이전 검사 대비 위험군에서 안전군으로 개선되었습니다!"
                        latest.finalRiskLevel == first.finalRiskLevel -> "이전 검사와 동일한 판정입니다."
                        else -> "이전 검사 대비 지속적으로 모니터링이 필요합니다."
                    }
                }
            }
        }

        loadExerciseQualityGraph()
    }

    /** "꾸준한 운동" 섹션 — 주간 이행률 + 최근 28일 세션 카운트 + 최근 세션 날짜 목록. */
    private fun observePage2() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.page2.collect { data ->
                    val b = _binding ?: return@collect
                    data ?: return@collect
                    b.tvWeeklyAdherence.text = data.weeklyAdherenceText
                    b.tvTotalSessions.text = "최근 28일 총 ${data.totalSessions}회 완료"
                    if (data.recentSessions.isEmpty()) {
                        b.tvRecentSessions.text = "아직 운동 기록이 없어요"
                    } else {
                        val dateFormat = SimpleDateFormat("MM/dd (E)", Locale.KOREAN)
                        val sb = StringBuilder("최근 운동:\n")
                        data.recentSessions.take(7).forEach { session ->
                            sb.append("• ${dateFormat.format(Date(session.startedAt))}\n")
                        }
                        b.tvRecentSessions.text = sb.toString().trimEnd()
                    }
                }
            }
        }
    }

    private fun observePage3() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.page3.collect { data ->
                    val b = _binding ?: return@collect
                    data ?: return@collect
                    loadAbilityDetails(b)
                }
            }
        }
    }

    /**
     * 8개 운동 각각 카드 1장 — 카드 안에서 좌우 스와이프로 최근 5개 record를 개별 확인.
     * 평균이 아니라 개별 세션 기록을 그대로 보여줌.
     */
    private suspend fun loadAbilityDetails(b: FragmentReportBinding) {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())
        val prbRepo = PRBRepository(db.prbDao())

        val container = b.layoutAbilityCards
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        SessionFlow.EXERCISE_DISPLAY_ORDER.forEach { exerciseId ->
            val records = db.sessionDao().getRecentRecordsByExercise(userId, exerciseId, 5)
            val prb = prbRepo.getLatestPRB(userId, exerciseId)
            val section = inflater.inflate(R.layout.item_ability_section, container, false)
            bindAbilitySection(section, exerciseId, records, prb?.prbValue)
            container.addView(section)
        }
    }

    /** 운동 1개 섹션 — header(이름+PRB) + 좌우 스와이프 RecyclerView(record당 1페이지) + 페이지 인디케이터. */
    private fun bindAbilitySection(
        section: View,
        exerciseId: Int,
        records: List<ExerciseRecord>,
        prbValue: Float?
    ) {
        val name = exerciseNames[exerciseId] ?: "운동 $exerciseId"
        section.findViewById<TextView>(R.id.tv_ex_name).text = name

        val tvPrb = section.findViewById<TextView>(R.id.tv_prb)
        if (prbValue != null) {
            val unit = when (exerciseId) { in 1..6 -> "°"; 7 -> "%"; else -> "초" }
            tvPrb.text = "기준값: ${"%.1f".format(prbValue)}$unit"
        } else {
            tvPrb.text = "기준값: 측정 전"
        }

        val tvEmpty = section.findViewById<TextView>(R.id.tv_empty)
        val rv = section.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_records)
        val tvPage = section.findViewById<TextView>(R.id.tv_page_indicator)

        if (records.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "⚪ 아직 한 번도 안 해 보셨어요"
            rv.visibility = View.GONE
            tvPage.visibility = View.GONE
            return
        }

        tvEmpty.visibility = View.GONE
        rv.visibility = View.VISIBLE
        tvPage.visibility = View.VISIBLE

        // 시간 효율 baseline — 이 운동의 최근 records 중 성공한 것들의 평균 durationMs.
        val baselineMs: Float? = records
            .filter { it.achievedCount >= it.targetCount && it.durationMs > 0L }
            .takeIf { it.size >= 3 }
            ?.map { it.durationMs.toFloat() }?.average()?.toFloat()

        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            requireContext(),
            androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
            false
        )
        // 스와이프 시 1 페이지씩 스냅
        // 기존 SnapHelper가 있으면 제거 후 새로 부착 (재바인딩 시 중복 부착 방지)
        rv.onFlingListener = null
        androidx.recyclerview.widget.PagerSnapHelper().attachToRecyclerView(rv)

        rv.adapter = AbilityRecordAdapter(records, baselineMs, isBalance = exerciseId == 8)

        fun updatePageIndicator(currentPos: Int) {
            // records는 최신순(내림차순)이라 position 0 = 가장 최근.
            tvPage.text = "최신 ◀ ${currentPos + 1} / ${records.size} ▶ 오래된"
        }
        updatePageIndicator(0)

        // 스크롤이 멈췄을 때 가운데에 있는 페이지 위치를 업데이트.
        rv.clearOnScrollListeners()
        rv.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
                    val pos = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
                        ?: lm.findFirstVisibleItemPosition()
                    if (pos >= 0) updatePageIndicator(pos)
                }
            }
        })
    }

    /** 점수 → 텍스트 색상. 85+ 녹색, 65+ 노랑(text_primary 유지), 그 아래 빨강. */
    private fun scoreColor(score: Int): Int = when {
        score >= 85 -> ContextCompat.getColor(requireContext(), R.color.success)
        score >= 65 -> ContextCompat.getColor(requireContext(), R.color.text_primary)
        else -> ContextCompat.getColor(requireContext(), R.color.error)
    }

    /** 속도 유지력 점수 (QualityScorer.calcSpeedMaintenance와 동일 식). */
    private fun calcSpeedScore(speedLossRate: Float): Int =
        ((1f - speedLossRate) * 100f).toInt().coerceIn(0, 100)

    /** 시간 효율 점수 (QualityScorer.calcTimeEfficiency와 동일 식). */
    private fun calcTimeScore(durationMs: Long, baselineMs: Float?): Int {
        if (baselineMs == null || baselineMs <= 0f || durationMs <= 0L) return 100
        val ratio = durationMs.toFloat() / baselineMs
        if (ratio <= 1.0f) return 100
        return (100f - (ratio - 1.0f) * 80f).toInt().coerceIn(0, 100)
    }

    /** 현재 진급 단계 표시 — 균형 운동(stage 1~5)과 근력 운동(1세트/2세트) 모두. */
    private fun loadProgressionStatus() {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val balanceStage = prefs.getInt("current_set_level", 1).coerceIn(1, 5)
        val balanceLevel = BalanceProgressionManager.getLevel(balanceStage)
        binding.tvBalanceProgression.text =
            "🟢 한 발 서기: ${balanceLevel.description} ${balanceLevel.targetTimeSec.toInt()}초 (${balanceStage}/5단계)"

        // 근력 운동(#1~#7) 세트 단계 — 모두 1세트면 "전체 1세트", 일부 진급 시 운동명 나열
        val advanced = (1..7).filter { id -> prefs.getInt("set_level_ex_$id", 1) >= 2 }
        binding.tvStrengthProgression.text = if (advanced.isEmpty()) {
            "💪 근력 운동: 모두 1세트"
        } else {
            val names = advanced.joinToString(", ") { SessionFlow.exerciseName(it) }
            "💪 2세트 진급: $names\n그 외 운동: 1세트"
        }
    }

    private fun shareGuardianReport() {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            // ── 데이터 수집 ──
            val latestExam = db.examResultDao().getLatestResult(userId)
            val firstExam = db.examResultDao().getFirstResult(userId)
            val recentSessions = db.sessionDao().getRecentCompletedSessions(userId, 28)
            val dayEpochs = db.sessionDao().getCompletedDayEpochs(userId)
            val streak = calculateStreak(dayEpochs)
            val weekStart = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000L
            val weekCount = recentSessions.count { it.startedAt >= weekStart }
            val balanceStage = prefs.getInt("current_set_level", 1).coerceIn(1, 5)
            val balanceLevel = BalanceProgressionManager.getLevel(balanceStage)
            val advanced = (1..7).filter { prefs.getInt("set_level_ex_$it", 1) >= 2 }

            // 운동별 PRB
            val prbRepo = PRBRepository(db.prbDao())
            val prbList = (1..8).mapNotNull { id ->
                val prb = prbRepo.getLatestPRB(userId, id) ?: return@mapNotNull null
                val unit = when (id) { in 1..6 -> "°"; 7 -> "%"; else -> "초" }
                Triple(exerciseNames[id] ?: "운동 $id", prb.prbValue, unit)
            }

            // 마지막 세션 점수·오류
            val latestSession = recentSessions.firstOrNull()
            val latestRecords = latestSession?.let { db.sessionDao().getRecordsBySession(it.id) }
                ?: emptyList()
            val avgScore = if (latestRecords.isNotEmpty())
                latestRecords.map { it.qualityScore }.average().toInt() else null
            val errorTotal = latestRecords.sumOf { it.errorCount }
            val achievedTotal = latestRecords.sumOf { it.achievedCount }

            // 검사 비교
            val examChange = when {
                latestExam == null -> "-"
                firstExam == null || firstExam.id == latestExam.id -> "첫 검사"
                latestExam.finalRiskLevel == "low" && firstExam.finalRiskLevel == "high" -> "위험군 → 안전군으로 개선"
                latestExam.finalRiskLevel == "high" && firstExam.finalRiskLevel == "low" -> "안전군 → 위험군으로 악화"
                else -> "동일 등급 유지"
            }
            val dateLong = SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)
            val dateShort = SimpleDateFormat("M/d (E)", Locale.KOREAN)

            // ── 보고서 생성 ──
            val report = buildString {
                appendLine("[낙상제로] 어르신 운동 종합 현황")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                appendLine("📋 낙상 위험 검사 결과")
                if (latestExam != null) {
                    appendLine("  · 검사일: ${dateLong.format(Date(latestExam.performedAt))}")
                    appendLine("  · 위험 등급: ${if (latestExam.finalRiskLevel == "high") "낙상 위험군" else "낙상 안전군"}")
                    appendLine("  · 의자 일어서기: ${latestExam.chairStandCount}회 (기준 ${latestExam.chairStandNorm}회 이상)")
                    appendLine("  · 균형 검사: ${latestExam.balanceStageReached}단계 도달")
                    appendLine("  · 일렬 자세 유지: ${latestExam.tandemTimeSec.toInt()}초")
                    if (latestExam.oneLegTimeSec > 0f) {
                        appendLine("  · 한 발 서기 유지: ${latestExam.oneLegTimeSec.toInt()}초")
                    }
                    appendLine("  · 이전 검사 대비: $examChange")
                } else {
                    appendLine("  · 아직 검사를 받지 않으셨습니다")
                }
                appendLine()

                appendLine("📅 운동 이행 현황")
                appendLine("  · 연속 운동: ${streak}일")
                appendLine("  · 이번 주: ${weekCount}회 (권장 3회 이상)")
                appendLine("  · 최근 28일 누적: ${recentSessions.size}회")
                if (latestSession != null) {
                    appendLine("  · 마지막 운동: ${dateShort.format(Date(latestSession.startedAt))}")
                } else {
                    appendLine("  · 아직 운동을 시작하지 않으셨습니다")
                }
                appendLine()

                appendLine("📈 훈련 진급 단계")
                appendLine("  · 한 발로 서기: ${balanceLevel.description} ${balanceLevel.targetTimeSec.toInt()}초 (${balanceStage}/5단계)")
                if (advanced.isEmpty()) {
                    appendLine("  · 근력 운동: 모두 1세트")
                } else {
                    val advancedNames = advanced.joinToString(", ") { SessionFlow.exerciseName(it) }
                    appendLine("  · 2세트 진급: $advancedNames")
                    appendLine("  · 그 외 근력 운동: 1세트")
                }
                appendLine()

                if (prbList.isNotEmpty()) {
                    appendLine("💪 운동 능력 (개인 기준값)")
                    prbList.forEach { (name, value, unit) ->
                        // unit이 "%"일 수 있으니 format() 분리 — UnknownFormatConversionException 회피.
                        appendLine("  · $name: ${"%.1f".format(value)}$unit")
                    }
                    appendLine()
                }

                if (avgScore != null && latestSession != null) {
                    appendLine("🎯 최근 운동 평가 (${dateShort.format(Date(latestSession.startedAt))})")
                    appendLine("  · 평균 품질 점수: ${avgScore}점 / 100점")
                    appendLine("  · 수행: ${achievedTotal}회 중 자세 오류 ${errorTotal}회")
                    val errorRate = if (achievedTotal > 0) (errorTotal * 100f / achievedTotal).toInt() else 0
                    when {
                        errorRate == 0 -> appendLine("  · 자세 평가: 매우 정확")
                        errorRate <= 10 -> appendLine("  · 자세 평가: 양호")
                        errorRate <= 20 -> appendLine("  · 자세 평가: 주의 필요")
                        else -> appendLine("  · 자세 평가: 자세 교정 권장")
                    }
                    appendLine()
                }

                appendLine("━━━━━━━━━━━━━━━━━━━━")
                appendLine("작성일: ${dateLong.format(Date())}")
                appendLine("낙상제로 앱에서 전송됨")
            }
            ShareHelper.shareText(requireActivity(), "낙상제로 어르신 운동 현황", report)
        }
    }

    /** 연속 운동 일수 계산 — 오늘 또는 어제 운동했으면 그날부터 거꾸로 연속된 날 카운트 */
    private fun calculateStreak(dayEpochs: List<Long>): Int {
        if (dayEpochs.isEmpty()) return 0
        val todayEpoch = System.currentTimeMillis() / 86400000L
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

    /**
     * 운동 품질 점수 꺾은선 그래프 (최근 5회 세션 평균).
     * 새 6지표(달성·자세·ROM·일관성·속도유지·시간효율)의 산술 평균을 record별로 계산하고,
     * session 내 모든 record의 그 평균을 다시 평균. legacy qualityScore(가중합) 미사용.
     */
    private fun loadExerciseQualityGraph() {
        val prefs = requireActivity().getSharedPreferences("fallzero_prefs", Context.MODE_PRIVATE)
        val userId = prefs.getInt("user_id", 0)
        val db = FallZeroDatabase.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val sessions = db.sessionDao().getRecentCompletedSessions(userId, 5)
            val b = _binding ?: return@launch
            val chart = b.root.findViewById<SimpleChartView>(R.id.chart_exercise_quality)

            if (sessions.isEmpty()) return@launch

            // 시간 효율 baseline은 운동별로 다름. 그래프에 등장하는 운동들에 대해 미리 계산해 cache.
            val allRecords = sessions.flatMap { db.sessionDao().getRecordsBySession(it.id) }
            val exerciseIds = allRecords.map { it.exerciseId }.toSet()
            val baselineCache: Map<Int, Float?> = exerciseIds.associateWith { exerciseId ->
                val recs = db.sessionDao().getRecentRecordsByExercise(userId, exerciseId, 30)
                    .filter { it.achievedCount >= it.targetCount && it.durationMs > 0L }
                if (recs.size >= 3) recs.map { it.durationMs.toFloat() }.average().toFloat() else null
            }

            // 세션별 6지표 산술평균 계산 (오래된 순)
            val scores = sessions.reversed().mapNotNull { session ->
                val records = db.sessionDao().getRecordsBySession(session.id)
                if (records.isEmpty()) null
                else records.map { rec ->
                    val baseline = baselineCache[rec.exerciseId]
                    val sixDimAvg = (
                        rec.completionScore +
                        rec.formScore +
                        rec.romScore +
                        rec.consistencyScore +
                        calcSpeedScore(rec.speedLossRate) +
                        calcTimeScore(rec.durationMs, baseline)
                    ) / 6f
                    sixDimAvg
                }.average().toFloat()
            }

            if (scores.isNotEmpty()) {
                chart?.setData(scores, "#FFFF00")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}