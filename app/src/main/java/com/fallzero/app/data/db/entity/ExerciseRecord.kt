package com.fallzero.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exercise_records")
data class ExerciseRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: Int,
    val exerciseId: Int,       // 운동 번호 (1~8)
    val setLevel: Int,         // 현재 세트 수준
    val targetCount: Int,
    val achievedCount: Int,
    /**
     * 반복별 음성피드백 발생 여부 — CSV ("0"/"1"). 길이 = 실제 수행된 반복 수.
     * "0" = 그 반복 중 어떤 음성 안내도 발화되지 않음(=잘 수행).
     * "1" = 그 반복 중 errorMessage 또는 coachingCueMessage가 한 번이라도 검출됨.
     * 단순 진급 룰("전체 반복에서 피드백 0건이어야 진급")의 단일 입력.
     */
    val repFeedbackFlags: String = "",
    val performedAt: Long = System.currentTimeMillis()
)
