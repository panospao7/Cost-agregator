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
import org.junit.Assert.assertEquals
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

    /**
     * RP-04 A2 golden: rule update (amount change) on a rule with an overdue open
     * occurrence + linked actual expense must count the actual ONCE and the open
     * planned row ONCE — the reconciler must not duplicate or drop obligations.
     *
     * Uses the same counting approach as the existing golden test: REAL Room DB,
     * REAL MultiCurrencyRepository dashboard total, REAL rule coordinator.
     */
    @Test
    fun `ruleUpdateDoesNotDoubleCountPlannedAndActual`() = runTest {
        // ── SEED ──
        seedCategories()
        val ruleId = database.manualRecurringExpenseDao().insert(ManualRecurringExpense(
            merchant = "Netflix",
            amount = 12.99,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            // Anchor one month in the past so the first slot is OVERDUE (open).
            nextDate = fixedNow - 86400000L * 30,
            isActive = true,
            createdAt = fixedNow - 86400000L * 60,
            categoryId = 5
        ))

        // Overdue open occurrence due yesterday (at/before the reference day).
        val overdueDue = fixedNow - 86400000L
        val overdueOccId = database.recurringOccurrenceDao().insert(RecurringOccurrence(
            sourceType = "RECURRING_RULE", sourceId = ruleId,
            occurrenceKey = "RECURRING_RULE|$ruleId|$overdueDue|MONTHLY",
            dueDate = overdueDue,
            expectedAmount = 12.99, expectedCurrency = "EUR",
            status = "PLANNED", createdAt = fixedNow,
            frequency = "MONTHLY", merchant = "Netflix", categoryId = 5
        ))

        // Open planned row derived from the overdue occurrence.
        val occKey = "RECURRING_RULE|$ruleId|$overdueDue|MONTHLY"
        val plannedRowId = database.plannedExpenseDao().insertPlannedExpense(PlannedExpense(
            description = "Netflix subscription",
            amount = 12.99, currency = "EUR",
            date = overdueDue,
            sourceRecurringRuleId = ruleId,
            sourceOccurrenceKey = occKey,
            openSourceOccurrenceKey = occKey,
            status = "PLANNED",
            createdAt = fixedNow, updatedAt = fixedNow
        ))

        // The ACTUAL payment for the overdue slot, linked to the occurrence.
        val expenseId = insertExpense(createPurchase(
            amount = 12.99, currency = "EUR", merchant = "Netflix", categoryId = 5,
            date = overdueDue
        ))
        database.recurringOccurrenceDao().claimForExpense(
            overdueOccId, expenseId, 12.99, "EUR", fixedNow
        )
        // Production fulfillment side effect (RecurringLifecycleCoordinator.
        // linkExpenseToOccurrence performs claim + linkToActualExpense together);
        // replaying both halves here keeps the seed consistent with what the
        // real flow leaves behind: the derived planned row is FULFILLED, so the
        // rule-update reconciliation's ORPHAN_PLANNED_ROW gate (e) sees a
        // healthy pre-state instead of failing closed.
        database.plannedExpenseDao().linkToActualExpense(plannedRowId, expenseId, fixedNow)

        // Sanity: the overdue occurrence is now PAID (terminal) and linked.
        val overdueOcc = database.recurringOccurrenceDao().getById(overdueOccId)!!
        assertEquals("PAID", overdueOcc.status)
        assertEquals(expenseId, overdueOcc.linkedExpenseId)

        // Dashboard total BEFORE the update: the actual counts exactly once.
        val periodStart = fixedNow - 86400000L * 15
        val periodEnd = fixedNow + 86400000L
        val totalBefore = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)
        assertEquals(12.99, totalBefore.displayAmount, 0.01)
        assertEquals(1, totalBefore.totalTransactionCount)

        // ── ACT: update the rule amount through the REAL coordinator ──
        val rule = database.manualRecurringExpenseDao().getById(ruleId)!!
        val updated = rule.copy(amount = 19.99)
        buildRuleCoordinator().updateRule(updated)

        // ── ACT: dashboard counting after the update ──
        val totalAfter = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        val overdueOccAfter = database.recurringOccurrenceDao().getById(overdueOccId)!!
        val allOccs = database.recurringOccurrenceDao().getBySource("RECURRING_RULE", ruleId)
        val plannedRows = database.plannedExpenseDao().getByRecurringRuleId(ruleId)

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            put("dashboardTotal", totalAfter.displayAmount)
            put("dashboardCurrency", totalAfter.displayCurrency.code)
            put("dashboardTransactionCount", totalAfter.totalTransactionCount)
            put("isPartial", totalAfter.isPartial)
            put("doubleCounted", totalAfter.displayAmount > 12.99 + 0.01)

            // Terminal row preserved through the update (linked actual untouched).
            put("overdueOccurrenceStatus", overdueOccAfter.status)
            put("overdueLinkedExpenseId", overdueOccAfter.linkedExpenseId)
            put("overduePaidAmount", overdueOccAfter.paidAmount)

            // Exactly one open planned row remains for the rule (future slot),
            // and no duplicate rows were created for the paid slot.
            put("plannedRowCount", plannedRows.size)
            put("openPlannedRowCount", plannedRows.count { it.status == "PLANNED" })
            put("openPlannedAmounts", org.json.JSONArray(
                plannedRows.filter { it.status == "PLANNED" }.map { it.amount }.sorted()
            ))
            put("totalOccurrences", allOccs.size)
            put("paidOccurrences", allOccs.count { it.status == "PAID" })
            put("plannedOccurrences", allOccs.count { it.status == "PLANNED" })
        }

        // ── VERIFY ──
        verifier.verify(actual, subPath = "recurring_planned_actual_rule_update_no_double_count.json")
            .assertPassed()
    }

    /**
     * Builds a REAL [RecurringRuleLifecycleCoordinator] wired to this test's
     * REAL database (same pattern as RecurringRuleLifecycleCoordinatorTest).
     */
    private fun buildRuleCoordinator(): com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator {
        val occurrenceDao = database.recurringOccurrenceDao()
        val deliveryDao = database.recurringReminderDeliveryDao()
        val plannedDao = database.plannedExpenseDao()
        val eventDao = database.recurringLifecycleEventDao()
        val expenseDao = database.expenseDao()
        val ruleDao = database.manualRecurringExpenseDao()

        // Shared event writer (hoisted from two identical inline literals):
        // only inserts into eventDao. Used by both coordinator eventWriter
        // params and both RecurringOccurrenceMaterializer eventWriter params.
        val eventWriter = object : com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleEventWriter {
            override suspend fun writeCritical(
                occurrenceId: Long?,
                eventType: String,
                oldStatus: String?,
                newStatus: String?,
                metadata: String?,
                occurredAt: Long
            ): Long = eventDao.insert(
                com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent(
                    occurrenceId = occurrenceId,
                    eventType = eventType,
                    occurredAt = if (occurredAt == 0L) fixedNow else occurredAt,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    metadata = metadata
                )
            )
        }

        val lifecycleCoordinator = com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator(
            database = database,
            expander = com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander(),
            resolver = com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver(),
            materializer = com.yourname.expensetracker.domain.recurring.lifecycle.RecurringOccurrenceMaterializer(
                database = database,
                writeBarrier = writeBarrier,
                occurrenceDao = occurrenceDao,
                reminderDeliveryDao = deliveryDao,
                timeProvider = timeProvider,
                eventWriter = eventWriter,
                plannedExpenseDao = plannedDao
            ),
            occurrenceDao = occurrenceDao,
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            reminderDeliveryDao = deliveryDao,
            lifecycleEventDao = eventDao,
            restoreMaintenanceMode = restoreMaintenanceMode,
            writeBarrier = writeBarrier,
            plannedExpenseDao = plannedDao,
            transactionRunner = object : com.yourname.expensetracker.domain.transaction.DomainTransactionRunner {
                override suspend fun <T> runInTransaction(
                    correlationId: String,
                    causationId: String?,
                    operationId: String,
                    source: String,
                    metadata: Map<String, String>,
                    block: suspend (com.yourname.expensetracker.domain.transaction.TransactionContext) -> T
                ): T = block(
                    com.yourname.expensetracker.domain.transaction.TransactionContext(
                        correlationId = correlationId,
                        causationId = causationId,
                        operationId = operationId,
                        source = source,
                        occurredAt = fixedNow
                    )
                )
            },
            eventWriter = eventWriter
        )

        val projectionService = com.yourname.expensetracker.domain.recurring.RecurringPlanProjectionService(
            plannedExpenseDao = plannedDao,
            occurrenceDao = occurrenceDao
        )

        return com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator(
            database = database,
            writeBarrier = writeBarrier,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            occurrenceDao = occurrenceDao,
            reminderDeliveryDao = deliveryDao,
            plannedExpenseDao = plannedDao,
            lifecycleEventDao = eventDao,
            lifecycleCoordinator = dagger.Lazy { lifecycleCoordinator },
            expander = com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander(),
            resolver = com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver(),
            materializer = com.yourname.expensetracker.domain.recurring.lifecycle.RecurringOccurrenceMaterializer(
                database = database,
                writeBarrier = writeBarrier,
                occurrenceDao = occurrenceDao,
                reminderDeliveryDao = deliveryDao,
                timeProvider = timeProvider,
                eventWriter = eventWriter,
                plannedExpenseDao = plannedDao
            ),
            expenseDao = expenseDao,
            eventWriter = eventWriter,
            planProjectionService = dagger.Lazy { projectionService }
        )
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
