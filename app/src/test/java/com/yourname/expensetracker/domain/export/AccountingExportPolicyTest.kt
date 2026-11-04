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

    // ── DR-08: every row is validated; rows the user bears nothing of are skipped ──

    @Test
    fun `DR-08 10499 EUR rows then a USD row at position 10500 fail single currency`() {
        val rows = (1..10_499).map { transaction(id = it.toLong(), currency = "EUR") } +
            transaction(id = 10_500L, currency = "USD")

        val error = assertFailsWith<AccountingExportPolicyViolationException> {
            policy.validateAccountingDataset(rows, "Xero")
        }

        assertThat(error.violation).isEqualTo(AccountingExportViolation.SINGLE_CURRENCY)
        assertThat(error.detailValues).containsExactly("EUR", "USD")
    }

    @Test
    fun `DR-08 10500 EUR rows are accepted`() {
        policy.validateAccountingDataset(
            (1..10_500).map { transaction(id = it.toLong(), currency = "EUR") },
            "Xero"
        )
    }

    @Test
    fun `DR-08 a USD not-mine row in a EUR dataset does not throw`() {
        policy.validateAccountingDataset(
            listOf(
                transaction(id = 1L, currency = "EUR"),
                transaction(id = 2L, currency = "USD", effectiveAmount = 0.0)
            ),
            "Xero"
        )
    }

    @Test
    fun `DR-08 a non-purchase row at effective zero does not throw purchase-only`() {
        policy.validateAccountingDataset(
            listOf(
                transaction(id = 1L, currency = "EUR"),
                transaction(id = 2L, currency = "EUR", transactionType = TransactionType.TRANSFER, effectiveAmount = 0.0)
            ),
            "QuickBooks"
        )
    }

    @Test
    fun `DR-08 a USD row with a non-zero effective amount still fails single currency`() {
        val error = assertFailsWith<AccountingExportPolicyViolationException> {
            policy.requireSingleCurrency(
                listOf(
                    transaction(id = 1L, currency = "EUR"),
                    transaction(id = 2L, currency = "USD", effectiveAmount = 0.01)
                ),
                "FreshBooks"
            )
        }
        assertThat(error.violation).isEqualTo(AccountingExportViolation.SINGLE_CURRENCY)
    }

    @Test
    fun `DR-08 global validation row count excludes skipped rows`() {
        val result = policy.validateGlobalDataset(
            listOf(
                transaction(id = 1L, currency = "EUR"),
                transaction(id = 2L, currency = "USD", effectiveAmount = 0.0),
                transaction(id = 3L, currency = "EUR")
            ),
            "Xero"
        )

        assertThat(result.rowCount).isEqualTo(2)
        assertThat(result.distinctCurrencies).containsExactly("EUR")
        assertThat(result.isSingleCurrency).isTrue()
    }

    private fun transaction(
        id: Long,
        currency: String,
        transactionType: TransactionType = TransactionType.PURCHASE,
        effectiveAmount: Double = 10.0
    ): ExportTransaction {
        return ExportTransaction(
            id = id,
            date = id,
            amount = 10.0,
            effectiveAmount = effectiveAmount,
            merchant = "Merchant$id",
            notes = null,
            categoryId = 1L,
            currency = currency,
            transactionType = transactionType,
            sourceAccountName = "Cash"
        )
    }
}
