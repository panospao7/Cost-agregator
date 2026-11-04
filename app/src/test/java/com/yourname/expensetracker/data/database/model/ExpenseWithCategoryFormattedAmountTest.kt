package com.yourname.expensetracker.data.database.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.ComparableHomeValue
import org.junit.Assert.assertEquals
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
    fun `polarity prefix per transaction type`() {
        // row: (type, amount, expected prefix or null for none, amount fragment or null)
        data class Row(val type: TransactionType, val amount: Double, val prefix: String?, val fragment: String?)
        val rows = listOf(
            Row(TransactionType.PURCHASE, 25.50, "-", "25"),
            Row(TransactionType.WITHDRAWAL, 100.0, "-", null),
            Row(TransactionType.DEPOSIT, 500.0, "+", null),
            Row(TransactionType.UNKNOWN, 10.0, null, null),
            Row(TransactionType.TRANSFER, 10.0, null, null)
        )
        assertEquals("polarity prefix rows size", 5, rows.size)
        rows.forEach { row ->
            val formatted = makeEwc(transactionType = row.type, amount = row.amount).formattedAmount
            val label = "Row ${row.type}: '$formatted'"
            if (row.prefix != null) {
                assertWithMessage(label).that(formatted).startsWith(row.prefix)
            } else {
                assertWithMessage(label).that(formatted).doesNotMatch("^[+-].*")
            }
            row.fragment?.let { assertWithMessage(label).that(formatted).contains(it) }
        }
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

        // Non-EUR currency is rendered with prefix then code
        val usd = makeEwc(currency = "USD", amount = 99.99, transactionType = TransactionType.DEPOSIT)
        assertThat(usd.formattedAmount).startsWith("+USD")
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
    fun `effective amount source per expense shape`() {
        val rows = listOf(
            // label to (ewc, expected fragment)
            "shared expense with myShareAmount uses that amount" to
                (makeEwc(
                    amount = 100.0,
                    isSharedExpense = true,
                    myShareAmount = 33.33,
                    transactionType = TransactionType.PURCHASE
                ) to "33"),
            // effectiveAmount = 200 * 50/100 = 100
            "shared expense with mySharePercentage uses proportional amount" to
                (makeEwc(
                    amount = 200.0,
                    isSharedExpense = true,
                    mySharePercentage = 50,
                    transactionType = TransactionType.PURCHASE
                ) to "100"),
            "standard expense uses full amount" to
                (makeEwc(amount = 75.50, transactionType = TransactionType.PURCHASE) to "75")
        )
        assertEquals("effective amount source rows size", 3, rows.size)
        rows.forEach { (label, pair) ->
            assertWithMessage("Row '$label': '${pair.first.formattedAmount}'")
                .that(pair.first.formattedAmount).contains(pair.second)
        }
    }

    // ---- Flagged (spending-excluded) rows print the full list amount ----

    private fun digits(s: String) = s.replace(Regex("[^0-9]"), "")

    @Test
    fun `flagged purchase prints its full amount not zero`() {
        val ewc = makeEwc(amount = 1200.0, exclusion = "INSTALLMENT_PURCHASE")
        assertThat(ewc.expense.effectiveAmount).isEqualTo(0.0)
        assertThat(ewc.formattedAmount).startsWith("-EUR")
        assertThat(digits(ewc.formattedAmount)).isEqualTo("120000")
    }

    @Test
    fun `flagged shared row prints the ownership adjusted share`() {
        val ewc = makeEwc(
            amount = 1200.0, isSharedExpense = true, myShareAmount = 300.0,
            exclusion = "INSTALLMENT_PURCHASE"
        )
        assertThat(digits(ewc.formattedAmount)).isEqualTo("30000")
    }

    @Test
    fun `flagged not mine row still prints zero`() {
        val ewc = makeEwc(amount = 1200.0, isNotMine = true, exclusion = "INSTALLMENT_PURCHASE")
        assertThat(digits(ewc.formattedAmount)).isEqualTo("000")
    }

    @Test
    fun `unflagged row prints the same amount as the list filter value`() {
        val ewc = makeEwc(amount = 1200.0)
        assertThat(digits(ewc.formattedAmount)).isEqualTo("120000")
        assertThat(ComparableHomeValue.listAmount(ewc.expense)).isEqualTo(ewc.expense.effectiveAmount)
    }

    // ---- Helpers ----

    private fun makeEwc(
        amount: Double = 10.0,
        currency: String = "EUR",
        transactionType: TransactionType = TransactionType.PURCHASE,
        isNotMine: Boolean = false,
        isSharedExpense: Boolean = false,
        myShareAmount: Double? = null,
        mySharePercentage: Int? = null,
        exclusion: String? = null
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
            mySharePercentage = mySharePercentage,
            spendingExclusionReason = exclusion
        )
        return ExpenseWithCategory(
            expense = expense,
            category = Category(id = 1, name = "Test", icon = "🧪", color = "#FF0000")
        )
    }
}
