package com.yourname.expensetracker.ui.components.ai

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.ai.model.ClarificationChoice
import com.yourname.expensetracker.domain.ai.model.PeriodChoice
import com.yourname.expensetracker.domain.ai.model.PresentationChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ai-fixes C4 (INV-C13): every typed clarification choice maps to its own localized label resource. */
class AssistantClarificationChoiceTextTest {

    private val allChoices: List<ClarificationChoice> =
        PeriodChoice.entries.map { ClarificationChoice.Period(it) } +
            PresentationChoice.entries.map { ClarificationChoice.Presentation(it) }

    @Test
    fun `INV-C13 every period and presentation choice maps to a non-zero resource id`() {
        assertEquals(5, allChoices.size)
        allChoices.forEach { choice ->
            assertNotEquals("no label for $choice", 0, assistantClarificationChoiceLabelRes(choice))
        }
    }

    @Test
    fun `INV-C13 label resource ids are distinct across all choices`() {
        val ids = allChoices.map { assistantClarificationChoiceLabelRes(it) }

        assertEquals("duplicate label ids: $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `INV-C13 period choices map to the period label strings`() {
        assertEquals(
            R.string.assistant_clarification_period_this_month,
            assistantClarificationChoiceLabelRes(ClarificationChoice.Period(PeriodChoice.THIS_MONTH))
        )
        assertEquals(
            R.string.assistant_clarification_period_this_week,
            assistantClarificationChoiceLabelRes(ClarificationChoice.Period(PeriodChoice.THIS_WEEK))
        )
        assertEquals(
            R.string.assistant_clarification_period_last_month,
            assistantClarificationChoiceLabelRes(ClarificationChoice.Period(PeriodChoice.LAST_MONTH))
        )
    }

    @Test
    fun `INV-C13 presentation choices map to the presentation label strings`() {
        assertEquals(
            R.string.assistant_clarification_presentation_breakdown,
            assistantClarificationChoiceLabelRes(ClarificationChoice.Presentation(PresentationChoice.BREAKDOWN))
        )
        assertEquals(
            R.string.assistant_clarification_presentation_transaction_list,
            assistantClarificationChoiceLabelRes(ClarificationChoice.Presentation(PresentationChoice.TRANSACTION_LIST))
        )
        assertTrue(
            assistantClarificationChoiceLabelRes(ClarificationChoice.Period(PeriodChoice.THIS_MONTH)) !=
                assistantClarificationChoiceLabelRes(ClarificationChoice.Presentation(PresentationChoice.BREAKDOWN))
        )
    }
}
