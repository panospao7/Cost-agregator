package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudQueryInterpretationServiceTest {

    @Test
    fun `interpret returns unsupported safely when api key is absent`() {
        // Mock SecureKeyStorage to return empty key
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns ""
        
        val service = CloudQueryInterpretationService(mockKeyStorage)

        val result = kotlinx.coroutines.runBlocking {
            service.interpret(
                FinancialQueryInterpretationInput(
                    rawQuery = "top merchants this month",
                    currentTimeMs = 1_000L,
                    localeTag = "en-US",
                    categoryNames = listOf("Groceries"),
                    merchantNames = listOf("Lidl")
                )
            )
        }

        assertTrue(result is FinancialQueryInterpretationResult.Unsupported)
    }
}
