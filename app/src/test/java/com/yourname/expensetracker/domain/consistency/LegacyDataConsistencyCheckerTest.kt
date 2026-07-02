package com.yourname.expensetracker.domain.consistency

import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ReceiptEventDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ReceiptEvent
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDataConsistencyCheckerTest {

    // ── Fake DAO implementations ─────────────────────────────────────────────

    private class FakeScannedReceiptDao : ScannedReceiptDao {
        private val receipts = mutableListOf<ScannedReceipt>()

        fun add(receipt: ScannedReceipt) { receipts.add(receipt) }

        override suspend fun getAll(): List<ScannedReceipt> = receipts.toList()
        override suspend fun getById(id: Long): ScannedReceipt? = receipts.firstOrNull { it.id == id }

        // Unused by the checker — stub implementations:
        override suspend fun insert(receipt: ScannedReceipt): Long = 0
        override suspend fun update(receipt: ScannedReceipt) {}
        override suspend fun delete(receipt: ScannedReceipt) {}
        override suspend fun deleteById(id: Long) {}
        override fun getAllFlow() = throw NotImplementedError()
        override suspend fun getReceiptsPaged(limit: Int, offset: Int) = throw NotImplementedError()
        override suspend fun getByExpenseId(expenseId: Long) = throw NotImplementedError()
        override suspend fun getCount(): Int = throw NotImplementedError()
        override suspend fun deleteAll() {}
        override suspend fun linkToExpense(receiptId: Long, expenseId: Long) {}
        override suspend fun claimForAutoMatch(receiptId: Long, expenseId: Long, confidence: Float?, now: Long): Int = 0
        override suspend fun updateCategorizationStatus(receiptId: Long, status: String) {}
        override suspend fun getUnmatchedReceipts() = throw NotImplementedError()
        override suspend fun getReceiptsWithSuggestions() = throw NotImplementedError()
        override suspend fun getProcessableReceipts() = throw NotImplementedError()
        override suspend fun getRecentReceipts(since: Long, limit: Int) = throw NotImplementedError()
        override suspend fun getByImageHash(imageHash: String) = throw NotImplementedError()
        override suspend fun getByTextFingerprint(fingerprint: String) = throw NotImplementedError()
        override suspend fun getBySemanticFingerprint(fingerprint: String) = throw NotImplementedError()
        override suspend fun getBySourceFingerprint(fingerprint: String) = throw NotImplementedError()
        override suspend fun getAllWithImagePath() = throw NotImplementedError()
        override suspend fun purgeRawOcrText(beforeMs: Long, nowMs: Long): Int = 0
        override suspend fun getUnpurgedScannedReceiptsOlderThan(cutoffMs: Long) = throw NotImplementedError()
        override suspend fun getUnpurgedScannedReceiptsOlderThan(cutoffMs: Long, limit: Int) = throw NotImplementedError()
        override suspend fun updateRawOcrTextPurged(id: Long, rawOcrTextPurgedAt: Long) {}
        override suspend fun countInvalidTimestamps(): Int = 0
        override suspend fun repairMissingCreatedAt(now: Long): Int = 0
        override suspend fun repairMissingUpdatedAt(): Int = 0
        override suspend fun countDuplicateImageHashGroups(): Int = 0
        override suspend fun countDuplicateSourceFingerprints(): Int = 0
        override suspend fun countDuplicateTextFingerprints(): Int = 0
        override suspend fun countDuplicateSemanticFingerprints(): Int = 0
    }

    private class FakeReceiptEventDao : ReceiptEventDao {
        private val events = mutableListOf<ReceiptEvent>()

        fun add(event: ReceiptEvent) { events.add(event) }

        override suspend fun getEventsForReceipt(receiptId: Long): List<ReceiptEvent> =
            events.filter { it.receiptId == receiptId }

        override suspend fun insert(event: ReceiptEvent): Long = 0
    }

    private class FakePendingReviewDao : PendingReviewDao {
        private val reviews = mutableListOf<PendingReview>()

        fun add(review: PendingReview) { reviews.add(review) }

        override suspend fun getPendingReviewsInDateRange(startDate: Long, endDate: Long): List<PendingReview> =
            reviews.toList()

        // Unused by the checker — stub implementations:
        override suspend fun insert(review: PendingReview): Long = 0
        override suspend fun update(review: PendingReview) {}
        override suspend fun delete(review: PendingReview) {}
        override fun getPendingUncappedFlow() = throw NotImplementedError()
        override fun getPendingFlow(limit: Int) = throw NotImplementedError()
        override suspend fun getPending(limit: Int) = throw NotImplementedError()
        override suspend fun getPendingUncapped() = throw NotImplementedError()
        override suspend fun getPending() = throw NotImplementedError()
        override fun getPendingCountFlow() = throw NotImplementedError()
        override suspend fun getPendingCount(): Int = 0
        override suspend fun getById(id: Long) = throw NotImplementedError()
        override suspend fun getPendingWithReceiptById(id: Long) = throw NotImplementedError()
        override suspend fun getByRawId(rawId: Long) = throw NotImplementedError()
        override suspend fun upsertByRawNotificationId(review: PendingReview): Long = 0
        override suspend fun deleteByRawId(rawId: Long) {}
        override suspend fun updateStatus(id: Long, status: com.yourname.expensetracker.data.database.entity.PendingReviewStatus) {}
        override suspend fun transitionStatus(id: Long, expectedStatus: com.yourname.expensetracker.data.database.entity.PendingReviewStatus, newStatus: com.yourname.expensetracker.data.database.entity.PendingReviewStatus): Int = 0
        override fun getAllFlow() = throw NotImplementedError()
        override suspend fun clearResolved() {}
        override suspend fun deleteAll() {}
        @Deprecated("stub")
        override suspend fun approveAllPending() {}
        override suspend fun rejectAllPending() {}
        override suspend fun getPendingReviewsByMerchantKeyAndDateRange(merchantKey: String, startDate: Long, endDate: Long) = throw NotImplementedError()
        override suspend fun getPendingReviewsByMerchantNameAndDateRange(merchantName: String, startDate: Long, endDate: Long) = throw NotImplementedError()
        override suspend fun getPendingReviewsByMerchantAndDateRange(merchantKey: String, merchantName: String, startDate: Long, endDate: Long) = throw NotImplementedError()
        override suspend fun getPendingByMerchantKey(merchantKey: String) = throw NotImplementedError()
        override suspend fun getPendingByMerchantName(merchantName: String) = throw NotImplementedError()
        override suspend fun getPendingByMerchant(merchantKey: String, merchantName: String) = throw NotImplementedError()
        override suspend fun bulkUpdateCategoryByMerchantKey(merchantKey: String, categoryId: Long) {}
        override suspend fun bulkUpdateCategoryByMerchantName(merchantName: String, categoryId: Long) {}
        override suspend fun bulkUpdateCategoryByMerchant(merchantKey: String, merchantName: String, categoryId: Long) {}
        override suspend fun bulkRenameMerchantByKey(oldMerchantKey: String, newMerchant: String, newMerchantKey: String) {}
        override suspend fun bulkRenameMerchantByName(oldMerchant: String, newMerchant: String, newMerchantKey: String) {}
        override suspend fun bulkRenameMerchant(oldMerchantKey: String, oldMerchant: String, newMerchant: String, newMerchantKey: String) {}
        override suspend fun hasPendingDuplicateByMerchantKeyInRange(merchantKey: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String): Boolean = false
        override suspend fun hasPendingDuplicateByMerchantNameInRange(merchantName: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String): Boolean = false
        override suspend fun hasPendingDuplicateInRange(merchantKey: String, merchantName: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String): Boolean = false
        override suspend fun getPendingDuplicateCandidateByMerchantKeyInRange(merchantKey: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String) = throw NotImplementedError()
        override suspend fun getPendingDuplicateCandidateByMerchantNameInRange(merchantName: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String) = throw NotImplementedError()
        override suspend fun getPendingDuplicateCandidateInRange(merchantKey: String, merchantName: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String) = throw NotImplementedError()
        override suspend fun hasPendingDuplicateByMerchantKeyInRangeTypeAware(merchantKey: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String, transactionType: String): Boolean = false
        override suspend fun hasPendingDuplicateByMerchantKeyPrefixInRangeTypeAware(merchantKey: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String, transactionType: String): Boolean = false
        override suspend fun hasPendingDuplicateByMerchantNameInRangeTypeAware(merchantName: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String, transactionType: String): Boolean = false
        override suspend fun hasPendingDuplicateInRangeTypeAware(merchantKey: String, merchantName: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String, transactionType: String): Boolean = false
        override suspend fun getPendingDuplicateCandidateByMerchantKeyInRangeTypeAware(merchantKey: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String, transactionType: String) = throw NotImplementedError()
        override suspend fun getPendingDuplicateCandidateByMerchantNameInRangeTypeAware(merchantName: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String, transactionType: String) = throw NotImplementedError()
        override suspend fun getPendingDuplicateCandidateInRangeTypeAware(merchantKey: String, merchantName: String, startDate: Long, endDate: Long, minAmount: Double, maxAmount: Double, currency: String, transactionType: String) = throw NotImplementedError()
        override suspend fun recoverStuckProcessing(): Int = 0
        override suspend fun deleteByScannedReceiptId(receiptId: Long): Int = 0
        override suspend fun getByScannedReceiptId(receiptId: Long) = throw NotImplementedError()
        override suspend fun countByScannedReceiptId(receiptId: Long): Int = 0
        override suspend fun redactNotificationTextOlderThan(cutoffMs: Long): Int = 0
    }

    private class FakeRecurringOccurrenceDao : RecurringOccurrenceDao {
        private val occurrences = mutableListOf<RecurringOccurrence>()

        fun add(occurrence: RecurringOccurrence) { occurrences.add(occurrence) }

        override suspend fun getByStatus(status: String): List<RecurringOccurrence> =
            occurrences.filter { it.status == status }

        // Unused by the checker — stub implementations:
        override suspend fun insert(occurrence: RecurringOccurrence): Long = 0
        override suspend fun insertAll(occurrences: List<RecurringOccurrence>) {}
        override suspend fun update(occurrence: RecurringOccurrence) {}
        override suspend fun getById(id: Long) = throw NotImplementedError()
        override suspend fun getByKey(key: String) = throw NotImplementedError()
        override suspend fun getBySource(sourceType: String, sourceId: Long) = throw NotImplementedError()
        override suspend fun getByDateRange(start: Long, end: Long) = throw NotImplementedError()
        override suspend fun getByLinkedExpenseId(expenseId: Long) = throw NotImplementedError()
        override suspend fun updateStatus(ids: List<Long>, newStatus: String, now: Long) {}
        override suspend fun deleteBySource(sourceType: String, sourceId: Long) {}
        override suspend fun getIdsBySource(sourceType: String, sourceId: Long) = throw NotImplementedError()
        override suspend fun getPlannedIdsBySource(sourceType: String, sourceId: Long) = throw NotImplementedError()
        override suspend fun claimForExpense(occurrenceId: Long, expenseId: Long, amount: Double, currency: String, paidAt: Long): Int = 0
        override suspend fun deleteOpenPlannedBySource(sourceType: String, sourceId: Long): Int = 0
        override suspend fun updateLinkedPaymentSnapshot(occurrenceId: Long, expenseId: Long, amount: Double, currency: String, paidAt: Long, updatedAt: Long): Int = 0
    }

    private class FakeRecurringLifecycleEventDao : RecurringLifecycleEventDao {
        private val events = mutableListOf<RecurringLifecycleEvent>()

        fun add(event: RecurringLifecycleEvent) { events.add(event) }

        override suspend fun getEventsForOccurrence(id: Long): List<RecurringLifecycleEvent> =
            events.filter { it.occurrenceId == id }

        override suspend fun insert(event: RecurringLifecycleEvent): Long = 0
        override suspend fun getRecentEvents(limit: Int) = throw NotImplementedError()
        override suspend fun getEventsByType(type: String, limit: Int) = throw NotImplementedError()
    }

    private class FakeDiagnosticEventWriter : DiagnosticEventWriter {
        val emittedEvents = mutableListOf<DiagnosticEvent>()
        override suspend fun emit(event: DiagnosticEvent) { emittedEvents.add(event) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun createReceipt(id: Long = 1L) = ScannedReceipt(
        id = id,
        imagePath = null,
        rawOcrText = "",
        parsedTotal = null,
        parsedMerchant = null,
        parsedDate = null,
        parsedItems = null,
        parsedTaxAmount = null,
        currency = "EUR",
        confidence = 1.0f,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    private fun createReceiptEvent(receiptId: Long, id: Long = 100L) = ReceiptEvent(
        id = id,
        receiptId = receiptId,
        sourceType = "MANUAL",
        documentType = "RECEIPT",
        eventType = "RECEIPT_SAVED",
        occurredAt = System.currentTimeMillis(),
        oldStatus = null,
        newStatus = "CAPTURED",
        actor = "system",
        message = null,
        metadata = null,
        errorDetails = null
    )

    private fun createPendingReview(id: Long = 1L, scannedReceiptId: Long? = null) = PendingReview(
        id = id,
        rawNotificationId = null,
        scannedReceiptId = scannedReceiptId,
        suggestedAmount = 42.0,
        suggestedCurrency = "EUR",
        suggestedMerchant = "Test Merchant",
        suggestedType = "EXPENSE",
        suggestedCategoryId = null,
        confidence = 1.0f,
        packageName = "com.test.app",
        notificationTitle = null,
        notificationText = null,
        createdAt = System.currentTimeMillis()
    )

    private fun createRecurringOccurrence(
        id: Long = 1L,
        status: String = "PLANNED"
    ) = RecurringOccurrence(
        id = id,
        sourceType = "RECURRING_RULE",
        sourceId = 1L,
        occurrenceKey = "test-key-$id",
        dueDate = System.currentTimeMillis(),
        status = status,
        expectedAmount = 100.0,
        expectedCurrency = "EUR",
        frequency = "MONTHLY",
        merchant = "Test Subscription",
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    private fun createRecurringLifecycleEvent(occurrenceId: Long, id: Long = 100L) = RecurringLifecycleEvent(
        id = id,
        occurrenceId = occurrenceId,
        eventType = "OCCURRENCE_GENERATED",
        occurredAt = System.currentTimeMillis(),
        oldStatus = null,
        newStatus = "PLANNED",
        metadata = null
    )

    private fun createChecker(
        receiptDao: ScannedReceiptDao,
        receiptEventDao: ReceiptEventDao,
        pendingReviewDao: PendingReviewDao,
        occurrenceDao: RecurringOccurrenceDao,
        lifecycleEventDao: RecurringLifecycleEventDao,
        diagnosticWriter: DiagnosticEventWriter
    ) = LegacyDataConsistencyChecker(
        diagnosticEventWriter = diagnosticWriter,
        scannedReceiptDao = receiptDao,
        receiptEventDao = receiptEventDao,
        pendingReviewDao = pendingReviewDao,
        occurrenceDao = occurrenceDao,
        lifecycleEventDao = lifecycleEventDao
    )

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `receipt without event is reported`() = runTest {
        val receiptDao = FakeScannedReceiptDao()
        val receiptEventDao = FakeReceiptEventDao()
        val pendingReviewDao = FakePendingReviewDao()
        val occurrenceDao = FakeRecurringOccurrenceDao()
        val lifecycleEventDao = FakeRecurringLifecycleEventDao()
        val diagnosticWriter = FakeDiagnosticEventWriter()

        // A receipt with no lifecycle event
        receiptDao.add(createReceipt(id = 1L))

        val checker = createChecker(receiptDao, receiptEventDao, pendingReviewDao, occurrenceDao, lifecycleEventDao, diagnosticWriter)
        val report = checker.runConsistencyCheck()

        assertTrue("Expected receipt without event count >= 1, got ${report.receiptsWithoutEvent}",
            report.receiptsWithoutEvent >= 1)
        assertEquals(1, report.totalItemsChecked)
    }

    @Test
    fun `pending review without receipt is reported`() = runTest {
        val receiptDao = FakeScannedReceiptDao()
        val receiptEventDao = FakeReceiptEventDao()
        val pendingReviewDao = FakePendingReviewDao()
        val occurrenceDao = FakeRecurringOccurrenceDao()
        val lifecycleEventDao = FakeRecurringLifecycleEventDao()
        val diagnosticWriter = FakeDiagnosticEventWriter()

        // A pending review referencing a non-existent receipt
        pendingReviewDao.add(createPendingReview(id = 1L, scannedReceiptId = 999L))

        val checker = createChecker(receiptDao, receiptEventDao, pendingReviewDao, occurrenceDao, lifecycleEventDao, diagnosticWriter)
        val report = checker.runConsistencyCheck()

        assertTrue("Expected pendingReviewsWithoutReceipt >= 1, got ${report.pendingReviewsWithoutReceipt}",
            report.pendingReviewsWithoutReceipt >= 1)
    }

    @Test
    fun `occurrence without event is reported`() = runTest {
        val receiptDao = FakeScannedReceiptDao()
        val receiptEventDao = FakeReceiptEventDao()
        val pendingReviewDao = FakePendingReviewDao()
        val occurrenceDao = FakeRecurringOccurrenceDao()
        val lifecycleEventDao = FakeRecurringLifecycleEventDao()
        val diagnosticWriter = FakeDiagnosticEventWriter()

        // An occurrence with no lifecycle event
        occurrenceDao.add(createRecurringOccurrence(id = 1L, status = "PLANNED"))

        val checker = createChecker(receiptDao, receiptEventDao, pendingReviewDao, occurrenceDao, lifecycleEventDao, diagnosticWriter)
        val report = checker.runConsistencyCheck()

        assertTrue("Expected occurrencesWithoutEvent >= 1, got ${report.occurrencesWithoutEvent}",
            report.occurrencesWithoutEvent >= 1)
    }

    @Test
    fun `elapsed time is preserved`() = runTest {
        val receiptDao = FakeScannedReceiptDao()
        val receiptEventDao = FakeReceiptEventDao()
        val pendingReviewDao = FakePendingReviewDao()
        val occurrenceDao = FakeRecurringOccurrenceDao()
        val lifecycleEventDao = FakeRecurringLifecycleEventDao()
        val diagnosticWriter = FakeDiagnosticEventWriter()

        val checker = createChecker(receiptDao, receiptEventDao, pendingReviewDao, occurrenceDao, lifecycleEventDao, diagnosticWriter)
        val report = checker.runConsistencyCheck()

        assertTrue("Expected elapsedMs >= 0, got ${report.elapsedMs}", report.elapsedMs >= 0L)
    }

    @Test
    fun `all clean returns zero counts`() = runTest {
        val receiptDao = FakeScannedReceiptDao()
        val receiptEventDao = FakeReceiptEventDao()
        val pendingReviewDao = FakePendingReviewDao()
        val occurrenceDao = FakeRecurringOccurrenceDao()
        val lifecycleEventDao = FakeRecurringLifecycleEventDao()
        val diagnosticWriter = FakeDiagnosticEventWriter()

        // A receipt with its matching lifecycle event — should be clean
        receiptDao.add(createReceipt(id = 1L))
        receiptEventDao.add(createReceiptEvent(receiptId = 1L))

        val checker = createChecker(receiptDao, receiptEventDao, pendingReviewDao, occurrenceDao, lifecycleEventDao, diagnosticWriter)
        val report = checker.runConsistencyCheck()

        assertEquals("Expected zero receiptsWithoutEvent", 0, report.receiptsWithoutEvent)
        assertEquals("Expected zero pendingReviewsWithoutReceipt", 0, report.pendingReviewsWithoutReceipt)
        assertEquals("Expected zero occurrencesWithoutEvent", 0, report.occurrencesWithoutEvent)
        assertEquals(1, report.totalItemsChecked)
    }
}
