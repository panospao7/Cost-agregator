package com.yourname.expensetracker.ui.screens.export

import com.yourname.expensetracker.ui.util.blockedWriteException
import com.yourname.expensetracker.util.CsvExpenseImporter
import com.yourname.expensetracker.util.CsvImportFailureCode
import com.yourname.expensetracker.util.CsvImportPreview
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * J-12-2 CSV import UI state machine: idle, reading, preview, importing, result,
 * cancel, 5 MB cap, write-blocked, bounded error mapping, double-tap guard and
 * "no URI / content logging".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CsvImportViewModelTest : ViewModelTestUtils() {

    private val importer = mockk<CsvExpenseImporter>()
    private val logs = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += message + (t?.toString() ?: "")
        }
    }

    private val preview = CsvImportPreview(
        totalRows = 3,
        validRows = 2,
        invalidRowsByCode = mapOf(CsvImportFailureCode.INVALID_DATE to 1),
        unknownCategoryRows = 1,
        skippedInstallmentRows = 0
    )

    @Before
    override fun setup() {
        super.setup()
        Timber.plant(tree)
        coEvery { importer.preview(any(), any()) } returns preview
    }

    @After
    override fun tearDown() {
        Timber.uproot(tree)
        super.tearDown()
    }

    private fun viewModel() = CsvImportViewModel(importer, testDispatcher)

    private fun streamOf(text: String): () -> InputStream? = { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) }

    private fun success(imported: Int = 2, duplicates: Int = 0, errors: Int = 1) =
        CsvExpenseImporter.ImportResult.Success(imported, duplicates, errors, emptyList())

    /** Stream of [limit] bytes (or unbounded when null) that records how many bytes were served. */
    private class CountingStream(private val limit: Long?) : InputStream() {
        var served = 0L
        override fun read(): Int {
            if (limit != null && served >= limit) return -1
            served++
            return 'a'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = if (limit == null) len.toLong() else minOf(len.toLong(), limit - served)
            if (n <= 0) return -1
            java.util.Arrays.fill(b, off, off + n.toInt(), 'a'.code.toByte())
            served += n
            return n.toInt()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.toPreview(vm: CsvImportViewModel) {
        vm.onFileSelected(streamOf("date,amount,merchant\n"))
        advanceUntilIdle()
        assertTrue(vm.state.value is CsvImportUiState.Preview)
    }

    @Test
    fun `starts idle`() = runTest(testDispatcher) {
        assertEquals(CsvImportUiState.Idle, viewModel().state.value)
    }

    @Test
    fun `file selection goes reading then preview with the importer preview`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.onFileSelected(streamOf("date,amount,merchant\n"))
        assertEquals(CsvImportUiState.Reading, vm.state.value)
        advanceUntilIdle()
        assertEquals(CsvImportUiState.Preview(preview), vm.state.value)
        coVerify(exactly = 1) { importer.preview("date,amount,merchant\n", any()) }
    }

    @Test
    fun `confirm goes importing then result with imported duplicates and failed counts`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { importer.importFromContent(any(), any(), any()) } coAnswers {
            gate.await()
            success(imported = 5, duplicates = 2, errors = 1)
        }
        val vm = viewModel()
        toPreview(vm)

        vm.confirmImport()
        assertEquals(CsvImportUiState.Importing(0, 3), vm.state.value)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(CsvImportUiState.Result(imported = 5, duplicates = 2, failed = 1), vm.state.value)
    }

    @Test
    fun `progress callback updates the importing state`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { importer.importFromContent(any(), any(), any()) } coAnswers {
            thirdArg<(Int, Int) -> Unit>().invoke(1, 3)
            gate.await()
            success()
        }
        val vm = viewModel()
        toPreview(vm)
        vm.confirmImport()
        advanceUntilIdle()

        assertEquals(CsvImportUiState.Importing(1, 3), vm.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `cancel during import cancels the job keeps state cancelled and rethrows cancellation`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        var cancelledInside = false
        coEvery { importer.importFromContent(any(), any(), any()) } coAnswers {
            thirdArg<(Int, Int) -> Unit>().invoke(1, 3)
            try {
                gate.await()
            } catch (e: CancellationException) {
                cancelledInside = true
                throw e
            }
            success()
        }
        val vm = viewModel()
        toPreview(vm)
        vm.confirmImport()
        advanceUntilIdle()

        vm.cancelImport()
        advanceUntilIdle()

        assertTrue("importer coroutine must observe cancellation", cancelledInside)
        assertEquals(CsvImportUiState.Cancelled(processedRows = 1), vm.state.value)
    }

    @Test
    fun `cancel in preview returns to idle and drops the content`() = runTest(testDispatcher) {
        val vm = viewModel()
        toPreview(vm)

        vm.cancelPreview()
        assertEquals(CsvImportUiState.Idle, vm.state.value)
        vm.confirmImport()
        advanceUntilIdle()

        assertEquals(CsvImportUiState.Idle, vm.state.value)
        coVerify(exactly = 0) { importer.importFromContent(any(), any(), any()) }
    }

    @Test
    fun `confirm is ignored when no valid rows`() = runTest(testDispatcher) {
        coEvery { importer.preview(any(), any()) } returns CsvImportPreview(
            totalRows = 1,
            validRows = 0,
            invalidRowsByCode = mapOf(CsvImportFailureCode.INVALID_AMOUNT to 1),
            unknownCategoryRows = 0,
            skippedInstallmentRows = 0
        )
        val vm = viewModel()
        toPreview(vm)

        vm.confirmImport()
        advanceUntilIdle()

        assertTrue(vm.state.value is CsvImportUiState.Preview)
        coVerify(exactly = 0) { importer.importFromContent(any(), any(), any()) }
    }

    @Test
    fun `result is cleared to idle on dismiss and content is not retained`() = runTest(testDispatcher) {
        coEvery { importer.importFromContent(any(), any(), any()) } returns success()
        val vm = viewModel()
        toPreview(vm)
        vm.confirmImport()
        advanceUntilIdle()
        assertTrue(vm.state.value is CsvImportUiState.Result)

        vm.dismiss()
        assertEquals(CsvImportUiState.Idle, vm.state.value)
        vm.confirmImport()
        advanceUntilIdle()

        coVerify(exactly = 1) { importer.importFromContent(any(), any(), any()) }
        assertEquals(CsvImportUiState.Idle, vm.state.value)
    }

    // ---- 5 MB cap ---------------------------------------------------------------------------------

    @Test
    fun `file over the cap ends in size limit error and the read is bounded`() = runTest(testDispatcher) {
        val stream = CountingStream(limit = null)
        val vm = viewModel()

        vm.onFileSelected { stream }
        advanceUntilIdle()

        assertEquals(CsvImportUiState.Error(CsvImportError.SizeLimitExceeded), vm.state.value)
        assertTrue("read must stop soon after the cap", stream.served <= CsvImportViewModel.MAX_CSV_BYTES + 8 * 1024L)
        coVerify(exactly = 0) { importer.preview(any(), any()) }
    }

    @Test
    fun `file of exactly the cap is accepted and one byte more is rejected`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.onFileSelected { CountingStream(CsvImportViewModel.MAX_CSV_BYTES.toLong()) }
        advanceUntilIdle()
        assertTrue(vm.state.value is CsvImportUiState.Preview)
        vm.cancelPreview()

        vm.onFileSelected { CountingStream(CsvImportViewModel.MAX_CSV_BYTES.toLong() + 1) }
        advanceUntilIdle()
        assertEquals(CsvImportUiState.Error(CsvImportError.SizeLimitExceeded), vm.state.value)
    }

    @Test
    fun `unreadable file maps to read failed`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.onFileSelected { null }
        advanceUntilIdle()
        assertEquals(CsvImportUiState.Error(CsvImportError.ReadFailed), vm.state.value)

        vm.dismiss()
        vm.onFileSelected { throw IOException("/storage/emulated/0/secret.csv") }
        advanceUntilIdle()
        assertEquals(CsvImportUiState.Error(CsvImportError.ReadFailed), vm.state.value)
        assertFalse(vm.state.value.toString().contains("secret"))
    }

    // ---- write blocked and error mapping ----------------------------------------------------------

    @Test
    fun `importer write blocked code maps to write blocked state`() = runTest(testDispatcher) {
        coEvery { importer.importFromContent(any(), any(), any()) } returns
            CsvExpenseImporter.ImportResult.Error(CsvImportFailureCode.WRITE_BLOCKED)
        val vm = viewModel()
        toPreview(vm)
        vm.confirmImport()
        advanceUntilIdle()

        assertEquals(CsvImportUiState.WriteBlocked, vm.state.value)
    }

    @Test
    fun `database access blocked exception maps to write blocked state`() = runTest(testDispatcher) {
        coEvery { importer.importFromContent(any(), any(), any()) } throws blockedWriteException()
        val vm = viewModel()
        toPreview(vm)
        vm.confirmImport()
        advanceUntilIdle()

        assertEquals(CsvImportUiState.WriteBlocked, vm.state.value)
    }

    @Test
    fun `header failure in preview maps to the invalid header error`() = runTest(testDispatcher) {
        coEvery { importer.preview(any(), any()) } returns CsvImportPreview(
            totalRows = 0,
            validRows = 0,
            invalidRowsByCode = emptyMap(),
            unknownCategoryRows = 0,
            skippedInstallmentRows = 0,
            headerFailure = CsvImportFailureCode.INVALID_HEADER
        )
        val vm = viewModel()
        vm.onFileSelected(streamOf("\"broken"))
        advanceUntilIdle()

        assertEquals(
            CsvImportUiState.Error(CsvImportError.Failure(CsvImportFailureCode.INVALID_HEADER)),
            vm.state.value
        )
    }

    @Test
    fun `importer error codes map one to one into the error state`() = runTest(testDispatcher) {
        val codes = CsvImportFailureCode.values().filter { it != CsvImportFailureCode.WRITE_BLOCKED }
        for (code in codes) {
            coEvery { importer.importFromContent(any(), any(), any()) } returns CsvExpenseImporter.ImportResult.Error(code)
            val vm = viewModel()
            toPreview(vm)
            vm.confirmImport()
            advanceUntilIdle()
            assertEquals(CsvImportUiState.Error(CsvImportError.Failure(code)), vm.state.value)
        }
    }

    @Test
    fun `unexpected exceptions become internal and never expose the message`() = runTest(testDispatcher) {
        coEvery { importer.preview(any(), any()) } throws IllegalStateException("/data/user/0/secret/path SELECT *")
        val vm = viewModel()
        vm.onFileSelected(streamOf("a,b\n"))
        advanceUntilIdle()

        assertEquals(
            CsvImportUiState.Error(CsvImportError.Failure(CsvImportFailureCode.INTERNAL)),
            vm.state.value
        )
        assertFalse(vm.state.value.toString().contains("secret"))

        coEvery { importer.preview(any(), any()) } returns preview
        coEvery { importer.importFromContent(any(), any(), any()) } throws IllegalStateException("secret payload")
        vm.dismiss()
        toPreview(vm)
        vm.confirmImport()
        advanceUntilIdle()
        assertEquals(
            CsvImportUiState.Error(CsvImportError.Failure(CsvImportFailureCode.INTERNAL)),
            vm.state.value
        )
        assertTrue(logs.none { it.contains("secret") })
    }

    // ---- double tap -------------------------------------------------------------------------------

    @Test
    fun `second file selection while reading or in preview is ignored`() = runTest(testDispatcher) {
        var opened = 0
        val vm = viewModel()
        vm.onFileSelected { opened++; ByteArrayInputStream("a,b\n".toByteArray()) }
        vm.onFileSelected { opened++; ByteArrayInputStream("a,b\n".toByteArray()) }
        advanceUntilIdle()
        vm.onFileSelected { opened++; ByteArrayInputStream("a,b\n".toByteArray()) }
        advanceUntilIdle()

        assertEquals(1, opened)
        coVerify(exactly = 1) { importer.preview(any(), any()) }
    }

    @Test
    fun `double tap on confirm starts exactly one import`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { importer.importFromContent(any(), any(), any()) } coAnswers {
            gate.await()
            success()
        }
        val vm = viewModel()
        toPreview(vm)

        vm.confirmImport()
        vm.confirmImport()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { importer.importFromContent(any(), any(), any()) }
        assertTrue(vm.state.value is CsvImportUiState.Result)
    }

    @Test
    fun `dismiss is ignored while importing`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { importer.importFromContent(any(), any(), any()) } coAnswers {
            gate.await()
            success()
        }
        val vm = viewModel()
        toPreview(vm)
        vm.confirmImport()
        advanceUntilIdle()

        vm.dismiss()

        assertTrue(vm.state.value is CsvImportUiState.Importing)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    // ---- logging ----------------------------------------------------------------------------------

    @Test
    fun `happy path and blocked write never log content or uri`() = runTest(testDispatcher) {
        coEvery { importer.importFromContent(any(), any(), any()) } throws blockedWriteException()
        val vm = viewModel()
        vm.onFileSelected(streamOf("date,amount,merchant\n2024-01-15,1.00,SECRET_MERCHANT\n"))
        advanceUntilIdle()
        vm.confirmImport()
        advanceUntilIdle()

        assertTrue(logs.none { it.contains("SECRET_MERCHANT") || it.contains("content://") })
    }
}
