package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.domain.groups.SettlementCalculator
import com.yourname.expensetracker.domain.groups.SharedExpenseDataPort
import com.yourname.expensetracker.domain.groups.SharedExpenseManager
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method
import kotlin.math.abs

class FinancialArithmeticPrecisionTest : AnalyticsEngineTestBase() {

    private lateinit var settlementCalculator: SettlementCalculator
    private lateinit var sharedExpenseManager: SharedExpenseManager

    private lateinit var settlementAmountToCentsMethod: Method
    private lateinit var settlementCentsToAmountMethod: Method
    private lateinit var sharedToCentsMethod: Method
    private lateinit var sharedFromCentsMethod: Method

    @Before
    override fun setUp() {
        super.setUp()

        settlementCalculator = SettlementCalculator(currencySettingsRepository = mock())
        sharedExpenseManager = SharedExpenseManager(
            sharedExpenseDataPort = mockk<SharedExpenseDataPort>(relaxed = true),
            timeProvider = timeProvider,
            currencySettingsRepository = mock(),
            ioDispatcher = StandardTestDispatcher()
        )

        settlementAmountToCentsMethod = SettlementCalculator::class.java
            .getDeclaredMethod("amountToCents", java.lang.Double.TYPE)
            .apply { isAccessible = true }
        settlementCentsToAmountMethod = SettlementCalculator::class.java
            .getDeclaredMethod("centsToAmount", java.lang.Long.TYPE)
            .apply { isAccessible = true }

        sharedToCentsMethod = SharedExpenseManager::class.java
            .getDeclaredMethod("toCents", java.lang.Double.TYPE)
            .apply { isAccessible = true }
        sharedFromCentsMethod = SharedExpenseManager::class.java
            .getDeclaredMethod("fromCents", java.lang.Long.TYPE)
            .apply { isAccessible = true }
    }

    @Test
    fun `toCents and fromCents canonical 100 euro conversion is exact`() {
        assertEquals(10000L, settlementAmountToCents(100.00))
        assertEquals(10000L, sharedToCents(100.00))

        assertApproxEquals(100.00, settlementCentsToAmount(10000L), 0.0)
        assertApproxEquals(100.00, sharedFromCents(10000L), 0.0)
    }

    @Test
    fun `roundtrip cents conversion is stable for representative values`() {
        val values = listOf(
            0.0,
            0.01,
            1.23,
            10.10,
            99.99,
            1234.56,
            999_999.99,
            21_474_836.47,
            -42.42
        )

        values.forEach { value ->
            val settlementRoundtrip = settlementCentsToAmount(settlementAmountToCents(value))
            val sharedRoundtrip = sharedFromCents(sharedToCents(value))

            assertApproxEquals(value, settlementRoundtrip, 0.0, "Settlement roundtrip for $value: ")
            assertApproxEquals(value, sharedRoundtrip, 0.0, "Shared roundtrip for $value: ")
            assertEquals(settlementAmountToCents(value), sharedToCents(value))
        }
    }

    @Test
    fun `500 repeated conversions keep numerical drift under one cent`() {
        val initial = 1234.56
        var amount = initial

        repeat(500) {
            amount = settlementCentsToAmount(settlementAmountToCents(amount))
            amount = sharedFromCents(sharedToCents(amount))
        }

        val drift = abs(amount - initial)
        assertApproxEquals(0.0, drift, 0.01)
    }

    @Test
    fun `overflow boundary amount converts without integer overflow`() {
        val boundaryAmount = 21_474_836.47
        val expectedBoundaryCents = 2_147_483_647L

        val settlementBoundaryCents = settlementAmountToCents(boundaryAmount)
        val sharedBoundaryCents = sharedToCents(boundaryAmount)

        assertEquals(expectedBoundaryCents, settlementBoundaryCents)
        assertEquals(expectedBoundaryCents, sharedBoundaryCents)
        assertApproxEquals(boundaryAmount, settlementCentsToAmount(settlementBoundaryCents), 0.0)
        assertApproxEquals(boundaryAmount, sharedFromCents(sharedBoundaryCents), 0.0)

        // One cent above Int.MAX_VALUE in cents must still be representable with Long-based arithmetic.
        val oneCentAbove = 21_474_836.48
        val expectedOneCentAboveCents = 2_147_483_648L
        assertEquals(expectedOneCentAboveCents, settlementAmountToCents(oneCentAbove))
        assertEquals(expectedOneCentAboveCents, sharedToCents(oneCentAbove))
    }

    private fun settlementAmountToCents(amount: Double): Long {
        return settlementAmountToCentsMethod.invoke(settlementCalculator, amount) as Long
    }

    private fun settlementCentsToAmount(cents: Long): Double {
        return settlementCentsToAmountMethod.invoke(settlementCalculator, cents) as Double
    }

    private fun sharedToCents(amount: Double): Long {
        return sharedToCentsMethod.invoke(sharedExpenseManager, amount) as Long
    }

    private fun sharedFromCents(cents: Long): Double {
        return sharedFromCentsMethod.invoke(sharedExpenseManager, cents) as Double
    }
}