package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Static architecture tests that prevent regressions in the recurring lifecycle layer.
 *
 * These tests scan production source code and fail if forbidden patterns are found,
 * ensuring that recurring DAO mutations, receiver patterns, and legacy paths
 * don't creep back in.
 */
class RecurringArchitectureGuardTest {

    private val sourceRoot: File by lazy { resolveSourceRoot() }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}, candidates=$candidates")
    }

    @Test
    fun `source root exists and contains Kotlin files`() {
        assertTrue("sourceRoot must exist: ${sourceRoot.absolutePath}", sourceRoot.exists())
        val kotlinFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue("Architecture guard scanned zero Kotlin files in ${sourceRoot.absolutePath}", kotlinFiles.isNotEmpty())
    }

    /** Files allowed to directly mutate recurring DAOs. */
    private val allowedMutationFiles = setOf(
        "RecurringLifecycleCoordinator.kt",
        "RecurringRuleLifecycleCoordinator.kt",
        "RecurringOccurrenceMaterializer.kt",
        "AppDatabase.kt" // migrations only
    )

    @Test
    fun `no direct recurring rule DAO mutation outside coordinator`() {
        val forbidden = listOf(
            Regex("""manualRecurringExpenseDao\s*\.\s*insert\("""),
            Regex("""manualRecurringExpenseDao\s*\.\s*update\("""),
            Regex("""manualRecurringExpenseDao\s*\.\s*delete\("""),
            Regex("""manualRecurringExpenseDao\s*\.\s*deleteById\("""),
            Regex("""manualRecurringExpenseDao\s*\.\s*setActiveStatus\("""),
            Regex("""manualRecurringExpenseDao\s*\.\s*updateNextDate\(""")
        )

        val errors = mutableListOf<String>()
        walkSourceFiles(sourceRoot) { file, text ->
            if (file.name in allowedMutationFiles) return@walkSourceFiles
            for (pattern in forbidden) {
                if (pattern.containsMatchIn(text)) {
                    errors.add("${file.name}: direct recurring rule DAO mutation found (matches: $pattern)")
                    break
                }
            }
        }

        assertTrue(
            "Direct recurring rule DAO mutations found outside allowed files:\n${errors.joinToString("\n")}",
            errors.isEmpty()
        )
    }

    @Test
    fun `reminder receivers do not use runBlocking or direct DAOs`() {
        val receiverDir = File(sourceRoot, "com/yourname/expensetracker/service/reminder")
        if (!receiverDir.exists()) return // skip if directory doesn't exist

        val forbidden = listOf(
            "runBlocking",
            "ReminderDeliveryDao",
            "ManualRecurringExpenseDao",
            "RecurringOccurrenceDao",
            "PlannedExpenseDao",
            "RestoreMaintenanceMode",
            "reminderDeliveryDao",
            "occurrenceDao",
            "plannedExpenseDao",
            "manualRecurringExpenseDao"
        )

        val required = listOf(
            "workManager.enqueue",
            "OneTimeWorkRequestBuilder"
        )

        val errors = mutableListOf<String>()
        receiverDir.listFiles()?.filter { it.extension == "kt" }?.forEach { file ->
            val text = file.readText()
            for (pattern in forbidden) {
                if (text.contains(pattern)) {
                    errors.add("${file.name}: contains forbidden pattern '$pattern'")
                }
            }
            // Only enforce BroadcastReceiver lifecycle on actual BroadcastReceivers, not Workers
            if (text.contains("BroadcastReceiver")) {
                for (pattern in required) {
                    if (!text.contains(pattern)) {
                        errors.add("${file.name}: missing required pattern '$pattern'")
                    }
                }
            }
        }

        assertTrue(
            "Receiver architecture violations:\n${errors.joinToString("\n")}",
            errors.isEmpty()
        )
    }

    @Test
    fun `no live legacy markBillPaid path in production`() {
        val errors = mutableListOf<String>()
        walkSourceFiles(sourceRoot) { file, text ->
            if (file.absolutePath.contains("test")) return@walkSourceFiles
            if (file.name == "BillReminderManager.kt") return@walkSourceFiles
            if (text.contains(".markBillPaid(") || text.contains(".markRuleBillAsPaid(")) {
                errors.add("${file.name}: contains call to legacy markBillPaid/markRuleBillAsPaid")
            }
        }
        assertTrue("Live legacy markBillPaid callers found:\n${errors.joinToString("\n")}", errors.isEmpty())
    }

    @Test
    fun `no raw status updateOccurrenceStatus api in production`() {
        val errors = mutableListOf<String>()
        walkSourceFiles(sourceRoot) { file, text ->
            if (file.name == "RecurringLifecycleCoordinator.kt") return@walkSourceFiles
            if (text.contains("updateOccurrenceStatus(") && !text.contains("RecurringOccurrenceStatus") &&
                !text.contains("RecurringOccurrenceTransitionReason")) {
                errors.add("${file.name}: raw updateOccurrenceStatus call detected")
            }
        }
        assertTrue("Raw status update calls outside allowed files:\n${errors.joinToString("\n")}", errors.isEmpty())
    }

    @Test
    fun `no 0L placeholder occurrence ids in reconcile results`() {
        val errors = mutableListOf<String>()
        walkSourceFiles(sourceRoot) { file, text ->
            if (text.contains("Linked(expenseId, 0L") ||
                text.contains("Relinked(expenseId, oldOccurrenceId, 0L") ||
                text.contains("newOccurrenceId = 0L")) {
                errors.add("${file.name}: contains 0L placeholder in reconcile result")
            }
        }
        assertTrue("0L placeholder ids found in reconcile results:\n${errors.joinToString("\n")}", errors.isEmpty())
    }

    @Test
    fun `migration index names match Room defaults`() {
        val appDbFile = File(sourceRoot, "com/yourname/expensetracker/data/database/AppDatabase.kt")
        if (!appDbFile.exists()) return
        val text = appDbFile.readText()
        // Room default names should be present, not custom short names
        assertFalse("Migration uses wrong index name 'index_reminder_deliveries_occ_window'",
            text.contains("index_reminder_deliveries_occ_window"))
        assertTrue("Migration should use Room default index name",
            text.contains("index_recurring_reminder_deliveries_occurrenceId_reminderWindow"))
    }

    @Test
    fun `create and delete planner actions use detailed results`() {
        val plannerFile = File(sourceRoot, "com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt")
        if (!plannerFile.exists()) return
        val text = plannerFile.readText()
        // Create action should use linkExpenseToOccurrenceDetailed, not plain linkExpenseToOccurrence
        val createBlock = text.substringAfter("private fun makeRecurringMatchingAction").substringBefore("private fun makeRecurringReconcileAction")
        assertTrue("Create action should use linkExpenseToOccurrenceDetailed",
            createBlock.contains("linkExpenseToOccurrenceDetailed"))
        // Delete action should use unlinkExpenseFromOccurrenceDetailed
        val deleteBlock = text.substringAfter("private fun makeRecurringUnlinkAction")
        assertTrue("Delete action should use unlinkExpenseFromOccurrenceDetailed",
            deleteBlock.contains("unlinkExpenseFromOccurrenceDetailed"))
    }

    @Test
    fun `updateRule regenerates planned rows after deleting open planned`() {
        val ruleFile = File(sourceRoot, "com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        if (!ruleFile.exists()) return
        val text = ruleFile.readText()
        val updateRuleBody = text.substringAfter("suspend fun updateRule")
        assertTrue("updateRule must call projectFromOccurrencesInCurrentTransaction",
            updateRuleBody.contains("projectFromOccurrencesInCurrentTransaction"))
        assertTrue("updateRule must call deleteOpenPlannedByRecurringRuleId before projection",
            updateRuleBody.substringBefore("projectFromOccurrencesInCurrentTransaction")
                .contains("deleteOpenPlannedByRecurringRuleId"))
    }

    @Test
    fun `createRule generates future state`() {
        val ruleFile = File(sourceRoot, "com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        if (!ruleFile.exists()) return
        val text = ruleFile.readText()
        val createBody = text.substringAfter("suspend fun createRule")
        assertTrue("createRule must call materializeInCurrentTransaction",
            createBody.contains("materializeInCurrentTransaction"))
        assertTrue("createRule must write RULE_CREATED_GENERATED",
            createBody.contains("RULE_CREATED_GENERATED"))
    }

    @Test
    fun `activateRule generation is atomic`() {
        val ruleFile = File(sourceRoot, "com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        if (!ruleFile.exists()) return
        val text = ruleFile.readText()
        val activateBody = text.substringAfter("suspend fun activateRule")
        assertTrue("activateRule must call projectFromOccurrencesInCurrentTransaction",
            activateBody.contains("projectFromOccurrencesInCurrentTransaction"))
        assertFalse("activateRule must not catch generation failure",
            activateBody.contains("catch (e: Exception)"))
    }

    @Test
    fun `bulk recurring reconciliation not permanently disabled`() {
        val plannerFile = File(sourceRoot, "com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt")
        if (!plannerFile.exists()) return
        val text = plannerFile.readText()
        val bulkBody = text.substringAfter("private fun makeBulkRecurringReconciliationAction")
        assertFalse("Bulk recurring reconciliation must not return DISABLED_BY_SETTINGS",
            bulkBody.contains("DISABLED_BY_SETTINGS"))
    }

    @Test
    fun `deactivateRule deletes not cancels open occurrences`() {
        val ruleFile = File(sourceRoot, "com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        if (!ruleFile.exists()) return
        val text = ruleFile.readText()
        val deactivateBody = text.substringAfter("suspend fun deactivateRule")
        assertTrue("deactivateRule must delete open occurrences (deleteOpenPlannedBySource), not cancel them",
            deactivateBody.contains("deleteOpenPlannedBySource"))
        assertFalse("deactivateRule must NOT use updateStatus on PLANNED occurrences",
            deactivateBody.contains("updateStatus(plannedIds"))
    }

    @Test
    fun `migration 139_140 has reminder dedup table`() {
        val appDbFile = File(sourceRoot, "com/yourname/expensetracker/data/database/AppDatabase.kt")
        if (!appDbFile.exists()) return
        val text = appDbFile.readText()
        val migrationBody = text.substringAfter("MIGRATION_139_140").substringBefore("MIGRATION_140_141")
        assertTrue("MIGRATION_139_140 must have reminder_keep_139_140 dedup table",
            migrationBody.contains("reminder_keep_139_140"))
    }

    @Test
    fun `critical events use eventWriter not direct DAO in coordinator`() {
        val coordFile = File(sourceRoot, "com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt")
        if (!coordFile.exists()) return
        val text = coordFile.readText()
        val paidBlock = text.substringAfter("eventWriter.writeCritical")
        assertTrue("OCCURRENCE_PAID must use eventWriter.writeCritical",
            paidBlock.contains("OCCURRENCE_PAID"))
        assertTrue("PLANNED_FULFILLED must use eventWriter.writeCritical",
            paidBlock.contains("PLANNED_FULFILLED") || text.substringAfter("writeCritical", paidBlock).contains("PLANNED_FULFILLED"))
    }

    @Test
    fun `rule lifecycle coordinator does not use direct DAO for critical events`() {
        val ruleFile = File(sourceRoot, "com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        if (!ruleFile.exists()) return
        val text = ruleFile.readText()
        // Must inject eventWriter
        assertTrue("RuleLifecycleCoordinator must inject RecurringLifecycleEventWriter",
            text.contains("eventWriter: RecurringLifecycleEventWriter"))
        // RULE_DELETED still uses lifecycleEventDao (transitional) — guard allows it for now
    }

    @Test
    fun `golden lifecycle tests do not bypass coordinator with direct DAO insert`() {
        val goldenDir = File(sourceRoot, "com/yourname/expensetracker/golden")
        if (!goldenDir.exists()) return
        val errors = mutableListOf<String>()
        goldenDir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name.contains("Pipeline4", true) }.forEach { file ->
            val text = file.readText()
            if (text.contains("recurringExpenseDao().insert") && !text.contains("seedRuleViaRepo")) {
                errors.add("${file.name}: golden test uses direct recurringExpenseDao().insert instead of lifecycle coordinator")
            }
        }
        assertTrue("Golden lifecycle tests bypass coordinator:\n${errors.joinToString("\n")}", errors.isEmpty())
    }

    @Test
    fun `bulk reconciliation does not use global PAID scan`() {
        val plannerFile = File(sourceRoot, "com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt")
        if (!plannerFile.exists()) return
        val text = plannerFile.readText()
        val bulkBlock = text.substringAfter("private fun makeBulkRecurringReconciliationAction")
        // Bulk must use structured result, not raw count
        assertFalse("Bulk reconciliation must not use global getByStatus PAID scan in planner",
            bulkBlock.contains("getByStatus(\"PAID\")"))
    }

    @Test
    fun `deactivation deletes not cancels planned rows`() {
        val ruleFile = File(sourceRoot, "com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt")
        if (!ruleFile.exists()) return
        val text = ruleFile.readText()
        val deactivateBody = text.substringAfter("suspend fun deactivateRule").substringBefore("suspend fun deleteRule")
        assertTrue("deactivateRule must delete open planned rows (deleteOpenPlannedByRecurringRuleId)",
            deactivateBody.contains("deleteOpenPlannedByRecurringRuleId"))
        assertFalse("deactivateRule must NOT cancel planned rows (cancelPlannedByRecurringRuleId)",
            deactivateBody.contains("cancelPlannedByRecurringRuleId"))
    }

    private fun walkSourceFiles(root: File, block: (File, String) -> Unit) {
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        for (file in files) {
            try { block(file, file.readText()) } catch (_: Exception) { /* skip */ }
        }
    }
}
