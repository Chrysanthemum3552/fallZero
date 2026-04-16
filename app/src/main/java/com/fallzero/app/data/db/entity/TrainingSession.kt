package com.fallzero.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "training_sessions")
data class TrainingSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: Int,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val isCompleted: Boolean = false
)
