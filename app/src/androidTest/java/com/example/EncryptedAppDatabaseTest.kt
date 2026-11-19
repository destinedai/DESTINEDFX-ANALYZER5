package com.example

import android.content.ContentValues
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class EncryptedAppDatabaseTest {
    @Test
    fun sqlCipherRoomDatabaseCopiesLegacyRowsAndReopensWithTheSameKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.currentTimeMillis().toString()
        val plaintextName = "destinedfx_plain_migration_$suffix"
        val encryptedName = "destinedfx_secure_migration_$suffix"
        val passphrase = "f13a7b9c2d4e6f80123456789abcdef0".toByteArray(Charsets.US_ASCII)

        context.deleteDatabase(plaintextName)
        context.deleteDatabase(encryptedName)
        System.loadLibrary("sqlcipher")

        val migrations = arrayOf(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
        )
        val legacy = Room.databaseBuilder(context, AppDatabase::class.java, plaintextName)
            .addMigrations(*migrations)
            .build()
        val encrypted = Room.databaseBuilder(context, AppDatabase::class.java, encryptedName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf(), null, false))
            .addMigrations(*migrations)
            .build()

        try {
            val plainDb = legacy.openHelper.writableDatabase
            val user = ContentValues().apply {
                put("email", "encrypted-migration@example.test")
                put("username", "Migration Test")
                put("passwordHash", "fixture-hash-not-a-password")
                put("uid", "migration-user-1")
                put("registrationDate", 1_800_000_000_000L)
            }
            assertTrue(plainDb.insert("users", androidx.sqlite.db.SupportSQLiteDatabase.CONFLICT_ABORT, user) != -1L)

            val trade = ContentValues().apply {
                put("id", 77)
                put("ticketId", "DEMO-TRADE-77")
                put("symbol", "EURUSD")
                put("positionType", "BUY")
                put("entryPrice", 1.08)
                put("stopLoss", 1.07)
                put("takeProfit", 1.10)
                put("rationale", "encrypted migration fixture")
                put("status", "CLOSED")
                put("pipsProfit", 20)
                put("plAmount", 12.5)
                put("lotSize", 0.01)
                put("setupType", "SMC")
                put("timestamp", 1_800_000_000_000L)
                put("closeTimestamp", 1_800_000_060_000L)
            }
            assertTrue(plainDb.insert("trade_journal", androidx.sqlite.db.SupportSQLiteDatabase.CONFLICT_ABORT, trade) != -1L)

            val analysis = ContentValues().apply {
                put("id", 88)
                put("symbol", "EURUSD")
                put("timeframe", "M15")
                put("strategy", "smc-confluence-1.0.0")
                put("dateMs", 1_800_000_000_000L)
                put("winRateConfidence", 84.0)
                put("signal", "BUY")
                put("report", "local analysis history fixture")
            }
            assertTrue(plainDb.insert("analysis_history", androidx.sqlite.db.SupportSQLiteDatabase.CONFLICT_ABORT, analysis) != -1L)

            val encryptedDb = encrypted.openHelper.writableDatabase
            AppDatabase.copyPlaintextTables(plainDb, encryptedDb)

            encryptedDb.query("SELECT passwordHash FROM users WHERE email='encrypted-migration@example.test'").use {
                assertTrue(it.moveToFirst())
                assertEquals("fixture-hash-not-a-password", it.getString(0))
            }
            encryptedDb.query("SELECT ticketId,plAmount FROM trade_journal WHERE id=77").use {
                assertTrue(it.moveToFirst())
                assertEquals("DEMO-TRADE-77", it.getString(0))
                assertEquals(12.5, it.getDouble(1), 0.0)
            }
            encryptedDb.query("SELECT report FROM analysis_history WHERE id=88").use {
                assertTrue(it.moveToFirst())
                assertEquals("local analysis history fixture", it.getString(0))
            }
        } finally {
            legacy.close()
            encrypted.close()
        }

        val encryptedFile = context.getDatabasePath(encryptedName)
        assertTrue(encryptedFile.isFile)
        val header = ByteArray(16)
        FileInputStream(encryptedFile).use { assertTrue(it.read(header) == header.size) }
        assertFalse(String(header, Charsets.US_ASCII).startsWith("SQLite format 3"))

        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, encryptedName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf(), null, false))
            .addMigrations(*migrations)
            .build()
        try {
            reopened.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM trade_journal WHERE id=77"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(1L, it.getLong(0))
            }
        } finally {
            reopened.close()
            context.deleteDatabase(plaintextName)
            context.deleteDatabase(encryptedName)
        }
    }
}
