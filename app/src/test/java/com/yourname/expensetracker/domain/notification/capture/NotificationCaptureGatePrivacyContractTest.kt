package com.yourname.expensetracker.domain.notification.capture

import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.BlockedPackageDao
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-gate contract tests for PRIV-441-05 / PRIV-441-06 / PRIV-441-07.
 *
 * These tests drive the production-wired [NotificationCaptureGate] (the class
 * injected into `NotificationCaptureService`). Only the gate's four
 * collaborators are test doubles; the gate itself is never mocked.
 *
 * Requirement mapping (the gate is the single decision point that must return
 * [NotificationCaptureDecision.Allowed] before the service may read any
 * notification text/extras or insert a dedupe entry; a
 * [NotificationCaptureDecision.TemporarilyUnavailable] decision is not an allow
 * either, but the service may still proceed after it through its own fresh
 * re-authorization, see `NotificationCaptureServiceDeferredPolicyTest`). This
 * file proves the DECISIONS at the gate boundary only. Whether the service
 * actually decides before extraction and before `deduper.tryStart` is not
 * observable at the gate; that ordering is proven at service level by
 * `NotificationCaptureServiceBlockedDecisionTest` (B2-2b), which also proves
 * PRIV-441-07 there (B2-2c: a step-5 privacy denial releases the dedupe key).
 *  - PRIV-441-05: the gate returns Allowed only on an explicit allow. Anything
 *    else (disabled capture, corrupted settings, Denied/FailClosed/NotApplicable,
 *    gate failure) is Blocked, and settings that could not be loaded never
 *    return Allowed (they return TemporarilyUnavailable).
 *  - PRIV-441-06: a blocked package is Blocked, and an unverifiable
 *    blocked-package state (load failure, lookup failure, hang) fails closed.
 *  - PRIV-441-07: a privacy-denied notification must not poison later
 *    decisions. At the gate boundary this means neither a denial nor a
 *    temporary unavailability is sticky or memoized: the same package is
 *    admitted as soon as privacy permits it, and revocation takes effect
 *    immediately. (Dedupe insertion itself happens in the service strictly
 *    after this gate returns Allowed; that ordering is not exercised here.)
 *
 * All data is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCaptureGatePrivacyContractTest {

    private companion object {
        const val ALLOWED_PKG = "com.example.synthetic.bank"
        const val BLOCKED_PKG = "com.example.synthetic.blocked"

        val ENABLED_SETTINGS = PrivacySettings(notificationCaptureEnabled = true)
        val DISABLED_SETTINGS = PrivacySettings(notificationCaptureEnabled = false)

        // What the real repository exposes via getSettings() when the DataStore is corrupt:
        // FAIL_CLOSED_DEFAULTS (that repository behaviour is pinned by
        // PrivacySettingsRepositoryImplCorruptionTest, not by this gate test).
        val CORRUPTED_SETTINGS: PrivacySettings = PrivacySettings.FAIL_CLOSED_DEFAULTS

        val DISABLED_BLOCK = NotificationCaptureDecision.Blocked(
            reason = NotificationCaptureBlockReason.PRIVACY_SETTING_DISABLED,
            diagnosticReasonCode = DiagnosticReasonCode.PRIVACY_DENIED
        )
        val BLOCKED_PACKAGE_BLOCK = NotificationCaptureDecision.Blocked(
            reason = NotificationCaptureBlockReason.BLOCKED_PACKAGE,
            diagnosticReasonCode = DiagnosticReasonCode.BLOCKED_PACKAGE
        )
        val NOT_READY = NotificationCaptureDecision.TemporarilyUnavailable(
            reason = NotificationCaptureBlockReason.GATE_NOT_READY,
            retryable = true
        )

        fun privacyBlock(reason: NotificationCaptureBlockReason) =
            NotificationCaptureDecision.Blocked(
                reason = reason,
                diagnosticReasonCode = DiagnosticReasonCode.PRIVACY_DENIED
            )
    }

    /** Mutable, synthetic world behind the gate's four collaborators. */
    private class Fixture {
        val settingsFlow = MutableStateFlow(ENABLED_SETTINGS)
        val blockedFlow = MutableStateFlow<List<String>>(emptyList())

        var writesAllowed = true
        var settingsLoader: suspend () -> PrivacySettings = { settingsFlow.value }
        var blockedListLoader: suspend () -> List<String> = { blockedFlow.value }
        var isBlockedLookup: suspend (String) -> Boolean = { pkg -> pkg in blockedFlow.value }
        var privacyGateBehavior: suspend () -> PrivacyDecision = { PrivacyDecision.Allowed }

        val repository = mockk<PrivacySettingsRepository>().also { repo ->
            coEvery { repo.getSettings() } coAnswers { settingsLoader() }
            every { repo.observeSettings() } returns settingsFlow
        }
        val privacyGate = mockk<PrivacyGate>().also { gate ->
            coEvery { gate.check(any(), any()) } coAnswers { privacyGateBehavior() }
        }
        val blockedPackageDao = mockk<BlockedPackageDao>().also { dao ->
            coEvery { dao.getAllPackageNamesOnce() } coAnswers { blockedListLoader() }
            every { dao.getAllPackageNamesFlow() } returns blockedFlow
            coEvery { dao.isBlocked(any()) } coAnswers { isBlockedLookup(firstArg()) }
        }
        val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>().also { restore ->
            every { restore.isWritesAllowed() } answers { writesAllowed }
        }

        val gate = NotificationCaptureGate(
            privacySettingsRepository = repository,
            privacyGate = privacyGate,
            blockedPackageDao = blockedPackageDao,
            restoreMaintenanceMode = restoreMaintenanceMode
        )
    }

    private class Scenario(
        val name: String,
        val warmUp: Boolean,
        val packageName: String = ALLOWED_PKG,
        val isShuttingDown: Boolean = false,
        val expected: NotificationCaptureDecision,
        val afterDecision: Fixture.() -> Unit = {},
        val configure: Fixture.() -> Unit
    )

    private suspend fun runScenarios(scenarios: List<Scenario>) {
        for (scenario in scenarios) {
            val fixture = Fixture().apply(scenario.configure)
            if (scenario.warmUp) fixture.gate.warmUp()
            val actual = fixture.gate.decide(scenario.packageName, scenario.isShuttingDown)
            assertEquals("scenario '${scenario.name}'", scenario.expected, actual)
            scenario.afterDecision(fixture)
        }
    }

    // -- PRIV-441-05 ---------------------------------------------------------

    @Test
    fun `PRIV-441-05 disabled or corrupted fail-closed settings deny capture`() = runTest {
        runScenarios(
            listOf(
                Scenario("capture disabled (warmed)", warmUp = true, expected = DISABLED_BLOCK) {
                    settingsFlow.value = DISABLED_SETTINGS
                },
                Scenario("capture disabled (self-healed, no warmUp)", warmUp = false, expected = DISABLED_BLOCK) {
                    settingsFlow.value = DISABLED_SETTINGS
                },
                Scenario("CorruptedFailClosed settings (warmed)", warmUp = true, expected = DISABLED_BLOCK) {
                    settingsFlow.value = CORRUPTED_SETTINGS
                },
                Scenario("CorruptedFailClosed settings (no warmUp)", warmUp = false, expected = DISABLED_BLOCK) {
                    settingsFlow.value = CORRUPTED_SETTINGS
                }
            )
        )
    }

    @Test
    fun `PRIV-441-05 any non-Allowed privacy gate outcome or gate failure denies capture`() = runTest {
        runScenarios(
            listOf(
                Scenario(
                    "PrivacyGate Denied",
                    warmUp = true,
                    expected = privacyBlock(NotificationCaptureBlockReason.PRIVACY_GATE_DENIED)
                ) { privacyGateBehavior = { PrivacyDecision.Denied("synthetic denial") } },
                Scenario(
                    "PrivacyGate FailClosed",
                    warmUp = true,
                    expected = privacyBlock(NotificationCaptureBlockReason.PRIVACY_GATE_FAIL_CLOSED)
                ) { privacyGateBehavior = { PrivacyDecision.FailClosed("synthetic fail-closed") } },
                Scenario(
                    "PrivacyGate NotApplicable is not an allow",
                    warmUp = true,
                    expected = privacyBlock(NotificationCaptureBlockReason.PRIVACY_GATE_NOT_APPLICABLE)
                ) { privacyGateBehavior = { PrivacyDecision.NotApplicable } },
                Scenario(
                    "PrivacyGate throws",
                    warmUp = true,
                    expected = privacyBlock(NotificationCaptureBlockReason.GATE_ERROR)
                ) { privacyGateBehavior = { throw IllegalStateException("synthetic gate failure") } }
            )
        )
    }

    @Test
    fun `PRIV-441-05 settings that cannot be loaded keep the gate closed and never allow`() = runTest {
        runScenarios(
            listOf(
                Scenario("settings load throws, no warmUp", warmUp = false, expected = NOT_READY) {
                    settingsLoader = { throw IllegalStateException("synthetic load failure") }
                },
                Scenario("settings load throws during warmUp", warmUp = true, expected = NOT_READY) {
                    settingsLoader = { throw IllegalStateException("synthetic load failure") }
                },
                Scenario("settings load never completes (self-heal timeout)", warmUp = false, expected = NOT_READY) {
                    settingsLoader = { awaitCancellation() }
                }
            )
        )

        // warmUp with both sources failing must leave the gate not ready.
        val fixture = Fixture().apply {
            settingsLoader = { throw IllegalStateException("synthetic load failure") }
            blockedListLoader = { throw IllegalStateException("synthetic load failure") }
        }
        fixture.gate.warmUp()
        assertFalse("gate must not report ready after failed warmUp", fixture.gate.isReady())
        assertTrue(
            "gate state must be Error after failed warmUp",
            fixture.gate.state.value is NotificationCaptureGate.GateState.Error
        )
    }

    // -- PRIV-441-06 ---------------------------------------------------------

    @Test
    fun `PRIV-441-06 blocked package is denied while a non-blocked package is allowed and unverifiable state fails closed`() = runTest {
        runScenarios(
            listOf(
                Scenario("blocked package", warmUp = true, packageName = BLOCKED_PKG, expected = BLOCKED_PACKAGE_BLOCK) {
                    blockedFlow.value = listOf(BLOCKED_PKG)
                },
                Scenario(
                    "control: other package not blocked when a different one is",
                    warmUp = true,
                    packageName = ALLOWED_PKG,
                    expected = NotificationCaptureDecision.Allowed
                ) { blockedFlow.value = listOf(BLOCKED_PKG) },
                Scenario(
                    "blocked list load fails, one-shot lookup says blocked",
                    warmUp = true,
                    packageName = BLOCKED_PKG,
                    expected = BLOCKED_PACKAGE_BLOCK
                ) {
                    blockedFlow.value = listOf(BLOCKED_PKG)
                    blockedListLoader = { throw IllegalStateException("synthetic list failure") }
                },
                Scenario(
                    "blocked list load fails and one-shot lookup throws",
                    warmUp = true,
                    expected = BLOCKED_PACKAGE_BLOCK
                ) {
                    blockedListLoader = { throw IllegalStateException("synthetic list failure") }
                    isBlockedLookup = { throw IllegalStateException("synthetic lookup failure") }
                },
                Scenario(
                    "blocked list load fails and one-shot lookup never completes",
                    warmUp = true,
                    expected = BLOCKED_PACKAGE_BLOCK
                ) {
                    blockedListLoader = { throw IllegalStateException("synthetic list failure") }
                    isBlockedLookup = { awaitCancellation() }
                },
                Scenario(
                    "control: blocked list unavailable but lookup proves package not blocked",
                    warmUp = true,
                    expected = NotificationCaptureDecision.Allowed
                ) {
                    blockedListLoader = { throw IllegalStateException("synthetic list failure") }
                    isBlockedLookup = { false }
                }
            )
        )
    }

    @Test
    fun `PRIV-441-06 blocked-list reload that hangs does not mark the list loaded and still blocks the package`() = runTest {
        // No warmUp: warmUp has no timeout and would hang on the reload. decide()
        // self-heals with a bounded timeout. A timed-out reload must leave the
        // blocked list "not loaded" (the `?: false` fallback in decide) so the
        // per-package lookup runs. If the timeout were treated as "loaded", the
        // still-empty in-memory list would admit the blocked package.
        runScenarios(
            listOf(
                Scenario(
                    "blocked list reload hangs, one-shot lookup says blocked",
                    warmUp = false,
                    packageName = BLOCKED_PKG,
                    expected = BLOCKED_PACKAGE_BLOCK,
                    afterDecision = {
                        assertFalse(
                            "a timed-out blocked-list reload must leave the gate not ready",
                            gate.isReady()
                        )
                    }
                ) {
                    blockedFlow.value = listOf(BLOCKED_PKG)
                    blockedListLoader = { awaitCancellation() }
                },
                Scenario(
                    "blocked list reload hangs and one-shot lookup also hangs",
                    warmUp = false,
                    packageName = BLOCKED_PKG,
                    expected = BLOCKED_PACKAGE_BLOCK
                ) {
                    blockedFlow.value = listOf(BLOCKED_PKG)
                    blockedListLoader = { awaitCancellation() }
                    isBlockedLookup = { awaitCancellation() }
                },
                Scenario(
                    "control: blocked list reload hangs but lookup proves other package not blocked",
                    warmUp = false,
                    packageName = ALLOWED_PKG,
                    expected = NotificationCaptureDecision.Allowed
                ) {
                    blockedFlow.value = listOf(BLOCKED_PKG)
                    blockedListLoader = { awaitCancellation() }
                }
            )
        )
    }

    // -- PRIV-441-07 ---------------------------------------------------------

    @Test
    fun `PRIV-441-07 a denial is never sticky and revocation takes effect immediately`() = runTest {
        val fixture = Fixture().apply { settingsFlow.value = DISABLED_SETTINGS }
        val gate = fixture.gate
        gate.warmUp()
        gate.startObservers(backgroundScope)
        runCurrent()

        // Denied twice with capture disabled: repeated denial, nothing remembered.
        assertEquals(DISABLED_BLOCK, gate.decide(ALLOWED_PKG, false))
        assertEquals(DISABLED_BLOCK, gate.decide(ALLOWED_PKG, false))

        // Privacy re-enabled: the very same package is no longer dropped.
        fixture.settingsFlow.value = ENABLED_SETTINGS
        runCurrent()
        assertEquals(NotificationCaptureDecision.Allowed, gate.decide(ALLOWED_PKG, false))

        // Revocation: capture disabled again must deny at once (no stale allow).
        fixture.settingsFlow.value = DISABLED_SETTINGS
        runCurrent()
        assertEquals(DISABLED_BLOCK, gate.decide(ALLOWED_PKG, false))

        // A PrivacyGate denial is likewise not memoized.
        fixture.settingsFlow.value = ENABLED_SETTINGS
        runCurrent()
        fixture.privacyGateBehavior = { PrivacyDecision.Denied("synthetic denial") }
        assertEquals(
            privacyBlock(NotificationCaptureBlockReason.PRIVACY_GATE_DENIED),
            gate.decide(ALLOWED_PKG, false)
        )
        fixture.privacyGateBehavior = { PrivacyDecision.Allowed }
        assertEquals(NotificationCaptureDecision.Allowed, gate.decide(ALLOWED_PKG, false))

        // Blocked-package changes also apply live in both directions.
        fixture.blockedFlow.value = listOf(ALLOWED_PKG)
        runCurrent()
        assertEquals(BLOCKED_PACKAGE_BLOCK, gate.decide(ALLOWED_PKG, false))
        fixture.blockedFlow.value = emptyList()
        runCurrent()
        assertEquals(NotificationCaptureDecision.Allowed, gate.decide(ALLOWED_PKG, false))
    }

    @Test
    fun `PRIV-441-07 TemporarilyUnavailable is not sticky and recovers to Allowed once settings load`() = runTest {
        val fixture = Fixture().apply {
            settingsLoader = { throw IllegalStateException("synthetic load failure") }
        }
        val gate = fixture.gate

        // Settings cannot be loaded: not ready, and (repeatedly) never Allowed.
        assertEquals(NOT_READY, gate.decide(ALLOWED_PKG, false))
        assertEquals(NOT_READY, gate.decide(ALLOWED_PKG, false))

        // Loader recovers: the very next decision is Allowed (nothing was remembered).
        fixture.settingsLoader = { fixture.settingsFlow.value }
        assertEquals(NotificationCaptureDecision.Allowed, gate.decide(ALLOWED_PKG, false))

        // Same recovery when the first failure was a hang instead of a throw,
        // and recovery into a disabled setting yields Blocked, not a stale not-ready.
        val hanging = Fixture().apply {
            settingsLoader = { awaitCancellation() }
            settingsFlow.value = DISABLED_SETTINGS
        }
        assertEquals(NOT_READY, hanging.gate.decide(ALLOWED_PKG, false))
        hanging.settingsLoader = { hanging.settingsFlow.value }
        assertEquals(DISABLED_BLOCK, hanging.gate.decide(ALLOWED_PKG, false))
    }

    // -- Fail-closed composition ---------------------------------------------

    @Test
    fun `capture is allowed only when every condition permits`() = runTest {
        // Control: every condition permits (warmed and self-healed).
        val warmed = Fixture()
        warmed.gate.warmUp()
        assertEquals(NotificationCaptureDecision.Allowed, warmed.gate.decide(ALLOWED_PKG, false))
        coVerify(exactly = 1) { warmed.privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE, any()) }
        assertEquals(
            NotificationCaptureDecision.Allowed,
            Fixture().gate.decide(ALLOWED_PKG, false)
        )

        // Boundary: flipping exactly one condition must deny.
        runScenarios(
            listOf(
                Scenario(
                    "restore maintenance active",
                    warmUp = true,
                    expected = NotificationCaptureDecision.Blocked(
                        reason = NotificationCaptureBlockReason.RESTORE_MODE,
                        diagnosticReasonCode = DiagnosticReasonCode.RESTORE_BLOCKED
                    )
                ) { writesAllowed = false },
                Scenario(
                    "restore maintenance active outranks unloaded settings",
                    warmUp = false,
                    expected = NotificationCaptureDecision.Blocked(
                        reason = NotificationCaptureBlockReason.RESTORE_MODE,
                        diagnosticReasonCode = DiagnosticReasonCode.RESTORE_BLOCKED
                    )
                ) {
                    writesAllowed = false
                    settingsLoader = { throw IllegalStateException("synthetic load failure") }
                },
                Scenario(
                    "service shutting down",
                    warmUp = true,
                    isShuttingDown = true,
                    expected = NotificationCaptureDecision.Blocked(
                        reason = NotificationCaptureBlockReason.SERVICE_SHUTTING_DOWN,
                        diagnosticReasonCode = DiagnosticReasonCode.CANCELLED_BY_SYSTEM
                    )
                ) { },
                Scenario("capture disabled", warmUp = true, expected = DISABLED_BLOCK) {
                    settingsFlow.value = DISABLED_SETTINGS
                },
                Scenario(
                    "privacy gate denies",
                    warmUp = true,
                    expected = privacyBlock(NotificationCaptureBlockReason.PRIVACY_GATE_DENIED)
                ) { privacyGateBehavior = { PrivacyDecision.Denied("synthetic denial") } },
                Scenario("package blocked", warmUp = true, packageName = BLOCKED_PKG, expected = BLOCKED_PACKAGE_BLOCK) {
                    blockedFlow.value = listOf(BLOCKED_PKG)
                }
            )
        )
    }
}
