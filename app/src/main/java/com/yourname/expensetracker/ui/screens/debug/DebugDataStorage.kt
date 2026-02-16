package com.yourname.expensetracker.ui.screens.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
    fun save(debugData: DebugData) {
        try {
            file.writeText(debugData.toJson())
            android.util.Log.d("DebugDataStorage", "Saved debug data to ${file.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("DebugDataStorage", "Failed to save debug data: ${e.message}")
        }
    }
    
    /**
     * Load debug data from file
     */
    fun load(): DebugData? {
        if (!file.exists()) {
            android.util.Log.d("DebugDataStorage", "No saved debug data found")
            return null
        }
        
        return try {
            val json = file.readText()
            parseDebugDataFromJson(json)
        } catch (e: Exception) {
            android.util.Log.e("DebugDataStorage", "Failed to load debug data: ${e.message}")
            null
        }
    }
    
    /**
     * Clear saved debug data
     */
    fun clear() {
        if (file.exists()) {
            file.delete()
            android.util.Log.d("DebugDataStorage", "Cleared debug data")
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
                    type = com.yourname.expensetracker.data.database.entity.TransactionType.valueOf(
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
            android.util.Log.e("DebugDataStorage", "Failed to parse JSON: ${e.message}")
            null
        }
    }
}
