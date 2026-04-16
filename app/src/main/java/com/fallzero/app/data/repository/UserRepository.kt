package com.fallzero.app.data.repository

import com.fallzero.app.data.db.dao.UserDao
import com.fallzero.app.data.db.entity.User
import kotlinx.coroutines.flow.Flow

class UserRepository(private val userDao: UserDao) {

    fun getLatestUser(): Flow<User?> = userDao.getLatestUser()

    suspend fun saveUser(gender: String, age: Int): Long {
        val user = User(gender = gender, age = age)
        return userDao.insert(user)
    }

    suspend fun saveSteadiResults(userId: Int, q1: Boolean, q2: Boolean, q3: Boolean) {
        userDao.saveSteadiResults(userId, q1, q2, q3)
    }

    suspend fun updateOnboardingComplete(userId: Int) {
        userDao.updateOnboardingComplete(userId)
    }

    suspend fun getUserById(userId: Int): User? = userDao.getUserById(userId)
}
