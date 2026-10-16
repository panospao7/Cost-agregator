package com.yourname.expensetracker.di

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.privacy.RetentionTarget
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R26: the diagnostics and privacy-audit retention targets purge in bounded
 * batches. Real in-memory Room DAOs are used so the batched
 * `DELETE ... WHERE id IN (SELECT ... LIMIT)` statements are exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RetentionModuleBatchedPurgeTest {

    private lateinit var database: AppDatabase

    private val cutoffMs = 1_000_000L
    private val batchSize = RetentionModule.RETENTION_PURGE_BATCH_SIZE

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun targets(barrier: DatabaseWriteBarrier): Map<String, RetentionTarget> =
        RetentionModule.provideRetentionTargets(
            appDatabase = database,
            timeProvider = FakeTimeProvider(2_000_000L),
            writeBarrier = barrier
        ).associateBy { it.name }

    private fun openBarrier(): DatabaseWriteBarrier = mockk(relaxed = true)

    /** Bulk-inserts rows in one transaction; raw SQL keeps 20k-row seeding fast. */
    private fun seed(table: String, tsColumn: String, extraColumns: String, extraValues: String, count: Int, ts: Long) {
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            val statement = db.compileStatement(
                "INSERT INTO $table ($extraColumns, $tsColumn) VALUES ($extraValues, ?)"
            )
            repeat(count) { i ->
                statement.clearBindings()
                statement.bindLong(1, ts - (i % 100))
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun seedAudit(count: Int, ts: Long) =
        seed("privacy_audit_events", "timestampMs", "capability, decision", "'LOCATION', 'ALLOWED'", count, ts)

    private fun seedDiagnostics(count: Int, ts: Long) = seed(
        "pipeline_diagnostic_events", "timestamp",
        "pipeline, stage, outcome, metadataSchemaVersion", "'P', 'S', 'O', 1", count, ts
    )

    private fun rowCount(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun rowCountOlderThan(table: String, tsColumn: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table WHERE $tsColumn < $cutoffMs").use {
                it.moveToFirst()
                it.getInt(0)
            }

    @Test
    fun `privacy audit purge deletes 20k old rows in multiple batches and keeps newer rows`() = runTest {
        seedAudit(count = 20_000, ts = cutoffMs - 1)
        seedAudit(count = 300, ts = cutoffMs + 1_000)
        var barrierChecks = 0
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        every { barrier.checkWritesAllowed(any<String>()) } answers { barrierChecks++ }

        val result = targets(barrier).getValue("50_privacy_audit_events").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals(20_000, result.rowsPurged)
        assertEquals(0, rowCountOlderThan("privacy_audit_events", "timestampMs"))
        assertEquals(300, rowCount("privacy_audit_events"))
        // 4 full batches + 1 terminating empty batch, each preceded by a barrier check.
        assertTrue("expected 4+ batches, barrier checked $barrierChecks times", barrierChecks >= 5)
    }

    @Test
    fun `pipeline diagnostics purge deletes 20k old rows in multiple batches and keeps newer rows`() = runTest {
        seedDiagnostics(count = 20_000, ts = cutoffMs - 1)
        seedDiagnostics(count = 300, ts = cutoffMs + 1_000)
        var barrierChecks = 0
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        every { barrier.checkWritesAllowed(any<String>()) } answers { barrierChecks++ }

        val result = targets(barrier).getValue("pipeline_diagnostic_events").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals(20_000, result.rowsPurged)
        assertEquals(0, rowCountOlderThan("pipeline_diagnostic_events", "timestamp"))
        assertEquals(300, rowCount("pipeline_diagnostic_events"))
        assertTrue("expected 4+ batches, barrier checked $barrierChecks times", barrierChecks >= 5)
    }

    @Test
    fun `purge with fewer rows than one batch needs a single batch`() = runTest {
        seedAudit(count = batchSize - 1, ts = cutoffMs - 1)
        var barrierChecks = 0
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        every { barrier.checkWritesAllowed(any<String>()) } answers { barrierChecks++ }

        val result = targets(barrier).getValue("50_privacy_audit_events").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals(batchSize - 1, result.rowsPurged)
        assertEquals(1, barrierChecks)
    }

    @Test
    fun `barrier closing mid-run returns the transient write barrier result and keeps remaining rows`() = runTest {
        seedAudit(count = 20_000, ts = cutoffMs - 1)
        var checks = 0
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        every { barrier.checkWritesAllowed(any<String>()) } answers {
            checks++
            if (checks > 2) {
                throw DatabaseAccessBlockedException(
                    DatabaseAccessType.WRITE,
                    DatabaseAccessOperation("test"),
                    RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
                )
            }
        }

        val result = targets(barrier).getValue("50_privacy_audit_events").purge(cutoffMs)

        assertFalse(result.success)
        assertTrue(result.isTransient)
        assertEquals("WRITE_BARRIER_BLOCKED", result.errorCode)
        assertEquals("WRITE_BARRIER_BLOCKED", result.errorMessage)
        // Two batches ran before the barrier closed; the rest stays for the next run.
        assertEquals(2 * batchSize, result.rowsPurged)
        assertEquals(20_000 - 2 * batchSize, rowCountOlderThan("privacy_audit_events", "timestampMs"))
    }

    @Test
    fun `diagnostics barrier closing mid-run returns the transient write barrier result`() = runTest {
        seedDiagnostics(count = 12_000, ts = cutoffMs - 1)
        var checks = 0
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        every { barrier.checkWritesAllowed(any<String>()) } answers {
            checks++
            if (checks > 1) {
                throw DatabaseAccessBlockedException(
                    DatabaseAccessType.WRITE,
                    DatabaseAccessOperation("test"),
                    RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
                )
            }
        }

        val result = targets(barrier).getValue("pipeline_diagnostic_events").purge(cutoffMs)

        assertFalse(result.success)
        assertTrue(result.isTransient)
        assertEquals("WRITE_BARRIER_BLOCKED", result.errorCode)
        assertEquals(batchSize, result.rowsPurged)
        assertEquals(12_000 - batchSize, rowCountOlderThan("pipeline_diagnostic_events", "timestamp"))
    }

    @Test
    fun `cancellation from the barrier propagates instead of becoming a transient result`() = runTest {
        seedAudit(count = 12_000, ts = cutoffMs - 1)
        val cancellation = CancellationException("cancelled mid-run")
        var checks = 0
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        every { barrier.checkWritesAllowed(any<String>()) } answers {
            checks++
            if (checks > 1) throw cancellation
        }

        try {
            targets(barrier).getValue("50_privacy_audit_events").purge(cutoffMs)
            fail("CancellationException must propagate")
        } catch (thrown: CancellationException) {
            assertSame(cancellation, thrown)
        }
    }

    @Test
    fun `coroutine cancelled between batches stops the purge and propagates cancellation`() = runTest {
        seedDiagnostics(count = 20_000, ts = cutoffMs - 1)
        var checks = 0
        var deferredRef: kotlinx.coroutines.Deferred<*>? = null
        val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        every { barrier.checkWritesAllowed(any<String>()) } answers {
            checks++
            // Cancel the purge while the second batch is about to run.
            if (checks == 2) deferredRef?.cancel()
        }

        val purge = targets(barrier).getValue("pipeline_diagnostic_events")
        val deferred = async(start = CoroutineStart.UNDISPATCHED) { purge.purge(cutoffMs) }
        deferredRef = deferred

        try {
            deferred.await()
            fail("cancelled purge must not complete normally")
        } catch (e: CancellationException) {
            assertNotNull(e)
        }
        assertTrue(deferred.isCancelled)
        assertTrue(
            "purge must stop early after cancellation",
            rowCountOlderThan("pipeline_diagnostic_events", "timestamp") > 0
        )
    }
}
