package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.export.ExportInstallmentMarker
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Bounded failure codes for CSV import (J-12-2). Failures never carry raw
 * file text, exception messages, paths, or SQL; a failure is the code plus
 * (for row failures) the 1-based data-row number only.
 */
enum class CsvImportFailureCode {
    /** The `date` cell does not parse as `yyyy-MM-dd`. */
    INVALID_DATE,

    /** Neither `amount` nor `effectiveAmount` parses. */
    INVALID_AMOUNT,

    /** A required cell (`date` or `merchant`) is missing or blank. */
    MISSING_COLUMN,

    /** The header record is malformed (unclosed quote). */
    INVALID_HEADER,

    /** A data record is malformed (unclosed quote). */
    MALFORMED_ROW,

    /** A present but unknown `transactionType` value. */
    UNKNOWN_TRANSACTION_TYPE,

    /** The lifecycle coordinator rejected the row (validation or insert conflict). */
    ROW_REJECTED,

    /** The database write barrier blocked the import (maintenance in progress). */
    WRITE_BLOCKED,

    /** Unexpected internal failure; details are intentionally not surfaced. */
    INTERNAL
}

/**
 * Write-free preview of a CSV import (J-12-2). Produced by the same
 * parse/validate path as the import itself; no DB writes, no category creation.
 *
 * @param totalRows              Data rows seen (header/comment/blank rows excluded).
 * @param validRows              Rows that would be submitted to the lifecycle coordinator.
 * @param invalidRowsByCode      Count of invalid rows per [CsvImportFailureCode].
 * @param unknownCategoryRows    Valid rows whose category name does not exist and
 *                               would be imported as "Uncategorized".
 * @param skippedInstallmentRows Rows the exporter marked as installment-flagged
 *                               (skipped on import, never failures).
 * @param headerFailure          Non-null when the header itself is unusable
 *                               (no rows are previewed in that case).
 */
data class CsvImportPreview(
    val totalRows: Int,
    val validRows: Int,
    val invalidRowsByCode: Map<CsvImportFailureCode, Int>,
    val unknownCategoryRows: Int,
    val skippedInstallmentRows: Int,
    val headerFailure: CsvImportFailureCode? = null
) {
    val invalidRows: Int get() = invalidRowsByCode.values.sum()
}

/**
 * Utility for importing expenses from CSV exported from old app versions.
 *
 * CSV Format expected:
 * date,amount,merchant,category,description
 * 2024-01-15,25.50,Starbucks,Coffee,Morning coffee
 *
 * RP-19 (19-A): rows are parsed by [Rfc4180CsvReader] as one character stream,
 * so quoted fields may contain embedded LF/CRLF and `""` escapes without
 * splitting records; a UTF-8 BOM is stripped at the first ingestion boundary,
 * including one preceding the header after leading blank/comment lines; the
 * date formatter is pinned to [Locale.US] (zone-less `LocalDate`, digits never
 * locale-shaped) while the resulting instant stays anchored to start-of-day in
 * the device zone.
 *
 * RP-19 (19-B): the roundtrip contract is column-name driven — see
 * `docs/analyses and debug master/remediation/RP-19-ROUNDTRIP-MATRIX.md`.
 * `amount` is the original amount (EffectiveAmount is a fallback only when
 * `amount` is absent/unparseable) and `TransactionType` maps to the exact enum;
 * a present but unknown value fails the row with
 * [CsvImportFailureCode.UNKNOWN_TRANSACTION_TYPE] instead of silently
 * defaulting to PURCHASE.
 *
 * J-12-2: failures are bounded [CsvImportFailureCode]s; [preview] shares the
 * row parse/validate path with [importFromContent] and is write-free; unknown
 * category names import as the default "Uncategorized" category (this class
 * never creates categories).
 *
 * Uses Hilt-provided [TransactionLifecycleCoordinator] so every imported row
 * goes through the full lifecycle: validate → normalize → dedupe → insert
 * atomic → event logging.
 *
 * Uses [CategoryDao] for read-only category lookup (shared singleton instance).
 */
