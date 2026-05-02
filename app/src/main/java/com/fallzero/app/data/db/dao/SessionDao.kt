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

    /** 세션 id로 완료 표시 (Q8a/Q8b — 풀 세션 종료 시 호출) */
    @Query("UPDATE training_sessions SET isCompleted = 1, completedAt = :completedAt WHERE id = :sessionId")
    suspend fun markSessionCompleted(sessionId: Int, completedAt: Long)

    /** 오늘 어떤 운동 ID들을 했는지 조회 (Q8b 체크리스트용 — JOIN으로 오늘 시작된 세션의 운동 기록만 조회) */
    @Query("""
        SELECT DISTINCT er.exerciseId
        FROM exercise_records er
        INNER JOIN training_sessions ts ON er.sessionId = ts.id
        WHERE ts.userId = :userId AND ts.startedAt >= :todayStartMillis
    """)
    suspend fun getTodayCompletedExerciseIds(userId: Int, todayStartMillis: Long): List<Int>

    /** 최근 N일 간 날짜별 완료 세션 수 (연속일 계산용) */
    @Query("SELECT DISTINCT(startedAt / 86400000) as dayEpoch FROM training_sessions WHERE userId = :userId AND isCompleted = 1 ORDER BY dayEpoch DESC")
    suspend fun getCompletedDayEpochs(userId: Int): List<Long>
}
