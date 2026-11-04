package com.yourname.expensetracker.ui.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OwnershipValidatorTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun shareAmount(text: String): OwnershipValidator.ValidationResult =
        OwnershipValidator.validate(
            isNotMine = false,
            isSharedExpense = true,
            sharedWithName = "Anna",
            sharePercentageText = "",
            shareAmountText = text
        )

    private val invalidAmountMessage = "Share amount must be greater than 0"

    @Test
    fun `el_GR comma decimal share amount is valid`() {
        Locale.setDefault(Locale("el", "GR"))
        assertEquals(OwnershipValidator.ValidationResult.Valid, shareAmount("12,50"))
        assertEquals(OwnershipValidator.ValidationResult.Valid, shareAmount("1.234,56"))
        assertEquals(OwnershipValidator.ValidationResult.Valid, shareAmount("12.5"))
    }

    @Test
    fun `el_GR ambiguous comma grouping share amount is rejected with the existing message`() {
        Locale.setDefault(Locale("el", "GR"))
        assertEquals(OwnershipValidator.ValidationResult.Invalid(invalidAmountMessage), shareAmount("1,234"))
    }

    @Test
    fun `en_US comma grouping is valid and dot grouping is ambiguous`() {
        Locale.setDefault(Locale.US)
        assertEquals(OwnershipValidator.ValidationResult.Valid, shareAmount("1,234"))
        assertEquals(OwnershipValidator.ValidationResult.Valid, shareAmount("12.50"))
        assertEquals(OwnershipValidator.ValidationResult.Invalid(invalidAmountMessage), shareAmount("1.234"))
    }

    @Test
    fun `zero and invalid share amounts are rejected`() {
        Locale.setDefault(Locale.US)
        assertEquals(OwnershipValidator.ValidationResult.Invalid(invalidAmountMessage), shareAmount("0"))
        assertEquals(OwnershipValidator.ValidationResult.Invalid(invalidAmountMessage), shareAmount("0.00"))
        assertEquals(OwnershipValidator.ValidationResult.Invalid(invalidAmountMessage), shareAmount("abc"))
        assertEquals(OwnershipValidator.ValidationResult.Invalid(invalidAmountMessage), shareAmount("1,23.4"))
    }

    @Test
    fun `validation and save parser agree for every input`() {
        for (locale in listOf(Locale("el", "GR"), Locale.US)) {
            Locale.setDefault(locale)
            for (text in listOf("12,50", "12.50", "1.234", "1,234", "1.234,56", "1,234.56", "1,23.4", "0", "x")) {
                val parsed = AmountInputSanitizer.parseOrNull(text)
                val valid = shareAmount(text) == OwnershipValidator.ValidationResult.Valid
                assertEquals("$locale $text", parsed != null && parsed > 0, valid)
            }
        }
    }

    @Test
    fun `non shared expense skips amount parsing`() {
        val result = OwnershipValidator.validate(false, false, "", "", "garbage")
        assertTrue(result is OwnershipValidator.ValidationResult.Valid)
    }

    private fun shareAmountWithTotal(text: String, total: Double?): OwnershipValidator.ValidationResult {
        Locale.setDefault(Locale.US)
        return OwnershipValidator.validate(
            isNotMine = false,
            isSharedExpense = true,
            sharedWithName = "Anna",
            sharePercentageText = "",
            shareAmountText = text,
            totalAmount = total
        )
    }

    @Test
    fun `N-01-2 share amount over total is rejected`() {
        assertEquals(
            OwnershipValidator.ValidationResult.Invalid("Share amount cannot exceed the transaction total"),
            shareAmountWithTotal("10.01", 10.0)
        )
    }

    @Test
    fun `N-01-2 share amount equal to total and below total are valid`() {
        assertEquals(OwnershipValidator.ValidationResult.Valid, shareAmountWithTotal("10.00", 10.0))
        assertEquals(OwnershipValidator.ValidationResult.Valid, shareAmountWithTotal("9.99", 10.0))
    }

    @Test
    fun `N-01-2 no total keeps the previous behaviour for large share amounts`() {
        assertEquals(OwnershipValidator.ValidationResult.Valid, shareAmountWithTotal("1000000", null))
        assertEquals(
            OwnershipValidator.ValidationResult.Valid,
            OwnershipValidator.validate(false, true, "Anna", "", "1000000")
        )
    }

    @Test
    fun `N-01-2 total does not affect percentage shares or non shared expenses`() {
        assertEquals(
            OwnershipValidator.ValidationResult.Valid,
            OwnershipValidator.validate(false, true, "Anna", "50", "", totalAmount = 1.0)
        )
        assertEquals(
            OwnershipValidator.ValidationResult.Valid,
            OwnershipValidator.validate(false, false, "", "", "99999", totalAmount = 1.0)
        )
    }
}
