package com.yourname.expensetracker.ui.screens.budget

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.budget.AdjustedSpendBreakdown
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AE-17 (INV-17): with no adjusted-spend breakdown the card carries NO spend, remaining,
 * over-budget amount, percent or health, so gross `status.spentAmount` can never be shown.
 */
class BudgetCardDisplayTest {

    private fun breakdown(effectiveSpend: Double) = AdjustedSpendBreakdown(
        personalSpend = effectiveSpend,
        sharedSpend = 0.0,
        reimbursedAmount = 0.0,
        netSharedLiability = 0.0,
        effectiveSpend = effectiveSpend,
        pendingReimbursements = 0.0
    )

    private fun status(
        spentAmount: Double = 80.0,
        effectiveLimit: Double = 100.0,
        percentUsed: Float = 0.8f,
        health: BudgetHealthStatus = BudgetHealthStatus.WARNING,
        adjusted: AdjustedSpendBreakdown? = null,
        percentKnown: Boolean = true
    ) = BudgetStatus(
        budget = Budget(
            id = 1L,
            categoryId = null,
            amount = effectiveLimit,
            period = BudgetPeriod.MONTHLY,
            startDate = 1_700_000_000_000L,
            notifyAtWarning = 0.75f,
            notifyAtCritical = 0.9f
        ),
        category = null,
        spentAmount = spentAmount,
        remainingAmount = (effectiveLimit - spentAmount).coerceAtLeast(0.0),
        percentUsed = percentUsed,
        healthStatus = health,
        periodStart = 1_700_000_000_000L,
        periodEnd = 1_702_592_000_000L,
        effectiveLimit = effectiveLimit,
        adjustedSpendBreakdown = adjusted,
        percentKnown = percentKnown
    )

    // ---- AE-17: null breakdown ----

    @Test
    fun `AE-17 null breakdown carries no spend remaining over-budget percent or health`() {
        val display = BudgetCardDisplay.from(status(spentAmount = 80.0, effectiveLimit = 100.0, adjusted = null))

        assertFalse(display.effectiveSpendAvailable)
        assertNull(display.spend)
        assertNull(display.remaining)
        assertNull(display.overBudgetAmount)
        assertNull(display.percentUsed)
        assertNull(display.healthStatus)
        assertEquals(BudgetCardRemainingSlot.UNAVAILABLE, display.remainingSlot)
    }

    @Test
    fun `AE-17 gross spentAmount never appears anywhere in the unavailable display`() {
        val display = BudgetCardDisplay.from(status(spentAmount = 80.0, adjusted = null))

        // The mapping is a plain data class: render every property and look for the gross value.
        assertFalse(display.toString().contains("80"))
    }

    @Test
    fun `AE-17 null breakdown over limit is still unavailable not an over-budget amount`() {
        val display = BudgetCardDisplay.from(
            status(
                spentAmount = 150.0,
                effectiveLimit = 100.0,
                percentUsed = 1.5f,
                health = BudgetHealthStatus.EXCEEDED,
                adjusted = null
            )
        )

        assertNull(display.overBudgetAmount)
        assertNull(display.spend)
        assertEquals(BudgetCardRemainingSlot.UNAVAILABLE, display.remainingSlot)
    }

    @Test
    fun `AE-17 null breakdown with unknown percent is still unavailable`() {
        val display = BudgetCardDisplay.from(
            status(percentUsed = 0f, health = BudgetHealthStatus.UNKNOWN, adjusted = null, percentKnown = false)
        )

        assertNull(display.percentUsed)
        assertNull(display.healthStatus)
        assertEquals(BudgetCardRemainingSlot.UNAVAILABLE, display.remainingSlot)
    }

    // ---- Available breakdown keeps today's behaviour ----

