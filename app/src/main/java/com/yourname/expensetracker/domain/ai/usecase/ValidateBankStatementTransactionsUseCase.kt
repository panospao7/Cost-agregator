package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.ai.provider.OnDeviceReceiptAssistService
import com.yourname.expensetracker.data.ai.provider.SmartReceiptAssistService
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
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
 * @property merchant The merchant or counterparty name (possibly AI-corrected).
 * @property amount The transaction amount (always positive).
 * @property currency ISO 4217 currency code (e.g., "EUR", "USD").
 * @property date Epoch millis timestamp of the transaction.
 * @property confidence Confidence score (0.0 to 1.0).
 * @property source Origin of this transaction: "PARSER_ONLY" (deterministic),
 *                  "AI_VALIDATED" (AI confirmed the parser output),
 *                  or "AI_CORRECTED" (AI corrected the parser output).
 */
data class CleanTransaction(
    val merchant: String,
    val amount: Double,
    val currency: String,
    val date: Long,
    val confidence: Float,
    val source: String // "PARSER_ONLY", "AI_VALIDATED", "AI_CORRECTED"
)

/**
 * AI-powered bank statement transaction validator.
 *
 * Takes the raw OCR text and candidate transactions from the deterministic
 * [BankStatementParser] and uses on-device (or cloud) AI to:
 * 1. Filter out false positives (headers, bank info, page numbers, etc.)
 * 2. Correct merchant names, amounts, currencies, and dates
 * 3. Return only validated real transactions with confidence scores
 *
 * ## Privacy
 * - Tries on-device AI first (no network, runs entirely on the phone).
 * - Cloud AI is only used as a fallback when on-device is unavailable and
 *   the user has explicitly enabled cloud AI in settings.
 * - All cloud requests pass through the [PrivacyGate] before sending data.
 *
 * RESOLVED (P8-P1-07): Redaction is handled at the service layer —
 * [CloudReceiptAssistService.suggestFromText] checks AiSettings.redactBeforeCloud
 * and applies [CloudPayloadRedactor.redactText] before sending to cloud.
 */
