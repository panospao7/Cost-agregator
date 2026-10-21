package com.yourname.expensetracker.domain.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.yourname.expensetracker.data.location.MerchantKeyBackfillWorker
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Robolectric-style contract tests for [WorkerSpecScheduler.scheduleAtMidnight]
 * and [WorkerSpecScheduler.scheduleFromSpec].
 *
 * Covers P9-P1-10 / NEW-11: the midnight scheduler must cancel any existing
 * scheduled work when its spec is disabled (parity with [WorkerSpecScheduler.scheduleFromSpec])
 * and must enqueue using the spec's [WorkerSpec.oneShotPolicy] when enabled.
 *
 * PR7 additions: version comparison with `!=`, [ScheduleResult] return values,
 * and failure diagnostics.
 *
 * [WorkManager] is statically mocked (rather than using the WorkManager test driver)
 * because the scheduler reads specs exclusively from [WorkerSpec.DEFAULTS] — none of
 * which are disabled — so [WorkerSpec.Companion] is also stubbed to inject a disabled
 * spec. Mocking lets us assert the exact cancel/enqueue calls directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkerSpecSchedulerTest {

    private lateinit var context: Context
    private val workManager: WorkManager = mockk(relaxed = true)

    // A concrete worker class is only needed as a type token for the request
    // builder; the worker itself is never instantiated here.
    private val workerClass: Class<out ListenableWorker> = MerchantKeyBackfillWorker::class.java

    // Fixed "now" (2024-05-20T00:00:00Z) so next-midnight delay assertions are
    // deterministic and never depend on the wall clock (G-TIME-01).
    private val timeProvider: FakeTimeProvider = FakeTimeProvider(1716163200000L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        mockkObject(WorkerSpec.Companion)
        // Deterministic clean slate for the persisted version prefs.
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Existing tests (P9-P1-10 / NEW-11) ─────────────────────────────────

    @Test
    fun `scheduleAtMidnight cancels unique work when spec disabled`() {
        val name = "disabled_midnight_worker"
        every { WorkerSpec.DEFAULTS } returns mapOf(
            name to WorkerSpec(
                name = name,
                enabled = false,
                repeatIntervalHours = null
            )
        )

        val result = WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider)

        // Parity with scheduleFromSpec: disabled → cancel existing, never enqueue.
        verify(exactly = 1) { workManager.cancelUniqueWork(name) }
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>())
        }
        assertFalse("Disabled worker should return scheduled=false", result.scheduled)
        assertEquals("Disabled worker should have error", "Worker '$name' is disabled", result.error)
    }

    @Test
    fun `enabled midnight worker enqueues with oneShotPolicy`() {
        val name = "enabled_midnight_worker"
        val spec = WorkerSpec(
            name = name,
            version = 1,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.KEEP
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)
        // Pre-seed the persisted version so the version-bump path does NOT fire;
        // this isolates the policy to spec.oneShotPolicy (KEEP) rather than the
        // REPLACE that a version change would force.
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().putInt(name, spec.version).commit()

        val policySlot = slot<ExistingWorkPolicy>()
        every {
            workManager.enqueueUniqueWork(eq(name), capture(policySlot), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        val result = WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(eq(name), any(), any<OneTimeWorkRequest>())
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
        assertEquals(
            "Enabled midnight worker should enqueue with the spec's oneShotPolicy",
            ExistingWorkPolicy.KEEP, policySlot.captured
        )
        assertTrue("Enabled worker should return scheduled=true", result.scheduled)
        assertFalse("Same version should not flag versionChanged", result.versionChanged)
    }

    @Test
    fun `scheduleAtMidnight version bump forces REPLACE over KEEP oneShotPolicy`() {
        val name = "version_bump_midnight_worker"
        val spec = WorkerSpec(
            name = name,
            version = 2,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.KEEP
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)
        // Opposite of the enabled test: seed a LOWER persisted version so the
        // version-bump path DOES fire. A bump (1 → 2) must force REPLACE even
        // though the spec's oneShotPolicy is KEEP — this is the planner's #1
        // precedence rule (version bump always wins).
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().putInt(name, 1).commit()

        val policySlot = slot<ExistingWorkPolicy>()
        every {
            workManager.enqueueUniqueWork(eq(name), capture(policySlot), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        val result = WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(eq(name), any(), any<OneTimeWorkRequest>())
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
        assertEquals(
            "A version bump must force REPLACE even when oneShotPolicy is KEEP",
            ExistingWorkPolicy.REPLACE, policySlot.captured
        )
        assertTrue("Version bump should flag versionChanged", result.versionChanged)
    }

    // ── R21 / HZ-05: startup scheduling must not replace in-flight one-shot work ──

    @Test
    fun `two startup schedules with unchanged version both enqueue with KEEP`() {
        val name = "startup_keep_midnight_worker"
        val spec = WorkerSpec(
            name = name,
            version = 1,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.KEEP
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)

        val policies = mutableListOf<ExistingWorkPolicy>()
        every {
            workManager.enqueueUniqueWork(eq(name), capture(policies), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        // First cold start: no stored version yet, so the version-bump REPLACE is
        // legitimate. It persists the version after the enqueue.
        WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider)
        // Second and third cold starts: version unchanged, must use KEEP so the
        // queued unique work (and its work id) is preserved.
        val second = WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider)
        val third = WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider)

        assertEquals(3, policies.size)
        assertEquals(ExistingWorkPolicy.KEEP, policies[1])
        assertEquals(ExistingWorkPolicy.KEEP, policies[2])
        assertFalse(second.versionChanged)
        assertFalse(third.versionChanged)
        assertEquals(ExistingWorkPolicy.KEEP.name, second.policyUsed)
    }

    @Test
    fun `ai_daily_briefing startup schedule uses KEEP when version unchanged and REPLACE on version change`() {
        val name = "ai_daily_briefing"
        val spec = WorkerSpec.DEFAULTS.getValue(name)
        val prefs = context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)

        val policySlot = slot<ExistingWorkPolicy>()
        every {
            workManager.enqueueUniqueWork(eq(name), capture(policySlot), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        prefs.edit().putInt(name, spec.version).commit()
        WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider)
        assertEquals(ExistingWorkPolicy.KEEP, policySlot.captured)

        prefs.edit().putInt(name, spec.version - 1).commit()
        val bumped = WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider)
        assertEquals(ExistingWorkPolicy.REPLACE, policySlot.captured)
        assertTrue(bumped.versionChanged)
    }

    @Test
    fun `scheduleAtMidnight policyOverride wins over spec policy and version bump`() {
        val name = "override_midnight_worker"
        val spec = WorkerSpec(
            name = name,
            version = 2,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.KEEP
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)
        val prefs = context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)

        val policySlot = slot<ExistingWorkPolicy>()
        every {
            workManager.enqueueUniqueWork(eq(name), capture(policySlot), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        // Unchanged version: override beats the spec's KEEP.
        prefs.edit().putInt(name, spec.version).commit()
        val unchanged = WorkerSpecScheduler.scheduleAtMidnight(
            context, name, workerClass, timeProvider,
            policyOverride = ExistingWorkPolicy.APPEND_OR_REPLACE
        )
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, policySlot.captured)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE.name, unchanged.policyUsed)

        // Version bump: override still beats the forced REPLACE.
        prefs.edit().putInt(name, 1).commit()
        WorkerSpecScheduler.scheduleAtMidnight(
            context, name, workerClass, timeProvider,
            policyOverride = ExistingWorkPolicy.APPEND_OR_REPLACE
        )
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, policySlot.captured)
    }

    @Test
    fun `scheduleFromSpec merchant_key one-shot enqueues with REPLACE oneShotPolicy`() {
        // Exercises the REAL merchant_key_backfill path, which schedules via
        // scheduleFromSpec (not scheduleAtMidnight) with oneShotPolicy=REPLACE.
        val name = "merchant_key_backfill"
        val spec = WorkerSpec(
            name = name,
            version = 1,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.REPLACE
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)
        // Pre-seed the persisted version so the version-bump path does NOT fire;
        // this isolates the policy to spec.oneShotPolicy (REPLACE) — the actual
        // merchant_key configuration — rather than a bump-forced REPLACE.
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().putInt(name, spec.version).commit()

        val policySlot = slot<ExistingWorkPolicy>()
        every {
            workManager.enqueueUniqueWork(eq(name), capture(policySlot), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        val result = WorkerSpecScheduler.scheduleFromSpec(context, name, workerClass)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(eq(name), any(), any<OneTimeWorkRequest>())
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
        assertEquals(
            "merchant_key one-shot must enqueue with its REPLACE oneShotPolicy",
            ExistingWorkPolicy.REPLACE, policySlot.captured
        )
        assertTrue("merchant_key scheduling should succeed", result.scheduled)
    }

    // ── PR7: Version comparison with != ───────────────────────────────────

    @Test
    fun `version_bump_replaces_when_version_differs`() {
        // PR7: Verify that != triggers replacement even when stored version is higher
        // than spec version (corrupted prefs scenario).
        val name = "version_differs_worker"
        val spec = WorkerSpec(
            name = name,
            version = 2,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.KEEP
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)
        // Seed a HIGHER persisted version (3 > 2). With !=, this must still
        // trigger the version-changed path and force REPLACE.
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().putInt(name, 3).commit()

        val policySlot = slot<ExistingWorkPolicy>()
        every {
            workManager.enqueueUniqueWork(eq(name), capture(policySlot), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        val result = WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(eq(name), any(), any<OneTimeWorkRequest>())
        }
        assertEquals(
            "Stored version 3 != spec version 2 must force REPLACE",
            ExistingWorkPolicy.REPLACE, policySlot.captured
        )
        assertTrue("Differing versions should flag versionChanged", result.versionChanged)
    }

    @Test
    fun `corrupted_high_version_pref_forces_reschedule`() {
        // PR7: Stored version 999 vs spec version 2 — the != guard should force
        // a reschedule with REPLACE, fixing the corrupted pref state.
        val name = "corrupted_prefs_worker"
        val spec = WorkerSpec(
            name = name,
            version = 2,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.KEEP
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)
        // Simulate a corrupted stored version far above the spec version.
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().putInt(name, 999).commit()

        val policySlot = slot<ExistingWorkPolicy>()
        every {
            workManager.enqueueUniqueWork(eq(name), capture(policySlot), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        val result = WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(eq(name), any(), any<OneTimeWorkRequest>())
        }
        assertEquals(
            "Corrupted stored version 999 vs spec 2 must force REPLACE with != guard",
            ExistingWorkPolicy.REPLACE, policySlot.captured
        )
        assertTrue("Corrupted prefs should flag versionChanged", result.versionChanged)
        assertTrue("Scheduling should succeed after version-bump forced reschedule", result.scheduled)
    }

    // ── PR7: Failure returns ScheduleResult with error ────────────────────

    @Test
    fun `schedule_failure_returns_result_with_error`() {
        // PR7: When WorkManager throws, the scheduler must return a ScheduleResult
        // with scheduled=false and a non-null error message.
        val name = "failing_worker"
        val spec = WorkerSpec(
            name = name,
            version = 1,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.REPLACE
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)
        // Pre-seed version to match so no version-bump path fires.
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().putInt(name, spec.version).commit()

        val simulatedError = "WorkManager is not initialized"
        every {
            workManager.enqueueUniqueWork(eq(name), any(), any<OneTimeWorkRequest>())
        } throws RuntimeException(simulatedError)

        val result = WorkerSpecScheduler.scheduleFromSpec(context, name, workerClass)

        assertFalse("WorkManager failure should return scheduled=false", result.scheduled)
        assertNotNull("Error message must be set on failure", result.error)
        // DIAG-10: only the exception class name is surfaced, never the message text.
        assertEquals(
            "Error must be the exception class name only",
            "RuntimeException", result.error
        )
        assertFalse(result.error!!.contains(simulatedError))
    }

    @Test
    fun `scheduleFromSpec failure keeps exception message out of result diagnostics and logs`() {
        val name = "failing_worker_privacy"
        val spec = WorkerSpec(
            name = name,
            version = 1,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.REPLACE
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().putInt(name, spec.version).commit()
        val secret = "secret-123"
        every {
            workManager.enqueueUniqueWork(eq(name), any(), any<OneTimeWorkRequest>())
        } throws IllegalStateException("merchant Lidl $secret")
        val events = java.util.concurrent.CopyOnWriteArrayList<DiagnosticEvent>()
        val latch = java.util.concurrent.CountDownLatch(1)
        val writer = object : DiagnosticEventWriter {
            override suspend fun emit(event: DiagnosticEvent) {
                events.add(event)
                latch.countDown()
            }
        }
        ShadowLog.clear()

        val result = WorkerSpecScheduler.scheduleFromSpec(context, name, workerClass, null, writer)

        assertEquals("IllegalStateException", result.error)
        assertTrue("diagnostic event must be emitted", latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val stored = events.single()
        assertEquals("IllegalStateException", JSONObject(stored.metadata.toJson()).getString("error"))
        assertFalse(stored.metadata.toJson().contains(secret))
        assertFalse(result.toString().contains(secret))
        ShadowLog.getLogs().forEach { item ->
            assertFalse("log must not contain message text", item.msg.contains(secret))
            assertTrue("log must not attach the throwable", item.throwable == null)
        }
    }

    @Test
    fun `scheduleAtMidnight failure keeps exception message out of result diagnostics and logs`() {
        val name = "failing_midnight_worker_privacy"
        val spec = WorkerSpec(
            name = name,
            version = 1,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.REPLACE
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().putInt(name, spec.version).commit()
        val secret = "secret-123"
        every {
            workManager.enqueueUniqueWork(eq(name), any(), any<OneTimeWorkRequest>())
        } throws IllegalStateException("account 4242 $secret")
        val events = java.util.concurrent.CopyOnWriteArrayList<DiagnosticEvent>()
        val latch = java.util.concurrent.CountDownLatch(1)
        val writer = object : DiagnosticEventWriter {
            override suspend fun emit(event: DiagnosticEvent) {
                events.add(event)
                latch.countDown()
            }
        }
        ShadowLog.clear()

        val result = WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider, writer)

        assertEquals("IllegalStateException", result.error)
        assertTrue("diagnostic event must be emitted", latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("IllegalStateException", JSONObject(events.single().metadata.toJson()).getString("error"))
        assertFalse(events.single().metadata.toJson().contains(secret))
        ShadowLog.getLogs().forEach { item ->
            assertFalse("log must not contain message text", item.msg.contains(secret))
            assertTrue("log must not attach the throwable", item.throwable == null)
        }
    }

    // ── G-TIME-01: deterministic next-midnight delay via injected TimeProvider ──

    @Test
    fun `midnight worker initial delay equals next-midnight delay from fixed time`() {
        val name = "fixed_time_midnight_worker"
        val spec = WorkerSpec(
            name = name,
            version = 1,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.KEEP
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)
        // Pre-seed the persisted version so no version-bump path fires; this keeps
        // the enqueued request's initialDelay attributable purely to the fixed time.
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().putInt(name, spec.version).commit()

        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(eq(name), any(), capture(requestSlot))
        } returns mockk(relaxed = true)

        val result = WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, timeProvider)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(eq(name), any(), any<OneTimeWorkRequest>())
        }
        assertTrue("Fixed-time midnight worker should return scheduled=true", result.scheduled)

        // Expected delay mirrors production exactly: next midnight in the system zone
        // from the SAME fixed "now" (no wall clock), floored at the 60s minimum.
        val now = timeProvider.now()
        val zone = java.time.ZoneId.systemDefault()
        val nextMidnightMs = java.time.Instant.ofEpochMilli(now)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val expectedDelay = maxOf(nextMidnightMs - now, 60_000L)

        assertEquals(
            "OneTimeWorkRequest.workSpec.initialDelay must equal the next-midnight delay " +
                "computed from the same fixed time/zone",
            expectedDelay,
            requestSlot.captured.workSpec.initialDelay
        )
    }

    @Test
    fun `near-midnight schedule floors initial delay at 60 seconds`() {
        val name = "near_midnight_worker"
        val spec = WorkerSpec(
            name = name,
            version = 1,
            enabled = true,
            repeatIntervalHours = null,
            oneShotPolicy = ExistingWorkPolicy.KEEP
        )
        every { WorkerSpec.DEFAULTS } returns mapOf(name to spec)
        // Pre-seed the persisted version so no version-bump path fires.
        context.getSharedPreferences("worker_spec_versions", Context.MODE_PRIVATE)
            .edit().putInt(name, spec.version).commit()

        // Derive a deterministic time 30s before the next midnight boundary from the
        // same fixed timestamp/zone (still no wall clock), so the raw delay would be
        // sub-minute and the production 60s floor must apply.
        val zone = java.time.ZoneId.systemDefault()
        val nextMidnightMs = java.time.Instant.ofEpochMilli(timeProvider.now())
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val nearMidnight = FakeTimeProvider(nextMidnightMs - 30_000L)

        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(eq(name), any(), capture(requestSlot))
        } returns mockk(relaxed = true)

        val result = WorkerSpecScheduler.scheduleAtMidnight(context, name, workerClass, nearMidnight)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(eq(name), any(), any<OneTimeWorkRequest>())
        }
        assertTrue("Near-midnight worker should return scheduled=true", result.scheduled)
        assertEquals(
            "Sub-minute raw delay must be floored to the production minimum of 60s",
            60_000L,
            requestSlot.captured.workSpec.initialDelay
        )
    }
}
