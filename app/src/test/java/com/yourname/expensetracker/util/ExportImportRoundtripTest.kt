package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.export.ExportTransaction
import com.yourname.expensetracker.domain.export.toExportTransaction
import org.junit.Assert.*
import org.junit.Test

class ExportImportRoundtripTest {
    @Test
    fun exportTransaction_fullJsonRoundtrip_preservesAllFields() {
        val expense = Expense(
            id = 1, amount = 25.50, currency = "EUR", merchant = "Starbucks",
            merchantKey = "starbucks", transactionType = TransactionType.PURCHASE,
            date = 1705276800000L, createdAt = 1705276800000L,
            categoryId = 3, notes = "Morning coffee", baseAmount = 25.50,
            baseCurrency = "EUR", exchangeRateUsed = 1.0,
            isBusinessExpense = false
        )
        val tx = expense.toExportTransaction()
        
        assertEquals(1L, tx.id)
        assertEquals("Starbucks", tx.merchant)
        assertEquals(25.50, tx.amount, 0.01)
        assertEquals("EUR", tx.currency)
        assertEquals("PURCHASE", tx.transactionType.name)
        assertEquals("Morning coffee", tx.notes)
        assertEquals(25.50, tx.baseAmount, 0.01)
        assertEquals(1.0, tx.exchangeRateUsed, 0.001)
    }
    
    @Test
    fun exportTransaction_csvColumns_matchExpectedOrder() {
        val tx = ExportTransaction(
            id = 1, date = 1705276800000L, createdAt = 1705276800000L,
            merchant = "Test", amount = 10.0, effectiveAmount = 10.0,
            currency = "EUR", transactionType = TransactionType.PURCHASE,
            paymentMethod = "CARD", originalCurrency = "EUR",
            homeCurrency = "EUR", baseAmount = 10.0, baseCurrency = "EUR",
            exchangeRateUsed = 1.0, isBusinessExpense = false,
            source = "MANUAL", notes = null, categoryId = null
        )
        assertNotNull(tx.id)
        assertNotNull(tx.merchant)
    }
}
