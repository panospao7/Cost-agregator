package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.ai.service.QueryInterpretationService
import javax.inject.Inject

class NoOpQueryInterpretationService @Inject constructor() : QueryInterpretationService {

    override suspend fun interpret(
        input: FinancialQueryInterpretationInput
    ): FinancialQueryInterpretationResult {
        return FinancialQueryInterpretationResult.Unsupported(
            "Query interpretation provider is not configured"
        )
    }
}
