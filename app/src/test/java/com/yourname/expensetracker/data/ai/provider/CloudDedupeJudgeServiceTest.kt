package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDedupeJudgeServiceTest {

    @Test
    fun `judge returns a safe result shape and never crashes`() {
        val service = CloudDedupeJudgeService()

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

        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            assertNull(result)
        } else {
            assertNotNull(result)
            result!!
            assertTrue(result.verdict.name.isNotBlank())
        }
    }
}
