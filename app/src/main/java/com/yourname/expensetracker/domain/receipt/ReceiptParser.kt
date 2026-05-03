package com.yourname.expensetracker.domain.receipt

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber

/**
 * Parses raw OCR text from receipt images into structured data.
 *
 * ## N5: Tax amount duplicated (line item total + receipt total)
 * The [parse] method extracts `tax` independently from OCR text patterns
 * (via [extractTax]) and also computes a cross-validated `finalTotal`. If a
 * receipt includes the tax amount as a separate line item AND also as a
 * receipt total tax field, there is a risk that the tax value is counted
 * twice: once in the sum of line items and once in the explicit tax field.
 *
 * ## RCP-4 fix
 * The parser now sets [ParsedReceipt.taxInclusive] when it detects that the
 * sum of line items is within 5% of the receipt total AND a separate tax
 * value was also extracted. Downstream consumers should check this flag to
 * decide whether the total already includes the tax amount, avoiding
 * double-counting when computing subtotal = total - tax.
 */
@Singleton
class ReceiptParser @Inject constructor(
    private val merchantRules: MerchantRulesPolicy,
    private val timeProvider: TimeProvider
) {
    companion object {
        // Pre-compiled regex patterns for performance (Issue 2.13)
        private val GEO_STRIP_REGEX = Regex("""[><\}|▶]""")
        private val NUMBER_SPACE_FIX = Regex("""(?<=\d)[ \t\u00A0]+(?=[.,\d])""")
        private val SEPARATOR_SPACE_AFTER = Regex("""(?<=\d)[ \t\u00A0]+([.,])""")
        private val SEPARATOR_SPACE_BEFORE = Regex("""([.,])[ \t\u00A0]+(?=\d)""")
        private val COMPOUND_TOTAL = Regex("""ΣΥΝΟΛΙΚΗ\s+ΑΞΙΑ""")
        private val COMPOUND_SUBTOTAL = Regex("""ΚΑΘΑΡΗ\s+ΑΞΙΑ""")
        private val COMPOUND_GENIKO = Regex("""ΓΕΝΙΚΟ\s+ΣΥΝΟΛΟ""")
        private val COMPOUND_MERIKO = Regex("""ΜΕΡΙΚΟ\s+ΣΥΝΟΛΟ""")
        private val COMPOUND_TELIKI = Regex("""ΤΕΛΙΚΗ\s+ΑΞΙΑ""")
        private val DATE_OCR_FIX = Regex("""(\d{1,2})[-/][DO0](\d{1,2})[-/](\d{2,4})""")
        private val CLEAN_WORD_REGEX = Regex("""[^A-ZΑ-Ω0-9]""")
        private val ANY_NONSPACE = Regex("""\S+""")
        private val TIME_PATTERN = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")
        private val CHANGE_PATTERN = Regex("""(CHANGE|ΡΕΣΤΑ|RESTA|ΑΛΛΑΓΗ)""")
        private val QUANTITY_PREFIX_PATTERN = Regex("""^\d+\s*[xX*]\s*.+""")
    }

    data class ParsedReceipt(
        val merchantName: String?,
        val total: Double?,
        val subtotal: Double?,
        val tax: Double?,
        val date: Long?,
        val currency: String,
        val lineItems: List<LineItem>,
        val confidence: Float,
        /**
         * RCP-4 / RCP-14: Set to `true` when we detect that tax is already included
         * in both line item totals and the receipt total. Downstream consumers
         * should check this flag to avoid double-counting tax (e.g. when
         * computing subtotal = total - tax, if the total is tax-inclusive
         * and the line items already sum to the total including tax, the
         * subtraction would be incorrect for tax-exclusive calculations).
         *
         * ## When `taxInclusive == true`:
         * - **Do NOT** add tax to item-level totals — each [LineItem.totalPrice]
         *   already contains the proportional tax.
         * - **Do NOT** subtract tax from the receipt total when saving as expense amount.
         * - If a tax-exclusive subtotal is needed: `subtotal = total - tax`.
         * - The sum of line items approximates `total` (within 5%) — both include tax.
         *
         * ## When `taxInclusive == false` (default):
         * - The receipt total may be tax-exclusive; tax is a separate surcharge.
         * - Line items likely do not include tax either.
         * - `subtotal = total - tax` is the correct tax-exclusive amount.
         */
        val taxInclusive: Boolean = false
    )

    data class LineItem(
        val description: String,
        val quantity: Double?,
        val unitPrice: Double?,
        val totalPrice: Double
    )

    // Total amount patterns (Greek + English receipts)
    private val totalPatterns = listOf(
        // Look for our normalized keys (High confidence)
        Pattern.compile(
            """(?:TOTAL_KEY|AMOUNT_KEY|PAYMENT_KEY|CASH_KEY|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΣΥΝΟΛΟ|ΣYNONO|TOTAL|AMOUNT|CASH|METPHTA|ΜΕΤΡΗΤΑ)\s*[:\s]*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // Fallback for raw totals or noise in between
        Pattern.compile(
            """(?:TOTAL_KEY|TOTAL|ΣΥΝΟΛΟ|AMOUNT_KEY|ΠΟΣΟ|AMOUNT|ΣYNONO|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|CASH_KEY|CASH|ΜΕΤΡΗΤΑ|METPHTA).*?(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Tax patterns
    private val taxPatterns = listOf(
        Pattern.compile(
            """(?:VAT_KEY|VAT|TAX|Φ\.?Π\.?Α\.?)[^(\d+[.,]\d{2})]*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Date patterns
    private val datePatterns = listOf(
        Pattern.compile("""(\d{2})[/\-.](\d{2})[/\-.](\d{4}|\d{2})"""),  // DD/MM/YYYY or DD/MM/YY
        Pattern.compile("""(\d{4})[/\-.](\d{2})[/\-.](\d{2})""")   // YYYY/MM/DD
    )

    // Line item pattern: "description  price" with at least 2 spaces or tab
    private val lineItemPatterns = listOf(
        // "Item description    12.50" (fuzzy spaces in amount)
        Pattern.compile(
            """^(.{3,40}?)\s{2,}(\d+[\s.,]\s*\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        ),
        // "Quantity x Description   Sum"
        Pattern.compile(
            """^(\d+)\s*[xX*]\s*(.{3,40}?)\s{2,}(\d+[\s.,]\s*\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        ),
        // "Description @ UnitPrice   Sum"
        Pattern.compile(
            """^(.{3,40}?)\s*@\s*(\d+[\s.,]\d{2})\s{2,}(\d+[\s.,]\d{2})\s*$""",
            Pattern.MULTILINE
        ),
        // "Qty x Desc @ UnitPrice   Sum"
        Pattern.compile(
            """^(\d+)\s*[xX*]\s*(.{3,40}?)\s*@\s*(\d+[\s.,]\d{2})\s{2,}(\d+[\s.,]\d{2})\s*$""",
            Pattern.MULTILINE
        )
    )

    // Subtotal patterns (to distinguish from total)
    private val subtotalPatterns = listOf(
        Pattern.compile(
            """(?:SUBTOTAL_KEY|SUBTOTAL|ΥΠΟΣΥΝΟΛΟ|ΚΑΘΑΡΗ\s*ΑΞΙΑ)\s*[:\s]*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Discount patterns
    private val discountPatterns = listOf(
        Pattern.compile(
            """(?:DISCOUNT|ΕΚΠΤΩΣΗ|SAVINGS?|ΜΕΙΟΝ|ΕΚΠΤ)\s*[:\s]*-?\s*€?\s*(\d+[\s.,]\s*\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    fun parse(rawText: String, homeCurrency: String = "EUR"): ParsedReceipt {
        // 1. Pre-process text to fix OCR spacing issues and Greek characters
        val cleanedText = normalizeGreekOcr(rawText)
        val lines = cleanedText.lines().filter { it.isNotBlank() }

        // 2. Extract merchant
        val merchant = extractMerchant(lines)

        // 3. Extract date
        val date = extractDate(cleanedText)

        // 4. Extract total
        val total = extractTotal(lines)

        // 5. Extract subtotal (using original text as fallback or new logic if needed)
        val subtotal = extractSubtotal(cleanedText) ?: extractSubtotal(rawText)

        // 6. Extract tax
        val tax = extractTax(cleanedText) ?: extractTax(rawText)

        // 7. Extract line items
        val lineItems = extractLineItems(cleanedText)

        // 8. Cross-validate
        val finalTotal = total ?: lineItems.sumOf { it.totalPrice }.takeIf { it > 0 }

        // 9. Detect tax-inclusive: if tax was found separately AND line items
        //    sum is close to the total (within 5%), the tax is already embedded
        //    in both the line items and the receipt total. Mark as inclusive so
        //    downstream consumers avoid double-counting.
        //
        // RCP-14: When `taxInclusive == true`, the receipt total already contains
        // the tax amount and so do the item totals. Downstream consumers MUST
        // NOT subtract tax from line-item totals or add it to the subtotal;
        // doing so would count the tax twice.  The correct approach is:
        //   - Use `total` as-is for the expense amount.
        //   - If item-level amounts are needed, each LineItem.totalPrice already
        //     includes the proportional tax — do not add tax on top.
        //   - For a tax-exclusive subtotal, compute: subtotal = total - tax.
        //     The line items sum already approximates `total` (within 5%).
        val taxInclusive = if (finalTotal != null && tax != null && lineItems.isNotEmpty()) {
            val itemsSum = lineItems.sumOf { it.totalPrice }
            val diff = kotlin.math.abs(finalTotal - itemsSum)
            diff < finalTotal * 0.05
        } else false

        // 10. Calculate subtotal
        val finalSubtotal = subtotal
            ?: if (finalTotal != null && tax != null) finalTotal - tax else null

        // 11. Confidence - set to 0 if critical fields are missing
        val hasCriticalData = merchant != null || finalTotal != null || date != null
        val confidence = if (!hasCriticalData) {
            0f
        } else {
            calculateConfidence(merchant, finalTotal, date, lineItems, tax)
        }

        return ParsedReceipt(
            merchantName = merchant,
            total = finalTotal,
            subtotal = finalSubtotal,
            tax = tax,
            date = date,
            // Fallback chain: detected currency → explicit homeCurrency → "EUR" last resort
            currency = detectCurrency(cleanedText) ?: homeCurrency,
            lineItems = lineItems,
            confidence = confidence,
            taxInclusive = taxInclusive
        )
    }

    private fun normalizeGreekOcr(text: String): String {
        var normalized = text.uppercase()

        // --- PRE-PROCESSING: GEOMETRY STRIPPING & LATIN INTRUSION ---
        normalized = normalized.replace(GEO_STRIP_REGEX, " ")
        normalized = normalized.replace("ΠΟSΟ", "ΠΟΣΟ")

        // Exact Map based on OCR test document output
        val exactHallucinationMap = mapOf(
            "ZYNOAO" to "ΣΥΝΟΛΟ",
            "EYNONO" to "ΣΥΝΟΛΟ",
            "2YNONO" to "ΣΥΝΟΛΟ",
            "ZYNOIO" to "ΣΥΝΟΛΟ",
            "ZYN0/\\0" to "ΣΥΝΟΛΟ",
            "NAHPQTEO" to "ΠΛΗΡΩΤΕΟ",
            "NODO" to "ΠΟΣΟ",
            "NOZA" to "ΠΟΣΟ",
            "NOZO" to "ΠΟΣΟ",
            "NOZOTHTA" to "ΠΟΣΟΤΗΤΑ",
            "METEHTA" to "ΜΕΤΡΗΤΑ",
            "NETPETA" to "ΜΕΤΡΗΤΑ",
            "METFHTA" to "ΜΕΤΡΗΤΑ",
            "EYP9" to "ΕΥΡΩ",
            "EYP2" to "ΕΥΡΩ",
            "EXFQ" to "ΕΥΡΩ",
            "EYO"  to "ΕΥΡΩ",
            "EYPQ" to "ΕΥΡΩ",
            "KAAAPH ABIA" to "ΚΑΘΑΡΗ ΑΞΙΑ",
            "ERITORH" to "ΕΚΠΤΩΣΗ"
        )

        for ((badStr, goodStr) in exactHallucinationMap) {
            normalized = normalized.replace(badStr, goodStr)
        }

        // Fix numbers FIRST
        normalized = normalized.replace(NUMBER_SPACE_FIX, "")
        normalized = normalized.replace(SEPARATOR_SPACE_AFTER, "$1")
        normalized = normalized.replace(SEPARATOR_SPACE_BEFORE, "$1")

        // Compound keywords
        normalized = normalized.replace(COMPOUND_TOTAL, "TOTAL_KEY")
        normalized = normalized.replace(COMPOUND_SUBTOTAL, "SUBTOTAL_KEY")
        normalized = normalized.replace(COMPOUND_GENIKO, "TOTAL_KEY")
        normalized = normalized.replace(COMPOUND_MERIKO, "SUBTOTAL_KEY")
        normalized = normalized.replace(COMPOUND_TELIKI, "TOTAL_KEY")

        val boundary = """(?:^|[\s:;.,/-])"""
        val endBoundary = """(?:$|[\s:;.,/-])"""

        // Total keywords
        normalized = normalized.replace(Regex(boundary + "ΣΥΝΟΛΟ" + endBoundary), " TOTAL_KEY ")
        normalized = normalized.replace(Regex(boundary + "ΤΕΛΙΚΟ" + endBoundary), " TOTAL_KEY ")
        normalized = normalized.replace(Regex(boundary + "ΠΛΗΡΩΤΕΟ" + endBoundary), " TOTAL_KEY ")
        
        val synoloVariations = listOf(
            "[EZI23][YVUI]N[O0I]?[AΛVLN][O0ΩI]?",
            "ZYNOAO", "ZYNOAΩ", "2YNONO", "2YNOAO", 
            "EYNOAO", "EYNONO", "SYNOAO", "ZYNOIO"
        )
        for (variant in synoloVariations) {
            normalized = normalized.replace(Regex(boundary + variant + endBoundary), " TOTAL_KEY ")
        }
        
        val telikoVariations = listOf("TEAIKO", "TEΛIKO", "TΕΛΙΚΟ")
        for (variant in telikoVariations) {
            normalized = normalized.replace(Regex(boundary + variant + endBoundary), " TOTAL_KEY ")
        }
        
        val pliroteoVariations = listOf("NAHPΩTEO", "NAHPQTEO", "ΠΛHPΩTEO")
        for (variant in pliroteoVariations) {
            normalized = normalized.replace(Regex(boundary + variant + endBoundary), " TOTAL_KEY ")
        }
        
        normalized = normalized.replace(Regex(boundary + "ΠΟΣΟ" + endBoundary), " AMOUNT_KEY ")
        normalized = normalized.replace(Regex(boundary + "[NΠn][O0][SZsz][O0]" + endBoundary), " AMOUNT_KEY ")

        normalized = normalized.replace(Regex(boundary + "ΜΕΤΡΗΤΑ" + endBoundary), " CASH_KEY ")
        normalized = normalized.replace(Regex(boundary + "METPHTA" + endBoundary), " CASH_KEY ")

        normalized = normalized.replace(Regex(boundary + "(?:VAT\\s*/\\s*ΦΠΑ|ΦΠΑ\\s*/\\s*VAT|Φ\\.?Π\\.?Α\\.?)" + endBoundary), " VAT_KEY ")
        normalized = normalized.replace(Regex(boundary + "0\\.?n\\.?A\\.?" + endBoundary), " VAT_KEY ")
        normalized = normalized.replace(Regex(boundary + "0\\.?Π\\.?Α" + endBoundary), " VAT_KEY ")
        normalized = normalized.replace(Regex(boundary + "O\\.?n\\.?A" + endBoundary), " VAT_KEY ")

        normalized = normalized.replace(Regex(boundary + "ΗΜΕΡΟΜΗΝΙΑ" + endBoundary), " DATE_KEY ")
        normalized = normalized.replace(Regex(boundary + "HM/NIA" + endBoundary), " DATE_KEY ")
        normalized = normalized.replace(Regex(boundary + "HMEPOMHNIA" + endBoundary), " DATE_KEY ")

        normalized = normalized.replace(Regex(boundary + "ΑΞΙΑ" + endBoundary), " VALUE_KEY ")

        normalized = normalized.replace(Regex(boundary + "ΕΥΡΩ" + endBoundary), " EUR ")
        normalized = normalized.replace(Regex(boundary + "ΕΥΡΑ" + endBoundary), " EUR ")
        normalized = normalized.replace(Regex(boundary + "[E3]YP[ΩO9]" + endBoundary), " EUR ")

        normalized = normalized.replace(DATE_OCR_FIX, "$1-0$2-$3")
        normalized = normalized.replace("-00", "-0")

        // Fuzzy matching fallback
        normalized = normalized.replace(ANY_NONSPACE) { match ->
            val word = match.value
            val cleanWord = word.replace(CLEAN_WORD_REGEX, "")
            if (cleanWord.length > 3) {
                when {
                    StringDistanceUtils.isFuzzyMatch(cleanWord, "ΣΥΝΟΛΟ", 2) -> word.replace(cleanWord, "TOTAL_KEY")
                    StringDistanceUtils.isFuzzyMatch(cleanWord, "ΤΕΛΙΚΟ", 2) -> word.replace(cleanWord, "TOTAL_KEY")
                    StringDistanceUtils.isFuzzyMatch(cleanWord, "ΠΛΗΡΩΤΕΟ", 2) -> word.replace(cleanWord, "TOTAL_KEY")
                    StringDistanceUtils.isFuzzyMatch(cleanWord, "ΜΕΤΡΗΤΑ", 2) -> word.replace(cleanWord, "CASH_KEY")
                    StringDistanceUtils.isFuzzyMatch(cleanWord, "ΠΟΣΟ", 1) -> word.replace(cleanWord, "AMOUNT_KEY")
                    StringDistanceUtils.isFuzzyMatch(cleanWord, "ΕΥΡΩ", 1) -> word.replace(cleanWord, "EUR")
                    else -> word
                }
            } else {
                word
            }
        }

        return normalized
    }


    // --- MERCHANT EXTRACTION ---
    private fun extractMerchant(lines: List<String>): String? {
        // Find markers and extract merchant above them
        for ((index, line) in lines.withIndex()) {
            if (index > 10) break

            if (merchantRules.containsHeaderMarker(line)) {
                // Scan upwards for valid merchant
                for (j in index - 1 downTo 0) {
                    val candidate = lines[j]
                    if (merchantRules.isValidMerchantLine(candidate)) {
                        val cleaned = merchantRules.cleanMerchantName(candidate)
                        // Additional check: don't return card processor names
                        if (!merchantRules.isCardProcessor(cleaned)) {
                            return cleaned
                        }
                    }
                }
            }
        }

        // Fallback
        for (line in lines.take(5)) {
            if (merchantRules.isValidMerchantLine(line)) {
                val cleaned = merchantRules.cleanMerchantName(line)
                if (!merchantRules.isCardProcessor(cleaned)) {
                    return cleaned
                }
            }
        }

        return null
    }


    private fun extractTotal(lines: List<String>): Double? {
        val amountRegex = Regex("""(\d{1,10}(?:[.,\s]\d{3})*[.,]\d{2})(?!\s?%)""")

        // Lines that should be COMPLETELY skipped (receipt numbers, IDs, etc.)
        val nonTotalIndicators = listOf(
            "APIOMOE", "APIOMOX", "ZEIPA", "SERIAL", "AA/Y",
            "AP.r.E.MH", "APIEMOE", "ANEAATH", "APIEMOX",
            "AOM", "AFM", "A.F.M.", "THA", "THA:", "DATE_KEY", "HM/NIA",
            // Card receipt markers
            "5356", "****", "ENTER BONUS", "MARK:", "UID:", "AUTH:",
            // Change/Resta patterns
            "CHANGE_KEY", "ΡΕΣΤΑ", "RESTA", "ΑΛΛΑΓΗ",
            // Time markers (Greek QPA = TIME)
            "QPA:", "OPA:", "ΩΡΑ:"
        )

        // Priority-based extraction: track best candidate
        // Priority: TOTAL_KEY > AMOUNT_KEY > CASH_KEY > standalone amounts
        var bestTotal: Double? = null
        var bestPriority: Int = -1 // 3=TOTAL_KEY, 2=AMOUNT_KEY, 1=CASH_KEY, 0=standalone

        // Strategy 1: Look for TOTAL_KEY (highest priority)
        val totalLineIndex = lines.indexOfLast { it.contains("TOTAL_KEY") }
        if (totalLineIndex != -1) {
            // Check this line and next 3 lines (amount may be split)
            for (offset in 0..3) {
                if (totalLineIndex + offset < lines.size) {
                    val lineToCheck = lines[totalLineIndex + offset]
                    val amount = extractAmountFromLine(lineToCheck, amountRegex)
                    if (amount != null && amount > 0.01) {
                        bestTotal = amount
                        bestPriority = 3
                        break
                    }
                }
            }
        }
        
        // If we found TOTAL_KEY amount, return it immediately
        if (bestTotal != null && bestPriority == 3) return bestTotal

        // Strategy 2: Look for AMOUNT_KEY (medium priority)
        val amountLineIndex = lines.indexOfLast { it.contains("AMOUNT_KEY") && !it.contains("TOTAL_KEY") }
        if (amountLineIndex != -1) {
            for (offset in 0..2) {
                if (amountLineIndex + offset < lines.size) {
                    val lineToCheck = lines[amountLineIndex + offset]
                    val amount = extractAmountFromLine(lineToCheck, amountRegex)
                    if (amount != null && amount > 0.01 && (bestPriority < 2)) {
                        bestTotal = amount
                        bestPriority = 2
                        break
                    }
                }
            }
        }

        // Strategy 3: Look for CASH_KEY (lower priority - only if nothing better found)
        val cashLineIndex = lines.indexOfLast { it.contains("CASH_KEY") && !it.contains("TOTAL_KEY") && !it.contains("CHANGE_KEY") }
        if (cashLineIndex != -1 && bestPriority < 2) {
            for (offset in 0..2) {
                if (cashLineIndex + offset < lines.size) {
                    val lineToCheck = lines[cashLineIndex + offset]
                    val amount = extractAmountFromLine(lineToCheck, amountRegex)
                    if (amount != null && amount > 0.01 && (bestPriority < 1)) {
                        bestTotal = amount
                        bestPriority = 1
                        break
                    }
                }
            }
        }

        // If we found any keyword-based amount, return it
        if (bestTotal != null && bestPriority > 0) return bestTotal

        // Strategy 3.5: Look for card receipt format "POSO/AMOUNT:" or "€XX,XX" alone
        for (i in lines.indices) {
            val line = lines[i]
            
            // Card receipt pattern: "POSO/AMOUNT:" or standalone euro amount
            if (line.contains("POSO") || line.matches(Regex("""^€?\s*\d+[.,]\d{2}\s*€?\s*$"""))) {
                val amount = extractAmountFromLine(line, amountRegex)
                if (amount != null && isValidAmount(amount, line) && bestPriority < 2) {
                    bestTotal = amount
                    bestPriority = 2
                }
            }
        }

        if (bestTotal != null) return bestTotal

        // Strategy 4: Fallback - Find largest VALID standalone amount
        var maxAmount = 0.0
        
        // More flexible regex for fallback that includes currency symbols
        val fallbackRegex = Regex("""€?\s*(\d{1,10}(?:[.,\s]\d{3})*[.,]\d{2})\s*€?""")
        
        // Time pattern to skip
        val timePattern = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")
        
        // Change/Resta pattern
        val changePattern = Regex("""(CHANGE|ΡΕΣΤΑ|RESTA|ΑΛΛΑΓΗ)""")

        for (i in lines.indices) {
            val line = lines[i]

            // Skip lines with non-total indicators or tax-only lines
            if (nonTotalIndicators.any { line.contains(it, ignoreCase = true) }) continue
            if (isTaxOnlyLine(line)) continue
            if (timePattern.containsMatchIn(line)) continue
            if (changePattern.containsMatchIn(line)) continue
            
            // Skip long number lines (barcodes/IDs)
            if (line.replace(Regex("[^0-9]"), "").length > 9) continue

            // Skip VAT percentage lines
            if (line.contains("%") && !line.contains("TOTAL")) continue

            // Skip card reference lines
            if (line.contains("5356") || line.contains("****") || line.contains("ENTER BONUS")) continue

            // Try both the primary regex and fallback regex
            val matches = amountRegex.findAll(line).toList() + fallbackRegex.findAll(line).toList()
            for (match in matches) {
                val rawVal = match.groupValues[1]
                val amount = parseAmount(rawVal)

                if (isValidAmount(amount, line) && amount > maxAmount) {
                    maxAmount = amount
                }
            }
        }

        // Use fallback if we found something and no better option exists
        if (maxAmount > 0.0 && bestPriority < 1) return maxAmount
        
        // Return best found (might be null)
        return bestTotal ?: if (maxAmount > 0.0) maxAmount else null
    }

    private fun isValidAmount(amount: Double, line: String): Boolean {
        // Reject zero or near-zero
        if (amount < 0.01) return false

        // Reject unreasonably large amounts (increased to 50000 for B2B)
        if (amount > 50000.0) return false

        // Reject year-like numbers (allow decimal years only if not whole)
        if (amount >= 2015.0 && amount <= 2035.0 && amount % 1.0 == 0.0) return false

        // NEW: Reject if line looks like a receipt number line
        val receiptNumberPatterns = listOf(
            Regex("""APIOMOE|APIOMOX|ΑΡΙΘΜΟΣ""", RegexOption.IGNORE_CASE),
            Regex("""ZEIPA|ΣΕΙΡΑ"""),
            Regex("""AP\.?r\.?E\.?MH"""),
            Regex("""ΑΠΟΔΕΙΞΗ|ΠΑΡΑΣΤΑΤΙΚΟ""", RegexOption.IGNORE_CASE)
        )
        if (receiptNumberPatterns.any { it.containsMatchIn(line) }) return false

        return true
    }

    private fun parseAmount(rawAmount: String): Double {
        return AmountUtils.parseAmount(rawAmount) ?: 0.0
    }

    private fun extractAmountFromLine(line: String, regex: Regex): Double? {
        // NEW: First check if line contains percentage - if so, extract differently
        if (line.contains("%")) {
            // Try before % first (for "20,13 24,00%")
            val beforePercent = line.substringBefore("%", "")
            val matchesBefore = regex.findAll(beforePercent).toList()
            if (matchesBefore.isNotEmpty()) {
                // Return the FIRST amount before the percentage sign
                return parseAmount(matchesBefore.first().groupValues[1])
            }
            
            // Fallback to after % (for "ΦΠΑ 24%: 4,14")
            val afterPercent = line.substringAfter("%", "")
            val matchesAfter = regex.findAll(afterPercent).toList()
            if (matchesAfter.isNotEmpty()) {
                // Return the LAST amount after the percentage sign
                return parseAmount(matchesAfter.last().groupValues[1])
            }
        }

        // NEW: Skip lines that look like time: "14:24" or "QPA: 14.24"
        if (line.matches(Regex(""".*\b\d{1,2}:\d{2}\b.*"""))) {
            // Check if the amount is actually a time
            val timeMatch = Regex("""\b(\d{1,2}):(\d{2})\b""").find(line)
            if (timeMatch != null) {
                val hour = timeMatch.groupValues[1].toIntOrNull()
                val minute = timeMatch.groupValues[2].toIntOrNull()
                if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                    // This is likely a time, skip it
                    return null
                }
            }
        }
        
        // NEW: Handle E-prefixed numbers (E0,13 -> extract 0,13)
        val cleanedLine = line.replace(Regex("""\bE(\d)"""), "$1")

        val matches = regex.findAll(cleanedLine)
        return matches.lastOrNull()?.groupValues?.get(1)?.let { parseAmount(it) }
    }

    private fun isTaxOnlyLine(line: String): Boolean {
        // Lines like "ΦΠΑ 24%: 4,14" or "VAT 20% 1.00"
        val taxKeywords = listOf("ΦΠΑ", "VAT", "TAX", "VAT_KEY")
        val hasTaxKeyword = taxKeywords.any { line.contains(it, ignoreCase = true) }
        return hasTaxKeyword && line.contains("%")
    }

    private fun extractSubtotal(text: String): Double? {
        for (pattern in subtotalPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.let { AmountUtils.parseAmount(it) }
            }
        }
        return null
    }

    private fun extractTax(text: String): Double? {
        // Multiple tax patterns to handle Greek ΦΠΑ OCR variations
        val taxPatterns = listOf(
            // Normalized VAT_KEY pattern
            Regex("""VAT_KEY\s*[:\s]*[€$]?\s*(\d+[.,]\d{2})"""),
            // Greek/English with percentage and bilingual: "VAT / ΦΠΑ: €2.76" or "ΦΠΑ 24%: 4,14"
            Regex("""(?:Φ\.?Π\.?Α\.?|VAT|TAX)(?:\s*/\s*(?:Φ\.?Π\.?Α\.?|VAT|TAX))?\s*\d*[.,]?\d*%?\s*:?\s*[€$]?\s*(\d+[.,]\d{2})"""),
            // OCR corrupted: "0.n.A 24,00%" or "O.n.A"
            Regex("""0\.?n\.?A\.?\s*\d*[.,]?\d*%?\s*[€$]?\s*(\d+[.,]\d{2})"""),
            Regex("""O\.?n\.?A\s*\d*[.,]?\d*%?\s*[€$]?\s*(\d+[.,]\d{2})"""),
            Regex("""0\.?Π\.?Α\s*\d*[.,]?\d*%?\s*[€$]?\s*(\d+[.,]\d{2})"""),
            // Line with tax percentage: "4,14 24%"
            Regex("""(\d+[.,]\d{2})\s*\d{1,3}[.,]?\d{0,2}%""")
        )

        for (pattern in taxPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].let { AmountUtils.parseAmount(it) }
            }
        }
        return null
    }

    // --- DATE EXTRACTION ---
    private fun extractDate(text: String): Long? {
        // Regex handles: dd/MM/yyyy, dd-MM-yyyy, dd.MM.yyyy
        val datePatterns = listOf(
            Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(20\d{2})\b"""),
            Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(\d{2})\b""")
        )

        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)

        for (pattern in datePatterns) {
            pattern.find(text)?.let { match ->
                val (d, m, y) = match.destructured
                val year = if (y.length == 2) "20$y" else y
                
                // SANITY CHECK: Year must be reasonable (Dynamic range)
                val yearInt = year.toIntOrNull() ?: 0
                val now = timeProvider.now()
                val currentYear = TimePeriodUtils.getYear(now)
                if (yearInt in (currentYear - 10)..(currentYear + 1)) { 
                    try {
                        val parsed = LocalDate.parse("${d.padStart(2, '0')}/${m.padStart(2, '0')}/$year", dateFormatter)
                        return parsed.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        Timber.d("Failed to parse date: $d/$m/$year")
                    }
                }
            }
        }
        return null
    }

    private fun extractLineItems(text: String): List<LineItem> {
        val items = mutableListOf<LineItem>()

        // Skip lines that look like totals/subtotals
        val skipLinePattern = Regex(
            """(?i)(TOTAL|ΣΥΝΟΛΟ|VAT|ΦΠΑ|CHANGE|ΡΕΣΤΑ|CASH|CARD|VISA|MASTER|SUBTOTAL|ΥΠΟΣΥΝΟΛΟ|ΜΕΤΡΗΤΑ|ΚΑΡΤΑ|ΠΛΗΡΩΜΗ|PAYMENT|DISCOUNT|ΕΚΠΤΩΣΗ|AMOUNT|ΠΟΣΟ|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΑΞΙΑ|VALUE)"""
        )

        // Pattern 1: "description   amount"
        val matcher1 = lineItemPatterns[0].matcher(text)
        while (matcher1.find()) {
            val desc = matcher1.group(1)?.trim() ?: continue
            val price = matcher1.group(2)?.let { AmountUtils.parseAmount(it) } ?: continue
            if (skipLinePattern.containsMatchIn(desc)) continue
            if (desc.matches(QUANTITY_PREFIX_PATTERN)) continue
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
            val price = matcher2.group(3)?.let { AmountUtils.parseAmount(it) } ?: continue
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

        return deduplicateLineItems(items)
    }

    private fun deduplicateLineItems(items: List<LineItem>): List<LineItem> {
        if (items.size < 2) return items

        val uniqueItems = LinkedHashMap<String, LineItem>()
        for (item in items) {
            uniqueItems.putIfAbsent(item.deduplicationKey(), item)
        }
        return uniqueItems.values.toList()
    }

    private fun LineItem.deduplicationKey(): String {
        val normalizedDescription = description
            .uppercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

        return listOf(
            normalizedDescription,
            quantity.normalizedNumberKey(),
            totalPrice.normalizedNumberKey()
        ).joinToString("|")
    }

    private fun Double?.normalizedNumberKey(): String {
        val value = this ?: return "-"
        return java.lang.String.format(Locale.US, "%.4f", value)
    }

    private fun detectCurrency(text: String): String? {
        return when {
            text.contains("€") || 
            text.contains("EUR", ignoreCase = true) ||
            text.contains("ΕΥΡΩ", ignoreCase = true) ||
            text.contains("ΕΥΡ", ignoreCase = true) -> "EUR"
            
            text.contains("$") || text.contains("USD", ignoreCase = true) -> "USD"
            
            // Be more strict with GBP to avoid OYP/OYR corruption
            text.contains("£") || 
            (text.contains("GBP", ignoreCase = true) && !text.contains("OYP") && !text.contains("OYR")) -> "GBP"
            
            else -> null // Currency not detected
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
        
        // Merchant (15%)
        if (merchant != null && merchant.length >= 3) {
            score += 0.15f
            // Bonus for recognizable business patterns (uppercase names)
            if (merchant.matches(Regex(".*[A-Z]{3,}.*"))) score += 0.05f
        }
        
        // Total (40%) - Most important
        if (total != null && total > 0) {
            score += 0.40f
            // Bonus if total is reasonable
            if (total in 0.5..2000.0) score += 0.05f
        }
        
        // Date (15%)
        if (date != null) {
            score += 0.15f
            // Bonus if date is recent
            val daysDiff = TimePeriodUtils.daysBetween(date, timeProvider.now())
            if (daysDiff in 0..365) score += 0.05f
        }
        
        // Line items (15%)
        if (items.isNotEmpty()) {
            score += 0.10f
            if (items.size >= 2) score += 0.05f
        }
        
        // Tax (5%)
        if (tax != null && tax > 0) score += 0.05f

        // Cross-validation bonus (10%)
        if (total != null && items.isNotEmpty()) {
            val itemsSum = items.sumOf { it.totalPrice }
            val diff = kotlin.math.abs(total - itemsSum)
            if (diff < total * 0.10) { // Within 10%
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