    @Test
    fun `available breakdown uses effective spend not gross spend`() {
        val display = BudgetCardDisplay.from(status(spentAmount = 80.0, adjusted = breakdown(effectiveSpend = 55.0)))

        assertTrue(display.effectiveSpendAvailable)
        assertEquals(55.0, display.spend!!, 0.0)
        assertEquals(45.0, display.remaining!!, 0.0)
        assertNull(display.overBudgetAmount)
        assertEquals(0.8f, display.percentUsed!!, 0.0f)
        assertEquals(BudgetHealthStatus.WARNING, display.healthStatus)
        assertEquals(BudgetCardRemainingSlot.REMAINING, display.remainingSlot)
    }

    @Test
    fun `available breakdown over budget shows the over amount from effective spend`() {
        val display = BudgetCardDisplay.from(
            status(
                spentAmount = 130.0,
                effectiveLimit = 100.0,
                percentUsed = 1.3f,
                health = BudgetHealthStatus.EXCEEDED,
                adjusted = breakdown(effectiveSpend = 130.0)
            )
        )

        assertEquals(30.0, display.overBudgetAmount!!, 0.0)
        assertEquals(BudgetCardRemainingSlot.OVER_BUDGET, display.remainingSlot)
        assertEquals(BudgetHealthStatus.EXCEEDED, display.healthStatus)
    }

    @Test
    fun `available breakdown with unknown percent hides the remaining slot as before`() {
        val display = BudgetCardDisplay.from(
            status(
                percentUsed = 0f,
                health = BudgetHealthStatus.UNKNOWN,
                adjusted = breakdown(effectiveSpend = 10.0),
                percentKnown = false
            )
        )

        assertEquals(BudgetCardRemainingSlot.HIDDEN, display.remainingSlot)
        assertNull(display.overBudgetAmount)
        assertTrue(display.effectiveSpendAvailable)
    }

    @Test
    fun `available breakdown with zero effective spend is a real zero not unavailable`() {
        val display = BudgetCardDisplay.from(status(spentAmount = 40.0, adjusted = breakdown(effectiveSpend = 0.0)))

        assertTrue(display.effectiveSpendAvailable)
        assertEquals(0.0, display.spend!!, 0.0)
        assertEquals(100.0, display.remaining!!, 0.0)
    }

    // ---- AE-17: visible text and content description carry no amount or percent ----

    @Test
    fun `AE-17 unavailable strings contain no digits or numeric placeholders in every locale`() {
        val keys = listOf(
            "budget_effective_spend_unavailable",
            "budget_remaining_unavailable",
            "budget_usage_unavailable",
            "budget_cd_spend_unavailable_format"
        )
        for (folder in listOf("values", "values-el", "values-es")) {
            val xml = readStringsXml(folder)
            for (key in keys) {
                val value = stringValue(xml, key)
                assertNotNull("$folder is missing $key", value)
                // Only %N$s placeholders (category name, active/inactive text) are allowed; strip them,
                // then no digit, no percent sign and no currency symbol may remain.
                val stripped = Regex("%\\d+\\$" + "s").replace(value!!, "")
                assertFalse("$folder/$key contains a digit: $value", stripped.any { it.isDigit() })
                assertFalse("$folder/$key contains a percent sign or numeric placeholder: $value", stripped.contains('%'))
                assertFalse("$folder/$key contains a currency symbol: $value", stripped.contains('€'))
            }
        }
    }

    @Test
    fun `AE-17 unavailable content description format takes only name and status text`() {
        for (folder in listOf("values", "values-el", "values-es")) {
            val value = stringValue(readStringsXml(folder), "budget_cd_spend_unavailable_format")!!
            val placeholders = Regex("%(\\d)\\$([a-z])").findAll(value).map { it.groupValues[1] to it.groupValues[2] }.toList()
            assertEquals("$folder placeholders: $value", listOf("1" to "s", "2" to "s"), placeholders.sortedBy { it.first })
        }
    }

    private fun readStringsXml(folder: String): String {
        val candidates = listOf(
            File("src/main/res/$folder/strings.xml"),
            File("app/src/main/res/$folder/strings.xml")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull("strings.xml not found for $folder", file)
        return file!!.readText(Charsets.UTF_8)
    }

    private fun stringValue(xml: String, name: String): String? =
        Regex("<string name=\"" + Regex.escape(name) + "\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
}
