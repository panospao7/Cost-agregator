package com.yourname.expensetracker.domain.installment.lifecycle

import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType

/**
 * The facts about a captured expense that the plan lifecycle coordinator needs (domain spec 7.0,
 * INV-5, INV-16, R1 to R5). Money is not part of it: the plan coordinator never reads or writes
 * an expense amount, it only decides whether an expense may be a purchase or a payment.
 *
 * @property dateMillis the expense timestamp; the caller converts it to a civil date in its own zone.
 * @property spendingExclusionReason the stored flag name, or null when the expense counts as spending.
 * @property linkedRecurringOccurrenceId the recurring occurrence that links this expense, or null.
 */
data class InstallmentExpenseFacts(
    val expenseId: Long,
    val currency: String,
    val dateMillis: Long,
    val isSpendingType: Boolean,
    val isNotMine: Boolean,
    val isSharedExpense: Boolean,
    val hasSplit: Boolean,
    val spendingExclusionReason: String?,
    val categoryId: Long?,
    val paymentMethod: PaymentMethod,
    val linkedRecurringOccurrenceId: Long?
) {
    companion object {
        /**
         * The single definition of "a spending type" for plan code (domain spec 7.0): only a
         * purchase can be a plan purchase or fulfil an installment. The reader implementation
         * and the pre-create check of a brand new purchase both use this function.
         */
        fun isSpendingType(type: TransactionType): Boolean = type == TransactionType.PURCHASE

        /**
         * The single definition of "has a split" for plan code: a split template or a non-blank
         * split visualization. The reader implementation and the pre-create check of a brand new
         * purchase both use this function.
         */
        fun hasSplit(splitTemplateId: Long?, splitVisualization: String?): Boolean =
            splitTemplateId != null || !splitVisualization.isNullOrBlank()
    }
}

/**
 * One expense that carries a spending-exclusion flag: its id and the stored flag name. Nothing
 * else about the expense is exposed (no text, no amount).
 */
data class InstallmentFlaggedExpense(
    val expenseId: Long,
    val reason: String
)

/**
 * Read-only port through which plan code looks at expenses, categories and recurring links.
 *
 * Plan code never touches the expense store or the recurring store directly (INV-24, INV-25);
 * the implementation lives in the data layer and only reads. The plan coordinator calls it inside
 * its own transaction, so a read sees the writes made earlier in the same transaction.
 */
interface InstallmentExpenseFactsReader {

    /** Facts of the expense, or null when it does not exist. */
    suspend fun findExpenseFacts(expenseId: Long): InstallmentExpenseFacts?

    /** true when a category with this id exists (INV-33: a plan never creates a category). */
    suspend fun categoryExists(categoryId: Long): Boolean

    /**
     * Every expense that carries any spending-exclusion flag (id and stored flag name), for the
     * orphan-flag repair of reconcile R1 and R3. The caller filters by flag name and re-checks
     * each candidate inside its own transaction before clearing anything.
     */
    suspend fun findFlaggedExpenses(): List<InstallmentFlaggedExpense>

    /**
     * The stored money of an expense for the read model (S6a part 2b), or null when it does not
     * exist. Read-only; the plan coordinator never calls it.
     */
    suspend fun findExpenseMoney(expenseId: Long): InstallmentExpenseMoney?

    /**
     * Expenses dated in [startMillisInclusive, endMillisExclusive) that the owner counts as theirs,
     * with their stored money, for the INV-13 suggestion scan. Read-only. The caller narrows by
     * currency, amount and eligibility.
     */
    suspend fun findExpenseMoneyBetween(startMillisInclusive: Long, endMillisExclusive: Long): List<InstallmentExpenseMoney>
}

/**
 * Stored money of one expense, for the read model only. Amounts are the stored Doubles; the read
 * repository converts them to minor units with `InstallmentMinorUnits.toMinorOrNull`.
 *
 * @property amount the gross `amount`.
 * @property effectiveAmount the stored `effectiveAmount` (what counts as spending).
 */
data class InstallmentExpenseMoney(
    val expenseId: Long,
    val currency: String,
    val dateMillis: Long,
    val amount: Double,
    val effectiveAmount: Double,
    /** The expense's own category, or null when uncategorised (S12b budget line). */
    val categoryId: Long? = null
)
