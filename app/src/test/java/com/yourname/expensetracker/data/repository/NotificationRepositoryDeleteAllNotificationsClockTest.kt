package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BlockedPackageDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.RawNotificationDao
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T3A / G-TIME-01 focused test for [NotificationRepository.deleteAllNotifications].
 *
 * The method takes an explicit `now` from the caller's injected
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
        every { database.transactionEventDao() } returns transactionEventDao
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { transactionEventDao.insert(capture(eventSlot)) } returns 1L
    }

    private fun repository() = NotificationRepository(
        database = database,
        dao = dao,
        blockedPackageDao = blockedPackageDao,
        expenseDao = expenseDao,
        pendingReviewDao = pendingReviewDao,
        userCorrectionDao = userCorrectionDao,
        sourceStatsDao = sourceStatsDao,
        classifier = classifier,
        pipeline = pipeline,
        writeBarrier = writeBarrier,
        privacySettingsRepository = privacySettingsRepository,
        diagnosticEmitter = diagnosticEmitter
    )

    @Test
    fun `deleteAllNotifications writes audit event occurredAt equal to the provider-derived now`() = runTest {
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
    fun `deleteAllNotifications preserves deletion behavior`() = runTest {
        repository().deleteAllNotifications(fakeTimeProvider.now())

        coVerify(exactly = 1) { dao.deleteAll() }
        coVerify(exactly = 1) { pendingReviewDao.deleteAll() }
        coVerify(exactly = 1) { userCorrectionDao.deleteAll() }
        coVerify(exactly = 1) { sourceStatsDao.resetAllPendingCounts() }
        coVerify(exactly = 1) { diagnosticEmitter.emit(any()) }
    }
}
