package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryOption
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCategorizationAssistServiceTest {

    @Test
    fun `suggest returns a safe result shape and never crashes`() {
        val service = CloudCategorizationAssistService()

        val result = kotlinx.coroutines.runBlocking {
            service.suggest(
                CategorizationAssistInput(
                    targetType = com.yourname.expensetracker.domain.ai.model.AiTargetType.PENDING_REVIEW,
                    targetId = 1L,
                    merchant = "Lidl",
                    amount = 24.5,
                    currency = "EUR",
                    transactionType = TransactionType.PURCHASE,
                    date = null,
                    currentCategoryId = null,
                    deterministicMatchType = "FALLBACK",
                    deterministicExplanation = "weak deterministic match",
                    candidateCategories = listOf(
                        CategoryOption(1L, "Groceries"),
                        CategoryOption(2L, "Transport")
                    ),
                    supportingText = null
                )
            )
        }

        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            assertNull(result)
        } else {
            assertNotNull(result)
            result!!
            assertTrue(result.categoryId > 0)
            assertTrue(result.categoryName.isNotBlank())
        }
    }
}
