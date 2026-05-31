package com.yourname.expensetracker.data.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import timber.log.Timber

/**
 * Verifies database integrity and table row counts for restore operations.
 *
 * Replaces the old 5-table verification with full 57-entity verification.
 *
 * ## Tier definitions
 *
 * - **Tier 1 (EXACT):** User/business data tables. Row count MUST match exactly.
 * - **Tier 2 (VALIDITY):** Derived, cached, or event-log tables. Row count may
 *   differ but must pass integrity and FK checks.
 * - **Tier 3 (OPTIONAL):** Cache/external data tables. May be absent entirely.
 *
 * ## Future verification
 * P7-P1-05: Full semantic equivalence checks are required to prove that a
 * restored database produces identical dashboard totals, analytics aggregates,
 * budget projections, and receipt-to-expense link integrity as the original.
 * Current verification covers: table existence, row counts (Tier 1),
 * PRAGMA integrity_check, and PRAGMA foreign_key_check.
 *
 * TODO (P7-P1-05): Implement post-restore semantic equivalence verification:
 *   1. Compare SUM(amount) per category between manifest snapshot and restored DB
 *   2. Verify budget spent/remaining calculations match pre-backup values
 *   3. Validate receipt_expense_links referential integrity (all linked receipts exist)
 *   4. Check analytics aggregates (monthly totals, category breakdowns) match
 *   5. Verify investment portfolio total value matches manifest checkpoint
 *   These checks should run as a Tier 0 (SEMANTIC) verification pass after
 *   Tier 1 row counts pass, and failures should trigger rollback.
 */
object BackupVerifier {

    // ── Verification tier assignment for all 57 entities ──────────

