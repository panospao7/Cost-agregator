package com.yourname.expensetracker.domain.schedule

/**
 * Bounded refusal codes of [ExpenseScheduleLinkPolicy]. Names are chosen to match the
 * installment feature's refusal codes one to one (loan-installments domain spec 7.0, INV-5,
 * INV-16) so the plan coordinator can map them without reinterpretation. This enum lives in the
 * schedule package so both sides consult one policy. The policy itself performs no I/O and has no
 * installment import. Since loan-installments S6c-2 the recurring side does depend on the
 * installment package, through the read-only port `InstallmentEntryLinkReader`
 * (`domain/installment/lifecycle`) plus the pure `InstallmentLinkRelations` /
 * `InstallmentExpenseFacts` types, and the helper `RecurringInstallmentEligibility`
 * (`domain/recurring`), which feed this policy.
 */
enum class ScheduleLinkRefusal {
    NOT_A_SPENDING_EXPENSE,
    NOT_MINE,
    SHARED_NOT_SUPPORTED,
    SPLIT_NOT_SUPPORTED,
    ALREADY_DECLARED,
    LINKED_ELSEWHERE,
    CURRENCY_MISMATCH
}

/** The kind of schedule item an expense is about to be linked to. */
enum class ScheduleLinkTarget {
    INSTALLMENT_ENTRY,
    RECURRING_OCCURRENCE
}

/**
 * Everything the policy needs to know about an expense's existing schedule relations. All fields
 * are read by the caller from its own store; the policy performs no I/O.
 *
 * @property linkedInstallmentEntryId id of the installment entry that already links this
 *   expense, or null.
 * @property linkedRecurringOccurrenceId id of the recurring occurrence that already links this
 *   expense, or null.
 * @property isPlanPurchaseRecord true when the expense is the purchase record of a plan (it is
 *   referenced as a plan's purchase, or carries the INSTALLMENT_PURCHASE exclusion flag).
 */
data class ExpenseScheduleRelations(
    val linkedInstallmentEntryId: Long? = null,
    val linkedRecurringOccurrenceId: Long? = null,
    val isPlanPurchaseRecord: Boolean = false
)

/** Facts about a prospective plan purchase (domain spec 7.0). */
data class PurchaseDeclarationFacts(
    /** true when the transaction type is a spending type (not income, transfer or refund). */
    val isSpendingType: Boolean,
    val isNotMine: Boolean,
    val isSharedExpense: Boolean,
    /** true when the expense carries a split template or a split visualisation. */
    val hasSplit: Boolean,
    /** The stored `spendingExclusionReason` text, or null when the expense counts as spending. */
    val spendingExclusionReason: String?,
    val relations: ExpenseScheduleRelations = ExpenseScheduleRelations()
)

/** Outcome of a schedule-link or declaration check. */
sealed interface ScheduleLinkDecision {
    /** The link or declaration may proceed. */
    data object Allowed : ScheduleLinkDecision

    /** The expense is already linked to exactly this target; callers treat it as success. */
    data object AlreadyLinkedToTarget : ScheduleLinkDecision

    /** Refused with a bounded code; nothing may be written. */
    data class Refused(val reason: ScheduleLinkRefusal) : ScheduleLinkDecision
}

/**
 * INV-5 schedule exclusivity: an expense is never more than one of
 * (a) linked to an installment entry, (b) linked to a recurring occurrence, (c) the purchase
 * record of an installment plan. This object is the only place that rule lives. The plan
 * coordinator consults it on link, confirm and declare; the recurring lifecycle consults it
 * before an auto-link or a manual link.
 *
 * Pure: no I/O, no clock, no Android. Currencies are compared as stored ISO 4217 text, exactly.
 */
object ExpenseScheduleLinkPolicy {

