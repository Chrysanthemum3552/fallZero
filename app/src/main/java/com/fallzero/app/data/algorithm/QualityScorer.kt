package com.fallzero.app.data.algorithm

import kotlin.math.sqrt

/**
 * 다차원 운동 품질 점수 계산기
 *
 * 4개 차원으로 운동 품질을 평가합니다:
 * 1. 달성도 (Completion)  — 목표 횟수 대비 실제 수행 비율
 * 2. 자세 정확도 (Form)   — 보상 동작(오류) 없이 수행한 비율
 * 3. ROM 활용도 (ROM)     — PRB 대비 실제 동작 크기 (얼마나 충분히 움직였는지)
 * 4. 동작 일관성 (Consistency) — 반복 간 동작 크기의 균일도 (CV 기반)
 *
 * 총합 점수 = 달성도×25% + 자세정확도×30% + ROM활용도×25% + 일관성×20%
 *
 * 학술 근거:
 * - ROM 활용도: 충분한 가동범위 사용이 근력 향상에 필수 (Schoenfeld & Grgic, JSCR, 2020)
 * - 동작 일관성: 낮은 CV는 운동 제어 능력을 반영 (Stergiou & Decker, JOSPT, 2011)
 * - 보상 동작: 잘못된 자세는 운동 효과를 감소시킴 (Sahrmann, 2002)
 */
object QualityScorer {

    data class QualityBreakdown(
        val totalScore: Int,         // 0~100 총합
        val completionScore: Int,    // 0~100 달성도
        val formScore: Int,          // 0~100 자세 정확도
        val romScore: Int,           // 0~100 ROM 활용도
        val consistencyScore: Int,   // 0~100 일관성
        val avgMetricRatio: Float    // PRB 대비 평균 동작 비율
    )

    /**
     * @param achievedCount 실제 수행 횟수
     * @param targetCount 목표 횟수
     * @param errorCount 오류(보상 동작) 발생 횟수
     * @param repMetrics 각 rep의 extreme 동작 지표 (각도/거리 등)
     * @param prbValue 해당 운동의 PRB (개인 기준값)
     * @param metricIsDecreasing true면 작을수록 좋은 메트릭 (ChairStand D%). ratio 계산 시 prb/avg 사용.
     * @param balanceWobble 균형 운동 전용 — sway/threshold 평균 비율 (0~1+). null이면 ROM 슬롯에 기존 ROM 계산 사용.
     *                      비-null이면 ROM 슬롯에 "안정성" 점수 (1 - wobble) 사용.
     * @param bestHoldSec 균형 운동 전용 — 최대 연속 유지 시간(초). targetHoldSec와 함께 사용.
     * @param targetHoldSec 균형 운동 전용 — 목표 시간(초). 비-null이면 Consistency 슬롯에 "유지시간 비율" 점수 사용.
     *
     * 균형 운동(#8)은 정지 자세 유지라 ROM/일관성 개념이 의미 없으므로 같은 슬롯을
     * 안정성/유지시간으로 재해석. 컬럼 스키마는 유지하되 의미가 운동별로 달라짐.
     */
    fun calculate(
        achievedCount: Int,
        targetCount: Int,
        errorCount: Int,
        repMetrics: List<Float>,
        prbValue: Float,
        metricIsDecreasing: Boolean = false,
        balanceWobble: Float? = null,
        bestHoldSec: Float? = null,
        targetHoldSec: Float? = null
    ): QualityBreakdown {
        val completion = calcCompletion(achievedCount, targetCount)
        val form = calcForm(achievedCount, errorCount)
        val rom = if (balanceWobble != null) calcBalanceStability(balanceWobble)
                  else calcROM(repMetrics, prbValue, metricIsDecreasing)
        val consistency = if (bestHoldSec != null && targetHoldSec != null && targetHoldSec > 0f)
                  calcHoldDuration(bestHoldSec, targetHoldSec)
                  else calcConsistency(repMetrics)

        // 가중합: 달성도 25% + 자세 30% + ROM(or 안정성) 25% + 일관성(or 유지시간) 20%
        val total = (completion * 0.25f + form * 0.30f + rom * 0.25f + consistency * 0.20f)
            .toInt().coerceIn(0, 100)

        // avgMetricRatio: 일반 운동은 PRB 대비 평균 동작. 균형 운동은 유지시간 비율을 대신 저장.
        val avgRatio = when {
            bestHoldSec != null && targetHoldSec != null && targetHoldSec > 0f ->
                (bestHoldSec / targetHoldSec).coerceAtMost(1f)
            prbValue > 0f && repMetrics.isNotEmpty() -> {
                val avg = repMetrics.average().toFloat()
                if (metricIsDecreasing && avg > 0f) prbValue / avg else avg / prbValue
            }
            else -> 0f
        }

        return QualityBreakdown(total, completion, form, rom, consistency, avgRatio)
    }

