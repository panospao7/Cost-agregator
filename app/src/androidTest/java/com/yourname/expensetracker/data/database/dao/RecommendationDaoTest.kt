package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.RecommendationEntity
import com.yourname.expensetracker.domain.model.recommendation.RecommendationPriority
import com.yourname.expensetracker.domain.model.recommendation.RecommendationStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecommendationDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: RecommendationDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.recommendationDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeRecommendation(
        id: String,
        userId: String = "user-1",
        status: RecommendationStatus = RecommendationStatus.ACTIVE,
        expiresAt: Long,
        createdAt: Long,
        dismissedAt: Long? = null,
        priority: RecommendationPriority = RecommendationPriority.MEDIUM
    ) = RecommendationEntity(
        id = id,
        userId = userId,
        recommendationText = "Review your recent spending",
        navigationTarget = "dashboard",
        filterCriteria = "{}",
        createdAt = createdAt,
        updatedAt = createdAt,
        dismissedAt = dismissedAt,
        expiresAt = expiresAt,
        priority = priority,
        category = "INSIGHT",
        sourceArtifactId = "artifact-$id",
        status = status
    )

    @Test
    fun insertRecommendation_retrieveById() = runBlocking {
        val now = 1_700_000_000_000L
        val recommendation = makeRecommendation(
            id = "rec-1",
            expiresAt = now + 86_400_000L,
            createdAt = now
        )

        dao.insert(recommendation)

        val fetched = dao.getById("rec-1")
        assertNotNull(fetched)
        assertEquals("rec-1", fetched!!.id)
        assertEquals("user-1", fetched.userId)
    }

    @Test
    fun queryUnreadRecommendations_returnsActiveUndismissedUnexpired() = runBlocking {
        val now = 1_700_000_000_000L

        dao.insert(makeRecommendation(id = "unread", expiresAt = now + 10_000, createdAt = now + 3_000))
        dao.insert(makeRecommendation(id = "archived", status = RecommendationStatus.ARCHIVED, expiresAt = now + 10_000, createdAt = now + 2_000, dismissedAt = now + 2_500))
        dao.insert(makeRecommendation(id = "expired", expiresAt = now - 1, createdAt = now + 1_000))

        val unread = dao.getActiveByUser(userId = "user-1", nowMillis = now)

        assertEquals(1, unread.size)
        assertEquals("unread", unread[0].id)
    }

    @Test
    fun markAsRead_archiveRecommendation_verifyStateChange() = runBlocking {
        val now = 1_700_000_000_000L
        dao.insert(makeRecommendation(id = "rec-read", expiresAt = now + 100_000, createdAt = now))

        dao.archive(id = "rec-read", nowMillis = now + 5_000)

        val updated = dao.getById("rec-read")
        assertNotNull(updated)
        assertEquals(RecommendationStatus.ARCHIVED, updated!!.status)
        assertEquals(now + 5_000, updated.dismissedAt)

        val activeAfterArchive = dao.getActiveByUser("user-1", now)
        assertTrue(activeAfterArchive.none { it.id == "rec-read" })
    }

    @Test
    fun deleteOldRecommendations_removesExpiredRows() = runBlocking {
        val now = 1_700_000_000_000L

        dao.insert(makeRecommendation(id = "to-delete", status = RecommendationStatus.EXPIRED, expiresAt = now - 1_000, createdAt = now - 10_000))
        dao.insert(makeRecommendation(id = "keep-active", status = RecommendationStatus.ACTIVE, expiresAt = now + 10_000, createdAt = now - 5_000))

        val deletedCount = dao.deleteExpired(nowMillis = now)

        assertEquals(1, deletedCount)
        assertNull(dao.getById("to-delete"))
        assertNotNull(dao.getById("keep-active"))
    }
}
