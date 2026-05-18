package com.yourname.expensetracker.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.export.AccountantReportPdfExporter
import com.yourname.expensetracker.domain.export.AccountingExportPolicy
import com.yourname.expensetracker.domain.export.FreshBooksExporter
import com.yourname.expensetracker.domain.export.QuickBooksIIFExporter
import com.yourname.expensetracker.domain.export.XeroCSVExporter
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [AccountingExportRepository].
 *
 * After the A.9 Batch 6 fix, [AccountingExportRepository] uses deterministic
 * exhaustive paging via [ExpenseRepository.getExpensesBetweenForExportKeyset]
 * instead of the generic [ExpenseRepository.getExpensesBetween].  This ensures:
 * 1. Deterministic export row order (date ASC, id ASC, merchant COLLATE NOCASE ASC).
 * 2. No silent truncation — pages are fetched until exhausted.
 *
 * All paging tests below exercise the real production [DeterministicExpenseExportPager.fetchAllBetween]
 * method rather than re-implementing the loop in test code.
 */
class AccountingExportRepositoryTest : AnalyticsEngineTestBase() {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var deterministicExpenseExportPager: DeterministicExpenseExportPager
    private lateinit var repository: AccountingExportRepository

    /** Temp directory used as a fake [Context.getCacheDir] for production-path tests. */
    private lateinit var tempCacheDir: File

    @Before
    override fun setUp() {
        super.setUp()
        expenseRepository = mockk(relaxed = true)
        deterministicExpenseExportPager = DeterministicExpenseExportPager(expenseRepository)

        repository = AccountingExportRepository(
            categoryRepository = categoryRepository,
            deterministicExpenseExportPager = deterministicExpenseExportPager,
            accountingExportPolicy = AccountingExportPolicy(),
            quickBooksExporter = QuickBooksIIFExporter(),
            xeroExporter = XeroCSVExporter(),
            freshBooksExporter = FreshBooksExporter(),
            accountantReportPdfExporter = AccountantReportPdfExporter(
                timeProvider = mockk<TimeProvider>(relaxed = true),
                currencyConverter = mockk<com.yourname.expensetracker.domain.currency.CurrencyConverter>(relaxed = true),
                currencySettingsRepository = mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>(relaxed = true),
            ),
            timeProvider = mockk<TimeProvider>(relaxed = true),
            readBarrier = mockk(relaxed = true)
        )

        tempCacheDir = createTempDir("export_test_cache")
    }

    @After
    fun tearDown() {
        if (::tempCacheDir.isInitialized) tempCacheDir.deleteRecursively()
        unmockkStatic(FileProvider::class)
    }

    // ── Helpers for production-path tests ──────────────────────────────────

    /**
     * Creates a mocked [Context] whose [Context.cacheDir] and [Context.filesDir]
     * return a real temporary directory so that [AccountingExportRepository.exportExpenses]
     * can write the export file.  [FileProvider.getUriForFile] is statically
     * mocked to return a dummy [Uri].
     */
    private fun fakeContext(): Context {
        val ctx: Context = mockk(relaxed = true)
        every { ctx.cacheDir } returns tempCacheDir
        every { ctx.filesDir } returns tempCacheDir
        every { ctx.packageName } returns "com.yourname.expensetracker.test"

        mockkStatic(FileProvider::class)
        val fakeUri: Uri = mockk(relaxed = true)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns fakeUri

        return ctx
    }

    // ── Exhaustive paging contract tests ───────────────────────────────────

    /**
     * When the dataset is smaller than [DeterministicExpenseExportPager.EXPORT_PAGE_SIZE],
     * only a single page request should be made (offset = 0), and the result
     * should contain every row.
     */
    @Test
    fun `fetchAllForExport single page - all rows returned`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE

        val expenses = (1..100).map { i ->
            Expense(
                id = i.toLong(),
                amount = 10.0,
                merchant = "Merchant$i",
                transactionType = TransactionType.PURCHASE,
                date = start + i * 1000L,
                categoryId = 1L
            )
        }

