package com.fallzero.app.data.algorithm

import com.fallzero.app.data.db.entity.ExerciseRecord

/**
 * 세트 진급 관리자
 * NSCA '2 for 2 원칙' (Baechle & Earle, 2008) 기반 보수적 적용:
 * → 3일 연속 목표 횟수를 오류 없이 완수 시 1세트 → 2세트 진급
 *
 * 양쪽 다리 운동(#1~#3): 좌우 양쪽 모두 오류 없이 완수해야 인정
 */
object ProgressionManager {

    /**
     * 진급 여부 판단
     * @param records 해당 운동의 기록 목록 (최신순 정렬 권장)
     * @return true면 2세트로 진급 가능
     */
    fun shouldProgressToTwoSets(records: List<ExerciseRecord>): Boolean {
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

        // 3일 모두 목표 달성 + 오류 없음
        return byDay.values.all { it.achievedCount >= it.targetCount && it.errorCount == 0 }
    }

    private fun toDayEpoch(timestamp: Long): Long = timestamp / (24 * 60 * 60 * 1000L)
}
