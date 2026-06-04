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

    /** 진급에 필요한 연속 "깨끗한 완료" 횟수. (ProgressionManager와 동일 규칙) */
    const val REQUIRED_CLEAN_STREAK = 3

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
     * 균형 운동 단계 진급 판단 (2026-06 개정 — ProgressionManager와 동일 규칙).
     * 해당 운동의 가장 최근 3개 기록이 모두 "깨끗한 완료"이면 다음 stage로 진급:
     *   · 깨끗한 완료 = 목표 시간 달성(achievedCount ≥ targetCount) AND 자세 오류 0회(errorCount == 0)
     *   날짜(연속일)는 따지지 않음. 흔들림 게이트도 제거(완료+오류0만으로 판정).
     * @param records 운동 #8의 기록 목록 (최신순 정렬 권장)
     */
    fun shouldProgressStage(records: List<ExerciseRecord>): Boolean {
        if (records.size < REQUIRED_CLEAN_STREAK) return false
        val recent = records.sortedByDescending { it.performedAt }.take(REQUIRED_CLEAN_STREAK)
        return recent.all { it.achievedCount >= it.targetCount && it.errorCount == 0 }
    }
}
