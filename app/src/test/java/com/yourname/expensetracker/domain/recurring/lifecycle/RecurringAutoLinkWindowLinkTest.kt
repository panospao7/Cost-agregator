package com.yourname.expensetracker.domain.recurring.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.InstallmentEntryLinkReaderImpl
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
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
 * DR-11: an expense may auto-link to a RECURRING_RULE occurrence up to 1 day before and up to 3 days
 * after its due day, shrunk by the rule's period (weekly 1/2, biweekly and monthly 1/3, irregular 0/0).
 * Every other source stays same-day. The nearest occurrence wins and a tie goes to the earlier due
 * date. The post-update keep-check uses the same window.
 *
 * Harness: REAL in-memory Room [AppDatabase] (Robolectric), REAL barrier over a mocked
 * [RestoreMaintenanceMode], REAL coordinator. The rule coordinator behind the DR-03 advance is a
 * relaxed mock (the advance is outside this slice). Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringAutoLinkWindowLinkTest {

    private lateinit var database: AppDatabase
    private lateinit var ruleDao: ManualRecurringExpenseDao
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var eventDao: RecurringLifecycleEventDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var lifecycle: RecurringLifecycleCoordinator

    private val clockNow = at(DUE, 12)
    private val timeProvider = object : TimeProvider {
        override fun now() = clockNow
    }

    companion object {
        /** A Thursday in September, away from every common DST transition. */
        private val DUE: LocalDate = LocalDate.of(2026, 9, 10)

        private fun at(day: LocalDate, hour: Int = 6): Long =
            day.atStartOfDay(ZoneId.systemDefault()).plusHours(hour.toLong()).toInstant().toEpochMilli()

        private fun startOf(day: LocalDate): Long = at(day, 0)
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ruleDao = database.manualRecurringExpenseDao()
        occurrenceDao = database.recurringOccurrenceDao()
        eventDao = database.recurringLifecycleEventDao()
        expenseDao = database.expenseDao()
        val deliveryDao = database.recurringReminderDeliveryDao()
        val plannedDao = database.plannedExpenseDao()

        val maintenance = mockk<RestoreMaintenanceMode>(relaxed = true).apply {
            every { currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
            every { isWritesAllowed() } returns true
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
        val installmentEligibility = RecurringInstallmentEligibility(
            dagger.Lazy { InstallmentEntryLinkReaderImpl(database.installmentPlanDao()) }
        )
        lifecycle = RecurringLifecycleCoordinator(
            database = database,
            expander = RecurringOccurrenceExpander(),
            resolver = OccurrenceConflictResolver(installmentEligibility),
            materializer = RecurringOccurrenceMaterializer(
                database = database,
                writeBarrier = barrier,
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
            restoreMaintenanceMode = maintenance,
            writeBarrier = barrier,
            plannedExpenseDao = plannedDao,
            transactionRunner = mockk(relaxed = true),
            eventWriter = eventWriter,
            installmentEligibility = installmentEligibility,
            ruleCoordinator = dagger.Lazy { mockk<RecurringRuleLifecycleCoordinator>(relaxed = true) }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** The rule's own nextDate is parked far away so the DR-03 advance never matches these cases. */
    private suspend fun seedRule(id: Long = 1L, frequency: RecurrenceFrequency) {
        ruleDao.insert(
            ManualRecurringExpense(
                id = id,
                merchant = "Netflix",
                amount = 15.99,
                currency = "EUR",
                frequency = frequency,
                nextDate = startOf(DUE.plusYears(5)),
                createdAt = startOf(DUE),
                isActive = true
            )
        )
    }

    private suspend fun seedOccurrence(
        due: LocalDate,
        ruleId: Long = 1L,
        sourceType: String = "RECURRING_RULE",
        frequency: String = "MONTHLY"
    ): Long = occurrenceDao.insert(
        RecurringOccurrence(
            sourceType = sourceType,
            sourceId = ruleId,
            occurrenceKey = "$sourceType|$ruleId|${startOf(due)}|$frequency",
            dueDate = startOf(due),
            status = "PLANNED",
            expectedAmount = 15.99,
            expectedCurrency = "EUR",
            frequency = frequency,
            merchant = "Netflix",
            createdAt = startOf(due),
            updatedAt = startOf(due)
        )
    )

    private suspend fun seedExpense(day: LocalDate): Long = expenseDao.insert(
        Expense(
            amount = 15.99,
            currency = "EUR",
            merchant = "Netflix",
            transactionType = TransactionType.PURCHASE,
            date = at(day),
            createdAt = at(day),
            source = "test"
        )
    )

    private suspend fun linkedOccurrenceOf(expenseId: Long): Long? =
        occurrenceDao.getByLinkedExpenseId(expenseId)?.id

    // ── weekly (window 1 early / 2 late) ────────────────────────────────────

    @Test
    fun `DR-11 weekly rule links an expense two days late`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.WEEKLY)
        val occ = seedOccurrence(DUE, frequency = "WEEKLY")
        val expense = seedExpense(DUE.plusDays(2))

        assertTrue(lifecycle.linkExpenseToOccurrence(expense))

        assertEquals(occ, linkedOccurrenceOf(expense))
        assertEquals("PAID", occurrenceDao.getById(occ)!!.status)
    }

    @Test
    fun `DR-11 weekly rule does not link an expense three days late`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.WEEKLY)
        val occ = seedOccurrence(DUE, frequency = "WEEKLY")
        val expense = seedExpense(DUE.plusDays(3))

        assertFalse(lifecycle.linkExpenseToOccurrence(expense))

        assertNull(linkedOccurrenceOf(expense))
        assertEquals("PLANNED", occurrenceDao.getById(occ)!!.status)
    }

    @Test
    fun `DR-11 weekly rule links an expense one day early`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.WEEKLY)
        val occ = seedOccurrence(DUE, frequency = "WEEKLY")
        val expense = seedExpense(DUE.minusDays(1))

        assertTrue(lifecycle.linkExpenseToOccurrence(expense))

        assertEquals(occ, linkedOccurrenceOf(expense))
    }

    @Test
    fun `DR-11 weekly rule does not link an expense two days early`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.WEEKLY)
        val occ = seedOccurrence(DUE, frequency = "WEEKLY")
        val expense = seedExpense(DUE.minusDays(2))

        assertFalse(lifecycle.linkExpenseToOccurrence(expense))

        assertEquals("PLANNED", occurrenceDao.getById(occ)!!.status)
    }

    @Test
    fun `DR-11 same day still links for a weekly rule`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.WEEKLY)
        val occ = seedOccurrence(DUE, frequency = "WEEKLY")
        val expense = seedExpense(DUE)

        assertTrue(lifecycle.linkExpenseToOccurrence(expense))

        assertEquals(occ, linkedOccurrenceOf(expense))
    }

    // ── monthly / biweekly (window 1 early / 3 late) ────────────────────────

    @Test
    fun `DR-11 monthly rule links an expense three days late`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.MONTHLY)
        val occ = seedOccurrence(DUE)
        val expense = seedExpense(DUE.plusDays(3))

        assertTrue(lifecycle.linkExpenseToOccurrence(expense))

        assertEquals(occ, linkedOccurrenceOf(expense))
    }

    @Test
    fun `DR-11 monthly rule does not link an expense four days late`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.MONTHLY)
        val occ = seedOccurrence(DUE)
        val expense = seedExpense(DUE.plusDays(4))

        assertFalse(lifecycle.linkExpenseToOccurrence(expense))

        assertNull(linkedOccurrenceOf(expense))
        assertEquals("PLANNED", occurrenceDao.getById(occ)!!.status)
    }

    @Test
    fun `DR-11 monthly rule links one day early but not two`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.MONTHLY)
        val occ = seedOccurrence(DUE)
        val twoEarly = seedExpense(DUE.minusDays(2))
        val oneEarly = seedExpense(DUE.minusDays(1))

        assertFalse(lifecycle.linkExpenseToOccurrence(twoEarly))
        assertTrue(lifecycle.linkExpenseToOccurrence(oneEarly))

        assertEquals(occ, linkedOccurrenceOf(oneEarly))
        assertNull(linkedOccurrenceOf(twoEarly))
    }

    @Test
    fun `DR-11 biweekly rule links an expense three days late but not four`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.BIWEEKLY)
        val occ = seedOccurrence(DUE, frequency = "BIWEEKLY")
        val fourLate = seedExpense(DUE.plusDays(4))
        val threeLate = seedExpense(DUE.plusDays(3))

        assertFalse(lifecycle.linkExpenseToOccurrence(fourLate))
        assertTrue(lifecycle.linkExpenseToOccurrence(threeLate))

        assertEquals(occ, linkedOccurrenceOf(threeLate))
    }

    // ── irregular / unresolved period: same day only ────────────────────────

    @Test
    fun `DR-11 irregular rule links the same day and never the next day`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.IRREGULAR)
        val occ = seedOccurrence(DUE, frequency = "IRREGULAR")
        val nextDay = seedExpense(DUE.plusDays(1))
        val dayBefore = seedExpense(DUE.minusDays(1))
        val sameDay = seedExpense(DUE)

        assertFalse(lifecycle.linkExpenseToOccurrence(nextDay))
        assertFalse(lifecycle.linkExpenseToOccurrence(dayBefore))
        assertTrue(lifecycle.linkExpenseToOccurrence(sameDay))

        assertEquals(occ, linkedOccurrenceOf(sameDay))
    }

    @Test
    fun `DR-11 an unreadable frequency with no rule row is same-day only`() = runTest(timeout = 60.seconds) {
        // No rule row and a frequency value that is not a RecurrenceFrequency: period 0.
        val occ = seedOccurrence(DUE, ruleId = 77L, frequency = "DAILY")
        val nextDay = seedExpense(DUE.plusDays(1))
        val sameDay = seedExpense(DUE)

        assertFalse(lifecycle.linkExpenseToOccurrence(nextDay))
        assertTrue(lifecycle.linkExpenseToOccurrence(sameDay))

        assertEquals(occ, linkedOccurrenceOf(sameDay))
    }

    @Test
    fun `DR-11 a missing rule row falls back to the occurrence frequency column`() = runTest(timeout = 60.seconds) {
        val occ = seedOccurrence(DUE, ruleId = 78L, frequency = "WEEKLY")
        val threeLate = seedExpense(DUE.plusDays(3))
        val twoLate = seedExpense(DUE.plusDays(2))

        assertFalse(lifecycle.linkExpenseToOccurrence(threeLate))
        assertTrue(lifecycle.linkExpenseToOccurrence(twoLate))

        assertEquals(occ, linkedOccurrenceOf(twoLate))
    }

    // ── non-rule source: same day only ──────────────────────────────────────

    @Test
    fun `DR-11 a non-rule source occurrence stays same-day only`() = runTest(timeout = 60.seconds) {
        val occ = seedOccurrence(DUE, ruleId = 5L, sourceType = "SUBSCRIPTION", frequency = "MONTHLY")
        val oneLate = seedExpense(DUE.plusDays(1))
        val oneEarly = seedExpense(DUE.minusDays(1))
        val sameDay = seedExpense(DUE)

        assertFalse(lifecycle.linkExpenseToOccurrence(oneLate))
        assertFalse(lifecycle.linkExpenseToOccurrence(oneEarly))
        assertEquals("PLANNED", occurrenceDao.getById(occ)!!.status)
        assertTrue(lifecycle.linkExpenseToOccurrence(sameDay))

        assertEquals(occ, linkedOccurrenceOf(sameDay))
    }

    // ── candidate selection ─────────────────────────────────────────────────

    @Test
    fun `DR-11 the nearest due date wins even when it is the later one`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.MONTHLY)
        val first = seedOccurrence(DUE)
        val second = seedOccurrence(DUE.plusDays(2))
        // Expense on the 12th: 2 days after the first, exact day of the second.
        val expense = seedExpense(DUE.plusDays(2))

        assertTrue(lifecycle.linkExpenseToOccurrence(expense))

        assertEquals(second, linkedOccurrenceOf(expense))
        assertEquals("PLANNED", occurrenceDao.getById(first)!!.status)
    }

    @Test
    fun `DR-11 the nearest due date wins when it is the earlier one`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.MONTHLY)
        val first = seedOccurrence(DUE)
        val second = seedOccurrence(DUE.plusDays(2))
        // Expense on the 9th: 1 day before the first, 3 days before the second (outside the early window).
        val expense = seedExpense(DUE.minusDays(1))

        assertTrue(lifecycle.linkExpenseToOccurrence(expense))

        assertEquals(first, linkedOccurrenceOf(expense))
        assertEquals("PLANNED", occurrenceDao.getById(second)!!.status)
    }

    @Test
    fun `DR-11 on a tie the earlier due date wins`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.MONTHLY)
        // Insert the later one first so insertion order cannot decide the tie.
        val later = seedOccurrence(DUE.plusDays(2))
        val earlier = seedOccurrence(DUE)
        // Expense on the 11th: 1 day after the earlier due date, 1 day before the later one.
        val expense = seedExpense(DUE.plusDays(1))

        assertTrue(lifecycle.linkExpenseToOccurrence(expense))

        assertEquals(earlier, linkedOccurrenceOf(expense))
        assertEquals("PLANNED", occurrenceDao.getById(later)!!.status)
    }

    @Test
    fun `DR-11 first come wins - a late link is not displaced by a later same-day expense`() =
        runTest(timeout = 60.seconds) {
            seedRule(frequency = RecurrenceFrequency.WEEKLY)
            val occ = seedOccurrence(DUE, frequency = "WEEKLY")
            val lateFirst = seedExpense(DUE.plusDays(2))
            val sameDayLater = seedExpense(DUE)

            assertTrue(lifecycle.linkExpenseToOccurrence(lateFirst))
            // The occurrence is already linked: the exact-day expense finds no target and no
            // displacement happens, even though it is nearer to the due date.
            assertFalse(lifecycle.linkExpenseToOccurrence(sameDayLater))

            assertEquals(occ, linkedOccurrenceOf(lateFirst))
            assertNull(linkedOccurrenceOf(sameDayLater))
            assertEquals(lateFirst, occurrenceDao.getById(occ)!!.linkedExpenseId)
            assertEquals("PAID", occurrenceDao.getById(occ)!!.status)
        }

    // ── keep-check after an expense update ──────────────────────────────────

    @Test
    fun `DR-11 editing a late-linked expense without changing its date keeps the link`() =
        runTest(timeout = 60.seconds) {
            seedRule(frequency = RecurrenceFrequency.WEEKLY)
            val occ = seedOccurrence(DUE, frequency = "WEEKLY")
            val expenseId = seedExpense(DUE.plusDays(2))
            assertTrue(lifecycle.linkExpenseToOccurrence(expenseId))

            // Edit the amount inside the 10% tolerance; the date stays two days late.
            val expense = expenseDao.getById(expenseId)!!
            expenseDao.update(expense.copy(amount = 16.50))
            val result = lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)

            assertTrue(result is RecurringExpenseReconcileResult.UpdatedLinkedSnapshot)
            assertEquals(occ, linkedOccurrenceOf(expenseId))
            assertEquals("PAID", occurrenceDao.getById(occ)!!.status)
        }

    @Test
    fun `DR-11 moving a late-linked expense outside the window unlinks it`() = runTest(timeout = 60.seconds) {
        seedRule(frequency = RecurrenceFrequency.WEEKLY)
        val occ = seedOccurrence(DUE, frequency = "WEEKLY")
        val expenseId = seedExpense(DUE.plusDays(2))
        assertTrue(lifecycle.linkExpenseToOccurrence(expenseId))

        // Four days late is outside the weekly keep window S(P) of three (D-4; A(P) late is two).
        val expense = expenseDao.getById(expenseId)!!
        expenseDao.update(expense.copy(date = at(DUE.plusDays(4))))
        val result = lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)

        assertTrue(result is RecurringExpenseReconcileResult.Unlinked)
        assertNull(linkedOccurrenceOf(expenseId))
        assertEquals("PLANNED", occurrenceDao.getById(occ)!!.status)
    }

    @Test
    fun `DR-11 moving a monthly late-linked expense from three to eight days late unlinks it`() =
        runTest(timeout = 60.seconds) {
            seedRule(frequency = RecurrenceFrequency.MONTHLY)
            val occ = seedOccurrence(DUE)
            val expenseId = seedExpense(DUE.plusDays(3))
            assertTrue(lifecycle.linkExpenseToOccurrence(expenseId))
            // Control: untouched date is kept.
            assertTrue(
                lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)
                    is RecurringExpenseReconcileResult.UpdatedLinkedSnapshot
            )

            // Eight days late is outside the monthly keep window S(P) of seven (D-4).
            val expense = expenseDao.getById(expenseId)!!
            expenseDao.update(expense.copy(date = at(DUE.plusDays(8))))
            val result = lifecycle.reconcileExpenseLinkAfterUpdate(expenseId)

            assertTrue(result is RecurringExpenseReconcileResult.Unlinked)
            assertNull(linkedOccurrenceOf(expenseId))
            assertEquals("PLANNED", occurrenceDao.getById(occ)!!.status)
        }
}
