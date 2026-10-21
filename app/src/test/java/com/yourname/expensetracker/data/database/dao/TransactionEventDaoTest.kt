package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Unit tests for [TransactionEventDao] covering insert, query-by-expense,
 * timestamp ordering, and count verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionEventDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: TransactionEventDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.transactionEventDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createEvent(
        expenseId: Long? = 100L,
        eventType: String = "CREATED",
        source: String = "TEST",
        actor: String? = "test",
        occurredAt: Long = FIXED_NOW,
        dedupeKey: String? = null,
        duplicateExpenseId: Long? = null,
        beforeSnapshot: String? = null,
        afterSnapshot: String? = null,
        metadata: String? = null,
        reason: String? = null
    ): TransactionEvent = TransactionEvent(
        expenseId = expenseId,
        eventType = eventType,
        source = source,
        actor = actor,
        occurredAt = occurredAt,
        dedupeKey = dedupeKey,
        duplicateExpenseId = duplicateExpenseId,
        beforeSnapshot = beforeSnapshot,
        afterSnapshot = afterSnapshot,
        metadata = metadata,
        reason = reason
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `insert then read back returns every transaction event field exactly`() = runTest {
        val expenseId = 42L
        val id = dao.insert(
            createEvent(
                expenseId = expenseId,
                eventType = "CREATED",
                source = "TEST",
                actor = "test",
                occurredAt = FIXED_NOW,
                dedupeKey = "dedupe-key-1",
                duplicateExpenseId = 7L,
                beforeSnapshot = """{"a":1}""",
                afterSnapshot = """{"a":2}""",
                metadata = "meta-code",
                reason = "reason-code"
            )
        )
        assertEquals(1L, id)

        val results = dao.getEventsForExpense(expenseId)

        assertEquals(1, results.size)
        val loaded = results[0]
        assertEquals(id, loaded.id)
        assertEquals(expenseId, loaded.expenseId)
        assertEquals("CREATED", loaded.eventType)
        assertEquals("TEST", loaded.source)
        assertEquals("test", loaded.actor)
        assertEquals(FIXED_NOW, loaded.occurredAt)
        assertEquals("dedupe-key-1", loaded.dedupeKey)
        assertEquals(7L, loaded.duplicateExpenseId)
        assertEquals("""{"a":1}""", loaded.beforeSnapshot)
        assertEquals("""{"a":2}""", loaded.afterSnapshot)
        assertEquals("meta-code", loaded.metadata)
        assertEquals("reason-code", loaded.reason)
    }

    @Test
    fun `query events by expenseId returns empty list for unknown expense`() = runTest {
        val event = createEvent(expenseId = 1L)
        dao.insert(event)

        val results = dao.getEventsForExpense(999L)

        assertEquals(0, results.size)
    }

    @Test
    fun `verify ordering by timestamp descending`() = runTest {
        val expenseId = 10L
        val early = createEvent(expenseId = expenseId, occurredAt = FIXED_NOW)
        val late = createEvent(expenseId = expenseId, occurredAt = FIXED_NOW + 1000)

        dao.insert(early)
        dao.insert(late)

        val results = dao.getEventsForExpense(expenseId)

        assertEquals(2, results.size)
        // Most recent first
        assertEquals(FIXED_NOW + 1000, results[0].occurredAt)
        assertEquals(FIXED_NOW, results[1].occurredAt)
    }

    @Test
    fun `events for different expenseIds do not mix`() = runTest {
        dao.insert(createEvent(expenseId = 1L, eventType = "CREATED"))
        dao.insert(createEvent(expenseId = 2L, eventType = "CREATED"))
        dao.insert(createEvent(expenseId = 1L, eventType = "UPDATED"))

        val resultsFor1 = dao.getEventsForExpense(1L)
        val resultsFor2 = dao.getEventsForExpense(2L)

        assertEquals(2, resultsFor1.size)
        assertEquals(1, resultsFor2.size)
    }

    @Test
    fun `insert event with nullable expenseId`() = runTest {
        val event = createEvent(expenseId = null)
        val id = dao.insert(event)

        assertNotNull(id)
        val results = dao.getEventsForExpense(0L) // no expense should match
        assertEquals(0, results.size)
    }

    // -------------------------------------------------------------------------
    // RP-14 P8-001: snapshot nulling (stage 1)
    // -------------------------------------------------------------------------

    private val dayMs = 24L * 60 * 60 * 1000

    @Test
    fun `nullSnapshotsOlderThan clears only snapshot columns of older events`() = runTest {
        val oldId = dao.insert(
            createEvent(
                eventType = "UPDATED",
                occurredAt = FIXED_NOW - 31 * dayMs,
                beforeSnapshot = """{"a":1}""",
                afterSnapshot = """{"a":2}"""
            )
        )
        val recentId = dao.insert(
            createEvent(
                occurredAt = FIXED_NOW,
                beforeSnapshot = """{"a":1}""",
                afterSnapshot = """{"a":2}"""
            )
        )

        val nulled = dao.nullSnapshotsOlderThan(FIXED_NOW - 30 * dayMs)

        assertEquals(1, nulled)
        val all = dao.getEventsForExpense(100L)
        val oldRow = all.first { it.id == oldId }
        assertNull(oldRow.beforeSnapshot)
        assertNull(oldRow.afterSnapshot)
        // Other columns are untouched.
        assertEquals("UPDATED", oldRow.eventType)
        assertNotNull(all.first { it.id == recentId }.beforeSnapshot)
    }

    @Test
    fun `nullSnapshotsOlderThan keeps rows exactly at cutoff and skips snapshot-less rows`() = runTest {
        val atCutoffId = dao.insert(
            createEvent(occurredAt = FIXED_NOW - 30 * dayMs, beforeSnapshot = """{"a":1}""")
        )
        dao.insert(
            createEvent(
                occurredAt = FIXED_NOW - 40 * dayMs,
                beforeSnapshot = null,
                afterSnapshot = null
            )
        )

        // Strict `<`: at-cutoff row kept; the snapshot-less old row is not counted.
        val nulled = dao.nullSnapshotsOlderThan(FIXED_NOW - 30 * dayMs)

        assertEquals(0, nulled)
        assertNotNull(dao.getEventsForExpense(100L).first { it.id == atCutoffId }.beforeSnapshot)
    }

    @Test
    fun `nullSnapshotsOlderThan is idempotent`() = runTest {
        dao.insert(createEvent(occurredAt = FIXED_NOW - 31 * dayMs, afterSnapshot = """{"a":1}"""))
        val cutoff = FIXED_NOW - 30 * dayMs

        assertEquals(1, dao.nullSnapshotsOlderThan(cutoff))
        assertEquals(0, dao.nullSnapshotsOlderThan(cutoff))
    }
}
