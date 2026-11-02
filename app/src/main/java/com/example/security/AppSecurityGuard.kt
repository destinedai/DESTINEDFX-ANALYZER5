package com.example.security

import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File

/**
 * Process-local authorization boundary for sensitive account and trading actions.
 * Guest/read-only analysis does not consult this guard. Trading code must.
 */
object AppSecurityGuard {
    private const val AUTHORIZATION_TIMEOUT_MS = 5 * 60 * 1000L

    @Volatile private var locked = true
    @Volatile private var authorizedUntilMs = 0L

    fun lock() {
        locked = true
        authorizedUntilMs = 0L
    }

    fun unlock(nowMs: Long = System.currentTimeMillis()) {
        locked = false
        authorizedUntilMs = nowMs + AUTHORIZATION_TIMEOUT_MS
    }

    fun isLocked(nowMs: Long = System.currentTimeMillis()): Boolean =
        locked || nowMs >= authorizedUntilMs

    /** Returns true only for a currently authenticated, non-expired sensitive-action lease. */
    fun authorizeSensitiveAction(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isLocked(nowMs)) {
            lock()
            return false
        }
        return true
    }

    data class SecurityReport(
        val isRooted: Boolean,
        val isDebuggerAttached: Boolean,
        val isHookDetected: Boolean,
        val isEncryptedStorageAvailable: Boolean,
        val statusMessage: String
    )

    fun auditAppSecurity(context: Context, isSecureStorageAvailable: Boolean): SecurityReport {
        val rooted = isDeviceRooted()
        val debugger = isDebuggerAttached()
        val hook = isHookingFrameworkDetected()
        val status = buildString {
            append("APP SECURITY: ")
            if (!rooted && !debugger && !hook && isSecureStorageAvailable) {
                append("secure storage available; sensitive actions require reauthentication")
            } else {
                append("restricted environment; sensitive actions remain blocked")
                if (rooted) append("; root detected")
                if (debugger) append("; debugger attached")
                if (hook) append("; hooking detected")
                if (!isSecureStorageAvailable) append("; encrypted storage unavailable")
            }
        }
        return SecurityReport(rooted, debugger, hook, isSecureStorageAvailable, status)
    }

    private fun isDeviceRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        return arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su"
        ).any { File(it).exists() }
    }

    private fun isDebuggerAttached(): Boolean = Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    private fun isHookingFrameworkDetected(): Boolean = try {
        val mapsFile = File("/proc/self/maps")
        mapsFile.exists() && mapsFile.readText().let { content ->
            content.contains("frida") || content.contains("xposed") || content.contains("substrate")
        }
    } catch (_: Exception) {
        false
    }
}
