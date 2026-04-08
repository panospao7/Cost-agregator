package com.yourname.expensetracker.service

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.navigation.DomainOwnershipFilter
import com.yourname.expensetracker.domain.model.navigation.DomainTransactionFilter
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes and deserializes TransactionFilter to/from JSON strings.
 * 
 * Handles versioning for forward/backward compatibility and graceful
 * degradation when encountering unknown or renamed fields.
 */
@Singleton
class TransactionFilterSerializer @Inject constructor() {
    
    companion object {
        private const val CURRENT_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_CATEGORY_ID = "categoryId"
        private const val KEY_MERCHANT_NAME = "merchantName"
        private const val KEY_TRANSACTION_TYPE = "transactionType"
        private const val KEY_DATE_RANGE_START = "dateRangeStart"
        private const val KEY_DATE_RANGE_END = "dateRangeEnd"
        private const val KEY_OWNERSHIP = "ownership"
        private const val KEY_MIN_AMOUNT = "minAmount"
        private const val KEY_MAX_AMOUNT = "maxAmount"
        private const val KEY_CORRELATION_ID = "correlationId"
    }
    
    /**
     * Serialize a DomainTransactionFilter to JSON string.
     */
    fun serialize(filter: DomainTransactionFilter): String {
        return try {
            val json = JSONObject()
            json.put(KEY_VERSION, CURRENT_VERSION)
            
            filter.categoryId?.let { json.put(KEY_CATEGORY_ID, it) }
            filter.merchantName?.let { json.put(KEY_MERCHANT_NAME, it) }
            filter.transactionType?.let { json.put(KEY_TRANSACTION_TYPE, it.name) }
            filter.dateRange?.let { (start, end) ->
                json.put(KEY_DATE_RANGE_START, start)
                json.put(KEY_DATE_RANGE_END, end)
            }
            filter.ownership?.let { json.put(KEY_OWNERSHIP, it.name) }
            filter.minAmount?.let { json.put(KEY_MIN_AMOUNT, it) }
            filter.maxAmount?.let { json.put(KEY_MAX_AMOUNT, it) }
            json.put(KEY_CORRELATION_ID, filter.correlationId)
            
            json.toString()
        } catch (e: Exception) {
            // Return minimal valid JSON on error
            "{\"version\":$CURRENT_VERSION}"
        }
    }
    
    /**
     * Deserialize a JSON string to DomainTransactionFilter.
     * 
     * Handles missing fields gracefully by using null defaults.
     * Unknown fields are ignored for forward compatibility.
     */
    fun deserialize(jsonString: String): DomainTransactionFilter? {
        return try {
            val json = JSONObject(jsonString)
            
            // Version check for future migrations
            val version = json.optInt(KEY_VERSION, 1)
            
            val categoryId = if (json.has(KEY_CATEGORY_ID)) {
                json.getLong(KEY_CATEGORY_ID)
            } else null
            
            val merchantName = json.optString(KEY_MERCHANT_NAME).takeIf { it.isNotBlank() }

            val transactionType = json.optString(KEY_TRANSACTION_TYPE).takeIf { it.isNotBlank() }?.let {
                try {
                    DomainTransactionType.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null // Ignore invalid transaction types
                }
            }
            
            val dateRange = if (json.has(KEY_DATE_RANGE_START) && json.has(KEY_DATE_RANGE_END)) {
                Pair(
                    json.getLong(KEY_DATE_RANGE_START),
                    json.getLong(KEY_DATE_RANGE_END)
                )
            } else null
            
            val ownership = json.optString(KEY_OWNERSHIP).takeIf { it.isNotBlank() }?.let {
                try {
                    DomainOwnershipFilter.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null // Ignore invalid ownership filters
                }
            }
            
            val minAmount = if (json.has(KEY_MIN_AMOUNT)) {
                json.getDouble(KEY_MIN_AMOUNT)
            } else null
            
            val maxAmount = if (json.has(KEY_MAX_AMOUNT)) {
                json.getDouble(KEY_MAX_AMOUNT)
            } else null
            
            // Deserialize correlationId; legacy payloads without this key default to 0L
            val correlationId = if (json.has(KEY_CORRELATION_ID)) {
                json.getLong(KEY_CORRELATION_ID)
            } else 0L
            
            DomainTransactionFilter(
                categoryId = categoryId,
                merchantName = merchantName,
                transactionType = transactionType,
                dateRange = dateRange,
                ownership = ownership,
                minAmount = minAmount,
                maxAmount = maxAmount,
                correlationId = correlationId
            )
        } catch (e: Exception) {
            // Return null on parsing error - caller should handle gracefully
            null
        }
    }
    
    /**
     * Validate that a JSON string can be successfully deserialized.
     */
    fun isValid(jsonString: String): Boolean {
        return deserialize(jsonString) != null
    }
}
