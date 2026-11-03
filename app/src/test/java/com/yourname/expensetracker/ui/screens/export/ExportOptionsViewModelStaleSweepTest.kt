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
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * J-12-1 (fix-1.0.4 S2 / D5): the export screen ViewModel triggers the
 * age-based export-file sweep from `init` (and best effort from
 * `generateExport`) without ever blocking the screen or leaking details.
 *
 * Dispatchers are unconfined, so the sweep launched by `init` has completed by
 * the time the constructor returns; every assertion below runs after it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportOptionsViewModelStaleSweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val nowMs = 1_700_000_000_000L

    private lateinit var exportDataRepository: ExportDataRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var privacyGate: PrivacyGate

    private val logs = mutableListOf<Triple<Int, String, Throwable?>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += Triple(priority, message, t)
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

    private fun buildViewModel(
        serializer: ExportJobSerializer = ExportJobSerializer()
    ): ExportOptionsViewModel = ExportOptionsViewModel(
        exportDataRepository = exportDataRepository,
        accountingExportPolicy = AccountingExportPolicy(),
        timeProvider = timeProvider,
        xeroExporter = XeroCSVExporter(),
        quickBooksExporter = QuickBooksIIFExporter(),
        freshBooksExporter = FreshBooksExporter(),
        readBarrier = mockk<DatabaseReadBarrier>(relaxed = true),
        privacyGate = privacyGate,
        exportJobSerializer = serializer,
        ioDispatcher = testDispatcher
    )

    /** A serializer that records every job it launched, so a test can inspect how the run really ended. */
    private fun recordingSerializer(launched: MutableList<Job>): ExportJobSerializer {
        val real = ExportJobSerializer()
        val spy = spyk(real)
        every { spy.launch(any(), any()) } answers { callOriginal().also { launched += it } }
        return spy
    }

    @Test
    fun `init calls the export sweep once with the provider now and the default one hour age`() {
        buildViewModel()

        verify(exactly = 1) {
            exportDataRepository.sweepStaleExportFiles(nowMs, ExportDataRepository.EXPORT_SWEEP_MIN_AGE_MS)
        }
    }

    @Test
    fun `init sweep failure still loads screen state and logs no path or message`() {
        val secretPath = "/data/user/0/secret.pkg/files/exports/expenses_999.csv"
        every { exportDataRepository.sweepStaleExportFiles(any(), any()) } throws
            IOException("disk failure at $secretPath")

        val vm = buildViewModel()

        verify(exactly = 1) { exportDataRepository.sweepStaleExportFiles(any(), any()) }
        val state = vm.uiState.value
        assertThat(state.expenseCount).isEqualTo(7)
        assertThat(state.error).isNull()
        assertThat(state.isLoading).isFalse()
        assertThat(state.endDate).isEqualTo(nowMs)
        logs.forEach { (_, message, throwable) ->
            assertThat(message).doesNotContain("secret.pkg")
            assertThat(message).doesNotContain("expenses_999")
            assertThat(message).doesNotContain("disk failure")
            assertThat(message).doesNotContain("/data/")
            assertThat(throwable).isNull()
        }
    }

    @Test
    fun `init sweep runtime failure does not stop later retry or screen state`() {
        every { exportDataRepository.sweepStaleExportFiles(any(), any()) } throws
            IllegalStateException("boom")

        val vm = buildViewModel()
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 9
        vm.retry()

        assertThat(vm.uiState.value.expenseCount).isEqualTo(9)
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test
    fun `init sweep CancellationException is not swallowed as a failure`() {
        every { exportDataRepository.sweepStaleExportFiles(any(), any()) } throws
            CancellationException("cancelled")

        val vm = buildViewModel()

        verify(exactly = 1) { exportDataRepository.sweepStaleExportFiles(any(), any()) }
        // A swallowed CancellationException would be reported as a sweep failure.
        val failureLogs = logs.filter { (priority, message, _) ->
            priority >= 5 || message.contains("CancellationException")
        }
        assertThat(failureLogs).isEmpty()
        // Cancelling the sweep child never fails the screen.
        assertThat(vm.uiState.value.expenseCount).isEqualTo(7)
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test
    fun `generateExport still succeeds when its sweep fails`() {
        val out = tmp.newFile("expenses_${nowMs}.csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out
        var calls = 0
        every { exportDataRepository.sweepStaleExportFiles(any(), any()) } answers {
            calls++
            if (calls > 1) throw IllegalStateException("sweep failed in generateExport") else 0
        }
        val vm = buildViewModel()

        vm.generateExport()

        assertThat(calls).isAtLeast(2)
        val state = vm.uiState.value
        assertThat(state.exportSuccess).isTrue()
        assertThat(state.exportFilePath).isEqualTo(out.absolutePath)
        assertThat(state.error).isNull()
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `generateExport sweeps with the provider now`() {
        val out = tmp.newFile("expenses_${nowMs}.csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out
        val vm = buildViewModel()

        vm.generateExport()

        assertThat(vm.uiState.value.exportSuccess).isTrue()
        // init + generateExport
        verify(atLeast = 2) { exportDataRepository.sweepStaleExportFiles(nowMs, any()) }
    }

    @Test
    fun `generateExport sweep CancellationException propagates and no export file is created`() {
        val out = tmp.newFile("expenses_${nowMs}.csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out
        var calls = 0
        every { exportDataRepository.sweepStaleExportFiles(any(), any()) } answers {
            calls++
            if (calls > 1) throw CancellationException("cancelled in generateExport") else 0
        }
        val vm = buildViewModel()

        vm.generateExport()

        assertThat(calls).isAtLeast(2)
        val state = vm.uiState.value
        assertThat(state.exportSuccess).isFalse()
        assertThat(state.exportFilePath).isNull()
        assertThat(state.isLoading).isFalse()
        // Cancellation escaped the sweep's try block: the export never proceeded.
        verify(exactly = 0) { exportDataRepository.createExportFile(any(), any()) }
    }

    // Review fix (reviewer-strict REJECT on 013b78be): the generate catch for CancellationException used to swallow it.
    // The test above only proves no file was made; the two below prove the run ends cancelled and not stuck loading.
    // (The state flag is `isLoading`; the ViewModel has no `isGenerating`.)

    @Test
    fun `generateExport cancelled by the sweep ends the job cancelled and does not leave the screen loading`() {
        val out = tmp.newFile("expenses_${nowMs}.csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out
        var calls = 0
        every { exportDataRepository.sweepStaleExportFiles(any(), any()) } answers {
            calls++
            if (calls > 1) throw CancellationException("cancelled in generateExport") else 0
        }
        val launched = mutableListOf<Job>()
        val vm = buildViewModel(recordingSerializer(launched))

        vm.generateExport()

        assertThat(calls).isAtLeast(2)
        val job = launched.single()
        runBlocking { job.join() }
        assertThat(job.isCancelled).isTrue()
        assertThat(job.isCompleted).isTrue()
        assertThat(vm.uiState.value.isLoading).isFalse()
        assertThat(vm.uiState.value.exportSuccess).isFalse()
    }

    @Test
    fun `cancelExport while the run is suspended ends the job cancelled and does not leave the screen loading`() {
        val out = tmp.newFile("expenses_${nowMs}.csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out
        val launched = mutableListOf<Job>()
        val vm = buildViewModel(recordingSerializer(launched))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } coAnswers { awaitCancellation() }

        vm.generateExport()

        val job = launched.single()
        assertThat(job.isActive).isTrue()
        assertThat(vm.uiState.value.isLoading).isTrue()

        vm.cancelExport()
        runBlocking { job.join() }

        assertThat(job.isCancelled).isTrue()
        assertThat(vm.uiState.value.isLoading).isFalse()
        assertThat(vm.uiState.value.exportSuccess).isFalse()
        assertThat(vm.uiState.value.exportFilePath).isNull()
    }
}
