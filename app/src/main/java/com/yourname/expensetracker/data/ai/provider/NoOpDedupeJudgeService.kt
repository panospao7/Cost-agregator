package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.service.DedupeJudgeService
import javax.inject.Inject

class NoOpDedupeJudgeService @Inject constructor() : DedupeJudgeService {
    override suspend fun judge(input: DedupeJudgeInput): DedupeJudgeSuggestion? = null
}
