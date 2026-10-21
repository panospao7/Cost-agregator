package com.yourname.expensetracker.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real behavior regression tests for DDL-A8 series.
 *
 * Sanitizer behaviour formerly asserted here (hash-key values, unknown-object
 * toString) lives in [EventMetadataSanitizerTest].
 */
class DurableDiagnosticsA8RegressionTest {

    // ── PR 2: Restore privacy — no full paths in diagnostics ──────────────────

    @Test
    fun restore_journal_diagnostics_json_strips_internal_paths() {
        // toDiagnosticsJson() must not expose _sourceBackupPath etc.
        val entry = com.yourname.expensetracker.data.backup.RestoreJournal.JournalEntry(
            sourceBackupPath = "/data/user/0/com.app/files/backup.costbackup",
            stagedDbPath = "/data/user/0/com.app/databases/staged.db",
            liveDbPath = "/data/user/0/com.app/databases/app.db"
        )
        val diagJson = entry.toDiagnosticsJson().toString()
        assertFalse("_sourceBackupPath must not be in diagnostics", diagJson.contains("_sourceBackupPath"))
        assertFalse("_stagedDbPath must not be in diagnostics", diagJson.contains("_stagedDbPath"))
        assertFalse("full path must not be in diagnostics", diagJson.contains("/data/user/0"))
        // Recovery journal (toJson) still has them
        val recoveryJson = entry.toJson().toString()
        assertTrue("_sourceBackupPath must be in recovery JSON", recoveryJson.contains("_sourceBackupPath"))
    }

    // PR 4 (safe handle terminal-once) is covered against the production class in
    // com.yourname.expensetracker.domain.diagnostics.SafeSinkOperationRunHandleTest.
}
