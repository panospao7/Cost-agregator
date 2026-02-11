This is a fantastic set of data. By comparing the === EXPORTED PARSER DEBUG DATA (18.txt with the raw receipt images, I've identified the specific "blind spots" in your current parser.

The core issue isn't just the scanning quality; it's that standard OCR engines often struggle with Greek characters, often interpreting them as visually similar Latin characters or numbers (e.g., interpreting ΣΥΝΟΛΟ as EYNOAO or 2YNOAO).

Here is the breakdown of the problems and the code solution to fix them.

1. The Diagnostic: Why the Parser is Failing
I analyzed your debug logs against the likely real-world text. Here are the three main failure patterns:

Failure Type	What OCR Sees (Raw Text)	What It Means (Greek)	Why Parser Fails
"Leetspeak" Totals	EYNOAO, 2YNOAO, XYN. IOžOTHTA	ΣΥΝΟΛΟ (Total)	Regex expects Total or Sum. It doesn't know EYNOAO = TOTAL.
Date Transposition	HM/NIA, HMEP, 1/7/2025	ΗΜΕΡΟΜΗΝΙΑ (Date)	Regex misses dates labeled with HM/NIA or formats without leading zeros (e.g., 1/7/2025).
Merchant Obfuscation	4iamanthe Naeoythe	ΔΙΑΜΑΝΤΗΣ ΜΑΣΟΥΤΗΣ	The OCR reads Greek Delta Δ as 4 and Σ as E. The merchant lookup fails because of these typos.
2. The Solution: "Fuzzy" Greek Parsing
We need to evolve ReceiptParser.kt to include a Normalization Layer. Before we try to find the date or total, we will run the raw text through a cleaner that fixes common "OCR-to-Greek" mapping errors.

Step 1: Update ReceiptParser.kt
Replace your current extraction logic with this robust, multi-stage approach.

Kotlin
// src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt

package com.yourname.expensetracker.domain.receipt

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern
import javax.inject.Inject

class ReceiptParser @Inject constructor() {

    data class ParsedReceipt(
        val merchantName: String?,
        val date: Long?,
        val totalAmount: Double?
    )

    fun parse(rawText: String): ParsedReceipt {
        // 1. Normalize the text (Fix common Greek OCR errors)
        val normalizedLines = rawText.lines().map { normalizeOcrLine(it) }
        val fullNormalizedText = normalizedLines.joinToString("\n")

        // 2. Extract Data using enhanced strategies
        val merchant = extractMerchant(normalizedLines)
        val date = extractDate(fullNormalizedText)
        val total = extractTotal(normalizedLines) // Pass lines to analyze structure

        return ParsedReceipt(merchant, date, total)
    }

    /**
     * Fixes common OCR misinterpretations of Greek characters.
     * OCR often reads Greek letters as numbers or Latin look-alikes.
     */
    private fun normalizeOcrLine(line: String): String {
        return line.uppercase()
            .replace("EYNOAO", "ΣΥΝΟΛΟ")
            .replace("2YNOAO", "ΣΥΝΟΛΟ")
            .replace("ZYNOAO", "ΣΥΝΟΛΟ")
            .replace("XYNOAO", "ΣΥΝΟΛΟ")
            .replace("IYNOAO", "ΣΥΝΟΛΟ") // Common misread of ΣΥΝΟΛΟ
            .replace("METPHTA", "ΜΕΤΡΗΤΑ")
            .replace("HM/NIA", "ΗΜΕΡΟΜΗΝΙΑ")
            .replace("HMEP", "ΗΜΕΡΟΜΗΝΙΑ")
            .replace("A.1.A", "Α.Λ.Α") // Fiscal number
            .replace("AOM", "ΑΦΜ") // Tax ID
            .replace("EVP9", "EUR") // Euro symbol misread
            .replace("€", "EUR")
    }

    private fun extractMerchant(lines: List<String>): String? {
        // Strategy 1: Look for known "header" keywords that usually appear BELOW the merchant name
        // If we find "Address", "Tax ID" (AFM), or "Tel", the lines ABOVE are likely the merchant.
        val headerKeywords = listOf("ΑΦΜ", "AOM", "Α.Φ.Μ", "THA", "ΤΗΛ", "STR.", "ODOQS", "ΟΔΟΣ")
        
        for ((index, line) in lines.withIndex()) {
            if (headerKeywords.any { line.contains(it) }) {
                // Return the best-looking line before this marker
                // Usually line 0 or 1 is the merchant.
                return if (index > 0) cleanMerchantName(lines[index - 1]) else cleanMerchantName(line)
            }
        }

        // Strategy 2: Just take the first non-empty line if it looks valid
        return lines.firstOrNull { it.length > 3 }?.let { cleanMerchantName(it) }
    }

    private fun cleanMerchantName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Zα-ωΑ-Ω0-9\\s]"), "") // Remove garbage chars
            .trim()
    }

    private fun extractDate(text: String): Long? {
        // Regex for dd/MM/yyyy, dd-MM-yyyy, dd.MM.yyyy
        // Also handles OCR adding spaces like 14 / 03 / 2020
        val datePatterns = listOf(
            Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(\d{4})"""), // 15/04/2024
            Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(\d{2})""")   // 15/04/24
        )

        for (pattern in datePatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val (day, month, yearRaw) = match.destructured
                // Fix 2-digit years
                val year = if (yearRaw.length == 2) "20$yearRaw" else yearRaw
                
                try {
                    val dateStr = "$day/$month/$year"
                    val format = SimpleDateFormat("dd/MM/yyyy", Locale.US)
                    format.isLenient = false
                    return format.parse(dateStr)?.time
                } catch (e: Exception) {
                    continue // Try next match
                }
            }
        }
        return null
    }

    private fun extractTotal(lines: List<String>): Double? {
        // Keywords for Total in Greek and English
        val totalKeywords = listOf("ΣΥΝΟΛΟ", "TOTAL", "AMOUNT", "ΠΛΗΡΩΤΕΟ", "AEIA", "ΑΞΙΑ")
        
        // Regex to find amounts like 12,50 or 12.50
        // We look for a number at the END of the line or essentially the only thing on the line
        val amountRegex = Regex("""(\d+)[.,](\d{2})\s?(EUR|€|EVP|E)?""")

        // Reverse iteration: Totals are usually at the bottom
        for (line in lines.reversed()) {
            val upperLine = line.uppercase()
            
            // Case 1: The line contains "TOTAL" and the amount
            if (totalKeywords.any { upperLine.contains(it) }) {
                amountRegex.find(line)?.let {
                    return parseAmount(it.groupValues[1], it.groupValues[2])
                }
            }
        }
        
        // Fallback: If no "Total" keyword found, look for the LARGEST number in the bottom 50% of lines
        // This helps with receipts that just list numbers
        var maxAmount = 0.0
        val searchLimit = (lines.size * 0.5).toInt()
        
        for (i in lines.lastIndex downTo searchLimit) {
            val matches = amountRegex.findAll(lines[i])
            for (match in matches) {
                val amount = parseAmount(match.groupValues[1], match.groupValues[2])
                if (amount > maxAmount) {
                    maxAmount = amount
                }
            }
        }

        return if (maxAmount > 0) maxAmount else null
    }

    private fun parseAmount(whole: String, fraction: String): Double {
        return "$whole.$fraction".toDoubleOrNull() ?: 0.0
    }
}
3. Key Improvements Explained
The normalizeOcrLine Function:

