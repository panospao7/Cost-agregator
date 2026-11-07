package com.yourname.expensetracker.domain.export

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Test

class ExpenseExportMapperTest {

    @Test
    fun `toExportTransaction preserves full and effective accounting amounts`() {
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
        assertThat(result.amount).isEqualTo(100.0)
        assertThat(result.effectiveAmount).isEqualTo(25.0)
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

    @Test
    fun `toExportTransaction normalizes sentinel conversion snapshot and preserves non-sentinel snapshot`() {
        val sentinelExpense = Expense(
            id = 50L,
            amount = 80.0,
            currency = "USD",
            merchant = "NoRateHotel",
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L,
            baseAmount = 0.0,
            baseCurrency = "",
            exchangeRateUsed = 0.0
        )
        val convertedExpense = sentinelExpense.copy(
            id = 51L,
            merchant = "ConvertedHotel",
            baseAmount = 68.0,
            baseCurrency = "EUR",
            exchangeRateUsed = 0.85
        )

        val sentinelMapped = sentinelExpense.toExportTransaction()
        assertThat(sentinelMapped.baseAmount).isEqualTo(0.0)
        assertThat(sentinelMapped.baseCurrency).isEmpty()
        assertThat(sentinelMapped.homeCurrency).isEmpty()
        assertThat(sentinelMapped.exchangeRateUsed).isEqualTo(0.0)
        assertThat(sentinelMapped.conversionRateUsed).isNull()

        val convertedMapped = convertedExpense.toExportTransaction()
        assertThat(convertedMapped.baseAmount).isEqualTo(68.0)
        assertThat(convertedMapped.baseCurrency).isEqualTo("EUR")
        assertThat(convertedMapped.homeCurrency).isEqualTo("EUR")
        assertThat(convertedMapped.exchangeRateUsed).isEqualTo(0.85)
        assertThat(convertedMapped.conversionRateUsed).isEqualTo(0.85)
    }

    @Test
    fun `toExportTransaction maps refundNettedAmount with gross amount and net effective amount`() {
        val expense = Expense(
            id = 60L,
            amount = 100.0,
            currency = "EUR",
            merchant = "Shirt",
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L,
            refundNettedAmount = 40.0
        )

        val result = expense.toExportTransaction()

        assertThat(result.amount).isEqualTo(100.0)
        assertThat(result.effectiveAmount).isEqualTo(60.0)
        assertThat(result.refundNettedAmount).isEqualTo(40.0)
    }

    @Test
    fun `toExportTransaction defaults refundNettedAmount to zero and guards non-finite values`() {
        val base = Expense(
            id = 61L,
            amount = 25.0,
            currency = "EUR",
            merchant = "Plain",
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L
        )

        assertThat(base.toExportTransaction().refundNettedAmount).isEqualTo(0.0)
        assertThat(base.copy(refundNettedAmount = Double.NaN).toExportTransaction().refundNettedAmount)
            .isEqualTo(0.0)
        assertThat(base.copy(refundNettedAmount = Double.POSITIVE_INFINITY).toExportTransaction().refundNettedAmount)
            .isEqualTo(0.0)
    }
}
