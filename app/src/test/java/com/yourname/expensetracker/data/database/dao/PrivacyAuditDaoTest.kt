package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.PrivacyAuditEvent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Unit tests for [PrivacyAuditDao] covering insert, query recent, and
 * ordering by timestamp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacyAuditDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: PrivacyAuditDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.privacyAuditDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createEvent(
        capability: String = "LOCATION",
        decision: String = "ALLOWED",
        reason: String? = "User granted permission",
        context: String? = null,
        timestampMs: Long = FIXED_NOW,
        caller: String? = "test"
    ): PrivacyAuditEvent = PrivacyAuditEvent(
        capability = capability,
        decision = decision,
        reason = reason,
        context = context,
        timestampMs = timestampMs,
        caller = caller
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `insert an audit event`() = runTest {
        val event = createEvent()
        val id = dao.insert(event)

        assertTrue(id > 0)
    }

    @Test
    fun `getRecent returns all events within limit`() = runTest {
        dao.insert(createEvent(capability = "LOCATION", timestampMs = FIXED_NOW))
        dao.insert(createEvent(capability = "CAMERA", timestampMs = FIXED_NOW + 1000))
        dao.insert(createEvent(capability = "CONTACTS", timestampMs = FIXED_NOW + 2000))

        val results = dao.getRecent(10)

        assertEquals(3, results.size)
    }

    @Test
    fun `getRecent respects limit parameter`() = runTest {
        dao.insert(createEvent(timestampMs = FIXED_NOW))
        dao.insert(createEvent(timestampMs = FIXED_NOW + 1000))
        dao.insert(createEvent(timestampMs = FIXED_NOW + 2000))

        val results = dao.getRecent(2)

        assertEquals(2, results.size)
    }

    @Test
    fun `verify ordering by timestamp descending`() = runTest {
        dao.insert(createEvent(timestampMs = FIXED_NOW))
        dao.insert(createEvent(timestampMs = FIXED_NOW + 5000))
        dao.insert(createEvent(timestampMs = FIXED_NOW + 2000))

        val results = dao.getRecent(10)

        assertEquals(3, results.size)
        // Most recent first
        assertEquals(FIXED_NOW + 5000, results[0].timestampMs)
        assertEquals(FIXED_NOW + 2000, results[1].timestampMs)
        assertEquals(FIXED_NOW, results[2].timestampMs)
    }

    @Test
    fun `insert multiple events and verify content`() = runTest {
        dao.insert(createEvent(capability = "LOCATION", decision = "ALLOWED"))
        dao.insert(createEvent(capability = "CAMERA", decision = "DENIED", reason = "No permission"))

        val results = dao.getRecent(10)

        assertEquals(2, results.size)
        assertEquals("CAMERA", results[0].capability)
        assertEquals("DENIED", results[0].decision)
    }

    @Test
    fun `getRecent returns empty list when no events exist`() = runTest {
        val results = dao.getRecent(10)

        assertTrue(results.isEmpty())
    }
}
