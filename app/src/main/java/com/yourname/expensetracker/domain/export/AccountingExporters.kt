package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.Expense
import java.text.SimpleDateFormat
import java.util.*

/**
 * CRITICAL FIX (CRITICAL-4): Safe CSV/IIF exporters with proper escaping.
 * 
 * Replaces manual string concatenation with proper field escaping to prevent
 * injection attacks and format corruption from special characters in data.
 * 
 * Security: All fields are escaped to prevent delimiter injection
 * Correctness: Handles commas, quotes, tabs, and newlines in data
 */

class QuickBooksIIFExporter {
    private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)

    fun export(expenses: List<Expense>, categories: Map<Long, String>): String {
        return buildString {
            // Header
            append("!TRNS\tDATE\tACCNT\tAMOUNT\tMEMO\tNAME\tCLASS\n")
            append("!SPL\tDATE\tACCNT\tAMOUNT\tMEMO\tNAME\tCLASS\n")
            append("!ENDTRNS\n")

            // Transactions
            expenses.forEach { expense ->
                val date = dateFormat.format(Date(expense.date))
                val account = categories[expense.categoryId] ?: "Uncategorized"
                val amount = expense.amount
                // CRITICAL FIX: Escape fields that might contain tabs or newlines
                val memo = escapeIifField(expense.notes ?: "")
                val name = escapeIifField(expense.merchant)

                append("TRNS\t${escapeIifField(date)}\t${escapeIifField(account)}\t$amount\t$memo\t$name\t\n")
                append("SPL\t${escapeIifField(date)}\t${escapeIifField(account)}\t-$amount\t$memo\t$name\t\n")
                append("ENDTRNS\n")
            }
        }
    }
    
    /**
     * CRITICAL: Escape IIF field to prevent delimiter injection.
     * IIF uses tabs as delimiters, so we must escape/remove them.
     */
    private fun escapeIifField(field: String): String {
        return field
            .replace("\t", " ")  // Replace tabs with space (tab is delimiter)
            .replace("\n", " ")  // Replace newlines with space
            .replace("\r", "")   // Remove carriage returns
            .trim()
    }
}

class XeroCSVExporter {
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.UK)

    fun export(expenses: List<Expense>, categories: Map<Long, String>): String {
        return buildString {
            // Header
            append("Date,Description,Amount,Account,Reference\n")

            // Transactions
            expenses.forEach { expense ->
                val date = dateFormat.format(Date(expense.date))
                // CRITICAL FIX: Proper CSV escaping according to RFC 4180
                val description = escapeCsvField(expense.merchant)
                val amount = expense.amount
                val account = escapeCsvField(categories[expense.categoryId] ?: "Uncategorized")
                val reference = expense.id.toString()

                append("${escapeCsvField(date)},$description,$amount,$account,$reference\n")
            }
        }
    }
    
    /**
     * HIGH-05 FIX: Proper CSV field escaping with formula injection prevention.
     * - Trim whitespace first, then check first non-whitespace character
     * - Prefix fields starting with =, +, -, @ with single quote (prevents formula execution)
     * - If field contains comma, quote, or newline: wrap in quotes
     * - If field contains quotes: double them (escape)
     */
    private fun escapeCsvField(field: String): String {
        val trimmed = field.trim()
        if (trimmed.isEmpty()) return field
        
        // Formula injection prevention: check first non-whitespace character
        val firstChar = trimmed.first()
        val neutralizedField = if (firstChar == '=' || firstChar == '+' || 
                                   firstChar == '-' || firstChar == '@') {
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
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun export(expenses: List<Expense>, categories: Map<Long, String>): String {
        return buildString {
            // Header
            append("date,description,amount,category,vendor\n")

            // Transactions
            expenses.forEach { expense ->
                val date = dateFormat.format(Date(expense.date))
                // CRITICAL FIX: Proper CSV escaping
                val description = escapeCsvField(expense.merchant)
                val amount = expense.amount
                val category = escapeCsvField(categories[expense.categoryId] ?: "Uncategorized")
                val vendor = escapeCsvField(expense.merchant)

                append("${escapeCsvField(date)},$description,$amount,$category,$vendor\n")
            }
        }
    }
    
    /**
     * HIGH-05 FIX: Proper CSV field escaping with formula injection prevention.
     * - Trim whitespace first, then check first non-whitespace character
     * - Prefix fields starting with =, +, -, @ with single quote (prevents formula execution)
     * - If field contains comma, quote, or newline: wrap in quotes
     * - If field contains quotes: double them (escape)
     */
    private fun escapeCsvField(field: String): String {
        val trimmed = field.trim()
        if (trimmed.isEmpty()) return field
        
        // Formula injection prevention: check first non-whitespace character
        val firstChar = trimmed.first()
        val neutralizedField = if (firstChar == '=' || firstChar == '+' || 
                                   firstChar == '-' || firstChar == '@') {
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
