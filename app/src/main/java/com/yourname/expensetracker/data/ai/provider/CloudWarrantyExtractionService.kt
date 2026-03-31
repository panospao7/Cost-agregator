package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.domain.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig.
 * API key is retrieved from encrypted storage at runtime, not compiled into APK.
 */
@Singleton
class CloudWarrantyExtractionService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage
) {
    private var apiKeyOverride: String? = null
    
    internal constructor(apiKeyOverride: String, secureKeyStorage: SecureKeyStorage) : this(secureKeyStorage) {
        this.apiKeyOverride = apiKeyOverride
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * CRITICAL: API key is now retrieved from secure storage at runtime.
     * No longer compiled into BuildConfig, preventing APK extraction.
     */
    private val apiKey: String
        get() = apiKeyOverride ?: secureKeyStorage.getGeminiKey() ?: ""

    suspend fun extractWarranty(receipt: ScannedReceipt): Warranty? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Timber.d("CloudWarrantyExtractionService: Gemini API key missing, skipping.")
            return@withContext null
        }

        val prompt = buildPrompt(receipt)
        
        try {
            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 1024)
                })
            }

            val url = "${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Gemini API error: ${response.code}")
                    return@withContext null
                }

                val responseBody = response.body?.string()
                parseResponse(responseBody, receipt)
            }
        } catch (e: IOException) {
            Timber.e(e, "Network error extracting warranty")
            null
        } catch (e: Exception) {
            Timber.e(e, "Error extracting warranty")
            null
        }
    }

    private fun buildPrompt(receipt: ScannedReceipt): String {
        return """
            Analyze this receipt and extract warranty information.
            
            Receipt Text:
            ${receipt.rawOcrText}
            
            Merchant: ${receipt.parsedMerchant ?: "Unknown"}
            Total Amount: ${receipt.parsedTotal ?: "Unknown"}
            Date: ${receipt.parsedDate ?: "Unknown"}
            
            Extract the following warranty information:
            1. Product name (if multiple items, list the most expensive or main item)
            2. Warranty duration in months (or convert years to months)
            3. Warranty type: MANUFACTURER (default), EXTENDED, STORE, or THIRD_PARTY
            4. Support phone number (if provided)
            5. Support email (if provided)
            6. Return policy: number of days allowed for returns
            7. Return conditions (any special conditions)
            
            Return ONLY a JSON object in this exact format:
            {
                "hasWarranty": true/false,
                "productName": "Product Name",
                "warrantyMonths": 24,
                "warrantyType": "MANUFACTURER",
                "supportPhone": "+1234567890" (or null),
                "supportEmail": "support@company.com" (or null),
                "returnDays": 30,
                "returnConditions": "Original packaging required" (or null),
                "confidence": 0.95
            }
            
            If no warranty information is found, return:
            {"hasWarranty": false, "confidence": 0.0}
            
            Be precise. If uncertain, use lower confidence scores.
        """.trimIndent()
    }

    private fun parseResponse(responseBody: String?, receipt: ScannedReceipt): Warranty? {
        if (responseBody == null) return null

        return try {
            val json = JSONObject(responseBody)
            val candidates = json.getJSONArray("candidates")
            if (candidates.length() == 0) return null

            val content = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            // Extract JSON from the text (it might be wrapped in markdown)
            val jsonPattern = """\{[^{}]*\}""".toRegex()
            val jsonMatch = jsonPattern.find(content)?.value
            
            if (jsonMatch == null) {
                Timber.w("No JSON found in response: $content")
                return null
            }

            val warrantyJson = JSONObject(jsonMatch)
            
            if (!warrantyJson.optBoolean("hasWarranty", false)) {
                return null
            }

            val warrantyMonths = warrantyJson.optInt("warrantyMonths", 0)
            if (warrantyMonths <= 0) {
                return null // No valid warranty duration
            }

            val purchaseDate = receipt.parsedDate ?: receipt.createdAt
            val warrantyEndDate = purchaseDate + (warrantyMonths * 30L * 24 * 60 * 60 * 1000)

            Warranty(
                receiptId = receipt.id,
                expenseId = receipt.expenseId,
                productName = warrantyJson.optString("productName", "Unknown Product"),
                merchantName = receipt.parsedMerchant ?: "Unknown",
                purchaseDate = purchaseDate,
                warrantyDurationMonths = warrantyMonths,
                warrantyEndDate = warrantyEndDate,
                warrantyType = parseWarrantyType(warrantyJson.optString("warrantyType", "MANUFACTURER")),
                supportPhone = warrantyJson.optString("supportPhone").takeIf { it.isNotBlank() },
                supportEmail = warrantyJson.optString("supportEmail").takeIf { it.isNotBlank() },
                notes = warrantyJson.optString("returnConditions").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Timber.e(e, "Error parsing warranty response: $responseBody")
            null
        }
    }

    private fun parseWarrantyType(type: String): WarrantyType {
        return try {
            WarrantyType.valueOf(type.uppercase())
        } catch (e: IllegalArgumentException) {
            WarrantyType.MANUFACTURER
        }
    }
}
