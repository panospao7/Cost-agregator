package com.yourname.expensetracker.contracts

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Architecture contract: deactivateRule must atomically clean up future occurrences,
 * suppress reminders, and cancel planned expenses.
 */
class RecurringDeactivateContractTest {

    private val coordinatorFile = File(
        "app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt"
    )

    @Test
    fun `deactivateRule method exists`() {
        assertTrue("RecurringRuleLifecycleCoordinator.kt must exist", coordinatorFile.exists())
        val content = coordinatorFile.readText()
        assertTrue(
            "deactivateRule function must exist",
            content.contains("suspend fun deactivateRule")
        )
    }

    @Test
    fun `deactivateRule checks writeBarrier`() {
        val content = coordinatorFile.readText()
        val deactivateBlock = extractFunctionBlock(content, "deactivateRule")
        assertNotNull("Could not extract deactivateRule body", deactivateBlock)
        assertTrue(
            "deactivateRule must check writeBarrier",
            deactivateBlock!!.contains("writeBarrier")
        )
    }

    @Test
    fun `deactivateRule cancels future occurrences`() {
        val content = coordinatorFile.readText()
        val deactivateBlock = extractFunctionBlock(content, "deactivateRule")
        assertNotNull("Could not extract deactivateRule body", deactivateBlock)
        assertTrue(
            "deactivateRule must cancel planned occurrences (updateStatus to CANCELLED)",
            deactivateBlock!!.contains("CANCELLED") || deactivateBlock.contains("cancel")
        )
    }

    @Test
    fun `deactivateRule suppresses reminders`() {
        val content = coordinatorFile.readText()
        val deactivateBlock = extractFunctionBlock(content, "deactivateRule")
        assertNotNull("Could not extract deactivateRule body", deactivateBlock)
        assertTrue(
            "deactivateRule must delete/suppress reminders",
            deactivateBlock!!.contains("reminderDeliveryDao") ||
                    deactivateBlock.contains("deleteByOccurrence")
        )
    }

    @Test
    fun `deactivateRule cancels planned expenses`() {
        val content = coordinatorFile.readText()
        val deactivateBlock = extractFunctionBlock(content, "deactivateRule")
        assertNotNull("Could not extract deactivateRule body", deactivateBlock)
        assertTrue(
            "deactivateRule must cancel planned expenses",
            deactivateBlock!!.contains("plannedExpenseDao") ||
                    deactivateBlock.contains("cancelPlanned")
        )
    }

    private fun extractFunctionBlock(source: String, functionName: String): String? {
        val startIdx = source.indexOf("fun $functionName(")
        if (startIdx == -1) return null
        val braceStart = source.indexOf('{', startIdx)
        if (braceStart == -1) return null
        var depth = 0
        for (i in braceStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(braceStart, i + 1)
                }
            }
        }
        return null
    }
}
