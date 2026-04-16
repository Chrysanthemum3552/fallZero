package com.fallzero.app.data.repository

import com.fallzero.app.data.db.dao.ExamResultDao
import com.fallzero.app.data.db.entity.ExamResult
import kotlinx.coroutines.flow.Flow

class ExamRepository(private val examResultDao: ExamResultDao) {

    fun getResultsByUser(userId: Int): Flow<List<ExamResult>> =
        examResultDao.getResultsByUser(userId)

    suspend fun saveResult(result: ExamResult): Long =
        examResultDao.insert(result)

    suspend fun getLatestResult(userId: Int): ExamResult? =
        examResultDao.getLatestResult(userId)

    suspend fun getRecentResults(userId: Int, limit: Int = 5): List<ExamResult> =
        examResultDao.getRecentResults(userId, limit)
}
