package com.yourname.expensetracker.domain.naturallanguage

import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.capture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Tier 1 boundary tests for the legacy NL default search window.
 *
 * When no explicit date range is provided, [NaturalLanguageSearchEngine.executeSearch]
 * must resolve the window to `now minus 3 calendar months` .. `now` and pass
 * those exact bounds to the repository in a single keyset page call.
 *
 * Expected month-end dates are computed independently with explicit java.time
 * [LocalDateTime.minusMonths] logic (NOT [com.yourname.expensetracker.domain.util.TimePeriodUtils.addMonths],
 * which is the implementation oracle). Mar 31, Jan 31, and May 31 2024 exercise
 * month-end coercion semantics (e.g. May 31 - 3 months = Feb 29 in leap year 2024).
 */
class NaturalLanguageSearchEngineDefaultWindowBoundaryTest {

    @Test
    fun `default window on Mar 31 2024 is addMonths now minus 3 with end at now and one keyset call`() = runTest {
        assertDefaultSearchWindow(toEpochMs(2024, 3, 31, 12, 0))
    }

    @Test
    fun `default window on Jan 31 2024 is addMonths now minus 3 with end at now and one keyset call`() = runTest {
        assertDefaultSearchWindow(toEpochMs(2024, 1, 31, 12, 0))
    }

    @Test
    fun `default window on May 31 2024 is addMonths now minus 3 with end at now and one keyset call`() = runTest {
        assertDefaultSearchWindow(toEpochMs(2024, 5, 31, 12, 0))
    }

    private suspend fun assertDefaultSearchWindow(now: Long) {
        val timeProvider = mockk<TimeProvider>()
        every { timeProvider.now() } returns now

        val startSlot = slot<Long>()
        val endSlot = slot<Long>()
        val expenseQueryRepository = mockk<NaturalLanguageExpenseQueryRepository>()
        coEvery {
            expenseQueryRepository.getExpensesBetweenFilteredKeyset(
                startMs = capture(startSlot),
                endMs = capture(endSlot),
                categoryIds = any(),
                merchants = any(),
                transactionType = any(),
                keywordSearch = any(),
                limit = any(),
                cursor = any()
            )
        } returns emptyList()

        val currencySettingsRepository = mockk<CurrencySettingsRepository>()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")

        val engine = NaturalLanguageSearchEngine(
            expenseQueryRepository = expenseQueryRepository,
            speechInputGateway = mockk(),
            timeProvider = timeProvider,
            currencyConverter = mockk(),
            currencySettingsRepository = currencySettingsRepository,
            categoryRepository = mockk(),
            merchantNormalizationRepository = mockk(relaxed = true),
        )

        val interpretation = NaturalLanguageSearchEngine.QueryInterpretation(
            originalQuery = "show my recent spending",
            queryType = NaturalLanguageSearchEngine.QueryType.FIND_TRANSACTIONS,
            extractedAmounts = null,
            dateRange = null,
            locations = null,
            categories = null,
            merchants = null,
            searchFilter = NaturalLanguageSearchEngine.SearchFilter(
                minAmount = null, maxAmount = null, exactAmount = null,
                startDate = null, endDate = null,
                locations = null, categories = null, merchants = null
            ),
            confidence = 50.0
        )

        engine.executeSearch(interpretation)

        assertEquals(
            "Default window start must be now minus 3 calendar months (month-end coerced)",
            expectedDefaultWindowStart(now),
            startSlot.captured
        )
        assertEquals("Default window end must be now", now, endSlot.captured)
        coVerify(exactly = 1) {
            expenseQueryRepository.getExpensesBetweenFilteredKeyset(
                startMs = any(), endMs = any(),
                categoryIds = any(), merchants = any(),
                transactionType = any(), keywordSearch = any(),
                limit = any(), cursor = any()
            )
        }
    }

    /**
     * Independent expected value for the default window start: `now` minus 3
     * calendar months, computed with java.time only. This intentionally avoids
     * [com.yourname.expensetracker.domain.util.TimePeriodUtils.addMonths] so the
     * test does not compare the implementation against itself.
     */
    private fun expectedDefaultWindowStart(now: Long): Long {
        val zone = ZoneId.systemDefault()
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            .minusMonths(3)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
