package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BankApiIntegrationTest {

    private lateinit var integration: BankApiIntegration
    private lateinit var mapMethod: java.lang.reflect.Method
    private val connection = BankConnection(
        bankId = "revolut",
        bankName = "Revolut",
        countryCode = "EU",
        defaultCategoryId = 42L
    )

    @Before
    fun setUp() {
        integration = BankApiIntegration(
            timeProvider = FakeTimeProvider(),
            coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true),
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        )
        mapMethod = BankApiIntegration::class.java.getDeclaredMethod(
            "mapTransactionToExpense",
            BankTransaction::class.java,
            BankConnection::class.java
        ).apply {
            isAccessible = true
        }
    }

    @Test
    fun `mapTransactionToExpense keeps debit as purchase with negative amount`() {
        val request = mapTransaction(
            BankTransaction(
                id = "debit-1",
                date = 1_000L,
                amount = -24.5,
                currency = "EUR",
                merchant = "Supermarket",
                description = "Card purchase",
                reference = "REF1"
            )
        )

        assertEquals(TransactionType.PURCHASE, request.transactionType)
        assertEquals(-24.5, request.amount, 0.0)
        assertNull(request.transferDirection)
        assertEquals(ExpenseSource.BANK_API_SYNC, request.source)
    }

    @Test
    fun `mapTransactionToExpense keeps credit as deposit with positive amount`() {
        val request = mapTransaction(
            BankTransaction(
                id = "credit-1",
                date = 2_000L,
                amount = 1250.0,
                currency = "EUR",
                merchant = "Employer",
                description = "Salary credit",
                reference = "REF2"
            )
        )

        assertEquals(TransactionType.DEPOSIT, request.transactionType)
        assertEquals(1250.0, request.amount, 0.0)
        assertNull(request.transferDirection)
        assertEquals(ExpenseSource.BANK_API_SYNC, request.source)
    }

    @Test
    fun `mapTransactionToExpense keeps transfer meaning and direction`() {
        val request = mapTransaction(
            BankTransaction(
                id = "transfer-1",
                date = 3_000L,
                amount = -80.0,
                currency = "EUR",
                merchant = "Savings",
                description = "Transfer to savings",
                reference = "REF3",
                transferDirection = TransferDirection.OUTGOING
            )
        )

        assertEquals(TransactionType.TRANSFER, request.transactionType)
        assertEquals(-80.0, request.amount, 0.0)
        assertEquals(TransferDirection.OUTGOING, request.transferDirection)
        assertEquals(ExpenseSource.BANK_API_SYNC, request.source)
    }

    @Test
    fun `mapTransactionToExpense keeps refund as deposit instead of purchase`() {
        val request = mapTransaction(
            BankTransaction(
                id = "refund-1",
                date = 4_000L,
                amount = 19.99,
                currency = "EUR",
                merchant = "Store",
                description = "Card refund processed",
                reference = "REF4"
            )
        )

        assertEquals(TransactionType.DEPOSIT, request.transactionType)
        assertEquals(19.99, request.amount, 0.0)
        assertNull(request.transferDirection)
        assertEquals(ExpenseSource.BANK_API_SYNC, request.source)
    }

    private fun mapTransaction(transaction: BankTransaction) =
        mapMethod.invoke(integration, transaction, connection) as CreateExpenseRequest
}
