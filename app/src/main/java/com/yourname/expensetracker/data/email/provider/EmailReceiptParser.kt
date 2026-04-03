package com.yourname.expensetracker.data.email.provider

import com.yourname.expensetracker.domain.util.AmountUtils
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Data class representing parsed receipt data from email.
 */
data class ParsedEmailReceipt(
    val merchant: String,
    val amount: Double,
    val currency: String,
    val date: Long,
    val items: List<ReceiptItem>,
    val orderNumber: String?,
    val confidence: Double
)

data class ReceiptItem(
    val description: String,
    val quantity: Int,
    val unitPrice: Double,
    val totalPrice: Double
)

/**
 * Interface for email receipt parsers.
 */
interface EmailReceiptParser {
    /**
     * Check if this parser can handle the given email.
     */
    fun canParse(sender: String, subject: String, body: String): Boolean

    /**
     * Parse the email body into structured receipt data.
     * Returns null if parsing fails.
     */
    fun parse(emailBody: String, receivedAt: Long): ParsedEmailReceipt?
}

/**
 * Base class with common parsing utilities.
 */
abstract class BaseEmailParser : EmailReceiptParser {
    
    protected val amountRegex = Regex("""(?:€|\\$|£|EUR|USD|GBP)?\\s*([+-]?\\d{1,3}(?:[.,\\s]\\d{3})*[.,]\\d{2})""")
    
    protected val datePatterns = listOf(
        SimpleDateFormat("MMM dd, yyyy", Locale.US),
        SimpleDateFormat("dd MMM yyyy", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US),
        SimpleDateFormat("dd/MM/yyyy", Locale.US),
        SimpleDateFormat("MM/dd/yyyy", Locale.US)
    )

    protected fun extractAmount(text: String): Double? {
        return amountRegex.find(text)?.groupValues?.get(1)?.let {
            AmountUtils.parseAmount(it.replace(" ", "").replace(",", "."))
        }
    }

    protected fun parseDate(dateStr: String): Long? {
        for (pattern in datePatterns) {
            try {
                return pattern.parse(dateStr)?.time
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    protected fun cleanHtml(text: String): String {
        return text
            .replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""&\w+;"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
