package com.yourname.expensetracker.domain.receipt

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptParser @Inject constructor() {

    data class ParsedReceipt(
        val merchantName: String?,
        val total: Double?,
        val subtotal: Double?,
        val tax: Double?,
        val date: Long?,
        val currency: String,
        val lineItems: List<LineItem>,
        val confidence: Float
    )

    data class LineItem(
        val description: String,
        val quantity: Double?,
        val unitPrice: Double?,
        val totalPrice: Double
    )

    // Total amount patterns (Greek + English receipts)
    private val totalPatterns = listOf(
        // Greek patterns
        Pattern.compile(
            """(?:ΣΥΝΟΛΟ|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΓΕΝΙΚΟ\s*ΣΥΝΟΛΟ|TOTAL)\s*[:\s]*€?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // English patterns
        Pattern.compile(
            """(?:TOTAL|GRAND\s*TOTAL|AMOUNT\s*DUE|BALANCE\s*DUE|NET\s*TOTAL)\s*[:\s]*[€$£]?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE
        ),
        // Amount with currency symbol at end
        Pattern.compile(
            """(?:TOTAL|ΣΥΝΟΛΟ)\s*[:\s]*(\d+[.,]\d{2})\s*(?:€|EUR)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // Amount at bottom (common format) - standalone € amount
        Pattern.compile(
            """(?:€|EUR)\s*(\d+[.,]\d{2})\s*$""",
            Pattern.MULTILINE
        ),
        // Standalone large amount near end of text
        Pattern.compile(
            """^\s*(\d+[.,]\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        )
    )

    // Tax patterns
    private val taxPatterns = listOf(
        Pattern.compile(
            """(?:ΦΠΑ|Φ\.?Π\.?Α\.?|VAT|TAX|TVA)\s*[\d%]*\s*[:\s]*€?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        Pattern.compile(
            """(?:TAX|VAT)\s*(?:\d+%?)?\s*[:\s]*[€$£]?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE
        )
    )

    // Date patterns
    private val datePatterns = listOf(
        Pattern.compile("""(\d{2})[/\-.](\d{2})[/\-.](\d{4})"""),  // DD/MM/YYYY
        Pattern.compile("""(\d{4})[/\-.](\d{2})[/\-.](\d{2})"""),  // YYYY/MM/DD
        Pattern.compile("""(\d{2})[/\-.](\d{2})[/\-.](\d{2})""")   // DD/MM/YY
    )

    // Line item pattern: "description  price" with at least 2 spaces or tab
    private val lineItemPatterns = listOf(
        // "Item description    12.50" or "Item description    12,50€"
        Pattern.compile(
            """^(.{3,40}?)\s{2,}(\d+[.,]\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        ),
        // "1 x Item description  12.50"
        Pattern.compile(
            """^(\d+)\s*[xX×]\s*(.{3,35}?)\s{2,}(\d+[.,]\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        )
    )

    // Subtotal patterns (to distinguish from total)
    private val subtotalPatterns = listOf(
        Pattern.compile(
            """(?:SUBTOTAL|SUB\s*TOTAL|ΥΠΟΣΥΝΟΛΟ|ΥΠΟ\s*ΣΥΝΟΛΟ|ΜΕΡΙΚΟ)\s*[:\s]*€?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Discount patterns
    private val discountPatterns = listOf(
        Pattern.compile(
            """(?:DISCOUNT|ΕΚΠΤΩΣΗ|SAVINGS?)\s*[:\s]*-?\s*€?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    fun parse(ocrText: String): ParsedReceipt {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }

        // 1. Extract merchant (usually first 1-3 lines)
        val merchant = extractMerchant(lines)

        // 2. Extract total (scan from bottom up — total is usually at the end)
        val total = extractTotal(ocrText)

        // 3. Extract subtotal
        val subtotal = extractSubtotal(ocrText)

        // 4. Extract tax
        val tax = extractTax(ocrText)

        // 5. Extract date
        val date = extractDate(ocrText)

        // 6. Extract line items
        val lineItems = extractLineItems(ocrText)

        // 7. Cross-validate: if we found items but no total, sum them
        val finalTotal = total ?: lineItems.sumOf { it.totalPrice }.takeIf { it > 0 }

        // 8. Calculate subtotal if we have total and tax
        val finalSubtotal = subtotal
            ?: if (finalTotal != null && tax != null) finalTotal - tax else null

        // 9. Confidence based on what we found
        val confidence = calculateConfidence(merchant, finalTotal, date, lineItems, tax)

        return ParsedReceipt(
            merchantName = merchant,
            total = finalTotal,
            subtotal = finalSubtotal,
            tax = tax,
            date = date,
            currency = detectCurrency(ocrText),
            lineItems = lineItems,
            confidence = confidence
        )
    }

    private fun extractMerchant(lines: List<String>): String? {
        // Skip noise patterns commonly found at top of receipts
        val skipPatterns = listOf(
            Regex("""(?i)(ΑΦΜ|ΔΟΥ|ΤΗΛ|TEL|FAX|VAT|RECEIPT|ΑΠΟΔΕΙΞΗ|ΤΙΜΟΛΟΓΙΟ)"""),
            Regex("""(?i)(www\.|http|@|\.com|\.gr)"""),
            Regex("""^\d{5,}$"""),  // Long number (phone, tax ID)
            Regex("""^\d+[/\-.]"""),  // Date-like
            Regex("""^[\d\s.,€$£]+$"""),  // Just numbers/currency
            Regex("""(?i)(ΤΑΜΕΙΟ|CASHIER|REGISTER|ΤΑΜΕΙΑΚΗ)"""),
            Regex("""^\*+$""")  // Just asterisks
        )

        val candidateLines = mutableListOf<String>()

        for (line in lines.take(7)) {
            val cleaned = line.trim()
            if (cleaned.length < 3) continue
            if (skipPatterns.any { it.containsMatchIn(cleaned) }) continue
            candidateLines.add(cleaned)
            if (candidateLines.size >= 2) break  // Usually merchant is 1-2 lines
        }

        return if (candidateLines.isNotEmpty()) {
            candidateLines.joinToString(" ").take(50).trim()
        } else null
    }

    private fun extractTotal(text: String): Double? {
        val allMatches = mutableListOf<Pair<Double, Int>>() // value, position

        for (pattern in totalPatterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val amount = matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
                if (amount != null && amount > 0 && amount < 50000) {
                    allMatches.add(Pair(amount, matcher.start()))
                }
            }
        }

        if (allMatches.isEmpty()) return null

        // Strategy: prefer matches closer to the bottom of the text
        // If multiple "TOTAL" matches, the LAST one is usually the grand total
        return allMatches
            .sortedByDescending { it.second }  // Bottom of receipt first
            .firstOrNull()?.first
    }

    private fun extractSubtotal(text: String): Double? {
        for (pattern in subtotalPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun extractTax(text: String): Double? {
        for (pattern in taxPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun extractDate(text: String): Long? {
        for (pattern in datePatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return try {
                    val groups = (1..matcher.groupCount()).map { matcher.group(it) }
                    val cal = Calendar.getInstance()

                    when {
                        groups[0].length == 4 -> { // YYYY/MM/DD
                            val year = groups[0].toInt()
                            val month = groups[1].toInt()
                            val day = groups[2].toInt()
                            if (month in 1..12 && day in 1..31) {
                                cal.set(year, month - 1, day, 0, 0, 0)
                                cal.timeInMillis
                            } else null
                        }
                        groups[2].length == 4 -> { // DD/MM/YYYY
                            val day = groups[0].toInt()
                            val month = groups[1].toInt()
                            val year = groups[2].toInt()
                            if (month in 1..12 && day in 1..31 && year in 2000..2099) {
                                cal.set(year, month - 1, day, 0, 0, 0)
                                cal.timeInMillis
                            } else null
                        }
                        else -> { // DD/MM/YY
                            val day = groups[0].toInt()
                            val month = groups[1].toInt()
                            val year = 2000 + groups[2].toInt()
                            if (month in 1..12 && day in 1..31) {
                                cal.set(year, month - 1, day, 0, 0, 0)
                                cal.timeInMillis
                            } else null
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }

    private fun extractLineItems(text: String): List<LineItem> {
        val items = mutableListOf<LineItem>()

        // Skip lines that look like totals/subtotals
        val skipLinePattern = Regex(
            """(?i)(TOTAL|ΣΥΝΟΛΟ|VAT|ΦΠΑ|CHANGE|ΡΕΣΤΑ|CASH|CARD|VISA|MASTER|SUBTOTAL|ΥΠΟΣΥΝΟΛΟ|ΜΕΤΡΗΤΑ|ΚΑΡΤΑ|ΠΛΗΡΩΜΗ|PAYMENT|DISCOUNT|ΕΚΠΤΩΣΗ)"""
        )

        // Pattern 1: "description   amount"
        val matcher1 = lineItemPatterns[0].matcher(text)
        while (matcher1.find()) {
            val desc = matcher1.group(1)?.trim() ?: continue
            val price = matcher1.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: continue
            if (skipLinePattern.containsMatchIn(desc)) continue
            if (price <= 0 || price > 10000) continue

            items.add(
                LineItem(
                    description = desc,
                    quantity = null,
                    unitPrice = null,
                    totalPrice = price
                )
            )
        }

        // Pattern 2: "qty x description   amount"
        val matcher2 = lineItemPatterns[1].matcher(text)
        while (matcher2.find()) {
            val qty = matcher2.group(1)?.toDoubleOrNull() ?: continue
            val desc = matcher2.group(2)?.trim() ?: continue
            val price = matcher2.group(3)?.replace(",", ".")?.toDoubleOrNull() ?: continue
            if (skipLinePattern.containsMatchIn(desc)) continue
            if (price <= 0 || price > 10000) continue

            items.add(
                LineItem(
                    description = desc,
                    quantity = qty,
                    unitPrice = if (qty > 0) price / qty else null,
                    totalPrice = price
                )
            )
        }

        return items
    }

    private fun detectCurrency(text: String): String {
        return when {
            text.contains("€") || text.contains("EUR", ignoreCase = true) ||
                    text.contains("ΕΥΡΩ", ignoreCase = true) -> "EUR"
            text.contains("$") || text.contains("USD", ignoreCase = true) -> "USD"
            text.contains("£") || text.contains("GBP", ignoreCase = true) -> "GBP"
            else -> "EUR"
        }
    }

    private fun calculateConfidence(
        merchant: String?,
        total: Double?,
        date: Long?,
        items: List<LineItem>,
        tax: Double?
    ): Float {
        var score = 0f
        if (merchant != null) score += 0.15f
        if (total != null) score += 0.40f  // Most important
        if (date != null) score += 0.15f
        if (items.isNotEmpty()) score += 0.15f
        if (tax != null) score += 0.05f

        // Bonus: items sum matches total (cross-validation)
        if (total != null && items.isNotEmpty()) {
            val itemsSum = items.sumOf { it.totalPrice }
            val diff = kotlin.math.abs(total - itemsSum)
            if (diff < total * 0.05) { // Within 5%
                score += 0.10f
            }
        }

        return score.coerceIn(0f, 1f)
    }

    // Utility: serialize line items to JSON
    fun lineItemsToJson(items: List<LineItem>): String {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("description", item.description)
                put("totalPrice", item.totalPrice)
                item.quantity?.let { put("quantity", it) }
                item.unitPrice?.let { put("unitPrice", it) }
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    // Utility: deserialize line items from JSON
    fun lineItemsFromJson(json: String?): List<LineItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                LineItem(
                    description = obj.getString("description"),
                    totalPrice = obj.getDouble("totalPrice"),
                    quantity = if (obj.has("quantity")) obj.getDouble("quantity") else null,
                    unitPrice = if (obj.has("unitPrice")) obj.getDouble("unitPrice") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
