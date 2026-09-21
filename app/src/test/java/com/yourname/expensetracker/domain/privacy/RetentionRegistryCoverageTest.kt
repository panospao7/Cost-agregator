package com.yourname.expensetracker.domain.privacy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.privacy.DataRetentionWorker
import com.yourname.expensetracker.di.RetentionModule
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * RP-14 14c: registry coverage guard — the replacement for the tautological
 * `retention_registry_covers_all_sensitive_targets` assertions.
 *
 * Compares two INDEPENDENT sources of truth:
 *  1. [RetentionPolicyContract] — the policy descriptor owned by the privacy
 *     contract, deliberately OUTSIDE the Hilt module's construction list; and
 *  2. the REAL production registry built by [RetentionModule].
 *
 * Additionally asserts (via source inspection of DataRetentionWorker, the same
 * mechanism as `data_retention_worker_has_no_legacy_raw_purge_helpers`) that
 * every registered target has an EXPLICIT cutoff route — the `else` default is
 * never relied upon — and that the worker's named cutoff constants carry the
 * D14-approved values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RetentionRegistryCoverageTest {

    private lateinit var database: AppDatabase
    private val maintenanceMode = mockk<RestoreMaintenanceMode>()
    private val timeProvider: TimeProvider = object : TimeProvider {
        override fun now() = 0L
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

    /** The REAL production registry — same construction the Hilt module performs. */
    private fun realRegistryNames(): List<String> =
        RetentionModule.provideRetentionTargets(
            database,
            timeProvider,
            DatabaseWriteBarrier(maintenanceMode)
        ).map { it.name }

    private fun workerSource(): String =
        File("src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt")
            .readText()

    // ── coverage ──────────────────────────────────────────────────────────────

    @Test
    fun `every policy descriptor appears EXACTLY once in the real registry`() {
        val registered = realRegistryNames()

        for (required in RetentionPolicyContract.requiredTargets) {
            val count = registered.count { it == required }
            assertEquals(
                "Policy descriptor '$required' must be registered exactly once (found $count)",
                1,
                count
            )
        }
    }

    @Test
    fun `no policy descriptor is missing from the real registry`() {
        val registered = realRegistryNames().toSet()
        val missing = RetentionPolicyContract.requiredTargets - registered

        assertTrue(
            "Retention registry is missing policy descriptors: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `registry target names are unique`() {
        val registered = realRegistryNames()
        val duplicates = registered.groupingBy { it }.eachCount().filterValues { it > 1 }

        assertTrue(
            "Retention registry contains duplicate target names: $duplicates",
            duplicates.isEmpty()
        )
    }

    @Test
    fun `every registered target has an explicit cutoff route in the worker`() {
        val registered = realRegistryNames()
        val source = workerSource()

        val unrouted = registered.filter { !source.contains("\"$it\" ->") }

        assertTrue(
            "Targets without an explicit cutoffFor route (else-default fallback is forbidden): $unrouted",
            unrouted.isEmpty()
        )
    }

    // ── D14 cutoff constants ──────────────────────────────────────────────────

    @Test
    fun `worker named cutoff constants match the D14 policy descriptor`() {
        val expected = RetentionPolicyContract.requiredFixedCutoffDays

        assertEquals(
            expected["10_transaction_events.snapshots"],
            DataRetentionWorker.TRANSACTION_EVENT_SNAPSHOT_RETENTION_DAYS
        )
        assertEquals(
            expected["20_transaction_events.rows"],
            DataRetentionWorker.TRANSACTION_EVENT_ROW_RETENTION_DAYS
        )
        assertEquals(
            expected["30_operation_runs"],
            DataRetentionWorker.OPERATION_RUN_RETENTION_DAYS
        )
        assertEquals(
            expected["40_receipt_events"],
            DataRetentionWorker.RECEIPT_EVENT_RETENTION_DAYS
        )
        assertEquals(
            expected["50_privacy_audit_events"],
            DataRetentionWorker.PRIVACY_AUDIT_RETENTION_DAYS
        )
    }

    @Test
    fun `snapshot nulling sorts before row deletion by target name`() {
        // The numeric prefixes exist precisely because the worker sorts target
        // names lexicographically: snapshot nulling MUST run before row deletion.
        val names = realRegistryNames()
        val snapshotIdx = names.indexOf("10_transaction_events.snapshots")
        val rowsIdx = names.indexOf("20_transaction_events.rows")

        assertTrue(snapshotIdx in names.indices)
        assertTrue(rowsIdx in names.indices)
        assertTrue(
            "snapshot nulling target must sort before row deletion target",
            snapshotIdx < rowsIdx
        )
    }
}
