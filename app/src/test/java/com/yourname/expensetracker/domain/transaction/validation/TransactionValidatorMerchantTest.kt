package com.yourname.expensetracker.domain.transaction.validation

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-02-4: [TransactionValidator.validateMerchant] is the single merchant-only validation
 * (blank and placeholder). It rejects blank, "Unknown" and "Parsing Failed" and accepts a
 * normal name; [TransactionValidator.validateFinalExpenseState] (which shares the check)
 * keeps rejecting the same inputs.
 */
class TransactionValidatorMerchantTest {

    private val timeProvider = FakeTimeProvider.forDate(2026, 5, 1)
    private val validator = TransactionValidator(timeProvider, DefaultTransactionDatePolicy())

    private fun codes(merchant: String): List<String> =
        validator.validateMerchant(merchant).map { it.code }

    @Test
    fun `P-02-4 control - normal merchant name is accepted`() {
        assertTrue(validator.validateMerchant("Synthetic Cafe").isEmpty())
    }

    @Test
    fun `P-02-4 control - name that merely starts with a placeholder word is accepted`() {
        assertTrue(validator.validateMerchant("Unknown Store Athens").isEmpty())
        assertTrue(validator.validateMerchant("Parsing Failed Bakery").isEmpty())
    }

    @Test
    fun `P-02-4 empty merchant is rejected as blank`() {
        val errors = validator.validateMerchant("")
        assertEquals(listOf("MERCHANT_BLANK"), errors.map { it.code })
        assertEquals("merchant", errors.single().field)
    }

    @Test
    fun `P-02-4 whitespace-only merchant is rejected as blank`() {
        assertEquals(listOf("MERCHANT_BLANK"), codes("   "))
        assertEquals(listOf("MERCHANT_BLANK"), codes("\t\n"))
    }

    @Test
    fun `P-02-4 Unknown placeholder is rejected`() {
        val errors = validator.validateMerchant("Unknown")
        assertEquals(listOf("MERCHANT_PLACEHOLDER"), errors.map { it.code })
        assertEquals("merchant", errors.single().field)
    }

    @Test
    fun `P-02-4 Parsing Failed placeholder is rejected`() {
        val errors = validator.validateMerchant("Parsing Failed")
        assertEquals(listOf("MERCHANT_PLACEHOLDER"), errors.map { it.code })
        assertEquals("merchant", errors.single().field)
    }

    @Test
    fun `P-02-4 final-state validation still rejects blank and placeholder merchants`() {
        fun finalCodes(merchant: String): List<String> = validator.validateFinalExpenseState(
            amount = 10.0,
            merchant = merchant,
            currency = "EUR",
            date = timeProvider.now() - 86_400_000L,
            transactionType = TransactionType.PURCHASE,
            transferDirectionPresent = false,
            transferAccountName = null,
            isNotMine = false,
            isSharedExpense = false,
            latitude = null,
            longitude = null
        ).map { it.code }

        assertTrue(finalCodes("Synthetic Cafe").none { it.startsWith("MERCHANT_") })
        assertTrue("MERCHANT_BLANK" in finalCodes(""))
        assertTrue("MERCHANT_PLACEHOLDER" in finalCodes("Unknown"))
        assertTrue("MERCHANT_PLACEHOLDER" in finalCodes("Parsing Failed"))
    }
}
