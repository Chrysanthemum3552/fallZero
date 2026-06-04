package com.fallzero.app.data.algorithm

import com.fallzero.app.data.db.entity.ExerciseRecord

/**
 * 진급 조건 평가 — 보호자 보고서 시각화용.
 * ProgressionManager는 boolean만 반환하지만, 이 평가기는 게이트별 현재값·통과여부 + 연속 현황을 반환.
 *
 * 진급 규칙(2026-06 개정 — ProgressionManager와 동일):
 *   "깨끗한 완료(목표 횟수 달성 + 자세 오류 0회)"를 **최근 3회 연속**하면 진급.
 *   근력(#1~7): 목표 횟수 · 자세 오류 2게이트 / 균형(#8): 목표 시간 · 자세 오류 2게이트.
 *   (기존 ROM·일관성·속도·시간·흔들림 게이트와 연속 '일수' 기준은 제거됨.)
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
        val targetCount: Int,         // 목표 횟수 (균형은 1, 근력은 10~20)
        val errorCount: Int,
        val passedAllGates: Boolean,  // = 깨끗한 완료 여부
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
        val passedToday: Boolean,     // 가장 최근 1회 깨끗한 완료?
        val consecutiveDays: Int,     // 최근 '깨끗한 완료'가 연속된 횟수 (날짜 아님 — 기록 기준)
        val daysNeeded: Int = 3,      // 진급에 필요한 연속 횟수
        val history: List<HistoryPoint> = emptyList(),
        val trend: Trend = Trend.UNKNOWN
    ) {
        val canProgress: Boolean
            get() = currentLevel < maxLevel && consecutiveDays >= daysNeeded
    }

    /** 깨끗한 완료 = 목표 횟수 달성 + 자세 오류 0회. (ProgressionManager와 동일 기준) */
    private fun isClean(r: ExerciseRecord): Boolean =
        r.achievedCount >= r.targetCount && r.errorCount == 0

    fun evaluateStrength(
        exerciseId: Int,
        exerciseName: String,
        currentSetLevel: Int,
        records: List<ExerciseRecord>
    ): Eval {
        val latest = records.maxByOrNull { it.performedAt }
            ?: return Eval(exerciseId, exerciseName, currentSetLevel, 2, false, emptyList(), false, 0)
        val gates = buildStrengthGates(latest)
        val history = buildHistory(records, isBalance = false)
        return Eval(
            exerciseId, exerciseName, currentSetLevel, 2, true, gates,
            passedToday = gates.all { it.passed },
            consecutiveDays = countConsecutiveClean(records),
            history = history, trend = computeTrend(history)
        )
    }

    fun evaluateBalance(
        currentStage: Int,
        records: List<ExerciseRecord>
    ): Eval {
        val latest = records.maxByOrNull { it.performedAt }
            ?: return Eval(8, "균형 잡기", currentStage, 5, false, emptyList(), false, 0)
        val gates = buildBalanceGates(latest)
        val history = buildHistory(records, isBalance = true)
        return Eval(
            8, "균형 잡기", currentStage, 5, true, gates,
            passedToday = gates.all { it.passed },
            consecutiveDays = countConsecutiveClean(records),
            history = history, trend = computeTrend(history)
        )
    }

    /** 근력 운동 게이트 — 목표 횟수 + 자세 오류 (2개). */
    private fun buildStrengthGates(r: ExerciseRecord): List<Gate> = listOf(
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
        )
    )

    /** 균형 운동 게이트 — 목표 시간 + 자세 오류 (2개). */
    private fun buildBalanceGates(r: ExerciseRecord): List<Gate> = listOf(
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
        )
    )

    private fun buildHistory(records: List<ExerciseRecord>, isBalance: Boolean, max: Int = 5): List<HistoryPoint> {
        val sorted = records.sortedByDescending { it.performedAt }.take(max).reversed()
        return sorted.map { r ->
            HistoryPoint(
                r.performedAt, r.qualityScore, r.achievedCount, r.targetCount,
                r.errorCount, isClean(r), shortDate(r.performedAt), isBalance
            )
        }
    }

    /**
     * 가장 최근 기록부터 '깨끗한 완료'가 몇 회 연속인지 (첫 비깨끗에서 중단).
     * 이 값 ≥ 3 = "최근 3회 모두 깨끗"이므로 ProgressionManager의 진급 조건과 동일.
     */
    private fun countConsecutiveClean(records: List<ExerciseRecord>): Int {
        var c = 0
        for (r in records.sortedByDescending { it.performedAt }) {
            if (!isClean(r)) break
            c++
        }
        return c
    }

    /** 자연어 요약 한 줄 — 보호자가 직관적으로 파악 가능한 평가 문구. */
    fun summaryLine(eval: Eval): String {
        if (!eval.hasRecord) return "아직 기록이 없습니다"
        if (eval.canProgress) return "✓ 진급 조건 충족! 다음 단계로 진급 가능"
        if (eval.currentLevel >= eval.maxLevel) return "✓ 최종 단계 달성!"
        if (eval.consecutiveDays >= 3) return "${eval.consecutiveDays}회 연속 깨끗하게 완료 중 ✓"
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
}
