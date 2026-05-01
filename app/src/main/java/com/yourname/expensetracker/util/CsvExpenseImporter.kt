package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/**
 * Utility for importing expenses from CSV exported from old app versions.
 *
 * CSV Format expected:
 * date,amount,merchant,category,description
 * 2024-01-15,25.50,Starbucks,Coffee,Morning coffee
 *
 * Uses Hilt-provided [TransactionLifecycleCoordinator] so every imported row
 * goes through the full lifecycle: validate → normalize → dedupe → insert
 * atomic → event logging.
 *
 * Uses [CategoryDao] for category lookup/creation (shared singleton instance).
 */
class CsvExpenseImporter @Inject constructor(
    private val categoryDao: CategoryDao,
    private val coordinator: TransactionLifecycleCoordinator
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        isLenient = false
    }

    /**
     * Import expenses from raw CSV content.
     *
     * @param csvContent  The full CSV text (may include a header row).
     * @param onProgress  Callback invoked per row with (currentIndex, totalRows).
     * @return [ImportResult] summarizing every row outcome.
     */
    suspend fun importFromContent(
        csvContent: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val lines = csvContent.lines()
            if (lines.isEmpty()) {
                return@withContext ImportResult.Error("Empty CSV file")
            }

            // Skip header if present
            val dataLines = if (lines[0].contains("date", ignoreCase = true) ||
                lines[0].contains("amount", ignoreCase = true)
            ) {
                lines.drop(1)
            } else {
                lines
            }

            var importedCount = 0
            var duplicateCount = 0
            var errorCount = 0
            val perRowResults = mutableListOf<RowResult>()
            val total = dataLines.size

            dataLines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed

                val rowResult = parseAndImportLine(line)
                perRowResults.add(rowResult)

                when (rowResult) {
                    is RowResult.Imported -> importedCount++
                    is RowResult.Duplicate -> duplicateCount++
                    is RowResult.Failed -> errorCount++
                }

                onProgress(index + 1, total)
            }

            ImportResult.Success(
                imported = importedCount,
                duplicates = duplicateCount,
                errors = errorCount,
                perRowResults = perRowResults
            )
        } catch (e: Exception) {
            ImportResult.Error("Import failed: ${e.message}")
        }
    }

    /**
     * Parse a single CSV line and submit it through the lifecycle coordinator.
     *
     * @return [RowResult] indicating the outcome for this row.
     */
    private suspend fun parseAndImportLine(line: String): RowResult {
        return try {
            val parts = parseCsvLine(line)
            if (parts.size < 4) {
                return RowResult.Failed("Invalid CSV row: expected at least 4 columns")
            }

            val dateStr = parts[0].trim()
            val amountStr = parts[1].trim()
            val merchant = parts[2].trim()
            val categoryName = parts[3].trim()
            val description = if (parts.size > 4) parts[4].trim() else ""

            // Parse date
            val date = try {
                dateFormat.parse(dateStr)?.time
                    ?: return RowResult.Failed("Invalid date: $dateStr")
            } catch (e: Exception) {
                return RowResult.Failed("Invalid date: $dateStr — ${e.message}")
            }

            // Parse amount
            val amount = amountStr
                .replace("€", "")
                .replace("$", "")
                .trim()
                .toDoubleOrNull()
                ?: return RowResult.Failed("Invalid amount: $amountStr")

            // Get or create category
            val categoryId = getOrCreateCategory(categoryName)

            // Build creation request — the coordinator handles dedupeKey,
            // merchantKey, dedup, and lifecycle events automatically.
            val request = CreateExpenseRequest(
                merchant = merchant,
                amount = amount,
                currency = "EUR",
                date = date,
                transactionType = TransactionType.PURCHASE,
                source = ExpenseSource.CSV_IMPORT,
                categoryId = categoryId,
                notes = description.ifEmpty { null }
            )

            when (val result = coordinator.createExpense(request)) {
                is CreateExpenseResult.Created ->
                    RowResult.Imported(result.expenseId)

                is CreateExpenseResult.DuplicateSkipped ->
                    RowResult.Duplicate(result.reason)

                is CreateExpenseResult.ValidationFailed ->
                    RowResult.Failed("Validation: ${result.errors.joinToString(", ")}")

                is CreateExpenseResult.InsertConflict ->
                    RowResult.Failed("Insert conflict: dedupeKey=${result.dedupeKey}")

                is CreateExpenseResult.Error ->
                    RowResult.Failed(result.exception.message ?: "Unknown error")
            }
        } catch (e: Exception) {
            RowResult.Failed(e.message ?: "Unknown error")
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index += 2
                    continue
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
            index++
        }

        if (inQuotes) {
            throw IllegalArgumentException("Invalid CSV row: unclosed quote")
        }

        fields += current.toString()
        return fields
    }

    private suspend fun getOrCreateCategory(name: String): Long {
        // Check if category exists
        val existing = categoryDao.getByName(name)
        if (existing != null) {
            return existing.id
        }

        // Create new category
        val category = Category(
            name = name,
            icon = "\uD83D\uDCE6",
            color = generateColorForCategory(name)
        )
        return categoryDao.insert(category)
    }

    private fun generateColorForCategory(name: String): String {
        // Simple hash-based color generation - returns hex string
        val colors = listOf(
            "#E53935", // Red
            "#D81B60", // Pink
            "#8E24AA", // Purple
            "#5E35B1", // Deep Purple
            "#3949AB", // Indigo
            "#1E88E5", // Blue
            "#039BE5", // Light Blue
            "#00ACC1", // Cyan
            "#00897B", // Teal
            "#43A047", // Green
            "#7CB342", // Light Green
            "#C0CA33", // Lime
            "#FDD835", // Yellow
            "#FFB300", // Amber
            "#FB8C00", // Orange
            "#F4511E"  // Deep Orange
        )

        val hash = name.hashCode()
        return colors[Math.abs(hash) % colors.size]
    }

    /**
     * Outcome of a full CSV import operation.
     */
    sealed class ImportResult {
        /**
         * All rows were processed (some may have failed or been skipped).
         *
         * @param imported      Number of rows that created a new expense.
         * @param duplicates    Number of rows detected as duplicates and skipped.
         * @param errors        Number of rows that failed to parse or import.
         * @param perRowResults Detailed result for every processed row.
         */
        data class Success(
            val imported: Int,
            val duplicates: Int,
            val errors: Int,
            val perRowResults: List<RowResult>
        ) : ImportResult()

        /**
         * The entire import operation failed before any row was processed.
         */
        data class Error(val message: String) : ImportResult()
    }

    /**
     * Outcome for a single CSV row.
     */
    sealed class RowResult {
        /** Expense was successfully created with the given database ID. */
        data class Imported(val expenseId: Long) : RowResult()

        /** Row matched an existing expense and was skipped. */
        data class Duplicate(val reason: String) : RowResult()

        /** Row could not be imported. */
        data class Failed(val error: String) : RowResult()
    }
}
