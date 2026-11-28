package com.example

import com.example.data.broker.AccountMode
import com.example.data.broker.LiveArmRequest
import com.example.data.broker.ModeAccountIdentity
import com.example.data.broker.ModeArmingPolicy
import com.example.data.broker.ModeDemoAcceptance
import com.example.data.broker.ModePath
import com.example.data.broker.ModeReadOnlyVerification
import com.example.data.broker.VerifiedDemoProfileAssociation
import com.example.data.broker.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeArmingPolicyTest {
    private fun identity(path: ModePath = ModePath.FBS_MT5, mode: AccountMode = AccountMode.DEMO,
        account: String = "123456", generation: String = "cred-demo", server: String = "Broker-Demo") =
        ModeAccountIdentity(path, path.name, account, server, mode, generation)

    private fun verified(identity: ModeAccountIdentity, now: Long = 1_000_000L) =
        ModeReadOnlyVerification(identity, now, true, true, true, true, true)

    private fun accepted(identity: ModeAccountIdentity, now: Long = 900_000L) =
        ModeDemoAcceptance(identity, now, "a".repeat(64))

    private fun request(liveIdentity: ModeAccountIdentity = identity(mode = AccountMode.LIVE, generation = "cred-live"),
        demoIdentity: ModeAccountIdentity = identity(), liveVerified: ModeReadOnlyVerification = verified(liveIdentity),
        confirmation: String = if (liveIdentity.path == ModePath.DERIV_OPTIONS) "ARM LIVE " + liveIdentity.broker + " " + liveIdentity.accountId else "CONFIRM REAL TRADE", minSize: Boolean = true,
        dailyRisk: Boolean = true, riskCaps: Boolean = true, reauthenticated: Boolean = true,
        currentDemoGeneration: String = demoIdentity.credentialGeneration,
        hostProfile: HostProfile = HostProfile.PERSISTENT) =
        LiveArmRequest(liveIdentity, accepted(demoIdentity), currentDemoGeneration, liveVerified,
            confirmation, reauthenticated, minSize, dailyRisk, riskCaps, hostProfile)

    @Test fun demoRequiresMatchingAcceptanceCredentialGenerationAndFreshEvidence() {
        val policy = ModeArmingPolicy(nowMs = { 1_000_000L })
        val demo = identity()
        assertTrue(policy.demoDecision(demo, verified(demo), accepted(demo), "cred-demo").allowed)
        assertFalse(policy.demoDecision(demo, verified(demo), null, "cred-demo").allowed)
        assertFalse(policy.demoDecision(demo, verified(demo), accepted(demo), "rotated").allowed)
        assertFalse(policy.demoDecision(demo, verified(demo).copy(accountIdentityMatched = false), accepted(demo), "cred-demo").allowed)
        assertFalse(policy.demoDecision(demo, verified(demo).copy(checkedAtMs = 900_000L), accepted(demo), "cred-demo").allowed)
        assertFalse(policy.demoDecision(demo.copy(mode = AccountMode.LIVE), verified(demo), accepted(demo), "cred-demo").allowed)
    }

    @Test fun unknownModeCannotBeAcceptedOrArmed() {
        val policy = ModeArmingPolicy(nowMs = { 1_000_000L })
        val unknownDemo = identity(mode = AccountMode.UNKNOWN)
        assertFalse(unknownDemo.isValid())
        assertFalse(policy.demoDecision(unknownDemo, verified(unknownDemo), accepted(unknownDemo), "cred-demo").allowed)
        val unknownLive = identity(mode = AccountMode.UNKNOWN, generation = "cred-live", server = "Broker-Live")
        assertFalse(policy.armLive(request(unknownLive, unknownDemo, verified(unknownLive))).isSuccess)
        val live = request()
        policy.armLive(live).getOrThrow()
        assertFalse(policy.liveDecision(live.identity.copy(mode = AccountMode.UNKNOWN)).allowed)
    }

    @Test fun mt5LiveRequiresOnlyFreshExactLiveSessionAndExplicitConfirmation() {
        val policy = ModeArmingPolicy(nowMs = { 1_000_000L })
        val good = request(hostProfile = HostProfile.EPHEMERAL)
        val lease = policy.armLive(good).getOrThrow()
        assertEquals(Long.MAX_VALUE, lease.expiresAtMs)
        assertTrue(policy.liveDecision(good.identity, hostProfile = HostProfile.EPHEMERAL).allowed)
        assertTrue(policy.liveLease(good.identity, hostProfile = HostProfile.EPHEMERAL) != null)
        assertFalse(policy.armLive(request(hostProfile = HostProfile.EPHEMERAL, confirmation = "YES")).isSuccess)
        assertFalse(policy.armLive(request(hostProfile = HostProfile.EPHEMERAL,
            liveVerified = verified(good.identity).copy(accountIdentityMatched = false))).isSuccess)
        assertFalse(policy.armLive(request(hostProfile = HostProfile.EPHEMERAL,
            liveVerified = verified(good.identity).copy(checkedAtMs = 900_000L))).isSuccess)
        assertFalse(policy.armLive(request(hostProfile = HostProfile.EPHEMERAL,
            liveVerified = verified(good.identity).copy(authenticatedSession = false))).isSuccess)
        assertFalse(policy.armLive(request(hostProfile = HostProfile.EPHEMERAL,
            liveVerified = verified(good.identity).copy(freshAccountData = false))).isSuccess)
    }

    @Test fun mt5LiveDoesNotRequireASeparateDemoPairOrDemoPass() {
        val now = 1_000_000L
        val policy = ModeArmingPolicy(nowMs = { now })
        val live = identity(mode = AccountMode.LIVE, account = "700001",
            generation = "live-generation", server = "Broker-Live")
        val request = LiveArmRequest(
            identity = live,
            demoAcceptance = null,
            currentDemoCredentialGeneration = "",
            liveVerification = verified(live, now),
            typedConfirmation = "CONFIRM REAL TRADE",
            ownerReauthenticated = false,
            firstMinimumSizeVerified = false,
            dailyRiskClear = false,
            riskCapsClear = false,
            hostProfile = HostProfile.EPHEMERAL,
            demoProfileAssociation = null
        )
        assertTrue(policy.armLive(request).isSuccess)
        assertTrue(policy.liveDecision(live, hostProfile = HostProfile.EPHEMERAL).allowed)
    }

    @Test fun mt5HostProfileDoesNotDisarmConfirmedLiveButCredentialChangeDoes() {
        val policy = ModeArmingPolicy(nowMs = { 1_000_000L })
        val live = request(hostProfile = HostProfile.EPHEMERAL)
        policy.armLive(live).getOrThrow()
        assertTrue(policy.liveDecision(live.identity, hostProfile = HostProfile.PERSISTENT).allowed)
        assertTrue(policy.liveDecision(live.identity, hostProfile = HostProfile.EPHEMERAL).allowed)
        policy.onCredentialChanged(live.identity.path, live.identity.broker, live.identity.accountId)
        assertFalse(policy.liveDecision(live.identity, hostProfile = HostProfile.EPHEMERAL).allowed)
        assertEquals(0, policy.activeLeaseCount())
        val demo = identity()
        assertTrue(policy.demoDecision(demo, verified(demo), accepted(demo), "cred-demo").allowed)
    }
    @Test fun derivLeaseIsSixtyMinutesAndCtraderCannotArm() {
        val policy = ModeArmingPolicy(nowMs = { 2_000_000L })
        val demo = identity(ModePath.DERIV_OPTIONS, AccountMode.DEMO, "CR123", "deriv-gen", "")
        val live = demo.copy(mode = AccountMode.LIVE)
        val lease = policy.armLive(request(live, demo, verified(live, now = 2_000_000L))).getOrThrow()
        assertEquals(60L * 60L * 1000L, lease.expiresAtMs - lease.armedAtMs)
        val ctDemo = identity(ModePath.CTRADER_ACCOUNT, AccountMode.DEMO, "789", "ct-gen", "")
        val ctLive = ctDemo.copy(mode = AccountMode.LIVE)
        assertFalse(policy.armLive(request(ctLive, ctDemo, verified(ctLive))).isSuccess)
    }

    @Test fun mt5LiveLeasePersistsUntilAccountChangeOrEmergencyStop() {
        var now = 1_000_000L
        val policy = ModeArmingPolicy(nowMs = { now })
        val req = request(hostProfile = HostProfile.EPHEMERAL)
        policy.armLive(req).getOrThrow()
        now += 365L * 24L * 60L * 60L * 1000L
        assertTrue(policy.liveDecision(req.identity, now, HostProfile.EPHEMERAL).allowed)
        policy.onAccountSwitch(req.identity.path, req.identity.broker, "another-account")
        assertFalse(policy.liveDecision(req.identity, now, HostProfile.EPHEMERAL).allowed)
        policy.armLive(req.copy(
            liveVerification = req.liveVerification.copy(checkedAtMs = now)
        )).getOrThrow()
        assertEquals(1, policy.emergencyStop())
        assertFalse(policy.liveDecision(req.identity, now, HostProfile.EPHEMERAL).allowed)
    }

    @Test fun emergencyStopDisarmsAllPathsAndAuditContainsOnlyFingerprints() {
        val events = mutableListOf<com.example.data.broker.ModeArmingAuditEvent>()
        val policy = ModeArmingPolicy(nowMs = { 1_000_000L }, auditSink = { events += it; true })
        val fbs = request()
        val demo = identity(ModePath.DERIV_OPTIONS, AccountMode.DEMO, "CR123", "deriv-gen", "")
        val deriv = request(demo.copy(mode = AccountMode.LIVE), demo, verified(demo.copy(mode = AccountMode.LIVE)))
        policy.armLive(fbs).getOrThrow()
        policy.armLive(deriv).getOrThrow()
        assertEquals(2, policy.emergencyStop())
        assertEquals(0, policy.activeLeaseCount())
        assertFalse(policy.liveDecision(fbs.identity, hostProfile = HostProfile.PERSISTENT).allowed)
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.identityFingerprint.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(events.none { it.identityFingerprint.contains("123456") || it.identityFingerprint.contains("CR123") })
    }

    @Test fun auditFailureBlocksLiveLeaseCreation() {
        val policy = ModeArmingPolicy(nowMs = { 1_000_000L }, auditSink = { false })
        assertFalse(policy.armLive(request()).isSuccess)
        assertEquals(0, policy.activeLeaseCount())
    }

    @Test fun directServiceAndBridgeCommandsCannotCreateALeaseWithoutOwnerRequest() {
        val policy = ModeArmingPolicy(nowMs = { 1_000_000L })
        val live = request().identity
        assertFalse(policy.liveDecision(live).allowed)
        assertFalse(policy.liveLease(live, hostProfile = HostProfile.PERSISTENT) != null)
        assertEquals(0, policy.activeLeaseCount())
    }
}
