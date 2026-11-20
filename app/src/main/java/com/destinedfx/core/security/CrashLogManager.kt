package com.destinedfx.core.security

import java.io.File

class CrashLogManager(private val directory: File) {
    companion object {
        const val CRASH_FILE_NAME = "last_crash.txt"
    }

    @Deprecated("Read crash diagnostics through Keystore-backed SecretsManager storage.")
    fun readLastCrash(): String? = null

    @Deprecated("Clear crash diagnostics through Keystore-backed SecretsManager storage.")
    fun dismissCrash(): String? = null

    @Deprecated("Crash diagnostics must be retained through Keystore-backed SecretsManager storage.")
    fun writeCrash(crashReport: String): Boolean = false
}