@Singleton
class ValidateBankStatementTransactionsUseCase @Inject constructor(
    private val smartReceiptAssist: SmartReceiptAssistService,
    private val onDeviceReceiptAssist: OnDeviceReceiptAssistService,
    private val privacyGate: PrivacyGate
) {
    /**
     * Validate candidate transactions against raw OCR text using AI.
     *
     * @param rawOcrText The full OCR text from the bank statement.
     * @param candidateTransactions The transactions parsed by the deterministic parser.
     * @param homeCurrency The home currency code (e.g. "EUR").
     * @return A list of [CleanTransaction] objects, possibly corrected by AI.
     */
    suspend fun validateTransactions(
        rawOcrText: String,
        candidateTransactions: List<DebugTransaction>,
        homeCurrency: String
    ): List<CleanTransaction> {
        if (candidateTransactions.isEmpty()) return emptyList()

        Timber.d(
            "validateTransactions: validating %d candidate transactions, OCR length=%d, homeCurrency=%s",
            candidateTransactions.size, rawOcrText.length, homeCurrency
        )

        // ── Step 1: Build the AI prompt ───────────────────────────────────
        val prompt = buildValidationPrompt(rawOcrText, candidateTransactions)

        // ── Step 2: Try on-device AI first (privacy-safe, no network) ──────
        val onDeviceResponse = runCatching {
            onDeviceReceiptAssist.suggestFromText(prompt)
        }.getOrNull()

        val onDeviceParsed = onDeviceResponse?.let { result ->
            when (result) {
                is AiServiceResult.Success -> parseAiResponse(result.value, candidateTransactions)
                is AiServiceResult.Failure -> {
                    Timber.d("On-device AI failed: ${result.error}")
                    null
                }
            }
        }
        if (onDeviceParsed != null) {
            Timber.d("AI validated %d/%d transactions (on-device)", onDeviceParsed.size, candidateTransactions.size)
            return onDeviceParsed
        }

        // ── Step 5: Check privacy gate before cloud ───────────────────────
        val gateDecision = privacyGate.check(
            capability = PrivacyCapability.CLOUD_AI_BANK_STATEMENT,
            context = mapOf(
                "ocrLength" to rawOcrText.length.toString(),
                "candidateCount" to candidateTransactions.size.toString()
            )
        )

        val cloudResponse = if (gateDecision is PrivacyDecision.Allowed) {
            runCatching {
                smartReceiptAssist.suggestFromText(prompt)
            }.getOrNull()
        } else {
            Timber.d("Cloud AI blocked by privacy gate: ${(gateDecision as? PrivacyDecision.Denied)?.reason}")
            null
        }

        val cloudParsed = cloudResponse?.let { result ->
            when (result) {
                is AiServiceResult.Success -> parseAiResponse(result.value, candidateTransactions)
                is AiServiceResult.Failure -> {
                    Timber.d("Cloud AI failed: ${result.error}")
                    null
                }
            }
        }
        if (cloudParsed != null) {
            Timber.d("AI validated %d/%d transactions (cloud)", cloudParsed.size, candidateTransactions.size)
            return cloudParsed
        }

        // ── Step 4: Fall back to parser-only if AI fails ──────────────────
        Timber.d(
            "AI validation unavailable — returning %d transactions as PARSER_ONLY",
            candidateTransactions.size
        )

        return candidateTransactions.map { tx ->
            CleanTransaction(
                merchant = tx.merchant,
                amount = tx.amount,
                currency = tx.currency,
                date = tx.date,
                confidence = tx.confidence,
                source = "PARSER_ONLY"
            )
        }
    }

    /**
     * Build the AI prompt combining raw OCR text and candidate transactions.
     *
     * Guards against null/blank [rawOcrText] to prevent sending empty prompts
     * to the AI service.
     */
    private fun buildValidationPrompt(
        rawOcrText: String,
        candidates: List<DebugTransaction>
    ): String = buildString {
        if (rawOcrText.isBlank()) {
            Timber.w("buildValidationPrompt: rawOcrText is blank — AI validation will have no OCR context")
        }
        appendLine("You are a bank statement transaction validator.")
        appendLine("Below is OCR text from a bank statement. Candidate transactions were extracted by a parser.")
        appendLine("Filter out any entries that are NOT real financial transactions (headers, bank info, page numbers, etc).")
        appendLine("For real transactions, correct the merchant name, amount, and date if the parser got them wrong.")
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
        candidates.forEachIndexed { i, tx ->
            appendLine("$i: merchant='${tx.merchant}', amount=${tx.amount}, currency=${tx.currency}, date=${tx.date}")
        }
        appendLine()
        appendLine("""Return a JSON array of valid transactions: [{"merchant":"...", "amount":0.0, "currency":"EUR", "date":"YYYY-MM-DD", "confidence":0.9}]""")
    }

    /**
     * Parse the AI JSON response into a list of [CleanTransaction] objects.
     * Returns null if parsing fails or no valid transactions are found.
     */
    private fun parseAiResponse(
        jsonText: String,
        candidates: List<DebugTransaction>
    ): List<CleanTransaction>? {
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
                    ?: throw e
            }
            val results = mutableListOf<CleanTransaction>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val merchant = obj.optString("merchant", "").trim()
                val amount = obj.optDouble("amount", 0.0)
                val currency = obj.optString("currency", "").trim().ifBlank { "EUR" }
                val dateStr = obj.optString("date", "").trim()
                val confidence = obj.optDouble("confidence", 0.0).toFloat()

                // Skip clearly invalid entries
                if (merchant.isBlank() || amount <= 0.0 || dateStr.isBlank()) continue
                if (!amount.isFinite()) continue

                val date = parseDateFromAi(dateStr)
                if (date == null) {
                    Timber.d("parseAiResponse: skipping entry with unparseable date '$dateStr'")
                    continue
                }

                // Determine whether the AI actually changed values vs the candidate.
                // If any core field differs, mark as AI_CORRECTED; otherwise AI_VALIDATED.
                val candidate = candidates.getOrNull(i)
                val source = if (candidate != null && (
                        merchant != candidate.merchant ||
                        kotlin.math.abs(amount - candidate.amount) > 0.001 ||
                        currency != candidate.currency ||
                        kotlin.math.abs(date.toDouble() - candidate.date.toDouble()) > 1.0
                    )
                ) {
                    "AI_CORRECTED"
                } else {
                    "AI_VALIDATED"
                }

                results.add(
                    CleanTransaction(
                        merchant = merchant,
                        amount = amount,
                        currency = currency,
                        date = date,
                        confidence = confidence.coerceIn(0f, 1f),
                        source = source
                    )
                )
            }

            if (results.isEmpty()) null else results
        } catch (e: Exception) {
            Timber.w(e, "parseAiResponse: failed to parse AI JSON response")
            null
        }
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
 */
data class DebugTransaction(
    val merchant: String,
    val amount: Double,
    val currency: String,
    val date: Long = 0L,
    val confidence: Float = 0f,
    val type: String = "UNKNOWN",
    val validationSource: String = "PARSER_ONLY"
) {
    companion object {
        /**
         * Convert a [ParsedTransaction] into a [DebugTransaction].
         */
        fun fromParsedTransaction(
            tx: com.yourname.expensetracker.domain.parser.ParsedTransaction,
            validationSource: String = "PARSER_ONLY"
        ): DebugTransaction {
            return DebugTransaction(
                merchant = tx.merchant,
                amount = tx.amount,
                currency = tx.currency,
                date = tx.date ?: 0L,
                confidence = tx.confidence,
                type = tx.type.name,
                validationSource = validationSource
            )
        }
    }
}
