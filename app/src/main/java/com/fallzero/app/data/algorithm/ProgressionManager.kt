package com.fallzero.app.data.algorithm

import com.fallzero.app.data.db.entity.ExerciseRecord

/**
 * 세트 진급 관리자.
 *
 * 진급 규칙(2026-06 개정 — 사용자 확정):
 *   "오류 피드백 없이 운동을 3번 연속 완료" → 1세트 → 2세트 진급.
 *   = 해당 운동의 **가장 최근 3개 기록**이 모두 "깨끗한 완료"이면 진급.
 *     · 깨끗한 완료 = 목표 횟수 달성(achievedCount ≥ targetCount) AND 자세 오류 0회(errorCount == 0)
 *   날짜(연속일)는 따지지 않는다 — "연속"은 기록 순서상 연속(가장 최근 3개)을 의미.
 *   코칭 cue("더 펴주세요" 등 ROM 부족)는 오류가 아니므로 깨끗한 완료를 막지 않는다(errorCount에 안 잡힘).
 *
 * 보수성: NSCA "2 for 2 원칙"(Baechle & Earle, 2008)을 보수적으로 적용 — 2회가 아닌 3회 연속을 요구.
 *
 * 시연: 설정의 "더미 삽입" 버튼이 깨끗한 더미 기록 2개를 미리 넣어, 시연 당일 1회만 깨끗이 수행하면
 *       (더미2 + 실연1 = 최근 3개 모두 깨끗) 진급이 발생하도록 설계됨.
 *
 * 양쪽 다리 운동(#1~#3): 좌우 양쪽 모두 오류 없이 완수해야 인정 (ExerciseViewModel이 합산해 저장).
 */
object ProgressionManager {

    /** 진급에 필요한 연속 "깨끗한 완료" 횟수. */
    const val REQUIRED_CLEAN_STREAK = 3

    /**
     * 진급 여부 판단.
     * @param records 해당 운동의 기록 목록 (최신순 정렬 권장).
     * @return true면 2세트로 진급 가능 (가장 최근 3개 기록이 모두 깨끗한 완료).
     */
    fun shouldProgressToTwoSets(records: List<ExerciseRecord>): Boolean {
        if (records.size < REQUIRED_CLEAN_STREAK) return false
        val recent = records.sortedByDescending { it.performedAt }.take(REQUIRED_CLEAN_STREAK)
        return recent.all { isCleanCompletion(it) }
    }

    /** 깨끗한 완료 = 목표 횟수 달성 + 자세 오류 0회. */
    private fun isCleanCompletion(r: ExerciseRecord): Boolean =
        r.achievedCount >= r.targetCount && r.errorCount == 0
}
