package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * P6-CURRENT-026: Verifies [BudgetForecastingEngine] emits a durable "forecast generated" event on
 * success and a "forecast unavailable" event when home currency or the budget-limit conversion is
 * unavailable. All emissions reuse the existing BUDGET pipeline / DiagnosticEvent API.
 */
class BudgetForecastingEngineDiagnosticsTest : AnalyticsEngineTestBase() {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var budgetForecastDao: BudgetForecastDao
    private lateinit var engine: BudgetForecastingEngine
    private lateinit var mockExpenseRepo: ExpenseRepository
    private lateinit var mockCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var mockCurrencySettingsRepo: CurrencySettingsRepository
    private lateinit var mockConverter: CurrencyConverter
    private lateinit var mockWriteBarrier: DatabaseWriteBarrier
    private lateinit var diagnosticEventWriter: DiagnosticEventWriter
    private lateinit var diagnosticSink:
        com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink

    private val emitted = mutableListOf<DiagnosticEvent>()

    private val budget = Budget(
        id = 12L,
        categoryId = null,
        amount = 1_000.0,
        period = BudgetPeriod.MONTHLY,
        startDate = fixedNow
    )

    @Before
    override fun setUp() {
        super.setUp()
        budgetRepository = mockk(relaxed = true)
        budgetForecastDao = mockk(relaxed = true)
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } returns 1L

        mockExpenseRepo = mockk(relaxed = true)
        mockCurrencyNormalizer = mockk(relaxed = true)
        mockCurrencySettingsRepo = mockk(relaxed = true)
        mockConverter = mockk(relaxed = true)
        mockWriteBarrier = mockk(relaxed = true)
        diagnosticEventWriter = mockk(relaxed = true)
        diagnosticSink = mockk(relaxed = true)

        coEvery { mockExpenseRepo.getExpenseSnapshotsBetween(any(), any()) } returns emptyList()
        coEvery { mockCurrencyNormalizer.normalizeSnapshots(any(), any()) } answers {
            val expenses = firstArg<List<ExpenseSnapshot>>()
            val homeCurrency = secondArg<String>()
            AnalyticsNormalizationResult(
                homeCurrency = homeCurrency,
                normalizedExpenses = expenses.map {
                    NormalizedExpenseSnapshot(it, it.currency, it.effectiveAmount, it.effectiveAmount)
                },
                includedExpenses = expenses,
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            )
        }
        every { mockCurrencySettingsRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { mockCurrencySettingsRepo.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        emitted.clear()
        coEvery { diagnosticEventWriter.emit(capture(emitted)) } returns Unit

        engine = BudgetForecastingEngine(
            expenseDao = expenseDao,
            budgetRepository = budgetRepository,
            budgetForecastDao = budgetForecastDao,
            timeProvider = timeProvider,
            ioDispatcher = Dispatchers.Unconfined,
            analyticsCurrencyNormalizer = mockCurrencyNormalizer,
            expenseRepository = mockExpenseRepo,
            currencySettingsRepository = mockCurrencySettingsRepo,
            currencyConverter = mockConverter,
            writeBarrier = mockWriteBarrier,
            diagnosticEventWriter = diagnosticEventWriter,
            diagnosticSink = diagnosticSink
        )
    }

    private fun converted(amount: Double): ConversionOutcome.Converted = ConversionOutcome.Converted(
        originalAmount = amount,
        originalCurrency = CurrencyCode("EUR"),
        convertedAmount = amount,
        targetCurrency = CurrencyCode("EUR"),
        rateUsed = 1.0,
        rateBasis = RateBasis.IDENTITY,
        rateValidDate = null,
        rateLastUpdated = null,
        rateSource = null,
        conversionPath = ConversionPath.IDENTITY
    )

    @Test
    fun `generateForecastResult emits FORECAST_GENERATED on success`() = runTest {
        coEvery {
            mockConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } returns converted(1_000.0)

        val result = engine.generateForecastResult(budget)

        assertNotNull(result as? BudgetForecastResult.Available)
        val event = emitted.singleOrNull { it.stage == "FORECAST_GENERATED" }
        assertNotNull("Expected a FORECAST_GENERATED diagnostic event", event)
        assertEquals(AppPipeline.BUDGET, event!!.pipeline)
        assertEquals(EventOutcome.COMPLETED, event.outcome)
        assertEquals("Budget", event.entityType)
        assertEquals(12L, event.entityId)
    }

    @Test
    fun `generateForecastResult emits FORECAST_UNAVAILABLE when home currency unavailable`() = runTest {
        coEvery { mockCurrencySettingsRepo.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Failed("no home currency")

        val result = engine.generateForecastResult(budget)

        assertNotNull(result as? BudgetForecastResult.Unavailable)
        val event = emitted.singleOrNull { it.stage == "FORECAST_UNAVAILABLE" }
        assertNotNull("Expected a FORECAST_UNAVAILABLE diagnostic event", event)
        assertEquals(EventOutcome.SKIPPED, event!!.outcome)
        assertEquals(12L, event.entityId)
    }

    @Test
    fun `generateForecastResult emits FORECAST_UNAVAILABLE when limit conversion fails`() = runTest {
        coEvery {
            mockConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } returns ConversionOutcome.Failed(
            originalAmount = 1_000.0,
            originalCurrency = "USD",
            targetCurrency = "EUR",
            rateBasis = RateBasis.PERIOD_END,
            failureType = ConversionFailureType.MISSING_RATE,
            message = "no rate"
        )

        val result = engine.generateForecastResult(budget.copy(currency = "USD"))

        assertNotNull(result as? BudgetForecastResult.Unavailable)
        val event = emitted.singleOrNull { it.stage == "FORECAST_UNAVAILABLE" }
        assertNotNull("Expected a FORECAST_UNAVAILABLE diagnostic event", event)
        assertEquals(EventOutcome.SKIPPED, event!!.outcome)
    }

    @Test
    fun `event writer failure does not fail forecast generation`() = runTest {
        coEvery {
            mockConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } returns converted(1_000.0)
        coEvery { diagnosticEventWriter.emit(any()) } throws RuntimeException("diagnostic sink down")

        val result = engine.generateForecastResult(budget)

        assertNotNull("Forecast must succeed even when the event writer throws",
            result as? BudgetForecastResult.Available)
    }
}
