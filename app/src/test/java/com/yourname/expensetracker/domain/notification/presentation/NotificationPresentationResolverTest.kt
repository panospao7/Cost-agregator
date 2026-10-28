package com.yourname.expensetracker.domain.notification.presentation

import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingField
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S4: [NotificationPresentationResolver] bounded read (INV-2, INV-6, INV-9; AE-7, AE-8, AE-9, AE-10).
 * Virtual time throughout; the repository is a hanging or failing fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPresentationResolverTest {

    private class FakeRepository(
        private val read: suspend () -> AppLockSettingsLoadState
    ) : AppLockSettingsRepository {
        var reads = 0
        override suspend fun getLoadState(): AppLockSettingsLoadState {
            reads++
            return read()
        }

        override fun observeLoadState(): Flow<AppLockSettingsLoadState> = emptyFlow()

        override suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult =
            error("not used by the resolver")

        // S18 ii-f (finding 8): the write methods are abstract; this fake does not support them, so they fail closed.
        override suspend fun setAppLockEnabled(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED)

        override suspend fun setAutoLockTimeout(timeout: AutoLockTimeout): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT)

        override suspend fun setAutoLockTimeoutIfNotWeakening(timeout: AutoLockTimeout): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT)

        override suspend fun setDiscreetNotificationsIfNotForced(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.DISCREET)

        override suspend fun setSecureScreenIfNotForced(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.SECURE_SCREEN)
    }

    private data class Emitted(val code: PrivacyDiagnosticCode, val metadata: PrivacyDiagnosticMetadata)

    private val emitted = mutableListOf<Emitted>()
    private var emitBlocks = false

    private val diagnostics = mockk<PrivacyDiagnosticsEmitter>().also {
        coEvery { it.emit(any(), any()) } coAnswers {
            emitted += Emitted(firstArg(), secondArg())
            if (emitBlocks) awaitCancellation()
        }
    }

    private fun TestScope.resolverFor(repository: AppLockSettingsRepository) =
        NotificationPresentationResolver(
            repository,
            diagnostics,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        )

    private fun loaded(discreet: Boolean) =
        AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = discreet))

    // --- Readable ---------------------------------------------------------------------------

    @Test
    fun `readable settings with discreet off resolve FULL and emit nothing`() = runTest {
        val resolver = resolverFor(FakeRepository { loaded(false) })

        val decision = resolver.resolve(NotificationKind.ANOMALY, 34)

        assertEquals(NotificationPresentationMode.FULL, decision.mode)
        assertEquals(NotificationKind.ANOMALY, decision.kind)
        assertNull(decision.fallbackReason)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `first run default resolves FULL and discreet on resolves DISCREET without diagnostics`() = runTest {
        val first = resolverFor(FakeRepository { AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS) })
        assertEquals(NotificationPresentationMode.FULL, first.resolve(NotificationKind.BUDGET, 34).mode)

        val on = resolverFor(FakeRepository { loaded(true) })
        val decision = on.resolve(NotificationKind.BUDGET, 34)
        assertEquals(NotificationPresentationMode.DISCREET, decision.mode)
        assertNull(decision.fallbackReason)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `bill on api 34 carries the auth required action policy`() = runTest {
        val resolver = resolverFor(FakeRepository { loaded(false) })

        assertEquals(NotificationActionPolicy.AUTH_REQUIRED, resolver.resolve(NotificationKind.BILL, 34).actionPolicy)
        assertEquals(NotificationActionPolicy.UNCHANGED, resolver.resolve(NotificationKind.BILL, 30).actionPolicy)
    }

    // --- 1b lock terms (S14): AE-64, AE-65, INV-10 -------------------------------------------

    private fun lockOn() =
        AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = false, appLockEnabled = true))

    @Test
    fun `AE-65 lock on with discreet off resolves DISCREET for every kind without a fallback or diagnostic`() = runTest {
        val resolver = resolverFor(FakeRepository { lockOn() })

        for (kind in NotificationKind.entries) {
            val decision = resolver.resolve(kind, 34)
            assertEquals(NotificationPresentationMode.DISCREET, decision.mode)
            assertNull(decision.fallbackReason)
        }
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `AE-64 bill on api 30 with the lock on drops the actions and emits the suppressed code with the sdk only`() = runTest {
        val resolver = resolverFor(FakeRepository { lockOn() })

        val decision = resolver.resolve(NotificationKind.BILL, 30)

        assertEquals(NotificationActionPolicy.NONE, decision.actionPolicy)
        assertEquals(1, emitted.size)
        assertEquals(PrivacyDiagnosticCode.NOTIF_ACTIONS_SUPPRESSED_LOCK, emitted.single().code)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.SDK_INT to 30),
            emitted.single().metadata.entries
        )
    }

    @Test
    fun `AE-64 bill on api 30 with unreadable settings drops the actions and emits both codes`() = runTest {
        val resolver = resolverFor(FakeRepository { AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED) })

        val decision = resolver.resolve(NotificationKind.BILL, 30)

        assertEquals(NotificationActionPolicy.NONE, decision.actionPolicy)
        assertEquals(
            setOf(
                PrivacyDiagnosticCode.NOTIF_POLICY_SETTINGS_UNREADABLE,
                PrivacyDiagnosticCode.NOTIF_ACTIONS_SUPPRESSED_LOCK
            ),
            emitted.map { it.code }.toSet()
        )
    }

    @Test
    fun `AE-64 a timed out read on api 30 drops the bill actions`() = runTest {
        val resolver = resolverFor(FakeRepository { awaitCancellation() })

        val result = async { resolver.resolve(NotificationKind.BILL, 30) }
        runCurrent()
        advanceTimeBy(NotificationPresentationResolver.T_READ_MILLIS)
        runCurrent()

        assertEquals(NotificationActionPolicy.NONE, result.await().actionPolicy)
        assertTrue(emitted.any { it.code == PrivacyDiagnosticCode.NOTIF_ACTIONS_SUPPRESSED_LOCK })
    }

    @Test
    fun `AE-64 a policy exception on api 30 drops the bill actions and emits the suppressed code`() = runTest {
        val resolver = resolverFor(FakeRepository { throw IllegalStateException("boom") })

        val decision = resolver.resolve(NotificationKind.BILL, 30)

        assertEquals(NotificationActionPolicy.NONE, decision.actionPolicy)
        assertEquals(
            setOf(
                PrivacyDiagnosticCode.NOTIF_POLICY_FALLBACK_EXCEPTION,
                PrivacyDiagnosticCode.NOTIF_ACTIONS_SUPPRESSED_LOCK
            ),
            emitted.map { it.code }.toSet()
        )
    }

    @Test
    fun `AE-64 api 31 keeps the actions with authentication and emits no suppressed code`() = runTest {
        val resolver = resolverFor(FakeRepository { lockOn() })

        val decision = resolver.resolve(NotificationKind.BILL, 31)

        assertEquals(NotificationActionPolicy.AUTH_REQUIRED, decision.actionPolicy)
        assertTrue(emitted.none { it.code == PrivacyDiagnosticCode.NOTIF_ACTIONS_SUPPRESSED_LOCK })
    }

    @Test
    fun `lock off on api 30 keeps the bill actions and emits no suppressed code`() = runTest {
        val resolver = resolverFor(FakeRepository { loaded(false) })

        assertEquals(NotificationActionPolicy.UNCHANGED, resolver.resolve(NotificationKind.BILL, 30).actionPolicy)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `non bill kinds never emit the suppressed code even with the lock on`() = runTest {
        val resolver = resolverFor(FakeRepository { lockOn() })

        for (kind in NotificationKind.entries.filter { it != NotificationKind.BILL }) resolver.resolve(kind, 30)

        assertTrue(emitted.none { it.code == PrivacyDiagnosticCode.NOTIF_ACTIONS_SUPPRESSED_LOCK })
    }

    // --- AE-7: unreadable -------------------------------------------------------------------

    @Test
    fun `every unreadable cause resolves DISCREET and emits the unreadable code with that cause`() = runTest {
        for (cause in SettingsUnreadableCause.entries) {
            emitted.clear()
            val resolver = resolverFor(FakeRepository { AppLockSettingsLoadState.Unreadable(cause) })

            val decision = resolver.resolve(NotificationKind.BILL, 34)

            assertEquals(NotificationPresentationMode.DISCREET, decision.mode)
            assertEquals(1, emitted.size)
            assertEquals(PrivacyDiagnosticCode.NOTIF_POLICY_SETTINGS_UNREADABLE, emitted.single().code)
            assertEquals(cause, emitted.single().metadata.entries[PrivacyDiagnosticKey.CAUSE])
        }
    }

    // --- AE-8: slow read --------------------------------------------------------------------

    @Test
    fun `a hanging read resolves DISCREET at exactly T_read on virtual time and emits TIMEOUT`() = runTest {
        val repository = FakeRepository { awaitCancellation() }
        val resolver = resolverFor(repository)

        val result = async { resolver.resolve(NotificationKind.WARRANTY, 34) }
        runCurrent()
        advanceTimeBy(NotificationPresentationResolver.T_READ_MILLIS - 1)
        runCurrent()
        assertFalse("must still be waiting before T_read", result.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertTrue("must give up at T_read", result.isCompleted)

        val decision = result.await()
        assertEquals(NotificationPresentationMode.DISCREET, decision.mode)
        assertEquals(NotificationFallbackReason.READ_TIMEOUT, decision.fallbackReason)
        assertEquals(1, repository.reads)
        assertEquals(1, emitted.size)
        assertEquals(PrivacyDiagnosticCode.NOTIF_POLICY_SETTINGS_UNREADABLE, emitted.single().code)
        assertEquals(SettingsUnreadableCause.TIMEOUT, emitted.single().metadata.entries[PrivacyDiagnosticKey.CAUSE])
    }

    @Test
    fun `T_read is 500 ms`() {
        assertEquals(500L, NotificationPresentationResolver.T_READ_MILLIS)
    }

    // --- AE-9: cancellation -----------------------------------------------------------------

    @Test
    fun `cancelling the caller during the read propagates and posts no fallback and no diagnostic`() = runTest {
        val resolver = resolverFor(FakeRepository { awaitCancellation() })

        val result = async { resolver.resolve(NotificationKind.BILL, 34) }
        runCurrent()
        result.cancel()
        runCurrent()

        assertTrue(result.isCancelled)
        try {
            result.await()
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            // propagated, not converted into a DISCREET decision
        }
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `a CancellationException thrown by the read is rethrown and not mapped to DISCREET`() = runTest {
        val resolver = resolverFor(FakeRepository { throw CancellationException("stopped") })

        try {
            resolver.resolve(NotificationKind.BILL, 34)
            fail("expected CancellationException")
        } catch (expected: CancellationException) {
            // rethrown before the broad catch
        }
        assertTrue(emitted.isEmpty())
    }

    // --- AE-10: exception -------------------------------------------------------------------

    @Test
    fun `a non cancellation exception resolves DISCREET and emits the fallback code with class name only`() = runTest {
        val resolver = resolverFor(FakeRepository { throw IllegalStateException("SENTINEL-SECRET-MESSAGE") })

        val decision = resolver.resolve(NotificationKind.RECEIPT_MATCH, 34)

        assertEquals(NotificationPresentationMode.DISCREET, decision.mode)
        assertEquals(NotificationKind.RECEIPT_MATCH, decision.kind)
        assertEquals(NotificationFallbackReason.POLICY_EXCEPTION, decision.fallbackReason)
        assertEquals(1, emitted.size)
        val event = emitted.single()
        assertEquals(PrivacyDiagnosticCode.NOTIF_POLICY_FALLBACK_EXCEPTION, event.code)
        assertEquals(mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.EXCEPTION_CLASS to "IllegalStateException"), event.metadata.entries)
        assertFalse(event.metadata.entries.values.any { it.toString().contains("SENTINEL") })
    }

    // --- INV-9: diagnostics never block the post --------------------------------------------

    @Test
    fun `a diagnostic that never completes does not delay the decision`() = runTest {
        emitBlocks = true
        val resolver = resolverFor(FakeRepository { AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR) })

        val result = async { resolver.resolve(NotificationKind.BILL, 34) }
        runCurrent()

        assertTrue("the post path must not await the emitter", result.isCompleted)
        assertEquals(NotificationPresentationMode.DISCREET, result.await().mode)
        assertEquals(1, emitted.size)
    }

    @Test
    fun `the read happens once per resolve`() = runTest {
        val repository = FakeRepository { loaded(false) }
        val resolver = resolverFor(repository)

        resolver.resolve(NotificationKind.BILL, 34)
        resolver.resolve(NotificationKind.BUDGET, 34)

        assertEquals(2, repository.reads)
    }
}
