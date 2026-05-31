package com.yourname.expensetracker.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P7-CURRENT-014 / P7-CURRENT-015 — verifier hardening.
 *
 * - [BackupVerifier.requiredManifestTables] / [BackupVerifier.validateManifestCompleteness]:
 *   a backup manifest missing required Tier 1 counts must be rejected BEFORE the
 *   destructive live-DB swap (P7-CURRENT-014).
 * - [BackupVerifier.collectTableCountsStrict]: a count-query failure on a required
 *   table during backup creation must fail loudly, never silently record 0
 *   (P7-CURRENT-015).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupVerifierManifestTest {

    // ── P7-CURRENT-014: required manifest tables ──────────────────

    @Test
    fun `requiredManifestTables are the Tier 1 exact tables`() {
        val required = BackupVerifier.requiredManifestTables()
        assertTrue("expenses must be required", required.contains("expenses"))
        assertTrue("budgets must be required", required.contains("budgets"))
        // Tier 3 optional tables must NOT be required.
        assertFalse("exchange_rates is optional", required.contains("exchange_rates"))
        assertFalse("privacy_audit_events tier handled separately", required.contains("background_job_runs"))
        // Every required table is Tier 1 exact.
        required.forEach {
            assertEquals(
                "Required table $it must be TIER_1_EXACT",
                BackupVerifier.VerificationTier.TIER_1_EXACT,
                BackupVerifier.tableTier(it)
            )
        }
    }

    @Test
    fun `validateManifestCompleteness passes when all required counts present`() {
        val complete = BackupVerifier.requiredManifestTables().associateWith { 0 }
        // Should not throw.
        BackupVerifier.validateManifestCompleteness(complete)
    }

    @Test
    fun `validateManifestCompleteness throws when a required count is missing`() {
        val incomplete = BackupVerifier.requiredManifestTables()
            .associateWith { 1 }
            .toMutableMap()
            .apply { remove("expenses") }

        val ex = assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
            BackupVerifier.validateManifestCompleteness(incomplete)
        }
        assertTrue("missing list must name the dropped table", ex.missingTables.contains("expenses"))
    }

    @Test
    fun `validateManifestCompleteness lists all missing required tables`() {
        // Empty manifest → every required table is missing.
        val ex = assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
            BackupVerifier.validateManifestCompleteness(emptyMap())
        }
        assertEquals(
            BackupVerifier.requiredManifestTables().sorted(),
            ex.missingTables.sorted()
        )
    }

    // ── P7-CURRENT-015: strict count collection ───────────────────

    @Test
    fun `collectTableCountsStrict throws on a DB missing required tables`() {
        // An empty in-memory DB has none of the required tables.
        val db = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            assertThrows(BackupVerifier.RequiredTableCountException::class.java) {
                BackupVerifier.collectTableCountsStrict(db)
            }
        } finally {
            db.close()
        }
    }
}
