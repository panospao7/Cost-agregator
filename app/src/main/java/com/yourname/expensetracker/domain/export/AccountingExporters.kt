package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.domain.util.CurrencyFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// T05-FIXED: Business reports use filingCurrency from TaxSettings for all totals.
// CSV/IIF fields are sanitized via CsvCellSanitizer (RFC-4180 + formula neutralization).
// No hardcoded euro formatting.
//
// P12-CURRENT-025 / P12-NEW-08 (timezone): date columns below are currently rendered
// with ZoneId.systemDefault() — i.e. device-local, NOT UTC. This is non-deterministic
// across timezones for near-midnight transactions. A deterministic export timezone
// policy (UTC for machine formats / configured zone for human-facing accounting,
// declared in a manifest) is the planned PR-TZ fix and is NOT yet applied. Do not
// claim UTC date semantics until that change lands.

/**
 * CRITICAL FIX (CRITICAL-4): Safe CSV/IIF exporters with proper escaping.
 *
 * ## M4: Export paging lacks atomicity
 * Each exporter ([QuickBooksIIFExporter], [XeroCSVExporter], [FreshBooksExporter])
 * receives a fully-materialized [List] of expenses. There is no paging or
 * streaming — for very large data sets this can cause OOM. Additionally, the
 * export is not transactional: if the process is interrupted mid-export, the
 * output will be a truncated file with no rollback mechanism.
 *
 * ### Recommended fix
 * For atomicity, either:
 *   (a) Use a SQL `RETURNING` clause (e.g. `DELETE FROM export_queue WHERE ...
 *       RETURNING *`) to atomically claim and read rows in a single round-trip,
 *       ensuring each row is exported exactly once.
 *   (b) Write to a temporary table / staging file, then atomically rename or
 *       commit the temp file to the final path only after the full export
 *       succeeds (write-ahead buffering).
 * For paging, implement cursor-based (keyset) pagination on the expense table's
 * primary key or a monotonically increasing timestamp, rather than
 * offset-based paging which degrades on large datasets.
 */

class QuickBooksIIFExporter {
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)

    fun export(expenses: List<ExportTransaction>, categories: Map<Long, String>): String {
        return buildString {
            writeHeader(this)
            expenses.forEach { expense -> writeExpense(this, expense, categories) }
        }
    }

    fun writeHeader(writer: Appendable) {
        writer.append("!TRNS\tDATE\tACCNT\tAMOUNT\tCURRENCY\tMEMO\tNAME\tCLASS\n")
        writer.append("!SPL\tDATE\tACCNT\tAMOUNT\tCURRENCY\tMEMO\tNAME\tCLASS\n")
        writer.append("!ENDTRNS\n")
    }

    fun writeExpense(writer: Appendable, expense: ExportTransaction, categories: Map<Long, String>) {
        val date = Instant.ofEpochMilli(expense.date).atZone(ZoneId.systemDefault()).format(dateFormat)
        val fundingAccount = expense.sourceAccountName
        val categoryAccount = categories[expense.categoryId] ?: "Uncategorized"
        val amount = try {
            CurrencyFormatter.formatForExport(expense.amount)
        } catch (e: IllegalArgumentException) {
            "INVALID"
        }
        val splitAmount = try {
            CurrencyFormatter.formatForExport(-expense.amount)
        } catch (e: IllegalArgumentException) {
            "INVALID"
        }
        // P12-CURRENT-015: route ALL IIF string fields through the shared
        // CsvCellSanitizer.sanitizeIif(), which neutralizes formula-leading
        // characters (=, +, -, @) in addition to stripping tab/newline/CR.
        // The old private escapeIifField() stripped delimiters but left formula
        // prefixes intact, so a merchant like "=cmd|..." reached spreadsheet tools.
        val memo = CsvCellSanitizer.sanitizeIif(expense.notes ?: "")
        val name = CsvCellSanitizer.sanitizeIif(expense.merchant)

        writer.append("TRNS\t${CsvCellSanitizer.sanitizeIif(date)}\t${CsvCellSanitizer.sanitizeIif(fundingAccount)}\t$amount\t${CsvCellSanitizer.sanitizeIif(expense.currency)}\t$memo\t$name\t\n")
        writer.append("SPL\t${CsvCellSanitizer.sanitizeIif(date)}\t${CsvCellSanitizer.sanitizeIif(categoryAccount)}\t$splitAmount\t${CsvCellSanitizer.sanitizeIif(expense.currency)}\t$memo\t$name\t\n")
        writer.append("ENDTRNS\n")
    }
}

