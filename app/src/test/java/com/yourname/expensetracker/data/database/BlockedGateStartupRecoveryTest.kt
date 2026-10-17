package com.yourname.expensetracker.data.database

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** R25 D-M2: decision logic for a blocked open gate at startup, driven by fakes. */
class BlockedGateStartupRecoveryTest {

    private class Fakes(
        var journalActive: Boolean = true,
        var recoveryThrows: Throwable? = null,
        var reevaluated: String? = null
    ) {
        var journalChecks = 0
        var recoveryRuns = 0
        var reevaluations = 0

        fun resolve(initial: String?): String? = BlockedGateStartupRecovery.resolve(
            initialReason = initial,
            isJournalActive = { journalChecks++; journalActive },
            runJournalRecovery = {
                recoveryRuns++
                recoveryThrows?.let { throw it }
            },
            reevaluate = { reevaluations++; reevaluated }
        )
    }

    @Test
    fun notBlockedNeverRunsRecovery() {
        val fakes = Fakes()
        assertNull(fakes.resolve(null))
        assertEquals(0, fakes.recoveryRuns)
        assertEquals(0, fakes.reevaluations)
    }

    @Test
    fun schemaReasonsStayBlockedWithoutRecovery() {
        for (reason in listOf(DatabaseOpenGate.REASON_SCHEMA_TOO_OLD, DatabaseOpenGate.REASON_SCHEMA_TOO_NEW)) {
            val fakes = Fakes()
            assertEquals(reason, fakes.resolve(reason))
            assertEquals(0, fakes.journalChecks)
            assertEquals(0, fakes.recoveryRuns)
            assertEquals(0, fakes.reevaluations)
        }
    }

    @Test
    fun openFailedWithoutActiveJournalStaysBlocked() {
        val fakes = Fakes(journalActive = false)
        assertEquals(DatabaseOpenGate.REASON_OPEN_FAILED, fakes.resolve(DatabaseOpenGate.REASON_OPEN_FAILED))
        assertEquals(0, fakes.recoveryRuns)
        assertEquals(0, fakes.reevaluations)
    }

    @Test
    fun openFailedWithActiveJournalRecoversThenReprobesOnceAndProceedsWhenHealthy() {
        val fakes = Fakes(reevaluated = null)
        assertNull(fakes.resolve(DatabaseOpenGate.REASON_OPEN_FAILED))
        assertEquals(1, fakes.recoveryRuns)
        assertEquals(1, fakes.reevaluations)
    }

    @Test
    fun stillBlockedAfterRecoveryKeepsTheReprobeReason() {
        val fakes = Fakes(reevaluated = DatabaseOpenGate.REASON_OPEN_FAILED)
        assertEquals(DatabaseOpenGate.REASON_OPEN_FAILED, fakes.resolve(DatabaseOpenGate.REASON_OPEN_FAILED))
        assertEquals(1, fakes.recoveryRuns)
        assertEquals(1, fakes.reevaluations)
    }

    @Test
    fun recoveryFailureFailsClosedWithoutReprobe() {
        val fakes = Fakes(recoveryThrows = IllegalStateException("boom"), reevaluated = null)
        assertEquals(DatabaseOpenGate.REASON_OPEN_FAILED, fakes.resolve(DatabaseOpenGate.REASON_OPEN_FAILED))
        assertEquals(1, fakes.recoveryRuns)
        assertEquals(0, fakes.reevaluations)
    }

    @Test
    fun cancellationDuringRecoveryPropagates() {
        val fakes = Fakes(recoveryThrows = CancellationException("cancelled"))
        try {
            fakes.resolve(DatabaseOpenGate.REASON_OPEN_FAILED)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertTrue(e.message == "cancelled")
        }
        assertFalse(fakes.reevaluations > 0)
    }
}
