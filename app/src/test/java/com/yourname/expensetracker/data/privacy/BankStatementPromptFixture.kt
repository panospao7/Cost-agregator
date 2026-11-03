package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.data.ai.provider.OnDeviceReceiptAssistService
import com.yourname.expensetracker.data.ai.provider.SmartReceiptAssistService
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.usecase.DebugTransaction
import com.yourname.expensetracker.domain.ai.usecase.StatementValidationContracts
import com.yourname.expensetracker.domain.ai.usecase.ValidateBankStatementTransactionsUseCase
import com.yourname.expensetracker.domain.core.money.CurrencyAssumption
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking

/**
 * Synthetic-data fixture for the S6 (N-05-3) bank-statement prompt tests.
 *
 * Prompts are produced by the REAL [ValidateBankStatementTransactionsUseCase] (captured at the
 * on-device seam), so the policy tests exercise the exact shape the use case sends. All data is
 * synthetic (documentation example IBAN, well-known test card number, example.com addresses).
 */
internal object BankStatementPromptFixture {

    const val OCR_MARKER = StatementValidationContracts.PROMPT_OCR_MARKER
    const val CANDIDATES_MARKER = StatementValidationContracts.PROMPT_CANDIDATES_MARKER
    const val UNSANITIZABLE_CODE = "BANK_STATEMENT_PAYLOAD_UNSANITIZABLE"

    const val EMAIL = "jane.doe@example.com"
    const val IBAN = "DE89370400440532013000"
    const val CARD = "4111 1111 1111 1111"

    fun candidate(
        id: Int,
        merchant: String,
        amount: Double = 10.5,
        currency: String = "EUR",
        date: Long = 1_710_000_000_000L
    ) = DebugTransaction(
        merchant = merchant,
        amount = amount,
        currency = currency,
        date = date,
        confidence = 0.8f,
        candidateId = id,
        currencyAssumption = CurrencyAssumption.PARSED_FROM_SOURCE
    )

    /** Runs the real use case against a failing on-device stub and returns the prompt it built. */
    fun capturePrompt(ocr: String, candidates: List<DebugTransaction>): String {
        require(candidates.size in 1..StatementValidationContracts.MAX_CANDIDATES_PER_AI_CALL)
        val captured = slot<String>()
        val onDevice = mockk<OnDeviceReceiptAssistService>()
        coEvery { onDevice.suggestFromText(capture(captured)) } returns
            AiServiceResult.Failure(AiServiceError.Unknown("on-device unavailable"))
        val gate = mockk<PrivacyGate>()
        coEvery { gate.check(any<PrivacyCapability>(), any()) } returns PrivacyDecision.Denied("test")
        val useCase = ValidateBankStatementTransactionsUseCase(
            smartReceiptAssist = mockk<SmartReceiptAssistService>(relaxed = true),
            onDeviceReceiptAssist = onDevice,
            privacyGate = gate,
            currencyNormalizer = CurrencyNormalizer()
        )
        runBlocking { useCase.validateTransactions(ocr, candidates, "EUR") }
        return captured.captured
    }

    /** The constant instruction block of a real prompt (everything before the OCR marker). */
    val realHeader: String by lazy {
        capturePrompt("seed", listOf(candidate(0, "Seed"))).substringBefore(OCR_MARKER)
    }

    /** One candidate line exactly as the use case writes it. */
    fun candidateLine(
        id: Int,
        merchant: String,
        amount: String = "10.5",
        currency: String = "EUR",
        date: String = "1710000000000"
    ): String =
        "{\"candidateId\":$id, \"merchant\":\"$merchant\", \"amount\":$amount, " +
            "\"currency\":\"$currency\", \"date\":$date}"

    /** Hand-assembled prompt in the real section order, for shapes the use case cannot produce. */
    fun manualPrompt(
        ocr: String,
        candidateLines: List<String>,
        header: String = realHeader
    ): String =
        header + OCR_MARKER + "\n" + ocr + "\n\n" + CANDIDATES_MARKER + "\n" +
            candidateLines.joinToString("") { it + "\n" }

    /** Marker-based view of a prepared prompt: OCR segment lines and candidate lines. */
    class Sections(val text: String) {
        val lines: List<String> = text.lines()
        val ocrIndex: Int = lines.indexOf(OCR_MARKER)
        val candidatesIndex: Int = lines.lastIndexOf(CANDIDATES_MARKER)
        val ocrLines: List<String> =
            lines.subList(ocrIndex + 1, candidatesIndex).filter { it.isNotBlank() }
        val candidateLines: List<String> =
            lines.subList(candidatesIndex + 1, lines.size).filter { it.isNotBlank() }
    }
}
