package com.yourname.expensetracker.domain.naturallanguage

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * FL-03: digits inside a full date (dd/mm/yyyy or ISO yyyy-mm-dd) are not read as amounts, so a date search
 * is not filtered by amount, and the date range is still parsed.
 */
@Suppress("DEPRECATION")
class NaturalLanguageSearchEngineAmountTest {

    private fun engine() = NaturalLanguageSearchEngine(
        expenseQueryRepository = mockk(relaxed = true),
        speechInputGateway = mockk(relaxed = true),
        timeProvider = mockk(relaxed = true),
        currencyConverter = mockk(relaxed = true),
        currencySettingsRepository = mockk(relaxed = true),
        categoryRepository = mockk(relaxed = true),
        merchantNormalizationRepository = mockk(relaxed = true),
    )

    @Test
    fun `slash date digits are not extracted as amounts`() = runTest {
        val result = engine().interpretQuery("spent 15/03/2024 on coffee")

        assertNull(result.extractedAmounts)
        assertEquals(
            NaturalLanguageSearchEngine.DateRange(LocalDate.of(2024, 3, 15), LocalDate.of(2024, 3, 15)),
            result.dateRange
        )
    }

    @Test
    fun `iso date digits are not extracted as amounts`() = runTest {
        val result = engine().interpretQuery("paid 2024-03-15 for rent")

        assertNull(result.extractedAmounts)
    }
}
