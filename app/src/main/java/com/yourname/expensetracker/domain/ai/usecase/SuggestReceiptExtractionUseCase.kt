package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistGenerationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistSuggestion
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.ReceiptAssistService
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

class SuggestReceiptExtractionUseCase @Inject constructor(
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiArtifactRepository: AiArtifactRepository,
    private val receiptAssistService: ReceiptAssistService,
    private val aiCapabilityRouter: AiCapabilityRouter,
    private val inputBuilder: ReceiptAssistInputBuilder,
    private val receiptRepository: ReceiptRepository,
    private val timeProvider: TimeProvider
) {

    suspend operator fun invoke(
        receiptId: Long,
        force: Boolean = false
    ): ReceiptAssistGenerationResult {
        val settings = aiSettingsRepository.settings().first()
        if (!settings.aiEnabled || !settings.receiptAssistEnabled) {
            return ReceiptAssistGenerationResult.Disabled("AI receipt assist is disabled.")
        }

        val receipt = receiptRepository.getReceiptById(receiptId)
            ?: return ReceiptAssistGenerationResult.Error("Receipt not found.")

        if (!hasUsableOcrText(receipt)) {
            return ReceiptAssistGenerationResult.Error(
                "AI receipt assist needs OCR text from the scanned receipt."
            )
        }

        // REMOVED: Blocking "needsAssist" check that prevented AI from running
        // when receipt fields looked "complete". Now AI always attempts to assist
        // for better accuracy and to catch OCR errors, even if fields exist.

        val input = inputBuilder.build(receipt, settings)
        val routeDecision = aiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, settings)
        if (routeDecision.route == AiRoute.DISABLED) {
            return ReceiptAssistGenerationResult.Disabled(routeDecision.reason)
        }
        val targetKey = "scanned_receipt:$receiptId"
        val now = timeProvider.now()
        val sourceHash = input.hashCode().toString()

        val existing = aiArtifactRepository.getLatest(targetKey, AiCapability.RECEIPT_EXTRACTION)
        if (existing != null &&
            existing.status == AiArtifactStatus.READY &&
            existing.promptVersion == AppConfig.Ai.PROMPT_VERSION_RECEIPT &&
            existing.sourceHash == sourceHash &&
            existing.expiresAt != null &&
            existing.expiresAt > now
        ) {
            existing.payloadJson
                ?.toReceiptAssistSuggestionOrNull()
                ?.let {
                    return ReceiptAssistGenerationResult.Success(
                        suggestion = it,
                        fromCache = true,
                        usedImageInput = existing.explanationText?.contains("Image-aware cloud assist") == true
                    )
                }
        }

        val baseEntity = AiArtifactEntity(
            targetType = AiTargetType.SCANNED_RECEIPT,
            targetId = receiptId,
            targetKey = targetKey,
            capability = AiCapability.RECEIPT_EXTRACTION,
            status = AiArtifactStatus.RUNNING,
            mode = when (routeDecision.route) {
                AiRoute.ON_DEVICE -> AiMode.ON_DEVICE
                AiRoute.CLOUD -> AiMode.CLOUD
                AiRoute.DETERMINISTIC_FALLBACK,
                AiRoute.DISABLED -> AiMode.AUTO
            },
            provider = routeDecision.providerName,
            modelName = routeDecision.modelName,
            promptVersion = AppConfig.Ai.PROMPT_VERSION_RECEIPT,
            sourceHash = sourceHash,
            createdAt = now,
            updatedAt = now,
            expiresAt = now + AppConfig.Ai.RECEIPT_ASSIST_TTL_MS
        )
        aiArtifactRepository.upsert(baseEntity)

        return try {
            val serviceResult = receiptAssistService.suggest(input)
            val usedImageInput = receiptAssistService.usedImageInput(input)
            when (serviceResult) {
                is AiServiceResult.Success -> {
                    val suggestion = serviceResult.value
                    if (suggestion.isEmpty()) {
                        aiArtifactRepository.upsert(
                            baseEntity.copy(
                                status = AiArtifactStatus.FAILED,
                                errorMessage = failureMessage("No usable suggestions", routeDecision),
                                updatedAt = timeProvider.now()
                            )
                        )
                        ReceiptAssistGenerationResult.Error("AI receipt assist returned no usable suggestions.")
                    } else {
                        aiArtifactRepository.upsert(
                            baseEntity.copy(
                                status = AiArtifactStatus.READY,
                                summaryText = suggestion.toSummaryText(),
                                explanationText = suggestion.toExplanationText(usedImageInput).withRouteDiagnostics(routeDecision),
                                payloadJson = suggestion.toPayloadJson(),
                                updatedAt = timeProvider.now()
                            )
                        )
                        ReceiptAssistGenerationResult.Success(
                            suggestion = suggestion,
                            fromCache = false,
                            usedImageInput = usedImageInput
                        )
                    }
                }
                is AiServiceResult.Failure -> {
                    val readableError = serviceResult.error.toReadableMessage()
                    aiArtifactRepository.upsert(
                        baseEntity.copy(
                            status = AiArtifactStatus.FAILED,
                            errorMessage = failureMessage(readableError, routeDecision),
                            updatedAt = timeProvider.now()
                        )
                    )
                    ReceiptAssistGenerationResult.Error(readableError)
                }
            }
        } catch (e: Exception) {
            aiArtifactRepository.upsert(
                baseEntity.copy(
                    status = AiArtifactStatus.FAILED,
                    errorMessage = failureMessage(e.message, routeDecision),
                    updatedAt = timeProvider.now()
                )
            )
            ReceiptAssistGenerationResult.Error(
                e.message?.take(200) ?: "AI receipt assist failed."
            )
        }
    }

    private fun hasUsableOcrText(receipt: ScannedReceipt): Boolean {
        val rawText = receipt.rawOcrText.trim()
        if (rawText.isBlank()) return false
        if (rawText.startsWith("[OCR Failed", ignoreCase = true)) return false
        if (rawText.startsWith("Scan Failed:", ignoreCase = true)) return false
        return true
    }
}

