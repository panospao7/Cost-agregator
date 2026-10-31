package com.yourname.expensetracker.domain.installment.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OD-14 pure rule: an equal split needs the plan currency to be the purchase currency. */
class InstallmentSplitCurrencyRuleTest {

    @Test
    fun `the same currency allows an equal split`() {
        assertTrue(InstallmentSplitCurrencyRule.equalSplitAllowed("EUR", "EUR"))
        assertTrue(InstallmentSplitCurrencyRule.equalSplitAllowed("USD", "USD"))
    }

    @Test
    fun `a different currency never allows an equal split`() {
        assertFalse(InstallmentSplitCurrencyRule.equalSplitAllowed("EUR", "USD"))
        assertFalse(InstallmentSplitCurrencyRule.equalSplitAllowed("USD", "EUR"))
        assertFalse(InstallmentSplitCurrencyRule.equalSplitAllowed("EUR", "CHF"))
    }

    @Test
    fun `a null currency on either side fails closed`() {
        assertFalse(InstallmentSplitCurrencyRule.equalSplitAllowed(null, "EUR"))
        assertFalse(InstallmentSplitCurrencyRule.equalSplitAllowed("EUR", null))
        assertFalse(InstallmentSplitCurrencyRule.equalSplitAllowed(null, null))
    }

    @Test
    fun `an unsupported but equal code is allowed by the rule and left to the schedule checks`() {
        // UNSUPPORTED_CURRENCY is refused by the schedule calculator, not by this rule.
        assertTrue(InstallmentSplitCurrencyRule.equalSplitAllowed("CHF", "CHF"))
    }

    @Test
    fun `codes are compared exactly`() {
        assertFalse(InstallmentSplitCurrencyRule.equalSplitAllowed("eur", "EUR"))
        assertFalse(InstallmentSplitCurrencyRule.equalSplitAllowed("EUR ", "EUR"))
    }
}
