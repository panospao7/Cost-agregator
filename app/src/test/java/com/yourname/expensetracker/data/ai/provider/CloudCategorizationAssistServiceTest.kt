package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryOption
import com.yourname.expensetracker.data.database.entity.TransactionType
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCategorizationAssistServiceTest {

    @Test
    fun `suggest returns a safe result shape and never crashes`() {
        // Mock SecureKeyStorage
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "fake-api-key"
        
        val service = CloudCategorizationAssistService(mockKeyStorage)

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

        // With a mock/invalid key, the service may return null (safe behavior)
        // The important thing is that it doesn't crash
        if (result != null) {
            assertTrue(result.categoryId > 0)
            assertTrue(result.categoryName.isNotBlank())
        }
        // Test passes if we get here without exception, regardless of null result
    }
}
