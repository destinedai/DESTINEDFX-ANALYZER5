package com.example.data.codespaces

import org.json.JSONObject

/**
 * A GitHub STOP request must not be sent unless the Bridge reports both the
 * current broker state and the post-backup broker state as explicitly safe.
 * Missing/partial reports are treated as unsafe.
 */
internal fun isSafeHostStopPreflight(response: JSONObject?): Boolean {
    if (response == null) return false

    fun reportIsClean(report: JSONObject?): Boolean {
        if (report == null || !report.optBoolean("safe", false)) return false
        val reasons = report.optJSONArray("reasons") ?: return false
        return reasons.length() == 0
    }

    return response.optString("status") == "PREPARED" &&
        response.optString("host_generation").isNotBlank() &&
        response.optString("backup_name").isNotBlank() &&
        response.optBoolean("backup_verified", false) &&
        response.optBoolean("stop_script_ack", false) &&
        response.optString("stop_script_status") == "PREFLIGHT_PASSED" &&
        reportIsClean(response.optJSONObject("broker_safety")) &&
        reportIsClean(response.optJSONObject("backup_safety"))
}
