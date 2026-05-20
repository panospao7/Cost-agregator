package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
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
    private val coordinator: TransactionLifecycleCoordinator,
    private val currencySettingsRepository: CurrencySettingsRepository
) {

    /**
     * Thread-safe date parser for CSV import using [DateTimeFormatter]
     * (immutable and thread-safe, unlike the legacy SimpleDateFormat).
     */
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Import expenses from raw CSV content.
     *
     * @param csvContent  The full CSV text (may include comment lines, a header row).
     * @param onProgress  Callback invoked per row with (currentIndex, totalRows).
     * @return [ImportResult] summarizing every row outcome.
     */
    suspend fun importFromContent(
        csvContent: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val allLines = csvContent.lines()
            if (allLines.isEmpty()) {
                return@withContext ImportResult.Error("Empty CSV file")
            }

            val headerIndex = allLines.indexOfFirst { line ->
                val t = line.trim()
                t.isNotEmpty() && !t.startsWith("#")
            }
            if (headerIndex == -1) {
                return@withContext ImportResult.Success(0, 0, 0, emptyList())
            }

            val headerParts = allLines[headerIndex].split(",").map { it.trim().lowercase() }
            val columnIndex: Map<String, Int> = headerParts.withIndex().associate { (i, col) -> col to i }

            val dataLines = allLines.drop(headerIndex + 1)
                .filter { line ->
                    val t = line.trim()
                    t.isNotEmpty() && !t.startsWith("#")
                }

            if (dataLines.isEmpty()) {
                return@withContext ImportResult.Success(0, 0, 0, emptyList())
            }

            var importedCount = 0
            var duplicateCount = 0
            var errorCount = 0
            val perRowResults = mutableListOf<RowResult>()
            val total = dataLines.size

            dataLines.forEachIndexed { index, line ->
                val rowResult = parseAndImportLine(line, columnIndex)
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
     * Parse a single CSV line using a column-index map built from the header row
     * and submit it through the lifecycle coordinator.
     *
     * @return [RowResult] indicating the outcome for this row.
     */
    private suspend fun parseAndImportLine(
        line: String,
        columnIndex: Map<String, Int>
    ): RowResult {
        return try {
            val parts = parseCsvLine(line)

            fun col(name: String): String? {
                val idx = columnIndex[name.lowercase()] ?: return null
                return if (idx < parts.size) parts[idx].trim().takeIf { it.isNotEmpty() } else null
            }

            val dateStr = col("date") ?: return RowResult.Failed("Missing date column")
            val amountStr = col("amount") ?: return RowResult.Failed("Missing amount column")
            val merchant = col("merchant") ?: return RowResult.Failed("Missing merchant column")
            val categoryName = col("category") ?: "Uncategorized"
            val notesFromCol = col("notes") ?: ""

            // Parse date
            val date = try {
                java.time.LocalDate.parse(dateStr, dateFormat)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (e: Exception) {
                return RowResult.Failed("Invalid date: $dateStr — ${e.message}")
            }

            // Parse amount and detect currency symbol
            val currencyFromSymbol = detectCurrencySymbol(amountStr)
            val amount = amountStr
                .replace("€", "")
                .replace("$", "")
                .replace("£", "")
                .replace("¥", "")
                .trim()
                .toDoubleOrNull()
                ?: return RowResult.Failed("Invalid amount: $amountStr")

            // Resolve currency: explicit Currency column, then symbol from amount, then home fallback
            val homeResolution = currencySettingsRepository.resolveHomeCurrency()
            val homeCurrency = homeResolution.currencyOrNull?.code ?: "EUR" // last resort for CSV import
            val resolvedCurrency = col("currency") ?: currencyFromSymbol ?: homeCurrency

            // Get or create category
            val categoryId = getOrCreateCategory(categoryName)

            // Build creation request — the coordinator handles dedupeKey,
            // merchantKey, dedup, and lifecycle events automatically.
            val request = CreateExpenseRequest(
                merchant = merchant,
                amount = amount,
                currency = resolvedCurrency,
                date = date,
                transactionType = TransactionType.PURCHASE,
                source = ExpenseSource.CSV_IMPORT,
                categoryId = categoryId,
                notes = notesFromCol.ifEmpty { null }
            )

            @Suppress("DEPRECATION_ERROR") // TODO: migrate to createExpenseStandalone()
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

    // TODO (T07): Apply hardened CSV cell sanitizer that neutralizes formula-leading
    // characters (=, +, -, @) per OWASP CSV Injection guidelines before parsing lines.
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

    /**
     * Detects a currency code from a currency symbol in the amount string.
     * Returns null if no known symbol is found.
     */
    private fun detectCurrencySymbol(amountStr: String): String? {
        return when {
            amountStr.contains("€") -> "EUR"
            amountStr.contains("$") -> "USD"
            amountStr.contains("£") -> "GBP"
            amountStr.contains("¥") -> "JPY"
            else -> null
        }
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
