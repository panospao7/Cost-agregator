package com.yourname.expensetracker.ui.util

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.AmountCheck
import com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy
import com.yourname.expensetracker.domain.model.UiText

/**
 * Single owner of the UI mapping for [CurrencyAmountPolicy] amount checks (MEC INV-12/13/14),
 * shared by Add Expense and the Transactions ownership edit (N-01-2). Pure; never rounds.
 */
object AmountCurrencyCheck {

    /**
     * Validates the parsed [amount] for [currency] with [CurrencyAmountPolicy] (Double overload).
     * Returns the inline error, or null when the amount may be saved. An unparsable currency code
     * skips the precision check and uses the default cap.
     */
    fun check(amount: Double, currency: String): UiText? {
        val code = CurrencyCode.parse(currency)
        if (code == null) {
            return if (amount > CurrencyAmountPolicy.DEFAULT_CAP.toDouble()) {
                amountTooLargeText(CurrencyAmountPolicy.DEFAULT_CAP)
            } else {
                null
            }
        }
        return when (val check = CurrencyAmountPolicy.check(amount, code)) {
            AmountCheck.Ok -> null
            // Not reachable after the positive check; fail closed with the generic invalid-amount error.
            AmountCheck.NotPositive -> UiText.StringResource(R.string.add_expense_error_amount_invalid)
            // OD-6: digits unknown. Never assume 2 digits or "no limit".
            AmountCheck.DigitsUnavailable -> UiText.StringResource(R.string.add_expense_error_amount_invalid)
            is AmountCheck.PrecisionExceeded -> precisionText(code.code, check.allowedDigits)
            is AmountCheck.AboveCap -> amountTooLargeText(check.cap)
        }
    }

    fun precisionText(currencyCode: String, allowedDigits: Int): UiText =
        if (allowedDigits == 0) {
            UiText.StringResource(R.string.mec_amount_no_decimals, listOf(currencyCode))
        } else {
            UiText.StringResource(R.string.mec_amount_max_decimals, listOf(currencyCode, allowedDigits))
        }

    private fun amountTooLargeText(cap: java.math.BigDecimal): UiText =
        UiText.StringResource(
            R.string.mec_amount_too_large,
            listOf(java.text.NumberFormat.getIntegerInstance().format(cap.toLong()))
        )
}
