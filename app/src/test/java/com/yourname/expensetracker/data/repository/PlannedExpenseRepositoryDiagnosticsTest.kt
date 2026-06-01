package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P6-CURRENT-026: Verifies [PlannedExpenseRepository] emits durable BUDGET-pipeline diagnostic
 * events through the **repository** path (not raw DAO) on add/delete and on validation rejection,
 * and that the emission is strictly best-effort (a writer failure must NEVER fail the mutation).
 */
class PlannedExpenseRepositoryDiagnosticsTest {

    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val plannedExpenseDao = mockk<PlannedExpenseDao>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val diagnosticEventWriter = mockk<DiagnosticEventWriter>(relaxed = true)
    private val diagnosticSink =
        mockk<com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink>(relaxed = true)

    private val emitted = mutableListOf<DiagnosticEvent>()
    private lateinit var repository: PlannedExpenseRepository

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1_000_000L
        coEvery { plannedExpenseDao.insertPlannedExpense(any()) } returns 5L
        coEvery { plannedExpenseDao.deletePlannedExpense(any()) } returns Unit
        coEvery { plannedExpenseDao.deletePlannedExpenseById(any()) } returns Unit

        emitted.clear()
        coEvery { diagnosticEventWriter.emit(capture(emitted)) } returns Unit

        repository = PlannedExpenseRepository(
            writeBarrier = writeBarrier,
            plannedExpenseDao = plannedExpenseDao,
            timeProvider = timeProvider,
            diagnosticEventWriter = diagnosticEventWriter,
            diagnosticSink = diagnosticSink
        )
    }

    private fun validPlanned(amount: Double = 25.0, status: String = "PLANNED"): PlannedExpense =
        PlannedExpense(
            id = 0L,
            description = "Test planned",
            amount = amount,
            currency = "EUR",
            date = 700_000L,
            status = status
        )

    @Test
    fun `addPlannedExpense emits PLANNED_ADDED via repository on success`() =
        runTest(UnconfinedTestDispatcher()) {
            val id = repository.addPlannedExpense(validPlanned())

            assertEquals(5L, id)
            val event = emitted.singleOrNull { it.stage == "PLANNED_ADDED" }
            assertNotNull("Expected a PLANNED_ADDED diagnostic event", event)
            assertEquals(AppPipeline.BUDGET, event!!.pipeline)
            assertEquals(EventOutcome.CREATED, event.outcome)
            assertEquals("PlannedExpense", event.entityType)
            assertEquals(5L, event.entityId)
        }

    @Test
    fun `addPlannedExpense validation rejection emits PLANNED_ADD_REJECTED and still throws`() =
        runTest(UnconfinedTestDispatcher()) {
            var thrown: Throwable? = null
            try {
                repository.addPlannedExpense(validPlanned(amount = -1.0))
            } catch (e: IllegalArgumentException) {
                thrown = e
            }

            assertNotNull("Validation rejection must still propagate to the caller", thrown)
            val event = emitted.singleOrNull { it.stage == "PLANNED_ADD_REJECTED" }
            assertNotNull("Expected a PLANNED_ADD_REJECTED diagnostic event", event)
            assertEquals(EventOutcome.DROPPED, event!!.outcome)
            assertTrue(
                "Rejection event must record the validation reason",
                event.metadata.toJson().contains("VALIDATION_REJECTED")
            )
        }

    @Test
    fun `deletePlannedExpense emits PLANNED_DELETED via repository`() =
        runTest(UnconfinedTestDispatcher()) {
            repository.deletePlannedExpense(validPlanned().copy(id = 4L))

            val event = emitted.singleOrNull { it.stage == "PLANNED_DELETED" }
            assertNotNull("Expected a PLANNED_DELETED diagnostic event", event)
            assertEquals(EventOutcome.DELETED, event!!.outcome)
            assertEquals(4L, event.entityId)
        }

    @Test
    fun `deletePlannedExpenseById emits PLANNED_DELETED via repository`() =
        runTest(UnconfinedTestDispatcher()) {
            repository.deletePlannedExpenseById(8L)

            val event = emitted.singleOrNull { it.stage == "PLANNED_DELETED" }
            assertNotNull("Expected a PLANNED_DELETED diagnostic event", event)
            assertEquals(EventOutcome.DELETED, event!!.outcome)
            assertEquals(8L, event.entityId)
        }

    @Test
    fun `event writer failure does not fail the planned-expense add`() =
        runTest(UnconfinedTestDispatcher()) {
            // Best-effort contract: a throwing writer must be swallowed and the insert must succeed.
            coEvery { diagnosticEventWriter.emit(any()) } throws RuntimeException("diagnostic sink down")

            val id = repository.addPlannedExpense(validPlanned())

            assertEquals("Insert must succeed even when the event writer throws", 5L, id)
        }
}
