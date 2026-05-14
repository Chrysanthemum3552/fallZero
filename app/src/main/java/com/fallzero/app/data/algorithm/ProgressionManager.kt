package com.fallzero.app.data.algorithm

import com.fallzero.app.data.db.entity.ExerciseRecord

/**
 * 세트 진급 관리자 — PDF "FallZero 개선 진급 알고리즘" 반영.
 *
 * 핵심 설계 원칙:
 *   "성공했는가" 가 아니라 "다음 단계로 올려도 될 만큼 안정적이고 여유 있게 성공했는가" 를 본다.
 *   진급 단계를 늘리는 것이 아니라 진급 판단 파라미터를 보수적으로 확장한다.
 *
 * 3일 연속 다음 조건을 모두 충족 → 1세트 → 2세트 진급:
 *   1. 목표 횟수 달성                    (기존)
 *   2. 자세 오류 0회                     (기존)
 *   3. 동작 일관성 양호 (CV ≤ ~15%)        — consistencyScore ≥ 70
 *   4. 충분한 ROM 활용 (PRB의 60%+)        — romScore ≥ 80
 *   5. 후반부 속도 저하율 ≤ 20%            — speedLossRate ≤ 0.20  (PDF §5)
 *   6. 수행시간이 평소 평균의 1.25배 이하     — DurationRatio ≤ 1.25  (PDF §6)
 *
 * RPE는 사용자 명시 권한으로 제외.
 *
 * 학술 근거:
 *   - NSCA "2 for 2 원칙" (Baechle & Earle, 2008): 연속 안정 성공 시 부하 증가
 *   - Velocity-monitored RT in older adults: set 내 velocity loss 20% 기준
 *   - QualityScorer 4차원 (ROM/Consistency 등)이 이미 계산해 두는 점수 활용
 *
 * 양쪽 다리 운동(#1~#3): 좌우 양쪽 모두 오류 없이 완수해야 인정 (ExerciseViewModel이 합산해 저장).
 */
object ProgressionManager {

    /**
     * ⚠ 시연용 완화 패치 — true이면 직전 운동 1회만으로 "목표 달성 + 자세 오류 0회" 만족 시 즉시 진급.
     * 3일 연속, ROM/일관성/속도/Duration 게이트는 모두 우회. 시연 종료 후 반드시 false로 되돌릴 것.
     * (BalanceProgressionManager.DEMO_MODE 도 동일하게 토글.)
     */
    private const val DEMO_MODE = true

    init {
        if (DEMO_MODE) {
            android.util.Log.w("Progression",
                "⚠ DEMO_MODE=true — 진급 조건 완화 활성. 시연 후 false로 되돌릴 것!")
        }
    }

    /** 동작 일관성 최소 기준 — CV ≤ ~15% 수준 (QualityScorer 기준) */
    private const val MIN_CONSISTENCY_SCORE = 70

    /** ROM 활용 최소 기준 — PRB의 60% 이상 (QualityScorer 기준) */
    private const val MIN_ROM_SCORE = 80

    /** 후반부 속도 저하율 최대 — PDF §5: 20% 초과 시 피로 누적 가능 */
    private const val MAX_SPEED_LOSS_RATE = 0.20f

    /** 수행시간 비율 최대 — PDF §6: 평소의 25% 이상 초과 시 겨우 성공한 상태 */
    private const val MAX_DURATION_RATIO = 1.25f

    /** Duration baseline 산출을 위한 최소 사전 기록 수. 그 미만이면 duration 게이트는 통과로 처리. */
    private const val MIN_PRIOR_FOR_DURATION_BASELINE = 3

    /**
     * 진급 여부 판단.
     * @param records 해당 운동의 기록 목록 (최신순 정렬 권장). 길이가 충분해야 duration baseline 계산 가능.
     * @return true면 2세트로 진급 가능
     */
    fun shouldProgressToTwoSets(records: List<ExerciseRecord>): Boolean {
        if (DEMO_MODE) {
            val latest = records.maxByOrNull { it.performedAt } ?: return false
            return latest.achievedCount >= latest.targetCount && latest.errorCount == 0
        }

        if (records.size < 3) return false

        // 날짜별 최신 기록 수집 (같은 날 여러 번 운동 시 최신 것만)
        val byDay = mutableMapOf<Long, ExerciseRecord>()
        for (r in records.sortedByDescending { it.performedAt }) {
            val dayKey = toDayEpoch(r.performedAt)
            if (!byDay.containsKey(dayKey)) byDay[dayKey] = r
            if (byDay.size == 3) break
        }
        if (byDay.size < 3) return false

        // 3개 날짜가 연속인지 확인
        val sortedDays = byDay.keys.sorted()
        val isConsecutive = sortedDays[1] - sortedDays[0] == 1L && sortedDays[2] - sortedDays[1] == 1L
        if (!isConsecutive) return false

        val candidates = byDay.values.toList()
        val baseline = computeDurationBaseline(records, candidates)

        return candidates.all { r ->
            r.achievedCount >= r.targetCount &&
            r.errorCount == 0 &&
            r.consistencyScore >= MIN_CONSISTENCY_SCORE &&
            r.romScore >= MIN_ROM_SCORE &&
            r.speedLossRate <= MAX_SPEED_LOSS_RATE &&
            passesDurationGate(r, baseline)
        }
    }

    /**
     * PDF §6 — "최근 안정 성공 평균" 수행시간 산출.
     * 후보 3개를 제외한 사전 성공 기록(achievedCount ≥ target & durationMs > 0)의 평균.
     * 사전 기록이 부족하면 null → duration 게이트 자동 통과(관대 처리).
     */
    private fun computeDurationBaseline(
        all: List<ExerciseRecord>,
        candidates: List<ExerciseRecord>
    ): Float? {
        val candidateIds = candidates.mapTo(mutableSetOf()) { it.id }
        val priorSuccess = all.filter {
            it.id !in candidateIds &&
            it.achievedCount >= it.targetCount &&
            it.durationMs > 0L
        }
        if (priorSuccess.size < MIN_PRIOR_FOR_DURATION_BASELINE) return null
        return priorSuccess.map { it.durationMs.toFloat() }.average().toFloat()
    }

    /** baseline 데이터가 없거나 record duration이 0이면 통과(데이터 부족 시 관대). */
    private fun passesDurationGate(r: ExerciseRecord, baseline: Float?): Boolean {
        if (baseline == null || baseline <= 0f || r.durationMs <= 0L) return true
        return r.durationMs / baseline <= MAX_DURATION_RATIO
    }

    private fun toDayEpoch(timestamp: Long): Long = timestamp / (24 * 60 * 60 * 1000L)
}
