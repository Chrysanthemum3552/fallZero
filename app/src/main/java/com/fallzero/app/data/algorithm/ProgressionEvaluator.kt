package com.fallzero.app.data.algorithm

import com.fallzero.app.data.db.entity.ExerciseRecord
import kotlin.math.roundToInt

/**
 * 진급 조건 게이트별 평가 — 보호자 보고서 시각화용.
 * ProgressionManager는 boolean만 반환하지만, 이 평가기는 각 게이트의 현재 값과 통과 여부를 모두 반환.
 *
 * 근력 운동(#1~7): 6게이트 (목표 횟수 · 자세 오류 · 일관성 · ROM · 속도 · 시간)
 * 균형 운동(#8):   3게이트 (목표 시간 · 자세 오류 · 흔들림)
 * 3일 연속 모두 통과 시 진급 (DEMO_MODE 제외).
 */
object ProgressionEvaluator {

    data class Gate(
        val label: String,
        val currentText: String,
        val requirementText: String,
        val passed: Boolean
    )

    /** 과거 운동 1회의 결과 — 보호자 보고서 mini-cell용. */
    data class HistoryPoint(
        val performedAt: Long,
        val qualityScore: Int,        // 0~100 (참고용)
        val achievedCount: Int,       // 실제 수행 횟수
        val targetCount: Int,         // 목표 횟수 (균형은 1, 근력은 10)
        val errorCount: Int,
        val passedAllGates: Boolean,
        val dateLabel: String,        // "5/18"
        val isBalance: Boolean
    )

    enum class Trend { IMPROVING, STABLE, DECLINING, UNKNOWN }

    data class Eval(
        val exerciseId: Int,
        val exerciseName: String,
        val currentLevel: Int,        // 근력: 세트 수(1~2), 균형: stage(1~5)
        val maxLevel: Int,            // 근력: 2, 균형: 5
        val hasRecord: Boolean,
        val gates: List<Gate>,
        val passedToday: Boolean,     // 가장 최근 1회 모든 게이트 통과?
        val consecutiveDays: Int,     // 최근 연속 모든 게이트 통과한 일수
        val daysNeeded: Int = 3,      // 진급에 필요한 연속 일수
        val history: List<HistoryPoint> = emptyList(),
        val trend: Trend = Trend.UNKNOWN
    ) {
        val canProgress: Boolean
            get() = currentLevel < maxLevel && consecutiveDays >= daysNeeded
    }

    private const val MIN_CONSISTENCY_SCORE = 70
    private const val MIN_ROM_SCORE = 80
    private const val MAX_SPEED_LOSS_RATE = 0.20f
    private const val MAX_DURATION_RATIO = 1.25f
    private const val MAX_WOBBLE = 0.70f
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun evaluateStrength(
        exerciseId: Int,
        exerciseName: String,
        currentSetLevel: Int,
        records: List<ExerciseRecord>
    ): Eval {
        val latest = records.maxByOrNull { it.performedAt }
        if (latest == null) {
            return Eval(exerciseId, exerciseName, currentSetLevel, 2, false, emptyList(), false, 0)
        }
        val baseline = calcDurationBaseline(records, latest)
        val gates = buildStrengthGates(latest, baseline)
        val passedToday = gates.all { it.passed }
        val consecutive = countConsecutiveQualifiedDaysStrength(records)
        val history = buildHistoryStrength(records)
        val trend = computeTrend(history)
        return Eval(
            exerciseId, exerciseName, currentSetLevel, 2, true, gates,
            passedToday, consecutive, history = history, trend = trend
        )
    }

    fun evaluateBalance(
        currentStage: Int,
        records: List<ExerciseRecord>
    ): Eval {
        val latest = records.maxByOrNull { it.performedAt }
        if (latest == null) {
            return Eval(8, "균형 잡기", currentStage, 5, false, emptyList(), false, 0)
        }
        val gates = buildBalanceGates(latest)
        val passedToday = gates.all { it.passed }
        val consecutive = countConsecutiveQualifiedDaysBalance(records)
        val history = buildHistoryBalance(records)
        val trend = computeTrend(history)
        return Eval(
            8, "균형 잡기", currentStage, 5, true, gates,
            passedToday, consecutive, history = history, trend = trend
        )
    }

