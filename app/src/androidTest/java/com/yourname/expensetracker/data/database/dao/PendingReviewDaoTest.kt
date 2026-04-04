package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
import com.yourname.expensetracker.data.database.entity.RawNotification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingReviewDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var pendingReviewDao: PendingReviewDao
    private lateinit var rawNotificationDao: RawNotificationDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        pendingReviewDao = database.pendingReviewDao()
        rawNotificationDao = database.rawNotificationDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun insertRawNotification(): Long {
        return rawNotificationDao.insert(RawNotification(
            packageName = "com.test",
            appName = "Test",
            title = "Test",
            text = "Test",
            timestamp = System.currentTimeMillis(),
            capturedAt = System.currentTimeMillis()
        ))
    }

    private fun makeReview(rawId: Long) = PendingReview(
        rawNotificationId = rawId,
        suggestedAmount = 10.0,
        suggestedCurrency = "EUR",
        suggestedMerchant = "Test Merchant",
        suggestedType = "PURCHASE",
        suggestedCategoryId = null,
        confidence = 0.75f,
        packageName = "com.test",
        notificationTitle = "Test",
        notificationText = "Test text"
    )

    @Test
    fun insertAndRetrievePending() = runBlocking {
        val rawId = insertRawNotification()
        pendingReviewDao.insert(makeReview(rawId))

        val pending = pendingReviewDao.getPending()
        assertEquals(1, pending.size)
        assertEquals(PendingReviewStatus.PENDING, pending[0].review.status)
    }

    @Test
    fun pendingCountFlow() = runBlocking {
        val rawId = insertRawNotification()
        pendingReviewDao.insert(makeReview(rawId))

        val count = pendingReviewDao.getPendingCountFlow().first()
        assertEquals(1, count)
    }

    @Test
    fun updateStatusIfPendingSucceeds() = runBlocking {
        val rawId = insertRawNotification()
        val id = pendingReviewDao.insert(makeReview(rawId))

        val rows = pendingReviewDao.transitionStatus(
            id,
            PendingReviewStatus.PENDING,
            PendingReviewStatus.APPROVED
        )
        assertEquals(1, rows)

        val review = pendingReviewDao.getById(id)
        assertEquals(PendingReviewStatus.APPROVED, review?.status)
    }

    @Test
    fun updateStatusIfPendingFailsWhenAlreadyResolved() = runBlocking {
        val rawId = insertRawNotification()
        val id = pendingReviewDao.insert(makeReview(rawId))

        pendingReviewDao.transitionStatus(
            id,
            PendingReviewStatus.PENDING,
            PendingReviewStatus.APPROVED
        )
        val rows = pendingReviewDao.transitionStatus(
            id,
            PendingReviewStatus.PENDING,
            PendingReviewStatus.REJECTED
        )
        assertEquals(0, rows) // Already APPROVED, not PENDING
    }

    @Test
    fun getPendingExcludesResolved() = runBlocking {
        val rawId1 = insertRawNotification()
        val rawId2 = insertRawNotification()
        val id1 = pendingReviewDao.insert(makeReview(rawId1))
        pendingReviewDao.insert(makeReview(rawId2))

        pendingReviewDao.updateStatus(id1, PendingReviewStatus.APPROVED)

        val pending = pendingReviewDao.getPending()
        assertEquals(1, pending.size)
    }

    @Test
    fun clearResolvedKeepsPending() = runBlocking {
        val rawId1 = insertRawNotification()
        val rawId2 = insertRawNotification()
        val id1 = pendingReviewDao.insert(makeReview(rawId1))
        pendingReviewDao.insert(makeReview(rawId2))

        pendingReviewDao.updateStatus(id1, PendingReviewStatus.REJECTED)
        pendingReviewDao.clearResolved()

        val all = pendingReviewDao.getAllFlow().first()
        assertEquals(1, all.size)
        assertEquals(PendingReviewStatus.PENDING, all[0].status)
    }

    @Test
    fun approveAllPendingApprovesAllPending() = runBlocking {
        val rawId1 = insertRawNotification()
        val rawId2 = insertRawNotification()
        pendingReviewDao.insert(makeReview(rawId1))
        pendingReviewDao.insert(makeReview(rawId2))

        pendingReviewDao.approveAllPending()

        val pending = pendingReviewDao.getPending()
        assertEquals(0, pending.size)

        val all = pendingReviewDao.getAllFlow().first()
        assertEquals(2, all.size)
        assertEquals(PendingReviewStatus.APPROVED, all[0].status)
        assertEquals(PendingReviewStatus.APPROVED, all[1].status)
    }
}
