package com.yourname.expensetracker.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.yourname.expensetracker.domain.export.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
    private val deterministicExpenseExportPager: DeterministicExpenseExportPager,
    private val accountingExportPolicy: AccountingExportPolicy,
    private val quickBooksExporter: QuickBooksIIFExporter,
    private val xeroExporter: XeroCSVExporter,
    private val freshBooksExporter: FreshBooksExporter,
    private val accountantReportPdfExporter: AccountantReportPdfExporter
) {
    suspend fun exportExpenses(
        context: Context,
        startDate: Long,
        endDate: Long,
        format: ExportFormat
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            // A.9 Batch 6: fetch via deterministic exhaustive paging so that
            // exports are never silently truncated and row ordering is stable
            // (date ASC, id ASC, merchant COLLATE NOCASE ASC).
            val expenses = deterministicExpenseExportPager.fetchAllBetween(startDate, endDate)
            
            if (expenses.isEmpty() && !format.allowsEmptyDataset()) {
                return@withContext ExportResult(
                    success = false,
                    errorMessage = "No expenses found for selected date range"
                )
            }

            val exportTransactions = if (format.requiresAccountingPolicy()) {
                expenses.map { it.toExportTransaction() }
            } else {
                emptyList()
            }

            if (format.requiresAccountingPolicy() && exportTransactions.isNotEmpty()) {
                accountingExportPolicy.validateAccountingDataset(
                    exportTransactions,
                    format.displayName()
                )
            }

            val categories = categoryRepository.allCategories.first()
                .associateBy({ it.id }, { it.name })

            // Generate export
            val timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
            )
            val fileName = when (format) {
                ExportFormat.QUICKBOOKS_IIF -> "expenses_quickbooks_$timestamp.iif"
                ExportFormat.XERO_CSV -> "expenses_xero_$timestamp.csv"
                ExportFormat.FRESHBOOKS_CSV -> "expenses_freshbooks_$timestamp.csv"
                ExportFormat.ACCOUNTANT_REPORT_PDF -> "accountant_report_$timestamp.pdf"
            }

            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val exportFile = File(exportDir, fileName)

            when (format) {
                ExportFormat.QUICKBOOKS_IIF -> FileWriter(exportFile).use { writer ->
                    writer.write(quickBooksExporter.export(exportTransactions, categories))
                }
                ExportFormat.XERO_CSV -> FileWriter(exportFile).use { writer ->
                    writer.write(xeroExporter.export(exportTransactions, categories))
                }
                ExportFormat.FRESHBOOKS_CSV -> FileWriter(exportFile).use { writer ->
                    writer.write(freshBooksExporter.export(exportTransactions, categories))
                }
                ExportFormat.ACCOUNTANT_REPORT_PDF -> exportFile.outputStream().use { output ->
                    output.write(
                        accountantReportPdfExporter.export(
                            expenses = expenses,
                            categories = categories,
                            startDate = startDate,
                            endDate = endDate
                        )
                    )
                }
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
}

private fun ExportFormat.requiresAccountingPolicy(): Boolean = when (this) {
    ExportFormat.QUICKBOOKS_IIF,
    ExportFormat.XERO_CSV,
    ExportFormat.FRESHBOOKS_CSV -> true
    ExportFormat.ACCOUNTANT_REPORT_PDF -> false
}

private fun ExportFormat.allowsEmptyDataset(): Boolean = when (this) {
    ExportFormat.QUICKBOOKS_IIF,
    ExportFormat.XERO_CSV,
    ExportFormat.FRESHBOOKS_CSV -> true
    ExportFormat.ACCOUNTANT_REPORT_PDF -> false
}

private fun ExportFormat.displayName(): String = when (this) {
    ExportFormat.QUICKBOOKS_IIF -> "QuickBooks"
    ExportFormat.XERO_CSV -> "Xero"
    ExportFormat.FRESHBOOKS_CSV -> "FreshBooks"
    ExportFormat.ACCOUNTANT_REPORT_PDF -> "Accountant report"
}