    /**
     * All 57 entity table names with their verification tier.
     */
    private val TABLE_TIERS: Map<String, VerificationTier> = mapOf(
        // ── Tier 1: Exact row count required (37 total) ──
        // 30 listed here + 7 event/link tables grouped under the Tier 2 comment
        // below (transaction_events, receipt_events, receipt_expense_links,
        // recurring_occurrences, recurring_reminder_deliveries,
        // recurring_lifecycle_events, warranty_reminder_deliveries) which are also
        // TIER_1_EXACT. requiredManifestTables() filters by value, so the physical
        // grouping is cosmetic; the tier assignment is authoritative.
        "raw_notifications"              to VerificationTier.TIER_1_EXACT,
        "expenses"                       to VerificationTier.TIER_1_EXACT,
        "categories"                     to VerificationTier.TIER_1_EXACT,
        "merchant_categories"            to VerificationTier.TIER_1_EXACT,
        "pending_reviews"                to VerificationTier.TIER_1_EXACT,
        "user_corrections"               to VerificationTier.TIER_1_EXACT,
        "source_stats"                   to VerificationTier.TIER_1_EXACT,
        "budgets"                        to VerificationTier.TIER_1_EXACT,
        "scanned_receipts"               to VerificationTier.TIER_1_EXACT,
        "manual_recurring_expenses"      to VerificationTier.TIER_1_EXACT,
        "planned_expenses"               to VerificationTier.TIER_1_EXACT,
        "savings_goals"                  to VerificationTier.TIER_1_EXACT,
        "warranties"                     to VerificationTier.TIER_1_EXACT,
        "return_windows"                 to VerificationTier.TIER_1_EXACT,
        "mileage_tracking"               to VerificationTier.TIER_1_EXACT,
        "expense_groups"                 to VerificationTier.TIER_1_EXACT,
        "group_members"                  to VerificationTier.TIER_1_EXACT,
        "group_expenses"                 to VerificationTier.TIER_1_EXACT,
        "investments"                    to VerificationTier.TIER_1_EXACT,
        "investment_values"              to VerificationTier.TIER_1_EXACT,
        "bank_connections"               to VerificationTier.TIER_1_EXACT,
        "split_templates"                to VerificationTier.TIER_1_EXACT,
        "split_item_assignments"         to VerificationTier.TIER_1_EXACT,
        "subscription_candidates"        to VerificationTier.TIER_1_EXACT,
        "subscription_price_history"     to VerificationTier.TIER_1_EXACT,
        "subscription_usage"             to VerificationTier.TIER_1_EXACT,
        "budget_forecasts"               to VerificationTier.TIER_1_EXACT,
        "budget_adjustment_recommendations" to VerificationTier.TIER_1_EXACT,
        "budget_adjustment_events"       to VerificationTier.TIER_1_EXACT,
        "spending_challenges"            to VerificationTier.TIER_1_EXACT,

        // ── Tier 2: Validity check (10 tables) — followed by 7 TIER_1_EXACT
        //    event/link tables (see Tier 1 note above) ──
        "blocked_packages"               to VerificationTier.TIER_2_VALIDITY,
        "merchant_canonicals"            to VerificationTier.TIER_2_VALIDITY,
        "merchant_aliases"               to VerificationTier.TIER_2_VALIDITY,
        "merchant_locations"             to VerificationTier.TIER_2_VALIDITY,
        "merchant_location_corrections"  to VerificationTier.TIER_2_VALIDITY,
        "ai_artifacts"                   to VerificationTier.TIER_2_VALIDITY,
        "ai_chat_sessions"               to VerificationTier.TIER_2_VALIDITY,
        "ai_chat_messages"               to VerificationTier.TIER_2_VALIDITY,
        "recommendations"                to VerificationTier.TIER_2_VALIDITY,
        "receipt_item_categorizations"   to VerificationTier.TIER_2_VALIDITY,
        "transaction_events"             to VerificationTier.TIER_1_EXACT,
        "receipt_events"                 to VerificationTier.TIER_1_EXACT,
        "receipt_expense_links"          to VerificationTier.TIER_1_EXACT,
        "recurring_occurrences"          to VerificationTier.TIER_1_EXACT,
        "recurring_reminder_deliveries"  to VerificationTier.TIER_1_EXACT,
        "recurring_lifecycle_events"     to VerificationTier.TIER_1_EXACT,
        // S9 / P9-P1-09: durable warranty-reminder sent-state. Modeled on
        // recurring_reminder_deliveries; exact-count verified so it survives restore.
        "warranty_reminder_deliveries"   to VerificationTier.TIER_1_EXACT,

        // ── Tier 3: Optional (10 tables) ──
        "exchange_rates"                 to VerificationTier.TIER_3_OPTIONAL,
        "anomaly_alerts"                 to VerificationTier.TIER_3_OPTIONAL,
        "prompt_states"                  to VerificationTier.TIER_3_OPTIONAL,
        "health_score_history"           to VerificationTier.TIER_3_OPTIONAL,
        "savings_sweep_plan"             to VerificationTier.TIER_3_OPTIONAL,
        "spending_personality_profiles"  to VerificationTier.TIER_3_OPTIONAL,
        "stress_forecast_snapshots"      to VerificationTier.TIER_3_OPTIONAL,
        "email_receipt_sources"          to VerificationTier.TIER_3_OPTIONAL,
        "privacy_audit_events"           to VerificationTier.TIER_3_OPTIONAL,
        "background_job_runs"            to VerificationTier.TIER_3_OPTIONAL
    )

    // ── Verification result ───────────────────────────────────────

    data class TableResult(
        val tableName: String,
        val tier: VerificationTier,
        val expectedCount: Int?,
        val actualCount: Int,
        val passed: Boolean,
        val message: String
    )

    data class VerificationSummary(
        val passed: Boolean,
        val tableResults: List<TableResult>,
        val integrityCheckOk: Boolean,
        val foreignKeyCheckOk: Boolean,
        val totalTablesChecked: Int,
        val totalTablesPassed: Int,
        val totalTablesFailed: Int,
        val errors: List<String>
    )

    enum class VerificationTier {
        TIER_1_EXACT,
        TIER_2_VALIDITY,
        TIER_3_OPTIONAL
    }

    // ── Exceptions ────────────────────────────────────────────────

    class CountMismatchException(
        val tableName: String,
        val expected: Int,
        val actual: Int
    ) : Exception(
        "Table '$tableName' count mismatch: expected $expected, actual $actual"
    )

    class IntegrityCheckFailedException(val result: String) :
        Exception("Database integrity check failed: $result")

    class ForeignKeyCheckFailedException(val violationCount: Int) :
        Exception("Foreign key check failed: $violationCount violations")

