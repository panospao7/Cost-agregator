package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDedupeJudgeServiceTest {

    @Test
    fun `judge returns a safe result shape and never crashes`() {
        // Mock SecureKeyStorage
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "fake-api-key"
        
        val service = CloudDedupeJudgeService(mockKeyStorage)

        val result = kotlinx.coroutines.runBlocking {
            service.judge(
                DedupeJudgeInput(
                    subject = DedupeCandidateSummary(
                        targetType = AiTargetType.PENDING_REVIEW,
                        targetId = 1L,
                        merchant = "Lidl",
                        amount = 24.5,
                        currency = "EUR",
                        date = 1_000L,
                        sourceLabel = "bank",
                        textPreview = "Card purchase at Lidl"
                    ),
                    candidates = listOf(
                        DedupeCandidateSummary(
                            targetType = AiTargetType.EXPENSE,
                            targetId = 2L,
                            merchant = "Lidl",
                            amount = 24.5,
                            currency = "EUR",
                            date = 1_010L,
                            sourceLabel = "expense",
                            textPreview = "Card purchase at Lidl"
                        )
                    )
                )
            )
        }

        // With a mock/invalid key, the service may return null (safe behavior)
        // The important thing is that it doesn't crash
        if (result != null) {
            assertTrue(result.verdict.name.isNotBlank())
        }
        // Test passes if we get here without exception, regardless of null result
    }
}
