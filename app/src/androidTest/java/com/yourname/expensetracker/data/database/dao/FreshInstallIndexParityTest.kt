package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.data.database.entity.SubscriptionCandidate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Batch 7 closure — fresh-install parity regression test.**
 *
 * Verifies that every callback-managed index created by
 * [AppDatabase.FRESH_INSTALL_CALLBACK] is actually present when the database
 * is built via [AppDatabase.inMemoryBuilder].  This catches drift where a
 * builder path omits the callback and silently loses constraint enforcement.
 *
 * The test inspects `sqlite_master` for the expected index names **and**
 * verifies the subscription-candidate SQLite uniqueness is no longer present,
 * while budget-forecast behavior still relies on app-layer handling.
 */
@RunWith(AndroidJUnit4::class)
class FreshInstallIndexParityTest {

    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ── Schema-level assertions ─────────────────────────────────────────────

    /**
     * Fresh installs must keep Room-declared `group_members` / `group_expenses`
     * indexes plus the runtime-only callback-managed indexes that are not part
     * of Room's exported schema contract.
     */
    @Test
    fun fresh_install_keeps_expected_indexes_in_sqlite_master() {
        val expectedIndexes = listOf(
            // Room-declared group_members indexes
            "index_group_members_groupId",
            "index_group_members_groupId_isCurrentUser",
            "index_group_members_groupId_name",
            // Room-declared group_expenses indexes
            "index_group_expenses_groupId",
            "index_group_expenses_expenseId",
            "index_group_expenses_paidById",
            "index_group_expenses_groupId_date",
            "index_group_expenses_isReimbursable",
            // Fresh-install-only raw_notifications runtime dedup constraints
            "index_raw_notifications_dedup_nonnull",
            "index_raw_notifications_dedup_both_null",
            "index_raw_notifications_dedup_title_null",
            "index_raw_notifications_dedup_text_null",
            // Room-declared subscription_candidates indexes
            "index_subscription_candidates_canonicalMerchant",
            "index_subscription_candidates_isConverted",
            "index_subscription_candidates_confidence",
            // Room-declared budget_forecasts indexes
            "index_budget_forecasts_budgetId",
            "index_budget_forecasts_forecastDate",
            "index_budget_forecasts_isActive"
        )

        val db = database.openHelper.writableDatabase
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index'"
        )
        val actualIndexes = mutableListOf<String>()
        while (cursor.moveToNext()) {
            actualIndexes.add(cursor.getString(0))
        }
        cursor.close()

        for (expected in expectedIndexes) {
            assertTrue(
                "Expected index '$expected' is missing from fresh in-memory DB. " +
                    "FRESH_INSTALL_CALLBACK may not have fired. Found indexes: $actualIndexes",
                actualIndexes.contains(expected)
            )
        }

        assertFalse(actualIndexes.contains("index_group_members_groupId_currentUser"))
        assertFalse(actualIndexes.contains("index_group_expenses_expenseId_unique"))

        // Budgets must only keep Room-declared indexes.
        assertTrue(actualIndexes.contains("index_budgets_categoryId"))
        assertTrue(actualIndexes.contains("index_budgets_isActive"))
        assertTrue(!actualIndexes.contains("index_budgets_active_overall"))
        assertTrue(!actualIndexes.contains("index_budgets_active_category"))
        assertFalse(actualIndexes.contains("index_budget_forecasts_active_budget_period"))
        assertFalse(actualIndexes.contains("index_subscription_candidates_pending_merchant_interval"))

        val budgetForecastIndexes = actualIndexes.filter { it.startsWith("index_budget_forecasts_") }.toSet()
        assertEquals(
            setOf(
                "index_budget_forecasts_budgetId",
                "index_budget_forecasts_forecastDate",
                "index_budget_forecasts_isActive"
            ),
            budgetForecastIndexes
        )

        val groupExpenseIndexes = actualIndexes.filter { it.startsWith("index_group_expenses_") }.toSet()
        assertEquals(
            setOf(
                "index_group_expenses_groupId",
                "index_group_expenses_expenseId",
                "index_group_expenses_paidById",
                "index_group_expenses_groupId_date",
                "index_group_expenses_isReimbursable"
            ),
            groupExpenseIndexes
        )

