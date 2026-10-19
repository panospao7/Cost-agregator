package com.yourname.expensetracker.data.backup

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class MaintenanceSafeDiagnosticSinkTest {

    private val maintenanceMode = mockk<RestoreMaintenanceMode>()
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var sink: TimberMaintenanceSafeDiagnosticSink

    @Before
    fun setup() {
        writeBarrier = DatabaseWriteBarrier(maintenanceMode)
        sink = TimberMaintenanceSafeDiagnosticSink()
    }

    private fun setMode(mode: RestoreMaintenanceMode.Mode) {
        every { maintenanceMode.currentMode() } returns mode
    }

    @Test
    fun write_barrier_exception_carries_mode_for_sink() = runTest {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING)
        val op = DatabaseAccessOperation("saveExpense", pipeline = "P2", entity = "Expense")
        val ex = try { writeBarrier.checkWritesAllowed(op); null }
                 catch (e: DatabaseAccessBlockedException) { e }
        assertNotNull("Expected DatabaseAccessBlockedException", ex)
        assertEquals(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING, ex!!.mode)
        assertEquals("saveExpense", ex.operation.name)
        assertEquals("P2", ex.operation.pipeline)
        assertEquals("Expense", ex.operation.entity)
        // No-throw check: the Timber sink's only effect is a log line (no observable state),
        // so this verifies the exception's fields can be fed to the sink without failure.
        sink.recordBlockedOperation(
            operation = ex.operation.name,
            mode = ex.mode,
            pipeline = ex.operation.pipeline,
            entity = ex.operation.entity
        )
    }
}
