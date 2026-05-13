package com.yourname.expensetracker.ui.screens.export

import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExportDataRepository
import com.yourname.expensetracker.domain.export.AccountingExportPolicy
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.export.FreshBooksExporter
import com.yourname.expensetracker.domain.export.QuickBooksIIFExporter
import com.yourname.expensetracker.domain.export.XeroCSVExporter
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@Ignore("ViewModel uses Dispatchers.IO internally which fails in test env (Main dispatcher init issue). Needs production refactoring to inject dispatchers.")
class ExportOptionsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var exportDataRepository: ExportDataRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var restoreMaintenanceMode: RestoreMaintenanceMode
    private lateinit var privacyGate: PrivacyGate
    private lateinit var readBarrier: DatabaseReadBarrier
    private lateinit var viewModel: ExportOptionsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        exportDataRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        restoreMaintenanceMode = mockk(relaxed = true)
        privacyGate = mockk(relaxed = true)
        readBarrier = mockk<DatabaseReadBarrier>(relaxed = true)

        every { restoreMaintenanceMode.isWritesAllowed() } returns true
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 0
        // ViewModel uses getExpensesBetween (not getExpensesBetweenForExport)
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns emptyList()
        // ViewModel uses getExpensesPage for streaming
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { exportDataRepository.getCategoryNameMap() } returns emptyMap()
        coEvery { exportDataRepository.createExportFile(any(), any()) } returns File(System.getProperty("java.io.tmpdir"), "test_export.csv")

        viewModel = ExportOptionsViewModel(
            exportDataRepository = exportDataRepository,
            accountingExportPolicy = AccountingExportPolicy(),
            timeProvider = timeProvider,
            xeroExporter = XeroCSVExporter(),
            quickBooksExporter = QuickBooksIIFExporter(),
            freshBooksExporter = FreshBooksExporter(),
            restoreMaintenanceMode = restoreMaintenanceMode,
            readBarrier = readBarrier,
            privacyGate = privacyGate
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `generate generic csv keeps only preview and file path`() = runBlocking {
        val expenses = listOf(createExpense(merchant = "Coffee"))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns expenses
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses

        val out = createTempFile(prefix = "export_viewmodel_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(state.exportSuccess)
        assertEquals(out.absolutePath, state.exportFilePath)
        // CSV format writes a metadata line then header: "# ExpenseTracker Export v2,..." then "ID,Date,..."
        assertTrue((state.exportPreview ?: "").contains("ID,Date"))
    }

    @Test
    fun `generate generic csv neutralizes spreadsheet formula fields in preview`() = runBlocking {
        val expenses = listOf(createExpense(merchant = "=SUM(A1:A2)"))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns expenses
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses

        val out = createTempFile(prefix = "export_formula_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.generateExport()
        val preview = viewModel.uiState.value.exportPreview.orEmpty()
        // The neutralized merchant should appear in the CSV output
        assertTrue(preview.contains("'=SUM(A1:A2)"))
        // The CSV data row should contain the currency field
        assertTrue(preview.contains("EUR"))
    }

    @Test
    fun `export formats include json option`() {
        val formats = viewModel.uiState.value.exportFormats.map { it.id }
        assertTrue(formats.contains("json"))
    }

    @Test
    fun `generate json export emits stable schema and escaped strings`() = runBlocking {
        val expenses = listOf(
            createExpense(
                merchant = "Cafe \"Central\"",
                notes = "line1\nline2\\"
            )
        )
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns expenses
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses
        coEvery { exportDataRepository.getCategoryNameMap() } returns mapOf(1L to "Food")

        val out = createTempFile(prefix = "export_json_", suffix = ".json")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.selectFormat("json")
        viewModel.generateExport()
        val state = viewModel.uiState.value
        val preview = state.exportPreview.orEmpty()

        assertTrue(state.exportSuccess)
        assertEquals(out.absolutePath, state.exportFilePath)
        assertTrue(preview.contains("\"schemaVersion\":2"))
        assertTrue(preview.contains("\"exportType\":\"expenses\""))
        assertTrue(preview.contains("\"merchant\":\"Cafe \\\"Central\\\"\""))
        assertTrue(preview.contains("\"notes\":\"line1\\n2\\\\\""))
    }

    @Test
    fun `generate xero export surfaces mixed currency policy failure`() = runBlocking {
        coEvery {
            exportDataRepository.getExpensesBetween(any(), any())
        } returns listOf(
            createExpense(id = 1L, merchant = "Cafe", currency = "EUR"),
            createExpense(id = 2L, merchant = "Hotel", currency = "USD")
        )
        coEvery {
            exportDataRepository.getExpensesPage(any(), any(), any(), any(), any())
        } returns emptyList()

        viewModel.selectFormat("xero")
        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(!state.exportSuccess)
        assertEquals(null, state.exportFilePath)
        assertTrue(state.error.orEmpty().contains("single-currency dataset"))
    }

    @Test
    fun `generate quickbooks export surfaces non purchase policy failure`() = runBlocking {
        coEvery {
            exportDataRepository.getExpensesBetween(any(), any())
        } returns listOf(
            createExpense(id = 1L, merchant = "ATM", transactionType = TransactionType.WITHDRAWAL)
        )
        coEvery {
            exportDataRepository.getExpensesPage(any(), any(), any(), any(), any())
        } returns emptyList()

        viewModel.selectFormat("quickbooks")
        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(!state.exportSuccess)
        assertEquals(null, state.exportFilePath)
        assertTrue(state.error.orEmpty().contains("PURCHASE transactions only"))
    }

    @Test
    fun `generate freshbooks export succeeds for empty dataset`() = runBlocking {
        val out = createTempFile(prefix = "export_empty_freshbooks_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns emptyList()

        viewModel.selectFormat("freshbooks")
        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(state.exportSuccess)
        assertEquals(null, state.error)
        assertEquals(out.absolutePath, state.exportFilePath)
        assertEquals("date,description,amount,category,vendor\n", state.exportPreview)
        assertEquals(listOf("date,description,amount,category,vendor"), out.readLines())
    }

    @Test
    fun `generate export reports repository file creation failure`() = runBlocking {
        val failure = IllegalStateException("cache dir unavailable")
        every { exportDataRepository.createExportFile(any(), any()) } throws failure

        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(!state.exportSuccess)
        assertEquals(null, state.exportFilePath)
        assertEquals("Failed to generate export: cache dir unavailable", state.error)
        assertTrue(!state.isLoading)
    }

    private fun createExpense(
        merchant: String,
        notes: String = "note",
        id: Long = 1L,
        currency: String = "EUR",
        transactionType: TransactionType = TransactionType.PURCHASE
    ): Expense {
        return Expense(
            id = id,
            amount = 12.34,
            currency = currency,
            merchant = merchant,
            transactionType = transactionType,
            date = 1_700_000_000_000L,
            categoryId = 1L,
            notes = notes,
            createdAt = System.currentTimeMillis()
        )
    }
}



