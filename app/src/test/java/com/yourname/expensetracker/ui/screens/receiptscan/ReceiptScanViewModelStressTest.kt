package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.CategoryAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.SuggestCategoryFallbackUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptScanViewModelStressTest : ViewModelTestUtils() {

    private lateinit var receiptRepository: ReceiptRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var timeProvider: TimeProvider
    private lateinit var suggestReceiptExtractionUseCase: SuggestReceiptExtractionUseCase
    private lateinit var suggestCategoryFallbackUseCase: SuggestCategoryFallbackUseCase
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var settingsFlow: kotlinx.coroutines.flow.MutableStateFlow<AiSettings>

    private lateinit var viewModel: ReceiptScanViewModel

    @Before
    override fun setup() {
        super.setup()
        receiptRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        aiSettingsRepository = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()
        timeProvider = mockk(relaxed = true)
        suggestReceiptExtractionUseCase = mockk(relaxed = true)
        suggestCategoryFallbackUseCase = mockk(relaxed = true)
        aiArtifactRepository = mockk(relaxed = true)
        settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(AiSettings(aiEnabled = true))

        every { timeProvider.now() } returns System.currentTimeMillis()
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { aiSettingsRepository.settings() } returns settingsFlow
        every { receiptRepository.createTempPhotoUri() } returns Uri.parse("content://test/photo.jpg")

        viewModel = ReceiptScanViewModel(
            receiptRepository,
            categoryRepository,
            aiSettingsRepository,
            savedStateHandle,
            timeProvider,
            suggestReceiptExtractionUseCase,
            suggestCategoryFallbackUseCase,
            aiArtifactRepository
        )
    }

    @Test
    fun `stress - initial step is CAPTURE`() = runTest {
        assertEquals(ScanStep.CAPTURE, viewModel.state.value.step)
    }

    @Test
    fun `stress - createTempPhotoUri returns uri and updates state`() = runTest {
        val uri = viewModel.createTempPhotoUri()
        advanceUntilIdle()
        assertNotNull(uri)
        assertEquals(uri, viewModel.state.value.tempCameraUri)
    }

    @Test
    fun `stress - processPhoto with no uri does not crash`() = runTest {
        viewModel.processPhoto()
        advanceUntilIdle()
        assertEquals(ScanStep.CAPTURE, viewModel.state.value.step)
    }

    @Test
    fun `stress - processGalleryImage with uri updates step to PROCESSING`() = runTest {
        val uri = Uri.parse("content://test/gallery.jpg")
        viewModel.processGalleryImage(uri)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.step != ScanStep.CAPTURE)
        assertEquals(uri, viewModel.state.value.imageUri)
    }

    @Test
    fun `stress - requestReceiptAssist sets Ready and applies fields`() = runTest {
        val suggestion = ReceiptAssistSuggestion(
            merchant = SuggestedValue("Lidl"),
            total = SuggestedValue(12.34),
            date = SuggestedValue(999L)
        )
        coEvery { suggestReceiptExtractionUseCase(7L, false) } returns ReceiptAssistGenerationResult.Success(
            suggestion = suggestion,
            fromCache = false
        )
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.RECEIPT_EXTRACTION) } returns AiArtifactEntity(
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = 7L,
            targetKey = "scanned_receipt:7",
            capability = AiCapability.RECEIPT_EXTRACTION,
            status = AiArtifactStatus.READY,
            mode = AiMode.CLOUD,
            provider = "google-ai-studio",
            modelName = "gemini-2.5-flash",
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ReceiptScanState>
        stateFlow.value = ReceiptScanState(
            step = ScanStep.REVIEW,
            receiptId = 7L,
            rawOcrText = "LIDL TOTAL 12.34"
        )

        viewModel.requestReceiptAssist()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.receiptAssistState is AiLoadState.Ready)
        assertEquals("Cloud - google-ai-studio - gemini-2.5-flash", viewModel.state.value.receiptAssistDiagnostics)

        viewModel.applyAllReceiptAssist()
        advanceUntilIdle()

        assertEquals("Lidl", viewModel.state.value.editMerchant)
        assertEquals("12.34", viewModel.state.value.editAmount)
        assertEquals(999L, viewModel.state.value.editDate)
        coVerify { aiArtifactRepository.markApplied(any()) }
    }

    @Test
    fun `stress - requestReceiptAssist surfaces on-device diagnostics when latest artifact is local`() = runTest {
        val suggestion = ReceiptAssistSuggestion(
            merchant = SuggestedValue("Lidl")
        )
        coEvery { suggestReceiptExtractionUseCase(7L, false) } returns ReceiptAssistGenerationResult.Success(
            suggestion = suggestion,
            fromCache = false
        )
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.RECEIPT_EXTRACTION) } returns AiArtifactEntity(
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = 7L,
            targetKey = "scanned_receipt:7",
            capability = AiCapability.RECEIPT_EXTRACTION,
            status = AiArtifactStatus.READY,
            mode = AiMode.ON_DEVICE,
            provider = "mlkit-genai-nano",
            modelName = "gemini-nano-receipt",
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ReceiptScanState>
        stateFlow.value = ReceiptScanState(
            step = ScanStep.REVIEW,
            receiptId = 7L,
            rawOcrText = "LIDL TOTAL 12.34"
        )

        viewModel.requestReceiptAssist()
        advanceUntilIdle()

        assertEquals(
            "On-device - mlkit-genai-nano - gemini-nano-receipt",
            viewModel.state.value.receiptAssistDiagnostics
        )
    }

    @Test
    fun `stress - requestReceiptAssist keeps failed artifact diagnostics on error`() = runTest {
        coEvery { suggestReceiptExtractionUseCase(7L, false) } returns ReceiptAssistGenerationResult.Error(
            "AI receipt assist failed."
        )
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.RECEIPT_EXTRACTION) } returns AiArtifactEntity(
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = 7L,
            targetKey = "scanned_receipt:7",
            capability = AiCapability.RECEIPT_EXTRACTION,
            status = AiArtifactStatus.FAILED,
            mode = AiMode.CLOUD,
            provider = "google-ai-studio",
            modelName = "gemini-2.5-flash",
            promptVersion = "v1",
            sourceHash = "hash",
            errorMessage = "backend error",
            createdAt = 0L,
            updatedAt = 0L
        )

        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ReceiptScanState>
        stateFlow.value = ReceiptScanState(
            step = ScanStep.REVIEW,
            receiptId = 7L,
            rawOcrText = "LIDL TOTAL 12.34"
        )

        viewModel.requestReceiptAssist()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.receiptAssistState is AiLoadState.Error)
        assertEquals(
            "Cloud - google-ai-studio - gemini-2.5-flash",
            viewModel.state.value.receiptAssistDiagnostics
        )
    }

    @Test
    fun `stress - requestCategoryAssist sets Ready and applies category`() = runTest {
        val receipt = ScannedReceipt(
            id = 7L,
            imagePath = "receipt.jpg",
            rawOcrText = "LIDL TOTAL 12.34",
            parsedTotal = 12.34,
            parsedMerchant = "Lidl",
            parsedDate = 999L,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.3f
        )
        coEvery { receiptRepository.getReceiptById(7L) } returns receipt
        coEvery {
            suggestCategoryFallbackUseCase(receipt, "Lidl", 12.34, 999L, null, false)
        } returns CategoryAssistGenerationResult.Success(
            suggestion = CategoryAssistSuggestion(
                categoryId = 5L,
                categoryName = "Groceries",
                rationale = "merchant looks like a supermarket"
            ),
            fromCache = false
        )
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.CATEGORIZATION_FALLBACK) } returns AiArtifactEntity(
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = 7L,
            targetKey = "scanned_receipt:7",
            capability = AiCapability.CATEGORIZATION_FALLBACK,
            status = AiArtifactStatus.READY,
            mode = AiMode.CLOUD,
            provider = "google-ai-studio",
            modelName = "gemini-2.5-flash",
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ReceiptScanState>
        stateFlow.value = ReceiptScanState(
            step = ScanStep.REVIEW,
            receiptId = 7L,
            rawOcrText = "LIDL TOTAL 12.34",
            editMerchant = "Lidl",
            editAmount = "12.34",
            editDate = 999L,
            ocrConfidence = 0.3f
        )

        viewModel.requestCategoryAssist()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.categoryAssistState is AiLoadState.Ready)
        assertEquals("Cloud - google-ai-studio - gemini-2.5-flash", viewModel.state.value.categoryAssistDiagnostics)

        viewModel.applyCategoryAssist()
        advanceUntilIdle()

        assertEquals(5L, viewModel.state.value.selectedCategoryId)
        coVerify { aiArtifactRepository.markApplied(any()) }
    }

    @Test
    fun `stress - requestCategoryAssist keeps failed artifact diagnostics on error`() = runTest {
        val receipt = ScannedReceipt(
            id = 7L,
            imagePath = "receipt.jpg",
            rawOcrText = "LIDL TOTAL 12.34",
            parsedTotal = 12.34,
            parsedMerchant = "Lidl",
            parsedDate = 999L,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.3f
        )
        coEvery { receiptRepository.getReceiptById(7L) } returns receipt
        coEvery {
            suggestCategoryFallbackUseCase(receipt, "Lidl", 12.34, 999L, null, false)
        } returns CategoryAssistGenerationResult.Error("AI category assist failed.")
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.CATEGORIZATION_FALLBACK) } returns AiArtifactEntity(
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = 7L,
            targetKey = "scanned_receipt:7",
            capability = AiCapability.CATEGORIZATION_FALLBACK,
            status = AiArtifactStatus.FAILED,
            mode = AiMode.ON_DEVICE,
            provider = "mlkit-genai-nano",
            modelName = "gemini-nano",
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ReceiptScanState>
        stateFlow.value = ReceiptScanState(
            step = ScanStep.REVIEW,
            receiptId = 7L,
            rawOcrText = "LIDL TOTAL 12.34",
            editMerchant = "Lidl",
            editAmount = "12.34",
            editDate = 999L,
            ocrConfidence = 0.3f
        )

        viewModel.requestCategoryAssist()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.categoryAssistState is AiLoadState.Error)
        assertEquals("On-device - mlkit-genai-nano - gemini-nano", viewModel.state.value.categoryAssistDiagnostics)
    }

    @Test
    fun `stress - requestReceiptQuickSaveConfirmation builds preview from AI suggestions`() = runTest {
        settingsFlow.value = AiSettings(aiEnabled = true, receiptQuickSaveEnabled = true)
        advanceUntilIdle()

        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ReceiptScanState>
        stateFlow.value = ReceiptScanState(
            step = ScanStep.REVIEW,
            receiptId = 7L,
            editMerchant = "",
            editAmount = "",
            editDate = 999L,
            receiptQuickSaveEnabled = true,
            receiptAssistState = AiLoadState.Ready(
                ReceiptAssistSuggestion(
                    merchant = SuggestedValue("Lidl"),
                    total = SuggestedValue(12.34)
                )
            ),
            categoryAssistState = AiLoadState.Ready(
                CategoryAssistSuggestion(categoryId = 5L, categoryName = "Groceries")
            )
        )

        viewModel.requestReceiptQuickSaveConfirmation()

        val preview = viewModel.state.value.quickSavePreview
        assertNotNull(preview)
        assertEquals(listOf("merchant", "amount", "category"), preview?.autoAppliedFields)
        assertEquals("Lidl", preview?.merchant)
        assertEquals("12.34", preview?.amountText)
        assertEquals(5L, preview?.categoryId)
    }

    @Test
    fun `stress - confirmReceiptQuickSave saves through normal repository path`() = runTest {
        settingsFlow.value = AiSettings(aiEnabled = true, receiptQuickSaveEnabled = true)
        advanceUntilIdle()
        coEvery {
            receiptRepository.createExpenseFromReceipt(
                receiptId = 7L,
                merchant = "Lidl",
                amount = 12.34,
                currency = "EUR",
                categoryId = 5L,
                date = 999L,
                paymentMethod = any(),
                notes = null
            )
        } returns Result.Success(9L)
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.RECEIPT_EXTRACTION) } returns AiArtifactEntity(
            id = 11L,
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = 7L,
            targetKey = "scanned_receipt:7",
            capability = AiCapability.RECEIPT_EXTRACTION,
            status = AiArtifactStatus.READY,
            mode = AiMode.AUTO,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.CATEGORIZATION_FALLBACK) } returns AiArtifactEntity(
            id = 12L,
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = 7L,
            targetKey = "scanned_receipt:7",
            capability = AiCapability.CATEGORIZATION_FALLBACK,
            status = AiArtifactStatus.READY,
            mode = AiMode.AUTO,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ReceiptScanState>
        stateFlow.value = ReceiptScanState(
            step = ScanStep.REVIEW,
            receiptId = 7L,
            editMerchant = "",
            editAmount = "",
            editDate = 999L,
            receiptQuickSaveEnabled = true,
            receiptAssistState = AiLoadState.Ready(
                ReceiptAssistSuggestion(
                    merchant = SuggestedValue("Lidl"),
                    total = SuggestedValue(12.34)
                )
            ),
            categoryAssistState = AiLoadState.Ready(
                CategoryAssistSuggestion(categoryId = 5L, categoryName = "Groceries")
            )
        )

        viewModel.requestReceiptQuickSaveConfirmation()
        viewModel.confirmReceiptQuickSave()
        advanceUntilIdle()

        assertEquals(ScanStep.DONE, viewModel.state.value.step)
        assertEquals("Lidl", viewModel.state.value.editMerchant)
        assertEquals("12.34", viewModel.state.value.editAmount)
        assertEquals(5L, viewModel.state.value.selectedCategoryId)
        coVerify { aiArtifactRepository.markApplied(11L) }
        coVerify { aiArtifactRepository.markApplied(12L) }
    }

    @Test
    fun `stress - dismissCategoryAssist clears state and marks artifact dismissed`() = runTest {
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.CATEGORIZATION_FALLBACK) } returns AiArtifactEntity(
            id = 5L,
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = 7L,
            targetKey = "scanned_receipt:7",
            capability = AiCapability.CATEGORIZATION_FALLBACK,
            status = AiArtifactStatus.READY,
            mode = AiMode.AUTO,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ReceiptScanState>
        stateFlow.value = ReceiptScanState(
            step = ScanStep.REVIEW,
            receiptId = 7L,
            rawOcrText = "TEXT",
            categoryAssistState = AiLoadState.Ready(CategoryAssistSuggestion(5L, "Groceries")),
            categoryAssistDiagnostics = "Cloud - google-ai-studio - gemini-2.5-flash"
        )

        viewModel.dismissCategoryAssist()
        advanceUntilIdle()

        coVerify { aiArtifactRepository.markDismissed(5L) }
        assertEquals(AiLoadState.Idle, viewModel.state.value.categoryAssistState)
        assertEquals(null, viewModel.state.value.categoryAssistDiagnostics)
    }

    @Test
    fun `stress - dismissReceiptAssist clears state and marks artifact dismissed`() = runTest {
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.RECEIPT_EXTRACTION) } returns AiArtifactEntity(
            id = 4L,
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = 7L,
            targetKey = "scanned_receipt:7",
            capability = AiCapability.RECEIPT_EXTRACTION,
            status = AiArtifactStatus.READY,
            mode = AiMode.AUTO,
            promptVersion = "v1",
            sourceHash = "hash",
            createdAt = 0L,
            updatedAt = 0L
        )

        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ReceiptScanState>
        stateFlow.value = ReceiptScanState(
            step = ScanStep.REVIEW,
            receiptId = 7L,
            rawOcrText = "TEXT",
            receiptAssistState = AiLoadState.Ready(ReceiptAssistSuggestion(merchant = SuggestedValue("Lidl"))),
            receiptAssistDiagnostics = "Cloud - google-ai-studio - gemini-2.5-flash"
        )

        viewModel.dismissReceiptAssist()
        advanceUntilIdle()

        coVerify { aiArtifactRepository.markDismissed(4L) }
        assertEquals(AiLoadState.Idle, viewModel.state.value.receiptAssistState)
        assertEquals(null, viewModel.state.value.receiptAssistDiagnostics)
    }
}
