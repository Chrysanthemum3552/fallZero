package com.fallzero.app.data.algorithm

import kotlin.math.sqrt

/**
 * 다차원 운동 품질 점수 계산기 — 진급 6게이트와 1:1 통합된 6차원.
 *
 * 6개 차원으로 운동 품질을 평가합니다:
 * 1. 달성도 (Completion)        — 목표 횟수 대비 실제 수행 비율               20%
 * 2. 자세 정확도 (Form)          — 보상 동작(오류) 없이 수행한 비율           25%
 * 3. ROM 활용도 (ROM)           — PRB 대비 실제 동작 크기                  20%
 * 4. 동작 일관성 (Consistency)    — 반복 간 동작 크기의 균일도 (CV 기반)         15%
 * 5. 속도 유지력 (SpeedKeep)      — 후반부 속도 저하 없음 (PDF §5)             10%
 * 6. 시간 효율 (TimeEff)         — 평소 수행 시간 대비 빠르게 완수 (PDF §6)       10%
 *
 * 진급 판정과 동일한 6지표를 사용. 진급은 각 지표 점수 ≥ 임계값.
 *
 * 학술 근거:
 * - ROM: Schoenfeld & Grgic, JSCR 2020
 * - Consistency CV: Stergiou & Decker, JOSPT 2011
 * - Form (보상 동작): Sahrmann 2002
 * - Speed loss / RIR-velocity: NSCA 2-for-2 원칙 + Velocity-monitored RT (older adults)
 * - Duration ratio: PDF §6 (겨우 성공 판별)
 */
object QualityScorer {

    data class QualityBreakdown(
        val totalScore: Int,         // 0~100 총합
        val completionScore: Int,    // 0~100 달성도
        val formScore: Int,          // 0~100 자세 정확도
        val romScore: Int,           // 0~100 ROM 활용도 (균형 운동은 안정성)
        val consistencyScore: Int,   // 0~100 일관성    (균형 운동은 유지시간)
        val speedScore: Int,         // 0~100 속도 유지력 (1 - speedLossRate) 기반
        val timeScore: Int,          // 0~100 시간 효율  (1 / durationRatio) 기반
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
    /**
     * @param speedLossRate PDF §5 — 후반부 속도 저하율(0~1+). 데이터 부족 시 0f 권장.
     * @param durationMs    이번 운동 실제 수행 시간(ms). 0이면 시간 점수 기본 100.
     * @param baselineDurationMs 평소 평균 수행 시간(ms). null 또는 0이면 시간 점수 기본 100.
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
        targetHoldSec: Float? = null,
        speedLossRate: Float = 0f,
        durationMs: Long = 0L,
        baselineDurationMs: Float? = null
    ): QualityBreakdown {
        val completion = calcCompletion(achievedCount, targetCount)
        val form = calcForm(achievedCount, errorCount)
        val rom = if (balanceWobble != null) calcBalanceStability(balanceWobble)
                  else calcROM(repMetrics, prbValue, metricIsDecreasing)
        val consistency = if (bestHoldSec != null && targetHoldSec != null && targetHoldSec > 0f)
                  calcHoldDuration(bestHoldSec, targetHoldSec)
                  else calcConsistency(repMetrics)
        val speed = calcSpeedMaintenance(speedLossRate)
        val time = calcTimeEfficiency(durationMs, baselineDurationMs)

        // 가중합: 달성도 20% + 자세 25% + ROM(or 안정성) 20% + 일관성(or 유지시간) 15% + 속도 10% + 시간 10%
        val total = (completion * 0.20f + form * 0.25f + rom * 0.20f +
                     consistency * 0.15f + speed * 0.10f + time * 0.10f)
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

        return QualityBreakdown(total, completion, form, rom, consistency, speed, time, avgRatio)
    }

    /**
     * 속도 유지력 점수 (PDF §5) — 후반부 속도 저하율(0~1+)에서 변환.
     *   score = (1 - speedLossRate) × 100, 0~100 clamp
     *   loss 0%  → 100점
     *   loss 20% → 80점  ← 진급 게이트 임계값과 일치
     *   loss 50% → 50점
     *   loss 100%+ → 0점
     */
    private fun calcSpeedMaintenance(speedLossRate: Float): Int {
        return ((1f - speedLossRate) * 100f).toInt().coerceIn(0, 100)
    }

    /**
     * 시간 효율 점수 (PDF §6) — 평소 평균 수행 시간 대비 비율에서 변환.
     *   ratio = durationMs / baselineDurationMs
     *   ratio ≤ 1.0  → 100점 (평소만큼 또는 더 빠름)
     *   ratio = 1.25 → 80점  ← 진급 게이트 임계값과 일치
     *   ratio = 2.25 → 0점
     * 데이터 부족(baseline 없거나 durationMs=0) → 100점 (관대).
     */
    private fun calcTimeEfficiency(durationMs: Long, baselineMs: Float?): Int {
        if (baselineMs == null || baselineMs <= 0f || durationMs <= 0L) return 100
        val ratio = durationMs.toFloat() / baselineMs
        if (ratio <= 1.0f) return 100
        return (100f - (ratio - 1.0f) * 80f).toInt().coerceIn(0, 100)
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
