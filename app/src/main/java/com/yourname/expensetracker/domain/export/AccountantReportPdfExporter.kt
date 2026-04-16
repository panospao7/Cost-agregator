package com.yourname.expensetracker.domain.export

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.yourname.expensetracker.data.database.entity.Expense
import java.io.ByteArrayOutputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class AccountantReportPdfExporter @Inject constructor() {

    fun export(
        expenses: List<Expense>,
        categories: Map<Long, String>,
        startDate: Long,
        endDate: Long
    ): ByteArray {
        val document = PdfDocument()

        return try {
            val writer = PdfReportWriter(document)
            val monthFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val period = "${monthFormat.format(Date(startDate))} - ${monthFormat.format(Date(endDate))}"
            val expensesByCurrency = expenses.groupBy { it.reportCurrencyCode() }
                .toSortedMap()

            writer.writeTitle("Accountant Report")
            writer.writeBody("Period: $period")
            writer.writeBody("Generated: ${timestampFormat.format(Date())}")
            writer.blankLine()

            writer.writeHeading("Summary")
            writer.writeBody("Transaction Count: ${expenses.size}")
            expensesByCurrency.forEach { (currency, currencyExpenses) ->
                writer.writeBody(
                    "$currency Total Expenses: ${formatAmount(currencyExpenses.sumOf { it.effectiveAmount }, currency)}"
                )
                writer.writeBody("$currency Transaction Count: ${currencyExpenses.size}")
            }

            expensesByCurrency.forEach { (currency, currencyExpenses) ->
                writer.blankLine()
                writer.writeHeading("$currency Category Breakdown")

                val totalForCurrency = currencyExpenses.sumOf { it.effectiveAmount }
                currencyExpenses
                    .groupBy { categories[it.categoryId] ?: "Uncategorized" }
                    .toList()
                    .sortedByDescending { (_, categoryExpenses) ->
                        categoryExpenses.sumOf { it.effectiveAmount }
                    }
                    .forEach { (categoryName, categoryExpenses) ->
                        val categoryTotal = categoryExpenses.sumOf { it.effectiveAmount }
                        val percentage = if (totalForCurrency > 0.0) {
                            categoryTotal / totalForCurrency * 100.0
                        } else {
                            0.0
                        }
                        writer.writeBody(
                            "$categoryName: ${formatAmount(categoryTotal, currency)} " +
                                "(${categoryExpenses.size} transactions, ${ONE_DECIMAL_FORMAT.format(percentage)}%)"
                        )
                    }

                val largeExpenses = currencyExpenses
                    .filter { it.effectiveAmount > LARGE_TRANSACTION_THRESHOLD }
                    .sortedByDescending { it.effectiveAmount }

                if (largeExpenses.isNotEmpty()) {
                    writer.blankLine()
                    writer.writeHeading("$currency Large Transactions (Review)")
                    largeExpenses.forEach { expense ->
                        writer.writeBody(
                            "- ${expense.merchant}: ${formatAmount(expense.effectiveAmount, currency)} on " +
                                "${DAY_FORMAT.format(Date(expense.date))}"
                        )
                    }
                }
            }

            writer.finish()

            ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
    }

    private fun formatAmount(amount: Double, currency: String): String {
        return "$currency ${TWO_DECIMAL_FORMAT.format(amount)}"
    }

    private fun Expense.reportCurrencyCode(): String {
        val normalized = currency.trim().uppercase(Locale.ROOT)
        return normalized.ifEmpty { "UNKNOWN" }
    }

    private class PdfReportWriter(
        private val document: PdfDocument
    ) {
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
        }

        private var pageNumber = 0
        private var currentPage: PdfDocument.Page? = null
        private var yPosition = PAGE_MARGIN.toFloat()

        fun writeTitle(text: String) {
            writeText(text, titlePaint)
        }

        fun writeHeading(text: String) {
            writeText(text, headingPaint)
        }

        fun writeBody(text: String) {
            writeText(text, bodyPaint)
        }

        fun blankLine() {
            ensureSpace(lineHeight(bodyPaint) / 2f)
            yPosition += lineHeight(bodyPaint) / 2f
        }

        fun finish() {
            currentPage?.let(document::finishPage)
            currentPage = null
        }

        private fun writeText(text: String, paint: Paint) {
            val lines = wrapText(text, paint)
            lines.forEach { line ->
                val lineHeight = lineHeight(paint)
                ensureSpace(lineHeight)
                currentPage!!.canvas.drawText(line, PAGE_MARGIN.toFloat(), yPosition + paint.textSize, paint)
                yPosition += lineHeight
            }
        }

        private fun ensureSpace(requiredHeight: Float) {
            if (currentPage == null || yPosition + requiredHeight > PAGE_HEIGHT - PAGE_MARGIN) {
                startNewPage()
            }
        }

        private fun startNewPage() {
            currentPage?.let(document::finishPage)
            pageNumber += 1
            currentPage = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            )
            yPosition = PAGE_MARGIN.toFloat()
        }

        private fun wrapText(text: String, paint: Paint): List<String> {
            val wrappedLines = mutableListOf<String>()

            text.replace("\r", "")
                .split('\n')
                .forEach { paragraph ->
                    if (paragraph.isBlank()) {
                        wrappedLines += ""
                        return@forEach
                    }

                    var currentLine = ""
                    paragraph.split(Regex("\\s+"))
                        .filter { it.isNotEmpty() }
                        .forEach { word ->
                            val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
                            if (paint.measureText(candidate) <= CONTENT_WIDTH) {
                                currentLine = candidate
                            } else if (currentLine.isEmpty()) {
                                val splitWordLines = splitLongWord(word, paint)
                                wrappedLines += splitWordLines.dropLast(1)
                                currentLine = splitWordLines.last()
                            } else {
                                wrappedLines += currentLine
                                currentLine = if (paint.measureText(word) <= CONTENT_WIDTH) {
                                    word
                                } else {
                                    val splitWordLines = splitLongWord(word, paint)
                                    wrappedLines += splitWordLines.dropLast(1)
                                    splitWordLines.last()
                                }
                            }
                        }

                    if (currentLine.isNotEmpty()) {
                        wrappedLines += currentLine
                    }
                }

            return if (wrappedLines.isEmpty()) listOf("") else wrappedLines
        }

        private fun splitLongWord(word: String, paint: Paint): List<String> {
            val parts = mutableListOf<String>()
            var current = ""

            word.forEach { character ->
                val candidate = current + character
                if (paint.measureText(candidate) <= CONTENT_WIDTH || current.isEmpty()) {
                    current = candidate
                } else {
                    parts += current
                    current = character.toString()
                }
            }

            if (current.isNotEmpty()) {
                parts += current
            }

            return parts
        }

        private fun lineHeight(paint: Paint): Float = paint.textSize * LINE_HEIGHT_MULTIPLIER
    }

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val PAGE_MARGIN = 40
        const val CONTENT_WIDTH = (PAGE_WIDTH - (PAGE_MARGIN * 2)).toFloat()
        const val LARGE_TRANSACTION_THRESHOLD = 500.0
        const val LINE_HEIGHT_MULTIPLIER = 1.4f

        val TWO_DECIMAL_FORMAT = DecimalFormat("0.00")
        val ONE_DECIMAL_FORMAT = DecimalFormat("0.0")
        val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
}
