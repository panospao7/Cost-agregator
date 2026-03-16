package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiLoadState
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.model.SuggestedValue
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
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
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var timeProvider: TimeProvider
    private lateinit var suggestReceiptExtractionUseCase: SuggestReceiptExtractionUseCase
    private lateinit var aiArtifactRepository: AiArtifactRepository

    private lateinit var viewModel: ReceiptScanViewModel

    @Before
    override fun setup() {
        super.setup()
        receiptRepository = mockk(relaxed = true)
        categoryRepository = mockk(relaxed = true)
        savedStateHandle = SavedStateHandle()
        timeProvider = mockk(relaxed = true)
        suggestReceiptExtractionUseCase = mockk(relaxed = true)
        aiArtifactRepository = mockk(relaxed = true)

        every { timeProvider.now() } returns System.currentTimeMillis()
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        every { receiptRepository.createTempPhotoUri() } returns Uri.parse("content://test/photo.jpg")

        viewModel = ReceiptScanViewModel(
            receiptRepository,
            categoryRepository,
            savedStateHandle,
            timeProvider,
            suggestReceiptExtractionUseCase,
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

        assertEquals("Lidl", viewModel.state.value.editMerchant)
        assertEquals("12.34", viewModel.state.value.editAmount)
        assertEquals(999L, viewModel.state.value.editDate)
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
