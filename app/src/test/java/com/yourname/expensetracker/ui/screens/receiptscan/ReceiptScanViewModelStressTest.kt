package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
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
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.SuggestCategoryFallbackUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptItemCategorizationRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var aiSettingsRepository: AiSettingsRepository
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var timeProvider: TimeProvider
    private lateinit var suggestReceiptExtractionUseCase: SuggestReceiptExtractionUseCase
    private lateinit var suggestCategoryFallbackUseCase: SuggestCategoryFallbackUseCase
    private lateinit var aiArtifactRepository: AiArtifactRepository
    private lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics
    private lateinit var settingsFlow: kotlinx.coroutines.flow.MutableStateFlow<AiSettings>

    private lateinit var receiptLifecycleCoordinator: ReceiptLifecycleCoordinator
    private lateinit var receiptParser: ReceiptParser
    private lateinit var receiptLinkService: ReceiptLinkService
    private lateinit var merchantNormalizer: MerchantNormalizer
    private lateinit var hybridClassifier: HybridExpenseClassifier
    private lateinit var fixtureScope: CoroutineScope
    private lateinit var viewModel: ReceiptScanViewModel

    @Before
    override fun setup() {
        super.setup()
        receiptRepository = mockk(relaxed = true)
        receiptLifecycleCoordinator = mockk()
        receiptParser = mockk()
        receiptLinkService = mockk()
        merchantNormalizer = mockk()
        hybridClassifier = mockk()
        categoryRepository = mockk(relaxed = true)
        currencySettingsRepository = mockk(relaxed = true)
        aiSettingsRepository = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()
        timeProvider = mockk(relaxed = true)
        suggestReceiptExtractionUseCase = mockk(relaxed = true)
        suggestCategoryFallbackUseCase = mockk(relaxed = true)
        aiArtifactRepository = mockk(relaxed = true)
        // Spy over a real instance: the default `now = timeProvider.now()` argument is
        // resolved from the instance field, which is null on a constructor-less mock.
        aiRuntimeDiagnostics = spyk(AiRuntimeDiagnostics(timeProvider))
        settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(AiSettings(aiEnabled = true))
        
        val categorizeReceiptItemsUseCase = mockk<CategorizeReceiptItemsUseCase>(relaxed = true)
        val itemCategorizationRepository = mockk<ReceiptItemCategorizationRepository>(relaxed = true)

        every { timeProvider.now() } returns 1_700_000_000_000L
        every { categoryRepository.allCategories } returns flowOf(
            listOf(Category(id = 5L, name = "Groceries", icon = "G", color = "#008800"))
        )
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        every { aiSettingsRepository.settings() } returns settingsFlow
        every { receiptRepository.createTempPhotoUri() } returns Uri.parse("content://test/photo.jpg")

        viewModel = ReceiptScanViewModel(
            receiptRepository,
            categoryRepository,
            currencySettingsRepository,
            aiSettingsRepository,
            savedStateHandle,
            timeProvider,
            suggestReceiptExtractionUseCase,
            suggestCategoryFallbackUseCase,
            categorizeReceiptItemsUseCase,
            itemCategorizationRepository,
            aiArtifactRepository,
            aiRuntimeDiagnostics,
            receiptLifecycleCoordinator = receiptLifecycleCoordinator,
            receiptParser = receiptParser,
            transactionLifecycleCoordinator = mockk(),
            receiptLinkService = receiptLinkService,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
        )
        // Exercise the production WhileSubscribed category stream on the same scheduler.
        fixtureScope = CoroutineScope(SupervisorJob() + testDispatcher)
        fixtureScope.launch { viewModel.categories.collect { } }
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf(5L), viewModel.categories.value.map { it.id })
    }

    @After
    override fun tearDown() {
        try {
            if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
            if (::fixtureScope.isInitialized) fixtureScope.cancel()
            testDispatcher.scheduler.runCurrent()
        } finally {
            super.tearDown()
        }
    }

    @Test
    fun `stress - initial step is CAPTURE`() = runTest(testDispatcher) {
        assertEquals(ScanStep.CAPTURE, viewModel.state.value.step)
    }

    @Test
    fun `stress - createTempPhotoUri returns uri and updates state`() = runTest(testDispatcher) {
        val uri = viewModel.createTempPhotoUri()
        advanceUntilIdle()
        assertNotNull(uri)
        assertEquals(uri, viewModel.state.value.tempCameraUri)
    }

    @Test
    fun `stress - processPhoto with no uri does not crash`() = runTest(testDispatcher) {
        viewModel.processPhoto()
        advanceUntilIdle()
        assertEquals(ScanStep.CAPTURE, viewModel.state.value.step)
    }

    @Test
    fun `stress - processGalleryImage with uri updates step to PROCESSING`() = runTest(testDispatcher) {
        val uri = Uri.parse("content://test/gallery.jpg")
        coEvery { receiptLifecycleCoordinator.processReceiptInput(uri, any()) } coAnswers {
            awaitCancellation()
        }
        viewModel.processGalleryImage(uri)
        runCurrent()
        assertEquals(ScanStep.PROCESSING, viewModel.state.value.step)
        assertEquals(uri, viewModel.state.value.imageUri)
        coVerify(exactly = 1) { receiptLifecycleCoordinator.processReceiptInput(uri, any()) }
    }

    @Test
    fun `stress - OCR fallback resets editable and transient state`() = runTest(testDispatcher) {
        val uri = Uri.parse("content://test/fallback.jpg")
        val now = 1_234_567L
        every { timeProvider.now() } returns now

        coEvery { receiptLifecycleCoordinator.processReceiptInput(uri, any()) } returns Result.success(
            ReceiptLifecycleCoordinator.ReceiptProcessOutcome(
                savedReceipt = ScannedReceipt(
                    id = 42L,
                    imagePath = "/manual/path.jpg",
                    rawOcrText = "[OCR Failed or Skipped]",
                    parsedTotal = null,
                    parsedMerchant = null,
                    parsedDate = now,
                    parsedItems = null,
                    parsedTaxAmount = null,
                    currency = "EUR",
                    confidence = 0f,
                    processingStatus = ReceiptProcessingStatus.OCR_FAILED.name
                ),
                inserted = true,
                postCommitBatch = null
            )
        )
        coEvery { receiptLinkService.checkCanLinkReceipt(42L) } returns true

        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ReceiptScanState>
        stateFlow.value = ReceiptScanState(
            step = ScanStep.REVIEW,
            imageUri = Uri.parse("content://test/previous.jpg"),
            receiptId = 7L,
            rawOcrText = "OLD OCR",
            showRawText = true,
            editMerchant = "Old Merchant",
            editAmount = "9.99",
            editCurrency = "EUR",
            editDate = 111L,
            selectedCategoryId = 5L,
            errorMessage = "Old error",
            isSaving = true,
            saveResult = SaveReceiptResult.Success(expenseId = 1L),
            receiptAssistState = AiLoadState.Ready(ReceiptAssistSuggestion(merchant = SuggestedValue("AI Merchant"))),
            receiptAssistMessage = "old receipt assist",
            receiptAssistDiagnostics = "old receipt diagnostics",
            categoryAssistState = AiLoadState.Ready(CategoryAssistSuggestion(3L, "Groceries")),
            categoryAssistMessage = "old category assist",
            categoryAssistDiagnostics = "old category diagnostics",
            quickSavePreview = ReceiptQuickSavePreview(
                merchant = "Preview Merchant",
                amount = 12.0,
                amountText = "12.00",
                date = 999L,
                categoryId = 3L,
                categoryName = "Groceries",
                autoAppliedFields = listOf("merchant"),
                usedCapabilities = setOf(AiCapability.RECEIPT_EXTRACTION),
                fieldSummaries = emptyList(),
                diagnostics = emptyList()
            ),
            isAnalyzingItems = true,
            showItemBreakdown = true,
            itemAnalysisError = "old item analysis error",
            hasPartialOcr = true,
            hasUnverifiedOcrCoverage = true,
            pendingAppliedAiCapabilities = setOf(AiCapability.RECEIPT_EXTRACTION, AiCapability.CATEGORIZATION_FALLBACK)
        )

        viewModel.processGalleryImage(uri)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ScanStep.REVIEW, state.step)
        assertEquals(42L, state.receiptId)
        assertEquals("", state.rawOcrText)
        assertEquals("", state.editMerchant)
        assertEquals("", state.editAmount)
        assertEquals(now, state.editDate)
        assertEquals(null, state.selectedCategoryId)
        assertEquals(0f, state.ocrConfidence, 0.001f)
        assertEquals(false, state.isSaving)
        assertEquals(null, state.saveResult)
        assertEquals("OCR could not be processed (OCR_FAILED). You can enter details manually.", state.errorMessage)
        assertEquals(AiLoadState.Idle, state.receiptAssistState)
        assertEquals(null, state.receiptAssistMessage)
        assertEquals(null, state.receiptAssistDiagnostics)
        assertEquals(AiLoadState.Idle, state.categoryAssistState)
        assertEquals(null, state.categoryAssistMessage)
        assertEquals(null, state.categoryAssistDiagnostics)
        assertEquals(null, state.quickSavePreview)
        assertEquals(false, state.isAnalyzingItems)
        assertEquals(false, state.showItemBreakdown)
        assertEquals(null, state.itemAnalysisError)
        assertEquals("EUR", state.editCurrency)
        assertFalse(state.showRawText)
        assertFalse(state.hasPartialOcr)
        assertFalse(state.hasUnverifiedOcrCoverage)
        assertTrue(state.pendingAppliedAiCapabilities.isEmpty())
        assertEquals("", state.debugData?.rawText)
        assertEquals(listOf("Processing Error: OCR_FAILED"), state.debugData?.parsingLogs)
        coVerify(exactly = 1) { receiptLifecycleCoordinator.processReceiptInput(uri, any()) }
    }

    @Test
    fun `stress - requestReceiptAssist sets Ready and applies fields`() = runTest(testDispatcher) {
        val suggestion = ReceiptAssistSuggestion(
            merchant = SuggestedValue("Lidl"),
            total = SuggestedValue(12.34),
            date = SuggestedValue(999L)
        )
        coEvery { suggestReceiptExtractionUseCase(7L, false) } returns ReceiptAssistGenerationResult.Success(
            suggestion = suggestion,
            fromCache = false,
            usedImageInput = false
        )
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.RECEIPT_EXTRACTION) } returns AiArtifactRecord(
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
        assertEquals(setOf(AiCapability.RECEIPT_EXTRACTION), viewModel.state.value.pendingAppliedAiCapabilities)
        coVerify(exactly = 0) { aiArtifactRepository.markApplied(any()) }
    }

    @Test
    fun `stress - requestReceiptAssist surfaces on-device diagnostics when latest artifact is local`() = runTest(testDispatcher) {
        val suggestion = ReceiptAssistSuggestion(
            merchant = SuggestedValue("Lidl")
        )
        coEvery { suggestReceiptExtractionUseCase(7L, false) } returns ReceiptAssistGenerationResult.Success(
            suggestion = suggestion,
            fromCache = false,
            usedImageInput = false
        )
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.RECEIPT_EXTRACTION) } returns AiArtifactRecord(
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
    fun `stress - requestReceiptAssist surfaces image-aware message when vision input was used`() = runTest(testDispatcher) {
        val suggestion = ReceiptAssistSuggestion(
            merchant = SuggestedValue("AB Βασιλόπουλος")
        )
        coEvery { suggestReceiptExtractionUseCase(7L, false) } returns ReceiptAssistGenerationResult.Success(
            suggestion = suggestion,
            fromCache = false,
            usedImageInput = true
        )
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.RECEIPT_EXTRACTION) } returns AiArtifactRecord(
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
            rawOcrText = "ΑΒ ΒΑΣΙΛΟΠΟΥΛΟΣ"
        )

        viewModel.requestReceiptAssist()
        advanceUntilIdle()

        assertEquals(
            "Image-aware AI cross-checked the receipt photo and OCR text.",
            viewModel.state.value.receiptAssistMessage
        )
    }

    @Test
    fun `stress - image cloud toggle alone does not enable quick save`() = runTest(testDispatcher) {
        settingsFlow.value = AiSettings(
            aiEnabled = true,
            receiptAssistEnabled = true,
            receiptImageCloudEnabled = true,
            receiptQuickSaveEnabled = false
        )
        advanceUntilIdle()

        assertNull(viewModel.quickSaveUnavailableReason())
    }

    @Test
    fun `stress - requestReceiptAssist keeps failed artifact diagnostics on error`() = runTest(testDispatcher) {
        coEvery { suggestReceiptExtractionUseCase(7L, false) } returns ReceiptAssistGenerationResult.Error(
            "AI receipt assist failed."
        )
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.RECEIPT_EXTRACTION) } returns AiArtifactRecord(
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
    fun `stress - requestCategoryAssist sets Ready and applies category`() = runTest(testDispatcher) {
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
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.CATEGORIZATION_FALLBACK) } returns AiArtifactRecord(
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
        assertEquals(setOf(AiCapability.CATEGORIZATION_FALLBACK), viewModel.state.value.pendingAppliedAiCapabilities)
        coVerify(exactly = 0) { aiArtifactRepository.markApplied(any()) }
    }

    @Test
    fun `stress - requestCategoryAssist keeps failed artifact diagnostics on error`() = runTest(testDispatcher) {
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
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.CATEGORIZATION_FALLBACK) } returns AiArtifactRecord(
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
    fun `stress - requestReceiptQuickSaveConfirmation builds preview from AI suggestions`() = runTest(testDispatcher) {
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
            editCurrency = "EUR",
            // Above the production RCP-11 quick-save confidence minimum (0.5f).
            ocrConfidence = 0.8f,
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
    fun `stress - quickSaveUnavailableReason explains when AI assist has not been requested`() = runTest(testDispatcher) {
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
            receiptQuickSaveEnabled = true
        )

        assertEquals(
            "Request AI receipt or category assist first.",
            viewModel.quickSaveUnavailableReason()
        )
    }

    @Test
    fun `stress - requestReceiptQuickSaveConfirmation blocks when toggle is off`() = runTest(testDispatcher) {
        settingsFlow.value = AiSettings(aiEnabled = true, receiptQuickSaveEnabled = false)
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
            receiptQuickSaveEnabled = false,
            receiptAssistState = AiLoadState.Ready(
                ReceiptAssistSuggestion(
                    merchant = SuggestedValue("Lidl"),
                    total = SuggestedValue(12.34)
                )
            )
        )

        viewModel.requestReceiptQuickSaveConfirmation()

        assertEquals("Receipt quick save is turned off.", viewModel.state.value.errorMessage)
        assertEquals(null, viewModel.state.value.quickSavePreview)
    }

    @Test
    fun `stress - receipt quick save preview clears and confirm stops after toggle turns off`() = runTest(testDispatcher) {
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
            editCurrency = "EUR",
            // Above the production RCP-11 quick-save confidence minimum (0.5f).
            ocrConfidence = 0.8f,
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
        assertNotNull(viewModel.state.value.quickSavePreview)

        settingsFlow.value = AiSettings(aiEnabled = true, receiptQuickSaveEnabled = false)
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.quickSavePreview)
        viewModel.confirmReceiptQuickSave()
        advanceUntilIdle()

        coVerify(exactly = 0) { receiptLifecycleCoordinator.createExpenseAndLinkReceipt(any()) }
        coVerify(exactly = 0) { aiArtifactRepository.markApplied(any()) }
    }

    @Test
    fun `stress - confirmReceiptQuickSave saves through lifecycle path`() = runTest(testDispatcher) {
        settingsFlow.value = AiSettings(aiEnabled = true, receiptQuickSaveEnabled = true)
        advanceUntilIdle()
        val saveResult = CompletableDeferred<Long>()
        coEvery { receiptLinkService.checkCanLinkReceipt(7L) } returns true
        coEvery { merchantNormalizer.normalize(rawName = "Lidl", autoCreate = true) } returns mockk {
            every { canonical } returns mockk {
                every { normalizedName } returns "lidl"
            }
        }
        coEvery {
            hybridClassifier.learnFromCorrection(merchantName = "lidl", correctCategoryId = 5L, amount = 12.34)
        } returns Unit
        coEvery { receiptLifecycleCoordinator.createExpenseAndLinkReceipt(any()) } coAnswers {
            Result.success(saveResult.await())
        }
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.RECEIPT_EXTRACTION) } returns AiArtifactRecord(
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
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.CATEGORIZATION_FALLBACK) } returns AiArtifactRecord(
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
            editCurrency = "EUR",
            // Above the production RCP-11 quick-save confidence minimum (0.5f).
            ocrConfidence = 0.8f,
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
        assertNotNull(viewModel.state.value.quickSavePreview)
        viewModel.confirmReceiptQuickSave()
        runCurrent()
        assertTrue(viewModel.state.value.isSaving)
        assertEquals(ScanStep.REVIEW, viewModel.state.value.step)
        assertNotNull(viewModel.state.value.quickSavePreview)
        coVerify(exactly = 1) { receiptLifecycleCoordinator.createExpenseAndLinkReceipt(any()) }
        coVerify(exactly = 0) { aiArtifactRepository.markApplied(any()) }

        saveResult.complete(9L)
        advanceUntilIdle()

        assertEquals(ScanStep.DONE, viewModel.state.value.step)
        assertEquals("Lidl", viewModel.state.value.editMerchant)
        assertEquals("12.34", viewModel.state.value.editAmount)
        assertEquals(5L, viewModel.state.value.selectedCategoryId)
        assertEquals(9L, (viewModel.state.value.saveResult as? SaveReceiptResult.Success)?.expenseId)
        assertNull(viewModel.state.value.quickSavePreview)
        assertTrue(viewModel.state.value.pendingAppliedAiCapabilities.isEmpty())
        coVerify(exactly = 1) { receiptLifecycleCoordinator.createExpenseAndLinkReceipt(any()) }
        coVerify(exactly = 1) {
            receiptLifecycleCoordinator.createExpenseAndLinkReceipt(match {
                it.scannedReceiptId == 7L && it.merchant == "Lidl" && it.amount == 12.34 &&
                    it.currency == "EUR" && it.categoryId == 5L && it.date == 999L &&
                    it.notes == "Scanned from receipt"
            })
        }
        coVerify(exactly = 1) { receiptLinkService.checkCanLinkReceipt(7L) }
        coVerify(exactly = 1) { merchantNormalizer.normalize(rawName = "Lidl", autoCreate = true) }
        coVerify(exactly = 1) {
            hybridClassifier.learnFromCorrection(merchantName = "lidl", correctCategoryId = 5L, amount = 12.34)
        }
        coVerify(exactly = 1) { aiArtifactRepository.markApplied(11L) }
        coVerify(exactly = 1) { aiArtifactRepository.markApplied(12L) }
        verify { aiRuntimeDiagnostics.recordInteraction(type = "phase4_accept", message = any(), now = any()) }
    }

    @Test
    fun `stress - dismissCategoryAssist clears state and marks artifact dismissed`() = runTest(testDispatcher) {
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.CATEGORIZATION_FALLBACK) } returns AiArtifactRecord(
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
    fun `stress - dismissReceiptAssist clears state and marks artifact dismissed`() = runTest(testDispatcher) {
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:7", AiCapability.RECEIPT_EXTRACTION) } returns AiArtifactRecord(
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

    @Test
    fun switchingReceiptsDoesNotMarkUnappliedArtifactsOnTheNextReceipt() = runTest(testDispatcher) {
        val field = ReceiptScanViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ReceiptScanState>
        stateFlow.value = ReceiptScanState(
            step = ScanStep.REVIEW,
            receiptId = 7L,
            editCurrency = "EUR",
            receiptAssistState = AiLoadState.Ready(
                ReceiptAssistSuggestion(merchant = SuggestedValue("AI for receipt seven"))
            )
        )
        viewModel.applyAllReceiptAssist()
        assertEquals(setOf(AiCapability.RECEIPT_EXTRACTION), viewModel.state.value.pendingAppliedAiCapabilities)

        val uri = Uri.parse("content://test/another-receipt.jpg")
        val scanResult = CompletableDeferred<ReceiptLifecycleCoordinator.ReceiptProcessOutcome>()
        coEvery { receiptLifecycleCoordinator.processReceiptInput(uri, any()) } coAnswers {
            Result.success(scanResult.await())
        }
        coEvery { receiptLinkService.checkCanLinkReceipt(18L) } returns true
        viewModel.processGalleryImage(uri)
        assertEquals(ScanStep.PROCESSING, viewModel.state.value.step)
        assertTrue(viewModel.state.value.pendingAppliedAiCapabilities.isEmpty())
        runCurrent()
        scanResult.complete(
            ReceiptLifecycleCoordinator.ReceiptProcessOutcome(
                savedReceipt = ScannedReceipt(
                    id = 18L,
                    imagePath = null,
                    rawOcrText = "SECOND RECEIPT",
                    parsedTotal = 10.0,
                    parsedMerchant = "Second merchant",
                    parsedDate = 999L,
                    parsedItems = null,
                    parsedTaxAmount = null,
                    currency = "EUR",
                    confidence = 0.9f,
                    processingStatus = ReceiptProcessingStatus.PARSED.name
                ),
                inserted = false,
                postCommitBatch = null
            )
        )
        advanceUntilIdle()
        assertEquals(ScanStep.REVIEW, viewModel.state.value.step)
        assertEquals(18L, viewModel.state.value.receiptId)
        assertTrue(viewModel.state.value.pendingAppliedAiCapabilities.isEmpty())

        // The reopened receipt has a suggestion, but this user has not applied it.
        coEvery { aiArtifactRepository.getLatest("scanned_receipt:18", AiCapability.RECEIPT_EXTRACTION) } returns
            AiArtifactRecord(
                id = 22L, targetType = AiTargetType.SCANNED_RECEIPT, targetId = 18L,
                targetKey = "scanned_receipt:18", capability = AiCapability.RECEIPT_EXTRACTION,
                status = AiArtifactStatus.READY, mode = AiMode.AUTO, promptVersion = "v1",
                sourceHash = "hash", createdAt = 0L, updatedAt = 0L
            )
        coEvery { merchantNormalizer.normalize(rawName = "Second merchant", autoCreate = true) } returns mockk {
            every { canonical } returns mockk { every { normalizedName } returns "second merchant" }
        }
        coEvery { hybridClassifier.classify(merchantName = "second merchant", amount = 10.0) } returns mockk {
            every { categoryId } returns 5L
        }
        coEvery {
            hybridClassifier.learnFromCorrection(merchantName = "second merchant", correctCategoryId = 5L, amount = 10.0)
        } returns Unit
        coEvery { receiptLifecycleCoordinator.createExpenseAndLinkReceipt(any()) } returns Result.success(90L)

        viewModel.saveExpense()
        advanceUntilIdle()

        assertEquals(ScanStep.DONE, viewModel.state.value.step)
        coVerify(exactly = 1) {
            receiptLifecycleCoordinator.createExpenseAndLinkReceipt(match {
                it.scannedReceiptId == 18L && it.merchant == "Second merchant" &&
                    it.amount == 10.0 && it.currency == "EUR"
            })
        }
        coVerify(exactly = 0) {
            aiArtifactRepository.getLatest("scanned_receipt:18", AiCapability.RECEIPT_EXTRACTION)
        }
        coVerify(exactly = 0) { aiArtifactRepository.markApplied(any()) }
    }
}