    /**
     * P7-CURRENT-014: thrown when a backup manifest is missing required (Tier 1)
     * table counts, detected BEFORE the destructive live-DB swap.
     */
    class IncompleteManifestException(val missingTables: List<String>) :
        Exception("Backup manifest is missing required Tier 1 table counts: ${missingTables.sorted().joinToString(", ")}")

    /**
     * P7-CURRENT-015: thrown when a row-count query fails for a required
     * (Tier 1 / Tier 2) table during backup creation. Required-table counts must
     * never be silently recorded as zero.
     */
    class RequiredTableCountException(val tableName: String, cause: Throwable?) :
        Exception("Row-count query failed for required table '$tableName'", cause)

    // ── Manifest completeness (P7-CURRENT-014) ───────────────────

    /**
     * Returns the set of table names that MUST have a manifest count for the
     * given backup format version. For the current format (v1) this is every
     * Tier 1 table — they are exact-count verified and a missing entry would let
     * a malformed manifest pass [verifyQuick] (which skips null counts) and only
     * fail in full [verify] AFTER the destructive swap.
     */
    fun requiredManifestTables(backupFormatVersion: Int = 1): Set<String> =
        TABLE_TIERS.filterValues { it == VerificationTier.TIER_1_EXACT }.keys

    /**
     * P7-CURRENT-014: validates that [expectedCounts] (the backup manifest's
     * table counts) contains every required Tier 1 table for [backupFormatVersion].
     * Call this BEFORE any destructive swap.
     *
     * @throws IncompleteManifestException if any required table count is absent.
     */
    fun validateManifestCompleteness(
        expectedCounts: Map<String, Int>,
        backupFormatVersion: Int = 1
    ) {
        val missing = requiredManifestTables(backupFormatVersion)
            .filter { it !in expectedCounts }
        if (missing.isNotEmpty()) {
            throw IncompleteManifestException(missing)
        }
    }

    // ── Strict count collection for backup creation (P7-CURRENT-015) ──

