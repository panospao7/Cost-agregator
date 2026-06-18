package com.yourname.expensetracker.scenarios

import com.yourname.expensetracker.domain.currency.ConversionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure domain tests for map-marker currency display behaviour.
 *
 * Verifies that markers correctly show either the home currency (when
 * conversion succeeds) or the original currency (when conversion fails),
 * and that a conversion warning is populated on failure.
 *
 * These tests operate entirely in-process with no database or Android
 * framework dependencies beyond Robolectric's test runner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MapMarkerConversionCurrencyTest {

    // ── Test helpers ──────────────────────────────────────────────────────────

    /**
     * Represents the display state of a single map marker after a currency
     * conversion attempt. This is the domain contract tested below.
     */
    private data class ExpenseMapMarker(
        val expenseId: Long,
        val originalCurrency: String,
        val originalAmount: Double,
        val displayCurrency: String,
        val displayAmount: Double,
        val conversionWarning: String? = null
    )

    /**
     * Creates an [ExpenseMapMarker] simulating the post-conversion display state.
     *
     * @param expenseId        The expense's database ID.
     * @param originalAmount   The raw expense amount.
     * @param originalCurrency The raw expense ISO-4217 currency code.
     * @param homeCurrency     The user's home currency code.
     * @param conversionResult [ConversionResult] from [CurrencyConverter.convert],
     *                         or `null` when conversion failed.
     * @return An [ExpenseMapMarker] with the appropriate display fields.
     */
    private fun createMarker(
        expenseId: Long,
        originalAmount: Double,
        originalCurrency: String,
        homeCurrency: String,
        conversionResult: ConversionResult?
    ): ExpenseMapMarker {
        return if (conversionResult != null) {
            // Successful conversion — show home-currency amount
            ExpenseMapMarker(
                expenseId = expenseId,
                originalCurrency = originalCurrency,
                originalAmount = originalAmount,
                displayCurrency = homeCurrency,
                displayAmount = conversionResult.convertedAmount,
                conversionWarning = null
            )
        } else {
            // Failed conversion — show original-currency amount with warning
            ExpenseMapMarker(
                expenseId = expenseId,
                originalCurrency = originalCurrency,
                originalAmount = originalAmount,
                displayCurrency = originalCurrency,
                displayAmount = originalAmount,
                conversionWarning = "Conversion failed for $originalAmount $originalCurrency to $homeCurrency"
            )
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `failed conversion marker shows original currency, not home currency`() {
        // GIVEN an expense of 100.00 USD and a conversion that fails (null)
        val originalAmount = 100.0
        val originalCurrency = "USD"
        val homeCurrency = "EUR"

        val marker = createMarker(
            expenseId = 1L,
            originalAmount = originalAmount,
            originalCurrency = originalCurrency,
            homeCurrency = homeCurrency,
            conversionResult = null // conversion failed
        )

        // THEN the marker preserves the original currency and amount
        assertEquals(
            "Failed-conversion marker should display original currency",
            originalCurrency, marker.displayCurrency
        )
        assertEquals(
            "Failed-conversion marker should display original amount",
            originalAmount, marker.displayAmount, 0.001
        )

        // AND the home currency is NOT shown
        org.junit.Assert.assertNotEquals(
            "Failed-conversion marker must NOT show home currency",
            homeCurrency, marker.displayCurrency
        )
    }

    @Test
    fun `successful conversion marker shows home currency`() {
        // GIVEN an expense of 100.00 USD converted to 85.00 EUR
        val originalAmount = 100.0
        val originalCurrency = "USD"
        val homeCurrency = "EUR"
        val convertedAmount = 85.0
        val rateUsed = 0.85

        val conversionResult = ConversionResult(
            originalAmount = originalAmount,
            originalCurrency = originalCurrency,
            convertedAmount = convertedAmount,
            targetCurrency = homeCurrency,
            rateUsed = rateUsed,
            timestamp = 1_700_000_000_000L
        )

        val marker = createMarker(
            expenseId = 2L,
            originalAmount = originalAmount,
            originalCurrency = originalCurrency,
            homeCurrency = homeCurrency,
            conversionResult = conversionResult
        )

        // THEN the marker shows the home currency and converted amount
        assertEquals(
            "Successful-conversion marker should display home currency",
            homeCurrency, marker.displayCurrency
        )
        assertEquals(
            "Successful-conversion marker should display converted amount",
            convertedAmount, marker.displayAmount, 0.001
        )

        // AND no warning is present
        assertNull("Successful-conversion marker must have no conversion warning", marker.conversionWarning)
    }

    @Test
    fun `marker conversionWarning populated on failure`() {
        // GIVEN a conversion that fails
        val originalAmount = 50.0
        val originalCurrency = "JPY"
        val homeCurrency = "EUR"

        val marker = createMarker(
            expenseId = 3L,
            originalAmount = originalAmount,
            originalCurrency = originalCurrency,
            homeCurrency = homeCurrency,
            conversionResult = null
        )

        // THEN the marker has a conversion warning
        assertNotNull("Failed-conversion marker must have a conversionWarning", marker.conversionWarning)

        // AND the warning mentions the failure context
        val warning = marker.conversionWarning!!
        org.junit.Assert.assertTrue(
            "Warning should mention the original currency ($originalCurrency)",
            warning.contains(originalCurrency)
        )
        org.junit.Assert.assertTrue(
            "Warning should mention the home currency ($homeCurrency)",
            warning.contains(homeCurrency)
        )
    }
}
