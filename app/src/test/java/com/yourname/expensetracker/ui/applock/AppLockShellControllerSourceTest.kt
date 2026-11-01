package com.yourname.expensetracker.ui.applock

import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.LockCause
import com.yourname.expensetracker.domain.privacy.applock.LockNotice
import com.yourname.expensetracker.domain.privacy.applock.PinPresentation
import com.yourname.expensetracker.domain.privacy.applock.PinUnlockResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** S27 a2: `ControllerGateSource` hands the gate the controller's own flows and delegates the three PIN calls. */
class AppLockShellControllerSourceTest {

    private val stateFlow = MutableStateFlow<AppLockState>(AppLockState.Locked(LockCause.COLD_START))
    private val noticeFlow = MutableStateFlow<LockNotice?>(null)
    private val pinFlow = MutableStateFlow(PinPresentation.INITIAL)
    private val controller = mockk<AppLockController>()

    private fun source(): ControllerGateSource {
        every { controller.state } returns stateFlow
        every { controller.notice } returns noticeFlow
        every { controller.pin } returns pinFlow
        return ControllerGateSource(controller)
    }

    @Test
    fun flows_areTheControllersOwnInstances() {
        val source = source()
        assertSame(stateFlow, source.state)
        assertSame(noticeFlow, source.notice)
        assertSame(pinFlow, source.pin)
    }

    @Test
    fun submitUnlockPin_delegatesTheSameArray_andReturnsTheControllersResult() = runBlocking {
        val source = source()
        val captured = slot<CharArray>()
        coEvery { controller.submitUnlockPin(capture(captured)) } returns PinUnlockResult.NotInPinMode
        val digits = "135790".toCharArray()

        val result = source.pinActions.submitUnlockPin(digits)

        assertEquals(PinUnlockResult.NotInPinMode, result)
        assertSame(digits, captured.captured)
    }

    @Test
    fun retryPinRecovery_delegatesOnce() = runBlocking {
        val source = source()
        coEvery { controller.retryPinRecovery() } just Runs

        source.pinActions.retryPinRecovery()

        coVerify(exactly = 1) { controller.retryPinRecovery() }
    }

    @Test
    fun requestFingerprintUnlock_delegatesOnce() = runBlocking {
        val source = source()
        coEvery { controller.requestFingerprintUnlock() } just Runs

        source.pinActions.requestFingerprintUnlock()

        coVerify(exactly = 1) { controller.requestFingerprintUnlock() }
    }

    @Test
    fun dismissBiometricNotice_delegatesToTheControllerOnce() = runBlocking {
        val source = source()
        coEvery { controller.dismissBiometricNotice() } just Runs

        source.dismissBiometricNotice()

        coVerify(exactly = 1) { controller.dismissBiometricNotice() }
    }

    @Test
    fun constructingTheSource_callsNoControllerAction() {
        source()
        coVerify(exactly = 0) { controller.dismissBiometricNotice() }
        coVerify(exactly = 0) { controller.submitUnlockPin(any()) }
        coVerify(exactly = 0) { controller.retryPinRecovery() }
        coVerify(exactly = 0) { controller.requestFingerprintUnlock() }
    }
}
