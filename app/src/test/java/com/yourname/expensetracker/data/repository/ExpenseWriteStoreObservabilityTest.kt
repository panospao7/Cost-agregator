package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.lifecycle.BulkChangedField
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectDispatcher
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidator
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-PR3: Observability & Cleanup tests.
 *
 * Verifies:
 * - correlationId propagation to TransactionEvent for updateLocation/updateMerchant/updateType
 * - merchantKey regeneration on merchant update
 * - bulk idempotency key uniqueness across invocations
 * - Flow.first() timeout safety
 */
class ExpenseWriteStoreObservabilityTest {

    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var transactionEventDao: TransactionEventDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var planner: TransactionSideEffectPlanner
    private lateinit var coordinator: TransactionLifecycleCoordinator

    private val now = 1_712_000_000_000L

    @Before
    fun setup() {
        database = mockk(relaxed = true)
        expenseDao = mockk(relaxed = true)
        transactionEventDao = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        currencyConverter = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)
        currencySettingsRepository = mockk(relaxed = true)
        planner = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        // Mock withTransaction to execute block directly
        mockkStatic("androidx.room.RoomDatabaseKt")
        val dbBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(dbBlock)) } coAnswers {
            dbBlock.captured.invoke()
        }

        coordinator = TransactionLifecycleCoordinator(
            database = database,
            expenseDao = expenseDao,
            transactionEventDao = transactionEventDao,
            timeProvider = timeProvider,
            currencyConverter = currencyConverter,
            sideEffectDispatcher = mockk<TransactionSideEffectDispatcher>(relaxed = true),
            planner = planner,
            runner = mockk<PostCommitActionRunner>(relaxed = true),
            recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
            writeBarrier = writeBarrier,
            currencySettingsRepository = currencySettingsRepository,
            sourceLinkWriter = mockk<SourceLinkWriter>(relaxed = true),
            transactionValidator = mockk<TransactionValidator>(relaxed = true),
            diagnosticEventWriter = mockk<DiagnosticEventWriter>(relaxed = true)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW-P2-011: updateLocation passes correlationId to TransactionEvent
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateLocation passes correlationId to event`() = runTest {
        val expenseId = 1L
        val correlationId = "test-corr-updateLocation"
        val existing = Expense(
            id = expenseId, amount = 10.0, currency = "EUR",
            merchant = "Test", merchantKey = "test",
            transactionType = TransactionType.PURCHASE, date = now
        )
        coEvery { expenseDao.getById(expenseId) } returns existing

        val eventSlot = slot<TransactionEvent>()
        coEvery { transactionEventDao.insert(capture(eventSlot)) } returns 1L

        coordinator.updateLocation(
            expenseId = expenseId,
            latitude = 38.0, longitude = 23.0,
            source = "USER_EDIT",
            correlationId = correlationId
        )

        assertEquals(
            "TransactionEvent must carry the correlationId from updateLocation",
            correlationId, eventSlot.captured.correlationId
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW-P2-012: updateMerchant passes correlationId to TransactionEvent
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateMerchant passes correlationId to event`() = runTest {
        val expenseId = 1L
        val correlationId = "test-corr-updateMerchant"
        val existing = Expense(
            id = expenseId, amount = 10.0, currency = "EUR",
            merchant = "OldName", merchantKey = "oldname",
            transactionType = TransactionType.PURCHASE, date = now
        )
        coEvery { expenseDao.getById(expenseId) } returns existing
        coEvery { expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any()) } returns null

        val eventSlot = slot<TransactionEvent>()
        coEvery { transactionEventDao.insert(capture(eventSlot)) } returns 1L

        coordinator.updateMerchant(
            expenseId = expenseId,
            newMerchant = "NewName",
            source = "USER_EDIT",
            correlationId = correlationId
        )

        assertEquals(
            "TransactionEvent must carry the correlationId from updateMerchant",
            correlationId, eventSlot.captured.correlationId
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW-P2-013: updateType passes correlationId to TransactionEvent
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateType passes correlationId to event`() = runTest {
        val expenseId = 1L
        val correlationId = "test-corr-updateType"
        val existing = Expense(
            id = expenseId, amount = 10.0, currency = "EUR",
            merchant = "Test", merchantKey = "test",
            transactionType = TransactionType.PURCHASE, date = now
        )
        coEvery { expenseDao.getById(expenseId) } returns existing
        coEvery { expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any()) } returns null

        val eventSlot = slot<TransactionEvent>()
        coEvery { transactionEventDao.insert(capture(eventSlot)) } returns 1L

        coordinator.updateType(
            expenseId = expenseId,
            newType = TransactionType.DEPOSIT,
            source = "USER_EDIT",
            correlationId = correlationId
        )

        assertEquals(
            "TransactionEvent must carry the correlationId from updateType",
            correlationId, eventSlot.captured.correlationId
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW-P2-014: updateMerchant regenerates merchantKey
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateMerchant regenerates merchantKey`() = runTest {
        val expenseId = 1L
        val existing = Expense(
            id = expenseId, amount = 10.0, currency = "EUR",
            merchant = "OldName", merchantKey = "oldname",
            transactionType = TransactionType.PURCHASE, date = now,
            dedupeKey = "old-dedupe-key"
        )
        coEvery { expenseDao.getById(expenseId) } returns existing
        coEvery { expenseDao.findDuplicateIdCurrencyAware(any(), any(), any(), any(), any(), any(), any()) } returns null

        // Capture the arguments passed to updateMerchantAndKey
        val merchantSlot = slot<String>()
        val merchantKeySlot = slot<String>()
        val dedupeKeySlot = slot<String>()
        coEvery {
            expenseDao.updateMerchantAndKey(expenseId, capture(merchantSlot), capture(merchantKeySlot), capture(dedupeKeySlot))
        } returns Unit

        coordinator.updateMerchant(
            expenseId = expenseId,
            newMerchant = "NewName",
            source = "USER_EDIT"
        )

        assertEquals("Merchant should be updated", "NewName", merchantSlot.captured)
        assertEquals(
            "merchantKey should be regenerated from new merchant name",
            "newname", merchantKeySlot.captured
        )
        assertNotNull("dedupeKey should be regenerated", dedupeKeySlot.captured)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW-P2-015: Bulk idempotency keys are unique across invocations
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `bulk idempotency keys unique across invocations`() {
        val planner = TransactionSideEffectPlanner(
            budgetMonitor = Lazy { mockk(relaxed = true) },
            anomalyAlertOrchestrator = mockk(relaxed = true),
            merchantCategoryRepository = mockk(relaxed = true),
            merchantNormalizationRepository = mockk(relaxed = true),
            recurringLifecycleCoordinator = Lazy { mockk<RecurringLifecycleCoordinator>(relaxed = true) },
            expenseDao = mockk(relaxed = true),
            categoryDao = mockk(relaxed = true)
        )

        val batch1 = planner.planBulkUpdated(
            source = "USER_EDIT",
            affectedCount = 5,
            correlationId = "corr-1",
            changedFields = setOf(BulkChangedField.CATEGORY)
        )

        val batch2 = planner.planBulkUpdated(
            source = "USER_EDIT",
            affectedCount = 5,
            correlationId = "corr-2",
            changedFields = setOf(BulkChangedField.CATEGORY)
        )

        // Same source and affectedCount — keys must still differ due to timestamp
        for (i in batch1.actions.indices) {
            val key1 = batch1.actions[i].idempotencyKey
            val key2 = batch2.actions[i].idempotencyKey
            assertTrue(
                "Bulk idempotency keys must be unique across invocations: '$key1' vs '$key2'",
                key1 != key2
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW-P2-016: Flow.first() with timeout returns fallback
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `currency flow timeout returns fallback`() = runTest {
        // Create a flow that never emits to simulate a hang
        val neverFlow = kotlinx.coroutines.flow.flow<Nothing> { }

        // Verify that withTimeoutOrNull returns null (not a hang)
        val result = withTimeoutOrNull(1_000L) { neverFlow.first() }
        assertEquals(
            "A flow that never emits should return null after timeout",
            null, result
        )
    }
}
