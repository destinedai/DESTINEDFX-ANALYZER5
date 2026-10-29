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
import com.example.data.deriv.DerivExecutionDao
import com.example.data.deriv.DerivExecutionEntity

@Database(entities = [TradeEntity::class, User::class, AnalysisEntity::class, DerivExecutionEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tradeDao(): TradeDao
    abstract fun userDao(): UserDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun derivExecutionDao(): DerivExecutionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE trade_journal ADD COLUMN closeTimestamp INTEGER") }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS analysis_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, symbol TEXT NOT NULL, timeframe TEXT NOT NULL, strategy TEXT NOT NULL, dateMs INTEGER NOT NULL, winRateConfidence REAL NOT NULL, signal TEXT NOT NULL, report TEXT NOT NULL)""".trimIndent())
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE users ADD COLUMN uid TEXT NOT NULL DEFAULT ''") }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS deriv_execution_intents (signalId TEXT NOT NULL PRIMARY KEY, product TEXT NOT NULL, symbol TEXT NOT NULL, direction TEXT NOT NULL, stake REAL NOT NULL, currency TEXT NOT NULL, multiplier INTEGER NOT NULL, stopLossAmount REAL NOT NULL, takeProfitAmount REAL NOT NULL, strategyId TEXT NOT NULL, strategyVersion TEXT NOT NULL, signalTimestampMs INTEGER NOT NULL, requestId INTEGER, proposalId TEXT, contractId INTEGER, buyTransactionId INTEGER, sellTransactionId INTEGER, purchasePrice REAL, settlementValue REAL, settlementProfit REAL, status TEXT NOT NULL, lastError TEXT, updatedAtMs INTEGER NOT NULL)""".trimIndent())
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE deriv_execution_intents_v6 (accountId TEXT NOT NULL, accountMode TEXT NOT NULL, signalId TEXT NOT NULL, product TEXT NOT NULL, symbol TEXT NOT NULL, direction TEXT NOT NULL, stake REAL NOT NULL, currency TEXT NOT NULL, multiplier INTEGER NOT NULL, stopLossAmount REAL NOT NULL, takeProfitAmount REAL NOT NULL, strategyId TEXT NOT NULL, strategyVersion TEXT NOT NULL, signalTimestampMs INTEGER NOT NULL, requestId INTEGER, proposalId TEXT, submissionStage TEXT NOT NULL, contractId INTEGER, buyTransactionId INTEGER, sellTransactionId INTEGER, purchasePrice REAL, settlementValue REAL, settlementProfit REAL, status TEXT NOT NULL, lastError TEXT, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, PRIMARY KEY(accountId, accountMode, signalId))""".trimIndent())
                db.execSQL("""INSERT INTO deriv_execution_intents_v6 (accountId, accountMode, signalId, product, symbol, direction, stake, currency, multiplier, stopLossAmount, takeProfitAmount, strategyId, strategyVersion, signalTimestampMs, requestId, proposalId, submissionStage, contractId, buyTransactionId, sellTransactionId, purchasePrice, settlementValue, settlementProfit, status, lastError, createdAtMs, updatedAtMs) SELECT 'LEGACY_UNKNOWN', 'DEMO', signalId, product, symbol, direction, stake, currency, multiplier, stopLossAmount, takeProfitAmount, strategyId, strategyVersion, signalTimestampMs, requestId, proposalId, 'VALIDATED', contractId, buyTransactionId, sellTransactionId, purchasePrice, settlementValue, settlementProfit, status, lastError, updatedAtMs, updatedAtMs FROM deriv_execution_intents""".trimIndent())
                db.execSQL("DROP TABLE deriv_execution_intents")
                db.execSQL("ALTER TABLE deriv_execution_intents_v6 RENAME TO deriv_execution_intents")
            }
        }

        fun getDatabase(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "destinedfx_database")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigrationOnDowngrade(false)
                .build().also { INSTANCE = it }
        }
    }
}
