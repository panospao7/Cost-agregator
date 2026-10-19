package com.yourname.expensetracker.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.yourname.expensetracker.domain.workers.PendingWorkerTestFactory
import com.yourname.expensetracker.domain.workers.ScheduleResult
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistry
import com.yourname.expensetracker.domain.workers.WorkerRegistry
import com.yourname.expensetracker.domain.workers.WorkerSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Restore maintenance-mode persistence contracts (formerly part of P7BugFixesTest; B6-21):
 *
 * - NEW-P7-003: [RestoreMaintenanceMode.enterCriticalRecoveryRequired] writes
 *   mode + reason + timestamp in a single atomic commit.
 * - Persisted garbage / unreadable preferences / failed commits fail closed.
 * - Worker-resume pending barrier, cancellation and confirmation semantics.
 * - NEW-P7-004: [RestoreJournal.appendEvent] is synchronised so concurrent
 *   appends do not lose events (kept here: shares the Robolectric context and
 *   journal-file cleanup).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RestoreMaintenanceModePersistenceTest {

    // ── Shared infra ───────────────────────────────────────────────

    private lateinit var context: Context
    private val maintenanceContextId = AtomicInteger()

    /** Deterministic epoch-millis injected via FakeTimeProvider for all time-dependent code. */
    private val fixedTime = 1716163200000L // 2024-05-20 00:00 UTC

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setWorkerFactory(PendingWorkerTestFactory())
                .build()
        )
        File(context.noBackupFilesDir, CRITICAL_SENTINEL_FILE).delete()
        context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE).edit().clear().commit()
        // Clean up any left-over journal files from prior tests
        listOf(
            "restore_journal.json",
            RestoreJournal.FAILURE_JOURNAL_FILENAME,
            RestoreJournal.SUCCESS_JOURNAL_FILENAME
        ).forEach { File(context.filesDir, it).delete() }
    }

    /** Re-establishes the pristine per-test persisted state between table rows. */
    private fun resetPersistedMaintenanceState() {
        File(context.noBackupFilesDir, CRITICAL_SENTINEL_FILE).delete()
        context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE).edit().clear().commit()
        // B6 nit (39): a surviving sentinel / preference would make later table rows vacuous
        // (they would observe the previous row's state), so prove the reset really cleaned up.
        assertFalse(
            "reset must remove the critical sentinel file",
            File(context.noBackupFilesDir, CRITICAL_SENTINEL_FILE).exists()
        )
        assertTrue(
            "reset must leave restore_maintenance_mode preferences empty",
            context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE).all.isEmpty()
        )
    }

    private fun mockedMaintenanceContext(
        containsValue: (String) -> Boolean = { false },
        storedMode: () -> String? = { null },
        commitResult: () -> Boolean = { true }
    ): Context {
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.all } answers {
            buildMap<String, Any?> {
                if (containsValue("current_mode")) {
                    put("current_mode", storedMode())
                }
            }
        }
        every { preferences.contains(any()) } answers { containsValue(firstArg()) }
        every { preferences.getString(any(), any()) } answers {
            if (firstArg<String>() == "current_mode") storedMode() else null
        }
        every { preferences.getLong(any(), any()) } returns 0L
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } answers { commitResult() }
        val sentinelDirectory = tmp.newFolder("mock-maintenance-${maintenanceContextId.incrementAndGet()}")
        return object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "restore_maintenance_mode") preferences else super.getSharedPreferences(name, mode)

            override fun getNoBackupFilesDir(): File = sentinelDirectory
        }
    }

    private data class CommitBehavior(
        val result: Boolean = true,
        val persistWrites: Boolean = true,
        val throwable: RuntimeException? = null
    )

    private class StatefulPreferencesControl(
        initialValues: Map<String, Any?>
    ) {
        val memoryValues = initialValues.toMutableMap()
        val durableValues = initialValues.toMutableMap()
        val commitBehaviors = ArrayDeque<CommitBehavior>()
        var readFailure: RuntimeException? = null
        var onSnapshot: ((Map<String, Any?>) -> Unit)? = null
    }

    private data class StatefulPreferencesFixture(
        val context: Context,
        val control: StatefulPreferencesControl,
        val sentinelDirectory: File
    )

    /**
     * SharedPreferences fixture that stages editor writes and independently
     * models process-local memory and durable disk state. Failed commits expose
     * memory writes without changing the state seen by a reconstructed process.
     */
    private fun statefulMaintenanceContext(
        initialValues: Map<String, Any?> = emptyMap(),
        sentinelDirectory: File = tmp.newFolder("stateful-maintenance-${maintenanceContextId.incrementAndGet()}")
    ): StatefulPreferencesFixture {
        val control = StatefulPreferencesControl(initialValues)
        val preferences = mockk<SharedPreferences>()

        fun failReadIfRequested() {
            control.readFailure?.let { throw it }
        }

        every { preferences.all } answers {
            failReadIfRequested()
            val snapshot = control.memoryValues.toMap()
            control.onSnapshot?.invoke(snapshot)
            snapshot
        }
        every { preferences.contains(any()) } answers {
            failReadIfRequested()
            control.memoryValues.containsKey(firstArg())
        }
        every { preferences.getString(any(), any()) } answers {
            failReadIfRequested()
            val defaultValue = secondArg<String?>()
            when (val value = control.memoryValues[firstArg<String>()]) {
                null -> defaultValue
                is String -> value
                else -> throw ClassCastException("Not a String")
            }
        }
        every { preferences.getBoolean(any(), any()) } answers {
            failReadIfRequested()
            val defaultValue = secondArg<Boolean>()
            when (val value = control.memoryValues[firstArg<String>()]) {
                null -> defaultValue
                is Boolean -> value
                else -> throw ClassCastException("Not a Boolean")
            }
        }
        every { preferences.getLong(any(), any()) } answers {
            failReadIfRequested()
            val defaultValue = secondArg<Long>()
            when (val value = control.memoryValues[firstArg<String>()]) {
                null -> defaultValue
                is Long -> value
                else -> throw ClassCastException("Not a Long")
            }
        }
        every { preferences.edit() } answers {
            val editor = mockk<SharedPreferences.Editor>()
            val writes = linkedMapOf<String, Any?>()
            val removals = linkedSetOf<String>()

            every { editor.putString(any(), any()) } answers {
                val key = firstArg<String>()
                writes[key] = secondArg<String?>()
                removals.remove(key)
                editor
            }
            every { editor.putLong(any(), any()) } answers {
                val key = firstArg<String>()
                writes[key] = secondArg<Long>()
                removals.remove(key)
                editor
            }
            every { editor.putBoolean(any(), any()) } answers {
                val key = firstArg<String>()
                writes[key] = secondArg<Boolean>()
                removals.remove(key)
                editor
            }
            every { editor.remove(any()) } answers {
                val key = firstArg<String>()
                writes.remove(key)
                removals += key
                editor
            }
            every { editor.commit() } answers {
                val behavior = if (control.commitBehaviors.isEmpty()) {
                    CommitBehavior()
                } else {
                    control.commitBehaviors.removeFirst()
                }
                removals.forEach(control.memoryValues::remove)
                writes.forEach { (key, value) ->
                    if (value == null) control.memoryValues.remove(key) else control.memoryValues[key] = value
                }
                if (behavior.persistWrites) {
                    removals.forEach(control.durableValues::remove)
                    writes.forEach { (key, value) ->
                        if (value == null) control.durableValues.remove(key) else control.durableValues[key] = value
                    }
                }
                behavior.throwable?.let { throw it }
                behavior.result
            }
            editor
        }

        val wrappedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "restore_maintenance_mode") preferences else super.getSharedPreferences(name, mode)

            override fun getNoBackupFilesDir(): File = sentinelDirectory
        }
        return StatefulPreferencesFixture(wrappedContext, control, sentinelDirectory)
    }

    private fun recreateStatefulMaintenanceContext(
        fixture: StatefulPreferencesFixture
    ): StatefulPreferencesFixture = statefulMaintenanceContext(
        initialValues = fixture.control.durableValues.toMap(),
        sentinelDirectory = fixture.sentinelDirectory
    )

    private fun successfulScheduleSummary(): WorkerRegistry.ScheduleAllResult =
        WorkerRegistry.ScheduleAllResult(
            WorkerSpec.DEFAULTS.keys.map { name ->
                ScheduleResult(name, scheduled = true, policyUsed = "TEST", versionChanged = false)
            }
        )

    // ── NEW-P7-003: Atomic critical state transition ───────────────

    @Test
    fun `critical_state_transition_is_atomic`() {
        // P7-PR4 (NEW-P7-003): enterCriticalRecoveryRequired must write
        // mode, reason, AND timestamp in a single commit. A crash after
        // writing any subset must leave NO partial state.
        val reason = "RestoreMaintenanceModePersistenceTest simulated critical failure"
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        val mode = RestoreMaintenanceMode(context, timeProvider)
        mode.enterCriticalRecoveryRequired(reason)

        // Read the underlying SharedPreferences directly to verify all keys
        // were set in the same commit.
        val prefs = context.getSharedPreferences(
            "restore_maintenance_mode",
            Context.MODE_PRIVATE
        )

        // All three keys must be present and non-empty
        val savedMode = prefs.getString("current_mode", null)
        assertNotNull("current_mode must be written atomically", savedMode)
        assertEquals(
            "mode must be CRITICAL_RECOVERY_REQUIRED",
            "CRITICAL_RECOVERY_REQUIRED",
            savedMode
        )

        val savedReason = prefs.getString("critical_recovery_reason", null)
        assertNotNull("critical_recovery_reason must be written atomically", savedReason)
        assertEquals(reason, savedReason)

        val savedTimestamp = prefs.getLong("critical_recovery_timestamp", 0L)
        assertEquals(
            "critical_recovery_timestamp must be written atomically and equal the " +
                "injected TimeProvider time exactly",
            fixedTime,
            savedTimestamp
        )

        // The mode flow must also reflect the critical state
        assertEquals(
            RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
            mode.currentMode()
        )
    }

    private class PersistedGarbageRow(
        val name: String,
        val seed: (SharedPreferences.Editor) -> SharedPreferences.Editor
    )

    @Test
    fun `unknown blank false-pending wrong-type-pending and orphaned-critical persisted states fail closed across fresh instances`() {
        val prefs = context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        val rows = listOf(
            PersistedGarbageRow("unknown mode") { it.putString("current_mode", "NOT_A_MODE") },
            PersistedGarbageRow("blank mode") { it.putString("current_mode", "") },
            PersistedGarbageRow("worker_resume_pending false") {
                it.putString("current_mode", RestoreMaintenanceMode.Mode.NORMAL.name)
                    .putBoolean("worker_resume_pending", false)
            },
            PersistedGarbageRow("worker_resume_pending wrong type") {
                it.putString("current_mode", RestoreMaintenanceMode.Mode.NORMAL.name)
                    .putString("worker_resume_pending", "not-a-boolean")
            },
            PersistedGarbageRow("orphaned critical metadata without mode") {
                it.putString("critical_recovery_reason", "ORPHANED_CRITICAL")
                    .putLong("critical_recovery_timestamp", fixedTime)
            }
        )
        assertEquals("table must keep all five persisted-garbage rows", 5, rows.size)
        assertEquals("row names must be unique", rows.size, rows.map { it.name }.toSet().size)

        try {
            for (row in rows) {
                resetPersistedMaintenanceState()
                row.seed(prefs.edit()).commit()

                val mode = RestoreMaintenanceMode(context, timeProvider)
                assertEquals(
                    "[${row.name}] must fail closed",
                    RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
                    mode.currentMode()
                )
                assertTrue(
                    "[${row.name}] operational state must be CriticalRecoveryRequired",
                    mode.operationalStateFlow.value is AppOperationalState.CriticalRecoveryRequired
                )
                assertFalse("[${row.name}] writes must be blocked", mode.isWritesAllowed())
                assertThrows("[${row.name}] write barrier must block", DatabaseAccessBlockedException::class.java) {
                    DatabaseWriteBarrier(mode).checkWritesAllowed("persisted_garbage")
                }
                val fresh = RestoreMaintenanceMode(context, timeProvider)
                assertEquals(
                    "[${row.name}] a fresh instance must also fail closed",
                    RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
                    fresh.currentMode()
                )
            }
        } finally {
            prefs.edit().clear().commit()
            File(context.noBackupFilesDir, CRITICAL_SENTINEL_FILE).delete()
        }
    }

    @Test
    fun `critical state remains visible after fresh construction`() {
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        val first = RestoreMaintenanceMode(context, timeProvider)
        first.enterCriticalRecoveryRequired("TEST_CRITICAL")
        val second = RestoreMaintenanceMode(context, timeProvider)
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, second.currentMode())
        assertTrue(second.operationalStateFlow.value is AppOperationalState.CriticalRecoveryRequired)
        assertFalse(second.isWritesAllowed())
        context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
            .edit().putString("current_mode", RestoreMaintenanceMode.Mode.NORMAL.name).commit()
    }

    @Test
    fun absent_persisted_state_defaults_to_writable_NORMAL() {
        val mode = RestoreMaintenanceMode(
            context,
            com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        )

        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, mode.currentMode())
        assertTrue(mode.isWritesAllowed())
    }

    @Test
    fun present_null_mode_and_mode_read_exception_fail_closed() {
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        val presentNullContext = mockedMaintenanceContext(
            containsValue = { it == "current_mode" },
            storedMode = { null }
        )
        val presentNull = RestoreMaintenanceMode(presentNullContext, timeProvider)
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, presentNull.currentMode())
        assertTrue(presentNull.operationalStateFlow.value is AppOperationalState.CriticalRecoveryRequired)
        assertFalse(presentNull.isWritesAllowed())

        val readFailureContext = mockedMaintenanceContext(
            containsValue = { throw IllegalStateException("read failed") }
        )
        val readFailure = RestoreMaintenanceMode(readFailureContext, timeProvider)
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, readFailure.currentMode())
        assertTrue(readFailure.operationalStateFlow.value is AppOperationalState.CriticalRecoveryRequired)
        assertFalse(readFailure.isWritesAllowed())
    }

    @Test
    fun runtime_read_failure_latches_critical_flow_and_blocks_a_fresh_instance() {
        val fixture = statefulMaintenanceContext(
            mapOf("current_mode" to RestoreMaintenanceMode.Mode.NORMAL.name)
        )
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        val mode = RestoreMaintenanceMode(fixture.context, timeProvider)
        assertTrue(mode.isWritesAllowed())

        fixture.control.readFailure = IllegalStateException("runtime read failed")
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.currentMode())
        assertTrue(mode.operationalStateFlow.value is AppOperationalState.CriticalRecoveryRequired)
        assertFalse(mode.isWritesAllowed())

        // A later readable NORMAL value must not reopen this instance. The checked
        // critical fallback commit also makes a fresh instance fail closed.
        fixture.control.readFailure = null
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.currentMode())
        val fresh = RestoreMaintenanceMode(fixture.context, timeProvider)
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, fresh.currentMode())
        assertTrue(fresh.operationalStateFlow.value is AppOperationalState.CriticalRecoveryRequired)
        assertFalse(fresh.isWritesAllowed())
    }

    @Test
    fun failed_NORMAL_commits_with_successful_critical_fallback_block_fresh_instances() {
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        listOf(
            CommitBehavior(result = false, persistWrites = false),
            CommitBehavior(
                persistWrites = false,
                throwable = IllegalStateException("transition commit failed")
            )
        ).forEach { failedTransition ->
            val fixture = statefulMaintenanceContext(
                mapOf("current_mode" to RestoreMaintenanceMode.Mode.RESTORE_PREPARING.name)
            )
            fixture.control.commitBehaviors += failedTransition
            fixture.control.commitBehaviors += CommitBehavior()
            val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
            val mode = RestoreMaintenanceMode(
                fixture.context,
                dagger.Lazy { leaseRegistry },
                timeProvider
            )

            mockkObject(WorkerRegistry)
            try {
                every { WorkerRegistry.scheduleAll(any(), any(), null) } throws
                    AssertionError("Scheduling must not run after a failed NORMAL commit")
                assertThrows(RestoreMaintenanceMode.PersistenceException::class.java) {
                    mode.exit(forceRestartRequired = false)
                }
            } finally {
                unmockkObject(WorkerRegistry)
            }

            assertEquals(
                RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED.name,
                fixture.control.durableValues["current_mode"]
            )
            val fresh = RestoreMaintenanceMode(fixture.context, timeProvider)
            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, fresh.currentMode())
            assertFalse(fresh.isWritesAllowed())
            verify(exactly = 0) { leaseRegistry.resetStopFlag() }
        }
    }

    @Test
    fun failed_critical_preferences_commit_uses_durable_sentinel_across_reconstruction() {
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        listOf(
            CommitBehavior(result = false, persistWrites = false),
            CommitBehavior(
                persistWrites = false,
                throwable = IllegalStateException("critical commit failed")
            )
        ).forEach { failedCriticalCommit ->
            val fixture = statefulMaintenanceContext(
                mapOf("current_mode" to RestoreMaintenanceMode.Mode.NORMAL.name)
            )
            val mode = RestoreMaintenanceMode(fixture.context, timeProvider)
            assertTrue(mode.isWritesAllowed())

            fixture.control.commitBehaviors += failedCriticalCommit
            fixture.control.readFailure = IllegalStateException("runtime read failed")
            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.currentMode())
            fixture.control.readFailure = null

            assertEquals(
                "Failed preference commit must leave the durable preference value unchanged",
                RestoreMaintenanceMode.Mode.NORMAL.name,
                fixture.control.durableValues["current_mode"]
            )
            assertTrue(File(fixture.sentinelDirectory, CRITICAL_SENTINEL_FILE).exists())
            assertFalse(mode.isWritesAllowed())

            val reconstructed = recreateStatefulMaintenanceContext(fixture)
            val fresh = RestoreMaintenanceMode(reconstructed.context, timeProvider)
            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, fresh.currentMode())
            assertFalse(fresh.isWritesAllowed())
        }
    }

    @Test
    fun false_and_thrown_NORMAL_commits_never_schedule_or_reset_workers() {
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        listOf<() -> Boolean>({ false }, { throw IllegalStateException("commit failed") }).forEach { commitResult ->
            val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
            val mode = RestoreMaintenanceMode(
                mockedMaintenanceContext(commitResult = commitResult),
                dagger.Lazy { leaseRegistry },
                timeProvider
            )

            mockkObject(WorkerRegistry)
            try {
                every { WorkerRegistry.scheduleAll(any(), any(), null) } throws
                    AssertionError("Scheduling must not run after a failed NORMAL commit")
                assertThrows(RestoreMaintenanceMode.PersistenceException::class.java) {
                    mode.exit(forceRestartRequired = false)
                }
            } finally {
                unmockkObject(WorkerRegistry)
            }

            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.currentMode())
            assertTrue(mode.operationalStateFlow.value is AppOperationalState.CriticalRecoveryRequired)
            assertFalse(mode.isWritesAllowed())
            verify(exactly = 0) { leaseRegistry.resetStopFlag() }
        }
    }

    @Test
    fun worker_resume_pending_remains_blocked_across_fresh_instances() {
        val prefs = context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("current_mode", RestoreMaintenanceMode.Mode.NORMAL.name)
            .putBoolean("worker_resume_pending", true)
            .commit()
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)

        val first = RestoreMaintenanceMode(context, timeProvider)
        val second = RestoreMaintenanceMode(context, timeProvider)

        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, first.currentMode())
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, second.currentMode())
        assertFalse(first.isWritesAllowed())
        assertFalse(second.isWritesAllowed())
    }

    @Test
    fun coherent_snapshot_does_not_latch_critical_when_pending_clears_after_snapshot() {
        val fixture = statefulMaintenanceContext(
            mapOf(
                "current_mode" to RestoreMaintenanceMode.Mode.NORMAL.name,
                "worker_resume_pending" to true
            )
        )
        val mode = RestoreMaintenanceMode(
            fixture.context,
            com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        )
        val snapshotCaptured = CountDownLatch(1)
        val allowDecode = CountDownLatch(1)
        val interceptOnce = AtomicBoolean(true)
        fixture.control.onSnapshot = { snapshot ->
            if (interceptOnce.compareAndSet(true, false)) {
                assertEquals(true, snapshot["worker_resume_pending"])
                snapshotCaptured.countDown()
                assertTrue(allowDecode.await(5, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val readFuture = executor.submit<RestoreMaintenanceMode.Mode> { mode.currentMode() }
            assertTrue(snapshotCaptured.await(5, TimeUnit.SECONDS))

            // Deterministically model finalization committing the pending-key removal
            // immediately after the reader captured its snapshot. The reader must
            // decode the captured state, not combine pre- and post-commit values.
            fixture.control.memoryValues.remove("worker_resume_pending")
            fixture.control.durableValues.remove("worker_resume_pending")
            allowDecode.countDown()

            assertEquals(
                RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
                readFuture.get(5, TimeUnit.SECONDS)
            )
            assertFalse(File(fixture.sentinelDirectory, CRITICAL_SENTINEL_FILE).exists())
            assertFalse(mode.operationalStateFlow.value is AppOperationalState.CriticalRecoveryRequired)
        } finally {
            allowDecode.countDown()
            fixture.control.onSnapshot = null
            executor.shutdownNow()
        }
    }

    @Test
    fun fresh_instance_retries_durable_worker_resume_pending_before_becoming_writable() {
        val prefs = context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("current_mode", RestoreMaintenanceMode.Mode.NORMAL.name)
            .putBoolean("worker_resume_pending", true)
            .commit()
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        val resumed = RestoreMaintenanceMode(context, dagger.Lazy { leaseRegistry }, timeProvider)

        assertEquals(
            RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            resumed.currentMode()
        )
        assertFalse(resumed.isWritesAllowed())

        resumed.reset()

        verify(exactly = 1) { leaseRegistry.resetStopFlag() }
        assertFalse(prefs.contains("worker_resume_pending"))
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, resumed.currentMode())
        assertTrue(resumed.isWritesAllowed())
        val fresh = RestoreMaintenanceMode(context, timeProvider)
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, fresh.currentMode())
        assertTrue(fresh.isWritesAllowed())
    }

    @Test
    fun critical_mode_is_absorbing() {
        val mode = RestoreMaintenanceMode(
            context,
            com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        )
        mode.enterCriticalRecoveryRequired("TEST_CRITICAL")

        assertThrows(RestoreMaintenanceMode.PersistenceException::class.java) { mode.reset() }
        assertThrows(RestoreMaintenanceMode.PersistenceException::class.java) {
            mode.enter(RestoreMaintenanceMode.Mode.NORMAL)
        }
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.currentMode())
        assertFalse(mode.isWritesAllowed())
    }

    private class RescheduleFailureRow(
        val name: String,
        val scheduleSummary: WorkerRegistry.ScheduleAllResult,
        /** Null when the failure happens before any WorkManager confirmation is reached. */
        val confirmation: ListenableFuture<List<WorkInfo>>?
    )

    @Test
    fun worker_scheduling_or_confirmation_failures_retain_resume_pending_restart_required_before_NORMAL_is_published() {
        val unscheduledResults = WorkerSpec.DEFAULTS.keys.mapIndexed { index, name ->
            ScheduleResult(name, scheduled = index != 0, policyUsed = "TEST", versionChanged = false)
        }
        val missingWork = mockk<ListenableFuture<List<WorkInfo>>>()
        every { missingWork.get(any<Long>(), any<TimeUnit>()) } returns emptyList()
        val failedFuture = mockk<ListenableFuture<List<WorkInfo>>>()
        every { failedFuture.get(any<Long>(), any<TimeUnit>()) } throws
            IllegalStateException("confirmation failed")
        val rows = listOf(
            RescheduleFailureRow(
                name = "scheduling failure for the first worker",
                scheduleSummary = WorkerRegistry.ScheduleAllResult(unscheduledResults),
                confirmation = null
            ),
            RescheduleFailureRow(
                name = "successful registry summary without active work",
                scheduleSummary = successfulScheduleSummary(),
                confirmation = missingWork
            ),
            RescheduleFailureRow(
                name = "confirmation future failure",
                scheduleSummary = successfulScheduleSummary(),
                confirmation = failedFuture
            )
        )
        assertEquals("table must keep all three reschedule-failure rows", 3, rows.size)
        assertEquals("row names must be unique", rows.size, rows.map { it.name }.toSet().size)

        for (row in rows) {
            resetPersistedMaintenanceState()
            val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
            val mode = RestoreMaintenanceMode(
                context,
                dagger.Lazy { leaseRegistry },
                com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
            )
            mode.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
            val workManager = mockk<WorkManager>(relaxed = true)
            if (row.confirmation != null) {
                every { workManager.getWorkInfosForUniqueWork(any()) } returns row.confirmation
            }

            mockkObject(WorkerRegistry)
            if (row.confirmation != null) mockkStatic(WorkManager::class)
            try {
                every { WorkerRegistry.scheduleAll(any(), any(), null) } returns row.scheduleSummary
                if (row.confirmation != null) {
                    every { WorkManager.getInstance(any<Context>()) } returns workManager
                }

                assertThrows(
                    "[${row.name}] must raise WorkerRescheduleException",
                    RestoreMaintenanceMode.WorkerRescheduleException::class.java
                ) {
                    mode.exit(forceRestartRequired = false)
                }
            } finally {
                if (row.confirmation != null) unmockkStatic(WorkManager::class)
                unmockkObject(WorkerRegistry)
            }

            assertEquals(
                "[${row.name}] must retain restart-required",
                RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
                mode.currentMode()
            )
            assertEquals(
                "[${row.name}] operational state",
                AppOperationalState.RestartRequiredAfterRestore,
                mode.operationalStateFlow.value
            )
            assertFalse("[${row.name}] writes must stay blocked", mode.isWritesAllowed())
            val prefs = context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
            assertTrue(
                "[${row.name}] worker_resume_pending must be retained",
                prefs.getBoolean("worker_resume_pending", false)
            )
            verify(exactly = 0) { leaseRegistry.resetStopFlag() }
        }
    }

    @Test
    fun critical_transition_during_final_confirmation_cannot_be_overwritten_by_resume() {
        val fixture = statefulMaintenanceContext(
            mapOf("current_mode" to RestoreMaintenanceMode.Mode.RESTORE_PREPARING.name)
        )
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        val mode = RestoreMaintenanceMode(
            fixture.context,
            dagger.Lazy { leaseRegistry },
            timeProvider
        )
        val workManager = mockk<WorkManager>(relaxed = true)
        val activeWork = mockk<WorkInfo>()
        val confirmation = mockk<ListenableFuture<List<WorkInfo>>>()
        var confirmationCount = 0
        every { activeWork.state } returns WorkInfo.State.ENQUEUED
        every { confirmation.get(any<Long>(), any<TimeUnit>()) } answers {
            confirmationCount++
            if (confirmationCount == WorkerSpec.DEFAULTS.size) {
                fixture.control.readFailure = IllegalStateException("in-flight read failed")
                assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.currentMode())
                fixture.control.readFailure = null
            }
            listOf(activeWork)
        }
        every { workManager.getWorkInfosForUniqueWork(any()) } returns confirmation

        mockkObject(WorkerRegistry)
        mockkStatic(WorkManager::class)
        try {
            every { WorkerRegistry.scheduleAll(any(), any(), null) } returns successfulScheduleSummary()
            every { WorkManager.getInstance(any<Context>()) } returns workManager

            assertThrows(RestoreMaintenanceMode.PersistenceException::class.java) {
                mode.exit(forceRestartRequired = false)
            }
        } finally {
            unmockkStatic(WorkManager::class)
            unmockkObject(WorkerRegistry)
        }

        assertEquals(WorkerSpec.DEFAULTS.size, confirmationCount)
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.currentMode())
        assertFalse(mode.isWritesAllowed())
        val reconstructed = recreateStatefulMaintenanceContext(fixture)
        val fresh = RestoreMaintenanceMode(reconstructed.context, timeProvider)
        assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, fresh.currentMode())
        assertFalse(fresh.isWritesAllowed())
        verify(exactly = 0) { leaseRegistry.resetStopFlag() }
    }

    @Test
    fun confirmation_cancellation_preserves_original_exception_and_durable_pending_state() {
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val mode = RestoreMaintenanceMode(
            context,
            dagger.Lazy { leaseRegistry },
            com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        )
        mode.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        val workManager = mockk<WorkManager>(relaxed = true)
        val cancelledFuture = mockk<ListenableFuture<List<WorkInfo>>>()
        val cancellation = CancellationException("confirmation cancelled")
        every { cancelledFuture.get(any<Long>(), any<TimeUnit>()) } throws cancellation
        every { workManager.getWorkInfosForUniqueWork(any()) } returns cancelledFuture

        mockkObject(WorkerRegistry)
        mockkStatic(WorkManager::class)
        try {
            every { WorkerRegistry.scheduleAll(any(), any(), null) } returns successfulScheduleSummary()
            every { WorkManager.getInstance(any<Context>()) } returns workManager

            val thrown = assertThrows(CancellationException::class.java) {
                mode.exit(forceRestartRequired = false)
            }
            assertSame(cancellation, thrown)
        } finally {
            unmockkStatic(WorkManager::class)
            unmockkObject(WorkerRegistry)
        }

        val prefs = context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL.name, prefs.getString("current_mode", null))
        assertTrue(prefs.getBoolean("worker_resume_pending", false))
        assertFalse(mode.isWritesAllowed())
        val fresh = RestoreMaintenanceMode(
            context,
            com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        )
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, fresh.currentMode())
        assertFalse(fresh.isWritesAllowed())
        verify(exactly = 0) { leaseRegistry.resetStopFlag() }
    }

    @Test
    fun worker_scheduling_cancellation_preserves_durable_pending_barrier() {
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val mode = RestoreMaintenanceMode(
            context,
            dagger.Lazy { leaseRegistry },
            com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        )
        mode.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        val cancellation = CancellationException("test cancellation")
        var resetCalled = false
        every { leaseRegistry.resetStopFlag() } answers { resetCalled = true }

        mockkObject(WorkerRegistry)
        try {
            every { WorkerRegistry.scheduleAll(any(), any(), null) } answers {
                val prefs = context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
                assertEquals(RestoreMaintenanceMode.Mode.NORMAL.name, prefs.getString("current_mode", null))
                assertTrue(prefs.getBoolean("worker_resume_pending", false))
                assertFalse(mode.isWritesAllowed())
                assertFalse(resetCalled)
                val fresh = RestoreMaintenanceMode(
                    context,
                    com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
                )
                assertFalse(fresh.isWritesAllowed())
                throw cancellation
            }

            val thrown = assertThrows(CancellationException::class.java) {
                mode.exit(forceRestartRequired = false)
            }
            assertTrue(thrown === cancellation)
        } finally {
            unmockkObject(WorkerRegistry)
        }

        val prefs = context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL.name, prefs.getString("current_mode", null))
        assertTrue(prefs.getBoolean("worker_resume_pending", false))
        assertFalse(mode.isWritesAllowed())
        verify(exactly = 0) { leaseRegistry.resetStopFlag() }
    }

    @Test
    fun cancelling_actual_resume_job_while_confirmation_is_pending_keeps_barrier_closed() = runTest {
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val mode = RestoreMaintenanceMode(
            context,
            dagger.Lazy { leaseRegistry },
            com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        )
        mode.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        val workManager = mockk<WorkManager>(relaxed = true)
        val pendingConfirmation = SettableFuture.create<List<WorkInfo>>()
        every { workManager.getWorkInfosForUniqueWork(any()) } returns pendingConfirmation

        mockkObject(WorkerRegistry)
        mockkStatic(WorkManager::class)
        try {
            every { WorkerRegistry.scheduleAll(any(), any(), null) } returns
                successfulScheduleSummary()
            every { WorkManager.getInstance(any<Context>()) } returns workManager

            val resumeJob = launch { mode.exitCancellable(forceRestartRequired = false) }
            runCurrent()

            val prefs = context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
            assertTrue(prefs.getBoolean("worker_resume_pending", false))
            assertFalse(mode.isWritesAllowed())
            assertFalse(pendingConfirmation.isDone)

            resumeJob.cancelAndJoin()

            assertTrue(pendingConfirmation.isCancelled)
            assertTrue(prefs.getBoolean("worker_resume_pending", false))
            assertFalse(mode.isWritesAllowed())
            val fresh = RestoreMaintenanceMode(
                context,
                com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
            )
            assertEquals(
                RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
                fresh.currentMode()
            )
            assertFalse(fresh.isWritesAllowed())
            verify(exactly = 0) { leaseRegistry.resetStopFlag() }
        } finally {
            unmockkStatic(WorkManager::class)
            unmockkObject(WorkerRegistry)
        }
    }

    @Test
    fun successful_worker_confirmation_resets_stop_flag_before_publishing_NORMAL() {
        val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
        val mode = RestoreMaintenanceMode(
            context,
            dagger.Lazy { leaseRegistry },
            com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        )
        mode.enter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        val prefs = context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
        every { leaseRegistry.resetStopFlag() } answers {
            assertFalse("Pending must be durably cleared before the stop flag", prefs.contains("worker_resume_pending"))
            assertFalse("NORMAL must not be published before the stop flag reset completes", mode.isWritesAllowed())
        }

        mode.exit(forceRestartRequired = false)

        verify(exactly = 1) { leaseRegistry.resetStopFlag() }
        assertFalse(prefs.contains("worker_resume_pending"))
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, mode.currentMode())
        assertTrue(mode.isWritesAllowed())
    }

    @Test
    fun failed_pending_clear_keeps_stop_flag_set_and_latches_critical() {
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        listOf(
            CommitBehavior(result = false, persistWrites = false),
            CommitBehavior(
                persistWrites = false,
                throwable = IllegalStateException("pending removal failed")
            )
        ).forEach { failedPendingRemoval ->
            val fixture = statefulMaintenanceContext(
                mapOf("current_mode" to RestoreMaintenanceMode.Mode.RESTORE_PREPARING.name)
            )
            fixture.control.commitBehaviors += CommitBehavior()
            fixture.control.commitBehaviors += failedPendingRemoval
            fixture.control.commitBehaviors += CommitBehavior()
            val leaseRegistry = mockk<WorkerLeaseRegistry>(relaxed = true)
            val mode = RestoreMaintenanceMode(
                fixture.context,
                dagger.Lazy { leaseRegistry },
                timeProvider
            )

            assertThrows(RestoreMaintenanceMode.PersistenceException::class.java) {
                mode.exit(forceRestartRequired = false)
            }

            verify(exactly = 0) { leaseRegistry.resetStopFlag() }
            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.currentMode())
            assertTrue(mode.operationalStateFlow.value is AppOperationalState.CriticalRecoveryRequired)
            assertFalse(mode.isWritesAllowed())
            assertFalse(fixture.control.durableValues.containsKey("worker_resume_pending"))
            assertEquals(
                RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED.name,
                fixture.control.durableValues["current_mode"]
            )
            assertTrue(fixture.control.commitBehaviors.isEmpty())
        }
    }

    @Test
    fun sentinel_open_write_and_sync_failures_are_checked_on_every_retry() {
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        for (stage in RestoreMaintenanceMode.CriticalSentinelIoStage.values()) {
            for (failedCommit in listOf(
                CommitBehavior(result = false, persistWrites = false),
                CommitBehavior(persistWrites = false, throwable = IllegalStateException("TEST_COMMIT_FAILURE"))
            )) {
                val fixture = statefulMaintenanceContext(
                    mapOf("current_mode" to RestoreMaintenanceMode.Mode.RESTORE_PREPARING.name)
                )
                val leases = mockk<WorkerLeaseRegistry>(relaxed = true)
                val mode = RestoreMaintenanceMode(fixture.context, dagger.Lazy { leases }, timeProvider)
                val visited = mutableListOf<RestoreMaintenanceMode.CriticalSentinelIoStage>()
                mode.beforeCriticalSentinelIo = { current ->
                    visited += current
                    if (current == stage) throw java.io.IOException("TEST_SENTINEL_IO_FAILURE")
                }
                repeat(2) {
                    fixture.control.commitBehaviors += failedCommit
                    assertThrows(RestoreMaintenanceMode.PersistenceException::class.java) {
                        mode.enterCriticalRecoveryRequired("TEST_CRITICAL")
                    }
                    assertFalse(mode.isWritesAllowed())
                }
                assertEquals(2, visited.count { it == stage })
                val sentinel = File(fixture.sentinelDirectory, CRITICAL_SENTINEL_FILE)
                assertEquals(stage != RestoreMaintenanceMode.CriticalSentinelIoStage.OPEN, sentinel.exists())
                val reconstructed = recreateStatefulMaintenanceContext(fixture)
                assertFalse(RestoreMaintenanceMode(reconstructed.context, timeProvider).isWritesAllowed())

                // A successful retry must really write and sync the leftover file.
                visited.clear()
                mode.beforeCriticalSentinelIo = { visited += it }
                fixture.control.commitBehaviors += failedCommit
                assertThrows(RestoreMaintenanceMode.PersistenceException::class.java) {
                    mode.enterCriticalRecoveryRequired("TEST_CRITICAL")
                }
                assertEquals(RestoreMaintenanceMode.CriticalSentinelIoStage.values().toList(), visited)
                org.junit.Assert.assertArrayEquals(byteArrayOf(1), sentinel.readBytes())
                val fresh = recreateStatefulMaintenanceContext(fixture)
                assertEquals(
                    RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED,
                    RestoreMaintenanceMode(fresh.context, timeProvider).currentMode()
                )
                verify(exactly = 0) { leases.resetStopFlag() }
            }
        }
    }

    @Test
    fun checked_critical_preferences_remain_durable_when_the_sentinel_store_fails() {
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        for (stage in RestoreMaintenanceMode.CriticalSentinelIoStage.values()) {
            val fixture = statefulMaintenanceContext()
            val mode = RestoreMaintenanceMode(fixture.context, timeProvider)
            mode.beforeCriticalSentinelIo = {
                if (it == stage) throw java.io.IOException("TEST_SENTINEL_IO_FAILURE")
            }
            mode.enterCriticalRecoveryRequired("TEST_CRITICAL")
            val reconstructed = recreateStatefulMaintenanceContext(fixture)
            val fresh = RestoreMaintenanceMode(reconstructed.context, timeProvider)
            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, fresh.currentMode())
            assertFalse(fresh.isWritesAllowed())
        }
    }

    @Test
    fun critical_persistence_cancellation_preserves_identity_and_in_memory_lock() {
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        for (stage in RestoreMaintenanceMode.CriticalSentinelIoStage.values()) {
            val fixture = statefulMaintenanceContext()
            val mode = RestoreMaintenanceMode(fixture.context, timeProvider)
            val cancellation = CancellationException("TEST_CANCELLED")
            mode.beforeCriticalSentinelIo = { if (it == stage) throw cancellation }
            assertSame(cancellation, assertThrows(CancellationException::class.java) {
                mode.enterCriticalRecoveryRequired("TEST_CRITICAL")
            })
            assertFalse(mode.isWritesAllowed())
            assertTrue(mode.operationalStateFlow.value is AppOperationalState.CriticalRecoveryRequired)
        }
        val fixture = statefulMaintenanceContext()
        val mode = RestoreMaintenanceMode(fixture.context, timeProvider)
        val cancellation = CancellationException("TEST_CANCELLED")
        fixture.control.commitBehaviors += CommitBehavior(persistWrites = false, throwable = cancellation)
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            mode.enterCriticalRecoveryRequired("TEST_CRITICAL")
        })
        assertFalse(mode.isWritesAllowed())
        val fresh = recreateStatefulMaintenanceContext(fixture)
        assertFalse(RestoreMaintenanceMode(fresh.context, timeProvider).isWritesAllowed())
    }

    @Test
    fun unavailable_preferences_and_wrong_type_modes_fail_closed_in_the_real_owner() {
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        val sentinelDirectory = tmp.newFolder("unavailable-preferences")
        val unavailable = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw IllegalStateException("TEST_PREFERENCES_UNAVAILABLE")
            override fun getNoBackupFilesDir(): File = sentinelDirectory
        }
        repeat(2) {
            val mode = RestoreMaintenanceMode(unavailable, timeProvider)
            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.currentMode())
            assertThrows(DatabaseAccessBlockedException::class.java) {
                DatabaseWriteBarrier(mode).checkWritesAllowed("TEST_PREFERENCES_UNAVAILABLE")
            }
        }
        for (value in listOf<Any>(17, true, setOf("NORMAL"))) {
            val fixture = statefulMaintenanceContext(mapOf("current_mode" to value))
            val mode = RestoreMaintenanceMode(fixture.context, timeProvider)
            assertEquals(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED, mode.currentMode())
            assertFalse(mode.isWritesAllowed())
            val reconstructed = recreateStatefulMaintenanceContext(fixture)
            assertFalse(RestoreMaintenanceMode(reconstructed.context, timeProvider).isWritesAllowed())
        }
    }

    @Test
    fun every_persisted_non_normal_mode_blocks_the_real_barrier() {
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        for (persisted in RestoreMaintenanceMode.Mode.values()) {
            val fixture = statefulMaintenanceContext(mapOf("current_mode" to persisted.name))
            val mode = RestoreMaintenanceMode(fixture.context, timeProvider)
            val barrier = DatabaseWriteBarrier(mode)
            if (persisted == RestoreMaintenanceMode.Mode.NORMAL) {
                barrier.checkWritesAllowed("TEST_MODE_ADMISSION")
                assertTrue(mode.isWritesAllowed())
            } else {
                assertThrows(DatabaseAccessBlockedException::class.java) {
                    barrier.checkWritesAllowed("TEST_MODE_ADMISSION")
                }
                assertFalse(mode.isWritesAllowed())
            }
        }
    }

    @Test
    fun fresh_owner_cancellation_after_final_observation_invalidates_resume() {
        val fixture = statefulMaintenanceContext(
            mapOf("current_mode" to RestoreMaintenanceMode.Mode.RESTORE_PREPARING.name)
        )
        val timeProvider = com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime)
        val leases = mockk<WorkerLeaseRegistry>(relaxed = true)
        val mode = RestoreMaintenanceMode(fixture.context, dagger.Lazy { leases }, timeProvider)
        val workManager = mockk<WorkManager>(relaxed = true)
        val activeWork = mockk<WorkInfo>()
        val confirmation = mockk<ListenableFuture<List<WorkInfo>>>()
        var observations = 0
        every { activeWork.state } answers {
            observations++
            if (observations == WorkerSpec.DEFAULTS.size) {
                val fresh = RestoreMaintenanceMode(fixture.context, timeProvider)
                assertFalse(fresh.isWritesAllowed())
            }
            WorkInfo.State.ENQUEUED
        }
        every { confirmation.get(any<Long>(), any<TimeUnit>()) } returns listOf(activeWork)
        every { workManager.getWorkInfosForUniqueWork(any()) } returns confirmation
        mockkObject(WorkerRegistry)
        mockkStatic(WorkManager::class)
        try {
            every { WorkerRegistry.scheduleAll(any(), any(), null) } returns successfulScheduleSummary()
            every { WorkManager.getInstance(any<Context>()) } returns workManager
            assertThrows(RestoreMaintenanceMode.PersistenceException::class.java) { mode.exit(false) }
            assertEquals(WorkerSpec.DEFAULTS.size, observations)
            verify(exactly = 0) { leases.resetStopFlag() }
            WorkerSpec.DEFAULTS.keys.forEach { name ->
                verify(atLeast = 1) { workManager.cancelUniqueWork(name) }
            }
            assertFalse(mode.isWritesAllowed())
            val fresh = recreateStatefulMaintenanceContext(fixture)
            assertFalse(RestoreMaintenanceMode(fresh.context, timeProvider).isWritesAllowed())
        } finally {
            unmockkStatic(WorkManager::class)
            unmockkObject(WorkerRegistry)
        }
    }

    // ── NEW-P7-004: Thread-safe appendEvent ────────────────────────

    @Test
    fun `restore_journal_append_is_thread_safe`() {
        // P7-PR4 (NEW-P7-004): appendEventToFile is synchronised on
        // journalLock so concurrent calls do not interleave their
        // read-modify-write sequences and lose events.

        val journal = RestoreJournal(context, com.yourname.expensetracker.domain.util.FakeTimeProvider(fixedTime))
        val entry = journal.beginJournal(
            sourceBackupPath = "/tmp/src.costbackup",
            stagedDbPath = "/tmp/staged.db",
            liveDbPath = "/tmp/live.db"
        )

        val threadCount = 8
        val eventsPerThread = 25
        val totalEvents = threadCount * eventsPerThread

        val latch = CountDownLatch(threadCount)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) { t ->
            executor.submit {
                try {
                    repeat(eventsPerThread) { e ->
                        journal.appendEvent(
                            correlationId = entry.operationCorrelationId,
                            stage = "CONCURRENT_TEST",
                            outcome = "OK",
                            severity = "INFO",
                            reasonCode = "thread=${t}_event=${e}"
                        )
                    }
                } catch (ex: Exception) {
                    errors.add(ex)
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait for all threads to finish
        // B6 nit (41): bounded wait; a hung appender must fail the test instead of hanging the suite.
        val finished = latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("all $threadCount appender threads must finish within 30s", finished)

        // Verify no exceptions during concurrent appends
        assertTrue(
            "No exceptions should occur during concurrent appendEvent: ${errors.toList()}",
            errors.isEmpty()
        )

        // Read back all events and verify every single one was persisted
        val persisted = journal.getEventsByCorrelationId(entry.operationCorrelationId)
        val stageEvents = persisted.filter { it.stage == "CONCURRENT_TEST" }

        assertEquals(
            "All $totalEvents concurrently-appended events must be persisted; " +
                "found ${stageEvents.size}",
            totalEvents,
            stageEvents.size
        )

        // Verify each event has a unique reasonCode (thread_N_event_M)
        val reasonCodes = stageEvents.mapNotNull { it.reasonCode }.toSet()
        assertEquals(
            "Every concurrently-appended event must have a unique reasonCode; " +
                "expected $totalEvents unique codes, got ${reasonCodes.size}",
            totalEvents,
            reasonCodes.size
        )
    }

    private companion object {
        const val CRITICAL_SENTINEL_FILE = "restore_maintenance_critical"
    }
}
