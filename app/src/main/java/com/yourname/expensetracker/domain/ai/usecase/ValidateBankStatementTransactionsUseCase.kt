package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.ai.provider.OnDeviceReceiptAssistService
import com.yourname.expensetracker.data.ai.provider.SmartReceiptAssistService
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.core.money.CurrencyAssumption
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents a validated/corrected transaction produced by AI validation
 * of bank statement OCR output.
 *
 * @property candidateId Immutable source-line identity assigned by the parser
 *   (RP-13 Gate A / P3-002). The AI must echo this id verbatim; results are
 *   merged back to parser rows by this id, NEVER by response position.
 * @property merchant The merchant or counterparty name (possibly AI-corrected).
 * @property amount The transaction amount (always positive).
 * @property currency ISO 4217 currency code (e.g., "EUR", "USD").
 *   Empty string ([StatementValidationContracts.CURRENCY_UNKNOWN]) when no
 *   currency could be validated from the AI response or the explicitly-known
 *   statement/source currency — never a fabricated default (RP-13 Gate B / P3-010).
 * @property date Epoch millis timestamp of the transaction.
 * @property confidence Confidence score (0.0 to 1.0).
 * @property source Origin of this transaction: "PARSER_ONLY" (deterministic),
 *                  "AI_VALIDATED" (AI confirmed the parser output),
 *                  "AI_CORRECTED" (AI corrected the parser output),
 *                  or "AI_REJECTED" (AI proved, via candidateId, that this
 *                  parser row is not a real transaction).
 */
data class CleanTransaction(
    val candidateId: Int,
    val merchant: String,
    val amount: Double,
    val currency: String,
    val date: Long,
    val confidence: Float,
    val source: String // "PARSER_ONLY", "AI_VALIDATED", "AI_CORRECTED", "AI_REJECTED"
)

/**
 * Controlled constants for the RP-13 statement-validation contract
 * (register decisions D10/D11). All values are controlled codes — never
 * OCR text, paths, exception messages, or user payloads.
 */
object StatementValidationContracts {

    /**
     * Currency sentinel for "no currency could be validated" (Gate B / P3-010).
     * Callers (the statement processor) must turn this into a typed
     * CURRENCY_UNKNOWN ledger skip — a guessed currency must never reach a
     * review or a dedupe key.
     */
    const val CURRENCY_UNKNOWN = ""

    /** The AI response violated the identity/cardinality contract (Gate A / P3-002). */
    const val REASON_AI_IDENTITY_MISMATCH = "AI_IDENTITY_MISMATCH"

    /** AI (on-device and cloud) was unavailable, blocked, or returned unusable output. */
    const val REASON_AI_UNAVAILABLE = "AI_UNAVAILABLE"

    // ── Identity mismatch detail codes (controlled) ─────────────────────────
    const val DETAIL_DUPLICATE_CANDIDATE_ID = "DUPLICATE_CANDIDATE_ID"
    const val DETAIL_MISSING_CANDIDATE_ID = "MISSING_CANDIDATE_ID"
    const val DETAIL_MALFORMED_CANDIDATE_ID = "MALFORMED_CANDIDATE_ID"
    const val DETAIL_OUT_OF_RANGE_CANDIDATE_ID = "OUT_OF_RANGE_CANDIDATE_ID"
    const val DETAIL_OMITTED_CANDIDATE_ID = "OMITTED_CANDIDATE_ID"
    const val DETAIL_EXTRA_ENTRY = "EXTRA_ENTRY"
    const val DETAIL_REORDERED_CANDIDATE_IDS = "REORDERED_CANDIDATE_IDS"
    const val DETAIL_MALFORMED_ENTRY = "MALFORMED_ENTRY"

    // ── Source markers on [CleanTransaction] ────────────────────────────────
    const val SOURCE_PARSER_ONLY = "PARSER_ONLY"
    const val SOURCE_AI_VALIDATED = "AI_VALIDATED"
    const val SOURCE_AI_CORRECTED = "AI_CORRECTED"
    const val SOURCE_AI_REJECTED = "AI_REJECTED"
}

