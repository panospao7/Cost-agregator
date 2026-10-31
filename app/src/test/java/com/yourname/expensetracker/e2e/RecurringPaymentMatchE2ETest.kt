package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpense
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
import com.yourname.expensetracker.domain.recurring.lifecycle.RoomRecurringLifecycleEventWriter
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.golden.GoldenTestBase
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

/**
 * E2E Test 3: Recurring Rule -> Occurrence Generation -> Payment Match
 *
 * Wires the REAL RecurringLifecycleCoordinator with real expander/resolver/materializer
 * on a real in-memory Room database. The fixed clock ([fixedNow]) is 2026-04-16 12:00 UTC and
 * the JVM zone is pinned to UTC, so the first occurrence is due 2026-04-16 00:00 UTC.
 *
 * Proves:
 * 1. generateOccurrences() over [start of day of fixedNow, fixedNow + 90d) creates three
 *    PLANNED occurrences (Apr 16, May 16, Jun 16) and one DUE_DAY reminder delivery each
 *    (the Apr 16 delivery is already past due, so it is created only because the test opts in
 *    with allowPastDueReminderDeliveries).
 * 2. generateOccurrences() does NOT create planned-expense rows (those come from
 *    RecurringPlanProjectionService); the test seeds one planned row for the Apr 16
 *    occurrence itself, so "plannedExpensesCreated" stays 0.
 * 3. linkExpenseToOccurrence() matches an actual same-day, same-merchant, same-amount,
 *    same-currency payment to exactly the Apr 16 occurrence (linking is intentionally limited
 *    to occurrences due on the expense's local calendar day).
 * 4. That occurrence becomes PAID (linked to the expense, paid amount recorded), the seeded
 *    planned row becomes FULFILLED and linked to the expense, and the open reminder delivery
 *    of that occurrence is CANCELLED while the May/Jun deliveries stay SCHEDULED.
 * 5. The dashboard counts the actual payment once (not doubled with the planned occurrences).
 *
 * Not proven here: the coordinator's lifecycle eventWriter is a relaxed mock, so the link
 * lifecycle events (OCCURRENCE_PAID, PLANNED_FULFILLED, REMINDER_SUPPRESSED_PAID) are not
 * asserted. The materializer's own event writer is the real Room-backed writer.
 */
class RecurringPaymentMatchE2ETest : GoldenTestBase() {

    /**
     * Production date helpers read ZoneId.systemDefault(); pin it to UTC so the occurrence day
     * boundaries are host independent. Applied as an instance rule (not a class rule) because
     * the Robolectric runner is the one that honours instance rules for sure.
     */
    @get:Rule
    val utcZone = PinnedUtcTimeZoneRule()

