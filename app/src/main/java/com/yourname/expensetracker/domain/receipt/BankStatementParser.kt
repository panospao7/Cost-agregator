package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import javax.inject.Inject
import javax.inject.Singleton
import com.yourname.expensetracker.BuildConfig
import timber.log.Timber

@Singleton
class BankStatementParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner
) {
    companion object {
        // Header patterns for Greek National Bank
        private val ACCOUNT_NUMBER_PATTERN = Regex("""Κίνηση Λογαριασμού\s+(\d+)""")
        private val IBAN_PATTERN = Regex("""ΙΒΑΝ\s*Λογαριασμού[:\s]+(GR\d+)""")
        private val BALANCE_PATTERN = Regex("""Λογιστικό\s*Υπόλοιπο[:\s]+([\d.,]+)€""")

        // Date column keywords (Greek, English, Greeklish variants)
        // Transaction date keywords
        private val TRANSACTION_DATE_KEYWORDS = listOf(
            // Greek
            "ΗΜΕΡΟΜΗΝΙΑ ΣΥΝΑΛΛΑΓΗΣ", "ΗΜ/ΝΙΑ ΣΥΝΑΛΛΑΓΗΣ", "ΗΜΕΡΟΜΗΝΙΑ", "ΗΜ/ΝΙΑ",
            // English
            "DATE", "TRANSACTION DATE", "TX DATE", "POSTING DATE",
            // Greeklish
            "HM_NIA", "HM/NIA", "HMEROMHNIA", "hmeromhnia"
        )

        // Value date keywords (date when the transaction actually clears)
        private val VALUE_DATE_KEYWORDS = listOf(
            // Greek
            "ΗΜΕΡΟΜΗΝΙΑ ΑΞΙΑΣ", "ΗΜ/ΝΙΑ ΑΞΙΑΣ", "ΑΞΙΑ", "ΑΞΙΑΣ",
            // English
            "VALUE DATE", "VAL DATE", "VALUE DATE", "EFFECTIVE DATE",
            // Greeklish
            "AXIAS", "axia", "AXIA", "HM_NIA_AXIAS", "HM_NIA_AKIAS"
        )

        // Data class for date column detection info
        data class DateColumnInfo(
            val hasTransactionDateKeyword: Boolean,
            val hasValueDateKeyword: Boolean
        )
    }

    /**
     * Parse a list of text blocks (with spatial data) into multiple transactions.
     * Groups text into horizontal rows and then extracts data from each row.
     */
    fun parse(blocks: List<TextBlock>): List<ParsedTransaction> {
        if (blocks.isEmpty()) return emptyList()

        // 1. Group blocks into rows based on vertical proximity
        val rows = groupBlocksIntoRows(blocks)

        // 2. Detect header columns to identify which date is which
        val columnInfo = detectDateColumns(rows)

        // 3. Try Greek NBG specific parsing first
        val greekNbgTransactions = rows.mapNotNull { rowText ->
            tryParseGreekNbgTransaction(rowText)
        }
        
        // If we got good results from Greek NBG parser, use those
        if (greekNbgTransactions.isNotEmpty()) {
            Timber.d("Parsed ${greekNbgTransactions.size} Greek NBG transactions")
            return greekNbgTransactions
        }

        // 4. Otherwise fall back to generic parsing with column awareness
        return rows.mapNotNull { rowText ->
            extractTransactionFromRow(rowText, columnInfo)
        }
    }

    /**
     * Detect date column order from headers
     */
    private fun detectDateColumns(rows: List<String>): DateColumnInfo {
        val headerRows = rows.take(5).joinToString(" ").uppercase()
        
        val hasTransactionDateKeyword = TRANSACTION_DATE_KEYWORDS.any { headerRows.contains(it.uppercase()) }
        val hasValueDateKeyword = VALUE_DATE_KEYWORDS.any { headerRows.contains(it.uppercase()) }
        
        // If we have both keywords, transaction date should come first in typical Greek bank statements
        // If only one is present, the first date in each row is typically transaction date
        
        return DateColumnInfo(
            hasTransactionDateKeyword = hasTransactionDateKeyword,
            hasValueDateKeyword = hasValueDateKeyword
        )
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
    
    /**
     * Try to parse a Greek National Bank transaction line.
     * Format: 12/02/2026 15:22:23 11/02/2026 705 040 MASOUTIS Χ -4,00 1.602,57
     * 
     * Table columns:
     * - Ημερομηνία/Ώρα: 12/02/2026 15:22:23 (timestamp)
     * - Κατάστημα: 705 (store code - ignore)
     * - Σύν: 040 (transaction code - ignore)
     * - Περιγραφή: MASOUTIS (merchant name)
     * - Χ/Π: Χ (transaction type)
     * - Ποσό: -4,00 (amount)
     * - Λογιστικό Υπόλοιπο: 1.602,57 (balance - ignore)
     */
    private fun tryParseGreekNbgTransaction(rowText: String): ParsedTransaction? {
        val cleanRow = rowText.replace('\u00A0', ' ').trim()
        
        // Skip header rows and non-transaction rows
        if (cleanRow.contains("Ημερομηνία") || cleanRow.contains("Κατάστημα") || 
            cleanRow.contains("Περιγραφή") || cleanRow.isBlank()) {
            return null
        }
        
        try {
            // Split by whitespace
            val parts = cleanRow.split(Regex("\\s+"))
            if (parts.size < 6) return null
            
            // Find the Χ or Π indicator (this is our anchor point)
            val typeIndex = parts.indexOfFirst { it == "Χ" || it == "Π" }
            if (typeIndex < 0) return null
            
            // Extract transaction type
            val type = when (parts[typeIndex]) {
                "Χ" -> TransactionType.PURCHASE  // ΧΡΕΩΣΗ (debit)
                "Π" -> TransactionType.DEPOSIT   // ΠΙΣΤΩΣΗ (credit/transfer)
                else -> TransactionType.PURCHASE
            }
            
            // Extract amount (next part after Χ/Π)
            if (typeIndex + 1 >= parts.size) return null
            val amountStr = parts[typeIndex + 1]
            val amount = parseEuropeanNumber(amountStr)
            if (amount == null || amount == 0.0) return null
            
            // Extract timestamp (first two parts: date + time)
            if (parts.size < 2) return null
            val dateStr = parts[0]  // DD/MM/YYYY
            val timeStr = parts[1]  // HH:MM:SS
            val timestamp = parseGreekBankDateTime(dateStr, timeStr)
            
            // Extract merchant name
            // Everything between the timestamp/codes and the Χ/Π indicator
            // Skip: date (0), time (1), valeur date (2), store code (3), transaction code (4)
            // Start from index 5 (or first non-numeric after index 2) until typeIndex
            val merchantStartIndex = parts.drop(2).indexOfFirst { part ->
                // Find first part that's not a pure number (not 705, 040, etc.)
                !part.matches(Regex("\\d+")) && !part.matches(Regex("\\d{2}/\\d{2}/\\d{4}"))
            } + 2
            
            if (merchantStartIndex < 2 || merchantStartIndex >= typeIndex) {
                Timber.tag("BankStatementParser").w("Could not find merchant in: $cleanRow")
                return null
            }
            
            // Join all parts between merchant start and type indicator
            val merchantParts = parts.subList(merchantStartIndex, typeIndex)
            val merchant = merchantParts.joinToString(" ").trim()
            
            if (merchant.isBlank()) {
                Timber.tag("BankStatementParser").w("Empty merchant in: $cleanRow")
                return null
            }
            
            // Clean merchant name
            val cleanedMerchant = merchantCleaner.clean(merchant)
            
            Timber.tag("BankStatementParser").d("Parsed NBG: $cleanedMerchant €${kotlin.math.abs(amount)} ($type)")
            
            return ParsedTransaction(
                amount = kotlin.math.abs(amount),
                currency = "EUR",
                merchant = cleanedMerchant,
                type = type,
                confidence = 0.90f, // High confidence for structured format
                date = timestamp
            )
        } catch (e: Exception) {
            Timber.tag("BankStatementParser").w("Failed to parse NBG transaction: ${e.message} | Row: $cleanRow")
        return null
    }
}
    
    /**
     * Parse Greek bank date and time: DD/MM/YYYY HH:MM:SS
     */
    private fun parseGreekBankDateTime(dateStr: String, timeStr: String): Long? {
        return try {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
            sdf.isLenient = false
            sdf.parse("$dateStr $timeStr")?.time
        } catch (e: Exception) {
            // Fallback: try just the date
            parseGreekBankDate(dateStr)
        }
    }
    
    /**
     * Parse European number format: 1.602,57 -> 1602.57
     */
    private fun parseEuropeanNumber(numStr: String): Double? {
        return try {
            // Remove spaces, convert European format to US format
            val cleaned = numStr.trim().replace(" ", "")
            
            // Use parseAmount which handles both European and US formats
            AmountUtils.parseAmount(cleaned)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Parse Greek bank date format: DD/MM/YYYY
     */
    private fun parseGreekBankDate(dateStr: String): Long? {
        return try {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            sdf.isLenient = false
            sdf.parse(dateStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTransactionFromRow(rowText: String, columnInfo: DateColumnInfo = DateColumnInfo(false, false)): ParsedTransaction? {
        // 1. Clean noise
        val cleanRow = rowText.replace('\u00A0', ' ').trim()
        
        // Skip header rows
        if (cleanRow.contains("Ημερομηνία") || cleanRow.contains("ΗΜΕΡΟΜΗΝΙΑ") ||
            cleanRow.contains("Περιγραφή") || cleanRow.contains("ΠΕΡΙΓΡΑΦΗ") ||
            cleanRow.contains("ΠΟΣΟ") || cleanRow.contains("Ποσό") ||
            cleanRow.uppercase().contains("DATE") && cleanRow.uppercase().contains("AMOUNT") ||
            cleanRow.isBlank()) {
            return null
        }
        
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

        // 4. Extract dates - prioritize transaction date over value date
        // Find all dates in the row
        val allDates = extractAllDates(cleanRow)
        
        // Use transaction date if found, otherwise fall back to any date
        val dateValue = allDates.transactionDate 
            ?: allDates.valueDate 
            ?: allDates.firstDate

        // 5. Extract merchant - remove all dates from the row
        var merchant = cleanRow.replace(amountMatcher.group(0)!!, "")
            .replace(Regex("""\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?"""), "") // Dates
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

    /**
     * Extract all dates from a row and identify which is transaction date vs value date
     * Based on position: first date is typically transaction date, second is value date
     */
    private fun extractAllDates(text: String): AllDatesResult {
        val datePatterns = listOf(
            Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](20\d{2})"""),
            Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{2})""")
        )

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        sdf.isLenient = false

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val minYear = currentYear - 20

        val foundDates = mutableListOf<Long>()

        for (pattern in datePatterns) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                val (d, m, y) = match.destructured
                val year = if (y.length == 2) "20$y" else y
                val yearInt = year.toIntOrNull() ?: 0
                
                if (yearInt in minYear..currentYear) {
                    try {
                        val parsedDate = sdf.parse("${d.padStart(2, '0')}/${m.padStart(2, '0')}/$year")?.time
                        if (parsedDate != null) {
                            foundDates.add(parsedDate)
                        }
                    } catch (e: Exception) {
                        // Skip invalid dates
                    }
                }
            }
        }

        // First date is typically transaction date, second is value date
        return AllDatesResult(
            firstDate = foundDates.getOrNull(0),
            transactionDate = foundDates.getOrNull(0), // First date = transaction date
            valueDate = foundDates.getOrNull(1)
        )
    }

    data class AllDatesResult(
        val firstDate: Long?,
        val transactionDate: Long?,
        val valueDate: Long?
    )

    private fun extractDate(text: String): Long? {
        val datePatterns = listOf(
            Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](20\d{2})"""),
            Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{2})""")
        )

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        sdf.isLenient = false

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val minYear = currentYear - 20 // Allow up to 20 years of historical data

        for (pattern in datePatterns) {
            pattern.find(text)?.let { match ->
                val (d, m, y) = match.destructured
                val year = if (y.length == 2) "20$y" else y
                val yearInt = year.toIntOrNull() ?: 0
                
                if (yearInt in minYear..currentYear) {
                    try {
                        return sdf.parse("${d.padStart(2, '0')}/${m.padStart(2, '0')}/$year")?.time
                    } catch (e: Exception) {
                        Timber.d("Failed to parse date: $d/$m/$year")
                    }
                }
            }
        }
        return null
    }
}
