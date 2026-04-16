package com.yourname.expensetracker.domain.export

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Test

class ExpenseExportMapperTest {

    @Test
    fun `toExportTransaction preserves accounting fields and uses effective amount`() {
        val expense = Expense(
            id = 42L,
            amount = 100.0,
            currency = "USD",
            merchant = "Team Dinner",
            transactionType = TransactionType.TRANSFER,
            date = 1_700_000_000_000L,
            categoryId = 7L,
            notes = "split bill",
            paymentMethod = PaymentMethod.BANK_TRANSFER,
            isSharedExpense = true,
            mySharePercentage = 25
        )

        val result = expense.toExportTransaction()

        assertThat(result.id).isEqualTo(42L)
        assertThat(result.amount).isEqualTo(25.0)
        assertThat(result.currency).isEqualTo("USD")
        assertThat(result.transactionType).isEqualTo(TransactionType.TRANSFER)
        assertThat(result.sourceAccountName).isEqualTo("Bank Account")
    }

    @Test
    fun `toExportTransaction derives deterministic source account labels from payment method`() {
        assertThat(
            Expense(
                amount = 10.0,
                merchant = "Card",
                transactionType = TransactionType.PURCHASE,
                date = 1L,
                paymentMethod = PaymentMethod.CARD
            ).toExportTransaction().sourceAccountName
        ).isEqualTo("Credit Card")

        assertThat(
            Expense(
                amount = 10.0,
                merchant = "Cash",
                transactionType = TransactionType.PURCHASE,
                date = 1L,
                paymentMethod = PaymentMethod.CASH
            ).toExportTransaction().sourceAccountName
        ).isEqualTo("Cash")

        assertThat(
            Expense(
                amount = 10.0,
                merchant = "Unknown",
                transactionType = TransactionType.PURCHASE,
                date = 1L,
                paymentMethod = PaymentMethod.UNKNOWN
            ).toExportTransaction().sourceAccountName
        ).isEqualTo("Unknown Funding Source")
    }
}
