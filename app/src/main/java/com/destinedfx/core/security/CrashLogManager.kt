package com.destinedfx.core.security

import java.io.File

class CrashLogManager(private val directory: File) {
    companion object {
        const val CRASH_FILE_NAME = "last_crash.txt"
    }

    fun readLastCrash(): String? {
        return try {
            val file = File(directory, CRASH_FILE_NAME)
            if (file.exists() && file.isFile && file.canRead()) {
                val text = file.readText().trim()
                if (text.isNotEmpty()) text else null
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    fun dismissCrash(): String? {
        try {
            val file = File(directory, CRASH_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        return null
    }

    @Deprecated("Crash diagnostics must be retained through Keystore-backed SecretsManager storage.")
    fun writeCrash(crashReport: String): Boolean = false}
