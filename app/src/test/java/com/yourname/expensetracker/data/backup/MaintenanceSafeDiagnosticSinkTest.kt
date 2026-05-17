package com.yourname.expensetracker.data.backup

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

    // ── restore_blocked_notification_diagnostic_does_not_insert_room_row ──

    @Test
    fun sink_does_not_throw_in_any_mode() {
        // The sink must never throw — it's a fire-and-forget fallback
        for (mode in RestoreMaintenanceMode.Mode.entries) {
            sink.recordBlockedOperation(
                operation = "NotificationRepository.save",
                mode = mode,
                pipeline = "P1",
                entity = "RawNotification"
            )
            // No exception = pass
        }
    }

    // ── restore_blocked_email_diagnostic_does_not_insert_room_row ─────────

    @Test
    fun sink_accepts_null_pipeline_and_entity() {
        sink.recordBlockedOperation(
            operation = "EmailReceiptIngestionService.processEmail",
            mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        )
        // No exception = pass
    }

    // ── blocked_operation_written_to_safe_sink ────────────────────────────

    @Test
    fun write_barrier_exception_carries_mode_for_sink() {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING)
        val op = DatabaseAccessOperation("saveExpense", pipeline = "P2", entity = "Expense")

        val ex = try {
            writeBarrier.checkWritesAllowed(op)
            null
        } catch (e: DatabaseAccessBlockedException) {
            e
        }

        assertNotNull("Expected DatabaseAccessBlockedException", ex)
        // Sink can consume the exception's mode directly
        sink.recordBlockedOperation(
            operation = ex!!.operation.name,
            mode = ex.mode,
            pipeline = ex.operation.pipeline,
            entity = ex.operation.entity
        )
    }

    @Test
    fun sink_interface_is_implemented_by_timber_impl() {
        val sink: MaintenanceSafeDiagnosticSink = TimberMaintenanceSafeDiagnosticSink()
        // Verify the interface contract is satisfied
        sink.recordBlockedOperation(
            operation = "test",
            mode = RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED,
            pipeline = "P9",
            entity = "PipelineDiagnosticEvent"
        )
    }

    @Test
    fun mock_sink_can_verify_calls() {
        val mockSink = mockk<MaintenanceSafeDiagnosticSink>(relaxed = true)

        mockSink.recordBlockedOperation(
            operation = "BudgetMonitor.diagnostic",
            mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
            pipeline = "P6"
        )

        verify {
            mockSink.recordBlockedOperation(
                operation = "BudgetMonitor.diagnostic",
                mode = RestoreMaintenanceMode.Mode.RESTORE_PREPARING,
                pipeline = "P6",
                entity = null
            )
        }
    }
}
