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
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests for the RP-13 statement AI-validation contract.
 *
 * ## Gate A (P3-002 / D10) — explicit stable candidateId
 * The AI response must contain EXACTLY ONE entry per candidate, in candidate
 * order, each echoing an integer `candidateId`. Duplicate, missing, malformed,
 * out-of-range, omitted, reordered, and extra ids produce a structured
 * [StatementValidationOutcome.IdentityMismatch] (`AI_IDENTITY_MISMATCH`) whose
 * transactions are a parser echo — positions are never used as identity.
 *
 * ## Gate B (P3-010 / D11) — typed currency, never fabricated
 * A currency is used only when explicitly known and validated (AI-provided ISO
 * code, or the parser's explicitly-parsed source currency). There is NO
 * home-currency fallback and no hard-coded EUR default: unresolvable currency
 * is surfaced as [StatementValidationContracts.CURRENCY_UNKNOWN].
 */
class ValidateBankStatementTransactionsUseCaseTest {

    private lateinit var smartReceiptAssist: SmartReceiptAssistService
    private lateinit var onDeviceReceiptAssist: OnDeviceReceiptAssistService
    private lateinit var privacyGate: PrivacyGate
    private lateinit var useCase: ValidateBankStatementTransactionsUseCase

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
        // Deterministic default for single-provider tests: cloud is denied.
        coEvery { privacyGate.check(any<PrivacyCapability>(), any()) } returns PrivacyDecision.Denied("test")
    }

    // ─────────────────────────── helpers ───────────────────────────

    private fun candidate(
        id: Int,
        merchant: String,
        amount: Double,
        currency: String,
        assumption: CurrencyAssumption = CurrencyAssumption.PARSED_FROM_SOURCE,
        date: Long = 0L
    ) = DebugTransaction(
        merchant = merchant,
        amount = amount,
        currency = currency,
        date = date,
        confidence = 0.8f,
        candidateId = id,
        currencyAssumption = assumption
    )

    private fun acceptedJson(
        id: Int,
        merchant: String = "Shop",
        amount: Double = 10.0,
        currency: String? = "EUR",
        date: String = "2025-03-15",
        confidence: Double = 0.9
    ): String {
        val currencyField = if (currency == null) "" else "\"currency\":\"$currency\","
        return "{\"candidateId\":$id,\"status\":\"accepted\",\"merchant\":\"$merchant\"," +
            "\"amount\":$amount,$currencyField\"date\":\"$date\",\"confidence\":$confidence}"
    }

    private fun rejectedJson(id: Int): String =
        "{\"candidateId\":$id,\"status\":\"rejected\"}"

    private fun epochOf(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun onDeviceReturns(json: String) {
        coEvery { onDeviceReceiptAssist.suggestFromText(any()) } returns AiServiceResult.Success(json)
    }

    private fun onDeviceUnavailable() {
        coEvery { onDeviceReceiptAssist.suggestFromText(any()) } returns
            AiServiceResult.Failure(AiServiceError.Unknown("on-device unavailable"))
    }

    private fun cloudUnavailable() {
        coEvery { smartReceiptAssist.suggestFromText(any()) } returns
            AiServiceResult.Failure(AiServiceError.Unknown("cloud unavailable"))
    }

    private fun cloudAllowed() {
        coEvery { privacyGate.check(PrivacyCapability.CLOUD_AI_BANK_STATEMENT, any()) } returns
            PrivacyDecision.Allowed
    }

    // ─────────────────────────── Gate A: identity contract ───────────────────────────

    @Test
    fun `omitted middle candidate returns AI_IDENTITY_MISMATCH with parser echo`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Shop A", 10.0, "EUR"),
            candidate(1, "Shop B", 20.0, "EUR"),
            candidate(2, "Shop C", 30.0, "EUR")
        )
        // Middle candidate (id 1) omitted entirely — only 2 of 3 entries.
        onDeviceReturns("[${acceptedJson(0)},${acceptedJson(2)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue("expected IdentityMismatch", outcome is StatementValidationOutcome.IdentityMismatch)
        outcome as StatementValidationOutcome.IdentityMismatch
        assertEquals(
            StatementValidationContracts.DETAIL_MISSING_CANDIDATE_ID,
            outcome.detailCode
        )
        // Parser echo: one authoritative parser row per candidate.
        assertEquals(3, outcome.transactions.size)
        assertTrue(outcome.transactions.all { it.source == StatementValidationContracts.SOURCE_PARSER_ONLY })
        assertEquals(listOf(0, 1, 2), outcome.transactions.map { it.candidateId })
    }

    @Test
    fun `reordered output returns AI_IDENTITY_MISMATCH reordered ids`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Shop A", 10.0, "EUR"),
            candidate(1, "Shop B", 20.0, "EUR")
        )
        onDeviceReturns("[${acceptedJson(1)},${acceptedJson(0)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.IdentityMismatch)
        assertEquals(
            StatementValidationContracts.DETAIL_REORDERED_CANDIDATE_IDS,
            (outcome as StatementValidationOutcome.IdentityMismatch).detailCode
        )
    }

    @Test
    fun `duplicate candidate id returns AI_IDENTITY_MISMATCH duplicate`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Shop A", 10.0, "EUR"),
            candidate(1, "Shop B", 20.0, "EUR")
        )
        onDeviceReturns("[${acceptedJson(0)},${acceptedJson(0)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.IdentityMismatch)
        assertEquals(
            StatementValidationContracts.DETAIL_DUPLICATE_CANDIDATE_ID,
            (outcome as StatementValidationOutcome.IdentityMismatch).detailCode
        )
    }

    @Test
    fun `out of range candidate id returns AI_IDENTITY_MISMATCH out of range`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Shop A", 10.0, "EUR"),
            candidate(1, "Shop B", 20.0, "EUR")
        )
        onDeviceReturns("[${acceptedJson(0)},${acceptedJson(7)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.IdentityMismatch)
        assertEquals(
            StatementValidationContracts.DETAIL_OUT_OF_RANGE_CANDIDATE_ID,
            (outcome as StatementValidationOutcome.IdentityMismatch).detailCode
        )
    }

    @Test
    fun `extra entry returns AI_IDENTITY_MISMATCH extra`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Shop A", 10.0, "EUR"),
            candidate(1, "Shop B", 20.0, "EUR")
        )
        onDeviceReturns("[${acceptedJson(0)},${acceptedJson(1)},${acceptedJson(2)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.IdentityMismatch)
        assertEquals(
            StatementValidationContracts.DETAIL_EXTRA_ENTRY,
            (outcome as StatementValidationOutcome.IdentityMismatch).detailCode
        )
    }

    @Test
    fun `malformed string candidate id returns AI_IDENTITY_MISMATCH malformed`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Shop A", 10.0, "EUR"),
            candidate(1, "Shop B", 20.0, "EUR")
        )
        // Id as JSON string — violates the "integer verbatim" requirement.
        val bad = "{\"candidateId\":\"0\",\"status\":\"accepted\",\"merchant\":\"X\",\"amount\":1.0,\"date\":\"2025-03-15\"}"
        onDeviceReturns("[$bad,${acceptedJson(1)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.IdentityMismatch)
        assertEquals(
            StatementValidationContracts.DETAIL_MALFORMED_CANDIDATE_ID,
            (outcome as StatementValidationOutcome.IdentityMismatch).detailCode
        )
    }

    @Test
    fun `non integer candidate id returns AI_IDENTITY_MISMATCH malformed`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Shop A", 10.0, "EUR"),
            candidate(1, "Shop B", 20.0, "EUR")
        )
        val bad = "{\"candidateId\":1.5,\"status\":\"accepted\",\"merchant\":\"X\",\"amount\":1.0,\"date\":\"2025-03-15\"}"
        onDeviceReturns("[${acceptedJson(0)},$bad]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.IdentityMismatch)
        assertEquals(
            StatementValidationContracts.DETAIL_MALFORMED_CANDIDATE_ID,
            (outcome as StatementValidationOutcome.IdentityMismatch).detailCode
        )
    }

    @Test
    fun `omitted candidate id field returns AI_IDENTITY_MISMATCH omitted`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Shop A", 10.0, "EUR"),
            candidate(1, "Shop B", 20.0, "EUR")
        )
        // Entry with no candidateId key at all.
        val bad = "{\"status\":\"accepted\",\"merchant\":\"X\",\"amount\":1.0,\"date\":\"2025-03-15\"}"
        onDeviceReturns("[$bad,${acceptedJson(1)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.IdentityMismatch)
        assertEquals(
            StatementValidationContracts.DETAIL_OMITTED_CANDIDATE_ID,
            (outcome as StatementValidationOutcome.IdentityMismatch).detailCode
        )
    }

    @Test
    fun `one output per candidate rejection marks rejected row and keeps parser fields`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Real Merchant", 12.5, "USD"),
            candidate(1, "PAGE HEADER NOISE", 1.0, "USD")
        )
        // Candidate 1 is explicitly rejected instead of omitted — contract kept.
        onDeviceReturns("[${acceptedJson(0, merchant = "Real Merchant", amount = 12.5, currency = "USD")},${rejectedJson(1)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.Validated)
        val txs = (outcome as StatementValidationOutcome.Validated).transactions
        assertEquals(2, txs.size)
        assertEquals(StatementValidationContracts.SOURCE_AI_CORRECTED, txs[0].source) // date 0 -> parsed date
        assertEquals(StatementValidationContracts.SOURCE_AI_REJECTED, txs[1].source)
        // Rejected row: parser fields remain the authoritative description.
        assertEquals("PAGE HEADER NOISE", txs[1].merchant)
        assertEquals(1.0, txs[1].amount, 0.001)
        assertEquals(1, txs[1].candidateId)
    }

    @Test
    fun `cloud recovery after on-device identity mismatch returns validated`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Shop A", 10.0, "EUR"),
            candidate(1, "Shop B", 20.0, "EUR")
        )
        // On-device violates the contract...
        onDeviceReturns("[${acceptedJson(0)}]")
        // ...but cloud honors it.
        cloudAllowed()
        coEvery { smartReceiptAssist.suggestFromText(any()) } returns
            AiServiceResult.Success("[${acceptedJson(0)},${acceptedJson(1)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue("cloud should recover from on-device mismatch", outcome is StatementValidationOutcome.Validated)
        coVerify { privacyGate.check(PrivacyCapability.CLOUD_AI_BANK_STATEMENT, any()) }
    }

    @Test
    fun `both providers violating contract returns identity mismatch not parser only`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(candidate(0, "Shop A", 10.0, "EUR"))
        onDeviceReturns("[${acceptedJson(0)},${acceptedJson(1)}]") // extra
        cloudAllowed()
        coEvery { smartReceiptAssist.suggestFromText(any()) } returns
            AiServiceResult.Success("[${acceptedJson(5)}]") // out of range

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.IdentityMismatch)
        outcome as StatementValidationOutcome.IdentityMismatch
        assertEquals(StatementValidationContracts.DETAIL_OUT_OF_RANGE_CANDIDATE_ID, outcome.detailCode)
        assertEquals(1, outcome.transactions.size)
        assertEquals(StatementValidationContracts.SOURCE_PARSER_ONLY, outcome.transactions[0].source)
    }

    // ─────────────────────────── Gate B: currency policy ───────────────────────────

    @Test
    fun `blank ai currency with explicit USD source keeps USD`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(candidate(0, "US Shop", 10.0, "USD"))
        // AI returns no currency value at all.
        onDeviceReturns("[${acceptedJson(0, merchant = "US Shop", amount = 10.0, currency = null)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.Validated)
        val tx = (outcome as StatementValidationOutcome.Validated).transactions.single()
        assertEquals("USD", tx.currency)
    }

    @Test
    fun `blank ai currency with assumed home source does NOT fall back to home currency`() = kotlinx.coroutines.runBlocking {
        // Parser row defaulted to the home currency (EUR) — NOT explicitly known.
        val candidates = listOf(
            candidate(0, "Greek Shop", 10.0, "EUR", assumption = CurrencyAssumption.ASSUMED_HOME_CURRENCY)
        )
        onDeviceReturns("[${acceptedJson(0, merchant = "Greek Shop", amount = 10.0, currency = null)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.Validated)
        val tx = (outcome as StatementValidationOutcome.Validated).transactions.single()
        assertEquals(
            "assumed home currency must never be used as a fallback",
            StatementValidationContracts.CURRENCY_UNKNOWN,
            tx.currency
        )
    }

    @Test
    fun `both unknown yields CURRENCY_UNKNOWN`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Shop", 10.0, "EUR", assumption = CurrencyAssumption.UNKNOWN)
        )
        onDeviceReturns("[${acceptedJson(0, merchant = "Shop", amount = 10.0, currency = null)}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.Validated)
        val tx = (outcome as StatementValidationOutcome.Validated).transactions.single()
        assertEquals(StatementValidationContracts.CURRENCY_UNKNOWN, tx.currency)
    }

    @Test
    fun `ai explicit currency is normalized and used`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(candidate(0, "Greek Shop", 10.0, "EUR", assumption = CurrencyAssumption.ASSUMED_HOME_CURRENCY))
        // AI states the actual source currency.
        onDeviceReturns("[${acceptedJson(0, merchant = "Greek Shop", amount = 10.0, currency = "usd")}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        val tx = (outcome as StatementValidationOutcome.Validated).transactions.single()
        assertEquals("USD", tx.currency)
    }

    @Test
    fun `ai invalid currency falls back to explicit source currency`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(candidate(0, "US Shop", 10.0, "USD"))
        onDeviceReturns("[${acceptedJson(0, merchant = "US Shop", amount = 10.0, currency = "EUROS")}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        val tx = (outcome as StatementValidationOutcome.Validated).transactions.single()
        assertEquals("USD", tx.currency)
    }

    @Test
    fun `ai invalid currency with assumed source yields CURRENCY_UNKNOWN`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Greek Shop", 10.0, "EUR", assumption = CurrencyAssumption.ASSUMED_HOME_CURRENCY)
        )
        onDeviceReturns("[${acceptedJson(0, merchant = "Greek Shop", amount = 10.0, currency = "EUROS")}]")

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        val tx = (outcome as StatementValidationOutcome.Validated).transactions.single()
        assertEquals(StatementValidationContracts.CURRENCY_UNKNOWN, tx.currency)
    }

    @Test
    fun `parser only echo keeps explicit source currency and blanks assumed home`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "US Shop", 10.0, "USD", assumption = CurrencyAssumption.PARSED_FROM_SOURCE),
            candidate(1, "Greek Shop", 20.0, "EUR", assumption = CurrencyAssumption.ASSUMED_HOME_CURRENCY)
        )
        onDeviceUnavailable()
        cloudAllowed()
        cloudUnavailable()

        val outcome = useCase.validateTransactions("OCR", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.ParserOnly)
        outcome as StatementValidationOutcome.ParserOnly
        assertEquals(StatementValidationContracts.REASON_AI_UNAVAILABLE, outcome.reasonCode)
        assertEquals("USD", outcome.transactions[0].currency)
        assertEquals(StatementValidationContracts.CURRENCY_UNKNOWN, outcome.transactions[1].currency)
    }

    // ─────────────────────────── preserved behavior ───────────────────────────

    @Test
    fun `empty candidates returns validated empty outcome`() = kotlinx.coroutines.runBlocking {
        val outcome = useCase.validateTransactions(
            rawOcrText = "some OCR text",
            candidateTransactions = emptyList(),
            homeCurrency = "EUR"
        )
        assertTrue(outcome is StatementValidationOutcome.Validated)
        assertTrue((outcome as StatementValidationOutcome.Validated).transactions.isEmpty())
    }

    @Test
    fun `on device success returns validated transactions with candidate ids`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Coffee Shop", 4.50, "EUR")
        )
        onDeviceReturns("[${acceptedJson(0, merchant = "Coffee Shop", amount = 4.50)}]")

        val outcome = useCase.validateTransactions("OCR text", candidates, "EUR")

        val txs = (outcome as StatementValidationOutcome.Validated).transactions
        assertEquals(1, txs.size)
        assertEquals(0, txs[0].candidateId)
        assertEquals("Coffee Shop", txs[0].merchant)
        assertEquals(4.50, txs[0].amount, 0.001)
        assertEquals(StatementValidationContracts.SOURCE_AI_CORRECTED, txs[0].source)
    }

    @Test
    fun `fields identical to candidate are attributed AI_VALIDATED`() = kotlinx.coroutines.runBlocking {
        val aiDate = epochOf("2025-03-15")
        val candidates = listOf(candidate(0, "Coffee Shop", 4.50, "EUR", date = aiDate))
        onDeviceReturns("[${acceptedJson(0, merchant = "Coffee Shop", amount = 4.50, currency = "EUR", date = "2025-03-15")}]")

        val outcome = useCase.validateTransactions("OCR text", candidates, "EUR")

        val tx = (outcome as StatementValidationOutcome.Validated).transactions.single()
        assertEquals(StatementValidationContracts.SOURCE_AI_VALIDATED, tx.source)
    }

    @Test
    fun `on device failure falls back to cloud AI`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(candidate(0, "Supermarket", 32.00, "EUR"))
        onDeviceUnavailable()
        cloudAllowed()
        coEvery { smartReceiptAssist.suggestFromText(any()) } returns
            AiServiceResult.Success("""{"transactions":[${acceptedJson(0, merchant = "Supermarket", amount = 32.00)}]}""")

        val outcome = useCase.validateTransactions("OCR text", candidates, "EUR")

        val txs = (outcome as StatementValidationOutcome.Validated).transactions
        assertEquals(1, txs.size)
        assertEquals("Supermarket", txs[0].merchant)
        coVerify { privacyGate.check(PrivacyCapability.CLOUD_AI_BANK_STATEMENT, any()) }
    }

    @Test
    fun `privacy gate denial returns parser only outcome`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(candidate(0, "E-shop", 19.99, "EUR"))
        onDeviceUnavailable()

        val outcome = useCase.validateTransactions("OCR text", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.ParserOnly)
        val txs = (outcome as StatementValidationOutcome.ParserOnly).transactions
        assertEquals(1, txs.size)
        assertEquals(StatementValidationContracts.SOURCE_PARSER_ONLY, txs[0].source)
    }

    @Test
    fun `empty json response falls back to parser only`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(candidate(0, "Shop", 10.0, "EUR"))
        onDeviceReturns("")

        val outcome = useCase.validateTransactions("OCR text", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.ParserOnly)
    }

    @Test
    fun `wrapped envelope extracts transactions`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(candidate(0, "Coffee", 5.0, "EUR"))
        onDeviceReturns("""{"transactions":[${acceptedJson(0, merchant = "Coffee", amount = 5.0, confidence = 0.95)}]}""")

        val outcome = useCase.validateTransactions("OCR text", candidates, "EUR")

        val txs = (outcome as StatementValidationOutcome.Validated).transactions
        assertEquals(1, txs.size)
        assertEquals("Coffee", txs[0].merchant)
        assertTrue(txs[0].confidence > 0.9f)
    }

    @Test
    fun `markdown fences stripped`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(candidate(0, "Market", 20.0, "EUR"))
        onDeviceReturns("```json\n[${acceptedJson(0, merchant = "Market", amount = 20.0)}]\n```")

        val outcome = useCase.validateTransactions("OCR text", candidates, "EUR")

        val txs = (outcome as StatementValidationOutcome.Validated).transactions
        assertEquals(1, txs.size)
        assertEquals("Market", txs[0].merchant)
        assertEquals(20.0, txs[0].amount, 0.001)
    }

    @Test
    fun `ai corrected merchant name uses AI_CORRECTED source`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(candidate(0, "Starbx", 4.50, "EUR"))
        onDeviceReturns("[${acceptedJson(0, merchant = "Starbucks", amount = 4.50)}]")

        val outcome = useCase.validateTransactions("OCR text", candidates, "EUR")

        val txs = (outcome as StatementValidationOutcome.Validated).transactions
        assertEquals(1, txs.size)
        assertEquals("Starbucks", txs[0].merchant)
        assertEquals(StatementValidationContracts.SOURCE_AI_CORRECTED, txs[0].source)
    }

    @Test
    fun `both ai services unavailable returns parser only outcome`() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(
            candidate(0, "Bakery", 3.50, "EUR"),
            candidate(1, "Pharmacy", 12.00, "EUR")
        )
        onDeviceUnavailable()
        cloudAllowed()
        cloudUnavailable()

        val outcome = useCase.validateTransactions("OCR text", candidates, "EUR")

        assertTrue(outcome is StatementValidationOutcome.ParserOnly)
        val txs = (outcome as StatementValidationOutcome.ParserOnly).transactions
        assertEquals(2, txs.size)
        assertTrue(txs.all { it.source == StatementValidationContracts.SOURCE_PARSER_ONLY })
        assertEquals(listOf(0, 1), txs.map { it.candidateId })
    }
}
