package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudQueryInterpretationServiceTest {

    @Test
    fun `interpret returns unsupported safely when api key is absent`() {
        val service = CloudQueryInterpretationService("")

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