class XeroCSVExporter {
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.UK)

    fun export(expenses: List<ExportTransaction>, categories: Map<Long, String>): String {
        return buildString {
            writeHeader(this)
            expenses.forEach { expense -> writeExpense(this, expense, categories) }
        }
    }

    fun writeHeader(writer: Appendable) {
        writer.append("Date,Description,Amount,Currency,Account,Reference,OriginalCurrency,HomeCurrency,ConversionRate,OriginalAmount\n")
    }

    fun writeExpense(writer: Appendable, expense: ExportTransaction, categories: Map<Long, String>) {
        val date = Instant.ofEpochMilli(expense.date).atZone(ZoneId.systemDefault()).format(dateFormat)
        val description = CsvCellSanitizer.sanitize(expense.merchant)
        val amount = CsvCellSanitizer.sanitize(
            try {
                CurrencyFormatter.formatForExport(expense.amount)
            } catch (e: IllegalArgumentException) {
                "INVALID"
            }
        )
        val account = CsvCellSanitizer.sanitize(categories[expense.categoryId] ?: "Uncategorized")
        val reference = expense.id.toString()
        val conversionRate = expense.conversionRateUsed?.let {
            try {
                CurrencyFormatter.formatForExport(it)
            } catch (e: IllegalArgumentException) {
                "INVALID"
            }
        } ?: ""
        val originalAmount = expense.originalAmount?.let {
            try {
                CurrencyFormatter.formatForExport(it)
            } catch (e: IllegalArgumentException) {
                "INVALID"
            }
        } ?: ""

        writer.append(
            "${CsvCellSanitizer.sanitize(date)},$description,$amount,${CsvCellSanitizer.sanitize(expense.currency)}," +
                "$account,$reference," +
                "${CsvCellSanitizer.sanitize(expense.originalCurrency)}," +
                "${CsvCellSanitizer.sanitize(expense.homeCurrency)}," +
                "$conversionRate,$originalAmount\n"
        )
    }

}

class FreshBooksExporter {
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun export(expenses: List<ExportTransaction>, categories: Map<Long, String>): String {
        return buildString {
            writeHeader(this)
            expenses.forEach { expense -> writeExpense(this, expense, categories) }
        }
    }

    fun writeHeader(writer: Appendable) {
        writer.append("date,description,amount,currency,category,vendor,originalCurrency,homeCurrency,conversionRate,originalAmount\n")
    }

    fun writeExpense(writer: Appendable, expense: ExportTransaction, categories: Map<Long, String>) {
        val date = Instant.ofEpochMilli(expense.date).atZone(ZoneId.systemDefault()).format(dateFormat)
        val description = CsvCellSanitizer.sanitize(expense.merchant)
        val amount = CsvCellSanitizer.sanitize(
            try {
                CurrencyFormatter.formatForExport(expense.amount)
            } catch (e: IllegalArgumentException) {
                "INVALID"
            }
        )
        val category = CsvCellSanitizer.sanitize(categories[expense.categoryId] ?: "Uncategorized")
        val vendor = CsvCellSanitizer.sanitize(expense.merchant)
        val conversionRate = expense.conversionRateUsed?.let {
            try {
                CurrencyFormatter.formatForExport(it)
            } catch (e: IllegalArgumentException) {
                "INVALID"
            }
        } ?: ""
        val originalAmount = expense.originalAmount?.let {
            try {
                CurrencyFormatter.formatForExport(it)
            } catch (e: IllegalArgumentException) {
                "INVALID"
            }
        } ?: ""

        writer.append(
            "${CsvCellSanitizer.sanitize(date)},$description,$amount,${CsvCellSanitizer.sanitize(expense.currency)}," +
                "$category,$vendor," +
                "${CsvCellSanitizer.sanitize(expense.originalCurrency)}," +
                "${CsvCellSanitizer.sanitize(expense.homeCurrency)}," +
                "$conversionRate,$originalAmount\n"
        )
    }

}