This is the "secret sauce". The OCR saw EYNOAO in Receipt #1. Normal string matching fails. This function forces EYNOAO -> ΣΥΝΟΛΟ, which instantly makes the extractTotal function work.

It also handles AOM -> ΑΦΜ (Tax ID), which helps us locate the merchant (since the Merchant Name is usually printed right above the Tax ID).

Smarter Date Regex:

Your debug log showed 1/7/2025.

The updated regex (\d{1,2})\s?[/.-]\s?(\d{1,2}) handles single digits (1/7 instead of 01/07) and tolerates spaces that OCR sometimes inserts (1 / 7 / 2025).

Bottom-Up Total Search:

The code now searches from the bottom of the receipt up. The "Total" is almost always in the footer.

It includes a fallback: If it doesn't find the word "Total" (or ΣΥΝΟΛΟ), it scans the bottom half of the receipt for the largest dollar amount, which is a very high-probability guess for the total price.

4. Recommended Next Step
Would you like me to now generate a "Receipt Debugger" screen for your app? This would be a hidden screen where you can:

Take a photo.

See the Raw OCR Text side-by-side with the Parsed Result.

Highlight exactly which line the parser chose as the "Merchant" and "Total".

This will allow you to rapidly iterate on these regexes without recompiling and exporting logs every time.