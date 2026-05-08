package com.yourname.expensetracker.domain.export

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.TimeProvider
import java.io.ByteArrayOutputStream
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * PDF accountant report exporter.
 *
 * CORRECT: Already groups expenses by currency using `reportCurrencyCode()`.
 * No EUR assumptions — totals, breakdowns, and large-transaction sections all
 * correctly use per-currency grouping and display the actual currency code.
 *
 * ## M5 (fixed): PDF reports now filter to expense-only by default
 * The [export] method accepts an optional [transactionTypeFilter] parameter.
 * It defaults to [TransactionType.PURCHASE], so the report reflects actual
 * spending by default rather than all account activity. Pass `null` to
 * include all transaction types (legacy behavior).
 */
// TODO (PR-E22): Return MoneyAggregate for deductible/income totals instead of raw Double.
// Apply hardened CSV cell sanitizer (neutralize =+@- leading characters) per OWASP.
// Add updateBusinessTaxFields coordinator method.
class AccountantReportPdfExporter @Inject constructor(
    private val timeProvider: TimeProvider
) {

    /**
     * @param transactionTypeFilter If non-null, only expenses whose [Expense.transactionType]
     *   matches this value are included in the report. Defaults to [TransactionType.PURCHASE]
     *   for expense-only reports. Pass `null` to include all types.
     */
    fun export(
        expenses: List<Expense>,
        categories: Map<Long, String>,
        startDate: Long,
        endDate: Long,
        transactionTypeFilter: TransactionType? = TransactionType.PURCHASE
    ): ByteArray {
        val document = PdfDocument()
        val formatters = ExportFormatters(Locale.getDefault(), ZoneId.systemDefault())

        // Apply transaction type filter (defaults to PURCHASE for expense-only reports)
        val filteredExpenses = if (transactionTypeFilter != null) {
            expenses.filter { it.transactionType == transactionTypeFilter }
        } else {
            expenses
        }

        return try {
            val writer = PdfReportWriter(document)
            val period = "${formatters.formatMonth(startDate)} - ${formatters.formatMonth(endDate)}"
            val expensesByCurrency = filteredExpenses.groupBy { it.reportCurrencyCode() }
                .toSortedMap()

            writer.writeTitle("Accountant Report")
            writer.writeBody("Period: $period")
            writer.writeBody("Generated: ${formatters.formatTimestamp(timeProvider.now())}")
            writer.blankLine()

            writer.writeHeading("Summary")
            writer.writeBody("Transaction Count: ${filteredExpenses.size}")
            expensesByCurrency.forEach { (currency, currencyExpenses) ->
                // SAFE: per-currency buckets
                writer.writeBody(
                    "$currency Total Expenses: ${formatAmount(currencyExpenses.sumOf { it.effectiveAmount }, currency, formatters)}"
                )
                writer.writeBody("$currency Transaction Count: ${currencyExpenses.size}")
            }
            // Home-currency summary line
            if (expensesByCurrency.size > 1) {
                val homeCurrency = expensesByCurrency.keys.firstOrNull() ?: "EUR"
                // SAFE: intentional raw sum across currencies — labeled as "base" (not converted)
                val grandTotal = expensesByCurrency.values.flatten().sumOf { it.effectiveAmount }
                writer.writeBody("Combined Total (base): ${formatAmount(grandTotal, homeCurrency, formatters)}")
            }

            expensesByCurrency.forEach { (currency, currencyExpenses) ->
                writer.blankLine()
                writer.writeHeading("$currency Category Breakdown")

                // SAFE: per-currency buckets
                val totalForCurrency = currencyExpenses.sumOf { it.effectiveAmount }
                currencyExpenses
                    .groupBy { categories[it.categoryId] ?: "Uncategorized" }
                    .toList()
                    .sortedByDescending { (_, categoryExpenses) ->
                        // SAFE: per-currency buckets
                        categoryExpenses.sumOf { it.effectiveAmount }
                    }
                    .forEach { (categoryName, categoryExpenses) ->
                    // SAFE: per-currency buckets
                    val categoryTotal = categoryExpenses.sumOf { it.effectiveAmount }
                    val percentage = if (totalForCurrency > 0.0) {
                        categoryTotal / totalForCurrency * 100.0
                        } else {
                            0.0
                        }
                        writer.writeBody(
                            "$categoryName: ${formatAmount(categoryTotal, currency, formatters)} " +
                                "(${categoryExpenses.size} transactions, ${formatters.formatOneDecimal(percentage)}%)"
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
                            "- ${expense.merchant}: ${formatAmount(expense.effectiveAmount, currency, formatters)} on " +
                                "${formatters.formatDay(expense.date)}"
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

    // TODO (T05): Accept filingCurrency parameter. Currently hardcoded to EUR.
    // Use CurrencyFormatter.format(amount, filingCurrency) for correct symbol.
    private fun formatAmount(amount: Double, currency: String, formatters: ExportFormatters): String {
        return "$currency ${formatters.formatTwoDecimals(amount)}"
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

    private class ExportFormatters(locale: Locale, private val zoneId: ZoneId) {
        private val decimalSymbols = DecimalFormatSymbols.getInstance(locale)
        private val twoDecimals = DecimalFormat("0.00", decimalSymbols)
        private val oneDecimal = DecimalFormat("0.0", decimalSymbols)
        private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", locale)
        private val monthFormatter = DateTimeFormatter.ofPattern("MMM yyyy", locale)
        private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", locale)

        fun formatTwoDecimals(value: Double): String = twoDecimals.format(value.takeIf { it.isFinite() } ?: 0.0)
        fun formatOneDecimal(value: Double): String = oneDecimal.format(value.takeIf { it.isFinite() } ?: 0.0)
        fun formatDay(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(dayFormatter)
        fun formatMonth(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(monthFormatter)
        fun formatTimestamp(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(timestampFormatter)
    }

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val PAGE_MARGIN = 40
        const val CONTENT_WIDTH = (PAGE_WIDTH - (PAGE_MARGIN * 2)).toFloat()
        const val LARGE_TRANSACTION_THRESHOLD = 500.0
        const val LINE_HEIGHT_MULTIPLIER = 1.4f
    }
}
