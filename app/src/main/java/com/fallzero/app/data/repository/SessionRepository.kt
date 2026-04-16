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

    suspend fun saveRecord(record: ExerciseRecord): Long =
        sessionDao.insertRecord(record)

    suspend fun getRecentCompletedSessions(userId: Int, limit: Int = 7): List<TrainingSession> =
        sessionDao.getRecentCompletedSessions(userId, limit)
}
