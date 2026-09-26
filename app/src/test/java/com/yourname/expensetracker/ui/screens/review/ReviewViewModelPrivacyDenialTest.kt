package com.yourname.expensetracker.ui.screens.review

import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.debug.DebugExportResult
import com.yourname.expensetracker.domain.debug.ReceiptDebugExporter
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test

/**
 * RP-15 (15-D) — ReviewViewModel debug-export denial convergence.
 *
 * Debug-export denial must land on the typed [PrivacyBlockedUiState]
 * (debugExportBlocked) with a bounded user-facing string; the exporter's
 * internal reason text and exception messages must never be rendered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelPrivacyDenialTest : ViewModelTestUtils() {

    private val receiptDebugExporter = mockk<ReceiptDebugExporter>()
    private val aiSettingsRepository = mockk<AiSettingsRepository>()

    private lateinit var viewModel: ReviewViewModel

    @Before
    override fun setup() {
        super.setup()
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings())
        viewModel = ReviewViewModel(
            mockk(relaxed = true), // notificationRepository
            mockk(relaxed = true), // reviewQueueRepository
            mockk(relaxed = true), // categoryRepository
            mockk(relaxed = true), // receiptRepository
            mockk(relaxed = true), // expenseRepository
            mockk(relaxed = true), // debugDataStorage
            mockk(relaxed = true), // geocodingService
            mockk(relaxed = true), // privacyGate
            mockk(relaxed = true), // explainPendingReviewUseCase
            mockk(relaxed = true), // suggestCategoryFallbackUseCase
            mockk(relaxed = true), // suggestReceiptExtractionUseCase
            mockk(relaxed = true), // judgePendingReviewDuplicateUseCase
            mockk(relaxed = true), // aiArtifactRepository
            aiSettingsRepository,
            mockk(relaxed = true), // aiRuntimeDiagnostics
            mockk(relaxed = true), // receiptLifecycleCoordinator
            receiptDebugExporter = receiptDebugExporter
        )
    }

    @After
    override fun tearDown() {
        try {
            runTest(testDispatcher) {
                if (::viewModel.isInitialized) {
                    viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
        } finally {
            super.tearDown()
        }
    }

    // ── Denial: exporter refuses the debug export ─────────────────────────────

    @Test
    fun `debug export denial sets typed blocked state and bounded text`() = runTest(testDispatcher) {
        coEvery {
            receiptDebugExporter.exportParserDebugData(any(), any(), any())
        } returns DebugExportResult.Denied("Raw OCR export blocked: internal mode detail")

        val text = viewModel.getDebugExportData()
        advanceUntilIdle()

        val blocked = viewModel.debugExportBlocked.value
        assertNotNull("denial must set the typed state", blocked)
        assertEquals(PrivacyCapability.DEBUG_RAW_EXPORT, blocked!!.capability)
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, blocked.reasonCode)
        assertEquals("Debug export is blocked by privacy settings", text)
        assertFalse(
            "exporter reason text must not be rendered",
            text.contains("internal mode detail")
        )
    }

    @Test
    fun `receipt debug denial also converges on the typed state`() = runTest(testDispatcher) {
        coEvery {
            receiptDebugExporter.debugReceipt(any(), any(), any(), any())
        } returns DebugExportResult.Denied("Receipt not found: internal detail")

        val text = viewModel.getReceiptDebugInfo(receiptId = 7L)
        advanceUntilIdle()

        val blocked = viewModel.debugExportBlocked.value
        assertNotNull(blocked)
        assertEquals(PrivacyCapability.DEBUG_RAW_EXPORT, blocked!!.capability)
        assertFalse(text.contains("internal detail"))
    }

    // ── Permitted path unchanged ───────────────────────────────────────────────

    @Test
    fun `permitted debug export clears the blocked state and returns content`() = runTest(testDispatcher) {
        coEvery {
            receiptDebugExporter.exportParserDebugData(any(), any(), any())
        } returns DebugExportResult.Allowed("debug content")

        // Seed a prior denial, then let a permitted export clear it.
        coEvery {
            receiptDebugExporter.exportParserDebugData(any(), any(), any())
        } returnsMany listOf(
            DebugExportResult.Denied("first"),
            DebugExportResult.Allowed("debug content")
        )

        val deniedText = viewModel.getDebugExportData()
        advanceUntilIdle()
        assertNotNull(viewModel.debugExportBlocked.value)
        assertTrue(deniedText.contains("blocked"))

        val permittedText = viewModel.getDebugExportData()
        advanceUntilIdle()
        assertNull(viewModel.debugExportBlocked.value)
        assertEquals("debug content", permittedText)
    }
}
