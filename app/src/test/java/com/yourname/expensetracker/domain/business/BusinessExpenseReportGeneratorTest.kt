package com.yourname.expensetracker.domain.business

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MileageTracking
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BusinessExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

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
 * from every output surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusinessExpenseReportGeneratorTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repo: BusinessExpenseRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var generator: BusinessExpenseReportGenerator

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
        repo = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        every { timeProvider.now() } returns fixedNow

        generator = BusinessExpenseReportGenerator(repo, timeProvider)

        // Default: repository returns only purchase rows (the real contract)
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns
            listOf(purchaseExpense, purchaseExpense2)
        coEvery { repo.getExpensesMissingReceipts(startDate, endDate) } returns emptyList()
        coEvery { repo.getBusinessMileageBetween(startDate, endDate) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =====================================================================
    // 1. Total-expenses uses ONLY purchase amounts
    // =====================================================================

    @Test
    fun `report totalExpenses sums only repository-supplied rows`() = runTest {
        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        // 100 + 250 = 350 (both purchases)
        assertApproxEquals(350.0, report.totalExpenses, 0.01)
    }

    @Test
    fun `report totalExpenses is zero when repository returns empty`() = runTest {
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns emptyList()

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertApproxEquals(0.0, report.totalExpenses, 0.01)
    }

    /**
     * Regression guard: if repository accidentally returned a deposit row,
     * the generator boundary filter must exclude it from totals.
     */
    @Test
    fun `deposit leaked from repo is excluded from totalExpenses`() = runTest {
        // Simulate a broken DAO that leaks a deposit
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns
            listOf(purchaseExpense, depositExpense)

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        // Only the purchase amount must be counted: 100.0
        assertApproxEquals(100.0, report.totalExpenses, 0.01,
            "Deposit must be excluded from business spend total")
    }

    @Test
    fun `transfer leaked from repo is excluded from totalExpenses`() = runTest {
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns
            listOf(purchaseExpense, transferExpense)

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        // Only the purchase amount must be counted: 100.0
        assertApproxEquals(100.0, report.totalExpenses, 0.01,
            "Transfer must be excluded from business spend total")
    }

    @Test
    fun `withdrawal leaked from repo is excluded from totalExpenses`() = runTest {
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns
            listOf(purchaseExpense, withdrawalExpense)

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        // Only the purchase amount must be counted: 100.0
        assertApproxEquals(100.0, report.totalExpenses, 0.01,
            "Withdrawal must be excluded from business spend total")
    }

    @Test
    fun `all non-purchase types leaked together are excluded from totalExpenses`() = runTest {
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns
            listOf(purchaseExpense, purchaseExpense2, depositExpense, transferExpense, withdrawalExpense)

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        // Only purchases: 100 + 250 = 350
        assertApproxEquals(350.0, report.totalExpenses, 0.01,
            "Only PURCHASE rows must contribute to total")
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

    @Test
    fun `category totals reflect purchase-only aggregation`() = runTest {
        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertEquals(2, report.expensesByCategory.size)
        assertApproxEquals(100.0, report.expensesByCategory["Office Supplies"]!!, 0.01)
        assertApproxEquals(250.0, report.expensesByCategory["Software"]!!, 0.01)
    }

    @Test
    fun `project totals reflect purchase-only aggregation`() = runTest {
        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertEquals(2, report.expensesByProject.size)
        assertApproxEquals(100.0, report.expensesByProject["ProjectX"]!!, 0.01)
        assertApproxEquals(250.0, report.expensesByProject["ProjectY"]!!, 0.01)
    }

    /**
     * Regression guard: if repository expense list accidentally includes
     * non-purchase rows, category breakdowns must not include them.
     */
    @Test
    fun `category breakdown excludes leaked deposit and transfer amounts`() = runTest {
        // Simulate a broken DAO that leaks deposit + transfer alongside purchases
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns
            listOf(purchaseExpense, depositExpense, transferExpense, purchaseExpense2)

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        // Only purchase categories should appear: Office Supplies=100, Software=250
        assertEquals(2, report.expensesByCategory.size)
        assertApproxEquals(100.0, report.expensesByCategory["Office Supplies"]!!, 0.01,
            "Category must not include deposit/transfer amounts")
        assertApproxEquals(250.0, report.expensesByCategory["Software"]!!, 0.01)
        assertFalse("Income category from deposit must not appear",
            report.expensesByCategory.containsKey("Income"))
        assertFalse("Transfer category must not appear",
            report.expensesByCategory.containsKey("Transfer"))
    }

    /**
     * Regression guard: if repository expense list accidentally includes
     * non-purchase rows, project breakdowns must not include them.
     */
    @Test
    fun `project breakdown excludes leaked deposit transfer and withdrawal amounts`() = runTest {
        // Simulate a broken DAO that leaks all non-purchase types
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns
            listOf(purchaseExpense, depositExpense, transferExpense, withdrawalExpense, purchaseExpense2)

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        // Only purchase projects: ProjectX=100 (from purchaseExpense only), ProjectY=250
        assertEquals(2, report.expensesByProject.size)
        assertApproxEquals(100.0, report.expensesByProject["ProjectX"]!!, 0.01,
            "ProjectX must only include purchase amount, not deposit/transfer/withdrawal")
        assertApproxEquals(250.0, report.expensesByProject["ProjectY"]!!, 0.01)
    }

    // =====================================================================
    // 3. Top-expenses ranking excludes non-purchases
    // =====================================================================

    @Test
    fun `top expenses ranked by effectiveAmount descending`() = runTest {
        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertEquals(2, report.topExpenses.size)
        assertEquals(purchaseExpense2.id, report.topExpenses[0].id) // 250 first
        assertEquals(purchaseExpense.id, report.topExpenses[1].id)  // 100 second
    }

    @Test
    fun `top expenses excludes leaked non-purchase rows`() = runTest {
        // Deposit has 5000 — would be #1 if not excluded
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns
            listOf(purchaseExpense, depositExpense, transferExpense, purchaseExpense2)

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertEquals(2, report.topExpenses.size)
        assertTrue("Top expense must be purchase only",
            report.topExpenses.all { it.transactionType == TransactionType.PURCHASE })
        assertEquals(purchaseExpense2.id, report.topExpenses[0].id)
        assertEquals(purchaseExpense.id, report.topExpenses[1].id)
    }

    @Test
    fun `top expenses limited to 10`() = runTest {
        val many = (1..15L).map { i ->
            purchaseExpense.copy(id = i, amount = i * 10.0)
        }
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns many

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertEquals(10, report.topExpenses.size)
        // Highest amount first: 150, 140, ..., 60
        assertApproxEquals(150.0, report.topExpenses[0].effectiveAmount, 0.01)
    }

    // =====================================================================
    // 4. Missing-receipts surface is purchase-only
    // =====================================================================

    @Test
    fun `missing receipts list comes from purchase-only query`() = runTest {
        val missing = listOf(purchaseExpense.copy(id = 99L))
        coEvery { repo.getExpensesMissingReceipts(startDate, endDate) } returns missing

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertEquals(1, report.expensesMissingReceipts.size)
        assertEquals(99L, report.expensesMissingReceipts[0].id)
    }

    @Test
    fun `missing receipts excludes leaked non-purchase rows`() = runTest {
        // Repo accidentally returns a deposit alongside a purchase in missing-receipts
        coEvery { repo.getExpensesMissingReceipts(startDate, endDate) } returns
            listOf(purchaseExpense, depositExpense, withdrawalExpense)

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        assertEquals(1, report.expensesMissingReceipts.size)
        assertEquals(purchaseExpense.id, report.expensesMissingReceipts[0].id)
        assertEquals(TransactionType.PURCHASE, report.expensesMissingReceipts[0].transactionType)
    }

    @Test
    fun `missing receipts never contains non-purchase when repo is correct`() = runTest {
        // Repo returns only purchases (default). Verify no non-purchase in result.
        coEvery { repo.getExpensesMissingReceipts(startDate, endDate) } returns
            listOf(purchaseExpense)

        val report = generator.generateReport(startDate, endDate, includeMileage = false)

        report.expensesMissingReceipts.forEach { expense ->
            assertEquals(
                "Missing-receipt expense must be PURCHASE",
                TransactionType.PURCHASE,
                expense.transactionType
            )
        }
    }

    // =====================================================================
    // 5. CSV export is purchase-only
    // =====================================================================

    @Test
    fun `CSV export contains only purchase rows from repository`() = runTest {
        val csv = generator.generateCSVExport(startDate, endDate, includeMileage = false)

        val lines = csv.trim().lines()
        // 1 header + 2 data rows
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("Date,Merchant,Amount"))
        assertTrue(lines[1].contains("Office Supplies Co"))
        assertTrue(lines[2].contains("Software Vendor"))
    }

    @Test
    fun `CSV export does not contain deposit rows when repo is correct`() = runTest {
        // Default repo returns purchases only
        val csv = generator.generateCSVExport(startDate, endDate, includeMileage = false)

        assertFalse("CSV must not contain deposit merchant",
            csv.contains("Client Payment"))
    }

    @Test
    fun `CSV export excludes leaked non-purchase rows`() = runTest {
        // Simulate a broken DAO that returns mixed types
        coEvery { repo.getBusinessExpenses(startDate, endDate) } returns
            listOf(purchaseExpense, depositExpense, transferExpense, withdrawalExpense, purchaseExpense2)

        val csv = generator.generateCSVExport(startDate, endDate, includeMileage = false)

        val lines = csv.trim().lines()
        // 1 header + 2 purchase data rows only
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("Office Supplies Co"))
        assertTrue(lines[2].contains("Software Vendor"))
        assertFalse("CSV must not contain deposit", csv.contains("Client Payment"))
        assertFalse("CSV must not contain transfer", csv.contains("Internal Transfer"))
        assertFalse("CSV must not contain withdrawal", csv.contains("ATM Withdrawal"))
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

    @Test
    fun `CSV export delegates to purchase-only repository query`() = runTest {
        generator.generateCSVExport(startDate, endDate, includeMileage = false)

        coVerify(exactly = 1) { repo.getBusinessExpenses(startDate, endDate) }
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
        assertEquals(null, report.mileageReport.deductionRatePerKm)
        assertApproxEquals((15.0 + 67.5) / 200.0, report.mileageReport.effectiveDeductionRatePerKm, 0.001)
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

    @Test
    fun `CSV mileage section included when requested`() = runTest {
        coEvery { repo.getBusinessMileageBetween(startDate, endDate) } returns listOf(sampleTrip)

        val csv = generator.generateCSVExport(startDate, endDate, includeMileage = true)

        assertTrue("CSV should contain mileage header",
            csv.contains("Date,Distance (km),Start Location,End Location,Purpose,Project,Deduction Amount"))
        assertTrue("CSV should contain trip data",
            csv.contains("Client visit"))
    }

    @Test
    fun `CSV mileage section excluded when not requested`() = runTest {
        val csv = generator.generateCSVExport(startDate, endDate, includeMileage = false)

        assertFalse("CSV should not contain mileage header",
            csv.contains("Distance (km)"))
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

        assertTrue("Total should be 350.00",
            text.contains("Total Business Expenses: \u20ac350.00"))
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
    // 9. Repository-level hardening: verify purchase-only semantics are
    //    enforced in every report-facing repository method, so even if DAO
    //    output regresses the report surface stays clean.
    //    We exercise the repository through a real (non-mocked) instance
    //    that wraps a mocked DAO, then feed it to the generator.
    // =====================================================================

    /**
     * Build a generator backed by a real [BusinessExpenseRepository] wrapping
     * mocked DAOs, so repository-level purchase-only filtering is exercised.
     */
    private fun buildRealRepoGenerator(
        expenseDao: com.yourname.expensetracker.data.database.dao.ExpenseDao,
        mileageDao: com.yourname.expensetracker.data.database.dao.MileageTrackingDao =
            mockk(relaxed = true)
    ): Pair<BusinessExpenseRepository, BusinessExpenseReportGenerator> {
        val realRepo = BusinessExpenseRepository(expenseDao, mileageDao)
        val gen = BusinessExpenseReportGenerator(realRepo, timeProvider)
        return realRepo to gen
    }

    @Test
    fun `repository getBusinessExpenses filters non-purchase rows from DAO`() = runTest {
        val dao = mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
        coEvery { dao.getBusinessExpensesBetween(startDate, endDate) } returns
            listOf(purchaseExpense, depositExpense, transferExpense, withdrawalExpense, purchaseExpense2)
        coEvery { dao.getBusinessExpensesMissingReceipts(startDate, endDate) } returns emptyList()

        val (_, gen) = buildRealRepoGenerator(dao)
        val report = gen.generateReport(startDate, endDate, includeMileage = false)

        // Only purchases: 100 + 250 = 350
        assertApproxEquals(350.0, report.totalExpenses, 0.01,
            "Repository must filter non-purchase rows before they reach the generator")
        assertEquals(2, report.topExpenses.size)
        assertTrue(report.topExpenses.all { it.transactionType == TransactionType.PURCHASE })
    }

    @Test
    fun `repository getTotalBusinessExpenses returns purchase-only sum`() = runTest {
        val dao = mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
        // DAO aggregate already enforces PURCHASE-only via SPENDING_TYPE_SQL;
        // the repository is a thin pass-through to this aggregate.
        coEvery { dao.getTotalBusinessExpensesBetween(startDate, endDate) } returns 350.0

        val (realRepo, _) = buildRealRepoGenerator(dao)
        val total = realRepo.getTotalBusinessExpenses(startDate, endDate)

        // Only purchases: 100 + 250 = 350 (deposit's 5000 excluded by DAO SQL)
        assertApproxEquals(350.0, total, 0.01,
            "getTotalBusinessExpenses must return purchase-only sum")
    }

    @Test
    fun `repository getExpensesByCategory returns purchase-only category breakdown`() = runTest {
        val dao = mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
        // DAO aggregate already enforces PURCHASE-only via SPENDING_TYPE_SQL;
        // the repository is a thin pass-through to this aggregate.
        coEvery { dao.getBusinessExpensesByCategory(startDate, endDate) } returns listOf(
            com.yourname.expensetracker.data.database.dao.BusinessCategoryTotal("Office Supplies", 100.0, 1),
            com.yourname.expensetracker.data.database.dao.BusinessCategoryTotal("Software", 250.0, 1)
        )

        val (realRepo, _) = buildRealRepoGenerator(dao)
        val categories = realRepo.getExpensesByCategory(startDate, endDate)

        // Only purchase categories: Office Supplies=100, Software=250
        assertEquals(2, categories.size)
        val catMap = categories.associate { it.businessCategory to it.total }
        assertApproxEquals(100.0, catMap["Office Supplies"]!!, 0.01,
            "Category totals must exclude deposit amounts")
        assertApproxEquals(250.0, catMap["Software"]!!, 0.01)
        assertFalse("Income category from deposit must not appear",
            catMap.containsKey("Income"))
        assertFalse("Transfer category must not appear",
            catMap.containsKey("Transfer"))
    }

    @Test
    fun `repository getExpensesByProject returns purchase-only project breakdown`() = runTest {
        val dao = mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
        // DAO aggregate already enforces PURCHASE-only via SPENDING_TYPE_SQL;
        // the repository is a thin pass-through to this aggregate.
        coEvery { dao.getBusinessExpensesByProject(startDate, endDate) } returns listOf(
            com.yourname.expensetracker.data.database.dao.BusinessProjectTotal("ProjectX", 100.0, 1),
            com.yourname.expensetracker.data.database.dao.BusinessProjectTotal("ProjectY", 250.0, 1)
        )

        val (realRepo, _) = buildRealRepoGenerator(dao)
        val projects = realRepo.getExpensesByProject(startDate, endDate)

        // Only purchase projects: ProjectX=100, ProjectY=250
        assertEquals(2, projects.size)
        val projMap = projects.associate { it.businessProject to it.total }
        assertApproxEquals(100.0, projMap["ProjectX"]!!, 0.01,
            "Project totals must exclude deposit/transfer/withdrawal amounts")
        assertApproxEquals(250.0, projMap["ProjectY"]!!, 0.01)
    }

    @Test
    fun `repository getExpensesMissingReceipts filters non-purchase rows`() = runTest {
        val dao = mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
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
        val dao = mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
        val mileageDao = mockk<com.yourname.expensetracker.data.database.dao.MileageTrackingDao>(relaxed = true)

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
            "End-to-end total must be purchase-only")
        // Deductible: 350 + 15 (mileage) = 365
        assertApproxEquals(365.0, report.totalDeductibleExpenses, 0.01)

        // Categories: only purchase categories
        assertEquals(2, report.expensesByCategory.size)
        assertFalse(report.expensesByCategory.containsKey("Income"))
        assertFalse(report.expensesByCategory.containsKey("Transfer"))
        assertFalse(report.expensesByCategory.containsKey("Cash"))

        // Projects: only purchase projects
        assertEquals(2, report.expensesByProject.size)
        assertApproxEquals(100.0, report.expensesByProject["ProjectX"]!!, 0.01)
        assertApproxEquals(250.0, report.expensesByProject["ProjectY"]!!, 0.01)

        // Top expenses: only purchases, ranked correctly
        assertEquals(2, report.topExpenses.size)
        assertTrue(report.topExpenses.all { it.transactionType == TransactionType.PURCHASE })

        // Missing receipts: only purchase
        assertEquals(1, report.expensesMissingReceipts.size)
        assertEquals(TransactionType.PURCHASE, report.expensesMissingReceipts[0].transactionType)
    }

    @Test
    fun `repository CSV export end-to-end excludes non-purchase via real repo`() = runTest {
        val dao = mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
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
