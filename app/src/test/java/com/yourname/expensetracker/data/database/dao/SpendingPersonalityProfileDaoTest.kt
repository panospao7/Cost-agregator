package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.SpendingPersonalityProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Focused DAO tests for [SpendingPersonalityProfileDao.markAsViewed]:
 * - the exact caller-supplied timestamp must land in viewedAt (no wall clock)
 * - isViewed must become true
 * - unrelated profiles must be untouched
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpendingPersonalityProfileDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SpendingPersonalityProfileDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.spendingPersonalityProfileDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `markAsViewed persists exact supplied timestamp and sets viewed flag`() = runTest {
        val profileId = dao.insert(createProfile())

        val viewedAt = FIXED_NOW + 90_000L
        dao.markAsViewed(profileId, viewedAt)

        val loaded = dao.getActiveProfile()
        assertNotNull(loaded)
        assertTrue(loaded.isViewed)
        assertEquals(viewedAt, loaded.viewedAt)
    }

    @Test
    fun `markAsViewed only updates the targeted profile`() = runTest {
        val firstId = dao.insert(createProfile(lastUpdated = FIXED_NOW))
        val secondId = dao.insert(createProfile(lastUpdated = FIXED_NOW + 1L))

        val viewedAt = FIXED_NOW + 90_000L
        dao.markAsViewed(firstId, viewedAt)

        val first = dao.getAllProfiles().first { it.id == firstId }
        val second = dao.getAllProfiles().first { it.id == secondId }
        assertTrue(first.isViewed)
        assertEquals(viewedAt, first.viewedAt)
        assertFalse(second.isViewed)
        assertNull(second.viewedAt)
    }

    private fun createProfile(lastUpdated: Long = FIXED_NOW): SpendingPersonalityProfileEntity {
        return SpendingPersonalityProfileEntity(
            personalityType = "BALANCED",
            confidence = 0.75,
            featureScoresJson = "{}",
            explanationJson = "[]",
            coachingTipsJson = "[]",
            lastUpdated = lastUpdated,
            analysisPeriodStart = FIXED_NOW - 30L * 24 * 60 * 60 * 1000,
            analysisPeriodEnd = FIXED_NOW,
            transactionCount = 120,
            isViewed = false,
            viewedAt = null,
            isActive = true
        )
    }
}
