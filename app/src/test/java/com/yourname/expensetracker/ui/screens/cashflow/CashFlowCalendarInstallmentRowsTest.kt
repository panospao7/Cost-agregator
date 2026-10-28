package com.yourname.expensetracker.ui.screens.cashflow

import com.yourname.expensetracker.domain.cashflow.CashFlowRiskLevel
import com.yourname.expensetracker.domain.cashflow.DailyCashFlow
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectedEntry
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * PE-C (F4): the CashFlow calendar installment dot and day-list rows. The dot is drawn exactly when
 * [DailyCashFlow.projectedInstallments] is non-empty; the rows come from the pure
 * [formatCashFlowInstallmentLines], which formats each amount in the PLAN currency from its minor
 * units, never converts, uses the localized fallback for a blank name and lists at most three.
 */
class CashFlowCalendarInstallmentRowsTest {

    private fun entry(
        id: Long,
        name: String = "Synthetic plan $id",
        currency: InstallmentCurrency = InstallmentCurrency.EUR,
        minor: Long = 5_000L
    ) = InstallmentProjectedEntry(
        planId = id,
        planUuid = "plan-uuid-$id",
        displayName = name,
        entryId = id,
        seq = id.toInt(),
        currency = currency,
        dueEpochDay = 20_000L,
        amountMinor = minor,
        recordGeneration = 0
    )

    private fun day(installments: List<InstallmentProjectedEntry>) = DailyCashFlow(
        date = Date(0L),
        startingBalance = 0.0,
        income = emptyList(),
        expenses = emptyList(),
        predictedRecurring = emptyList(),
        endingBalance = 0.0,
        riskLevel = CashFlowRiskLevel.NONE,
        currency = "EUR",
        projectedInstallments = installments
    )

    @Test
    fun `the dot condition holds only for a day with a projected installment`() {
        // CashFlowCalendarScreen draws the installment dot iff projectedInstallments is non-empty.
        assertFalse(day(emptyList()).projectedInstallments.isNotEmpty())
        assertTrue(day(listOf(entry(1L))).projectedInstallments.isNotEmpty())
    }

    @Test
    fun `no entries give no lines`() {
        assertTrue(formatCashFlowInstallmentLines(emptyList(), "Fallback").isEmpty())
    }

    @Test
    fun `a row shows the plan name and the amount in the plan currency`() {
        val lines = formatCashFlowInstallmentLines(
            listOf(entry(1L, name = "Synthetic sofa", currency = InstallmentCurrency.EUR, minor = 12_345L)),
            "Fallback"
        )

        assertEquals(listOf("• Synthetic sofa: -" + CurrencyFormatter.formatMoney(123.45, "EUR")), lines)
    }

    @Test
    fun `a USD plan is formatted in USD from its own minor units and never converted`() {
        val usd = formatCashFlowInstallmentLines(
            listOf(entry(2L, currency = InstallmentCurrency.USD, minor = 5_000L)),
            "Fallback"
        ).single()
        val eur = formatCashFlowInstallmentLines(
            listOf(entry(2L, currency = InstallmentCurrency.EUR, minor = 5_000L)),
            "Fallback"
        ).single()

        assertEquals("• Synthetic plan 2: -" + CurrencyFormatter.formatMoney(50.0, "USD"), usd)
        // Same minor units, different plan currency: only the currency rendering differs (no rate).
        assertNotEquals(usd, eur)
    }

    @Test
    fun `a blank plan name uses the fallback name`() {
        val lines = formatCashFlowInstallmentLines(
            listOf(entry(3L, name = ""), entry(4L, name = "   ")),
            "Synthetic fallback"
        )

        assertTrue(lines[0].startsWith("• Synthetic fallback: -"))
        assertTrue(lines[1].startsWith("• Synthetic fallback: -"))
    }

    @Test
    fun `a named plan keeps its own name over the fallback`() {
        val lines = formatCashFlowInstallmentLines(listOf(entry(5L, name = "Synthetic phone")), "Synthetic fallback")

        assertTrue(lines.single().startsWith("• Synthetic phone: -"))
    }

    @Test
    fun `at most three rows are listed in the given order`() {
        val lines = formatCashFlowInstallmentLines((1L..5L).map { entry(it) }, "Fallback")

        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("• Synthetic plan 1: -"))
        assertTrue(lines[2].startsWith("• Synthetic plan 3: -"))
    }

    @Test
    fun `mixed currencies stay separate rows and are never summed`() {
        val lines = formatCashFlowInstallmentLines(
            listOf(
                entry(6L, currency = InstallmentCurrency.EUR, minor = 1_000L),
                entry(7L, currency = InstallmentCurrency.USD, minor = 2_000L)
            ),
            "Fallback"
        )

        assertEquals(2, lines.size)
        assertEquals("• Synthetic plan 6: -" + CurrencyFormatter.formatMoney(10.0, "EUR"), lines[0])
        assertEquals("• Synthetic plan 7: -" + CurrencyFormatter.formatMoney(20.0, "USD"), lines[1])
    }
}
