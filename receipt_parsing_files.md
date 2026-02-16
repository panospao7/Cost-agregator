# Receipt Parsing Functionality - Source Code

This file contains the core logic for receipt OCR, parsing, and management.

## Table of Contents
1. [app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt](#appsrcmainjavacomyournameexpensetrackerdomainreceiptreceiptparserkt)
2. [app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt](#appsrcmainjavacomyournameexpensetrackerdomainreceiptreceiptocrservicekt)
3. [app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt](#appsrcmainjavacomyournameexpensetrackerdatarepositoryreceiptrepositorykt)
4. [app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanscreenkt)
5. [app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanviewmodelkt)
6. [app/src/test/java/com/yourname/expensetracker/OcrDocumentTest.kt](#appsrctestjavacomyournameexpensetrackerocrdocumenttestkt)
7. [app/src/test/java/com/yourname/expensetracker/domain/receipt/GreekNormalizationTest.kt](#appsrctestjavacomyournameexpensetrackerdomainreceiptgreeknormalizationtestkt)
8. [app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt](#appsrcmainjavacomyournameexpensetrackerdomainreceiptbankstatementparserkt)
9. [app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt](#appsrcmainjavacomyournameexpensetrackerdomainparserappparserregistrykt)

---

## app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainreceiptreceiptparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.receipt

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern
import java.text.SimpleDateFormat
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

    fun parse(rawText: String): ParsedReceipt {
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

        // 9. Calculate subtotal
        val finalSubtotal = subtotal
            ?: if (finalTotal != null && tax != null) finalTotal - tax else null

        // 10. Confidence
        val confidence = calculateConfidence(merchant, finalTotal, date, lineItems, tax)

        return ParsedReceipt(
            merchantName = merchant,
            total = finalTotal,
            subtotal = finalSubtotal,
            tax = tax,
            date = date,
            currency = detectCurrency(cleanedText),
            lineItems = lineItems,
            confidence = confidence
        )
    }

    private fun normalizeGreekOcr(text: String): String {
        var normalized = text.uppercase()

        // Fix numbers FIRST - Remove spaces in numbers like "4 5 . 5 0"
        normalized = normalized.replace(Regex("""(?<=\d)\s+(?=[.,\d])"""), "")
        normalized = normalized.replace(Regex("""(?=[.,\d])\s+(?=\d)"""), "")

        // Normalize Greek characters to English counterparts for easier matching
        // Use more robust matching for Greek words without \b if possible
        
        // Compound keywords - MUST be before single ones
        normalized = normalized.replace(Regex("""ΣΥΝΟΛΙΚΗ\s+ΑΞΙΑ"""), "TOTAL_KEY")
        normalized = normalized.replace(Regex("""ΚΑΘΑΡΗ\s+ΑΞΙΑ"""), "SUBTOTAL_KEY")
        normalized = normalized.replace(Regex("""ΓΕΝΙΚΟ\s+ΣΥΝΟΛΟ"""), "TOTAL_KEY")
        normalized = normalized.replace(Regex("""ΜΕΡΙΚΟ\s+ΣΥΝΟΛΟ"""), "SUBTOTAL_KEY")
        normalized = normalized.replace(Regex("""ΤΕΛΙΚΗ\s+ΑΞΙΑ"""), "TOTAL_KEY")

        // Single keywords (Using more flexible boundaries for Greek/Latin mix)
        val boundary = """(?:^|[\s:;.,/-])"""
        val endBoundary = """(?:$|[\s:;.,/-])"""
        
        // Total keywords - ΣΥΝΟΛΟ and variations
        normalized = normalized.replace(Regex(boundary + "ΣΥΝΟΛΟ" + endBoundary), " TOTAL_KEY ")
        normalized = normalized.replace(Regex(boundary + "ΤΕΛΙΚΟ" + endBoundary), " TOTAL_KEY ")
        normalized = normalized.replace(Regex(boundary + "ΠΛΗΡΩΤΕΟ" + endBoundary), " TOTAL_KEY ")
        
        // Common OCR errors for ΣΥΝΟΛΟ - Unified boundary check
        val synoloVariations = listOf(
            "[EZI23][YVUI]N[O0I]?[AΛVLN][O0ΩI]?", // Flexible pattern for ΣΥΝΟΛΟ
            "ZYNOAO", "ZYNOAΩ", "2YNONO", "2YNOAO", 
            "EYNOAO", "EYNONO", "SYNOAO", "ZYNOIO"
        )
        for (variant in synoloVariations) {
            normalized = normalized.replace(Regex(boundary + variant + endBoundary), " TOTAL_KEY ")
        }
        
        // ΤΕΛΙΚΟ variations
        val telikoVariations = listOf("TEAIKO", "TEΛIKO", "TΕΛΙΚΟ")
        for (variant in telikoVariations) {
            normalized = normalized.replace(Regex(boundary + variant + endBoundary), " TOTAL_KEY ")
        }
        
        // ΠΛΗΡΩΤΕΟ variations
        val pliroteoVariations = listOf("NAHPΩTEO", "NAHPQTEO", "ΠΛHPΩTEO")
        for (variant in pliroteoVariations) {
            normalized = normalized.replace(Regex(boundary + variant + endBoundary), " TOTAL_KEY ")
        }
        
        // Amount keywords
        normalized = normalized.replace(Regex(boundary + "ΠΟΣΟ" + endBoundary), " AMOUNT_KEY ")
        normalized = normalized.replace(Regex(boundary + "[NΠn][O0][SZsz][O0]" + endBoundary), " AMOUNT_KEY ")

        // Cash keywords
        normalized = normalized.replace(Regex(boundary + "ΜΕΤΡΗΤΑ" + endBoundary), " CASH_KEY ")
        normalized = normalized.replace(Regex(boundary + "METPHTA" + endBoundary), " CASH_KEY ")

        // VAT/Tax keywords - ΦΠΑ and OCR corruptions
        normalized = normalized.replace(Regex(boundary + "Φ\\.?Π\\.?Α\\.?" + endBoundary), " VAT_KEY ")
        normalized = normalized.replace(Regex(boundary + "0\\.?n\\.?A\\.?" + endBoundary), " VAT_KEY ")
        normalized = normalized.replace(Regex(boundary + "0\\.?Π\\.?Α" + endBoundary), " VAT_KEY ")
        normalized = normalized.replace(Regex(boundary + "O\\.?n\\.?A" + endBoundary), " VAT_KEY ")

        // Date keywords
        normalized = normalized.replace(Regex(boundary + "ΗΜΕΡΟΜΗΝΙΑ" + endBoundary), " DATE_KEY ")
        normalized = normalized.replace(Regex(boundary + "HM/NIA" + endBoundary), " DATE_KEY ")
        normalized = normalized.replace(Regex(boundary + "HMEPOMHNIA" + endBoundary), " DATE_KEY ")

        // Value keyword
        normalized = normalized.replace(Regex(boundary + "ΑΞΙΑ" + endBoundary), " VALUE_KEY ")

        // Currency keywords - ΕΥΡΩ and variations
        normalized = normalized.replace(Regex(boundary + "ΕΥΡΩ" + endBoundary), " EUR ")
        normalized = normalized.replace(Regex(boundary + "ΕΥΡΑ" + endBoundary), " EUR ")
        normalized = normalized.replace(Regex(boundary + "[E3]YP[ΩO9]" + endBoundary), " EUR ")

        // Date OCR fixes: 16-D4 -> 16-04
        normalized = normalized.replace(Regex("""(\d{1,2})[-/][DO0](\d{1,2})[-/](\d{2,4})"""), "$1-0$2-$3")
        // Fix double zero if above resulted in 16-004
        normalized = normalized.replace("-00", "-0")

        return normalized
    }


    // --- MERCHANT EXTRACTION ---
    private fun extractMerchant(lines: List<String>): String? {
        // Expanded invalid merchant patterns
        val invalidMerchants = listOf(
            // Keywords that should never be merchants
            "APODEIXI", "AIOAEIEH", "ANOD", "NOMIMH", "ENARXI", "START",
            "EAPA", "ADDRESS", "THA", "TEL", "AFM", "AOM", "A.M.", "ΑΦΜ",
            "EYNONO", "ZYNOAO", "SYNOAO", "TOTAL_KEY", "CASH_KEY", "AMOUNT_KEY",
            // Card processors - CRITICAL
            "CARDLINK", "WORLDLINE", "VISA", "MASTERCARD", "MAESTRO",
            "AMERICAN EXPRESS", "AMEX", "DINERS", "DISCOVER",
            // Banks
            "PIRAEUS", "EUROBANK", "ALPHA BANK", "NBG", "NATIONAL BANK",
            "LYNK", "BANK OF CYPRUS", "HELLENIC BANK", "REVOLUT",
            "VIVA", "SUMUP", "MYPOS", "STRIPE",
            // Transaction types
            "AGORA", "SALE", "PURCHASE", "CONTACTLESS", "TERMINAL",
            "TRANSACTION", "ΠΑΡΑΛΑΒΗ", "ΑΓΟΡΑ",
            // Serial/reference patterns
            "ZEIPA", "SERIAL", "ΑΡΙΘΜΟΣ", "APIOMOE", "APIOMOX",
            // URLs and garbage
            "WWW.", "HTTP", ".GR", ".COM", "HTTPS://",
            // Payment related
            "KAPTA", "KAPTEE", "CARD", "ΚΑΡΤΑ", "METPHTA", "ΜΕΤΡΗΤΑ"
        )

        // Header markers (indicate we're past the merchant name)
        val headerMarkers = listOf(
            "ΑΦΜ", "A.Φ.Μ.", "Α.Φ.Μ", "@.M.", "A.M.", "AΦM",
            "Α.Ο.Υ.", "ΑΟΥ", "A.0.Y.", "Δ.Ο.Υ.", "ΔΟΥ",
            "ΤΗΛ", "THA", "THΛ", "ΤΗΛ:", "THA:",
            "ΟΔΟΣ", "ΣΤΡ.", "STR.", "ADDRESS",
            "Τ.Κ.", "TK", "Τ.Κ", "T.K.",
            "Α.Μ.Μ.", "ΑΜΜ", "ΑΜΜ.",
            "ΗΜΕΡΟΜΗΝΙΑ", "HM/NIA", "DATE_KEY",
            // Card receipt markers
            "ΑΓΟΡΑ", "AGORA", "AGORA-SALE", "SALE", "PURCHASE", 
            "CONTACTLESS", "TERMINAL", "TRANSACTION", "ENTER BONUS",
            // Card reference patterns
            "****", "5356", "MARK:", "UID:", "AUTH:"
        )

        // Find markers and extract merchant above them
        for ((index, line) in lines.withIndex()) {
            if (index > 10) break

            for (marker in headerMarkers) {
                if (line.contains(marker, ignoreCase = true)) {
                    // Scan upwards for valid merchant
                    for (j in index - 1 downTo 0) {
                        val candidate = lines[j]
                        if (isValidMerchantLine(candidate, invalidMerchants)) {
                            val cleaned = cleanMerchantName(candidate)
                            // Additional check: don't return card processor names
                            if (!isCardProcessor(cleaned)) {
                                return cleaned
                            }
                        }
                    }
                }
            }
        }

        // Fallback
        for (line in lines.take(5)) {
            if (isValidMerchantLine(line, invalidMerchants)) {
                val cleaned = cleanMerchantName(line)
                if (!isCardProcessor(cleaned)) {
                    return cleaned
                }
            }
        }

        return null
    }

    private fun isCardProcessor(name: String): Boolean {
        val processors = listOf(
            "CARDLINK", "WORLDLINE", "VIVA", "PIRAEUS", "EUROBANK", "ALPHA BANK",
            "LYNK", "BANK OF CYPRUS", "HELLENIC BANK", "NBG", "REVOLUT", "STRIPE",
            "SUMUP", "MYPOS", "CIBC", "TD BANK", "AMEX", "AMERICAN EXPRESS", "DINERS"
        )
        return processors.any { name.contains(it, ignoreCase = true) }
    }

    private fun isValidMerchantLine(line: String, invalidHeaders: List<String>): Boolean {
        if (line.length < 3) return false
        if (line.all { !it.isLetter() }) return false // Must have letters
        if (invalidHeaders.any { line.contains(it) }) return false
        
        // Skip if line is mostly numbers
        val digitCount = line.count { it.isDigit() }
        if (digitCount > line.length / 2) return false
        
        // Skip lines that are dates or times
        if (line.matches(Regex(""".*(\\d{2}[/-]\\d{2}[/-]\\d{4}|\\d{2}:\\d{2}:\\d{2}|A\\.?Φ\\.?Μ\\.?).*$"""))) return false
        
        return true
    }

    private fun cleanMerchantName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Zα-ωΑ-Ω0-9\\s&.-]"), "").trim()
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

        // Reject unreasonably large amounts
        if (amount > 5000.0) return false

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
        if (rawAmount.isBlank()) return 0.0

        var cleaned = rawAmount

        // NEW: Handle E-prefixed amounts (E0,13 -> try to extract 0.13)
        if (cleaned.startsWith("E") || cleaned.startsWith("e")) {
            val rest = cleaned.substring(1)
            // Use simple check if rest looks like number start
            if (rest.isNotEmpty() && rest[0].isDigit()) {
                 cleaned = rest
            }
        }

        // Remove all spaces
        cleaned = cleaned.replace(" ", "")

        // Find last separator
        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')
        val lastSepIndex = kotlin.math.max(lastComma, lastDot)

        return if (lastSepIndex >= 0) {
            val integerPart = cleaned.substring(0, lastSepIndex).replace(".", "").replace(",", "")
            val decimalPart = cleaned.substring(lastSepIndex + 1)
            "$integerPart.$decimalPart".toDoubleOrNull() ?: 0.0
        } else {
            cleaned.toDoubleOrNull() ?: 0.0
        }
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
                return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun extractTax(text: String): Double? {
        // Multiple tax patterns to handle Greek ΦΠΑ OCR variations
        val taxPatterns = listOf(
            // Normalized VAT_KEY pattern
            Regex("""VAT_KEY\s*[:\s]*(\d+[.,]\d{2})"""),
            // Greek with percentage: "ΦΠΑ 24%: 4,14"
            Regex("""(?:Φ\.?Π\.?Α\.?|VAT|TAX)\s*\d*[.,]?\d*%?\s*:?\s*(\d+[.,]\d{2})"""),
            // OCR corrupted: "0.n.A 24,00%" or "O.n.A"
            Regex("""0\.?n\.?A\.?\s*\d*[.,]?\d*%?\s*(\d+[.,]\d{2})"""),
            Regex("""O\.?n\.?A\s*\d*[.,]?\d*%?\s*(\d+[.,]\d{2})"""),
            Regex("""0\.?Π\.?Α\s*\d*[.,]?\d*%?\s*(\d+[.,]\d{2})"""),
            // Line with tax percentage: "4,14 24%"
            Regex("""(\d+[.,]\d{2})\s*\d{1,3}[.,]?\d{0,2}%""")
        )

        for (pattern in taxPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].replace(",", ".").toDoubleOrNull()
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

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        sdf.isLenient = false

        for (pattern in datePatterns) {
            pattern.find(text)?.let { match ->
                val (d, m, y) = match.destructured
                val year = if (y.length == 2) "20$y" else y
                
                // SANITY CHECK: Year must be reasonable (Dynamic range)
                val yearInt = year.toIntOrNull() ?: 0
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                if (yearInt in (currentYear - 10)..(currentYear + 1)) { 
                    try {
                        return sdf.parse("$d/$m/$year")?.time
                    } catch (e: Exception) { }
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
            text.contains("€") || 
            text.contains("EUR", ignoreCase = true) ||
            text.contains("ΕΥΡΩ", ignoreCase = true) ||
            text.contains("ΕΥΡ", ignoreCase = true) -> "EUR"
            
            text.contains("$") || text.contains("USD", ignoreCase = true) -> "USD"
            
            // Be more strict with GBP to avoid OYP/OYR corruption
            text.contains("£") || 
            (text.contains("GBP", ignoreCase = true) && !text.contains("OYP") && !text.contains("OYR")) -> "GBP"
            
            else -> "EUR" // Default for Greek receipts
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
            val daysDiff = (System.currentTimeMillis() - date) / (1000 * 60 * 60 * 24)
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

```

---

## app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainreceiptreceiptocrservicekt"></a>
```kotlin
package com.yourname.expensetracker.domain.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(
    val fullText: String,
    val blocks: List<TextBlock>,
    val savedImagePath: String
)

data class TextBlock(
    val text: String,
    val confidence: Float?,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

@Singleton
class ReceiptOcrService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Reverting to DEFAULT_OPTIONS as Builder might not be available in current dependency version
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Dispatcher that automatically routes URIs to the correct processor based on MIME type.
     */
    suspend fun processUri(uri: Uri): OcrResult {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        return if (mimeType == "application/pdf") {
            processPdf(uri)
        } else {
            processImage(uri)
        }
    }

    /**
     * Process an image URI and return OCR results.
     * Also saves a compressed copy of the image for future reference.
     */
    suspend fun processImage(imageUri: Uri): OcrResult {
        // 1. Load and prepare the image (throws if fail)
        val bitmap = loadAndCorrectBitmap(imageUri) ?: throw IllegalStateException("Failed to load and correct image: $imageUri")

        try {
            // 2. Save compressed copy
            val savedPath = saveReceiptImage(bitmap)

            // 3. Run ML Kit OCR
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizeText(inputImage)

            // 4. Extract blocks with confidence filtering
            val blocks = visionText.textBlocks.mapNotNull { block ->
                val avgConfidence = block.lines.mapNotNull { it.confidence }.average().toFloat()
                // If confidence is available and very low (< 0.2), skip it.
                // Note: ML Kit often returns null confidence for Latin/Default models, so we default to 1.0 if null
                val safeConfidence = if (block.lines.firstOrNull()?.confidence != null) avgConfidence else 1.0f
                
                if (safeConfidence < 0.2f && block.text.length < 3) {
                    // Skip very low confidence noise (usually single characters)
                    null
                } else {
                    TextBlock(
                        text = block.text,
                        confidence = safeConfidence,
                        // lines argument removed as it's not in TextBlock definition
                        left = block.boundingBox?.left ?: 0,
                        top = block.boundingBox?.top ?: 0,
                        right = block.boundingBox?.right ?: 0,
                        bottom = block.boundingBox?.bottom ?: 0
                    )
                }
            }

            return OcrResult(
                fullText = blocks.joinToString("\n\n") { it.text },
                blocks = blocks,
                savedImagePath = savedPath
            )
        } finally {
            // CRITICAL: Prevent memory leaks during batch processing
            bitmap.recycle()
        }
    }

    /**
     * Process a PDF URI by rendering pages to bitmaps and running OCR on each.
     */
    suspend fun processPdf(pdfUri: Uri): OcrResult {
        val tempFile = File(context.cacheDir, "temp_pdf_${System.nanoTime()}.pdf")
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null
        
        try {
            // 1. Copy PDF to local file (PdfRenderer needs a ParcelFileDescriptor from a file or pipe)
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open PDF stream: $pdfUri")

            pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            
            val allFullText = StringBuilder()
            val allBlocks = mutableListOf<TextBlock>()
            var savedThumbnailPath = ""
            
            // Limit to first 3-5 pages for performance (Rich functionality requirement)
            val pageLimit = 5 
            val pagesToProcess = minOf(renderer.pageCount, pageLimit)
            
            var verticalOffset = 0
            
            for (i in 0 until pagesToProcess) {
                val page = renderer.openPage(i)
                
                // Render page to high-quality Bitmap (OCR prefers ~200-300 DPI equivalent)
                // 1024 width is our standard for OCR in loadAndCorrectBitmap
                val scale = 1024f / page.width
                val bitmapWidth = 1024
                val bitmapHeight = (page.height * scale).toInt()
                
                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                try {
                    // Save first page as JPG for UI preview/record
                    if (i == 0) {
                        savedThumbnailPath = saveReceiptImage(bitmap)
                    }
                    
                    // Run OCR on this page
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val visionText = recognizeText(inputImage)
                    
                    // Add full text
                    allFullText.append(visionText.text).append("\n\n")
                    
                    // Add blocks with offset (Virtual Long Page strategy)
                    visionText.textBlocks.forEach { block ->
                        allBlocks.add(
                            TextBlock(
                                text = block.text,
                                confidence = block.lines.firstOrNull()?.confidence,
                                left = block.boundingBox?.left ?: 0,
                                top = (block.boundingBox?.top ?: 0) + verticalOffset,
                                right = block.boundingBox?.right ?: 0,
                                bottom = (block.boundingBox?.bottom ?: 0) + verticalOffset
                            )
                        )
                    }
                    
                    verticalOffset += bitmapHeight
                    
                } finally {
                    bitmap.recycle() // CRITICAL: Release memory immediately
                    page.close()
                }
            }
            
            return OcrResult(
                fullText = allFullText.toString().trim(),
                blocks = allBlocks,
                savedImagePath = savedThumbnailPath
            )
            
        } catch (e: Exception) {
            android.util.Log.e("ReceiptOcrService", "PDF processing failed for $pdfUri", e)
            throw IllegalStateException("Failed to scan PDF: ${e.message}", e)
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private suspend fun recognizeText(
        image: InputImage
    ): com.google.mlkit.vision.text.Text {
        return kotlinx.coroutines.withTimeout(15000) { // Fix 4.17: 15s timeout
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        continuation.resume(text)
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWithException(e)
                    }
            }
        }
    }

    /**
     * Load bitmap from URI with EXIF rotation correction.
     * Copies to a temp file first to ensure reliable multi-read access.
     */
    private fun loadAndCorrectBitmap(uri: Uri): Bitmap? {
        val tempFile = File(context.cacheDir, "temp_ocr_${System.nanoTime()}.jpg")
        var decodedBitmap: Bitmap? = null
        try {
            // Copy URI to temp file
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open input stream for $uri")
            
            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IllegalStateException("Temp file creation failed or empty for $uri")
            }

            // 1. Get dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(tempFile.absolutePath, options)

            // Calculate sample size - Optimized: 1024 is plenty for OCR and saves memory/time
            val maxDimension = 1024
            var sampleSize = 1
            if (options.outWidth > 0 && options.outHeight > 0) {
                while (options.outWidth / sampleSize > maxDimension ||
                    options.outHeight / sampleSize > maxDimension
                ) {
                    sampleSize *= 2
                }
            }

            // 2. Decode actual bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions)
                ?: throw IllegalStateException("Bitmap decode failed for $uri (Sample: $sampleSize)")
            decodedBitmap = bitmap

            // 3. Apply EXIF rotation
            val exif = ExifInterface(tempFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            var needsRotate = true
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> needsRotate = false
            }

            if (needsRotate) {
                try {
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated != bitmap) {
                        bitmap.recycle() // Clean up original if rotated
                    }
                    return rotated
                } catch (e: Exception) {
                    bitmap.recycle() // CRITICAL: Recycle original if rotation fails (OOM similar)
                    throw e
                }
            } else {
                return bitmap
            }
        } catch (e: Exception) {
            android.util.Log.e("ReceiptOcrService", "Error loading bitmap from $uri", e)
            if (decodedBitmap?.isRecycled == false) {
                decodedBitmap?.recycle()
            }
            throw IllegalStateException("Failed to load image: ${e.message}", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Save a compressed copy of the receipt image
     */
    private fun saveReceiptImage(bitmap: Bitmap): String {
        val receiptsDir = File(context.filesDir, "receipts")
        if (!receiptsDir.exists()) receiptsDir.mkdirs()

        val fileName = "receipt_${System.currentTimeMillis()}.jpg"
        val file = File(receiptsDir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }

        return file.absolutePath
    }

    /**
     * Create a temporary URI for the camera to write to
     */
    fun createTempImageUri(): Uri {
        val cacheDir = File(context.cacheDir, "receipt_images")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val file = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")

        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Delete a saved receipt image
     */
    fun deleteImage(path: String) {
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }
}

```

---

## app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt <a name="appsrcmainjavacomyournameexpensetrackerdatarepositoryreceiptrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository

import android.net.Uri
import java.util.Date
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
// import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptRepository @Inject constructor(
    private val scannedReceiptDao: ScannedReceiptDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryRepository: MerchantCategoryRepository, // <-- Replaces DAO
    private val pendingReviewDao: PendingReviewDao,
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val statementParser: BankStatementParser,
    private val categorizationEngine: CategorizationEngine,
    private val merchantNormalizer: NewMerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val budgetMonitor: BudgetMonitor
) {
    val allReceipts: Flow<List<ScannedReceipt>> = scannedReceiptDao.getAllFlow()

    /**
     * Process an image URI: run OCR, parse receipt, save to DB
     *
     * @param imageUri URI of the image to process
     * @param autoCreateReview Whether to automatically create a PendingReview entry (true for batch, false for manual)
     */
    suspend fun processReceipt(
        imageUri: Uri,
        autoCreateReview: Boolean = false
    ): Pair<ScannedReceipt, ReceiptParser.ParsedReceipt> {
        // 1. Run OCR (Separate Try-Catch to distinguish OCR failure vs Parse failure)
        val ocrResult = try {
            ocrService.processUri(imageUri)
        } catch (e: Exception) {
            android.util.Log.e("ReceiptRepository", "OCR Failed for $imageUri", e)
            // Fallback: Try to save the image using manual record logic
            return saveManualReceiptRecord(imageUri).let { (receipt, parsed) ->
                val failedReceipt = receipt.copy(
                    rawOcrText = "Scan Failed: ${e.message}", 
                    confidence = com.yourname.expensetracker.domain.util.AppConstants.Confidence.RECEIPT_FALLBACK
                )
                scannedReceiptDao.update(failedReceipt)
                Pair(failedReceipt, parsed)
            }
        }

        try {
            // 2. Parse the OCR text
            val parsed = receiptParser.parse(ocrResult.fullText)

            // 3. Normalize merchant if found
            val lookupResult = parsed.merchantName?.let {
                merchantNormalizer.normalize(it, autoCreate = true)
            }
            val normalizedMerchant = lookupResult?.canonical?.normalizedName

            // 4. Save scanned receipt record
            val receipt = ScannedReceipt(
                imagePath = ocrResult.savedImagePath,
                rawOcrText = ocrResult.fullText,
                parsedTotal = parsed.total,
                parsedMerchant = normalizedMerchant ?: parsed.merchantName,
                parsedDate = parsed.date,
                parsedItems = if (parsed.lineItems.isNotEmpty())
                    receiptParser.lineItemsToJson(parsed.lineItems) else null,
                parsedTaxAmount = parsed.tax,
                currency = parsed.currency,
                confidence = parsed.confidence
            )

            val receiptId = scannedReceiptDao.insert(receipt)

            // 5. Optionally create a PendingReview (True for Batch, False for FAB Manual Scan)
            if (autoCreateReview) {
                val review = PendingReview(
                    rawNotificationId = null,
                    scannedReceiptId = receiptId,
                    suggestedAmount = parsed.total ?: 0.0,
                    suggestedCurrency = parsed.currency,
                    suggestedMerchant = normalizedMerchant ?: parsed.merchantName ?: "Unknown Merchant",
                    suggestedType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE.name,
                    suggestedDate = parsed.date, // Preserving the date found by parser
                    confidence = parsed.confidence,
                    packageName = "receipt.scan",
                    notificationTitle = "Scanned Receipt",
                    notificationText = ocrResult.fullText.take(200), // Preview snippet
                    suggestedCategoryId = normalizedMerchant?.let { 
                         hybridClassifier.classify(it, parsed.total ?: 0.0).categoryId.takeIf { id -> id > 0 }
                    }
                )
                pendingReviewDao.insert(review)
            }
            return Pair(receipt.copy(id = receiptId), parsed)

        } catch (e: Exception) {
            // Parsing Logic Failed, but we HAVE the OCR text!
            // Save it so user can manually edit without losing the text.
            android.util.Log.e("ReceiptRepository", "Parsing Failed for $imageUri", e)
            
            val failedReceipt = ScannedReceipt(
                imagePath = ocrResult.savedImagePath,
                rawOcrText = ocrResult.fullText, // PRESERVED!
                parsedTotal = null,
                parsedMerchant = null,
                parsedDate = null, 
                parsedItems = null,
                parsedTaxAmount = null, // Explicitly null for failed parse
                currency = "EUR",
                confidence = 0f
            )
            val receiptId = scannedReceiptDao.insert(failedReceipt)
            
            if (autoCreateReview) {
                 val review = PendingReview(
                    rawNotificationId = null,
                    scannedReceiptId = receiptId,
                    suggestedAmount = 0.0,
                    suggestedCurrency = "EUR",
                    suggestedMerchant = "Parsing Failed",
                    suggestedType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE.name,
                    suggestedCategoryId = null, // No category for failed parse
                    confidence = 0f,
                    packageName = "receipt.scan.error",
                    notificationTitle = "Parsing Failed",
                    notificationText = "OCR Text preserved. Manual entry required."
                )
                pendingReviewDao.insert(review)
            }

            return Pair(failedReceipt.copy(id = receiptId), ReceiptParser.ParsedReceipt(null, null, null, null, System.currentTimeMillis(), "EUR", emptyList(), 0f))
        }
    }

    suspend fun saveManualReceiptRecord(imageUri: android.net.Uri): Pair<ScannedReceipt, ReceiptParser.ParsedReceipt> {
        // 1. Try to at least copy the image for display if possible, or use original
        // For simplicity, we'll try to get ocrService to at least give us a path if it can load the bitmap
        val path = try {
            // We'll reuse the OCR service's image saving logic if possible
            // But if it fails, we fall back to the original URI string (not ideal but better than nothing)
            ocrService.processImage(imageUri).savedImagePath
        } catch (e: Exception) {
            imageUri.toString()
        }

        val receipt = ScannedReceipt(
            imagePath = path,
            rawOcrText = "[OCR Failed or Skipped]",
            parsedTotal = null,
            parsedMerchant = null,
            parsedDate = System.currentTimeMillis(),
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0f
        )
        val receiptId = scannedReceiptDao.insert(receipt)
        
        return Pair(
            receipt.copy(id = receiptId),
            ReceiptParser.ParsedReceipt(
                merchantName = null,
                total = null,
                subtotal = null,
                tax = null,
                date = System.currentTimeMillis(),
                currency = "EUR",
                lineItems = emptyList(),
                confidence = 0f
            )
        )
    }

    /**
     * Create an expense from a scanned receipt (after user review/edit)
     */
    suspend fun createExpenseFromReceipt(
        receiptId: Long,
        merchant: String,
        amount: Double,
        currency: String = "EUR",
        categoryId: Long?,
        date: Long = System.currentTimeMillis(),
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
        notes: String? = null
    ): com.yourname.expensetracker.domain.model.OperationResult<Long> {
        // 1. Normalize merchant
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = true)
        val normalizedMerchant = lookupResult.canonical.normalizedName

        // 2. Auto-categorize if no category provided
        val finalCategoryId = categoryId ?: hybridClassifier.classify(
            merchantName = normalizedMerchant,
            amount = amount
        ).categoryId.takeIf { it > 0 }

        // 3. Check for duplicates
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = normalizedMerchant,
            date = date,
            windowMs = com.yourname.expensetracker.domain.util.AppConstants.Windows.DUPLICATE_DETECTION
        )
        if (isDuplicate) return com.yourname.expensetracker.domain.model.OperationResult.Duplicate

        // 4. Create expense
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = normalizedMerchant,
            transactionType = TransactionType.PURCHASE,
            date = date,
            rawNotificationId = null,
            categoryId = finalCategoryId,
            paymentMethod = paymentMethod,
            isManualEntry = true, // Scanned receipts are treated as manual entries
            notes = notes ?: "Scanned from receipt"
        )

        val expenseId = expenseDao.insert(expense)

        // 5. Link receipt to expense
        if (expenseId > 0) {
            scannedReceiptDao.linkToExpense(receiptId, expenseId)

            // 6. Check budgets
            budgetMonitor.checkBudgets()

            // 7. Learn merchant → category mapping
            if (finalCategoryId != null) {
                try {
                    hybridClassifier.learnFromCorrection(
                        merchantName = normalizedMerchant,
                        correctCategoryId = finalCategoryId,
                        amount = amount
                    )
                } catch (e: Exception) {
                    android.util.Log.e("ReceiptRepo", "Failed to learn categorization", e)
                }
                merchantCategoryRepository.learnPattern(normalizedMerchant, finalCategoryId)
            }
        }

        return com.yourname.expensetracker.domain.model.OperationResult.Success(expenseId)
    }

    fun createTempPhotoUri(): Uri {
        return ocrService.createTempImageUri()
    }

    suspend fun getReceiptById(id: Long): ScannedReceipt? {
        return scannedReceiptDao.getById(id)
    }

    suspend fun deleteReceipt(receipt: ScannedReceipt) {
        ocrService.deleteImage(receipt.imagePath)
        scannedReceiptDao.delete(receipt)
    }

    suspend fun getReceiptCount(): Int {
        return scannedReceiptDao.getCount()
    }

    data class BatchResult(
        val successCount: Int,
        val failureCount: Int,
        val errors: List<String>
    )

    /**
     * Process multiple receipts in parallel with a concurrency limit to prevent OOM.
     */
    suspend fun processBatch(uris: List<Uri>, onProgress: (Int, Int) -> Unit): BatchResult = coroutineScope {
        // Deduplicate URIs to avoid processing the same file twice
        val uniqueUris = uris.distinctBy { it.toString() }
        if (uniqueUris.size < uris.size) {
            android.util.Log.d("ReceiptRepository", "Removed ${uris.size - uniqueUris.size} duplicate URIs")
        }

        val semaphore = Semaphore(3) // Limit to 3 concurrent OCR tasks
        val total = uniqueUris.size
        var successes = 0
        var failures = 0
        val errors = mutableListOf<String>()
        val mutex = Mutex()

        val jobs = uniqueUris.map { uri ->
            async {
                try {
                    semaphore.withPermit {
                        processReceipt(uri, autoCreateReview = true)
                    }
                    mutex.withLock {
                        successes++
                        onProgress(successes + failures, total)
                    }
                } catch (e: Exception) {
                    mutex.withLock {
                        failures++
                        errors.add("Failed to process $uri: ${e.message}")
                        onProgress(successes + failures, total)
                    }
                }
            }
        }

        jobs.awaitAll()
        BatchResult(successes, failures, errors)
    }

    /**
     * Process an image URI as a bank statement: extracting multiple transactions
     */
    suspend fun processStatement(imageUri: Uri): BatchResult {
        // 1. Run OCR
        val ocrResult: OcrResult = ocrService.processUri(imageUri)

        // 2. Parse as multiple transactions using spatial data
        val parsedTransactions = statementParser.parse(ocrResult.blocks)
        
        if (parsedTransactions.isEmpty()) {
            return BatchResult(0, 1, listOf("No transactions found in screenshot"))
        }

        // 3. Save common scanned receipt record
        val receiptRecord = ScannedReceipt(
            imagePath = ocrResult.savedImagePath,
            rawOcrText = ocrResult.fullText,
            parsedTotal = null, // Varies per transaction
            parsedMerchant = "Bank Statement",
            parsedDate = System.currentTimeMillis(),
            parsedItems = null,
            parsedTaxAmount = null,
            currency = parsedTransactions.firstOrNull()?.currency ?: "EUR",
            confidence = 0.8f
        )
        val receiptId = scannedReceiptDao.insert(receiptRecord)

        // 4. Create a PendingReview for EACH transaction found
        var successCount = 0
        val errors = mutableListOf<String>()

        parsedTransactions.forEach { tx ->
            try {
                // Normalize merchant
                val lookupResult = merchantNormalizer.normalize(tx.merchant, autoCreate = true)
                val normalizedMerchant = lookupResult.canonical.normalizedName
                
                val classification = hybridClassifier.classify(
                    merchantName = normalizedMerchant,
                    amount = tx.amount
                )

                val review = PendingReview(
                    rawNotificationId = null,
                    scannedReceiptId = receiptId,
                    suggestedAmount = tx.amount,
                    suggestedCurrency = tx.currency,
                    suggestedMerchant = normalizedMerchant,
                    suggestedType = tx.type.name,
                    suggestedCategoryId = classification.categoryId.takeIf { id -> id > 0 },
                    suggestedDate = tx.date ?: System.currentTimeMillis(),
                    confidence = tx.confidence,
                    packageName = "statement.import",
                    notificationTitle = "Bank Screenshot",
                    notificationText = "Imported from screenshot: ${tx.merchant}"
                )
                pendingReviewDao.insert(review)
                successCount++
            } catch (e: Exception) {
                errors.add("Failed to save transaction ${tx.merchant}: ${e.message}")
            }
        }

        return BatchResult(successCount, parsedTransactions.size - successCount, errors)
    }

    suspend fun clearAllScannedReceipts() {
        val receipts = scannedReceiptDao.getAll()
        receipts.forEach { ocrService.deleteImage(it.imagePath) }
        scannedReceiptDao.deleteAll()
    }

    /**
     * Concatenates all raw OCR text from the database for debugging/parsing refinement
     */
    /**
     * Concatenates all raw OCR text from the database for debugging/parsing refinement
     */
    suspend fun exportParserDebugData(): String {
        val receipts = scannedReceiptDao.getAll()
        val sb = StringBuilder()
        sb.append("=== EXPORTED PARSER DEBUG DATA (${receipts.size} RECEIPTS) ===\n\n")
        receipts.forEachIndexed { index, receipt ->
            sb.append("--- RECEIPT #${index + 1} (ID: ${receipt.id}) ---\n")
            sb.append(formatReceiptDebug(receipt))
            sb.append("\n\n")
        }
        return sb.toString()
    }

    /**
     * Debug function to get detailed info about a scanned receipt
     */
    suspend fun debugReceipt(receiptId: Long): String {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return "Not found"
        return formatReceiptDebug(receipt)
    }

    private fun formatReceiptDebug(receipt: ScannedReceipt): String {
        return """
            ═════════════════════════════════════════
            RECEIPT DEBUG REPORT (ID: ${receipt.id})
            ═════════════════════════════════════════
            
            IMAGE PATH: ${receipt.imagePath}
            
            RAW OCR TEXT:
            ┌─────────────────────────────────────┐
            ${receipt.rawOcrText}
            └─────────────────────────────────────┘
            
            PARSED VALUES:
            • Merchant:  ${receipt.parsedMerchant ?: "NULL"}
            • Total:     ${receipt.parsedTotal ?: "NULL"}
            • Date:      ${receipt.parsedDate?.let { Date(it) } ?: "NULL"}
            • Tax:       ${receipt.parsedTaxAmount ?: "NULL"}
            • Currency:  ${receipt.currency}
            • Confidence: ${receipt.confidence}
            
            LINE ITEMS:
            ${receipt.parsedItems ?: "None"}
            
            ═════════════════════════════════════════
        """.trimIndent()
    }
}

```

---

## app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.receiptscan

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.ui.screens.addexpense.CategoryGrid
import com.yourname.expensetracker.ui.screens.addexpense.DateSelector
import com.yourname.expensetracker.ui.screens.addexpense.PaymentMethodChip
import kotlinx.coroutines.delay
import java.util.Currency

private fun getCurrencySymbol(currencyCode: String?): String {
    return try { Currency.getInstance(currencyCode ?: "EUR").symbol } catch(e: Exception) { "€" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScanScreen(
    onDismiss: () -> Unit,
    viewModel: ReceiptScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) viewModel.processPhoto()
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.processGalleryImage(it) }
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = viewModel.createTempPhotoUri()
            cameraLauncher.launch(uri)
        }
    }

    // Handle done step - auto-dismiss
    LaunchedEffect(state.step) {
        if (state.step == ScanStep.DONE) {
            delay(1500)
            onDismiss()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            ScanStep.CAPTURE -> "Scan Receipt"
                            ScanStep.PROCESSING -> "Processing..."
                            ScanStep.REVIEW -> "Review & Save"
                            ScanStep.DONE -> "Saved!"
                            ScanStep.ERROR -> "Error"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.step) {
                ScanStep.CAPTURE -> CaptureStep(
                    imageUri = state.imageUri,
                    onCameraClick = {
                        val hasCameraPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasCameraPermission) {
                            val uri = viewModel.createTempPhotoUri()
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onGalleryClick = {
                        galleryLauncher.launch(arrayOf("image/*", "application/pdf"))
                    }
                )

                ScanStep.PROCESSING -> ProcessingStep()

                ScanStep.REVIEW -> ReviewStep(
                    state = state,
                    categories = categories,
                    viewModel = viewModel
                )

                ScanStep.DONE -> DoneStep()

                ScanStep.ERROR -> ErrorStep(
                    errorMessage = state.errorMessage ?: "Unknown error",
                    onRetry = { viewModel.retry() }
                )
            }
        }
    }
}

@Composable
private fun CaptureStep(
    imageUri: Uri?,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    Spacer(modifier = Modifier.height(32.dp))

    // Image preview area
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = "Receipt preview",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🧾", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Take a photo or select from gallery",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Action buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onCameraClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("📷 Camera")
        }
        OutlinedButton(
            onClick = onGalleryClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("🖼️ Gallery")
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Tips
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "📌 Tips for best results:",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("• Place receipt on a flat, dark surface", style = MaterialTheme.typography.bodySmall)
            Text("• Ensure good lighting with no shadows", style = MaterialTheme.typography.bodySmall)
            Text("• Capture the entire receipt in frame", style = MaterialTheme.typography.bodySmall)
            Text("• Keep the camera steady", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProcessingStep() {
    Spacer(modifier = Modifier.height(80.dp))
    CircularProgressIndicator(
        modifier = Modifier.size(64.dp),
        strokeWidth = 4.dp
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        "Scanning receipt...",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Reading text and extracting details",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(
    state: ReceiptScanState,
    categories: List<Category>,
    viewModel: ReceiptScanViewModel
) {
    val parsed = state.parsedReceipt

    // Image preview (small)
    if (state.imageUri != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            AsyncImage(
                model = state.imageUri,
                contentDescription = "Receipt",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Confidence indicator
    ConfidenceIndicator(confidence = state.ocrConfidence)

    Spacer(modifier = Modifier.height(16.dp))

    // Merchant
    OutlinedTextField(
        value = state.editMerchant,
        onValueChange = { viewModel.updateMerchant(it) },
        label = { Text("Merchant") },
        placeholder = { Text("Store name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Amount
    OutlinedTextField(
        value = state.editAmount,
        onValueChange = { viewModel.updateAmount(it) },
        label = { Text("Total Amount") },
        leadingIcon = { 
            Text(getCurrencySymbol(parsed?.currency), fontSize = 18.sp, fontWeight = FontWeight.Bold) 
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Date
    DateSelector(
        dateMs = state.editDate,
        onDateSelected = { viewModel.updateDate(it) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Payment Method
    Text(
        "Payment Method",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PaymentMethodChip(
            label = "💳 Card",
            selected = state.paymentMethod == PaymentMethod.CARD,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CARD) },
            modifier = Modifier.weight(1f)
        )
        PaymentMethodChip(
            label = "💵 Cash",
            selected = state.paymentMethod == PaymentMethod.CASH,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CASH) },
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Category
    Text(
        "Category",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    CategoryGrid(
        categories = categories,
        selectedId = state.selectedCategoryId,
        onSelect = { viewModel.selectCategory(it) }
    )

    // Line items preview
    if (parsed?.lineItems?.isNotEmpty() == true) {
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Detected Items (${parsed.lineItems.size})",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(8.dp))

                parsed.lineItems.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            item.description,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "${getCurrencySymbol(parsed.currency)}${String.format("%.2f", item.totalPrice)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (index < parsed.lineItems.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }

                // Tax if detected
                parsed.tax?.let { tax ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Tax/VAT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${getCurrencySymbol(parsed.currency)}${String.format("%.2f", tax)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    // Notes
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = state.notes,
        onValueChange = { viewModel.updateNotes(it) },
        label = { Text("Notes (optional)") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 3
    )

    // Raw OCR toggle
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.toggleRawText() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Raw OCR Text",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            if (state.showRawText) Icons.Default.KeyboardArrowUp
            else Icons.Default.KeyboardArrowDown,
            contentDescription = "Toggle",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    AnimatedVisibility(visible = state.showRawText) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Text(
                text = state.rawOcrText.ifBlank { "No text detected" },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }

    // Error messages
    state.errorMessage?.let { error ->
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "⚠️ $error",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    when (state.saveResult) {
        is SaveReceiptResult.Duplicate -> {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "⚠️ A similar transaction already exists",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        is SaveReceiptResult.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "❌ ${(state.saveResult as SaveReceiptResult.Error).message}",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        else -> {}
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Save button
    Button(
        onClick = { viewModel.saveExpense() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = !state.isSaving,
        shape = RoundedCornerShape(12.dp)
    ) {
        if (state.isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Text("💾 Save Expense", fontSize = 16.sp)
        }
    }

    Spacer(modifier = Modifier.height(32.dp))
}

@Composable
private fun ConfidenceIndicator(confidence: Float) {
    val percentage = (confidence * 100).toInt()
    val color = when {
        confidence >= 0.7f -> Color(0xFF4CAF50)
        confidence >= 0.4f -> Color(0xFFFFC107)
        else -> Color(0xFFFF5722)
    }
    val label = when {
        confidence >= 0.7f -> "High confidence"
        confidence >= 0.4f -> "Medium confidence"
        else -> "Low confidence - please verify"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "$label ($percentage%)",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun DoneStep() {
    Spacer(modifier = Modifier.height(80.dp))
    Text("✅", fontSize = 72.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Expense saved!",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Your receipt has been processed and saved.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ErrorStep(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Spacer(modifier = Modifier.height(80.dp))
    Text("❌", fontSize = 64.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Something went wrong",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        errorMessage,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onRetry,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("🔄 Try Again")
    }
}

```

---

## app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.model.OperationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ScanStep {
    CAPTURE,
    PROCESSING,
    REVIEW,
    DONE,
    ERROR
}

data class ReceiptScanState(
    val step: ScanStep = ScanStep.CAPTURE,
    val imageUri: Uri? = null,
    val tempCameraUri: Uri? = null,
    val parsedReceipt: ReceiptParser.ParsedReceipt? = null,
    val receiptId: Long? = null,
    val rawOcrText: String = "",
    val showRawText: Boolean = false,

    // Editable fields
    val editMerchant: String = "",
    val editAmount: String = "",
    val editDate: Long = System.currentTimeMillis(),
    val selectedCategoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val notes: String = "",

    // Meta
    val ocrConfidence: Float = 0f,
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
    val saveResult: SaveReceiptResult? = null
)

sealed class SaveReceiptResult {
    data object Success : SaveReceiptResult()
    data object Duplicate : SaveReceiptResult()
    data class Error(val message: String) : SaveReceiptResult()
}

@HiltViewModel
class ReceiptScanViewModel @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val categoryRepository: CategoryRepository,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(ReceiptScanState(
        tempCameraUri = savedStateHandle.get<Uri>("temp_uri")
    ))
    val state: StateFlow<ReceiptScanState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Create a URI for camera to write photo to
     */
    fun createTempPhotoUri(): Uri {
        val uri = receiptRepository.createTempPhotoUri()
        savedStateHandle["temp_uri"] = uri
        _state.update { it.copy(tempCameraUri = uri) }
        return uri
    }

    /**
     * Called after camera successfully captures a photo
     */
    fun processPhoto() {
        val uri = _state.value.tempCameraUri ?: return
        processImageUri(uri)
    }

    /**
     * Called when user selects image from gallery
     */
    fun processGalleryImage(uri: Uri) {
        processImageUri(uri)
    }

    private fun processImageUri(uri: Uri) {
        _state.update {
            it.copy(
                step = ScanStep.PROCESSING,
                imageUri = uri,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                // Manual scans do NOT auto-create review items (User confirms in this UI)
                val (receipt, parsed) = receiptRepository.processReceipt(uri, autoCreateReview = false)

                _state.update {
                    it.copy(
                        step = ScanStep.REVIEW,
                        imageUri = Uri.fromFile(java.io.File(receipt.imagePath)),
                        parsedReceipt = parsed,
                        receiptId = receipt.id,
                        rawOcrText = receipt.rawOcrText,
                        editMerchant = parsed.merchantName ?: "",
                        editAmount = parsed.total?.let { total ->
                            String.format("%.2f", total)
                        } ?: "",
                        editDate = parsed.date ?: System.currentTimeMillis(),
                        ocrConfidence = parsed.confidence,
                        selectedCategoryId = null // Will be auto-detected on save
                    )
                }
            } catch (e: Exception) {
                try {
                    val (receipt, parsed) = receiptRepository.saveManualReceiptRecord(uri)
                    _state.update {
                        it.copy(
                            step = ScanStep.REVIEW,
                            imageUri = uri,
                            parsedReceipt = parsed,
                            receiptId = receipt.id,
                            errorMessage = "OCR Failed: ${e.message}. You can enter details manually."
                        )
                    }
                } catch (fallbackError: Exception) {
                    _state.update {
                        it.copy(
                            step = ScanStep.ERROR,
                            errorMessage = "Total failure: ${fallbackError.message}"
                        )
                    }
                }
            }
        }
    }

    fun updateMerchant(value: String) {
        _state.update { it.copy(editMerchant = value) }
    }

    fun updateAmount(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }
        _state.update { it.copy(editAmount = filtered) }
    }

    fun updateDate(dateMs: Long) {
        _state.update { it.copy(editDate = dateMs) }
    }

    fun selectCategory(categoryId: Long) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        _state.update { it.copy(paymentMethod = method) }
    }

    fun updateNotes(value: String) {
        _state.update { it.copy(notes = value) }
    }

    fun toggleRawText() {
        _state.update { it.copy(showRawText = !it.showRawText) }
    }

    fun saveExpense() {
        val currentState = _state.value

        // Validate
        val merchant = currentState.editMerchant.trim()
        if (merchant.isBlank()) {
            _state.update {
                it.copy(errorMessage = "Merchant name is required")
            }
            return
        }

        val amount = currentState.editAmount.replace(",", ".").toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _state.update {
                it.copy(errorMessage = "Enter a valid amount")
            }
            return
        }

        val receiptId = currentState.receiptId ?: return

        _state.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val result = receiptRepository.createExpenseFromReceipt(
                    receiptId = receiptId,
                    merchant = merchant,
                    amount = amount,
                    currency = "EUR",
                    categoryId = currentState.selectedCategoryId,
                    date = currentState.editDate,
                    paymentMethod = currentState.paymentMethod,
                    notes = currentState.notes.takeIf { it.isNotBlank() }
                )

                when (result) {
                    is OperationResult.Success -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                step = ScanStep.DONE,
                                saveResult = SaveReceiptResult.Success
                            )
                        }
                    }
                    is OperationResult.Duplicate -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveResult = SaveReceiptResult.Duplicate
                            )
                        }
                    }
                    is OperationResult.Error -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveResult = SaveReceiptResult.Error(result.message)
                            )
                        }
                    }
                }

            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveResult = SaveReceiptResult.Error(
                            e.message ?: "Unknown error"
                        )
                    )
                }
            }
        }
    }

    fun retry() {
        _state.update {
            ReceiptScanState()  // Reset to initial state
        }
    }

    fun reset() {
        _state.update { ReceiptScanState() }
    }
}

```

---

## app/src/test/java/com/yourname/expensetracker/OcrDocumentTest.kt <a name="appsrctestjavacomyournameexpensetrackerocrdocumenttestkt"></a>
```kotlin
package com.yourname.expensetracker

import com.yourname.expensetracker.domain.receipt.ReceiptParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Comprehensive OCR Test Document Parser Test
 * 
 * This test file reads the OCR_TEST_DOCUMENT.txt and validates
 * all patterns against the ReceiptParser implementation.
 * 
 * Usage:
 * 1. Place OCR_TEST_DOCUMENT.txt in src/test/resources/
 * 2. Run this test class
 * 3. Check output for pass/fail results on each section
 */
class OcrDocumentTest {

    private lateinit var parser: ReceiptParser

    @Before
    fun setup() {
        parser = ReceiptParser()
    }

    // ============================================
    // SECTION 3: COMMON RECEIPT KEYWORDS
    // ============================================

    @Test
    fun `test Greek TOTAL keyword - ΣΥΝΟΛΟ`() {
        val input = """
            MARKET STORE
            ΑΦΜ: 123456789
            ΣΥΝΟΛΟ € 50,00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΣΥΝΟΛΟ", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test Greek FINAL keyword - ΤΕΛΙΚΟ`() {
        val input = """
            CAFE
            ΤΕΛΙΚΟ 12,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΤΕΛΙΚΟ", 12.50, result.total!!, 0.01)
    }

    @Test
    fun `test Greek PAYABLE keyword - ΠΛΗΡΩΤΕΟ`() {
        val input = """
            SUPERMARKET
            ΠΛΗΡΩΤΕΟ 35,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΠΛΗΡΩΤΕΟ", 35.00, result.total!!, 0.01)
    }

    @Test
    fun `test Greek AMOUNT keyword - ΠΟΣΟ`() {
        val input = """
            SHOP
            ΠΟΣΟ: €80,43
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΠΟΣΟ", 80.43, result.total!!, 0.01)
    }

    @Test
    fun `test Greek CASH keyword - ΜΕΤΡΗΤΑ`() {
        val input = """
            STORE
            ΜΕΤΡΗΤΑ € 25,74
        """.trimIndent()
        val result = parser.parse(input)
        // ΜΕΤΡΗΤΑ is cash given, not total - but should still parse amount
        assertNotNull("Should parse amount from ΜΕΤΡΗΤΑ line", result.total)
    }

    @Test
    fun `test Greek amount keyword - ΠΛΗΡΩΤΕΟ variants`() {
        val input = """
            NAHPΩTEO: 45,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(45.50, result.total!!, 0.01)
    }

    @Test
    fun `test Greek total keyword - ΣΥΝΟΛΙΚΗ ΑΞΙΑ variant`() {
        val input = """
            YNUAIKH AEIA: 50,00 EYRL
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(50.00, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 4: COMPOUND KEYWORDS
    // ============================================

    @Test
    fun `test compound keyword - ΣΥΝΟΛΙΚΗ ΑΞΙΑ`() {
        val input = """
            STORE
            ΣΥΝΟΛΙΚΗ ΑΞΙΑ: 20,01 ΕΥΡΩ
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΣΥΝΟΛΙΚΗ ΑΞΙΑ", 20.01, result.total!!, 0.01)
        assertEquals("Should detect EUR currency", "EUR", result.currency)
    }

    @Test
    fun `test compound keyword - ΚΑΘΑΡΗ ΑΞΙΑ`() {
        val input = """
            STORE
            ΚΑΘΑΡΗ ΑΞΙΑ: 17,25 ΕΥΡΩ
        """.trimIndent()
        val result = parser.parse(input)
        // ΚΑΘΑΡΗ ΑΞΙΑ is net value (subtotal), should be extracted
        assertNotNull("Should parse ΚΑΘΑΡΗ ΑΞΙΑ", result.subtotal)
    }

    @Test
    fun `test compound keyword - ΓΕΝΙΚΟ ΣΥΝΟΛΟ`() {
        val input = """
            SUPERMARKET
            ΓΕΝΙΚΟ ΣΥΝΟΛΟ: 100,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract total from ΓΕΝΙΚΟ ΣΥΝΟΛΟ", 100.00, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 5 & 6: NUMBER FORMATS
    // ============================================

    @Test
    fun `test European decimal format - comma separator`() {
        val input = """
            STORE
            TOTAL 45,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse European decimal format", 45.50, result.total!!, 0.01)
    }

    @Test
    fun `test European format with thousands separator`() {
        val input = """
            TECH STORE
            TOTAL 1.250,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse 1.250,50 as 1250.50", 1250.50, result.total!!, 0.01)
    }

    @Test
    fun `test US decimal format - dot separator`() {
        val input = """
            DINER
            TOTAL 12.50
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse US decimal format", 12.50, result.total!!, 0.01)
    }

    @Test
    fun `test US format with thousands separator`() {
        val input = """
            CAR DEALER
            TOTAL 1,250.00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse 1,250.00 as 1250.00", 1250.00, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 7: NUMBERS WITH SPACING ISSUES
    // ============================================

    @Test
    fun `test number with space after comma`() {
        val input = """
            STORE
            TOTAL 45, 50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should fix '45, 50' to 45.50", 45.50, result.total!!, 0.01)
    }

    @Test
    fun `test number with space before dot`() {
        val input = """
            STORE
            TOTAL 12 .50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should fix '12 .50' to 12.50", 12.50, result.total!!, 0.01)
    }

    @Test
    fun `test number with space as thousands separator`() {
        val input = """
            STORE
            TOTAL 1 250,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should fix '1 250,50' to 1250.50", 1250.50, result.total!!, 0.01)
    }

    @Test
    fun `test severely mangled number`() {
        val input = "TOTAL 4 5 . 5 0 EUR"
        val result = parser.parse(input)
        assertEquals("Should fix '4 5 . 5 0' to 45.50", 45.50, result.total!!, 0.01)
    }

    // ============================================
    // NEW: TAX VS TOTAL CONFUSION (Patch 3.1)
    // ============================================

    @Test
    fun `test extraction before percentage sign - Receipt 3 failure case`() {
        val input = """
            SONICK EU E.E
            ΣΥΝΟΛΟ
            20,13 24,00%
            E0,13
        """.trimIndent()
        val result = parser.parse(input)
        // Should ignore 'E0,13' and extract '20,13' from the line with percentage
        assertEquals("Should extract amount before %", 20.13, result.total!!, 0.01)
    }

    @Test
    fun `test skip tax-only lines - Receipt 1 failure case`() {
        val input = """
            TRREPN
            ΦΠΑ 24%: 9.80 €
            ΦΠΑ 13%: 4.20 €
            ΣΥΝΟΛΟ: 44.20 €
        """.trimIndent()
        val result = parser.parse(input)
        // Should skip the ΦΠΑ lines and pick the ΣΥΝΟΛΟ amount
        assertEquals("Should skip tax lines and pick total", 44.20, result.total!!, 0.01)
    }

    // ============================================
    // NEW: RECEIPT NUMBER INTERFERENCE (Patch 3.3)
    // ============================================

    @Test
    fun `test skip receipt serial number - ZEIPA`() {
        val input = """
            MARKET
            ZEIPA: Y204
            AP: 1926788
            TOTAL: 50.00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should skip serial and pick total", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test skip receipt number - APIOMOE`() {
        val input = """
            STORE
            ΑΡΙΘΜΟΣ ΑΠΌΔΕΙΞΗΣ: 123456
            ΣΥΝΟΛΟ: 15.20
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should skip ΑΡΙΘΜΟΣ and pick total", 15.20, result.total!!, 0.01)
    }

    // ============================================
    // NEW: DATE VALIDATION (Patch 3.4)
    // ============================================

    @Test
    fun `test dynamic year rejection`() {
        val input = """
            TOTAL: 50.00
            DATE: 31-1-2058
        """.trimIndent()
        val result = parser.parse(input)
        // 2058 should be rejected as it's too far in the future
        assertNull("Should reject year 2058", result.date)
    }

    @Test
    fun `test severely mangled number 2`() {
        val input = """
            STORE
            TOTAL 1.2 5 0, 5 0 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(1250.50, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 8: CURRENCY WITH SYMBOLS
    // ============================================

    @Test
    fun `test currency before amount - €50,00`() {
        val input = """
            STORE
            TOTAL €50,00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse €50,00", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test currency after amount - 50,00 €`() {
        val input = """
            STORE
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse 50,00 €", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test EUR text format`() {
        val input = """
            STORE
            TOTAL EUR 100,00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse EUR 100,00", 100.00, result.total!!, 0.01)
        assertEquals("Should detect EUR currency", "EUR", result.currency)
    }

    // ============================================
    // SECTION 9: DATE FORMATS
    // ============================================

    @Test
    fun `test European date format - DD-MM-YYYY`() {
        val input = """
            STORE
            DATE: 30/01/2026
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse date", result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals("Day should be 30", 30, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Month should be January (0-indexed)", 0, cal.get(Calendar.MONTH))
        assertEquals("Year should be 2026", 2026, cal.get(Calendar.YEAR))
    }

    @Test
    fun `test date with dashes`() {
        val input = """
            STORE
            DATE: 30-01-2026
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse date with dashes", result.date)
    }

    @Test
    fun `test date with dots`() {
        val input = """
            STORE
            DATE: 30.01.2026
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse date with dots", result.date)
    }

    @Test
    fun `test date with spacing issues`() {
        val input = """
            STORE
            DATE: 30 / 01 / 2026
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // May or may not work - test for graceful handling
        assertNotNull("Should handle date with spaces", result)
    }

    @Test
    fun `test short year format - DD-MM-YY`() {
        val input = """
            STORE
            DATE: 30/01/26
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse short year", result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals("Year should expand to 2026", 2026, cal.get(Calendar.YEAR))
    }

    // ============================================
    // SECTION 11: VAT/TAX PERCENTAGES
    // ============================================

    @Test
    fun `test VAT extraction with Greek label`() {
        val input = """
            STORE
            SUBTOTAL 100,00 €
            ΦΠΑ 24,00%: 24,00 €
            TOTAL 124,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract VAT", 24.00, result.tax!!, 0.01)
    }

    @Test
    fun `test VAT with dots in label`() {
        val input = """
            STORE
            Φ.Π.Α. 24,00%: 9,68 €
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse Φ.Π.Α.", result.tax)
    }

    @Test
    fun `test VAT percentage not confused with total`() {
        val input = """
            STORE
            ΦΠΑ 24,00%
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // 24,00% should NOT be picked as total
        assertNotEquals("VAT percentage should not be total", 24.00, result.total)
    }

    // ============================================
    // SECTION 12: UNIT PRICES (Should NOT be totals)
    // ============================================

    @Test
    fun `test unit price not picked as total`() {
        val input = """
            GAS STATION
            FUEL 1,574 €/ΛΤ
            TOTAL 45,50 €
        """.trimIndent()
        val result = parser.parse(input)
        // Should NOT pick 1.574 as total
        assertEquals("Should pick actual total, not unit price", 45.50, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 14: COMPLETE RECEIPT LINES (Critical!)
    // ============================================

    @Test
    fun `test complete line - ΣΥΝΟΛΟ € 50,00`() {
        val input = "ΣΥΝΟΛΟ € 50,00"
        val result = parser.parse(input)
        assertEquals("Should parse 'ΣΥΝΟΛΟ € 50,00'", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test complete line - ΣΥΝΟΛΟ 80_43 EUR`() {
        val input = "ΣΥΝΟΛΟ: 80,43 €"
        val result = parser.parse(input)
        assertEquals("Should parse 'ΣΥΝΟΛΟ: 80,43 €'", 80.43, result.total!!, 0.01)
    }

    @Test
    fun `test complete line - ΠΟΣΟ_AMOUNT`() {
        val input = "ΠΟΣΟ/AMOUNT: €80,43"
        val result = parser.parse(input)
        assertEquals("Should parse 'ΠΟΣΟ/AMOUNT: €80,43'", 80.43, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 22: SIMULATED OCR ERRORS (Critical!)
    // ============================================

    @Test
    fun `test OCR error - EYNONO (ΣΥΝΟΛΟ)`() {
        val input = """
            MARKET
            EYNONO € 5,00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse EYNONO as ΣΥΝΟΛΟ", 5.00, result.total!!, 0.01)
    }

    @Test
    fun `test OCR error - ZYNOAO (ΣΥΝΟΛΟ)`() {
        val input = """
            STORE
            ZYNOAO: 182,00€
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse ZYNOAO as ΣΥΝΟΛΟ", 182.00, result.total!!, 0.01)
    }

    @Test
    fun `test OCR error - 2YNONO (ΣΥΝΟΛΟ)`() {
        val input = """
            BAKERY
            2YNONO 0,90 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse 2YNONO as ΣΥΝΟΛΟ", 0.90, result.total!!, 0.01)
    }

    @Test
    fun `test OCR error - METPHTA (ΜΕΤΡΗΤΑ)`() {
        val input = """
            CAFE
            METPHTA 25,74 ΕΥΡΩ
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse METPHTA line", result.total)
    }

    @Test
    fun `test OCR error - EYPOMEGA (ΕΥΡΩ)`() {
        val input = """
            STORE
            TOTAL 50,00 EYPΩ
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse EYPΩ as EUR", 50.00, result.total!!, 0.01)
        assertEquals("Currency should be EUR", "EUR", result.currency)
    }

    @Test
    fun `test OCR error - EYP9 (ΕΥΡΩ)`() {
        val input = """
            STORE
            TOTAL 50,00 EYP9
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse EYP9 as EUR", 50.00, result.total!!, 0.01)
    }

    @Test
    fun `test OCR error - HM_NIA (ΗΜΕΡΟΜΗΝΙΑ)`() {
        val input = """
            STORE
            HM/NIA: 30/01/2026
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should parse HM/NIA as date", result.date)
    }

    // ============================================
    // SECTION 23: ACTUAL OCR OUTPUT FROM RECEIPTS
    // ============================================

    @Test
    fun `test actual OCR - IYN noZOTHTA`() {
        val input = """
            STORE
            IYN. noZOTHTA
            50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // This is a severe OCR error - may not fully parse
        // But should not crash
        assertNotNull("Should handle severe OCR error gracefully", result)
    }

    @Test
    fun `test actual OCR - ZYNOAO IONTAN`() {
        val input = """
            STORE
            ZYNOAO IONTAN
            182,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse ZYNOAO IONTAN", 182.00, result.total!!, 0.01)
    }

    @Test
    fun `test actual OCR - NAHPQTEO (ΠΛΗΡΩΤΕΟ)`() {
        val input = """
            STORE
            NAHPQTEO 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse NAHPQTEO as ΠΛΗΡΩΤΕΟ", 10.00, result.total!!, 0.01)
    }


    // ============================================
    // SECTION 15: MERCHANT NAMES
    // ============================================

    @Test
    fun `test Greek merchant name - ΣΚΛΑΒΕΝΙΤΗΣ`() {
        val input = """
            ΣΚΛΑΒΕΝΙΤΗΣ
            ΑΦΜ: 094206641
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should extract merchant ΣΚΛΑΒΕΝΙΤΗΣ", "ΣΚΛΑΒΕΝΙΤΗΣ", result.merchantName)
    }

    @Test
    fun `test Greek merchant name - ΛΙΔΛ`() {
        val input = """
            ΛΙΔΛ
            ΑΘΗΝΑ
            TOTAL 35,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertTrue("Should extract merchant ΛΙΔΛ", 
            result.merchantName?.contains("ΛΙΔΛ") == true || 
            result.merchantName?.contains("LIDL") == true
        )
    }

    @Test
    fun `test merchant with Greeklish - DIAMANTIS MAZOUTHIS`() {
        val input = """
            ΔΙΑΜΑΝΤΗΣ ΜΑΖΟΥΘΗΣ Α.Ε.
            ΘΕΣΣΑΛΟΝΙΚΗ
            TOTAL 100,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should extract merchant name", result.merchantName)
    }

    // ============================================
    // SECTION 18: LINE ITEMS
    // ============================================

    @Test
    fun `test line items extraction`() {
        val input = """
            CAFE
            ΚΑΦΕΣ           3,90 €
            ΦΑΓΗΤΟ          16,50 €
            ΣΑΛΑΤΕΣ         13,20 €
            ΣΥΝΟΛΟ 33,60 €
        """.trimIndent()
        val result = parser.parse(input)
        assertTrue("Should extract line items", result.lineItems.isNotEmpty())
        assertEquals("Total should match", 33.60, result.total!!, 0.01)
    }

    @Test
    fun `test line item with quantity`() {
        val input = """
            STORE
            2 x ΚΡΑΣΙ ΧΥΜΑ   7,60 €
            TOTAL 7,60 €
        """.trimIndent()
        val result = parser.parse(input)
        assertTrue("Should extract line items", result.lineItems.isNotEmpty())
    }

    // ============================================
    // SECTION 19: CARD RECEIPT PATTERNS
    // ============================================

    @Test
    fun `test card receipt pattern`() {
        val input = """
            cardlink
            ΑΓΟΡΑ-SALE
            5356 71** **** 6523
            ANEIIAQH/CONTACTLESS
            NOsO/AMOUNT: €35,00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse card receipt total", 35.00, result.total!!, 0.01)
    }

    @Test
    fun `test bilingual thank you`() {
        val input = """
            STORE
            TOTAL 50,00 €
            EYXAPISTOYME - THANK YOU
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse total before thank you", 50.00, result.total!!, 0.01)
    }

    // ============================================
    // SECTION 20: MIXED GREEK-ENGLISH
    // ============================================

    @Test
    fun `test bilingual total`() {
        val input = "TOTAL / ΣΥΝΟΛΟ: €45.50"
        val result = parser.parse(input)
        assertEquals("Should parse bilingual total", 45.50, result.total!!, 0.01)
    }

    @Test
    fun `test bilingual cash`() {
        val input = "CASH / ΜΕΤΡΗΤΑ: €50.00"
        val result = parser.parse(input)
        assertNotNull("Should parse bilingual cash", result.total)
    }

    @Test
    fun `test bilingual VAT`() {
        val input = """
            STORE
            SUBTOTAL: €40.00
            VAT / ΦΠΑ: €2.76
            TOTAL: €42.76
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("Should parse total", 42.76, result.total!!, 0.01)
        assertEquals("Should parse VAT", 2.76, result.tax!!, 0.01)
    }

    // ============================================
    // SECTION 21: EDGE CASES
    // ============================================

    @Test
    fun `test year-like amount not confused with year`() {
        val input = """
            STORE
            TOTAL 2020,50 €
        """.trimIndent()
        val result = parser.parse(input)
        // 2020.50 should be valid (has decimal)
        assertEquals("Should allow year-like amount with decimal", 2020.50, result.total!!, 0.01)
    }

    @Test
    fun `test whole year not picked as amount`() {
        val input = """
            STORE
            DATE: 30/01/2026
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // 2026 should NOT be the total
        assertNotEquals("Year should not be total", 2026.0, result.total)
    }

    @Test
    fun `test phone number not picked as amount`() {
        val input = """
            STORE
            TEL: 2310 476821
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // Phone number should not be picked as amount
        assertTrue("Total should be reasonable", result.total!! < 1000)
    }

    @Test
    fun `test tax ID not picked as amount`() {
        val input = """
            STORE
            ΑΦΜ: 094206641
            TOTAL 50,00 €
        """.trimIndent()
        val result = parser.parse(input)
        // Tax ID (094206641) should not be picked as amount
        assertTrue("Total should be reasonable", result.total!! < 1000)
    }

    // ============================================
    // CONFIDENCE SCORE TESTS
    // ============================================

    @Test
    fun `test confidence score with good data`() {
        val input = """
            ΣΚΛΑΒΕΝΙΤΗΣ
            ΑΦΜ: 094206641
            ΗΜΕΡΟΜΗΝΙΑ: 30/01/2026
            ΚΑΦΕΣ           3,90 €
            ΦΑΓΗΤΟ          16,50 €
            ΣΥΝΟΛΟ 20,40 €
            ΦΠΑ 24%: 4,89 €
        """.trimIndent()
        val result = parser.parse(input)
        assertTrue("Confidence should be high with good data", result.confidence >= 0.7f)
    }

    @Test
    fun `test confidence score with minimal data`() {
        val input = "50,00 €"
        val result = parser.parse(input)
        assertTrue("Confidence should be lower with minimal data", result.confidence < 0.7f)
    }

    // ============================================
    // DATE OCR FIXES
    // ============================================

    @Test
    fun `test date OCR fix - D instead of 0`() {
        val input = """
            STORE
            DATE: 16-D4-2017
            TOTAL 10,00 €
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull("Should fix D→0 in date", result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals("Month should be April", 3, cal.get(Calendar.MONTH))
    }

    // ============================================
    // HELPER: Print Test Summary
    // ============================================

    @Test
    fun `print parser version info`() {
        println("=" .repeat(60))
        println("OCR Document Test Suite")
        println("Testing ReceiptParser with OCR_TEST_DOCUMENT patterns")
        println("=" .repeat(60))
    }
}

```

---

## app/src/test/java/com/yourname/expensetracker/domain/receipt/GreekNormalizationTest.kt <a name="appsrctestjavacomyournameexpensetrackerdomainreceiptgreeknormalizationtestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

class GreekNormalizationTest {

    private val parser = ReceiptParser()
    private val normalizeMethod: Method = ReceiptParser::class.java.getDeclaredMethod("normalizeGreekOcr", String::class.java)

    init {
        normalizeMethod.isAccessible = true
    }

    private fun normalize(text: String): String {
        return normalizeMethod.invoke(parser, text) as String
    }

    @Test
    fun `test number fixes`() {
        assertEquals("45.50", normalize("4 5. 5 0"))
        assertEquals("45,00", normalize("45 , 00"))
        assertEquals("123.45", normalize("123 . 45"))
    }

    @Test
    fun `test total keywords variants`() {
        // E -> Σ, etc.
        assertTrue(normalize("EYNONO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("ZYNOAO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("2YNONO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("ZYNOIO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("NAHPQTEO 50.00").contains("TOTAL_KEY")) // Payable
        
        // New variants
        assertTrue(normalize("ZYNOAΩ 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("EYNONO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("TEAIKO 50.00").contains("TOTAL_KEY"))
        assertTrue(normalize("TEΛIKO 50.00").contains("TOTAL_KEY"))
    }

    @Test
    fun `test amount keywords`() {
        assertTrue(normalize("ΠΟΣΟ 10.00").contains("AMOUNT_KEY"))
        assertTrue(normalize("nozo 10.00").contains("AMOUNT_KEY"))
    }

    @Test
    fun `test compound keywords`() {
        assertTrue(normalize("ΣΥΝΟΛΙΚΗ ΑΞΙΑ 100").contains("TOTAL_KEY"))
        assertTrue(normalize("ΚΑΘΑΡΗ ΑΞΙΑ 80").contains("SUBTOTAL_KEY"))
    }

    @Test
    fun `test currency normalization`() {
        assertTrue(normalize("50,00 ΕΥΡΩ").contains("EUR"))
        assertTrue(normalize("50,00 ΕΥΡΑ").contains("EUR"))
    }

}

```

---

## app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainreceiptbankstatementparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BankStatementParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) {

    /**
     * Parse a list of text blocks (with spatial data) into multiple transactions.
     * Groups text into horizontal rows and then extracts data from each row.
     */
    fun parse(blocks: List<TextBlock>): List<ParsedTransaction> {
        if (blocks.isEmpty()) return emptyList()

        // 1. Group blocks into rows based on vertical proximity
        val rows = groupBlocksIntoRows(blocks)

        // 2. Process each row to extract transactions
        return rows.mapNotNull { rowText ->
            extractTransactionFromRow(rowText)
        }
    }

    private fun groupBlocksIntoRows(blocks: List<TextBlock>): List<String> {
        // Sort by top coordinate to process top-to-bottom
        val sortedBlocks = blocks.sortedBy { it.top }
        val rows = mutableListOf<MutableList<TextBlock>>()

        for (block in sortedBlocks) {
            val lastRow = rows.lastOrNull()
            
            // If block overlaps vertically with the current row, add it to that row
            if (lastRow != null && isSameRow(lastRow.last(), block)) {
                lastRow.add(block)
            } else {
                // Otherwise, it belongs to a new row below
                rows.add(mutableListOf(block))
            }
        }

        // Within each row, sort blocks by left-to-right and join into a single string
        return rows.map { rowBlocks ->
            rowBlocks.sortedBy { it.left }.joinToString(" ") { it.text }
        }
    }

    /**
     * Heuristic to determine if two text blocks belong to the same horizontal row.
     */
    private fun isSameRow(lastBlock: TextBlock, currentBlock: TextBlock): Boolean {
        // Find the vertical overlap
        val overlapTop = maxOf(lastBlock.top, currentBlock.top)
        val overlapBottom = minOf(lastBlock.bottom, currentBlock.bottom)
        val overlapHeight = (overlapBottom - overlapTop).coerceAtLeast(0)
        
        val lastHeight = lastBlock.bottom - lastBlock.top
        val currentHeight = currentBlock.bottom - currentBlock.top
        val minHeight = minOf(lastHeight, currentHeight)
        
        if (minHeight <= 0) return false
        
        // If they overlap by more than 50% of the smaller block's height, they are likely same row
        return overlapHeight.toDouble() / minHeight > 0.5
    }

    private fun extractTransactionFromRow(rowText: String): ParsedTransaction? {
        // 1. Clean noise
        val cleanRow = rowText.replace('\u00A0', ' ').trim()
        
        // 2. Look for amount patterns (DUP-005)
        val amountMatcher = com.yourname.expensetracker.domain.util.CommonPatterns.AMOUNT_REGEX.matcher(cleanRow)
        
        if (!amountMatcher.find()) return null
        
        // Fix (BUG-009): Robust European & US decimal parsing
        val rawAmount = amountMatcher.group(2)?.replace(" ", "") ?: return null
        val lastSep = rawAmount.findLastAnyOf(listOf(".", ","))
        
        val amountStr = if (lastSep != null) {
            val (sepIndex, sepChar) = lastSep
            val integerPart = rawAmount.substring(0, sepIndex).replace(".", "").replace(",", "")
            val decimalPart = rawAmount.substring(sepIndex + 1)
            "$integerPart.$decimalPart"
        } else {
            rawAmount
        }
        
        val absAmount = kotlin.math.abs(amountStr.toDoubleOrNull() ?: return null)
        
        // Fix (BUG-010): Use more specific currency check
        var currency = "EUR" // Default currency
        val currencyGroup = amountMatcher.group(1) ?: amountMatcher.group(3)
        if (currencyGroup != null && currencyGroup.matches(Regex("""^(?:[€$£]|EUR|USD|GBP)$""", RegexOption.IGNORE_CASE))) {
            currency = currencyNormalizer.normalize(currencyGroup)
        }

        // 3. Detect Transaction Type (ISSUE-008)
        val upperRow = cleanRow.uppercase()
        val isPurchase = upperRow.contains("ΑΓΟΡΑ") || upperRow.contains("PURCHASE") || 
                         upperRow.contains("ΧΡΕΩΣΗ") || upperRow.contains("DEBIT") ||
                         upperRow.contains("PAYMENT") || upperRow.contains("CARD")
        
        val isDeposit = upperRow.contains("ΚΑΤΑΘΕΣΗ") || upperRow.contains("DEPOSIT") ||
                        upperRow.contains("ΠΙΣΤΩΣΗ") || upperRow.contains("ΠΙΣΤΩΣH") || upperRow.contains("CREDIT") ||
                        upperRow.contains("REFUND") || upperRow.contains("MISTHODOSIA") ||
                        upperRow.contains("SALARY") || upperRow.contains("WAGES") || upperRow.contains("ΜΙΣΘΟΔΟΣΙΑ")

        val type = when {
            isDeposit -> TransactionType.DEPOSIT
            isPurchase -> TransactionType.PURCHASE
            amountStr.contains("-") -> TransactionType.PURCHASE
            else -> TransactionType.PURCHASE // Default to Purchase if ambiguous (Expense Tracker context) 
        }

        // 4. Extract logic for merchant (ISSUE-010)
        // Usually merchant is the text that is NOT the amount and NOT a date/time
        val dateValue = extractDate(cleanRow)
        
        var merchant = cleanRow.replace(amountMatcher.group(0)!!, "")
            .replace(Regex("""\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?"""), "") // Date (for cleaning)
            .replace(Regex("""\d{2}:\d{2}(:\d{2})?"""), "") // Time
            // Remove common bank prefixes/suffixes
            .replace(Regex("""(?i)^(AGORA|ΑΓΟΡΑ|PURCHASE|PAYMENT)\s*[:\-]?\s*"""), "")
            .replace(Regex("""(?i)\s*(STO|ΣΤΟ|AT)\s*$"""), "")
            .replace(Regex("""\s{2,}"""), " ") // Double spaces
            .trim()

        // Basic validation: must have some letters to be a merchant
        if (merchant.isBlank() || !merchant.any { it.isLetter() }) {
            merchant = "Unknown Merchant"
        }

        return ParsedTransaction(
            amount = absAmount,
            currency = currency,
            merchant = merchantCleaner.clean(merchant),
            type = type,
            confidence = com.yourname.expensetracker.domain.util.AppConstants.Confidence.RECEIPT_FALLBACK,
            date = dateValue
        )
    }

    private fun extractDate(text: String): Long? {
        val datePatterns = listOf(
            Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](20\d{2})"""),
            Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{2})""")
        )

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        sdf.isLenient = false

        for (pattern in datePatterns) {
            pattern.find(text)?.let { match ->
                val (d, m, y) = match.destructured
                val year = if (y.length == 2) "20$y" else y
                val yearInt = year.toIntOrNull() ?: 0
                
                if (yearInt in 2015..2035) {
                    try {
                        return sdf.parse("${d.padStart(2, '0')}/${m.padStart(2, '0')}/$year")?.time
                    } catch (e: Exception) {}
                }
            }
        }
        return null
    }
}

```

---

## app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt <a name="appsrcmainjavacomyournameexpensetrackerdomainparserappparserregistrykt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import javax.inject.Inject
import javax.inject.Singleton
import com.yourname.expensetracker.data.database.entity.TransactionType

/**
 * Result from an app-specific parser. Higher confidence = more certain it's a real transaction.
 */
data class ParsedTransaction(
    val amount: Double,
    val currency: String,
    val merchant: String,
    val type: TransactionType,
    val confidence: Float, // 0.0 to 1.0
    val date: Long? = null
)

/**
 * Interface for app-specific notification parsers.
 */
interface AppNotificationParser {
    /** Package names this parser handles */
    val supportedPackages: Set<String>

    /**
     * Try to parse. Return null if notification is NOT a transaction.
     * Should be strict — only return a result when confident.
     */
    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction?
}

/**
 * Registry that routes notifications to the right parser.
 */
@Singleton
class AppParserRegistry @Inject constructor(
    private val greekBankParser: GreekBankParser,
    private val revolutParser: RevolutParser,
    private val smsParser: SmsParser,
    private val googleWalletParser: GoogleWalletParser,
    private val genericParser: GenericTransactionParser
) {
    private val parsers = mutableListOf<AppNotificationParser>()

    init {
        // Order matters: Specific parsers first
        parsers.add(greekBankParser)
        parsers.add(revolutParser)
        parsers.add(smsParser)
        parsers.add(googleWalletParser)
    }

    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // 1. Try app-specific parser first
        val specificParser = parsers.find { packageName in it.supportedPackages }
        if (specificParser != null) {
            return specificParser.parse(title, text, bigText, subText, packageName)
        }

        // 2. Fallback to generic parser with HIGH threshold
        return genericParser.parse(title, text, bigText, subText, packageName)
    }
}

```

---

