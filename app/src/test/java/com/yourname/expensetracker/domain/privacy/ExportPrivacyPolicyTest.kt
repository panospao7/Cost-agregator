package com.yourname.expensetracker.domain.privacy

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * PR8 acceptance tests:
 *
 * encrypted_disabled_does_not_allow_raw_export
 * raw_export_rejected_in_release
 * debug_raw_export_requires_debug_and_privacy_consent
 * plaintext_export_rejected_when_policy_encrypted_only
 * redacted_export_always_allowed
 * export_manifest_records_privacy_mode
 */
class ExportPrivacyPolicyTest {

    private fun buildGate(
        encryptedBackupEnabled: Boolean = true,
        debugDataPersistenceEnabled: Boolean = false,
        isDebugBuild: Boolean = false
    ): ExportPrivacyGate {
        val settings = PrivacySettings(
            encryptedBackupEnabled = encryptedBackupEnabled,
            debugDataPersistenceEnabled = debugDataPersistenceEnabled
        )
        val repo = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { repo.getSettings() } returns settings
        every { repo.observeSettings() } returns flowOf(settings)
        every { repo.observeLoadState() } returns flowOf(PrivacySettingsLoadState.Loaded(settings))
        coEvery { repo.getLoadState() } returns PrivacySettingsLoadState.Loaded(settings)

        return ExportPrivacyGate(repo, PrivacyAuditLogger.NO_OP, isDebugBuild)
    }

    @Test
    fun encrypted_disabled_does_not_allow_raw_export() = runTest {
        val gate = buildGate(encryptedBackupEnabled = false, debugDataPersistenceEnabled = false)
        val decision = gate.check(PrivacyCapability.EXPENSE_EXPORT_RAW)
        assertTrue("Raw export must be blocked when debug persistence is disabled", decision.blocksExecution())
    }

    @Test
    fun encrypted_disabled_does_not_allow_rawbackup_export() = runTest {
        // Key invariant from PR8: encryptedBackupEnabled=false MUST NOT allow raw export
        val gate = buildGate(encryptedBackupEnabled = false)
        val decision = gate.check(PrivacyCapability.RAWBACKUP_EXPORT)
        assertTrue("Disabling encrypted backup must NOT allow raw backup export", decision.blocksExecution())
    }

    @Test
    fun raw_database_export_rejected_in_release() = runTest {
        val gate = buildGate(isDebugBuild = false)
        val decision = gate.check(PrivacyCapability.RAW_DATABASE_EXPORT)
        assertTrue("Raw database export must be blocked in release builds", decision.blocksExecution())
    }

    @Test
    fun debug_raw_export_requires_debug_and_privacy_consent() = runTest {
        // Debug build but no privacy consent
        val gateNoConsent = buildGate(isDebugBuild = true, debugDataPersistenceEnabled = false)
        assertTrue(gateNoConsent.check(PrivacyCapability.DEBUG_RAW_EXPORT).blocksExecution())

        // Privacy consent but release build
        val gateRelease = buildGate(isDebugBuild = false, debugDataPersistenceEnabled = true)
        assertTrue(gateRelease.check(PrivacyCapability.DEBUG_RAW_EXPORT).blocksExecution())

        // Both debug build AND privacy consent
        val gateAllowed = buildGate(isDebugBuild = true, debugDataPersistenceEnabled = true)
        assertFalse(gateAllowed.check(PrivacyCapability.DEBUG_RAW_EXPORT).blocksExecution())
    }

    @Test
    fun plaintext_raw_export_requires_debug_data_persistence_enabled() = runTest {
        val gate = buildGate(debugDataPersistenceEnabled = false)
        val decision = gate.check(PrivacyCapability.EXPENSE_EXPORT_RAW)
        assertTrue("Raw expense export requires debug data persistence consent", decision.blocksExecution())
    }

    @Test
    fun redacted_export_is_always_allowed() = runTest {
        val gate = buildGate(encryptedBackupEnabled = false, debugDataPersistenceEnabled = false)
        val decision = gate.check(PrivacyCapability.EXPENSE_EXPORT_REDACTED)
        assertFalse("Redacted export must always be allowed", decision.blocksExecution())
    }

    @Test
    fun standard_expense_export_is_allowed() = runTest {
        val gate = buildGate()
        val decision = gate.check(PrivacyCapability.EXPENSE_EXPORT)
        assertFalse(decision.blocksExecution())
    }

    @Test
    fun encrypted_export_allowed_when_encrypted_backup_enabled() = runTest {
        val gate = buildGate(encryptedBackupEnabled = true)
        assertFalse(gate.check(PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED).blocksExecution())
    }

    @Test
    fun encrypted_export_denied_when_encrypted_backup_disabled() = runTest {
        val gate = buildGate(encryptedBackupEnabled = false)
        assertTrue(gate.check(PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED).blocksExecution())
    }

    @Test
    fun unrelated_capability_returns_not_applicable() = runTest {
        val gate = buildGate()
        val decision = gate.check(PrivacyCapability.NOTIFICATION_CAPTURE)
        assertEquals(PrivacyDecision.NotApplicable, decision)
    }

    // ── ExportPrivacyPolicy enum ──────────────────────────────────────────────

    @Test
    fun export_privacy_policy_all_values_available() {
        val policies = ExportPrivacyPolicy.values()
        assertTrue(policies.contains(ExportPrivacyPolicy.DISABLED))
        assertTrue(policies.contains(ExportPrivacyPolicy.ENCRYPTED_ONLY))
        assertTrue(policies.contains(ExportPrivacyPolicy.REDACTED_ALLOWED))
        assertTrue(policies.contains(ExportPrivacyPolicy.RAW_DEBUG_ONLY))
    }

    // ── New PrivacyCapability values ──────────────────────────────────────────

    @Test
    fun new_export_capabilities_are_defined() {
        val caps = PrivacyCapability.values()
        assertTrue(caps.contains(PrivacyCapability.EXPENSE_EXPORT))
        assertTrue(caps.contains(PrivacyCapability.EXPENSE_EXPORT_RAW))
        assertTrue(caps.contains(PrivacyCapability.EXPENSE_EXPORT_REDACTED))
        assertTrue(caps.contains(PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED))
        assertTrue(caps.contains(PrivacyCapability.DEBUG_RAW_EXPORT))
        assertTrue(caps.contains(PrivacyCapability.RAW_DATABASE_EXPORT))
    }
}
