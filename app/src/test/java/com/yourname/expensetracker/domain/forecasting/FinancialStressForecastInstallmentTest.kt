package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.noPlansInstallmentEligibility
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
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
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.toExpenseSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

/**
 * S8b-2: [FinancialStressForecastEngine] reads installments through [InstallmentProjectionPort].
 *
 * - INV-15: only what the port projects counts (the fake port runs the REAL projection rule).
 * - INV-16: an entry is in its plan currency; it is converted through the engine's HZ01-B conversion,
 *   and an entry without a rate is excluded and counted (partial), never 0 and never summed raw.
 * - INV-29 ("global while projected", owner-approvals 53a): installment-linked history leaves the
 *   Monte Carlo baseline only while at least one entry is projected, so nothing is counted twice.
 *
 * Synthetic data only. Balance and income are 0 in every fixture, so the discretionary p50 of a
 * horizon is `-projectedBalance - recurringObligations`.
 */
class FinancialStressForecastInstallmentTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var patternsProvider: MergedRecurringPatternsProvider
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
    private lateinit var accountBalanceProvider: AccountBalanceProvider

    private val dayMs = 24L * 60L * 60L * 1000L
    private val now = millis(2026, Calendar.APRIL, 20)
    private val todayEpochDay: Long =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private var allExpenses: List<Expense> = emptyList()

    /** Currency -> rate to EUR. A currency that is not in the table has no rate. */
    private var rates: Map<String, Double> = emptyMap()

    @Before
    fun setUp() {
        expenseRepository = mockk()
        timeProvider = mockk()
        patternsProvider = mockk()
        currencySettingsRepository = mockk(relaxed = true)
        analyticsCurrencyNormalizer = mockk(relaxed = true)
        accountBalanceProvider = mockk()
        currencyConverter = mockk(relaxed = true)

        every { timeProvider.now() } returns now
        coEvery { patternsProvider.getConfirmedPatterns() } returns emptyList()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        every { currencySettingsRepository.emergencyBuffer() } returns flowOf(500.0)
        coEvery { accountBalanceProvider.currentBalance(any()) } returns 0.0
        coEvery { expenseRepository.getDepositsBetween(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } answers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            allExpenses.filter { it.date in start..end }
        }
        coEvery { analyticsCurrencyNormalizer.normalizeExpenses(any(), any()) } answers {
            val expenses = firstArg<List<Expense>>()
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = expenses.map { it.toExpenseSnapshot() },
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = expenses.size
            )
        }
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } answers {
            val amount = firstArg<Double>()
            val source = secondArg<String>()
            val target = thirdArg<String>()
            val basis = arg<com.yourname.expensetracker.domain.core.money.RateBasis>(3)
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

    private fun engine(
        port: InstallmentProjectionPort,
        eligibility: RecurringInstallmentEligibility = noPlansInstallmentEligibility()
    ) = FinancialStressForecastEngine(
        synthesisEngine = mockk(relaxed = true),
        monteCarloSimulator = mockk(relaxed = true),
        recurringPatternsProvider = patternsProvider,
        expenseRepository = expenseRepository,
        budgetRepository = mockk<BudgetRepository>(relaxed = true),
        timeProvider = timeProvider,
        analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
        currencySettingsRepository = currencySettingsRepository,
        multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true),
        recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
        recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true),
        currencyConverter = currencyConverter,
        accountBalanceProvider = accountBalanceProvider,
        databaseReadBarrier = mockk<DatabaseReadBarrier>(relaxed = true),
        installmentProjectionPort = port,
        recurringInstallmentEligibility = eligibility
    )

    private fun entry(
        id: Long,
        dueInDays: Long,
        status: InstallmentEntryStatus = InstallmentEntryStatus.SCHEDULED,
        minor: Long = 5_000L
    ) = ProjectionEntryInput(
        entryId = id,
        seq = id.toInt(),
        kind = InstallmentEntryKind.REGULAR,
        status = status,
        dueEpochDay = todayEpochDay + dueInDays,
        scheduledMinor = minor,
        recordGeneration = 0
    )

    private fun plan(
        id: Long,
        entries: List<ProjectionEntryInput>,
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
        currency: InstallmentCurrency = InstallmentCurrency.EUR
    ) = ProjectionPlanInput(id, "plan-uuid-$id", "Synthetic plan $id", currency, status, entries)

    private fun portOf(vararg plans: ProjectionPlanInput) = fakeInstallmentProjectionPort(plans.toList(), todayEpochDay)

    private fun eligibilityLinking(
        vararg linkedExpenseIds: Long,
        reader: InstallmentEntryLinkReader = mockk()
    ): RecurringInstallmentEligibility {
        coEvery { reader.linkedExpenseIds() } returns linkedExpenseIds.toSet()
        coEvery { reader.planPurchaseExpenseIds() } returns emptySet()
        return RecurringInstallmentEligibility(dagger.Lazy { reader })
    }

    private fun StressForecastResult.horizon(days: Int) = horizons.first { it.daysAhead == days }

    /** Discretionary Monte Carlo p50 of a horizon (balance and income are 0 in these fixtures). */
    private fun StressHorizon.discretionaryP50() = -projectedBalance - recurringObligations

    private fun purchase(id: Long, amount: Double, merchant: String, daysAgo: Long) = Expense(
        id = id,
        amount = amount,
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = now - daysAgo * dayMs,
        categoryId = 1L
    )

    // ── Projected entries become known outflows ───────────────────────────────

    @Test
    fun `projected installment entries are known outflows per horizon and later ones stay out`() = runTest {
        val result = engine(
            portOf(plan(1L, listOf(entry(1L, 5), entry(2L, 35), entry(3L, 65), entry(4L, 95))))
        ).computeStressForecast()

        assertNull(result.failure)
        assertApproxEquals(50.0, result.horizon(30).recurringObligations, 0.0001)
        assertApproxEquals(100.0, result.horizon(60).recurringObligations, 0.0001)
        assertApproxEquals(150.0, result.horizon(90).recurringObligations, 0.0001)
        assertFalse(result.isPartial)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun `AE-7 after Stop nothing is projected and the forecast equals the no-plan forecast`() = runTest {
        // Three payments in the past, the rest CANCELLED(PLAN_STOPPED), plan CANCELLED.
        val stopped = plan(
            1L,
            listOf(
                entry(1L, -90, InstallmentEntryStatus.PAID),
                entry(2L, -60, InstallmentEntryStatus.PAID),
                entry(3L, -30, InstallmentEntryStatus.PAID),
                entry(4L, 5, InstallmentEntryStatus.CANCELLED),
                entry(5L, 35, InstallmentEntryStatus.CANCELLED),
                entry(6L, 65, InstallmentEntryStatus.CANCELLED)
            ),
            status = InstallmentPlanStatus.CANCELLED
        )

        val withStoppedPlan = engine(portOf(stopped)).computeStressForecast()
        val noPlans = engine(emptyInstallmentProjectionPort()).computeStressForecast()

        for (days in listOf(30, 60, 90)) {
            assertApproxEquals(0.0, withStoppedPlan.horizon(days).recurringObligations, 0.0001)
            assertApproxEquals(
                noPlans.horizon(days).projectedBalance,
                withStoppedPlan.horizon(days).projectedBalance,
                0.0001
            )
        }
    }

    @Test
    fun `AE-11 after completion no 50 outflow appears in any horizon`() = runTest {
        val completed = plan(
            1L,
            (1L..12L).map { entry(it, -400 + it * 30, InstallmentEntryStatus.PAID) },
            status = InstallmentPlanStatus.COMPLETED
        )

        val result = engine(portOf(completed)).computeStressForecast()

        for (days in listOf(30, 60, 90)) {
            assertApproxEquals(0.0, result.horizon(days).recurringObligations, 0.0001)
        }
    }

    @Test
    fun `INV-15 bounds - overdue paid before tracking and terminal entries are not projected`() = runTest {
        val active = plan(
            1L,
            listOf(
                entry(1L, -3),
                entry(2L, 2, InstallmentEntryStatus.PAID_BEFORE_TRACKING),
                entry(3L, 3, InstallmentEntryStatus.SKIPPED),
                entry(4L, 4, InstallmentEntryStatus.PAID),
                entry(5L, 5)
            )
        )
        val refunded = plan(2L, listOf(entry(6L, 6)), status = InstallmentPlanStatus.REFUNDED)
        val paidOff = plan(3L, listOf(entry(7L, 7)), status = InstallmentPlanStatus.PAID_OFF)

        val result = engine(portOf(active, refunded, paidOff)).computeStressForecast()

        // Only the single future SCHEDULED entry of the ACTIVE plan counts.
        assertApproxEquals(50.0, result.horizon(30).recurringObligations, 0.0001)
        assertApproxEquals(50.0, result.horizon(90).recurringObligations, 0.0001)
    }

    // ── Currency (INV-16) ─────────────────────────────────────────────────────

    @Test
    fun `INV-16 USD entry with a rate is converted to the display currency`() = runTest {
        rates = mapOf("USD" to 0.5)
        val usdPlan = plan(1L, listOf(entry(1L, 5, minor = 10_000L)), currency = InstallmentCurrency.USD)

        val result = engine(portOf(usdPlan)).computeStressForecast()

        assertApproxEquals(50.0, result.horizon(30).recurringObligations, 0.0001)
        assertFalse(result.horizon(30).recurringObligationsPartial)
        assertFalse(result.isPartial)
    }

    @Test
    fun `INV-16 USD entry without a rate is excluded and counted and never summed as EUR`() = runTest {
        val usdPlan = plan(1L, listOf(entry(1L, 5, minor = 10_000L)), currency = InstallmentCurrency.USD)
        val eurPlan = plan(2L, listOf(entry(2L, 6)))

        val result = engine(portOf(usdPlan, eurPlan)).computeStressForecast()

        val h30 = result.horizon(30)
        // 50.00 from the EUR plan only. 100.00 USD is not added raw and is not silently 0.
        assertApproxEquals(50.0, h30.recurringObligations, 0.0001)
        assertTrue(h30.recurringObligationsPartial)
        assertEquals(1, h30.recurringExcludedConversionCount)
        assertTrue(result.isPartial)
        assertEquals(1, result.excludedCount)
    }

    // ── No double count (INV-29, global while projected) ──────────────────────

    @Test
    fun `no double count - linked history leaves the baseline and the projection is counted once`() = runTest {
        val linked = listOf(
            purchase(101L, 50.0, "Lender", daysAgo = 50),
            purchase(102L, 50.0, "Lender", daysAgo = 20)
        )
        val ordinary = listOf(
            purchase(201L, 10.0, "Shop", daysAgo = 45),
            purchase(202L, 10.0, "Shop", daysAgo = 15)
        )

        // Reference: the same ordinary history and no installments at all.
        allExpenses = ordinary
        val reference = engine(emptyInstallmentProjectionPort()).computeStressForecast()

        // Unfiltered control: the linked payments stay in the baseline when nothing is projected.
        allExpenses = linked + ordinary
        val unfiltered = engine(emptyInstallmentProjectionPort()).computeStressForecast()

        // Aware: linked history is installment-linked and one entry is projected.
        val aware = engine(
            portOf(plan(1L, listOf(entry(1L, 5)))),
            eligibilityLinking(101L, 102L)
        ).computeStressForecast()

        for (days in listOf(30, 60, 90)) {
            assertApproxEquals(
                reference.horizon(days).discretionaryP50(),
                aware.horizon(days).discretionaryP50(),
                0.0001
            )
        }
        // Non-vacuity: without the exclusion the baseline would carry the linked 50.00 payments.
        assertTrue(
            unfiltered.horizon(30).discretionaryP50() > reference.horizon(30).discretionaryP50() + 1.0
        )
        // The projected 50.00 is counted exactly once, as a known outflow.
        assertApproxEquals(50.0, aware.horizon(30).recurringObligations, 0.0001)
        assertApproxEquals(50.0, aware.horizon(90).recurringObligations, 0.0001)
    }

    @Test
    fun `exclusion is global while projected - a projection only in the 90 day horizon still cleans 30 days`() = runTest {
        allExpenses = listOf(
            purchase(101L, 50.0, "Lender", daysAgo = 50),
            purchase(102L, 50.0, "Lender", daysAgo = 20),
            purchase(201L, 10.0, "Shop", daysAgo = 45),
            purchase(202L, 10.0, "Shop", daysAgo = 15)
        )
        val aware = engine(
            portOf(plan(1L, listOf(entry(1L, 65)))),
            eligibilityLinking(101L, 102L)
        ).computeStressForecast()
        allExpenses = listOf(
            purchase(201L, 10.0, "Shop", daysAgo = 45),
            purchase(202L, 10.0, "Shop", daysAgo = 15)
        )
        val reference = engine(emptyInstallmentProjectionPort()).computeStressForecast()

        assertApproxEquals(0.0, aware.horizon(30).recurringObligations, 0.0001)
        assertApproxEquals(50.0, aware.horizon(90).recurringObligations, 0.0001)
        assertApproxEquals(
            reference.horizon(30).discretionaryP50(),
            aware.horizon(30).discretionaryP50(),
            0.0001
        )
    }

    @Test
    fun `fully linked history with a projected entry gives a zero discretionary baseline and not the synthetic fallback`() = runTest {
        // Every purchase in the 60 day window is installment-linked and one entry is projected:
        // the history leaves the baseline, but it is still "history", so the synthetic
        // 20 per day fallback (the no-history branch) must not kick in.
        allExpenses = listOf(
            purchase(101L, 50.0, "Lender", daysAgo = 50),
            purchase(102L, 50.0, "Lender", daysAgo = 20)
        )
        val aware = engine(
            portOf(plan(1L, listOf(entry(1L, 5)))),
            eligibilityLinking(101L, 102L)
        ).computeStressForecast()

        // Non-vacuity: with no history at all the engine does use the synthetic baseline.
        allExpenses = emptyList()
        val noHistory = engine(emptyInstallmentProjectionPort()).computeStressForecast()

        for (days in listOf(30, 60, 90)) {
            assertApproxEquals(0.0, aware.horizon(days).discretionaryP50(), 0.0001)
        }
        assertTrue(noHistory.horizon(30).discretionaryP50() > 500.0)
        // The projected 50.00 is the only outflow.
        assertApproxEquals(50.0, aware.horizon(30).recurringObligations, 0.0001)
    }

    @Test
    fun `half open window - an entry due today counts and an entry due exactly at the horizon end does not`() = runTest {
        // Midnight now makes the horizon end an exact day boundary: [today, today + N days).
        every { timeProvider.now() } returns millis(2026, Calendar.APRIL, 20, hourOfDay = 0)

        val result = engine(
            portOf(plan(1L, listOf(entry(1L, 0), entry(2L, 30))))
        ).computeStressForecast()

        assertApproxEquals(50.0, result.horizon(30).recurringObligations, 0.0001) // +0 only
        assertApproxEquals(100.0, result.horizon(60).recurringObligations, 0.0001) // +30 enters here
        assertApproxEquals(100.0, result.horizon(90).recurringObligations, 0.0001)
    }

    @Test
    fun `baseline is unchanged when the port is empty and the exclusion owner is not consulted`() = runTest {
        allExpenses = listOf(
            purchase(101L, 50.0, "Lender", daysAgo = 50),
            purchase(102L, 50.0, "Lender", daysAgo = 20),
            purchase(201L, 10.0, "Shop", daysAgo = 45)
        )
        val reader = mockk<InstallmentEntryLinkReader>()
        val withLinkedIds = engine(emptyInstallmentProjectionPort(), eligibilityLinking(101L, 102L, reader = reader))
            .computeStressForecast()
        val plain = engine(emptyInstallmentProjectionPort()).computeStressForecast()

        for (days in listOf(30, 60, 90)) {
            assertApproxEquals(plain.horizon(days).projectedBalance, withLinkedIds.horizon(days).projectedBalance, 0.0001)
            assertApproxEquals(
                plain.horizon(days).probabilityOfCrunch,
                withLinkedIds.horizon(days).probabilityOfCrunch,
                0.0001
            )
            assertApproxEquals(0.0, plain.horizon(days).recurringObligations, 0.0001)
        }
        coVerify(exactly = 0) { reader.linkedExpenseIds() }
        coVerify(exactly = 0) { reader.planPurchaseExpenseIds() }
    }

    // ── Failure behaviour ─────────────────────────────────────────────────────

    @Test
    fun `a port failure makes the forecast unavailable instead of a wrong figure`() = runTest {
        val failingPort = object : InstallmentProjectionPort {
            override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> =
                throw IllegalStateException("port down")
        }

        val result = engine(failingPort).computeStressForecast()

        assertEquals(StressForecastFailure.CALCULATION_UNAVAILABLE, result.failure)
        assertTrue(result.horizons.isEmpty())
        assertTrue(result.isPartial)
    }

    @Test
    fun `cancellation from the port propagates unchanged`() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("port cancellation")
        val cancellingPort = object : InstallmentProjectionPort {
            override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> =
                throw cancellation
        }

        assertSame(cancellation, runCatching { engine(cancellingPort).computeStressForecast() }.exceptionOrNull())
    }

    private fun millis(year: Int, month: Int, day: Int, hourOfDay: Int = 12): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
