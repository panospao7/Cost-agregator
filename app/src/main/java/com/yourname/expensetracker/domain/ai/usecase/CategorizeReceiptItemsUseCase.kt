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
import com.yourname.expensetracker.domain.receipt.ReceiptParser
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

    /**
     * Categorizes the items of [receiptId].
     *
     * RP-12 12b (P3-007) conditional remainder: [ephemeralItems] is the
     * minimum in-memory projection of the freshly parsed line items, carried
     * ONLY for a fresh insert under a restricted storage mode. When present
     * it takes precedence as the item source; when it is absent (process
     * restart, or a mode where the persisted representation is permitted)
     * the allowed persisted representation (the receipt's parsedItems —
     * full parser JSON under STORE_RAW, typed redacted projection under
     * STORE_REDACTED) is used exactly as before. If neither source is
     * available the call returns [CategorizationResult.Error] (the planner
     * records the controlled STRUCTURED_RECEIPT_DATA_UNAVAILABLE skip
     * upstream). The ephemeral items are never persisted or logged by this
     * use case.
     *
     * RP-12 12b (P3-007) OUTPUT gate: [persistItemTextAllowed] = false (the
     * planner passes it for STORE_REDACTED) gates every PERSISTED projection
     * of the result. The [com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization]
     * entity declares a NOT-NULL itemDescription, so no name-free row
     * projection exists — restricted modes therefore persist NO categorization
     * rows (schema-forced path) and keep the receipt at PENDING, and the
     * artifact payload/summary carry only category ids/names, confidences and
     * counts: never item descriptions, amounts, rationales (the on-device
     * rationale embeds the raw description verbatim), or suggested new
     * category names (derivable from item text and not approved categories).
     * The in-session [CategorizationResult.Success] value is unaffected.
     * Defaults to true so every pre-existing caller keeps byte-identical
     * persistence behavior (STORE_RAW full output). The artifact sourceHash
     * stays a one-way SHA-256 digest in every mode.
     */
    suspend operator fun invoke(
        receiptId: Long,
        force: Boolean = false,
        ephemeralItems: List<ReceiptParser.LineItem>? = null,
        persistItemTextAllowed: Boolean = true
    ): CategorizationResult {
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

        // 4. Check if there are items to categorize.
        // RP-12 12b (P3-007): permitted ephemeral items for a fresh insert
        // take precedence; otherwise fall back to the allowed persisted
        // representation exactly as before.
        val hasEphemeralItems = !ephemeralItems.isNullOrEmpty()
        if (!hasEphemeralItems && receipt.parsedItems.isNullOrBlank()) {
            Timber.d("Receipt $receiptId has no line items to categorize")
            return CategorizationResult.Error
        }

        // 5. Build input — the ephemeral projection (when present) goes through
        // the SAME sanitization/redaction pipeline as persisted items; it is
        // never logged and never persisted by the builder.
        val input = inputBuilder.build(receipt, settings, ephemeralItems)
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

            // 10. Store results and check count.
            // RP-12 12b (P3-007) OUTPUT gate: under a restricted storage mode
            // the persisted output must not contain item-name-derived content.
            // ReceiptItemCategorization.itemDescription is NOT NULL in the Room
            // schema, so no name-free row projection exists — restricted modes
            // persist NO categorization rows (the schema-forced path). The
            // in-session result below is unaffected.
            val savedCount = if (persistItemTextAllowed) storeResults(receiptId, result) else 0

            // 11. Receipt status: READY only when rows were actually persisted.
            if (persistItemTextAllowed) {
                if (savedCount > 0) {
                    receiptRepository.updateCategorizationStatus(receiptId, CategorizationStatus.READY)
                } else {
                    Timber.w("Categorization completed for receipt $receiptId but no rows were saved — reverting to PENDING")
                    receiptRepository.updateCategorizationStatus(receiptId, CategorizationStatus.PENDING)
                    updateArtifactFailed(baseEntity, "No categorization rows were inserted")
                    return CategorizationResult.Error
                }
            } else {
                // Nothing was persisted — keep the truthful PENDING status.
                receiptRepository.updateCategorizationStatus(receiptId, CategorizationStatus.PENDING)
            }

            // 12. Update artifact to READY (payload gated by the storage mode)
            updateArtifactReady(baseEntity, result, persistItemTextAllowed)

            CategorizationResult.Success(result)
        } catch (e: Exception) {
            Timber.e(e, "Error categorizing receipt items for $receiptId")
            // RP-12 12b (P3-007) OUTPUT gate: restricted modes never persist
            // uncontrolled exception text (framework messages can embed
            // item-derived input); the exception class name is the permitted
            // bounded diagnostic. validateResult reasons are controlled
            // constants and are persisted in both cases.
            failCategorization(
                receiptId,
                baseEntity,
                if (persistItemTextAllowed) (e.message ?: "Unknown error")
                else e::class.java.simpleName
            )
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
        result: ReceiptItemCategorizationResult,
        persistItemTextAllowed: Boolean
    ) {
        val itemsArray = JSONArray().apply {
            result.items.forEach { item ->
                put(JSONObject().apply {
                    // RP-12 12b (P3-007) OUTPUT gate: restricted modes persist
                    // NO item-name-derived content — no description (echoed
                    // item name), no amount, no rationale (the on-device
                    // rationale embeds the raw description verbatim). Category
                    // ids/names, confidences and counts are the permitted
                    // projection under STORE_REDACTED.
                    if (persistItemTextAllowed) {
                        put("description", item.itemDescription)
                        put("amount", item.amount)
                    }
                    put("categoryName", item.suggestedCategory?.categoryName ?: "Unknown")
                    put("categoryId", item.suggestedCategory?.categoryId)
                    put("confidence", item.confidence)
                    if (persistItemTextAllowed) {
                        put("rationale", item.rationale)
                    }
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

        val payload = JSONObject().apply {
            put("items", itemsArray)
            if (persistItemTextAllowed) {
                // Amounts (tax distribution) and AI-suggested new category
                // names (derivable from item text, not approved categories)
                // are raw-only as well.
                val taxObject = JSONObject().apply {
                    result.taxDistribution.forEach { (categoryId, taxAmount) ->
                        put(categoryId.toString(), taxAmount)
                    }
                }
                put("suggestedNewCategories", JSONArray(result.suggestedNewCategories))
                put("taxDistribution", taxObject)
            }
        }

        aiArtifactRepository.upsert(
            baseEntity.copy(
                status = AiArtifactStatus.READY,
                summaryText = if (persistItemTextAllowed) {
                    "Categorized ${result.items.size} items"
                } else {
                    // Controlled constants + counts only — never item content.
                    "Categorized ${result.items.size} items (not persisted for storage mode)"
                },
                explanationText = buildExplanation(result, persistItemTextAllowed),
                payloadJson = payload.toString(),
                updatedAt = timeProvider.now()
            )
        )
    }

    private fun buildExplanation(
        result: ReceiptItemCategorizationResult,
        persistItemTextAllowed: Boolean
    ): String {
        val lines = buildList {
            add("Categorized ${result.items.size} receipt items")
            if (result.needsReview) {
                val uncertainCount = result.items.count { it.needsReview }
                add("⚠️ $uncertainCount items need review (confidence < 70%)")
            }
            if (persistItemTextAllowed && result.suggestedNewCategories.isNotEmpty()) {
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

    /**
     * RCP-13: Per-item validation beyond simple count checks.
     * Validates each categorized item for:
     * - Reasonable confidence range (0.0..1.0)
     * - Positive amount
     * - Non-empty description
     * - Presence of a suggested category (or explicit null if none)
     */
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

        // RCP-13: Per-item validation checks
        for ((index, item) in result.items.withIndex()) {
            // Confidence must be in valid range
            if (item.confidence < 0f || item.confidence > 1f || item.confidence.isNaN()) {
                return "Item $index has invalid confidence: ${item.confidence}"
            }

            // Amount must be positive and finite
            if (item.amount <= 0 || !item.amount.isFinite()) {
                return "Item $index has invalid amount: ${item.amount}"
            }

            // Description must be non-blank
            if (item.itemDescription.isBlank()) {
                return "Item $index has blank description"
            }

            // If a suggested category is provided, its confidence must be valid
            item.suggestedCategory?.let { cat ->
                if (cat.confidence < 0f || cat.confidence > 1f || cat.confidence.isNaN()) {
                    return "Item $index category '${cat.categoryName}' has invalid confidence: ${cat.confidence}"
                }
                if (cat.categoryName.isBlank()) {
                    return "Item $index has blank category name"
                }
            }

            // Alternative categories must also have valid confidence
            for ((altIndex, alt) in item.alternatives.withIndex()) {
                if (alt.confidence < 0f || alt.confidence > 1f || alt.confidence.isNaN()) {
                    return "Item $index alternative $altIndex has invalid confidence: ${alt.confidence}"
                }
            }
        }

        // Total confidence must be in valid range
        if (result.totalConfidence < 0f || result.totalConfidence > 1f || result.totalConfidence.isNaN()) {
            return "Total confidence is invalid: ${result.totalConfidence}"
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
