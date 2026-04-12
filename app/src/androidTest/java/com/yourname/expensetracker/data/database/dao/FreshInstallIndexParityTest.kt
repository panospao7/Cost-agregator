package com.yourname.expensetracker.data.database.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetForecast
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.ForecastRiskLevel
import com.yourname.expensetracker.data.database.entity.SubscriptionCandidate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Batch 7 closure — fresh-install parity regression test.**
 *
 * Verifies that every partial unique index created by
 * [AppDatabase.FRESH_INSTALL_CALLBACK] is actually present when the database
 * is built via [AppDatabase.inMemoryBuilder].  This catches drift where a
 * builder path omits the callback and silently loses constraint enforcement.
 *
 * The test inspects `sqlite_master` for the expected index names **and**
 * exercises the Batch 7 indexes behaviourally to prove they reject
 * duplicate rows at the SQLite level.
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
     * All partial unique indexes created by [AppDatabase.FRESH_INSTALL_CALLBACK]
     * must exist in `sqlite_master` after a fresh build.
     */
    @Test
    fun all_partial_unique_indexes_exist_in_sqlite_master() {
        val expectedIndexes = listOf(
            // Batch 3: group constraints
            "index_group_members_groupId_currentUser",
            "index_group_expenses_expenseId_unique",
            // Batch 4: budget constraints
            "index_budgets_active_overall",
            "index_budgets_active_category",
            // Batch 6: raw_notifications NULL-safety constraints
            "index_raw_notifications_dedup_nonnull",
            "index_raw_notifications_dedup_both_null",
            "index_raw_notifications_dedup_title_null",
            "index_raw_notifications_dedup_text_null",
            // Batch 7: subscription_candidates + budget_forecasts
            "index_subscription_candidates_pending_merchant_interval",
            "index_budget_forecasts_active_budget_period"
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
                "Partial unique index '$expected' is missing from fresh in-memory DB. " +
                    "FRESH_INSTALL_CALLBACK may not have fired. Found indexes: $actualIndexes",
                actualIndexes.contains(expected)
            )
        }
    }

    // ── Batch 7 behavioural assertions ──────────────────────────────────────

    /**
     * Two pending subscription candidates with the same (canonicalMerchant,
     * detectedInterval) must be rejected by the partial unique index.
     */
    @Test
    fun duplicate_pending_subscription_candidate_is_rejected() = runBlocking {
        val dao = database.subscriptionCandidateDao()

        val first = SubscriptionCandidate(
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
        dao.insert(first)

        val duplicate = SubscriptionCandidate(
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

        try {
            dao.insert(duplicate)
            fail("Expected SQLiteConstraintException for duplicate pending subscription candidate")
        } catch (_: SQLiteConstraintException) {
            // expected — partial unique index is enforced
        }
    }

    /**
     * Two active budget forecasts with the same (budgetId, targetPeriodStart,
     * targetPeriodEnd) must be rejected by the partial unique index.
     */
    @Test
    fun duplicate_active_budget_forecast_is_rejected() = runBlocking {
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
        forecastDao.insert(first)

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

        try {
            forecastDao.insert(duplicate)
            fail("Expected SQLiteConstraintException for duplicate active budget forecast")
        } catch (_: SQLiteConstraintException) {
            // expected — partial unique index is enforced
        }
    }

    /**
     * A converted (non-pending) subscription candidate must not collide with
     * a pending one — the index only covers pending rows.
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
        // Must NOT throw — the partial index only covers isConverted=0 AND userAction='pending'
        val id = dao.insert(converted)
        assertTrue("Converted candidate should insert without collision", id > 0)
    }

    /**
     * An inactive budget forecast must not collide with an active one —
     * the index only covers isActive = 1 rows.
     */
    @Test
    fun inactive_budget_forecast_does_not_collide_with_active() = runBlocking {
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

        val inactive = BudgetForecast(
            budgetId = budgetId,
            forecastDate = System.currentTimeMillis(),
            targetPeriodStart = 1_700_000_000_000L,
            targetPeriodEnd = 1_702_000_000_000L,
            predictedSpending = 420.0,
            predictedRemaining = 80.0,
            confidenceScore = 0.80,
            riskLevel = ForecastRiskLevel.MEDIUM,
            overspendProbability = 0.25,
            isActive = false,  // inactive → excluded from partial index
            createdAt = System.currentTimeMillis()
        )
        val id = forecastDao.insert(inactive)
        assertTrue("Inactive forecast should insert without collision", id > 0)
    }
}
