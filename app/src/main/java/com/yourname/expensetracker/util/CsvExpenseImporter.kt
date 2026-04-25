package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
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
 * Uses Hilt-provided DAOs so it shares the singleton [AppDatabase] instance
 * instead of creating a separate [Room.databaseBuilder] connection (B.4-10).
 */
class CsvExpenseImporter @Inject constructor(
    private val categoryDao: CategoryDao,
    private val expenseDao: ExpenseDao
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        isLenient = false
    }

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
                               lines[0].contains("amount", ignoreCase = true)) {
                lines.drop(1)
            } else {
                lines
            }

            var successCount = 0
            var errorCount = 0
            val total = dataLines.size

            dataLines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                
                try {
                    parseAndImportLine(line)
                    successCount++
                } catch (e: Exception) {
                    errorCount++
                    // Log error but continue
                    println("Failed to import line $index: ${e.message}")
                }
                
                onProgress(index + 1, total)
            }

            ImportResult.Success(successCount, errorCount)
        } catch (e: Exception) {
            ImportResult.Error("Import failed: ${e.message}")
        }
    }

    private suspend fun parseAndImportLine(line: String) {
        val parts = parseCsvLine(line)
        if (parts.size < 4) {
            throw IllegalArgumentException("Invalid CSV row: expected at least 4 columns")
        }

        val dateStr = parts[0].trim()
        val amountStr = parts[1].trim()
        val merchant = parts[2].trim()
        val categoryName = parts[3].trim()
        val description = if (parts.size > 4) parts[4].trim() else ""

        // Parse date
        val date = try {
            dateFormat.parse(dateStr)?.time
                ?: throw IllegalArgumentException("Invalid date: $dateStr")
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid date: $dateStr", e)
        }

        // Parse amount
        val amount = amountStr.replace("€", "").replace("$", "").trim().toDoubleOrNull() 
            ?: throw IllegalArgumentException("Invalid amount: $amountStr")

        // Get or create category
        val categoryId = getOrCreateCategory(categoryName)

        // Create expense
        val expense = Expense(
            amount = amount,
            merchant = merchant,
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = date,
            categoryId = categoryId,
            notes = description.ifEmpty { null }
        )

        expenseDao.insert(expense)
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
            icon = "📦",
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

    sealed class ImportResult {
        data class Success(val imported: Int, val errors: Int) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }
}
