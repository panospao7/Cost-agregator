package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.PipelineDiagnosticEvent
import com.yourname.expensetracker.di.RetentionModule
import com.yourname.expensetracker.domain.privacy.RetentionTarget
import com.yourname.expensetracker.domain.util.TimeProvider
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

    @Before
    fun setUp() {
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
        RetentionModule.provideRetentionTargets(database, timeProvider)
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

        val recent = dao.getById(recentId)!!
        assertNotNull("recent row title must be preserved", recent.title)
        assertNotNull("recent row text must be preserved", recent.text)
        assertNull("recent row must not be marked purged", recent.rawPayloadPurgedAt)
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
}
