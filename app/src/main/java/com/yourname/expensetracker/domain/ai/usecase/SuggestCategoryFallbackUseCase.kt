package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.CategorizationAssistInput
import com.yourname.expensetracker.domain.ai.model.CategoryAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.CategoryAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.CategorizationAssistService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

class SuggestCategoryFallbackUseCase @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val categorizationAssistService: CategorizationAssistService,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val inputBuilder: CategorizationAssistInputBuilder,
    private val categoryRepository: CategoryRepository,
    private val timeProvider: TimeProvider
) {
    private var uncategorizedCategoryIdsCache: Set<Long>? = null


    suspend operator fun invoke(
        item: PendingReviewWithReceipt,
        force: Boolean = false
    ): CategoryAssistGenerationResult {
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.categorizationFallbackEnabled) {
            return CategoryAssistGenerationResult.Disabled("AI category assist is disabled.")
        }

        if (!force && !needsFallback(item)) {
            return CategoryAssistGenerationResult.NotNeeded(
                "This review already has a strong deterministic category suggestion."
            )
        }

        return suggestCategory(
            input = inputBuilder.build(item, settings),
            settings = settings,
            force = force,
            targetKey = "pending_review:${item.review.id}",
            expiresInMs = AppConfig.Ai.REVIEW_CAPTURE_ASSIST_TTL_MS
        )
    }

    suspend operator fun invoke(
        receipt: ScannedReceipt,
        draftMerchant: String?,
        draftAmount: Double?,
        draftDate: Long?,
        currentCategoryId: Long?,
        force: Boolean = false
    ): CategoryAssistGenerationResult {
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.categorizationFallbackEnabled) {
            return CategoryAssistGenerationResult.Disabled("AI category assist is disabled.")
        }

        if (!force && !needsFallback(receipt, currentCategoryId)) {
            return CategoryAssistGenerationResult.NotNeeded(
                "Receipt details already look complete enough to choose a category manually."
            )
        }

        return suggestCategory(
            input = inputBuilder.build(
                receipt = receipt,
                draftMerchant = draftMerchant,
                draftAmount = draftAmount,
                draftDate = draftDate,
                currentCategoryId = currentCategoryId,
                settings = settings
            ),
            settings = settings,
            force = force,
            targetKey = "scanned_receipt:${receipt.id}",
            expiresInMs = AppConfig.Ai.RECEIPT_ASSIST_TTL_MS
        )
    }

    private suspend fun needsFallback(item: PendingReviewWithReceipt): Boolean {
        val review = item.review
        if (review.suggestedCategoryId == null) return true
        if (isUncategorizedCategory(review.suggestedCategoryId)) return true
        val weakMatchTypes = setOf("UNKNOWN", "FALLBACK", "ML_PREDICTION")
        val isWeakMatch = review.matchType?.uppercase() in weakMatchTypes
        return isWeakMatch || review.confidence < AppConfig.Ai.MIN_CATEGORY_CONFIDENCE_FOR_AI_FALLBACK
    }

    private suspend fun needsFallback(
        receipt: ScannedReceipt,
        currentCategoryId: Long?
    ): Boolean {
        if (currentCategoryId == null) return true
        if (isUncategorizedCategory(currentCategoryId)) return true
        return receipt.confidence < AppConfig.Ai.MIN_CATEGORY_CONFIDENCE_FOR_AI_FALLBACK
    }

    private suspend fun isUncategorizedCategory(categoryId: Long?): Boolean {
        if (categoryId == null) return false
        val cached = uncategorizedCategoryIdsCache ?: categoryRepository.getAll()
            .filter { category ->
                category.name.equals("Uncategorized", ignoreCase = true) ||
                    category.name.contains("Uncategorized", ignoreCase = true)
            }
            .map { it.id }
            .toSet()
            .also { uncategorizedCategoryIdsCache = it }
        return categoryId in cached
    }

    private suspend fun suggestCategory(
        input: CategorizationAssistInput,
        settings: AiSettings,
        force: Boolean,
        targetKey: String,
        expiresInMs: Long
    ): CategoryAssistGenerationResult {
        val routeDecision = aiCapabilityRouter.decide(AiCapability.CATEGORIZATION_FALLBACK, settings)
        if (routeDecision.route == AiRoute.DISABLED) {
            return CategoryAssistGenerationResult.Disabled(routeDecision.reason)
        }

        val now = timeProvider.now()
        val sourceHash = input.hashCode().toString()
        val existing = aiArtifactRepository.getLatest(targetKey, AiCapability.CATEGORIZATION_FALLBACK)
        if (!force &&
            existing != null &&
            existing.status == AiArtifactStatus.READY &&
            existing.promptVersion == AppConfig.Ai.PROMPT_VERSION_CATEGORIZATION &&
            existing.sourceHash == sourceHash &&
            existing.expiresAt != null &&
            existing.expiresAt > now
        ) {
            existing.payloadJson
                ?.toCategoryAssistSuggestionOrNull()
                ?.let { return CategoryAssistGenerationResult.Success(it, fromCache = true) }
        }

        val baseEntity = AiArtifactRecord(
            targetType = input.targetType,
            targetId = input.targetId,
            targetKey = targetKey,
            capability = AiCapability.CATEGORIZATION_FALLBACK,
            status = AiArtifactStatus.RUNNING,
            mode = when (routeDecision.route) {
                AiRoute.ON_DEVICE -> AiMode.ON_DEVICE
                AiRoute.CLOUD -> AiMode.CLOUD
                AiRoute.DETERMINISTIC_FALLBACK,
                AiRoute.DISABLED -> AiMode.AUTO
            },
            provider = routeDecision.providerName,
            modelName = routeDecision.modelName,
            promptVersion = AppConfig.Ai.PROMPT_VERSION_CATEGORIZATION,
            sourceHash = sourceHash,
            createdAt = now,
            updatedAt = now,
            expiresAt = now + expiresInMs
        )
        aiArtifactRepository.upsert(baseEntity)

        return try {
            val suggestion = categorizationAssistService.suggest(input)
            val validated = suggestion?.let { validateCategorySuggestion(it) }
            if (validated == null) {
                aiArtifactRepository.upsert(
                    baseEntity.copy(
                        status = AiArtifactStatus.FAILED,
                        errorMessage = failureMessage(routeDecision.reason, routeDecision),
                        updatedAt = timeProvider.now()
                    )
                )
                CategoryAssistGenerationResult.Error(
                    "AI category assist returned no supported category."
                )
            } else {
                aiArtifactRepository.upsert(
                    baseEntity.copy(
                        status = AiArtifactStatus.READY,
                        summaryText = "AI suggested ${validated.categoryName}",
                        explanationText = validated.rationale
                            ?.take(AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS)
                            .withRouteDiagnostics(routeDecision),
                        payloadJson = validated.toPayloadJson(),
                        updatedAt = timeProvider.now()
                    )
                )
                CategoryAssistGenerationResult.Success(validated, fromCache = false)
            }
        } catch (e: Exception) {
            aiArtifactRepository.upsert(
                baseEntity.copy(
                    status = AiArtifactStatus.FAILED,
                    errorMessage = failureMessage(e.message, routeDecision),
                    updatedAt = timeProvider.now()
                )
            )
            CategoryAssistGenerationResult.Error(
                e.message?.take(200) ?: "AI category assist failed."
            )
        }
    }

    private suspend fun validateCategorySuggestion(
        suggestion: CategoryAssistSuggestion
    ): CategoryAssistSuggestion? {
        val categories = categoryRepository.getAll()
        val selected = categories.firstOrNull { it.id == suggestion.categoryId }
            ?: categories.firstOrNull { it.name.equals(suggestion.categoryName, ignoreCase = true) }
            ?: return null

        val supportedAlternatives = suggestion.alternativeCategoryIds
            .filter { altId -> categories.any { it.id == altId } }
            .distinct()

        return suggestion.copy(
            categoryId = selected.id,
            categoryName = selected.name,
            alternativeCategoryIds = supportedAlternatives
        )
    }
}

