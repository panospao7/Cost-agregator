package com.yourname.expensetracker.data.repository

import android.content.Context
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
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
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectedEntry
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionRule
import com.yourname.expensetracker.domain.installment.projection.ProjectionEntryInput
import com.yourname.expensetracker.domain.installment.projection.ProjectionPlanInput
import com.yourname.expensetracker.domain.installment.projection.emptyInstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.projection.fakeInstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentOverview
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.UpcomingItem
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository as DomainSavingsGoalRepository

/**
 * S8b-3: [HomeInstallmentWeatherSource], the Home-only installment-aware weather (plan A15 option A).
 *
 * The three engines are the REAL, unmodified ForecastInputAssembler, SynthesisEngine and
 * NarrativeGenerator; the projection port is the real [InstallmentProjectionRule] behind a fake port,
 * so the INV-15 bounds under test are the product's. Money is checked in EUR (home) with EUR and USD
 * plans; a currency with no rate is omitted and flags the weather partial (INV-16), never 0.
 *
 * Arithmetic of the INV-29 test (synthetic, EUR, now = 2026-01-15 12:00): history is E1 = 40.00
 * (ordinary) and E2 = 100.00 (linked to an installment entry), both in the CURRENT month. A fake
 * pattern detector reports one monthly pattern worth the sum of the snapshots it is given, EXCEPT the
 * linked / plan-purchase ones (production: RecurringExpenseEngine excludes linked expenses). The entry
 * due 2026-01-20 is 50.00.
 *   baseline                          : committed = 40                  =  40, month-to-date = 140
 *   aware                             : committed = 40 + 50             =  90, month-to-date = 140
 *   (owner-approvals 60b) the exclusion applies only to snapshots dated BEFORE the current month, so the
 *   current-month actual payment E2 stays in month-to-date (counted once, never zero times).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeInstallmentWeatherSourceTest {

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 1, 15)
    private val nowMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun ms(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun jan(day: Int): LocalDate = LocalDate.of(2026, 1, day)

    private fun feb(day: Int): LocalDate = LocalDate.of(2026, 2, day)

    private lateinit var timeProvider: TimeProvider
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var plannedExpenseRepository: PlannedExpenseRepository
    private lateinit var savingsGoalRepository: DomainSavingsGoalRepository
    private lateinit var patternsProvider: MergedRecurringPatternsProvider
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var assembler: ForecastInputAssembler
    private lateinit var synthesisEngine: SynthesisEngine
    private lateinit var readRepository: InstallmentPlanReadRepository
    private lateinit var reader: CountingLinkReader
    private lateinit var context: Context

    private companion object {
        const val FALLBACK_LABEL = "Synthetic localized installment label"
    }

    /** Currency -> rate to EUR; a currency that is not in the table has no rate. */
    private var rates: Map<String, Double> = emptyMap()

    /** What the fake pattern detector returns for the snapshots it is handed. */
    private var patternsFor: (List<ExpenseSnapshot>) -> List<RecurringPattern> = { emptyList() }

    private val assembledInputs = mutableListOf<ForecastInputAssembler.ForecastInput>()

    /** Expense ids handed to the pattern detector, one set per call (baseline and aware both record). */
    private val detectorSeenIds = mutableListOf<Set<Long>>()

    private class CountingLinkReader(
        private val linkedExpenseIds: Set<Long> = emptySet(),
        private val planPurchaseIds: Set<Long> = emptySet()
    ) : InstallmentEntryLinkReader {
        var calls = 0

        override suspend fun linkedEntryIdFor(expenseId: Long): Long? {
            calls++
            return if (expenseId in linkedExpenseIds) 900L + expenseId else null
        }

        override suspend fun isPlanPurchase(expenseId: Long): Boolean {
            calls++
            return expenseId in planPurchaseIds
        }

        override suspend fun linkedExpenseIds(): Set<Long> {
            calls++
            return linkedExpenseIds
        }

        override suspend fun planPurchaseExpenseIds(): Set<Long> {
            calls++
            return planPurchaseIds
        }
    }

    @Before
    fun setUp() {
        timeProvider = mockk(relaxed = true)
        every { timeProvider.now() } returns nowMs
        expenseRepository = mockk(relaxed = true)
        budgetRepository = mockk(relaxed = true)
        recurringExpenseRepository = mockk(relaxed = true)
        plannedExpenseRepository = mockk(relaxed = true)
        savingsGoalRepository = mockk(relaxed = true)
        patternsProvider = mockk(relaxed = true)
        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
        coEvery { patternsProvider.getPatternsFromSnapshots(any(), any()) } answers {
            val snapshots = firstArg<List<ExpenseSnapshot>>()
            detectorSeenIds += snapshots.mapTo(HashSet()) { it.id }
            patternsFor(snapshots)
        }

        currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true).also {
            every { it.homeCurrency() } returns flowOf("EUR")
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }

        currencyConverter = mockk(relaxed = true)
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } answers {
            val amount = firstArg<Double>()
            val source = secondArg<String>()
            val target = thirdArg<String>()
            val basis = arg<RateBasis>(3)
            val rate = if (source.equals(target, ignoreCase = true)) 1.0 else rates[source.uppercase()]
            if (rate == null) {
                ConversionOutcome.Failed(
                    originalAmount = amount,
                    originalCurrency = source,
                    targetCurrency = target,
                    rateBasis = basis,
                    failureType = ConversionFailureType.MISSING_RATE,
                    message = ConversionFailureType.MISSING_RATE.name
                )
            } else {
                ConversionOutcome.Converted(
                    originalAmount = amount,
                    originalCurrency = CurrencyCode(source),
                    convertedAmount = amount * rate,
                    targetCurrency = CurrencyCode(target),
                    rateUsed = rate,
                    rateBasis = basis,
                    rateValidDate = null,
                    rateLastUpdated = null,
                    rateSource = null,
                    conversionPath = ConversionPath.DIRECT
                )
            }
        }

        // Pass-through normalizer: history is already in home currency, so the past series carries
        // the real effective amounts (same approach as FinancialWeatherRepositoryTest).
        val normalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
        coEvery { normalizer.normalizeSnapshots(any(), any()) } answers {
            val snapshots = firstArg<List<ExpenseSnapshot>>()
            AnalyticsNormalizationResult(
                homeCurrency = secondArg<String>(),
                normalizedExpenses = snapshots.map {
                    NormalizedExpenseSnapshot(it, it.currency, it.effectiveAmount, it.effectiveAmount)
                },
                includedExpenses = snapshots,
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = snapshots.size
            )
        }
        assembler = ForecastInputAssembler(
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = normalizer,
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = currencyConverter,
            recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
            recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true),
            databaseReadBarrier = mockk(relaxed = true)
        )
        synthesisEngine = spyk(SynthesisEngine(timeProvider, currencyConverter = currencyConverter))
        coEvery { synthesisEngine.synthesize(any<ForecastInputAssembler.ForecastInput>()) } coAnswers {
            assembledInputs += firstArg<ForecastInputAssembler.ForecastInput>()
            callOriginal()
        }

        readRepository = mockk(relaxed = true)
        every { readRepository.observeChanges() } returns flowOf(Unit)
        context = mockk(relaxed = true)
        every { context.getString(R.string.installment_plan_fallback_label) } returns FALLBACK_LABEL
        reader = CountingLinkReader()
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun source(
        port: InstallmentProjectionPort,
        linkReader: CountingLinkReader = reader
    ) = HomeInstallmentWeatherSource(
        expenseRepository = expenseRepository,
        budgetRepository = budgetRepository,
        recurringExpenseRepository = recurringExpenseRepository,
        mergedRecurringPatternsProvider = patternsProvider,
        plannedExpenseRepository = plannedExpenseRepository,
        savingsGoalRepository = savingsGoalRepository,
        forecastInputAssembler = assembler,
        synthesisEngine = synthesisEngine,
        narrativeGenerator = NarrativeGenerator(),
        currencySettingsRepository = currencySettingsRepository,
        timeProvider = timeProvider,
        installmentProjectionPort = port,
        recurringInstallmentEligibility = RecurringInstallmentEligibility(dagger.Lazy { linkReader }),
        installmentPlanReadRepository = readRepository,
        context = context
    )

    private fun baseline() = FinancialWeatherRepository(
        expenseRepository = expenseRepository,
        budgetRepository = budgetRepository,
        recurringExpenseRepository = recurringExpenseRepository,
        mergedRecurringPatternsProvider = patternsProvider,
        plannedExpenseRepository = plannedExpenseRepository,
        savingsGoalRepository = savingsGoalRepository,
        forecastInputAssembler = assembler,
        synthesisEngine = synthesisEngine,
        narrativeGenerator = NarrativeGenerator(),
        analyticsRepository = mockk(relaxed = true),
        currencySettingsRepository = currencySettingsRepository,
        timeProvider = timeProvider
    )

    private fun entry(
        id: Long,
        due: LocalDate,
        status: InstallmentEntryStatus = InstallmentEntryStatus.SCHEDULED,
        minor: Long = 5_000L
    ) = ProjectionEntryInput(
        entryId = id,
        seq = id.toInt(),
        kind = InstallmentEntryKind.REGULAR,
        status = status,
        dueEpochDay = due.toEpochDay(),
        scheduledMinor = minor,
        recordGeneration = 0
    )

    private fun plan(
        id: Long,
        entries: List<ProjectionEntryInput>,
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
        currency: InstallmentCurrency = InstallmentCurrency.EUR,
        name: String = "Synthetic plan $id"
    ) = ProjectionPlanInput(id, "plan-uuid-$id", name, currency, status, entries)

    private fun portOf(vararg plans: ProjectionPlanInput) =
        fakeInstallmentProjectionPort(plans.toList(), today.toEpochDay())

    private fun purchase(id: Long, amount: Double, date: LocalDate) = Expense(
        id = id,
        amount = amount,
        currency = "EUR",
        merchant = "Synthetic merchant $id",
        transactionType = TransactionType.PURCHASE,
        date = ms(date),
        createdAt = 1_000L,
        source = "test"
    )

    private fun monthlyPattern(name: String, amount: Double, nextDate: LocalDate) = RecurringPattern(
        merchantName = name,
        averageAmount = amount,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = ms(nextDate),
        confidence = 0.95f,
        previousDates = emptyList()
    )

    /**
     * One monthly pattern worth the sum of the snapshots handed to the detector (see the class KDoc),
     * except [linkedIds] (installment-linked / plan-purchase expenses): like production
     * (RecurringExpenseEngine excludes linked expenses) the detector never builds patterns from them.
     */
    private fun useSnapshotSensitiveDetector(linkedIds: Set<Long> = emptySet()) {
        patternsFor = { snapshots ->
            val sum = snapshots.filterNot { it.id in linkedIds }.sumOf { it.effectiveAmount }
            if (sum > 0.0) listOf(monthlyPattern("Synthetic detected", sum, jan(25))) else emptyList()
        }
    }

    // ── Null when nothing is projected ────────────────────────────────────────

    @Test
    fun `null when the port has no entries and the exclusion owner is not consulted`() = runTest {
        every { expenseRepository.getAllExpenses() } returns flowOf(listOf(purchase(1L, 40.0, jan(5))))

        val result = source(emptyInstallmentProjectionPort()).observe().first()

        assertNull(result)
        assertEquals("the link reader must not be read while nothing is projected", 0, reader.calls)
        assertTrue("the engines must not run while nothing is projected", assembledInputs.isEmpty())
    }

    @Test
    fun `AE-7 after Stop the aware weather is null`() = runTest {
        val stopped = plan(
            1L,
            listOf(
                entry(1L, jan(5), status = InstallmentEntryStatus.PAID),
                entry(2L, jan(20), status = InstallmentEntryStatus.CANCELLED),
                entry(3L, feb(10), status = InstallmentEntryStatus.CANCELLED)
            ),
            status = InstallmentPlanStatus.CANCELLED
        )

        assertNull(source(portOf(stopped)).observe().first())
    }

    @Test
    fun `AE-11 after completion the aware weather is null and linked history is left alone`() = runTest {
        every { expenseRepository.getAllExpenses() } returns flowOf(
            listOf(purchase(1L, 40.0, jan(5)), purchase(2L, 50.0, jan(10)))
        )
        val completed = plan(
            1L,
            listOf(entry(1L, jan(10), status = InstallmentEntryStatus.PAID)),
            status = InstallmentPlanStatus.COMPLETED
        )
        val linkReader = CountingLinkReader(linkedExpenseIds = setOf(2L))

        val result = source(portOf(completed), linkReader).observe().first()

        assertNull(result)
        assertEquals(0, linkReader.calls)
    }

    // ── INV-15 ───────────────────────────────────────────────────────────────

    @Test
    fun `INV-15 only SCHEDULED entries due today or later are projected into the month`() = runTest {
        val p = plan(
            1L,
            listOf(
                entry(1L, jan(10)), // overdue, never projected
                entry(2L, jan(18), status = InstallmentEntryStatus.PAID),
                entry(3L, jan(19), status = InstallmentEntryStatus.PAID_BEFORE_TRACKING),
                entry(4L, jan(20)), // projected, in this month
                entry(5L, feb(3), minor = 7_000L) // projected, in the 31-day list but not in this month
            )
        )

        val aware = source(portOf(p)).observe().first()

        assertNotNull(aware)
        assertEquals(listOf(4L, 5L), aware!!.installmentRows.map { it.entryId })
        // Only the Jan 20 entry is in the forecast month: 50.00.
        assertEquals(50.0, aware.weather.totalCommitted, 0.001)
        val projected = assembledInputs.single().plannedExpenses
        assertEquals(listOf(-4L, -5L), projected.map { it.id })
    }

    @Test
    fun `the rows use the 31-day window - today plus 30 is in and today plus 31 is out`() = runTest {
        val p = plan(1L, listOf(entry(1L, feb(14)), entry(2L, feb(15)), entry(3L, jan(15))))

        val aware = source(portOf(p)).observe().first()

        assertEquals(listOf(3L, 1L), aware!!.installmentRows.map { it.entryId })
    }

    // ── How the projections enter the unmodified assemble() ───────────────────

    @Test
    fun `projected entries enter assemble as MUST planned inputs in plan currency and are normalised by the assembler`() = runTest {
        rates = mapOf("USD" to 0.85)
        val eur = plan(1L, listOf(entry(11L, jan(20), minor = 5_000L)))
        val usd = plan(2L, listOf(entry(21L, jan(22), minor = 10_000L)), currency = InstallmentCurrency.USD)

        val aware = source(portOf(eur, usd)).observe().first()

        val input = assembledInputs.single()
        val byId = input.plannedExpenses.associateBy { it.id }
        // After the assembler's normalisation both are home-currency MUST items dated at the start of the due day.
        assertEquals(setOf(-11L, -21L), byId.keys)
        assertTrue(byId.values.all { it.priority == PlannedExpensePriority.MUST && it.currency == "EUR" })
        assertEquals(50.0, byId.getValue(-11L).amount, 0.001)
        assertEquals(85.0, byId.getValue(-21L).amount, 0.001)
        assertEquals(ms(jan(20)), byId.getValue(-11L).date)
        assertEquals(ms(jan(22)), byId.getValue(-21L).date)
        assertEquals("Synthetic plan 1", byId.getValue(-11L).description)
        assertEquals(0, input.dataQuality.excludedPlannedCount)
        // SynthesisEngine counts MUST items of the month as committed.
        assertEquals(135.0, aware!!.weather.totalCommitted, 0.001)
        assertFalse(aware.weather.isPartial)
    }

    // ── INV-29 ───────────────────────────────────────────────────────────────

    @Test
    fun `INV-29 aware committed is the baseline plus the projection and the current-month payment stays in month-to-date`() = runTest {
        useSnapshotSensitiveDetector(linkedIds = setOf(2L))
        val e1 = purchase(1L, 40.0, jan(5))
        val e2 = purchase(2L, 100.0, jan(10)) // linked to an installment entry, paid THIS month
        val linkReader = CountingLinkReader(linkedExpenseIds = setOf(2L))
        val p = plan(1L, listOf(entry(11L, jan(20), minor = 5_000L), entry(12L, feb(10), minor = 5_000L)))

        every { expenseRepository.getAllExpenses() } returns flowOf(listOf(e1, e2))
        val baselineAll = baseline().getFinancialWeather().first()
        val aware = source(portOf(p), linkReader).observe().first()!!.weather

        // The detector never builds a pattern from the linked E2 (production parity): baseline committed 40.
        assertEquals(40.0, baselineAll.totalCommitted, 0.001)
        val projectedThisMonth = 50.0
        assertEquals(baselineAll.totalCommitted + projectedThisMonth, aware.totalCommitted, 0.001)
        assertEquals(90.0, aware.totalCommitted, 0.001)
        // Month-to-date keeps the actual payment (owner-approvals 60b): 40 + 100, counted once.
        assertEquals(140.0, aware.pastSpendingPoints.last(), 0.001)
        assertEquals(140.0, baselineAll.pastSpendingPoints.last(), 0.001)
        assertTrue(linkReader.calls > 0)
    }

    @Test
    fun `INV-29 linked and plan purchase history before the current month leaves the aware baselines but stays in the current month`() = runTest {
        useSnapshotSensitiveDetector(linkedIds = setOf(2L, 3L, 4L, 5L))
        val december = LocalDate.of(2025, 12, 10)
        every { expenseRepository.getAllExpenses() } returns flowOf(
            listOf(
                purchase(1L, 40.0, jan(5)), // ordinary, current month
                purchase(2L, 100.0, jan(10)), // linked, current month: stays
                purchase(3L, 300.0, jan(2)), // plan purchase, current month: stays
                purchase(4L, 100.0, december), // linked, before the month: excluded
                purchase(5L, 900.0, december.minusDays(3)), // plan purchase, before the month: excluded
                purchase(6L, 60.0, december) // ordinary, before the month: stays
            )
        )
        val linkReader = CountingLinkReader(linkedExpenseIds = setOf(2L, 4L), planPurchaseIds = setOf(3L, 5L))
        val p = plan(1L, listOf(entry(11L, jan(20), minor = 5_000L)))
        detectorSeenIds.clear()

        val aware = source(portOf(p), linkReader).observe().first()!!.weather

        assertEquals(setOf(1L, 2L, 3L, 6L), detectorSeenIds.single())
        // Detected pattern = ordinary 40 + 60 (linked ones never feed it) = 100, plus the 50 projection.
        assertEquals(150.0, aware.totalCommitted, 0.001)
        // Month-to-date: 40 + 100 + 300 (current month, every one stays), December is not in it.
        assertEquals(440.0, aware.pastSpendingPoints.last(), 0.001)
    }

    @Test
    fun `monthly plan due on the 10th with today the 15th counts the paid entry once in month-to-date and the next entry next month`() = runTest {
        useSnapshotSensitiveDetector(linkedIds = setOf(2L))
        every { expenseRepository.getAllExpenses() } returns flowOf(
            listOf(purchase(1L, 40.0, jan(5)), purchase(2L, 100.0, jan(10)))
        )
        val linkReader = CountingLinkReader(linkedExpenseIds = setOf(2L))
        val p = plan(
            1L,
            listOf(
                entry(1L, jan(10), status = InstallmentEntryStatus.PAID, minor = 10_000L),
                entry(2L, feb(10), minor = 10_000L)
            )
        )
        detectorSeenIds.clear()

        val aware = source(portOf(p), linkReader).observe().first()!!

        // Only the NEXT entry (next month) is projected; the paid 10th is not projected again.
        assertEquals(listOf(2L), aware.installmentRows.map { it.entryId })
        assertEquals(listOf(-2L), assembledInputs.single().plannedExpenses.map { it.id })
        assertEquals(ms(feb(10)), assembledInputs.single().plannedExpenses.single().date)
        // The paid payment counts once in month-to-date (40 + 100) and the projection adds nothing this
        // month (committed is the detected pattern of the ordinary 40 only), so the installment is
        // counted exactly once for January.
        assertEquals(140.0, aware.weather.pastSpendingPoints.last(), 0.001)
        assertEquals(40.0, aware.weather.totalCommitted, 0.001)
        assertTrue("the current-month linked payment must reach the aware inputs", 2L in detectorSeenIds.single())
    }

    @Test
    fun `INV-29 plan purchase records are excluded from patterns and committed in the aware view`() = runTest {
        useSnapshotSensitiveDetector(linkedIds = setOf(3L))
        every { expenseRepository.getAllExpenses() } returns flowOf(
            listOf(purchase(1L, 40.0, jan(5)), purchase(3L, 300.0, jan(2)))
        )
        val linkReader = CountingLinkReader(planPurchaseIds = setOf(3L))
        val p = plan(1L, listOf(entry(11L, jan(20), minor = 5_000L)))

        val aware = source(portOf(p), linkReader).observe().first()!!.weather

        assertEquals(90.0, aware.totalCommitted, 0.001)
    }

    // ── INV-16 ───────────────────────────────────────────────────────────────

    @Test
    fun `INV-16 EUR home with a USD plan and a rate converts through the assembler and the row stays in USD`() = runTest {
        rates = mapOf("USD" to 0.85)
        val usd = plan(2L, listOf(entry(21L, jan(22), minor = 10_000L)), currency = InstallmentCurrency.USD)

        val aware = source(portOf(usd)).observe().first()!!

        assertEquals(85.0, aware.weather.totalCommitted, 0.001)
        assertFalse(aware.weather.isPartial)
        assertEquals(0, aware.weather.excludedCount)
        val row = aware.installmentRows.single()
        assertEquals(InstallmentCurrency.USD, row.currency)
        assertEquals(10_000L, row.amountMinor)
    }

    @Test
    fun `INV-16 a USD plan without a rate is omitted and partial, never 0 and never summed as EUR`() = runTest {
        rates = emptyMap()
        val eur = plan(1L, listOf(entry(11L, jan(20), minor = 5_000L)))
        val usd = plan(2L, listOf(entry(21L, jan(22), minor = 10_000L)), currency = InstallmentCurrency.USD)

        val aware = source(portOf(eur, usd)).observe().first()!!

        // The EUR entry counts (50.00). The 100.00 USD entry is omitted: not 0 added as a value, and
        // certainly not 100.00 summed as EUR.
        assertEquals(50.0, aware.weather.totalCommitted, 0.001)
        assertTrue(aware.weather.isPartial)
        assertTrue(aware.weather.excludedCount >= 1)
        assertTrue(aware.weather.qualityWarnings.any { it.contains("USD") })
        assertEquals(1, assembledInputs.single().dataQuality.excludedPlannedCount)
        assertEquals(setOf(-11L), assembledInputs.single().plannedExpenses.map { it.id }.toSet())
        // The Home rows still show both entries in their own currency, unconverted.
        assertEquals(
            listOf(InstallmentCurrency.EUR to 5_000L, InstallmentCurrency.USD to 10_000L),
            aware.installmentRows.map { it.currency to it.amountMinor }
        )
    }

    @Test
    fun `INV-16 only a USD plan without a rate leaves committed at the baseline and flags partial`() = runTest {
        rates = emptyMap()
        val usd = plan(2L, listOf(entry(21L, jan(22), minor = 10_000L)), currency = InstallmentCurrency.USD)

        val aware = source(portOf(usd)).observe().first()!!
        val base = baseline().getFinancialWeather().first()

        assertEquals(base.totalCommitted, aware.weather.totalCommitted, 0.001)
        assertFalse(base.isPartial)
        assertTrue(aware.weather.isPartial)
    }

    // ── upcomingItems ────────────────────────────────────────────────────────

    @Test
    fun `upcomingItems are identical to the baseline and a projection is never an UpcomingItem Planned`() = runTest {
        patternsFor = { listOf(monthlyPattern("Synthetic rent", 600.0, jan(25))) }
        val realPlanned = com.yourname.expensetracker.data.database.entity.PlannedExpense(
            id = 7L,
            description = "Synthetic laptop",
            amount = 300.0,
            date = ms(jan(18)),
            priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.LIKELY
        )
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(listOf(realPlanned))
        val p = plan(
            1L,
            listOf(entry(11L, jan(20)), entry(12L, feb(10))) // both inside the 31-day window
        )

        val base = baseline().getFinancialWeather().first()
        val aware = source(portOf(p)).observe().first()!!.weather

        assertEquals(base.upcomingItems, aware.upcomingItems)
        assertTrue(base.upcomingItems.any { it is UpcomingItem.Planned })
        assertTrue(aware.upcomingItems.none { it is UpcomingItem.Planned && it.expense.id < 0 })
        // Non-vacuity: the projection did change the numbers.
        assertEquals(base.totalCommitted + 50.0, aware.totalCommitted, 0.001)
    }

    // ── Invalidation and failure ─────────────────────────────────────────────

    @Test
    fun `PE-C a change emission re-runs the pipeline so an entry status change is picked up`() = runTest {
        val trigger = MutableSharedFlow<Unit>(replay = 1)
        every { readRepository.observeChanges() } returns trigger
        var plans = listOf(plan(1L, listOf(entry(11L, jan(20)), entry(12L, jan(28)))))
        val livePort = object : InstallmentProjectionPort {
            override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> =
                InstallmentProjectionRule.project(plans, today.toEpochDay(), horizonEndEpochDay)
        }
        val seen = mutableListOf<HomeInstallmentWeather?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { source(livePort).observe().toList(seen) }

        trigger.emit(Unit)
        runCurrent()
        assertEquals(1, seen.size)
        assertEquals(listOf(11L, 12L), seen[0]!!.installmentRows.map { it.entryId })
        assertEquals(100.0, seen[0]!!.weather.totalCommitted, 0.001)

        // Entry 11 becomes PAID: the plan rows change, the read repository re-emits.
        plans = listOf(plan(1L, listOf(entry(11L, jan(20), status = InstallmentEntryStatus.PAID), entry(12L, jan(28)))))
        trigger.emit(Unit)
        runCurrent()
        assertEquals(2, seen.size)
        assertEquals(listOf(12L), seen[1]!!.installmentRows.map { it.entryId })
        assertEquals(50.0, seen[1]!!.weather.totalCommitted, 0.001)

        // The last plan entry is paid: nothing is projected any more.
        plans = emptyList()
        trigger.emit(Unit)
        runCurrent()
        assertEquals(3, seen.size)
        assertNull(seen[2])
        job.cancel()
        // The trigger is the cheap change flow: the overview (which loads every plan) is never observed.
        verify(exactly = 0) { readRepository.observeOverview() }
    }

    @Test
    fun `PE-C a failing change trigger still lets the weather compute once`() = runTest {
        every { readRepository.observeChanges() } returns flow { throw IllegalStateException("trigger failed") }
        val p = plan(1L, listOf(entry(11L, jan(20))))

        val result = source(portOf(p)).observe().first()

        assertNotNull(result)
        assertEquals(listOf(11L), result!!.installmentRows.map { it.entryId })
    }

    // ── Fallback label (PE-C, owner-approvals 60d-2) ─────────────────────────

    @Test
    fun `PE-C a blank plan name gets the localized fallback label as the planned description`() = runTest {
        val p = plan(1L, listOf(entry(11L, jan(20))), name = "   ")

        source(portOf(p)).observe().first()

        assertEquals(FALLBACK_LABEL, assembledInputs.single().plannedExpenses.single().description)
    }

    @Test
    fun `PE-C a named plan keeps its own name and the label is resolved per computation`() = runTest {
        val p = plan(1L, listOf(entry(11L, jan(20))))
        val blank = plan(2L, listOf(entry(21L, jan(21))), name = "")

        val named = source(portOf(p)).observe().first()
        assertNotNull(named)
        assertEquals("Synthetic plan 1", assembledInputs.single().plannedExpenses.single().description)
        assembledInputs.clear()

        every { context.getString(R.string.installment_plan_fallback_label) } returnsMany listOf("first", "second")
        val src = source(portOf(blank))
        src.observe().first()
        src.observe().first()

        assertEquals(listOf("second", "first").sorted(), assembledInputs.map { it.plannedExpenses.single().description }.sorted())
    }

    @Test
    fun `a home currency failure fails closed to null so Home keeps the baseline widget`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Failed("test")
        val p = plan(1L, listOf(entry(11L, jan(20))))

        assertNull(source(portOf(p)).observe().first())
    }
}
