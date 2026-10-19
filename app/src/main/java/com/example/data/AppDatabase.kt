package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.trade.TradeEntity
import com.example.data.trade.TradeDao
import com.example.data.user.User
import com.example.data.user.UserDao

@Database(entities = [TradeEntity::class, User::class, AnalysisEntity::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tradeDao(): TradeDao
    abstract fun userDao(): UserDao
    abstract fun analysisDao(): AnalysisDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trade_journal ADD COLUMN closeTimestamp INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                        CREATE TABLE IF NOT EXISTS analysis_history (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            symbol TEXT NOT NULL,
                            timeframe TEXT NOT NULL,
                            strategy TEXT NOT NULL,
                            dateMs INTEGER NOT NULL,
                            winRateConfidence REAL NOT NULL,
                            signal TEXT NOT NULL,
                            report TEXT NOT NULL
                        )
                    """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "destinedfx_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigrationOnDowngrade(false)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
