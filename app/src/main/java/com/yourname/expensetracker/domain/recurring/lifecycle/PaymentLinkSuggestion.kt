package com.yourname.expensetracker.domain.recurring.lifecycle

/**
 * FC-PAID-UNLINKED: one suggested (occurrence, expense) pair, "this payment may already cover that
 * bill". In memory only, never persisted, and never read by a forecast, budget, savings or Radar
 * consumer (INV-1). Display names (merchant, rule name) are read live by the UI, not copied here.
 *
 * Money: amounts are the stored major units of the row's own currency. Nothing is rounded,
 * converted or summed.
 *
 * @property dayDelta calendar days from the due day to the expense day (device zone); positive when
 *   the expense is after the due day.
 * @property alternatives other open, pair-eligible occurrence ids for the same expense, best first.
 */
data class PaymentLinkSuggestion(
    val occurrenceId: Long,
    val ruleId: Long,
    val occurrenceKey: String,
    val dueDate: Long,
    val expectedAmount: Double,
    val expectedCurrency: String,
    val expenseId: Long,
    val expenseDate: Long,
    val paidAmount: Double,
    val paidCurrency: String,
    val dayDelta: Int,
    val alternatives: List<Long>
)

/** Bounded reason codes of [PaymentLinkSuggestions.Unavailable]. Never raw exception text. */
enum class PaymentLinkUnavailableReason {
    /** The restore or maintenance read barrier blocked the read. */
    READ_BLOCKED,

    /** Any other read error. */
    READ_FAILED
}

/**
 * Result of [RecurringLifecycleCoordinator.findPaymentLinkSuggestions]. [Unavailable] is never to be
 * shown as "no suggestions" (INV-11).
 */
sealed interface PaymentLinkSuggestions {
    data class Available(val items: List<PaymentLinkSuggestion>) : PaymentLinkSuggestions

    data class Unavailable(val reason: PaymentLinkUnavailableReason) : PaymentLinkSuggestions
}

/** Bounded failure codes of the confirm and decline results. */
object PaymentLinkFailureCodes {
    const val WRITE_BLOCKED = "WRITE_BLOCKED"
    const val INTERNAL = "INTERNAL"
}

/** Result of [RecurringLifecycleCoordinator.confirmPaymentLink]. */
sealed interface PaymentLinkConfirmResult {
    /** The pair was linked now (occurrence PAID). */
    data object Linked : PaymentLinkConfirmResult

    /** Idempotent retry: the expense is already linked to this occurrence. Nothing was written. */
    data object AlreadyLinkedToThis : PaymentLinkConfirmResult

    /** The pair no longer holds (INV-12 revalidation failed). Nothing was written. */
    data object NoLongerValid : PaymentLinkConfirmResult

    /** The expense is already linked to a different occurrence. Nothing was written. */
    data object ExpenseLinkedElsewhere : PaymentLinkConfirmResult

    /** [code] is one of [PaymentLinkFailureCodes]. */
    data class Failed(val code: String) : PaymentLinkConfirmResult
}

/** Result of [RecurringLifecycleCoordinator.declinePaymentLink]. */
sealed interface PaymentLinkDeclineResult {
    /** One LINK_SUGGESTION_DECLINED event was written. */
    data object Declined : PaymentLinkDeclineResult

    /** Idempotent retry: the pair was already declined. Nothing was written. */
    data object AlreadyDeclined : PaymentLinkDeclineResult

    /** The occurrence or the expense is missing, or the occurrence is not open. Nothing was written. */
    data object NoLongerValid : PaymentLinkDeclineResult

    /** [code] is one of [PaymentLinkFailureCodes]. */
    data class Failed(val code: String) : PaymentLinkDeclineResult
}
