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

    /** 시연용 더미 삽입 시 — 해당 사용자의 모든 운동 기록 삭제(깨끗한 슬레이트). */
    @Query("DELETE FROM exercise_records WHERE sessionId IN (SELECT id FROM training_sessions WHERE userId = :userId)")
    suspend fun deleteAllRecordsForUser(userId: Int)

    /** 진급이 발생한 기록에 진급 메시지 표식 — 운동 기록 화면 "🎉 진급!" 배지용. */
    @Query("UPDATE exercise_records SET promotedLabel = :label WHERE id = :recordId")
    suspend fun markRecordPromoted(recordId: Int, label: String)

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

    /**
     * 특정 사용자의 특정 운동 최근 기록. ProgressionManager의 3일 연속 안정 성공 판정용.
     * limit은 30(=한 달치) 정도로 넉넉히 잡아 DurationRatio baseline까지 함께 산출 가능하게 함.
     */
    @Query("""
        SELECT er.* FROM exercise_records er
        INNER JOIN training_sessions ts ON er.sessionId = ts.id
        WHERE ts.userId = :userId AND er.exerciseId = :exerciseId
        ORDER BY er.performedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentRecordsByExercise(userId: Int, exerciseId: Int, limit: Int = 30): List<ExerciseRecord>
}
