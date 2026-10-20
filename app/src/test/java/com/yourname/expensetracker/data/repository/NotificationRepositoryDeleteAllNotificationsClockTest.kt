package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BlockedPackageDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.BlockedPackage
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.transaction.lifecycle.RoomTransactionLifecycleEventWriter
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.notification.NotificationPersistenceContext
import com.yourname.expensetracker.domain.notification.NotificationPipelineOutcome
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * T3A / G-TIME-01 focused test for [NotificationRepository.deleteAllNotifications],
 * which now also covers the [NotificationRepository] query accessors, `exists`,
 * package blocking, `processAndSave` / `processAndSaveAll` storage-mode behaviour
 * (persistence context and sanitized storage content per raw storage mode),
 * delete source-stats bookkeeping, `resetSourceStats` and `retrainClassifier`.
 *
 * The delete method takes an explicit `now` from the caller's injected
 * [com.yourname.expensetracker.domain.util.TimeProvider] and must stamp the
 * BULK_DELETED audit [TransactionEvent] with exactly that value — there is no
 * hidden wall-clock read. Deletion behavior must remain unchanged.
 */
class NotificationRepositoryDeleteAllNotificationsClockTest {

    /**
     * Fixed "now" (far from the wall clock) used to seed the deterministic
     * [FakeTimeProvider]. The provider, not this literal, is the value source
     * passed to the repository.
     */
    private val fixedNow = 1_900_000_000_000L

    /**
     * Deterministic fake clock: `now()` always returns [fixedNow] so the
     * assertion compares against a provider-derived value, never a literal.
     */
    private val fakeTimeProvider = FakeTimeProvider(fixedNow)

    private val database = mockk<AppDatabase>(relaxed = true)
    private val dao = mockk<RawNotificationDao>(relaxed = true)
    private val blockedPackageDao = mockk<BlockedPackageDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val userCorrectionDao = mockk<UserCorrectionDao>(relaxed = true)
    private val sourceStatsDao = mockk<SourceStatsDao>(relaxed = true)
    private val classifier = mockk<TransactionClassifier>(relaxed = true)
    private val pipeline = mockk<NotificationProcessingPipeline>(relaxed = true)
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val privacySettingsRepository = mockk<PrivacySettingsRepository>(relaxed = true)
    private val diagnosticEmitter = mockk<DiagnosticEventWriter>(relaxed = true)
    private val transactionEventDao = mockk<TransactionEventDao>(relaxed = true)

    private val eventSlot = slot<TransactionEvent>()

