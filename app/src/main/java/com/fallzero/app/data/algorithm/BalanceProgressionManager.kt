package com.fallzero.app.data.algorithm

/**
 * 균형 훈련 진급 체계
 * OEP 공식 매뉴얼 점진적 난이도 원칙 (Campbell & Robertson, 2003)
 *
 * 단계  손 지지  목표시간
 *  1    양손     10초
 *  2    한손     10초
 *  3    없음     10초
 *  4    없음     20초
 *  5    없음     30초  ← OEP 최종 목표
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
}