    private lateinit var recurringCoordinator: RecurringLifecycleCoordinator
    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "e2e_recurring_payment_match",
        numericTolerance = 0.01,
        // Auto-generated row id; its correctness is asserted through
        // "paidOccurrenceLinkedExpenseIdMatches" against the real inserted expense id.
        ignoredFields = setOf("paidOccurrenceLinkedExpenseId")
    )

    @Before
    override fun setUp() {
        super.setUp()

        // Real recurring lifecycle coordinator with real sub-components
        val materializer = RecurringOccurrenceMaterializer(
            database = database,
            writeBarrier = writeBarrier,
            occurrenceDao = database.recurringOccurrenceDao(),
            reminderDeliveryDao = database.recurringReminderDeliveryDao(),
            timeProvider = timeProvider,
            eventWriter = RoomRecurringLifecycleEventWriter(
                dao = database.recurringLifecycleEventDao(),
                timeProvider = timeProvider,
                writeBarrier = writeBarrier
            ),
            plannedExpenseDao = database.plannedExpenseDao()
        )

        val installmentEligibility = com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility(
            dagger.Lazy {
                com.yourname.expensetracker.data.repository.InstallmentEntryLinkReaderImpl(
                    database.installmentPlanDao()
                )
            }
        )

        recurringCoordinator = RecurringLifecycleCoordinator(
            database = database,
            expander = RecurringOccurrenceExpander(),
            resolver = OccurrenceConflictResolver(installmentEligibility),
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
            // Relaxed mock: link lifecycle events are NOT asserted by this test.
            eventWriter = mockk(relaxed = true),
            installmentEligibility = installmentEligibility
        )

        // Multi-currency repository for dashboard verification
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
    fun `recurring rule generates occurrences and links actual payment`() = runTest {
        // Guard: the whole scenario assumes the UTC pin took effect.
        assertEquals("UTC", ZoneId.systemDefault().id)

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
        // startDate is the START of the fixedNow day so the Apr 16 slot (midnight) is not
        // filtered out by the expander (it keeps only dueDate >= startDate).
        val genResult = recurringCoordinator.generateOccurrences(
            ruleId = ruleId,
            startDate = TimePeriodUtils.getStartOfDay(fixedNow),
            endDate = fixedNow + 86400000L * 90, // 3 months
            options = com.yourname.expensetracker.domain.recurring.lifecycle.OccurrenceGenerationOptions(
                createReminderDeliveries = true,
                reminderWindows = listOf("DUE_DAY"),
                generationSource = com.yourname.expensetracker.domain.recurring.lifecycle.OccurrenceGenerationSource.TEST,
                // The Apr 16 DUE_DAY reminder (00:00) is before fixedNow (12:00); without this
                // flag the materializer skips it and nothing would be left to suppress.
                allowPastDueReminderDeliveries = true
            )
        )

        // ── Verify occurrences created ──
        val occurrences = database.recurringOccurrenceDao().getBySource("RECURRING_RULE", ruleId)
        // Number of planned rows created BY GENERATION (expected 0: planned rows come from
        // RecurringPlanProjectionService, not from generateOccurrences).
        val plannedExpenses = occurrences.mapNotNull { occ ->
            database.plannedExpenseDao().getBySourceOccurrenceKey(occ.occurrenceKey)
        }

        assertEquals(3, occurrences.size)
        val aprilOccurrence = occurrences.first() // getBySource orders by dueDate
        // 2026-04-16T00:00:00Z, hard-coded oracle for the UTC-pinned zone.
        assertEquals(1776297600000L, aprilOccurrence.dueDate)
        assertEquals("PLANNED", aprilOccurrence.status)

        // ── SEED: planned-expense row for the Apr 16 occurrence (as the projection service would) ──
        val plannedRowId = database.plannedExpenseDao().insertPlannedExpense(PlannedExpense(
            description = "Netflix",
            amount = 12.99,
            currency = "EUR",
            date = aprilOccurrence.dueDate,
            sourceRecurringRuleId = ruleId,
            sourceOccurrenceKey = aprilOccurrence.occurrenceKey,
            openSourceOccurrenceKey = aprilOccurrence.occurrenceKey,
            status = "PLANNED",
            createdAt = fixedNow,
            updatedAt = fixedNow
        ))
        assertTrue("planned row must be inserted", plannedRowId > 0)

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
        val plannedAfterLink = database.plannedExpenseDao().getBySourceOccurrenceKey(aprilOccurrence.occurrenceKey)
        val deliveriesAfter = database.recurringReminderDeliveryDao()
            .getByOccurrenceIds(occurrencesAfter.map { it.id })
            .sortedBy { it.scheduledAt }
        val aprilDeliveries = deliveriesAfter.filter { it.occurrenceId == aprilOccurrence.id }
        val suppressedReminderCount = aprilDeliveries.count { it.status == "CANCELLED" }

        // ── Headline claims, asserted directly so the test fails loudly before the JSON compare ──
        assertTrue("linkExpenseToOccurrence must match the Apr 16 occurrence", linked)
        assertEquals(1, paidOccurrences.size)
        assertEquals(aprilOccurrence.occurrenceKey, paidOccurrences.single().occurrenceKey)
        assertEquals(actualExpenseId, paidOccurrences.single().linkedExpenseId)
        assertNotNull("planned row must still exist", plannedAfterLink)
        assertEquals(plannedRowId, plannedAfterLink!!.id)
        assertEquals("FULFILLED", plannedAfterLink.status)
        assertEquals(actualExpenseId, plannedAfterLink.linkedActualExpenseId)
        assertEquals(1, aprilDeliveries.size)
        assertEquals("CANCELLED", aprilDeliveries.single().status)
        assertEquals("expense_linked:$actualExpenseId", aprilDeliveries.single().failureReason)
        assertEquals(listOf("CANCELLED", "SCHEDULED", "SCHEDULED"), deliveriesAfter.map { it.status })

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
            put("paidOccurrenceLinkedExpenseIdMatches", paidOccurrences.firstOrNull()?.linkedExpenseId == actualExpenseId)

            // Planned expense fulfilment
            put("plannedStatusAfterLink", plannedAfterLink.status)
            put("plannedLinkedActualExpenseIdMatches", plannedAfterLink.linkedActualExpenseId == actualExpenseId)

            // Reminder suppression
            put("suppressedReminderCount", suppressedReminderCount)
            put("reminderStatuses", JSONArray().apply {
                deliveriesAfter.forEach { put(it.status) }
            })

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
