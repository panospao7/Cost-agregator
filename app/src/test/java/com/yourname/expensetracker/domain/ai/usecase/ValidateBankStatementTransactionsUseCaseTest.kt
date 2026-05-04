package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.ai.provider.OnDeviceReceiptAssistService
import com.yourname.expensetracker.data.ai.provider.SmartReceiptAssistService
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Test stub for [ValidateBankStatementTransactionsUseCase].
 *
 * ## Coverage gaps (not yet tested):
 * 1. On-device AI success path — verifies that on-device results are returned
 *    without falling through to cloud.
 * 2. On-device failure → cloud fallback path — verifies privacy gate check
 *    and cloud service invocation.
 * 3. Privacy gate denial — verifies that cloud fallback is skipped when the
 *    gate denies [PrivacyCapability.CLOUD_AI_BANK_STATEMENT].
 * 4. Parser-only fallback — verifies that when both AI services fail, each
 *    candidate is returned as [CleanTransaction] with source "PARSER_ONLY".
 * 5. [parseAiResponse] edge cases — empty response, malformed JSON,
 *    wrapped `{"transactions": [...]}` envelope, markdown fence stripping.
 * 6. Source attribution — entries where AI values differ from candidates
 *    produce source "AI_CORRECTED" vs "AI_VALIDATED".
 *
 * These tests should be added incrementally as the use case matures.
 */
class ValidateBankStatementTransactionsUseCaseTest {

    private lateinit var smartReceiptAssist: SmartReceiptAssistService
    private lateinit var onDeviceReceiptAssist: OnDeviceReceiptAssistService
    private lateinit var privacyGate: PrivacyGate
    private lateinit var useCase: ValidateBankStatementTransactionsUseCase

    @Before
    fun setup() {
        smartReceiptAssist = mockk()
        onDeviceReceiptAssist = mockk()
        privacyGate = mockk()
        useCase = ValidateBankStatementTransactionsUseCase(
            smartReceiptAssist = smartReceiptAssist,
            onDeviceReceiptAssist = onDeviceReceiptAssist,
            privacyGate = privacyGate
        )
    }

    @Test
    fun `empty candidates returns empty list`() {
        val result = runBlocking {
            useCase.validateTransactions(
                rawOcrText = "some OCR text",
                candidateTransactions = emptyList(),
                homeCurrency = "EUR"
            )
        }
        assertTrue("Expected empty list for empty candidates", result.isEmpty())
    }

    @Test
    fun `on device success returns AI validated transactions`() {
        val candidates = listOf(
            DebugTransaction(merchant = "Coffee Shop", amount = 4.50, currency = "EUR", date = 0L, confidence = 0.8f)
        )
        val aiJson = """[{"merchant":"Coffee Shop","amount":4.50,"currency":"EUR","date":"2025-03-15","confidence":0.9}]"""

        coEvery { onDeviceReceiptAssist.suggestFromText(any()) } returns AiServiceResult.Success(aiJson)
        // Gate should not be called when on-device succeeds
        coEvery { privacyGate.check(any<PrivacyCapability>(), any()) } returns PrivacyDecision.Allowed

        val result = runBlocking {
            useCase.validateTransactions("OCR text", candidates, "EUR")
        }

        assertEquals("Expected 1 transaction", 1, result.size)
        assertEquals("Coffee Shop", result[0].merchant)
        assertEquals(4.50, result[0].amount, 0.001)
        assertEquals("AI_VALIDATED", result[0].source)
    }

    @Test
    fun `on device failure falls back to cloud AI`() {
        val candidates = listOf(
            DebugTransaction(merchant = "Supermarket", amount = 32.00, currency = "EUR", date = 0L, confidence = 0.8f)
        )
        val aiJson = """{"transactions":[{"merchant":"Supermarket","amount":32.00,"currency":"EUR","date":"2025-03-20","confidence":0.85}]}"""

        coEvery { onDeviceReceiptAssist.suggestFromText(any()) } returns AiServiceResult.Failure(
            com.yourname.expensetracker.domain.ai.model.AiServiceError.Unknown("on-device unavailable")
        )
        coEvery { privacyGate.check(PrivacyCapability.CLOUD_AI_BANK_STATEMENT, any()) } returns PrivacyDecision.Allowed
        coEvery { smartReceiptAssist.suggestFromText(any()) } returns AiServiceResult.Success(aiJson)

        val result = runBlocking {
            useCase.validateTransactions("OCR text", candidates, "EUR")
        }

        assertEquals("Expected 1 transaction", 1, result.size)
        assertEquals("Supermarket", result[0].merchant)
        // Verify the gate was checked before cloud
        coVerify { privacyGate.check(PrivacyCapability.CLOUD_AI_BANK_STATEMENT, any()) }
    }

    @Test
    fun `privacy gate denial skips cloud fallback`() {
        val candidates = listOf(
            DebugTransaction(merchant = "E-shop", amount = 19.99, currency = "EUR", date = 0L, confidence = 0.8f)
        )

        coEvery { onDeviceReceiptAssist.suggestFromText(any()) } returns AiServiceResult.Failure(
            com.yourname.expensetracker.domain.ai.model.AiServiceError.Unknown("on-device unavailable")
        )
        coEvery { privacyGate.check(PrivacyCapability.CLOUD_AI_BANK_STATEMENT, any()) } returns PrivacyDecision.Denied("user disabled")

        val result = runBlocking {
            useCase.validateTransactions("OCR text", candidates, "EUR")
        }

        assertEquals("Expected 1 PARSER_ONLY transaction", 1, result.size)
        assertEquals("PARSER_ONLY", result[0].source)
    }

    companion object {
        // Helper to run suspend functions in tests
        private fun <T> runBlocking(block: suspend () -> T): T {
            return kotlinx.coroutines.runBlocking { block() }
        }
    }
}
