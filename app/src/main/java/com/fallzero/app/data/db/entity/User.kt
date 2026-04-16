package com.fallzero.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val gender: String,         // "male" or "female"
    val age: Int,
    // CDC STEADI 설문 3문항 (온보딩 시 1회 저장, 재검사 시 재사용)
    val steadiQ1: Boolean = false,  // 지난 1년간 낙상 경험
    val steadiQ2: Boolean = false,  // 서 있거나 걸을 때 불안정감
    val steadiQ3: Boolean = false,  // 낙상에 대한 두려움
    val isOnboardingComplete: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
