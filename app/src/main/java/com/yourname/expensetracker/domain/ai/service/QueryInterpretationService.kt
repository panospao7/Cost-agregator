package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult

interface QueryInterpretationService {
    suspend fun interpret(
        input: FinancialQueryInterpretationInput
    ): FinancialQueryInterpretationResult
}
