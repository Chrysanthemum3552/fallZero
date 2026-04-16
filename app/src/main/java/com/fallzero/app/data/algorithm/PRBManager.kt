package com.fallzero.app.data.algorithm

/**
 * PRB (Personal ROM Baseline) 관리자
 * 개인화 자기 보정 방식 - 제안서 §2.3
 *
 * - 첫 훈련 세션 시작 시 각 운동 2회 캘리브레이션 (오류 판정 없음)
 * - 이후 매 세션 최대 측정값이 기존 PRB보다 높을 때만 자동 상향 갱신
 * - 하향 갱신 없음 (컨디션 저하·피로 반영 방지)
 */
object PRBManager {

    /** PRB 캘리브레이션 완료 여부 */
    fun isCalibrated(prbValue: Float): Boolean = prbValue > 0f

    /** 여러 측정값에서 PRB 계산 (중앙값 사용) */
    fun calculateFromMeasurements(measurements: List<Float>): Float {
        if (measurements.isEmpty()) return 0f
        val sorted = measurements.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * PRB 자동 상향 갱신 (상향만)
     * @return 갱신된 PRB 값 (더 높은 쪽)
     */
    fun updateIfHigher(newMeasurement: Float, existingPRB: Float): Float =
        if (newMeasurement > existingPRB) newMeasurement else existingPRB

    /** 계수 조건: 현재 값 ≥ PRB × 80% */
    fun isCountConditionMet(currentValue: Float, prb: Float): Boolean =
        isCalibrated(prb) && currentValue >= prb * 0.80f

    /** 코칭 큐 발동: PRB × 60~79% 구간 → "조금 더 해볼까요?" */
    fun isCoachingCueZone(currentValue: Float, prb: Float): Boolean =
        isCalibrated(prb) && currentValue >= prb * 0.60f && currentValue < prb * 0.80f
}
