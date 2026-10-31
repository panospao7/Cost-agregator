package com.yourname.expensetracker.domain.installment.readmodel

import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S12b (B-2, INV-1, AE-24): [InstallmentPlanReadModel.budgetInclusion], the installment money
 * already counted in a budget period, per currency. Synthetic data; money is Long minor units and
 * dates are epoch millis.
 */
class InstallmentPlanReadModelBudgetInclusionTest {

    private val periodStart = 1_000_000L
    private val periodEnd = 2_000_000L
    private val inPeriod = 1_500_000L

    private fun entry(
        id: Long,
        status: InstallmentEntryStatus = InstallmentEntryStatus.PAID,
        linked: LinkedExpenseMoney? = LinkedExpenseMoney("EUR", 5_000L, 5_000L, 1_500_000L, 7L)
    ) = EntrySnapshot(
        entryId = id,
        seq = id.toInt(),
        originalScheduledMinor = null,
        linkedExpenseId = if (linked != null) 100L + id else null,
        statusReason = null,
        money = EntryMoney(InstallmentEntryKind.REGULAR, status, 20_000L, 5_000L, linked)
    )

    private fun plan(
        id: Long,
        categoryId: Long? = 7L,
        currency: InstallmentCurrency = InstallmentCurrency.EUR,
        entries: List<EntrySnapshot>
    ) = PlanSnapshot(
        planId = id,
        planUuid = "uuid-$id",
        categoryId = categoryId,
        displayName = "Synthetic plan $id",
        lenderLabel = null,
        kind = InstallmentPlanKind.PURCHASE_ATTACHED,
        fulfilmentMode = InstallmentFulfilmentMode.CONFIRM,
        status = InstallmentPlanStatus.ACTIVE,
        currency = currency,
        purchaseExpenseId = 500L + id,
        purchase = PurchaseMoney(currency.code, 60_000L),
        terminalEpochDay = null,
        entries = entries
    )

    private fun inclusion(
        plans: List<PlanSnapshot>,
        categoryId: Long? = 7L,
        unreadable: Int = 0,
        start: Long = periodStart,
        end: Long = periodEnd
    ) = InstallmentPlanReadModel.budgetInclusion(
        InstallmentBudgetInputs(plans, unreadable), start, end, categoryId
    )

    @Test
    fun `AE-24 a recorded installment in the period counts and nothing else does`() {
        val result = inclusion(
            listOf(
                plan(
                    1L,
                    entries = listOf(
                        entry(1L),
                        entry(2L, status = InstallmentEntryStatus.SCHEDULED, linked = null),
                        entry(3L, status = InstallmentEntryStatus.SKIPPED, linked = null)
                    )
                )
            )
        )

        assertTrue(result.complete)
        assertEquals(mapOf(InstallmentCurrency.EUR to 5_000L), result.minorByCurrency)
    }

    @Test
    fun `AE-24 the purchase record is not an entry and never counts`() {
        val result = inclusion(listOf(plan(1L, entries = listOf(entry(1L, InstallmentEntryStatus.SCHEDULED, null)))))

        assertTrue(result.complete)
        assertTrue(result.minorByCurrency.isEmpty())
    }

    @Test
    fun `charge date places the entry in the period start inclusive end exclusive`() {
        fun linkedAt(date: Long) = LinkedExpenseMoney("EUR", 5_000L, 5_000L, date, 7L)
        val result = inclusion(
            listOf(
                plan(
                    1L,
                    entries = listOf(
                        entry(1L, linked = linkedAt(periodStart)),
                        entry(2L, linked = linkedAt(periodEnd)),
                        entry(3L, linked = linkedAt(periodStart - 1L))
                    )
                )
            )
        )

        assertEquals(mapOf(InstallmentCurrency.EUR to 5_000L), result.minorByCurrency)
    }

    @Test
    fun `currencies stay separate and are never summed`() {
        val result = inclusion(
            listOf(
                plan(1L, entries = listOf(entry(1L))),
                plan(
                    2L,
                    currency = InstallmentCurrency.USD,
                    entries = listOf(entry(2L, linked = LinkedExpenseMoney("USD", 2_000L, 2_000L, inPeriod, 7L)))
                )
            )
        )

        assertEquals(5_000L, result.minorByCurrency[InstallmentCurrency.EUR])
        assertEquals(2_000L, result.minorByCurrency[InstallmentCurrency.USD])
        assertEquals(2, result.minorByCurrency.size)
    }

