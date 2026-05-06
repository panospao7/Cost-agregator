package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Unit tests for [BackgroundJobRunDao] covering insert, getRecent,
 * update, and getStaleRunningRuns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackgroundJobRunDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: BackgroundJobRunDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.backgroundJobRunDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createRun(
        workerName: String = "data_retention",
        startedAt: Long = FIXED_NOW,
        finishedAt: Long? = null,
        status: String = "RUNNING",
        rowsScanned: Int = 0,
        rowsUpdated: Int = 0,
        notificationsSent: Int = 0,
        retryReason: String? = null,
        errorMessage: String? = null
    ): BackgroundJobRun = BackgroundJobRun(
        workerName = workerName,
        startedAt = startedAt,
        finishedAt = finishedAt,
        status = status,
        rowsScanned = rowsScanned,
        rowsUpdated = rowsUpdated,
        notificationsSent = notificationsSent,
        retryReason = retryReason,
        errorMessage = errorMessage
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `insert a job run`() = runTest {
        val run = createRun()
        val id = dao.insert(run)

        assertTrue(id > 0)
    }

    @Test
    fun `getRecent returns job runs ordered by startedAt DESC`() = runTest {
        dao.insert(createRun(workerName = "retention", startedAt = FIXED_NOW))
        dao.insert(createRun(workerName = "retention", startedAt = FIXED_NOW + 2000))
        dao.insert(createRun(workerName = "retention", startedAt = FIXED_NOW + 1000))

        val results = dao.getRecent("retention", 10)

        assertEquals(3, results.size)
        // Most recent first
        assertEquals(FIXED_NOW + 2000, results[0].startedAt)
        assertEquals(FIXED_NOW + 1000, results[1].startedAt)
        assertEquals(FIXED_NOW, results[2].startedAt)
    }

    @Test
    fun `getRecent respects limit`() = runTest {
        dao.insert(createRun(workerName = "retention", startedAt = FIXED_NOW))
        dao.insert(createRun(workerName = "retention", startedAt = FIXED_NOW + 1000))
        dao.insert(createRun(workerName = "retention", startedAt = FIXED_NOW + 2000))

        val results = dao.getRecent("retention", 2)

        assertEquals(2, results.size)
    }

    @Test
    fun `getRecent filters by workerName`() = runTest {
        dao.insert(createRun(workerName = "retention"))
        dao.insert(createRun(workerName = "backfill"))
        dao.insert(createRun(workerName = "retention"))

        val retentionRuns = dao.getRecent("retention", 10)
        val backfillRuns = dao.getRecent("backfill", 10)

        assertEquals(2, retentionRuns.size)
        assertEquals(1, backfillRuns.size)
    }

    @Test
    fun `update job run sets finishedAt and status`() = runTest {
        val id = dao.insert(createRun(status = "RUNNING"))

        val loaded = dao.getRecent("data_retention", 10).first { it.id == id }
        val updated = loaded.copy(
            status = "SUCCESS",
            finishedAt = FIXED_NOW + 5000,
            rowsScanned = 100,
            rowsUpdated = 50
        )
        dao.update(updated)

        // Re-fetch to verify
        val results = dao.getRecent("data_retention", 10)
        val result = results.first { it.id == id }
        assertEquals("SUCCESS", result.status)
        assertEquals(FIXED_NOW + 5000, result.finishedAt)
        assertEquals(100, result.rowsScanned)
        assertEquals(50, result.rowsUpdated)
    }

    @Test
    fun `getStaleRunningRuns returns RUNNING runs older than threshold`() = runTest {
        // Insert a stale RUNNING run (started well before threshold)
        dao.insert(createRun(
            workerName = "retention",
            startedAt = FIXED_NOW - 100_000L,
            status = "RUNNING"
        ))
        // Insert a fresh RUNNING run (started after threshold)
        dao.insert(createRun(
            workerName = "retention",
            startedAt = FIXED_NOW,
            status = "RUNNING"
        ))
        // Insert a SUCCESS run (should not be returned regardless)
        dao.insert(createRun(
            workerName = "retention",
            startedAt = FIXED_NOW - 100_000L,
            status = "SUCCESS",
            finishedAt = FIXED_NOW - 50_000L
        ))

        val stale = dao.getStaleRunningRuns(FIXED_NOW)

        assertEquals(1, stale.size)
        assertEquals(FIXED_NOW - 100_000L, stale[0].startedAt)
    }

    @Test
    fun `getStaleRunningRuns returns empty when no stale runs`() = runTest {
        dao.insert(createRun(workerName = "retention", startedAt = FIXED_NOW, status = "RUNNING"))
        dao.insert(createRun(workerName = "retention", startedAt = FIXED_NOW + 1000, status = "SUCCESS"))

        val stale = dao.getStaleRunningRuns(FIXED_NOW)

        assertTrue(stale.isEmpty())
    }

    @Test
    fun `insert with full field set`() = runTest {
        val run = createRun(
            workerName = "location_backfill",
            startedAt = FIXED_NOW,
            finishedAt = FIXED_NOW + 3000,
            status = "SUCCESS",
            rowsScanned = 500,
            rowsUpdated = 120,
            notificationsSent = 5,
            retryReason = null,
            errorMessage = null
        )
        val id = dao.insert(run)

        assertTrue(id > 0)

        val results = dao.getRecent("location_backfill", 1)
        assertEquals(1, results.size)
        val loaded = results[0]
        assertEquals("location_backfill", loaded.workerName)
        assertEquals(FIXED_NOW, loaded.startedAt)
        assertEquals(FIXED_NOW + 3000, loaded.finishedAt)
        assertEquals("SUCCESS", loaded.status)
        assertEquals(500, loaded.rowsScanned)
        assertEquals(120, loaded.rowsUpdated)
        assertEquals(5, loaded.notificationsSent)
    }
}