    private fun buildHistoryStrength(records: List<ExerciseRecord>, max: Int = 5): List<HistoryPoint> {
        val sorted = records.sortedByDescending { it.performedAt }.take(max).reversed()
        return sorted.map { r ->
            val baseline = calcDurationBaseline(records, r)
            val passed = buildStrengthGates(r, baseline).all { it.passed }
            HistoryPoint(
                r.performedAt, r.qualityScore, r.achievedCount, r.targetCount,
                r.errorCount, passed, shortDate(r.performedAt), isBalance = false
            )
        }
    }

    private fun buildHistoryBalance(records: List<ExerciseRecord>, max: Int = 5): List<HistoryPoint> {
        val sorted = records.sortedByDescending { it.performedAt }.take(max).reversed()
        return sorted.map { r ->
            val passed = buildBalanceGates(r).all { it.passed }
            HistoryPoint(
                r.performedAt, r.qualityScore, r.achievedCount, r.targetCount,
                r.errorCount, passed, shortDate(r.performedAt), isBalance = true
            )
        }
    }

    /** 자연어 요약 한 줄 — 보호자가 직관적으로 파악 가능한 평가 문구. */
    fun summaryLine(eval: Eval): String {
        if (!eval.hasRecord) return "아직 기록이 없습니다"
        if (eval.canProgress) return "✓ 진급 조건 충족! 다음 단계로 진급 가능"
        if (eval.currentLevel >= eval.maxLevel) return "✓ 최종 단계 달성!"
        if (eval.consecutiveDays >= 3) return "${eval.consecutiveDays}일 연속 목표 달성 중 ✓"
        if (eval.history.isEmpty()) return "기록을 더 모아주세요"

        val recentHits = eval.history.count { it.achievedCount >= it.targetCount }
        val total = eval.history.size
        return when (eval.trend) {
            Trend.IMPROVING -> "지난 회보다 향상되었어요 (${recentHits}/${total}회 목표 달성)"
            Trend.DECLINING -> "이전보다 조금 떨어졌어요 (${recentHits}/${total}회 목표 달성)"
            Trend.STABLE -> "꾸준한 상태입니다 (${recentHits}/${total}회 목표 달성)"
            Trend.UNKNOWN -> "${recentHits}/${total}회 목표 달성"
        }
    }

    /** 추세 판정 — 전반부 평균 vs 후반부 평균. ±5점 이상 차이 시 향상/하락, 그 외 안정. */
    private fun computeTrend(history: List<HistoryPoint>): Trend {
        if (history.size < 3) return Trend.UNKNOWN
        val half = history.size / 2
        val firstAvg = history.take(half).map { it.qualityScore }.average()
        val lastAvg = history.takeLast(history.size - half).map { it.qualityScore }.average()
        val diff = lastAvg - firstAvg
        return when {
            diff >= 5 -> Trend.IMPROVING
            diff <= -5 -> Trend.DECLINING
            else -> Trend.STABLE
        }
    }

    private fun shortDate(timeMs: Long): String {
        return java.text.SimpleDateFormat("M/d", java.util.Locale.KOREA)
            .format(java.util.Date(timeMs))
    }

