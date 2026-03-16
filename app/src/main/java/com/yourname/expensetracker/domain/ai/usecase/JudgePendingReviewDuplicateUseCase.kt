package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeBuildResult
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeGenerationResult
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeSuggestion
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.DedupeJudgeService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject

class JudgePendingReviewDuplicateUseCase @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val dedupeJudgeService: DedupeJudgeService,
    private val inputBuilder: DedupeJudgeInputBuilder,
    private val timeProvider: TimeProvider
) {

    suspend operator fun invoke(
        item: PendingReviewWithReceipt,
        force: Boolean = false
    ): DedupeJudgeGenerationResult {
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.dedupeJudgeEnabled) {
            return DedupeJudgeGenerationResult.Disabled("AI duplicate assist is disabled.")
        }

        val buildResult = inputBuilder.build(item)
        if (buildResult is DedupeJudgeBuildResult.NotNeeded) {
            return DedupeJudgeGenerationResult.NotNeeded(buildResult.reason)
        }

        val input = (buildResult as DedupeJudgeBuildResult.Ready).input
        val targetKey = "pending_review:${item.review.id}"
        val now = timeProvider.now()
        val sourceHash = input.hashCode().toString()

        val existing = aiArtifactRepository.getLatest(targetKey, AiCapability.DEDUPE_JUDGE)
        if (!force &&
            existing != null &&
            existing.status == AiArtifactStatus.READY &&
            existing.promptVersion == AppConfig.Ai.PROMPT_VERSION_DEDUPE &&
            existing.sourceHash == sourceHash &&
            existing.expiresAt != null &&
            existing.expiresAt > now
        ) {
            existing.payloadJson
                ?.toDedupeJudgeSuggestionOrNull()
                ?.let { return DedupeJudgeGenerationResult.Success(it, fromCache = true) }
        }

        val baseEntity = AiArtifactEntity(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = item.review.id,
            targetKey = targetKey,
            capability = AiCapability.DEDUPE_JUDGE,
            status = AiArtifactStatus.RUNNING,
            mode = AiMode.AUTO,
            promptVersion = AppConfig.Ai.PROMPT_VERSION_DEDUPE,
            sourceHash = sourceHash,
            createdAt = now,
            updatedAt = now,
            expiresAt = now + AppConfig.Ai.REVIEW_CAPTURE_ASSIST_TTL_MS
        )
        aiArtifactRepository.upsert(baseEntity)

        return try {
            val suggestion = dedupeJudgeService.judge(input)
            if (suggestion == null) {
                aiArtifactRepository.upsert(
                    baseEntity.copy(
                        status = AiArtifactStatus.FAILED,
                        errorMessage = "AI duplicate assist returned no verdict.",
                        updatedAt = timeProvider.now()
                    )
                )
                DedupeJudgeGenerationResult.Error("AI duplicate assist returned no verdict.")
            } else {
                aiArtifactRepository.upsert(
                    baseEntity.copy(
                        status = AiArtifactStatus.READY,
                        summaryText = "AI duplicate verdict: ${suggestion.verdict.name}",
                        explanationText = suggestion.rationale?.take(AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS),
                        payloadJson = suggestion.toPayloadJson(),
                        updatedAt = timeProvider.now()
                    )
                )
                DedupeJudgeGenerationResult.Success(suggestion, fromCache = false)
            }
        } catch (e: Exception) {
            aiArtifactRepository.upsert(
                baseEntity.copy(
                    status = AiArtifactStatus.FAILED,
                    errorMessage = e.message?.take(200),
                    updatedAt = timeProvider.now()
                )
            )
            DedupeJudgeGenerationResult.Error(
                e.message?.take(200) ?: "AI duplicate assist failed."
            )
        }
    }
}

private fun DedupeJudgeSuggestion.toPayloadJson(): String {
    return JSONObject().apply {
        put("verdict", verdict.name)
        matchedTargetType?.let { put("matchedTargetType", it.name) }
        matchedTargetId?.let { put("matchedTargetId", it) }
        confidence?.let { put("confidence", it) }
        rationale?.let { put("rationale", it) }
    }.toString()
}

private fun String.toDedupeJudgeSuggestionOrNull(): DedupeJudgeSuggestion? {
    return runCatching {
        val root = JSONObject(this)
        DedupeJudgeSuggestion(
            verdict = com.yourname.expensetracker.domain.ai.model.DuplicateVerdict.valueOf(root.getString("verdict")),
            matchedTargetType = root.optString("matchedTargetType")
                .takeIf { it.isNotBlank() }
                ?.let { AiTargetType.valueOf(it) },
            matchedTargetId = if (root.has("matchedTargetId") && !root.isNull("matchedTargetId")) root.optLong("matchedTargetId") else null,
            confidence = if (root.has("confidence") && !root.isNull("confidence")) root.optDouble("confidence").toFloat() else null,
            rationale = root.optString("rationale").takeIf { it.isNotBlank() }
        )
    }.getOrNull()
}
