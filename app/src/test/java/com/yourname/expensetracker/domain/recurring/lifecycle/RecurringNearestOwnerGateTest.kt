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
 * FC-PAID-UNLINKED S1, INV-6 (nearest-owner gate, D-3), INV-7 (auto tier), INV-14 (decline exclusion
 * on the auto tier) and INV-15 (keep window S(P), D-4).
 *
 * Expected outcomes come from the spec's acceptance examples (AE-3, AE-4, AE-5, AE-6, AE-13, AE-22,
 * AE-23, AE-8, AE-25, AE-7), re-based on September dates inside the monthly auto window A = 1 early /
 * 3 late and the suggestion window S = 7/7. Where a spec example is outside A (it only yields a
 * suggestion, an S2 concern), the test uses a nearest bill that is inside A so that the gate itself
 * is what decides: without the gate the farther bill would have been linked.
 *
 * Harness: REAL in-memory Room [AppDatabase] (Robolectric), REAL barrier over a mocked
 * [RestoreMaintenanceMode], REAL coordinator, as in [RecurringAutoLinkWindowLinkTest]. The rule
 * coordinator behind the DR-03 advance is a relaxed mock. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringNearestOwnerGateTest {

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

        private const val MERCHANT = "Acme Properties"

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
            readBarrier = io.mockk.mockk<com.yourname.expensetracker.data.backup.DatabaseReadBarrier>(relaxed = true),
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
    private suspend fun seedRule(
        id: Long,
        merchant: String = MERCHANT,
        amount: Double = 400.0,
        active: Boolean = true
    ) {
        ruleDao.insert(
            ManualRecurringExpense(
                id = id,
                merchant = merchant,
                amount = amount,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = startOf(DUE.plusYears(5)),
                createdAt = startOf(DUE),
                isActive = active
            )
        )
    }

    private suspend fun seedOccurrence(
        ruleId: Long,
        due: LocalDate,
        status: String = "PLANNED",
        expected: Double = 400.0,
        merchant: String? = MERCHANT
    ): Long = occurrenceDao.insert(
        RecurringOccurrence(
            sourceType = "RECURRING_RULE",
            sourceId = ruleId,
            occurrenceKey = "RECURRING_RULE|$ruleId|${startOf(due)}|MONTHLY",
            dueDate = startOf(due),
            status = status,
            expectedAmount = expected,
            expectedCurrency = "EUR",
            frequency = "MONTHLY",
            merchant = merchant,
            createdAt = startOf(due),
            updatedAt = startOf(due)
        )
    )

    private suspend fun seedExpense(
        day: LocalDate,
        amount: Double = 400.0,
        currency: String = "EUR"
    ): Long = expenseDao.insert(
        Expense(
            amount = amount,
            currency = currency,
            merchant = MERCHANT,
            transactionType = TransactionType.PURCHASE,
            date = at(day),
            createdAt = at(day),
            source = "test"
        )
    )

    private suspend fun declinePair(occurrenceId: Long, metadata: String?) {
        eventDao.insert(
            RecurringLifecycleEvent(
                occurrenceId = occurrenceId,
                eventType = "LINK_SUGGESTION_DECLINED",
                occurredAt = clockNow,
                oldStatus = null,
                newStatus = null,
                metadata = metadata
            )
        )
    }

    private suspend fun status(occurrenceId: Long): String = occurrenceDao.getById(occurrenceId)!!.status

    private suspend fun linkedOccurrenceOf(expenseId: Long): Long? =
        occurrenceDao.getByLinkedExpenseId(expenseId)?.id

    // ── AE-3: nearest same-merchant bill owns the payment ───────────────────

    @Test
    fun `AE-3 a payment two days before the nearest bill auto-links neither of two same-merchant bills`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L); seedRule(2L)
            val a = seedOccurrence(1L, DUE.plusDays(10))   // due Sep 20
            val b = seedOccurrence(2L, DUE.plusDays(15))   // due Sep 25
            val e = seedExpense(DUE.plusDays(8))            // Sep 18

            assertFalse(lifecycle.linkExpenseToOccurrence(e))

            assertNull(linkedOccurrenceOf(e))
            assertEquals("PLANNED", status(a))
            assertEquals("PLANNED", status(b))
        }

    @Test
    fun `AE-3 control - a payment on the nearest bill's due day links it and leaves the other bill planned`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L); seedRule(2L)
            val a = seedOccurrence(1L, DUE.plusDays(10))
            val b = seedOccurrence(2L, DUE.plusDays(15))
            val e = seedExpense(DUE.plusDays(10))

            assertTrue(lifecycle.linkExpenseToOccurrence(e))

            assertEquals(a, linkedOccurrenceOf(e))
            assertEquals("PAID", status(a))
            assertEquals("PLANNED", status(b))
        }

    // ── AE-4: a SKIPPED nearest bill blocks ─────────────────────────────────

    @Test
    fun `AE-4 a skipped nearest bill blocks the auto link to an open bill inside the auto window`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L); seedRule(2L)
            val skipped = seedOccurrence(1L, DUE, status = "SKIPPED")        // |d| 0, nearest
            val open = seedOccurrence(2L, DUE.minusDays(1))                   // d = +1, inside A (late 3)
            val e = seedExpense(DUE)

            assertFalse(lifecycle.linkExpenseToOccurrence(e))

            assertNull(linkedOccurrenceOf(e))
            assertEquals("SKIPPED", status(skipped))
            assertEquals("PLANNED", status(open))
        }

    @Test
    fun `AE-4 control - without the skipped neighbour the same payment links the open bill`() =
        runTest(timeout = 60.seconds) {
            seedRule(2L)
            val open = seedOccurrence(2L, DUE.minusDays(1))
            val e = seedExpense(DUE)

            assertTrue(lifecycle.linkExpenseToOccurrence(e))

            assertEquals(open, linkedOccurrenceOf(e))
        }

    // ── AE-5: a farther SKIPPED bill does not block; a nearer one does ──────

    @Test
    fun `AE-5 a farther skipped bill does not block the nearest open bill`() = runTest(timeout = 60.seconds) {
        seedRule(1L); seedRule(2L)
        val b = seedOccurrence(2L, DUE.plusDays(10))                          // due Sep 20, nearest
        val a = seedOccurrence(1L, DUE.plusDays(15), status = "SKIPPED")      // due Sep 25, farther
        val e = seedExpense(DUE.plusDays(10))

        assertTrue(lifecycle.linkExpenseToOccurrence(e))

        assertEquals(b, linkedOccurrenceOf(e))
        assertEquals("SKIPPED", status(a))
    }

    @Test
    fun `AE-5 the nearest open bill outside the auto window stays planned with no link`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L); seedRule(2L)
            val b = seedOccurrence(2L, DUE.plusDays(10))
            seedOccurrence(1L, DUE.plusDays(15), status = "SKIPPED")
            val e = seedExpense(DUE.plusDays(8))                              // d = -2 to B: suggestion only

            assertFalse(lifecycle.linkExpenseToOccurrence(e))

            assertNull(linkedOccurrenceOf(e))
            assertEquals("PLANNED", status(b))
        }

    @Test
    fun `AE-5 second variant - a skipped bill one day away blocks a tied open bill that is due later`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L); seedRule(2L)
            val skipped = seedOccurrence(1L, DUE.plusDays(5), status = "SKIPPED")  // Sep 15, |d| 1, earlier due
            val open = seedOccurrence(2L, DUE.plusDays(7))                         // Sep 17, |d| 1, inside A
            val e = seedExpense(DUE.plusDays(6))                                    // Sep 16

            assertFalse(lifecycle.linkExpenseToOccurrence(e))

            assertNull(linkedOccurrenceOf(e))
            assertEquals("SKIPPED", status(skipped))
            assertEquals("PLANNED", status(open))
        }

    // ── MISSED owners block too; inactive-rule owners do not (INV-6 amended) ─

    @Test
    fun `INV-6 a missed nearest bill blocks the auto link to an open bill`() = runTest(timeout = 60.seconds) {
        seedRule(1L); seedRule(2L)
        val missed = seedOccurrence(1L, DUE, status = "MISSED")
        val open = seedOccurrence(2L, DUE.minusDays(1))
        val e = seedExpense(DUE)

        assertFalse(lifecycle.linkExpenseToOccurrence(e))

        assertNull(linkedOccurrenceOf(e))
        assertEquals("MISSED", status(missed))
        assertEquals("PLANNED", status(open))
    }

    // ── D-3 clarified: CANCELLED and IGNORED rows are not candidates ────────

    @Test
    fun `D-3 a cancelled same-day row of a deleted rule does not block the new rule's planned row`() =
        runTest(timeout = 60.seconds) {
            seedRule(2L)                                                      // rule 1 was deleted
            val cancelled = seedOccurrence(1L, DUE, status = "CANCELLED")     // older id, same day
            val planned = seedOccurrence(2L, DUE)
            val e = seedExpense(DUE)

            assertTrue(lifecycle.linkExpenseToOccurrence(e))

            assertEquals(planned, linkedOccurrenceOf(e))
            assertEquals("CANCELLED", status(cancelled))
            assertEquals("PAID", status(planned))
        }

    @Test
    fun `D-3 an ignored same-day row does not block the planned row`() = runTest(timeout = 60.seconds) {
        seedRule(1L); seedRule(2L)
        val ignored = seedOccurrence(1L, DUE, status = "IGNORED")
        val planned = seedOccurrence(2L, DUE)
        val e = seedExpense(DUE)

        assertTrue(lifecycle.linkExpenseToOccurrence(e))

        assertEquals(planned, linkedOccurrenceOf(e))
        assertEquals("IGNORED", status(ignored))
        assertEquals("PAID", status(planned))
    }

    @Test
    fun `INV-6 amended - a nearest open bill of a paused rule still auto links and is PAID without advancing`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L, active = false); seedRule(2L)
            val paused = seedOccurrence(1L, DUE)
            val open = seedOccurrence(2L, DUE.minusDays(1))
            val e = seedExpense(DUE)

            assertTrue(lifecycle.linkExpenseToOccurrence(e))

            assertEquals(paused, linkedOccurrenceOf(e))
            assertEquals("PAID", status(paused))
            assertEquals("PLANNED", status(open))
        }

    // ── AE-6: tie on distance goes to the closer amount ─────────────────────

    @Test
    fun `AE-6 two bills due the same day - the payment links the one with the closer amount`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L, amount = 400.0); seedRule(2L, amount = 420.0)
            val day = DUE.plusDays(15)
            // The farther-amount bill is inserted first so row order cannot decide the rank.
            val b = seedOccurrence(2L, day, expected = 420.0)
            val a = seedOccurrence(1L, day, expected = 400.0)
            val e = seedExpense(day, amount = 400.0)

            assertTrue(lifecycle.linkExpenseToOccurrence(e))

            assertEquals(a, linkedOccurrenceOf(e))
            assertEquals("PAID", status(a))
            assertEquals("PLANNED", status(b))
        }

    // ── AE-13: a PAID nearest bill blocks a duplicate ───────────────────────

    @Test
    fun `AE-13 a duplicate payment next to a paid bill does not pay another bill`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L); seedRule(2L)
            val paid = seedOccurrence(1L, DUE)
            val other = seedOccurrence(2L, DUE.minusDays(1))                  // d = +1 for both payments
            val first = seedExpense(DUE)
            assertTrue(lifecycle.linkExpenseToOccurrence(first))
            assertEquals(paid, linkedOccurrenceOf(first))

            val duplicate = seedExpense(DUE)

            assertFalse(lifecycle.linkExpenseToOccurrence(duplicate))

            assertNull(linkedOccurrenceOf(duplicate))
            assertEquals(first, occurrenceDao.getById(paid)!!.linkedExpenseId)
            assertEquals("PLANNED", status(other))
        }

    // ── INV-14: a declined pair leaves M(E) ─────────────────────────────────

    @Test
    fun `INV-14 a declined nearest pair falls through to the next bill`() = runTest(timeout = 60.seconds) {
        seedRule(1L); seedRule(2L)
        val a = seedOccurrence(1L, DUE)                                       // |d| 0, nearest
        val b = seedOccurrence(2L, DUE.minusDays(1))                          // d = +1, inside A
        val e = seedExpense(DUE)
        declinePair(a, """{"expenseId":$e,"source":"user_declined_match"}""")

        assertTrue(lifecycle.linkExpenseToOccurrence(e))

        assertEquals(b, linkedOccurrenceOf(e))
        assertEquals("PLANNED", status(a))
        assertEquals("PAID", status(b))
    }

    @Test
    fun `INV-14 control - the same setup without a decline links the nearest bill`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L); seedRule(2L)
            val a = seedOccurrence(1L, DUE)
            val b = seedOccurrence(2L, DUE.minusDays(1))
            val e = seedExpense(DUE)

            assertTrue(lifecycle.linkExpenseToOccurrence(e))

            assertEquals(a, linkedOccurrenceOf(e))
            assertEquals("PLANNED", status(b))
        }

    @Test
    fun `INV-14 a decline of a different expense does not exclude the pair`() = runTest(timeout = 60.seconds) {
        seedRule(1L)
        val a = seedOccurrence(1L, DUE)
        val e = seedExpense(DUE)
        val other = seedExpense(DUE.plusDays(1))
        declinePair(a, """{"expenseId":$other,"source":"user_declined_match"}""")

        assertTrue(lifecycle.linkExpenseToOccurrence(e))

        assertEquals(a, linkedOccurrenceOf(e))
    }

    @Test
    fun `INV-14 a malformed decline row is not a decline and the pair still links`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L)
            val a = seedOccurrence(1L, DUE)
            val e = seedExpense(DUE)
            declinePair(a, "not json")
            declinePair(a, null)
            declinePair(a, "{}")

            assertTrue(lifecycle.linkExpenseToOccurrence(e))

            assertEquals(a, linkedOccurrenceOf(e))
        }

    @Test
    fun `INV-14 a declined pair with no other bill gets no link`() = runTest(timeout = 60.seconds) {
        seedRule(1L)
        val a = seedOccurrence(1L, DUE)
        val e = seedExpense(DUE)
        declinePair(a, """{"expenseId":$e,"source":"user_declined_match"}""")

        assertFalse(lifecycle.linkExpenseToOccurrence(e))

        assertNull(linkedOccurrenceOf(e))
        assertEquals("PLANNED", status(a))
    }

    @Test
    fun `AE-23 an edit of a declined, never-linked expense does not relink it`() = runTest(timeout = 60.seconds) {
        seedRule(1L)
        val a = seedOccurrence(1L, DUE)
        val e = seedExpense(DUE)
        declinePair(a, """{"expenseId":$e,"source":"user_declined_match"}""")
        val expense = expenseDao.getById(e)!!
        expenseDao.update(expense.copy(notes = "edited note"))

        val result = lifecycle.reconcileExpenseLinkAfterUpdate(e)

        assertTrue("result=$result", result is RecurringExpenseReconcileResult.NoMatch)
        assertNull(linkedOccurrenceOf(e))
        assertEquals("PLANNED", status(a))
    }

    @Test
    fun `AE-23 control - the same edit without a decline relinks the expense`() = runTest(timeout = 60.seconds) {
        seedRule(1L)
        val a = seedOccurrence(1L, DUE)
        val e = seedExpense(DUE)
        val expense = expenseDao.getById(e)!!
        expenseDao.update(expense.copy(notes = "edited note"))

        val result = lifecycle.reconcileExpenseLinkAfterUpdate(e)

        assertTrue("result=$result", result is RecurringExpenseReconcileResult.Linked)
        assertEquals(a, linkedOccurrenceOf(e))
    }

    // ── INV-4 through the auto tier: AE-7, AE-8, AE-25 ──────────────────────

    @Test
    fun `AE-7 the auto tier links 360 and 440 against a 400 bill and refuses 359_99 and 441`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L)
            val a = seedOccurrence(1L, DUE)
            val tooLow = seedExpense(DUE, amount = 359.99)
            val tooHigh = seedExpense(DUE, amount = 441.0)

            assertFalse(lifecycle.linkExpenseToOccurrence(tooLow))
            assertFalse(lifecycle.linkExpenseToOccurrence(tooHigh))
            assertEquals("PLANNED", status(a))

            val low = seedExpense(DUE, amount = 360.0)
            assertTrue(lifecycle.linkExpenseToOccurrence(low))
            assertEquals(a, linkedOccurrenceOf(low))
        }

    @Test
    fun `AE-8 a payment in another currency never auto-links`() = runTest(timeout = 60.seconds) {
        seedRule(1L)
        val a = seedOccurrence(1L, DUE)
        val gbp = seedExpense(DUE, amount = 345.0, currency = "GBP")
        val gbpSameNumber = seedExpense(DUE, amount = 400.0, currency = "GBP")

        assertFalse(lifecycle.linkExpenseToOccurrence(gbp))
        assertFalse(lifecycle.linkExpenseToOccurrence(gbpSameNumber))

        assertEquals("PLANNED", status(a))
    }

    @Test
    fun `AE-25 an occurrence with a blank merchant is never linked`() = runTest(timeout = 60.seconds) {
        seedRule(1L, merchant = "")
        val a = seedOccurrence(1L, DUE, merchant = "")
        val blankNull = seedOccurrence(1L, DUE.plusDays(1), merchant = null)
        val e = seedExpense(DUE)

        assertFalse(lifecycle.linkExpenseToOccurrence(e))

        assertNull(linkedOccurrenceOf(e))
        assertEquals("PLANNED", status(a))
        assertEquals("PLANNED", status(blankNull))
    }

    // ── INV-15: keep window S(P) on edit and on bulk reconcile ──────────────

    @Test
    fun `AE-22 bulk reconcile keeps a confirmed link paid two days early`() = runTest(timeout = 60.seconds) {
        seedRule(1L)
        val a = seedOccurrence(1L, DUE)
        val e = seedExpense(DUE.minusDays(2))                                  // d = -2: outside A early 1
        assertFalse(lifecycle.linkExpenseToOccurrence(e))                      // the auto tier does not link it
        assertEquals(
            SpecificLinkResult.Linked,
            lifecycle.linkExpenseToSpecificOccurrence(a, e, "user_confirmed_match")
        )

        val result = lifecycle.reconcileAllLinkedExpensesAfterBulkUpdate("category_fix")

        assertEquals(1, result.total)
        assertEquals(1, result.updatedSnapshots)
        assertEquals(0, result.unlinked)
        assertEquals(0, result.relinked)
        assertEquals(a, linkedOccurrenceOf(e))
        assertEquals("PAID", status(a))
    }

    @Test
    fun `AE-22 bulk reconcile keeps a link at the seven day edge of the monthly window`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L)
            val a = seedOccurrence(1L, DUE)
            val early = seedExpense(DUE.minusDays(7))
            assertEquals(
                SpecificLinkResult.Linked,
                lifecycle.linkExpenseToSpecificOccurrence(a, early, "user_confirmed_match")
            )

            val result = lifecycle.reconcileAllLinkedExpensesAfterBulkUpdate("category_fix")

            assertEquals(1, result.updatedSnapshots)
            assertEquals(0, result.unlinked)
            assertEquals(a, linkedOccurrenceOf(early))
        }

    @Test
    fun `AE-22 failing boundary - bulk reconcile unlinks a link eight days early`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L)
            val a = seedOccurrence(1L, DUE)
            val e = seedExpense(DUE.minusDays(8))
            assertEquals(
                SpecificLinkResult.Linked,
                lifecycle.linkExpenseToSpecificOccurrence(a, e, "user_confirmed_match")
            )

            val result = lifecycle.reconcileAllLinkedExpensesAfterBulkUpdate("category_fix")

            assertEquals(1, result.unlinked)
            assertEquals(0, result.updatedSnapshots)
            assertNull(linkedOccurrenceOf(e))
            assertEquals("PLANNED", status(a))
        }

    @Test
    fun `INV-15 an amount edit to 430 keeps the link and an edit to 450 unlinks it`() =
        runTest(timeout = 60.seconds) {
            seedRule(1L)
            val a = seedOccurrence(1L, DUE)
            val e = seedExpense(DUE.minusDays(2))
            assertEquals(
                SpecificLinkResult.Linked,
                lifecycle.linkExpenseToSpecificOccurrence(a, e, "user_confirmed_match")
            )
            val expense = expenseDao.getById(e)!!

            expenseDao.update(expense.copy(amount = 430.0))
            assertTrue(lifecycle.reconcileExpenseLinkAfterUpdate(e) is RecurringExpenseReconcileResult.UpdatedLinkedSnapshot)
            assertEquals(430.0, occurrenceDao.getById(a)!!.paidAmount!!, 0.0)

            expenseDao.update(expense.copy(amount = 450.0))
            assertTrue(lifecycle.reconcileExpenseLinkAfterUpdate(e) is RecurringExpenseReconcileResult.Unlinked)
            assertNull(linkedOccurrenceOf(e))
            assertEquals("PLANNED", status(a))
        }
}
