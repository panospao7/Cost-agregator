package com.yourname.expensetracker.domain.privacy

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * P8 gate-ownership tests.
 *
 * After the single-owner cleanup, [BackupPrivacyGate] owns only ENCRYPTED_BACKUP.
 * RAWBACKUP_EXPORT is owned solely by [ExportPrivacyGate], so BackupPrivacyGate
 * must return [PrivacyDecision.NotApplicable] for it (no conflicting verdict).
 */
class BackupPrivacyGateOwnershipTest {

    private fun gate(encryptedBackupEnabled: Boolean): BackupPrivacyGate {
        val settings = PrivacySettings(encryptedBackupEnabled = encryptedBackupEnabled)
        val repo = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { repo.getSettings() } returns settings
        every { repo.observeSettings() } returns flowOf(settings)
        return BackupPrivacyGate(repo, PrivacyAuditLogger.NO_OP)
    }

    @Test
    fun rawbackup_export_is_not_applicable_regardless_of_encryption() = runTest {
        assertEquals(
            PrivacyDecision.NotApplicable,
            gate(encryptedBackupEnabled = false).check(PrivacyCapability.RAWBACKUP_EXPORT)
        )
        assertEquals(
            PrivacyDecision.NotApplicable,
            gate(encryptedBackupEnabled = true).check(PrivacyCapability.RAWBACKUP_EXPORT)
        )
    }

    @Test
    fun encrypted_backup_allowed_when_enabled() = runTest {
        val decision = gate(encryptedBackupEnabled = true).check(PrivacyCapability.ENCRYPTED_BACKUP)
        assertEquals(PrivacyDecision.Allowed, decision)
    }

    @Test
    fun encrypted_backup_denied_when_disabled() = runTest {
        val decision = gate(encryptedBackupEnabled = false).check(PrivacyCapability.ENCRYPTED_BACKUP)
        assertTrue(decision.blocksExecution())
    }
}
