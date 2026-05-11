package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import org.json.JSONObject
import javax.inject.Inject

class JsonExpenseImporter @Inject constructor(
    private val coordinator: TransactionLifecycleCoordinator,
    private val categoryDao: CategoryDao
) {
    suspend fun importFromContent(jsonContent: String): ImportResult {
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
                    val request = if (version >= 2) parseV2Row(row, i) else parseV1Row(row, i)
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

    private suspend fun parseV2Row(row: JSONObject, i: Int): CreateExpenseRequest {
        val merchant = row.getString("merchant")
        val amount = row.optDouble("amount", row.optDouble("effectiveAmount", 0.0))
        val currency = row.optString("currency", "EUR")
        val date = row.optLong("date", row.optLong("timestamp", System.currentTimeMillis()))
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
            idempotencyKey = row.optLong("id", i.toLong()).let { if (it > 0) "import:json:$it" else null }
        )
    }

    private suspend fun parseV1Row(row: JSONObject, i: Int): CreateExpenseRequest {
        val categoryId = row.optString("category", null)?.let { name ->
            categoryDao.getByName(name)?.id ?: categoryDao.insert(com.yourname.expensetracker.data.database.entity.Category(name = name, icon = "📂", color = "#888888"))
        }
        return CreateExpenseRequest(
            merchant = row.getString("merchant"),
            amount = row.optDouble("amount", 0.0),
            currency = row.optString("currency", "EUR"),
            date = row.optLong("date", row.optLong("timestamp", System.currentTimeMillis())),
            transactionType = TransactionType.PURCHASE, source = ExpenseSource.CSV_IMPORT,
            categoryId = categoryId,
            notes = row.optString("notes", "").takeIf { it.isNotBlank() },
            deduplicationMode = DeduplicationMode.STANDARD,
            idempotencyKey = row.optLong("id", i.toLong()).let { if (it > 0) "import:json:$it" else null }
        )
    }
}