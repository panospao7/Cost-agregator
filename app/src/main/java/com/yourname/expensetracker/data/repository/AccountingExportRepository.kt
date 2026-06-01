package com.yourname.expensetracker.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseReadPolicy
import com.yourname.expensetracker.domain.export.*
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import timber.log.Timber
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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
    private val readBarrier: DatabaseReadBarrier,
    private val quickBooksExporter: QuickBooksIIFExporter,
    private val xeroExporter: XeroCSVExporter,
    private val freshBooksExporter: FreshBooksExporter,
    private val accountantReportPdfExporter: AccountantReportPdfExporter,
    private val timeProvider: TimeProvider
) {
    companion object {
        /** Maximum allowed export date range in days (10 years). */
        private const val MAX_EXPORT_RANGE_DAYS: Long = 3650
        /** Maximum allowed export date range in milliseconds. */
        private const val MAX_EXPORT_RANGE_MS: Long = MAX_EXPORT_RANGE_DAYS * 24L * 60L * 60L * 1000L
    }

    /**
     * Exports expenses within the given date range in the specified format.
     *
     * @param context Android context for file I/O.
     * @param startDate Start of date range (epoch ms, must be > 0 and < endDate).
     * @param endDate End of date range (epoch ms, must be > startDate).
     * @param format Export format (QuickBooks, Xero, FreshBooks, PDF).
     * @throws IllegalArgumentException if date range is invalid.
     */
    suspend fun exportExpenses(
        context: Context,
        startDate: Long,
        endDate: Long,
        format: ExportFormat
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            readBarrier.checkReadAllowed(
                DatabaseAccessOperation("AccountingExportRepository.exportExpenses", pipeline = "P12"),
                DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
            )
            // BAK-15: Validate date range — reject non-positive, zero-width, or inverted ranges
            require(startDate > 0L) { "startDate must be positive" }
            require(endDate > startDate) { "endDate must be after startDate" }
            require(endDate - startDate <= MAX_EXPORT_RANGE_MS) { "Date range exceeds maximum ($MAX_EXPORT_RANGE_DAYS days)" }

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
                val totalCount = expenses.size
                if (totalCount > AccountingExportPolicy.DEFAULT_MAX_VALIDATION_ROWS) {
                    Timber.w(
                        "Accounting export dataset size (%d) exceeds validation limit (%d); " +
                            "validating first %d rows only",
                        totalCount, AccountingExportPolicy.DEFAULT_MAX_VALIDATION_ROWS,
                        AccountingExportPolicy.DEFAULT_MAX_VALIDATION_ROWS
                    )
                }
                accountingExportPolicy.validateAccountingDataset(
                    exportTransactions,
                    format.displayName(),
                    maxValidationRows = AccountingExportPolicy.DEFAULT_MAX_VALIDATION_ROWS
                )
            }

            val categories = categoryRepository.allCategories.first()
                .associateBy({ it.id }, { it.name })

            // Generate export
            val timestamp = Instant.ofEpochMilli(timeProvider.now())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US))
            val fileName = when (format) {
                ExportFormat.QUICKBOOKS_IIF -> "expenses_quickbooks_$timestamp.iif"
                ExportFormat.XERO_CSV -> "expenses_xero_$timestamp.csv"
                ExportFormat.FRESHBOOKS_CSV -> "expenses_freshbooks_$timestamp.csv"
                ExportFormat.ACCOUNTANT_REPORT_PDF -> "accountant_report_$timestamp.pdf"
            }

            val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
            // SR-2: Atomic export via temp file + rename
            // Write to a temporary file first, then atomically rename to the
            // final path. This prevents partial/corrupted export files when
            // the process is interrupted mid-write.
            val exportFile = File(exportDir, fileName)
            val tempFile = File(exportDir, "${fileName}.tmp_${timeProvider.now()}")

            try {
                when (format) {
                    ExportFormat.QUICKBOOKS_IIF -> FileWriter(tempFile).use { writer ->
                        writer.write(quickBooksExporter.export(exportTransactions, categories))
                    }
                    ExportFormat.XERO_CSV -> FileWriter(tempFile).use { writer ->
                        writer.write(xeroExporter.export(exportTransactions, categories))
                    }
                    ExportFormat.FRESHBOOKS_CSV -> FileWriter(tempFile).use { writer ->
                        writer.write(freshBooksExporter.export(exportTransactions, categories))
                    }
                    ExportFormat.ACCOUNTANT_REPORT_PDF -> tempFile.outputStream().use { output ->
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

                // Atomic rename — on most filesystems this is an atomic metadata
                // operation when source and target are on the same volume.
                if (!tempFile.renameTo(exportFile)) {
                    // Rename failed (e.g. cross-volume); fall back to move/copy
                    tempFile.copyTo(exportFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (e: Exception) {
                tempFile.delete() // Clean up temp file on failure
                throw e
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
