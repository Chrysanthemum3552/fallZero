package com.fallzero.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "prb_values")
data class PRBValue(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    val exerciseId: Int,    // 운동 번호 (1~8)
    val prbValue: Float,    // PRB 기준 각도/거리
    val measuredAt: Long = System.currentTimeMillis()
)
