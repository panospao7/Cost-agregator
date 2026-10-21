package com.yourname.expensetracker.domain.carbon

import com.google.common.truth.Truth.assertWithMessage
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * CarbonFootprintCalculator tests.
 *
 * All expected values are hand-computed from the production emission factors
 * (merchant patterns first, then category factors, DEFAULT = 0.25) and the pinned clock
 * [FIXED_NOW]. The default report window is 30 days ending at [FIXED_NOW].
 *
 * Tolerance 1e-9 is used for doubles because factors such as 2.3 are not exactly
 * representable in binary floating point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarbonFootprintCalculatorTest {

    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>()
    private lateinit var calculator: CarbonFootprintCalculator

    @Before
    fun setup() {
        every { timeProvider.now() } returns FIXED_NOW
        // Non-relaxed mocks: homeCurrency() throws -> production treats home currency as
        // unavailable (runCatching -> null) and falls back to effectiveAmount per expense.
        calculator = CarbonFootprintCalculator(
            expenseDao,
            timeProvider,
            currencySettingsRepository = mockk(),
            analyticsCurrencyNormalizer = mockk()
        )
    }

    // ── Emission factors (merchant pattern -> category -> DEFAULT) ───────────

    private data class FactorRow(
        val label: String,
        val merchant: String,
        val amount: Double,
        val expectedTotalKg: Double,
        val expectedCategory: String
    )

    @Test
    fun `emission factor table - exact kg per merchant and detected category`() = runTest {
        val rows = listOf(
            FactorRow("SHELL fuel 2.3", "SHELL", 50.0, 115.0, "FUEL"),
            FactorRow("PLAISIO electronics 0.80", "PLAISIO", 100.0, 80.0, "ELECTRONICS"),
            FactorRow("EVEREST restaurant 0.35", "EVEREST", 30.0, 10.5, "RESTAURANT"),
            FactorRow("SKLAVENITIS grocery 0.25", "SKLAVENITIS", 80.0, 20.0, "GROCERY"),
            FactorRow("AEGEAN flight 0.50", "AEGEAN", 200.0, 100.0, "FLIGHT"),
            // Merchant pattern LIDL (0.20) takes precedence over the GROCERY category factor (0.25).
            FactorRow("LIDL pattern 0.20 beats GROCERY 0.25", "LIDL", 100.0, 20.0, "GROCERY"),
            // No pattern and no category keyword -> DEFAULT factor 0.25.
            FactorRow("UNKNOWN STORE falls back to DEFAULT 0.25", "UNKNOWN STORE", 100.0, 25.0, "DEFAULT"),
            // Greek name: non-A-Z stripped so no merchant pattern; category keyword -> GROCERY 0.25.
            FactorRow("Greek ΣΚΛΑΒΕΝΙΤΗΣ via category GROCERY 0.25", "ΣΚΛΑΒΕΝΙΤΗΣ", 50.0, 12.5, "GROCERY")
        )
        assertWithMessage("factor table size guard").that(rows.size).isEqualTo(8)

        for (row in rows) {
            coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
                listOf(createExpense(row.merchant, row.amount, TransactionType.PURCHASE))

            val report = calculator.calculateCarbonFootprint()

            assertWithMessage("[${row.label}] total emissions kg")
                .that(report.totalEmissionsKg).isWithin(TOL).of(row.expectedTotalKg)
            assertWithMessage("[${row.label}] single category breakdown entry")
                .that(report.categoryBreakdown.size).isEqualTo(1)
            val category = report.categoryBreakdown.single()
            assertWithMessage("[${row.label}] detected category")
                .that(category.category).isEqualTo(row.expectedCategory)
            assertWithMessage("[${row.label}] category emissions kg")
                .that(category.emissionsKg).isWithin(TOL).of(row.expectedTotalKg)
            assertWithMessage("[${row.label}] category percentage")
                .that(category.percentage).isEqualTo(100)
            assertWithMessage("[${row.label}] category transaction count")
                .that(category.transactionCount).isEqualTo(1)
        }
    }

    // ── Report shape for a mixed basket ───────────────────────────────────────

    @Test
    fun `mixed basket report has exact totals period score offset and benchmarks`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns createMockExpenses()

        val report = calculator.calculateCarbonFootprint()

        // 115.0 (SHELL) + 20.0 + 10.5 + 80.0 + 100.0 = 325.5 kg
        assertWithMessage("total kg").that(report.totalEmissionsKg).isWithin(TOL).of(325.5)
        assertWithMessage("period days (default 30-day window)").that(report.periodDays).isEqualTo(30)
        // 325.5 / 30 = 10.85
        assertWithMessage("daily average kg").that(report.dailyAverageKg).isWithin(TOL).of(10.85)
        // 100 - toInt(10.85 * 5 = 54.25) = 46, + toInt(GROCERY 20.0/325.5 * 20 = 1.23) = 47
        assertWithMessage("sustainability score").that(report.sustainabilityScore).isEqualTo(47)
        // 325.5 / 1000 * 22.0 = 7.161
        assertWithMessage("offset cost").that(report.offsetCost).isWithin(TOL).of(7.161)
        // round((10.85 / 12 - 1) * 100) = round(-9.58) = -10
        assertWithMessage("comparison to global average").that(report.comparisonToGlobalAverage).isEqualTo(-10)
        // round((10.85 - 4) / 4 * 100) = round(171.25) = 171
        assertWithMessage("paris agreement gap").that(report.parisAgreementGap).isEqualTo(171)
    }

    @Test
    fun `mixed basket category and merchant breakdown are exact sorted and sum to total`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns createMockExpenses()

        val report = calculator.calculateCarbonFootprint()

        // Percentages of 325.5: 35.33 -> 35, 30.72 -> 31, 24.58 -> 25, 6.14 -> 6, 3.23 -> 3
        val expectedCategories = listOf(
            Triple("FUEL", 115.0, 35),
            Triple("FLIGHT", 100.0, 31),
            Triple("ELECTRONICS", 80.0, 25),
            Triple("GROCERY", 20.0, 6),
            Triple("RESTAURANT", 10.5, 3)
        )
        assertWithMessage("category breakdown size").that(report.categoryBreakdown.size).isEqualTo(5)
        expectedCategories.forEachIndexed { i, (name, kg, pct) ->
            val actual = report.categoryBreakdown[i]
            assertWithMessage("[category #$i $name] name").that(actual.category).isEqualTo(name)
            assertWithMessage("[category #$i $name] kg").that(actual.emissionsKg).isWithin(TOL).of(kg)
            assertWithMessage("[category #$i $name] percentage").that(actual.percentage).isEqualTo(pct)
            assertWithMessage("[category #$i $name] transaction count").that(actual.transactionCount).isEqualTo(1)
        }
        assertWithMessage("category kg sum equals total")
            .that(report.categoryBreakdown.sumOf { it.emissionsKg }).isWithin(TOL).of(325.5)
        assertWithMessage("category percentages sum")
            .that(report.categoryBreakdown.sumOf { it.percentage }).isEqualTo(100)

        val expectedMerchants = listOf(
            Triple("SHELL", 115.0, 35),
            Triple("AEGEAN", 100.0, 31),
            Triple("PLAISIO", 80.0, 25),
            Triple("SKLAVENITIS", 20.0, 6),
            Triple("EVEREST", 10.5, 3)
        )
        assertWithMessage("merchant breakdown size").that(report.merchantBreakdown.size).isEqualTo(5)
        expectedMerchants.forEachIndexed { i, (name, kg, pct) ->
            val actual = report.merchantBreakdown[i]
            assertWithMessage("[merchant #$i $name] name").that(actual.merchant).isEqualTo(name)
            assertWithMessage("[merchant #$i $name] kg").that(actual.emissionsKg).isWithin(TOL).of(kg)
            assertWithMessage("[merchant #$i $name] percentage").that(actual.percentage).isEqualTo(pct)
        }
    }

    // ── Period / daily average ────────────────────────────────────────────────

    private data class PeriodRow(
        val label: String,
        val startDate: Long?,
        val endDate: Long?,
        val expectedPeriodDays: Int,
        val expectedDailyKg: Double
    )

    @Test
    fun `daily average table - 300 euro Test merchant at DEFAULT factor gives 75 kg total`() = runTest {
        val rows = listOf(
            PeriodRow("default window from pinned clock", null, null, 30, 2.5),
            PeriodRow("explicit 30 day window", 1_697_408_000_000L, 1_700_000_000_000L, 30, 2.5),
            // 12 hours < 1 day -> period clamped to 1 day, daily average equals total.
            PeriodRow("sub-day window clamps to 1 day", 1_699_956_800_000L, 1_700_000_000_000L, 1, 75.0)
        )
        assertWithMessage("period table size guard").that(rows.size).isEqualTo(3)

        for (row in rows) {
            coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
                listOf(createExpense("Test", 300.0, TransactionType.PURCHASE))

            val report = calculator.calculateCarbonFootprint(row.startDate, row.endDate)

            assertWithMessage("[${row.label}] total kg").that(report.totalEmissionsKg).isWithin(TOL).of(75.0)
            assertWithMessage("[${row.label}] period days").that(report.periodDays).isEqualTo(row.expectedPeriodDays)
            assertWithMessage("[${row.label}] daily average kg")
                .that(report.dailyAverageKg).isWithin(TOL).of(row.expectedDailyKg)
        }
    }

    @Test
    fun `single 100 euro DEFAULT purchase has exact offset benchmarks and score`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            listOf(createExpense("Test", 100.0, TransactionType.PURCHASE))

        val report = calculator.calculateCarbonFootprint()

        // total 25.0 kg over 30 days -> 0.8333 kg/day
        assertWithMessage("total kg").that(report.totalEmissionsKg).isWithin(TOL).of(25.0)
        assertWithMessage("daily average kg").that(report.dailyAverageKg).isWithin(TOL).of(0.833333333333)
        // 25.0 / 1000 * 22.0 = 0.55
        assertWithMessage("offset cost").that(report.offsetCost).isWithin(TOL).of(0.55)
        // national average 10.0 kg/day: round((0.0833 - 1) * 100) = round(-91.67) = -92
        assertWithMessage("comparison to national average").that(report.comparisonToNationalAverage).isEqualTo(-92)
        // global average 12.0 kg/day: round((0.0694 - 1) * 100) = round(-93.06) = -93
        assertWithMessage("comparison to global average").that(report.comparisonToGlobalAverage).isEqualTo(-93)
        // Paris target 4.0 kg/day: round((0.8333 - 4) / 4 * 100) = round(-79.17) = -79
        assertWithMessage("paris agreement gap").that(report.parisAgreementGap).isEqualTo(-79)
        // 100 - toInt(0.8333 * 5 = 4.17) = 96; DEFAULT is not a sustainable category -> no bonus
        assertWithMessage("sustainability score").that(report.sustainabilityScore).isEqualTo(96)
    }

    // ── Recommendations / alternatives / monthly trend ────────────────────────

    @Test
    fun `fuel heavy basket yields fuel recommendation then offset recommendation`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            listOf(createExpense("SHELL", 100.0, TransactionType.PURCHASE))

        val report = calculator.calculateCarbonFootprint()

        // FUEL = 100 * 2.3 = 230.0 kg
        assertWithMessage("recommendation count (fuel + offset)").that(report.recommendations.size).isEqualTo(2)
        val fuel = report.recommendations[0]
        assertWithMessage("fuel rec category").that(fuel.category).isEqualTo("Transportation")
        assertWithMessage("fuel rec title").that(fuel.title).isEqualTo(UiText.fromKey("domain_carbon_reduce_fuel"))
        assertWithMessage("fuel rec difficulty").that(fuel.difficulty).isEqualTo(CarbonFootprintCalculator.Difficulty.MEDIUM)
        assertWithMessage("fuel rec impact").that(fuel.potentialImpact).isEqualTo("Up to 40% reduction")
        // savings = 230.0 * 0.4 = 92.0
        assertWithMessage("fuel rec savings kg").that(fuel.savings).isWithin(TOL).of(92.0)
        assertWithMessage("fuel rec is not offset").that(fuel.isOffset).isFalse()

        val offset = report.recommendations[1]
        assertWithMessage("offset rec category").that(offset.category).isEqualTo("Offset")
        assertWithMessage("offset rec title").that(offset.title).isEqualTo(UiText.fromKey("domain_carbon_purchase_offsets"))
        assertWithMessage("offset rec flag").that(offset.isOffset).isTrue()
        assertWithMessage("offset rec savings").that(offset.savings).isEqualTo(0.0)
        // 230.0 / 1000 * 22.0 = 5.06
        assertWithMessage("offset rec cost").that(offset.offsetCost!!).isWithin(TOL).of(5.06)
    }

    @Test
    fun `mixed basket suggests fuel and flight alternatives with exact reductions`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns createMockExpenses()

        val report = calculator.calculateCarbonFootprint()

        // FUEL 115.0 > 10 and FLIGHT 100.0 > 20 qualify; CLOTHING absent.
        assertWithMessage("alternatives count").that(report.alternatives.size).isEqualTo(2)
        val fuel = report.alternatives[0]
        assertWithMessage("[fuel alt] behavior").that(fuel.currentBehavior).isEqualTo("Driving frequently")
        assertWithMessage("[fuel alt] alternative").that(fuel.alternative).isEqualTo("Use public transport or cycle")
        assertWithMessage("[fuel alt] co2 reduction (115.0 * 0.5)").that(fuel.co2Reduction).isWithin(TOL).of(57.5)
        assertWithMessage("[fuel alt] cost savings").that(fuel.costSavings).isEqualTo(200.0)
        assertWithMessage("[fuel alt] difficulty").that(fuel.difficulty).isEqualTo(CarbonFootprintCalculator.Difficulty.MEDIUM)
        val flight = report.alternatives[1]
        assertWithMessage("[flight alt] behavior").that(flight.currentBehavior).isEqualTo("Flying frequently")
        assertWithMessage("[flight alt] alternative").that(flight.alternative).isEqualTo("Take trains for shorter distances")
        assertWithMessage("[flight alt] co2 reduction (100.0 * 0.8)").that(flight.co2Reduction).isWithin(TOL).of(80.0)
        assertWithMessage("[flight alt] cost savings").that(flight.costSavings).isEqualTo(100.0)
        assertWithMessage("[flight alt] difficulty").that(flight.difficulty).isEqualTo(CarbonFootprintCalculator.Difficulty.HARD)
    }

    @Test
    fun `monthly trend is empty when home currency is unavailable`() = runTest {
        // setup() uses a non-relaxed currency repository, so homeCurrency() throws and
        // production must NOT fall back to a hardcoded currency (S12-025).
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns createMockExpenses()

        val report = calculator.calculateCarbonFootprint()

        assertWithMessage("monthly trend without home currency").that(report.monthlyTrend).isEmpty()
    }

    @Test
    fun `monthly trend groups by month when home currency is available and normalizer fails`() = runTest {
        val currencyRepo = mockk<CurrencySettingsRepository>()
        every { currencyRepo.homeCurrency() } returns flowOf("EUR")
        val normalizer = mockk<AnalyticsCurrencyNormalizer>()
        coEvery { normalizer.normalizeExpenses(any(), any()) } throws IllegalStateException("synthetic normalizer failure")
        val calc = CarbonFootprintCalculator(expenseDao, timeProvider, normalizer, currencyRepo)
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns createMockExpenses()

        val report = calc.calculateCarbonFootprint()

        // FIXED_NOW = 2023-11-14T22:13:20Z -> November 2023 in every system time zone.
        assertWithMessage("monthly trend size").that(report.monthlyTrend.size).isEqualTo(1)
        val month = report.monthlyTrend.single()
        assertWithMessage("month key").that(month.month).isEqualTo("2023-11-01")
        assertWithMessage("month emissions kg").that(month.emissionsKg).isWithin(TOL).of(325.5)
        assertWithMessage("month transaction count").that(month.transactionCount).isEqualTo(5)
        assertWithMessage("total falls back to effectiveAmount").that(report.totalEmissionsKg).isWithin(TOL).of(325.5)
    }

    // ── Filtering / empties ───────────────────────────────────────────────────

    @Test
    fun `non purchase transactions are filtered out`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            listOf(
                createExpense("Deposit", 100.0, TransactionType.DEPOSIT),
                createExpense("Purchase", 50.0, TransactionType.PURCHASE)
            )

        val report = calculator.calculateCarbonFootprint()

        // Only the purchase counts: 50.0 * 0.25 (DEFAULT factor) = 12.5
        assertWithMessage("total kg").that(report.totalEmissionsKg).isWithin(TOL).of(12.5)
    }

    @Test
    fun `empty expense list returns zero emissions`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns emptyList()

        val report = calculator.calculateCarbonFootprint()

        assertWithMessage("total kg").that(report.totalEmissionsKg).isEqualTo(0.0)
        assertWithMessage("daily average kg").that(report.dailyAverageKg).isEqualTo(0.0)
    }

    // ── DAO snapshot path regression tests ───────────────────────────────────

    /**
     * Regression: 2500 purchase expenses must all be counted, proving the uncapped
     * one-shot DAO path (no old LIMIT 2000) is used.
     */
    @Test
    fun `regression - carbon report includes all rows beyond old 2000 limit`() = runTest {
        val allExpenses = (1..2500).map {
            createExpense("SHELL", 10.0, TransactionType.PURCHASE)
        }
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns allExpenses

        val report = calculator.calculateCarbonFootprint()

        // 2500 purchases x 10.0 x 2.3 = 57500.0 kg
        assertWithMessage("total kg").that(report.totalEmissionsKg).isWithin(0.1).of(57_500.0)
    }

    @Test
    fun `regression - calculator uses one shot uncapped dao path only`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns
            listOf(createExpense("Test", 10.0, TransactionType.PURCHASE))

        calculator.calculateCarbonFootprint()

        coVerify(exactly = 1) { expenseDao.getExpensesBetweenUncapped(any(), any()) }
        verify(exactly = 0) { expenseDao.getExpensesBetweenFlowUncapped(any(), any()) }
        verify(exactly = 0) { expenseDao.getExpensesBetweenFlow(any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetween(any(), any(), any(), any()) }
    }

    // Helper methods

    private fun createMockExpenses(): List<Expense> {
        return listOf(
            createExpense("SHELL", 50.0, TransactionType.PURCHASE),
            createExpense("SKLAVENITIS", 80.0, TransactionType.PURCHASE),
            createExpense("EVEREST", 30.0, TransactionType.PURCHASE),
            createExpense("PLAISIO", 100.0, TransactionType.PURCHASE),
            createExpense("AEGEAN", 200.0, TransactionType.PURCHASE)
        )
    }

    private fun createExpense(
        merchant: String,
        amount: Double,
        type: TransactionType
    ): Expense {
        return Expense(
            id = 1L,
            merchant = merchant,
            amount = amount,
            date = FIXED_NOW,
            categoryId = 1,
            notes = null,
            transactionType = type,
            currency = "EUR",
            createdAt = FIXED_NOW
        )
    }

    private companion object {
        /** 2023-11-14T22:13:20Z */
        const val FIXED_NOW = 1_700_000_000_000L
        const val TOL = 1e-9
    }
}
