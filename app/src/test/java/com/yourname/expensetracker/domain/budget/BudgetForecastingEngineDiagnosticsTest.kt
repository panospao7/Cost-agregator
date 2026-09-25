package com.yourname.expensetracker.domain.budget

import android.database.sqlite.SQLiteConstraintException
import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetForecast
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
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * P6-CURRENT-026: Verifies [BudgetForecastingEngine] emits a durable "forecast generated" event on
 * success and a "forecast unavailable" event when home currency or the budget-limit conversion is
 * unavailable. All emissions reuse the existing BUDGET pipeline / DiagnosticEvent API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BudgetForecastingEngineDiagnosticsTest : AnalyticsEngineTestBase() {

    private val logs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += t to message
        }
    }

    @After
    fun removeLogTree() { Timber.uproot(logTree) }

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
        logs.clear()
        Timber.plant(logTree)
        budgetRepository = mockk(relaxed = true)
        budgetForecastDao = mockk(relaxed = true)
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } returns 1L

        // RP-09 (P6-007): deterministic PERIOD_END period-spend aggregate so the
        // risk-math path always proceeds (never the typed-unavailable branch) in
        // these diagnostics-focused tests.
        coEvery {
            budgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd(any(), any(), any(), any())
        } returns BudgetRepository.CurrentPeriodSpendAtPeriodEnd(
            aggregate = com.yourname.expensetracker.domain.core.money.MoneyAggregate.empty(
                com.yourname.expensetracker.domain.core.money.CurrencyCode("EUR"),
                com.yourname.expensetracker.domain.core.money.RateBasis.PERIOD_END
            ),
            rateAsOfMillis = fixedNow
        )

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

    @Test
    fun `unique insert stays duplicate with bounded log`() = runTest {
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } throws
            DiagnosticsSQLiteConstraintException("UNIQUE constraint failed: SQL /private/receipt merchant 1234.56")

        assertEquals(ForecastInsertResult.DuplicateInSameInstant, engine.insertForecast(mockk<BudgetForecast>()))

        assertEquals(listOf(null to "BudgetForecastingEngine: UNKNOWN_ERROR stage=unique_insert class=DiagnosticsSQLiteConstraintException"), logs)
    }

    @Test
    fun `foreign key insert failure returns typed constraint result without leaked payload`() = runTest {
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } throws
            DiagnosticsSQLiteConstraintException("FOREIGN KEY constraint failed: SQL /private/receipt merchant 1234.56")

        assertEquals(ForecastInsertResult.ConstraintViolation, engine.insertForecast(mockk<BudgetForecast>()))

        assertEquals(listOf(null to "BudgetForecastingEngine: UNKNOWN_ERROR stage=constraint_insert class=DiagnosticsSQLiteConstraintException"), logs)
        assertTrue(logs.all { it.first == null })
    }

    @Test
    fun `constraint insert with null message stays typed and bounded`() = runTest {
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } throws
            DiagnosticsSQLiteConstraintException(null)

        assertEquals(ForecastInsertResult.ConstraintViolation, engine.insertForecast(mockk<BudgetForecast>()))

        assertEquals(
            listOf(
                null to
                    "BudgetForecastingEngine: UNKNOWN_ERROR stage=constraint_insert class=DiagnosticsSQLiteConstraintException"
            ),
            logs
        )
        assertTrue(logs.all { it.first == null })
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
    fun `generateForecastResult maps persistence constraint to bounded unavailable event`() = runTest {
        val rawPayload = "CHECK constraint failed: SQL /private/receipt merchant 1234.56"
        coEvery {
            mockConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } returns converted(1_000.0)
        coEvery { budgetForecastDao.insertWithDeactivation(any()) } throws
            DiagnosticsSQLiteConstraintException(rawPayload)

        val result = engine.generateForecastResult(budget)

        val unavailable = result as BudgetForecastResult.Unavailable
        assertEquals(ForecastUnavailableReason.UNKNOWN, unavailable.reasonCode)
        assertEquals("Forecast skipped: persistence constraint rejected the insert", unavailable.reason)
        assertTrue(!unavailable.reason.contains(rawPayload))

        val event = emitted.single { it.stage == "FORECAST_UNAVAILABLE" }
        assertEquals(EventOutcome.SKIPPED, event.outcome)
        assertEquals(12L, event.entityId)
        assertTrue(event.metadata.toJson().contains("PERSISTENCE_CONSTRAINT"))
        assertTrue(!event.metadata.toJson().contains(rawPayload))
        assertTrue(emitted.none { it.stage == "FORECAST_GENERATED" })
        assertEquals(
            listOf(
                null to
                    "BudgetForecastingEngine: UNKNOWN_ERROR stage=constraint_insert class=DiagnosticsSQLiteConstraintException"
            ),
            logs
        )
        assertTrue(logs.all { it.first == null })
    }

    @Test
    fun `generateForecastResult emits FORECAST_UNAVAILABLE when home currency unavailable`() = runTest {
        coEvery { mockCurrencySettingsRepo.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Failed("SQL /data/user/0/private.db merchant 42.00")

        val result = engine.generateForecastResult(budget)

        assertEquals("Home currency unavailable", (result as BudgetForecastResult.Unavailable).reason)
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
            message = "SQL /data/user/0/private.db merchant 42.00"
        )

        val result = engine.generateForecastResult(budget.copy(currency = "USD"))

        assertEquals("Budget limit conversion unavailable", (result as BudgetForecastResult.Unavailable).reason)
        val event = emitted.singleOrNull { it.stage == "FORECAST_UNAVAILABLE" }
        assertNotNull("Expected a FORECAST_UNAVAILABLE diagnostic event", event)
        assertEquals(EventOutcome.SKIPPED, event!!.outcome)
    }

    @Test
    fun `unavailable period spend uses fixed reason and does not insert forecast`() = runTest {
        val unavailable = com.yourname.expensetracker.domain.core.money.MoneyAggregate.empty(
            CurrencyCode("EUR"), RateBasis.PERIOD_END
        ).copy(conversionQuality = com.yourname.expensetracker.domain.core.money.ConversionQuality.UNAVAILABLE)
        coEvery { budgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd(any(), any(), any(), any()) } returns
            BudgetRepository.CurrentPeriodSpendAtPeriodEnd(unavailable, fixedNow)
        coEvery { mockConverter.convertOutcome(any(), any(), any(), any(), any(), any()) } returns converted(1_000.0)

        val result = engine.generateForecastResult(budget)

        assertEquals(ForecastUnavailableReason.MISSING_RATE, (result as BudgetForecastResult.Unavailable).reasonCode)
        assertEquals("Current-period spend unavailable: exchange rates missing for the budget period", result.reason)
        assertEquals(EventOutcome.SKIPPED, emitted.single { it.stage == "FORECAST_UNAVAILABLE" }.outcome)
        io.mockk.coVerify(exactly = 0) { budgetForecastDao.insertWithDeactivation(any()) }
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

private class DiagnosticsSQLiteConstraintException(
    override val message: String?
) : SQLiteConstraintException(message)
