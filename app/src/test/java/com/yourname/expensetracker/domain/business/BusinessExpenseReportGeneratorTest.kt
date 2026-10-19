package com.yourname.expensetracker.domain.business

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.BusinessCategoryTotal
import com.yourname.expensetracker.data.database.dao.BusinessProjectTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MileageTrackingDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MileageTracking
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BusinessExpenseRepository
import com.yourname.expensetracker.data.repository.TaxSettingsRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * A.10 Batch 4 – Business expense report spend-semantics guard tests.
 *
 * The report boundary ([BusinessExpenseReportGenerator]) enforces purchase-only
 * semantics as defense-in-depth: even if the upstream DAO/repository leaked
 * non-purchase rows (DEPOSIT, TRANSFER, WITHDRAWAL), the generator filters
 * them out so that totals, top-expense rankings, missing-receipt surfaces,
 * and CSV spend rows never include non-purchase movements.
 *
 * Strategy: mock the repository to return mixed transaction types and verify
 * that the generator boundary filter correctly excludes all non-PURCHASE rows
 * from every output surface. Table-driven tests re-stub the shared repository
 * mock per row; tables that verify call counts clear recorded calls (not
 * stubs) first so counts never leak between rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class BusinessExpenseReportGeneratorTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repo: BusinessExpenseRepository
    private lateinit var timeProvider: TimeProvider
    private val taxSettingsRepository: TaxSettingsRepository = mockk(relaxed = true)
    private lateinit var generator: BusinessExpenseReportGenerator
    private lateinit var originalLocale: Locale

    // Fixed reference timestamps
    private val startDate: Long = LocalDate.of(2026, 3, 1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val endDate: Long = LocalDate.of(2026, 4, 1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val fixedNow: Long = LocalDate.of(2026, 4, 1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ── Test data ────────────────────────────────────────────────────────────

    /** A normal purchase business expense. */
    private val purchaseExpense = Expense(
        id = 1L,
        amount = 100.0,
        merchant = "Office Supplies Co",
        transactionType = TransactionType.PURCHASE,
        date = startDate + 86_400_000L, // day 2
        isBusinessExpense = true,
        businessCategory = "Office Supplies",
        businessPurpose = "Printer paper",
        businessProject = "ProjectX",
        requiresReceipt = true
    )

    /** A second purchase for ranking/totals verification. */
    private val purchaseExpense2 = Expense(
        id = 2L,
        amount = 250.0,
        merchant = "Software Vendor",
        transactionType = TransactionType.PURCHASE,
        date = startDate + 172_800_000L, // day 3
        isBusinessExpense = true,
        businessCategory = "Software",
        businessPurpose = "IDE licence",
        businessProject = "ProjectY",
        requiresReceipt = true
    )

    /** A deposit that should NEVER inflate business spending if it leaked through. */
    private val depositExpense = Expense(
        id = 10L,
        amount = 5000.0,
        merchant = "Client Payment",
        transactionType = TransactionType.DEPOSIT,
        date = startDate + 86_400_000L,
        isBusinessExpense = true,
        businessCategory = "Income",
        businessProject = "ProjectX",
        requiresReceipt = false
    )

    /** A transfer that should NEVER inflate business spending if it leaked through. */
    private val transferExpense = Expense(
        id = 11L,
        amount = 800.0,
        merchant = "Internal Transfer",
        transactionType = TransactionType.TRANSFER,
        date = startDate + 86_400_000L,
        isBusinessExpense = true,
        businessCategory = "Transfer",
        businessProject = "ProjectX",
        requiresReceipt = false
    )

    /** A withdrawal that should NEVER inflate business spending if it leaked through. */
    private val withdrawalExpense = Expense(
        id = 12L,
        amount = 200.0,
        merchant = "ATM Withdrawal",
        transactionType = TransactionType.WITHDRAWAL,
        date = startDate + 86_400_000L,
        isBusinessExpense = true,
        businessCategory = "Cash",
        businessProject = "ProjectX",
        requiresReceipt = false
    )

    /** A sample mileage trip. */
    private val sampleTrip = MileageTracking(
        id = 1L,
        date = startDate + 86_400_000L,
        distanceKm = 50.0,
        tripPurpose = "Client visit",
        businessProject = "ProjectX",
        deductionRatePerKm = 0.30,
        calculatedDeduction = 15.0,
        isBusinessTrip = true
    )

    // ── Setup / Teardown ─────────────────────────────────────────────────────

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // The generator formats amounts with String.format(...) (default Locale);
        // pin US so "EUR 350.00" is deterministic, and restore in tearDown.
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)

        repo = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        every { timeProvider.now() } returns fixedNow
        every { taxSettingsRepository.getFilingCurrency() } returns "EUR"

        generator = BusinessExpenseReportGenerator(repo, timeProvider, taxSettingsRepository, Dispatchers.Unconfined)

        // Default: repository returns only purchase rows (the real contract)
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns
            listOf(purchaseExpense, purchaseExpense2)
        coEvery { repo.getExpensesMissingReceipts(startDate, endDate) } returns emptyList()
        coEvery { repo.getBusinessMileageBetween(startDate, endDate) } returns emptyList()
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        Dispatchers.resetMain()
    }

    // =====================================================================
    // 1. Total-expenses uses ONLY purchase amounts
    // =====================================================================

    private class TotalRow(val label: String, val repoRows: List<Expense>, val expectedTotal: Double)

    /**
     * Regression guard: if the repository accidentally returned a deposit,
     * transfer or withdrawal row, the generator boundary filter must exclude it
     * from totals. Also covers the empty and normal controls.
     */
    @Test
    fun `report totalExpenses counts only purchase rows for every leaked type`() = runTest {
        val rows = listOf(
            TotalRow("two purchases", listOf(purchaseExpense, purchaseExpense2), 350.0),
            TotalRow("repository returns empty", emptyList(), 0.0),
            TotalRow("deposit leaked", listOf(purchaseExpense, depositExpense), 100.0),
            TotalRow("transfer leaked", listOf(purchaseExpense, transferExpense), 100.0),
            TotalRow("withdrawal leaked", listOf(purchaseExpense, withdrawalExpense), 100.0),
            TotalRow(
                "all non-purchase types leaked together",
                listOf(purchaseExpense, purchaseExpense2, depositExpense, transferExpense, withdrawalExpense),
                350.0
            )
        )
        assertEquals("table size guard", 6, rows.size)

        for (row in rows) {
            coEvery { repo.getBusinessExpenses(startDate, endDate) } returns row.repoRows

            val report = generator.generateReport(startDate, endDate, includeMileage = false)

            assertApproxEquals(
                row.expectedTotal, report.totalExpenses, 0.01,
                "[${row.label}] only PURCHASE rows must contribute to total"
            )
        }
    }

    @Test
    fun `generator delegates to repository for expense queries`() = runTest {
        generator.generateReport(startDate, endDate, includeMileage = false)

        coVerify(exactly = 1) { repo.getBusinessExpenses(startDate, endDate) }
        coVerify(exactly = 1) { repo.getExpensesMissingReceipts(startDate, endDate) }
    }

    // =====================================================================
    // 2. Category and project breakdowns are derived from purchase-only list
    // =====================================================================

    private class BreakdownRow(
        val label: String,
        val repoRows: List<Expense>,
        val expected: Map<String, Double>
    )

    @Test
    fun `category breakdown is purchase-only aggregation and excludes leaked amounts`() = runTest {
        val expected = mapOf("Office Supplies" to 100.0, "Software" to 250.0)
        val rows = listOf(
            BreakdownRow("clean repository", listOf(purchaseExpense, purchaseExpense2), expected),
            BreakdownRow(
                "deposit and transfer leaked",
                listOf(purchaseExpense, depositExpense, transferExpense, purchaseExpense2),
                expected
            )
        )
        assertEquals("table size guard", 2, rows.size)

        for (row in rows) {
            coEvery { repo.getBusinessExpenses(startDate, endDate) } returns row.repoRows

            val report = generator.generateReport(startDate, endDate, includeMileage = false)

            assertEquals("[${row.label}] category map", row.expected, report.expensesByCategory)
            assertFalse(
                "[${row.label}] Income category from deposit must not appear",
                report.expensesByCategory.containsKey("Income")
            )
            assertFalse(
                "[${row.label}] Transfer category must not appear",
                report.expensesByCategory.containsKey("Transfer")
            )
        }
    }

    @Test
    fun `project breakdown is purchase-only aggregation and excludes leaked amounts`() = runTest {
        val expected = mapOf("ProjectX" to 100.0, "ProjectY" to 250.0)
        val rows = listOf(
            BreakdownRow("clean repository", listOf(purchaseExpense, purchaseExpense2), expected),
            BreakdownRow(
                "deposit transfer and withdrawal leaked",
                listOf(purchaseExpense, depositExpense, transferExpense, withdrawalExpense, purchaseExpense2),
                expected
            )
        )
        assertEquals("table size guard", 2, rows.size)

        for (row in rows) {
            coEvery { repo.getBusinessExpenses(startDate, endDate) } returns row.repoRows

            val report = generator.generateReport(startDate, endDate, includeMileage = false)

            assertEquals(
                "[${row.label}] ProjectX must only include the purchase amount, not deposit/transfer/withdrawal",
                row.expected,
                report.expensesByProject
            )
        }
    }

    // =====================================================================
    // 3. Top-expenses ranking excludes non-purchases
    // =====================================================================

    private class TopRow(val label: String, val repoRows: List<Expense>, val expectedIds: List<Long>)

    @Test
    fun `top expenses ranked by effectiveAmount descending and purchase-only`() = runTest {
        val rows = listOf(
            TopRow("clean repository", listOf(purchaseExpense, purchaseExpense2), listOf(2L, 1L)),
            // Deposit has 5000 and would be #1 if not excluded
            TopRow(
                "deposit and transfer leaked",
                listOf(purchaseExpense, depositExpense, transferExpense, purchaseExpense2),
                listOf(2L, 1L)
            )
        )
        assertEquals("table size guard", 2, rows.size)

        for (row in rows) {
            coEvery { repo.getBusinessExpenses(startDate, endDate) } returns row.repoRows

            val report = generator.generateReport(startDate, endDate, includeMileage = false)

            assertEquals("[${row.label}] ranked ids", row.expectedIds, report.topExpenses.map { it.id })
            assertTrue(
                "[${row.label}] top expenses must be purchase only",
                report.topExpenses.all { it.transactionType == TransactionType.PURCHASE }
            )
        }
    }

    @Test
    fun `top expenses limited to 10`() = runTest {
        val many = (1..15L).map { i ->
            purchaseExpense.copy(id = i, amount = i * 10.0)
        }
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns many

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertEquals(10, report.topExpenses.size)
        // Highest amount first: 150, 140, ..., 60 (ids 15 down to 6)
        assertEquals(
            listOf(15L, 14L, 13L, 12L, 11L, 10L, 9L, 8L, 7L, 6L),
            report.topExpenses.map { it.id }
        )
        assertApproxEquals(150.0, report.topExpenses[0].effectiveAmount, 0.01)
    }

    // =====================================================================
    // 4. Missing-receipts surface is purchase-only
    // =====================================================================

    private class MissingRow(val label: String, val stubbed: List<Expense>, val expectedIds: List<Long>)

    @Test
    fun `missing receipts surface contains only purchase rows`() = runTest {
        val rows = listOf(
            MissingRow(
                "single purchase from purchase-only query",
                listOf(purchaseExpense.copy(id = 99L)),
                listOf(99L)
            ),
            MissingRow(
                "deposit and withdrawal leaked alongside purchase",
                listOf(purchaseExpense, depositExpense, withdrawalExpense),
                listOf(1L)
            ),
            MissingRow("repository correct, purchase only", listOf(purchaseExpense), listOf(1L))
        )
        assertEquals("table size guard", 3, rows.size)

        for (row in rows) {
            coEvery { repo.getExpensesMissingReceipts(startDate, endDate) } returns row.stubbed

            val report = generator.generateReport(startDate, endDate, includeMileage = false)

            assertEquals(
                "[${row.label}] missing-receipt ids",
                row.expectedIds,
                report.expensesMissingReceipts.map { it.id }
            )
            assertTrue(
                "[${row.label}] missing-receipt expense must be PURCHASE",
                report.expensesMissingReceipts.all { it.transactionType == TransactionType.PURCHASE }
            )
        }
    }

    // =====================================================================
    // 5. CSV export is purchase-only
    // =====================================================================

    private class CsvRow(val label: String, val repoRows: List<Expense>)

    @Test
    fun `CSV export contains only purchase rows from repository`() = runTest {
        val rows = listOf(
            CsvRow("clean repository", listOf(purchaseExpense, purchaseExpense2)),
            CsvRow(
                "deposit transfer and withdrawal leaked",
                listOf(purchaseExpense, depositExpense, transferExpense, withdrawalExpense, purchaseExpense2)
            )
        )
        assertEquals("table size guard", 2, rows.size)

        for (row in rows) {
            coEvery { repo.getBusinessExpenses(startDate, endDate) } returns row.repoRows
            // Fresh call counts per row; stubs are kept.
            clearMocks(repo, answers = false, recordedCalls = true, childMocks = false)

            val csv = generator.generateCSVExport(startDate, endDate, includeMileage = false)

            val lines = csv.trim().lines()
            // 1 header + 2 purchase data rows only
            assertEquals("[${row.label}] line count", 3, lines.size)
            assertTrue("[${row.label}] header", lines[0].startsWith("Date,Merchant,Amount"))
            assertTrue(
                "[${row.label}] first data row",
                Regex("""\d{4}-\d{2}-\d{2},Office Supplies Co,100\.0,EUR,Office Supplies,Printer paper,ProjectX,Yes,""")
                    .matches(lines[1])
            )
            assertTrue(
                "[${row.label}] second data row",
                Regex("""\d{4}-\d{2}-\d{2},Software Vendor,250\.0,EUR,Software,IDE licence,ProjectY,Yes,""")
                    .matches(lines[2])
            )
            assertFalse("[${row.label}] CSV must not contain deposit", csv.contains("Client Payment"))
            assertFalse("[${row.label}] CSV must not contain transfer", csv.contains("Internal Transfer"))
            assertFalse("[${row.label}] CSV must not contain withdrawal", csv.contains("ATM Withdrawal"))
            // Delegation to the purchase-only repository query
            coVerify(exactly = 1) { repo.getBusinessExpenses(startDate, endDate) }
        }
    }

    @Test
    fun `CSV export preserves column order`() = runTest {
        val csv = generator.generateCSVExport(startDate, endDate, includeMileage = false)

        val header = csv.lines().first()
        assertEquals(
            "Date,Merchant,Amount,Currency,Business Category,Business Purpose,Project,Requires Receipt,Notes",
            header
        )
    }

    // =====================================================================
    // 6. Mileage logic is independent and preserved
    // =====================================================================

    @Test
    fun `mileage report included when requested`() = runTest {
        coEvery { repo.getBusinessMileageBetween(startDate, endDate) } returns listOf(sampleTrip)

        val report = generator.generateReport(startDate, endDate, includeMileage = true)

        assertApproxEquals(50.0, report.mileageReport.totalDistanceKm, 0.01)
        assertApproxEquals(15.0, report.mileageReport.totalDeduction, 0.01)
        assertEquals(1, report.mileageReport.tripCount)
        assertApproxEquals(0.30, report.mileageReport.deductionRatePerKm ?: error("Expected single deduction rate"), 0.001)
    }

    @Test
    fun `mileage report exposes weighted rate when trips use multiple rates`() = runTest {
        val higherRateTrip = sampleTrip.copy(id = 2L, distanceKm = 150.0, deductionRatePerKm = 0.45, calculatedDeduction = 67.5)
        coEvery { repo.getBusinessMileageBetween(startDate, endDate) } returns listOf(sampleTrip, higherRateTrip)

        val report = generator.generateReport(startDate, endDate, includeMileage = true)

        assertTrue(report.mileageReport.hasMultipleRates)
        assertNull(report.mileageReport.deductionRatePerKm)
        // (15.0 + 67.5) / (50 + 150) = 0.4125
        assertApproxEquals(0.4125, report.mileageReport.effectiveDeductionRatePerKm, 0.001)
        assertTrue(report.formattedReport.contains("weighted average, multiple rates applied"))
    }

    @Test
    fun `mileage excluded when not requested`() = runTest {
        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertApproxEquals(0.0, report.mileageReport.totalDistanceKm, 0.01)
        assertApproxEquals(0.0, report.mileageReport.totalDeduction, 0.01)
        assertEquals(0, report.mileageReport.tripCount)
    }

    @Test
    fun `totalDeductibleExpenses includes mileage deduction`() = runTest {
        coEvery { repo.getBusinessMileageBetween(startDate, endDate) } returns listOf(sampleTrip)

        val report = generator.generateReport(startDate, endDate, includeMileage = true)

        // 350 (expenses) + 15 (mileage) = 365
        assertApproxEquals(365.0, report.totalDeductibleExpenses, 0.01)
    }

    private class MileageCsvRow(
        val label: String,
        val includeMileage: Boolean,
        val expectSection: Boolean,
        val expectedMileageQueries: Int
    )

    @Test
    fun `CSV mileage section follows includeMileage flag`() = runTest {
        val rows = listOf(
            MileageCsvRow("mileage requested", includeMileage = true, expectSection = true, expectedMileageQueries = 1),
            MileageCsvRow("mileage not requested", includeMileage = false, expectSection = false, expectedMileageQueries = 0)
        )
        assertEquals("table size guard", 2, rows.size)

        for (row in rows) {
            // Trip data is available in both rows: only the flag may decide inclusion.
            coEvery { repo.getBusinessMileageBetween(startDate, endDate) } returns listOf(sampleTrip)
            clearMocks(repo, answers = false, recordedCalls = true, childMocks = false)

            val csv = generator.generateCSVExport(startDate, endDate, includeMileage = row.includeMileage)

            assertEquals(
                "[${row.label}] mileage header presence",
                row.expectSection,
                csv.contains("Date,Distance (km),Start Location,End Location,Purpose,Project,Deduction Amount")
            )
            assertEquals("[${row.label}] trip data presence", row.expectSection, csv.contains("Client visit"))
            assertEquals(
                "[${row.label}] distance column marker presence",
                row.expectSection,
                csv.contains("Distance (km)")
            )
            coVerify(exactly = row.expectedMileageQueries) { repo.getBusinessMileageBetween(startDate, endDate) }
        }
    }

    // =====================================================================
    // 7. Formatted report layout preserved
    // =====================================================================

    @Test
    fun `formatted report contains expected sections`() = runTest {
        coEvery { repo.getBusinessMileageBetween(startDate, endDate) } returns listOf(sampleTrip)
        val missing = listOf(purchaseExpense.copy(id = 99L))
        coEvery { repo.getExpensesMissingReceipts(startDate, endDate) } returns missing

        val report = generator.generateReport(startDate, endDate, includeMileage = true)
        val text = report.formattedReport

        assertTrue("Header present", text.contains("BUSINESS EXPENSE REPORT"))
        assertTrue("Summary present", text.contains("SUMMARY"))
        assertTrue("Category section present", text.contains("EXPENSES BY CATEGORY"))
        assertTrue("Project section present", text.contains("EXPENSES BY PROJECT"))
        assertTrue("Mileage section present", text.contains("MILEAGE DEDUCTION"))
        assertTrue("Top expenses section present", text.contains("TOP 10 EXPENSES"))
        assertTrue("Missing receipts section present", text.contains("EXPENSES MISSING RECEIPTS"))
        assertTrue("Footer present", text.contains("End of Report"))
    }

    @Test
    fun `formatted report total reflects purchase-only amounts`() = runTest {
        val report = generator.generateReport(startDate, endDate, includeMileage = false)
        val text = report.formattedReport

        // Locale is pinned to US in setUp (production formats with the default Locale).
        assertTrue("Total should be 350.00 EUR",
            text.contains("Total Business Expenses: EUR 350.00"))
    }

    // =====================================================================
    // 8. Report metadata
    // =====================================================================

    @Test
    fun `report metadata has correct dates and generation time`() = runTest {
        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertEquals(startDate, report.startDate)
        assertEquals(endDate, report.endDate)
        assertEquals(fixedNow, report.generatedAt)
    }

    // =====================================================================
    // 9. Export safety – CSV injection and mixed-currency
    // =====================================================================

    @Test
    fun `CSV export neutralizes formula injection in merchant and notes`() = runTest {
        val formulaExpense = purchaseExpense.copy(
            id = 100L,
            merchant = "=cmd|'/C calc'!A0",
            notes = "@SUM(A1:A10)"
        )
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns listOf(formulaExpense)

        val csv = generator.generateCSVExport(startDate, endDate, includeMileage = false)

        // Formula-leading values should be prefixed with single quote
        assertTrue("Formula merchant must be neutralized", csv.contains("'=cmd|'/C calc'!A0"))
        assertTrue("Formula notes must be neutralized", csv.contains("'@SUM(A1:A10)"))
    }

    @Test
    fun `formatted report does not hardcode euro for non EUR filing currency`() = runTest {
        every { taxSettingsRepository.getFilingCurrency() } returns "USD"
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns listOf(
            purchaseExpense.copy(currency = "USD"),
            purchaseExpense2.copy(currency = "USD")
        )

        val report = generator.generateReport(startDate, endDate, includeMileage = false)
        val text = report.formattedReport

        assertFalse("Report must not contain euro symbol", text.contains("\u20AC"))
        assertTrue("Report must show USD currency", text.contains("USD"))
    }

    @Test
    fun `mixed currency report marks partial and includes warning`() = runTest {
        val usdExpense = purchaseExpense.copy(id = 200L, currency = "USD")
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns listOf(purchaseExpense, usdExpense)

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertTrue("Mixed currency must be marked partial", report.isPartial)
        assertTrue("Warning must mention mixed currencies", report.warningMessage?.contains("mixed") == true)
        assertTrue("Warning must list currencies", report.warningMessage?.contains("EUR") == true)
        assertTrue("Warning must list currencies", report.warningMessage?.contains("USD") == true)
    }

    // =====================================================================
    // 10. Repository-level hardening: verify purchase-only semantics are
    //     enforced in every report-facing repository method, so even if DAO
    //     output regresses the report surface stays clean.
    //     We exercise the repository through a real (non-mocked) instance
    //     that wraps a mocked DAO, then feed it to the generator.
    // =====================================================================

    /**
     * Build a generator backed by a real [BusinessExpenseRepository] wrapping
     * mocked DAOs, so repository-level purchase-only filtering is exercised.
     */
    private fun buildRealRepoGenerator(
        expenseDao: ExpenseDao,
        mileageDao: MileageTrackingDao = mockk(relaxed = true)
    ): Pair<BusinessExpenseRepository, BusinessExpenseReportGenerator> {
        val realRepo = BusinessExpenseRepository(mockk(relaxed = true), expenseDao, mileageDao)
        val gen = BusinessExpenseReportGenerator(realRepo, timeProvider, taxSettingsRepository, Dispatchers.Unconfined)
        return realRepo to gen
    }

    @Test
    fun `repository category and project aggregates return purchase-only breakdowns`() = runTest {
        val dao = mockk<ExpenseDao>(relaxed = true)
        // DAO aggregates already enforce PURCHASE-only via SPENDING_TYPE_SQL;
        // the repository is a thin pass-through to these aggregates.
        coEvery { dao.getBusinessExpensesByCategory(startDate, endDate) } returns listOf(
            BusinessCategoryTotal("Office Supplies", 100.0, 1),
            BusinessCategoryTotal("Software", 250.0, 1)
        )
        coEvery { dao.getBusinessExpensesByProject(startDate, endDate) } returns listOf(
            BusinessProjectTotal("ProjectX", 100.0, 1),
            BusinessProjectTotal("ProjectY", 250.0, 1)
        )

        val (realRepo, _) = buildRealRepoGenerator(dao)
        val categories = realRepo.getExpensesByCategory(startDate, endDate)
        val projects = realRepo.getExpensesByProject(startDate, endDate)

        assertEquals("[category] row count", 2, categories.size)
        val catMap = categories.associate { it.businessCategory to it.total }
        assertEquals(
            "[category] totals must exclude deposit amounts",
            mapOf("Office Supplies" to 100.0, "Software" to 250.0),
            catMap
        )
        assertFalse("[category] Income category from deposit must not appear", catMap.containsKey("Income"))
        assertFalse("[category] Transfer category must not appear", catMap.containsKey("Transfer"))

        assertEquals("[project] row count", 2, projects.size)
        val projMap = projects.associate { it.businessProject to it.total }
        assertEquals(
            "[project] totals must exclude deposit/transfer/withdrawal amounts",
            mapOf("ProjectX" to 100.0, "ProjectY" to 250.0),
            projMap
        )
    }

    @Test
    fun `repository getExpensesMissingReceipts filters non-purchase rows`() = runTest {
        val dao = mockk<ExpenseDao>(relaxed = true)
        coEvery { dao.getBusinessExpensesBetween(startDate, endDate) } returns
            listOf(purchaseExpense, purchaseExpense2)
        coEvery { dao.getBusinessExpensesMissingReceipts(startDate, endDate) } returns
            listOf(purchaseExpense, depositExpense, withdrawalExpense)

        val (_, gen) = buildRealRepoGenerator(dao)
        val report = gen.generateReport(startDate, endDate, includeMileage = false)

        // Only the purchase should remain in missing receipts
        assertEquals(1, report.expensesMissingReceipts.size)
        assertEquals(purchaseExpense.id, report.expensesMissingReceipts[0].id)
        assertEquals(TransactionType.PURCHASE, report.expensesMissingReceipts[0].transactionType)
    }

    @Test
    fun `repository hardening end-to-end report with mixed DAO output`() = runTest {
        val dao = mockk<ExpenseDao>(relaxed = true)
        val mileageDao = mockk<MileageTrackingDao>(relaxed = true)

        // DAO returns all transaction types — worst-case regression
        coEvery { dao.getBusinessExpensesBetween(startDate, endDate) } returns
            listOf(purchaseExpense, depositExpense, transferExpense, withdrawalExpense, purchaseExpense2)
        coEvery { dao.getBusinessExpensesMissingReceipts(startDate, endDate) } returns
            listOf(purchaseExpense, depositExpense)
        coEvery { mileageDao.getBusinessMileageBetween(startDate, endDate) } returns listOf(sampleTrip)

        val (_, gen) = buildRealRepoGenerator(dao, mileageDao)
        val report = gen.generateReport(startDate, endDate, includeMileage = true)

        // Totals: only purchases 100 + 250 = 350
        assertApproxEquals(350.0, report.totalExpenses, 0.01,
            "End-to-end total must be purchase-only (repository must filter non-purchase rows)")
        // Deductible: 350 + 15 (mileage) = 365
        assertApproxEquals(365.0, report.totalDeductibleExpenses, 0.01)

        // Categories: only purchase categories
        assertEquals(mapOf("Office Supplies" to 100.0, "Software" to 250.0), report.expensesByCategory)
        assertFalse(report.expensesByCategory.containsKey("Income"))
        assertFalse(report.expensesByCategory.containsKey("Transfer"))
        assertFalse(report.expensesByCategory.containsKey("Cash"))

        // Projects: only purchase projects
        assertEquals(mapOf("ProjectX" to 100.0, "ProjectY" to 250.0), report.expensesByProject)

        // Top expenses: only purchases, ranked correctly
        assertEquals(listOf(2L, 1L), report.topExpenses.map { it.id })
        assertTrue(report.topExpenses.all { it.transactionType == TransactionType.PURCHASE })

        // Missing receipts: only purchase
        assertEquals(listOf(1L), report.expensesMissingReceipts.map { it.id })
        assertEquals(TransactionType.PURCHASE, report.expensesMissingReceipts[0].transactionType)
    }

    @Test
    fun `repository CSV export end-to-end excludes non-purchase via real repo`() = runTest {
        val dao = mockk<ExpenseDao>(relaxed = true)
        coEvery { dao.getBusinessExpensesBetween(startDate, endDate) } returns
            listOf(purchaseExpense, depositExpense, transferExpense, withdrawalExpense, purchaseExpense2)

        val (_, gen) = buildRealRepoGenerator(dao)
        val csv = gen.generateCSVExport(startDate, endDate, includeMileage = false)

        val lines = csv.trim().lines()
        // 1 header + 2 purchase data rows only
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("Office Supplies Co"))
        assertTrue(lines[2].contains("Software Vendor"))
        assertFalse("CSV must not contain deposit", csv.contains("Client Payment"))
        assertFalse("CSV must not contain transfer", csv.contains("Internal Transfer"))
        assertFalse("CSV must not contain withdrawal", csv.contains("ATM Withdrawal"))
    }
}
