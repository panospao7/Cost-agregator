package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

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
 * [ImportContractErrorCodes.UNKNOWN_TRANSACTION_TYPE] instead of silently
 * defaulting to PURCHASE.
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
            return@withContext ImportResult.Error(
                "Import blocked: database maintenance in progress"
            )
        }
        try {
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
                if (record.malformed || isSkippable(record)) return@indexOfFirst false
                val first = record.fields.first().trimStart('\uFEFF').trim()
                first.isNotEmpty() && !first.startsWith("#")
            }
            if (headerRecordIndex == -1) {
                return@withContext ImportResult.Success(0, 0, 0, emptyList())
            }

            val headerRecord = allRecords[headerRecordIndex]
            val headerParts = headerRecord.fields.mapIndexed { i, col ->
                if (i == 0) col.trimStart('\uFEFF') else col
            }.map { it.trim().lowercase() }
            val columnIndex: Map<String, Int> = headerParts.withIndex().associate { (i, col) -> col to i }

            val dataRecords = allRecords.drop(headerRecordIndex + 1)
                .filter { !isSkippable(it) }

            if (dataRecords.isEmpty()) {
                return@withContext ImportResult.Success(0, 0, 0, emptyList())
            }

            var importedCount = 0
            var duplicateCount = 0
            var errorCount = 0
            val perRowResults = mutableListOf<RowResult>()
            val total = dataRecords.size

            dataRecords.forEachIndexed { index, record ->
                val rowResult = if (record.malformed) {
                    // RP-19 (19-A): one malformed record → exactly one failed
                    // row; an unclosed quote never fabricates spurious rows.
                    RowResult.Failed("Invalid CSV row: unclosed quote")
                } else {
                    parseAndImportRecord(record.fields, columnIndex, fileImportRunId)
                }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Error("Import failed: ${e.message}")
        }
    }

    /**
     * Parse a single CSV record (already field-split by [Rfc4180CsvReader])
     * using a column-index map built from the header row and submit it through
     * the lifecycle coordinator.
     *
     * @return [RowResult] indicating the outcome for this row.
     */
    private suspend fun parseAndImportRecord(
        fields: List<String>,
        columnIndex: Map<String, Int>,
        fileImportRunId: Long? = null
    ): RowResult {
        return try {
            fun col(name: String): String? {
                val idx = columnIndex[name.lowercase()] ?: return null
                return if (idx < fields.size) fields[idx].trim().takeIf { it.isNotEmpty() } else null
            }

            val dateStr = col("date") ?: return RowResult.Failed("Missing date column")
            val merchant = col("merchant") ?: return RowResult.Failed("Missing merchant column")

            // RP-19 (19-B) amount precedence (single rule for every type, see
            // the roundtrip matrix): original `amount` first, `effectiveAmount`
            // only as fallback when amount is absent or unparseable.
            val amountStr = col("amount")
            val effectiveStr = col("effectiveAmount")
            val amount = parseAmount(amountStr)
                ?: parseAmount(effectiveStr)
                ?: return RowResult.Failed("Invalid amount")

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

            // RP-19 (19-B): transactionType maps to the exact enum. Absent →
            // PURCHASE (legacy default); present but unknown → controlled
            // UNKNOWN_TRANSACTION_TYPE failure, never a silent default.
            val transactionType = col("transactionType")
                ?.let { parseTransactionTypeStrict(it) }
                ?: TransactionType.PURCHASE

            // Parse amount and detect currency symbol
            val currencyFromSymbol = detectCurrencySymbol(amountStr ?: effectiveStr ?: "")
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
                transactionType = transactionType,
                source = ExpenseSource.CSV_IMPORT,
                categoryId = categoryId,
                notes = notesFromCol.ifEmpty { null },
                fileImportRunId = fileImportRunId
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportContractException) {
            RowResult.Failed(e.code)
        } catch (e: Exception) {
            RowResult.Failed(e.message ?: "Unknown error")
        }
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
