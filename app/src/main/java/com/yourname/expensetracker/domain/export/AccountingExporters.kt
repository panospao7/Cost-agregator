package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.Expense
import java.text.SimpleDateFormat
import java.util.*

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
                val memo = expense.notes ?: ""
                val name = expense.merchant

                append("TRNS\t$date\t$account\t$amount\t$memo\t$name\t\n")
                append("SPL\t$date\t$account\t-$amount\t$memo\t$name\t\n")
                append("ENDTRNS\n")
            }
        }
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
                val description = expense.merchant.replace(",", " ") // Escape commas
                val amount = expense.amount
                val account = categories[expense.categoryId] ?: "Uncategorized"
                val reference = expense.id.toString()

                append("$date,$description,$amount,$account,$reference\n")
            }
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
                val description = expense.merchant.replace(",", " ")
                val amount = expense.amount
                val category = categories[expense.categoryId] ?: "Uncategorized"
                val vendor = expense.merchant.replace(",", " ")

                append("$date,$description,$amount,$category,$vendor\n")
            }
        }
    }
}
