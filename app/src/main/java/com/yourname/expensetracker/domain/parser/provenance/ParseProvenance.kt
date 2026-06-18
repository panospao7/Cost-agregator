package com.yourname.expensetracker.domain.parser.provenance

enum class ParserSource {
    SPECIFIC_DETERMINISTIC,
    GENERIC_DETERMINISTIC,
    AI_FALLBACK,
    NONE
}

enum class AiFallbackStatus {
    NOT_NEEDED,
    SKIPPED_POLICY,
    SKIPPED_PRIVACY,
    UNAVAILABLE,
    ATTEMPTED_NO_RESULT,
    FAILED_EXCEPTION,
    SUCCEEDED
}

enum class ParseFailureReason {
    NO_DETERMINISTIC_MATCH,
    AI_NOT_ALLOWED_FOR_PACKAGE,
    AI_UNAVAILABLE,
    AI_EXCEPTION,
    AI_NO_RESULT,
    PARSER_EXCEPTION,
    NO_FINANCIAL_SIGNAL
}

data class ParserAttempt(
    val parserId: String,
    val parserType: ParserSource,
    val attempted: Boolean,
    val succeeded: Boolean,
    val failureReason: ParseFailureReason? = null
)

data class ParseProvenance(
    val source: ParserSource,
    val winningParserId: String?,
    val deterministicAttempted: Boolean,
    val deterministicSucceeded: Boolean,
    val aiAttempted: Boolean,
    val aiStatus: AiFallbackStatus,
    val aiProvider: String?,
    val aiModel: String?,
    val confidence: Float?,
    val failureReason: ParseFailureReason?,
    val attempts: List<ParserAttempt>
)
