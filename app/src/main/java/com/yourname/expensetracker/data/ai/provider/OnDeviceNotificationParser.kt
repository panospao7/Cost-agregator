package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.yourname.expensetracker.domain.ai.model.NotificationParseResult
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.NotificationFallbackParser
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device AI notification parser using ML Kit GenAI (Gemini Nano).
 * 
 * This is a fallback parser that activates when all deterministic parsers fail.
 * It uses on-device AI to extract transaction details from notifications in
 * any language or unstructured format.
 * 
 * Key features:
 * - Privacy-first: Processes entirely on-device (no cloud for notifications)
 * - Multilingual: Handles Greek, English, and other languages
 * - Fast: <500ms typical response time
 * - Graceful degradation: Returns null if AI unavailable
 */
@Singleton
class OnDeviceNotificationParser @Inject constructor(
    private val router: AiCapabilityRouter,
    private val settingsRepository: AiSettingsRepository
) : NotificationFallbackParser {

    @Volatile
    private var cachedModel: GenerativeModel? = null

    private fun getOrCreateModel(): GenerativeModel {
        cachedModel?.let { return it }
        return synchronized(this) {
            cachedModel ?: Generation.getClient().also { cachedModel = it }
        }
    }

    override suspend fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        packageName: String
    ): ParsedTransaction? {
        // Check if AI is enabled and on-device is available
        val settings = settingsRepository.settings().first()
        val decision = router.decide(AiCapability.NOTIFICATION_PARSE, settings)
        
        if (decision.route != com.yourname.expensetracker.domain.ai.model.AiRoute.ON_DEVICE) {
            Timber.d("OnDeviceNotificationParser: AI not available (route=${decision.route}), skipping")
            return null
        }

        // Combine notification fields
        val fullText = buildString {
            title?.let { append(it).append(" ") }
            text?.let { append(it).append(" ") }
            bigText?.let { append(it) }
        }.trim()

        if (fullText.isBlank()) {
            Timber.d("OnDeviceNotificationParser: Empty notification text, skipping")
            return null
        }

        // Truncate if too long
        val truncatedText = if (fullText.length > AppConfig.Ai.MAX_NOTIFICATION_TEXT_CHARS_FOR_AI) {
            fullText.take(AppConfig.Ai.MAX_NOTIFICATION_TEXT_CHARS_FOR_AI) + "..."
        } else {
            fullText
        }

        return try {
            val model = getOrCreateModel()
            val request = buildRequest(truncatedText, packageName)
            val response = model.generateContent(request)
            val responseText = response.candidates.firstOrNull()?.text ?: return null
            parseResponse(responseText, packageName)
        } catch (e: GenAiException) {
            Timber.w(e, "OnDeviceNotificationParser: GenAI error (code=%d)", e.errorCode)
            null
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceNotificationParser: unexpected error")
            null
        }
    }

    private fun buildRequest(notificationText: String, packageName: String): GenerateContentRequest {
        val prompt = buildPrompt(notificationText, packageName)
        val builder = GenerateContentRequest.builder(TextPart(prompt))
        builder.temperature = AppConfig.Ai.ON_DEVICE_NOTIFICATION_TEMPERATURE
        builder.maxOutputTokens = AppConfig.Ai.ON_DEVICE_NOTIFICATION_MAX_TOKENS
        return builder.build()
    }

    internal fun buildPrompt(notificationText: String, packageName: String): String {
        return buildString {
		appendLine("You are a financial transaction parser. Extract transaction details from the notification below.")
		appendLine("If the notification is NOT a financial transaction (e.g., social media, marketing, chat message), return: {\"is_transaction\": false}")
		appendLine()
		appendLine("The notification may be in any language (Greek, English, etc.).")
            appendLine()
            appendLine("Notification source: $packageName")
            appendLine("Notification text: \"$notificationText\"")
            appendLine()
		appendLine("Extract these fields and return ONLY a JSON object:")
		appendLine("If this IS a financial transaction:")
		appendLine("{")
		appendLine("  \"is_transaction\": true,")
		appendLine("  \"amount\": number (positive, no currency symbol),")
            appendLine("  \"currency\": string (ISO code: EUR, USD, GBP, etc., default EUR),")
            appendLine("  \"merchant\": string (merchant name or \"Unknown\" if unclear),")
            appendLine("  \"type\": string (one of: PURCHASE, TRANSFER, DEPOSIT, WITHDRAWAL, default PURCHASE),")
            appendLine("  \"direction\": string (one of: INCOMING, OUTGOING, or null; only set for TRANSFER or DEPOSIT, otherwise null),")
            appendLine("  \"confidence\": number (0.0-1.0, your confidence in this parsing),")
            appendLine("  \"reasoning\": string (brief explanation of how you interpreted the notification)")
		appendLine("}")
		appendLine()
		appendLine("If this is NOT a financial transaction, return: {\"is_transaction\": false}")
		appendLine()
		appendLine("Guidelines:")
            appendLine("- amount: Always positive number (5.0, not -5.0)")
            appendLine("- type PURCHASE: Buying something at a store/merchant")
            appendLine("- type TRANSFER: Moving money between accounts/people")
            appendLine("- type DEPOSIT: Receiving money (salary, refund, etc.)")
            appendLine("- type WITHDRAWAL: Taking cash from ATM")
            appendLine("- direction INCOMING: Use only when type is DEPOSIT or incoming TRANSFER")
            appendLine("- direction OUTGOING: Use only when type is outgoing TRANSFER")
            appendLine("- For PURCHASE or WITHDRAWAL, set direction to null")
            appendLine("- For Greek: χρεωθήκατε = charged (usually PURCHASE, direction null), πιστώθηκε = credited (DEPOSIT, direction INCOMING)")
            appendLine()
            appendLine("Example for 'χρεωθήκατε 5€ στο Σκλαβενίτη':")
		appendLine("{\"is_transaction\":true,\"amount\":5.0,\"currency\":\"EUR\",\"merchant\":\"Σκλαβενίτης\",\"type\":\"PURCHASE\",\"direction\":null,\"confidence\":0.85,\"reasoning\":\"Greek word 'χρεωθήκατε' means 'charged', indicating a purchase\"}")
        }
    }

    internal fun parseResponse(text: String, packageName: String): ParsedTransaction? {
        val jsonText = extractFirstJsonObject(text.trim()) ?: return null
        return try {
		val json = JSONObject(jsonText)

		// Check if the model says this is not a transaction
		if (!json.optBoolean("is_transaction", true)) {
			Timber.d("OnDeviceNotificationParser: AI classified as non-transaction")
			return null
		}

		// Required fields
            val amount = json.optDouble("amount", -1.0)
            if (amount <= 0) {
                Timber.w("OnDeviceNotificationParser: Invalid or missing amount")
                return null
            }

            val currency = json.optString("currency", "EUR").uppercase()
            if (currency.length != 3) {
                Timber.w("OnDeviceNotificationParser: Invalid currency code: $currency")
                return null
            }

            val merchant = json.optString("merchant", "Unknown").ifBlank { "Unknown" }
            
            // Parse transaction type
            val typeStr = json.optString("type", "PURCHASE").uppercase()
            val transactionType = try {
                ParsedTransactionType.valueOf(typeStr)
            } catch (e: IllegalArgumentException) {
                Timber.w("OnDeviceNotificationParser: Unknown transaction type: $typeStr, defaulting to PURCHASE")
                ParsedTransactionType.PURCHASE
            }

            // Parse direction
            val directionStr = json.optString("direction", "")
            val direction = when {
                directionStr.isBlank() -> null
                directionStr.equals("INCOMING", ignoreCase = true) -> ParsedTransferDirection.INCOMING
                directionStr.equals("OUTGOING", ignoreCase = true) -> ParsedTransferDirection.OUTGOING
                else -> null
            }

            val transferDirection = when (transactionType) {
                ParsedTransactionType.TRANSFER,
                ParsedTransactionType.DEPOSIT -> direction
                else -> null
            }

            // Parse confidence
            val confidence = json.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f)

            // Log the reasoning for debugging
            val reasoning = json.optString("reasoning", "")
            if (reasoning.isNotBlank()) {
                Timber.d("OnDeviceNotificationParser: AI reasoning - $reasoning")
            }

            ParsedTransaction(
                amount = amount,
                currency = currency,
                merchant = merchant,
                type = transactionType,
                confidence = confidence,
                transferDirection = transferDirection,
                transferAccountName = transferDirection?.let {
                    when (it) {
                        ParsedTransferDirection.INCOMING -> "From: $merchant"
                        ParsedTransferDirection.OUTGOING -> "To: $merchant"
                    }
                }
            )
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceNotificationParser: JSON parse failure")
            null
        }
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }
}
