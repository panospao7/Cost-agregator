package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.data.database.entity.CategorizationStatus
import com.yourname.expensetracker.data.repository.ReceiptItemCategorizationRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.CategorizationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptItemCategorizationService
import com.yourname.expensetracker.domain.ai.util.AiArtifactSourceHash
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

/**
 * Use case for categorizing individual receipt items using AI.
 */
class CategorizeReceiptItemsUseCase @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val aiArtifactRepository: AiArtifactRepository,
    private val receiptRepository: ReceiptRepository,
    private val receiptItemCategorizationRepository: ReceiptItemCategorizationRepository,
    private val inputBuilder: ReceiptItemCategorizationInputBuilder,
    private val onDeviceService: ReceiptItemCategorizationService,
    private val cloudService: ReceiptItemCategorizationService,
    private val timeProvider: TimeProvider
) {

    suspend operator fun invoke(receiptId: Long, force: Boolean = false): CategorizationResult {
        // 1. Check AI enabled
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.receiptItemCategorizationEnabled) {
            Timber.d("Receipt item categorization disabled")
            return CategorizationResult.Disabled
        }

        // 2. Check if already analyzed (unless forced)
        if (!force) {
            val existing = receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId)
            if (existing.isNotEmpty()) {
                Timber.d("Receipt $receiptId already analyzed, returning cached results")
                return CategorizationResult.AlreadyAnalyzed(existing)
            }
        } else {
            // Clear previous results if forcing re-analysis
            receiptItemCategorizationRepository.deleteByReceiptId(receiptId)
        }

        // 3. Get receipt
        val receipt = receiptRepository.getReceiptById(receiptId)
            ?: return CategorizationResult.Error

        // 3b. Document-type gating: skip incompatible receipts
        if (receipt.documentType == ReceiptDocumentType.BANK_STATEMENT.name ||
            receipt.documentType == ReceiptDocumentType.MANUAL_PLACEHOLDER.name ||
            receipt.processingStatus == ReceiptProcessingStatus.OCR_FAILED.name) {
            Timber.d("Skipping categorization for receipt $receiptId: documentType=${receipt.documentType}, processingStatus=${receipt.processingStatus}")
            return CategorizationResult.Error
        }

        // 4. Check if there are items to categorize
        if (receipt.parsedItems.isNullOrBlank()) {
            Timber.d("Receipt $receiptId has no line items to categorize")
            return CategorizationResult.Error
        }

        // 5. Build input
        val input = inputBuilder.build(receipt, settings)
        if (input.lineItems.isEmpty()) {
            Timber.d("No line items found for receipt $receiptId")
            return CategorizationResult.Error
        }

        // 6. Route to AI
        val route = aiCapabilityRouter.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, settings)
        if (route.route == AiRoute.DISABLED) {
            Timber.d("Router disabled receipt item categorization")
            return CategorizationResult.Disabled
        }

        // 7. Update receipt status to analyzing
        receiptRepository.updateCategorizationStatus(receiptId, CategorizationStatus.ANALYZING)

        // 8. Create artifact (RUNNING)
        val targetKey = "receipt_items:$receiptId"
        val now = timeProvider.now()
        val baseEntity = AiArtifactRecord(
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = receiptId,
            targetKey = targetKey,
            capability = AiCapability.RECEIPT_ITEM_CATEGORIZATION,
            status = AiArtifactStatus.RUNNING,
            mode = when (route.route) {
                AiRoute.ON_DEVICE -> AiMode.ON_DEVICE
                AiRoute.CLOUD -> AiMode.CLOUD
                else -> AiMode.AUTO
            },
            provider = route.providerName,
            modelName = route.modelName,
            promptVersion = AppConfig.Ai.PROMPT_VERSION_RECEIPT_ITEMS,
            sourceHash = AiArtifactSourceHash.forReceiptItemCategorization(input),
            createdAt = now,
            updatedAt = now,
            expiresAt = now + AppConfig.Ai.RECEIPT_ITEMS_TTL_MS
        )
        aiArtifactRepository.upsert(baseEntity)

        // 9. Call AI service
        return try {
            val result = when (route.route) {
                AiRoute.ON_DEVICE -> onDeviceService.categorizeItems(input)
                AiRoute.CLOUD -> cloudService.categorizeItems(input)
                AiRoute.DETERMINISTIC_FALLBACK -> {
                    // Use simple keyword matching as fallback
                    createFallbackResult(input)
                }
                else -> return failCategorization(receiptId, baseEntity, "Invalid route")
            }

            if (result == null) {
                return failCategorization(receiptId, baseEntity, "Service returned null")
            }

            validateResult(input, result)?.let { reason ->
                return failCategorization(receiptId, baseEntity, reason)
            }

            // 10. Store results and check count
            val savedCount = storeResults(receiptId, result)

            // 11. Update receipt status to READY ONLY IF at least one row was inserted
            if (savedCount > 0) {
                receiptRepository.updateCategorizationStatus(receiptId, CategorizationStatus.READY)
            } else {
                Timber.w("Categorization completed for receipt $receiptId but no rows were saved — reverting to PENDING")
                receiptRepository.updateCategorizationStatus(receiptId, CategorizationStatus.PENDING)
                updateArtifactFailed(baseEntity, "No categorization rows were inserted")
                return CategorizationResult.Error
            }

            // 12. Update artifact to READY
            updateArtifactReady(baseEntity, result)

            CategorizationResult.Success(result)
        } catch (e: Exception) {
            Timber.e(e, "Error categorizing receipt items for $receiptId")
            failCategorization(receiptId, baseEntity, e.message ?: "Unknown error")
        }
    }

    private fun createFallbackResult(
        input: ReceiptItemCategorizationInput
    ): ReceiptItemCategorizationResult {
        // Simple fallback: categorize based on merchant
        val defaultCategory = input.userCategories.firstOrNull { it.name.contains("Shopping", true) }
            ?: input.userCategories.firstOrNull { it.name.contains("Food", true) }
            ?: input.userCategories.firstOrNull()

        val categorizedItems = input.lineItems.map { item ->
            com.yourname.expensetracker.domain.ai.model.CategorizedReceiptItem(
                itemDescription = item.description,
                amount = item.totalPrice,
                suggestedCategory = defaultCategory?.let {
                    com.yourname.expensetracker.domain.ai.model.CategorySuggestion(
                        categoryId = it.id,
                        categoryName = it.name,
                        confidence = 0.5f
                    )
                },
                confidence = 0.5f,
                rationale = "Default categorization (AI unavailable)",
                alternatives = emptyList(),
                needsReview = true
            )
        }

        return ReceiptItemCategorizationResult(
            items = categorizedItems,
            totalConfidence = 0.5f,
            needsReview = true,
            suggestedNewCategories = emptyList(),
            taxDistribution = emptyMap()
        )
    }

    /**
     * Stores categorization results and returns the number of rows inserted.
     * @return The count of successfully inserted rows (0 if none).
     */
    private suspend fun storeResults(
        receiptId: Long,
        result: ReceiptItemCategorizationResult
    ): Int {
        return receiptItemCategorizationRepository.saveCategorizationResult(
            receiptId = receiptId,
            result = result,
            now = timeProvider.now()
        )
    }

    private suspend fun updateArtifactReady(
        baseEntity: AiArtifactRecord,
        result: ReceiptItemCategorizationResult
    ) {
        val itemsArray = JSONArray().apply {
            result.items.forEach { item ->
                put(JSONObject().apply {
                    put("description", item.itemDescription)
                    put("amount", item.amount)
                    put("categoryName", item.suggestedCategory?.categoryName ?: "Unknown")
                    put("categoryId", item.suggestedCategory?.categoryId)
                    put("confidence", item.confidence)
                    put("rationale", item.rationale)
                    put("isNewCategorySuggestion", item.suggestedCategory?.isNewCategorySuggestion ?: false)
                    put("alternatives", JSONArray().apply {
                        item.alternatives.forEach { alt ->
                            put(JSONObject().apply {
                                put("categoryName", alt.categoryName)
                                put("categoryId", alt.categoryId)
                                put("confidence", alt.confidence)
                            })
                        }
                    })
                })
            }
        }

        val taxObject = JSONObject().apply {
            result.taxDistribution.forEach { (categoryId, taxAmount) ->
                put(categoryId.toString(), taxAmount)
            }
        }

        val payload = JSONObject().apply {
            put("items", itemsArray)
            put("suggestedNewCategories", JSONArray(result.suggestedNewCategories))
            put("taxDistribution", taxObject)
        }

        aiArtifactRepository.upsert(
            baseEntity.copy(
                status = AiArtifactStatus.READY,
                summaryText = "Categorized ${result.items.size} items",
                explanationText = buildExplanation(result),
                payloadJson = payload.toString(),
                updatedAt = timeProvider.now()
            )
        )
    }

    private fun buildExplanation(result: ReceiptItemCategorizationResult): String {
        val lines = buildList {
            add("Categorized ${result.items.size} receipt items")
            if (result.needsReview) {
                val uncertainCount = result.items.count { it.needsReview }
                add("⚠️ $uncertainCount items need review (confidence < 70%)")
            }
            if (result.suggestedNewCategories.isNotEmpty()) {
                add("💡 Suggested new categories: ${result.suggestedNewCategories.joinToString()}")
            }
            add("Average confidence: ${(result.totalConfidence * 100).toInt()}%")
        }
        return lines.joinToString("\n")
    }

    private suspend fun updateArtifactFailed(baseEntity: AiArtifactRecord, reason: String) {
        aiArtifactRepository.upsert(
            baseEntity.copy(
                status = AiArtifactStatus.FAILED,
                errorMessage = reason.take(200),
                updatedAt = timeProvider.now()
            )
        )
    }

    private fun validateResult(
        input: ReceiptItemCategorizationInput,
        result: ReceiptItemCategorizationResult
    ): String? {
        if (result.items.isEmpty()) {
            return "Service returned no categorized items"
        }

        if (result.items.size != input.lineItems.size) {
            return "Service returned invalid item count"
        }

        return null
    }

    private suspend fun failCategorization(
        receiptId: Long,
        baseEntity: AiArtifactRecord,
        reason: String
    ): CategorizationResult {
        updateArtifactFailed(baseEntity, reason)
        receiptRepository.updateCategorizationStatus(receiptId, CategorizationStatus.PENDING)
        return CategorizationResult.Error
    }
}
