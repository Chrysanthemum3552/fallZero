package com.fallzero.app.data.db.dao

import androidx.room.*
import com.fallzero.app.data.db.entity.PRBValue
import kotlinx.coroutines.flow.Flow

@Dao
interface PRBDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(prbValue: PRBValue): Long

    @Query("SELECT * FROM prb_values WHERE userId = :userId AND exerciseId = :exerciseId ORDER BY measuredAt DESC LIMIT 1")
    suspend fun getLatestPRB(userId: Int, exerciseId: Int): PRBValue?

    @Query("SELECT * FROM prb_values WHERE userId = :userId ORDER BY measuredAt DESC")
    fun getAllPRBByUser(userId: Int): Flow<List<PRBValue>>
}
