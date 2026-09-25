package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.yourname.expensetracker.data.repository.*
import com.yourname.expensetracker.domain.ai.service.*
import com.yourname.expensetracker.domain.ai.usecase.*
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.After
import org.json.JSONException
import timber.log.Timber
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.debug.DebugData

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptScanViewModelTest : ViewModelTestUtils() {
    private lateinit var model: ReceiptScanViewModel
    private val coordinator = mockk<ReceiptLifecycleCoordinator>()
    private val links = mockk<ReceiptLinkService>()
    private val parser = mockk<ReceiptParser>(relaxed = true)
    private val hostile = IllegalStateException("Duplicate merchant private receipt=/data/918")
    private val failureLogs = mutableListOf<Pair<Throwable?, String>>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= 5) failureLogs += t to message
        }
    }

    @After override fun tearDown() {
        Timber.uproot(logTree)
        super.tearDown()
        assertTrue(failureLogs.all { it.first == null && !it.second.contains("private") && !it.second.contains("918") })
    }

    @Before override fun setup() {
        super.setup()
        Timber.plant(logTree)
        val categories = mockk<CategoryRepository>(relaxed = true)
        every { categories.allCategories } returns flowOf(emptyList())
        val currency = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currency.homeCurrency() } returns flowOf("EUR")
        val aiSettings = mockk<AiSettingsRepository>(relaxed = true)
        every { aiSettings.settings() } returns flowOf(AiSettings(aiEnabled = false))
        val clock = mockk<TimeProvider>(relaxed = true)
        every { clock.now() } returns 1_700_000_000_000L
        model = ReceiptScanViewModel(
            mockk<ReceiptRepository>(relaxed = true), categories, currency, aiSettings,
            SavedStateHandle(), clock, mockk<SuggestReceiptExtractionUseCase>(relaxed = true),
            mockk<SuggestCategoryFallbackUseCase>(relaxed = true),
            mockk<CategorizeReceiptItemsUseCase>(relaxed = true),
            mockk<ReceiptItemCategorizationRepository>(relaxed = true),
            mockk<AiArtifactRepository>(relaxed = true), mockk<AiRuntimeDiagnostics>(relaxed = true),
            coordinator, parser,
            mockk<TransactionLifecycleCoordinator>(relaxed = true), links,
            mockk<MerchantNormalizer>(relaxed = true), mockk<HybridExpenseClassifier>(relaxed = true)
        )
    }

    @Test fun `scan exception clears previous raw data and bounds failure`() = runTest(testDispatcher) {
        val uri = Uri.parse("content://private/receipt")
        coEvery { coordinator.processReceiptInput(uri, any()) } throws hostile
        setState(ReceiptScanState(rawOcrText = "old private OCR", editCurrency = "EUR"))
        model.processGalleryImage(uri)
        advanceUntilIdle()
        val state = model.state.value
        assertEquals("Receipt processing failed (UNKNOWN_ERROR)", state.errorMessage)
        assertEquals("", state.rawOcrText)
        assertEquals("", state.debugData?.rawText)
        assertEquals(listOf("Processing Error: UNKNOWN_ERROR"), state.debugData?.parsingLogs)
    }

    @Test fun `typed duplicate save shows duplicate card without raw data or failure log`() = runTest(testDispatcher) {
        coEvery { links.checkCanLinkReceipt(17L) } returns true
        coEvery { coordinator.createExpenseAndLinkReceipt(any()) } returns
            Result.failure(ReceiptLifecycleCoordinator.DuplicateTransactionException())
        readyToSave()
        model.saveExpense()
        advanceUntilIdle()
        val state = model.state.value
        assertEquals(SaveReceiptResult.DuplicateTransaction, state.saveResult)
        assertEquals(ScanStep.REVIEW, state.step)
        assertFalse(state.isSaving)
        assertNull(state.errorMessage)
        assertEquals("", state.rawOcrText)
        assertFalse(state.showRawText)
        assertEquals("", state.debugData?.rawText)
        assertTrue(state.debugData!!.parsedTransactions.isEmpty())
        assertTrue(state.debugData!!.parsingLogs.isEmpty())
        assertTrue(failureLogs.isEmpty())
        coVerify(exactly = 1) { coordinator.createExpenseAndLinkReceipt(any()) }
    }

    @Test fun `misleading duplicate text in failed save is not a duplicate card`() = runTest(testDispatcher) {
        coEvery { links.checkCanLinkReceipt(17L) } returns true
        coEvery { coordinator.createExpenseAndLinkReceipt(any()) } returns
            kotlin.Result.failure(hostile)
        readyToSave()
        model.saveExpense()
        advanceUntilIdle()
        assertEquals(SaveReceiptResult.Error("Receipt save failed (UNKNOWN_ERROR)"), model.state.value.saveResult)
        assertEquals("", model.state.value.rawOcrText)
        assertEquals("", model.state.value.debugData?.rawText)
        assertEquals(listOf("Save Error: UNKNOWN_ERROR"), model.state.value.debugData?.parsingLogs)
    }

    @Test fun `result wrapped scan cancellation is not displayed as a failure`() = runTest(testDispatcher) {
        val uri = Uri.parse("content://private/cancelled")
        coEvery { coordinator.processReceiptInput(uri, any()) } returns
            kotlin.Result.failure(CancellationException("private OCR"))
        model.processGalleryImage(uri)
        advanceUntilIdle()
        assertNull(model.state.value.errorMessage)
        assertNull(model.state.value.saveResult)
        assertTrue(failureLogs.isEmpty())
    }

    @Test fun `result wrapped save cancellation is not displayed as a failure`() = runTest(testDispatcher) {
        coEvery { links.checkCanLinkReceipt(17L) } returns true
        coEvery { coordinator.createExpenseAndLinkReceipt(any()) } returns
            kotlin.Result.failure(CancellationException("private merchant"))
        setState(ReceiptScanState(step = ScanStep.REVIEW, receiptId = 17L,
            editMerchant = "merchant", editAmount = "10", editCurrency = "EUR", editDate = 1_700_000_000_000L))
        model.saveExpense()
        advanceUntilIdle()
        assertNull(model.state.value.saveResult)
        assertTrue(failureLogs.isEmpty())
    }

    private fun receipt(id: Long = 17L, status: String = "PARSED") = ScannedReceipt(
        id = id, imagePath = null, rawOcrText = "SQL /private/receipt content://private/918 merchant 1234.56",
        parsedTotal = 10.0, parsedMerchant = "Merchant", parsedDate = 1_700_000_000_000L,
        parsedItems = null, parsedTaxAmount = null, currency = "EUR", confidence = 0.8f, processingStatus = status
    )

    private fun stubScan(uri: Uri, receipt: ScannedReceipt) {
        coEvery { coordinator.processReceiptInput(uri, any()) } returns
            Result.success(ReceiptLifecycleCoordinator.ReceiptProcessOutcome(receipt, true, null))
        coEvery { links.checkCanLinkReceipt(receipt.id) } returns true
    }

    private fun readyToSave() {
        setState(ReceiptScanState(step = ScanStep.REVIEW, receiptId = 17L,
            rawOcrText = "private OCR 918", showRawText = true,
            debugData = DebugData(rawText = "private OCR 918", parsedTransactions = emptyList(), parsingLogs = listOf("private OCR 918")),
            editMerchant = "merchant", editAmount = "10", editCurrency = "EUR", editDate = 1_700_000_000_000L))
    }

    private fun assertScanFailure(code: String) {
        val state = model.state.value
        assertEquals("Receipt processing failed ($code)", state.errorMessage)
        assertEquals("", state.rawOcrText)
        assertEquals("", state.debugData?.rawText)
        assertEquals(listOf("Processing Error: $code"), state.debugData?.parsingLogs)
    }

    @Test fun `OCR failure keeps manual entry but removes raw failure payloads`() = runTest(testDispatcher) {
        val uri = Uri.parse("content://private/ocr")
        stubScan(uri, receipt(status = "OCR_FAILED"))
        model.processGalleryImage(uri)
        advanceUntilIdle()
        assertEquals(ScanStep.REVIEW, model.state.value.step)
        assertEquals("OCR could not be processed (OCR_FAILED). You can enter details manually.", model.state.value.errorMessage)
        assertEquals("", model.state.value.rawOcrText)
        assertEquals("", model.state.value.debugData?.rawText)
        assertEquals(listOf("Processing Error: OCR_FAILED"), model.state.value.debugData?.parsingLogs)
    }

    @Test fun `parser failure returns parser code without raw debug data`() = runTest(testDispatcher) {
        val uri = Uri.parse("content://private/parser")
        coEvery { coordinator.processReceiptInput(uri, any()) } returns
            Result.failure(JSONException("SQL /private/receipt content://private/918 merchant 1234.56"))
        model.processGalleryImage(uri)
        advanceUntilIdle()
        assertScanFailure("PARSER_FAILED")
        assertTrue(failureLogs.contains(null to "ReceiptScan: PARSER_FAILED stage=scan class=JSONException"))
    }

    @Test fun `source link scan failure returns fixed code and no raw debug data`() = runTest(testDispatcher) {
        val uri = Uri.parse("content://private/link")
        stubScan(uri, receipt())
        coEvery { links.checkCanLinkReceipt(17L) } throws hostile
        model.processGalleryImage(uri)
        advanceUntilIdle()
        assertScanFailure("SOURCE_LINK_FAILED")
        assertTrue(failureLogs.contains(null to "ReceiptScan: SOURCE_LINK_FAILED stage=scan class=IllegalStateException"))
    }

    @Test fun `source link save failure is bounded without creating expense`() = runTest(testDispatcher) {
        coEvery { links.checkCanLinkReceipt(17L) } throws hostile
        readyToSave()
        model.saveExpense()
        advanceUntilIdle()
        assertEquals(SaveReceiptResult.Error("Receipt save failed (SOURCE_LINK_FAILED)"), model.state.value.saveResult)
        assertEquals("", model.state.value.rawOcrText)
        assertEquals("", model.state.value.debugData?.rawText)
        assertEquals(listOf("Save Error: SOURCE_LINK_FAILED"), model.state.value.debugData?.parsingLogs)
        assertFalse(model.state.value.showRawText)
        coVerify(exactly = 0) { coordinator.createExpenseAndLinkReceipt(any()) }
    }

    @Test fun `thrown save failure is bounded and clears previous raw OCR`() = runTest(testDispatcher) {
        coEvery { links.checkCanLinkReceipt(17L) } returns true
        coEvery { coordinator.createExpenseAndLinkReceipt(any()) } throws hostile
        readyToSave()
        model.saveExpense()
        advanceUntilIdle()
        assertEquals(SaveReceiptResult.Error("Receipt save failed (UNKNOWN_ERROR)"), model.state.value.saveResult)
        assertEquals("", model.state.value.rawOcrText)
        assertEquals("", model.state.value.debugData?.rawText)
        assertEquals(listOf("Save Error: UNKNOWN_ERROR"), model.state.value.debugData?.parsingLogs)
        assertFalse(model.state.value.showRawText)
    }

    @Test fun `thrown scan cancellation has no failure UI or log`() = runTest(testDispatcher) {
        coEvery { coordinator.processReceiptInput(any(), any()) } throws CancellationException("private OCR")
        model.processGalleryImage(Uri.parse("content://private/cancel"))
        advanceUntilIdle()
        assertNull(model.state.value.errorMessage)
        assertTrue(failureLogs.isEmpty())
    }

    @Test fun `thrown save cancellation has no failure UI or log`() = runTest(testDispatcher) {
        coEvery { links.checkCanLinkReceipt(17L) } returns true
        coEvery { coordinator.createExpenseAndLinkReceipt(any()) } throws CancellationException("private OCR")
        readyToSave()
        model.saveExpense()
        advanceUntilIdle()
        assertNull(model.state.value.saveResult)
        assertNull(model.state.value.errorMessage)
        assertTrue(failureLogs.isEmpty())
    }

    @Test fun `line item parser cancellation is not swallowed as empty items`() = runTest(testDispatcher) {
        val uri = Uri.parse("content://private/items")
        stubScan(uri, receipt().copy(parsedItems = "[]"))
        every { parser.lineItemsFromJson("[]") } throws CancellationException("private OCR")
        model.processGalleryImage(uri)
        advanceUntilIdle()
        verify(exactly = 1) { parser.lineItemsFromJson("[]") }
        assertNull(model.state.value.errorMessage)
        assertTrue(failureLogs.isEmpty())
        assertNotEquals(ScanStep.REVIEW, model.state.value.step)
    }

    @Test fun `late failure from old scan cannot overwrite newer review`() = runTest(testDispatcher) {
        val oldUri = Uri.parse("content://private/old")
        val newUri = Uri.parse("content://private/new")
        val waiting = CompletableDeferred<Unit>()
        coEvery { coordinator.processReceiptInput(oldUri, any()) } coAnswers {
            withContext(NonCancellable) { waiting.await() }
            throw hostile
        }
        stubScan(newUri, receipt(id = 18L).copy(rawOcrText = "current OCR"))
        model.processGalleryImage(oldUri)
        runCurrent()
        model.processGalleryImage(newUri)
        runCurrent()
        waiting.complete(Unit)
        advanceUntilIdle()
        assertEquals(18L, model.state.value.receiptId)
        assertEquals(ScanStep.REVIEW, model.state.value.step)
        assertNull(model.state.value.errorMessage)
        assertTrue(failureLogs.isEmpty())
    }

    private fun setState(state: ReceiptScanState) {
        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(model) as MutableStateFlow<ReceiptScanState>).value = state
    }
}
