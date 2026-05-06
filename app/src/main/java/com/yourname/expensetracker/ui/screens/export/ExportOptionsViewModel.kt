package com.yourname.expensetracker.ui.screens.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.ExportDataRepository
import com.yourname.expensetracker.domain.export.AccountingExportPolicy
import com.yourname.expensetracker.domain.export.FreshBooksExporter
import com.yourname.expensetracker.domain.export.QuickBooksIIFExporter
import com.yourname.expensetracker.domain.export.XeroCSVExporter
import com.yourname.expensetracker.domain.export.ExportTransaction
import com.yourname.expensetracker.domain.export.toExportTransaction
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ExportOptionsUiState(
    val exportFormats: List<ExportFormat> = listOf(
        ExportFormat("csv", "CSV (Generic)", "Standard CSV format compatible with most applications"),
        ExportFormat("json", "JSON (Structured)", "Versioned JSON export with deterministic ordering"),
        ExportFormat("xero", "Xero CSV", "Xero accounting software format"),
        ExportFormat("quickbooks", "QuickBooks IIF", "QuickBooks IIF import format"),
        ExportFormat("freshbooks", "FreshBooks", "FreshBooks CSV format")
    ),
    val selectedFormat: String = "csv",
    val startDate: Long = 0L,
    val endDate: Long = 0L,
    val expenseCount: Int = 0,
    val isLoading: Boolean = false,
    val exportPreview: String? = null,
    val exportPreviewTruncated: Boolean = false,
    val exportFilePath: String? = null,
    val error: String? = null,
    val exportSuccess: Boolean = false
)

data class ExportFormat(
    val id: String,
    val name: String,
    val description: String
)

