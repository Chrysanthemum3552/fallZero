package com.fallzero.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 5,  // v5: ExerciseRecord에 promotedLabel 추가 (진급 발생 회차 배지). v3→v4→v5 모두 데이터 보존 마이그레이션.
    exportSchema = false
)
abstract class FallZeroDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun examResultDao(): ExamResultDao
    abstract fun sessionDao(): SessionDao
    abstract fun prbDao(): PRBDao

    companion object {
        @Volatile private var INSTANCE: FallZeroDatabase? = null

        /** v3 → v4: ExerciseRecord에 repResults(TEXT) 컬럼 추가. 기존 데이터(온보딩·검사·기록) 보존. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercise_records ADD COLUMN repResults TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v4 → v5: ExerciseRecord에 promotedLabel(TEXT) 컬럼 추가. 기존 데이터 보존. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercise_records ADD COLUMN promotedLabel TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): FallZeroDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    FallZeroDatabase::class.java,
                    "fallzero_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)  // 정상 경로: 데이터 보존 마이그레이션
                    .fallbackToDestructiveMigration()             // 백스톱: 마이그레이션 경로 없을 때만 초기화
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