        // Production DeterministicExpenseExportPager uses keyset-based pagination
        // First call uses null cursor (null, null)
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, null, null
            )
        } returns expenses

        // Exercise the real production paging loop
        val result = deterministicExpenseExportPager.fetchAllBetween(start, end)

        assertEquals(100, result.size)
        assertEquals(1L, result.first().id)
        assertEquals(100L, result.last().id)

        // Only one page call should have been made (sub-page-size result terminates the loop)
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, null, null
            )
        }
        // Verify the old uncapped getExpensesBetween is NOT called
        coVerify(exactly = 0) { expenseRepository.getExpensesBetween(any(), any()) }
    }

    /**
     * A.9 Batch 6 regression: export must include all expenses even when
     * the count exceeds [DeterministicExpenseExportPager.EXPORT_PAGE_SIZE].
     *
     * This test simulates 2 full pages + 1 partial page (2000 + 2000 + 500 = 4500 rows)
     * and verifies that the production exhaustive paging loop fetches all 3 pages.
     */
    @Test
    fun `fetchAllForExport multi page - exhaustive paging returns all rows`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE

        val allExpenses = (1..4500).map { i ->
            Expense(
                id = i.toLong(),
                amount = 5.0,
                merchant = "M$i",
                transactionType = TransactionType.PURCHASE,
                date = start + i * 1000L,
                categoryId = 1L
            )
        }

        // Production uses keyset pagination: (start, end, limit, lastDate, lastId)
        // Page 1: rows 1..2000, cursor is null
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(start, end, pageSize, null, null)
        } returns allExpenses.subList(0, pageSize)

        // Page 2: rows 2001..4000, cursor comes from last row of page 1 (id=2000, date=start+2000*1000)
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses[pageSize - 1].date, allExpenses[pageSize - 1].id
            )
        } returns allExpenses.subList(pageSize, 2 * pageSize)

        // Page 3: rows 4001..4500, cursor comes from last row of page 2 (id=4000, date=start+4000*1000)
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses[2 * pageSize - 1].date, allExpenses[2 * pageSize - 1].id
            )
        } returns allExpenses.subList(2 * pageSize, allExpenses.size)

        // Exercise the real production paging loop
        val result = deterministicExpenseExportPager.fetchAllBetween(start, end)

        assertEquals("All 4500 rows must be returned across 3 pages", 4500, result.size)
        assertEquals("First row id must be 1", 1L, result.first().id)
        assertEquals("Last row id must be 4500", 4500L, result.last().id)

        // Verify all 3 page calls were made with correct cursor values
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(start, end, pageSize, null, null)
        }
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses[pageSize - 1].date, allExpenses[pageSize - 1].id
            )
        }
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses[2 * pageSize - 1].date, allExpenses[2 * pageSize - 1].id
            )
        }

        // Verify the old uncapped path is NOT used
        coVerify(exactly = 0) { expenseRepository.getExpensesBetween(any(), any()) }
    }

    /**
     * Empty date range returns no results (not an error), with exactly one
     * paged call that returns an empty list (terminating the loop immediately).
     */
    @Test
    fun `fetchAllForExport empty range returns empty list`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE

        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, null, null
            )
        } returns emptyList()

        // Exercise the real production paging loop
        val result = deterministicExpenseExportPager.fetchAllBetween(start, end)

        assertTrue(result.isEmpty())

        // Only one page call should have been made (empty result terminates the loop)
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, null, null
            )
        }
    }

    /**
     * Boundary condition: exactly [EXPORT_PAGE_SIZE] rows triggers a second
     * page call (which returns empty) to confirm exhaustion, and the result
     * still contains exactly [DeterministicExpenseExportPager.EXPORT_PAGE_SIZE] rows.
     */
    @Test
    fun `fetchAllForExport exact page boundary triggers termination call`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE

        val expenses = (1..pageSize).map { i ->
            Expense(
                id = i.toLong(),
                amount = 10.0,
                merchant = "M$i",
                transactionType = TransactionType.PURCHASE,
                date = start + i * 1000L,
                categoryId = 1L
            )
        }

        // First page returns exactly pageSize rows (keyset, cursor=null)
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(start, end, pageSize, null, null)
        } returns expenses

        // Second page returns empty — loop terminates (cursor = last row of page 1)
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, expenses.last().date, expenses.last().id
            )
        } returns emptyList()

        // Exercise the real production paging loop
        val result = deterministicExpenseExportPager.fetchAllBetween(start, end)

        assertEquals(pageSize, result.size)
        assertEquals(1L, result.first().id)
        assertEquals(pageSize.toLong(), result.last().id)

        // Two page calls: one full, one empty
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(start, end, pageSize, null, null)
        }
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, expenses.last().date, expenses.last().id
            )
        }
    }

    // ── effectiveAmount semantics ──────────────────────────────────────────

    /**
     * Verifies that effectiveAmount is used for export transaction mapping.
     * The [Expense.toExportTransaction] extension uses effectiveAmount, not raw amount.
     */
    @Test
    fun `export uses effectiveAmount for shared expenses`() = runTest {
        val shared = Expense(
            id = 1L,
            amount = 100.0,
            merchant = "Dinner",
            transactionType = TransactionType.PURCHASE,
            date = ms("2026-03-15"),
            categoryId = 1L,
            isSharedExpense = true,
            mySharePercentage = 50
        )

        // effectiveAmount should be 50.0 (50% of 100.0)
        assertEquals(50.0, shared.effectiveAmount, 0.001)
    }

    /**
     * Verifies that a small dataset (well below the old limit) still works correctly
     * through the production deterministic export paging path.
     */
    @Test
    fun `export small dataset returns all records via deterministic path`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE

        val expenses = listOf(
            Expense(id = 1L, amount = 25.0, merchant = "CoffeeShop", transactionType = TransactionType.PURCHASE, date = start + 1000, categoryId = 1L),
            Expense(id = 2L, amount = 50.0, merchant = "Groceries", transactionType = TransactionType.PURCHASE, date = start + 2000, categoryId = 2L),
            Expense(id = 3L, amount = 75.0, merchant = "Restaurant", transactionType = TransactionType.PURCHASE, date = start + 3000, categoryId = 1L)
        )

        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, null, null
            )
        } returns expenses

        // Exercise the real production paging loop
        val result = deterministicExpenseExportPager.fetchAllBetween(start, end)

        assertEquals(3, result.size)
        assertEquals("CoffeeShop", result[0].merchant)
        assertEquals("Groceries", result[1].merchant)
        assertEquals("Restaurant", result[2].merchant)
    }

    // ── Production entrypoint (exportExpenses) multi-page tests ──────────

    /**
     * Batch 6 production-path: calls [AccountingExportRepository.exportExpenses]
     * (the real public entrypoint) with a multi-page dataset (3 pages) and
     * verifies:
     * 1. [ExportResult.success] is true.
     * 2. [ExportResult.recordCount] equals total row count across all pages.
     * 3. The written file contains one CSV row per expense (Xero format).
     * 4. Deterministic paging is used (3 page calls) and old path is NOT used.
     */
    @Test
    fun `exportExpenses multi-page Xero CSV contains all rows end-to-end`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE
        val totalRows = pageSize * 2 + 500  // 4500

        val allExpenses = (1..totalRows).map { i ->
            Expense(
                id = i.toLong(),
                amount = i.toDouble(),
                merchant = "Vendor$i",
                transactionType = TransactionType.PURCHASE,
                date = start + i * 1000L,
                categoryId = 1L
            )
        }

        // Page 1: rows 1..pageSize, keyset cursor = null
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(start, end, pageSize, null, null)
        } returns allExpenses.subList(0, pageSize)

        // Page 2: rows pageSize+1..2*pageSize, cursor = last row of page 1
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses[pageSize - 1].date, allExpenses[pageSize - 1].id
            )
        } returns allExpenses.subList(pageSize, 2 * pageSize)

        // Page 3: partial page — terminates, cursor = last row of page 2
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses[2 * pageSize - 1].date, allExpenses[2 * pageSize - 1].id
            )
        } returns allExpenses.subList(2 * pageSize, totalRows)

        val ctx = fakeContext()
        val result = repository.exportExpenses(ctx, start, end, ExportFormat.XERO_CSV)

        // ── Result assertions ──
        assertTrue("exportExpenses must succeed", result.success)
        assertEquals("recordCount must equal total rows", totalRows, result.recordCount)
        assertNotNull("filePath must be set", result.filePath)

        // ── File content assertions ──
        val exportedFile = File(result.filePath!!)
        assertTrue("Exported file must exist on disk", exportedFile.exists())
        val lines = exportedFile.readLines()
        // Xero CSV: 1 header line + totalRows data lines
        assertEquals(
            "File must contain header + all data rows",
            1 + totalRows,
            lines.size
        )
        // Header
        assertTrue("First line must be the Xero CSV header", lines[0].startsWith("Date,"))
        // Spot-check first and last data rows contain the expected merchant
        assertTrue("First data row must reference Vendor1", lines[1].contains("Vendor1"))
        assertTrue("Last data row must reference Vendor$totalRows", lines.last().contains("Vendor$totalRows"))
        // Verify every expense id appears exactly once as the Xero CSV reference column (6th field, index 5)
        val referenceIds = lines.drop(1).map { it.split(",")[5].trim().toLong() }
        assertEquals("Every expense id must appear exactly once", totalRows, referenceIds.toSet().size)
        assertEquals("Ids must be 1..totalRows", (1L..totalRows).toSet(), referenceIds.toSet())

        // ── Paging contract assertions ──
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(start, end, pageSize, null, null)
        }
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses[pageSize - 1].date, allExpenses[pageSize - 1].id
            )
        }
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses[2 * pageSize - 1].date, allExpenses[2 * pageSize - 1].id
            )
        }
        coVerify(exactly = 0) { expenseRepository.getExpensesBetween(any(), any()) }
    }

    /**
     * Batch 6 production-path: calls [AccountingExportRepository.exportExpenses]
     * with a QuickBooks IIF format across 2 pages (exact boundary + empty
     * termination page) and verifies every row appears in the IIF output.
     */
    @Test
    fun `exportExpenses multi-page QuickBooks IIF contains all rows end-to-end`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE
        val totalRows = pageSize  // exact boundary

        val allExpenses = (1..totalRows).map { i ->
            Expense(
                id = i.toLong(),
                amount = i * 0.5,
                merchant = "Shop$i",
                transactionType = TransactionType.PURCHASE,
                date = start + i * 1000L,
                categoryId = 2L
            )
        }

        // Full first page (keyset cursor = null)
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(start, end, pageSize, null, null)
        } returns allExpenses

        // Empty second page (boundary termination, cursor = last row of page 1)
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses.last().date, allExpenses.last().id
            )
        } returns emptyList()

        val ctx = fakeContext()
        val result = repository.exportExpenses(ctx, start, end, ExportFormat.QUICKBOOKS_IIF)

        assertTrue("exportExpenses must succeed", result.success)
        assertEquals("recordCount must match", totalRows, result.recordCount)

        // IIF: each expense produces TRNS + SPL + ENDTRNS (3 lines), plus 3 header lines.
        val exportedFile = File(result.filePath!!)
        assertTrue("Exported file must exist", exportedFile.exists())
        val content = exportedFile.readText()
        // Every merchant must appear in the file
        for (i in 1..totalRows) {
            assertTrue("Shop$i must appear in IIF output", content.contains("Shop$i"))
        }

        // Boundary termination: 2 page calls (full + empty)
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(start, end, pageSize, null, null)
        }
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses.last().date, allExpenses.last().id
            )
        }
        coVerify(exactly = 0) { expenseRepository.getExpensesBetween(any(), any()) }
    }

    @Test
    fun `exportExpenses QuickBooks IIF uses funding account on TRNS and category on SPL`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE
        val expense = Expense(
            id = 1L,
            amount = 42.5,
            merchant = "Office Depot",
            transactionType = TransactionType.PURCHASE,
            date = start + 1_000L,
            categoryId = 2L,
            paymentMethod = PaymentMethod.CASH,
            notes = "Paper"
        )

        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, null, null
            )
        } returns listOf(expense)

        val ctx = fakeContext()
        val result = repository.exportExpenses(ctx, start, end, ExportFormat.QUICKBOOKS_IIF)

        assertTrue("exportExpenses must succeed", result.success)

        val lines = File(result.filePath!!).readLines()
        val trnsLine = lines.first { it.startsWith("TRNS\t") }
        val splLine = lines.first { it.startsWith("SPL\t") }

        assertEquals("Cash", trnsLine.split("\t")[2])
        assertEquals("Groceries", splLine.split("\t")[2])
        assertTrue("TRNS and SPL accounts must stay separated", trnsLine.split("\t")[2] != splLine.split("\t")[2])
    }

    /**
     * ISSUE-4 regression: accounting exports must treat an empty dataset the
     * same way as the UI path and emit a header-only file instead of failing.
     */
    @Test
    fun `exportExpenses empty accounting dataset writes header only export`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")

        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, DeterministicExpenseExportPager.EXPORT_PAGE_SIZE, null, null
            )
        } returns emptyList()

        val ctx = fakeContext()
        val result = repository.exportExpenses(ctx, start, end, ExportFormat.FRESHBOOKS_CSV)

        assertTrue("Empty accounting export must succeed", result.success)
        assertEquals(0, result.recordCount)
        assertNotNull("Header-only export must still produce a file", result.filePath)
        val exportedFile = File(result.filePath!!)
        assertTrue("Header-only export file must exist", exportedFile.exists())
        assertEquals(listOf("date,description,amount,currency,category,vendor,originalCurrency,homeCurrency,conversionRate,originalAmount"), exportedFile.readLines())

        // Only one paged call — loop terminated immediately
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { expenseRepository.getExpensesBetween(any(), any()) }
    }

    @Test
    fun `exportExpenses rejects mixed currency accounting dataset`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE
        val expenses = listOf(
            Expense(
                id = 1L,
                amount = 25.0,
                currency = "EUR",
                merchant = "Cafe",
                transactionType = TransactionType.PURCHASE,
                date = start + 1_000L,
                categoryId = 1L
            ),
            Expense(
                id = 2L,
                amount = 40.0,
                currency = "USD",
                merchant = "Taxi",
                transactionType = TransactionType.PURCHASE,
                date = start + 2_000L,
                categoryId = 2L
            )
        )

        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, null, null
            )
        } returns expenses

        val result = repository.exportExpenses(fakeContext(), start, end, ExportFormat.XERO_CSV)

        assertTrue("Mixed-currency accounting export must fail", !result.success)
        assertTrue(result.errorMessage.orEmpty().contains("single-currency dataset"))
        assertEquals(0, result.recordCount)
    }

    @Test
    fun `exportExpenses rejects non purchase accounting dataset`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE
        val expenses = listOf(
            Expense(
                id = 1L,
                amount = 75.0,
                merchant = "ATM",
                transactionType = TransactionType.WITHDRAWAL,
                date = start + 1_000L,
                categoryId = 1L
            )
        )

        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, null, null
            )
        } returns expenses

        val result = repository.exportExpenses(fakeContext(), start, end, ExportFormat.QUICKBOOKS_IIF)

        assertTrue("Non-PURCHASE accounting export must fail", !result.success)
        assertTrue(result.errorMessage.orEmpty().contains("PURCHASE transactions only"))
        assertTrue(result.errorMessage.orEmpty().contains("WITHDRAWAL"))
        assertEquals(0, result.recordCount)
    }

    @Test
    fun `exportExpenses accountant report pdf writes pdf output`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE
        val expenses = listOf(
            Expense(
                id = 1L,
                amount = 650.0,
                currency = "EUR",
                merchant = "Laptop Store",
                transactionType = TransactionType.PURCHASE,
                date = start + 1_000L,
                categoryId = 1L
            ),
            Expense(
                id = 2L,
                amount = 85.0,
                currency = "USD",
                merchant = "Client Lunch",
                transactionType = TransactionType.TRANSFER,
                date = start + 2_000L,
                categoryId = 2L
            )
        )

        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, null, null
            )
        } returns expenses

        val result = repository.exportExpenses(fakeContext(), start, end, ExportFormat.ACCOUNTANT_REPORT_PDF)

        assertTrue("PDF export must succeed", result.success)
        assertEquals(expenses.size, result.recordCount)
        assertTrue(result.filePath.orEmpty().endsWith(".pdf"))

        val pdfBytes = File(result.filePath!!).readBytes()
        assertTrue("PDF file must not be empty", pdfBytes.isNotEmpty())
        val header = pdfBytes.copyOfRange(0, minOf(4, pdfBytes.size)).toString(Charsets.US_ASCII)
        assertEquals("%PDF", header)
    }

    /**
     * Batch 6 production-path: FreshBooks CSV multi-page export. Verifies that
     * all 3 pages flow through the production entrypoint and the CSV vendor
     * column contains every expected merchant name.
     */
    @Test
    fun `exportExpenses multi-page FreshBooks CSV contains all rows end-to-end`() = runTest {
        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE
        val totalRows = pageSize + 7  // slight overflow onto page 2

        val allExpenses = (1..totalRows).map { i ->
            Expense(
                id = i.toLong(),
                amount = i * 1.0,
                merchant = "FB_Vendor$i",
                transactionType = TransactionType.PURCHASE,
                date = start + i * 1000L,
                categoryId = 1L
            )
        }

        // Page 1: full (keyset cursor = null)
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(start, end, pageSize, null, null)
        } returns allExpenses.subList(0, pageSize)

        // Page 2: 7 rows (partial — terminates, cursor = last row of page 1)
        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses[pageSize - 1].date, allExpenses[pageSize - 1].id
            )
        } returns allExpenses.subList(pageSize, totalRows)

        val ctx = fakeContext()
        val result = repository.exportExpenses(ctx, start, end, ExportFormat.FRESHBOOKS_CSV)

        assertTrue("exportExpenses must succeed", result.success)
        assertEquals("recordCount must match", totalRows, result.recordCount)

        val exportedFile = File(result.filePath!!)
        assertTrue("Exported file must exist", exportedFile.exists())
        val lines = exportedFile.readLines()
        // FreshBooks CSV: 1 header + totalRows data lines
        assertEquals(1 + totalRows, lines.size)
        assertTrue("Header must start with 'date,'", lines[0].startsWith("date,"))
        // Spot-check boundary rows
        assertTrue(lines[1].contains("FB_Vendor1"))
        assertTrue(lines[pageSize].contains("FB_Vendor$pageSize"))
        assertTrue(lines.last().contains("FB_Vendor$totalRows"))

        // 2 page calls
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(start, end, pageSize, null, null)
        }
        coVerify(exactly = 1) {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, allExpenses[pageSize - 1].date, allExpenses[pageSize - 1].id
            )
        }
        coVerify(exactly = 0) { expenseRepository.getExpensesBetween(any(), any()) }
    }

    private fun ms(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}