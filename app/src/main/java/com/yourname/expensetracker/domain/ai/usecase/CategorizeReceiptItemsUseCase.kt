package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.dto.ReceiptItemCategorizationSnapshot
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
import com.yourname.expensetracker.domain.ai.model.ReceiptItemPlaceholder
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
     *
     * N-05-3 step 2: [retryUncategorized] (only the user's Analyze tap passes true) completes a
     * PARTIAL result. When stored rows exist and some are still unsent placeholders that the
     * user has not categorised, only those items are sent (at most one service call, no tax
     * figure because the tax would be attributed to a subset) and only those rows are filled,
     * by the repository in one transaction that skips any row that changed meanwhile. READY is
     * set only when no such placeholder remains. A failed retry changes nothing: stored rows,
     * the artifact and the receipt status stay as they were. With the default false, stored
     * rows always return [CategorizationResult.AlreadyAnalyzed] as before.
     */
    suspend operator fun invoke(
        receiptId: Long,
        force: Boolean = false,
        ephemeralItems: List<ReceiptParser.LineItem>? = null,
        persistItemTextAllowed: Boolean = true,
        retryUncategorized: Boolean = false
    ): CategorizationResult {
        // 1. Check AI enabled
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.receiptItemCategorizationEnabled) {
            Timber.d("Receipt item categorization disabled")
            return CategorizationResult.Disabled
        }

        // 2. Check if already analyzed (unless forced)
        var retryRows: List<ReceiptItemCategorizationSnapshot>? = null
        if (!force) {
            val existing = receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId)
            if (existing.isNotEmpty()) {
                val canRetry = retryUncategorized && persistItemTextAllowed &&
                    existing.any { ReceiptItemPlaceholder.isRetryable(it) }
                if (!canRetry) {
                    Timber.d("Receipt $receiptId already analyzed, returning cached results")
                    return CategorizationResult.AlreadyAnalyzed(existing)
                }
                retryRows = existing
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

        // 5b. Retry only: the stored rows must line up one-to-one with the input items
        // (same count, same amounts), else nothing is sent and the stored rows are returned.
        val retryStored: List<ReceiptItemCategorizationSnapshot>? = retryRows
        val isRetry = retryStored != null
        val retryIndices: List<Int> = if (retryStored != null) {
            retryIndicesOrNull(retryStored, input)
                ?: return CategorizationResult.AlreadyAnalyzed(retryStored)
        } else {
            emptyList()
        }
        // A retry sends the not-yet-categorised subset only, and never a tax figure.
        val serviceInput = if (isRetry) {
            input.copy(lineItems = retryIndices.map { input.lineItems[it] }, totalTax = null)
        } else {
            input
        }

        // 6. Route to AI
        val route = aiCapabilityRouter.decide(AiCapability.RECEIPT_ITEM_CATEGORIZATION, settings)
        if (route.route == AiRoute.DISABLED) {
            Timber.d("Router disabled receipt item categorization")
            return CategorizationResult.Disabled
        }
        // A retry must never be answered by the keyword fallback: its default categories would
        // fill the placeholder rows and end the retry for good. Nothing is written or changed.
        if (isRetry && route.route != AiRoute.CLOUD && route.route != AiRoute.ON_DEVICE) {
            Timber.d("Receipt item retry for %d skipped: route=%s", receiptId, route.route.name)
            return CategorizationResult.Error
        }

        // 7. Update receipt status to analyzing (not on a retry: the stored status stays truthful)
        if (!isRetry) {
            receiptRepository.updateCategorizationStatus(receiptId, CategorizationStatus.ANALYZING)
        }

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
        // A retry never replaces the stored artifact with a RUNNING one: a failed retry must
        // leave the earlier artifact as it was.
        if (!isRetry) {
            aiArtifactRepository.upsert(baseEntity)
        }

        // 9. Call AI service
        return try {
            val result = when (route.route) {
                AiRoute.ON_DEVICE -> onDeviceService.categorizeItems(serviceInput)
                AiRoute.CLOUD -> cloudService.categorizeItems(serviceInput)
                AiRoute.DETERMINISTIC_FALLBACK -> {
                    // Use simple keyword matching as fallback
                    createFallbackResult(serviceInput)
                }
                else -> return failCategorization(receiptId, baseEntity, "Invalid route", isRetry)
            }

            if (result == null) {
                return failCategorization(receiptId, baseEntity, "Service returned null", isRetry)
            }

            validateResult(serviceInput, result)?.let { reason ->
                return failCategorization(receiptId, baseEntity, reason, isRetry)
            }

            if (retryStored != null) {
                return completeRetry(receiptId, baseEntity, retryStored, retryIndices, result)
            }

            // 10. Store results and check count.
            // RP-12 12b (P3-007) OUTPUT gate: under a restricted storage mode
            // the persisted output must not contain item-name-derived content.
            // ReceiptItemCategorization.itemDescription is NOT NULL in the Room
            // schema, so no name-free row projection exists — restricted modes
            // persist NO categorization rows (the schema-forced path). The
            // in-session result below is unaffected.
            val savedCount = if (persistItemTextAllowed) storeResults(receiptId, result) else 0

            // 11. Receipt status: READY only when rows were actually persisted AND no
            // placeholder (an item the cloud call did not send) remains; a partial result
            // keeps the truthful PENDING status.
            if (persistItemTextAllowed) {
                if (savedCount > 0) {
                    val hasPlaceholders = result.items.any { ReceiptItemPlaceholder.isPlaceholder(it) }
                    receiptRepository.updateCategorizationStatus(
                        receiptId,
                        if (hasPlaceholders) CategorizationStatus.PENDING else CategorizationStatus.READY
                    )
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // R6b HD-R6b-1: the receipt was set to ANALYZING before this try;
            // reset it to PENDING (status only, no FAILED artifact — the
            // cancellation is not a categorization failure) before
            // propagating. Cancellation is never swallowed.
            // N1: a failing reset must never replace the original
            // CancellationException (class name only in the log).
            // A retry never set ANALYZING, so it has nothing to reset.
            if (!isRetry) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        receiptRepository.updateCategorizationStatus(receiptId, CategorizationStatus.PENDING)
                    } catch (resetError: Exception) {
                        Timber.w("CategorizeReceiptItems: CANCEL_STATUS_RESET_FAILED class=%s", resetError::class.java.simpleName)
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            // R6b privacy: class name + id only in logs; never e.message or the throwable.
            Timber.e("Error categorizing receipt items class=%s for %d", e::class.java.simpleName, receiptId)
            // RP-12 12b (P3-007) / R6b OUTPUT gate: no mode persists
            // uncontrolled exception text (framework messages can embed
            // item-derived input); the exception class name is the permitted
            // bounded diagnostic. validateResult reasons are controlled
            // constants and are persisted in both cases.
            failCategorization(
                receiptId,
                baseEntity,
                e::class.java.simpleName,
                isRetry
            )
        }
    }

    /**
     * Retry only: the indices (into [input]'s items) of the stored rows that are still
     * uncategorised placeholders, or null when the stored rows do not line up with the input
     * (different count or amount at any position) or nothing is left to retry. Row i of the
     * stored rows (ordered by id, as inserted) belongs to item i of the input.
     */
    private fun retryIndicesOrNull(
        rows: List<ReceiptItemCategorizationSnapshot>,
        input: ReceiptItemCategorizationInput
    ): List<Int>? {
        if (rows.size != input.lineItems.size) return null
        if (rows.indices.any { rows[it].itemAmount != input.lineItems[it].totalPrice }) return null
        return rows.indices.filter { ReceiptItemPlaceholder.isRetryable(rows[it]) }.ifEmpty { null }
    }

    /**
     * Retry only: writes the real answers into their placeholder rows (the repository skips
     * any row that is no longer an untouched placeholder), then re-reads the stored rows and
     * sets READY only when no uncategorised placeholder remains. The artifact is rebuilt from
     * the stored rows so earlier results are kept. If the service answered nothing real, no
     * progress was made and nothing is changed.
     */
    private suspend fun completeRetry(
        receiptId: Long,
        baseEntity: AiArtifactRecord,
        rows: List<ReceiptItemCategorizationSnapshot>,
        retryIndices: List<Int>,
        result: ReceiptItemCategorizationResult
    ): CategorizationResult {
        val answered = result.items.indices.filter { !ReceiptItemPlaceholder.isPlaceholder(result.items[it]) }
        if (answered.isEmpty()) {
            Timber.w("Receipt item retry for %d made no progress", receiptId)
            return CategorizationResult.Error
        }
        val updated = receiptItemCategorizationRepository.updatePlaceholderRowsWithRetryResult(
            receiptId = receiptId,
            rowIds = answered.map { rows[retryIndices[it]].id },
            items = answered.map { result.items[it] },
            now = timeProvider.now()
        )
        Timber.d("Receipt item retry for %d updated %d of %d rows", receiptId, updated, answered.size)

        val stored = receiptItemCategorizationRepository.getByReceiptIdAsSnapshots(receiptId)
        if (stored.isEmpty()) {
            // The receipt (and its rows) went away during the call: nothing to report or store.
            Timber.w("Receipt item retry for %d found no stored rows", receiptId)
            return CategorizationResult.Error
        }
        val remaining = stored.count { ReceiptItemPlaceholder.isRetryable(it) }
        receiptRepository.updateCategorizationStatus(
            receiptId,
            if (remaining == 0) CategorizationStatus.READY else CategorizationStatus.PENDING
        )
        val merged = mergedResultFromStoredRows(stored)
        updateArtifactReady(baseEntity, merged, persistItemTextAllowed = true)
        return CategorizationResult.Success(merged)
    }

    /**
     * Rebuilds the AI result from the stored rows (suggestions only; user corrections are not
     * part of the AI artifact). The tax distribution is the stored per-item tax share summed
     * by suggested category.
     */
    private fun mergedResultFromStoredRows(
        stored: List<ReceiptItemCategorizationSnapshot>
    ): ReceiptItemCategorizationResult {
        val items = stored.map { row ->
            val categoryName = row.suggestedCategoryName
            val suggestion = if (row.suggestedCategoryId != null || !categoryName.isNullOrBlank()) {
                com.yourname.expensetracker.domain.ai.model.CategorySuggestion(
                    categoryId = row.suggestedCategoryId,
                    categoryName = categoryName.orEmpty(),
                    confidence = row.confidence,
                    isNewCategorySuggestion = row.isNewCategorySuggestion
                )
            } else {
                null
            }
            com.yourname.expensetracker.domain.ai.model.CategorizedReceiptItem(
                itemDescription = row.itemDescription,
                amount = row.itemAmount,
                suggestedCategory = suggestion,
                confidence = row.confidence,
                rationale = row.aiRationale.orEmpty(),
                alternatives = alternativesFromJson(row.alternativeCategoriesJson),
                needsReview = row.confidence < 0.7f || ReceiptItemPlaceholder.isPlaceholder(row)
            )
        }
        val answered = stored.filter { !ReceiptItemPlaceholder.isPlaceholder(it) }
        val taxByCategory = linkedMapOf<Long, java.math.BigDecimal>()
        stored.forEach { row ->
            val categoryId = row.suggestedCategoryId?.takeIf { it > 0 }
            val tax = row.taxAmount
            if (categoryId != null && tax != null && tax.isFinite()) {
                taxByCategory[categoryId] = (taxByCategory[categoryId] ?: java.math.BigDecimal.ZERO)
                    .add(java.math.BigDecimal.valueOf(tax))
            }
        }
        return ReceiptItemCategorizationResult(
            items = items,
            totalConfidence = if (answered.isEmpty()) 0f else answered.map { it.confidence }.average().toFloat(),
            needsReview = items.any { it.needsReview },
            suggestedNewCategories = stored
                .filter { it.isNewCategorySuggestion && !it.suggestedCategoryName.isNullOrBlank() }
                .mapNotNull { it.suggestedCategoryName }
                .distinct(),
            taxDistribution = taxByCategory.mapValues { it.value.toDouble() }
        )
    }

    private fun alternativesFromJson(json: String?): List<com.yourname.expensetracker.domain.ai.model.CategorySuggestion> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val alt = array.optJSONObject(index) ?: return@mapNotNull null
                com.yourname.expensetracker.domain.ai.model.CategorySuggestion(
                    categoryId = if (alt.isNull("id")) null else alt.optLong("id"),
                    categoryName = alt.optString("name", ""),
                    confidence = alt.optDouble("confidence", 0.0).toFloat()
                )
            }
        } catch (e: org.json.JSONException) {
            emptyList()
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
                    "${categorizedCountPhrase(result)} items"
                } else {
                    // Controlled constants + counts only — never item content.
                    "${categorizedCountPhrase(result)} items (not persisted for storage mode)"
                },
                explanationText = buildExplanation(result, persistItemTextAllowed),
                payloadJson = payload.toString(),
                updatedAt = timeProvider.now()
            )
        )
    }

    /**
     * "Categorized N" when every item has a result, "Categorized X of Y" on a partial result.
     * X counts real results only; placeholders (items not sent) are not counted. Counts only.
     */
    private fun categorizedCountPhrase(result: ReceiptItemCategorizationResult): String {
        val total = result.items.size
        val categorized = result.items.count { !ReceiptItemPlaceholder.isPlaceholder(it) }
        return if (categorized == total) "Categorized $total" else "Categorized $categorized of $total"
    }

    private fun buildExplanation(
        result: ReceiptItemCategorizationResult,
        persistItemTextAllowed: Boolean
    ): String {
        val lines = buildList {
            add("${categorizedCountPhrase(result)} receipt items")
            if (result.needsReview) {
                val uncertainCount = result.items.count { it.needsReview && !ReceiptItemPlaceholder.isPlaceholder(it) }
                if (uncertainCount > 0) add("⚠️ $uncertainCount items need review (confidence < 70%)")
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
        reason: String,
        isRetry: Boolean = false
    ): CategorizationResult {
        if (isRetry) {
            // A failed retry leaves the stored rows, the READY/partial artifact and the receipt
            // status exactly as they were. Only the id is logged: reasons can carry item or
            // category text and are not persisted for a retry.
            Timber.w("Receipt item retry failed for %d", receiptId)
            return CategorizationResult.Error
        }
        updateArtifactFailed(baseEntity, reason)
        receiptRepository.updateCategorizationStatus(receiptId, CategorizationStatus.PENDING)
        return CategorizationResult.Error
    }
}
