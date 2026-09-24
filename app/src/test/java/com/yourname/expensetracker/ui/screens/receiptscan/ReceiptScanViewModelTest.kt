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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptScanViewModelTest : ViewModelTestUtils() {
    private lateinit var model: ReceiptScanViewModel
    private val coordinator = mockk<ReceiptLifecycleCoordinator>()
    private val links = mockk<ReceiptLinkService>()
    private val hostile = IllegalStateException("Duplicate merchant private receipt=/data/918")

    @Before override fun setup() {
        super.setup()
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
            coordinator, mockk<ReceiptParser>(relaxed = true),
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

    @Test fun `misleading duplicate text in failed save is not a duplicate card`() = runTest(testDispatcher) {
        coEvery { links.checkCanLinkReceipt(17L) } returns true
        coEvery { coordinator.createExpenseAndLinkReceipt(any()) } returns
            kotlin.Result.failure(hostile)
        setState(ReceiptScanState(step = ScanStep.REVIEW, receiptId = 17L,
            editMerchant = "merchant", editAmount = "10", editCurrency = "EUR", editDate = 1_700_000_000_000L))
        model.saveExpense()
        advanceUntilIdle()
        assertEquals(SaveReceiptResult.Error("Receipt save failed (UNKNOWN_ERROR)"), model.state.value.saveResult)
    }

    @Test fun `result wrapped scan cancellation is not displayed as a failure`() = runTest(testDispatcher) {
        val uri = Uri.parse("content://private/cancelled")
        coEvery { coordinator.processReceiptInput(uri, any()) } returns
            kotlin.Result.failure(CancellationException("private OCR"))
        model.processGalleryImage(uri)
        advanceUntilIdle()
        assertNull(model.state.value.errorMessage)
        assertNull(model.state.value.saveResult)
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
    }

    private fun setState(state: ReceiptScanState) {
        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(model) as MutableStateFlow<ReceiptScanState>).value = state
    }
}
