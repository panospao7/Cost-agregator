package com.yourname.expensetracker.domain.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Direct pins for the canonical [StaleRatePolicy] selection (CURR-3E8-08) and the
 * constant values it hands out.
 *
 * Moved from the deleted integration.Curr587BehavioralTest. The 7-day [StaleRatePolicy.LatestDefault]
 * threshold itself is already pinned by CurrencyNormalizationPost9a6Test and its boundary behaviour
 * (just under 7d converts, just over is STALE_RATE), so it is intentionally not duplicated here.
 */
class StaleRatePolicyTest {

    @Test
    fun `forBasis LATEST_AVAILABLE selects LatestDefault`() {
        assertEquals(StaleRatePolicy.LatestDefault, StaleRatePolicy.forBasis(RateBasis.LATEST_AVAILABLE))
    }

    @Test
    fun `forBasis TRANSACTION_DATE selects None so historical rates are never stale`() {
        val policy = StaleRatePolicy.forBasis(RateBasis.TRANSACTION_DATE)
        assertEquals(StaleRatePolicy.None, policy)
        assertNull(policy.maxAgeMs)
    }

    @Test
    fun `forBasis non-latest bases never apply a max age`() {
        listOf(
            RateBasis.IDENTITY,
            RateBasis.TRANSACTION_DATE,
            RateBasis.PERIOD_START,
            RateBasis.PERIOD_END
        ).forEach { basis ->
            assertNull("Basis $basis must not be age-limited", StaleRatePolicy.forBasis(basis).maxAgeMs)
        }
    }

    @Test
    fun `default policy is 24 hours against now`() {
        assertEquals(24L * 60L * 60L * 1000L, StaleRatePolicy.Default.maxAgeMs)
        assertEquals(StaleRateReference.NOW, StaleRatePolicy.Default.compareAgainst)
    }
}
