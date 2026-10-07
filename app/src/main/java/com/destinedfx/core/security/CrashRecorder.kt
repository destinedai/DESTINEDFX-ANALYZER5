package com.destinedfx.core.security

object CrashRecorder {
    /**
     * Formats an unhandled crash into a redacted, safe diagnostics record.
     * NEVER includes throwable.message to prevent secret leakage.
     * Returns:
     * - Exception class name
     * - Top 12 stack frames
     * - Cause chain class names
     */
    fun format(thread: String, error: Throwable, nowMs: Long): String {
        val sb = StringBuilder()
        sb.appendLine("CRASH: ${error.javaClass.name}")
        sb.appendLine("Thread: $thread | TimeMs: $nowMs")
        sb.appendLine("Stack frames:")
        error.stackTrace.take(12).forEach { frame ->
            sb.appendLine("  at $frame")
        }
        val causes = mutableListOf<String>()
        var curr: Throwable? = error.cause
        while (curr != null) {
            causes.add(curr.javaClass.name)
            curr = curr.cause
        }
        if (causes.isNotEmpty()) {
            sb.appendLine("Causes: ${causes.joinToString(" -> ")}")
        }
        return Redactor.redact(sb.toString().trimEnd())
    }
}
