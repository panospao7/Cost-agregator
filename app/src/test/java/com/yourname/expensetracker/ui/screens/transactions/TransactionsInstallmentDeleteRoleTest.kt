package com.yourname.expensetracker.ui.screens.transactions

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * loan-installments PE-D: the role line of the Transactions delete dialog (ux-flows 3.7, spec 7.6).
 * Pure: the role comes from the row marks, with the stored flag as the fallback, and needs no plan read.
 */
class TransactionsInstallmentDeleteRoleTest {

    private fun expense(expenseId: Long, exclusion: SpendingExclusionReason? = null): Expense = mockk {
        every { id } returns expenseId
        // The entity stores the enum NAME as a String?.
        every { spendingExclusionReason } returns exclusion?.name
    }

    private fun marksOf(vararg marks: Pair<Long, InstallmentRowMark>) = InstallmentRowMarks(byExpenseId = mapOf(*marks))

    @Test
    fun `a purchase record mark is the PURCHASE role`() {
        val marks = marksOf(10L to InstallmentRowMark.PurchaseRecord(planId = 3L, installmentCount = 4))
        assertEquals(InstallmentDeleteRole.PURCHASE, InstallmentDeleteRole.of(expense(10L), marks))
    }

    @Test
    fun `a paid regular installment mark is the INSTALLMENT role`() {
        val marks = marksOf(11L to InstallmentRowMark.Payment(planId = 3L, seq = 1, total = 4, reversed = false))
        assertEquals(InstallmentDeleteRole.INSTALLMENT, InstallmentDeleteRole.of(expense(11L), marks))
    }

    @Test
    fun `a PAYOFF payment counts as an INSTALLMENT`() {
        val marks = marksOf(
            12L to InstallmentRowMark.Payment(planId = 3L, seq = 0, total = 4, reversed = false, showSequence = false)
        )
        assertEquals(InstallmentDeleteRole.INSTALLMENT, InstallmentDeleteRole.of(expense(12L), marks))
    }

    @Test
    fun `a reversed payment mark is the REVERSED role`() {
        val marks = marksOf(13L to InstallmentRowMark.Payment(planId = 3L, seq = 2, total = 4, reversed = true))
        assertEquals(InstallmentDeleteRole.REVERSED, InstallmentDeleteRole.of(expense(13L), marks))
    }

    @Test
    fun `the mark wins over the stored flag`() {
        // A purchase record whose flag reads REVERSED (refunded plan) is still the purchase of the plan.
        val marks = marksOf(10L to InstallmentRowMark.PurchaseRecord(planId = 3L, installmentCount = 4))
        assertEquals(
            InstallmentDeleteRole.PURCHASE,
            InstallmentDeleteRole.of(expense(10L, SpendingExclusionReason.INSTALLMENT_REVERSED), marks)
        )
    }

    @Test
    fun `with no mark the stored flag is the fallback`() {
        assertEquals(
            InstallmentDeleteRole.PURCHASE,
            InstallmentDeleteRole.of(expense(20L, SpendingExclusionReason.INSTALLMENT_PURCHASE), InstallmentRowMarks.EMPTY)
        )
        assertEquals(
            InstallmentDeleteRole.REVERSED,
            InstallmentDeleteRole.of(expense(21L, SpendingExclusionReason.INSTALLMENT_REVERSED), InstallmentRowMarks.EMPTY)
        )
    }

    @Test
    fun `an ordinary expense has no role, so the dialog is unchanged`() {
        assertNull(InstallmentDeleteRole.of(expense(30L), InstallmentRowMarks.EMPTY))
        // Marks exist for other expenses only.
        val marks = marksOf(11L to InstallmentRowMark.Payment(planId = 3L, seq = 1, total = 4, reversed = false))
        assertNull(InstallmentDeleteRole.of(expense(30L), marks))
    }

    @Test
    fun `a SCHEDULED or CANCELLED entry link is never a role (build produces no mark for it)`() {
        val plan = MarkPlanInput(
            planId = 3L,
            currencyCode = "EUR",
            purchaseExpenseId = null,
            entries = listOf(
                MarkEntryInput(1, InstallmentEntryKind.REGULAR, InstallmentEntryStatus.SCHEDULED, linkedExpenseId = 40L, dueEpochDay = 100L, scheduledMinor = 5_000L),
                MarkEntryInput(2, InstallmentEntryKind.REGULAR, InstallmentEntryStatus.CANCELLED, linkedExpenseId = 41L, dueEpochDay = 101L, scheduledMinor = 5_000L)
            )
        )
        val marks = InstallmentRowMarksLoader.build(listOf(plan))
        assertNull(InstallmentDeleteRole.of(expense(40L), marks))
        assertNull(InstallmentDeleteRole.of(expense(41L), marks))
    }

    @Test
    fun `each role maps to its own distinct message`() {
        assertEquals(R.string.transactions_installment_delete_role_purchase, InstallmentDeleteRole.PURCHASE.messageRes())
        assertEquals(R.string.transactions_installment_delete_role_installment, InstallmentDeleteRole.INSTALLMENT.messageRes())
        assertEquals(R.string.transactions_installment_delete_role_reversed, InstallmentDeleteRole.REVERSED.messageRes())
        assertNotEquals(InstallmentDeleteRole.PURCHASE.messageRes(), InstallmentDeleteRole.INSTALLMENT.messageRes())
        assertNotEquals(InstallmentDeleteRole.INSTALLMENT.messageRes(), InstallmentDeleteRole.REVERSED.messageRes())
    }
}
