package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.ai.provider.OnDeviceReceiptAssistService
import com.yourname.expensetracker.data.ai.provider.SmartReceiptAssistService
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.core.money.CurrencyAssumption
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Large candidate lists are validated in AI calls of at most
 * [StatementValidationContracts.MAX_CANDIDATES_PER_AI_CALL] candidates. Inside a call the identity
 * contract is the existing positional one (ids 0..n-1); the caller's candidateIds are restored on
 * the way back, and one unusable call only degrades its own chunk to the parser echo.
 */
class ValidateBankStatementTransactionsBatchingTest {

    private lateinit var smartReceiptAssist: SmartReceiptAssistService
    private lateinit var onDeviceReceiptAssist: OnDeviceReceiptAssistService
    private lateinit var privacyGate: PrivacyGate
    private lateinit var useCase: ValidateBankStatementTransactionsUseCase
    private val prompts = mutableListOf<String>()

    private val acceptedDate = "2025-03-15"
    private val acceptedEpoch =
        LocalDate.parse(acceptedDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setup() {
        smartReceiptAssist = mockk(relaxed = true)
        onDeviceReceiptAssist = mockk(relaxed = true)
        privacyGate = mockk(relaxed = true)
        useCase = ValidateBankStatementTransactionsUseCase(
            smartReceiptAssist = smartReceiptAssist,
            onDeviceReceiptAssist = onDeviceReceiptAssist,
            privacyGate = privacyGate,
            currencyNormalizer = CurrencyNormalizer()
        )
        coEvery { privacyGate.check(any<PrivacyCapability>(), any()) } returns PrivacyDecision.Denied("test")
    }

    private fun candidates(count: Int) = (0 until count).map { id ->
        DebugTransaction(
            merchant = "M$id", amount = 1.0 + id, currency = "EUR", date = acceptedEpoch,
            confidence = 0.8f, candidateId = id, currencyAssumption = CurrencyAssumption.PARSED_FROM_SOURCE
        )
    }

    private val candidateLine = Regex("""\{"candidateId":(\d+), "merchant":"([^"]*)", "amount":([0-9.]+),""")

    /** Contract-valid response that accepts every candidate of the prompt unchanged. */
    private fun acceptEverything(prompt: String): String =
        candidateLine.findAll(prompt).joinToString(prefix = "[", postfix = "]", separator = ",") { match ->
            val (id, merchant, amount) = match.destructured
            "{\"candidateId\":$id,\"status\":\"accepted\",\"merchant\":\"$merchant\",\"amount\":$amount," +
                "\"currency\":\"EUR\",\"date\":\"$acceptedDate\",\"confidence\":0.9}"
        }

    private fun promptIds(prompt: String): List<Int> =
        candidateLine.findAll(prompt).map { it.groupValues[1].toInt() }.toList()

    private fun onDeviceAnswers(answer: (callIndex: Int, prompt: String) -> AiServiceResult<String>) {
        coEvery { onDeviceReceiptAssist.suggestFromText(any()) } coAnswers {
            val prompt = firstArg<String>()
            prompts += prompt
            answer(prompts.size - 1, prompt)
        }
    }

    @Test
    fun `ninety five candidates are validated in calls of forty forty and fifteen with global ids restored`() = runBlocking {
        onDeviceAnswers { _, prompt -> AiServiceResult.Success(acceptEverything(prompt)) }

        val outcome = useCase.validateTransactions("raw", candidates(95), "EUR")

        assertEquals(listOf(40, 40, 15), prompts.map { promptIds(it).size })
        // Each call is positional 0..n-1, exactly the contract of a single batch.
        for (prompt in prompts) assertEquals((0 until promptIds(prompt).size).toList(), promptIds(prompt))
        assertTrue(outcome is StatementValidationOutcome.Validated)
        assertEquals((0 until 95).toList(), outcome.transactions.map { it.candidateId })
        // Ownership by id: every outcome still describes ITS candidate.
        assertEquals((0 until 95).map { "M$it" }, outcome.transactions.map { it.merchant })
        assertTrue(outcome.transactions.all { it.source == StatementValidationContracts.SOURCE_AI_VALIDATED })
    }

    @Test
    fun `exactly forty candidates need one call and keep the single batch behaviour`() = runBlocking {
        onDeviceAnswers { _, prompt -> AiServiceResult.Success(acceptEverything(prompt)) }

        val outcome = useCase.validateTransactions("raw", candidates(40), "EUR")

        assertEquals(1, prompts.size)
        assertEquals(40, outcome.transactions.size)
    }

    @Test
    fun `every call keeps the raw text cap of four thousand characters`() = runBlocking {
        onDeviceAnswers { _, prompt -> AiServiceResult.Success(acceptEverything(prompt)) }

        useCase.validateTransactions("x".repeat(10_000), candidates(85), "EUR")

        assertEquals(3, prompts.size)
        for (prompt in prompts) {
            assertTrue(prompt.contains("x".repeat(4_000)))
            assertFalse(prompt.contains("x".repeat(4_001)))
            assertTrue(prompt.contains("truncated to 4000 characters"))
        }
    }

    @Test
    fun `a chunk with an identity mismatch degrades that chunk and every later chunk to the parser echo`() = runBlocking {
        onDeviceAnswers { index, prompt ->
            // The second call answers with too few entries: a contract violation.
            if (index == 1) AiServiceResult.Success("[]") else AiServiceResult.Success(acceptEverything(prompt))
        }

        val outcome = useCase.validateTransactions("raw", candidates(95), "EUR")

        // No third call: after the first unusable chunk the AI is not asked again.
        assertEquals(2, prompts.size)
        assertTrue(outcome is StatementValidationOutcome.Validated)
        assertEquals((0 until 95).toList(), outcome.transactions.map { it.candidateId })
        for (tx in outcome.transactions) {
            val expected = if (tx.candidateId >= 40) {
                StatementValidationContracts.SOURCE_PARSER_ONLY
            } else {
                StatementValidationContracts.SOURCE_AI_VALIDATED
            }
            assertEquals("candidate ${tx.candidateId}", expected, tx.source)
            assertEquals("M${tx.candidateId}", tx.merchant)
        }
    }

    @Test
    fun `a chunk whose call throws falls back for that chunk and the remaining chunks without more AI calls`() = runBlocking {
        onDeviceAnswers { index, prompt ->
            if (index == 1) AiServiceResult.Failure(AiServiceError.Unknown("unavailable"))
            else AiServiceResult.Success(acceptEverything(prompt))
        }
        // The failed on-device call reaches the privacy gate, which blows up for that chunk.
        coEvery { privacyGate.check(any<PrivacyCapability>(), any()) } throws IllegalStateException("TEST_GATE_FAILURE")

        val outcome = useCase.validateTransactions("raw", candidates(95), "EUR")

        assertTrue(outcome is StatementValidationOutcome.Validated)
        assertEquals((0 until 95).toList(), outcome.transactions.map { it.candidateId })
        val parserOnly = outcome.transactions.filter { it.source == StatementValidationContracts.SOURCE_PARSER_ONLY }
        assertEquals((40 until 95).toList(), parserOnly.map { it.candidateId })
        assertTrue(parserOnly.all { it.currency == "EUR" })
        assertEquals(2, prompts.size)
    }

    @Test
    fun `after the first unusable chunk the remaining chunks are never sent and carry the parser values`() = runBlocking {
        // 125 candidates = chunks of 40, 40, 40, 5. The very first call fails.
        onDeviceAnswers { _, _ -> AiServiceResult.Failure(AiServiceError.Unknown("unavailable")) }

        val outcome = useCase.validateTransactions("header text", candidates(125), "EUR")

        // The header text is sent once, not once per chunk.
        assertEquals(1, prompts.size)
        assertTrue(outcome is StatementValidationOutcome.ParserOnly)
        assertEquals((0 until 125).toList(), outcome.transactions.map { it.candidateId })
        for (tx in outcome.transactions) {
            assertEquals("M${tx.candidateId}", tx.merchant)
            assertEquals(1.0 + tx.candidateId, tx.amount, 0.0001)
            assertEquals(StatementValidationContracts.SOURCE_PARSER_ONLY, tx.source)
        }
    }

    @Test
    fun `cancellation in a later chunk propagates and is never swallowed`() {
        onDeviceAnswers { index, prompt ->
            if (index == 1) throw kotlinx.coroutines.CancellationException("TEST_CANCELLED")
            AiServiceResult.Success(acceptEverything(prompt))
        }
        try {
            runBlocking { useCase.validateTransactions("raw", candidates(95), "EUR") }
            fail("CancellationException must propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("TEST_CANCELLED", e.message)
        }
        assertEquals(2, prompts.size)
    }

    @Test
    fun `when no chunk is usable the result is a parser only echo with global ids`() = runBlocking {
        onDeviceAnswers { _, _ -> AiServiceResult.Failure(AiServiceError.Unknown("unavailable")) }

        val outcome = useCase.validateTransactions("raw", candidates(95), "EUR")

        assertTrue(outcome is StatementValidationOutcome.ParserOnly)
        assertEquals(
            StatementValidationContracts.REASON_AI_UNAVAILABLE,
            (outcome as StatementValidationOutcome.ParserOnly).reasonCode
        )
        assertEquals((0 until 95).toList(), outcome.transactions.map { it.candidateId })
        assertTrue(outcome.transactions.all { it.source == StatementValidationContracts.SOURCE_PARSER_ONLY })
    }

    @Test
    fun `calls per run are capped at five chunks and the rest stay unvalidated but are not dropped`() = runBlocking {
        onDeviceAnswers { _, prompt -> AiServiceResult.Success(acceptEverything(prompt)) }

        // 260 candidates = 6 full chunks of 40 plus 20; only the first 5 chunks (200 candidates) are sent.
        val outcome = useCase.validateTransactions("raw", candidates(260), "EUR")

        assertEquals(5, StatementValidationContracts.MAX_CHUNKS_PER_RUN)
        assertEquals(listOf(40, 40, 40, 40, 40), prompts.map { promptIds(it).size })
        assertTrue(outcome is StatementValidationOutcome.Validated)
        // Every candidate still has exactly one outcome, owned by its own id.
        assertEquals((0 until 260).toList(), outcome.transactions.map { it.candidateId })
        for (tx in outcome.transactions) {
            val expected = if (tx.candidateId >= 200) {
                StatementValidationContracts.SOURCE_PARSER_ONLY
            } else {
                StatementValidationContracts.SOURCE_AI_VALIDATED
            }
            assertEquals("candidate ${tx.candidateId}", expected, tx.source)
            assertEquals("M${tx.candidateId}", tx.merchant)
            assertEquals(1.0 + tx.candidateId, tx.amount, 0.0001)
            assertEquals("EUR", tx.currency)
        }
    }

    @Test
    fun `exactly two hundred candidates use five calls and none is left unvalidated`() = runBlocking {
        onDeviceAnswers { _, prompt -> AiServiceResult.Success(acceptEverything(prompt)) }

        val outcome = useCase.validateTransactions("raw", candidates(200), "EUR")

        assertEquals(5, prompts.size)
        assertTrue(outcome.transactions.all { it.source == StatementValidationContracts.SOURCE_AI_VALIDATED })
    }

    @Test
    fun `when every chunk violates the identity contract the outcome is an identity mismatch`() = runBlocking {
        onDeviceAnswers { _, _ -> AiServiceResult.Success("[]") }

        val outcome = useCase.validateTransactions("raw", candidates(95), "EUR")

        assertTrue(outcome is StatementValidationOutcome.IdentityMismatch)
        assertEquals((0 until 95).toList(), outcome.transactions.map { it.candidateId })
    }
}