    /**
     * Collects row counts for ALL known tables from [db], failing fast if a
     * count query throws for a required (Tier 1 / Tier 2) table.
     *
     * Unlike a `catch { 0 }` collector, a query failure on a required table is a
     * real error: silently writing 0 would produce a misleading manifest and let
     * a later restore compare against fake zero counts. Optional (Tier 3) tables
     * that are absent or unqueryable are recorded as 0 (they may legitimately not
     * exist in the snapshot).
     *
     * @throws RequiredTableCountException if a required table's count query fails.
     */
    fun collectTableCountsStrict(db: SQLiteDatabase): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for ((tableName, tier) in TABLE_TIERS) {
            val required = tier == VerificationTier.TIER_1_EXACT || tier == VerificationTier.TIER_2_VALIDITY
            if (required && !tableExists(db, tableName)) {
                throw RequiredTableCountException(tableName, null)
            }
            val actual = countRows(db, tableName)
            if (actual == -1) {
                if (required) throw RequiredTableCountException(tableName, null)
                counts[tableName] = 0 // optional table absent/unqueryable
            } else {
                counts[tableName] = actual
            }
        }
        return counts
    }

    // ── Full verification ─────────────────────────────────────────

    /**
     * Runs full verification against the given database file.
     *
     * @param dbFile the SQLite database file to verify
     * @param expectedCounts expected row counts per table (from backup manifest)
     * @return [VerificationSummary] with per-table results
     */
    fun verify(
        dbFile: java.io.File,
        expectedCounts: Map<String, Int>
    ): VerificationSummary {
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        return try {
            verifyInternal(db, expectedCounts)
        } finally {
            db.close()
        }
    }

    /**
     * Runs full verification against an already-opened database.
     */
    fun verify(
        db: SQLiteDatabase,
        expectedCounts: Map<String, Int>
    ): VerificationSummary {
        return verifyInternal(db, expectedCounts)
    }

    private fun verifyInternal(
        db: SQLiteDatabase,
        expectedCounts: Map<String, Int>
    ): VerificationSummary {
        val errors = mutableListOf<String>()
        val tableResults = mutableListOf<TableResult>()
        var integrityCheckOk = true
        var foreignKeyCheckOk = true

        // 1. PRAGMA integrity_check
        val integrityResult = readSingleString(db, "PRAGMA integrity_check")
        if (!integrityResult.equals("ok", ignoreCase = true)) {
            integrityCheckOk = false
            errors.add("Integrity check failed: $integrityResult")
        }

        // 2. PRAGMA foreign_key_check
        val fkViolations = countFkViolations(db)
        if (fkViolations > 0) {
            foreignKeyCheckOk = false
            errors.add("Foreign key check: $fkViolations violation(s)")
        }

        // 3. Per-table verification
        for ((tableName, tier) in TABLE_TIERS) {
            val expected = expectedCounts[tableName]

            // [P1-4] Explicit table-existence check for Tier 1 and Tier 2 tables.
            // A missing required table must be reported as CRITICAL, not silently
            // treated as "0 rows" (which could happen when countRows returns -1).
            if (tier == VerificationTier.TIER_1_EXACT || tier == VerificationTier.TIER_2_VALIDITY) {
                if (!tableExists(db, tableName)) {
                    val message = "CRITICAL: Required table '$tableName' is missing from database"
                    tableResults.add(
                        TableResult(
                            tableName = tableName,
                            tier = tier,
                            expectedCount = expected,
                            actualCount = 0,
                            passed = false,
                            message = message
                        )
                    )
                    errors.add(message)
                    continue
                }
            }

            val actual = countRows(db, tableName)

            // [P1-6] If countRows returns -1 despite the table existing (confirmed above),
            // the count query itself failed (SQL exception, etc.). For Tier 1/2 this
            // must fail verification — not silently pass as "0 rows".
            if (actual == -1 && (tier == VerificationTier.TIER_1_EXACT || tier == VerificationTier.TIER_2_VALIDITY)) {
                val message = "CRITICAL: Count query failed for required table '$tableName'"
                tableResults.add(
                    TableResult(
                        tableName = tableName,
                        tier = tier,
                        expectedCount = expected,
                        actualCount = 0,
                        passed = false,
                        message = message
                    )
                )
                errors.add(message)
                continue
            }

            val (passed, message) = when (tier) {
                VerificationTier.TIER_1_EXACT -> {
                    if (expected == null) {
                        false to "Table '$tableName' not found in manifest counts"
                    } else if (actual != expected) {
                        false to "Count mismatch: expected $expected, actual $actual"
                    } else {
                        true to "OK (exact: $actual)"
                    }
                }

                VerificationTier.TIER_2_VALIDITY -> {
                    // Must exist, must pass FK check
                    val fkOk = countFkViolationsForTable(db, tableName) == 0
                    if (!fkOk) {
                        false to "FK violations in tier-2 table"
                    } else {
                        true to "OK (valid: $actual rows)"
                    }
                }

                VerificationTier.TIER_3_OPTIONAL -> {
                    // May be absent; if present, just log the count
                    if (actual == -1) {
                        true to "OK (table absent, optional)"
                    } else {
                        true to "OK (optional: $actual rows)"
                    }
                }
            }

            val tableResult = TableResult(
                tableName = tableName,
                tier = tier,
                expectedCount = expected,
                actualCount = if (actual == -1) 0 else actual,
                passed = passed,
                message = message
            )
            tableResults.add(tableResult)
            if (!passed) {
                errors.add(message)
            }
        }

        val totalPassed = tableResults.count { it.passed }
        val totalFailed = tableResults.count { !it.passed }

        // 4. Semantic integrity checks (orphan FK references)
        val semanticErrors = verifySemanticIntegrity(db)
        errors.addAll(semanticErrors)

        val overallPassed = integrityCheckOk && foreignKeyCheckOk && errors.isEmpty()

        return VerificationSummary(
            passed = overallPassed,
            tableResults = tableResults,
            integrityCheckOk = integrityCheckOk,
            foreignKeyCheckOk = foreignKeyCheckOk,
            totalTablesChecked = tableResults.size,
            totalTablesPassed = totalPassed,
            totalTablesFailed = totalFailed,
            errors = errors
        )
    }

    // ── Quick verification (for staged import) ────────────────────

    /**
     * Fast pre-swap verification: checks integrity, FK, and that Tier 1
     * tables have matching counts. Throws on failure.
     *
     * @throws CountMismatchException, IntegrityCheckFailedException, ForeignKeyCheckFailedException
     */
    fun verifyQuick(
        dbFile: java.io.File,
        expectedCounts: Map<String, Int>
    ) {
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            // Integrity
            val integrityResult = readSingleString(db, "PRAGMA integrity_check")
            if (!integrityResult.equals("ok", ignoreCase = true)) {
                throw IntegrityCheckFailedException(integrityResult)
            }

            // FK check
            val fkViolations = countFkViolations(db)
            if (fkViolations > 0) {
                throw ForeignKeyCheckFailedException(fkViolations)
            }

            // Tier 1 exact counts
            for ((tableName, tier) in TABLE_TIERS) {
                if (tier != VerificationTier.TIER_1_EXACT) continue
                val expected = expectedCounts[tableName]
                if (expected == null) continue // skip if not in manifest
                val actual = countRows(db, tableName)
                if (actual != expected) {
                    throw CountMismatchException(tableName, expected, actual)
                }
            }
        } finally {
            db.close()
        }
    }

    // ── Internal helpers ──────────────────────────────────────────

    private fun verifySemanticIntegrity(db: SQLiteDatabase): List<String> {
        val errors = mutableListOf<String>()
        val checks = listOf(
            "SELECT COUNT(*) FROM receipt_expense_links WHERE expenseId NOT IN (SELECT id FROM expenses)"
                to "Orphan receipt_expense_links referencing non-existent expenses",
            "SELECT COUNT(*) FROM recurring_occurrences WHERE sourceId NOT IN (SELECT id FROM manual_recurring_expenses)"
                to "Orphan recurring_occurrences referencing non-existent recurring expenses",
            "SELECT COUNT(*) FROM budget_forecasts WHERE budgetId NOT IN (SELECT id FROM budgets)"
                to "Orphan budget_forecasts referencing non-existent budgets"
        )
        for ((sql, description) in checks) {
            try {
                val cursor = db.rawQuery(sql, null)
                val count = cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
                if (count > 0) {
                    errors.add("Semantic integrity: $description ($count orphan rows)")
                }
            } catch (e: Exception) {
                Timber.w(e, "Semantic integrity check skipped: $description")
            }
        }
        return errors
    }

    private fun countRows(db: SQLiteDatabase, tableName: String): Int {
        return try {
            if (!tableExists(db, tableName)) return -1
            val cursor = db.rawQuery("SELECT COUNT(*) FROM \"$tableName\"", null)
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) {
            Timber.w("Could not count rows for table '%s': %s", tableName, e.message)
            -1 // Table doesn't exist or not accessible
        }
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        )
        return cursor.use { it.moveToFirst() }
    }

    private fun readSingleString(db: SQLiteDatabase, sql: String): String {
        val cursor = db.rawQuery(sql, null)
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) ?: "" else ""
        }
    }

    private fun countFkViolations(db: SQLiteDatabase): Int {
        val cursor = db.rawQuery("PRAGMA foreign_key_check", null)
        return cursor.use {
            var count = 0
            while (it.moveToNext()) count++
            count
        }
    }

    private fun countFkViolationsForTable(db: SQLiteDatabase, tableName: String): Int {
        // PRAGMA foreign_key_check doesn't support filtering by table directly
        // We check all and count for the specific table
        val cursor = db.rawQuery("PRAGMA foreign_key_check", null)
        return cursor.use {
            var count = 0
            while (it.moveToNext()) {
                val table = it.getString(it.getColumnIndexOrThrow("table"))
                if (table == tableName) count++
            }
            count
        }
    }

    /**
     * Returns the set of all 57 table names for ArchUnit/grep assertions.
     */
    fun allTableNames(): Set<String> = TABLE_TIERS.keys

    /**
     * Returns the verification tier for a given table name.
     * Defaults to TIER_3_OPTIONAL for unknown tables.
     */
    fun tableTier(tableName: String): VerificationTier {
        return TABLE_TIERS[tableName] ?: VerificationTier.TIER_3_OPTIONAL
    }
}
