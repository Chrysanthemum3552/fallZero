package com.fallzero.app.data.algorithm

import com.fallzero.app.data.db.entity.ExerciseRecord

/**
 * 균형 훈련 진급 체계
 * OEP 공식 매뉴얼 점진적 난이도 원칙 (Campbell & Robertson, 2003) + PDF 개선안 §9.
 *
 * 단계  손 지지  목표시간
 *  1    양손     10초
 *  2    한손     10초
 *  3    없음     10초
 *  4    없음     20초
 *  5    없음     30초  ← OEP 최종 목표
 *
 * 진급 조건 (PDF §9, RPE 제외):
 *   목표 시간 달성 + 흔들림 기준 통과 + 최근 3회 안정 성공 → 다음 stage
 */
object BalanceProgressionManager {

    /**
     * ⚠ 시연용 완화 패치 — true이면 직전 운동 1회만으로 "목표 달성 + 자세 오류 0회" 만족 시 즉시 stage 진급.
     * 3일 연속, 흔들림 게이트는 모두 우회. 시연 종료 후 반드시 false로 되돌릴 것.
     * (ProgressionManager.DEMO_MODE 도 동일하게 토글.)
     */
    private const val DEMO_MODE = true

    init {
        if (DEMO_MODE) {
            android.util.Log.w("Progression",
                "⚠ DEMO_MODE=true (Balance) — 진급 조건 완화 활성. 시연 후 false로 되돌릴 것!")
        }
    }

    /** 흔들림 최대 — sway/threshold 평균이 이 값 이하여야 안정 성공으로 인정.
     *  BalanceEngine.balanceWobble은 0~1+ 범위 (1.0 = 임계값 근처 평균). 0.70이면 상당히 안정. */
    private const val MAX_WOBBLE = 0.70f

    data class BalanceLevel(
        val stage: Int,
        val description: String,
        val handSupport: HandSupport,
        val targetTimeSec: Float
    )

    enum class HandSupport { BOTH_HANDS, ONE_HAND, NO_SUPPORT }

    val stages = listOf(
        BalanceLevel(1, "양손 지지",  HandSupport.BOTH_HANDS, 10f),
        BalanceLevel(2, "한손 지지",  HandSupport.ONE_HAND,   10f),
        BalanceLevel(3, "손 없이",    HandSupport.NO_SUPPORT, 10f),
        BalanceLevel(4, "손 없이",    HandSupport.NO_SUPPORT, 20f),
        BalanceLevel(5, "손 없이",    HandSupport.NO_SUPPORT, 30f)
    )

    fun getLevel(stage: Int): BalanceLevel = stages.getOrElse(stage - 1) { stages.last() }

    fun getTargetTime(stage: Int): Float = getLevel(stage).targetTimeSec

    fun getNextStage(currentStage: Int): Int = (currentStage + 1).coerceAtMost(stages.size)

    fun isFinalStage(stage: Int): Boolean = stage >= stages.size

    fun getCompletionMessage(stage: Int): String = when {
        isFinalStage(stage) -> "완벽해요! OEP 최종 목표를 달성했습니다!"
        else -> "${getLevel(stage).description} 단계를 완료했습니다! 다음 단계로 진급해요."
    }

    /**
     * 균형 운동 단계 진급 판단 (PDF §9).
     * 3일 연속 다음 조건을 모두 충족 → 다음 stage:
     *   1. 목표 시간 달성 (achievedCount ≥ targetCount, 즉 1)
     *   2. 자세 오류 0회
     *   3. 흔들림(balanceWobble) ≤ MAX_WOBBLE
     * @param records 운동 #8의 기록 목록 (최신순 정렬 권장)
     */
    fun shouldProgressStage(records: List<ExerciseRecord>): Boolean {
        if (DEMO_MODE) {
            val latest = records.maxByOrNull { it.performedAt } ?: return false
            return latest.achievedCount >= latest.targetCount && latest.errorCount == 0
        }

        if (records.size < 3) return false
        val byDay = mutableMapOf<Long, ExerciseRecord>()
        for (r in records.sortedByDescending { it.performedAt }) {
            val dayKey = r.performedAt / (24 * 60 * 60 * 1000L)
            if (!byDay.containsKey(dayKey)) byDay[dayKey] = r
            if (byDay.size == 3) break
        }
        if (byDay.size < 3) return false
        val sortedDays = byDay.keys.sorted()
        val isConsecutive = sortedDays[1] - sortedDays[0] == 1L && sortedDays[2] - sortedDays[1] == 1L
        if (!isConsecutive) return false
        return byDay.values.all {
            it.achievedCount >= it.targetCount &&
            it.errorCount == 0 &&
            it.balanceWobble <= MAX_WOBBLE
        }
    }
}
