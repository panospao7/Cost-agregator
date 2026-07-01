package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

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
 * The APPROVED_FILES set represents the current approved write owners per
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

    companion object {
        /**
         * Files approved to call critical event DAO insert methods.
         *
         * Each entry requires: owner, reason, and expiry MIT reference.
         * This list MUST only grow with architecture review — never arbitrarily.
         */
        val APPROVED_FILES = setOf(
            // === Transaction events ===
            "TransactionLifecycleCoordinator.kt",         // Owner: P2 coordinator — MIT-031
            "DebugExpenseAuditWriter.kt",                  // Owner: P2 debug audit — MIT-031
            "DefaultExpenseCategoryAssignmentService.kt",  // Owner: P2 category assignment — MIT-031
            "GroupTransactionCoordinator.kt",              // Owner: P2 group transactions — MIT-031
            "TransactionSideEffectFailureEventWriter.kt",  // Owner: P2 side-effect mirror — MIT-031
            "TransactionSideEffectPlanner.kt",             // Owner: P2 side-effect planner — MIT-031

            // === Receipt events ===
            "ReceiptLifecycleCoordinator.kt",              // Owner: P3 coordinator — MIT-041
            "ReceiptSideEffectPlanner.kt",                  // Owner: P3 side-effect planner — MIT-041
            "ReceiptMatchLifecycleService.kt",              // Owner: P3 match lifecycle — MIT-041
            "ReceiptLinkService.kt",                        // Owner: P3 link service — MIT-041
            "BankStatementLifecycleProcessor.kt",           // Owner: P3 bank statement — MIT-041

            // === Recurring lifecycle events ===
            "RecurringLifecycleCoordinator.kt",            // Owner: P4 coordinator — MIT-043
            "RecurringRuleLifecycleCoordinator.kt",        // Owner: P4 rule lifecycle — MIT-043
            "RecurringOccurrenceMaterializer.kt",          // Owner: P4 materializer (known LEGAL_PATHS deviation) — MIT-043
            "RecurringLifecycleEventWriter.kt",            // Owner: P4 event writer — MIT-043
            "RecurringLifecycleEventWriter.kt",            // Owner: P4 event writer interface — MIT-043

            // === Group events ===
            "GroupLifecycleCoordinator.kt",                // Owner: P2 groups — MIT-031

            // === Warranty events ===
            "WarrantyTrackerRepository.kt",                // Owner: warranty tracking — MIT-031

            // === Operation run events ===
            "OperationRunRecorder.kt",                     // Owner: Ops run recorder — MIT-031
            "RestoreJournalImporter.kt",                   // Owner: P7 restore — MIT-031

            // === Worker events ===
            "WorkerRunLogger.kt",                          // Owner: P9 worker runs — MIT-031
            "WorkerExecutionGuard.kt",                     // Owner: P9 worker guard — MIT-031

            // === Repository-level events (legacy, approved for now) ===
            "ReceiptRepository.kt",                        // Owner: P3 repository — MIT-041 (to be migrated to coordinator)
            "NotificationRepository.kt",                   // Owner: P1 notification — MIT-031 (to be migrated)
            "ReviewQueueRepository.kt",                    // Owner: P3 review queue — MIT-041 (to be migrated)
            "RecurringExpenseRepository.kt",               // Owner: P4 repository — MIT-043 (to be migrated)
            "ManualRecurringExpenseRepository.kt",         // Owner: P4 manual recurring — MIT-043 (to be migrated)
            "ExpenseRepository.kt",                        // Owner: P2 repository — MIT-031 (to be migrated)

            // === Bank integration ===
            "BankApiIntegration.kt",                       // Owner: P10 bank integration — MIT-041 (to be migrated to coordinator)

            // === Privacy/audit events ===
            "DataRetentionWorker.kt",                      // Owner: P8 data retention — MIT-031
            "ReceiptLifecycleEventWriter.kt",               // Owner: P3 receipt event writer (contains Room impl) — MIT-041
            "TransactionLifecycleEventWriter.kt",           // Owner: P2 transaction event writer (contains Room impl) — MIT-031
            "DiagnosticEventWriter.kt",                      // Owner: diagnostics (contains Room impl) — MIT-031
            "RecurringLifecycleEventWriter.kt",              // Owner: P4 recurring event writer (contains Room impl) — MIT-043
            "DiagnosticSideEffectEventWriter.kt",          // Owner: diagnostics — MIT-031
            "CompositeSideEffectEventWriter.kt",           // Owner: diagnostics — MIT-031
            "CompositeDiagnosticEventWriter.kt",           // Owner: diagnostics — MIT-031
            "CompositeOperationRunRecorder.kt",            // Owner: diagnostics — MIT-031
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
        val ktFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in APPROVED_FILES }
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
                "Add the file to APPROVED_FILES with owner/reason, or route through an approved writer.\n" +
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
        val stale = APPROVED_FILES.filter { it !in allKtNames }
        assertTrue(
            "APPROVED_FILES contains entries that don't map to real source files: $stale. " +
                "Remove stale/renamed entries.",
            stale.isEmpty()
        )
    }
}
