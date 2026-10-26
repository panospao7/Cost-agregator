package com.yourname.expensetracker.domain.recurring.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.InstallmentEntryLinkReaderImpl
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.recurring.RecurringPlanProjectionService
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * R21 slice S1: rolling-horizon top-up, month-end anchor, shared claim/link path
 * (specific + automatic) and stale due-delivery collapse.
 *
 * Harness: REAL in-memory Room [AppDatabase] (Robolectric), REAL barrier over a mocked
 * [RestoreMaintenanceMode], REAL expander/resolver/materializer/projection service and
 * REAL coordinators. Only the lifecycle event writer seam is an in-memory-DAO fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringHorizonAndLinkR21Test {

    private lateinit var database: AppDatabase
    private lateinit var ruleDao: ManualRecurringExpenseDao
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var deliveryDao: RecurringReminderDeliveryDao
    private lateinit var plannedDao: PlannedExpenseDao
    private lateinit var eventDao: RecurringLifecycleEventDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var lifecycle: RecurringLifecycleCoordinator
    private lateinit var ruleCoordinator: RecurringRuleLifecycleCoordinator

    private var clockNow = dayOf(2026, 9, 19) + HALF_DAY
    private val timeProvider = object : TimeProvider {
        override fun now() = clockNow
    }

    companion object {
        private const val HALF_DAY = 12L * 60 * 60 * 1000
        private const val DAY = 24L * 60 * 60 * 1000

        private fun dayOf(y: Int, m: Int, d: Int): Long =
            LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ruleDao = database.manualRecurringExpenseDao()
        occurrenceDao = database.recurringOccurrenceDao()
        deliveryDao = database.recurringReminderDeliveryDao()
        plannedDao = database.plannedExpenseDao()
        eventDao = database.recurringLifecycleEventDao()
        expenseDao = database.expenseDao()

        val maintenance = io.mockk.mockk<RestoreMaintenanceMode>(relaxed = true).apply {
            io.mockk.every { currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
            io.mockk.every { isWritesAllowed() } returns true
        }
        val barrier = DatabaseWriteBarrier(maintenance)
        val eventWriter = object : RecurringLifecycleEventWriter {
            override suspend fun writeCritical(
                occurrenceId: Long?,
                eventType: String,
                oldStatus: String?,
                newStatus: String?,
                metadata: String?,
                occurredAt: Long
            ): Long = eventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = occurrenceId,
                    eventType = eventType,
                    occurredAt = if (occurredAt == 0L) clockNow else occurredAt,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    metadata = metadata
                )
            )
        }
        fun materializer() = RecurringOccurrenceMaterializer(
            database = database,
            writeBarrier = barrier,
            occurrenceDao = occurrenceDao,
            reminderDeliveryDao = deliveryDao,
            timeProvider = timeProvider,
            eventWriter = eventWriter,
            plannedExpenseDao = plannedDao
        )

        val installmentEligibility = RecurringInstallmentEligibility(
            dagger.Lazy { InstallmentEntryLinkReaderImpl(database.installmentPlanDao()) }
        )

        lifecycle = RecurringLifecycleCoordinator(
            database = database,
            expander = RecurringOccurrenceExpander(),
            resolver = OccurrenceConflictResolver(installmentEligibility),
            materializer = materializer(),
            occurrenceDao = occurrenceDao,
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            reminderDeliveryDao = deliveryDao,
            lifecycleEventDao = eventDao,
            restoreMaintenanceMode = maintenance,
            writeBarrier = barrier,
            plannedExpenseDao = plannedDao,
            transactionRunner = io.mockk.mockk(relaxed = true),
            eventWriter = eventWriter,
            installmentEligibility = installmentEligibility
        )
        val projection = RecurringPlanProjectionService(
            plannedExpenseDao = plannedDao,
            occurrenceDao = occurrenceDao,
            writeBarrier = barrier
        )
        ruleCoordinator = RecurringRuleLifecycleCoordinator(
            database = database,
            writeBarrier = barrier,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            occurrenceDao = occurrenceDao,
            reminderDeliveryDao = deliveryDao,
            plannedExpenseDao = plannedDao,
            lifecycleEventDao = eventDao,
            lifecycleCoordinator = dagger.Lazy { lifecycle },
            expander = RecurringOccurrenceExpander(),
            resolver = OccurrenceConflictResolver(installmentEligibility),
            materializer = materializer(),
            expenseDao = expenseDao,
            eventWriter = eventWriter,
            planProjectionService = dagger.Lazy { projection }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun seedRule(
        id: Long = 1L,
        nextDate: Long,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        isActive: Boolean = true
    ) {
        ruleDao.insert(
            ManualRecurringExpense(
                id = id,
                merchant = "Netflix",
                amount = 15.99,
                currency = "EUR",
                frequency = frequency,
                nextDate = nextDate,
                createdAt = nextDate,
                isActive = isActive
            )
        )
    }

    private suspend fun seedOccurrence(
        ruleId: Long = 1L,
        due: Long,
        status: String = "PLANNED",
        keySuffix: String = ""
    ): Long = occurrenceDao.insert(
        RecurringOccurrence(
            sourceType = "RECURRING_RULE",
            sourceId = ruleId,
            occurrenceKey = "RECURRING_RULE|$ruleId|$due|MONTHLY$keySuffix",
            dueDate = due,
            status = status,
            expectedAmount = 15.99,
            expectedCurrency = "EUR",
            frequency = "MONTHLY",
            merchant = "Netflix",
            createdAt = due,
            updatedAt = due
        )
    )

    private suspend fun seedDelivery(
        occurrenceId: Long,
        window: String,
        scheduledAt: Long,
        status: String = "SCHEDULED"
    ): Long = deliveryDao.insert(
        RecurringReminderDelivery(
            occurrenceId = occurrenceId,
            reminderWindow = window,
            scheduledAt = scheduledAt,
            status = status,
            createdAt = scheduledAt,
            updatedAt = scheduledAt
        )
    )

    /** Seeds the three default windows for an occurrence due at [due]; returns ids by window. */
    private suspend fun seedWindows(occurrenceId: Long, due: Long): Map<String, Long> = mapOf(
        "3_DAYS_BEFORE" to seedDelivery(occurrenceId, "3_DAYS_BEFORE", due - 3 * DAY),
        "DUE_DAY" to seedDelivery(occurrenceId, "DUE_DAY", due),
        "OVERDUE" to seedDelivery(occurrenceId, "OVERDUE", due + DAY)
    )

    private suspend fun seedExpense(date: Long, merchant: String = "Netflix"): Long =
        expenseDao.insert(
            Expense(
                amount = 15.99,
                currency = "EUR",
                merchant = merchant,
                transactionType = TransactionType.PURCHASE,
                date = date,
                createdAt = date,
                source = "test"
            )
        )

    private suspend fun occurrencesOf(ruleId: Long = 1L) =
        occurrenceDao.getBySource("RECURRING_RULE", ruleId)

    // ── topUpHorizon ────────────────────────────────────────────────────────

    @Test
    fun topUpHorizonKeepsMonthEndAnchorAcrossShortMonths() = runTest(timeout = 60.seconds) {
        val jan31 = dayOf(2027, 1, 31)
        clockNow = jan31 + HALF_DAY
        seedRule(nextDate = jan31)
        seedOccurrence(due = jan31)

        val inserted = ruleCoordinator.topUpHorizon(1L, clockNow)

        val dues = occurrencesOf().map { it.dueDate }.toSet()
        assertTrue("Feb must clamp to 28", dayOf(2027, 2, 28) in dues)
        assertTrue("Mar must recover anchor day 31", dayOf(2027, 3, 31) in dues)
        assertTrue(dayOf(2027, 4, 30) in dues)
        assertTrue(dayOf(2027, 5, 31) in dues)
        assertEquals(inserted + 1, dues.size)
        assertTrue("horizon is bounded to 12 months", dues.all { it < dayOf(2028, 1, 31) })
        assertEquals(1, eventDao.getEventsByType("RULE_HORIZON_TOPPED_UP").size)
    }

    @Test
    fun secondTopUpInsertsNothingAndWritesNoSecondEvent() = runTest(timeout = 60.seconds) {
        val jan31 = dayOf(2027, 1, 31)
        clockNow = jan31 + HALF_DAY
        seedRule(nextDate = jan31)
        seedOccurrence(due = jan31)

        assertTrue(ruleCoordinator.topUpHorizon(1L, clockNow) > 0)
        val afterFirst = occurrencesOf()
        val plannedAfterFirst = plannedDao.getByRecurringRuleId(1L)

        assertEquals(0, ruleCoordinator.topUpHorizon(1L, clockNow))

        assertEquals(afterFirst, occurrencesOf())
        assertEquals(plannedAfterFirst, plannedDao.getByRecurringRuleId(1L))
        assertEquals(1, eventDao.getEventsByType("RULE_HORIZON_TOPPED_UP").size)
    }

    @Test
    fun thirteenMissedMonthsCatchUpIsBoundedToTwelveAndCreatesNoPastDueReminders() =
        runTest(timeout = 60.seconds) {
            val today = dayOf(2026, 9, 19)
            clockNow = today + HALF_DAY
            val lastKnown = dayOf(2025, 8, 19) // 13 months before today
            seedRule(nextDate = lastKnown)
            val seededId = seedOccurrence(due = lastKnown)

            val inserted = ruleCoordinator.topUpHorizon(1L, clockNow)

            assertTrue(inserted > 0)
            val all = occurrencesOf()
            val catchUp = all.filter { it.id != seededId && it.dueDate <= today }
            // Strictly after today-12m (Sep 19 2025): Oct 2025 .. Sep 2026 inclusive = exactly 12.
            assertEquals(12, catchUp.size)
            assertEquals(dayOf(2025, 10, 19), catchUp.minOf { it.dueDate })
            assertEquals(today, catchUp.maxOf { it.dueDate })
            assertTrue(catchUp.all { it.status == "PLANNED" })
            assertTrue(
                "nothing older than 12 months is back-filled",
                all.filter { it.id != seededId }.all { it.dueDate > dayOf(2025, 9, 19) }
            )
            val deliveries = deliveryDao.getByOccurrenceIds(all.map { it.id })
            assertTrue(
                "no past-due reminder deliveries",
                deliveries.none { it.scheduledAt < clockNow }
            )
        }

    @Test
    fun firstSlotIsKeptWhenNextDateCarriesATimeOfDay() = runTest(timeout = 60.seconds) {
        // No existing occurrences: the first slot is rule.nextDate itself (15:30 local).
        val firstSlotDay = dayOf(2026, 10, 5)
        seedRule(nextDate = firstSlotDay + 15 * 60 * 60 * 1000L + 30 * 60 * 1000L)

        val inserted = ruleCoordinator.topUpHorizon(1L, clockNow)

        val dues = occurrencesOf().map { it.dueDate }
        assertTrue("first slot (start of day) must not be dropped", firstSlotDay in dues)
        // Oct 5 2026 .. Sep 5 2027 inclusive; horizon end Sep 19 2027 is exclusive.
        assertEquals(12, inserted)
        assertEquals(12, dues.size)
    }

    @Test
    fun catchUpFirstSlotIsKeptWhenNextDateCarriesATimeOfDay() = runTest(timeout = 60.seconds) {
        val firstSlotDay = dayOf(2025, 10, 19) // inside the 12-month catch-up window
        seedRule(nextDate = firstSlotDay + 15 * 60 * 60 * 1000L + 30 * 60 * 1000L)

        ruleCoordinator.topUpHorizon(1L, clockNow)

        val dues = occurrencesOf().map { it.dueDate }
        assertEquals(firstSlotDay, dues.minOf { it })
        // Oct 19 2025 .. Aug 19 2027 inclusive = 23 monthly slots.
        assertEquals(23, dues.size)
    }

    @Test
    fun quarterlyRuleMovedFromDay31ToDay5UsesTheNewDayAfterTopUp() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.QUARTERLY, nextDate = dayOf(2027, 1, 5))
        seedOccurrence(due = dayOf(2026, 1, 31))
        seedOccurrence(due = dayOf(2026, 4, 30))
        seedOccurrence(due = dayOf(2026, 7, 31))
        seedOccurrence(due = dayOf(2026, 10, 5))
        seedOccurrence(due = dayOf(2027, 1, 5))

        ruleCoordinator.topUpHorizon(1L, clockNow)

        val dues = occurrencesOf().map { it.dueDate }.toSet()
        assertTrue("must continue on the new day 5", dayOf(2027, 4, 5) in dues)
        assertTrue(dayOf(2027, 7, 5) in dues)
        assertFalse("old anchor day 31 must not resurrect", dayOf(2027, 4, 30) in dues)
        assertFalse(dayOf(2027, 7, 31) in dues)
    }

    @Test
    fun monthEndAnchorSurvivesClampedFebruaryInExistingOccurrences() = runTest(timeout = 60.seconds) {
        val feb28 = dayOf(2027, 2, 28)
        clockNow = feb28 + HALF_DAY
        seedRule(nextDate = dayOf(2027, 1, 31))
        seedOccurrence(due = dayOf(2027, 1, 31))
        seedOccurrence(due = feb28)

        ruleCoordinator.topUpHorizon(1L, clockNow)

        val dues = occurrencesOf().map { it.dueDate }.toSet()
        assertTrue("Mar must recover anchor day 31", dayOf(2027, 3, 31) in dues)
        assertFalse("Mar must not drift to 28", dayOf(2027, 3, 28) in dues)
        assertTrue(dayOf(2027, 4, 30) in dues)
    }

    @Test
    fun topUpNeverPaysANewSlotWithAnExpenseAlreadyLinkedToAnotherOccurrence() =
        runTest(timeout = 60.seconds) {
            val aug = dayOf(2026, 8, 19)
            val sep = dayOf(2026, 9, 19)
            seedRule(nextDate = aug)
            val augId = seedOccurrence(due = aug)
            // The September payment is explicitly assigned to the unpaid August bill.
            val expenseId = seedExpense(sep + HALF_DAY / 2)
            assertEquals(
                SpecificLinkResult.Linked,
                lifecycle.linkExpenseToSpecificOccurrence(augId, expenseId, "TEST_SPECIFIC")
            )

            ruleCoordinator.topUpHorizon(1L, clockNow)

            val sepOccurrence = occurrencesOf().single { it.dueDate == sep }
            assertEquals("PLANNED", sepOccurrence.status)
            assertNull(sepOccurrence.linkedExpenseId)
            assertEquals(augId, occurrenceDao.getByLinkedExpenseId(expenseId)!!.id)
            assertEquals(1, occurrencesOf().count { it.linkedExpenseId == expenseId })
            assertTrue(
                "new slot's reminders must not be suppressed",
                deliveryDao.getByOccurrenceIds(listOf(sepOccurrence.id)).none { it.status == "CANCELLED" }
            )
        }

    @Test
    fun topUpDoesNothingForInactiveOrIrregularRules() = runTest(timeout = 60.seconds) {
        seedRule(id = 1L, nextDate = dayOf(2026, 10, 1), isActive = false)
        seedRule(id = 2L, nextDate = dayOf(2026, 10, 1), frequency = RecurrenceFrequency.IRREGULAR)

        assertEquals(0, ruleCoordinator.topUpHorizon(1L, clockNow))
        assertEquals(0, ruleCoordinator.topUpHorizon(2L, clockNow))
        assertEquals(0, ruleCoordinator.topUpHorizon(404L, clockNow))

        assertTrue(occurrencesOf(1L).isEmpty())
        assertTrue(occurrencesOf(2L).isEmpty())
        assertTrue(eventDao.getEventsByType("RULE_HORIZON_TOPPED_UP").isEmpty())
    }

    // ── R21 F1: one expense, at most one occurrence (all resolver paths) ───

    @Test
    fun activateRuleNeverPaysARegeneratedSlotWithAnExpenseAlreadyLinkedToAnOverdueOccurrence() =
        runTest(timeout = 60.seconds) {
            val aug = dayOf(2026, 8, 19)
            val sep = dayOf(2026, 9, 19)
            seedRule(nextDate = sep, isActive = false)
            val augId = seedOccurrence(due = aug)
            // The September payment is explicitly assigned to the unpaid August bill.
            val expenseId = seedExpense(sep + HALF_DAY / 2)
            assertEquals(
                SpecificLinkResult.Linked,
                lifecycle.linkExpenseToSpecificOccurrence(augId, expenseId, "TEST_SPECIFIC")
            )

            ruleCoordinator.activateRule(1L)

            val sepOccurrence = occurrencesOf().single { it.dueDate == sep }
            assertEquals("PLANNED", sepOccurrence.status)
            assertNull(sepOccurrence.linkedExpenseId)
            assertNull(sepOccurrence.paidAmount)
            assertEquals(augId, occurrenceDao.getByLinkedExpenseId(expenseId)!!.id)
            assertEquals(1, occurrencesOf().count { it.linkedExpenseId == expenseId })
        }

    @Test
    fun updateRuleDayChangeNeverPaysARegeneratedSlotWithAnAlreadyLinkedExpense() =
        runTest(timeout = 60.seconds) {
            val aug = dayOf(2026, 8, 19)
            val sep19 = dayOf(2026, 9, 19)
            val sep22 = dayOf(2026, 9, 22)
            seedRule(nextDate = sep19)
            val augId = seedOccurrence(due = aug)
            // Paid on Sep 22 but explicitly assigned to the unpaid August bill.
            val expenseId = seedExpense(sep22 + HALF_DAY / 2)
            assertEquals(
                SpecificLinkResult.Linked,
                lifecycle.linkExpenseToSpecificOccurrence(augId, expenseId, "TEST_SPECIFIC")
            )

            // The day changes 19 -> 22: the regenerated Sep 22 slot would match the expense.
            ruleCoordinator.updateRule(ruleDao.getById(1L)!!.copy(nextDate = sep22))

            val regenerated = occurrencesOf().single { it.dueDate == sep22 }
            assertEquals("PLANNED", regenerated.status)
            assertNull(regenerated.linkedExpenseId)
            assertEquals(augId, occurrenceDao.getByLinkedExpenseId(expenseId)!!.id)
            assertEquals(1, occurrencesOf().count { it.linkedExpenseId == expenseId })
        }

    // ── R21 F3: month-end anchor with a deliberate 31 -> 30 change ─────────

    @Test
    fun quarterlyRuleMovedFromDay31ToDay30KeepsDay30AfterTopUp() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.QUARTERLY, nextDate = dayOf(2027, 1, 30))
        seedOccurrence(due = dayOf(2026, 7, 31))
        seedOccurrence(due = dayOf(2026, 10, 31))
        seedOccurrence(due = dayOf(2027, 1, 30)) // Jan has 31 days: 30 is the real anchor

        ruleCoordinator.topUpHorizon(1L, clockNow)

        val dues = occurrencesOf().map { it.dueDate }.toSet()
        assertTrue(dayOf(2027, 4, 30) in dues)
        assertTrue("must continue on day 30", dayOf(2027, 7, 30) in dues)
        assertFalse("old anchor day 31 must not resurrect", dayOf(2027, 7, 31) in dues)
    }

    @Test
    fun monthlyMonthEndRunStillRecoversDay31AcrossFebAndApril() = runTest(timeout = 60.seconds) {
        val apr30 = dayOf(2027, 4, 30)
        clockNow = apr30 + HALF_DAY
        seedRule(nextDate = dayOf(2027, 1, 31))
        seedOccurrence(due = dayOf(2027, 1, 31))
        seedOccurrence(due = dayOf(2027, 2, 28))
        seedOccurrence(due = dayOf(2027, 3, 31))
        seedOccurrence(due = apr30)

        ruleCoordinator.topUpHorizon(1L, clockNow)

        val dues = occurrencesOf().map { it.dueDate }.toSet()
        assertTrue("every row is a month-end day: anchor 31 recovered", dayOf(2027, 5, 31) in dues)
        assertFalse(dayOf(2027, 5, 30) in dues)
    }

    // ── R21 S2: specific link created on the due date survives expense edits ─

    @Test
    fun specificLinkOnTheDueDateSurvivesAnExpenseUpdateThatKeepsTheDate() =
        runTest(timeout = 60.seconds) {
            val due = dayOf(2026, 9, 19)
            seedRule(nextDate = due)
            val occurrenceId = seedOccurrence(due = due)
            // MarkRecurringBillPaidUseCase dates the expense on the occurrence due date.
            val expenseId = seedExpense(due)
            assertEquals(
                SpecificLinkResult.Linked,
                lifecycle.linkExpenseToSpecificOccurrence(
                    occurrenceId,
                    expenseId,
                    RecurringLifecycleCoordinator.SOURCE_DIRECT_EXPENSE_LINK
                )
            )

            val result = lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)

            assertTrue(result is RecurringExpenseReconcileResult.UpdatedLinkedSnapshot)
            val kept = occurrenceDao.getById(occurrenceId)!!
            assertEquals("PAID", kept.status)
            assertEquals(expenseId, kept.linkedExpenseId)
        }

    // ── specific / automatic link ───────────────────────────────────────────

    @Test
    fun specificLinkThenAutoLinkProducesExactlyOnePaidOccurrence() = runTest(timeout = 60.seconds) {
        val due = dayOf(2026, 9, 19)
        seedRule(nextDate = due)
        val a = seedOccurrence(due = due)
        // A second open row on the same day/merchant/amount: the automatic matcher
        // would happily claim it for the same expense if the helper did not guard.
        val b = seedOccurrence(due = due, keySuffix = "|dup")
        val deliveryId = seedDelivery(a, "DUE_DAY", due + HALF_DAY)
        val expenseId = seedExpense(due + HALF_DAY / 2)

        assertEquals(
            SpecificLinkResult.Linked,
            lifecycle.linkExpenseToSpecificOccurrence(a, expenseId, "TEST_SPECIFIC")
        )
        assertFalse(lifecycle.linkExpenseToOccurrence(expenseId))

        val paid = occurrencesOf().filter { it.status == "PAID" }
        assertEquals(1, paid.size)
        assertEquals(a, paid.single().id)
        assertEquals(expenseId, paid.single().linkedExpenseId)
        assertEquals("PLANNED", occurrenceDao.getById(b)!!.status)
        assertEquals("CANCELLED", deliveryDao.getById(deliveryId)!!.status)
        assertTrue(eventDao.getEventsByType("OCCURRENCE_PAID").single().metadata!!.contains("expenseId"))
    }

    @Test
    fun autoLinkStillLinksTheMatchingOccurrenceAndIsIdempotent() = runTest(timeout = 60.seconds) {
        val due = dayOf(2026, 9, 19)
        seedRule(nextDate = due)
        val a = seedOccurrence(due = due)
        val expenseId = seedExpense(due + HALF_DAY / 2)

        assertTrue(lifecycle.linkExpenseToOccurrence(expenseId))
        assertFalse(lifecycle.linkExpenseToOccurrence(expenseId))

        val linked = occurrenceDao.getById(a)!!
        assertEquals("PAID", linked.status)
        assertEquals(expenseId, linked.linkedExpenseId)
        assertEquals(1, eventDao.getEventsByType("OCCURRENCE_PAID").size)
        assertTrue(
            eventDao.getEventsByType("REMINDER_SUPPRESSION_NOOP").single().metadata!!
                .contains(RecurringLifecycleCoordinator.SOURCE_DIRECT_EXPENSE_LINK)
        )
    }

    @Test
    fun alreadyLinkedExpenseIsReportedAndNeverLinkedTwice() = runTest(timeout = 60.seconds) {
        val due = dayOf(2026, 9, 19)
        seedRule(nextDate = due)
        val a = seedOccurrence(due = due)
        val b = seedOccurrence(due = dayOf(2026, 10, 19))
        val expenseId = seedExpense(due)

        assertEquals(SpecificLinkResult.Linked, lifecycle.linkExpenseToSpecificOccurrence(a, expenseId, "T"))

        assertEquals(
            SpecificLinkResult.AlreadyLinkedToThis,
            lifecycle.linkExpenseToSpecificOccurrence(a, expenseId, "T")
        )
        assertEquals(
            SpecificLinkResult.AlreadyLinkedElsewhere,
            lifecycle.linkExpenseToSpecificOccurrence(b, expenseId, "T")
        )
        // Another expense cannot take an occurrence already held by a different expense:
        // the OCCURRENCE is not open (AlreadyLinkedElsewhere is only "this expense is
        // linked to a different occurrence").
        val other = seedExpense(due)
        assertEquals(
            SpecificLinkResult.NotOpen,
            lifecycle.linkExpenseToSpecificOccurrence(a, other, "T")
        )
        assertNull(occurrenceDao.getByLinkedExpenseId(other))
        assertEquals("PLANNED", occurrenceDao.getById(b)!!.status)
        assertEquals(1, occurrencesOf().count { it.status == "PAID" })
    }

    @Test
    fun detailedLinkReportsDistinctReasonForAlreadyLinkedExpense() = runTest(timeout = 60.seconds) {
        val due = dayOf(2026, 9, 19)
        seedRule(nextDate = due)
        val a = seedOccurrence(due = due)
        seedOccurrence(due = due, keySuffix = "|dup")
        val expenseId = seedExpense(due + HALF_DAY / 2)
        assertEquals(SpecificLinkResult.Linked, lifecycle.linkExpenseToSpecificOccurrence(a, expenseId, "T"))

        assertEquals(
            RecurringExpenseReconcileResult.NoMatch(expenseId, "expense_already_linked"),
            lifecycle.linkExpenseToOccurrenceDetailed(expenseId)
        )
    }

    @Test
    fun provenanceSourceIsBoundedAndFallsBackToUnknownSource() = runTest(timeout = 60.seconds) {
        fun sanitize(source: String) = RecurringLifecycleCoordinator.sanitizeProvenanceSource(source)
        assertEquals("direct_expense_link", sanitize(RecurringLifecycleCoordinator.SOURCE_DIRECT_EXPENSE_LINK))
        assertEquals("transaction_created:sms-1.0", sanitize("transaction_created:sms-1.0"))
        assertEquals(RecurringLifecycleCoordinator.SOURCE_UNKNOWN, sanitize("free text with spaces"))
        assertEquals(RecurringLifecycleCoordinator.SOURCE_UNKNOWN, sanitize(""))
        assertEquals(RecurringLifecycleCoordinator.SOURCE_UNKNOWN, sanitize("a".repeat(65)))
        assertEquals(RecurringLifecycleCoordinator.SOURCE_UNKNOWN, sanitize("bad\nline"))
        assertEquals("a".repeat(64), sanitize("a".repeat(64)))

        // End to end: free text never reaches persisted event metadata and the link still succeeds.
        val due = dayOf(2026, 9, 19)
        seedRule(nextDate = due)
        val a = seedOccurrence(due = due)
        val expenseId = seedExpense(due + HALF_DAY / 2)
        assertEquals(
            SpecificLinkResult.Linked,
            lifecycle.linkExpenseToSpecificOccurrence(a, expenseId, "Rent for Mr Smith")
        )
        val metadata = eventDao.getEventsByType("REMINDER_SUPPRESSION_NOOP").single().metadata!!
        assertTrue(metadata.contains("unknown_source"))
        assertFalse(metadata.contains("Smith"))
    }

    @Test
    fun specificLinkRejectsNonOpenOccurrencesAndIneligibleExpenses() = runTest(timeout = 60.seconds) {
        val due = dayOf(2026, 9, 19)
        seedRule(nextDate = due)
        val skipped = seedOccurrence(due = due, status = "SKIPPED")
        val open = seedOccurrence(due = dayOf(2026, 10, 19))
        val expenseId = seedExpense(due)
        val transfer = expenseDao.insert(
            Expense(
                amount = 15.99, currency = "EUR", merchant = "Netflix",
                transactionType = TransactionType.TRANSFER, date = due, createdAt = due, source = "test"
            )
        )

        assertEquals(SpecificLinkResult.NotOpen, lifecycle.linkExpenseToSpecificOccurrence(skipped, expenseId, "T"))
        assertEquals(SpecificLinkResult.NotOpen, lifecycle.linkExpenseToSpecificOccurrence(9_999L, expenseId, "T"))
        assertEquals(SpecificLinkResult.ExpenseNotEligible, lifecycle.linkExpenseToSpecificOccurrence(open, transfer, "T"))
        assertEquals(SpecificLinkResult.ExpenseNotEligible, lifecycle.linkExpenseToSpecificOccurrence(open, 9_999L, "T"))

        assertEquals("PLANNED", occurrenceDao.getById(open)!!.status)
        assertNull(occurrenceDao.getByLinkedExpenseId(expenseId))
    }

    @Test
    fun findEarliestOpenOccurrenceHonoursUpperBound() = runTest(timeout = 60.seconds) {
        seedRule(nextDate = dayOf(2026, 7, 19))
        seedOccurrence(due = dayOf(2026, 6, 19), status = "PAID")
        val jul = seedOccurrence(due = dayOf(2026, 7, 19))
        seedOccurrence(due = dayOf(2026, 8, 19))

        assertEquals(jul, lifecycle.findEarliestOpenOccurrence(1L, null)!!.id)
        assertEquals(jul, lifecycle.findEarliestOpenOccurrence(1L, dayOf(2026, 7, 19))!!.id)
        assertNull(lifecycle.findEarliestOpenOccurrence(1L, dayOf(2026, 7, 18)))
        assertNull(lifecycle.findEarliestOpenOccurrence(404L, null))
    }

    // ── collapseStaleDueDeliveries ──────────────────────────────────────────

    @Test
    fun threeMissedMonthsCollapseToOneOpenDueDeliveryPerRule() = runTest(timeout = 60.seconds) {
        val jun = dayOf(2026, 6, 19)
        val jul = dayOf(2026, 7, 19)
        val aug = dayOf(2026, 8, 19)
        seedRule(id = 1L, nextDate = jun)
        seedRule(id = 2L, nextDate = jun)
        val junId = seedOccurrence(due = jun)
        val julId = seedOccurrence(due = jul)
        val augId = seedOccurrence(due = aug)
        val augWindows = seedWindows(augId, aug)
        seedWindows(junId, jun)
        seedWindows(julId, jul)
        // A different rule with one stale delivery keeps it (per-rule collapse).
        val otherOcc = seedOccurrence(ruleId = 2L, due = aug)
        val otherDelivery = seedDelivery(otherOcc, "DUE_DAY", aug)

        val cancelled = ruleCollapse()

        assertEquals(8, cancelled)
        val open = deliveryDao.getPendingDeliveriesForPlannedOccurrences(clockNow)
        assertEquals(setOf(augWindows.getValue("OVERDUE"), otherDelivery), open.map { it.id }.toSet())
        val rule1Open = open.filter { it.occurrenceId in setOf(junId, julId, augId) }
        assertEquals(1, rule1Open.size)

        val all = deliveryDao.getByOccurrenceIds(listOf(junId, julId, augId))
        val superseded = all.filter { it.failureReason == RecurringLifecycleCoordinator.REASON_STALE_WINDOW_SUPERSEDED }
        val expired = all.filter { it.failureReason == RecurringLifecycleCoordinator.REASON_STALE_WINDOW_EXPIRED }
        assertEquals(6, superseded.size)
        assertEquals(2, expired.size)
        assertTrue(expired.all { it.reminderWindow == "OVERDUE" && it.occurrenceId != augId })
        assertTrue((superseded + expired).all { it.status == "CANCELLED" })

        // Second pass is a no-op.
        assertEquals(0, ruleCollapse())
    }

    @Test
    fun collapseNeverTouchesClaimedDeliveriesOrUpcomingOccurrences() = runTest(timeout = 60.seconds) {
        val aug = dayOf(2026, 8, 19)
        val upcoming = dayOf(2026, 9, 21)
        seedRule(nextDate = aug)
        val augId = seedOccurrence(due = aug)
        val upcomingId = seedOccurrence(due = upcoming)
        val windows = seedWindows(augId, aug)
        val claimed = seedDelivery(augId, "SPECIAL", aug + 2 * DAY, status = "CLAIMED")
        // Upcoming occurrence whose early window is already due: not "overdue", must survive.
        val upcomingDelivery = seedDelivery(upcomingId, "3_DAYS_BEFORE", upcoming - 3 * DAY)

        ruleCollapse()

        assertEquals("CLAIMED", deliveryDao.getById(claimed)!!.status)
        assertNull(deliveryDao.getById(claimed)!!.failureReason)
        assertEquals("SCHEDULED", deliveryDao.getById(upcomingDelivery)!!.status)
        assertEquals("SCHEDULED", deliveryDao.getById(windows.getValue("OVERDUE"))!!.status)
        assertEquals("CANCELLED", deliveryDao.getById(windows.getValue("DUE_DAY"))!!.status)
        assertEquals("CANCELLED", deliveryDao.getById(windows.getValue("3_DAYS_BEFORE"))!!.status)
    }

    @Test
    fun collapseIgnoresDeliveriesOfNonPlannedOccurrences() = runTest(timeout = 60.seconds) {
        val aug = dayOf(2026, 8, 19)
        seedRule(nextDate = aug)
        val paidId = seedOccurrence(due = aug, status = "PAID")
        val windows = seedWindows(paidId, aug)

        assertEquals(0, ruleCollapse())

        assertTrue(windows.values.all { deliveryDao.getById(it)!!.status == "SCHEDULED" })
    }

    @Test
    fun collapseWritesOneBoundedSummaryEventOnlyWhenSomethingWasCancelled() = runTest(timeout = 60.seconds) {
        val aug = dayOf(2026, 8, 19)
        seedRule(nextDate = aug)
        val augId = seedOccurrence(due = aug)
        seedWindows(augId, aug)

        assertEquals(2, ruleCollapse())

        val summary = eventDao.getEventsByType("STALE_DUE_DELIVERIES_COLLAPSED").single()
        assertNull(summary.occurrenceId)
        val metadata = summary.metadata!!.replace(" ", "")
        assertTrue(metadata.contains("\"supersededCount\":2"))
        assertTrue(metadata.contains("\"expiredCount\":0"))

        assertEquals(0, ruleCollapse())
        assertEquals(1, eventDao.getEventsByType("STALE_DUE_DELIVERIES_COLLAPSED").size)
    }

    // ── R21 review: "already recorded" (skip) leaves nothing open ────────────

    @Test
    fun skipOccurrenceCancelsPlannedProjectionAndSuppressesOpenDeliveries() = runTest(timeout = 60.seconds) {
        val due = dayOf(2026, 9, 19)
        seedRule(nextDate = due)
        val occurrenceId = seedOccurrence(due = due)
        val key = occurrenceDao.getById(occurrenceId)!!.occurrenceKey
        val plannedId = plannedDao.insertPlannedExpense(
            PlannedExpense(
                description = "Netflix",
                amount = 15.99,
                currency = "EUR",
                date = due,
                createdAt = due,
                sourceOccurrenceKey = key,
                sourceRecurringRuleId = 1L,
                status = "PLANNED",
                openSourceOccurrenceKey = key
            )
        )
        val windows = seedWindows(occurrenceId, due)
        val expensesBefore = expenseDao.countAllExpenses()

        lifecycle.skipOccurrence(occurrenceId)

        assertEquals("SKIPPED", occurrenceDao.getById(occurrenceId)!!.status)
        val planned = plannedDao.getBySourceOccurrenceKey(key)!!
        assertEquals(plannedId, planned.id)
        assertEquals("CANCELLED", planned.status)
        assertNull(planned.openSourceOccurrenceKey)
        assertTrue(
            "no open delivery remains",
            windows.values.all { deliveryDao.getById(it)!!.status == "CANCELLED" }
        )
        assertEquals(expensesBefore, expenseDao.countAllExpenses())
        assertEquals(1, eventDao.getEventsByType("OCCURRENCE_SKIPPED").size)
    }

    @Test
    fun cancellingOrMissingAnOccurrenceDoesNotTouchItsPlannedProjection() = runTest(timeout = 60.seconds) {
        val due = dayOf(2026, 9, 19)
        seedRule(nextDate = due)
        val occurrenceId = seedOccurrence(due = due)
        val key = occurrenceDao.getById(occurrenceId)!!.occurrenceKey
        plannedDao.insertPlannedExpense(
            PlannedExpense(
                description = "Netflix", amount = 15.99, currency = "EUR", date = due, createdAt = due,
                sourceOccurrenceKey = key, sourceRecurringRuleId = 1L, status = "PLANNED",
                openSourceOccurrenceKey = key
            )
        )

        lifecycle.markOccurrenceMissed(occurrenceId)

        assertEquals("PLANNED", plannedDao.getBySourceOccurrenceKey(key)!!.status)
    }

    private suspend fun ruleCollapse(): Int = lifecycle.collapseStaleDueDeliveries(clockNow)
}
