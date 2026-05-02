package com.fallzero.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.fallzero.app.data.db.dao.ExamResultDao
import com.fallzero.app.data.db.dao.PRBDao
import com.fallzero.app.data.db.dao.SessionDao
import com.fallzero.app.data.db.dao.UserDao
import com.fallzero.app.data.db.entity.ExamResult
import com.fallzero.app.data.db.entity.ExerciseRecord
import com.fallzero.app.data.db.entity.PRBValue
import com.fallzero.app.data.db.entity.TrainingSession
import com.fallzero.app.data.db.entity.User

@Database(
    entities = [
        User::class,
        ExamResult::class,
        TrainingSession::class,
        ExerciseRecord::class,
        PRBValue::class
    ],
    version = 2,  // v2: ExamResult.oneLegTimeSec 추가
    exportSchema = false
)
abstract class FallZeroDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun examResultDao(): ExamResultDao
    abstract fun sessionDao(): SessionDao
    abstract fun prbDao(): PRBDao

    companion object {
        @Volatile private var INSTANCE: FallZeroDatabase? = null

        fun getInstance(context: Context): FallZeroDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    FallZeroDatabase::class.java,
                    "fallzero_database"
                )
                    .fallbackToDestructiveMigration() // 개발 단계: 스키마 변경 시 초기화
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
