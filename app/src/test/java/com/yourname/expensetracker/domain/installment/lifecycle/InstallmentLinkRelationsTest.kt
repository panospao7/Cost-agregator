package com.yourname.expensetracker.domain.installment.lifecycle

import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.domain.schedule.ExpenseScheduleRelations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InstallmentLinkRelations.forExistingExpense]: the single definition of the schedule relations
 * of an existing expense (INV-4, INV-5, INV-16). Pure; the coordinator (link) and the read
 * repository (suggestion eligibility) both use it, so these cases pin the shared behaviour.
 */
class InstallmentLinkRelationsTest {

    private fun facts(
        reason: String? = null,
        recurringOccurrenceId: Long? = null
    ) = InstallmentExpenseFacts(
        expenseId = 900L,
        currency = "EUR",
        dateMillis = 1_800_000_000_000L,
        isSpendingType = true,
        isNotMine = false,
        isSharedExpense = false,
        hasSplit = false,
        spendingExclusionReason = reason,
        categoryId = null,
        paymentMethod = PaymentMethod.UNKNOWN,
        linkedRecurringOccurrenceId = recurringOccurrenceId
    )

    @Test
    fun a_free_expense_has_no_link_and_is_not_a_purchase_record() {
        val relations = InstallmentLinkRelations.forExistingExpense(null, false, facts())

        assertEquals(ExpenseScheduleRelations(null, null, false), relations)
    }

    @Test
    fun entry_link_is_passed_through_for_any_entry_status() {
        val relations = InstallmentLinkRelations.forExistingExpense(
            linkedInstallmentEntryId = 41L,
            planPurchaseRecordExists = false,
            facts = facts()
        )

        assertEquals(41L, relations.linkedInstallmentEntryId)
        assertNull(relations.linkedRecurringOccurrenceId)
        assertFalse(relations.isPlanPurchaseRecord)
    }

    @Test
    fun recurring_link_is_read_from_the_facts() {
        val relations = InstallmentLinkRelations.forExistingExpense(null, false, facts(recurringOccurrenceId = 77L))

        assertEquals(77L, relations.linkedRecurringOccurrenceId)
        assertNull(relations.linkedInstallmentEntryId)
        assertFalse(relations.isPlanPurchaseRecord)
    }

    @Test
    fun a_plan_row_that_references_the_expense_makes_it_a_purchase_record() {
        val relations = InstallmentLinkRelations.forExistingExpense(null, true, facts())

        assertTrue(relations.isPlanPurchaseRecord)
    }

    @Test
    fun a_flag_only_purchase_is_a_purchase_record_even_without_a_plan_row() {
        val relations = InstallmentLinkRelations.forExistingExpense(null, false, facts(reason = "INSTALLMENT_PURCHASE"))

        assertTrue(relations.isPlanPurchaseRecord)
    }

    @Test
    fun the_reversed_flag_makes_the_expense_non_linkable_and_other_flags_alone_do_not() {
        assertTrue(
            InstallmentLinkRelations.forExistingExpense(null, false, facts(reason = "INSTALLMENT_REVERSED"))
                .isPlanPurchaseRecord
        )
        assertFalse(
            InstallmentLinkRelations.forExistingExpense(null, false, facts(reason = "SOMETHING_ELSE"))
                .isPlanPurchaseRecord
        )
    }

    @Test
    fun all_three_sources_combine_without_masking_each_other() {
        val relations = InstallmentLinkRelations.forExistingExpense(
            linkedInstallmentEntryId = 5L,
            planPurchaseRecordExists = true,
            facts = facts(reason = "INSTALLMENT_PURCHASE", recurringOccurrenceId = 9L)
        )

        assertEquals(ExpenseScheduleRelations(5L, 9L, true), relations)
    }
}