private fun AiServiceError.toReadableMessage(): String = when (this) {
    AiServiceError.Timeout -> "AI receipt assist timed out. Please retry."
    AiServiceError.Offline -> "Network unavailable. Check connection and retry."
    AiServiceError.SslError -> "Secure connection failed. Please retry later."
    is AiServiceError.HttpError -> "AI service returned HTTP $code."
    is AiServiceError.ParseError -> message ?: "AI response could not be parsed."
    is AiServiceError.Disabled -> reason
    is AiServiceError.Unknown -> message ?: "AI receipt assist failed."
}

// REMOVED: needsAssist() function - no longer blocking AI from running

private fun ReceiptAssistSuggestion.isEmpty(): Boolean =
    merchant == null && total == null && date == null && taxAmount == null && notes.isEmpty()

private fun ReceiptAssistSuggestion.toSummaryText(): String {
    val suggestedFields = buildList {
        if (merchant != null) add("merchant")
        if (total != null) add("total")
        if (date != null) add("date")
        if (taxAmount != null) add("tax")
    }

    return when (suggestedFields.size) {
        0 -> "AI receipt assist added guidance"
        1 -> "AI suggested ${suggestedFields.first()}"
        else -> "AI suggested ${suggestedFields.size} receipt fields"
    }
}

private fun ReceiptAssistSuggestion.toExplanationText(usedImageInput: Boolean): String? {
    val lines = buildList {
        if (usedImageInput) add("Image-aware cloud assist cross-checked the receipt photo with OCR.")
        merchant?.rationale?.let { add("Merchant: $it") }
        total?.rationale?.let { add("Total: $it") }
        date?.rationale?.let { add("Date: $it") }
        taxAmount?.rationale?.let { add("Tax: $it") }
        addAll(notes)
    }

    return lines
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n")
        ?.take(AppConfig.Ai.MAX_CAPTURE_SUPPORTING_TEXT_CHARS)
}

private fun String?.withRouteDiagnostics(routeDecision: AiRouteDecision): String? {
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

private fun ReceiptAssistSuggestion.toPayloadJson(): String {
    return JSONObject().apply {
        merchant?.let { put("merchant", it.toJson()) }
        total?.let { put("total", it.toJson()) }
        date?.let { put("date", it.toJson()) }
        taxAmount?.let { put("taxAmount", it.toJson()) }
        put("notes", JSONArray(notes))
    }.toString()
}

private fun <T> com.yourname.expensetracker.domain.ai.model.SuggestedValue<T>.toJson(): JSONObject {
    return JSONObject().apply {
        put("value", value)
        confidence?.let { put("confidence", it) }
        rationale?.let { put("rationale", it) }
    }
}

private fun String.toReceiptAssistSuggestionOrNull(): ReceiptAssistSuggestion? {
    return runCatching {
        val root = JSONObject(this)
        ReceiptAssistSuggestion(
            merchant = root.optJSONObject("merchant")?.toSuggestedString(),
            total = root.optJSONObject("total")?.toSuggestedDouble(),
            date = root.optJSONObject("date")?.toSuggestedLong(),
            taxAmount = root.optJSONObject("taxAmount")?.toSuggestedDouble(),
            notes = root.optJSONArray("notes").toStringList()
        )
    }.getOrNull()
}

private fun JSONObject.toSuggestedString() = com.yourname.expensetracker.domain.ai.model.SuggestedValue(
    value = optString("value"),
    confidence = optDoubleOrNull("confidence")?.toFloat(),
    rationale = optString("rationale").takeIf { it.isNotBlank() }
)

private fun JSONObject.toSuggestedDouble() = com.yourname.expensetracker.domain.ai.model.SuggestedValue(
    value = optDouble("value"),
    confidence = optDoubleOrNull("confidence")?.toFloat(),
    rationale = optString("rationale").takeIf { it.isNotBlank() }
)

private fun JSONObject.toSuggestedLong() = com.yourname.expensetracker.domain.ai.model.SuggestedValue(
    value = optLong("value"),
    confidence = optDoubleOrNull("confidence")?.toFloat(),
    rationale = optString("rationale").takeIf { it.isNotBlank() }
)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optString(index)
                .takeIf { it.isNotBlank() }
                ?.let(::add)
        }
    }
}
