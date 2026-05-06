package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.yourname.expensetracker.data.backup.BackupVerifier
import com.yourname.expensetracker.data.backup.BackupVerifier.VerificationTier
import com.yourname.expensetracker.data.backup.CostbackupBundle
import com.yourname.expensetracker.data.backup.CostbackupBundle.InvalidBackupFormatException
import com.yourname.expensetracker.data.backup.CostbackupBundle.UnsupportedBackupVersionException
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreJournal.RecoveryResult
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Low-level contract tests for backup/restore primitives.
 *
 * These tests verify each primitive in isolation without relying on the
 * [com.yourname.expensetracker.domain.backup.DatabaseBackupRepository]
 * orchestration layer.
 *
 * ## Primitives covered
 *
 * - [RestoreMaintenanceMode]: write-blocking state machine
 * - [RestoreJournal]: crash-safe restore journal initial state
 * - [CostbackupBundle]: magic header validation
 * - [BackupVerifier]: table name registry and tier mapping
 *
 * ## Primitives with existing coverage (skipped)
 *
 * - [com.yourname.expensetracker.data.privacy.BackupEncryptionService] —
 *   fully covered by [com.yourname.expensetracker.data.privacy.BackupEncryptionServiceTest]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupRestoreContractTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Initialise WorkManager so that RestoreMaintenanceMode.enter() can
        // call pauseAllWorkers() without throwing.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        // WorkManager is a process-level singleton; the test initialisation
        // via WorkManagerTestInitHelper is automatically cleaned up by
        // Robolectric between tests.
    }

    // ─────────────────────────────────────────────────────────────────────
    // RestoreMaintenanceMode
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `restoreMaintenanceMode blocks writes when in restore mode`() {
        // GIVEN: a fresh RestoreMaintenanceMode (defaults to NORMAL)
        val modeManager = RestoreMaintenanceMode(context)

        // THEN: writes are allowed in the initial NORMAL state
        assertTrue(
            "Writes should be allowed in NORMAL mode",
            modeManager.isWritesAllowed()
        )

        // WHEN: entering RESTORE_PREPARING mode
        modeManager.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)

        // THEN: writes are blocked
        assertFalse(
            "Writes should be blocked in RESTORE_PREPARING mode",
            modeManager.isWritesAllowed()
        )
        assertEquals(
            "Current mode should be RESTORE_PREPARING",
            RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
            modeManager.currentMode()
        )

        // Clean up: exit maintenance mode so other tests start fresh
        modeManager.exit()
    }

    @Test
    fun `restoreMaintenanceMode allows writes in normal and backup modes`() {
        // GIVEN: a fresh RestoreMaintenanceMode
        val modeManager = RestoreMaintenanceMode(context)

        // THEN: NORMAL mode allows writes
        assertTrue(
            "Writes should be allowed in NORMAL mode",
            modeManager.isWritesAllowed()
        )

        // WHEN: entering BACKUP_EXPORTING mode
        modeManager.enter(RestoreMaintenanceMode.Mode.BACKUP_EXPORTING)

        // THEN: writes are still allowed during backup export
        assertTrue(
            "Writes should be allowed in BACKUP_EXPORTING mode",
            modeManager.isWritesAllowed()
        )
        assertEquals(
            "Current mode should be BACKUP_EXPORTING",
            RestoreMaintenanceMode.Mode.BACKUP_EXPORTING,
            modeManager.currentMode()
        )

        // Clean up: reset to NORMAL (avoids side effects in other tests)
        modeManager.exit()
    }

    @Test
    fun `restoreMaintenanceMode reset restores writes`() {
        // GIVEN: RestoreMaintenanceMode in RESTORE_STAGING mode
        val modeManager = RestoreMaintenanceMode(context)
        modeManager.enter(RestoreMaintenanceMode.Mode.RESTORE_STAGING)
        assertFalse("Precondition: writes should be blocked", modeManager.isWritesAllowed())

        // WHEN: resetting to NORMAL
        modeManager.reset()

        // THEN: writes are allowed again and current mode is NORMAL
        assertTrue("Writes should be allowed after reset", modeManager.isWritesAllowed())
        assertEquals(
            "Current mode should be NORMAL after reset",
            RestoreMaintenanceMode.Mode.NORMAL,
            modeManager.currentMode()
        )
    }

    @Test
    fun `restoreMaintenanceMode exit without force restores writes`() {
        // GIVEN: RestoreMaintenanceMode in RESTORE_VERIFYING mode
        val modeManager = RestoreMaintenanceMode(context)
        modeManager.enter(RestoreMaintenanceMode.Mode.RESTORE_VERIFYING)
        assertFalse("Precondition: writes should be blocked", modeManager.isWritesAllowed())

        // WHEN: exiting without forceRestartRequired
        modeManager.exit(forceRestartRequired = false)

        // THEN: writes are allowed and mode is NORMAL
        assertTrue(
            "Writes should be allowed after exit(false)",
            modeManager.isWritesAllowed()
        )
        assertEquals(
            "Current mode should be NORMAL after exit(false)",
            RestoreMaintenanceMode.Mode.NORMAL,
            modeManager.currentMode()
        )
    }

    @Test
    fun `restoreMaintenanceMode exit with force keeps writes blocked`() {
        // GIVEN: RestoreMaintenanceMode in RESTORE_ROLLING_BACK mode
        val modeManager = RestoreMaintenanceMode(context)
        modeManager.enter(RestoreMaintenanceMode.Mode.RESTORE_ROLLING_BACK)
        assertFalse("Precondition: writes should be blocked", modeManager.isWritesAllowed())

        // WHEN: exiting with forceRestartRequired = true
        modeManager.exit(forceRestartRequired = true)

        // THEN: writes remain blocked and mode transitions to RESTORE_COMPLETE_RESTART_REQUIRED
        assertFalse(
            "Writes should remain blocked after exit(true)",
            modeManager.isWritesAllowed()
        )
        assertEquals(
            "Current mode should be RESTORE_COMPLETE_RESTART_REQUIRED after exit(true)",
            RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            modeManager.currentMode()
        )

        // Clean up: reset for next tests
        modeManager.reset()
    }

    @Test
    fun `restoreMaintenanceMode all restore modes block writes`() {
        // GIVEN: RestoreMaintenanceMode
        val modeManager = RestoreMaintenanceMode(context)

        // WHEN/THEN: each restore mode blocks writes
        val restoreModes = listOf(
            RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
            RestoreMaintenanceMode.Mode.RESTORE_STAGING,
            RestoreMaintenanceMode.Mode.RESTORE_SWAPPING,
            RestoreMaintenanceMode.Mode.RESTORE_VERIFYING,
            RestoreMaintenanceMode.Mode.RESTORE_ROLLING_BACK,
            RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED
        )

        for (mode in restoreModes) {
            modeManager.reset()
            modeManager.enter(mode)
            assertFalse("Writes should be blocked in $mode mode", modeManager.isWritesAllowed())
        }

        // Clean up
        modeManager.reset()
    }

    // ─────────────────────────────────────────────────────────────────────
    // RestoreJournal
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `restoreJournal initial state is clean`() {
        // GIVEN: a fresh RestoreJournal with no prior journal file
        val journal = RestoreJournal(context)

        // THEN: no journal exists
        assertFalse("hasJournal should return false initially", journal.hasJournal())
        assertNull("readJournal should return null initially", journal.readJournal())
    }

    @Test
    fun `restoreJournal checkAndRecover returns NoAction when clean`() {
        // GIVEN: a fresh RestoreJournal with no journal file
        val journal = RestoreJournal(context)

        // WHEN: checking and recovering
        val result = journal.checkAndRecover()

        // THEN: NoAction is returned
        assertTrue(
            "Expected NoAction for a clean journal, got $result",
            result is RecoveryResult.NoAction
        )
    }

    @Test
    fun `restoreJournal beginJournal creates a journal entry`() {
        // GIVEN: a fresh RestoreJournal
        val journal = RestoreJournal(context)

        // WHEN: beginning a journal
        val sourcePath = "/tmp/source.costbackup"
        val stagedPath = "/tmp/staged.db"
        val livePath = "/tmp/live.db"
        val entry = journal.beginJournal(
            sourceBackupPath = sourcePath,
            stagedDbPath = stagedPath,
            liveDbPath = livePath
        )

        // THEN: a journal entry is created with PREPARING state
        assertEquals(
            "Journal state should be PREPARING",
            RestoreJournal.JournalState.PREPARING,
            entry.state
        )
        assertEquals("Source backup path should match", sourcePath, entry.sourceBackupPath)
        assertEquals("Staged DB path should match", stagedPath, entry.stagedDbPath)
        assertEquals("Live DB path should match", livePath, entry.liveDbPath)
        assertTrue("Journal file should exist after beginJournal", journal.hasJournal())

        // Clean up
        journal.deleteJournal()
    }

    @Test
    fun `restoreJournal transitionTo updates state`() {
        // GIVEN: an existing journal entry
        val journal = RestoreJournal(context)
        val entry = journal.beginJournal(
            sourceBackupPath = "/tmp/source.costbackup",
            stagedDbPath = "/tmp/staged.db",
            liveDbPath = "/tmp/live.db"
        )

        // WHEN: transitioning to STAGED
        val updated = journal.transitionTo(entry, RestoreJournal.JournalState.STAGED)

        // THEN: the state is updated
        assertEquals(
            "Journal state should transition to STAGED",
            RestoreJournal.JournalState.STAGED,
            updated.state
        )

        // Clean up
        journal.deleteJournal()
    }

    @Test
    fun `restoreJournal commitJournal clears the journal file`() {
        // GIVEN: an existing journal entry
        val journal = RestoreJournal(context)
        journal.beginJournal(
            sourceBackupPath = "/tmp/source.costbackup",
            stagedDbPath = "/tmp/staged.db",
            liveDbPath = "/tmp/live.db"
        )
        assertTrue("Precondition: journal file should exist", journal.hasJournal())

        // WHEN: committing the journal
        val readEntry = journal.readJournal()!!
        journal.commitJournal(readEntry)

        // THEN: the journal file is deleted and no journal exists
        assertFalse("Journal file should be deleted after commit", journal.hasJournal())
    }

    @Test
    fun `restoreJournal failJournal clears the journal file`() {
        // GIVEN: an existing journal entry
        val journal = RestoreJournal(context)
        journal.beginJournal(
            sourceBackupPath = "/tmp/source.costbackup",
            stagedDbPath = "/tmp/staged.db",
            liveDbPath = "/tmp/live.db"
        )
        assertTrue("Precondition: journal file should exist", journal.hasJournal())

        // WHEN: failing the journal
        val readEntry = journal.readJournal()!!
        val failed = journal.failJournal(readEntry, "Test failure")

        // THEN: the journal file is deleted and no journal exists
        assertFalse("Journal file should be deleted after fail", journal.hasJournal())
        assertEquals(
            "Failed journal should report FAILED state",
            RestoreJournal.JournalState.FAILED,
            failed.state
        )
        assertEquals(
            "Failed journal should include the error message",
            "Test failure",
            failed.error
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // CostbackupBundle — magic header validation
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `backupBundle valid header returns remaining ciphertext`() {
        // GIVEN: a valid .costbackup header: "COSTBACKUP" + version 1
        val magic = "COSTBACKUP".toByteArray(Charsets.US_ASCII) // 10 bytes
        val version = byteArrayOf(0x00, 0x01) // big-endian uint16 = 1
        val ciphertext = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val headerPlusCipher = magic + version + ciphertext

        // WHEN: reading the header
        val remaining = CostbackupBundle.readHeader(headerPlusCipher)

        // THEN: no exception is thrown and the remaining bytes are the ciphertext
        assertArrayEquals(
            "Remaining bytes should be the ciphertext payload",
            ciphertext, remaining
        )
    }

    @Test
    fun `backupBundle invalid magic throws InvalidBackupFormatException`() {
        // GIVEN: an invalid magic prefix
        val badMagic = "BADMAGIC!!".toByteArray(Charsets.US_ASCII)
        val version = byteArrayOf(0x00, 0x01)
        val header = badMagic + version

        // WHEN: reading the header
        val exception = assertThrows(
            "Invalid magic should throw InvalidBackupFormatException",
            InvalidBackupFormatException::class.java
        ) {
            CostbackupBundle.readHeader(header)
        }

        // THEN: the exception message mentions the magic
        assertTrue(
            "Exception should mention invalid magic",
            exception.message?.contains("Invalid magic") == true
        )
        assertTrue(
            "Exception should mention the expected magic",
            exception.message?.contains("COSTBACKUP") == true
        )
    }

    @Test
    fun `backupBundle unsupported version throws UnsupportedBackupVersionException`() {
        // GIVEN: magic followed by unsupported version 999
        val magic = "COSTBACKUP".toByteArray(Charsets.US_ASCII)
        val badVersion = byteArrayOf(0x03, 0xE7.toByte()) // big-endian uint16 = 999
        val header = magic + badVersion

        // WHEN: reading the header
        val exception = assertThrows(
            "Unsupported version should throw UnsupportedBackupVersionException",
            UnsupportedBackupVersionException::class.java
        ) {
            CostbackupBundle.readHeader(header)
        }

        // THEN: the exception message mentions the version
        assertTrue(
            "Exception should mention unsupported version",
            exception.message?.contains("Unsupported format version") == true
        )
        assertTrue(
            "Exception should mention version 999",
            exception.message?.contains("999") == true
        )
    }

    @Test
    fun `backupBundle header too short throws IllegalArgumentException`() {
        // GIVEN: a byte array shorter than the 12-byte header
        val tooShort = "COSTBACKUP".toByteArray(Charsets.US_ASCII) // only 10 bytes

        // WHEN: reading the header
        val exception = assertThrows(
            "Short header should throw IllegalArgumentException",
            IllegalArgumentException::class.java
        ) {
            CostbackupBundle.readHeader(tooShort)
        }

        // THEN: the exception mentions the file being too short
        assertTrue(
            "Exception should mention the file is too short",
            exception.message?.contains("too short") == true
        )
    }

    @Test
    fun `backupBundle readHeaderFromStream consumes correct bytes`() {
        // GIVEN: valid header bytes in an input stream
        val magic = "COSTBACKUP".toByteArray(Charsets.US_ASCII)
        val version = byteArrayOf(0x00, 0x01) // big-endian uint16 = 1
        val ciphertext = ByteArray(32) { it.toByte() }
        val fullData = magic + version + ciphertext
        val inputStream = java.io.ByteArrayInputStream(fullData)

        // WHEN: reading the header from the stream (consumes 12 bytes)
        CostbackupBundle.readHeaderFromStream(inputStream)

        // THEN: the stream is positioned at the ciphertext start
        val remaining = inputStream.readBytes()
        assertArrayEquals(
            "Stream should be positioned at ciphertext after header read",
            ciphertext, remaining
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // BackupVerifier — table count and tier mapping
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `backupVerifier reports 56 table names`() {
        // GIVEN: the BackupVerifier table registry
        val tableNames = BackupVerifier.allTableNames()

        // THEN: it contains exactly 56 tables
        assertEquals(
            "BackupVerifier should track exactly 56 tables",
            56, tableNames.size
        )
    }

    @Test
    fun `backupVerifier includes known core tables`() {
        // GIVEN: the BackupVerifier table registry
        val tableNames = BackupVerifier.allTableNames()

        // THEN: core user-data tables are present
        assertTrue("expenses should be in the table registry", tableNames.contains("expenses"))
        assertTrue("categories should be in the table registry", tableNames.contains("categories"))
        assertTrue("budgets should be in the table registry", tableNames.contains("budgets"))
        assertTrue("warranties should be in the table registry", tableNames.contains("warranties"))
        assertTrue("investments should be in the table registry", tableNames.contains("investments"))
    }

    @Test
    fun `backupVerifier tier returns TIER_1_EXACT for core tables`() {
        // GIVEN: core user-data tables
        // THEN: they are mapped to TIER_1_EXACT
        assertEquals(
            "expenses should be TIER_1_EXACT",
            VerificationTier.TIER_1_EXACT,
            BackupVerifier.tableTier("expenses")
        )
        assertEquals(
            "categories should be TIER_1_EXACT",
            VerificationTier.TIER_1_EXACT,
            BackupVerifier.tableTier("categories")
        )
        assertEquals(
            "budgets should be TIER_1_EXACT",
            VerificationTier.TIER_1_EXACT,
            BackupVerifier.tableTier("budgets")
        )
    }

    @Test
    fun `backupVerifier tier returns TIER_2_VALIDITY for derived tables`() {
        // GIVEN: derived/cached tables
        // THEN: they are mapped to TIER_2_VALIDITY
        assertEquals(
            "blocked_packages should be TIER_2_VALIDITY",
            VerificationTier.TIER_2_VALIDITY,
            BackupVerifier.tableTier("blocked_packages")
        )
        assertEquals(
            "ai_artifacts should be TIER_2_VALIDITY",
            VerificationTier.TIER_2_VALIDITY,
            BackupVerifier.tableTier("ai_artifacts")
        )
    }

    @Test
    fun `backupVerifier tier returns TIER_3_OPTIONAL for optional tables`() {
        // GIVEN: optional/cache tables
        // THEN: they are mapped to TIER_3_OPTIONAL
        assertEquals(
            "exchange_rates should be TIER_3_OPTIONAL",
            VerificationTier.TIER_3_OPTIONAL,
            BackupVerifier.tableTier("exchange_rates")
        )
        assertEquals(
            "anomaly_alerts should be TIER_3_OPTIONAL",
            VerificationTier.TIER_3_OPTIONAL,
            BackupVerifier.tableTier("anomaly_alerts")
        )
    }

    @Test
    fun `backupVerifier tier defaults to TIER_3_OPTIONAL for unknown tables`() {
        // GIVEN: a table name not in the registry
        // THEN: it defaults to TIER_3_OPTIONAL
        assertEquals(
            "Unknown table should default to TIER_3_OPTIONAL",
            VerificationTier.TIER_3_OPTIONAL,
            BackupVerifier.tableTier("nonexistent_table_xyz")
        )
    }
}
