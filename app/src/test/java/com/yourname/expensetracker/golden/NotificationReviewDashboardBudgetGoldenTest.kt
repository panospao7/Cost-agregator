package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

/**
 * Golden Scenario Test: Notification → Review → Dashboard → Budget Contract
 *
 * Tests the DB contract that NotificationProcessingPipeline produces:
 * 1. Expense created with dedupeKey → counts in dashboard
 * 2. Duplicate expense (same dedupeKey) rejected by unique index
 * 3. Transaction events written for each lifecycle step
 * 4. Dashboard total reflects only non-duplicate expenses
 * 5. Multiple expenses from different notifications sum correctly
 *
 * Uses REAL Room DB (dedupeKey unique index enforced) + REAL MultiCurrencyRepository.
 * Simulates what the pipeline writes without wiring 24 dependencies.
 */
class NotificationReviewDashboardBudgetGoldenTest : GoldenTestBase() {

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "notification_review_dashboard_budget",
        numericTolerance = 0.01
    )

    @Before
    override fun setUp() {
        super.setUp()

        val currencySettings = mockk<CurrencySettingsRepository>().also {
            every { it.homeCurrency() } returns flowOf("EUR")
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }
        val exchangeRateStore = ExchangeRateStoreAdapter(database.exchangeRateDao())
        val currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = database.expenseDao(),
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettings
        )
    }

    @Test
    fun `notification pipeline DB contract - dedup and dashboard`() = runTest {
        seedCategories()

        // ── Simulate: Greek bank notification → auto-accepted expense ──
        val expense1Id = database.expenseDao().insertAtomic(createPurchase(
            amount = 45.50, currency = "EUR", merchant = "Sklavenitis", categoryId = 1
        ).copy(dedupeKey = "45.50_sklavenitis_${fixedNow / 86400000}_EUR"))

        database.transactionEventDao().insert(TransactionEvent(
            expenseId = expense1Id, eventType = "CREATED",
            source = "NOTIFICATION", actor = "system:pipeline",
            occurredAt = fixedNow, dedupeKey = null,
            duplicateExpenseId = null, beforeSnapshot = null,
            afterSnapshot = null, metadata = null, reason = null
        ))

        // ── Simulate: Revolut notification → auto-accepted expense ──
        val expense2Id = database.expenseDao().insertAtomic(createPurchase(
            amount = 30.0, currency = "EUR", merchant = "Amazon", categoryId = 3
        ).copy(dedupeKey = "30.0_amazon_${fixedNow / 86400000}_EUR"))

        database.transactionEventDao().insert(TransactionEvent(
            expenseId = expense2Id, eventType = "CREATED",
            source = "NOTIFICATION", actor = "system:pipeline",
            occurredAt = fixedNow, dedupeKey = null,
            duplicateExpenseId = null, beforeSnapshot = null,
            afterSnapshot = null, metadata = null, reason = null
        ))

        // ── Simulate: DUPLICATE Greek notification (same dedupeKey) ──
        val duplicateResult = database.expenseDao().insertAtomic(createPurchase(
            amount = 45.50, currency = "EUR", merchant = "Sklavenitis", categoryId = 1
        ).copy(dedupeKey = "45.50_sklavenitis_${fixedNow / 86400000}_EUR"))

        // Record the duplicate skip event
        database.transactionEventDao().insert(TransactionEvent(
            expenseId = null, eventType = "CREATE_DUPLICATE_SKIPPED",
            source = "NOTIFICATION", actor = "system:pipeline",
            occurredAt = fixedNow, dedupeKey = "45.50_sklavenitis_${fixedNow / 86400000}_EUR",
            duplicateExpenseId = expense1Id, beforeSnapshot = null,
            afterSnapshot = null, metadata = null, reason = "dedupeKey conflict"
        ))

        // ── Simulate: Review-approved expense (low confidence, user approved) ──
        val expense3Id = database.expenseDao().insertAtomic(createPurchase(
            amount = 70.0, currency = "EUR", merchant = "Public", categoryId = 3
        ).copy(dedupeKey = "70.0_public_${fixedNow / 86400000}_EUR"))

        database.transactionEventDao().insert(TransactionEvent(
            expenseId = expense3Id, eventType = "CREATED_FROM_REVIEW",
            source = "REVIEW_APPROVAL", actor = "user",
            occurredAt = fixedNow, dedupeKey = null,
            duplicateExpenseId = null, beforeSnapshot = null,
            afterSnapshot = null, metadata = null, reason = null
        ))

        // ── QUERY: Dashboard total ──
        val periodStart = fixedNow - 86400000L * 30
        val periodEnd = fixedNow + 86400000L
        val dashboardTotal = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)
        val categoryTotals = multiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals(periodStart, periodEnd)

        // ── QUERY: Transaction events ──
        val allEvents = database.transactionEventDao().getRecentEvents(50)

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            put("expensesCreated", 3)
            put("duplicatesSkipped", 1)
            put("duplicateInsertReturnedZeroOrNegative", duplicateResult <= 0L)

            put("dashboardTotal", dashboardTotal.displayAmount)
            put("dashboardTransactionCount", dashboardTotal.totalTransactionCount)

            put("categoryTotals", JSONObject().apply {
                categoryTotals.forEach { (catId, agg) ->
                    put(catId?.toString() ?: "uncategorized", agg.displayAmount)
                }
            })

            put("transactionEvents", JSONArray().apply {
                allEvents.sortedBy { it.id }.forEach { put(it.eventType) }
            })

            put("expectedTotal", 145.50) // 45.50 + 30.0 + 70.0
            put("totalsMatch", Math.abs(dashboardTotal.displayAmount - 145.50) < 0.01)
        }

        verifier.verify(actual).assertPassed()
    }
}