private fun CategoryAssistSuggestion.toPayloadJson(): String {
    return JSONObject().apply {
        put("categoryId", categoryId)
        put("categoryName", categoryName)
        confidence?.let { put("confidence", it) }
        rationale?.let { put("rationale", it) }
        put("alternativeCategoryIds", JSONArray(alternativeCategoryIds))
    }.toString()
}

private fun String.toCategoryAssistSuggestionOrNull(): CategoryAssistSuggestion? {
    return runCatching {
        val root = JSONObject(this)
        CategoryAssistSuggestion(
            categoryId = root.optLong("categoryId"),
            categoryName = root.optString("categoryName"),
            confidence = if (root.has("confidence") && !root.isNull("confidence")) root.optDouble("confidence").toFloat() else null,
            rationale = root.optString("rationale").takeIf { it.isNotBlank() },
            alternativeCategoryIds = root.optJSONArray("alternativeCategoryIds").toLongList()
        )
    }.getOrNull()
}

private fun JSONArray?.toLongList(): List<Long> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            add(optLong(index))
        }
    }
}

private fun String?.withRouteDiagnostics(routeDecision: AiRouteDecision): String {
    val diagnostic = routeDecision.toRouteDiagnosticLine()
    val combined = listOfNotNull(this?.takeIf { it.isNotBlank() }, diagnostic).joinToString("\n")
    return combined.take(AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS)
}

private fun failureMessage(message: String?, routeDecision: AiRouteDecision): String {
    val base = message?.takeIf { it.isNotBlank() } ?: "AI generation failed."
    return "$base ${routeDecision.toRouteDiagnosticLine()}".take(200)
}

private fun AiRouteDecision.toRouteDiagnosticLine(): String {
    val providerPart = providerName ?: "none"
    val modelPart = modelName ?: "none"
    return "Route: ${route.name}, provider: $providerPart, model: $modelPart. Reason: $reason"
}
