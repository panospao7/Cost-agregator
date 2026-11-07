package com.yourname.expensetracker.domain.transaction.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectCategory
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectTriggerType
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.RefundLinkRefusal
import com.yourname.expensetracker.domain.transaction.RefundLinkResult
import com.yourname.expensetracker.domain.transaction.RefundUnlinkResult
import com.yourname.expensetracker.domain.transaction.RefundWriteFailure
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.noLinkedReceiptsLinkService
import dagger.Lazy
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Refund netting S3 (write path): [TransactionLifecycleCoordinator.linkRefund],
 * [TransactionLifecycleCoordinator.unlinkRefund], the deleteExpense link cleanup and the
 * INV-12 edit lock. Covers INV-2, 4, 5, 12, 13, 14, 15, 19, 22, 23 and AE-1..6, 11, 13..18, 27.
 *
 * Real in-memory Room database, real [DatabaseWriteBarrier] with a mutable maintenance mode and
 * the real coordinator. The planner, the post-commit runner and the other collaborators are
 * mocks. Synthetic data only. Expected values come from the domain spec, never from production
 * code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionLifecycleCoordinatorRefundLinkTest {

    private lateinit var db: AppDatabase
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var planner: TransactionSideEffectPlanner
    private lateinit var runner: PostCommitActionRunner
    private lateinit var barrier: DatabaseWriteBarrier
    private lateinit var coordinator: TransactionLifecycleCoordinator
    private var mode: RestoreMaintenanceMode.Mode = RestoreMaintenanceMode.Mode.NORMAL
    private var cancelOnModeCheck = false
    private var base = 0L

    private val day = 24L * 60L * 60L * 1000L

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        timeProvider = FakeTimeProvider.forDate(2026, 5, 1)
        base = timeProvider.now()

        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } answers {
            if (cancelOnModeCheck) throw CancellationException("synthetic cancellation")
            mode
        }
        every { maintenanceMode.isWritesAllowed() } answers { mode == RestoreMaintenanceMode.Mode.NORMAL }
        barrier = DatabaseWriteBarrier(maintenanceMode)

        planner = mockk(relaxed = true)
        every { planner.planUpdated(any(), any(), any(), any()) } answers {
            nonEmptyBatch("updated-${firstArg<Long>()}", firstArg<Long>())
        }
        every { planner.planDeleted(any(), any(), any()) } answers {
            nonEmptyBatch("deleted-${firstArg<Long>()}", firstArg<Long>())
        }
        runner = mockk(relaxed = true)

        coordinator = buildCoordinator(noLinkedReceiptsLinkService())
    }

    private fun nonEmptyBatch(correlationId: String, entityId: Long): PostCommitActionBatch {
        val action = PostCommitAction(
            pipeline = AppPipeline.TRANSACTION,
            name = "test_action",
            category = SideEffectCategory.BUDGET,
            triggerType = SideEffectTriggerType.EXPENSE_CREATED,
            targetEntityType = "expense",
            targetEntityId = entityId,
            source = "test",
            correlationId = correlationId,
            causationId = null,
            idempotencyKey = "key-$correlationId",
            execute = { SideEffectOutcome.Completed }
        )
        return PostCommitActionBatch(correlationId, listOf(action))
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun buildCoordinator(receiptLinkService: Lazy<ReceiptLinkService>): TransactionLifecycleCoordinator {
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        return TransactionLifecycleCoordinator(
            database = db,
            expenseDao = db.expenseDao(),
            transactionEventDao = db.transactionEventDao(),
            expenseRefundLinkDao = db.expenseRefundLinkDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = planner,
            runner = runner,
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = barrier,
            currencySettingsRepository = currencySettingsRepository,
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true),
            receiptLinkService = receiptLinkService
        )
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private suspend fun seed(
        type: TransactionType,
        amount: Double,
        merchant: String = "Synthetic Merchant",
        currency: String = "EUR",
        date: Long = base,
        notes: String? = null,
        spendingExclusionReason: String? = null
    ): Long = db.expenseDao().insert(
        Expense(
            amount = amount,
            currency = currency,
            merchant = merchant,
            merchantKey = merchant.lowercase(),
            transactionType = type,
            date = date,
            notes = notes,
            spendingExclusionReason = spendingExclusionReason
        )
    ).also { assertTrue("fixture: expense insert failed", it > 0L) }

    private suspend fun purchase(amount: Double = 100.0, merchant: String = "Synthetic Merchant", notes: String? = null) =
        seed(TransactionType.PURCHASE, amount, merchant = merchant, notes = notes)

    private suspend fun refund(amount: Double, merchant: String = "Synthetic Merchant", currency: String = "EUR") =
        seed(TransactionType.DEPOSIT, amount, merchant = merchant, currency = currency, date = base + day)

    private suspend fun netted(id: Long): Double = db.expenseDao().getById(id)!!.refundNettedAmount

    private suspend fun linkCount(): Int = db.expenseRefundLinkDao().countAll()

    private suspend fun events(id: Long): List<TransactionEvent> =
        db.transactionEventDao().getEventsForExpense(id)

    private suspend fun eventsWithReason(id: Long, reason: String): List<TransactionEvent> =
        events(id).filter { it.reason == reason }

    private suspend fun totalEvents(): Int = db.transactionEventDao().getRecentEvents(10_000).size

    private fun assertExact(expected: String, actual: Double) {
        assertEquals(
            "expected exactly $expected but was $actual",
            0,
            BigDecimal(expected).compareTo(BigDecimal.valueOf(actual))
        )
    }

    private fun Throwable.causeChain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    private suspend fun linked(refundId: Long, purchaseId: Long): RefundLinkResult.Linked {
        val result = coordinator.linkRefund(refundId, purchaseId)
        assertTrue("Expected Linked, got $result", result is RefundLinkResult.Linked)
        return result as RefundLinkResult.Linked
    }

    private fun installFailingEventTrigger(reason: String) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER forced_refund_event_failure BEFORE INSERT ON transaction_events " +
                "WHEN NEW.reason = '$reason' " +
                "BEGIN SELECT RAISE(ABORT, 'forced_refund_event_failure'); END"
        )
    }

    // ── INV-15 / AE-1: link ─────────────────────────────────────────────────

    @Test
    fun `INV-15 AE-1 link inserts one USER_CONFIRMED link row and nets both rows exactly`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)

        val result = linked(d1, p1)

        assertExact("40.00", result.applied.amount)
        assertEquals(CurrencyCode("EUR"), result.applied.currency)
        assertEquals(1, linkCount())
        val link = db.expenseRefundLinkDao().getById(result.linkId)!!
        assertEquals(d1, link.refundExpenseId)
        assertEquals(p1, link.purchaseExpenseId)
        assertExact("40.00", link.appliedAmount)
        assertEquals("EUR", link.currency)
        assertEquals("USER_CONFIRMED", link.source)
        assertExact("40.00", netted(p1))
        assertExact("40.00", netted(d1))
    }

    @Test
    fun `INV-22 AE-1 AE-27 link writes exactly two UPDATED events with bounded metadata and no text or amounts`() = runTest {
        val p1 = purchase(100.0, merchant = "Zarafix Synthetic", notes = "synthetic note body")
        val d1 = refund(40.0, merchant = "ZARAFIX SYNTHETIC")

        val result = linked(d1, p1)

        assertEquals(2, totalEvents())
        for ((rowId, counterpart) in listOf(d1 to p1, p1 to d1)) {
            val event = eventsWithReason(rowId, "REFUND_LINKED").single()
            assertEquals("UPDATED", event.eventType)
            val metadata = JSONObject(event.metadata!!)
            assertEquals(setOf("updateKind", "linkId", "counterpartExpenseId"), metadata.keys().asSequence().toSet())
            assertEquals("REFUND_NETTING", metadata.getString("updateKind"))
            assertEquals(result.linkId, metadata.getLong("linkId"))
            assertEquals(counterpart, metadata.getLong("counterpartExpenseId"))
            val everything = listOfNotNull(event.metadata, event.beforeSnapshot, event.afterSnapshot, event.reason)
                .joinToString(" ")
            assertFalse(everything.contains("zarafix", ignoreCase = true))
            assertFalse(everything.contains("synthetic", ignoreCase = true))
            assertFalse(everything.contains("40"))
            assertFalse(everything.contains("100"))
        }
    }

    @Test
    fun `INV-15 post-commit REFUND_NETTING plans run once per touched row after a successful link`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)

        linked(d1, p1)

        verify(exactly = 1) { planner.planUpdated(d1, any(), any(), TransactionUpdateKind.REFUND_NETTING) }
        verify(exactly = 1) { planner.planUpdated(p1, any(), any(), TransactionUpdateKind.REFUND_NETTING) }
        coVerify(exactly = 1) { runner.run(match { it.correlationId == "updated-$d1" }) }
        coVerify(exactly = 1) { runner.run(match { it.correlationId == "updated-$p1" }) }
    }

    @Test
    fun `INV-15 a failure inside the transaction rolls back link columns and events and dispatches nothing`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        // Fails the second step after the link row, both column updates and the first event ran.
        installFailingEventTrigger("REFUND_LINKED")

        val result = coordinator.linkRefund(d1, p1)

        assertEquals(RefundLinkResult.Failed(RefundWriteFailure.INTERNAL), result)
        assertEquals(0, linkCount())
        assertExact("0.0", netted(p1))
        assertExact("0.0", netted(d1))
        assertEquals(0, totalEvents())
        verify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { runner.run(any()) }
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `INV-5 AE-4 a refund larger than the purchase is capped at the purchase amount`() = runTest {
        val p2 = purchase(30.0)
        val d4 = refund(35.0)

        val result = linked(d4, p2)

        assertExact("30.00", result.applied.amount)
        assertExact("30.00", netted(p2))
        assertExact("30.00", netted(d4))
    }

    @Test
    fun `INV-4 INV-5 AE-2 AE-3 two refunds accumulate on one purchase and a third finds no capacity`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        val d2 = refund(60.0)
        val d3 = refund(10.0)

        linked(d1, p1)
        val second = linked(d2, p1)
        assertExact("60.00", second.applied.amount)
        assertExact("100.00", netted(p1))
        assertExact("40.00", netted(d1))
        assertExact("60.00", netted(d2))
        assertEquals(2, linkCount())

        val eventsBefore = totalEvents()
        val third = coordinator.linkRefund(d3, p1)

        assertEquals(RefundLinkResult.Refused(RefundLinkRefusal.NOTHING_TO_APPLY), third)
        assertEquals(2, linkCount())
        assertExact("100.00", netted(p1))
        assertExact("0.0", netted(d3))
        assertEquals(eventsBefore, totalEvents())
    }

    @Test
    fun `INV-5 AE-20 rounding conservation three partial refunds sum to exactly the purchase amount`() = runTest {
        val p = purchase(100.0)
        val a = refund(33.33)
        val b = refund(33.33)
        val c = refund(33.34)

        linked(a, p)
        linked(b, p)
        val last = linked(c, p)

        assertExact("33.34", last.applied.amount)
        assertEquals(100.0, netted(p), 0.0)
        assertExact("100.00", netted(p))
        val applied = db.expenseRefundLinkDao().getByPurchase(p).map { it.appliedAmount }
        assertEquals(listOf(33.33, 33.33, 33.34), applied)
    }

    @Test
    fun `INV-3 AE-6 a cross-currency pair is refused and nothing is written`() = runTest {
        val p3 = seed(TransactionType.PURCHASE, 50.0, currency = "USD")
        val d5 = refund(46.0, currency = "EUR")

        val result = coordinator.linkRefund(d5, p3)

        assertEquals(RefundLinkResult.Refused(RefundLinkRefusal.CURRENCY_MISMATCH), result)
        assertEquals(0, linkCount())
        assertExact("0.0", netted(p3))
        assertExact("0.0", netted(d5))
        assertEquals(0, totalEvents())
        verify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
    }

    @Test
    fun `INV-3 control a same-currency non-EUR pair links`() = runTest {
        val p = seed(TransactionType.PURCHASE, 50.0, currency = "USD")
        val d = refund(46.0, currency = "USD")

        val result = linked(d, p)

        assertExact("46.00", result.applied.amount)
        assertEquals(CurrencyCode("USD"), result.applied.currency)
    }

    @Test
    fun `INV-4 AE-5 an exact repeat is AlreadyLinked with no write and no event`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        val first = linked(d1, p1)
        val eventsBefore = totalEvents()
        clearMocks(planner, runner, answers = false)

        val again = coordinator.linkRefund(d1, p1)

        assertEquals(RefundLinkResult.AlreadyLinked(first.linkId), again)
        assertEquals(1, linkCount())
        assertExact("40.00", netted(p1))
        assertExact("40.00", netted(d1))
        assertEquals(eventsBefore, totalEvents())
        verify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `INV-4 AE-5 a refund linked to another purchase is refused as REFUND_LINKED_ELSEWHERE`() = runTest {
        val p1 = purchase(100.0)
        val p2 = purchase(30.0)
        val d1 = refund(40.0)
        linked(d1, p1)
        val eventsBefore = totalEvents()

        val result = coordinator.linkRefund(d1, p2)

        assertEquals(RefundLinkResult.Refused(RefundLinkRefusal.REFUND_LINKED_ELSEWHERE), result)
        assertEquals(1, linkCount())
        assertExact("0.0", netted(p2))
        assertEquals(eventsBefore, totalEvents())
    }

    @Test
    fun `INV-19 AE-11 an installment-flagged purchase is refused as INSTALLMENT_MANAGED`() = runTest {
        val p8 = seed(
            TransactionType.PURCHASE,
            600.0,
            spendingExclusionReason = SpendingExclusionReason.INSTALLMENT_PURCHASE.name
        )
        val d = refund(100.0)

        val result = coordinator.linkRefund(d, p8)

        assertEquals(RefundLinkResult.Refused(RefundLinkRefusal.INSTALLMENT_MANAGED), result)
        assertEquals(0, linkCount())
        assertEquals(0, totalEvents())
    }

    @Test
    fun `INV-3 missing rows are refused with a typed code and nothing is written`() = runTest {
        val p = purchase(100.0)
        val d = refund(40.0)

        assertEquals(
            RefundLinkResult.Refused(RefundLinkRefusal.REFUND_NOT_FOUND),
            coordinator.linkRefund(9_999L, p)
        )
        assertEquals(
            RefundLinkResult.Refused(RefundLinkRefusal.PURCHASE_NOT_FOUND),
            coordinator.linkRefund(d, 9_999L)
        )
        assertEquals(0, linkCount())
        assertEquals(0, totalEvents())
    }

    // ── AE-18: capacity under competing links ───────────────────────────────

    @Test
    fun `INV-5 AE-18 sequential links of 70 then 50 never exceed the purchase amount`() = runTest {
        val p10 = purchase(100.0)
        val d12 = refund(70.0)
        val d13 = refund(50.0)

        val first = linked(d12, p10)
        val second = linked(d13, p10)

        assertExact("70.00", first.applied.amount)
        assertExact("30.00", second.applied.amount)
        assertExact("100.00", netted(p10))
        assertExact("30.00", netted(d13))
    }

    @Test
    fun `INV-5 AE-18 sequential links of 50 then 70 never exceed the purchase amount`() = runTest {
        val p10 = purchase(100.0)
        val d12 = refund(70.0)
        val d13 = refund(50.0)

        val first = linked(d13, p10)
        val second = linked(d12, p10)

        assertExact("50.00", first.applied.amount)
        assertExact("50.00", second.applied.amount)
        assertExact("100.00", netted(p10))
    }

    @Test
    fun `INV-5 AE-18 two links launched together in either serialized order end at exactly the purchase amount`() = runTest {
        val p10 = purchase(100.0)
        val d12 = refund(70.0)
        val d13 = refund(50.0)

        // Room serializes the two write transactions (single SQLite writer); the later one must
        // see the reduced capacity. Either order is valid, so the assertions are order independent.
        val results = coroutineScope {
            val a = async { coordinator.linkRefund(d12, p10) }
            val b = async { coordinator.linkRefund(d13, p10) }
            listOf(a.await(), b.await())
        }

        assertTrue("Expected both Linked, got $results", results.all { it is RefundLinkResult.Linked })
        val applied = db.expenseRefundLinkDao().getByPurchase(p10).associate { it.refundExpenseId to it.appliedAmount }
        val total = applied.values.fold(BigDecimal.ZERO) { acc, v -> acc.add(BigDecimal.valueOf(v)) }
        assertEquals("Σ applied must equal the purchase amount", 0, BigDecimal("100.00").compareTo(total))
        assertExact("100.00", netted(p10))
        val pair = applied.getValue(d12) to applied.getValue(d13)
        assertTrue("unexpected split $pair", pair == (70.0 to 30.0) || pair == (50.0 to 50.0))
        assertEquals(applied.getValue(d12), netted(d12), 0.0)
        assertEquals(applied.getValue(d13), netted(d13), 0.0)
    }

    // ── INV-14 / AE-13: unlink ──────────────────────────────────────────────

    @Test
    fun `INV-14 AE-13 unlink removes the link restores both columns and writes one event pair`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        val link = linked(d1, p1)
        clearMocks(planner, runner, answers = false)

        val result = coordinator.unlinkRefund(d1)

        assertEquals(RefundUnlinkResult.Unlinked(link.linkId), result)
        assertEquals(0, linkCount())
        assertExact("0.0", netted(p1))
        assertExact("0.0", netted(d1))
        for ((rowId, counterpart) in listOf(d1 to p1, p1 to d1)) {
            val event = eventsWithReason(rowId, "REFUND_UNLINKED").single()
            assertEquals("UPDATED", event.eventType)
            val metadata = JSONObject(event.metadata!!)
            assertEquals(setOf("updateKind", "linkId", "counterpartExpenseId"), metadata.keys().asSequence().toSet())
            assertEquals("REFUND_NETTING", metadata.getString("updateKind"))
            assertEquals(counterpart, metadata.getLong("counterpartExpenseId"))
        }
        assertEquals(4, totalEvents())
        verify(exactly = 1) { planner.planUpdated(d1, any(), any(), TransactionUpdateKind.REFUND_NETTING) }
        verify(exactly = 1) { planner.planUpdated(p1, any(), any(), TransactionUpdateKind.REFUND_NETTING) }
    }

    @Test
    fun `INV-14 unlinking one of two refunds recomputes the purchase from the remaining link`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        val d2 = refund(60.0)
        linked(d1, p1)
        linked(d2, p1)

        coordinator.unlinkRefund(d1)

        assertExact("60.00", netted(p1))
        assertExact("0.0", netted(d1))
        assertExact("60.00", netted(d2))
        assertEquals(1, linkCount())
    }

    @Test
    fun `INV-14 AE-13 a second unlink is NotLinked with no write and no event`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        linked(d1, p1)
        coordinator.unlinkRefund(d1)
        val eventsBefore = totalEvents()
        clearMocks(planner, runner, answers = false)

        val again = coordinator.unlinkRefund(d1)

        assertEquals(RefundUnlinkResult.NotLinked, again)
        assertEquals(eventsBefore, totalEvents())
        verify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `INV-14 a refund that was never linked is NotLinked`() = runTest {
        val d1 = refund(40.0)

        assertEquals(RefundUnlinkResult.NotLinked, coordinator.unlinkRefund(d1))
        assertEquals(0, totalEvents())
    }

    @Test
    fun `INV-14 a failure while unlinking rolls the unlink back`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        linked(d1, p1)
        val eventsBefore = totalEvents()
        installFailingEventTrigger("REFUND_UNLINKED")
        clearMocks(planner, runner, answers = false)

        val result = coordinator.unlinkRefund(d1)

        assertEquals(RefundUnlinkResult.Failed(RefundWriteFailure.INTERNAL), result)
        assertEquals(1, linkCount())
        assertExact("40.00", netted(p1))
        assertExact("40.00", netted(d1))
        assertEquals(eventsBefore, totalEvents())
        verify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { runner.run(any()) }
    }

    // ── INV-23 / AE-22: restore barrier ─────────────────────────────────────

    @Test
    fun `INV-23 AE-22 a blocked barrier gives Failed WRITE_BLOCKED for link and writes nothing`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        val result = coordinator.linkRefund(d1, p1)

        assertEquals(RefundLinkResult.Failed(RefundWriteFailure.WRITE_BLOCKED), result)
        assertEquals(0, linkCount())
        assertExact("0.0", netted(p1))
        assertExact("0.0", netted(d1))
        assertEquals(0, totalEvents())
        verify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `INV-23 AE-22 a blocked barrier gives Failed WRITE_BLOCKED for unlink and keeps the link`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        linked(d1, p1)
        val eventsBefore = totalEvents()
        mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        val result = coordinator.unlinkRefund(d1)

        assertEquals(RefundUnlinkResult.Failed(RefundWriteFailure.WRITE_BLOCKED), result)
        assertEquals(1, linkCount())
        assertExact("40.00", netted(p1))
        assertEquals(eventsBefore, totalEvents())
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `INV-23 control the same link succeeds once the barrier is back to normal`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        assertEquals(
            RefundLinkResult.Failed(RefundWriteFailure.WRITE_BLOCKED),
            coordinator.linkRefund(d1, p1)
        )
        mode = RestoreMaintenanceMode.Mode.NORMAL

        linked(d1, p1)

        assertExact("40.00", netted(p1))
    }

    @Test
    fun `INV-15 a CancellationException before the transaction propagates and is not mapped to Failed`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        cancelOnModeCheck = true

        try {
            coordinator.linkRefund(d1, p1)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(
                "cause chain must lead to the synthetic cancellation",
                expected.causeChain().any { it.message == "synthetic cancellation" }
            )
        }
        try {
            coordinator.unlinkRefund(d1)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(expected.causeChain().any { it.message == "synthetic cancellation" })
        }
        cancelOnModeCheck = false

        assertEquals(0, linkCount())
        assertEquals(0, totalEvents())
        assertEquals(0, barrier.inFlightWriteCount())
    }

    @Test
    fun `INV-15 a CancellationException from the post-commit plan propagates and the committed link stays`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        every { planner.planUpdated(any(), any(), any(), any()) } throws CancellationException("post-commit cancel")

        try {
            coordinator.linkRefund(d1, p1)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(expected.causeChain().any { it.message == "post-commit cancel" })
        }

        assertEquals(1, linkCount())
        assertExact("40.00", netted(p1))
        assertEquals(0, barrier.inFlightWriteCount())
    }

    // ── INV-13 / AE-14 / AE-15: delete cleanup ──────────────────────────────

    @Test
    fun `INV-13 AE-14 deleting the purchase removes its links recomputes the refunds and writes counterpart events before DELETED`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        val d2 = refund(60.0)
        linked(d1, p1)
        linked(d2, p1)
        val p1Row = db.expenseDao().getById(p1)!!
        clearMocks(planner, runner, answers = false)

        val result = coordinator.deleteExpense(p1Row, correlationId = "corr-del")

        assertTrue(result.isSuccess)
        assertNull(db.expenseDao().getById(p1))
        assertEquals(0, linkCount())
        assertExact("0.0", netted(d1))
        assertExact("0.0", netted(d2))
        val counterpartEvents = listOf(d1, d2).map { eventsWithReason(it, "REFUND_COUNTERPART_DELETED").single() }
        for (event in counterpartEvents) {
            assertEquals("UPDATED", event.eventType)
            val metadata = JSONObject(event.metadata!!)
            assertEquals(setOf("updateKind", "linkId", "counterpartExpenseId"), metadata.keys().asSequence().toSet())
            assertEquals("REFUND_NETTING", metadata.getString("updateKind"))
            assertEquals(p1, metadata.getLong("counterpartExpenseId"))
        }
        val deletedEvents = events(p1).filter { it.eventType == "DELETED" }
        assertEquals(1, deletedEvents.size)
        assertTrue(
            "the DELETED event is written after the counterpart events",
            counterpartEvents.all { it.id < deletedEvents.single().id }
        )
        verify(exactly = 1) { planner.planDeleted(p1, any(), "corr-del") }
        verify(exactly = 1) { planner.planUpdated(d1, any(), "corr-del", TransactionUpdateKind.REFUND_NETTING) }
        verify(exactly = 1) { planner.planUpdated(d2, any(), "corr-del", TransactionUpdateKind.REFUND_NETTING) }
        coVerify(exactly = 1) { runner.run(match { it.correlationId == "updated-$d1" }) }
        coVerify(exactly = 1) { runner.run(match { it.correlationId == "updated-$d2" }) }
        coVerify(exactly = 1) { runner.run(match { it.correlationId == "deleted-$p1" }) }
    }

    @Test
    fun `INV-13 AE-14 a forced failure at the final delete rolls back links columns and events`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        val d2 = refund(60.0)
        linked(d1, p1)
        linked(d2, p1)
        val p1Row = db.expenseDao().getById(p1)!!
        val eventsBefore = totalEvents()
        // Fires on the last statement of deleteExpense, after the link removal, the counterpart
        // recompute and every event insert already ran inside the transaction.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER forced_refund_delete_failure BEFORE DELETE ON expenses " +
                "BEGIN SELECT RAISE(ABORT, 'forced_refund_delete_failure'); END"
        )
        clearMocks(planner, runner, answers = false)

        val result = coordinator.deleteExpense(p1Row)

        assertTrue("delete must fail", result.isFailure)
        val failure = result.exceptionOrNull()!!
        assertTrue(
            "unexpected failure: ${failure.javaClass.simpleName}",
            failure.causeChain().any {
                it is android.database.sqlite.SQLiteConstraintException ||
                    it.message.orEmpty().contains("forced_refund_delete_failure")
            }
        )
        assertNotNull(db.expenseDao().getById(p1))
        assertEquals(2, linkCount())
        assertExact("100.00", netted(p1))
        assertExact("40.00", netted(d1))
        assertExact("60.00", netted(d2))
        assertEquals(eventsBefore, totalEvents())
        verify(exactly = 0) { planner.planDeleted(any(), any(), any()) }
        verify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `INV-13 AE-15 deleting the refund frees the purchase and writes one counterpart event`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        linked(d1, p1)
        val d1Row = db.expenseDao().getById(d1)!!

        val result = coordinator.deleteExpense(d1Row)

        assertTrue(result.isSuccess)
        assertNull(db.expenseDao().getById(d1))
        assertEquals(0, linkCount())
        assertExact("0.0", netted(p1))
        val event = eventsWithReason(p1, "REFUND_COUNTERPART_DELETED").single()
        assertEquals("UPDATED", event.eventType)
        assertEquals(d1, JSONObject(event.metadata!!).getLong("counterpartExpenseId"))
        assertEquals(1, events(d1).count { it.eventType == "DELETED" })
        assertEquals(0, eventsWithReason(d1, "REFUND_COUNTERPART_DELETED").size)
    }

    @Test
    fun `INV-13 deleting one of two refunds leaves the purchase netted by the other`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        val d2 = refund(60.0)
        linked(d1, p1)
        linked(d2, p1)

        coordinator.deleteExpense(db.expenseDao().getById(d1)!!)

        assertExact("60.00", netted(p1))
        assertExact("60.00", netted(d2))
        assertEquals(1, linkCount())
    }

    @Test
    fun `INV-13 control deleting an unlinked expense writes no refund event`() = runTest {
        val plain = purchase(25.0)

        val result = coordinator.deleteExpense(db.expenseDao().getById(plain)!!)

        assertTrue(result.isSuccess)
        assertEquals("only the DELETED event of the row itself exists", events(plain).size, totalEvents())
        assertEquals(0, events(plain).count { it.reason == "REFUND_COUNTERPART_DELETED" })
        assertEquals(1, events(plain).count { it.eventType == "DELETED" })
    }

    @Test
    fun `INV-23 a blocked barrier makes deleteExpense fail closed and keeps links`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        linked(d1, p1)
        val eventsBefore = totalEvents()
        mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        val result = coordinator.deleteExpense(db.expenseDao().getById(p1)!!)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DatabaseAccessBlockedException)
        assertNotNull(db.expenseDao().getById(p1))
        assertEquals(1, linkCount())
        assertExact("40.00", netted(d1))
        assertEquals(eventsBefore, totalEvents())
    }

    @Test
    fun `INV-13 a CancellationException inside the delete transaction propagates and nothing is deleted`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        linked(d1, p1)
        val eventsBefore = totalEvents()
        val receiptService = mockk<ReceiptLinkService>(relaxed = true)
        val cancellation = CancellationException("synthetic delete cancellation")
        coEvery { receiptService.unlinkAllReceiptsForExpense(any()) } throws cancellation
        val cancelling = buildCoordinator(Lazy { receiptService })

        try {
            cancelling.deleteExpense(db.expenseDao().getById(p1)!!)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertTrue(expected.causeChain().any { it === cancellation })
        }

        assertNotNull(db.expenseDao().getById(p1))
        assertEquals(1, linkCount())
        assertExact("40.00", netted(p1))
        assertExact("40.00", netted(d1))
        assertEquals(eventsBefore, totalEvents())
        assertEquals(0, barrier.inFlightWriteCount())
    }

    // ── INV-12 / INV-2: edit lock ───────────────────────────────────────────

    private suspend fun assertRefundLocked(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected RefundLinkedExpenseException")
        } catch (e: RefundLinkedExpenseException) {
            assertEquals(RefundLinkedExpenseException.EXPENSE_LOCKED_BY_REFUND_LINK, e.code)
        }
    }

    @Test
    fun `INV-12 AE-16 amount currency and type edits of a linked purchase are refused and nothing changes`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        linked(d1, p1)
        val before = db.expenseDao().getById(p1)!!
        val eventsBefore = totalEvents()

        assertRefundLocked { coordinator.updateExpense(before.copy(amount = 120.0)) }
        assertRefundLocked { coordinator.updateExpense(before.copy(currency = "USD")) }
        assertRefundLocked { coordinator.updateExpense(before.copy(transactionType = TransactionType.WITHDRAWAL)) }
        assertRefundLocked { coordinator.updateType(p1, TransactionType.WITHDRAWAL) }

        assertEquals(before, db.expenseDao().getById(p1))
        assertEquals(eventsBefore, totalEvents())
    }

    @Test
    fun `INV-12 AE-16 a type change of the linked refund is refused on both type paths`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        linked(d1, p1)
        val before = db.expenseDao().getById(d1)!!
        val eventsBefore = totalEvents()

        assertRefundLocked { coordinator.updateType(d1, TransactionType.TRANSFER) }
        assertRefundLocked {
            coordinator.updateTypeAndTransferDetails(
                expenseId = d1,
                newType = TransactionType.TRANSFER,
                transferDirection = null,
                transferAccountName = null
            )
        }
        assertRefundLocked { coordinator.updateExpense(before.copy(amount = 41.0)) }

        assertEquals(before, db.expenseDao().getById(d1))
        assertEquals(eventsBefore, totalEvents())
    }

    @Test
    fun `INV-12 D-10 notes merchant category date and ownership edits of linked rows stay allowed`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        linked(d1, p1)
        val categoryId = db.categoryDao().insert(Category(name = "Gifts", icon = "G", color = "#112233"))
        val stale = db.expenseDao().getById(p1)!!

        coordinator.updateExpense(stale.copy(notes = "edited synthetic note", date = stale.date - 3 * day))
        coordinator.updateCategory(p1, categoryId)
        coordinator.updateMerchant(p1, "Renamed Synthetic")
        coordinator.updateOwnershipDbOnlyV2(
            expenseId = p1,
            isNotMine = true,
            ownerName = "Synthetic Owner",
            isSharedExpense = false,
            sharedWithName = null,
            mySharePercentage = null,
            myShareAmount = null
        )

        val after = db.expenseDao().getById(p1)!!
        assertEquals("edited synthetic note", after.notes)
        assertEquals(stale.date - 3 * day, after.date)
        assertEquals(categoryId, after.categoryId)
        assertEquals("Renamed Synthetic", after.merchant)
        assertTrue(after.isNotMine)
        assertEquals(100.0, after.amount, 0.0)
        assertExact("40.00", after.refundNettedAmount)
        assertEquals(1, linkCount())
        // Same lock-free edit on the refund side.
        coordinator.updateExpense(db.expenseDao().getById(d1)!!.copy(notes = "refund note"))
        assertExact("40.00", netted(d1))
    }

    @Test
    fun `INV-2 AE-16 a stale full-row copy with refundNettedAmount zero leaves the column untouched`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)
        val stale = db.expenseDao().getById(p1)!!
        assertExact("0.0", stale.refundNettedAmount)
        linked(d1, p1)

        coordinator.updateExpense(stale.copy(notes = "saved from a stale editor"))

        val after = db.expenseDao().getById(p1)!!
        assertEquals("saved from a stale editor", after.notes)
        assertExact("40.00", after.refundNettedAmount)
    }

    @Test
    fun `INV-2 a full-row copy that carries a forged refundNettedAmount cannot set the column`() = runTest {
        val plain = purchase(100.0)
        val row = db.expenseDao().getById(plain)!!

        coordinator.updateExpense(row.copy(notes = "forged column", refundNettedAmount = 25.0))

        assertExact("0.0", netted(plain))
    }

    @Test
    fun `INV-2 AE-17 a created expense stores refundNettedAmount zero`() = runTest {
        val result = coordinator.createExpenseDbOnlyV2(
            CreateExpenseRequest(
                merchant = "Synthetic Create",
                amount = 25.0,
                currency = "EUR",
                date = base,
                transactionType = TransactionType.PURCHASE,
                source = ExpenseSource.MANUAL_ENTRY,
                isManualEntry = true
            )
        ).value

        assertTrue("Expected Created, got $result", result is CreateExpenseResult.Created)
        val id = (result as CreateExpenseResult.Created).expenseId
        assertExact("0.0", netted(id))
    }

    @Test
    fun `INV-12 control amount currency and type edits work on an unlinked row and after the unlink`() = runTest {
        val p1 = purchase(100.0)
        val d1 = refund(40.0)

        coordinator.updateExpense(db.expenseDao().getById(p1)!!.copy(amount = 110.0))
        assertEquals(110.0, db.expenseDao().getById(p1)!!.amount, 0.0)

        linked(d1, p1)
        assertRefundLocked { coordinator.updateExpense(db.expenseDao().getById(p1)!!.copy(amount = 120.0)) }

        coordinator.unlinkRefund(d1)
        coordinator.updateExpense(db.expenseDao().getById(p1)!!.copy(amount = 120.0))

        assertEquals(120.0, db.expenseDao().getById(p1)!!.amount, 0.0)
        assertExact("0.0", netted(p1))
    }
}