        val subscriptionCandidateIndexes = actualIndexes.filter { it.startsWith("index_subscription_candidates_") }.toSet()
        assertEquals(
            setOf(
                "index_subscription_candidates_canonicalMerchant",
                "index_subscription_candidates_isConverted",
                "index_subscription_candidates_confidence"
            ),
            subscriptionCandidateIndexes
        )
    }

    // ── Batch 7 behavioural assertions ──────────────────────────────────────

    /**
     * Fresh installs must no longer enforce pending-candidate uniqueness via a
     * non-Room SQLite index.
     */
    @Test
    fun duplicate_pending_subscription_candidate_can_exist_without_non_room_index() = runBlocking {
        val dao = database.subscriptionCandidateDao()

        dao.insert(
            SubscriptionCandidate(
                merchant = "Netflix Inc.",
                canonicalMerchant = "Netflix",
                detectedInterval = "MONTHLY",
                averageAmount = 15.99,
                currency = "EUR",
                confidence = 0.9,
                transactionCount = 3,
                firstSeen = 1_700_000_000_000L,
                lastSeen = 1_702_000_000_000L,
                estimatedAnnualCost = 191.88,
                userAction = "pending",
                isConverted = false
            )
        )

        val secondId = dao.insert(
            SubscriptionCandidate(
                merchant = "Netflix Inc.",
                canonicalMerchant = "Netflix",
                detectedInterval = "MONTHLY",
                averageAmount = 16.99,
                currency = "EUR",
                confidence = 0.92,
                transactionCount = 4,
                firstSeen = 1_700_000_000_000L,
                lastSeen = 1_703_000_000_000L,
                estimatedAnnualCost = 203.88,
                userAction = "pending",
                isConverted = false
            )
        )

        assertTrue(secondId > 0)
        assertEquals(2, dao.getPendingCandidates().size)
    }

    /**
     * App-layer insertion should still keep only the newest active forecast
     * for the same (budgetId, targetPeriodStart, targetPeriodEnd).
     */
    @Test
    fun insert_with_deactivation_keeps_single_active_budget_forecast() = runBlocking {
        // First, insert a budget to satisfy the foreign key.
        val budgetDao = database.budgetDao()
        val budgetId = budgetDao.insert(
            Budget(
                categoryId = null,
                amount = 500.0,
                period = BudgetPeriod.MONTHLY,
                startDate = 1_700_000_000_000L,
                isActive = true
            )
        )

        val forecastDao = database.budgetForecastDao()

        val first = BudgetForecast(
            budgetId = budgetId,
            forecastDate = System.currentTimeMillis(),
            targetPeriodStart = 1_700_000_000_000L,
            targetPeriodEnd = 1_702_000_000_000L,
            predictedSpending = 400.0,
            predictedRemaining = 100.0,
            confidenceScore = 0.85,
            riskLevel = ForecastRiskLevel.MEDIUM,
            overspendProbability = 0.20,
            isActive = true,
            createdAt = System.currentTimeMillis()
        )
        forecastDao.insertWithDeactivation(first)

        val duplicate = BudgetForecast(
            budgetId = budgetId,
            forecastDate = System.currentTimeMillis(),
            targetPeriodStart = 1_700_000_000_000L,
            targetPeriodEnd = 1_702_000_000_000L,
            predictedSpending = 450.0,
            predictedRemaining = 50.0,
            confidenceScore = 0.88,
            riskLevel = ForecastRiskLevel.HIGH,
            overspendProbability = 0.60,
            isActive = true,
            createdAt = System.currentTimeMillis()
        )

        forecastDao.insertWithDeactivation(duplicate)

        val activeForDate = forecastDao.getForecastForDate(budgetId, 1_701_000_000_000L)
        assertTrue(activeForDate != null)
        assertEquals(450.0, activeForDate!!.predictedSpending, 0.0001)

        val allForecasts = forecastDao.getForecastsForBudget(budgetId)
        assertEquals(2, allForecasts.first().size)
    }

    /**
     * A converted (non-pending) subscription candidate must coexist with
     * a pending one without relying on any custom SQLite index.
     */
    @Test
    fun converted_subscription_candidate_does_not_collide_with_pending() = runBlocking {
        val dao = database.subscriptionCandidateDao()

        val pending = SubscriptionCandidate(
            merchant = "Spotify AB",
            canonicalMerchant = "Spotify",
            detectedInterval = "MONTHLY",
            averageAmount = 9.99,
            currency = "EUR",
            confidence = 0.88,
            transactionCount = 3,
            firstSeen = 1_700_000_000_000L,
            lastSeen = 1_702_000_000_000L,
            estimatedAnnualCost = 119.88,
            userAction = "pending",
            isConverted = false
        )
        dao.insert(pending)

        val converted = SubscriptionCandidate(
            merchant = "Spotify AB",
            canonicalMerchant = "Spotify",
            detectedInterval = "MONTHLY",
            averageAmount = 9.99,
            currency = "EUR",
            confidence = 0.95,
            transactionCount = 5,
            firstSeen = 1_700_000_000_000L,
            lastSeen = 1_703_000_000_000L,
            estimatedAnnualCost = 119.88,
            userAction = "pending",
            isConverted = true  // converted → excluded from partial index
        )
        val id = dao.insert(converted)
        assertTrue("Converted candidate should insert without collision", id > 0)
    }

    /**
     * Direct inserts can coexist now because budget_forecasts keeps only
     * Room-declared indexes on fresh install.
     */
    @Test
    fun duplicate_active_budget_forecast_direct_insert_does_not_collide() = runBlocking {
        val budgetDao = database.budgetDao()
        val budgetId = budgetDao.insert(
            Budget(
                categoryId = null,
                amount = 500.0,
                period = BudgetPeriod.MONTHLY,
                startDate = 1_700_000_000_000L,
                isActive = true
            )
        )

        val forecastDao = database.budgetForecastDao()

        val active = BudgetForecast(
            budgetId = budgetId,
            forecastDate = System.currentTimeMillis(),
            targetPeriodStart = 1_700_000_000_000L,
            targetPeriodEnd = 1_702_000_000_000L,
            predictedSpending = 400.0,
            predictedRemaining = 100.0,
            confidenceScore = 0.85,
            riskLevel = ForecastRiskLevel.MEDIUM,
            overspendProbability = 0.20,
            isActive = true,
            createdAt = System.currentTimeMillis()
        )
        forecastDao.insert(active)

        val duplicate = BudgetForecast(
            budgetId = budgetId,
            forecastDate = System.currentTimeMillis(),
            targetPeriodStart = 1_700_000_000_000L,
            targetPeriodEnd = 1_702_000_000_000L,
            predictedSpending = 420.0,
            predictedRemaining = 80.0,
            confidenceScore = 0.80,
            riskLevel = ForecastRiskLevel.MEDIUM,
            overspendProbability = 0.25,
            isActive = true,
            createdAt = System.currentTimeMillis()
        )
        val id = forecastDao.insert(duplicate)
        assertTrue("Duplicate active forecast should insert without SQLite collision", id > 0)
    }
}
