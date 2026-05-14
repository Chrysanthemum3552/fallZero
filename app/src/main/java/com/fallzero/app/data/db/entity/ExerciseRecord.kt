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
    val errorCount: Int = 0,
    val qualityScore: Int = 0,       // 0~100 총합 품질 점수
    val completionScore: Int = 0,    // 달성도 점수 (0~100)
    val formScore: Int = 0,          // 자세 정확도 점수 (0~100)
    val romScore: Int = 0,           // ROM 활용도 점수 (0~100)
    val consistencyScore: Int = 0,   // 동작 일관성 점수 (0~100)
    val avgMetricRatio: Float = 0f,  // PRB 대비 평균 동작 크기 비율
    // ─── 개선 진급 알고리즘용 추가 필드 (PDF §5, §6, §9) ───
    val durationMs: Long = 0L,         // 운동 시작 ~ 목표 수행 완료까지의 총 시간 (ms)
    val speedLossRate: Float = 0f,     // 후반 3rep vs 초반 3rep RepSpeed 중앙값 기반 저하율 (0~1)
    val balanceWobble: Float = 0f,     // 균형 운동 sway/threshold 평균 비율 (운동 #8 전용, 0~1+)
    val performedAt: Long = System.currentTimeMillis()
)
