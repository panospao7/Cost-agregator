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
                        upperRow.contains("ΠΙΣΤΩΣΗ") || upperRow.contains("CREDIT") ||
                        upperRow.contains("REFUND") || upperRow.contains("MISTHODOSIA")

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
