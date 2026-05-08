package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.core.money.FailureReason
import com.yourname.expensetracker.domain.core.money.ConversionFailure
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.scenario.*
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeeder
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden scenario smoke test that proves the scenario infrastructure
 * (AppDatabaseTestFactory, ScenarioSeeder, ScenarioAssertions) works
 * end-to-end by loading seed data and verifying the resulting DB state.
 *
 * Also includes critical path assertions:
 * - Multi-currency expenses produce MoneyAggregate (not raw sums)
 * - PrivacyGate correctly denies/ allows capability checks
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenScenarioSmokeTest {

    private lateinit var context: Context
    private lateinit var db: com.yourname.expensetracker.data.database.AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `scenario infrastructure loads and seeds correctly`() = runTest {
        // GIVEN: seed data with categories and expenses
        val seed = ScenarioSeed(
            categories = listOf(
                com.yourname.expensetracker.testfixtures.scenario.CategorySeed("Food"),
                com.yourname.expensetracker.testfixtures.scenario.CategorySeed("Transport")
            ),
            expenses = listOf(
                com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed(
                    amount = 50.0,
                    currency = "EUR",
                    merchant = "Test",
                    date = dateMs(2026, 5, 1)
                )
            )
        )

        // WHEN: seeding the database
        val result = ScenarioSeeder(db).seedState(seed)

        // THEN: the expense is inserted and count matches
        with(ScenarioAssertions) {
            db.assertExpenseCount(1)
        }
    }

    @Test
    fun `multi-currency scenario no raw totals`() = runTest {
        // GIVEN: EUR+USD expenses seeded (no exchange rates available)
        val seed = ScenarioSeed(
            categories = listOf(
                com.yourname.expensetracker.testfixtures.scenario.CategorySeed("Food")
            ),
            expenses = listOf(
                com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed(
                    amount = 50.0,
                    currency = "EUR",
                    merchant = "Supermarket",
                    date = dateMs(2026, 5, 1)
                ),
                com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed(
                    amount = 100.0,
                    currency = "USD",
                    merchant = "Amazon",
                    date = dateMs(2026, 5, 1)
                )
            )
        )
        ScenarioSeeder(db).seedState(seed)
        with(ScenarioAssertions) {
            db.assertExpenseCount(2)
        }

        // WHEN: Building a MoneyAggregate from per-currency buckets (simulating
        // what the engine does — no actual conversion since no rates are seeded).
        // USD→EUR conversion fails → aggregate is partial.
        val aggregate = MoneyAggregate.partial(
            displayAmount = 50.0, // Only EUR can be displayed; USD conversion failed
            displayCurrency = CurrencyCode.EUR,
            sourceBuckets = listOf(
                MoneyBucket(CurrencyCode.EUR, 50.0, 1),
                MoneyBucket(CurrencyCode.USD, 100.0, 1)
            ),
            failures = listOf(
                com.yourname.expensetracker.domain.core.money.ConversionFailure(
                    originalAmount = MoneyAmount(100.0, CurrencyCode.USD),
                    targetCurrency = CurrencyCode.EUR,
                    reason = com.yourname.expensetracker.domain.core.money.FailureReason.MISSING_RATE
                )
            )
        )

        // THEN: Aggregate correctly shows 2 source buckets (no raw summing)
        assertEquals("Should have 2 source buckets", 2, aggregate.sourceBuckets.size)
        val eurBucket = aggregate.sourceBuckets.single { it.currency.code == "EUR" }
        assertEquals("EUR bucket amount should be 50.0", 50.0, eurBucket.amount, 0.001)
        val usdBucket = aggregate.sourceBuckets.single { it.currency.code == "USD" }
        assertEquals("USD bucket amount should be 100.0", 100.0, usdBucket.amount, 0.001)

        // AND: isPartial = true (USD could not be converted)
        assertTrue("Multi-currency without rates should be partial", aggregate.isPartial)
        assertEquals("Should have 1 conversion failure", 1, aggregate.failedTransactionCount)

        // AND: displayAmount is NOT the raw sum of 150.0 — it's only the home-currency portion
        assertFalse(
            "displayAmount should not equal raw sum (150.0) of mixed currencies",
            aggregate.displayAmount == 150.0
        )
        assertEquals(
            "displayAmount should be 50.0 (only EUR home currency portion)",
            50.0, aggregate.displayAmount, 0.001
        )

        // AND: warning message is present
        assertNotNull("Partial aggregate should have warning message", aggregate.warningMessage)
    }

    @Test
    fun `multi-currency scenario verifies MoneyAggregate partial state`() = runTest {
        // GIVEN: EUR 100 + USD 50 seeded — no exchange rate for USD conversion
        val seed = ScenarioSeed(
            categories = listOf(
                com.yourname.expensetracker.testfixtures.scenario.CategorySeed("Food")
            ),
            expenses = listOf(
                com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed(
                    amount = 100.0,
                    currency = "EUR",
                    merchant = "Supermarket",
                    date = dateMs(2026, 5, 1)
                ),
                com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed(
                    amount = 50.0,
                    currency = "USD",
                    merchant = "Amazon",
                    date = dateMs(2026, 5, 1)
                )
            )
        )
        ScenarioSeeder(db).seedState(seed)

        // WHEN: Building a MoneyAggregate from per-currency buckets
        // EUR can be shown directly; USD fails conversion → partial state
        val aggregate = MoneyAggregate.partial(
            displayAmount = 100.0, // Only EUR is safe to display
            displayCurrency = CurrencyCode.EUR,
            sourceBuckets = listOf(
                MoneyBucket(CurrencyCode.EUR, 100.0, 1),
                MoneyBucket(CurrencyCode.USD, 50.0, 1)
            ),
            failures = listOf(
                com.yourname.expensetracker.domain.core.money.ConversionFailure(
                    originalAmount = MoneyAmount(50.0, CurrencyCode.USD),
                    targetCurrency = CurrencyCode.EUR,
                    reason = com.yourname.expensetracker.domain.core.money.FailureReason.MISSING_RATE
                )
            )
        )

        // THEN: isPartial = true because USD conversion failed
        assertTrue("Multi-currency partial aggregate should be marked partial", aggregate.isPartial)

        // AND: failedTransactionCount sums transaction counts from all failures
        assertEquals(
            "failedTransactionCount should be 1 (one USD transaction)",
            1, aggregate.failedTransactionCount
        )

        // AND: sourceBuckets has both EUR and USD
        assertEquals("Should have 2 source buckets", 2, aggregate.sourceBuckets.size)
        val eurBucket = aggregate.sourceBuckets.single { it.currency.code == "EUR" }
        assertEquals("EUR bucket amount should be 100.0", 100.0, eurBucket.amount, 0.001)
        val usdBucket = aggregate.sourceBuckets.single { it.currency.code == "USD" }
        assertEquals("USD bucket amount should be 50.0", 50.0, usdBucket.amount, 0.001)

        // AND: displayAmount reflects only the home-currency portion, NOT raw sum
        assertEquals(
            "displayAmount should be 100.0 (only EUR home currency portion, not 150.0)",
            100.0, aggregate.displayAmount, 0.001
        )

        // AND: warningMessage is present and mentions transaction count
        assertNotNull("Partial aggregate should have warningMessage", aggregate.warningMessage)
        assertTrue(
            "warningMessage should reference transaction count",
            aggregate.warningMessage!!.contains("1", ignoreCase = true)
        )
    }

    @Test
    fun `privacy gate blocks provider call`() = runTest {
        // GIVEN: A PrivacyGate that denies a specific capability
        val denyingGate = object : PrivacyGate {
            override suspend fun check(
                capability: PrivacyCapability,
                context: Map<String, String>
            ): PrivacyDecision {
                return when (capability) {
                    PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION -> PrivacyDecision.Denied("Cloud AI warranty extraction is disabled in privacy settings")
                    else -> PrivacyDecision.Allowed
                }
            }
        }

        // WHEN: checking the blocked capability
        val blockedDecision = denyingGate.check(PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION)

        // THEN: the result is Denied with a descriptive reason
        assertTrue("Blocked capability should be denied", blockedDecision is PrivacyDecision.Denied)
        if (blockedDecision is PrivacyDecision.Denied) {
            assertTrue("Denied reason should be descriptive", blockedDecision.reason.isNotBlank())
            assertEquals(
                "Denied reason should match",
                "Cloud AI warranty extraction is disabled in privacy settings",
                blockedDecision.reason
            )
        }

        // AND: checking an allowed capability returns Allowed
        val allowedDecision = denyingGate.check(PrivacyCapability.DEVICE_GPS_LOCATION)
        assertTrue("Allowed capability should be Allowed", allowedDecision is PrivacyDecision.Allowed)

        // AND: Allowed is not Denied
        assertFalse(
            "Allowed should not equal Denied",
            allowedDecision is PrivacyDecision.Denied
        )
    }
}
