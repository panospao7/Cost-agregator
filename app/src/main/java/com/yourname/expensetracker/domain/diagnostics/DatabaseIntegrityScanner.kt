package com.yourname.expensetracker.domain.diagnostics

import android.util.Log
import com.yourname.expensetracker.data.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the database for invariant violations and data integrity issues.
 *
 * Each check returns a list of [IntegrityViolation] objects describing what
 * was found. Callers can aggregate results for logging, debug UI display,
 * or automated repair.
 */
@Singleton
class DatabaseIntegrityScanner @Inject constructor(
    private val database: AppDatabase
) {

    /** Runs all scans and returns combined results. */
    suspend fun runFullScan(): List<IntegrityViolation> {
        val results = mutableListOf<IntegrityViolation>()
        results.addAll(checkDuplicateActiveOverallBudgets())
        results.addAll(checkDuplicateActiveCategoryBudgets())
        results.addAll(checkMultipleCurrentUsersPerGroup())
        results.addAll(checkDuplicateGroupExpenseLinks())
        results.addAll(checkDuplicatePlannedExpenseOccurrenceKeys())
        results.addAll(checkRawNotificationFingerprintDupes())
        results.addAll(checkExpensesWithNullDedupeKey())
        results.addAll(checkPartialLatLonRows())
        results.addAll(checkInvalidCurrencyValues())
        results.addAll(checkOrphanedWarranties())
        results.addAll(checkOrphanedReceiptLinks())
        logResults(results)
        return results
    }

    /** Runs only P0 (critical) scans — suitable for cold-start checks. */
    suspend fun runCriticalScans(): List<IntegrityViolation> {
        val results = mutableListOf<IntegrityViolation>()
        results.addAll(checkDuplicateActiveOverallBudgets())
        results.addAll(checkDuplicateActiveCategoryBudgets())
        results.addAll(checkMultipleCurrentUsersPerGroup())
        results.addAll(checkDuplicateGroupExpenseLinks())
        logResults(results)
        return results
    }

    // ── Individual scan methods ──────────────────────────────────────────────

    /** 1. Duplicate active overall budgets (more than one row with activeOverallKey IS NOT NULL). */
    suspend fun checkDuplicateActiveOverallBudgets(): List<IntegrityViolation> {
        val violations = mutableListOf<IntegrityViolation>()
        val db = database.openHelper.writableDatabase
        db.query("SELECT COUNT(*) AS cnt FROM budgets WHERE activeOverallKey IS NOT NULL").use { c ->
            if (c.moveToFirst() && c.getInt(0) > 1) {
                val sampleIds = mutableListOf<Long>()
                db.query("SELECT id FROM budgets WHERE activeOverallKey IS NOT NULL ORDER BY id").use { s ->
                    while (s.moveToNext()) { sampleIds.add(s.getLong(0)) }
                }
                violations.add(
                    IntegrityViolation(
                        invariantName = "DUPLICATE_ACTIVE_OVERALL_BUDGET",
                        tableName = "budgets",
                        severity = Severity.CRITICAL,
                        count = c.getInt(0),
                        sampleIds = sampleIds,
                        detail = "Multiple active overall budgets found (more than one row with activeOverallKey IS NOT NULL)"
                    )
                )
            }
        }
        return violations
    }

    /** 2. Duplicate active category budgets (same activeCategoryKey in multiple rows). */
    suspend fun checkDuplicateActiveCategoryBudgets(): List<IntegrityViolation> {
        val violations = mutableListOf<IntegrityViolation>()
        val db = database.openHelper.writableDatabase
        db.query("""
            SELECT activeCategoryKey, COUNT(*) AS cnt
            FROM budgets WHERE activeCategoryKey IS NOT NULL
            GROUP BY activeCategoryKey HAVING cnt > 1
        """.trimIndent()).use { c ->
            while (c.moveToNext()) {
                val key = c.getLong(0)
                val count = c.getInt(1)
                val sampleIds = mutableListOf<Long>()
                db.query("SELECT id FROM budgets WHERE activeCategoryKey = $key ORDER BY id").use { s ->
                    while (s.moveToNext()) { sampleIds.add(s.getLong(0)) }
                }
                violations.add(
                    IntegrityViolation(
                        invariantName = "DUPLICATE_ACTIVE_CATEGORY_BUDGET",
                        tableName = "budgets",
                        severity = Severity.CRITICAL,
                        count = count,
                        sampleIds = sampleIds,
                        detail = "Category $key has $count active budgets"
                    )
                )
            }
        }
        return violations
    }

    /** 3. Multiple current users per group (same currentUserGroupKey in multiple rows). */
    suspend fun checkMultipleCurrentUsersPerGroup(): List<IntegrityViolation> {
        val violations = mutableListOf<IntegrityViolation>()
        val db = database.openHelper.writableDatabase
        db.query("""
            SELECT currentUserGroupKey, COUNT(*) AS cnt
            FROM group_members WHERE currentUserGroupKey IS NOT NULL
            GROUP BY currentUserGroupKey HAVING cnt > 1
        """.trimIndent()).use { c ->
            while (c.moveToNext()) {
                val groupId = c.getLong(0)
                val count = c.getInt(1)
                val sampleIds = mutableListOf<Long>()
                db.query("SELECT id FROM group_members WHERE currentUserGroupKey = $groupId ORDER BY id").use { s ->
                    while (s.moveToNext()) { sampleIds.add(s.getLong(0)) }
                }
                violations.add(
                    IntegrityViolation(
                        invariantName = "MULTIPLE_CURRENT_USERS_PER_GROUP",
                        tableName = "group_members",
                        severity = Severity.CRITICAL,
                        count = count,
                        sampleIds = sampleIds,
                        detail = "Group $groupId has $count current users"
                    )
                )
            }
        }
        return violations
    }

    /** 4. Duplicate group expense links (same expenseId in multiple group_expenses rows). */
    suspend fun checkDuplicateGroupExpenseLinks(): List<IntegrityViolation> {
        val violations = mutableListOf<IntegrityViolation>()
        val db = database.openHelper.writableDatabase
        db.query("""
            SELECT expenseId, COUNT(*) AS cnt
            FROM group_expenses WHERE expenseId IS NOT NULL
            GROUP BY expenseId HAVING cnt > 1
        """.trimIndent()).use { c ->
            while (c.moveToNext()) {
                val expenseId = c.getLong(0)
                val count = c.getInt(1)
                val sampleIds = mutableListOf<Long>()
                db.query("SELECT id FROM group_expenses WHERE expenseId = $expenseId ORDER BY id").use { s ->
                    while (s.moveToNext()) { sampleIds.add(s.getLong(0)) }
                }
                violations.add(
                    IntegrityViolation(
                        invariantName = "DUPLICATE_GROUP_EXPENSE_LINK",
                        tableName = "group_expenses",
                        severity = Severity.CRITICAL,
                        count = count,
                        sampleIds = sampleIds,
                        detail = "Expense $expenseId is linked to $count group_expenses rows"
                    )
                )
            }
        }
        return violations
    }

    /** 5. Duplicate planned expense open occurrence keys. */
    suspend fun checkDuplicatePlannedExpenseOccurrenceKeys(): List<IntegrityViolation> {
        val violations = mutableListOf<IntegrityViolation>()
        val db = database.openHelper.writableDatabase
        db.query("""
            SELECT openSourceOccurrenceKey, COUNT(*) AS cnt
            FROM planned_expenses WHERE openSourceOccurrenceKey IS NOT NULL
            GROUP BY openSourceOccurrenceKey HAVING cnt > 1
        """.trimIndent()).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                val count = c.getInt(1)
                val sampleIds = mutableListOf<Long>()
                db.query("SELECT id FROM planned_expenses WHERE openSourceOccurrenceKey = ? ORDER BY id",
                    arrayOf(key)).use { s ->
                    while (s.moveToNext()) { sampleIds.add(s.getLong(0)) }
                }
                violations.add(
                    IntegrityViolation(
                        invariantName = "DUPLICATE_PLANNED_OCCURRENCE_KEY",
                        tableName = "planned_expenses",
                        severity = Severity.WARNING,
                        count = count,
                        sampleIds = sampleIds,
                        detail = "Occurrence key '$key' has $count PLANNED rows"
                    )
                )
            }
        }
        return violations
    }

    /** 6. Raw notification fingerprint duplicates. */
    suspend fun checkRawNotificationFingerprintDupes(): List<IntegrityViolation> {
        val violations = mutableListOf<IntegrityViolation>()
        val db = database.openHelper.writableDatabase
        db.query("""
            SELECT dedupeFingerprint, COUNT(*) AS cnt
            FROM raw_notifications WHERE dedupeFingerprint IS NOT NULL
            GROUP BY dedupeFingerprint HAVING cnt > 1
        """.trimIndent()).use { c ->
            while (c.moveToNext()) {
                val fp = c.getString(0)
                val count = c.getInt(1)
                val sampleIds = mutableListOf<Long>()
                db.query("SELECT id FROM raw_notifications WHERE dedupeFingerprint = ? ORDER BY id",
                    arrayOf(fp)).use { s ->
                    while (s.moveToNext()) { sampleIds.add(s.getLong(0)) }
                }
                violations.add(
                    IntegrityViolation(
                        invariantName = "DUPLICATE_NOTIFICATION_FINGERPRINT",
                        tableName = "raw_notifications",
                        severity = Severity.WARNING,
                        count = count,
                        sampleIds = sampleIds,
                        detail = "Fingerprint '$fp' has $count raw_notifications rows (truncated: ${fp.take(32)}...)"
                    )
                )
            }
        }
        return violations
    }

    /** 7. Expenses with null dedupeKey. */
    suspend fun checkExpensesWithNullDedupeKey(): List<IntegrityViolation> {
        val violations = mutableListOf<IntegrityViolation>()
        val db = database.openHelper.writableDatabase
        db.query("SELECT COUNT(*) AS cnt FROM expenses WHERE dedupeKey IS NULL").use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) {
                val count = c.getInt(0)
                val sampleIds = mutableListOf<Long>()
                db.query("SELECT id FROM expenses WHERE dedupeKey IS NULL ORDER BY id LIMIT 10").use { s ->
                    while (s.moveToNext()) { sampleIds.add(s.getLong(0)) }
                }
                violations.add(
                    IntegrityViolation(
                        invariantName = "NULL_DEDUPE_KEY",
                        tableName = "expenses",
                        severity = Severity.INFO,
                        count = count,
                        sampleIds = sampleIds,
                        detail = "$count expenses have NULL dedupeKey (sample IDs: $sampleIds)"
                    )
                )
            }
        }
        return violations
    }

    /** 8. Partial lat/lon rows (one of latitude/longitude is NULL but not both). */
    suspend fun checkPartialLatLonRows(): List<IntegrityViolation> {
        val violations = mutableListOf<IntegrityViolation>()
        val db = database.openHelper.writableDatabase
        db.query("""
            SELECT COUNT(*) AS cnt FROM expenses
            WHERE (latitude IS NULL) != (longitude IS NULL)
        """.trimIndent()).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) {
                val count = c.getInt(0)
                val sampleIds = mutableListOf<Long>()
                db.query("""
                    SELECT id FROM expenses
                    WHERE (latitude IS NULL) != (longitude IS NULL)
                    ORDER BY id LIMIT 10
                """.trimIndent()).use { s ->
                    while (s.moveToNext()) { sampleIds.add(s.getLong(0)) }
                }
                violations.add(
                    IntegrityViolation(
                        invariantName = "PARTIAL_LAT_LON",
                        tableName = "expenses",
                        severity = Severity.WARNING,
                        count = count,
                        sampleIds = sampleIds,
                        detail = "$count expenses have partial lat/lon (one is NULL, the other is not)"
                    )
                )
            }
        }
        return violations
    }

    /** 9. Invalid currency values (non-empty but not matching known pattern). */
    suspend fun checkInvalidCurrencyValues(): List<IntegrityViolation> {
        val violations = mutableListOf<IntegrityViolation>()
        val db = database.openHelper.writableDatabase
        db.query("""
            SELECT COUNT(*) AS cnt FROM expenses
            WHERE currency IS NOT NULL
              AND LENGTH(currency) != 3
        """.trimIndent()).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) {
                val count = c.getInt(0)
                val sampleIds = mutableListOf<Long>()
                db.query("""
                    SELECT id FROM expenses
                    WHERE currency IS NOT NULL AND LENGTH(currency) != 3
                    ORDER BY id LIMIT 10
                """.trimIndent()).use { s ->
                    while (s.moveToNext()) { sampleIds.add(s.getLong(0)) }
                }
                violations.add(
                    IntegrityViolation(
                        invariantName = "INVALID_CURRENCY",
                        tableName = "expenses",
                        severity = Severity.WARNING,
                        count = count,
                        sampleIds = sampleIds,
                        detail = "$count expenses have non-ISO-4217 currency values (not 3 chars)"
                    )
                )
            }
        }
        return violations
    }

    /** 10. Orphaned warranties (receiptId or expenseId references deleted rows). */
    suspend fun checkOrphanedWarranties(): List<IntegrityViolation> {
        val violations = mutableListOf<IntegrityViolation>()
        val db = database.openHelper.writableDatabase
        // Check warranties whose receiptId points to a non-existent receipt
        // Null receiptId is allowed (SET_NULL FK), so exclude those rows.
        db.query("""
            SELECT COUNT(*) AS cnt FROM warranties w
            LEFT JOIN scanned_receipts sr ON sr.id = w.receiptId
            WHERE w.receiptId IS NOT NULL AND sr.id IS NULL
        """.trimIndent()).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) {
                violations.add(
                    IntegrityViolation(
                        invariantName = "ORPHANED_WARRANTY_RECEIPT",
                        tableName = "warranties",
                        severity = Severity.WARNING,
                        count = c.getInt(0),
                        sampleIds = emptyList(),
                        detail = "${c.getInt(0)} warranties reference non-existent receipts"
                    )
                )
            }
        }
        // Check warranties whose expenseId points to a non-existent expense
        db.query("""
            SELECT COUNT(*) AS cnt FROM warranties w
            LEFT JOIN expenses e ON e.id = w.expenseId
            WHERE w.expenseId IS NOT NULL AND e.id IS NULL
        """.trimIndent()).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) {
                violations.add(
                    IntegrityViolation(
                        invariantName = "ORPHANED_WARRANTY_EXPENSE",
                        tableName = "warranties",
                        severity = Severity.WARNING,
                        count = c.getInt(0),
                        sampleIds = emptyList(),
                        detail = "${c.getInt(0)} warranties reference non-existent expenses"
                    )
                )
            }
        }
        return violations
    }

    /** 11. Orphaned receipt-expense links (receiptId or expenseId references deleted rows). */
    suspend fun checkOrphanedReceiptLinks(): List<IntegrityViolation> {
        val violations = mutableListOf<IntegrityViolation>()
        val db = database.openHelper.writableDatabase
        db.query("""
            SELECT COUNT(*) AS cnt FROM receipt_expense_links rel
            LEFT JOIN scanned_receipts sr ON sr.id = rel.receiptId
            LEFT JOIN expenses e ON e.id = rel.expenseId
            WHERE sr.id IS NULL OR e.id IS NULL
        """.trimIndent()).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) {
                violations.add(
                    IntegrityViolation(
                        invariantName = "ORPHANED_RECEIPT_LINK",
                        tableName = "receipt_expense_links",
                        severity = Severity.WARNING,
                        count = c.getInt(0),
                        sampleIds = emptyList(),
                        detail = "${c.getInt(0)} receipt_expense_links have orphaned references"
                    )
                )
            }
        }
        return violations
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    private fun logResults(results: List<IntegrityViolation>) {
        if (results.isEmpty()) {
            Log.i(TAG, "Integrity scan complete — 0 violations found")
            return
        }
        for (v in results) {
            when (v.severity) {
                Severity.CRITICAL -> Log.e(TAG, "CRITICAL: ${v.invariantName} — ${v.detail}")
                Severity.WARNING -> Log.w(TAG, "WARNING: ${v.invariantName} — ${v.detail}")
                Severity.INFO -> Log.i(TAG, "INFO: ${v.invariantName} — ${v.detail}")
            }
        }
        val criticalCount = results.count { it.severity == Severity.CRITICAL }
        val warningCount = results.count { it.severity == Severity.WARNING }
        Log.w(TAG, "Integrity scan complete — ${results.size} total violations " +
            "($criticalCount critical, $warningCount warnings)")
    }

    companion object {
        private const val TAG = "DbIntegrityScanner"
    }
}

/**
 * Describes a single invariant violation discovered by [DatabaseIntegrityScanner].
 */
data class IntegrityViolation(
    val invariantName: String,
    val tableName: String,
    val severity: Severity,
    val count: Int,
    val sampleIds: List<Long>,
    val detail: String
)

/**
 * Severity levels for [IntegrityViolation].
 */
enum class Severity {
    /** Data corruption or invariant violation requiring immediate repair. */
    CRITICAL,
    /** Data anomaly that should be investigated but not blocking. */
    WARNING,
    /** Informational observation (e.g. many null dedupeKeys on legacy data). */
    INFO
}
