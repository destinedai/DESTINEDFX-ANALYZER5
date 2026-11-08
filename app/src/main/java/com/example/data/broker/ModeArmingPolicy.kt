package com.example.data.broker

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class ModePath {
    DERIV_OPTIONS, CTRADER_ACCOUNT, FBS_MT5, EXNESS_MT5, HFM_MT5, DERIV_MT5;

    val leaseDurationMs: Long
        get() = when (this) {
            DERIV_OPTIONS -> 60L * 60L * 1000L
            FBS_MT5, EXNESS_MT5, HFM_MT5, DERIV_MT5 -> 15L * 60L * 1000L
            CTRADER_ACCOUNT -> 0L
        }
}

enum class AccountMode { DEMO, LIVE }
enum class HostProfile { PERSISTENT, EPHEMERAL }

/** Account identity only. Never put passwords, PATs or OAuth tokens in this model. */
data class ModeAccountIdentity(
    val path: ModePath,
    val broker: String,
    val accountId: String,
    val server: String,
    val mode: AccountMode,
    val credentialGeneration: String
) {
    fun isValid(): Boolean =
        broker.isNotBlank() && accountId.isNotBlank() && credentialGeneration.isNotBlank() &&
            (path !in setOf(ModePath.FBS_MT5, ModePath.EXNESS_MT5, ModePath.HFM_MT5, ModePath.DERIV_MT5) || server.isNotBlank())

    fun sameLogicalAccount(other: ModeAccountIdentity): Boolean =
        path == other.path && broker.equals(other.broker, true) && accountId == other.accountId

    fun fingerprint(): String = sha256(
        listOf(path.name, broker.uppercase(), accountId, server, mode.name, credentialGeneration).joinToString("\u0000")
    )

    fun acceptanceKey(): String = fingerprint()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

data class ModeReadOnlyVerification(
    val identity: ModeAccountIdentity,
    val checkedAtMs: Long,
    val authenticatedSession: Boolean,
    val freshAccountData: Boolean,
    val freshQuote: Boolean,
    val freshHeartbeat: Boolean,
    val accountIdentityMatched: Boolean
)

data class ModeDemoAcceptance(
    val identity: ModeAccountIdentity,
    val acceptedAtMs: Long,
    val evidenceDigest: String
)

data class ModeDecision(val allowed: Boolean, val status: String, val reason: String)
data class ModeLiveLease(val identity: ModeAccountIdentity, val armedAtMs: Long, val expiresAtMs: Long)
data class ModeArmingAuditEvent(
    val event: String,
    val path: String,
    val mode: String,
    val identityFingerprint: String,
    val timestampMs: Long,
    val allowed: Boolean,
    val reasonCode: String
)

data class LiveArmRequest(
    val identity: ModeAccountIdentity,
    val demoAcceptance: ModeDemoAcceptance?,
    val currentDemoCredentialGeneration: String,
    val liveVerification: ModeReadOnlyVerification,
    val typedConfirmation: String,
    val ownerReauthenticated: Boolean,
    val firstMinimumSizeVerified: Boolean,
    val dailyRiskClear: Boolean,
    val riskCapsClear: Boolean,
    val hostProfile: HostProfile = HostProfile.PERSISTENT
)

/**
 * Single process-scoped mode authority. DEMO acceptance is separately persisted against exact
 * identity/generation. LIVE leases are never persisted, so process death and reboot disarm.
 */
class ModeArmingPolicy(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val auditSink: (ModeArmingAuditEvent) -> Boolean = { true }
) {
    private val liveLeases = ConcurrentHashMap<String, ModeLiveLease>()

    fun demoDecision(
        identity: ModeAccountIdentity,
        verification: ModeReadOnlyVerification?,
        acceptance: ModeDemoAcceptance?,
        currentCredentialGeneration: String,
        now: Long = nowMs()
    ): ModeDecision {
        val reason = when {
            identity.mode != AccountMode.DEMO -> "DEMO_MODE_REQUIRED"
            !identity.isValid() -> "ACCOUNT_IDENTITY_INCOMPLETE"
            currentCredentialGeneration.isBlank() || currentCredentialGeneration != identity.credentialGeneration -> "CREDENTIAL_GENERATION_CHANGED"
            acceptance == null -> "OWNER_DEMO_ACCEPTANCE_REQUIRED"
            acceptance.identity != identity -> "DEMO_ACCEPTANCE_IDENTITY_MISMATCH"
            acceptance.acceptedAtMs <= 0L || acceptance.acceptedAtMs > now -> "DEMO_ACCEPTANCE_TIMESTAMP_INVALID"
            !acceptance.evidenceDigest.matches(Regex("[0-9a-f]{64}")) -> "DEMO_ACCEPTANCE_EVIDENCE_MISSING"
            verification == null -> "FRESH_READ_ONLY_VERIFICATION_REQUIRED"
            verification.identity != identity -> "VERIFIED_ACCOUNT_IDENTITY_MISMATCH"
            !isFresh(verification.checkedAtMs, now, freshnessLimit(identity.path)) -> "READ_ONLY_VERIFICATION_STALE"
            !verification.authenticatedSession -> "AUTHENTICATED_SESSION_REQUIRED"
            !verification.freshAccountData -> "FRESH_ACCOUNT_DATA_REQUIRED"
            !(verification.freshQuote || verification.freshHeartbeat) -> "FRESH_QUOTE_OR_HEARTBEAT_REQUIRED"
            !verification.accountIdentityMatched -> "BROKER_ACCOUNT_IDENTITY_MISMATCH"
            else -> null
        }
        return if (reason == null) ModeDecision(true, "DEMO_ENABLED", "Exact DEMO acceptance and fresh account evidence match.")
        else ModeDecision(false, "BLOCKED", reason)
    }

    fun armLive(request: LiveArmRequest): Result<ModeLiveLease> {
        val identity = request.identity
        val now = nowMs()
        val reason = when {
            request.hostProfile != HostProfile.PERSISTENT -> "EPHEMERAL_HOST_LIVE_BLOCKED"
            identity.mode != AccountMode.LIVE -> "LIVE_PROFILE_REQUIRED"
            identity.path == ModePath.CTRADER_ACCOUNT -> "CTRADER_TRADING_NOT_IMPLEMENTED"
            identity.path.leaseDurationMs <= 0L -> "LIVE_PATH_NOT_SUPPORTED"
            !identity.isValid() -> "LIVE_ACCOUNT_IDENTITY_INCOMPLETE"
            request.demoAcceptance == null -> "MATCHING_DEMO_PASS_REQUIRED"
            request.demoAcceptance.identity.mode != AccountMode.DEMO -> "DEMO_PASS_MODE_INVALID"
            !request.demoAcceptance.identity.sameLogicalAccount(identity) -> "DEMO_PASS_ACCOUNT_MISMATCH"
            request.currentDemoCredentialGeneration.isBlank() ||
                request.currentDemoCredentialGeneration != request.demoAcceptance.identity.credentialGeneration -> "DEMO_CREDENTIAL_GENERATION_CHANGED"
            request.demoAcceptance.acceptedAtMs <= 0L || request.demoAcceptance.acceptedAtMs > now -> "DEMO_ACCEPTANCE_TIMESTAMP_INVALID"
            !request.demoAcceptance.evidenceDigest.matches(Regex("[0-9a-f]{64}")) -> "DEMO_ACCEPTANCE_EVIDENCE_MISSING"
            request.liveVerification.identity != identity -> "LIVE_VERIFICATION_IDENTITY_MISMATCH"
            !isFresh(request.liveVerification.checkedAtMs, now, freshnessLimit(identity.path)) -> "LIVE_VERIFICATION_STALE"
            !request.liveVerification.authenticatedSession -> "LIVE_AUTHENTICATED_SESSION_REQUIRED"
            !request.liveVerification.freshAccountData -> "LIVE_ACCOUNT_DATA_STALE"
            !(request.liveVerification.freshQuote || request.liveVerification.freshHeartbeat) -> "LIVE_QUOTE_OR_HEARTBEAT_REQUIRED"
            !request.liveVerification.accountIdentityMatched -> "LIVE_ACCOUNT_IDENTITY_MISMATCH"
            request.typedConfirmation != "ARM LIVE " + identity.broker + " " + identity.accountId -> "TYPED_CONFIRMATION_MISMATCH"
            !request.ownerReauthenticated -> "OWNER_REAUTHENTICATION_REQUIRED"
            !request.firstMinimumSizeVerified -> "FIRST_MINIMUM_STAKE_OR_LOT_NOT_VERIFIED"
            !request.dailyRiskClear -> "DAILY_LOSS_LOCK_OR_RISK_DATA_BLOCKED"
            !request.riskCapsClear -> "RISK_CAPS_NOT_VERIFIED"
            else -> null
        }
        if (reason != null) {
            audit(identity, "LIVE_ARM_BLOCKED", false, reason, now)
            return Result.failure(IllegalStateException(reason))
        }
        val lease = ModeLiveLease(identity, now, now + identity.path.leaseDurationMs)
        if (!audit(identity, "LIVE_ARMED", true, "LEASE_CREATED", now)) {
            return Result.failure(IllegalStateException("MODE_AUDIT_WRITE_FAILED"))
        }
        liveLeases[identity.fingerprint()] = lease
        return Result.success(lease)
    }

    fun liveDecision(identity: ModeAccountIdentity, now: Long = nowMs(), hostProfile: HostProfile = HostProfile.PERSISTENT): ModeDecision {
        if (hostProfile != HostProfile.PERSISTENT) return ModeDecision(false, "LIVE_LOCKED", "EPHEMERAL_HOST_LIVE_BLOCKED")
        if (identity.mode != AccountMode.LIVE) return ModeDecision(false, "BLOCKED", "LIVE_PROFILE_REQUIRED")
        val lease = liveLeases[identity.fingerprint()]
            ?: return ModeDecision(false, "LIVE_LOCKED", "LIVE_LEASE_MISSING_OR_PROCESS_RESTARTED")
        if (lease.identity != identity || now < lease.armedAtMs || now >= lease.expiresAtMs) {
            liveLeases.remove(identity.fingerprint())
            audit(identity, "LIVE_LEASE_EXPIRED", false, "LEASE_EXPIRED_OR_IDENTITY_CHANGED", now)
            return ModeDecision(false, "LIVE_LOCKED", "LIVE_LEASE_EXPIRED_OR_IDENTITY_CHANGED")
        }
        return ModeDecision(true, "LIVE_ARMED", "Account-bound lease active until " + lease.expiresAtMs)
    }

    fun liveLease(identity: ModeAccountIdentity, now: Long = nowMs()): ModeLiveLease? =
        if (liveDecision(identity, now).allowed) liveLeases[identity.fingerprint()] else null

    fun disarm(identity: ModeAccountIdentity, reason: String = "OWNER_DISARMED"): Boolean {
        val removed = liveLeases.remove(identity.fingerprint()) != null
        val written = audit(identity, "LIVE_DISARMED", true, reason.take(64), nowMs())
        return removed && written
    }

    fun onCredentialChanged(path: ModePath, broker: String, accountId: String) {
        val affected = liveLeases.values.filter {
            it.identity.path == path && it.identity.broker.equals(broker, true) && it.identity.accountId == accountId
        }
        affected.forEach { liveLeases.remove(it.identity.fingerprint()) }
        affected.forEach { audit(it.identity, "LIVE_DISARMED", true, "CREDENTIAL_CHANGED", nowMs()) }
    }

    /** Selecting a different account in a connector disarms all that connector's previous leases. */
    fun onAccountSwitch(path: ModePath, broker: String, newAccountId: String) {
        val affected = liveLeases.values.filter {
            it.identity.path == path && it.identity.broker.equals(broker, true) && it.identity.accountId != newAccountId
        }
        affected.forEach { liveLeases.remove(it.identity.fingerprint()) }
        affected.forEach { audit(it.identity, "LIVE_DISARMED", true, "ACCOUNT_SWITCHED", nowMs()) }
    }

    /** Emergency stop revokes every process-local LIVE lease across every connector. */
    fun emergencyStop(): Int {
        val affected = liveLeases.values.toList()
        liveLeases.clear()
        affected.forEach { audit(it.identity, "EMERGENCY_STOP_DISARMED", true, "EMERGENCY_STOP", nowMs()) }
        return affected.size
    }

    fun activeLeaseCount(): Int = liveLeases.size

    private fun audit(identity: ModeAccountIdentity, event: String, allowed: Boolean, reason: String, at: Long): Boolean =
        runCatching {
            auditSink(ModeArmingAuditEvent(
                event, identity.path.name, identity.mode.name, identity.fingerprint(), at, allowed, reason
            ))
        }.getOrDefault(false)

    private fun isFresh(checkedAt: Long, now: Long, limit: Long): Boolean =
        checkedAt > 0L && checkedAt <= now && now - checkedAt <= limit

    private fun freshnessLimit(path: ModePath): Long =
        if (path == ModePath.DERIV_OPTIONS) 8_000L else 15_000L
}
