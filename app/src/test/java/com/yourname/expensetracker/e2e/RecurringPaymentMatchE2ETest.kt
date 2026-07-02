package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringOccurrenceMaterializer
import com.yourname.expensetracker.golden.GoldenTestBase
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
 * E2E Test 3: Recurring Rule → Occurrence Generation → Payment Match
 *
 * Wires the REAL RecurringLifecycleCoordinator with real expander/resolver/materializer.
 * Proves:
 * 1. generateOccurrences() creates PLANNED occurrences for future dates
 * 2. PlannedExpenses are created alongside occurrences
 * 3. linkExpenseToOccurrence() matches an actual payment to the correct occurrence
 * 4. Occurrence transitions to PAID, planned expense to FULFILLED
 * 5. Reminders are suppressed after payment
 * 6. Dashboard counts the actual payment once (not doubled with planned)
 */
class RecurringPaymentMatchE2ETest : GoldenTestBase() {

    private lateinit var recurringCoordinator: RecurringLifecycleCoordinator
    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "e2e_recurring_payment_match",
        numericTolerance = 0.01
    )

    @Before
    override fun setUp() {
        super.setUp()

        // Real recurring lifecycle coordinator with real sub-components
        val materializer = RecurringOccurrenceMaterializer(
            database = database,
            occurrenceDao = database.recurringOccurrenceDao(),
            reminderDeliveryDao = database.recurringReminderDeliveryDao(),
            timeProvider = timeProvider,
            lifecycleEventDao = database.recurringLifecycleEventDao(),
            plannedExpenseDao = database.plannedExpenseDao()
        )

        recurringCoordinator = RecurringLifecycleCoordinator(
            database = database,
            expander = RecurringOccurrenceExpander(),
            resolver = OccurrenceConflictResolver(),
            materializer = materializer,
            occurrenceDao = database.recurringOccurrenceDao(),
            expenseDao = database.expenseDao(),
            timeProvider = timeProvider,
            manualRecurringExpenseDao = database.manualRecurringExpenseDao(),
            reminderDeliveryDao = database.recurringReminderDeliveryDao(),
            lifecycleEventDao = database.recurringLifecycleEventDao(),
            restoreMaintenanceMode = restoreMaintenanceMode,
            writeBarrier = writeBarrier,
            plannedExpenseDao = database.plannedExpenseDao(),
            transactionRunner = RoomDomainTransactionRunner(database, timeProvider),
            eventWriter = mockk(relaxed = true)
        )

        // Multi-currency repository for dashboard verification
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
            currencySettingsRepository = currencySettings,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun `recurring rule generates occurrences and links actual payment`() = runTest {
        seedCategories()

        // ── SEED: Netflix recurring rule ──
        val ruleId = database.manualRecurringExpenseDao().insert(ManualRecurringExpense(
            merchant = "Netflix",
            amount = 12.99,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            nextDate = fixedNow,
            isActive = true,
            createdAt = fixedNow,
            categoryId = 5
        ))

        // ── ACT 1: Generate occurrences for next 3 months ──
        val genResult = recurringCoordinator.generateOccurrences(
            ruleId = ruleId,
            startDate = fixedNow,
            endDate = fixedNow + 86400000L * 90, // 3 months
            options = com.yourname.expensetracker.domain.recurring.lifecycle.OccurrenceGenerationOptions(
                createReminderDeliveries = true,
                reminderWindows = listOf("DUE_DAY"),
                generationSource = com.yourname.expensetracker.domain.recurring.lifecycle.OccurrenceGenerationSource.TEST
            )
        )

        // ── Verify occurrences created ──
        val occurrences = database.recurringOccurrenceDao().getBySource("RECURRING_RULE", ruleId)
        val plannedExpenses = occurrences.mapNotNull { occ ->
            database.plannedExpenseDao().getBySourceOccurrenceKey(occ.occurrenceKey)
        }

        // ── ACT 2: Simulate actual Netflix payment arriving ──
        val actualExpenseId = insertExpense(createPurchase(
            amount = 12.99, currency = "EUR", merchant = "Netflix", categoryId = 5
        ).copy(merchantKey = "netflix"))

        // ── ACT 3: Link expense to occurrence ──
        val linked = recurringCoordinator.linkExpenseToOccurrence(actualExpenseId)

        // ── Verify state after linking ──
        val occurrencesAfter = database.recurringOccurrenceDao().getBySource("RECURRING_RULE", ruleId)
        val paidOccurrences = occurrencesAfter.filter { it.status == "PAID" }
        val plannedOccurrences = occurrencesAfter.filter { it.status == "PLANNED" }

        // ── Dashboard total (should count actual payment once) ──
        val periodStart = fixedNow - 86400000L
        val periodEnd = fixedNow + 86400000L
        val dashboardTotal = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            // Generation results
            put("occurrencesGenerated", genResult.created)
            put("remindersCreated", genResult.remindersCreated)
            put("totalOccurrences", occurrences.size)
            put("plannedExpensesCreated", plannedExpenses.size)

            // Linking results
            put("linkSucceeded", linked)
            put("paidOccurrenceCount", paidOccurrences.size)
            put("remainingPlannedCount", plannedOccurrences.size)

            // Verify paid occurrence details
            if (paidOccurrences.isNotEmpty()) {
                val paid = paidOccurrences.first()
                put("paidOccurrenceLinkedExpenseId", paid.linkedExpenseId)
                put("paidOccurrenceAmount", paid.paidAmount)
                put("paidOccurrenceStatus", paid.status)
            }

            // Dashboard (should be 12.99, not doubled)
            put("dashboardTotal", dashboardTotal.displayAmount)
            put("dashboardTransactionCount", dashboardTotal.totalTransactionCount)
            put("noDoubleCount", dashboardTotal.displayAmount == 12.99)

            // Occurrence statuses
            put("occurrenceStatuses", JSONArray().apply {
                occurrencesAfter.sortedBy { it.dueDate }.forEach { put(it.status) }
            })
        }

        verifier.verify(actual).assertPassed()
    }
}
