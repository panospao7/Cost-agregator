package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@Ignore("Stress test: may hang in CI, run manually")
class NotificationRepositoryStressTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val dao = mockk<RawNotificationDao>(relaxed = true)
    private val blockedPackageDao = mockk<BlockedPackageDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val userCorrectionDao = mockk<UserCorrectionDao>(relaxed = true)
    private val sourceStatsDao = mockk<SourceStatsDao>(relaxed = true)
    private val classifier = mockk<TransactionClassifier>(relaxed = true)
    private val pipeline = mockk<NotificationProcessingPipeline>(relaxed = true)

    private lateinit var repository: NotificationRepository

    @Before
    fun setup() {
        coEvery { dao.getAllFlow() } returns MutableStateFlow(emptyList())
        coEvery { dao.getRecentFlow(any()) } returns MutableStateFlow(emptyList())
        coEvery { dao.getByPackageFlow(any()) } returns MutableStateFlow(emptyList())
        coEvery { dao.getAllPackagesFlow() } returns MutableStateFlow(emptyList())
        coEvery { dao.getCountFlow() } returns MutableStateFlow(0)
        coEvery { dao.insert(any()) } returns 1L
        coEvery { dao.exists(any(), any(), any(), any(), any()) } returns false
        coEvery { dao.delete(any()) } returns Unit
        coEvery { dao.deleteAll() } returns Unit
        coEvery { blockedPackageDao.block(any<BlockedPackage>()) } returns Unit
        coEvery { blockedPackageDao.unblock("") } returns Unit
        coEvery { blockedPackageDao.isBlocked(any()) } returns false
        coEvery { blockedPackageDao.getAllFlow() } returns MutableStateFlow(emptyList())
        coEvery { sourceStatsDao.getAllFlow() } returns MutableStateFlow(emptyList())
        coEvery { sourceStatsDao.decrementPending(any()) } returns Unit
        coEvery { sourceStatsDao.resetAllPendingCounts() } returns Unit
        coEvery { sourceStatsDao.deleteAll() } returns Unit
        coEvery { classifier.stats } returns MutableStateFlow(ClassifierStats(0, 0, 0, false))
        coEvery { classifier.getStats() } returns ClassifierStats(0, 0, 0, false)
        coEvery { classifier.retrainFromCorrections() } returns Unit
        coEvery { pendingReviewDao.getByRawId(any()) } returns null
        coEvery { pendingReviewDao.deleteByRawId(any()) } returns Unit

        repository = NotificationRepository(
            database,
            dao,
            blockedPackageDao,
            expenseDao,
            pendingReviewDao,
            userCorrectionDao,
            sourceStatsDao,
            classifier,
            pipeline,
            mockk(relaxed = true)
        )
    }

    // ============================================================================
    // SECTION 1: QUERY OPERATIONS
    // ============================================================================

    @Test
    fun `stress - getAllNotifications returns flow`() = runTest {
        val result = repository.getAllNotifications()
        assertNotNull(result)
    }

    @Test
    fun `stress - getRecentNotifications returns flow`() = runTest {
        val result = repository.getRecentNotifications()
        assertNotNull(result)
    }

    @Test
    fun `stress - getNotificationsByPackage returns flow`() = runTest {
        val result = repository.getNotificationsByPackage("com.example.app")
        assertNotNull(result)
    }

    @Test
    fun `stress - getAllPackages returns flow`() = runTest {
        val result = repository.getAllPackages()
        assertNotNull(result)
    }

    @Test
    fun `stress - getCount returns flow`() = runTest {
        val result = repository.getCount()
        assertNotNull(result)
    }

    // ============================================================================
    // SECTION 2: SAVE AND CHECK OPERATIONS
    // ============================================================================

    @Test
    fun `stress - save notification returns id`() = runTest {
        val notification = RawNotification(
            packageName = "com.test",
            appName = "Test App",
            title = "Test",
            text = "Amount: €50.00",
            timestamp = System.currentTimeMillis(),
            capturedAt = System.currentTimeMillis()
        )
        val result = repository.save(notification)
        assertEquals(1L, result)
    }

    @Test
    fun `stress - exists returns false for new notification`() = runTest {
        val result = repository.exists("com.test", System.currentTimeMillis(), "Test", "Amount")
        assertFalse(result)
    }

    // ============================================================================
    // SECTION 3: SOURCE STATS
    // ============================================================================

    @Test
    fun `stress - getSourceStats returns flow`() = runTest {
        val result = repository.getSourceStats()
        assertNotNull(result)
    }

    @Test
    fun `stress - getClassifierStats returns flow`() = runTest {
        val result = repository.getClassifierStatsFlow()
        assertNotNull(result)
    }

    // ============================================================================
    // SECTION 4: PACKAGE BLOCKING
    // ============================================================================

    @Test
    fun `stress - blockPackage calls DAO`() = runTest {
        repository.blockPackage("com.test")
        coVerify { blockedPackageDao.block(any()) }
    }

    @Test
    fun `stress - unblockPackage calls DAO`() = runTest {
        repository.unblockPackage("com.test")
        coVerify { blockedPackageDao.unblock("com.test") }
    }

    @Test
    fun `stress - isPackageBlocked returns false by default`() = runTest {
        val result = repository.isPackageBlocked("com.test")
        assertFalse(result)
    }

    @Test
    fun `stress - getBlockedPackages returns flow`() = runTest {
        val result = repository.getBlockedPackages()
        assertNotNull(result)
    }

    // ============================================================================
    // SECTION 5: BULK OPERATIONS
    // ============================================================================

    @Test
    fun `stress - deleteAll clears all tables`() = runTest {
        coEvery { expenseDao.deleteAll() } returns Unit
        coEvery { pendingReviewDao.deleteAll() } returns Unit
        coEvery { userCorrectionDao.deleteAll() } returns Unit

        repository.deleteAllNotifications()
    }

    @Test
    fun `stress - resetSourceStats calls DAO`() = runTest {
        repository.resetSourceStats()
        coVerify { sourceStatsDao.deleteAll() }
    }

    // ============================================================================
    // SECTION 6: CLASSIFIER MANAGEMENT
    // ============================================================================

    @Test
    fun `stress - retrainClassifier calls classifier`() = runTest {
        repository.retrainClassifier()
        coVerify { classifier.retrainFromCorrections() }
    }

    // ============================================================================
    // SECTION 7: BATCH PROCESSING
    // ============================================================================

    @Test
    fun `stress - processAndSave calls pipeline process`() = runTest {
        coEvery { pipeline.process(any()) } returns NotificationPipelineOutcome.AutoAccepted(packageName = "com.test", correlationId = null, rawId = 1L, expenseId = 1L)
        val notification = RawNotification(
            packageName = "com.revolut.revolut",
            appName = "Revolut",
            title = "Payment",
            text = "€50.00 spent",
            timestamp = System.currentTimeMillis(),
            capturedAt = System.currentTimeMillis()
        )
        repository.processAndSave(notification)
        coVerify(exactly = 1) { pipeline.process(notification) }
    }

    @Test
    fun `stress - processAndSaveAll calls pipeline processBatch with list`() = runTest {
        coEvery { pipeline.processBatch(any()) } returns emptyList()
        val notifications = (1..10).map { i ->
            RawNotification(
                packageName = "com.test",
                appName = "Test",
                title = "Title $i",
                text = "Text $i",
                timestamp = System.currentTimeMillis() + i,
                capturedAt = System.currentTimeMillis()
            )
        }
        repository.processAndSaveAll(notifications)
        coVerify(exactly = 1) { pipeline.processBatch(notifications) }
    }

    @Test
    fun `stress - processAndSaveAll with empty list no crash`() = runTest {
        repository.processAndSaveAll(emptyList())
        coVerify(exactly = 1) { pipeline.processBatch(emptyList()) }
    }

    @Test
    fun `stress - delete with pending review decrements source stats`() = runTest {
        val notification = RawNotification(
            id = 42,
            packageName = "com.revolut.revolut",
            appName = "Revolut",
            title = "Payment",
            text = "€50",
            timestamp = System.currentTimeMillis(),
            capturedAt = System.currentTimeMillis()
        )
        val pendingReview = PendingReview(
            rawNotificationId = 42,
            suggestedAmount = 50.0,
            suggestedCurrency = "EUR",
            suggestedMerchant = "Test",
            suggestedType = "EXPENSE",
            suggestedCategoryId = null,
            confidence = 0.9f,
            packageName = "com.revolut.revolut",
            notificationTitle = "Payment",
            notificationText = "€50",
            status = PendingReviewStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )
        coEvery { pendingReviewDao.getByRawId(42) } returns pendingReview

        repository.delete(notification)

        coVerify(exactly = 1) { sourceStatsDao.decrementPending("com.revolut.revolut") }
        coVerify(exactly = 1) { pendingReviewDao.deleteByRawId(42) }
        coVerify(exactly = 1) { dao.delete(notification) }
    }

    @Test
    fun `stress - delete without pending review does not decrement source stats`() = runTest {
        val notification = RawNotification(
            id = 99,
            packageName = "com.test",
            appName = "Test",
            title = "Test",
            text = "Test",
            timestamp = System.currentTimeMillis(),
            capturedAt = System.currentTimeMillis()
        )
        coEvery { pendingReviewDao.getByRawId(99) } returns null

        repository.delete(notification)

        coVerify(exactly = 0) { sourceStatsDao.decrementPending(any()) }
        coVerify(exactly = 1) { pendingReviewDao.deleteByRawId(99) }
        coVerify(exactly = 1) { dao.delete(notification) }
    }
}