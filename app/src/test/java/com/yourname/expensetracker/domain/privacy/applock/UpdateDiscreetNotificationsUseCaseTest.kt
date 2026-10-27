package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.service.NotificationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * S9b pass 2: [UpdateDiscreetNotificationsUseCase] (INV-13, INV-46, AMD-7; AE-15, AE-102 trigger side,
 * AE-105). The sweep runs only after a committed write that turned effective discreet on.
 */
class UpdateDiscreetNotificationsUseCaseTest {

    /** In-memory repository with committed-only semantics: a failed write leaves the value in force. */
    private class FakeRepository(
        var state: AppLockSettingsLoadState,
        var writeOutcome: (Boolean) -> AppLockSettingsWriteResult = { enabled ->
            AppLockSettingsWriteResult.Committed(AppLockPrivacySettings(discreetNotifications = enabled))
        }
    ) : AppLockSettingsRepository {
        var writes = 0

        override suspend fun getLoadState(): AppLockSettingsLoadState = state

        override fun observeLoadState(): Flow<AppLockSettingsLoadState> = flowOf(state)

        override suspend fun setDiscreetNotifications(enabled: Boolean): AppLockSettingsWriteResult {
            writes++
            val result = writeOutcome(enabled)
            if (result is AppLockSettingsWriteResult.Committed) {
                state = AppLockSettingsLoadState.Loaded(result.settings)
            }
            return result
        }

        override suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult =
            throw UnsupportedOperationException()

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

    private val service = mockk<NotificationService>()
    private val diagnostics = mockk<PrivacyDiagnosticsEmitter>(relaxed = true)
    private val trigger = DiscreetRepostTrigger(service, diagnostics)

    private fun loaded(discreet: Boolean) =
        AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = discreet))

    private fun useCase(repository: FakeRepository) =
        UpdateDiscreetNotificationsUseCase(repository, trigger, diagnostics)

    private fun sweepReturns() {
        coEvery { service.repostActiveAsDiscreet() } returns NotificationService.RepostResult(reposted = 1, failed = 0)
    }

    @Test
    fun `turning discreet on from off commits and runs exactly one sweep`() = runTest {
        sweepReturns()
        val repository = FakeRepository(loaded(false))

        val result = useCase(repository)(true)

        assertEquals(UpdateDiscreetNotificationsResult.Committed(true, DiscreetRepostOutcome.SWEPT), result)
        assertEquals(loaded(true), repository.state)
        coVerify(exactly = 1) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `turning discreet on from the first run default runs one sweep`() = runTest {
        sweepReturns()
        val repository = FakeRepository(AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS))

        val result = useCase(repository)(true)

        assertEquals(UpdateDiscreetNotificationsResult.Committed(true, DiscreetRepostOutcome.SWEPT), result)
        coVerify(exactly = 1) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `turning discreet on when already on commits without a sweep`() = runTest {
        sweepReturns()
        val repository = FakeRepository(loaded(true))

        val result = useCase(repository)(true)

        assertEquals(UpdateDiscreetNotificationsResult.Committed(true, DiscreetRepostOutcome.NOT_TRIGGERED), result)
        coVerify(exactly = 0) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `turning discreet off never re-posts`() = runTest {
        sweepReturns()
        val repository = FakeRepository(loaded(true))

        val result = useCase(repository)(false)

        assertEquals(UpdateDiscreetNotificationsResult.Committed(false, DiscreetRepostOutcome.NOT_TRIGGERED), result)
        assertEquals(loaded(false), repository.state)
        coVerify(exactly = 0) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `turning discreet off when already off never re-posts`() = runTest {
        sweepReturns()
        val repository = FakeRepository(loaded(false))

        val result = useCase(repository)(false)

        assertEquals(UpdateDiscreetNotificationsResult.Committed(false, DiscreetRepostOutcome.NOT_TRIGGERED), result)
        coVerify(exactly = 0) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `AE-105 a failed write keeps the committed value, records the field and runs no sweep`() = runTest {
        sweepReturns()
        val repository = FakeRepository(
            state = loaded(false),
            writeOutcome = { AppLockSettingsWriteResult.Failed(AppLockSettingField.DISCREET) }
        )
        val code = slot<PrivacyDiagnosticCode>()
        val metadata = slot<PrivacyDiagnosticMetadata>()
        coEvery { diagnostics.emit(capture(code), capture(metadata)) } returns Unit

        val result = useCase(repository)(true)

        assertEquals(UpdateDiscreetNotificationsResult.Failed(AppLockSettingField.DISCREET), result)
        assertEquals(loaded(false), repository.state)
        assertEquals(PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED, code.captured)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.FIELD to AppLockSettingField.DISCREET),
            metadata.captured.entries
        )
        coVerify(exactly = 0) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `a write rejected while unreadable runs no sweep and records no persist failure`() = runTest {
        sweepReturns()
        val repository = FakeRepository(
            state = AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED),
            writeOutcome = { AppLockSettingsWriteResult.Rejected(AppLockWriteRejection.UNREADABLE) }
        )

        val result = useCase(repository)(true)

        assertEquals(UpdateDiscreetNotificationsResult.Rejected(AppLockWriteRejection.UNREADABLE), result)
        coVerify(exactly = 0) { service.repostActiveAsDiscreet() }
        coVerify(exactly = 0) { diagnostics.emit(any(), any()) }
    }

    @Test
    fun `an unreadable before-read with a committed ON is treated as was-off and runs exactly one sweep`() = runTest {
        sweepReturns()
        val repository = FakeRepository(AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.CORRUPTED))

        val result = useCase(repository)(true)

        assertEquals(UpdateDiscreetNotificationsResult.Committed(true, DiscreetRepostOutcome.SWEPT), result)
        assertEquals(loaded(true), repository.state)
        coVerify(exactly = 1) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `a sweep that throws leaves the committed write successful and records the failure`() = runTest {
        coEvery { service.repostActiveAsDiscreet() } throws IllegalStateException("never stored")
        val repository = FakeRepository(loaded(false))
        val code = slot<PrivacyDiagnosticCode>()
        val metadata = slot<PrivacyDiagnosticMetadata>()
        coEvery { diagnostics.emit(capture(code), capture(metadata)) } returns Unit

        val result = useCase(repository)(true)

        assertEquals(UpdateDiscreetNotificationsResult.Committed(true, DiscreetRepostOutcome.SWEEP_FAILED), result)
        assertEquals(loaded(true), repository.state)
        assertEquals(PrivacyDiagnosticCode.NOTIF_REPOST_FAILED, code.captured)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.EXCEPTION_CLASS to "IllegalStateException"),
            metadata.captured.entries
        )
    }

    @Test
    fun `cancellation from the sweep propagates after the write committed`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { service.repostActiveAsDiscreet() } throws cancellation
        val repository = FakeRepository(loaded(false))

        try {
            useCase(repository)(true)
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        assertEquals(loaded(true), repository.state)
    }

    @Test
    fun `cancellation from the repository write propagates and runs no sweep`() = runTest {
        sweepReturns()
        val cancellation = CancellationException("cancelled")
        val repository = FakeRepository(loaded(false), writeOutcome = { throw cancellation })

        try {
            useCase(repository)(true)
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        coVerify(exactly = 0) { service.repostActiveAsDiscreet() }
    }
}
