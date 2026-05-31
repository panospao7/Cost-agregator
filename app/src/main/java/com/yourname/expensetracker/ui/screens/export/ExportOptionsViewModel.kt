package com.yourname.expensetracker.ui.screens.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.EntitySourceLink
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.ExportDataRepository
import com.yourname.expensetracker.domain.export.AccountingExportPolicy
import com.yourname.expensetracker.domain.export.ExpenseExportMapper
import com.yourname.expensetracker.domain.export.FreshBooksExporter
import com.yourname.expensetracker.domain.export.QuickBooksIIFExporter
import com.yourname.expensetracker.domain.export.XeroCSVExporter
import com.yourname.expensetracker.domain.export.ExportTransaction
import com.yourname.expensetracker.domain.export.toExportTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseReadPolicy
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
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
    private val freshBooksExporter: FreshBooksExporter,
    private val readBarrier: DatabaseReadBarrier,
    private val privacyGate: PrivacyGate,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
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
     * ## BAK-12: Streaming export via keyset pagination
     * Expenses are streamed page-by-page using [DeterministicExpenseExportPager]'s
     * keyset-based cursor pagination. Each page (up to 2000 rows) is written to
     * the output file immediately, so memory usage stays O(pageSize) rather than
     * O(totalExpenses). The cursor (date, id) of the last row on each page drives
     * the next query.
     *
     * ## P12-REG-01: Correct privacy capability
     * Ordinary expense export is **not** a raw database backup. It requests
     * [PrivacyCapability.EXPENSE_EXPORT] (allowed by [com.yourname.expensetracker.domain.privacy.ExportPrivacyGate]);
     * encrypted export requests [PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED].
     * Using `RAWBACKUP_EXPORT` here made the gate deny every normal export.
     *
     * ## P12-NEW-01: Fail-closed encryption
     * @param encryptExport When true, the output is encrypted via
     *   [ExportDataRepository.encryptExportFile]. There is **no** default/constant
     *   passphrase: if [encryptExport] is true, [passphrase] MUST be a non-blank
     *   user-supplied secret, otherwise the export fails closed. The plaintext is
     *   encrypted in the app-private directory and the plaintext file is always
     *   deleted (success or failure) so no cleartext financial data is left behind.
     * @param passphrase User-supplied encryption secret. Required when
     *   [encryptExport] is true; ignored otherwise.
     */
    fun generateExport(encryptExport: Boolean = false, passphrase: String? = null) {
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

            // P12-NEW-01: Fail closed BEFORE doing any work if encryption is
            // requested without a real passphrase. Never fall back to a constant.
            if (encryptExport && passphrase.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Encrypted export requires a passphrase"
                )
                return@launch
            }

            // P12-REG-01: Use the dedicated expense-export capability — ordinary
            // export is NOT a raw database backup. Encrypted export uses the
            // encrypted-export capability.
            val exportCapability = if (encryptExport) {
                PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED
            } else {
                PrivacyCapability.EXPENSE_EXPORT
            }
            val privacyDecision = privacyGate.check(
                exportCapability,
                mapOf("operation" to "export", "encrypted" to encryptExport.toString())
            )
            if (privacyDecision.blocksExecution()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Export denied by privacy settings: ${privacyDecision.reason()}"
                )
                return@launch
            }

            try {
                readBarrier.checkReadAllowed(
                    DatabaseAccessOperation("ExportOptionsViewModel.generateExport", pipeline = "P12"),
                    DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Export unavailable: ${e.message}"
                )
                return@launch
            }

            try {
                val format = _uiState.value.selectedFormat
                val startDate = _uiState.value.startDate
                val endDate = _uiState.value.endDate
                Timber.i("Export started: format=%s, startDate=%d, endDate=%d", format, startDate, endDate)

                val categories = withContext(ioDispatcher) { exportDataRepository.getCategoryNameMap() }
                val extension = extensionFor(format)
                val exportFile = exportDataRepository.createExportFile(extension, timeProvider.now())

                val previewCollector = PreviewCollector(PREVIEW_MAX_CHARS)

                // BAK-12: Validate dataset size and accounting policy before streaming
                val expenseCount = withContext(ioDispatcher) {
                    exportDataRepository.countExpensesBetween(startDate, endDate)
                }
                if (expenseCount == 0 && !format.allowsEmptyDataset()) {
                    throw IllegalArgumentException("No expenses found for selected date range")
                }

                // Accounting format validation: validate full dataset before streaming
                // to catch data quality issues across all pages, not just the first.
                if (format.requiresAccountingPolicy()) {
                    val allExpenses = withContext(ioDispatcher) {
                        exportDataRepository.getExpensesBetween(startDate, endDate)
                    }
                    if (allExpenses.isNotEmpty()) {
                        accountingExportPolicy.validateAccountingDataset(
                            allExpenses.map { it.toExportTransaction() },
                            format.accountingExportDisplayName()
                        )
                    }
                }

                // BAK-12: Warn when dataset is large enough to warrant streaming
                if (expenseCount > 5000) {
                    Timber.w("BAK-12: Exporting %d expenses via streaming", expenseCount)
                }

                val finalFile = withContext(ioDispatcher) {
                    val tempFile = java.io.File(exportFile.parentFile, ".tmp_${exportFile.name}")
                    try {
                        tempFile.writer().use { writer ->
                            writeStreamHeader(
                                writer = writer,
                                format = format,
                                categories = categories,
                                preview = previewCollector,
                                rowCount = expenseCount,
                                startDate = startDate,
                                endDate = endDate
                            )

                            streamExpensesToWriter(
                                writer = writer,
                                startDate = startDate,
                                endDate = endDate,
                                categories = categories,
                                format = format,
                                preview = previewCollector
                            )

                            writeStreamFooter(writer, format, previewCollector)
                        }

                        if (encryptExport) {
                            // P12-NEW-01: Encrypt the hidden temp file directly into the
                            // final .enc path. Plaintext NEVER lands at the shareable
                            // export path. passphrase is guaranteed non-blank above.
                            val encryptedFile = java.io.File(
                                exportFile.parentFile,
                                "${exportFile.name}.enc"
                            )
                            try {
                                exportDataRepository.encryptExportFile(
                                    tempFile,
                                    encryptedFile,
                                    passphrase!!
                                )
                            } catch (e: Exception) {
                                // Fail closed: leave no plaintext and no partial ciphertext.
                                encryptedFile.delete()
                                throw e
                            }
                            encryptedFile
                        } else {
                            if (!tempFile.renameTo(exportFile)) {
                                tempFile.copyTo(exportFile, overwrite = true)
                            }
                            exportFile
                        }
                    } finally {
                        // Always remove the plaintext temp (rename consumes it; this is a
                        // no-op then). On encryption or any failure this is what prevents a
                        // plaintext leak.
                        tempFile.delete()
                    }
                }

                if (exportGeneration != generation) return@launch
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    exportPreview = previewCollector.value,
                    exportPreviewTruncated = previewCollector.truncated,
                    exportFilePath = finalFile.absolutePath,
                    exportSuccess = true,
                    error = null
                )
                Timber.i("Export finished: format=%s, path=%s, previewChars=%d", format, finalFile.absolutePath, previewCollector.value.length)
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

    @Deprecated("Use writePage which supports full ExportTransaction schema")
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
                val amount = escapeCsv(CurrencyFormatter.formatForExport(expense.effectiveAmount))
                val currency = escapeCsv(expense.currency)
                val category = escapeCsv(categories[expense.categoryId] ?: "Uncategorized")
                val notes = escapeCsv(expense.notes ?: "")
                append("$date,$merchant,$amount,$currency,$category,$notes,${expense.id}\n")
            }
            writer.append(line)
            preview.append(line)
        }
    }

    @Deprecated("Use writePage which supports full ExportTransaction schema")
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
                append("\"amount\":").append(formatJsonNumber(expense.effectiveAmount)).append(',')
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

    /**
     * Writes a single page of expenses to [writer] without holding all rows in memory.
     * Each page is the result of one keyset-paginated query from [DeterministicExpenseExportPager].
     *
     * ## BAK-12: Streaming export helper
     * Dispatches to the appropriate format-specific row writer based on [format].
     * No headers or structural wrappers are written here — those are handled by
     * [writeStreamHeader] / [writeStreamFooter] before and after the pagination loop.
     *
     * @param writer    The output writer (file writer or buffer).
     * @param page      A single page of expenses (typically up to 2000 rows).
     * @param categories Category name map for display.
     * @param format    Export format identifier (e.g. "csv", "json", "xero").
     * @param preview   Optional preview collector.
     * @param pageNumber 1-based page index (used by JSON to avoid leading comma on first page).
     * @param pageSize  Page size in rows.
     */
    private suspend fun writePage(
        writer: Appendable,
        page: List<Expense>,
        categories: Map<Long, String>,
        format: String,
        preview: PreviewCollector? = null,
        pageNumber: Int = 0,
        pageSize: Int = 2000,
        sourceLinksByExpense: Map<Long, List<EntitySourceLink>> = emptyMap()
    ) {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val zoneId = ZoneId.systemDefault()
        when (format) {
            "json" -> {
                writeJsonPageRows(writer, page, categories, preview, pageNumber, dateFormatter, zoneId, sourceLinksByExpense)
            }
            "xero" -> {
                page.forEach { expense ->
                    val tx = ExpenseExportMapper.mapWithSourceLinks(
                        expense,
                        sourceLinksByExpense[expense.id] ?: emptyList()
                    )
                    val line = buildString {
                        xeroExporter.writeExpense(this, tx, categories)
                    }
                    writer.append(line)
                    preview?.append(line)
                }
            }
            "quickbooks" -> {
                page.forEach { expense ->
                    val tx = ExpenseExportMapper.mapWithSourceLinks(
                        expense,
                        sourceLinksByExpense[expense.id] ?: emptyList()
                    )
                    val block = buildString {
                        quickBooksExporter.writeExpense(this, tx, categories)
                    }
                    writer.append(block)
                    preview?.append(block)
                }
            }
            "freshbooks" -> {
                page.forEach { expense ->
                    val tx = ExpenseExportMapper.mapWithSourceLinks(
                        expense,
                        sourceLinksByExpense[expense.id] ?: emptyList()
                    )
                    val line = buildString {
                        freshBooksExporter.writeExpense(this, tx, categories)
                    }
                    writer.append(line)
                    preview?.append(line)
                }
            }
            else -> {
                // Generic CSV — full ExportTransaction schema
                page.forEach { expense ->
                    val tx = ExpenseExportMapper.mapWithSourceLinks(
                        expense,
                        sourceLinksByExpense[expense.id] ?: emptyList()
                    )
                    val line = buildString {
                        append(tx.id).append(',')
                        append(Instant.ofEpochMilli(tx.date).atZone(zoneId).toLocalDate().format(dateFormatter)).append(',')
                        append(Instant.ofEpochMilli(tx.createdAt).atZone(zoneId).toLocalDate().format(dateFormatter)).append(',')
                        append(escapeCsv(tx.merchant)).append(',')
                        append(tx.amount).append(',')
                        append(tx.effectiveAmount).append(',')
                        append(escapeCsv(tx.currency)).append(',')
                        append(tx.transactionType.name).append(',')
                        append(escapeCsv(categories[tx.categoryId] ?: "")).append(',')
                        append(escapeCsv(tx.notes ?: "")).append(',')
                        append(escapeCsv(tx.source ?: "")).append(',')
                        append(escapeCsv(tx.paymentMethod)).append(',')
                        append(escapeCsv(tx.originalCurrency)).append(',')
                        append(tx.originalAmount?.let { formatCsvNumber(it) } ?: "").append(',')
                        append(escapeCsv(tx.homeCurrency)).append(',')
                        append(tx.baseAmount).append(',')
                        append(escapeCsv(tx.baseCurrency)).append(',')
                        append(tx.exchangeRateUsed).append(',')
                        append(if (tx.isBusinessExpense) "true" else "false").append(',')
                        append(escapeCsv(tx.businessPurpose ?: "")).append(',')
                        append(escapeCsv(tx.sourceLinksJson ?: "")).append('\n')
                    }
                    writer.append(line)
                    preview?.append(line)
                }
            }
        }
    }

    private suspend fun writeJsonPageRows(
        writer: Appendable,
        page: List<Expense>,
        categories: Map<Long, String>,
        preview: PreviewCollector?,
        pageNumber: Int,
        dateFormatter: DateTimeFormatter,
        zoneId: ZoneId,
        sourceLinksByExpense: Map<Long, List<EntitySourceLink>> = emptyMap()
    ) {
        var first = pageNumber == 1
        page.forEach { expense ->
            val tx = ExpenseExportMapper.mapWithSourceLinks(
                expense,
                sourceLinksByExpense[expense.id] ?: emptyList()
            )
            val row = buildString {
                if (!first) append(',')
                first = false
                val date = Instant.ofEpochMilli(tx.date).atZone(zoneId).toLocalDate().format(dateFormatter)
                val category = categories[tx.categoryId] ?: ""
                append("{")
                append("\"id\":").append(tx.id).append(',')
                append("\"date\":\"").append(escapeJson(date)).append("\",")
                append("\"timestamp\":").append(tx.date).append(',')
                append("\"createdAt\":").append(tx.createdAt).append(',')
                append("\"merchant\":\"").append(escapeJson(tx.merchant)).append("\",")
                append("\"amount\":").append(formatJsonNumber(tx.amount)).append(',')
                append("\"effectiveAmount\":").append(formatJsonNumber(tx.effectiveAmount)).append(',')
                append("\"currency\":\"").append(escapeJson(tx.currency)).append("\",")
                append("\"transactionType\":\"").append(tx.transactionType.name).append("\",")
                append("\"category\":\"").append(escapeJson(category)).append("\",")
                append("\"notes\":")
                if (tx.notes == null) append("null,")
                else append("\"").append(escapeJson(tx.notes)).append("\",")
                append("\"source\":")
                if (tx.source == null) append("null,")
                else append("\"").append(escapeJson(tx.source)).append("\",")
                append("\"paymentMethod\":\"").append(escapeJson(tx.paymentMethod)).append("\",")
                append("\"originalCurrency\":\"").append(escapeJson(tx.originalCurrency)).append("\",")
                append("\"originalAmount\":").append(tx.originalAmount?.let { formatJsonNumber(it) } ?: "null").append(',')
                append("\"homeCurrency\":\"").append(escapeJson(tx.homeCurrency)).append("\",")
                append("\"baseAmount\":").append(formatJsonNumber(tx.baseAmount)).append(',')
                append("\"baseCurrency\":\"").append(escapeJson(tx.baseCurrency)).append("\",")
                append("\"exchangeRateUsed\":").append(tx.exchangeRateUsed).append(',')
                append("\"isBusinessExpense\":").append(if (tx.isBusinessExpense) "true" else "false").append(',')
                append("\"businessPurpose\":")
                if (tx.businessPurpose == null) append("null")
                else append("\"").append(escapeJson(tx.businessPurpose)).append("\",")
                append("\"sourceLinks\":")
                if (tx.sourceLinksJson == null) append("null")
                else append(escapeJson(tx.sourceLinksJson))
                append("}")
            }
            writer.append(row)
            preview?.append(row)
        }
    }

    /**
     * Streams all expenses in [startDate..endDate) to [writer] using keyset
     * cursor pagination, writing one page of rows at a time via [writePage].
     *
     * Unlike the previous bulk-load approach (which called
     * [ExportDataRepository.getExpensesBetweenForExport] and accumulated all
     * expenses into a single [List]), this method keeps memory usage bounded
     * to O([pageSize]).
     *
     * ## Pagination flow
     * 1. Fetch the first page with `lastDate = null, lastId = null`.
     * 2. Write rows via [writePage].
     * 3. Update cursors from the last row of the current page.
     * 4. Fetch next page with updated cursors.
     * 5. Repeat until an empty page is returned.
     *
     * @param writer     The output writer.
     * @param startDate  Start of the date range (inclusive).
     * @param endDate    End of the date range (exclusive).
     * @param categories Category name map for display.
     * @param format     Export format identifier.
     * @param preview    Optional preview collector.
     * @param pageSize   Number of rows per page (default 2000).
     */
    private suspend fun streamExpensesToWriter(
        writer: Appendable,
        startDate: Long,
        endDate: Long,
        categories: Map<Long, String>,
        format: String,
        preview: PreviewCollector?,
        pageSize: Int = 2000
    ) {
        var lastDate: Long? = null
        var lastId: Long? = null
        var pageCount = 0
        while (true) {
            val page = exportDataRepository.getExpensesPage(
                startDate, endDate, pageSize, lastDate, lastId
            )
            if (page.isEmpty()) break

            // PR7: Batch-load source links for this page to avoid N+1 queries.
            // P12-CURRENT-020: routed through the barrier-guarded repository method
            // (not the raw DAO) so this read is fenced during restore like the others.
            val expenseIds = page.map { it.id }
            val sourceLinksByExpense = exportDataRepository.getSourceLinksForExpenses(expenseIds)

            pageCount++
            writePage(writer, page, categories, format, preview, pageCount, pageSize, sourceLinksByExpense)
            val last = page.last()
            lastDate = last.date
            lastId = last.id
        }
    }

    /**
     * Writes the format-specific header / structural prefix before the row data.
     *
     * For JSON this writes the schema wrapper up to (but not including) the
     * first row. For CSV and accounting formats this writes the column header
     * line(s) via the appropriate exporter.
     */
    private suspend fun writeStreamHeader(
        writer: Appendable,
        format: String,
        categories: Map<Long, String>,
        preview: PreviewCollector,
        rowCount: Int,
        startDate: Long,
        endDate: Long
    ) {
        when (format) {
            "json" -> {
                val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                val zoneId = ZoneId.systemDefault()
                val generatedAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timeProvider.now()))
                val sDate = Instant.ofEpochMilli(startDate).atZone(zoneId).toLocalDate().format(dateFormatter)
                val eDate = Instant.ofEpochMilli(endDate).atZone(zoneId).toLocalDate().format(dateFormatter)
                val prefix = buildString {
                    append("{")
                    append("\"schemaVersion\":2,")
                    append("\"exportType\":\"expenses\",")
                    append("\"generatedAt\":\"").append(escapeJson(generatedAtIso)).append("\",")
                    append("\"dateRange\":{")
                    append("\"startDate\":\"").append(escapeJson(sDate)).append("\",")
                    append("\"endDate\":\"").append(escapeJson(eDate)).append("\"},")
                    append("\"rowCount\":").append(rowCount).append(",")
                    append("\"rows\":[")
                }
                writer.append(prefix)
                preview.append(prefix)
            }
            "csv" -> {
                val metadataLine = "# ExpenseTracker Export v2, rowCount=$rowCount, startDate=$startDate, endDate=$endDate\n"
                writer.append(metadataLine)
                preview.append(metadataLine)
                val header = "ID,Date,CreatedAt,Merchant,Amount,EffectiveAmount,Currency,TransactionType,Category,Notes,Source,PaymentMethod,OriginalCurrency,OriginalAmount,HomeCurrency,BaseAmount,BaseCurrency,ExchangeRateUsed,IsBusinessExpense,BusinessPurpose,SourceLinks\n"
                writer.append(header)
                preview.append(header)
            }
            "xero" -> {
                xeroExporter.writeHeader(writer)
                // Capture actual header for preview parity
                val headerBuffer = StringBuilder()
                xeroExporter.writeHeader(headerBuffer)
                preview.append(headerBuffer.toString())
            }
            "quickbooks" -> {
                quickBooksExporter.writeHeader(writer)
                // Capture actual header for preview parity
                val headerBuffer = StringBuilder()
                quickBooksExporter.writeHeader(headerBuffer)
                preview.append(headerBuffer.toString())
            }
            "freshbooks" -> {
                freshBooksExporter.writeHeader(writer)
                // Capture actual header for preview parity
                val headerBuffer = StringBuilder()
                freshBooksExporter.writeHeader(headerBuffer)
                preview.append(headerBuffer.toString())
            }
        }
    }

    /**
     * Writes the format-specific footer / structural suffix after all row data.
     * Currently only needed for JSON (closes the rows array and the root object).
     */
    private suspend fun writeStreamFooter(
        writer: Appendable,
        format: String,
        preview: PreviewCollector
    ) {
        if (format == "json") {
            writer.append("]}")
            preview.append("]}")
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

    private fun formatCsvNumber(value: Double): String = if (value.isFinite()) value.toString() else ""

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
