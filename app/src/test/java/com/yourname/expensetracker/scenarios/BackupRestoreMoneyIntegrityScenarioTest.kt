package com.yourname.expensetracker.scenarios

import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.APP_DATABASE_SCHEMA_VERSION
import com.yourname.expensetracker.data.database.DatabaseSchemaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario test for backup/restore money integrity.
 *
 * Validates that supported migration definitions are derived consistently from the
 * current schema version and migration baseline. DAO-presence and expense-field
 * roundtrip checks were removed as redundant: every real-DB test builds the full
 * AppDatabase, and data/database/dao/ExpenseDaoDateRangeTest already asserts the
 * expense core-field roundtrip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupRestoreMoneyIntegrityScenarioTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: ALL_MIGRATIONS covers the supported baseline through the current schema
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `ALL_MIGRATIONS covers the supported migration chain`() {
        val baseline = DatabaseSchemaPolicy.MIGRATION_BASELINE
        val expectedSteps = (baseline until APP_DATABASE_SCHEMA_VERSION)
            .map { it to it + 1 }
        val registeredSteps = AppDatabase.ALL_MIGRATIONS
            .map { it.startVersion to it.endVersion }

        // Registration contract only: migration execution needs its own DB tests.
        assertTrue("Supported baseline must not exceed current schema", baseline <= APP_DATABASE_SCHEMA_VERSION)
        assertEquals("Exactly one registration for each supported step", expectedSteps, registeredSteps)
        assertTrue("Retired schemas are outside the supported chain",
            (117..120).all { it in DatabaseSchemaPolicy.UNSUPPORTED_VERSIONS })
    }
}
