package com.fallzero.app.data.algorithm

import com.fallzero.app.data.db.entity.ExerciseRecord

/**
 * 세트 진급 관리자 — 단순 룰.
 *
 * 핵심:
 *   직전 1회 운동에서 "목표 횟수 달성" + "전체 반복 중 음성피드백 0건" → 즉시 2세트 진급.
 *
 * 기존 ROM·일관성·속도·시간·자세오류 등 6게이트 지표는 모두 무시한다.
 * 음성피드백(errorMessage·coachingCueMessage)이 한 번도 발화되지 않았다는 것은
 * 모든 반복이 엔진 기준으로 흠 없이 수행됐다는 뜻 — 이를 단일 진급 기준으로 사용.
 *
 * 양쪽 다리 운동(#1·#2·#3·#8): ExerciseViewModel이 좌·우 양측 반복을 단일 리스트로
 * 누적해 repFeedbackFlags에 저장하므로, 본 클래스에서 별도 양측 처리 불필요.
 */
object ProgressionManager {

    /**
     * 진급 여부 판단.
     * @param records 해당 운동의 기록 목록 (정렬 무관). 가장 최근 1건만 사용.
     * @return true면 2세트로 진급 가능
     */
    fun shouldProgressToTwoSets(records: List<ExerciseRecord>): Boolean {
        val latest = records.maxByOrNull { it.performedAt } ?: return false
        if (latest.achievedCount < latest.targetCount) return false
        val flags = latest.repFeedbackFlags
            .split(",")
            .filter { it.isNotBlank() }
        // 반복 데이터가 비어 있으면(=구버전 기록 또는 측정 실패) 진급 불가.
        if (flags.isEmpty()) return false
        return flags.all { it == "0" }
    }
}