    /**
     * Whether an expense with [relations] may be linked to the given [target] item.
     *
     * - INSTALLMENT_ENTRY: refused with LINKED_ELSEWHERE when the expense is a plan purchase
     *   record, is linked to a recurring occurrence, or is linked to a different installment
     *   entry. Linking it to the same entry again is [ScheduleLinkDecision.AlreadyLinkedToTarget].
     * - RECURRING_OCCURRENCE: refused with LINKED_ELSEWHERE when the expense is a plan purchase
     *   record or is linked to an installment entry. Uniqueness between recurring occurrences
     *   stays with the recurring lifecycle and is deliberately not decided here.
     *
     * [targetId] is the id of the entry or occurrence being linked.
     */
    fun evaluateLink(
        target: ScheduleLinkTarget,
        targetId: Long,
        relations: ExpenseScheduleRelations
    ): ScheduleLinkDecision = when (target) {
        ScheduleLinkTarget.INSTALLMENT_ENTRY -> when {
            relations.isPlanPurchaseRecord -> refused(ScheduleLinkRefusal.LINKED_ELSEWHERE)
            relations.linkedRecurringOccurrenceId != null -> refused(ScheduleLinkRefusal.LINKED_ELSEWHERE)
            relations.linkedInstallmentEntryId == targetId -> ScheduleLinkDecision.AlreadyLinkedToTarget
            relations.linkedInstallmentEntryId != null -> refused(ScheduleLinkRefusal.LINKED_ELSEWHERE)
            else -> ScheduleLinkDecision.Allowed
        }

        ScheduleLinkTarget.RECURRING_OCCURRENCE -> when {
            relations.isPlanPurchaseRecord -> refused(ScheduleLinkRefusal.LINKED_ELSEWHERE)
            relations.linkedInstallmentEntryId != null -> refused(ScheduleLinkRefusal.LINKED_ELSEWHERE)
            else -> ScheduleLinkDecision.Allowed
        }
    }

    /**
     * Full check for linking or confirming an expense to an installment entry: the type check
     * (an expense that is no longer a spending type cannot fulfil an installment, R4), the
     * currency integrity rule (INV-16: expense currency must equal the plan currency) and the
     * exclusivity rule ([evaluateLink]).
     *
     * Order: type, then currency, then exclusivity. A re-link to the same entry is reported as
     * [ScheduleLinkDecision.AlreadyLinkedToTarget] only when type and currency still hold.
     */
    fun evaluateInstallmentLink(
        planCurrency: String,
        expenseCurrency: String,
        isSpendingType: Boolean,
        targetEntryId: Long,
        relations: ExpenseScheduleRelations
    ): ScheduleLinkDecision {
        if (!isSpendingType) return refused(ScheduleLinkRefusal.NOT_A_SPENDING_EXPENSE)
        if (planCurrency != expenseCurrency) return refused(ScheduleLinkRefusal.CURRENCY_MISMATCH)
        return evaluateLink(ScheduleLinkTarget.INSTALLMENT_ENTRY, targetEntryId, relations)
    }

    /**
     * Declaration preconditions on the purchase expense (domain spec 7.0), in spec order:
     * spending type, not-mine, shared, split, already declared, linked elsewhere. The first
     * failing rule wins. The expense must exist; that is checked by the caller in its transaction.
     */
    fun evaluateDeclaration(facts: PurchaseDeclarationFacts): ScheduleLinkDecision = when {
        !facts.isSpendingType -> refused(ScheduleLinkRefusal.NOT_A_SPENDING_EXPENSE)
        facts.isNotMine -> refused(ScheduleLinkRefusal.NOT_MINE)
        facts.isSharedExpense -> refused(ScheduleLinkRefusal.SHARED_NOT_SUPPORTED)
        facts.hasSplit -> refused(ScheduleLinkRefusal.SPLIT_NOT_SUPPORTED)
        facts.spendingExclusionReason != null || facts.relations.isPlanPurchaseRecord ->
            refused(ScheduleLinkRefusal.ALREADY_DECLARED)
        facts.relations.linkedInstallmentEntryId != null || facts.relations.linkedRecurringOccurrenceId != null ->
            refused(ScheduleLinkRefusal.LINKED_ELSEWHERE)
        else -> ScheduleLinkDecision.Allowed
    }

    /**
     * Convenience for the recurring lifecycle: true only when [relations] allow linking to a
     * recurring occurrence (see [evaluateLink]).
     */
    fun mayLinkToRecurringOccurrence(occurrenceId: Long, relations: ExpenseScheduleRelations): Boolean =
        evaluateLink(ScheduleLinkTarget.RECURRING_OCCURRENCE, occurrenceId, relations) == ScheduleLinkDecision.Allowed

    private fun refused(reason: ScheduleLinkRefusal): ScheduleLinkDecision = ScheduleLinkDecision.Refused(reason)
}
