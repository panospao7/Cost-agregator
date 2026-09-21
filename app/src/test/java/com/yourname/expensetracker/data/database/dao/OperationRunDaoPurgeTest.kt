package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
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
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L
private val DAY_MS = 24L * 60 * 60 * 1000

/**
 * RP-14 P8-003: operation-run retention DAO tests.
 *
 * Covers the compound [OperationRunDao.purgeTerminalRunsWithEvents] transaction
 * (children then parents for TERMINAL runs only — RUNNING rows are never
 * purged), the strict `finishedAt < cutoff` boundary, and the separate orphan
 * cleanup ([OperationRunEventDao.deleteOrphanEventsOlderThan], where orphan =
 * null parent or no matching parent row).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OperationRunDaoPurgeTest {

    private lateinit var database: AppDatabase
    private lateinit var runDao: OperationRunDao
    private lateinit var eventDao: OperationRunEventDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        runDao = database.operationRunDao()
        eventDao = database.operationRunEventDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createRun(
        correlationId: String,
        status: String,
        startedAt: Long,
        finishedAt: Long? = null
    ) = OperationRun(
        correlationId = correlationId,
        operationType = "TEST_OP",
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt
    )

    private fun createEvent(runId: Long?, occurredAt: Long = FIXED_NOW) = OperationRunEvent(
        operationRunId = runId,
        correlationId = "corr-${runId ?: "orphan"}",
        operationType = "TEST_OP",
        stage = "stage",
        eventType = "EVENT",
        outcome = "DONE",
        severity = "INFO",
        occurredAt = occurredAt
    )

    private suspend fun insertRunWithEvents(
        correlationId: String,
        status: String,
        startedAt: Long,
        finishedAt: Long?,
        eventCount: Int = 1
    ): Long {
        val runId = runDao.insert(createRun(correlationId, status, startedAt, finishedAt))
        repeat(eventCount) { eventDao.insert(createEvent(runId, startedAt)) }
        return runId
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `purge deletes children then parents for old terminal runs and reports counts`() = runTest {
        val cutoff = FIXED_NOW - 90 * DAY_MS
        val oldTerminalId = insertRunWithEvents("old-terminal", "SUCCESS", cutoff - 1, finishedAt = cutoff - 1, eventCount = 2)
        val recentId = insertRunWithEvents("recent", "FAILED_FINAL", FIXED_NOW, finishedAt = FIXED_NOW)

        val counts = runDao.purgeTerminalRunsWithEvents(cutoff)

        assertEquals(2, counts.childEventsDeleted)
        assertEquals(1, counts.parentRunsDeleted)
        assertNull(runDao.getById(oldTerminalId))
        assertTrue(eventDao.getByRunId(oldTerminalId).isEmpty())
        // Recent terminal run untouched.
        assertNotNull(runDao.getById(recentId))
        assertEquals(1, eventDao.getByRunId(recentId).size)
    }

    @Test
    fun `purge never touches RUNNING rows even with old startedAt`() = runTest {
        val cutoff = FIXED_NOW - 90 * DAY_MS
        val runningId = runDao.insert(createRun("still-running", "RUNNING", cutoff - 1, finishedAt = null))
        eventDao.insert(createEvent(runningId, cutoff - 1))

        val counts = runDao.purgeTerminalRunsWithEvents(cutoff)

        assertEquals(0, counts.childEventsDeleted)
        assertEquals(0, counts.parentRunsDeleted)
        assertNotNull(runDao.getById(runningId))
        assertEquals(1, eventDao.getByRunId(runningId).size)
    }

    @Test
    fun `purge keeps terminal runs exactly at the cutoff - strict less-than`() = runTest {
        val cutoff = FIXED_NOW - 90 * DAY_MS
        val atCutoffId = insertRunWithEvents("at-cutoff", "SUCCESS", cutoff, finishedAt = cutoff)

        val counts = runDao.purgeTerminalRunsWithEvents(cutoff)

        assertEquals(0, counts.childEventsDeleted)
        assertEquals(0, counts.parentRunsDeleted)
        assertNotNull(runDao.getById(atCutoffId))
    }

    @Test
    fun `orphan cleanup deletes events with null parent or dangling parent id only`() = runTest {
        val cutoff = FIXED_NOW - 90 * DAY_MS
        val parentId = insertRunWithEvents("parent", "SUCCESS", cutoff - 1, finishedAt = cutoff - 1)
        eventDao.insert(createEvent(runId = null, occurredAt = cutoff - 1))
        eventDao.insert(createEvent(runId = 999_999L, occurredAt = cutoff - 1)) // no matching parent
        eventDao.insert(createEvent(runId = parentId, occurredAt = cutoff - 1)) // attached — kept by orphan pass
        eventDao.insert(createEvent(runId = null, occurredAt = FIXED_NOW)) // recent orphan — kept

        val orphans = eventDao.deleteOrphanEventsOlderThan(cutoff)

        assertEquals(2, orphans)
        val remaining = eventDao.getByCorrelationId("corr-$parentId") +
            eventDao.getByCorrelationId("corr-orphan")
        // Remaining: the attached event on the surviving... note the parent run
        // was NOT purged in this test, so its event must still exist.
        assertTrue(remaining.any { it.operationRunId == parentId })
        assertTrue(remaining.any { it.operationRunId == null && it.occurredAt == FIXED_NOW })
    }

    @Test
    fun `purge is idempotent - rerun reports zero`() = runTest {
        val cutoff = FIXED_NOW - 90 * DAY_MS
        insertRunWithEvents("old", "SUCCESS", cutoff - 1, finishedAt = cutoff - 1, eventCount = 3)

        val first = runDao.purgeTerminalRunsWithEvents(cutoff)
        assertEquals(3, first.childEventsDeleted)
        assertEquals(1, first.parentRunsDeleted)

        val second = runDao.purgeTerminalRunsWithEvents(cutoff)
        assertEquals(0, second.childEventsDeleted)
        assertEquals(0, second.parentRunsDeleted)
    }

    @Test
    fun `full pass - compound purge then orphan sweep leaves only recent and running state`() = runTest {
        val cutoff = FIXED_NOW - 90 * DAY_MS
        insertRunWithEvents("old-done", "SUCCESS", cutoff - 1, finishedAt = cutoff - 1)
        val runningId = insertRunWithEvents("old-running", "RUNNING", cutoff - 1, finishedAt = null)
        val recentId = insertRunWithEvents("recent", "SUCCESS", FIXED_NOW, finishedAt = FIXED_NOW)
        eventDao.insert(createEvent(runId = null, occurredAt = cutoff - 1))

        val counts = runDao.purgeTerminalRunsWithEvents(cutoff)
        val orphans = eventDao.deleteOrphanEventsOlderThan(cutoff)

        assertEquals(1, counts.childEventsDeleted)
        assertEquals(1, counts.parentRunsDeleted)
        assertEquals(1, orphans)
        // Only the RUNNING row and the recent terminal run survive.
        assertEquals(2, runDao.getRecent("TEST_OP", limit = 10).size)
        assertNotNull(runDao.getById(runningId))
        assertNotNull(runDao.getById(recentId))
    }
}