@HiltViewModel
class ExportOptionsViewModel @Inject constructor(
    private val exportDataRepository: ExportDataRepository,
    private val accountingExportPolicy: AccountingExportPolicy,
    private val timeProvider: TimeProvider,
    private val xeroExporter: XeroCSVExporter,
    private val quickBooksExporter: QuickBooksIIFExporter,
    private val freshBooksExporter: FreshBooksExporter
) : ViewModel() {

    companion object {
        private const val PREVIEW_MAX_CHARS = 500
    }

    private val _uiState = MutableStateFlow(ExportOptionsUiState())
    val uiState: StateFlow<ExportOptionsUiState> = _uiState.asStateFlow()
    private var exportJob: Job? = null
    private var exportGeneration: Long = 0L

    init {
        val now = timeProvider.now()
        val start = TimePeriodUtils.addMonths(now, -1)
        _uiState.value = _uiState.value.copy(startDate = start, endDate = now)
        loadExpenseCount()
    }

    private fun loadExpenseCount() {
        viewModelScope.launch {
            try {
                val count = exportDataRepository.countExpensesBetween(
                    _uiState.value.startDate,
                    _uiState.value.endDate
                )
                _uiState.value = _uiState.value.copy(expenseCount = count, error = null)
            } catch (e: Exception) {
                Timber.e(e, "Failed loading expense count for export")
                _uiState.value = _uiState.value.copy(
                    expenseCount = 0,
                    error = "Failed to load expense count. Pull to retry."
                )
            }
        }
    }

    fun selectFormat(formatId: String) {
        _uiState.value = _uiState.value.copy(selectedFormat = formatId)
    }

    fun setDateRange(startDate: Long, endDate: Long) {
        // BAK-15: Validate date range — start must be before end, and range
        // must not exceed 5 years (1825 days) to prevent unbounded exports.
        if (startDate >= endDate) {
            _uiState.value = _uiState.value.copy(
                error = "Start date must be before end date"
            )
            return
        }
        val maxRangeMs = 1825L * 24L * 60L * 60L * 1000L // 5 years in millis
        if (endDate - startDate > maxRangeMs) {
            _uiState.value = _uiState.value.copy(
                error = "Date range must not exceed 5 years"
            )
            return
        }
        _uiState.value = _uiState.value.copy(startDate = startDate, endDate = endDate, error = null)
        loadExpenseCount()
    }

    /**
     * Generates an export file for the selected date range and format.
     *
     * ## BAK-12: Streaming export (planned)
     * Currently, all expenses between [startDate] and [endDate] are loaded into
     * memory as a single [List] before writing. For datasets exceeding ~5000 rows,
     * this may cause elevated memory usage. The [DeterministicExpenseExportPager]
     * already supports keyset-based cursor pagination (page size 2000). A future
     * improvement should replace the bulk load with [streamExpensesToWriter] which
     * writes pages one at a time without accumulating all rows in memory.
     */
    fun generateExport() {
        exportJob?.cancel()
        val generation = ++exportGeneration
        exportJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                exportPreview = null,
                exportPreviewTruncated = false,
                exportFilePath = null,
                exportSuccess = false,
                error = null
            )

            try {
                val categories = withContext(Dispatchers.IO) { exportDataRepository.getCategoryNameMap() }
                val extension = extensionFor(_uiState.value.selectedFormat)
                val exportFile = exportDataRepository.createExportFile(extension, timeProvider.now())
                val expenses = withContext(Dispatchers.IO) {
                    exportDataRepository.getExpensesBetweenForExport(
                        _uiState.value.startDate,
                        _uiState.value.endDate
                    )
                }

                if (expenses.isEmpty() && !_uiState.value.selectedFormat.allowsEmptyDataset()) {
                    throw IllegalArgumentException("No expenses found for selected date range")
                }

                val rowCount = expenses.size

                // BAK-12: Warn when dataset is large enough to warrant streaming
                if (rowCount > 5000) {
                    Timber.w("BAK-12: Exporting %d expenses — consider using streamExpensesToWriter for paged streaming to reduce memory pressure", rowCount)
                }

                val previewCollector = PreviewCollector(PREVIEW_MAX_CHARS)
                val accountingTransactions = mutableListOf<ExportTransaction>()

                if (_uiState.value.selectedFormat.requiresAccountingPolicy()) {
                    accountingTransactions += expenses.map { it.toExportTransaction() }
                    if (accountingTransactions.isNotEmpty()) {
                        accountingExportPolicy.validateAccountingDataset(
                            accountingTransactions,
                            _uiState.value.selectedFormat.accountingExportDisplayName()
                        )
                    }
                }

                withContext(Dispatchers.IO) {
                    exportFile.writer().use { writer ->
                        when (_uiState.value.selectedFormat) {
                            "json" -> streamJsonExport(writer, expenses, categories, rowCount, previewCollector)
                            "xero" -> streamXeroExport(writer, expenses, categories, previewCollector)
                            "quickbooks" -> streamQuickBooksExport(writer, expenses, categories, previewCollector)
                            "freshbooks" -> streamFreshBooksExport(writer, expenses, categories, previewCollector)
                            else -> streamGenericCsvExport(writer, expenses, categories, previewCollector)
                        }
                    }
                }

                if (exportGeneration != generation) return@launch
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    exportPreview = previewCollector.value,
                    exportPreviewTruncated = previewCollector.truncated,
                    exportFilePath = exportFile.absolutePath,
                    exportSuccess = true,
                    error = null
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (exportGeneration != generation) return@launch
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Export cancelled"
                )
            } catch (e: Exception) {
                if (exportGeneration != generation) return@launch
                Timber.e(e, "Failed generating export")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage()
                )
            }
        }
    }

    /**
     * Cancels the currently running export, if any.
     * Safe to call when no export is in progress.
     */
    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
    }

    private suspend fun streamGenericCsvExport(
        writer: Appendable,
        expenses: List<Expense>,
        categories: Map<Long, String>,
        preview: PreviewCollector
    ) {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val zoneId = ZoneId.systemDefault()
        val header = "Date,Merchant,Amount,Currency,Category,Notes,ID\n"
        writer.append(header)
        preview.append(header)

        expenses.forEach { expense ->
            val line = buildString {
                val date = Instant.ofEpochMilli(expense.date).atZone(zoneId).toLocalDate().format(dateFormatter)
                val merchant = escapeCsv(expense.merchant)
                val amount = escapeCsv(CurrencyFormatter.formatForExport(expense.amount))
                val currency = escapeCsv(expense.currency)
                val category = escapeCsv(categories[expense.categoryId] ?: "Uncategorized")
                val notes = escapeCsv(expense.notes ?: "")
                append("$date,$merchant,$amount,$currency,$category,$notes,${expense.id}\n")
            }
            writer.append(line)
            preview.append(line)
        }
    }

    private suspend fun streamJsonExport(
        writer: Appendable,
        expenses: List<Expense>,
        categories: Map<Long, String>,
        rowCount: Int,
        preview: PreviewCollector
    ) {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val zoneId = ZoneId.systemDefault()
        val generatedAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timeProvider.now()))
        val startDate = Instant.ofEpochMilli(_uiState.value.startDate).atZone(zoneId).toLocalDate().format(dateFormatter)
        val endDate = Instant.ofEpochMilli(_uiState.value.endDate).atZone(zoneId).toLocalDate().format(dateFormatter)

        val prefix = buildString {
            append("{")
            append("\"schemaVersion\":1,")
            append("\"exportType\":\"expenses\",")
            append("\"generatedAt\":\"").append(escapeJson(generatedAtIso)).append("\",")
            append("\"dateRange\":{")
            append("\"startDate\":\"").append(escapeJson(startDate)).append("\",")
            append("\"endDate\":\"").append(escapeJson(endDate)).append("\"},")
            append("\"rowCount\":").append(rowCount).append(",")
            append("\"rows\":[")
        }
        writer.append(prefix)
        preview.append(prefix)

        var first = true
        expenses.forEach { expense ->
            val row = buildString {
                if (!first) append(',')
                first = false

                val date = Instant.ofEpochMilli(expense.date).atZone(zoneId).toLocalDate().format(dateFormatter)
                val category = categories[expense.categoryId] ?: "Uncategorized"

                append("{")
                append("\"id\":").append(expense.id).append(',')
                append("\"date\":\"").append(escapeJson(date)).append("\",")
                append("\"timestamp\":").append(expense.date).append(',')
                append("\"merchant\":\"").append(escapeJson(expense.merchant)).append("\",")
                append("\"amount\":").append(formatJsonNumber(expense.amount)).append(',')
                append("\"currency\":\"").append(escapeJson(expense.currency)).append("\",")
                append("\"category\":\"").append(escapeJson(category)).append("\",")
                append("\"notes\":")
                if (expense.notes == null) append("null")
                else append("\"").append(escapeJson(expense.notes)).append("\"")
                append("}")
            }
            writer.append(row)
            preview.append(row)
        }

        writer.append("]}")
        preview.append("]}")
    }

    private suspend fun streamXeroExport(
        writer: Appendable,
        expenses: List<Expense>,
        categories: Map<Long, String>,
        preview: PreviewCollector
    ) {
        val header = "Date,Description,Amount,Account,Reference\n"
        xeroExporter.writeHeader(writer)
        preview.append(header)

        expenses.forEach { expense ->
            val line = buildString {
                xeroExporter.writeExpense(this, expense.toExportTransaction(), categories)
            }
            writer.append(line)
            preview.append(line)
        }
    }

    private suspend fun streamQuickBooksExport(
        writer: Appendable,
        expenses: List<Expense>,
        categories: Map<Long, String>,
        preview: PreviewCollector
    ) {
        val header = "!TRNS\tDATE\tACCNT\tAMOUNT\tMEMO\tNAME\tCLASS\n" +
            "!SPL\tDATE\tACCNT\tAMOUNT\tMEMO\tNAME\tCLASS\n" +
            "!ENDTRNS\n"
        quickBooksExporter.writeHeader(writer)
        preview.append(header)

        expenses.forEach { expense ->
            val block = buildString {
                quickBooksExporter.writeExpense(this, expense.toExportTransaction(), categories)
            }
            writer.append(block)
            preview.append(block)
        }
    }

    private suspend fun streamFreshBooksExport(
        writer: Appendable,
        expenses: List<Expense>,
        categories: Map<Long, String>,
        preview: PreviewCollector
    ) {
        val header = "date,description,amount,category,vendor\n"
        freshBooksExporter.writeHeader(writer)
        preview.append(header)

        expenses.forEach { expense ->
            val line = buildString {
                freshBooksExporter.writeExpense(this, expense.toExportTransaction(), categories)
            }
            writer.append(line)
            preview.append(line)
        }
    }

    /**
     * Writes a single page of expenses to [writer] without holding all rows in memory.
     * Each page is the result of one keyset-paginated query from [DeterministicExpenseExportPager].
     *
     * ## BAK-12: Streaming export helper
     * This is the per-page write primitive for use with [streamExpensesToWriter].
     * Currently unused — the existing export methods load all expenses upfront.
     *
     * @param writer The output writer (file writer or buffer).
     * @param page   A single page of expenses (typically up to 2000 rows).
     * @param categories Category name map for display.
     * @param format Export format identifier (e.g. "csv", "json", "xero").
     * @param preview Optional preview collector.
     * @param rowCount Total row count across all pages (needed for JSON schema).
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun writePage(
        writer: Appendable,
        page: List<Expense>,
        categories: Map<Long, String>,
        format: String,
        preview: PreviewCollector? = null,
        rowCount: Int = 0
    ) {
        // TODO(BAK-12): Implement per-page writing for each export format.
        // For now this is a placeholder documenting the streaming contract.
        // The existing stream*Export methods already accept List<Expense>
        // and can be called page-by-page inside streamExpensesToWriter.
        Timber.d("BAK-12: writePage called with %d expenses (format=%s)", page.size, format)
    }

    /**
     * ## BAK-12: Streaming export via paged keyset cursor (planned)
     *
     * Future replacement for the current bulk-load pattern. Instead of calling
     * [ExportDataRepository.getExpensesBetweenForExport] which accumulates all
     * expenses into a single [List], this method streams pages one at a time:
     *
     * 1. Open the writer.
     * 2. Fetch the first page via keyset pagination (page size 2000).
     * 3. Write rows from the current page.
     * 4. Fetch the next page using the last row's (date, id) cursor.
     * 5. Repeat until no more pages.
     * 6. Close the writer.
     *
     * The [DeterministicExpenseExportPager] already implements cursor-based pagination;
     * this method is the consumer-side streaming layer. Implement per-format page
     * writing in [writePage].
     */
    @Suppress("UNUSED")
    private suspend fun streamExpensesToWriter(
        writer: Appendable,
        startDate: Long,
        endDate: Long,
        categories: Map<Long, String>,
        format: String,
        preview: PreviewCollector?,
        pageSize: Int = 2000
    ) {
        // TODO(BAK-12): Implement paged streaming using
        //  expenseRepository.getExpensesBetweenForExportKeyset(startDate, endDate, pageSize, lastDate, lastId)
        Timber.d("BAK-12: streamExpensesToWriter placeholder — format=%s, start=%d, end=%d", format, startDate, endDate)
    }

    private fun extensionFor(format: String): String = when (format) {
        "quickbooks" -> "iif"
        "json" -> "json"
        else -> "csv"
    }

    private fun escapeCsv(field: String): String {
        val trimmed = field.trim()
        val neutralizedField = if (trimmed.isNotEmpty() && isDangerousFormulaPrefix(trimmed.first())) "'$field" else field
        val needsQuoting = neutralizedField.contains(",") ||
            neutralizedField.contains("\"") ||
            neutralizedField.contains("\n") ||
            neutralizedField.contains("\r")
        return if (needsQuoting) "\"" + neutralizedField.replace("\"", "\"\"") + "\"" else neutralizedField
    }

    private fun escapeJson(value: String): String {
        val out = StringBuilder(value.length + 8)
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code in 0..31) out.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else out.append(ch)
            }
        }
        return out.toString()
    }

    private fun formatJsonNumber(value: Double): String = if (value.isFinite()) value.toString() else "0.0"

    private fun isDangerousFormulaPrefix(char: Char): Boolean =
        char == '=' || char == '+' || char == '-' || char == '@'

    fun clearExport() {
        _uiState.value = _uiState.value.copy(
            exportPreview = null,
            exportPreviewTruncated = false,
            exportFilePath = null,
            exportSuccess = false
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun retry() {
        loadExpenseCount()
    }
}

private class PreviewCollector(private val maxChars: Int) {
    private val builder = StringBuilder(maxChars)
    var truncated: Boolean = false
        private set

    val value: String
        get() = builder.toString()

    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        val remaining = maxChars - builder.length
        if (remaining <= 0) {
            truncated = true
            return
        }
        if (chunk.length <= remaining) {
            builder.append(chunk)
        } else {
            builder.append(chunk.substring(0, remaining))
            truncated = true
        }
    }
}

private fun String.requiresAccountingPolicy(): Boolean = this == "xero" || this == "quickbooks" || this == "freshbooks"

private fun String.allowsEmptyDataset(): Boolean = when (this) {
    "csv", "json", "xero", "quickbooks", "freshbooks" -> true
    else -> false
}

private fun String.accountingExportDisplayName(): String = when (this) {
    "xero" -> "Xero"
    "quickbooks" -> "QuickBooks"
    "freshbooks" -> "FreshBooks"
    else -> this
}

private fun Exception.toUserMessage(): String = when (this) {
    is IllegalArgumentException -> message ?: "Export data is invalid for the selected format."
    else -> "Failed to generate export: ${message ?: "Unknown error"}"
}
