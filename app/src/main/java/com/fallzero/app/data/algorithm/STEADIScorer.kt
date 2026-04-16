package com.fallzero.app.data.algorithm

/**
 * CDC STEADI 기준 낙상 위험 판정
 * 출처: CDC STEADI Stay Independent Brochure + 30-Second Chair Stand Test
 *
 * 판정 원칙: 설문 3문항 / 의자일어서기 / 탠덤균형검사 중
 *           하나라도 기준 미달 → 위험군("high"), 전부 통과 → 비위험군("low")
 */
object STEADIScorer {

    data class ExamRiskResult(
        val isSurveyHighRisk: Boolean,       // Q1||Q2||Q3 중 하나라도 "예"
        val isChairStandHighRisk: Boolean,   // 달성 횟수 < 연령·성별 기준
        val isBalanceHighRisk: Boolean,      // 탠덤(3단계) 유지시간 < 10초
        val chairStandNorm: Int,             // 해당 연령·성별 기준값
        val finalRiskLevel: String           // "high" or "low"
    )

    // (여성기준, 남성기준) - CDC STEADI + Jones et al. 1999
    private val chairStandNorms: Map<IntRange, Pair<Int, Int>> = mapOf(
        (60..64) to Pair(12, 14),
        (65..69) to Pair(11, 12),
        (70..74) to Pair(10, 12),
        (75..79) to Pair(10, 11),
        (80..84) to Pair(9, 10),
        (85..89) to Pair(8, 8),
        (90..94) to Pair(4, 7)
    )

    fun evaluate(
        steadiQ1: Boolean, steadiQ2: Boolean, steadiQ3: Boolean,
        age: Int,
        gender: String,          // "male" or "female"
        chairStandCount: Int,
        tandemTimeSec: Float     // ★3단계 탠덤 유지시간
    ): ExamRiskResult {
        val isSurveyHighRisk = steadiQ1 || steadiQ2 || steadiQ3
        val norm = getChairStandNorm(age, gender)
        val isChairStandHighRisk = chairStandCount < norm
        val isBalanceHighRisk = tandemTimeSec < 10f
        val finalRisk = if (isSurveyHighRisk || isChairStandHighRisk || isBalanceHighRisk) "high" else "low"
        return ExamRiskResult(isSurveyHighRisk, isChairStandHighRisk, isBalanceHighRisk, norm, finalRisk)
    }

    fun getChairStandNorm(age: Int, gender: String): Int {
        val entry = chairStandNorms.entries.firstOrNull { age in it.key }
            ?: return if (gender == "male") 7 else 4  // 90세 이상 기본값
        return if (gender == "male") entry.value.second else entry.value.first
    }

    /** 검사 결과에 따른 초기 세트 수: 위험군=1세트, 비위험군=2세트 */
    fun getInitialSetCount(riskLevel: String): Int = if (riskLevel == "high") 1 else 2
}
