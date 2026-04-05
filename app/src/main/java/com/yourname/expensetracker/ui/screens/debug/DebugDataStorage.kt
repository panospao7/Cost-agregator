package com.yourname.expensetracker.ui.screens.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Handles persistence of debug data to file storage.
 * Saves the most recent bank statement import debug data so it can be
 * reviewed even after app restart.
 */
@Singleton
class DebugDataStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val file = File(context.filesDir, "last_debug_data.json")
    
    /**
     * Save debug data to file
     */
    suspend fun save(debugData: DebugData) {
        withContext(Dispatchers.IO) {
            try {
                file.writeText(debugData.toJson())
                Timber.d("Saved debug data to ${file.absolutePath}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save debug data: ${e.message}")
            }
        }
    }
    
    /**
     * Load debug data from file
     */
    suspend fun load(): DebugData? {
        return withContext(Dispatchers.IO) {
            if (!file.exists()) {
                Timber.d("No saved debug data found")
                return@withContext null
            }
            
            return@withContext try {
                val json = file.readText()
                parseDebugDataFromJson(json)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load debug data: ${e.message}")
                null
            }
        }
    }
    
    /**
     * Clear saved debug data
     */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            if (file.exists()) {
                file.delete()
                Timber.d("Cleared debug data")
            }
        }
    }
    
    /**
     * Parse JSON back into DebugData object
     */
    private fun parseDebugDataFromJson(json: String): DebugData? {
        return try {
            val root = org.json.JSONObject(json)
            
            // Extract metadata
            val metadata = root.optJSONObject("metadata")
            val processingTimeMs = metadata?.optLong("processingTimeMs") ?: 0L
            val parserUsed = metadata?.optString("parserUsed") ?: "Unknown"
            
            // Extract transactions
            val transactionsArray = root.optJSONArray("transactions") ?: org.json.JSONArray()
            val transactions = mutableListOf<com.yourname.expensetracker.domain.parser.ParsedTransaction>()
            for (i in 0 until transactionsArray.length()) {
                val txObj = transactionsArray.getJSONObject(i)
                transactions.add(com.yourname.expensetracker.domain.parser.ParsedTransaction(
                    amount = txObj.optDouble("amount", 0.0),
                    currency = txObj.optString("currency", "EUR"),
                    merchant = txObj.optString("merchant", ""),
                    type = ParsedTransactionType.valueOf(
                        txObj.optString("type", "PURCHASE")
                    ),
                    confidence = txObj.optDouble("confidence", 0.0).toFloat(),
                    date = if (txObj.isNull("date")) null else txObj.optLong("date")
                ))
            }
            
            // Extract issues
            val issuesRoot = root.optJSONObject("issues")
            val issuesArray = issuesRoot?.optJSONArray("details") ?: org.json.JSONArray()
            val issues = mutableListOf<DebugIssue>()
            for (i in 0 until issuesArray.length()) {
                val issueObj = issuesArray.getJSONObject(i)
                issues.add(DebugIssue(
                    severity = IssueSeverity.valueOf(issueObj.optString("severity", "INFO")),
                    category = issueObj.optString("category", ""),
                    message = issueObj.optString("message", ""),
                    transactionIndex = if (issueObj.isNull("transactionIndex")) null else issueObj.optInt("transactionIndex"),
                    suggestion = if (issueObj.isNull("suggestion")) null else issueObj.optString("suggestion")
                ))
            }
            
            // Extract logs
            val logsArray = root.optJSONArray("parsingLogs") ?: org.json.JSONArray()
            val logs = mutableListOf<String>()
            for (i in 0 until logsArray.length()) {
                logs.add(logsArray.getString(i))
            }
            
            // Extract raw text preview (we don't store full raw text for space, but we could)
            // For now, use the preview or just empty string if not available
            val rawTextObj = root.optJSONObject("rawText")
            val rawText = rawTextObj?.optString("preview") ?: ""
            
            DebugData(
                rawText = rawText,
                parsedTransactions = transactions,
                parsingLogs = logs,
                processingTimeMs = processingTimeMs,
                parserUsed = parserUsed,
                issues = issues
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse JSON: ${e.message}")
            null
        }
    }
}
