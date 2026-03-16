package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion

interface DedupeJudgeService {
    suspend fun judge(input: DedupeJudgeInput): DedupeJudgeSuggestion?
}
