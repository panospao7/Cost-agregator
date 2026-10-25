package com.yourname.expensetracker.domain.notification.presentation

import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4: [NotificationContentPolicy] decision table (INV-4, INV-6, INV-10, INV-12; AE-1..AE-6, AE-12,
 * AE-109, AE-111 decision rows).
 */
class NotificationContentPolicyTest {

    private val sdkLevels = listOf(26, 28, 29, 30, 31, 34)

    private val full = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = false))
    private val discreetOn = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = true))
    private val firstRun = AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS)

    @Test
    fun `registry holds exactly the six kinds with pinned tokens`() {
        assertEquals(
            mapOf(
                NotificationKind.BILL to "BILL",
                NotificationKind.ANOMALY to "ANOMALY",
                NotificationKind.BUDGET to "BUDGET",
                NotificationKind.WARRANTY to "WARRANTY",
                NotificationKind.BRIEFING to "BRIEFING",
                NotificationKind.RECEIPT_MATCH to "RECEIPT_MATCH"
            ),
            NotificationKind.entries.associateWith { it.stableToken }
        )
    }

    @Test
    fun `readable settings with discreet off give FULL for every kind and sdk`() {
        for (kind in NotificationKind.entries) for (sdk in sdkLevels) for (state in listOf(full, firstRun)) {
            val decision = NotificationContentPolicy.decide(kind, state, sdk)
            assertEquals(kind, decision.kind)
            assertEquals(NotificationPresentationMode.FULL, decision.mode)
            assertTrue(decision.usesCallerText)
            assertNull(decision.fallbackReason)
        }
    }

    @Test
    fun `discreet on gives DISCREET without fallback for every kind and sdk`() {
        for (kind in NotificationKind.entries) for (sdk in sdkLevels) {
            val decision = NotificationContentPolicy.decide(kind, discreetOn, sdk)
            assertEquals(NotificationPresentationMode.DISCREET, decision.mode)
            assertFalse(decision.usesCallerText)
            assertFalse(decision.expandedStyleAllowed)
            assertNull(decision.fallbackReason)
        }
    }

    @Test
    fun `every unreadable cause gives DISCREET with a fallback reason for every kind and sdk`() {
        for (kind in NotificationKind.entries) for (sdk in sdkLevels) for (cause in SettingsUnreadableCause.entries) {
            val decision = NotificationContentPolicy.decide(kind, AppLockSettingsLoadState.Unreadable(cause), sdk)
            assertEquals(NotificationPresentationMode.DISCREET, decision.mode)
            assertFalse(decision.expandedStyleAllowed)
            val expected = if (cause == SettingsUnreadableCause.TIMEOUT) {
                NotificationFallbackReason.READ_TIMEOUT
            } else {
                NotificationFallbackReason.SETTINGS_UNREADABLE
            }
            assertEquals(expected, decision.fallbackReason)
        }
    }

    @Test
    fun `a null load state means the read timed out and gives DISCREET`() {
        for (kind in NotificationKind.entries) for (sdk in sdkLevels) {
            val decision = NotificationContentPolicy.decide(kind, null, sdk)
            assertEquals(NotificationPresentationMode.DISCREET, decision.mode)
            assertEquals(NotificationFallbackReason.READ_TIMEOUT, decision.fallbackReason)
        }
    }

    @Test
    fun `fail closed decision is DISCREET with the supplied reason`() {
        for (kind in NotificationKind.entries) for (sdk in sdkLevels) {
            val decision = NotificationContentPolicy.decideFailClosed(
                kind,
                sdk,
                NotificationFallbackReason.POLICY_EXCEPTION
            )
            assertEquals(NotificationPresentationMode.DISCREET, decision.mode)
            assertFalse(decision.expandedStyleAllowed)
            assertEquals(NotificationFallbackReason.POLICY_EXCEPTION, decision.fallbackReason)
        }
    }

    @Test
    fun `decideDiscreet gives DISCREET without fallback for every kind and sdk`() {
        for (kind in NotificationKind.entries) for (sdk in sdkLevels) {
            val decision = NotificationContentPolicy.decideDiscreet(kind, sdk)
            assertEquals(kind, decision.kind)
            assertEquals(NotificationPresentationMode.DISCREET, decision.mode)
            assertFalse(decision.usesCallerText)
            assertFalse(decision.expandedStyleAllowed)
            assertNull(decision.fallbackReason)
            assertEquals(NotificationDecisionVisibility.PRIVATE, decision.visibility)
            assertEquals(NotificationPublicVersion.GENERIC, decision.publicVersion)
        }
    }

    @Test
    fun `decideDiscreet equals the decide result for a discreet on state for every kind and sdk`() {
        for (kind in NotificationKind.entries) for (sdk in sdkLevels) {
            assertEquals(
                NotificationContentPolicy.decide(kind, discreetOn, sdk),
                NotificationContentPolicy.decideDiscreet(kind, sdk)
            )
        }
    }

    @Test
    fun `decideDiscreet applies the action policy per sdk`() {
        for (kind in NotificationKind.entries) for (sdk in sdkLevels) {
            val expected = if (kind == NotificationKind.BILL && sdk >= 31) {
                NotificationActionPolicy.AUTH_REQUIRED
            } else {
                NotificationActionPolicy.UNCHANGED
            }
            assertEquals(expected, NotificationContentPolicy.decideDiscreet(kind, sdk).actionPolicy)
        }
    }

    @Test
    fun `fail closed bill decision is auth required on api 31 and drops the actions on api 30 and below`() {
        for (reason in NotificationFallbackReason.entries) {
            for (sdk in listOf(31, 34)) {
                assertEquals(
                    NotificationActionPolicy.AUTH_REQUIRED,
                    NotificationContentPolicy.decideFailClosed(NotificationKind.BILL, sdk, reason).actionPolicy
                )
            }
            for (sdk in listOf(26, 30)) {
                assertEquals(
                    NotificationActionPolicy.NONE,
                    NotificationContentPolicy.decideFailClosed(NotificationKind.BILL, sdk, reason).actionPolicy
                )
            }
        }
    }

    // --- 1b lock terms (S14): INV-6 / OD-2a, INV-10, AE-54, AE-64, AE-65 ----------------------------

    private fun lockOn(discreet: Boolean = false) =
        AppLockSettingsLoadState.Loaded(
            AppLockPrivacySettings(discreetNotifications = discreet, appLockEnabled = true)
        )

    @Test
    fun `AE-65 lock on with discreet off gives DISCREET without fallback for every kind and sdk`() {
        for (kind in NotificationKind.entries) for (sdk in sdkLevels) {
            val decision = NotificationContentPolicy.decide(kind, lockOn(discreet = false), sdk)
            assertEquals(NotificationPresentationMode.DISCREET, decision.mode)
            assertFalse(decision.usesCallerText)
            assertFalse(decision.expandedStyleAllowed)
            assertNull(decision.fallbackReason)
        }
    }

    @Test
    fun `lock term holds on api 26 to 29 where the lock can only be inert`() {
        for (sdk in listOf(26, 28, 29)) {
            assertEquals(
                NotificationPresentationMode.DISCREET,
                NotificationContentPolicy.decide(NotificationKind.BILL, lockOn(), sdk).mode
            )
        }
    }

    @Test
    fun `lock off with discreet off stays FULL (the lock term does not leak)`() {
        val state = AppLockSettingsLoadState.Loaded(
            AppLockPrivacySettings(discreetNotifications = false, appLockEnabled = false, secureScreen = true)
        )
        for (kind in NotificationKind.entries) for (sdk in sdkLevels) {
            assertEquals(NotificationPresentationMode.FULL, NotificationContentPolicy.decide(kind, state, sdk).mode)
        }
    }

    @Test
    fun `AE-64 bill actions follow the api and lock rows`() {
        // API 31+: auth required for every state.
        for (sdk in listOf(31, 34)) for (state in listOf(full, lockOn(), null)) {
            assertEquals(
                NotificationActionPolicy.AUTH_REQUIRED,
                NotificationContentPolicy.decide(NotificationKind.BILL, state, sdk).actionPolicy
            )
        }
        // API 30 and below: lock on, unreadable of any cause, or a timed-out read drop the actions.
        val dropped = listOf(lockOn(), lockOn(discreet = true), null) +
            SettingsUnreadableCause.entries.map { AppLockSettingsLoadState.Unreadable(it) }
        for (sdk in listOf(26, 28, 29, 30)) for (state in dropped) {
            assertEquals(
                "state=$state sdk=$sdk",
                NotificationActionPolicy.NONE,
                NotificationContentPolicy.decide(NotificationKind.BILL, state, sdk).actionPolicy
            )
        }
        // API 30 and below, lock off and readable: unchanged, including with discreet on.
        for (sdk in listOf(26, 28, 29, 30)) for (state in listOf(full, discreetOn, firstRun)) {
            assertEquals(
                NotificationActionPolicy.UNCHANGED,
                NotificationContentPolicy.decide(NotificationKind.BILL, state, sdk).actionPolicy
            )
        }
    }

    @Test
    fun `the lock never changes the action policy of non bill kinds`() {
        for (kind in NotificationKind.entries.filter { it != NotificationKind.BILL }) for (sdk in sdkLevels) {
            for (state in listOf(lockOn(), null, AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))) {
                assertEquals(
                    NotificationActionPolicy.UNCHANGED,
                    NotificationContentPolicy.decide(kind, state, sdk).actionPolicy
                )
            }
        }
    }

    @Test
    fun `decideDiscreet drops bill actions on api 30 and below only when told the lock or unreadable applies`() {
        for (sdk in listOf(26, 30)) {
            assertEquals(
                NotificationActionPolicy.NONE,
                NotificationContentPolicy.decideDiscreet(NotificationKind.BILL, sdk, actionsSuppressed = true).actionPolicy
            )
            assertEquals(
                NotificationActionPolicy.UNCHANGED,
                NotificationContentPolicy.decideDiscreet(NotificationKind.BILL, sdk).actionPolicy
            )
        }
        assertEquals(
            NotificationActionPolicy.AUTH_REQUIRED,
            NotificationContentPolicy.decideDiscreet(NotificationKind.BILL, 31, actionsSuppressed = true).actionPolicy
        )
    }

    @Test
    fun `visibility is private and the public version is the generic one in every mode`() {
        val states = listOf(full, discreetOn, firstRun, null) +
            SettingsUnreadableCause.entries.map { AppLockSettingsLoadState.Unreadable(it) }
        for (kind in NotificationKind.entries) for (state in states) {
            val decision = NotificationContentPolicy.decide(kind, state, 34)
            assertEquals(NotificationDecisionVisibility.PRIVATE, decision.visibility)
            assertEquals(NotificationPublicVersion.GENERIC, decision.publicVersion)
        }
    }

    @Test
    fun `bill actions require authentication on api 31 and above in every mode`() {
        val states = listOf(full, discreetOn, null, AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR))
        for (sdk in listOf(31, 34)) for (state in states) {
            assertEquals(
                NotificationActionPolicy.AUTH_REQUIRED,
                NotificationContentPolicy.decide(NotificationKind.BILL, state, sdk).actionPolicy
            )
        }
    }

    @Test
    fun `bill actions are unchanged on api 30 and below while the lock is off and the settings are readable`() {
        val states = listOf(full, discreetOn, firstRun)
        for (sdk in listOf(26, 28, 29, 30)) for (state in states) {
            assertEquals(
                NotificationActionPolicy.UNCHANGED,
                NotificationContentPolicy.decide(NotificationKind.BILL, state, sdk).actionPolicy
            )
        }
    }

    @Test
    fun `kinds other than bill never get an action policy that changes anything`() {
        for (kind in NotificationKind.entries.filter { it != NotificationKind.BILL }) for (sdk in sdkLevels) {
            assertEquals(
                NotificationActionPolicy.UNCHANGED,
                NotificationContentPolicy.decide(kind, full, sdk).actionPolicy
            )
        }
    }

    @Test
    fun `expanded style is allowed only for anomaly and briefing in FULL`() {
        for (kind in NotificationKind.entries) {
            val expected = kind == NotificationKind.ANOMALY || kind == NotificationKind.BRIEFING
            assertEquals(expected, NotificationContentPolicy.decide(kind, full, 34).expandedStyleAllowed)
            assertFalse(NotificationContentPolicy.decide(kind, discreetOn, 34).expandedStyleAllowed)
        }
    }

    @Test
    fun `warranty and receipt match are distinct kinds from budget in every decision`() {
        val budget = NotificationContentPolicy.decide(NotificationKind.BUDGET, discreetOn, 34)
        for (kind in listOf(NotificationKind.WARRANTY, NotificationKind.RECEIPT_MATCH)) {
            val decision = NotificationContentPolicy.decide(kind, discreetOn, 34)
            assertEquals(kind, decision.kind)
            assertFalse(decision.kind == budget.kind)
        }
    }
}
