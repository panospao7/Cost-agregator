package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.domain.currency.CurrencyUnsetReason
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFacts
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentResidualPlacement
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.installment.schedule.InstallmentScheduleCalculator
import com.yourname.expensetracker.domain.installment.schedule.InstallmentScheduleRequest
import com.yourname.expensetracker.domain.installment.schedule.ScheduleGenerationResult
import com.yourname.expensetracker.ui.screens.installments.actions.InstallmentAmountInput
import com.yourname.expensetracker.ui.screens.installments.declare.DeclareForm
import com.yourname.expensetracker.ui.util.AmountInputSanitizer
import java.time.LocalDate
import java.time.ZoneId

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
 * Loan-installments S11c: the live preview card of the Add expense installments section (ux-flows
 * 3.1 item 7). Computed by [AddExpenseInstallmentsDerivation] with the domain
 * [InstallmentScheduleCalculator]; the UI only formats it (no money math in the composable).
 * Minor units plus an explicit currency; unavailable is a distinct case, never zero.
 */
sealed interface AddExpenseInstallmentPreview {
    /** Installments off, or the plan terms are not yet valid enough to preview (the field shows its own error). */
    data object Hidden : AddExpenseInstallmentPreview

    /** The purchase amount is empty or invalid: "Enter the purchase amount first" (never "0.00 x 12"). */
    data object NeedsAmount : AddExpenseInstallmentPreview

    /** The calculator refused the terms; only the bounded code is kept (mapped to a fixed string). */
    data class Refused(val code: InstallmentRefusalCode) : AddExpenseInstallmentPreview

    /**
     * [firstPaymentMinor] differs from [regularPaymentMinor] only for an equal split with a residual
     * (placed on the first payment, OD-6). [financingCostMinor] is set for a fixed payment only
     * (total of installments minus purchase; negative is legitimate), else null (not applicable).
     * [pastDueCount] is the number of payments due strictly before today in the entry zone.
     */
    data class Ready(
        val count: Int,
        val currency: InstallmentCurrency,
        val firstPaymentMinor: Long,
        val regularPaymentMinor: Long,
        val totalMinor: Long,
        val lastDueEpochDay: Long,
        val financingCostMinor: Long?,
        val pastDueCount: Int
    ) : AddExpenseInstallmentPreview {
        val hasResidual: Boolean get() = firstPaymentMinor != regularPaymentMinor
    }
}

/**
 * Loan-installments S11c: zone-dependent values derived from the form, exposed by the view model
 * (`AddExpenseViewModel.installmentsDerived`). [defaultFirstDueEpochDay] is the purchase civil date + 1
 * month; [effectiveFirstDueEpochDay] is the explicit pick or that default; [firstDueBeforePurchase] is
 * true when the effective first payment lies before the purchase civil date. All use the entry zone
 * (the sheet-session zone, else the device default), never a zone read by the UI.
 */
data class AddExpenseInstallmentsDerived(
    val defaultFirstDueEpochDay: Long,
    val effectiveFirstDueEpochDay: Long,
    val firstDueBeforePurchase: Boolean,
    val preview: AddExpenseInstallmentPreview
)

/** Pure derivation of [AddExpenseInstallmentsDerived]; the only place of the "+1 month" default rule. */
object AddExpenseInstallmentsDerivation {

    /** The default first payment: the purchase civil date plus one month. */
    fun defaultFirstDueEpochDay(purchaseEpochDay: Long): Long =
        LocalDate.ofEpochDay(purchaseEpochDay).plusMonths(1).toEpochDay()

    /**
     * @param zone the entry zone (sheet-session zone, else the device default)
     * @param todayEpochDay today's civil date in [zone] (injected clock), for the past-due count
     */
    fun derive(state: AddExpenseState, zone: ZoneId, todayEpochDay: Long): AddExpenseInstallmentsDerived {
        val plan = state.installments
        val purchaseDay = InstallmentScheduleCalculator.civilEpochDay(state.date, zone)
        val defaultFirst = defaultFirstDueEpochDay(purchaseDay)
        val effectiveFirst = plan.firstDueEpochDay ?: defaultFirst
        return AddExpenseInstallmentsDerived(
            defaultFirstDueEpochDay = defaultFirst,
            effectiveFirstDueEpochDay = effectiveFirst,
            firstDueBeforePurchase = effectiveFirst < purchaseDay,
            preview = if (plan.on) preview(state, effectiveFirst, todayEpochDay) else AddExpenseInstallmentPreview.Hidden
        )
    }

    private fun preview(state: AddExpenseState, firstDue: Long, todayEpochDay: Long): AddExpenseInstallmentPreview {
        val plan = state.installments
        val count = plan.count ?: return AddExpenseInstallmentPreview.Hidden
        val currency = state.selectedCurrency?.let { InstallmentCurrency.fromCodeOrNull(it) }
            ?: return AddExpenseInstallmentPreview.Hidden
        val equal = plan.split == InstallmentSplitMethod.EQUAL_SPLIT
        if (!equal && plan.fixedMinor == null) return AddExpenseInstallmentPreview.Hidden
        val amount = AmountInputSanitizer.parseOrNull(state.amount)?.takeIf { it > 0.0 }
            ?: return AddExpenseInstallmentPreview.NeedsAmount
        val purchaseMinor = InstallmentMinorUnits.toMinorOrNull(amount)
            ?: return AddExpenseInstallmentPreview.NeedsAmount
        val result = InstallmentScheduleCalculator.generate(
            InstallmentScheduleRequest(
                splitMethod = plan.split,
                currency = currency.code,
                installmentCount = count,
                declaredTotal = if (equal) amount else null,
                fixedPaymentAmount = if (equal) null else plan.fixedMinor?.let(InstallmentAmountInput::toAmount),
                firstDueEpochDay = firstDue,
                residualPlacement = InstallmentResidualPlacement.FIRST,
                declaredOnEpochDay = todayEpochDay,
                // null: the before-purchase rule is surfaced by [AddExpenseInstallmentsDerived.firstDueBeforePurchase],
                // so the card still shows the schedule beside that warning.
                purchaseEpochDay = null
            )
        )
        return when (result) {
            is ScheduleGenerationResult.Refused -> AddExpenseInstallmentPreview.Refused(result.code)
            is ScheduleGenerationResult.Success -> {
                val schedule = result.schedule
                AddExpenseInstallmentPreview.Ready(
                    count = count,
                    currency = currency,
                    firstPaymentMinor = schedule.entries.first().amountMinor,
                    regularPaymentMinor = schedule.entries.last().amountMinor,
                    totalMinor = schedule.totalMinor,
                    lastDueEpochDay = schedule.lastDueEpochDay,
                    financingCostMinor = if (equal) null else schedule.totalMinor - purchaseMinor,
                    pastDueCount = schedule.dueBeforeDeclaredCount
                )
            }
        }
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
