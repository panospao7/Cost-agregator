package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.model.DomainTransactionType
import org.junit.Assert.assertThrows
import org.junit.Test

class CategorizationAssistInputTest {

    @Test
    fun `CategorizationAssistInput rejects non-finite amount`() {
        assertThrows(IllegalArgumentException::class.java) {
            CategorizationAssistInput(
                targetType = AiTargetType.PENDING_REVIEW,
                targetId = 1L,
                merchant = "Store",
                amount = Double.NaN,
                currency = "EUR",
                transactionType = DomainTransactionType.PURCHASE,
                date = null,
                currentCategoryId = null,
                deterministicMatchType = null,
                deterministicExplanation = null,
                candidateCategories = listOf(CategoryOption(1L, "Food"))
            )
        }
    }
}
