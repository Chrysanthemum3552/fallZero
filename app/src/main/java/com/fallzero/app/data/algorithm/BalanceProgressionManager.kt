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
     * 균형 운동 단계 진급 판단 — 단순 룰.
     *
     * 직전 1회 운동에서 "목표 시간 달성" + "유지 동안 음성피드백 0건" → 즉시 다음 stage.
     * 균형 운동은 1 "rep"이므로
     * repFeedbackFlags 길이도 1. 유지 시간 동안 errorMessage 또는 coachingCueMessage가
     * 한 번도 발화되지 않았다는 단일 기준.
     */
    fun shouldProgressStage(records: List<ExerciseRecord>): Boolean {
        val latest = records.maxByOrNull { it.performedAt } ?: return false
        if (latest.achievedCount < latest.targetCount) return false
        val flags = latest.repFeedbackFlags.split(",").filter { it.isNotBlank() }
        if (flags.isEmpty()) return false
        return flags.all { it == "0" }
    }
}
