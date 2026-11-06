package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.domain.backup.SemanticNotVerifiableReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

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
        // Schema 153: a pre-153 source also gains the empty refund link table (INV-20).
        assertEquals(
            mapOf(
                "expenses" to 2,
                "savings_contribution_ledger" to 0,
                "savings_sweep_month_acceptance" to 1,
                "installment_plans" to 0,
                "installment_entries" to 0,
                "expense_refund_links" to 0
            ),
            BackupVerifier.expectedCountsAfterMigration(legacyCounts, 149)
        )
    }

    @Test
    fun current_counts_are_not_changed_by_post_migration_normalization() {
        val currentCounts = mapOf("savings_contribution_ledger" to 3, "expense_refund_links" to 2)
        // 153 is the current version; the version-152 and version-151 sources gain empty tables
        // (see the dedicated cases below).
        assertEquals(
            currentCounts,
            BackupVerifier.expectedCountsAfterMigration(currentCounts, 153)
        )
    }

    @Test
    fun v152_counts_gain_only_the_empty_refund_link_table_after_migration() {
        val v152Counts = mapOf("savings_contribution_ledger" to 3, "installment_plans" to 1)
        assertEquals(
            v152Counts + ("expense_refund_links" to 0),
            BackupVerifier.expectedCountsAfterMigration(v152Counts, 152)
        )
    }

    @Test
    fun v151_counts_gain_only_the_two_empty_installment_tables_and_the_empty_refund_link_table_after_migration() {
        val v151Counts = mapOf("savings_contribution_ledger" to 3, "savings_sweep_month_acceptance" to 2)
        assertEquals(
            v151Counts + ("installment_plans" to 0) + ("installment_entries" to 0) + ("expense_refund_links" to 0),
            BackupVerifier.expectedCountsAfterMigration(v151Counts, 151)
        )
    }

    // ── INV-20: expense_refund_links manifest tier and gating (schema 153) ──

    @Test
    fun INV20_expense_refund_links_is_tier_1_exact_and_tracked() {
        assertEquals(
            BackupVerifier.VerificationTier.TIER_1_EXACT,
            BackupVerifier.tableTier("expense_refund_links")
        )
        assertTrue(BackupVerifier.allTableNames().contains("expense_refund_links"))
    }

    @Test
    fun INV20_refund_links_count_is_required_from_153_and_for_unknown_version_but_not_below() {
        assertTrue(BackupVerifier.requiredManifestTables(databaseVersion = 153).contains("expense_refund_links"))
        assertTrue(BackupVerifier.requiredManifestTables(databaseVersion = null).contains("expense_refund_links"))
        assertTrue(BackupVerifier.requiredManifestTables().contains("expense_refund_links"))
        listOf(145, 149, 150, 151, 152).forEach { version ->
            assertFalse(
                "v$version must not require expense_refund_links",
                BackupVerifier.requiredManifestTables(databaseVersion = version).contains("expense_refund_links")
            )
        }
        // 153 adds exactly the link table over 152 (no other table is newly required).
        assertEquals(
            setOf("expense_refund_links"),
            BackupVerifier.requiredManifestTables(databaseVersion = 153) -
                BackupVerifier.requiredManifestTables(databaseVersion = 152)
        )
    }

    @Test
    fun INV20_manifest_at_153_without_the_refund_link_count_is_rejected_and_a_152_manifest_is_accepted() {
        val complete153 = BackupVerifier.requiredManifestTables(databaseVersion = 153).associateWith { 0 }
        // Control: complete 153 manifest passes.
        BackupVerifier.validateManifestCompleteness(complete153, databaseVersion = 153)

        val thrown = assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
            BackupVerifier.validateManifestCompleteness(complete153 - "expense_refund_links", databaseVersion = 153)
        }
        assertEquals(listOf("expense_refund_links"), thrown.missingTables)
        assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
            BackupVerifier.validateManifestCompleteness(complete153 - "expense_refund_links", databaseVersion = null)
        }
        // A pre-153 manifest legitimately has no link count.
        BackupVerifier.validateManifestCompleteness(
            BackupVerifier.requiredManifestTables(databaseVersion = 152).associateWith { 0 },
            databaseVersion = 152
        )
    }

    @Test
    fun INV20_sources_below_153_expect_an_empty_link_table_and_153_or_unknown_do_not_add_it() {
        listOf(140, 145, 149, 150, 151, 152).forEach { source ->
            assertEquals(
                "source $source",
                0,
                BackupVerifier.expectedCountsAfterMigration(mapOf("expenses" to 3), source)["expense_refund_links"]
            )
        }
        listOf(153, null).forEach { source ->
            assertFalse(
                "source $source",
                BackupVerifier.expectedCountsAfterMigration(mapOf("expenses" to 3), source)
                    .containsKey("expense_refund_links")
            )
        }
        // A recorded count is kept, never overwritten with 0.
        assertEquals(
            4,
            BackupVerifier.expectedCountsAfterMigration(mapOf("expense_refund_links" to 4), 152)["expense_refund_links"]
        )
    }

    @Test
    fun INV20_link_table_row_count_is_verified_exactly_both_ways() {
        val db = createAllTablesDatabase()
        try {
            db.execSQL(
                "INSERT INTO expenses (id, refundNettedAmount) VALUES (1, 40.0), (2, 40.0)"
            )
            db.execSQL(
                "INSERT INTO expense_refund_links (id, refundExpenseId, purchaseExpenseId, appliedAmount) " +
                    "VALUES (1, 2, 1, 40.0)"
            )
            val exact = BackupVerifier.requiredManifestTables(databaseVersion = 153)
                .associateWith { 0 } + ("expenses" to 2) + ("expense_refund_links" to 1)
            // Control: exact counts pass.
            val ok = BackupVerifier.verify(db, exact)
            assertTrue(ok.errors.toString(), ok.passed)

            listOf(0, 2).forEach { wrong ->
                val summary = BackupVerifier.verify(db, exact + ("expense_refund_links" to wrong))
                assertFalse("expected=$wrong must fail", summary.passed)
                val result = summary.tableResults.single { it.tableName == "expense_refund_links" }
                assertFalse(result.passed)
                assertEquals(1, result.actualCount)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun v150_backup_counts_gain_exact_one_month_marker_and_v151_requires_the_marker_count() {
        val oldCounts = mapOf("savings_contribution_ledger" to 3)
        assertFalse(BackupVerifier.requiredManifestTables(databaseVersion = 150).contains("savings_sweep_month_acceptance"))
        assertEquals(
            oldCounts + ("savings_sweep_month_acceptance" to 1) +
                ("installment_plans" to 0) + ("installment_entries" to 0) + ("expense_refund_links" to 0),
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

    // ── UF-46 / S1: typed semantic-aggregate pass ──────────────────
    //
    // This section REPLACES the two tests of the deleted SQL-keyed API (owner decision O-2, INV-1):
    //  - `semantic_aggregates_match_passes_verification`    -> the "fixture F" match tests below
    //  - `semantic_aggregates_mismatch_fails_verification`  -> the mismatch / flag-flip / hostile tests
    // The expectation is a typed SemanticExpectation built from hand-computed values for the synthetic
    // fixture F of the spec (section 7); nothing here executes a manifest-supplied string.

    private val forbiddenInBoundedText = listOf("12.5", "1000", "EUR", "USD", "PURCHASE", "SELECT", "DROP", "DELETE")

    private fun fixtureBucket(n: Int, s: BigDecimal, vararg key: String, u: Int = 0) =
        AggregateBucket(key.toList(), n, u, s)

    private fun fixtureEntry(id: SemanticAggregateId, vararg buckets: AggregateBucket) =
        AggregateEntry(SemanticAggregates.definitionHash(id), buckets.toList())

    /**
     * Hand-computed aggregates of fixture F (E1..E6, R1, R2); with [withFlaggedPurchase] also E7, a PURCHASE
     * EUR 100.0 row carrying a spending-exclusion reason (counts in n, adds 0 to the spending sum).
     */
    private fun fixtureFExpectedSet(withFlaggedPurchase: Boolean = false): SemanticAggregateSet {
        val purchaseEurRows = if (withFlaggedPurchase) 3 else 2
        val purchaseEurSum = if (withFlaggedPurchase) BigDecimal("152.5") else BigDecimal("52.5")
        val spendingEurRows = if (withFlaggedPurchase) 3 else 2
        return SemanticAggregateSet.ofCaptured(
            mapOf(
                SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY to fixtureEntry(
                    SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY,
                    fixtureBucket(1, BigDecimal("1000"), "DEPOSIT", "EUR"),
                    fixtureBucket(purchaseEurRows, purchaseEurSum, "PURCHASE", "EUR"),
                    fixtureBucket(1, BigDecimal(9.99), "PURCHASE", "USD"),
                    fixtureBucket(1, BigDecimal("200"), "TRANSFER", "EUR"),
                    fixtureBucket(1, BigDecimal("5"), "UNKNOWN", "GBP")
                ),
                SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY to fixtureEntry(
                    SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY,
                    fixtureBucket(spendingEurRows, BigDecimal("32.5"), "EUR"),
                    fixtureBucket(1, BigDecimal.ZERO, "USD")
                ),
                SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY to fixtureEntry(
                    SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
                    fixtureBucket(1, BigDecimal("1000"), "EUR")
                ),
                SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY to fixtureEntry(
                    SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY,
                    fixtureBucket(1, BigDecimal("15"), "0", "USD"),
                    fixtureBucket(1, BigDecimal("9"), "1", "EUR")
                )
            )
        )
    }

    private fun compareExpectation(
        set: SemanticAggregateSet,
        checkpoint: SemanticCheckpoint = SemanticCheckpoint.C1_STAGED_SOURCE
    ) = SemanticExpectation(checkpoint, SemanticBasis.Compare(set))

    /** Replaces the two fingerprinted tables of an all-tables database with the columns the registry reads. */
    private fun applyFixtureF(db: android.database.sqlite.SQLiteDatabase, withFlaggedPurchase: Boolean) {
        db.execSQL("DROP TABLE expenses")
        db.execSQL(
            "CREATE TABLE expenses (id INTEGER PRIMARY KEY, transactionType TEXT NOT NULL, currency TEXT NOT NULL, " +
                "amount REAL, isNotMine INTEGER NOT NULL DEFAULT 0, isSharedExpense INTEGER NOT NULL DEFAULT 0, " +
                "myShareAmount REAL, mySharePercentage REAL, spendingExclusionReason TEXT DEFAULT NULL, " +
                "refundNettedAmount REAL NOT NULL DEFAULT 0.0)"
        )
        db.execSQL("DROP TABLE manual_recurring_expenses")
        db.execSQL(
            "CREATE TABLE manual_recurring_expenses (id INTEGER PRIMARY KEY, isActive INTEGER NOT NULL, " +
                "currency TEXT NOT NULL, amount REAL NOT NULL)"
        )
        db.execSQL("INSERT INTO expenses (id, transactionType, currency, amount) VALUES (1, 'PURCHASE', 'EUR', 12.5)")
        db.execSQL(
            "INSERT INTO expenses (id, transactionType, currency, amount, isSharedExpense, mySharePercentage) " +
                "VALUES (2, 'PURCHASE', 'EUR', 40.0, 1, 50)"
        )
        db.execSQL("INSERT INTO expenses (id, transactionType, currency, amount) VALUES (3, 'DEPOSIT', 'EUR', 1000.0)")
        db.execSQL(
            "INSERT INTO expenses (id, transactionType, currency, amount, isNotMine) " +
                "VALUES (4, 'PURCHASE', 'USD', 9.99, 1)"
        )
        db.execSQL("INSERT INTO expenses (id, transactionType, currency, amount) VALUES (5, 'TRANSFER', 'EUR', 200.0)")
        db.execSQL("INSERT INTO expenses (id, transactionType, currency, amount) VALUES (6, 'UNKNOWN', 'GBP', 5.0)")
        if (withFlaggedPurchase) {
            db.execSQL(
                "INSERT INTO expenses (id, transactionType, currency, amount, spendingExclusionReason) " +
                    // Any non-null reason excludes the row from spending. The two orphan-checked reason texts
                    // are avoided on purpose so that only the semantic aggregate is under test.
                    "VALUES (7, 'PURCHASE', 'EUR', 100.0, 'FIXTURE_EXCLUSION')"
            )
        }
        db.execSQL("INSERT INTO manual_recurring_expenses (id, isActive, currency, amount) VALUES (1, 1, 'EUR', 9.0)")
        db.execSQL("INSERT INTO manual_recurring_expenses (id, isActive, currency, amount) VALUES (2, 0, 'USD', 15.0)")
    }

    private fun createFixtureFDatabase(withFlaggedPurchase: Boolean = false): android.database.sqlite.SQLiteDatabase {
        val db = createAllTablesDatabase()
        applyFixtureF(db, withFlaggedPurchase)
        return db
    }

    private inline fun <T> withFixtureF(withFlaggedPurchase: Boolean = false, block: (android.database.sqlite.SQLiteDatabase) -> T): T {
        val db = createFixtureFDatabase(withFlaggedPurchase)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun expenseCount(db: android.database.sqlite.SQLiteDatabase): Long =
        android.database.DatabaseUtils.queryNumEntries(db, "expenses")

    @Test
    fun semantic_expectation_fixture_F_columns_exist_in_the_exported_152_schema() {
        val fixtureColumns = mapOf(
            "expenses" to listOf(
                "transactionType", "currency", "amount", "isNotMine", "isSharedExpense",
                "myShareAmount", "mySharePercentage", "spendingExclusionReason"
            ),
            "manual_recurring_expenses" to listOf("isActive", "currency", "amount")
        )
        val file = listOf(
            java.io.File("app/schemas/com.yourname.expensetracker.data.database.AppDatabase/152.json"),
            java.io.File("schemas/com.yourname.expensetracker.data.database.AppDatabase/152.json"),
            java.io.File(System.getProperty("user.dir") ?: ".", "app/schemas/com.yourname.expensetracker.data.database.AppDatabase/152.json"),
            java.io.File(System.getProperty("user.dir") ?: ".", "schemas/com.yourname.expensetracker.data.database.AppDatabase/152.json")
        ).firstOrNull { it.isFile }
        assertNotNull("Could not locate exported Room schema 152.json", file)
        val entities = org.json.JSONObject(file!!.readText(Charsets.UTF_8))
            .getJSONObject("database").getJSONArray("entities")
        val columnsByTable = (0 until entities.length()).associate { index ->
            val entity = entities.getJSONObject(index)
            val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }.toSet()
        }
        fixtureColumns.forEach { (table, columns) ->
            val real = columnsByTable[table]
            assertNotNull("$table must exist in the exported schema", real)
            columns.forEach { column -> assertTrue("$table.$column must exist in schema 152", column in real!!) }
        }
    }

    @Test
    fun semantic_expectation_matching_fixture_F_verifies_and_passes_verify() {
        withFixtureF { db ->
            val counts = BackupVerifier.collectTableCountsStrict(db)
            assertEquals(6, counts["expenses"])
            assertEquals(2, counts["manual_recurring_expenses"])

            val summary = BackupVerifier.verify(db, counts, compareExpectation(fixtureFExpectedSet()))

            assertTrue(summary.errors.toString(), summary.passed)
            val result = summary.semanticResult
            assertTrue("expected Verified but was $result", result is SemanticCheckResult.Verified)
            assertEquals(SemanticCheckpoint.C1_STAGED_SOURCE, result!!.checkpoint)
        }
    }

    @Test
    fun semantic_pass_does_not_run_without_an_expectation() {
        withFixtureF { db ->
            val counts = BackupVerifier.collectTableCountsStrict(db)

            val summary = BackupVerifier.verify(db, counts)

            assertTrue(summary.errors.toString(), summary.passed)
            assertNull(summary.semanticResult)
        }
    }

    @Test
    fun semantic_expectation_with_a_changed_amount_fails_verify_with_bounded_issue_text() {
        withFixtureF { db ->
            val counts = BackupVerifier.collectTableCountsStrict(db)
            // Control: unchanged data verifies against the same expectation.
            assertTrue(BackupVerifier.verify(db, counts, compareExpectation(fixtureFExpectedSet())).passed)

            db.execSQL("UPDATE expenses SET amount = 12.500000001 WHERE id = 1")
            val summary = BackupVerifier.verify(db, counts, compareExpectation(fixtureFExpectedSet()))

            assertFalse(summary.passed)
            val failed = summary.semanticResult as SemanticCheckResult.Failed
            assertEquals(SemanticCode.SEMANTIC_MISMATCH, failed.code)
            assertEquals(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY, failed.aggregateId)
            assertEquals(SemanticCheckpoint.C1_STAGED_SOURCE, failed.checkpoint)
            assertEquals(1, failed.mismatchedBuckets)
            val issue = summary.errors.single()
            assertTrue(issue, issue.startsWith("SEMANTIC_MISMATCH"))
            assertTrue(issue, issue.contains("EXPENSE_RAW_BY_TYPE_CURRENCY"))
            assertTrue(issue, issue.contains("C1_STAGED_SOURCE"))
            forbiddenInBoundedText.forEach { assertFalse("issue text must not contain $it: $issue", issue.contains(it)) }
        }
    }

    @Test
    fun flipping_the_isNotMine_flag_is_detected_as_a_spending_mismatch() {
        withFixtureF { db ->
            val counts = BackupVerifier.collectTableCountsStrict(db)
            db.execSQL("UPDATE expenses SET isNotMine = 1 WHERE id = 1")

            val summary = BackupVerifier.verify(db, counts, compareExpectation(fixtureFExpectedSet()))

            assertFalse(summary.passed)
            val failed = summary.semanticResult as SemanticCheckResult.Failed
            assertEquals(SemanticCode.SEMANTIC_MISMATCH, failed.code)
            // The raw aggregate is unaffected by the flag, so the first divergence is the spending one.
            assertEquals(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY, failed.aggregateId)
            assertEquals(1, failed.mismatchedBuckets)
        }
    }

    @Test
    fun flagged_purchase_row_counts_in_n_without_changing_s_and_dropping_the_flag_changes_s() {
        withFixtureF(withFlaggedPurchase = true) { db ->
            val counts = BackupVerifier.collectTableCountsStrict(db)
            assertEquals(7, counts["expenses"])
            val expectation = compareExpectation(fixtureFExpectedSet(withFlaggedPurchase = true))

            // Control: the flagged row adds to n (3) and contributes 0 to the EUR spending sum (32.5).
            val control = BackupVerifier.verify(db, counts, expectation)
            assertTrue(control.errors.toString(), control.passed)
            assertTrue(control.semanticResult is SemanticCheckResult.Verified)

            // Boundary: losing the exclusion flag moves 100.0 into the spending sum.
            db.execSQL("UPDATE expenses SET spendingExclusionReason = NULL WHERE id = 7")
            val summary = BackupVerifier.verify(db, counts, expectation)

            assertFalse(summary.passed)
            val failed = summary.semanticResult as SemanticCheckResult.Failed
            assertEquals(SemanticCode.SEMANTIC_MISMATCH, failed.code)
            assertEquals(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY, failed.aggregateId)
            assertEquals(1, failed.mismatchedBuckets)
        }
    }

    @Test
    fun hostile_manifest_aggregate_ids_are_malformed_and_fail_closed_without_executing_anything() {
        withFixtureF { db ->
            val counts = BackupVerifier.collectTableCountsStrict(db)
            val rowsBefore = expenseCount(db)
            val hostile = SemanticAggregates.registry.fold(org.json.JSONObject()) { acc, definition ->
                acc.put(definition.id.name, org.json.JSONObject().put("definitionHash", "h").put("buckets", org.json.JSONArray()))
            }.put("SELECT 1", org.json.JSONObject()).put("DROP TABLE x", org.json.JSONObject())
            val section = org.json.JSONObject()
                .put("version", SemanticAggregates.SECTION_VERSION)
                .put("status", "CAPTURED")
                .put("aggregates", hostile)

            val parsed = SemanticAggregates.parse(section)
            assertTrue(parsed is SemanticParseResult.Malformed)
            val basis = BackupVerifier.expectedAggregatesForSection(parsed, 152)
            assertTrue(basis is SemanticBasis.Malformed)
            val summary = BackupVerifier.verify(
                db, counts, SemanticExpectation(SemanticCheckpoint.C1_STAGED_SOURCE, basis)
            )

            assertFalse(summary.passed)
            val failed = summary.semanticResult as SemanticCheckResult.Failed
            assertEquals(SemanticCode.SEMANTIC_MANIFEST_MALFORMED, failed.code)
            assertNull(failed.aggregateId)
            assertTrue(summary.errors.single().startsWith("SEMANTIC_MANIFEST_MALFORMED"))
            forbiddenInBoundedText.forEach { word ->
                summary.errors.forEach { assertFalse("issue text must not contain $word: $it", it.contains(word)) }
            }
            assertEquals(rowsBefore, expenseCount(db))
        }
    }

    @Test
    fun hostile_text_inside_a_well_formed_section_is_inert_data_that_never_reaches_sql() {
        withFixtureF { db ->
            val counts = BackupVerifier.collectTableCountsStrict(db)
            val rowsBefore = expenseCount(db)
            val section = fixtureFExpectedSet().toJson()
            val aggregates = section.getJSONObject("aggregates")
            aggregates.getJSONObject("EXPENSE_RAW_BY_TYPE_CURRENCY")
                .put("definitionHash", "x'); DELETE FROM expenses; --")
            aggregates.getJSONObject("INCOME_EFFECTIVE_BY_CURRENCY")
                .getJSONArray("buckets").getJSONObject(0)
                .put("key", org.json.JSONArray().put("'; DROP TABLE expenses; --"))

            val parsed = SemanticAggregates.parse(section)
            assertTrue("hostile text is data, not structure", parsed is SemanticParseResult.Parsed)
            val basis = BackupVerifier.expectedAggregatesForSection(parsed, 152)
            val summary = BackupVerifier.verify(db, counts, SemanticExpectation(SemanticCheckpoint.C2_STAGED_POST_MIGRATION, basis))

            assertFalse(summary.passed)
            val failed = summary.semanticResult as SemanticCheckResult.Failed
            assertEquals(SemanticCode.SEMANTIC_MISMATCH, failed.code)
            // The foreign hash only makes EXPENSE_RAW not comparable; the hostile INCOME key is a plain
            // bucket that exists on one side only (expected side hostile, actual side EUR).
            assertEquals(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, failed.aggregateId)
            assertEquals(2, failed.mismatchedBuckets)
            summary.errors.forEach { issue ->
                forbiddenInBoundedText.forEach { assertFalse("issue text must not contain $it: $issue", issue.contains(it)) }
            }
            assertEquals(rowsBefore, expenseCount(db))
        }
    }

    @Test
    fun not_verifiable_expectations_pass_verify_and_never_report_verified() {
        SemanticNotVerifiableReason.values().forEach { reason ->
            withFixtureF { db ->
                val counts = BackupVerifier.collectTableCountsStrict(db)

                val summary = BackupVerifier.verify(
                    db,
                    counts,
                    SemanticExpectation(SemanticCheckpoint.C3_LIVE_POST_SWAP, SemanticBasis.NotVerifiable(reason))
                )

                assertTrue("$reason: ${summary.errors}", summary.passed)
                val result = summary.semanticResult as SemanticCheckResult.NotVerifiable
                assertEquals(reason, result.reason)
                assertEquals(SemanticCheckpoint.C3_LIVE_POST_SWAP, result.checkpoint)
            }
        }
    }

    @Test
    fun semantic_pass_is_skipped_when_an_earlier_check_already_failed() {
        withFixtureF { db ->
            val counts = BackupVerifier.collectTableCountsStrict(db)

            val summary = BackupVerifier.verify(
                db, counts + ("expenses" to 99), compareExpectation(fixtureFExpectedSet())
            )

            assertFalse(summary.passed)
            assertNull("the semantic pass must not run behind a failed count check", summary.semanticResult)
        }
    }

    @Test
    fun verifyQuick_and_file_verify_run_the_semantic_pass_read_only() {
        val file = java.io.File.createTempFile("s1-semantic-", ".db")
        try {
            val seed = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
            val counts = try {
                createAllTables(seed)
                applyFixtureF(seed, withFlaggedPurchase = false)
                BackupVerifier.collectTableCountsStrict(seed)
            } finally {
                seed.close()
            }
            val bytesBefore = file.readBytes()

            // Control: no expectation -> no semantic pass.
            assertNull(BackupVerifier.verifyQuick(file, counts))
            // Match -> Verified, with the checkpoint passed in.
            val verified = BackupVerifier.verifyQuick(file, counts, compareExpectation(fixtureFExpectedSet()))
            assertTrue(verified is SemanticCheckResult.Verified)
            assertEquals(SemanticCheckpoint.C1_STAGED_SOURCE, verified!!.checkpoint)
            val full = BackupVerifier.verify(file, counts, compareExpectation(fixtureFExpectedSet()))
            assertTrue(full.errors.toString(), full.passed)
            assertTrue(full.semanticResult is SemanticCheckResult.Verified)
            // NOT_VERIFIABLE is returned, not thrown.
            val notVerifiable = BackupVerifier.verifyQuick(
                file, counts,
                SemanticExpectation(
                    SemanticCheckpoint.C2_STAGED_POST_MIGRATION,
                    SemanticBasis.NotVerifiable(SemanticNotVerifiableReason.LEGACY)
                )
            )
            assertEquals(SemanticNotVerifiableReason.LEGACY, (notVerifiable as SemanticCheckResult.NotVerifiable).reason)

            // Mismatch -> throws a bounded exception (message is the code only).
            val mismatch = assertThrows(BackupVerifier.SemanticVerificationFailedException::class.java) {
                BackupVerifier.verifyQuick(file, counts, compareExpectation(fixtureFExpectedSet(withFlaggedPurchase = true)))
            }
            assertEquals("SEMANTIC_MISMATCH", mismatch.message)
            assertEquals(SemanticCode.SEMANTIC_MISMATCH, mismatch.result.code)
            // Malformed expectation -> fails closed with the malformed code.
            val malformed = assertThrows(BackupVerifier.SemanticVerificationFailedException::class.java) {
                BackupVerifier.verifyQuick(
                    file, counts,
                    SemanticExpectation(SemanticCheckpoint.C1_STAGED_SOURCE, SemanticBasis.Malformed())
                )
            }
            assertEquals("SEMANTIC_MANIFEST_MALFORMED", malformed.message)

            // INV-16: every pass above left the database file byte-identical.
            assertArrayEquals(bytesBefore, file.readBytes())
        } finally {
            file.delete()
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

    // ── INV-11 / m1: the gated-table requirement depends on the semantic marker ──

    @Test
    fun INV11_m1_v152_without_marker_does_not_require_the_gated_counts() {
        val required = BackupVerifier.requiredManifestTables(databaseVersion = 152, semanticMarker = false)

        s0bGatedTables.forEach { table ->
            assertFalse("v152 without marker must not require $table", required.contains(table))
        }
        // Everything else a 152 manifest requires is unchanged by the marker.
        assertTrue(required.contains("expenses"))
        assertTrue(required.contains("installment_plans"))
        assertTrue(required.contains("installment_entries"))
        assertEquals(
            BackupVerifier.requiredManifestTables(databaseVersion = 152, semanticMarker = true) - s0bGatedTables.toSet(),
            required
        )
        // Control: counts for exactly the required set (no gated counts) are complete without the marker.
        BackupVerifier.validateManifestCompleteness(
            required.associateWith { 0 }, databaseVersion = 152, semanticMarker = false
        )
    }

    @Test
    fun INV11_m1_v152_with_marker_requires_all_nine_gated_counts() {
        val required = BackupVerifier.requiredManifestTables(databaseVersion = 152, semanticMarker = true)

        assertEquals(9, s0bGatedTables.size)
        s0bGatedTables.forEach { table ->
            assertTrue("v152 with marker must require $table", required.contains(table))
        }
        val complete = required.associateWith { 0 }
        BackupVerifier.validateManifestCompleteness(complete, databaseVersion = 152, semanticMarker = true)
        // Boundary: dropping any single gated count is rejected and named when the marker is set.
        s0bGatedTables.forEach { table ->
            val thrown = assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
                BackupVerifier.validateManifestCompleteness(complete - table, databaseVersion = 152, semanticMarker = true)
            }
            assertEquals(listOf(table), thrown.missingTables)
        }
    }

    @Test
    fun INV11_m1_v151_and_below_never_require_the_gated_counts_whatever_the_marker() {
        listOf(145, 149, 150, 151).forEach { version ->
            listOf(true, false).forEach { marker ->
                val required = BackupVerifier.requiredManifestTables(databaseVersion = version, semanticMarker = marker)
                s0bGatedTables.forEach { table ->
                    assertFalse("v$version marker=$marker must not require $table", required.contains(table))
                }
                BackupVerifier.validateManifestCompleteness(
                    required.associateWith { 0 }, databaseVersion = version, semanticMarker = marker
                )
            }
        }
    }

    @Test
    fun INV11_m1_unknown_database_version_requires_the_gated_counts_whatever_the_marker() {
        listOf(true, false).forEach { marker ->
            val required = BackupVerifier.requiredManifestTables(databaseVersion = null, semanticMarker = marker)
            s0bGatedTables.forEach { table ->
                assertTrue("null version marker=$marker must require $table", required.contains(table))
            }
            val complete = required.associateWith { 0 }
            BackupVerifier.validateManifestCompleteness(complete, databaseVersion = null, semanticMarker = marker)
            assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
                BackupVerifier.validateManifestCompleteness(
                    complete - "negotiation_outcomes", databaseVersion = null, semanticMarker = marker
                )
            }
        }
    }

    @Test
    fun INV11_m1_default_marker_parameter_is_fail_closed_like_marker_true() {
        assertEquals(
            BackupVerifier.requiredManifestTables(databaseVersion = 152, semanticMarker = true),
            BackupVerifier.requiredManifestTables(databaseVersion = 152)
        )
        val withoutGated = BackupVerifier.requiredManifestTables(databaseVersion = 152, semanticMarker = false)
            .associateWith { 0 }
        // Control: marker=false accepts this map, the default (marker=true) rejects it.
        BackupVerifier.validateManifestCompleteness(withoutGated, databaseVersion = 152, semanticMarker = false)
        val thrown = assertThrows(BackupVerifier.IncompleteManifestException::class.java) {
            BackupVerifier.validateManifestCompleteness(withoutGated, databaseVersion = 152)
        }
        assertEquals(s0bGatedTables.sorted(), thrown.missingTables.sorted())
    }

    @Test
    fun P071_post_restore_verify_with_legacy_manifest_and_null_gated_counts_passes() {
        val db = createAllTablesDatabase()
        try {
            // Rows exist in gated tables, but a legacy manifest recorded no count for them.
            db.execSQL("INSERT INTO group_settlements (id) VALUES (1)")
            db.execSQL("INSERT INTO entity_source_links (id) VALUES (1)")
            val legacyManifestCounts = BackupVerifier.requiredManifestTables(databaseVersion = 151).associateWith { 0 }
            s0bGatedTables.forEach { assertFalse(legacyManifestCounts.containsKey(it)) }
            // Real restore flow: the staged DB is migrated from the source version, so the
            // expectations are extended with tables the migration creates empty (installment_*).
            val legacyCounts = BackupVerifier.expectedCountsAfterMigration(legacyManifestCounts, 151)
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
            val legacyManifestCounts = BackupVerifier.requiredManifestTables(databaseVersion = 151).associateWith { 0 }
            // Real restore flow: counts pass through the migration step before post-restore verify.
            val legacyCounts = BackupVerifier.expectedCountsAfterMigration(legacyManifestCounts, 151)
            // Control: complete legacy manifest (gated counts null) passes.
            val control = BackupVerifier.verify(db, legacyCounts)
            assertTrue(control.errors.toString(), control.passed)

            val missingCore = BackupVerifier.verify(
                db,
                BackupVerifier.expectedCountsAfterMigration(legacyManifestCounts - "expenses", 151)
            )

            assertFalse(missingCore.passed)
            assertTrue(missingCore.errors.contains("Table 'expenses' not found in manifest counts"))
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
                "expenses" -> ", spendingExclusionReason TEXT, refundNettedAmount REAL NOT NULL DEFAULT 0.0"
                // Refund netting (INV-20): columns read by the two refund semantic checks.
                "expense_refund_links" -> ", refundExpenseId INTEGER, purchaseExpenseId INTEGER, appliedAmount REAL"
                else -> ""
            }
            db.execSQL("CREATE TABLE \"$table\" (id INTEGER PRIMARY KEY$reference)")
        }
    }
}
