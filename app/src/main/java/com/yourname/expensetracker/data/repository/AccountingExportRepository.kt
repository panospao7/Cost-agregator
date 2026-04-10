package com.yourname.expensetracker.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.export.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

enum class ExportFormat {
    QUICKBOOKS_IIF,
    XERO_CSV,
    FRESHBOOKS_CSV,
    ACCOUNTANT_REPORT_PDF
}

data class ExportResult(
    val success: Boolean,
    val fileUri: Uri? = null,
    val filePath: String? = null,
    val errorMessage: String? = null,
    val recordCount: Int = 0
)

@Singleton
class AccountingExportRepository @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
    private val quickBooksExporter: QuickBooksIIFExporter,
    private val xeroExporter: XeroCSVExporter,
    private val freshBooksExporter: FreshBooksExporter
) {
    /**
     * Page size used by the exhaustive-paging loop in [fetchAllForExport].
     * Visible for testing so that unit tests can verify the paging contract
     * without having to generate thousands of rows.
     */
    internal companion object {
        const val EXPORT_PAGE_SIZE = 2000
    }

    suspend fun exportExpenses(
        context: Context,
        startDate: Long,
        endDate: Long,
        format: ExportFormat,
        includeReceipts: Boolean = false
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            // A.9 Batch 6: fetch via deterministic exhaustive paging so that
            // exports are never silently truncated and row ordering is stable
            // (date ASC, id ASC, merchant COLLATE NOCASE ASC).
            val expenses = fetchAllForExport(startDate, endDate)
            
            if (expenses.isEmpty()) {
                return@withContext ExportResult(
                    success = false,
                    errorMessage = "No expenses found for selected date range"
                )
            }

            val categories = categoryRepository.allCategories.first()
                .associateBy({ it.id }, { it.name })

            // Generate export
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = when (format) {
                ExportFormat.QUICKBOOKS_IIF -> "expenses_quickbooks_$timestamp.iif"
                ExportFormat.XERO_CSV -> "expenses_xero_$timestamp.csv"
                ExportFormat.FRESHBOOKS_CSV -> "expenses_freshbooks_$timestamp.csv"
                ExportFormat.ACCOUNTANT_REPORT_PDF -> "accountant_report_$timestamp.txt"
            }

            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val exportFile = File(exportDir, fileName)

            val content = when (format) {
                ExportFormat.QUICKBOOKS_IIF -> quickBooksExporter.export(expenses.map { it.toExportTransaction() }, categories)
                ExportFormat.XERO_CSV -> xeroExporter.export(expenses.map { it.toExportTransaction() }, categories)
                ExportFormat.FRESHBOOKS_CSV -> freshBooksExporter.export(expenses.map { it.toExportTransaction() }, categories)
                ExportFormat.ACCOUNTANT_REPORT_PDF -> {
                    generateSimpleReport(expenses, categories, startDate, endDate)
                }
            }

            FileWriter(exportFile).use { writer ->
                writer.write(content)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportFile
            )

            ExportResult(
                success = true,
                fileUri = uri,
                filePath = exportFile.absolutePath,
                recordCount = expenses.size
            )
        } catch (e: Exception) {
            ExportResult(
                success = false,
                errorMessage = e.message ?: "Unknown error during export"
            )
        }
    }

    /**
     * Fetches **all** expenses in the date range using deterministic exhaustive
     * paging via [ExpenseRepository.getExpensesBetweenPagedForDeterministicExport].
     *
     * The underlying DAO query orders by `date ASC, id ASC, merchant COLLATE
     * NOCASE ASC` which gives a stable, deterministic row order suitable for
     * accounting exports.  Pages of [EXPORT_PAGE_SIZE] are fetched in a loop
     * until a page returns fewer rows than the page size, guaranteeing that
     * every matching row is included regardless of dataset size.
     */
    @VisibleForTesting
    internal suspend fun fetchAllForExport(startDate: Long, endDate: Long): List<Expense> {
        val result = mutableListOf<Expense>()
        var offset = 0
        while (true) {
            val page = expenseRepository.getExpensesBetweenPagedForDeterministicExport(
                startDate, endDate, EXPORT_PAGE_SIZE, offset
            )
            result.addAll(page)
            if (page.size < EXPORT_PAGE_SIZE) break
            offset += EXPORT_PAGE_SIZE
        }
        return result
    }

    private fun generateSimpleReport(
        expenses: List<Expense>,
        categories: Map<Long, String>,
        startDate: Long,
        endDate: Long
    ): String {
        val dateFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        val period = "${dateFormat.format(Date(startDate))} - ${dateFormat.format(Date(endDate))}"
        
        // Use effectiveAmount (ownership-adjusted) for all report totals so that shared and
        // "not-mine" rows do not overstate the user's actual spend.
        var totalExpenses = 0.0
        expenses.forEach { totalExpenses += it.effectiveAmount }
        
        // Group by category manually
        val expensesByCategory = mutableMapOf<Long?, MutableList<Expense>>()
        expenses.forEach { expense ->
            val list = expensesByCategory.getOrPut(expense.categoryId) { mutableListOf() }
            list.add(expense)
        }
        
        return buildString {
            append("ACCOUNTANT REPORT\n")
            append("Period: $period\n")
            append("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}\n")
            append("\n")
            
            append("=== SUMMARY ===\n")
            append("Total Expenses: €${String.format("%.2f", totalExpenses)}\n")
            append("Transaction Count: ${expenses.size}\n")
            append("\n")
            
            append("=== CATEGORY BREAKDOWN ===\n")
            expensesByCategory.toList()
                .sortedByDescending { (_, expList) -> 
                    var sum = 0.0
                    // Use effectiveAmount for category sort order too.
                    expList.forEach { sum += it.effectiveAmount }
                    sum
                }
                .forEach { (categoryId, expList) ->
                    val categoryName = categories[categoryId] ?: "Uncategorized"
                    var categoryTotal = 0.0
                    expList.forEach { categoryTotal += it.effectiveAmount }
                    val percentage = if (totalExpenses > 0) (categoryTotal / totalExpenses * 100) else 0.0
                    append("$categoryName: €${String.format("%.2f", categoryTotal)} (${expList.size} transactions, ${String.format("%.1f", percentage)}%)\n")
                }
            append("\n")
            
            // Large transactions — threshold checked against effectiveAmount so that a shared
            // row for which the user owes only €50 of a €600 bill is not flagged as large.
            val largeExpenses = expenses.filter { it.effectiveAmount > 500 }
            if (largeExpenses.isNotEmpty()) {
                append("=== LARGE TRANSACTIONS (REVIEW) ===\n")
                largeExpenses.forEach { expense ->
                    append("- ${expense.merchant}: €${String.format("%.2f", expense.effectiveAmount)} on ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(expense.date))}\n")
                }
            }
        }
    }
}

/**
 * Maps an [Expense] to an [ExportTransaction] for accounting exporters.
 * Uses [Expense.effectiveAmount] (ownership-adjusted) so that shared and "not-mine" rows
 * do not overstate the user's actual spend in the exported payload.
 */
private fun Expense.toExportTransaction(): ExportTransaction {
    return ExportTransaction(
        id = id,
        date = date,
        amount = effectiveAmount,
        merchant = merchant,
        notes = notes,
        categoryId = categoryId
    )
}
