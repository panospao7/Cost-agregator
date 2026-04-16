package com.yourname.expensetracker.domain.export

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlin.test.assertFailsWith
import org.junit.Test

class AccountingExportPolicyTest {

    private val policy = AccountingExportPolicy()

    @Test
    fun `validateAccountingDataset accepts single currency purchase dataset`() {
        policy.validateAccountingDataset(
            transactions = listOf(
                transaction(id = 1L, currency = "EUR", transactionType = TransactionType.PURCHASE),
                transaction(id = 2L, currency = "EUR", transactionType = TransactionType.PURCHASE)
            ),
            exportName = "Xero"
        )
    }

    @Test
    fun `requireSingleCurrency fails fast for mixed currency datasets`() {
        val error = assertFailsWith<IllegalArgumentException> {
            policy.requireSingleCurrency(
                transactions = listOf(
                    transaction(id = 1L, currency = "EUR"),
                    transaction(id = 2L, currency = "USD")
                ),
                exportName = "QuickBooks"
            )
        }

        assertThat(error.message).contains("single-currency dataset")
        assertThat(error.message).contains("EUR, USD")
    }

    @Test
    fun `requirePurchaseTransactions fails fast for unsupported transaction types`() {
        val error = assertFailsWith<IllegalArgumentException> {
            policy.requirePurchaseTransactions(
                transactions = listOf(
                    transaction(id = 1L, currency = "EUR", transactionType = TransactionType.PURCHASE),
                    transaction(id = 2L, currency = "EUR", transactionType = TransactionType.TRANSFER)
                ),
                exportName = "FreshBooks"
            )
        }

        assertThat(error.message).contains("PURCHASE transactions only")
        assertThat(error.message).contains("TRANSFER")
    }

    private fun transaction(
        id: Long,
        currency: String,
        transactionType: TransactionType = TransactionType.PURCHASE
    ): ExportTransaction {
        return ExportTransaction(
            id = id,
            date = id,
            amount = 10.0,
            merchant = "Merchant$id",
            notes = null,
            categoryId = 1L,
            currency = currency,
            transactionType = transactionType,
            sourceAccountName = "Cash"
        )
    }
}
