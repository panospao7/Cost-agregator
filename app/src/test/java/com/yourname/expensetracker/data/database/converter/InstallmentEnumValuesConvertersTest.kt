package com.yourname.expensetracker.data.database.converter

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.SourceLearningPolicy
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionUpdateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * IM-8 (loan-installments D-14): the new enum values `ExpenseSource.INSTALLMENT_PLAN` and
 * `TransactionUpdateKind.SPENDING_INCLUSION` are persisted and parsed by name, never by ordinal,
 * so they are appended last and no existing stored value can move. `Converters` has no converter
 * for these enums: `Expense.source` is plain TEXT and an update kind is never persisted, so the
 * round trip is the enum name and an unknown name is handled where it is parsed.
 */
class InstallmentEnumValuesConvertersTest {

    private val originalExpenseSources = listOf(
        "MANUAL_ENTRY", "MANUAL", "NOTIFICATION_AUTO_ACCEPT", "SMS_NOTIFICATION", "REVIEW_APPROVAL",
        "RECEIPT_SCAN", "RECEIPT_BATCH_REVIEW", "BANK_STATEMENT_REVIEW", "CSV_IMPORT", "EMAIL_RECEIPT",
        "GROUP_EXPENSE", "BANK_SYNC", "BANK_API_SYNC", "RECURRING_GENERATED", "DEBUG_TOOL",
        "MIGRATION", "UNKNOWN"
    )

    private val originalUpdateKinds = listOf(
        "FULL", "CATEGORY_ONLY", "LOCATION_ONLY", "BUSINESS_FLAGS_ONLY", "MERCHANT", "TYPE",
        "TRANSFER_DETAILS", "AMOUNT", "DATE", "CURRENCY", "OWNERSHIP", "PAYMENT_CORE"
    )

    @Test
    fun `ExpenseSource keeps every existing value in place and appends INSTALLMENT_PLAN last`() {
        val names = ExpenseSource.entries.map { it.name }

        assertEquals(originalExpenseSources, names.take(originalExpenseSources.size))
        assertEquals(originalExpenseSources.size + 1, names.size)
        assertEquals("INSTALLMENT_PLAN", names.last())
    }

    @Test
    fun `TransactionUpdateKind keeps every existing value in place and appends SPENDING_INCLUSION last`() {
        val names = TransactionUpdateKind.entries.map { it.name }

        assertEquals(originalUpdateKinds, names.take(originalUpdateKinds.size))
        assertEquals(originalUpdateKinds.size + 1, names.size)
        assertEquals("SPENDING_INCLUSION", names.last())
    }

    @Test
    fun `every ExpenseSource round trips through its name including INSTALLMENT_PLAN`() {
        ExpenseSource.entries.forEach { source ->
            assertEquals(source, ExpenseSource.valueOf(source.name))
        }
        assertEquals(ExpenseSource.INSTALLMENT_PLAN, ExpenseSource.valueOf("INSTALLMENT_PLAN"))
    }

    @Test
    fun `every TransactionUpdateKind round trips through its name including SPENDING_INCLUSION`() {
        TransactionUpdateKind.entries.forEach { kind ->
            assertEquals(kind, TransactionUpdateKind.valueOf(kind.name))
        }
        assertEquals(
            TransactionUpdateKind.SPENDING_INCLUSION,
            TransactionUpdateKind.valueOf("SPENDING_INCLUSION")
        )
    }

    @Test
    fun `the source and exclusion reason columns are stored as text not as ordinals`() {
        assertEquals(String::class.java, Expense::class.java.getDeclaredField("source").type)
        assertEquals(
            String::class.java,
            Expense::class.java.getDeclaredField("spendingExclusionReason").type
        )
    }

    @Test
    fun `an unknown stored source name fails valueOf with a bounded exception for callers that guard it`() {
        try {
            ExpenseSource.valueOf("A_FUTURE_SOURCE")
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // The JSON importer, the learning policy and the backfill worker all wrap valueOf.
        }
        assertNull(runCatching { ExpenseSource.valueOf("A_FUTURE_SOURCE") }.getOrNull())
    }

    @Test
    fun `learning policy treats INSTALLMENT_PLAN and unknown names as untrusted without throwing`() {
        assertFalse(SourceLearningPolicy.isTrustedForLearning(ExpenseSource.INSTALLMENT_PLAN))
        assertFalse(SourceLearningPolicy.isTrustedForLearning("INSTALLMENT_PLAN"))
        assertFalse(SourceLearningPolicy.isTrustedForLearning("A_FUTURE_SOURCE"))
    }

    @Test
    fun `SPENDING_INCLUSION never affects recurring matching or category learning`() {
        assertFalse(TransactionUpdateKind.SPENDING_INCLUSION.affectsRecurringMatch())
        assertFalse(TransactionUpdateKind.SPENDING_INCLUSION.involvesCategoryChange())
        // The pre-existing recurring-affecting kinds are unchanged.
        assertTrue(TransactionUpdateKind.FULL.affectsRecurringMatch())
        assertFalse(TransactionUpdateKind.CATEGORY_ONLY.affectsRecurringMatch())
    }

    @Test
    fun `SpendingExclusionReason names round trip and stay the two bounded values`() {
        assertEquals(
            listOf("INSTALLMENT_PURCHASE", "INSTALLMENT_REVERSED"),
            SpendingExclusionReason.entries.map { it.name }
        )
        SpendingExclusionReason.entries.forEach {
            assertEquals(it, SpendingExclusionReason.valueOf(it.name))
        }
    }
}
