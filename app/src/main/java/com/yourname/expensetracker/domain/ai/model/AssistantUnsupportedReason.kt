package com.yourname.expensetracker.domain.ai.model

/**
 * Closed vocabulary for why an assistant query could not be answered.
 *
 * This is the only value an `Unsupported` result may carry: producers never put exception
 * text, model text, prompt text or settings text into a result. The UI layer maps each code
 * to a localized message (`AssistantUnsupportedReasonText`); the code name is also what is
 * persisted in the assistant message payload.
 */
enum class AssistantUnsupportedReason {
    /** AI, assistant or query interpretation is switched off. */
    INTERPRETATION_DISABLED,

    /** The interpretation provider threw a non-cancellation exception. */
    INTERPRETATION_FAILED,

    /** No usable provider answer (missing key, privacy gate, network, timeout, no-op provider). */
    PROVIDER_UNAVAILABLE,

    /** The provider answered with output that could not be parsed. */
    PROVIDER_RESPONSE_INVALID,

    /** The model said the query is unsupported. Any model-provided text is discarded. */
    MODEL_DECLINED,

    /** No executor exists for the requested query shape. */
    METRIC_NOT_SUPPORTED,

    /** The query matched no transactions for a single-row metric. */
    NO_MATCHING_TRANSACTIONS
}
