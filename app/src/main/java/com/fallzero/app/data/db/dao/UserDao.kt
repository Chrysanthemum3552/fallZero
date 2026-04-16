package com.fallzero.app.data.db.dao

import androidx.room.*
import com.fallzero.app.data.db.entity.User
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: User): Long

    @Query("SELECT * FROM users ORDER BY id DESC LIMIT 1")
    fun getLatestUser(): Flow<User?>

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserById(userId: Int): User?

    @Query("UPDATE users SET isOnboardingComplete = 1 WHERE id = :userId")
    suspend fun updateOnboardingComplete(userId: Int)

    @Query("UPDATE users SET steadiQ1 = :q1, steadiQ2 = :q2, steadiQ3 = :q3 WHERE id = :userId")
    suspend fun saveSteadiResults(userId: Int, q1: Boolean, q2: Boolean, q3: Boolean)

    @Update
    suspend fun update(user: User)
}
