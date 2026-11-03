package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.domain.ai.usecase.StatementValidationContracts
import com.yourname.expensetracker.domain.common.sha256Prefix
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.CloudPayloadRedactor
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PreparedCloudPayload
import com.yourname.expensetracker.domain.privacy.SafePrivacyMetadata
import com.yourname.expensetracker.domain.util.CancellationSafe
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [CloudPayloadPolicy] implementation.
 *
 * Redaction authority comes exclusively from [EffectiveCloudAiPolicyResolver].
 * No raw string is sent unless effective policy allows it.
 * Image upload is suppressed when redaction is required.
 */
@Singleton
class DefaultCloudPayloadPolicy @Inject constructor(
    private val policyResolver: EffectiveCloudAiPolicyResolver,
    private val redactor: CloudPayloadRedactor
) : CloudPayloadPolicy {

    override suspend fun prepareText(
        purpose: CloudPayloadPurpose,
        rawText: String,
        context: SafePrivacyMetadata
    ): PreparedCloudPayload {
        val policy = policyResolver.resolve()
        val redactRequired = policy.redactBeforeCloud

        return if (redactRequired) {
            val redacted = redactor.redactText(rawText, purpose)
            PreparedCloudPayload(
                purpose = purpose,
                text = redacted.text,
                redactionApplied = true,
                fieldsRedacted = redacted.fieldsRedacted,
                payloadHash = redacted.payloadHash,
                rawTextIncluded = false,
                rawImageIncluded = false,
                auditMetadata = SafePrivacyMetadata.builder()
                    .put("purpose", purpose.name)
                    .put("redacted", true)
                    .build()
            )
        } else {
            val hash = rawText.sha256Prefix(32)
            PreparedCloudPayload(
                purpose = purpose,
                text = rawText,
                redactionApplied = false,
                fieldsRedacted = emptySet(),
                payloadHash = hash,
                rawTextIncluded = true,
                rawImageIncluded = false,
                auditMetadata = SafePrivacyMetadata.builder()
                    .put("purpose", purpose.name)
                    .put("redacted", false)
                    .build()
            )
        }
    }

    override suspend fun prepareReceiptAssist(
        rawPrompt: String,
        imagePath: String?,
        imageMimeType: String?,
        allowImage: Boolean,
        context: SafePrivacyMetadata
    ): PreparedCloudPayload {
        val policy = policyResolver.resolve()
        val redactRequired = policy.redactBeforeCloud

        val textPayload = if (redactRequired) {
            redactor.redactText(rawPrompt, CloudPayloadPurpose.RECEIPT_ASSIST)
        } else null

        val preparedText = textPayload?.text ?: rawPrompt
        val hash = preparedText.sha256Prefix(32)

        // Image is only included when: allowImage=true AND redaction is NOT required AND file exists AND MIME allowed
        val (imageBytes, resolvedMimeType) = if (allowImage && !redactRequired && imagePath != null && imageMimeType != null) {
            if (imageMimeType !in ALLOWED_RECEIPT_IMAGE_MIME_TYPES) {
                null to null  // unsupported MIME — suppress image
            } else {
                val file = java.io.File(imagePath)
                if (file.exists() && file.length() <= MAX_INLINE_IMAGE_BYTES) {
                    CancellationSafe.runCatchingCancellable { file.readBytes() }.getOrNull() to imageMimeType
                } else null to null
            }
        } else null to null

        return PreparedCloudPayload(
            purpose = CloudPayloadPurpose.RECEIPT_ASSIST,
            text = preparedText,
            redactionApplied = redactRequired,
            fieldsRedacted = textPayload?.fieldsRedacted ?: emptySet(),
            payloadHash = hash,
            rawTextIncluded = !redactRequired,
            rawImageIncluded = imageBytes != null,
            imageBytes = imageBytes,
            imageMimeType = resolvedMimeType,
            auditMetadata = SafePrivacyMetadata.builder()
                .put("purpose", CloudPayloadPurpose.RECEIPT_ASSIST.name)
                .put("redacted", redactRequired)
                .put("imageIncluded", imageBytes != null)
                .build()
        )
    }

    override suspend fun prepareBankStatementValidation(
        rawText: String,
        context: SafePrivacyMetadata
    ): PreparedCloudPayload {
        // Bank statement always uses strict redaction regardless of general policy.
        // Prompts built by ValidateBankStatementTransactionsUseCase are segmented: only the OCR
        // segment is redacted and capped (the redactor caps a whole text at 2000 chars, which
        // would cut the candidate block off); the candidate block is sanitized field by field.
        // Text without the OCR marker keeps the whole-text strict redaction.
        val segmented = segmentBankStatementPrompt(rawText)
        val preparedText: String
        val preparedFields: Set<String>
        val preparedHash: String
        if (segmented == null) {
            val redacted = redactor.redactText(rawText, CloudPayloadPurpose.BANK_STATEMENT_VALIDATION)
            preparedText = redacted.text
            preparedFields = redacted.fieldsRedacted
            preparedHash = redacted.payloadHash
        } else {
            preparedText = segmented.text
            preparedFields = segmented.fieldsRedacted
            // Same format as the redactor's payloadHash: full SHA-256, 64 lowercase hex chars.
            preparedHash = segmented.text.sha256Prefix(SHA256_HEX_LENGTH)
        }
        return PreparedCloudPayload(
            purpose = CloudPayloadPurpose.BANK_STATEMENT_VALIDATION,
            text = preparedText,
            redactionApplied = true,
            fieldsRedacted = preparedFields,
            payloadHash = preparedHash,
            rawTextIncluded = false,
            rawImageIncluded = false,
            auditMetadata = SafePrivacyMetadata.builder()
                .put("purpose", CloudPayloadPurpose.BANK_STATEMENT_VALIDATION.name)
                .put("redacted", true)
                .build()
        )
    }

    private class SegmentedPrompt(val text: String, val fieldsRedacted: Set<String>)

    /**
     * Splits a bank-statement validation prompt into instructions / OCR / candidate block.
     * Returns null when the text carries no OCR marker line (caller redacts the whole text).
     *
     * Fails closed (throws a bounded reason code, no text) when the structure is present but
     * cannot be sanitized: oversize instructions, missing candidate marker, no candidates, too
     * many candidates, or any candidate line that is not exactly the expected shape. Callers
     * treat the throw as "cloud unavailable"; nothing is sent.
     *
     * The OCR marker is the FIRST marker line (instructions are program text and precede it) and
     * the candidate marker is the LAST one, so OCR text that forges a marker stays inside the
     * redacted OCR segment. The redacted OCR segment is a single line (whitespace collapsed), so
     * it can never add a marker or candidate line.
     */
    private fun segmentBankStatementPrompt(rawText: String): SegmentedPrompt? {
        val lines = rawText.lines()
        val ocrIndex = lines.indexOf(StatementValidationContracts.PROMPT_OCR_MARKER)
        if (ocrIndex < 0) return null
        val candidateIndex = lines.lastIndexOf(StatementValidationContracts.PROMPT_CANDIDATES_MARKER)
        if (candidateIndex <= ocrIndex) throw unsanitizable()

        // The only unredacted segment must be exactly the program's instruction block; anything
        // else (e.g. user text from a future caller) fails closed.
        val headerLines = lines.subList(0, ocrIndex)
        if (headerLines.joinToString("\n", postfix = "\n") != StatementValidationContracts.PROMPT_INSTRUCTIONS) {
            throw unsanitizable()
        }

        val fields = mutableSetOf<String>()
        val ocrText = redactOcrSegment(lines.subList(ocrIndex + 1, candidateIndex), fields)

        val candidateLines = lines.subList(candidateIndex + 1, lines.size).filter { it.isNotBlank() }
        if (candidateLines.isEmpty() ||
            candidateLines.size > StatementValidationContracts.MAX_CANDIDATES_PER_AI_CALL
        ) {
            throw unsanitizable()
        }
        val sanitizedCandidates = candidateLines.map { line ->
            val match = CANDIDATE_LINE_REGEX.matchEntire(line) ?: throw unsanitizable()
            val (id, merchant, amount, currency, date) = match.destructured
            val currencyCode = if (CURRENCY_CODE_REGEX.matches(currency)) currency.uppercase() else ""
            "{\"candidateId\":$id, \"merchant\":\"${sanitizeCandidateMerchant(merchant, fields)}\", " +
                "\"amount\":$amount, \"currency\":\"$currencyCode\", \"date\":$date}"
        }

        val assembled = buildList {
            addAll(headerLines)
            add(StatementValidationContracts.PROMPT_OCR_MARKER)
            add(ocrText)
            add("")
            add(StatementValidationContracts.PROMPT_CANDIDATES_MARKER)
            addAll(sanitizedCandidates)
        }.joinToString("\n", postfix = "\n")
        return SegmentedPrompt(assembled, fields)
    }

    /**
     * Redacts the OCR segment line by line and returns ONE line (lines joined with a space,
     * capped at [MAX_BANK_OCR_CHARS] AFTER redaction). The sanitizer cuts its input at
     * [SANITIZER_INPUT_CUT] characters before redacting, so a whole-text call could leave a plain
     * fragment of an email/number straddling that cut; every line handed to it is therefore
     * shorter than the cut (see [fitLineBelowSanitizerCut]) and the cut never applies.
     * Blank lines are skipped (a blank input makes the sanitizer emit a pseudonym placeholder).
     * [fields] receives the union of the per-line redaction field names.
     */
    private fun redactOcrSegment(ocrLines: List<String>, fields: MutableSet<String>): String {
        val out = StringBuilder()
        for (rawLine in ocrLines) {
            if (out.length >= MAX_BANK_OCR_CHARS) break // output is capped; bound the work
            if (rawLine.isBlank()) continue
            val line = fitLineBelowSanitizerCut(rawLine.trim())
            if (line.isBlank()) continue
            val redacted = redactor.redactText(line, CloudPayloadPurpose.BANK_STATEMENT_VALIDATION)
            fields += redacted.fieldsRedacted
            if (out.isNotEmpty()) out.append(' ')
            out.append(redacted.text.replace(WHITESPACE_RUN_REGEX, " "))
        }
        return out.toString().take(MAX_BANK_OCR_CHARS)
    }

    /**
     * Returns [line] unchanged when it is shorter than the sanitizer cut. Otherwise drops the
     * tail: cut at the last whitespace inside a window that leaves [PII_STRADDLE_MARGIN] chars of
     * margin, then strip trailing digit/separator characters so a multi-word number (card, phone)
     * straddling the cut cannot leave a plain partial. The dropped tail is never sent. A line with
     * no whitespace in the window is one oversize token that cannot be cut safely: fail closed.
     */
    private fun fitLineBelowSanitizerCut(line: String): String {
        if (line.length < SANITIZER_INPUT_CUT) return line
        val window = line.take(SANITIZER_INPUT_CUT - PII_STRADDLE_MARGIN)
        val cut = window.indexOfLast { it.isWhitespace() }
        if (cut <= 0) throw unsanitizable()
        return window.substring(0, cut).trimEnd { it.isDigit() || it.isWhitespace() || it in NUMBER_SEPARATORS }
    }

    /** Redact first (the redactor caps the input), then bound; never cut raw text before redacting. */
    private fun sanitizeCandidateMerchant(merchant: String, fields: MutableSet<String>): String {
        if (merchant.isBlank()) return ""
        val redacted = redactor.redactText(merchant, CloudPayloadPurpose.BANK_STATEMENT_VALIDATION)
        fields += redacted.fieldsRedacted
        return redacted.text.take(MAX_BANK_CANDIDATE_MERCHANT_CHARS).replace('"', '\'').replace('\\', '/')
    }

    private fun unsanitizable() = IllegalStateException(BANK_PAYLOAD_UNSANITIZABLE_CODE)

    private companion object {
        /** Bounded reason code carried by the fail-closed throw; never any payload text. */
        private const val BANK_PAYLOAD_UNSANITIZABLE_CODE = "BANK_STATEMENT_PAYLOAD_UNSANITIZABLE"
        /** Final cap of the redacted OCR segment (matches the redactor's text cap). */
        private const val MAX_BANK_OCR_CHARS = 2000
        /** CloudPiiSanitizer.sanitizeText cuts its raw input at the caller's maxChars (2000 here). */
        private const val SANITIZER_INPUT_CUT = 2000
        /** Window kept clear below the sanitizer cut so a multi-word number cannot straddle it. */
        private const val PII_STRADDLE_MARGIN = 64
        private const val SHA256_HEX_LENGTH = 64
        private const val NUMBER_SEPARATORS = "+-().,/"
        private val WHITESPACE_RUN_REGEX = Regex("\\s+")
        private const val MAX_BANK_CANDIDATE_MERCHANT_CHARS = 80
        /**
         * Exactly the line ValidateBankStatementTransactionsUseCase writes per candidate:
         * integer id, merchant without quotes/backslashes/line breaks, a Double.toString amount,
         * currency text, epoch-millis date. Anything else fails closed.
         */
        private val CANDIDATE_LINE_REGEX = Regex(
            """\{"candidateId":(-?\d{1,9}), "merchant":"([^"\\\r\n]*)", """ +
                """"amount":(-?\d{1,7}\.\d{1,17}(?:E-?\d{1,3})?), "currency":"([^"\\\r\n]*)", """ +
                """"date":(-?\d{1,19})\}"""
        )
        private val CURRENCY_CODE_REGEX = Regex("[A-Za-z]{3}")
        private const val MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024
        private val ALLOWED_RECEIPT_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
