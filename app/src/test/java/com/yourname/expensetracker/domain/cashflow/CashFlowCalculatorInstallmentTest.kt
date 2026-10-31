package com.yourname.expensetracker.domain.cashflow

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.MoneyNormalizationEngine
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectedEntry
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.projection.ProjectionEntryInput
import com.yourname.expensetracker.domain.installment.projection.ProjectionPlanInput
import com.yourname.expensetracker.domain.installment.projection.emptyInstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.projection.fakeInstallmentProjectionPort
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * S8b-2: [CashFlowCalculator.calculateDailyCashFlow] reads installments through
 * [InstallmentProjectionPort]; [CashFlowCalculator.getUpcomingBills] does not (the health score and
 * SmartSavingsEngine read it, OD-12).
 *
 * - INV-15: the fake port runs the REAL projection rule, so the bounds under test are the product's.
 * - INV-16: an entry is in its plan currency and goes through the calculator's convertOutcome. An
 *   entry without a rate is omitted and raises isPartial; it is never 0 and never summed as EUR.
 *
 * Synthetic data only. Home currency is EUR and the starting balance is 1000.00 EUR.
 */
class CashFlowCalculatorInstallmentTest : AnalyticsEngineTestBase() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var patternsProvider: MergedRecurringPatternsProvider
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var normalizationEngine: MoneyNormalizationEngine

    /** Currency -> rate to EUR; a currency that is not in the table has no rate. */
    private var rates: Map<String, Double> = emptyMap()

    private val starting = MoneyAmount(1000.0, CurrencyCode("EUR"))
    private val today = LocalDate.parse("2026-04-01")

    @Before
    override fun setUp() {
        super.setUp()
        expenseRepository = mockk(relaxed = true)
        patternsProvider = mockk(relaxed = true)
        currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true).also {
            every { it.homeCurrency() } returns flowOf("EUR")
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }
        normalizationEngine = mockk(relaxed = true)
        coEvery { normalizationEngine.aggregateExpenses(any(), CurrencyCode.EUR, any(), any()) } answers {
            val expenses = firstArg<List<Expense>>()
            MoneyAggregate(
                displayAmount = expenses.sumOf { it.effectiveAmount },
                displayCurrency = CurrencyCode(secondArg<String>()),
                sourceBuckets = emptyList(),
                conversionFailures = emptyList()
            )
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
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun calculator(port: InstallmentProjectionPort) = CashFlowCalculator(
        expenseRepository = expenseRepository,
        recurringPatternsProvider = patternsProvider,
        timeProvider = timeProvider,
        recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
        recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true),
        analyticsCurrencyNormalizer = mockk(relaxed = true),
        currencySettingsRepository = currencySettingsRepository,
        currencyConverter = currencyConverter,
        databaseReadBarrier = mockk(relaxed = true),
        normalizationEngine = normalizationEngine,
        installmentProjectionPort = port
    )

    private fun ms(date: LocalDate): Long = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun ms(date: String): Long = ms(LocalDate.parse(date))

    private fun entry(
        id: Long,
        due: String,
        status: InstallmentEntryStatus = InstallmentEntryStatus.SCHEDULED,
        minor: Long = 5_000L
    ) = ProjectionEntryInput(
        entryId = id,
        seq = id.toInt(),
        kind = InstallmentEntryKind.REGULAR,
        status = status,
        dueEpochDay = LocalDate.parse(due).toEpochDay(),
        scheduledMinor = minor,
        recordGeneration = 0
    )

    private fun plan(
        id: Long,
        entries: List<ProjectionEntryInput>,
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
        currency: InstallmentCurrency = InstallmentCurrency.EUR
    ) = ProjectionPlanInput(id, "plan-uuid-$id", "Synthetic plan $id", currency, status, entries)

    private fun portOf(vararg plans: ProjectionPlanInput, todayDate: LocalDate = today) =
        fakeInstallmentProjectionPort(plans.toList(), todayDate.toEpochDay())

    private val ae7Today = LocalDate.parse("2027-07-05")

    /** AE-7 spec entries: 1-3 PAID (2027-04/05/06-10), 4-12 in [remaining] (2027-07-10 .. 2028-03-10). */
    private fun ae7Entries(remaining: InstallmentEntryStatus): List<ProjectionEntryInput> =
        (0L..11L).map { index ->
            val due = LocalDate.parse("2027-04-10").plusMonths(index)
            entry(index + 1L, due.toString(), if (index < 3) InstallmentEntryStatus.PAID else remaining)
        }

    private suspend fun runCashFlow(
        port: InstallmentProjectionPort,
        from: String = "2026-04-01",
        to: String = "2026-04-11"
    ): List<DailyCashFlow> =
        calculator(port).calculateDailyCashFlow(Date(ms(from)), Date(ms(to)), starting)

    // ── Projected entries become dated outflows ───────────────────────────────

    @Test
    fun `a projected EUR entry is an outflow on its due day and later balances carry it`() = runTest {
        val result = runCashFlow(portOf(plan(1L, listOf(entry(1L, "2026-04-04")))))

        assertEquals(10, result.size)
        assertApproxEquals(1000.0, result[2].endingBalance)
        assertApproxEquals(950.0, result[3].endingBalance)
        assertApproxEquals(950.0, result[9].endingBalance)
        assertEquals(listOf(1L), result[3].projectedInstallments.map { it.entryId })
        assertApproxEquals(50.0, result[3].recurringAggregate!!.displayAmount)
        assertTrue(result.filterIndexed { index, _ -> index != 3 }.all { it.projectedInstallments.isEmpty() })
        assertTrue(result.none { it.isPartial })
        assertTrue(result.all { it.predictedRecurring.isEmpty() })
    }

    @Test
    fun `only entries inside the requested range count and the end day is exclusive`() = runTest {
        val p = plan(
            1L,
            listOf(
                entry(1L, "2026-04-02"), // before the range
                entry(2L, "2026-04-03"), // first day of the range
                entry(3L, "2026-04-05"), // last day of the range
                entry(4L, "2026-04-06"), // the exclusive end day
                entry(5L, "2026-04-08")
            )
        )

        val result = runCashFlow(portOf(p), from = "2026-04-03", to = "2026-04-06")

        assertEquals(3, result.size)
        assertEquals(listOf(2L), result[0].projectedInstallments.map { it.entryId })
        assertTrue(result[1].projectedInstallments.isEmpty())
        assertEquals(listOf(3L), result[2].projectedInstallments.map { it.entryId })
        assertApproxEquals(900.0, result[2].endingBalance)
    }

    @Test
    fun `AE-7 after Stop nothing is projected`() = runTest {
        // Spec fixture: 12 x 50, three PAID, stopped on 2027-07-05, the other nine CANCELLED.
        val stopped = plan(
            1L,
            ae7Entries(remaining = InstallmentEntryStatus.CANCELLED),
            status = InstallmentPlanStatus.CANCELLED
        )

        val result = runCashFlow(
            portOf(stopped, todayDate = ae7Today),
            from = "2027-07-05",
            to = "2028-04-01"
        )

        assertEquals(ChronoUnit.DAYS.between(ae7Today, LocalDate.parse("2028-04-01")).toInt(), result.size)
        assertTrue(result.all { it.projectedInstallments.isEmpty() })
        assertTrue(result.all { it.endingBalance == 1000.0 })
        assertTrue(result.none { it.isPartial })
    }

    @Test
    fun `AE-7 control - the same plan still ACTIVE with the remaining entries SCHEDULED yields nine outflows of 50`() = runTest {
        val active = plan(
            1L,
            ae7Entries(remaining = InstallmentEntryStatus.SCHEDULED),
            status = InstallmentPlanStatus.ACTIVE
        )

        val result = runCashFlow(
            portOf(active, todayDate = ae7Today),
            from = "2027-07-05",
            to = "2028-04-01"
        )

        val projected = result.flatMap { it.projectedInstallments }
        assertEquals((4L..12L).toList(), projected.map { it.entryId })
        assertTrue(projected.all { it.amountMinor == 5_000L })
        assertApproxEquals(550.0, result.last().endingBalance)
        assertTrue(result.none { it.isPartial })
    }

    @Test
    fun `AE-11 after completion there is no 50 outflow from 2028-04`() = runTest {
        val months = (0L..11L).map { LocalDate.parse("2027-04-10").plusMonths(it) }
        val completed = plan(
            1L,
            months.mapIndexed { index, due -> entry(index + 1L, due.toString(), InstallmentEntryStatus.PAID) },
            status = InstallmentPlanStatus.COMPLETED
        )

        val result = runCashFlow(
            portOf(completed, todayDate = LocalDate.parse("2028-03-11")),
            from = "2028-04-01",
            to = "2028-05-01"
        )

        assertEquals(30, result.size)
        assertTrue(result.all { it.projectedInstallments.isEmpty() })
        assertTrue(result.all { it.endingBalance == 1000.0 })
    }

    @Test
    fun `INV-15 bounds - overdue paid before tracking and terminal entries are not projected`() = runTest {
        val active = plan(
            1L,
            listOf(
                entry(1L, "2026-03-30"), // overdue SCHEDULED: due before today
                entry(2L, "2026-04-02", InstallmentEntryStatus.PAID_BEFORE_TRACKING),
                entry(3L, "2026-04-02", InstallmentEntryStatus.PAID),
                entry(4L, "2026-04-02", InstallmentEntryStatus.SKIPPED),
                entry(5L, "2026-04-03") // the only projectable entry
            )
        )
        val stopped = plan(2L, listOf(entry(6L, "2026-04-04")), status = InstallmentPlanStatus.CANCELLED)
        val refunded = plan(3L, listOf(entry(7L, "2026-04-04")), status = InstallmentPlanStatus.REFUNDED)
        val paidOff = plan(4L, listOf(entry(8L, "2026-04-04")), status = InstallmentPlanStatus.PAID_OFF)

        val result = runCashFlow(portOf(active, stopped, refunded, paidOff), from = "2026-03-28", to = "2026-04-07")

        assertEquals(listOf(5L), result.flatMap { it.projectedInstallments }.map { it.entryId })
        assertApproxEquals(950.0, result.last().endingBalance)
    }

    // ── Currency (INV-16) ─────────────────────────────────────────────────────

    @Test
    fun `INV-16 a USD entry with a rate is converted to the home currency`() = runTest {
        rates = mapOf("USD" to 0.5)
        val usd = plan(1L, listOf(entry(1L, "2026-04-04", minor = 10_000L)), currency = InstallmentCurrency.USD)

        val result = runCashFlow(portOf(usd))

        assertApproxEquals(950.0, result[3].endingBalance)
        assertApproxEquals(50.0, result[3].recurringAggregate!!.displayAmount)
        assertEquals(InstallmentCurrency.USD, result[3].projectedInstallments.single().currency)
        assertEquals(10_000L, result[3].projectedInstallments.single().amountMinor)
        assertTrue(result.none { it.isPartial })
    }

    @Test
    fun `INV-16 a USD entry without a rate is omitted and flagged partial and never summed as EUR`() = runTest {
        val usd = plan(1L, listOf(entry(1L, "2026-04-04", minor = 10_000L)), currency = InstallmentCurrency.USD)
        val eur = plan(2L, listOf(entry(2L, "2026-04-04", minor = 2_000L)))

        val result = runCashFlow(portOf(usd, eur))

        // 20.00 from the EUR plan only. The 100.00 USD is neither added raw nor shown as zero.
        assertApproxEquals(980.0, result[3].endingBalance)
        assertEquals(listOf(2L), result[3].projectedInstallments.map { it.entryId })
        assertTrue(result[3].isPartial)
        assertEquals(1, result[3].failedConversionCount)
        // Only the affected day is partial.
        assertTrue(result.filterIndexed { index, _ -> index != 3 }.none { it.isPartial })
    }

    // ── getUpcomingBills stays installment-free (OD-12) ───────────────────────

    @Test
    fun `getUpcomingBills is unchanged with a non-empty port and never reads it`() = runTest {
        every { timeProvider.now() } returns ms("2026-04-01")
        val gym = RecurringPattern(
            merchantName = "Gym",
            averageAmount = 20.0,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = ms("2026-04-05"),
            confidence = 0.9f,
            previousDates = emptyList()
        )
        coEvery { patternsProvider.getConfirmedPatterns() } returns listOf(gym)
        var portReads = 0
        val countingPort = object : InstallmentProjectionPort {
            private val delegate = portOf(plan(1L, listOf(entry(1L, "2026-04-03"), entry(2L, "2026-04-04"))))
            override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> {
                portReads++
                return delegate.upcomingEntries(horizonEndEpochDay)
            }
        }

        val withPlans = calculator(countingPort).getUpcomingBills(daysAhead = 10)
        val withoutPlans = calculator(emptyInstallmentProjectionPort()).getUpcomingBills(daysAhead = 10)

        assertEquals(withoutPlans, withPlans)
        assertEquals(listOf("Gym"), withPlans.map { it.merchantName })
        assertEquals(0, portReads)
    }

    // ── Port behaviour ────────────────────────────────────────────────────────

    @Test
    fun `an empty range does not read the port`() = runTest {
        var portReads = 0
        val countingPort = object : InstallmentProjectionPort {
            override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> {
                portReads++
                return emptyList()
            }
        }

        val result = runCashFlow(countingPort, from = "2026-04-05", to = "2026-04-05")

        assertTrue(result.isEmpty())
        assertEquals(0, portReads)
    }

    @Test
    fun `an empty port leaves the cash flow exactly as before`() = runTest {
        val result = runCashFlow(emptyInstallmentProjectionPort())

        assertEquals(10, result.size)
        assertTrue(result.all { it.endingBalance == 1000.0 && it.projectedInstallments.isEmpty() && !it.isPartial })
    }

    @Test
    fun `cancellation from the port propagates unchanged`() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("port cancellation")
        val cancellingPort = object : InstallmentProjectionPort {
            override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> =
                throw cancellation
        }

        assertSame(cancellation, runCatching { runCashFlow(cancellingPort) }.exceptionOrNull())
    }
}
