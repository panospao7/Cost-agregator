package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.data.security.getGeminiKey
import com.yourname.expensetracker.domain.ai.model.CategorizedReceiptItem
import com.yourname.expensetracker.domain.ai.model.CategorySuggestion
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.service.ReceiptItemCategorizationService
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// CRITICAL FIX (CRITICAL-1): Now uses SecureKeyStorage instead of BuildConfig
class CloudReceiptItemCategorizationService @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage
) : ReceiptItemCategorizationService {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(AppConfig.Ai.RECEIPT_ASSIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(AppConfig.Ai.RECEIPT_ASSIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    
    private val apiKey: String
        get() = secureKeyStorage.getGeminiKey() ?: ""
    
    override suspend fun categorizeItems(input: ReceiptItemCategorizationInput): ReceiptItemCategorizationResult? {
        if (apiKey.isBlank()) {
            Timber.d("CloudReceiptItemCategorizationService: Gemini API key missing, skipping.")
            return null
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val prompt = buildPrompt(input)
                val requestBody = buildRequestBody(prompt)
                
                val request = Request.Builder()
                    .url("${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/${AppConfig.Ai.RECEIPT_ITEM_CATEGORIZATION_CLOUD_MODEL}:generateContent?key=$apiKey")
                    .post(requestBody.toRequestBody(jsonMediaType))
                    .build()
                
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                
                if (!response.isSuccessful || body == null) {
                    Timber.e("CloudReceiptItemCategorizationService: Cloud AI request failed: ${response.code}")
                    return@withContext null
                }
                
                parseResponse(body, input)
            } catch (e: Exception) {
                Timber.e(e, "CloudReceiptItemCategorizationService: Error calling cloud AI for receipt item categorization")
                null
            }
        }
    }
    
    private fun buildPrompt(input: ReceiptItemCategorizationInput): String {
        val categoriesList = input.userCategories.joinToString(", ") { "${it.name} (id: ${it.id})" }
        
        val itemsList = input.lineItems.joinToString("\n") { item ->
            "- ${item.description}: €${item.totalPrice}"
        }
        
        return """
You are a receipt item categorization assistant. Categorize each item below.

Store: ${input.merchant}
Available categories: $categoriesList

Items:
$itemsList

For each item, provide:
1. categoryId (from available categories, or null if suggesting new)
2. categoryName (MUST match one of the available categories exactly, or provide a new category name)
3. confidence (0.0-1.0)
4. rationale (brief explanation)
5. isNewCategorySuggestion (true only if suggesting a category not in the list)

If an item doesn't fit existing categories, you MAY suggest a new category name (isNewCategorySuggestion: true).

Confidence guidelines:
- 0.9-1.0: Clear match (e.g., "Apples" → "Food")
- 0.7-0.89: Good match (e.g., "Detergent" → "Household")  
- 0.5-0.69: Uncertain (provide 2 alternative suggestions)
- <0.5: Very unclear

Also calculate proportional tax distribution if total tax is provided: ${input.totalTax ?: 0.0}

Output JSON format:
{
  "items": [
    {
      "description": "item name",
      "amount": 2.50,
      "categoryId": 5,
      "categoryName": "Food",
      "confidence": 0.94,
      "rationale": "Fresh produce",
      "isNewCategorySuggestion": false,
      "alternatives": [
        {"categoryId": 12, "categoryName": "Groceries", "confidence": 0.89}
      ]
    }
  ],
  "suggestedNewCategories": ["Sportswear"],
  "taxDistribution": {"5": 0.25, "12": 1.75}
}
""".trimIndent()
    }
    
    private fun buildRequestBody(prompt: String): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        
        return JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", parts)
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", AppConfig.Ai.ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS)
                put("responseMimeType", "application/json")
            })
        }.toString()
    }
    
    private fun parseResponse(body: String, input: ReceiptItemCategorizationInput): ReceiptItemCategorizationResult? {
        return try {
            val root = JSONObject(body)
            val candidates = root.optJSONArray("candidates") ?: return null
            val text = candidates.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text") ?: ""
            
            if (text.isBlank()) return null
            
            // Extract JSON from response
            val jsonText = extractFirstJsonObject(text) ?: return null
            val result = JSONObject(jsonText)
            
            val itemsArray = result.optJSONArray("items") ?: return null
            val items = mutableListOf<CategorizedReceiptItem>()
            
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.getJSONObject(i)
                val categoryId = item.optLong("categoryId", -1).takeIf { it > 0 }
                val categoryName = item.optString("categoryName", "Unknown")
                
                val alternatives = mutableListOf<CategorySuggestion>()
                item.optJSONArray("alternatives")?.let { altArray ->
                    for (j in 0 until altArray.length()) {
                        val alt = altArray.getJSONObject(j)
                        alternatives.add(CategorySuggestion(
                            categoryId = alt.optLong("categoryId", -1).takeIf { it > 0 },
                            categoryName = alt.optString("categoryName", ""),
                            confidence = alt.optDouble("confidence", 0.0).toFloat()
                        ))
                    }
                }
                
                items.add(CategorizedReceiptItem(
                    itemDescription = item.optString("description", ""),
                    amount = item.optDouble("amount", 0.0),
                    suggestedCategory = CategorySuggestion(
                        categoryId = categoryId,
                        categoryName = categoryName,
                        confidence = item.optDouble("confidence", 0.0).toFloat(),
                        isNewCategorySuggestion = item.optBoolean("isNewCategorySuggestion", false)
                    ),
                    confidence = item.optDouble("confidence", 0.0).toFloat(),
                    rationale = item.optString("rationale", ""),
                    alternatives = alternatives,
                    needsReview = item.optDouble("confidence", 0.0).toFloat() < 0.7f
                ))
            }
            
            val newCategories = mutableListOf<String>()
            result.optJSONArray("suggestedNewCategories")?.let { array ->
                for (i in 0 until array.length()) {
                    newCategories.add(array.getString(i))
                }
            }
            
            val taxDistribution = mutableMapOf<Long, Double>()
            result.optJSONObject("taxDistribution")?.let { taxObj ->
                taxObj.keys().forEach { key ->
                    val categoryId = key.toLongOrNull() ?: return@forEach
                    taxDistribution[categoryId] = taxObj.optDouble(key, 0.0)
                }
            }
            
            val avgConfidence = if (items.isNotEmpty()) {
                items.map { it.confidence }.average().toFloat()
            } else 0f
            
            ReceiptItemCategorizationResult(
                items = items,
                totalConfidence = avgConfidence,
                needsReview = items.any { it.needsReview },
                suggestedNewCategories = newCategories,
                taxDistribution = taxDistribution
            )
        } catch (e: Exception) {
            Timber.e(e, "Error parsing AI response")
            null
        }
    }
    
    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null
        
        var braceCount = 0
        var end = start
        
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> braceCount++
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        end = i + 1
                        break
                    }
                }
            }
        }
        
        return text.substring(start, end)
    }
}
