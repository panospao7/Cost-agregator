package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.PipelineDiagnosticEvent
import com.yourname.expensetracker.di.RetentionModule
import com.yourname.expensetracker.domain.privacy.RetentionTarget
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * P8F-01 / P8F-06: Row-level retention tests for the two PII-bearing targets
 * added to [RetentionModule] — `notification_intake` and `pipeline_diagnostic_events`.
 *
 * These exercise the REAL production [RetentionTarget] implementations (obtained
 * via [RetentionModule.provideRetentionTargets]) against an in-memory Room
 * [AppDatabase], mirroring the Robolectric + in-memory Room harness used by the
 * Golden scenario tests. They assert that rows older than the cutoff are
 * purged/nulled while recent rows are preserved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RetentionTargetPurgeTest {

    private lateinit var database: AppDatabase

    private val now = 1_700_000_000_000L
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = now }

    private val cutoffMs = now - TimeUnit.DAYS.toMillis(30)
    private val olderThanCutoff = cutoffMs - TimeUnit.DAYS.toMillis(1)

    // GR-14u51b: the retention targets now require the canonical write
    // barrier; NORMAL mode keeps the purge behavior unchanged.
    private val maintenanceMode = mockk<RestoreMaintenanceMode>()

    private fun writeBarrier(mode: RestoreMaintenanceMode.Mode): DatabaseWriteBarrier {
        val barrier = DatabaseWriteBarrier(maintenanceMode)
        every { maintenanceMode.currentMode() } returns mode
        every { maintenanceMode.isWritesAllowed() } returns
            (mode == RestoreMaintenanceMode.Mode.NORMAL)
        return barrier
    }

    @Before
    fun setUp() {
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun targetNamed(name: String): RetentionTarget =
        RetentionModule.provideRetentionTargets(database, timeProvider, writeBarrier(RestoreMaintenanceMode.Mode.NORMAL))
            .first { it.name == name }

    private fun intakeRow(fingerprint: String, capturedAt: Long) = NotificationIntakeEntity(
        packageName = "com.bank.app",
        appName = "Bank",
        notificationKeyHash = "keyhash-$fingerprint",
        postTime = capturedAt,
        capturedAt = capturedAt,
        source = "LISTENER",
        correlationId = "corr-$fingerprint",
        dedupeFingerprint = fingerprint,
        contentHash = "hash-$fingerprint",
        title = "Payment of \$50",
        text = "You spent \$50 at Store",
        bigText = "big body",
        subText = "sub",
        extrasJson = "{\"k\":\"v\"}",
        rawStorageMode = "STORE_RAW",
        payloadMode = "RAW",
        status = "DONE",
        createdAt = capturedAt,
        updatedAt = capturedAt
    )

    private fun diagnosticRow(timestamp: Long, message: String) = PipelineDiagnosticEvent(
        pipeline = "notification",
        stage = "parse",
        outcome = "DONE",
        message = message,
        timestamp = timestamp
    )

    @Test
    fun `notification_intake target nulls raw payload of old rows and keeps recent`() = runTest {
        val dao = database.notificationIntakeDao()
        val oldId = dao.insertOrIgnore(intakeRow("old-fp", olderThanCutoff))
        val recentId = dao.insertOrIgnore(intakeRow("recent-fp", now))

        // notificationCutoff is passed by DataRetentionWorker; here we pass it directly.
        val result = targetNamed("notification_intake").purge(cutoffMs)

        assertEquals(1, result.rowsPurged)
        assertTrue(result.success)

        val old = dao.getById(oldId)!!
        assertNull("old row title must be nulled", old.title)
        assertNull("old row text must be nulled", old.text)
        assertNull("old row bigText must be nulled", old.bigText)
        assertNull("old row subText must be nulled", old.subText)
        assertNull("old row extrasJson must be nulled", old.extrasJson)
        assertNotNull("old row must be marked purged", old.rawPayloadPurgedAt)
        assertNotNull("old row transient payload must be marked purged", old.transientPayloadPurgedAt)

        val recent = dao.getById(recentId)!!
        assertNotNull("recent row title must be preserved", recent.title)
        assertNotNull("recent row text must be preserved", recent.text)
        assertNull("recent row must not be marked purged", recent.rawPayloadPurgedAt)
    }

    // ── R28: encrypted transient payload retention ────────────────────────────

    /** Synthetic encrypted-transient intake row: no visible plaintext, placeholder ciphertext. */
    private fun transientIntakeRow(
        fingerprint: String,
        capturedAt: Long,
        status: String = "PROCESSED"
    ) = intakeRow(fingerprint, capturedAt).copy(
        title = null,
        text = null,
        bigText = null,
        subText = null,
        extrasJson = null,
        rawStorageMode = "STORE_REDACTED",
        payloadMode = "TRANSIENT",
        transientPayloadCiphertext = "cipher-$fingerprint",
        transientPayloadNonce = "nonce-$fingerprint",
        transientPayloadVersion = 1,
        status = status
    )

    /**
     * B6-10 table: old encrypted-transient rows past the cutoff lose their ciphertext.
     * Merges the former "old transient row", "leftover ciphertext on already raw-purged row"
     * (legacy backlog: old target only nulled plaintext and set rawPayloadPurgedAt) and
     * "regardless of non-terminal status" (age-based, status-agnostic: a RECEIVED row past the
     * cutoff must not keep its encrypted payload) tests. All rows are inserted together; the
     * per-row assertions are the union of the originals.
     */
    private data class CiphertextRow(
        val rowName: String,
        val row: NotificationIntakeEntity
    )

    @Test
    fun `notification_intake target clears ciphertext of old transient rows regardless of state`() = runTest {
        val dao = database.notificationIntakeDao()
        val table = listOf(
            CiphertextRow(
                "old transient row",
                transientIntakeRow("transient-fp", olderThanCutoff)
            ),
            CiphertextRow(
                "leftover ciphertext on already raw-purged row",
                transientIntakeRow("legacy-fp", olderThanCutoff).copy(rawPayloadPurgedAt = olderThanCutoff)
            ),
            CiphertextRow(
                "non-terminal RECEIVED status",
                transientIntakeRow("received-fp", olderThanCutoff, status = "RECEIVED")
            )
        )
        assertEquals("table size guard", 3, table.size)
        val ids = table.map { dao.insertOrIgnore(it.row) }

        val result = targetNamed("notification_intake").purge(cutoffMs)

        assertEquals("every old ciphertext row is purged", table.size, result.rowsPurged)
        assertTrue(result.success)
        table.forEachIndexed { index, case ->
            val row = dao.getById(ids[index])!!
            val label = "[${case.rowName}]"
            assertNull("$label ciphertext", row.transientPayloadCiphertext)
            assertNull("$label nonce", row.transientPayloadNonce)
            assertNull("$label version", row.transientPayloadVersion)
            assertNotNull("$label transientPayloadPurgedAt", row.transientPayloadPurgedAt)
            // B6 nit (45): asserted on every row (the legacy row had it pre-set, the others must gain it).
            assertNotNull("$label rawPayloadPurgedAt", row.rawPayloadPurgedAt)
        }
    }

    @Test
    fun `notification_intake target leaves recent transient row untouched`() = runTest {
        val dao = database.notificationIntakeDao()
        val id = dao.insertOrIgnore(transientIntakeRow("recent-transient-fp", now))

        val result = targetNamed("notification_intake").purge(cutoffMs)

        assertEquals(0, result.rowsPurged)
        val row = dao.getById(id)!!
        assertEquals("cipher-recent-transient-fp", row.transientPayloadCiphertext)
        assertEquals("nonce-recent-transient-fp", row.transientPayloadNonce)
        assertEquals(1, row.transientPayloadVersion)
        assertNull(row.rawPayloadPurgedAt)
        assertNull(row.transientPayloadPurgedAt)
    }

    @Test
    fun `notification_intake target does not reselect fully purged rows and is idempotent`() = runTest {
        val dao = database.notificationIntakeDao()
        val purgedUpdatedAt = olderThanCutoff + 1_000L
        val id = dao.insertOrIgnore(
            intakeRow("purged-fp", olderThanCutoff).copy(
                title = null,
                text = null,
                bigText = null,
                subText = null,
                extrasJson = null,
                rawPayloadPurgedAt = purgedUpdatedAt,
                transientPayloadPurgedAt = purgedUpdatedAt,
                updatedAt = purgedUpdatedAt
            )
        )
        val target = targetNamed("notification_intake")

        val first = target.purge(cutoffMs)
        assertEquals(0, first.rowsPurged)
        assertEquals(purgedUpdatedAt, dao.getById(id)!!.updatedAt)

        // A second sweep must also be a no-op (no reselection).
        val second = target.purge(cutoffMs)
        assertEquals(0, second.rowsPurged)
        assertTrue(second.success)
        assertEquals(purgedUpdatedAt, dao.getById(id)!!.updatedAt)
    }

    @Test
    fun `notification_intake target purges more than one batch of ciphertext rows`() = runTest {
        val dao = database.notificationIntakeDao()
        // 205 rows crosses the 100-row batch boundary (100 + 100 + 5).
        val ids = (1..205).map { i ->
            dao.insertOrIgnore(transientIntakeRow("bulk-$i", olderThanCutoff))
        }

        val result = targetNamed("notification_intake").purge(cutoffMs)

        assertEquals(205, result.rowsPurged)
        assertTrue(result.success)
        ids.forEach { id ->
            val row = dao.getById(id)!!
            assertNull(row.transientPayloadCiphertext)
            assertNull(row.transientPayloadNonce)
            assertNull(row.transientPayloadVersion)
            assertNotNull(row.transientPayloadPurgedAt)
        }
    }

    @Test
    fun `pipeline_diagnostic_events target deletes old rows and keeps recent`() = runTest {
        val dao = database.pipelineDiagnosticEventDao()
        dao.insert(diagnosticRow(olderThanCutoff, "old PII message"))
        dao.insert(diagnosticRow(now, "recent message"))

        val result = targetNamed("pipeline_diagnostic_events").purge(cutoffMs)

        assertEquals(1, result.rowsPurged)
        assertTrue(result.success)

        val remaining = dao.getRecent(50)
        assertEquals(1, remaining.size)
        assertEquals("recent message", remaining.first().message)
    }

    // ── GR-14u51b write-barrier gating ────────────────────────────────────────

    @Test
    fun `blocked mode returns the controlled constant and performs zero writes`() = runTest {
        // The write barrier must gate the purge entry BEFORE the
        // runCatchingCancellable wrapper (a check inside it would swallow
        // DatabaseAccessBlockedException into RETENTION_PURGE_FAILED) and
        // report the CONTROLLED CONSTANT "WRITE_BARRIER_BLOCKED" — never
        // e.message.
        val dao = database.pipelineDiagnosticEventDao()
        dao.insert(diagnosticRow(olderThanCutoff, "old PII message"))
        val before = dao.getRecent(50).size

        val blockedTargets = RetentionModule.provideRetentionTargets(
            database,
            timeProvider,
            writeBarrier(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        )
        val result = blockedTargets
            .first { it.name == "pipeline_diagnostic_events" }
            .purge(cutoffMs)

        assertEquals("WRITE_BARRIER_BLOCKED", result.errorMessage)
        assertEquals("WRITE_BARRIER_BLOCKED", result.errorCode)
        assertEquals(false, result.success)
        assertEquals(0, result.rowsPurged)
        // Zero DAO writes: the row is untouched.
        val after = dao.getRecent(50)
        assertEquals(before, after.size)
        assertEquals("old PII message", after.first().message)
    }

    @Test
    fun `cancellation from the barrier propagates`() = runTest {
        val cancellingMode = mockk<RestoreMaintenanceMode>()
        every { cancellingMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { cancellingMode.isWritesAllowed() } returns true
        val cancellingBarrier = mockk<DatabaseWriteBarrier>()
        io.mockk.coEvery {
            cancellingBarrier.checkWritesAllowed(any<String>())
        } throws CancellationException("cancelled")

        val targets = RetentionModule.provideRetentionTargets(
            database,
            timeProvider,
            cancellingBarrier
        )

        val thrown = runCatching {
            targets.first { it.name == "ai_artifacts" }.purge(cutoffMs)
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }
}