class CsvExpenseImporter @Inject constructor(
    private val categoryDao: CategoryDao,
    private val coordinator: TransactionLifecycleCoordinator,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val writeBarrier: DatabaseWriteBarrier
) {

    /**
     * Thread-safe date parser for CSV import using [DateTimeFormatter]
     * (immutable and thread-safe, unlike the legacy SimpleDateFormat).
     * RP-19 (19-A): pinned to [Locale.US] so numeric-only date parsing never
     * picks up locale digit shaping (e.g. Arabic-Indic numerals) from the
     * device's default FORMAT locale. Dates remain zone-less `LocalDate`s.
     */
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    /** Header + data records ready for row parsing, or a terminal state. */
    private sealed class PreparedCsv {
        object Empty : PreparedCsv()
        object InvalidHeader : PreparedCsv()
        class Ready(
            val columnIndex: Map<String, Int>,
            val dataRecords: List<Rfc4180CsvReader.Record>
        ) : PreparedCsv()
    }

    /** A fully validated row, before category/currency resolution. */
    private class ParsedRow(
        val merchant: String,
        val amount: Double,
        val date: Long,
        val transactionType: TransactionType,
        val explicitCurrency: String?,
        val symbolCurrency: String?,
        val categoryName: String?,
        val notes: String?
    )

    /** Outcome of the shared parse/validate step for one record. */
    private sealed class RowParse {
        class Valid(val row: ParsedRow) : RowParse()
        object InstallmentSkipped : RowParse()
        class Invalid(val code: CsvImportFailureCode) : RowParse()
    }

    /**
     * Import expenses from raw CSV content.
     *
     * @param csvContent  The full CSV text (may include comment lines, a header row).
     * @param onProgress  Callback invoked per row with (currentIndex, totalRows).
     * @return [ImportResult] summarizing every row outcome.
     */
    suspend fun importFromContent(
        csvContent: String,
        fileImportRunId: Long? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ImportResult = withContext(Dispatchers.IO) {
        // GR-14u44b: canonical write-barrier admission BEFORE the outer
        // try/catch — a check inside the try would be swallowed by the
        // generic `catch (e: Exception)` below and misreported as an
        // import failure.  Mirrors the
        // BankStatementLifecycleProcessor.processBankStatement entry
        // pattern: CancellationException propagates, everything else
        // becomes a controlled-constant failure (never e.message).
        try {
            writeBarrier.checkWritesAllowed("CsvExpenseImporter.importFromContent")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext ImportResult.Error(CsvImportFailureCode.WRITE_BLOCKED)
        }
        try {
            val prepared = prepare(csvContent)
            if (prepared is PreparedCsv.InvalidHeader) {
                return@withContext ImportResult.Error(CsvImportFailureCode.INVALID_HEADER)
            }
            if (prepared !is PreparedCsv.Ready) {
                return@withContext ImportResult.Success(0, 0, 0, emptyList())
            }

            var importedCount = 0
            var duplicateCount = 0
            var errorCount = 0
            val perRowResults = mutableListOf<RowResult>()
            val total = prepared.dataRecords.size
            // CA-P-12-001: CSV_IMPORT provenance requires a batch id + row
            // number; one batch id per import run, 1-based data-row number.
            val csvImportBatchId = UUID.randomUUID().toString()

            for ((index, record) in prepared.dataRecords.withIndex()) {
                // J-12-2: cooperative cancellation between rows.
                currentCoroutineContext().ensureActive()
                val rowNumber = index + 1
                val rowResult = importRecord(
                    record,
                    prepared.columnIndex,
                    fileImportRunId,
                    csvImportBatchId = csvImportBatchId,
                    csvRowNumber = rowNumber
                )
                perRowResults.add(rowResult)

                when (rowResult) {
                    is RowResult.Imported -> importedCount++
                    is RowResult.Duplicate -> duplicateCount++
                    is RowResult.Failed -> errorCount++
                }

                onProgress(rowNumber, total)
            }

            ImportResult.Success(
                imported = importedCount,
                duplicates = duplicateCount,
                errors = errorCount,
                perRowResults = perRowResults
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Error(CsvImportFailureCode.INTERNAL)
        }
    }

    /**
     * J-12-2: write-free preview. Runs the exact same [prepare] and
     * [parseRecord] code as [importFromContent]; performs only category
     * READS (never inserts) and never calls the lifecycle coordinator, so it
     * needs no write-barrier admission.
     */
    suspend fun preview(
        csvContent: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): CsvImportPreview = withContext(Dispatchers.IO) {
        val prepared = prepare(csvContent)
        if (prepared is PreparedCsv.InvalidHeader) {
            return@withContext CsvImportPreview(
                totalRows = 0,
                validRows = 0,
                invalidRowsByCode = emptyMap(),
                unknownCategoryRows = 0,
                skippedInstallmentRows = 0,
                headerFailure = CsvImportFailureCode.INVALID_HEADER
            )
        }
        if (prepared !is PreparedCsv.Ready) {
            return@withContext CsvImportPreview(0, 0, emptyMap(), 0, 0)
        }

        val total = prepared.dataRecords.size
        var valid = 0
        var unknownCategory = 0
        var skippedInstallment = 0
        val invalid = mutableMapOf<CsvImportFailureCode, Int>()
        val knownCategoryCache = mutableMapOf<String, Boolean>()

        for ((index, record) in prepared.dataRecords.withIndex()) {
            currentCoroutineContext().ensureActive()
            when (val parsed = parseRecord(record, prepared.columnIndex)) {
                is RowParse.Valid -> {
                    valid++
                    val name = parsed.row.categoryName
                    if (name != null) {
                        val known = knownCategoryCache.getOrPut(name) {
                            categoryDao.getByName(name) != null
                        }
                        if (!known) unknownCategory++
                    }
                }
                is RowParse.InstallmentSkipped -> skippedInstallment++
                is RowParse.Invalid -> invalid[parsed.code] = (invalid[parsed.code] ?: 0) + 1
            }
            onProgress(index + 1, total)
        }

        CsvImportPreview(
            totalRows = total,
            validRows = valid,
            invalidRowsByCode = invalid.toMap(),
            unknownCategoryRows = unknownCategory,
            skippedInstallmentRows = skippedInstallment
        )
    }

    /**
     * Shared by import and preview: BOM strip, RFC-4180 record parse, header
     * lookup, comment/blank filtering.
     */
    private fun prepare(csvContent: String): PreparedCsv {
        // RP-19 (19-A): strip the UTF-8 BOM at the first ingestion boundary.
        // Concatenated/edited files may carry a BOM after leading blank or
        // comment lines too, so the header lookup also strips a BOM prefix
        // from the first field (see headerRecordIndex below).
        val bomStripped = csvContent.trimStart('\uFEFF')
        val allRecords = Rfc4180CsvReader.parse(bomStripped)

        // Comment/blank skipping now happens at RECORD level, so a `#`
        // inside a quoted multi-line field can never be mistaken for a
        // comment line.
        fun isSkippable(record: Rfc4180CsvReader.Record): Boolean {
            val first = record.fields.firstOrNull()?.trim()
            return (record.fields.size == 1 && first.isNullOrEmpty()) ||
                (first != null && first.startsWith("#"))
        }

        val headerRecordIndex = allRecords.indexOfFirst { record ->
            if (record.malformed) return@indexOfFirst true
            if (isSkippable(record)) return@indexOfFirst false
            val first = record.fields.first().trimStart('\uFEFF').trim()
            first.isNotEmpty() && !first.startsWith("#")
        }
        if (headerRecordIndex == -1) return PreparedCsv.Empty

        val headerRecord = allRecords[headerRecordIndex]
        if (headerRecord.malformed) return PreparedCsv.InvalidHeader
        val headerParts = headerRecord.fields.mapIndexed { i, col ->
            if (i == 0) col.trimStart('\uFEFF') else col
        }.map { it.trim().lowercase() }
        val columnIndex: Map<String, Int> = headerParts.withIndex().associate { (i, col) -> col to i }

        val dataRecords = allRecords.drop(headerRecordIndex + 1)
            .filter { !isSkippable(it) }

        return if (dataRecords.isEmpty()) PreparedCsv.Empty else PreparedCsv.Ready(columnIndex, dataRecords)
    }

    /**
     * Shared by import and preview: validates one record (already
     * field-split by [Rfc4180CsvReader]) against the header column map.
     * Pure — no DB access, no writes.
     */
    private fun parseRecord(
        record: Rfc4180CsvReader.Record,
        columnIndex: Map<String, Int>
    ): RowParse {
        // RP-19 (19-A): one malformed record → exactly one failed row; an
        // unclosed quote never fabricates spurious rows.
        if (record.malformed) return RowParse.Invalid(CsvImportFailureCode.MALFORMED_ROW)
        val fields = record.fields
        return try {
            fun col(name: String): String? {
                val idx = columnIndex[name.lowercase()] ?: return null
                return if (idx < fields.size) fields[idx].trim().takeIf { it.isNotEmpty() } else null
            }

            val dateStr = col("date")
                ?: return RowParse.Invalid(CsvImportFailureCode.MISSING_COLUMN)
            val merchant = col("merchant")?.let { stripFormulaNeutralizer(it) }
                ?: return RowParse.Invalid(CsvImportFailureCode.MISSING_COLUMN)

            // RP-19 (19-B) amount precedence (single rule for every type, see
            // the roundtrip matrix): original `amount` first, `effectiveAmount`
            // only as fallback when amount is absent or unparseable.
            val amountStr = col("amount")
            val effectiveStr = col("effectiveAmount")
            val amount = parseAmount(amountStr)
                ?: parseAmount(effectiveStr)
                ?: return RowParse.Invalid(CsvImportFailureCode.INVALID_AMOUNT)

            val categoryName = col("category")?.let { stripFormulaNeutralizer(it) }
            // Owner-approvals 41(b): rows the exporter marked as installment-flagged are skipped
            // (counted in the skipped/duplicates counter); re-import never creates a flag.
            val notesFromCol = col("notes")?.let { stripFormulaNeutralizer(it) } ?: ""
            if (ExportInstallmentMarker.isMarkedNotes(notesFromCol)) {
                return RowParse.InstallmentSkipped
            }

            // Parse date
            val date = try {
                java.time.LocalDate.parse(dateStr, dateFormat)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (e: Exception) {
                return RowParse.Invalid(CsvImportFailureCode.INVALID_DATE)
            }

            // RP-19 (19-B): transactionType maps to the exact enum. Absent →
            // PURCHASE (legacy default); present but unknown → controlled
            // UNKNOWN_TRANSACTION_TYPE failure, never a silent default.
            val transactionType = col("transactionType")
                ?.let { parseTransactionTypeStrict(it) }
                ?: TransactionType.PURCHASE

            RowParse.Valid(
                ParsedRow(
                    merchant = merchant,
                    amount = amount,
                    date = date,
                    transactionType = transactionType,
                    explicitCurrency = col("currency"),
                    symbolCurrency = detectCurrencySymbol(amountStr ?: effectiveStr ?: ""),
                    categoryName = categoryName,
                    notes = notesFromCol.ifEmpty { null }
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportContractException) {
            RowParse.Invalid(mapContractCode(e.code))
        } catch (e: Exception) {
            RowParse.Invalid(CsvImportFailureCode.INTERNAL)
        }
    }

    /**
     * Parse a single CSV record via [parseRecord] and submit it through
     * the lifecycle coordinator.
     *
     * @return [RowResult] indicating the outcome for this row.
     */
    private suspend fun importRecord(
        record: Rfc4180CsvReader.Record,
        columnIndex: Map<String, Int>,
        fileImportRunId: Long? = null,
        csvImportBatchId: String,
        csvRowNumber: Int
    ): RowResult {
        return try {
            val row = when (val parsed = parseRecord(record, columnIndex)) {
                is RowParse.Invalid -> return RowResult.Failed(parsed.code, csvRowNumber)
                is RowParse.InstallmentSkipped -> return RowResult.Duplicate("INSTALLMENT_EXPORT_MARKER")
                is RowParse.Valid -> parsed.row
            }

            // Resolve currency: explicit Currency column, then symbol from amount, then home fallback
            val homeResolution = currencySettingsRepository.resolveHomeCurrency()
            val homeCurrency = homeResolution.currencyOrNull?.code ?: "EUR" // last resort for CSV import
            val resolvedCurrency = row.explicitCurrency ?: row.symbolCurrency ?: homeCurrency

            // Resolve category (read-only; unknown → Uncategorized)
            val categoryId = resolveCategoryId(row.categoryName)

            // Build creation request — the coordinator handles dedupeKey,
            // merchantKey, dedup, and lifecycle events automatically.
            val request = CreateExpenseRequest(
                merchant = row.merchant,
                amount = row.amount,
                currency = resolvedCurrency,
                date = row.date,
                transactionType = row.transactionType,
                source = ExpenseSource.CSV_IMPORT,
                categoryId = categoryId,
                notes = row.notes,
                fileImportRunId = fileImportRunId,
                csvImportBatchId = csvImportBatchId,
                csvRowNumber = csvRowNumber
            )

            @Suppress("DEPRECATION_ERROR") // TODO: migrate to createExpenseStandalone()
            when (val result = coordinator.createExpense(request)) {
                is CreateExpenseResult.Created ->
                    RowResult.Imported(result.expenseId)

                is CreateExpenseResult.DuplicateSkipped ->
                    RowResult.Duplicate(result.reason)

                is CreateExpenseResult.ValidationFailed ->
                    RowResult.Failed(CsvImportFailureCode.ROW_REJECTED, csvRowNumber)

                is CreateExpenseResult.InsertConflict ->
                    RowResult.Failed(CsvImportFailureCode.ROW_REJECTED, csvRowNumber)

                is CreateExpenseResult.Error ->
                    RowResult.Failed(CsvImportFailureCode.INTERNAL, csvRowNumber)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportContractException) {
            RowResult.Failed(mapContractCode(e.code), csvRowNumber)
        } catch (e: Exception) {
            RowResult.Failed(CsvImportFailureCode.INTERNAL, csvRowNumber)
        }
    }

    private fun mapContractCode(code: String): CsvImportFailureCode = when (code) {
        ImportContractErrorCodes.UNKNOWN_TRANSACTION_TYPE -> CsvImportFailureCode.UNKNOWN_TRANSACTION_TYPE
        ImportContractErrorCodes.INVALID_AMOUNT -> CsvImportFailureCode.INVALID_AMOUNT
        else -> CsvImportFailureCode.ROW_REJECTED
    }

    /**
     * P-12-3: inverse of the leading "'" that [com.yourname.expensetracker.domain.export.CsvCellSanitizer]
     * prepends to text cells whose trimStart() begins with = + - @ (formula neutralization; a
     * plain number such as "-5" is exempt). The quote is removed only when the remainder's
     * trimStart() begins with one of those characters and is not a plain number, so an ordinary
     * value such as "'abc" or "'-5" is left exactly as written.
     */
    private fun stripFormulaNeutralizer(value: String): String {
        if (value.length > 1 && value[0] == '\'') {
            val rest = value.substring(1)
            val trimmed = rest.trimStart()
            val isPlainNumber = trimmed.matches(Regex("^-?\\d+(\\.\\d+)?$"))
            if (!isPlainNumber && trimmed.isNotEmpty() && trimmed[0] in "=+-@") {
                return rest
            }
        }
        return value
    }

    /**
     * RP-19 (19-B): exact-enum transaction type mapping for CSV. Lenient on
     * case/whitespace, strict on unknown values — fails with the controlled
     * [ImportContractErrorCodes.UNKNOWN_TRANSACTION_TYPE] code.
     */
    private fun parseTransactionTypeStrict(raw: String): TransactionType {
        val normalized = raw.trim().uppercase(Locale.US)
        return TransactionType.entries.firstOrNull { it.name == normalized }
            ?: throw ImportContractException(ImportContractErrorCodes.UNKNOWN_TRANSACTION_TYPE)
    }

    /**
     * Strips common currency symbols and parses the remainder as a double.
     * Returns null when the value is absent or unparseable (never 0.0 by
     * default — an unparseable amount must fail the row, not zero it).
     */
    private fun parseAmount(raw: String?): Double? {
        if (raw == null) return null
        return raw
            .replace("€", "")
            .replace("$", "")
            .replace("£", "")
            .replace("¥", "")
            .trim()
            .toDoubleOrNull()
    }

    /**
     * J-12-2: read-only category resolution. A named category that exists is
     * used as-is; an absent or unknown name resolves to the default
     * "Uncategorized" category. Never inserts. Returns null only when even
     * the default category is missing (expense is stored uncategorized).
     */
    private suspend fun resolveCategoryId(name: String?): Long? {
        if (name != null) {
            categoryDao.getByName(name)?.let { return it.id }
            if (name.equals(UNCATEGORIZED_NAME, ignoreCase = true)) return null
        }
        return categoryDao.getByName(UNCATEGORIZED_NAME)?.id
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
         * Carries a bounded [CsvImportFailureCode] only.
         */
        data class Error(val code: CsvImportFailureCode) : ImportResult()
    }

    /**
     * Outcome for a single CSV row.
     */
    sealed class RowResult {
        /** Expense was successfully created with the given database ID. */
        data class Imported(val expenseId: Long) : RowResult()

        /** Row matched an existing expense and was skipped. */
        data class Duplicate(val reason: String) : RowResult()

        /**
         * Row could not be imported: bounded [code] plus the 1-based data-row
         * number ([rowNumber]); never raw text.
         */
        data class Failed(val code: CsvImportFailureCode, val rowNumber: Int) : RowResult()
    }

    private companion object {
        const val UNCATEGORIZED_NAME = "Uncategorized"
    }
}
