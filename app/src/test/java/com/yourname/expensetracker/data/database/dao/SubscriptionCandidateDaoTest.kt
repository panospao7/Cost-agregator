package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.SubscriptionCandidate
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
 * Unit tests for [SubscriptionCandidateDao.markAsConverted] and
 * [SubscriptionCandidateDao.markAsRejected].
 *
 * Verifies that the exact caller-supplied timestamp is persisted into
 * `updatedAt`, and that the status/id fields (isConverted,
 * convertedSubscriptionId, userAction) are set exactly as documented —
 * without any wall clock or DAO-side defaulting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubscriptionCandidateDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SubscriptionCandidateDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.subscriptionCandidateDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun candidate(
        canonicalMerchant: String,
        merchant: String = canonicalMerchant,
        userAction: String = "pending",
        isConverted: Boolean = false,
        convertedSubscriptionId: Long? = null,
        createdAt: Long = FIXED_NOW,
        updatedAt: Long = FIXED_NOW
    ): SubscriptionCandidate = SubscriptionCandidate(
        merchant = merchant,
        canonicalMerchant = canonicalMerchant,
        averageAmount = 9.99,
        currency = "EUR",
        detectedInterval = "monthly",
        confidence = 0.95,
        transactionCount = 3,
        firstSeen = FIXED_NOW - 30L * 86_400_000L,
        lastSeen = FIXED_NOW,
        estimatedAnnualCost = 119.88,
        isConverted = isConverted,
        convertedSubscriptionId = convertedSubscriptionId,
        userAction = userAction,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    @Test
    fun `markAsConverted persists exact timestamp and status fields`() = runTest {
        val id = dao.insert(candidate(canonicalMerchant = "netflix"))

        // Deliberately non-round timestamp that differs from creation time.
        val convertTimestamp = FIXED_NOW + 321_000L
        dao.markAsConverted(id, subscriptionId = 42L, timestamp = convertTimestamp)

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertTrue(loaded.isConverted)
        assertEquals(42L, loaded.convertedSubscriptionId)
        assertEquals("accepted", loaded.userAction)
        assertEquals(convertTimestamp, loaded.updatedAt)
    }

    @Test
    fun `markAsConverted uses caller timestamp even when older than creation`() = runTest {
        val id = dao.insert(candidate(canonicalMerchant = "spotify"))

        // Timestamp strictly older than createdAt proves the DAO does not
        // substitute its own clock or default to creation time.
        val olderTimestamp = FIXED_NOW - 3_000L
        dao.markAsConverted(id, subscriptionId = 7L, timestamp = olderTimestamp)

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals(olderTimestamp, loaded.updatedAt)
        assertEquals(7L, loaded.convertedSubscriptionId)
        assertEquals("accepted", loaded.userAction)
    }

    @Test
    fun `markAsRejected persists exact timestamp and status fields`() = runTest {
        val id = dao.insert(candidate(canonicalMerchant = "hbo"))

        val rejectTimestamp = FIXED_NOW + 88_888L
        dao.markAsRejected(id, rejectTimestamp)

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals("rejected", loaded.userAction)
        assertEquals(rejectTimestamp, loaded.updatedAt)
        // Rejection must not touch conversion state.
        assertFalse(loaded.isConverted)
        assertNull(loaded.convertedSubscriptionId)
    }

    @Test
    fun `markAsConverted does not affect other pending candidates`() = runTest {
        val convertedId = dao.insert(candidate(canonicalMerchant = "disney"))
        val pendingId = dao.insert(candidate(canonicalMerchant = "audible"))

        dao.markAsConverted(convertedId, subscriptionId = 5L, timestamp = FIXED_NOW + 10L)

        val converted = dao.getById(convertedId)
        val pending = dao.getById(pendingId)
        assertNotNull(converted)
        assertNotNull(pending)

        assertEquals("accepted", converted.userAction)
        assertTrue(converted.isConverted)

        assertEquals("pending", pending.userAction)
        assertFalse(pending.isConverted)
        assertNull(pending.convertedSubscriptionId)
        assertEquals(FIXED_NOW, pending.updatedAt)
    }
}
