package com.yourname.expensetracker.domain.installment.model

/**
 * OD-14 as a pure rule: an EQUAL_SPLIT plan divides the purchase amount, so it is only meaningful
 * when the plan currency IS the purchase currency. A plan in another currency can only be a
 * FIXED_PAYMENT plan (the user types the payment; nothing is converted).
 *
 * The coordinator enforces it on declareOnExpense, declareWithNewExpense and the currency change of
 * editDetails (refusal [InstallmentRefusalCode.CURRENCY_MISMATCH]); the declare sheet uses the same
 * function to enable the equal-split chip. One definition, no duplicated comparison.
 */
object InstallmentSplitCurrencyRule {

    /**
     * true only when both currency codes are known and equal. A null code (an unsupported or unknown
     * currency) is never allowed: fail closed. Codes are compared exactly; both sides are stored
     * upper-case ISO codes.
     */
    fun equalSplitAllowed(planCurrency: String?, purchaseCurrency: String?): Boolean =
        planCurrency != null && purchaseCurrency != null && planCurrency == purchaseCurrency
}
