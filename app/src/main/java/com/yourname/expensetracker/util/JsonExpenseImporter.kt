package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

class JsonExpenseImporter @Inject constructor(
    private val coordinator: TransactionLifecycleCoordinator,
    private val categoryDao: CategoryDao,
    private val timeProvider: TimeProvider
) {
    suspend fun importFromContent(
        jsonContent: String,
        fileImportRunId: Long? = null
    ): ImportResult {
        return try {
            val json = JSONObject(jsonContent)
            val rows = json.optJSONArray("rows") ?: return ImportResult(false, 0, 0, 1, listOf("No rows array found"), emptyList())
            val version = json.optInt("schemaVersion", 1)

            var imported = 0; var skipped = 0; var errors = 0
            val errorMessages = mutableListOf<String>()
            val expenseIds = mutableListOf<Long>()

            for (i in 0 until rows.length()) {
                try {
                    val row = rows.getJSONObject(i)
                    val request = if (version >= 2) parseV2Row(row, i, fileImportRunId) else parseV1Row(row, i, fileImportRunId)
                    @Suppress("DEPRECATION_ERROR") // TODO: migrate to createExpenseStandalone()
                    when (val result = coordinator.createExpense(request)) {
                        is CreateExpenseResult.Created -> { imported++; expenseIds.add(result.expenseId) }
                        is CreateExpenseResult.DuplicateSkipped -> skipped++
                        is CreateExpenseResult.ValidationFailed -> { errors++; errorMessages.add("Row $i: ${result.errors.joinToString()}") }
                        else -> { errors++; errorMessages.add("Row $i: import failed") }
                    }
                } catch (e: Exception) {
                    errors++; errorMessages.add("Row $i: ${e.message}")
                }
            }
            ImportResult(success = errors == 0, importedCount = imported, skippedCount = skipped, errorCount = errors, errors = errorMessages, expenseIds = expenseIds)
        } catch (e: Exception) {
            ImportResult(false, 0, 0, 1, listOf("Parse error: ${e.message}"), emptyList())
        }
    }

    private suspend fun parseV2Row(row: JSONObject, i: Int, fileImportRunId: Long? = null): CreateExpenseRequest {
        val merchant = row.getString("merchant")
        val amount = row.optDouble("amount", row.optDouble("effectiveAmount", 0.0))
        val currency = row.optString("currency", "EUR")
        val date = resolveDate(row)
        val notes = row.optString("notes", null)
        val sourceStr = row.optString("source", null)
        val txTypeStr = row.optString("transactionType", "PURCHASE")
        val paymentMethodStr = row.optString("paymentMethod", null)

        val categoryId = row.optString("category", null)?.let { name ->
            categoryDao.getByName(name)?.id ?: categoryDao.insert(com.yourname.expensetracker.data.database.entity.Category(name = name, icon = "📂", color = "#888888"))
        }
        val source = runCatching { sourceStr?.let { ExpenseSource.valueOf(it) } }.getOrNull() ?: ExpenseSource.CSV_IMPORT
        val txType = runCatching { TransactionType.valueOf(txTypeStr) }.getOrDefault(TransactionType.PURCHASE)
        val paymentMethod = runCatching { paymentMethodStr?.let { com.yourname.expensetracker.data.database.entity.PaymentMethod.valueOf(it) } }.getOrNull()

        return CreateExpenseRequest(
            merchant = merchant, amount = amount, currency = currency, date = date,
            transactionType = txType, source = source, categoryId = categoryId,
            notes = if (notes.isNullOrBlank()) null else notes, paymentMethod = paymentMethod,
            isBusinessExpense = row.optBoolean("isBusinessExpense", false),
            businessPurpose = row.optString("businessPurpose", "").takeIf { it.isNotBlank() },
            deduplicationMode = DeduplicationMode.STANDARD,
            idempotencyKey = row.optLong("id", i.toLong()).let { if (it > 0) "import:json:$it" else null },
            fileImportRunId = fileImportRunId
        )
    }

    private suspend fun parseV1Row(row: JSONObject, i: Int, fileImportRunId: Long? = null): CreateExpenseRequest {
        val categoryId = row.optString("category", null)?.let { name ->
            categoryDao.getByName(name)?.id ?: categoryDao.insert(com.yourname.expensetracker.data.database.entity.Category(name = name, icon = "📂", color = "#888888"))
        }
        return CreateExpenseRequest(
            merchant = row.getString("merchant"),
            amount = row.optDouble("amount", 0.0),
            currency = row.optString("currency", "EUR"),
            date = resolveDate(row),
            transactionType = TransactionType.PURCHASE, source = ExpenseSource.CSV_IMPORT,
            categoryId = categoryId,
            notes = row.optString("notes", "").takeIf { it.isNotBlank() },
            deduplicationMode = DeduplicationMode.STANDARD,
            idempotencyKey = row.optLong("id", i.toLong()).let { if (it > 0) "import:json:$it" else null },
            fileImportRunId = fileImportRunId
        )
    }

    /**
     * Resolves the expense timestamp from a JSON row.
     *
     * Contract:
     * - A present, valid `date` field is always preferred.
     * - Otherwise a present, valid `timestamp` field is used.
     * - Only when both fields are absent, null, or invalid is the time provider
     *   consulted, and it is consulted exactly once, so imports stay deterministic.
     *
     * Validity mirrors [JSONObject.optLong]: numeric values (and numeric strings)
     * are accepted; missing, null, or non-numeric values are treated as invalid.
     * Parsing is collision-free: every `Long` value, including [Long.MIN_VALUE],
     * is a legitimate parsed result and never a marker for "no value".
     */
    private fun resolveDate(row: JSONObject): Long {
        parseEpochMillis(row, "date")?.let { return it }
        parseEpochMillis(row, "timestamp")?.let { return it }
        return timeProvider.now()
    }

    /**
     * Parses `key` from `row` as epoch millis.
     *
     * Returns `null` when the field is absent, null, or not numeric, so callers
     * can fall through to the next resolution step. A successfully parsed value
     * is returned as-is even when it equals [Long.MIN_VALUE].
     */
    private fun parseEpochMillis(row: JSONObject, key: String): Long? {
        if (!row.has(key) || row.isNull(key)) return null
        return try {
            row.getLong(key)
        } catch (e: JSONException) {
            null
        }
    }
}