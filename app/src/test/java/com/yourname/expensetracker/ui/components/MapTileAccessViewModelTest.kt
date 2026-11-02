package com.yourname.expensetracker.ui.components

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S19-2 / D7: osmdroid tile downloads disclose the viewed map area, so tiles are gated by
 * `PrivacyGate.check(EXTERNAL_GEOCODING)`. Only `Allowed` may turn tiles on; everything else, including a
 * gate failure, keeps them off (fail closed) and shows the resource-backed denied state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapTileAccessViewModelTest : ViewModelTestUtils() {

    private val gate = mockk<PrivacyGate>()

    private fun stubGate(decision: PrivacyDecision) {
        coEvery { gate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } returns decision
    }

    private fun assertDeniedState(blocked: PrivacyBlockedUiState?) {
        assertNotNull("denied state must be present", blocked)
        assertEquals(PrivacyCapability.EXTERNAL_GEOCODING, blocked!!.capability)
        assertEquals(R.string.privacy_blocked_external_geocoding, blocked.messageResId)
        assertEquals(PrivacyGateReasonCodes.EXTERNAL_GEOCODING_DISABLED, blocked.reasonCode)
    }

    @Test
    fun `S19-2 tilesAllowed starts false before the gate has answered`() = runTest(testDispatcher) {
        stubGate(PrivacyDecision.Allowed)

        val vm = MapTileAccessViewModel(gate)

        assertFalse(vm.tilesAllowed.value)
        assertNull(vm.blocked.value)
        advanceUntilIdle()
        assertTrue(vm.tilesAllowed.value)
    }

    @Test
    fun `S19-2 pending ui state has tiles off and no card`() {
        assertFalse(MapTileAccessUiState.Pending.tilesAllowed)
        assertNull(MapTileAccessUiState.Pending.blocked)
    }

    @Test
    fun `S19-2 Allowed turns tiles on and clears the blocked state`() = runTest(testDispatcher) {
        stubGate(PrivacyDecision.Allowed)

        val vm = MapTileAccessViewModel(gate)
        advanceUntilIdle()

        assertTrue(vm.tilesAllowed.value)
        assertNull(vm.blocked.value)
        coVerify(exactly = 1) { gate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) }
    }

    @Test
    fun `S19-2 Denied leaves tiles off and sets the resource backed blocked state`() = runTest(testDispatcher) {
        stubGate(PrivacyDecision.Denied("anything"))

        val vm = MapTileAccessViewModel(gate)
        advanceUntilIdle()

        assertFalse(vm.tilesAllowed.value)
        assertDeniedState(vm.blocked.value)
    }

    @Test
    fun `S19-2 FailClosed leaves tiles off and sets the blocked state`() = runTest(testDispatcher) {
        stubGate(PrivacyDecision.FailClosed("anything"))

        val vm = MapTileAccessViewModel(gate)
        advanceUntilIdle()

        assertFalse(vm.tilesAllowed.value)
        assertDeniedState(vm.blocked.value)
    }

    @Test
    fun `S19-2 NotApplicable never turns tiles on`() = runTest(testDispatcher) {
        stubGate(PrivacyDecision.NotApplicable)

        val vm = MapTileAccessViewModel(gate)
        advanceUntilIdle()

        assertFalse(vm.tilesAllowed.value)
        assertDeniedState(vm.blocked.value)
    }

    @Test
    fun `S19-2 a gate exception leaves tiles off and stores no exception text`() = runTest(testDispatcher) {
        val secret = "SECRET-GATE-TEXT /storage/emulated/0/x.db"
        coEvery { gate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } throws IllegalStateException(secret)

        val vm = MapTileAccessViewModel(gate)
        advanceUntilIdle()

        assertFalse(vm.tilesAllowed.value)
        assertDeniedState(vm.blocked.value)
        val rendered = vm.blocked.value.toString()
        assertFalse(rendered, rendered.contains("SECRET"))
        assertFalse(rendered, rendered.contains("/storage"))
    }

    @Test
    fun `S19-2 evaluate propagates CancellationException instead of swallowing it`() = runTest(testDispatcher) {
        var next: () -> PrivacyDecision = { PrivacyDecision.Allowed }
        coEvery { gate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } answers { next() }
        val vm = MapTileAccessViewModel(gate)
        advanceUntilIdle()
        next = { throw CancellationException("cancelled by test") }

        var thrown: CancellationException? = null
        try {
            vm.evaluate()
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull("CancellationException must propagate out of evaluate()", thrown)
    }

    @Test
    fun `S19-2 a cancelled initial check keeps tiles off and the blocked state empty`() = runTest(testDispatcher) {
        coEvery { gate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } throws CancellationException("cancelled")

        val vm = MapTileAccessViewModel(gate)
        advanceUntilIdle()

        assertFalse(vm.tilesAllowed.value)
        assertNull(vm.blocked.value)
    }

    @Test
    fun `S19-2 refresh flipping from allowed to denied turns tiles off`() = runTest(testDispatcher) {
        var decision: PrivacyDecision = PrivacyDecision.Allowed
        coEvery { gate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } answers { decision }
        val vm = MapTileAccessViewModel(gate)
        advanceUntilIdle()
        assertTrue(vm.tilesAllowed.value)
        assertNull(vm.blocked.value)

        decision = PrivacyDecision.Denied("user turned it off")
        vm.refresh()
        advanceUntilIdle()

        assertFalse(vm.tilesAllowed.value)
        assertDeniedState(vm.blocked.value)
    }

    @Test
    fun `S19-2 refresh flipping from denied to allowed turns tiles on and clears the card`() = runTest(testDispatcher) {
        var decision: PrivacyDecision = PrivacyDecision.Denied("off")
        coEvery { gate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } answers { decision }
        val vm = MapTileAccessViewModel(gate)
        advanceUntilIdle()
        assertFalse(vm.tilesAllowed.value)

        decision = PrivacyDecision.Allowed
        vm.refresh()
        advanceUntilIdle()

        assertTrue(vm.tilesAllowed.value)
        assertNull(vm.blocked.value)
    }

    @Test
    fun `S19-2 refresh turns tiles off immediately and keeps them off while the check is pending then Allowed turns them on`() =
        runTest(testDispatcher) {
            val pending = CompletableDeferred<PrivacyDecision>()
            var calls = 0
            coEvery { gate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } coAnswers {
                calls++
                if (calls == 1) PrivacyDecision.Allowed else pending.await()
            }
            val vm = MapTileAccessViewModel(gate)
            advanceUntilIdle()
            assertTrue("tiles must be allowed before refresh", vm.tilesAllowed.value)

            vm.refresh()
            assertFalse("refresh must turn tiles off immediately", vm.tilesAllowed.value)
            runCurrent()
            assertEquals("refresh check must be in flight", 2, calls)
            assertFalse("tiles must stay off while the check is pending", vm.tilesAllowed.value)

            pending.complete(PrivacyDecision.Allowed)
            advanceUntilIdle()

            assertTrue(vm.tilesAllowed.value)
            assertNull(vm.blocked.value)
        }

    @Test
    fun `S19-2 refresh turns tiles off immediately and keeps them off while the check is pending then Denied keeps them off`() =
        runTest(testDispatcher) {
            val pending = CompletableDeferred<PrivacyDecision>()
            var calls = 0
            coEvery { gate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } coAnswers {
                calls++
                if (calls == 1) PrivacyDecision.Allowed else pending.await()
            }
            val vm = MapTileAccessViewModel(gate)
            advanceUntilIdle()
            assertTrue("tiles must be allowed before refresh", vm.tilesAllowed.value)

            vm.refresh()
            assertFalse("refresh must turn tiles off immediately", vm.tilesAllowed.value)
            runCurrent()
            assertEquals("refresh check must be in flight", 2, calls)
            assertFalse("tiles must stay off while the check is pending", vm.tilesAllowed.value)

            pending.complete(PrivacyDecision.Denied("off"))
            advanceUntilIdle()

            assertFalse(vm.tilesAllowed.value)
            assertDeniedState(vm.blocked.value)
        }

    @Test
    fun `S19-2 overlapping refreshes latest wins when a slow earlier allowed answer arrives late`() =
        runTest(testDispatcher) {
            val slowAllowed = CompletableDeferred<PrivacyDecision>()
            var calls = 0
            coEvery { gate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } coAnswers {
                calls++
                if (calls == 1) slowAllowed.await() else PrivacyDecision.Denied("latest")
            }
            val vm = MapTileAccessViewModel(gate)
            runCurrent()
            assertEquals("first check must be in flight", 1, calls)

            vm.refresh()
            advanceUntilIdle()
            slowAllowed.complete(PrivacyDecision.Allowed)
            advanceUntilIdle()

            assertEquals(2, calls)
            assertFalse("a stale Allowed answer must not turn tiles on", vm.tilesAllowed.value)
            assertDeniedState(vm.blocked.value)
        }

    @Test
    fun `S19-2 overlapping refreshes latest wins when a slow earlier denied answer arrives late`() =
        runTest(testDispatcher) {
            val slowDenied = CompletableDeferred<PrivacyDecision>()
            var calls = 0
            coEvery { gate.check(PrivacyCapability.EXTERNAL_GEOCODING, any()) } coAnswers {
                calls++
                if (calls == 1) slowDenied.await() else PrivacyDecision.Allowed
            }
            val vm = MapTileAccessViewModel(gate)
            runCurrent()
            assertEquals("first check must be in flight", 1, calls)

            vm.refresh()
            advanceUntilIdle()
            slowDenied.complete(PrivacyDecision.Denied("stale"))
            advanceUntilIdle()

            assertEquals(2, calls)
            assertTrue("a stale Denied answer must not turn tiles off", vm.tilesAllowed.value)
            assertNull(vm.blocked.value)
        }
}
