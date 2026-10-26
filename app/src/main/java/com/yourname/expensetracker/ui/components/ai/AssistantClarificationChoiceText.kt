package com.yourname.expensetracker.ui.components.ai

import androidx.annotation.StringRes
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.ai.model.ClarificationChoice
import com.yourname.expensetracker.domain.ai.model.PeriodChoice
import com.yourname.expensetracker.domain.ai.model.PresentationChoice

/**
 * Single mapping from a typed [ClarificationChoice] to its localized option label.
 *
 * Used by the result card and sheet (rendering) and by the assistant view model (the USER transcript
 * turn appended on a tap). The `when` expressions are exhaustive on purpose.
 */
@StringRes
fun assistantClarificationChoiceLabelRes(choice: ClarificationChoice): Int = when (choice) {
    is ClarificationChoice.Period -> when (choice.choice) {
        PeriodChoice.THIS_MONTH -> R.string.assistant_clarification_period_this_month
        PeriodChoice.THIS_WEEK -> R.string.assistant_clarification_period_this_week
        PeriodChoice.LAST_MONTH -> R.string.assistant_clarification_period_last_month
    }
    is ClarificationChoice.Presentation -> when (choice.choice) {
        PresentationChoice.BREAKDOWN -> R.string.assistant_clarification_presentation_breakdown
        PresentationChoice.TRANSACTION_LIST -> R.string.assistant_clarification_presentation_transaction_list
    }
}