/**
 * Typed outcome of bank-statement AI validation (RP-13 Gate A / P3-002).
 *
 * [transactions] always contains EXACTLY ONE entry per candidate, keyed by
 * [CleanTransaction.candidateId], so the caller can merge back to parser rows
 * by identity. For [ParserOnly] and [IdentityMismatch] the list is a parser
 * echo: the parser row remains the authoritative candidate.
 */
sealed interface StatementValidationOutcome {

    /** One outcome per candidate, in candidateId order. */
    val transactions: List<CleanTransaction>

    /** AI applied: one [CleanTransaction] per candidate (accepted, rejected, validated or corrected). */
    data class Validated(
        override val transactions: List<CleanTransaction>
    ) : StatementValidationOutcome

    /** AI was unavailable/blocked/unusable — parser rows are authoritative. */
    data class ParserOnly(
        val reasonCode: String,
        override val transactions: List<CleanTransaction>
    ) : StatementValidationOutcome

    /**
     * AI responded but violated the identity contract (duplicate / missing /
     * malformed / out-of-range / omitted / reordered / extra ids). The parser
     * echo is returned and the caller must fall back to parser-only for the
     * affected statement.
     */
    data class IdentityMismatch(
        val detailCode: String,
        override val transactions: List<CleanTransaction>
    ) : StatementValidationOutcome
}

/**
 * AI-powered bank statement transaction validator.
 *
 * Takes the raw OCR text and candidate transactions from the deterministic
 * [BankStatementParser] and uses on-device (or cloud) AI to:
 * 1. Mark false positives (headers, bank info, page numbers, etc.) as rejected
 *    — via the explicit per-candidate status, never by omitting candidates.
 * 2. Correct merchant names, amounts, currencies, and dates
 * 3. Return exactly one outcome per candidate, owned by [CleanTransaction.candidateId]
 *
 * ## Identity contract (RP-13 Gate A / P3-002, decision D10)
 * The prompt requires an immutable `candidateId` (generated by the parser from
 * its source-line identity) echoed by every response entry. The response must
 * contain exactly one entry per candidate, in candidate order. Duplicate,
 * missing, malformed, out-of-range, omitted, reordered, and extra ids yield a
 * structured [StatementValidationOutcome.IdentityMismatch] and the caller falls
 * back to parser-only. Positions are never used as identity.
 *
 * ## Currency policy (RP-13 Gate B / P3-010, decision D11)
 * A currency is used only when explicitly known and validated: an AI-provided
 * ISO-normalizable code, or the statement/source currency when the parser
 * marked it explicitly parsed. There is NO home-currency fallback and no
 * hard-coded default; unresolvable currency is surfaced as
 * [StatementValidationContracts.CURRENCY_UNKNOWN].
 *
 * ## Privacy
 * - Tries on-device AI first (no network, runs entirely on the phone).
 * - Cloud AI is only used as a fallback when on-device is unavailable and
 *   the user has explicitly enabled cloud AI in settings.
 * - All cloud requests pass through the [PrivacyGate] before sending data.
 *
 * IMPLEMENTED (P8-P1-07): Redaction is handled at the service layer —
 * [CloudReceiptAssistService.suggestFromText] calls
 * [CloudPayloadPolicy.prepareBankStatementValidation] which always applies
 * [CloudPayloadRedactor.redactText] before sending to cloud. The raw prompt
 * never reaches the cloud API unredacted for bank statement validation.
 */