    @Test
    fun `an excluded expense contributes its effective zero and leaves no zero row`() {
        val result = inclusion(
            listOf(plan(1L, entries = listOf(entry(1L, linked = LinkedExpenseMoney("EUR", 5_000L, 0L, inPeriod, 7L)))))
        )

        assertTrue(result.complete)
        assertNull(result.minorByCurrency[InstallmentCurrency.EUR])
        assertTrue(result.minorByCurrency.isEmpty())
    }

    @Test
    fun `category budget counts only expenses of its category and the overall budget counts all`() {
        fun paidIn(category: Long?) = LinkedExpenseMoney("EUR", 5_000L, 5_000L, inPeriod, category)
        val plans = listOf(
            plan(1L, entries = listOf(entry(1L, linked = paidIn(7L)))),
            plan(2L, entries = listOf(entry(2L, linked = paidIn(8L)))),
            plan(3L, entries = listOf(entry(3L, linked = paidIn(null))))
        )

        assertEquals(5_000L, inclusion(plans, categoryId = 7L).minorByCurrency[InstallmentCurrency.EUR])
        assertEquals(15_000L, inclusion(plans, categoryId = null).minorByCurrency[InstallmentCurrency.EUR])
    }

    @Test
    fun `the expense category decides and the plan category is ignored`() {
        fun paidIn(category: Long?) = LinkedExpenseMoney("EUR", 5_000L, 5_000L, inPeriod, category)
        // Plan category 7, but its expense was recategorised to 8: it follows the expense.
        val moved = listOf(plan(1L, categoryId = 7L, entries = listOf(entry(1L, linked = paidIn(8L)))))
        // Plan without a category, expense in 7: counts in 7.
        val adopted = listOf(plan(2L, categoryId = null, entries = listOf(entry(2L, linked = paidIn(7L)))))

        assertTrue(inclusion(moved, categoryId = 7L).minorByCurrency.isEmpty())
        assertEquals(5_000L, inclusion(moved, categoryId = 8L).minorByCurrency[InstallmentCurrency.EUR])
        assertEquals(5_000L, inclusion(adopted, categoryId = 7L).minorByCurrency[InstallmentCurrency.EUR])
        assertTrue(inclusion(moved, categoryId = 8L).complete)
    }

    @Test
    fun `a paid entry with no readable expense makes the result incomplete never zero`() {
        val result = inclusion(listOf(plan(1L, entries = listOf(entry(1L, linked = null)))))

        assertFalse(result.complete)
        assertTrue(result.minorByCurrency.isEmpty())
    }

    @Test
    fun `a linked expense without a date or in an unsupported currency is incomplete`() {
        val noDate = inclusion(
            listOf(plan(1L, entries = listOf(entry(1L, linked = LinkedExpenseMoney("EUR", 5_000L, 5_000L, null, 7L)))))
        )
        val unsupported = inclusion(
            listOf(plan(1L, entries = listOf(entry(1L, linked = LinkedExpenseMoney("XXX", 5_000L, 5_000L, inPeriod, 7L)))))
        )

        assertFalse(noDate.complete)
        assertFalse(unsupported.complete)
    }

    @Test
    fun `an unreadable plan makes every inclusion incomplete`() {
        val result = inclusion(listOf(plan(1L, entries = listOf(entry(1L)))), unreadable = 1)

        assertFalse(result.complete)
    }

    @Test
    fun `a readable paid expense of another category does not taint a category budget`() {
        val result = inclusion(
            listOf(
                plan(1L, entries = listOf(entry(1L))),
                plan(2L, entries = listOf(entry(2L, linked = LinkedExpenseMoney("XXX", 1L, 1L, null, 8L))))
            ),
            categoryId = 7L
        )

        assertTrue(result.complete)
        assertEquals(5_000L, result.minorByCurrency[InstallmentCurrency.EUR])
    }

    @Test
    fun `a paid entry whose expense is missing has no known category so a category budget is incomplete`() {
        val result = inclusion(
            listOf(
                plan(1L, entries = listOf(entry(1L))),
                plan(2L, categoryId = 8L, entries = listOf(entry(2L, linked = null)))
            ),
            categoryId = 7L
        )

        assertFalse(result.complete)
    }
}
