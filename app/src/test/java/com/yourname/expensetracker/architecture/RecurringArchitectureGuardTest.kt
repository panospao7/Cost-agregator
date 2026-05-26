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

    private val sourceRoot = File("app/src/main/java")

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
            Regex("""manualRecurringExpenseDao\s*\.\s*updateNextDate\("""),
            // Also catch generic dao.X calls that shadow ManualRecurringExpenseDao
            Regex("""\bdao\s*\.\s*insert\("""),
            Regex("""\bdao\s*\.\s*update\("""),
            Regex("""\bdao\s*\.\s*delete\("""),
            Regex("""\bdao\s*\.\s*deleteById\("""),
            Regex("""\bdao\s*\.\s*setActiveStatus\("""),
            Regex("""\bdao\s*\.\s*updateNextDate\(""")
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
            "goAsync()",
            "pendingResult.finish()"
        )

        val errors = mutableListOf<String>()
        receiverDir.listFiles()?.filter { it.extension == "kt" }?.forEach { file ->
            val text = file.readText()
            for (pattern in forbidden) {
                if (text.contains(pattern)) {
                    errors.add("${file.name}: contains forbidden pattern '$pattern'")
                }
            }
            for (pattern in required) {
                if (!text.contains(pattern)) {
                    errors.add("${file.name}: missing required pattern '$pattern'")
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
            // Skip test files
            if (file.absolutePath.contains("test")) return@walkSourceFiles
            // The BillReminderManager itself is allowed to have the deprecated method
            if (file.name == "BillReminderManager.kt") return@walkSourceFiles

            if (text.contains(".markBillPaid(") || text.contains(".markRuleBillAsPaid(")) {
                errors.add("${file.name}: contains call to legacy markBillPaid/markRuleBillAsPaid")
            }
        }

        assertTrue(
            "Live legacy markBillPaid callers found:\n${errors.joinToString("\n")}",
            errors.isEmpty()
        )
    }

    private fun walkSourceFiles(root: File, block: (File, String) -> Unit) {
        root.walkTopDown().forEach { file ->
            if (file.isFile && file.extension == "kt") {
                try {
                    block(file, file.readText())
                } catch (_: Exception) {
                    // skip unreadable files
                }
            }
        }
    }
}
