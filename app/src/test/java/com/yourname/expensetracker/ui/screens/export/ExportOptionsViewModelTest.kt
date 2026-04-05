package com.yourname.expensetracker.ui.screens.export

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExportDataRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ExportOptionsViewModelTest : ViewModelTestUtils() {

    private lateinit var exportDataRepository: ExportDataRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var viewModel: ExportOptionsViewModel

    @Before
    override fun setup() {
        super.setup()
        exportDataRepository = mockk(relaxed = true)
        timeProvider = mockk()

        every { timeProvider.now() } returns 1_700_000_000_000L
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 0
        coEvery { exportDataRepository.getExpensesBetweenPaged(any(), any(), any(), any()) } returns emptyList()
        coEvery { exportDataRepository.getCategoryNameMap() } returns emptyMap()
        every { exportDataRepository.createExportFile(any(), any()) } returns File("build/tmp/test_export.csv")

        viewModel = ExportOptionsViewModel(exportDataRepository, timeProvider)
    }

    @Test
    fun `generate generic csv keeps only preview and file path`() = runTest(testDispatcher) {
        val expenses = listOf(createExpense(merchant = "Coffee"))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesBetweenPaged(any(), any(), any(), 0) } returns expenses
        coEvery { exportDataRepository.getExpensesBetweenPaged(any(), any(), any(), 1) } returns emptyList()

        val out = createTempFile(prefix = "export_viewmodel_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.generateExport()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.exportSuccess)
        assertEquals(out.absolutePath, state.exportFilePath)
        assertTrue((state.exportPreview ?: "").startsWith("Date,Merchant,Amount"))
    }

    @Test
    fun `generate generic csv neutralizes spreadsheet formula fields in preview`() = runTest(testDispatcher) {
        val expenses = listOf(createExpense(merchant = "=SUM(A1:A2)"))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesBetweenPaged(any(), any(), any(), 0) } returns expenses
        coEvery { exportDataRepository.getExpensesBetweenPaged(any(), any(), any(), 1) } returns emptyList()

        val out = createTempFile(prefix = "export_formula_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.generateExport()
        advanceUntilIdle()

        val preview = viewModel.uiState.value.exportPreview.orEmpty()
        assertTrue(preview.contains("'=SUM(A1:A2)"))
    }

    @Test
    fun `export formats include json option`() {
        val formats = viewModel.uiState.value.exportFormats.map { it.id }
        assertTrue(formats.contains("json"))
    }

    @Test
    fun `generate json export emits stable schema and escaped strings`() = runTest(testDispatcher) {
        val expenses = listOf(
            createExpense(
                merchant = "Cafe \"Central\"",
                notes = "line1\nline2\\"
            )
        )
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesBetweenPaged(any(), any(), any(), 0) } returns expenses
        coEvery { exportDataRepository.getExpensesBetweenPaged(any(), any(), any(), 1) } returns emptyList()
        coEvery { exportDataRepository.getCategoryNameMap() } returns mapOf(1L to "Food")

        val out = createTempFile(prefix = "export_json_", suffix = ".json")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.selectFormat("json")
        viewModel.generateExport()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val preview = state.exportPreview.orEmpty()

        assertTrue(state.exportSuccess)
        assertEquals(out.absolutePath, state.exportFilePath)
        assertTrue(preview.contains("\"schemaVersion\":1"))
        assertTrue(preview.contains("\"exportType\":\"expenses\""))
        assertTrue(preview.contains("\"merchant\":\"Cafe \\\"Central\\\"\""))
        assertTrue(preview.contains("\"notes\":\"line1\\nline2\\\\\""))
    }

    private fun createExpense(
        merchant: String,
        notes: String = "note"
    ): Expense {
        return Expense(
            id = 1L,
            amount = 12.34,
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L,
            categoryId = 1L,
            notes = notes
        )
    }
}
