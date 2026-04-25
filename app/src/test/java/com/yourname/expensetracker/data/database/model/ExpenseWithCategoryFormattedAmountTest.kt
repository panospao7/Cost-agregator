package com.yourname.expensetracker.data.database.model

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Test
import java.util.Locale

/**
 * B.4-10: Verifies that [ExpenseWithCategory.formattedAmount] applies the correct
 * polarity prefix, uses effectiveAmount, and places currency before the number.
 *
 * The prior bug: a member property used "12.50 EUR" (no prefix, suffix currency,
 * Locale.US) while a shadowed extension used "-EUR12.50" (with prefix, prefix
 * currency, Locale.getDefault()). Only the member was reachable. The fix merges
 * the extension's richer behaviour into the member.
 */
class ExpenseWithCategoryFormattedAmountTest {

    // ---- Polarity prefix ----

    @Test
    fun `PURCHASE gets minus prefix`() {
        val ewc = makeEwc(transactionType = TransactionType.PURCHASE, amount = 25.50)
        assertThat(ewc.formattedAmount).startsWith("-")
        assertThat(ewc.formattedAmount).contains("25")
    }

    @Test
    fun `WITHDRAWAL gets minus prefix`() {
        val ewc = makeEwc(transactionType = TransactionType.WITHDRAWAL, amount = 100.0)
        assertThat(ewc.formattedAmount).startsWith("-")
    }

    @Test
    fun `DEPOSIT gets plus prefix`() {
        val ewc = makeEwc(transactionType = TransactionType.DEPOSIT, amount = 500.0)
        assertThat(ewc.formattedAmount).startsWith("+")
    }

    @Test
    fun `UNKNOWN gets no prefix`() {
        val ewc = makeEwc(transactionType = TransactionType.UNKNOWN, amount = 10.0)
        // Should not start with + or -
        assertThat(ewc.formattedAmount).doesNotMatch("^[+-].*")
    }

    @Test
    fun `TRANSFER gets no prefix`() {
        val ewc = makeEwc(transactionType = TransactionType.TRANSFER, amount = 10.0)
        assertThat(ewc.formattedAmount).doesNotMatch("^[+-].*")
    }

    // ---- Currency placement ----

    @Test
    fun `currency code appears before the numeric value`() {
        val ewc = makeEwc(currency = "EUR", amount = 42.0, transactionType = TransactionType.PURCHASE)
        // Expected: "-EUR42.00" (with locale-specific decimal, but EUR before digits)
        assertThat(ewc.formattedAmount).contains("EUR")
        val eurIndex = ewc.formattedAmount.indexOf("EUR")
        val digitIndex = ewc.formattedAmount.indexOfFirst { it.isDigit() }
        assertThat(eurIndex).isLessThan(digitIndex)
    }

    @Test
    fun `non-EUR currency is rendered correctly`() {
        val ewc = makeEwc(currency = "USD", amount = 99.99, transactionType = TransactionType.DEPOSIT)
        assertThat(ewc.formattedAmount).startsWith("+USD")
    }

    // ---- effectiveAmount usage ----

    @Test
    fun `isNotMine expense formats as zero`() {
        val ewc = makeEwc(amount = 50.0, isNotMine = true, transactionType = TransactionType.PURCHASE)
        // effectiveAmount = 0.0 for isNotMine
        assertThat(ewc.formattedAmount).contains("0")
        // The formatted value should reflect 0.00
        val numericPart = ewc.formattedAmount.replace(Regex("[^0-9.,]"), "")
        assertThat(numericPart).matches("0[.,]00")
    }

    @Test
    fun `shared expense with myShareAmount uses that amount`() {
        val ewc = makeEwc(
            amount = 100.0,
            isSharedExpense = true,
            myShareAmount = 33.33,
            transactionType = TransactionType.PURCHASE
        )
        assertThat(ewc.formattedAmount).contains("33")
    }

    @Test
    fun `shared expense with mySharePercentage uses proportional amount`() {
        val ewc = makeEwc(
            amount = 200.0,
            isSharedExpense = true,
            mySharePercentage = 50,
            transactionType = TransactionType.PURCHASE
        )
        // effectiveAmount = 200 * 50/100 = 100
        assertThat(ewc.formattedAmount).contains("100")
    }

    @Test
    fun `standard expense uses full amount`() {
        val ewc = makeEwc(amount = 75.50, transactionType = TransactionType.PURCHASE)
        assertThat(ewc.formattedAmount).contains("75")
    }

    // ---- Helpers ----

    private fun makeEwc(
        amount: Double = 10.0,
        currency: String = "EUR",
        transactionType: TransactionType = TransactionType.PURCHASE,
        isNotMine: Boolean = false,
        isSharedExpense: Boolean = false,
        myShareAmount: Double? = null,
        mySharePercentage: Int? = null
    ): ExpenseWithCategory {
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = "TestMerchant",
            transactionType = transactionType,
            date = 1_700_000_000_000L,
            isNotMine = isNotMine,
            isSharedExpense = isSharedExpense,
            myShareAmount = myShareAmount,
            mySharePercentage = mySharePercentage
        )
        return ExpenseWithCategory(
            expense = expense,
            category = Category(id = 1, name = "Test", icon = "🧪", color = "#FF0000")
        )
    }
}
