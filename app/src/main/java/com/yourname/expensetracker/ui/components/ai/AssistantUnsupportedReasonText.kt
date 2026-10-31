package com.yourname.expensetracker.ui.components.ai

import androidx.annotation.StringRes
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.ai.model.AssistantUnsupportedReason

/**
 * Single mapping from a bounded [AssistantUnsupportedReason] to its localized message.
 *
 * Used by both the result card (rendering) and the assistant view model (persisted
 * transcript text). The `when` is exhaustive on purpose: adding a code without a message
 * fails compilation. Messages never echo user, model or exception text.
 */
@StringRes
fun assistantUnsupportedReasonMessageRes(reason: AssistantUnsupportedReason): Int = when (reason) {
    AssistantUnsupportedReason.INTERPRETATION_DISABLED -> R.string.assistant_unsupported_interpretation_disabled
    AssistantUnsupportedReason.INTERPRETATION_FAILED -> R.string.assistant_unsupported_interpretation_failed
    AssistantUnsupportedReason.PROVIDER_UNAVAILABLE -> R.string.assistant_unsupported_provider_unavailable
    AssistantUnsupportedReason.PROVIDER_RESPONSE_INVALID -> R.string.assistant_unsupported_response_invalid
    AssistantUnsupportedReason.MODEL_DECLINED -> R.string.assistant_unsupported_model_declined
    AssistantUnsupportedReason.METRIC_NOT_SUPPORTED -> R.string.assistant_unsupported_metric_not_supported
    AssistantUnsupportedReason.NO_MATCHING_TRANSACTIONS -> R.string.assistant_unsupported_no_matching_transactions
}
