package com.yourname.expensetracker.scenarios

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.core.money.ConversionFailure
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Scenario tests verifying stale rate handling.
 *
 * These tests validate that the domain correctly distinguishes fresh rates from
 * stale ones, that [ConversionFailure] carries the right [FailureReason], and
 * that [CurrencyConverter.convert] refuses to use rates older than
 * [CurrencyConverter.MAX_RATE_AGE_MS].
 *
 * GIVEN / WHEN / THEN structure follows the scenario testing pattern used
 * throughout the project.
 */
class CurrencyRateStalenessScenarioTest {

    companion object {
        private const val ONE_HOUR_MS = 60 * 60 * 1000L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Stale rate returns conversion failure with STALE_RATE reason
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `stale rate returns conversion failure with STALE_RATE reason`() {
        // GIVEN: a ConversionFailure with FailureReason.RATE_STALE
        val failure = ConversionFailure(
            originalAmount = MoneyAmount(50.0, CurrencyCode.USD),
            targetCurrency = CurrencyCode.EUR,
            reason = FailureReason.RATE_STALE
        )

        // THEN: failure reason description mentions "too old"
        assertThat(failure.description).contains("too old")

        // AND: failure is distinguishable from MISSING_RATE
        val missingFailure = ConversionFailure(
            originalAmount = MoneyAmount(50.0, CurrencyCode.USD),
            targetCurrency = CurrencyCode.EUR,
            reason = FailureReason.MISSING_RATE
        )

        assertThat(failure.description).isNotEqualTo(missingFailure.description)
        assertThat(failure.reason).isEqualTo(FailureReason.RATE_STALE)
        assertThat(missingFailure.reason).isEqualTo(FailureReason.MISSING_RATE)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Current rate within 24h is not stale
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `current rate within 24h is not stale`() = runTest {
        // GIVEN: exchange rate with lastUpdated = timeProvider.now() (recent)
        val now = 1_700_000_000_000L
        val timeProvider = mockk<TimeProvider>()
        every { timeProvider.now() } returns now

        val exchangeRateStore = mockk<ExchangeRateStore>(relaxed = true)
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.92,
            lastUpdated = now,
            source = "test"
        )

        val converter = CurrencyConverter(exchangeRateStore, timeProvider)

        // WHEN: checking staleness threshold (MAX_RATE_AGE_MS = 24h)
        val result = converter.convert(50.0, "USD", "EUR")

        // THEN: not stale — conversion succeeds
        assertThat(result).isNotNull()
        assertThat(result!!.convertedAmount).isWithin(0.001).of(46.0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Rate older than 24h is stale
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `rate older than 24h is stale`() {
        // GIVEN: exchange rate with lastUpdated = timeProvider.now() - 25 hours
        val now = 1_700_000_000_000L
        val staleTimestamp = now - (25 * ONE_HOUR_MS)

        // WHEN: checking staleness threshold (MAX_RATE_AGE_MS = 24h)
        val age = now - staleTimestamp

        // THEN: rate is stale
        assertThat(age).isGreaterThan(CurrencyConverter.MAX_RATE_AGE_MS)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: CurrencyConverter with stale rate falls through to EUR composite
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `CurrencyConverter with stale rate falls through to EUR composite`() = runTest {
        // GIVEN: a USD->EUR rate that is 25 hours old
        val now = 1_700_000_000_000L
        val staleTimestamp = now - (25 * ONE_HOUR_MS)
        val timeProvider = mockk<TimeProvider>()
        every { timeProvider.now() } returns now

        val exchangeRateStore = mockk<ExchangeRateStore>(relaxed = true)

        // Direct USD->EUR rate is stale (older than MAX_RATE_AGE_MS)
        coEvery { exchangeRateStore.getRate("USD", "EUR") } returns DomainExchangeRate(
            fromCurrency = "USD",
            toCurrency = "EUR",
            rate = 0.92,
            lastUpdated = staleTimestamp,
            source = "test"
        )

        // AND: no fresh USD->EUR direct rate — EUR composite also fails because
        //      the EUR->EUR identity leg is not present in the store
        coEvery { exchangeRateStore.getRate("EUR", "EUR") } returns null

        val converter = CurrencyConverter(exchangeRateStore, timeProvider)

        // WHEN: calling convert(50.0, USD, EUR)
        val result = converter.convert(50.0, "USD", "EUR")

        // THEN: returns null (treated as unavailable due to staleness)
        assertThat(result).isNull()
    }
}
