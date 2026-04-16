package com.fallzero.app.data.db.dao

import androidx.room.*
import com.fallzero.app.data.db.entity.ExerciseRecord
import com.fallzero.app.data.db.entity.TrainingSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: TrainingSession): Long

    @Update
    suspend fun updateSession(session: TrainingSession)

    @Insert
    suspend fun insertRecord(record: ExerciseRecord): Long

    @Query("SELECT * FROM training_sessions WHERE userId = :userId ORDER BY startedAt DESC")
    fun getSessionsByUser(userId: Int): Flow<List<TrainingSession>>

    @Query("SELECT * FROM exercise_records WHERE sessionId = :sessionId")
    suspend fun getRecordsBySession(sessionId: Int): List<ExerciseRecord>

    @Query("SELECT * FROM training_sessions WHERE userId = :userId AND isCompleted = 1 ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecentCompletedSessions(userId: Int, limit: Int): List<TrainingSession>

    /** 오늘 완료된 세션이 있는지 확인 */
    @Query("SELECT COUNT(*) FROM training_sessions WHERE userId = :userId AND isCompleted = 1 AND startedAt >= :todayStartMillis")
    suspend fun getTodayCompletedCount(userId: Int, todayStartMillis: Long): Int

    /** 최근 N일 간 날짜별 완료 세션 수 (연속일 계산용) */
    @Query("SELECT DISTINCT(startedAt / 86400000) as dayEpoch FROM training_sessions WHERE userId = :userId AND isCompleted = 1 ORDER BY dayEpoch DESC")
    suspend fun getCompletedDayEpochs(userId: Int): List<Long>
}
