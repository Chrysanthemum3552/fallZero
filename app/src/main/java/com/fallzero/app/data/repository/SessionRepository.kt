package com.fallzero.app.data.repository

import com.fallzero.app.data.db.dao.SessionDao
import com.fallzero.app.data.db.entity.ExerciseRecord
import com.fallzero.app.data.db.entity.TrainingSession
import kotlinx.coroutines.flow.Flow

class SessionRepository(private val sessionDao: SessionDao) {

    fun getSessionsByUser(userId: Int): Flow<List<TrainingSession>> =
        sessionDao.getSessionsByUser(userId)

    suspend fun startSession(userId: Int): Long {
        val session = TrainingSession(userId = userId)
        return sessionDao.insertSession(session)
    }

    suspend fun completeSession(session: TrainingSession) {
        sessionDao.updateSession(session.copy(
            isCompleted = true,
            completedAt = System.currentTimeMillis()
        ))
    }

    /** id로 세션 완료 표시 (풀 세션 종료 시) */
    suspend fun markSessionCompleted(sessionId: Int) {
        sessionDao.markSessionCompleted(sessionId, System.currentTimeMillis())
    }

    suspend fun saveRecord(record: ExerciseRecord): Long =
        sessionDao.insertRecord(record)

    /** 진급이 발생한 기록에 진급 메시지 표식 (운동 기록 "🎉 진급!" 배지용). */
    suspend fun markRecordPromoted(recordId: Int, label: String) =
        sessionDao.markRecordPromoted(recordId, label)

    suspend fun getRecentCompletedSessions(userId: Int, limit: Int = 7): List<TrainingSession> =
        sessionDao.getRecentCompletedSessions(userId, limit)

    /** ProgressionManager 진급 판정용 — 운동별 최근 기록 */
    suspend fun getRecentRecordsByExercise(userId: Int, exerciseId: Int, limit: Int = 30): List<ExerciseRecord> =
        sessionDao.getRecentRecordsByExercise(userId, exerciseId, limit)
}
