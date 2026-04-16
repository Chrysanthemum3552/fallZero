package com.fallzero.app.data.db.dao

import androidx.room.*
import com.fallzero.app.data.db.entity.ExamResult
import kotlinx.coroutines.flow.Flow

@Dao
interface ExamResultDao {
    @Insert
    suspend fun insert(examResult: ExamResult): Long

    @Query("SELECT * FROM exam_results WHERE userId = :userId ORDER BY performedAt DESC")
    fun getResultsByUser(userId: Int): Flow<List<ExamResult>>

    @Query("SELECT * FROM exam_results WHERE userId = :userId ORDER BY performedAt DESC LIMIT 1")
    suspend fun getLatestResult(userId: Int): ExamResult?

    @Query("SELECT * FROM exam_results WHERE userId = :userId ORDER BY performedAt ASC LIMIT 1")
    suspend fun getFirstResult(userId: Int): ExamResult?

    @Query("SELECT * FROM exam_results WHERE userId = :userId ORDER BY performedAt DESC LIMIT :limit")
    suspend fun getRecentResults(userId: Int, limit: Int): List<ExamResult>
}
