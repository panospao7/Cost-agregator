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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        _uiState.value = _uiState.value.copy(startDate = startDate, endDate = endDate)
        loadExpenseCount()
    }

    fun generateExport() {
        viewModelScope.launch {
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

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    exportPreview = previewCollector.value,
                    exportPreviewTruncated = previewCollector.truncated,
                    exportFilePath = exportFile.absolutePath,
                    exportSuccess = true,
                    error = null
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed generating export")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage()
                )
            }
        }
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
