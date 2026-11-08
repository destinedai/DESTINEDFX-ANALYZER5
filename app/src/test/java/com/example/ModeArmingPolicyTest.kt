package com.example

import com.example.data.broker.AccountMode
import com.example.data.broker.LiveArmRequest
import com.example.data.broker.ModeAccountIdentity
import com.example.data.broker.ModeArmingPolicy
import com.example.data.broker.ModeDemoAcceptance
import com.example.data.broker.ModePath
import com.example.data.broker.ModeReadOnlyVerification
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

    private fun request(liveIdentity: ModeAccountIdentity = identity(mode = AccountMode.LIVE, generation = "cred-live", server = "Broker-Live"),
        demoIdentity: ModeAccountIdentity = identity(), liveVerified: ModeReadOnlyVerification = verified(liveIdentity),
        confirmation: String = "ARM LIVE " + liveIdentity.broker + " " + liveIdentity.accountId, minSize: Boolean = true,
        dailyRisk: Boolean = true, riskCaps: Boolean = true, reauthenticated: Boolean = true,
        currentDemoGeneration: String = demoIdentity.credentialGeneration) =
        LiveArmRequest(liveIdentity, accepted(demoIdentity), currentDemoGeneration, liveVerified,
            confirmation, reauthenticated, minSize, dailyRisk, riskCaps)

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

    @Test fun liveNeedsMatchingDemoPassFreshLiveVerificationTypedConfirmationAndAllRiskGates() {
        val policy = ModeArmingPolicy(nowMs = { 1_000_000L })
        val good = request()
        val lease = policy.armLive(good).getOrThrow()
        assertEquals(15L * 60L * 1000L, lease.expiresAtMs - lease.armedAtMs)
        assertFalse(policy.armLive(request(confirmation = "yes")).isSuccess)
        assertFalse(policy.armLive(request(minSize = false)).isSuccess)
        assertFalse(policy.armLive(request(dailyRisk = false)).isSuccess)
        assertFalse(policy.armLive(request(riskCaps = false)).isSuccess)
        assertFalse(policy.armLive(request(reauthenticated = false)).isSuccess)
        assertFalse(policy.armLive(request(demoIdentity = identity(account = "999999"))).isSuccess)
        assertFalse(policy.armLive(request(currentDemoGeneration = "rotated")).isSuccess)
        assertFalse(policy.armLive(request(liveVerified = verified(identity(mode = AccountMode.LIVE).copy(accountId = "999999")))).isSuccess)
    }

    @Test fun ephemeralHostCannotArmOrDecideLiveButDemoPolicyRemainsAvailable() {
        val policy = ModeArmingPolicy(nowMs = { 1_000_000L })
        val req = request()
        assertFalse(policy.armLive(req.copy(hostProfile = HostProfile.EPHEMERAL)).isSuccess)
        assertEquals("EPHEMERAL_HOST_LIVE_BLOCKED", policy.liveDecision(req.identity, hostProfile = HostProfile.EPHEMERAL).reason)
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

    @Test fun leasesExpireOnTimeCredentialRotationAccountSwitchAndProcessDeath() {
        var now = 1_000_000L
        val policy = ModeArmingPolicy(nowMs = { now })
        val req = request()
        policy.armLive(req).getOrThrow()
        assertTrue(policy.liveDecision(req.identity).allowed)
        policy.onCredentialChanged(req.identity.path, req.identity.broker, req.identity.accountId)
        assertFalse(policy.liveDecision(req.identity).allowed)
        policy.armLive(req).getOrThrow()
        policy.onAccountSwitch(req.identity.path, req.identity.broker, "other-account")
        assertFalse(policy.liveDecision(req.identity).allowed)
        policy.armLive(req).getOrThrow()
        now += 15L * 60L * 1000L
        assertFalse(policy.liveDecision(req.identity).allowed)
        assertFalse(ModeArmingPolicy(nowMs = { now }).liveDecision(req.identity).allowed)
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
        assertFalse(policy.liveDecision(fbs.identity).allowed)
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
        assertFalse(policy.liveLease(live) != null)
        assertEquals(0, policy.activeLeaseCount())
    }
}
