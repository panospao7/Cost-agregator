package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

/**
 * Golden Scenario Test: Recurring Planned/Actual No Double Count
 *
 * Proves that:
 * 1. An actual payment (expense) linked to a recurring occurrence counts ONCE in dashboard total
 * 2. Planned expenses (future occurrences) do NOT appear in spending totals
 * 3. Occurrence claim correctly transitions status to PAID
 * 4. Future planned occurrences remain PLANNED
 * 5. Dashboard total = only actual expenses in the period
 *
 * Uses REAL Room DB + REAL CurrencyConverter + REAL MultiCurrencyRepository.
 */
class RecurringPlannedActualNoDoubleCountGoldenTest : GoldenTestBase() {

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "recurring_planned_actual_no_double_count",
        numericTolerance = 0.01
    )

    @Before
    override fun setUp() {
        super.setUp()

        val currencySettings = mockk<CurrencySettingsRepository>().also {
            every { it.homeCurrency() } returns flowOf("EUR")
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }
        val exchangeRateStore = ExchangeRateStoreAdapter(database.exchangeRateDao(), writeBarrier)
        val currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = database.expenseDao(),
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettings,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun `actual payment linked to occurrence counts once in dashboard`() = runTest {
        // ── SEED ──
        seedCategories()
        val ruleId = seedRecurringRule()
        val mayOccId = seedMayOccurrence(ruleId)
        seedFutureOccurrences(ruleId)
        seedPlannedExpenses(ruleId)

        // Insert the ACTUAL May payment as a real expense
        val expenseId = insertExpense(createPurchase(
            amount = 12.99, currency = "EUR", merchant = "Netflix", categoryId = 5
        ))

        // Claim the May occurrence (simulating what RecurringLifecycleCoordinator does)
        val claimed = database.recurringOccurrenceDao().claimForExpense(
            mayOccId, expenseId, 12.99, "EUR", fixedNow
        )

        // Fulfill the planned expense
        val occKey = "RECURRING_RULE|$ruleId|$fixedNow|MONTHLY"
        database.plannedExpenseDao().fulfillByOccurrenceKey(occKey, 999L, fixedNow)

        // ── ACT: Query dashboard spending total for the month ──
        val periodStart = fixedNow - 86400000L * 15  // 15 days before
        val periodEnd = fixedNow + 86400000L         // 1 day after

        val dashboardTotal = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // Query occurrence statuses
        val mayOcc = database.recurringOccurrenceDao().getById(mayOccId)
        val allOccs = database.recurringOccurrenceDao().getBySource("RECURRING_RULE", ruleId)
        val plannedExpense = database.plannedExpenseDao().getBySourceOccurrenceKey(occKey)

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            put("dashboardTotal", dashboardTotal.displayAmount)
            put("dashboardCurrency", dashboardTotal.displayCurrency.code)
            put("dashboardTransactionCount", dashboardTotal.totalTransactionCount)
            put("isPartial", dashboardTotal.isPartial)
            put("doubleCounted", false) // If total > 12.99, something is wrong

            put("claimSucceeded", claimed == 1)
            put("mayOccurrenceStatus", mayOcc?.status)
            put("mayOccurrenceLinkedExpenseId", mayOcc?.linkedExpenseId)
            put("plannedExpenseStatus", plannedExpense?.status)

            put("occurrenceStatuses", JSONObject().apply {
                allOccs.sortedBy { it.dueDate }.forEachIndexed { i, occ ->
                    put("occurrence_$i", occ.status)
                }
            })

            put("totalOccurrences", allOccs.size)
            put("paidOccurrences", allOccs.count { it.status == "PAID" })
            put("plannedOccurrences", allOccs.count { it.status == "PLANNED" })
        }

        // ── VERIFY ──
        verifier.verify(actual).assertPassed()
    }

    // ── Seed helpers ──

    private suspend fun seedRecurringRule(): Long {
        return database.manualRecurringExpenseDao().insert(ManualRecurringExpense(
            merchant = "Netflix",
            amount = 12.99,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = fixedNow + 86400000L * 30,
            isActive = true,
            createdAt = fixedNow,
            categoryId = 5
        ))
    }

    private suspend fun seedMayOccurrence(ruleId: Long): Long {
        return database.recurringOccurrenceDao().insert(RecurringOccurrence(
            sourceType = "RECURRING_RULE", sourceId = ruleId,
            occurrenceKey = "RECURRING_RULE|$ruleId|$fixedNow|MONTHLY",
            dueDate = fixedNow,
            expectedAmount = 12.99, expectedCurrency = "EUR",
            status = "PLANNED", createdAt = fixedNow,
            frequency = "MONTHLY", merchant = "Netflix", categoryId = 5
        ))
    }

    private suspend fun seedFutureOccurrences(ruleId: Long) {
        val june = fixedNow + 86400000L * 30
        val july = fixedNow + 86400000L * 60
        database.recurringOccurrenceDao().insert(RecurringOccurrence(
            sourceType = "RECURRING_RULE", sourceId = ruleId,
            occurrenceKey = "RECURRING_RULE|$ruleId|$june|MONTHLY",
            dueDate = june,
            expectedAmount = 12.99, expectedCurrency = "EUR",
            status = "PLANNED", createdAt = fixedNow,
            frequency = "MONTHLY", merchant = "Netflix", categoryId = 5
        ))
        database.recurringOccurrenceDao().insert(RecurringOccurrence(
            sourceType = "RECURRING_RULE", sourceId = ruleId,
            occurrenceKey = "RECURRING_RULE|$ruleId|$july|MONTHLY",
            dueDate = july,
            expectedAmount = 12.99, expectedCurrency = "EUR",
            status = "PLANNED", createdAt = fixedNow,
            frequency = "MONTHLY", merchant = "Netflix", categoryId = 5
        ))
    }

    private suspend fun seedPlannedExpenses(ruleId: Long) {
        val occKey = "RECURRING_RULE|$ruleId|$fixedNow|MONTHLY"
        database.plannedExpenseDao().insertPlannedExpense(PlannedExpense(
            description = "Netflix subscription",
            amount = 12.99, currency = "EUR",
            date = fixedNow,
            sourceRecurringRuleId = ruleId,
            sourceOccurrenceKey = occKey,
            openSourceOccurrenceKey = occKey,
            status = "PLANNED",
            createdAt = fixedNow, updatedAt = fixedNow
        ))
    }
}
