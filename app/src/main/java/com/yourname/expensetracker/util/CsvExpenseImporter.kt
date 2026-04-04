package com.yourname.expensetracker.util

import android.content.Context
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Utility for importing expenses from CSV exported from old app versions.
 * 
 * CSV Format expected:
 * date,amount,merchant,category,description
 * 2024-01-15,25.50,Starbucks,Coffee,Morning coffee
 * 
 * Usage:
 * ```kotlin
 * val importer = CsvExpenseImporter(context)
 * importer.importFromUri(uri) { progress ->
 *     // Update UI with progress
 * }
 * ```
 */
class CsvExpenseImporter(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val categoryDao = AppDatabase.getInstance(context).categoryDao()
    private val expenseDao = AppDatabase.getInstance(context).expenseDao()

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
        val parts = line.split(",")
        if (parts.size < 4) return

        val dateStr = parts[0].trim()
        val amountStr = parts[1].trim()
        val merchant = parts[2].trim()
        val categoryName = parts[3].trim()
        val description = if (parts.size > 4) parts[4].trim() else ""

        // Parse date
        val date = try {
            dateFormat.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }

        // Parse amount
        val amount = amountStr.replace("€", "").replace("$", "").trim().toDoubleOrNull() 
            ?: return

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
            "#FFE53935", // Red
            "#FFD81B60", // Pink
            "#FF8E24AA", // Purple
            "#FF5E35B1", // Deep Purple
            "#FF3949AB", // Indigo
            "#FF1E88E5", // Blue
            "#FF039BE5", // Light Blue
            "#FF00ACC1", // Cyan
            "#FF00897B", // Teal
            "#FF43A047", // Green
            "#FF7CB342", // Light Green
            "#FFC0CA33", // Lime
            "#FFFDD835", // Yellow
            "#FFFFB300", // Amber
            "#FFFB8C00", // Orange
            "#FFF4511E"  // Deep Orange
        )
        
        val hash = name.hashCode()
        return colors[Math.abs(hash) % colors.size]
    }

    sealed class ImportResult {
        data class Success(val imported: Int, val errors: Int) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }
}

/**
 * Helper extension to get database instance
 */
fun AppDatabase.Companion.getInstance(context: Context): AppDatabase {
    return androidx.room.Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "expense_tracker_db"
    ).addMigrations(*AppDatabase.ALL_MIGRATIONS)
        .build()
}
