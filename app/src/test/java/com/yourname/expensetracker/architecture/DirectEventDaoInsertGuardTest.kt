package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * PR 3 — Guard: Direct lifecycle event DAO inserts from non-approved files.
 *
 * Contract EVENT-GUARD-01: Critical lifecycle event DAO `insert()` calls (from
 * `TransactionEventDao`, `ReceiptEventDao`, `RecurringLifecycleEventDao`,
 * `LifecycleEventDao`, `WarrantyLifecycleEventDao`, `GroupLifecycleEventDao`)
 * MUST only originate from approved coordinator/writer files.
 *
 * This test scans production `.kt` source files and fails if any non-approved
 * file contains a direct event DAO `.insert(` call on a critical DAO type.
 *
 * The APPROVED_ENTRIES list represents the current approved write owners per
 * `docs/atomicity/TRANSACTIONAL_EVENT_POLICY.md` §6.1. New entries require
 * architecture review and owner/reason documentation.
 */
class DirectEventDaoInsertGuardTest {

    private val sourceRoot: File by lazy { resolveSourceRoot() }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}")
    }

    /**
     * Structured allowlist entry that requires owner, reason, issue tracking, and
     * expiry date — ensuring no allowlisted entry is left unaccounted for.
     */
    data class DirectEventAllowlistEntry(
        val fileName: String,
        val rule: String,
        val owner: String,
        val reason: String,
        val issue: String,
        val expires: LocalDate
    )

    companion object {
        /**
         * Files approved to call critical event DAO insert methods.
         *
         * Each entry is a structured [DirectEventAllowlistEntry] with owner, reason,
         * issue tracking, and expiry date. This list MUST only grow with architecture
         * review — never arbitrarily.
         */
        val APPROVED_ENTRIES = listOf(
            // === Transaction events (DIRECT_TRANSACTION_EVENT_DAO_INSERT) ===
            DirectEventAllowlistEntry(
                "TransactionLifecycleCoordinator.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Transaction", "P2 coordinator writes lifecycle events", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "DebugExpenseAuditWriter.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Transaction", "P2 debug audit writes transaction events", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "DefaultExpenseCategoryAssignmentService.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Transaction", "P2 category assignment writes transaction events", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "GroupTransactionCoordinator.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Transaction", "P2 group transaction coordinator", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "TransactionSideEffectFailureEventWriter.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Transaction", "P2 side-effect mirror writes failure events", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "TransactionSideEffectPlanner.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Transaction", "P2 side-effect planner coordinates event writes", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "GroupLifecycleCoordinator.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Transaction", "P2 group lifecycle coordinator", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "WarrantyTrackerRepository.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Transaction", "Warranty tracking writes lifecycle events", "MIT-031",
                LocalDate.of(2026, 8, 15)
            ),
            DirectEventAllowlistEntry(
                "TransactionLifecycleEventWriter.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Transaction", "P2 transaction event writer (contains Room impl)", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "ExpenseRepository.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Transaction", "P2 repository (legacy, to be migrated to coordinator)", "MIT-031",
                LocalDate.of(2026, 8, 15)
            ),

            // === Receipt events (DIRECT_RECEIPT_EVENT_DAO_INSERT) ===
            DirectEventAllowlistEntry(
                "ReceiptLifecycleCoordinator.kt", "DIRECT_RECEIPT_EVENT_DAO_INSERT",
                "Receipt", "P3 coordinator writes receipt lifecycle events", "MIT-041",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "ReceiptSideEffectPlanner.kt", "DIRECT_RECEIPT_EVENT_DAO_INSERT",
                "Receipt", "P3 side-effect planner coordinates receipt event writes", "MIT-041",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "ReceiptMatchLifecycleService.kt", "DIRECT_RECEIPT_EVENT_DAO_INSERT",
                "Receipt", "P3 match lifecycle writes receipt events", "MIT-041",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "ReceiptLinkService.kt", "DIRECT_RECEIPT_EVENT_DAO_INSERT",
                "Receipt", "P3 link service writes receipt events", "MIT-041",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "BankStatementLifecycleProcessor.kt", "DIRECT_RECEIPT_EVENT_DAO_INSERT",
                "Receipt", "P3 bank statement processor writes receipt events", "MIT-041",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "ReceiptRepository.kt", "DIRECT_RECEIPT_EVENT_DAO_INSERT",
                "Receipt", "P3 repository (legacy, to be migrated to coordinator)", "MIT-041",
                LocalDate.of(2026, 8, 15)
            ),
            DirectEventAllowlistEntry(
                "ReviewQueueRepository.kt", "DIRECT_RECEIPT_EVENT_DAO_INSERT",
                "Receipt", "P3 review queue repository (legacy, to be migrated)", "MIT-041",
                LocalDate.of(2026, 8, 15)
            ),
            DirectEventAllowlistEntry(
                "ReceiptLifecycleEventWriter.kt", "DIRECT_RECEIPT_EVENT_DAO_INSERT",
                "Receipt", "P3 receipt event writer (contains Room impl)", "MIT-041",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "BankApiIntegration.kt", "DIRECT_RECEIPT_EVENT_DAO_INSERT",
                "Receipt", "P10 bank integration (legacy, to be migrated to coordinator)", "MIT-041",
                LocalDate.of(2026, 8, 15)
            ),

            // === Recurring lifecycle events (DIRECT_RECURRING_EVENT_DAO_INSERT) ===
            DirectEventAllowlistEntry(
                "RecurringLifecycleCoordinator.kt", "DIRECT_RECURRING_EVENT_DAO_INSERT",
                "Recurring", "P4 coordinator writes recurring lifecycle events", "MIT-043",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "RecurringRuleLifecycleCoordinator.kt", "DIRECT_RECURRING_EVENT_DAO_INSERT",
                "Recurring", "P4 rule lifecycle coordinator", "MIT-043",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "RecurringOccurrenceMaterializer.kt", "DIRECT_RECURRING_EVENT_DAO_INSERT",
                "Recurring", "P4 materializer (known LEGAL_PATHS deviation)", "MIT-043",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "RecurringLifecycleEventWriter.kt", "DIRECT_RECURRING_EVENT_DAO_INSERT",
                "Recurring", "P4 event writer (contains Room impl)", "MIT-043",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "RecurringExpenseRepository.kt", "DIRECT_RECURRING_EVENT_DAO_INSERT",
                "Recurring", "P4 repository (legacy, to be migrated)", "MIT-043",
                LocalDate.of(2026, 8, 15)
            ),
            DirectEventAllowlistEntry(
                "ManualRecurringExpenseRepository.kt", "DIRECT_RECURRING_EVENT_DAO_INSERT",
                "Recurring", "P4 manual recurring repository (legacy, to be migrated)", "MIT-043",
                LocalDate.of(2026, 8, 15)
            ),

            // === Operations, diagnostics, and infrastructure events ===
            DirectEventAllowlistEntry(
                "OperationRunRecorder.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Operations", "Ops run recorder writes operation events", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "RestoreJournalImporter.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Operations", "P7 restore journal importer", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "WorkerRunLogger.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Operations", "P9 worker run logger writes lifecycle events", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "WorkerExecutionGuard.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Operations", "P9 worker guard writes safeguard events", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "NotificationRepository.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Operations", "P1 notification repository (legacy, to be migrated)", "MIT-031",
                LocalDate.of(2026, 8, 15)
            ),
            DirectEventAllowlistEntry(
                "DataRetentionWorker.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Operations", "P8 data retention worker writes audit events", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "DiagnosticEventWriter.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Operations", "Diagnostic event writer (contains Room impl)", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "DiagnosticSideEffectEventWriter.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Operations", "Diagnostic side-effect event writer", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "CompositeSideEffectEventWriter.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Operations", "Composite side-effect event writer", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "CompositeDiagnosticEventWriter.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Operations", "Composite diagnostic event writer", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
            DirectEventAllowlistEntry(
                "CompositeOperationRunRecorder.kt", "DIRECT_TRANSACTION_EVENT_DAO_INSERT",
                "Operations", "Composite operation run recorder", "MIT-031",
                LocalDate.of(2026, 12, 31)
            ),
        )

        /** DAO variable names whose `.insert()` calls are guarded. */
        val GUARDED_DAO_NAMES = setOf(
            "transactionEventDao",
            "receiptEventDao",
            "lifecycleEventDao",
            "recurringLifecycleEventDao",
            "warrantyLifecycleEventDao",
            "groupLifecycleEventDao",
            "operationRunEventDao",
            "auditDao",
            "eventDao",          // Used by OperationRunRecorder
            "pendingReviewDao",  // MIT-041: PendingReview insert from non-coordinator
        )

        /**
         * DAO insert patterns to detect.
         *
         * Pattern 1 (direct): `transactionEventDao.insert()`
         * Pattern 2 (database-chained): `database.transactionEventDao().insert()`
         */
        val DIRECT_INSERT_PATTERN = Regex("""(?:\b(\w+)\.insert\s*\()""")
        val CHAINED_INSERT_PATTERN = Regex("""database\s*\.\s*(\w*)Dao\s*\(\s*\)\s*\.\s*insert\s*\(""")
    }

    @Test
    fun `direct event DAO inserts only from approved files`() {
        val approvedFileNames = APPROVED_ENTRIES.map { it.fileName }.toSet()
        val ktFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in approvedFileNames }
            .filter {
                val text = it.readText()
                // Check for direct pattern: daoName.insert()
                val hasDirect = GUARDED_DAO_NAMES.any { name -> text.contains("$name.insert") }
                // Check for chained pattern: database.daoName().insert()
                val hasChained = CHAINED_INSERT_PATTERN.containsMatchIn(text)
                hasDirect || hasChained
            }
            .toList()

        assertTrue(
            "Architecture guard scanned ZERO .kt files in ${sourceRoot.absolutePath}. " +
                "Source root resolution is broken — this test would pass vacuously.",
            sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.count() >= 10
        )

        val violations = mutableListOf<String>()

        for (file in ktFiles) {
            val content = file.readText()
            val relativePath = file.relativeTo(sourceRoot).path

            // Check pattern 1: direct daoName.insert()
            for (match in DIRECT_INSERT_PATTERN.findAll(content)) {
                val varName = match.groupValues[1]
                if (varName !in GUARDED_DAO_NAMES) continue
                val lineNum = content.substring(0, match.range.first).count { it == '\n' } + 1
                violations.add("$relativePath:$lineNum — $varName.insert() call outside approved file")
            }

            // Check pattern 2: database.xxxDao().insert()
            for (match in CHAINED_INSERT_PATTERN.findAll(content)) {
                val daoName = match.groupValues[1]
                // Build the variable-like name: "transactionEventDao"
                val varName = "${daoName}Dao"
                if (varName !in GUARDED_DAO_NAMES) continue
                val lineNum = content.substring(0, match.range.first).count { it == '\n' } + 1
                violations.add("$relativePath:$lineNum — database.$daoName Dao().insert() call outside approved file")
            }
        }

        assertTrue(
            "EVENT-GUARD-01 violations: direct event DAO insert calls from non-approved files.\n" +
                "Add the file to APPROVED_ENTRIES with owner/reason/issue/expiry, or route through an approved writer.\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `approved files list maps to real source files`() {
        val allKtNames = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name }
            .toSet()
        val stale = APPROVED_ENTRIES.filter { it.fileName !in allKtNames }
        assertTrue(
            "APPROVED_ENTRIES contains entries that don't map to real source files: " +
                stale.map { it.fileName } +
                ". Remove stale/renamed entries.",
            stale.isEmpty()
        )
    }

    // ── Structured allowlist validation ──────────────────────────────────────

    @Test
    fun `structured allowlist requires owner reason issue expiry`() {
        for (entry in APPROVED_ENTRIES) {
            assertTrue(
                "Allowlist entry ${entry.fileName} missing owner",
                entry.owner.isNotBlank()
            )
            assertTrue(
                "Allowlist entry ${entry.fileName} missing reason",
                entry.reason.isNotBlank()
            )
            assertTrue(
                "Allowlist entry ${entry.fileName} missing issue",
                entry.issue.isNotBlank()
            )
            assertNotNull(
                "Allowlist entry ${entry.fileName} missing expiry",
                entry.expires
            )
            assertTrue(
                "Allowlist entry ${entry.fileName} missing rule",
                entry.rule.isNotBlank()
            )
        }
    }

    @Test
    fun `expired direct event allowlist entries fail`() {
        val today = LocalDate.now()
        val expired = APPROVED_ENTRIES.filter { it.expires.isBefore(today) }
        assertTrue(
            "Expired direct event allowlist entries found: " +
                expired.map { it.fileName },
            expired.isEmpty()
        )
    }

    @Test
    fun `no duplicate direct event allowlist entries`() {
        val duplicates = APPROVED_ENTRIES.groupBy { it.fileName }
            .filter { it.value.size > 1 }
            .keys
        assertTrue(
            "Duplicate direct event allowlist entries found: $duplicates",
            duplicates.isEmpty()
        )
    }
}
