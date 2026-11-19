package com.example.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
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
import com.example.security.SecretsManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

@Database(entities = [TradeEntity::class, User::class, AnalysisEntity::class, DerivExecutionEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tradeDao(): TradeDao
    abstract fun userDao(): UserDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun derivExecutionDao(): DerivExecutionDao

    companion object {
        private const val LEGACY_DATABASE_NAME = "destinedfx_database"
        private const val ENCRYPTED_DATABASE_NAME = "destinedfx_database_secure"
        private val ROOM_INTERNAL_TABLES = setOf("room_master_table", "android_metadata")
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

        fun getDatabase(context: Context, secrets: SecretsManager): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: run {
                val appContext = context.applicationContext
                if (!secrets.isSecureStorageAvailable) {
                    // Guest/read-only operation may continue, but no personal financial history is
                    // persisted without a Keystore-protected database key.
                    Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                        .build().also { INSTANCE = it }
                } else {
                    System.loadLibrary("sqlcipher")
                    val passphrase = secrets.getOrCreateAppDatabasePassphrase()
                    val factory = SupportOpenHelperFactory(passphrase.copyOf(), null, false)
                    val encrypted = Room.databaseBuilder(appContext, AppDatabase::class.java, ENCRYPTED_DATABASE_NAME)
                        .openHelperFactory(factory)
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                        .fallbackToDestructiveMigrationOnDowngrade(false)
                        .build()
                    try {
                        val encryptedDb = encrypted.openHelper.writableDatabase
                        migrateLegacyPlaintextDatabase(appContext, encryptedDb)
                        encrypted.also { INSTANCE = it }
                    } catch (error: Throwable) {
                        encrypted.close()
                        throw IllegalStateException(
                            "Encrypted database initialization or safe legacy-data migration failed. Existing records were preserved; broker actions are unavailable.",
                            error
                        )
                    }
                }
            }
        }

        private fun migrateLegacyPlaintextDatabase(context: Context, encryptedDb: SupportSQLiteDatabase) {
            val legacyFile = context.getDatabasePath(LEGACY_DATABASE_NAME)
            if (!legacyFile.exists()) return

            val legacy = Room.databaseBuilder(context, AppDatabase::class.java, LEGACY_DATABASE_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigrationOnDowngrade(false)
                .build()
            try {
                // Opening writableDatabase applies the same historical Room migrations before copying.
                val plaintextDb = legacy.openHelper.writableDatabase
                copyPlaintextTables(plaintextDb, encryptedDb)
                legacy.close()

                // Never remove the plaintext source until every app table's row count was verified.
                check(context.deleteDatabase(LEGACY_DATABASE_NAME)) {
                    "Encrypted copy verified, but the old plaintext database could not be removed."
                }
            } catch (error: Throwable) {
                legacy.close()
                throw error
            }
        }

        /**
         * Copies all application tables between two Room databases with matching schemas.
         * Room's identity table and SQLite's own sequence metadata are intentionally regenerated.
         * This helper is internal so the Android instrumentation suite can verify the exact copy path.
         */
        internal fun copyPlaintextTables(source: SupportSQLiteDatabase, target: SupportSQLiteDatabase) {
            val sourceTables = appTableNames(source)
            val targetTables = appTableNames(target)
            check(sourceTables.toSet() == targetTables.toSet()) {
                "Legacy and encrypted database table sets differ; refusing a partial migration."
            }

            target.beginTransaction()
            try {
                for (table in sourceTables) {
                    val sourceColumns = tableColumnNames(source, table)
                    val targetColumns = tableColumnNames(target, table)
                    check(sourceColumns.toSet() == targetColumns.toSet()) {
                        "Legacy and encrypted database columns differ for $table; refusing a partial migration."
                    }

                    source.query("SELECT * FROM ${quoteIdentifier(table)}").use { cursor ->
                        while (cursor.moveToNext()) {
                            val values = ContentValues(cursor.columnCount)
                            for (index in 0 until cursor.columnCount) {
                                val column = cursor.getColumnName(index)
                                when (cursor.getType(index)) {
                                    Cursor.FIELD_TYPE_NULL -> values.putNull(column)
                                    Cursor.FIELD_TYPE_INTEGER -> values.put(column, cursor.getLong(index))
                                    Cursor.FIELD_TYPE_FLOAT -> values.put(column, cursor.getDouble(index))
                                    Cursor.FIELD_TYPE_BLOB -> values.put(column, cursor.getBlob(index))
                                    else -> values.put(column, cursor.getString(index))
                                }
                            }
                            check(target.insert(table, SupportSQLiteDatabase.CONFLICT_REPLACE, values) != -1L) {
                                "A legacy record could not be copied from $table."
                            }
                        }
                    }
                }

                for (table in sourceTables) {
                    val sourceCount = tableRowCount(source, table)
                    val targetCount = tableRowCount(target, table)
                    check(sourceCount == targetCount) {
                        "Row-count verification failed for $table (legacy=$sourceCount encrypted=$targetCount)."
                    }
                }
                target.setTransactionSuccessful()
            } finally {
                target.endTransaction()
            }
        }

        private fun appTableNames(database: SupportSQLiteDatabase): List<String> {
            val names = mutableListOf<String>()
            database.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn)
                    if (name !in ROOM_INTERNAL_TABLES && !name.startsWith("sqlite_")) names += name
                }
            }
            return names
        }

        private fun tableColumnNames(database: SupportSQLiteDatabase, table: String): List<String> {
            val columns = mutableListOf<String>()
            database.query("PRAGMA table_info(${quoteIdentifier(table)})").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameColumn)
            }
            return columns
        }

        private fun tableRowCount(database: SupportSQLiteDatabase, table: String): Long =
            database.query("SELECT COUNT(*) FROM ${quoteIdentifier(table)}").use { cursor ->
                check(cursor.moveToFirst()) { "Could not verify row count for $table." }
                cursor.getLong(0)
            }

        private fun quoteIdentifier(identifier: String): String =
            "\"" + identifier.replace("\"", "\"\"") + "\""
    }
}
