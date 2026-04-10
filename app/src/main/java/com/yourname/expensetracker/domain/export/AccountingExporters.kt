package com.yourname.expensetracker.domain.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CRITICAL FIX (CRITICAL-4): Safe CSV/IIF exporters with proper escaping.
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
        writer.append("!TRNS\tDATE\tACCNT\tAMOUNT\tMEMO\tNAME\tCLASS\n")
        writer.append("!SPL\tDATE\tACCNT\tAMOUNT\tMEMO\tNAME\tCLASS\n")
        writer.append("!ENDTRNS\n")
    }

    fun writeExpense(writer: Appendable, expense: ExportTransaction, categories: Map<Long, String>) {
        val date = Instant.ofEpochMilli(expense.date).atZone(ZoneId.systemDefault()).format(dateFormat)
        val account = categories[expense.categoryId] ?: "Uncategorized"
        val amount = expense.amount
        val memo = escapeIifField(expense.notes ?: "")
        val name = escapeIifField(expense.merchant)

        writer.append("TRNS\t${escapeIifField(date)}\t${escapeIifField(account)}\t$amount\t$memo\t$name\t\n")
        writer.append("SPL\t${escapeIifField(date)}\t${escapeIifField(account)}\t-$amount\t$memo\t$name\t\n")
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
        writer.append("Date,Description,Amount,Account,Reference\n")
    }

    fun writeExpense(writer: Appendable, expense: ExportTransaction, categories: Map<Long, String>) {
        val date = Instant.ofEpochMilli(expense.date).atZone(ZoneId.systemDefault()).format(dateFormat)
        val description = escapeCsvField(expense.merchant)
        val amount = expense.amount
        val account = escapeCsvField(categories[expense.categoryId] ?: "Uncategorized")
        val reference = expense.id.toString()

        writer.append("${escapeCsvField(date)},$description,$amount,$account,$reference\n")
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
        writer.append("date,description,amount,category,vendor\n")
    }

    fun writeExpense(writer: Appendable, expense: ExportTransaction, categories: Map<Long, String>) {
        val date = Instant.ofEpochMilli(expense.date).atZone(ZoneId.systemDefault()).format(dateFormat)
        val description = escapeCsvField(expense.merchant)
        val amount = expense.amount
        val category = escapeCsvField(categories[expense.categoryId] ?: "Uncategorized")
        val vendor = escapeCsvField(expense.merchant)

        writer.append("${escapeCsvField(date)},$description,$amount,$category,$vendor\n")
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
