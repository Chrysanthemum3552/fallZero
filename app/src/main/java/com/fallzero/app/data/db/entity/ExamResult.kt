package com.fallzero.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exam_results")
data class ExamResult(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    // 검사 1: 30초 앉았다 일어서기
    val chairStandCount: Int,
    val chairStandNorm: Int,              // 연령·성별 기준값
    val isHighRiskChairStand: Boolean,    // count < norm
    // 검사 2: 4단계 균형검사
    val balanceStageReached: Int,         // 통과한 마지막 단계 (1~4)
    val tandemTimeSec: Float,             // ★3단계(탠덤) 유지 시간
    val isHighRiskBalance: Boolean,       // tandemTimeSec < 10초
    // STEADI 설문 (재검사 시에는 User.steadi* 값 재사용)
    val isHighRiskSurvey: Boolean,        // Q1||Q2||Q3
    // 최종 판정
    val finalRiskLevel: String,           // "high" or "low"
    val performedAt: Long = System.currentTimeMillis()
)
