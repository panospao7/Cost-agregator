package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFacts
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentAmountInput
import com.yourname.expensetracker.ui.screens.installments.declare.DeclareForm

/**
 * Loan-installments S11a: the session-only installment sub-state of the Add expense form. Held in
 * [AddExpenseState.installments]; cleared whole by `AddExpenseViewModel.reset()`, so every dismiss
 * path and a confirmed navigation discards it. Never persisted, never logged (no SavedStateHandle).
 *
 * [on] can become true only through `AddExpenseViewModel.setInstallmentsEnabled`, which is gated by
 * `InstallmentsFeature.ENABLED`. Everything else is a plan term the user typed or a default derived
 * at save time.
 *
 * - [nameText] null = the plan name follows the merchant (ux-flows: name defaults to the merchant).
 * - [firstDueEpochDay] null = the default, purchase civil date + 1 month (computed at save/preview).
 * - [planUuid] is generated once per session when installments are first switched on and reused by a
 *   retry after a refusal, so a double submit is idempotent (AE-15d). `reset()` clears it.
 * - [currencyUnset] is set while installment mode has no selectable currency (MEC UX-Q-6 / AE-28):
 *   the selection is `Unset`, never silently replaced.
 * - [repeatClearedByInstallments] is true when switching on turned Repeat off (helper text, S11b).
 */
data class AddExpenseInstallmentsState(
    val on: Boolean = false,
    val countText: String = DEFAULT_COUNT_TEXT,
    val split: InstallmentSplitMethod = InstallmentSplitMethod.EQUAL_SPLIT,
    val fixedText: String = "",
    val nameText: String? = null,
    val firstDueEpochDay: Long? = null,
    val mode: InstallmentFulfilmentMode = InstallmentFulfilmentMode.AUTO_RECORD,
    val planUuid: String? = null,
    val currencyUnset: CurrencyUnsetReason? = null,
    val repeatClearedByInstallments: Boolean = false
) {
    /** The installment count, or null when the text is not a whole number in the allowed range. */
    val count: Int?
        get() = countText.trim().toIntOrNull()?.takeIf { it in DeclareForm.MIN_COUNT..DeclareForm.MAX_COUNT }

    /** The fixed payment in minor units, or null when the text is not a valid positive amount. */
    val fixedMinor: Long? get() = InstallmentAmountInput.parseMinor(fixedText)

    /** The plan name: the typed name, else the merchant, trimmed and bounded. */
    fun effectiveName(merchant: String): String =
        (nameText ?: merchant).trim().take(DeclareForm.MAX_NAME_CHARS)

    companion object {
        const val DEFAULT_COUNT_TEXT: String = "12"
    }
}

/**
 * Why the installments switch is disabled for the current form (ux-flows 3.1): Not mine, Shared (which
 * includes a share split) or a non-spending transaction type. Null when the switch may be turned on.
 * The codes map to fixed strings through `declareMutationMessage` (`declare_installments_refused_*`).
 */
val AddExpenseState.installmentsBlockReason: InstallmentRefusalCode?
    get() = when {
        isNotMine -> InstallmentRefusalCode.NOT_MINE
        isSharedExpense -> InstallmentRefusalCode.SHARED_NOT_SUPPORTED
        !InstallmentExpenseFacts.isSpendingType(transactionType) -> InstallmentRefusalCode.NOT_A_SPENDING_EXPENSE
        else -> null
    }
