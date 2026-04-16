package com.fallzero.app.data.repository

import com.fallzero.app.data.db.dao.PRBDao
import com.fallzero.app.data.db.entity.PRBValue
import kotlinx.coroutines.flow.Flow

class PRBRepository(private val prbDao: PRBDao) {

    suspend fun savePRB(userId: Int, exerciseId: Int, prbValue: Float): Long {
        val prb = PRBValue(userId = userId, exerciseId = exerciseId, prbValue = prbValue)
        return prbDao.insert(prb)
    }

    suspend fun getLatestPRB(userId: Int, exerciseId: Int): PRBValue? =
        prbDao.getLatestPRB(userId, exerciseId)

    fun getAllPRBByUser(userId: Int): Flow<List<PRBValue>> =
        prbDao.getAllPRBByUser(userId)
}
