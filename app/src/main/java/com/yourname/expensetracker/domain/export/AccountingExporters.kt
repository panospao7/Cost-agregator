package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.domain.util.CurrencyFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
        val safeAmount = expense.amount.takeIf { it.isFinite() } ?: 0.0
        val amount = CurrencyFormatter.formatForExport(safeAmount)
        val splitAmount = CurrencyFormatter.formatForExport(-safeAmount)
        val memo = escapeIifField(expense.notes ?: "")
        val name = escapeIifField(expense.merchant)

        writer.append("TRNS\t${escapeIifField(date)}\t${escapeIifField(fundingAccount)}\t$amount\t${escapeIifField(expense.currency)}\t$memo\t$name\t\n")
        writer.append("SPL\t${escapeIifField(date)}\t${escapeIifField(categoryAccount)}\t$splitAmount\t${escapeIifField(expense.currency)}\t$memo\t$name\t\n")
        writer.append("ENDTRNS\n")
    }

    private fun escapeIifField(field: String): String {
        return field
            .replace("\t", " ")
            .replace("\n", " ")
            .replace("\r", "")
            .trim()
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
        val description = escapeCsvField(expense.merchant)
        val amount = escapeCsvField(CurrencyFormatter.formatForExport(expense.amount))
        val account = escapeCsvField(categories[expense.categoryId] ?: "Uncategorized")
        val reference = expense.id.toString()
        val conversionRate = expense.conversionRateUsed?.let { CurrencyFormatter.formatForExport(it) } ?: ""
        val originalAmount = expense.originalAmount?.let { CurrencyFormatter.formatForExport(it) } ?: ""

        writer.append(
            "${escapeCsvField(date)},$description,$amount,${escapeCsvField(expense.currency)}," +
                "$account,$reference," +
                "${escapeCsvField(expense.originalCurrency)}," +
                "${escapeCsvField(expense.homeCurrency)}," +
                "$conversionRate,$originalAmount\n"
        )
    }

    private fun escapeCsvField(field: String): String {
        val trimmed = field.trim()
        if (trimmed.isEmpty()) return field

        val firstChar = trimmed.first()
        val neutralizedField = if (
            firstChar == '=' || firstChar == '+' ||
            firstChar == '-' || firstChar == '@'
        ) {
            "'$field"
        } else {
            field
        }

        val needsQuoting = neutralizedField.contains(",") ||
            neutralizedField.contains("\"") ||
            neutralizedField.contains("\n") ||
            neutralizedField.contains("\r")

        return if (needsQuoting) {
            "\"" + neutralizedField.replace("\"", "\"\"") + "\""
        } else {
            neutralizedField
        }
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
        val description = escapeCsvField(expense.merchant)
        val amount = escapeCsvField(CurrencyFormatter.formatForExport(expense.amount))
        val category = escapeCsvField(categories[expense.categoryId] ?: "Uncategorized")
        val vendor = escapeCsvField(expense.merchant)
        val conversionRate = expense.conversionRateUsed?.let { CurrencyFormatter.formatForExport(it) } ?: ""
        val originalAmount = expense.originalAmount?.let { CurrencyFormatter.formatForExport(it) } ?: ""

        writer.append(
            "${escapeCsvField(date)},$description,$amount,${escapeCsvField(expense.currency)}," +
                "$category,$vendor," +
                "${escapeCsvField(expense.originalCurrency)}," +
                "${escapeCsvField(expense.homeCurrency)}," +
                "$conversionRate,$originalAmount\n"
        )
    }

    private fun escapeCsvField(field: String): String {
        val trimmed = field.trim()
        if (trimmed.isEmpty()) return field

        val firstChar = trimmed.first()
        val neutralizedField = if (
            firstChar == '=' || firstChar == '+' ||
            firstChar == '-' || firstChar == '@'
        ) {
            "'$field"
        } else {
            field
        }

        val needsQuoting = neutralizedField.contains(",") ||
            neutralizedField.contains("\"") ||
            neutralizedField.contains("\n") ||
            neutralizedField.contains("\r")

        return if (needsQuoting) {
            "\"" + neutralizedField.replace("\"", "\"\"") + "\""
        } else {
            neutralizedField
        }
    }
}