    private fun buildStrengthGates(r: ExerciseRecord, baseline: Float?): List<Gate> {
        val durationRatio = if (baseline != null && baseline > 0f && r.durationMs > 0L)
            r.durationMs / baseline
        else null
        val durationOk = durationRatio == null || durationRatio <= MAX_DURATION_RATIO

        return listOf(
            Gate(
                "목표 횟수 달성",
                "${r.achievedCount} / ${r.targetCount} 회",
                "달성 필요",
                r.achievedCount >= r.targetCount
            ),
            Gate(
                "자세 오류",
                "${r.errorCount} 회",
                "0 회 필요",
                r.errorCount == 0
            ),
            Gate(
                "동작 일관성",
                "${r.consistencyScore} 점",
                "${MIN_CONSISTENCY_SCORE} 점 이상",
                r.consistencyScore >= MIN_CONSISTENCY_SCORE
            ),
            Gate(
                "동작 범위(ROM)",
                "${r.romScore} 점",
                "${MIN_ROM_SCORE} 점 이상",
                r.romScore >= MIN_ROM_SCORE
            ),
            Gate(
                "속도 유지",
                "${(r.speedLossRate * 100).roundToInt()} % 저하",
                "${(MAX_SPEED_LOSS_RATE * 100).toInt()} % 이하",
                r.speedLossRate <= MAX_SPEED_LOSS_RATE
            ),
            Gate(
                "수행 시간",
                when {
                    durationRatio != null -> "평소의 ${(durationRatio * 100).roundToInt()} %"
                    r.durationMs > 0L -> "${r.durationMs / 1000}초 (첫 측정)"
                    else -> "측정 데이터 없음"
                },
                "평소의 ${(MAX_DURATION_RATIO * 100).toInt()} % 이하",
                durationOk
            )
        )
    }

    private fun buildBalanceGates(r: ExerciseRecord): List<Gate> {
        return listOf(
            Gate(
                "목표 시간 유지",
                if (r.achievedCount >= r.targetCount) "달성" else "미달성",
                "목표 시간 유지 필요",
                r.achievedCount >= r.targetCount
            ),
            Gate(
                "자세 오류",
                "${r.errorCount} 회",
                "0 회 필요",
                r.errorCount == 0
            ),
            Gate(
                "흔들림 안정",
                "${(r.balanceWobble * 100).roundToInt()} %",
                "${(MAX_WOBBLE * 100).toInt()} % 이하",
                r.balanceWobble <= MAX_WOBBLE
            )
        )
    }

    /** 사전 성공 기록 평균. 보호자 보고서용으로 완화 — 1회 이상만 있으면 baseline 산출.
     *  ProgressionManager의 보수적 3회 기준과 별개 (진급 판정은 그대로 3회 유지). */
    private fun calcDurationBaseline(all: List<ExerciseRecord>, latest: ExerciseRecord): Float? {
        val priorSuccess = all.filter {
            it.id != latest.id &&
            it.achievedCount >= it.targetCount &&
            it.durationMs > 0L
        }
        if (priorSuccess.isEmpty()) return null
        return priorSuccess.map { it.durationMs.toFloat() }.average().toFloat()
    }

    private fun countConsecutiveQualifiedDaysStrength(records: List<ExerciseRecord>): Int {
        val byDay = latestPerDay(records)
        val sortedDays = byDay.keys.sortedDescending()
        var consecutive = 0
        var prevDay = -1L
        for (day in sortedDays) {
            if (prevDay != -1L && prevDay - day != 1L) break
            val r = byDay[day]!!
            val baseline = calcDurationBaseline(records, r)
            val allOk = buildStrengthGates(r, baseline).all { it.passed }
            if (!allOk) break
            consecutive++
            prevDay = day
        }
        return consecutive
    }

    private fun countConsecutiveQualifiedDaysBalance(records: List<ExerciseRecord>): Int {
        val byDay = latestPerDay(records)
        val sortedDays = byDay.keys.sortedDescending()
        var consecutive = 0
        var prevDay = -1L
        for (day in sortedDays) {
            if (prevDay != -1L && prevDay - day != 1L) break
            val r = byDay[day]!!
            val allOk = buildBalanceGates(r).all { it.passed }
            if (!allOk) break
            consecutive++
            prevDay = day
        }
        return consecutive
    }

    private fun latestPerDay(records: List<ExerciseRecord>): Map<Long, ExerciseRecord> {
        val byDay = mutableMapOf<Long, ExerciseRecord>()
        for (r in records.sortedByDescending { it.performedAt }) {
            val day = r.performedAt / DAY_MS
            byDay.putIfAbsent(day, r)
        }
        return byDay
    }
}
