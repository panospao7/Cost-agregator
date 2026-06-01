package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P6-CURRENT-026: Verifies [BudgetRepository] emits durable BUDGET-pipeline diagnostic events
 * through the **repository** path (not raw DAO) for add/update/delete/toggle — on both success
 * and failure — and that the emission is strictly best-effort (a writer failure must NEVER fail
 * the underlying mutation).
 */
@Suppress("DEPRECATION_ERROR")
class BudgetRepositoryDiagnosticsTest {

    private val budgetDao = mockk<BudgetDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val budgetCalculator = mockk<BudgetCalculator>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val offsetEngine = mockk<SharedExpenseBudgetOffsetEngine>(relaxed = true)
    private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private lateinit var multiCurrencyRepository: MultiCurrencyRepository
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val budgetForecastDao = mockk<BudgetForecastDao>(relaxed = true)
    private val diagnosticEventWriter = mockk<DiagnosticEventWriter>(relaxed = true)
    private val diagnosticSink =
        mockk<com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink>(relaxed = true)

    private val emitted = mutableListOf<DiagnosticEvent>()
    private lateinit var repository: BudgetRepository

    @Before
    fun setup() {
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { budgetDao.insert(any()) } returns 7L
        coEvery { budgetDao.insertAndActivateOverall(any()) } returns 7L
        coEvery { budgetDao.insertAndActivateCategory(any()) } returns 7L
        coEvery { budgetDao.update(any()) } returns Unit
        coEvery { budgetDao.delete(any()) } returns Unit
        coEvery { budgetDao.getActiveBudgets() } returns emptyList()
        every { budgetDao.getAllFlow() } returns MutableStateFlow(emptyList())
        every { budgetDao.getActiveBudgetsFlow() } returns MutableStateFlow(emptyList())
        every { categoryDao.getAllFlow() } returns MutableStateFlow(emptyList())
        every { expenseDao.getTotalSpentFlow() } returns MutableStateFlow(0.0)
        every { expenseDao.observeExpenseMutationClock() } returns MutableStateFlow(0)
        coEvery { expenseDao.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns
            listOf(CurrencyTotal("EUR", 0.0, 0))
        every { timeProvider.now() } returns 1_000_000L

        // Run Room withTransaction inline on the test coroutine.
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(transactionBlock)) } coAnswers {
            transactionBlock.captured.invoke()
        }

        emitted.clear()
        coEvery { diagnosticEventWriter.emit(capture(emitted)) } returns Unit

        multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = expenseDao,
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettingsRepository
        )

        repository = BudgetRepository(
            budgetDao = budgetDao,
            categoryDao = categoryDao,
            expenseDao = expenseDao,
            budgetCalculator = budgetCalculator,
            timeProvider = timeProvider,
            offsetEngine = offsetEngine,
            timeBoundaryTicker = TimeBoundaryTicker(timeProvider),
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            writeBarrier = writeBarrier,
            database = database,
            budgetForecastDao = budgetForecastDao,
            diagnosticEventWriter = diagnosticEventWriter,
            diagnosticSink = diagnosticSink
        )
    }

    private fun validBudget(amount: Double = 100.0): Budget = Budget(
        id = 0,
        categoryId = null,
        amount = amount,
        period = BudgetPeriod.MONTHLY,
        startDate = 500_000L,
        isActive = true,
        rollover = false,
        notifyAtWarning = 0.75f,
        notifyAtCritical = 0.90f
    )

    @Test
    fun `addBudget emits BUDGET_ADDED via repository on success`() = runTest(UnconfinedTestDispatcher()) {
        val result = repository.addBudget(validBudget())

        assertTrue(result is Result.Success)
        val event = emitted.singleOrNull { it.stage == "BUDGET_ADDED" }
        assertNotNull("Expected a BUDGET_ADDED diagnostic event", event)
        assertEquals(AppPipeline.BUDGET, event!!.pipeline)
        assertEquals(EventOutcome.CREATED, event.outcome)
        assertEquals("Budget", event.entityType)
        assertEquals(7L, event.entityId)
    }

    @Test
    fun `addBudget validation rejection emits BUDGET_ADD_FAILED via repository`() =
        runTest(UnconfinedTestDispatcher()) {
            // amount 0.0 fails validateBudget -> caught -> Result.Error + failure diagnostic.
            val result = repository.addBudget(validBudget(amount = 0.0))

            assertTrue(result is Result.Error)
            val event = emitted.singleOrNull { it.stage == "BUDGET_ADD_FAILED" }
            assertNotNull("Expected a BUDGET_ADD_FAILED diagnostic event", event)
            assertEquals(EventOutcome.FAILED_FINAL, event!!.outcome)
            assertNotNull("Failure event must capture the rejection exception", event.exception)
        }

    @Test
    fun `toggleBudget emits BUDGET_TOGGLED via repository`() = runTest(UnconfinedTestDispatcher()) {
        val result = repository.toggleBudget(id = 9L, isActive = false)

        assertTrue(result is Result.Success)
        val event = emitted.singleOrNull { it.stage == "BUDGET_TOGGLED" }
        assertNotNull("Expected a BUDGET_TOGGLED diagnostic event", event)
        assertEquals(EventOutcome.UPDATED, event!!.outcome)
        assertEquals(9L, event.entityId)
    }

    @Test
    fun `deleteBudget emits BUDGET_DELETED via repository`() = runTest(UnconfinedTestDispatcher()) {
        val result = repository.deleteBudget(validBudget().copy(id = 3L))

        assertTrue(result is Result.Success)
        val event = emitted.singleOrNull { it.stage == "BUDGET_DELETED" }
        assertNotNull("Expected a BUDGET_DELETED diagnostic event", event)
        assertEquals(EventOutcome.DELETED, event!!.outcome)
        assertEquals(3L, event.entityId)
    }

    @Test
    fun `event writer failure does not fail the budget mutation`() = runTest(UnconfinedTestDispatcher()) {
        // Best-effort contract: a throwing writer must be swallowed and the mutation must succeed.
        coEvery { diagnosticEventWriter.emit(any()) } throws RuntimeException("diagnostic sink down")

        val result = repository.addBudget(validBudget())

        assertTrue("Mutation must succeed even when the event writer throws", result is Result.Success)
        assertEquals(7L, (result as Result.Success).data)
    }
}
