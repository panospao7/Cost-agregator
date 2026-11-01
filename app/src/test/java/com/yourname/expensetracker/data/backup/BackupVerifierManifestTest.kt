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

    @Test
    fun pre_ledger_database_backups_do_not_require_the_new_ledger_count() {
        val required = BackupVerifier.requiredManifestTables(databaseVersion = 149)
        assertFalse(required.contains("savings_contribution_ledger"))
        assertFalse(required.contains("savings_sweep_month_acceptance"))
        BackupVerifier.validateManifestCompleteness(
            required.associateWith { 0 },
            databaseVersion = 149
        )
    }

    @Test
    fun pre_ledger_counts_gain_an_exact_empty_ledger_expectation_after_migration() {
        val legacyCounts = mapOf("expenses" to 2)
        // Schema 152: a pre-152 source also gains the two empty installment tables.
        assertEquals(
            mapOf(
                "expenses" to 2,
                "savings_contribution_ledger" to 0,
                "savings_sweep_month_acceptance" to 1,
                "installment_plans" to 0,
                "installment_entries" to 0
            ),
            BackupVerifier.expectedCountsAfterMigration(legacyCounts, 149)
        )
    }

    @Test
    fun current_counts_are_not_changed_by_post_migration_normalization() {
        val currentCounts = mapOf("savings_contribution_ledger" to 3)
        // 152 is the current version; the version-151 source gains the installment tables
        // (see the dedicated 151 case below).
        assertEquals(
            currentCounts,
            BackupVerifier.expectedCountsAfterMigration(currentCounts, 152)
        )
    }

    @Test
    fun v151_counts_gain_only_the_two_empty_installment_tables_after_migration() {
        val v151Counts = mapOf("savings_contribution_ledger" to 3, "savings_sweep_month_acceptance" to 2)
        assertEquals(
            v151Counts + ("installment_plans" to 0) + ("installment_entries" to 0),
            BackupVerifier.expectedCountsAfterMigration(v151Counts, 151)
        )
    }

    @Test
    fun v150_backup_counts_gain_exact_one_month_marker_and_v151_requires_the_marker_count() {
        val oldCounts = mapOf("savings_contribution_ledger" to 3)
        assertFalse(BackupVerifier.requiredManifestTables(databaseVersion = 150).contains("savings_sweep_month_acceptance"))
        assertEquals(
            oldCounts + ("savings_sweep_month_acceptance" to 1) +
                ("installment_plans" to 0) + ("installment_entries" to 0),
            BackupVerifier.expectedCountsAfterMigration(oldCounts, 150)
        )
        val incomplete = BackupVerifier.requiredManifestTables(databaseVersion = 151)
            .filter { it != "savings_sweep_month_acceptance" }.associateWith { 0 }
        assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
            BackupVerifier.validateManifestCompleteness(incomplete, databaseVersion = 151)
        }
    }

    // ── P7-CURRENT-014: required manifest tables ──────────────────

    @Test
    fun `requiredManifestTables are the Tier 1 exact tables`() {
        val required = BackupVerifier.requiredManifestTables()
        assertTrue("expenses must be required", required.contains("expenses"))
        assertTrue("budgets must be required", required.contains("budgets"))
        assertTrue("savings contribution ledger must be required", required.contains("savings_contribution_ledger"))
        assertTrue(required.contains("savings_sweep_month_acceptance"))
        assertEquals(BackupVerifier.VerificationTier.TIER_1_EXACT,
            BackupVerifier.tableTier("savings_sweep_month_acceptance"))
        assertEquals(
            BackupVerifier.VerificationTier.TIER_1_EXACT,
            BackupVerifier.tableTier("savings_contribution_ledger")
        )
        // Tier 1 exact tables must be required; Tier 3 optional tables must NOT be required.
        assertTrue("privacy_audit_events is now Tier 1 required", required.contains("privacy_audit_events"))
        assertFalse("exchange_rates is optional", required.contains("exchange_rates"))
        assertFalse("background_job_runs is optional", required.contains("background_job_runs"))
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

    // ── P7-P1-05: semantic aggregate verification ──────────────────

    @Test
    fun `semantic_aggregates_match_passes_verification`() {
        val db = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            // Create required tables
            db.execSQL("CREATE TABLE expenses (id INTEGER PRIMARY KEY, effectiveAmount REAL, transactionType TEXT, isNotMine INTEGER)")
            db.execSQL("CREATE TABLE budgets (id INTEGER PRIMARY KEY, name TEXT)")
            db.execSQL("CREATE TABLE receipt_expense_links (id INTEGER PRIMARY KEY, receiptId INTEGER, expenseId INTEGER)")

            // Insert data
            db.execSQL("INSERT INTO expenses (id, effectiveAmount, transactionType, isNotMine) VALUES (1, 100.0, 'EXPENSE', 0)")
            db.execSQL("INSERT INTO expenses (id, effectiveAmount, transactionType, isNotMine) VALUES (2, 50.0, 'INCOME', 0)")
            db.execSQL("INSERT INTO expenses (id, effectiveAmount, transactionType, isNotMine) VALUES (3, 200.0, 'EXPENSE', 1)") // isNotMine, excluded
            db.execSQL("INSERT INTO budgets (id, name) VALUES (1, 'Groceries')")
            db.execSQL("INSERT INTO receipt_expense_links (id, receiptId, expenseId) VALUES (1, 1, 1)")

            // Build expected aggregates matching what the DB produces
            val expected = mapOf(
                "SELECT CAST(SUM(effectiveAmount) AS TEXT) FROM expenses WHERE transactionType = 'EXPENSE' AND isNotMine = 0" to "100.0",
                "SELECT CAST(SUM(effectiveAmount) AS TEXT) FROM expenses WHERE transactionType = 'INCOME' AND isNotMine = 0" to "50.0",
                "SELECT CAST(COUNT(*) AS TEXT) FROM expenses" to "3",
                "SELECT CAST(COUNT(*) AS TEXT) FROM budgets" to "1",
                "SELECT CAST(COUNT(*) AS TEXT) FROM receipt_expense_links" to "1"
            )

            val issues = BackupVerifier.verifySemanticAggregates(db, expected)
            assertTrue("All aggregates should match: $issues", issues.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `semantic_aggregates_mismatch_fails_verification`() {
        val db = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            // Create tables and insert data
            db.execSQL("CREATE TABLE expenses (id INTEGER PRIMARY KEY, effectiveAmount REAL, transactionType TEXT, isNotMine INTEGER)")
            db.execSQL("CREATE TABLE budgets (id INTEGER PRIMARY KEY, name TEXT)")
            db.execSQL("CREATE TABLE receipt_expense_links (id INTEGER PRIMARY KEY, receiptId INTEGER, expenseId INTEGER)")
            db.execSQL("INSERT INTO expenses (id, effectiveAmount, transactionType, isNotMine) VALUES (1, 100.0, 'EXPENSE', 0)")
            db.execSQL("INSERT INTO budgets (id, name) VALUES (1, 'Groceries')")
            db.execSQL("INSERT INTO receipt_expense_links (id, receiptId, expenseId) VALUES (1, 1, 1)")

            // Build expected aggregates where one value intentionally mismatches
            val expected = mapOf(
                "SELECT CAST(SUM(effectiveAmount) AS TEXT) FROM expenses WHERE transactionType = 'EXPENSE' AND isNotMine = 0" to "999.0", // wrong!
                "SELECT CAST(COUNT(*) AS TEXT) FROM expenses" to "42" // intentional mismatch
            )

            val issues = BackupVerifier.verifySemanticAggregates(db, expected)
            assertEquals("Should have 2 mismatches", 2, issues.size)
            assertTrue("First issue should be SEMANTIC_MISMATCH", issues[0].code == "SEMANTIC_MISMATCH")
            assertTrue("Second issue should be SEMANTIC_MISMATCH", issues[1].code == "SEMANTIC_MISMATCH")
            assertTrue("Message should mention expected=999.0", issues[0].message.contains("expected=999.0"))
        } finally {
            db.close()
        }
    }

    // ── P-07-1 / S0b: version-gated coverage of newly classified Tier 1 tables ──

    /** Tier 1 tables first classified by S0b; manifests before schema 152 carry no count for them. */
    private val s0bGatedTables = listOf(
        "source_stats_events",
        "warranty_lifecycle_events",
        "investment_transactions",
        "group_settlements",
        "group_lifecycle_events",
        "entity_source_links",
        "bank_statement_import_runs",
        "bank_statement_import_items",
        "negotiation_outcomes"
    )

    @Test
    fun P071_manifest_at_151_without_the_gated_counts_is_complete() {
        val required151 = BackupVerifier.requiredManifestTables(databaseVersion = 151)
        s0bGatedTables.forEach { table ->
            assertFalse("v151 must not require $table", required151.contains(table))
            assertEquals(BackupVerifier.VerificationTier.TIER_1_EXACT, BackupVerifier.tableTier(table))
        }

        // Must not throw: a legacy manifest cannot carry counts that were never recorded.
        BackupVerifier.validateManifestCompleteness(required151.associateWith { 0 }, databaseVersion = 151)
    }

    @Test
    fun P071_manifest_at_152_and_null_version_require_every_gated_count() {
        val required152 = BackupVerifier.requiredManifestTables(databaseVersion = 152)
        val requiredNull = BackupVerifier.requiredManifestTables(databaseVersion = null)
        val requiredDefault = BackupVerifier.requiredManifestTables()
        s0bGatedTables.forEach { table ->
            assertTrue("v152 must require $table", required152.contains(table))
            assertTrue("unknown version must require $table (fail-closed)", requiredNull.contains(table))
            assertTrue("default must require $table", requiredDefault.contains(table))
        }
    }

    @Test
    fun P071_manifest_at_152_lacking_a_single_gated_count_is_rejected_and_names_it() {
        val complete = BackupVerifier.requiredManifestTables(databaseVersion = 152).associateWith { 0 }
        // Control: complete 152 manifest passes.
        BackupVerifier.validateManifestCompleteness(complete, databaseVersion = 152)

        s0bGatedTables.forEach { table ->
            val thrown = assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
                BackupVerifier.validateManifestCompleteness(complete - table, databaseVersion = 152)
            }
            assertEquals(listOf(table), thrown.missingTables)
            assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
                BackupVerifier.validateManifestCompleteness(complete - table, databaseVersion = null)
            }
        }
    }

    @Test
    fun P071_post_restore_verify_with_legacy_manifest_and_null_gated_counts_passes() {
        val db = createAllTablesDatabase()
        try {
            // Rows exist in gated tables, but a legacy manifest recorded no count for them.
            db.execSQL("INSERT INTO group_settlements (id) VALUES (1)")
            db.execSQL("INSERT INTO entity_source_links (id) VALUES (1)")
            val legacyCounts = BackupVerifier.requiredManifestTables(databaseVersion = 151).associateWith { 0 }
            s0bGatedTables.forEach { assertFalse(legacyCounts.containsKey(it)) }

            val summary = BackupVerifier.verify(db, legacyCounts)

            assertTrue(summary.errors.toString(), summary.passed)
            s0bGatedTables.forEach { table ->
                val result = summary.tableResults.single { it.tableName == table }
                assertTrue("$table must pass on a legacy manifest", result.passed)
                assertTrue("$table must carry no expected count", result.expectedCount == null)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun P071_legacy_leniency_is_limited_to_the_gated_tables() {
        val db = createAllTablesDatabase()
        try {
            val legacyCounts = BackupVerifier.requiredManifestTables(databaseVersion = 151).associateWith { 0 }
            // Control: complete legacy manifest passes.
            assertTrue(BackupVerifier.verify(db, legacyCounts).passed)

            val missingCore = BackupVerifier.verify(db, legacyCounts - "expenses")

            assertFalse(missingCore.passed)
            assertFalse(missingCore.tableResults.single { it.tableName == "expenses" }.passed)
        } finally {
            db.close()
        }
    }

    @Test
    fun P071_manifest_at_152_with_a_wrong_count_for_a_gated_table_fails_verification() {
        s0bGatedTables.forEach { table ->
            val db = createAllTablesDatabase()
            try {
                db.execSQL("INSERT INTO \"$table\" (id) VALUES (1)")
                val exact = BackupVerifier.requiredManifestTables(databaseVersion = 152)
                    .associateWith { 0 } + (table to 1)
                // Control: exact count passes.
                val ok = BackupVerifier.verify(db, exact)
                assertTrue("$table: ${ok.errors}", ok.passed)

                // Too low and too high are both rejected for a 152 manifest.
                listOf(0, 2).forEach { wrong ->
                    val summary = BackupVerifier.verify(db, exact + (table to wrong))
                    assertFalse("$table expected=$wrong must fail", summary.passed)
                    val result = summary.tableResults.single { it.tableName == table }
                    assertFalse(result.passed)
                    assertEquals(wrong, result.expectedCount)
                    assertEquals(1, result.actualCount)
                    assertTrue(summary.errors.any { it.contains("Count mismatch: expected $wrong, actual 1") })
                }
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun P071_verifyQuick_rejects_a_wrong_gated_count_and_accepts_the_exact_one() {
        val file = java.io.File.createTempFile("s0b-quick-", ".db")
        try {
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                createAllTables(db)
                db.execSQL("INSERT INTO investment_transactions (id) VALUES (1)")
            } finally {
                db.close()
            }
            val exact = BackupVerifier.requiredManifestTables(databaseVersion = 152)
                .associateWith { 0 } + ("investment_transactions" to 1)
            BackupVerifier.verifyQuick(file, exact)

            val thrown = assertThrows(BackupVerifier.CountMismatchException::class.java) {
                BackupVerifier.verifyQuick(file, exact + ("investment_transactions" to 0))
            }
            assertEquals("investment_transactions", thrown.tableName)
            assertEquals(0, thrown.expected)
            assertEquals(1, thrown.actual)
        } finally {
            file.delete()
        }
    }

    @Test
    fun P071_strict_count_collection_records_every_gated_table_and_yields_a_complete_152_manifest() {
        val db = createAllTablesDatabase()
        try {
            db.execSQL("INSERT INTO negotiation_outcomes (id) VALUES (1)")

            val counts = BackupVerifier.collectTableCountsStrict(db)

            s0bGatedTables.forEach { assertTrue("$it must be counted", counts.containsKey(it)) }
            assertEquals(1, counts["negotiation_outcomes"])
            assertEquals(BackupVerifier.allTableNames(), counts.keys)
            BackupVerifier.validateManifestCompleteness(counts, databaseVersion = 152)
        } finally {
            db.close()
        }
    }

    @Test
    fun P071_negotiation_outcomes_is_expected_empty_for_sources_below_146() {
        listOf(140, 145).forEach { source ->
            val migrated = BackupVerifier.expectedCountsAfterMigration(mapOf("expenses" to 3), source)
            assertEquals("source $source", 0, migrated["negotiation_outcomes"])
            assertEquals(3, migrated["expenses"])
        }
    }

    @Test
    fun P071_negotiation_outcomes_expectation_is_not_added_at_or_above_146_or_for_unknown_source() {
        listOf(146, 149, 151, 152, 153).forEach { source ->
            assertFalse(
                "source $source",
                BackupVerifier.expectedCountsAfterMigration(mapOf("expenses" to 3), source)
                    .containsKey("negotiation_outcomes")
            )
        }
        assertFalse(
            BackupVerifier.expectedCountsAfterMigration(mapOf("expenses" to 3), null)
                .containsKey("negotiation_outcomes")
        )
    }

    @Test
    fun P071_negotiation_outcomes_recorded_count_is_kept_for_sources_below_146() {
        val migrated = BackupVerifier.expectedCountsAfterMigration(mapOf("negotiation_outcomes" to 4), 145)

        assertEquals(4, migrated["negotiation_outcomes"])
    }

    @Test
    fun P071_pre_146_restore_verifies_negotiation_outcomes_as_exactly_empty() {
        val legacyCounts = BackupVerifier.requiredManifestTables(databaseVersion = 145).associateWith { 0 }
        val expected = BackupVerifier.expectedCountsAfterMigration(legacyCounts, 145)
        val db = createAllTablesDatabase()
        try {
            // A pre-151 restore gains exactly one conservative month marker (see expectedCountsAfterMigration).
            db.execSQL("INSERT INTO savings_sweep_month_acceptance (id) VALUES (1)")
            // The migrated, empty table verifies exactly.
            assertTrue(BackupVerifier.verify(db, expected).passed)

            // A non-empty table where the migration must have created an empty one is rejected.
            db.execSQL("INSERT INTO negotiation_outcomes (id) VALUES (1)")
            val summary = BackupVerifier.verify(db, expected)
            assertFalse(summary.passed)
            assertFalse(summary.tableResults.single { it.tableName == "negotiation_outcomes" }.passed)
        } finally {
            db.close()
        }
    }

    private fun createAllTablesDatabase(): android.database.sqlite.SQLiteDatabase {
        val db = android.database.sqlite.SQLiteDatabase.create(null)
        createAllTables(db)
        return db
    }

    /** One empty table per tracked name, with the reference columns the semantic checks read. */
    private fun createAllTables(db: android.database.sqlite.SQLiteDatabase) {
        for (table in BackupVerifier.allTableNames()) {
            val reference = when (table) {
                "receipt_expense_links" -> ", expenseId INTEGER"
                "recurring_occurrences" -> ", sourceId INTEGER"
                "budget_forecasts" -> ", budgetId INTEGER"
                "installment_entries" -> ", planId INTEGER, linkedExpenseId INTEGER, status TEXT"
                "installment_plans" -> ", purchaseExpenseId INTEGER"
                "expenses" -> ", spendingExclusionReason TEXT"
                else -> ""
            }
            db.execSQL("CREATE TABLE \"$table\" (id INTEGER PRIMARY KEY$reference)")
        }
    }
}