@Singleton
class ValidateBankStatementTransactionsUseCase @Inject constructor(
    private val smartReceiptAssist: SmartReceiptAssistService,
    private val onDeviceReceiptAssist: OnDeviceReceiptAssistService,
    private val privacyGate: PrivacyGate,
    private val currencyNormalizer: CurrencyNormalizer
) {
    /**
     * Validate candidate transactions against raw OCR text using AI.
     *
     * @param rawOcrText The full OCR text from the bank statement.
     * @param candidateTransactions The transactions parsed by the deterministic parser.
     *   Each candidate must carry a unique, in-range [DebugTransaction.candidateId]
     *   (assigned by the parser/processor from its source-line identity).
     * @param homeCurrency Informational only (diagnostics). Per RP-13 Gate B it is
     *   NEVER used as a currency fallback.
     * @return A [StatementValidationOutcome] with exactly one entry per candidate.
     */
    suspend fun validateTransactions(
        rawOcrText: String,
        candidateTransactions: List<DebugTransaction>,
        homeCurrency: String
    ): StatementValidationOutcome {
        val candidates = candidateTransactions
        if (candidates.isEmpty()) {
            return StatementValidationOutcome.Validated(emptyList())
        }

        Timber.d(
            "validateTransactions: validating %d candidate transactions, OCR length=%d",
            candidates.size, rawOcrText.length
        )

        // ── Step 1: Build the AI prompt ───────────────────────────────────
        val prompt = buildValidationPrompt(rawOcrText, candidates)

        // ── Step 2: Try on-device AI first (privacy-safe, no network) ──────
        val onDeviceResponse = try {
            onDeviceReceiptAssist.suggestFromText(prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("On-device AI invocation failed (class=${e::class.simpleName})")
            null
        }

        var lastMismatchDetail: String? = null
        val onDeviceParsed = onDeviceResponse?.let { result ->
            when (result) {
                is AiServiceResult.Success -> when (val parsed = parseAiResponse(result.value, candidates)) {
                    is AiContractParseResult.Ok -> parsed
                    is AiContractParseResult.IdentityMismatch -> {
                        lastMismatchDetail = parsed.detailCode
                        Timber.w("On-device AI response violated identity contract: %s", parsed.detailCode)
                        null
                    }
                    is AiContractParseResult.InvalidResponse -> {
                        Timber.d("On-device AI response unusable: %s", parsed.errorClass)
                        null
                    }
                }
                is AiServiceResult.Failure -> {
                    Timber.d("On-device AI failed: ${result.error::class.simpleName}")
                    null
                }
            }
        }
        if (onDeviceParsed != null) {
            Timber.d(
                "AI returned a contract-valid response for %d/%d transactions (on-device)",
                onDeviceParsed.transactions.size, candidates.size
            )
            return StatementValidationOutcome.Validated(onDeviceParsed.transactions)
        }

        // ── Step 3: Check privacy gate before cloud ───────────────────────
        val gateDecision = privacyGate.check(
            capability = PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
            context = mapOf(
                "ocrLength" to rawOcrText.length.toString(),
                "candidateCount" to candidates.size.toString()
            )
        )

        val cloudResponse = if (gateDecision is PrivacyDecision.Allowed) {
            try {
                smartReceiptAssist.suggestFromText(prompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Cloud AI invocation failed (class=${e::class.simpleName})")
                null
            }
        } else {
            Timber.d("Cloud AI blocked by privacy gate: ${(gateDecision as? PrivacyDecision.Denied)?.reason}")
            null
        }

        val cloudParsed = cloudResponse?.let { result ->
            when (result) {
                is AiServiceResult.Success -> when (val parsed = parseAiResponse(result.value, candidates)) {
                    is AiContractParseResult.Ok -> parsed
                    is AiContractParseResult.IdentityMismatch -> {
                        lastMismatchDetail = parsed.detailCode
                        Timber.w("Cloud AI response violated identity contract: %s", parsed.detailCode)
                        null
                    }
                    is AiContractParseResult.InvalidResponse -> {
                        Timber.d("Cloud AI response unusable: %s", parsed.errorClass)
                        null
                    }
                }
                is AiServiceResult.Failure -> {
                    Timber.d("Cloud AI failed: ${result.error::class.simpleName}")
                    null
                }
            }
        }
        if (cloudParsed != null) {
            Timber.d(
                "AI returned a contract-valid response for %d/%d transactions (cloud)",
                cloudParsed.transactions.size, candidates.size
            )
            return StatementValidationOutcome.Validated(cloudParsed.transactions)
        }

        // ── Step 4: Typed fallback — identity mismatch wins over plain unavailability ──
        return if (lastMismatchDetail != null) {
            Timber.w(
                "AI identity mismatch (%s) — falling back to parser-only for %d candidates",
                lastMismatchDetail, candidates.size
            )
            StatementValidationOutcome.IdentityMismatch(
                detailCode = lastMismatchDetail!!,
                transactions = parserEcho(candidates)
            )
        } else {
            Timber.d(
                "AI validation unavailable — returning %d transactions as PARSER_ONLY",
                candidates.size
            )
            StatementValidationOutcome.ParserOnly(
                reasonCode = StatementValidationContracts.REASON_AI_UNAVAILABLE,
                transactions = parserEcho(candidates)
            )
        }
    }

    /**
     * Parser echo: one [CleanTransaction] per candidate with parser values and
     * the Gate B currency policy applied (explicit source currency is kept,
     * assumed/unknown currency becomes [StatementValidationContracts.CURRENCY_UNKNOWN]).
     */
    private fun parserEcho(candidates: List<DebugTransaction>): List<CleanTransaction> =
        candidates.map { tx ->
            CleanTransaction(
                candidateId = tx.candidateId,
                merchant = tx.merchant,
                amount = tx.amount,
                currency = resolveCurrency(aiCurrencyRaw = "", candidate = tx),
                date = tx.date,
                confidence = tx.confidence,
                source = StatementValidationContracts.SOURCE_PARSER_ONLY
            )
        }

    /**
     * Build the AI prompt combining raw OCR text and candidate transactions.
     *
     * Guards against null/blank [rawOcrText] to prevent sending empty prompts
     * to the AI service. Each candidate is listed with its immutable
     * `candidateId` and the strict response contract (Gate A / P3-002).
     */
    private fun buildValidationPrompt(
        rawOcrText: String,
        candidates: List<DebugTransaction>
    ): String = buildString {
        if (rawOcrText.isBlank()) {
            Timber.w("buildValidationPrompt: OCR text input is blank — AI validation will have no context")
        }
        appendLine("You are a bank statement transaction validator.")
        appendLine("Below is OCR text from a bank statement. Candidate transactions were extracted by a parser.")
        appendLine("Filter out any entries that are NOT real financial transactions (headers, bank info, page numbers, etc).")
        appendLine("For real transactions, correct the merchant name, amount, and date if the parser got them wrong.")
        appendLine()
        appendLine("RESPONSE CONTRACT (STRICT — violations are rejected):")
        appendLine("- Return a JSON array with EXACTLY ONE object per candidate listed below, in the SAME ORDER.")
        appendLine("- Every object MUST contain \"candidateId\" copied VERBATIM from the candidate as a JSON integer (never a string, never omitted).")
        appendLine("- Every object MUST contain \"status\": \"accepted\" for a real transaction, or \"rejected\" for a non-transaction (headers, bank info, etc). Never omit a candidate.")
        appendLine("- For \"accepted\" objects include merchant, amount (positive number), currency (ISO 4217 code; omit the value only if truly undeterminable), date (YYYY-MM-DD), confidence (0.0-1.0).")
        appendLine("- For \"rejected\" objects only candidateId and status are required.")
        appendLine()
        appendLine("--- OCR TEXT ---")
        if (rawOcrText.length > 4000) {
            appendLine(rawOcrText.take(4000))
            appendLine("… (OCR text truncated to 4000 characters)")
        } else {
            appendLine(rawOcrText)
        }
        appendLine()
        appendLine("--- CANDIDATE TRANSACTIONS ---")
        candidates.forEach { tx ->
            appendLine(
                "{\"candidateId\":${tx.candidateId}, \"merchant\":\"${promptSafe(tx.merchant)}\", " +
                    "\"amount\":${tx.amount}, \"currency\":\"${promptSafe(tx.currency)}\", \"date\":${tx.date}}"
            )
        }
        appendLine()
        appendLine(
            """Return example: [{"candidateId":0,"status":"accepted","merchant":"...",""" +
                """"amount":0.0,"currency":"EUR","date":"YYYY-MM-DD","confidence":0.9}]"""
        )
    }

    /** Internal parse result for one AI provider response. */
    private sealed interface AiContractParseResult {
        /** Contract satisfied: one entry per candidate, ids complete/unique/ordered. */
        data class Ok(val transactions: List<CleanTransaction>) : AiContractParseResult

        /** Usable JSON but unusable content (e.g. invalid status values). */
        data class InvalidResponse(val errorClass: String) : AiContractParseResult

        /** Identity/cardinality violation — controlled detail code only. */
        data class IdentityMismatch(val detailCode: String) : AiContractParseResult
    }

    /**
     * Parse the AI JSON response and enforce the identity contract (Gate A).
     *
     * Returns [AiContractParseResult.Ok] with exactly one [CleanTransaction] per
     * candidate (owned by candidateId), [AiContractParseResult.InvalidResponse]
     * when the JSON cannot be used at all, or
     * [AiContractParseResult.IdentityMismatch] with a controlled detail code for
     * any identity/cardinality violation. Never throws for malformed AI output
     * and never merges by position.
     */
    private fun parseAiResponse(
        jsonText: String,
        candidates: List<DebugTransaction>
    ): AiContractParseResult {
        return try {
            val text = jsonText.trim()
            // The response might be wrapped in markdown code fence
            val cleanJson = text.removeSurrounding("```json\n", "\n```")
                .removeSurrounding("```", "```")
                .trim()
            val jsonArray = try {
                JSONArray(cleanJson)
            } catch (e: JSONException) {
                // AI may wrap the array in an object like {"transactions":[...]}
                // or {"results":[...]}. Try unwrapping before giving up.
                JSONObject(cleanJson).optJSONArray("transactions")
                    ?: JSONObject(cleanJson).optJSONArray("results")
                    ?: return AiContractParseResult.InvalidResponse("MALFORMED_JSON")
            }

            // ── Identity/cardinality validation (Gate A / P3-002) ──────────
            val entryCount = jsonArray.length()
            if (entryCount > candidates.size) {
                return AiContractParseResult.IdentityMismatch(
                    StatementValidationContracts.DETAIL_EXTRA_ENTRY
                )
            }
            if (entryCount < candidates.size) {
                return AiContractParseResult.IdentityMismatch(
                    StatementValidationContracts.DETAIL_MISSING_CANDIDATE_ID
                )
            }

            val ids = IntArray(entryCount)
            for (i in 0 until entryCount) {
                val obj = jsonArray.optJSONObject(i)
                    ?: return AiContractParseResult.IdentityMismatch(
                        StatementValidationContracts.DETAIL_MALFORMED_ENTRY
                    )
                val raw = obj.opt("candidateId")
                when {
                    raw == null || raw == JSONObject.NULL ->
                        return AiContractParseResult.IdentityMismatch(
                            StatementValidationContracts.DETAIL_OMITTED_CANDIDATE_ID
                        )
                    // Strict: JSON integers only (Integer/Long). Strings like "0"
                    // and fractional numbers like 0.5 are malformed ids.
                    raw !is Int && raw !is Long ->
                        return AiContractParseResult.IdentityMismatch(
                            StatementValidationContracts.DETAIL_MALFORMED_CANDIDATE_ID
                        )
                }
                // Int-range check BEFORE toInt(): a huge Long would wrap on
                // narrowing conversion and silently alias candidate 0.
                val id = when (raw) {
                    is Int -> raw
                    is Long -> if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) {
                        return AiContractParseResult.IdentityMismatch(
                            StatementValidationContracts.DETAIL_OUT_OF_RANGE_CANDIDATE_ID
                        )
                    } else raw.toInt()
                    else -> return AiContractParseResult.IdentityMismatch(
                        StatementValidationContracts.DETAIL_MALFORMED_CANDIDATE_ID
                    )
                }
                if (id < 0 || id >= candidates.size) {
                    return AiContractParseResult.IdentityMismatch(
                        StatementValidationContracts.DETAIL_OUT_OF_RANGE_CANDIDATE_ID
                    )
                }
                for (j in 0 until i) {
                    if (ids[j] == id) {
                        return AiContractParseResult.IdentityMismatch(
                            StatementValidationContracts.DETAIL_DUPLICATE_CANDIDATE_ID
                        )
                    }
                }
                ids[i] = id
            }
            // With entryCount == candidates.size and unique in-range ids the set
            // is already complete; require the documented candidate order too.
            for (i in ids.indices) {
                if (ids[i] != i) {
                    return AiContractParseResult.IdentityMismatch(
                        StatementValidationContracts.DETAIL_REORDERED_CANDIDATE_IDS
                    )
                }
            }

            // ── Per-entry outcome construction (identity proven by ids) ────
            val results = mutableListOf<CleanTransaction>()
            for (i in 0 until entryCount) {
                val obj = jsonArray.getJSONObject(i)
                val id = ids[i]
                val candidate = candidates[id]
                val status = obj.optString("status", "").trim().lowercase()
                if (status != "accepted" && status != "rejected") {
                    return AiContractParseResult.InvalidResponse("INVALID_STATUS")
                }

                if (status == "rejected") {
                    // AI rejected this parser row; identity is proven by the
                    // validated candidateId, so the caller records a skip while
                    // the parser fields remain the authoritative description.
                    results.add(rejectedEcho(candidate))
                    continue
                }

                val merchant = obj.optString("merchant", "").trim()
                val amount = obj.optDouble("amount", 0.0)
                val dateStr = obj.optString("date", "").trim()
                val confidence = obj.optDouble("confidence", 0.0).toFloat()

                // Accepted entries must carry usable fields; entries that fail
                // field validation are treated as identity-proven rejections
                // (parser row stays authoritative, ledger records a skip).
                if (merchant.isBlank() || amount <= 0.0 || !amount.isFinite() || dateStr.isBlank()) {
                    results.add(rejectedEcho(candidate))
                    continue
                }

                val date = parseDateFromAi(dateStr)
                if (date == null) {
                    Timber.d("parseAiResponse: accepted entry has unparseable date — treated as rejected")
                    results.add(rejectedEcho(candidate))
                    continue
                }

                // Determine whether the AI actually changed values vs the candidate
                // (compared BY candidateId, never by position).
                val source = if (
                    merchant != candidate.merchant ||
                    kotlin.math.abs(amount - candidate.amount) > 0.001 ||
                    currencyChanged(obj, candidate) ||
                    kotlin.math.abs(date.toDouble() - candidate.date.toDouble()) > 1.0
                ) {
                    StatementValidationContracts.SOURCE_AI_CORRECTED
                } else {
                    StatementValidationContracts.SOURCE_AI_VALIDATED
                }

                results.add(
                    CleanTransaction(
                        candidateId = id,
                        merchant = merchant,
                        amount = amount,
                        currency = resolveCurrency(
                            aiCurrencyRaw = obj.optString("currency", "").trim(),
                            candidate = candidate
                        ),
                        date = date,
                        confidence = confidence.coerceIn(0f, 1f),
                        source = source
                    )
                )
            }

            AiContractParseResult.Ok(results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "parseAiResponse: failed to parse AI JSON response")
            AiContractParseResult.InvalidResponse("MALFORMED_JSON")
        }
    }

    /** Identity-proven rejection echo: parser fields remain authoritative. */
    private fun rejectedEcho(candidate: DebugTransaction): CleanTransaction =
        CleanTransaction(
            candidateId = candidate.candidateId,
            merchant = candidate.merchant,
            amount = candidate.amount,
            currency = resolveCurrency(aiCurrencyRaw = "", candidate = candidate),
            date = candidate.date,
            confidence = candidate.confidence,
            source = StatementValidationContracts.SOURCE_AI_REJECTED
        )

    /**
     * Keep candidate lines well-formed inside the prompt: quotes/backslashes/
     * newlines in parser-extracted text must not break the JSON-like structure.
     * Prompt formatting only — values are never persisted or logged.
     */
    private fun promptSafe(raw: String): String =
        raw.replace("\\", "/").replace("\"", "'").replace("\n", " ").replace("\r", " ")

    /**
     * Whether the AI currency differs from the candidate currency (used only
     * for the AI_CORRECTED vs AI_VALIDATED attribution).
     */
    private fun currencyChanged(obj: JSONObject, candidate: DebugTransaction): Boolean {
        val aiRaw = obj.optString("currency", "").trim()
        if (aiRaw.isEmpty()) return false
        val normalized = currencyNormalizer.normalizeOrNull(aiRaw) ?: return true
        return normalized != candidate.currency
    }

    /**
     * Gate B currency policy (P3-010, decision D11):
     * 1. An AI-provided currency is used only when it normalizes to a valid code.
     * 2. Otherwise the statement/source currency is used only when the parser
     *    marked it explicitly parsed from the source.
     * 3. Otherwise [StatementValidationContracts.CURRENCY_UNKNOWN] — there is NO
     *    home-currency fallback and no hard-coded default.
     */
    private fun resolveCurrency(aiCurrencyRaw: String, candidate: DebugTransaction): String {
        currencyNormalizer.normalizeOrNull(aiCurrencyRaw)?.let { return it }
        if (candidate.currencyAssumption == CurrencyAssumption.PARSED_FROM_SOURCE) {
            currencyNormalizer.normalizeOrNull(candidate.currency)?.let { return it }
        }
        return StatementValidationContracts.CURRENCY_UNKNOWN
    }

    /**
     * Parse a date string returned by AI (expected format: YYYY-MM-DD)
     * into epoch milliseconds.
     */
    private fun parseDateFromAi(dateStr: String): Long? {
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            LocalDate.parse(dateStr, formatter)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Lightweight debug representation of a parsed transaction.
 *
 * Carries the core transaction fields plus a [validationSource] field that
 * tracks whether the transaction came from the deterministic parser, was
 * validated by AI, or was corrected by AI.
 *
 * This is intentionally separate from [ParsedTransaction] to avoid modifying
 * that sealed/validated data class's strict init contract.
 *
 * @property merchant The merchant or counterparty name.
 * @property amount The transaction amount (always positive).
 * @property currency ISO 4217 currency code.
 * @property date Epoch millis timestamp, or 0 if unknown.
 * @property confidence Detection confidence (0.0 to 1.0).
 * @property type Transaction type label (e.g. "PURCHASE", "DEPOSIT").
 * @property validationSource Source of this transaction: "PARSER_ONLY",
 *                            "AI_VALIDATED", or "AI_CORRECTED".
 * @property candidateId Immutable parser source-line identity (RP-13 Gate A).
 *                       Assigned by the parser/processor; must be unique and
 *                       within `0 until candidateCount`.
 * @property currencyAssumption How [currency] was assigned (RP-13 Gate B):
 *                            PARSED_FROM_SOURCE when an explicit currency token
 *                            was parsed, ASSUMED_HOME_CURRENCY / UNKNOWN when it
 *                            was defaulted. Drives the no-home-fallback policy.
 */
data class DebugTransaction(
    val merchant: String,
    val amount: Double,
    val currency: String,
    val date: Long = 0L,
    val confidence: Float = 0f,
    val type: String = "UNKNOWN",
    val validationSource: String = "PARSER_ONLY",
    val candidateId: Int = -1,
    val currencyAssumption: CurrencyAssumption = CurrencyAssumption.UNKNOWN
) {
    companion object {
        /**
         * Convert a [ParsedTransaction] into a [DebugTransaction].
         *
         * @param candidateId Immutable parser source-line identity for Gate A.
         */
        fun fromParsedTransaction(
            tx: com.yourname.expensetracker.domain.parser.ParsedTransaction,
            validationSource: String = "PARSER_ONLY",
            candidateId: Int
        ): DebugTransaction {
            return DebugTransaction(
                merchant = tx.merchant,
                amount = tx.amount,
                currency = tx.currency,
                date = tx.date ?: 0L,
                confidence = tx.confidence,
                type = tx.type.name,
                validationSource = validationSource,
                candidateId = candidateId,
                currencyAssumption = tx.currencyAssumption
            )
        }
    }
}
