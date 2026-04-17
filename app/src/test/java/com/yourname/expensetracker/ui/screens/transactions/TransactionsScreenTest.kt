package com.yourname.expensetracker.ui.screens.transactions

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TransactionsScreenTest {

    @Test
    fun `date headers use signed totals and expense safe colors`() {
        val source = transactionsScreenSource()

        assertTrue(source.contains("items.sumOf { it.expense.signedEffectiveAmount() }"))
        assertTrue(source.contains("CurrencyFormatter.formatWithSign(totalAmount, CurrencyConverter.DEFAULT_BASE_CURRENCY)"))
        assertTrue(source.contains("totalAmount < 0 -> SemanticColors.DangerRed"))
    }

    private fun transactionsScreenSource(): String {
        val file = File("app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt")
        require(file.exists()) { "Unable to locate TransactionsScreen.kt" }
        return file.readText()
    }
}
