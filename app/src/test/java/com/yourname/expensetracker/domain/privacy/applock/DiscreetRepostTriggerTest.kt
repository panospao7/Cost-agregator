package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.service.NotificationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * S9b pass 2: [DiscreetRepostTrigger] edge rule (INV-13, INV-46, AMD-7, AE-15, AE-105) and failure
 * isolation (CancellationException propagates; any other sweep failure is bounded and swallowed).
 */
class DiscreetRepostTriggerTest {

    private val service = mockk<NotificationService>()
    private val diagnostics = mockk<PrivacyDiagnosticsEmitter>(relaxed = true)
    private val trigger = DiscreetRepostTrigger(service, diagnostics)

    private fun sweepReturns() {
        coEvery { service.repostActiveAsDiscreet() } returns NotificationService.RepostResult(reposted = 2, failed = 0)
    }

    @Test
    fun `false to true runs exactly one sweep`() = runTest {
        sweepReturns()

        val outcome = trigger.onCommittedWrite(wasEffectiveDiscreet = false, isEffectiveDiscreet = true)

        assertEquals(DiscreetRepostOutcome.SWEPT, outcome)
        coVerify(exactly = 1) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `true to false never sweeps`() = runTest {
        sweepReturns()

        val outcome = trigger.onCommittedWrite(wasEffectiveDiscreet = true, isEffectiveDiscreet = false)

        assertEquals(DiscreetRepostOutcome.NOT_TRIGGERED, outcome)
        coVerify(exactly = 0) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `true to true never sweeps`() = runTest {
        sweepReturns()

        val outcome = trigger.onCommittedWrite(wasEffectiveDiscreet = true, isEffectiveDiscreet = true)

        assertEquals(DiscreetRepostOutcome.NOT_TRIGGERED, outcome)
        coVerify(exactly = 0) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `false to false never sweeps`() = runTest {
        sweepReturns()

        val outcome = trigger.onCommittedWrite(wasEffectiveDiscreet = false, isEffectiveDiscreet = false)

        assertEquals(DiscreetRepostOutcome.NOT_TRIGGERED, outcome)
        coVerify(exactly = 0) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `a sweep that throws a non cancellation exception is swallowed and recorded by class name only`() = runTest {
        coEvery { service.repostActiveAsDiscreet() } throws IllegalStateException("sentinel message must never be stored")
        val code = slot<PrivacyDiagnosticCode>()
        val metadata = slot<PrivacyDiagnosticMetadata>()
        coEvery { diagnostics.emit(capture(code), capture(metadata)) } returns Unit

        val outcome = trigger.onCommittedWrite(wasEffectiveDiscreet = false, isEffectiveDiscreet = true)

        assertEquals(DiscreetRepostOutcome.SWEEP_FAILED, outcome)
        assertEquals(PrivacyDiagnosticCode.NOTIF_REPOST_FAILED, code.captured)
        assertEquals(
            mapOf<PrivacyDiagnosticKey, Any>(PrivacyDiagnosticKey.EXCEPTION_CLASS to "IllegalStateException"),
            metadata.captured.entries
        )
        coVerify(exactly = 1) { service.repostActiveAsDiscreet() }
    }

    @Test
    fun `cancellation from the sweep propagates and records nothing`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { service.repostActiveAsDiscreet() } throws cancellation

        try {
            trigger.onCommittedWrite(wasEffectiveDiscreet = false, isEffectiveDiscreet = true)
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }

        coVerify(exactly = 0) { diagnostics.emit(any(), any()) }
    }
}
