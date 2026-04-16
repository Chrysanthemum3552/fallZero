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
    val performedAt: Long = System.currentTimeMillis()
)