    /**
     * 균형 운동 안정성 점수 — 흔들림 비율(0~1+)을 100점 만점 점수로 변환.
     * wobble 0 = 임계값 대비 흔들림 없음 → 100점.
     * wobble 1+ = 임계값 근처 평균 → 0점.
     */
    private fun calcBalanceStability(wobble: Float): Int {
        return ((1f - wobble.coerceIn(0f, 1f)) * 100).toInt().coerceIn(0, 100)
    }

    /**
     * 균형 운동 유지시간 비율 점수 — 최대 연속 유지 시간 / 목표 시간. 100% 초과는 100점으로 clamp.
     * bestSec ≥ targetSec → 100점, 비례 감소.
     */
    private fun calcHoldDuration(bestSec: Float, targetSec: Float): Int {
        if (targetSec <= 0f) return 100
        return ((bestSec / targetSec).coerceAtMost(1f) * 100).toInt().coerceIn(0, 100)
    }

    /** 달성도: 목표 횟수 대비 비율. 100% 초과 불가. */
    private fun calcCompletion(achieved: Int, target: Int): Int {
        if (target <= 0) return 100
        return ((achieved.toFloat() / target) * 100).toInt().coerceIn(0, 100)
    }

    /**
     * 자세 정확도: 오류 없이 수행한 rep 비율.
     * 0회 수행이면 0점.
     */
    private fun calcForm(achieved: Int, errors: Int): Int {
        if (achieved <= 0) return 0
        val correctReps = (achieved - errors).coerceAtLeast(0)
        return ((correctReps.toFloat() / achieved) * 100).toInt().coerceIn(0, 100)
    }

    /**
     * ROM 활용도: PRB 대비 평균 동작 크기.
     * PRB의 70% 이상이면 만점 → 100점.
     * PRB의 50~69%이면 코칭 큐 구간 → 60~99점.
     * PRB의 50% 미만이면 부족 → 비례 감소.
     *
     * 노인 대상 — calibration 시 최대 노력 측정값(PRB)을 본 운동에서 항상 도달하기 어려우므로
     * 임계값을 80%/60% → 70%/50% 로 완화. 정상 자세에서 ROM 점수가 부당하게 낮게 나오는 문제 해결.
     *
     * PRB가 없거나(0) 메트릭이 없으면 기본 70점 (캘리브레이션 미완료).
     *
     * @param metricIsDecreasing true면 작을수록 좋음 (D%). ratio = prb / avg.
     */
    private fun calcROM(metrics: List<Float>, prb: Float, metricIsDecreasing: Boolean): Int {
        if (prb <= 0f || metrics.isEmpty()) return 70
        val avgMetric = metrics.average().toFloat()
        if (avgMetric <= 0f) return 0
        val ratio = if (metricIsDecreasing) prb / avgMetric else avgMetric / prb
        return when {
            ratio >= 0.70f -> 100
            ratio >= 0.50f -> (60 + (ratio - 0.50f) / 0.20f * 40).toInt()
            else -> (ratio / 0.50f * 60).toInt()
        }.coerceIn(0, 100)
    }

    /**
     * 동작 일관성: 변동계수(CV) 기반.
     * CV = 표준편차 / 평균 × 100
     * CV ≤ 10%: 매우 일관됨 → 100점
     * CV 10~25%: 양호 → 70~99점
     * CV 25~40%: 보통 → 40~69점
     * CV > 40%: 불일관 → 비례 감소
     *
     * 노인 대상 — 사람의 자연스러운 rep-to-rep 변동(CV 15~25%)을 양호로 인정.
     * 기존 임계값(5/15/30)은 운동선수 기준에 가까워 일반 사용자에서 점수가 부당하게 낮게 나옴 → 10/25/40으로 완화.
     *
     * 2회 미만이면 일관성 판정 불가 → 기본 80점.
     */
    private fun calcConsistency(metrics: List<Float>): Int {
        if (metrics.size < 2) return 80
        val mean = metrics.average().toFloat()
        if (mean <= 0.01f) return 80
        // 작은 신호 운동(ToeRaise/CalfRaise: mean ~0.03~0.06)은 픽셀 노이즈가 상대적으로 커서
        // CV 판정 신뢰도 낮음 → 기본 80점. 각도 운동(mean 20~150°)은 영향 없음.
        if (mean < 0.1f) return 80
        val variance = metrics.map { (it - mean) * (it - mean) }.average().toFloat()
        val sd = sqrt(variance)
        val cv = (sd / mean) * 100f
        return when {
            cv <= 10f -> 100
            cv <= 25f -> (100 - (cv - 10f) / 15f * 30).toInt()
            cv <= 40f -> (70 - (cv - 25f) / 15f * 30).toInt()
            else -> (40 - (cv - 40f) / 20f * 40).toInt()
        }.coerceIn(0, 100)
    }
}
