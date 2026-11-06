package com.yourname.expensetracker.ui.screens.export

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.repository.ExportDataRepository
import com.yourname.expensetracker.domain.export.AccountingExportPolicy
import com.yourname.expensetracker.domain.export.FreshBooksExporter
import com.yourname.expensetracker.domain.export.QuickBooksIIFExporter
import com.yourname.expensetracker.domain.export.XeroCSVExporter
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * J-12-1: dismissing the export result ([ExportOptionsViewModel.clearExport]) deletes the
 * unshared export file through [ExportDataRepository.deleteExportFile]; a file the screen marked
 * as shared via [ExportOptionsViewModel.markExportShared] is kept for the receiver. A delete
 * failure never reaches the screen. Dispatchers are unconfined, so the delete launched by
 * `clearExport` has completed when it returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportOptionsViewModelClearExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val nowMs = 1_700_000_000_000L

    private lateinit var exportDataRepository: ExportDataRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var privacyGate: PrivacyGate

    private val logs = mutableListOf<Pair<String, Throwable?>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += message to t
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        Timber.plant(logTree)

        exportDataRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        privacyGate = mockk(relaxed = true)
        every { timeProvider.now() } returns nowMs
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 7
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { exportDataRepository.getSourceLinksForExpenses(any()) } returns emptyMap()
        coEvery { exportDataRepository.getCategoryNameMap() } returns emptyMap()
    }

    @After
    fun tearDown() {
        Timber.uproot(logTree)
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): ExportOptionsViewModel = ExportOptionsViewModel(
        exportDataRepository = exportDataRepository,
        accountingExportPolicy = AccountingExportPolicy(),
        timeProvider = timeProvider,
        xeroExporter = XeroCSVExporter(),
        quickBooksExporter = QuickBooksIIFExporter(),
        freshBooksExporter = FreshBooksExporter(),
        readBarrier = mockk<DatabaseReadBarrier>(relaxed = true),
        privacyGate = privacyGate,
        exportJobSerializer = ExportJobSerializer(),
        ioDispatcher = testDispatcher
    )

    private fun newExportFile(name: String = "expenses_${nowMs}.csv"): File {
        val out = tmp.newFile(name)
        every { exportDataRepository.createExportFile(any(), any()) } returns out
        return out
    }

    @Test
    fun `clearExport deletes an unshared export file through the repository`() {
        val out = newExportFile()
        val vm = buildViewModel()
        vm.generateExport()
        assertThat(vm.uiState.value.exportFilePath).isEqualTo(out.absolutePath)

        vm.clearExport()

        verify(exactly = 1) { exportDataRepository.deleteExportFile(out.absolutePath) }
        assertThat(vm.uiState.value.exportFilePath).isNull()
        assertThat(vm.uiState.value.exportSuccess).isFalse()
        assertThat(vm.uiState.value.exportPreview).isNull()
    }

    @Test
    fun `clearExport without any export does not call delete`() {
        val vm = buildViewModel()

        vm.clearExport()

        verify(exactly = 0) { exportDataRepository.deleteExportFile(any()) }
    }

    @Test
    fun `clearExport twice deletes only once`() {
        val out = newExportFile()
        val vm = buildViewModel()
        vm.generateExport()

        vm.clearExport()
        vm.clearExport()

        verify(exactly = 1) { exportDataRepository.deleteExportFile(out.absolutePath) }
    }

    @Test
    fun `markExportShared then clearExport keeps the file`() {
        val out = newExportFile()
        val vm = buildViewModel()
        vm.generateExport()

        vm.markExportShared()
        vm.clearExport()

        verify(exactly = 0) { exportDataRepository.deleteExportFile(any()) }
        assertThat(out.exists()).isTrue()
        assertThat(vm.uiState.value.exportFilePath).isNull()
        assertThat(vm.uiState.value.exportSuccess).isFalse()
    }

    @Test
    fun `markExportShared without an export then clearExport does not call delete`() {
        val vm = buildViewModel()

        vm.markExportShared()
        vm.clearExport()

        verify(exactly = 0) { exportDataRepository.deleteExportFile(any()) }
    }

    @Test
    fun `sharing one export does not protect a later unshared export`() {
        val first = tmp.newFile("expenses_1.csv")
        val second = tmp.newFile("expenses_2.csv")
        every { exportDataRepository.createExportFile(any(), any()) } returnsMany listOf(first, second)
        val vm = buildViewModel()

        vm.generateExport()
        vm.markExportShared()
        vm.clearExport()
        vm.generateExport()
        assertThat(vm.uiState.value.exportFilePath).isEqualTo(second.absolutePath)
        vm.clearExport()

        verify(exactly = 0) { exportDataRepository.deleteExportFile(first.absolutePath) }
        verify(exactly = 1) { exportDataRepository.deleteExportFile(second.absolutePath) }
    }

    @Test
    fun `delete failure is not propagated and the screen state is still cleared`() {
        val out = newExportFile()
        every { exportDataRepository.deleteExportFile(any()) } throws IOException("disk failure")
        val vm = buildViewModel()
        vm.generateExport()

        vm.clearExport()

        verify(exactly = 1) { exportDataRepository.deleteExportFile(out.absolutePath) }
        assertThat(vm.uiState.value.exportFilePath).isNull()
        assertThat(vm.uiState.value.exportSuccess).isFalse()
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test
    fun `delete runtime failure is not propagated and logs no path or message`() {
        val out = newExportFile()
        every { exportDataRepository.deleteExportFile(any()) } throws
            IllegalStateException("boom at ${out.absolutePath}")
        val vm = buildViewModel()
        vm.generateExport()

        vm.clearExport()

        assertThat(vm.uiState.value.exportFilePath).isNull()
        logs.forEach { (message, throwable) ->
            assertThat(message).doesNotContain(out.name)
            assertThat(message).doesNotContain("boom")
            assertThat(throwable).isNull()
        }
    }

    @Test
    fun `delete returning false is not an error`() {
        val out = newExportFile()
        every { exportDataRepository.deleteExportFile(any()) } returns false
        val vm = buildViewModel()
        vm.generateExport()

        vm.clearExport()

        assertThat(vm.uiState.value.error).isNull()
        assertThat(vm.uiState.value.exportFilePath).isNull()
        verify(exactly = 1) { exportDataRepository.deleteExportFile(out.absolutePath) }
    }
}