    @Before
    fun setup() {
        // withTransaction compiles to the TOP-LEVEL static facade
        // androidx.room.RoomDatabaseKt.withTransaction (Room 2.7.2). Without
        // mockkStatic, the coAnswers stub records but never intercepts: the real
        // Room body runs against the relaxed AppDatabase mock and suspends
        // forever (measured full-suite stall point,
        // docs/testing/test-sweep-outcomes-2026-09-18.md, §Hangs).
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        coEvery { transactionEventDao.insert(capture(eventSlot)) } returns 1L
        // R27-S2c: runWrite is a pass-through here; a relaxed mock would never invoke the block.
        coEvery {
            writeBarrier.runWrite(any<DatabaseAccessOperation>(), any<suspend () -> Any?>())
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun repository() = NotificationRepository(
        database = database,
        dao = dao,
        blockedPackageDao = blockedPackageDao,
        expenseDao = expenseDao,
        pendingReviewDao = pendingReviewDao,
        userCorrectionDao = userCorrectionDao,
        sourceStatsDao = sourceStatsDao,
        // Runner clock deliberately differs from the injected `now`: the event must carry
        // the caller-supplied value (ctx.copy(occurredAt = now)), not the runner's clock.
        transactionRunner = RoomDomainTransactionRunner(database, FakeTimeProvider(fixedNow + 98_765L)),
        transactionLifecycleEventWriter = RoomTransactionLifecycleEventWriter(
            transactionEventDao, EventMetadataSanitizer(), fakeTimeProvider, writeBarrier
        ),
        classifier = classifier,
        pipeline = pipeline,
        writeBarrier = writeBarrier,
        privacySettingsRepository = privacySettingsRepository,
        diagnosticEmitter = diagnosticEmitter
    )

    @Test
    fun `deleteAllNotifications writes audit event occurredAt equal to the provider-derived now`() = runTest(timeout = 60.seconds) {
        val providedNow = fakeTimeProvider.now()
        repository().deleteAllNotifications(providedNow)

        coVerify(exactly = 1) { writeBarrier.checkWritesAllowed("NotificationRepository.deleteAllNotifications") }
        coVerify(exactly = 1) { transactionEventDao.insert(any()) }

        val event = eventSlot.captured
        assertEquals(LifecycleEventType.BULK_DELETED.name, event.eventType)
        assertEquals("SYSTEM", event.source)
        assertEquals(
            "occurredAt must be exactly the value read from the FakeTimeProvider",
            fakeTimeProvider.now(), event.occurredAt
        )
        assertTrue("audit metadata must identify the operation", event.metadata!!.contains("deleteAllNotifications"))
    }

    @Test
    fun `deleteAllNotifications preserves deletion behavior`() = runTest(timeout = 60.seconds) {
        repository().deleteAllNotifications(fakeTimeProvider.now())

        coVerify(exactly = 1) { dao.deleteAll() }
        coVerify(exactly = 1) { pendingReviewDao.deleteAll() }
        coVerify(exactly = 1) { userCorrectionDao.deleteAll() }
        coVerify(exactly = 1) { sourceStatsDao.resetAllPendingCounts() }
        coVerify(exactly = 1) { diagnosticEmitter.emit(any()) }
    }

    // ============================================================================
    // Ported from the retired NotificationRepositoryStressTest: the repository's
    // query accessors (reads), package blocking, delete, stats reset, classifier
    // retrain and pipeline delegation. Only the write paths go through the
    // pass-through write barrier; the query accessors are plain reads.
    // ============================================================================

    private fun sampleNotification(id: Long = 0L) = RawNotification(
        id = id,
        packageName = "com.test.bank",
        appName = "Test Bank",
        title = "Payment",
        text = "Spent 50 EUR",
        bigText = "Spent 50 EUR at Test Merchant",
        subText = "Synthetic card",
        extrasJson = """{"k":"v"}""",
        timestamp = 1_000L,
        capturedAt = 2_000L
    )

    private fun stubStorageMode(mode: RawStorageMode) {
        coEvery { privacySettingsRepository.getSettings() } returns
            PrivacySettings(rawNotificationStorageMode = mode)
    }

    private val sampleOutcome = NotificationPipelineOutcome.AutoAccepted(
        packageName = "com.test.bank", correlationId = null, rawId = 1L, expenseId = 1L
    )

    /**
     * Runs processAndSave under [mode]; returns the (processing, storage) notifications and the
     * [NotificationPersistenceContext] the pipeline received.
     */
    private suspend fun processUnderMode(
        mode: RawStorageMode,
        notification: RawNotification
    ): Triple<RawNotification, RawNotification, NotificationPersistenceContext> {
        stubStorageMode(mode)
        val processing = slot<RawNotification>()
        val storage = slot<RawNotification>()
        val context = slot<NotificationPersistenceContext>()
        coEvery {
            pipeline.process(capture(processing), capture(storage), any(), capture(context))
        } returns sampleOutcome
        repository().processAndSave(notification)
        return Triple(processing.captured, storage.captured, context.captured)
    }

    @Test
    fun `query accessors expose the DAO flows unchanged`() = runTest(timeout = 60.seconds) {
        val all = MutableStateFlow(emptyList<RawNotification>())
        val recent = MutableStateFlow(emptyList<RawNotification>())
        val byPackage = MutableStateFlow(emptyList<RawNotification>())
        val packages = MutableStateFlow(emptyList<String>())
        val count = MutableStateFlow(4)
        val stats = MutableStateFlow(emptyList<SourceStats>())
        val blocked = MutableStateFlow(emptyList<BlockedPackage>())
        val classifierStats = MutableStateFlow(ClassifierStats(0, 0, 0, false))
        every { dao.getAllFlow() } returns all
        every { dao.getRecentFlow(100) } returns recent
        every { dao.getByPackageFlow("com.test.bank") } returns byPackage
        every { dao.getAllPackagesFlow() } returns packages
        every { dao.getCountFlow() } returns count
        every { sourceStatsDao.getAllFlow() } returns stats
        every { blockedPackageDao.getAllFlow() } returns blocked
        every { classifier.stats } returns classifierStats

        val repo = repository()

        assertSame(all, repo.getAllNotifications())
        assertSame("default recent limit is 100", recent, repo.getRecentNotifications())
        assertSame(byPackage, repo.getNotificationsByPackage("com.test.bank"))
        assertSame(packages, repo.getAllPackages())
        assertSame(count, repo.getCount())
        assertSame(stats, repo.getSourceStats())
        assertSame(blocked, repo.getBlockedPackages())
        assertSame(classifierStats, repo.getClassifierStatsFlow())
    }

    @Test
    fun `exists passes the lookup through to the DAO and reports its answer`() = runTest(timeout = 60.seconds) {
        coEvery { dao.exists("com.test.bank", 1_000L, "Payment", "Spent 50 EUR", null) } returns true
        coEvery { dao.exists("com.test.bank", 1_001L, "Payment", "Spent 50 EUR", null) } returns false

        val repo = repository()

        assertTrue(repo.exists("com.test.bank", 1_000L, "Payment", "Spent 50 EUR"))
        assertFalse(repo.exists("com.test.bank", 1_001L, "Payment", "Spent 50 EUR"))
    }

    @Test
    fun `blockPackage stores the package inside the write barrier`() = runTest(timeout = 60.seconds) {
        repository().blockPackage("com.test.blocked")

        coVerify(exactly = 1) { writeBarrier.checkWritesAllowed("NotificationRepository.blockPackage") }
        coVerify(exactly = 1) {
            writeBarrier.runWrite(
                match<DatabaseAccessOperation> { it.name == "NotificationRepository.blockPackage" },
                any<suspend () -> Any?>()
            )
        }
        coVerify(exactly = 1) {
            blockedPackageDao.block(match { it.packageName == "com.test.blocked" })
        }
        coVerify(exactly = 0) { blockedPackageDao.unblock(any<String>()) }
        coVerify(exactly = 0) { blockedPackageDao.unblock(any<BlockedPackage>()) }
    }

    @Test
    fun `unblockPackage removes exactly that package inside the write barrier`() = runTest(timeout = 60.seconds) {
        repository().unblockPackage("com.test.blocked")

        coVerify(exactly = 1) { writeBarrier.checkWritesAllowed("NotificationRepository.unblockPackage") }
        coVerify(exactly = 1) {
            writeBarrier.runWrite(
                match<DatabaseAccessOperation> { it.name == "NotificationRepository.unblockPackage" },
                any<suspend () -> Any?>()
            )
        }
        coVerify(exactly = 1) { blockedPackageDao.unblock("com.test.blocked") }
        coVerify(exactly = 0) { blockedPackageDao.block(any()) }
    }

    @Test
    fun `isPackageBlocked reports the DAO answer per package`() = runTest(timeout = 60.seconds) {
        coEvery { blockedPackageDao.isBlocked("com.test.blocked") } returns true
        coEvery { blockedPackageDao.isBlocked("com.test.open") } returns false

        val repo = repository()

        assertTrue(repo.isPackageBlocked("com.test.blocked"))
        assertFalse(repo.isPackageBlocked("com.test.open"))
    }

    @Test
    fun `delete of a notification with a PENDING review decrements the pending count and removes both rows`() =
        runTest(timeout = 60.seconds) {
            val notification = sampleNotification(id = 42L)
            coEvery { pendingReviewDao.getByRawId(42L) } returns pendingReviewFor(42L, PendingReviewStatus.PENDING)

            repository().delete(notification)

            coVerify(exactly = 1) { sourceStatsDao.decrementPending("com.test.bank") }
            coVerify(exactly = 1) { pendingReviewDao.deleteByRawId(42L) }
            coVerify(exactly = 1) { dao.delete(notification) }
        }

    @Test
    fun `delete of a notification without a review does not decrement the pending count`() =
        runTest(timeout = 60.seconds) {
            val notification = sampleNotification(id = 99L)
            coEvery { pendingReviewDao.getByRawId(99L) } returns null

            repository().delete(notification)

            coVerify(exactly = 0) { sourceStatsDao.decrementPending(any()) }
            coVerify(exactly = 1) { pendingReviewDao.deleteByRawId(99L) }
            coVerify(exactly = 1) { dao.delete(notification) }
        }

    @Test
    fun `delete of a notification whose review is already APPROVED does not decrement the pending count`() =
        runTest(timeout = 60.seconds) {
            val notification = sampleNotification(id = 77L)
            coEvery { pendingReviewDao.getByRawId(77L) } returns pendingReviewFor(77L, PendingReviewStatus.APPROVED)

            repository().delete(notification)

            coVerify(exactly = 0) { sourceStatsDao.decrementPending(any()) }
            coVerify(exactly = 1) { pendingReviewDao.deleteByRawId(77L) }
            coVerify(exactly = 1) { dao.delete(notification) }
        }

    @Test
    fun `resetSourceStats clears the source stats inside the write barrier`() = runTest(timeout = 60.seconds) {
        repository().resetSourceStats()

        coVerify(exactly = 1) { writeBarrier.checkWritesAllowed("NotificationRepository.resetSourceStats") }
        coVerify(exactly = 1) {
            writeBarrier.runWrite(
                match<DatabaseAccessOperation> { it.name == "NotificationRepository.resetSourceStats" },
                any<suspend () -> Any?>()
            )
        }
        coVerify(exactly = 1) { sourceStatsDao.deleteAll() }
    }

    @Test
    fun `retrainClassifier delegates to the classifier exactly once`() = runTest(timeout = 60.seconds) {
        repository().retrainClassifier()

        coVerify(exactly = 1) { classifier.retrainFromCorrections() }
    }

    @Test
    fun `processAndSave under STORE_RAW persists the notification unchanged and returns the pipeline outcome`() =
        runTest(timeout = 60.seconds) {
            val notification = sampleNotification()
            stubStorageMode(RawStorageMode.STORE_RAW)
            coEvery { pipeline.process(any(), any(), any(), any()) } returns sampleOutcome

            val result = repository().processAndSave(notification)

            assertEquals(sampleOutcome, result)
            coVerify(exactly = 1) {
                pipeline.process(
                    notification,
                    notification,
                    null,
                    NotificationPersistenceContext(RawStorageMode.STORE_RAW, null, "direct")
                )
            }
        }

    @Test
    fun `processAndSave under STORE_REDACTED parses the original but persists only redacted text`() =
        runTest(timeout = 60.seconds) {
            val notification = sampleNotification()

            val (processing, storage, context) = processUnderMode(RawStorageMode.STORE_REDACTED, notification)

            assertEquals(
                NotificationPersistenceContext(RawStorageMode.STORE_REDACTED, null, "direct"),
                context
            )
            assertEquals(RawStorageMode.STORE_REDACTED, context.rawStorageMode)
            assertEquals("parsing must see the original content", notification, processing)
            assertEquals("[REDACTED]", storage.title)
            assertEquals("[REDACTED]", storage.text)
            assertEquals("[REDACTED]", storage.bigText)
            assertEquals("[REDACTED]", storage.subText)
            assertEquals("""{"redacted":true}""", storage.extrasJson)
            assertEquals(notification.packageName, storage.packageName)
            assertEquals(notification.timestamp, storage.timestamp)
        }

    @Test
    fun `processAndSave under STORE_METADATA_ONLY and DO_NOT_STORE persists no notification text`() =
        runTest(timeout = 60.seconds) {
            val notification = sampleNotification()

            for (mode in listOf(RawStorageMode.STORE_METADATA_ONLY, RawStorageMode.DO_NOT_STORE)) {
                val (processing, storage, context) = processUnderMode(mode, notification)

                assertEquals(
                    NotificationPersistenceContext(mode, null, "direct"),
                    context
                )
                assertEquals("rawStorageMode under $mode", mode, context.rawStorageMode)
                assertEquals("parsing must see the original content under $mode", notification, processing)
                assertNull("title under $mode", storage.title)
                assertNull("text under $mode", storage.text)
                assertNull("bigText under $mode", storage.bigText)
                assertNull("subText under $mode", storage.subText)
                assertNull("extrasJson under $mode", storage.extrasJson)
                assertEquals(notification.packageName, storage.packageName)
                assertEquals(notification.timestamp, storage.timestamp)
            }
        }

    @Test
    fun `processAndSaveAll returns one outcome per notification in input order`() = runTest(timeout = 60.seconds) {
        val first = sampleNotification().copy(title = "First")
        val second = sampleNotification().copy(title = "Second")
        val firstOutcome = NotificationPipelineOutcome.AutoAccepted("com.test.bank", null, 1L, 11L)
        val secondOutcome = NotificationPipelineOutcome.Duplicate("com.test.bank", null, "synthetic-duplicate")
        stubStorageMode(RawStorageMode.STORE_RAW)
        coEvery { pipeline.process(first, first, null, any()) } returns firstOutcome
        coEvery { pipeline.process(second, second, null, any()) } returns secondOutcome

        val results = repository().processAndSaveAll(listOf(first, second))

        assertEquals(listOf<NotificationPipelineOutcome>(firstOutcome, secondOutcome), results)
    }

    @Test
    fun `processAndSaveAll with an empty list returns an empty list and never touches the pipeline`() =
        runTest(timeout = 60.seconds) {
            val results = repository().processAndSaveAll(emptyList())

            assertTrue(results.isEmpty())
            coVerify(exactly = 0) { pipeline.process(any(), any(), any(), any()) }
            coVerify(exactly = 0) { pipeline.processBatch(any()) }
        }

    private fun pendingReviewFor(rawId: Long, status: PendingReviewStatus) = PendingReview(
        rawNotificationId = rawId,
        suggestedAmount = 50.0,
        suggestedCurrency = "EUR",
        suggestedMerchant = "Test Merchant",
        suggestedType = "PURCHASE",
        suggestedCategoryId = null,
        confidence = 0.9f,
        packageName = "com.test.bank",
        notificationTitle = "Payment",
        notificationText = "Spent 50 EUR",
        status = status
    )
}
