package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import java.util.Locale
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.yourname.expensetracker.domain.core.money.CurrencyAssumption
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import com.yourname.expensetracker.BuildConfig
import kotlinx.coroutines.flow.first
import timber.log.Timber

@Singleton
class BankStatementParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner,
    private val timeProvider: TimeProvider,
    private val currencySettingsRepository: CurrencySettingsRepository
) {
    companion object {
        // ── Header / footer pre-filter constants ─────────────────────────────
        /** Minimum line length (characters) to be considered a real transaction row. */
        const val MIN_LINE_LENGTH: Int = 15

        /** Bank-specific keywords that identify header, footer, or metadata lines. */
        val HEADER_KEYWORDS: Set<String> = setOf(
            // Greek
            "Γ.Ε.Μ.Η", "Μ.Α.Ε", "Ημ/νία Εκτύπωσης", "Κίνηση", "Αρ. Κάρτας",
            "Σελίδα", "Αρ. Λογαριασμού", "ΑΡ. ΛΟΓΑΡΙΑΣΜΟΥ", "ΙΒΑΝ", "Υπόλοιπο",
            "Κατάστημα", "Ημερομηνία", "Περιγραφή", "ΠΟΣΟ", "Ποσό",
            // Greek — additional bank names and statement terms
            "ALPHA BANK", "EUROBANK", "ΠΕΙΡΑΙΩΣ", "ΚΑΡΤΑΣ", "ΛΟΓΑΡΙΑΣΜΟΥ",
            "ΥΠΟΚΑΤΑΣΤΗΜΑ",
            // English
            "www.", "page", "statement", "account", "balance", "date", "description",
            "amount", "debit", "credit", "transaction", "TOTAL", "SUBTOTAL",
            // Greeklish / other
            "SELIDA", "SELIS", "AP. CARD", "AP.  LOG",
            // NBG debit card statement column headers (OCR format)
            "Λογαριασμός", "Χ/Π", "Τερματικό", "Χρεωστικής Κάρτας",
            // English AM/PM markers
            "AM", "PM"
        )

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

        /**
         * Represents the detected position of the transaction-date column
         * relative to the value-date column in header rows.
         */
        enum class TransactionDateOrder {
            /** Transaction date appears before the value date in the header. */
            FIRST,
            /** Transaction date appears after the value date in the header. */
            SECOND,
            /** Header order could not be determined. */
            UNKNOWN
        }

        // Data class for date column detection info
        data class DateColumnInfo(
            val hasTransactionDateKeyword: Boolean,
            val hasValueDateKeyword: Boolean,
            val transactionDateOrder: TransactionDateOrder = TransactionDateOrder.UNKNOWN
        )
    }

    /**
     * Resolves the home currency from [CurrencySettingsRepository] as a suspend function.
     *
     * Falls back to "EUR" with a warning log and
     * [CurrencyAssumption.ASSUMED_HOME_CURRENCY] metadata hint if no home
     * currency is configured.
     */
    suspend fun resolveHomeCurrencySuspend(): String {
        return runCatching {
            val currency = currencySettingsRepository.homeCurrency().first()
            if (currency.isNotBlank()) {
                currency
            } else {
                Timber.w("No home currency configured, fallback to EUR (CurrencyAssumption.ASSUMED_HOME_CURRENCY)")
                "EUR"
            }
        }.getOrElse { e ->
            Timber.w(e, "Failed to read home currency, fallback to EUR")
            "EUR"
        }
    }

    /**
     * Parse a list of text blocks (with spatial data) into multiple transactions.
     * Groups text into horizontal rows and then extracts data from each row.
     *
     * @param blocks The OCR text blocks to parse.
     * @param homeCurrency The home currency to use when a transaction's currency
     *            cannot be determined from the text.  The caller must resolve this
     *            via [resolveHomeCurrencySuspend] or another mechanism.
     */
    fun parse(blocks: List<TextBlock>, homeCurrency: String): List<ParsedTransaction> {
        if (blocks.isEmpty()) return emptyList()

        // 1. Group blocks into rows based on vertical proximity
        // Now returns List<List<TextBlock>> to preserve spatial data
        val rows = groupBlocksIntoRowLists(blocks)
        
        // Convert to strings for existing logic and header detection
        var rowStrings = rows.map { rowBlocks ->
            rowBlocks.sortedBy { it.left }.joinToString(" ") { it.text }
        }

        // 1b. Pre-filter rows: strip headers, footers, page numbers, duplicates
        val preFiltered = preFilterRows(rows, rowStrings)
        val filteredRows = preFiltered.first
        rowStrings = preFiltered.second

        // 2. Detect header columns to identify which date is which
        val columnInfo = detectDateColumns(rowStrings)

        val transactions = mutableListOf<ParsedTransaction>()

        for (i in filteredRows.indices) {
            val rowBlocks = filteredRows[i]
            val rowText = rowStrings[i]

            // Auto-detect statement format from full OCR text.
            // This determines parser priority order to avoid wrong-format matches.
            val fullText = blocks.joinToString(" ") { it.text }
            val isRevolut = fullText.contains("Revolut", ignoreCase = true)
            val isGreekBank = fullText.contains("Τράπεζα") || fullText.contains("Εθνική") ||
                    fullText.contains("ALPHA BANK", ignoreCase = true) ||
                    fullText.contains("EUROBANK", ignoreCase = true) ||
                    fullText.contains("ΠΕΙΡΑΙΩΣ")

            // Try parsers in priority order based on detected format
            val parserOrder = when {
                isGreekBank -> listOf("greek", "generic", "revolut")
                isRevolut -> listOf("revolut", "generic", "greek")
                else -> listOf("generic", "greek", "revolut")
            }

            for (parser in parserOrder) {
                when (parser) {
                    "revolut" -> {
                        val revolutTx = tryParseRevolutTransaction(rowBlocks, homeCurrency)
                        if (revolutTx != null) {
                            transactions.add(revolutTx)
                            break
                        }
                    }
                    "greek" -> {
                        val greekNbgTx = tryParseGreekNbgTransaction(rowText, homeCurrency)
                        if (greekNbgTx != null) {
                            transactions.add(greekNbgTx)
                            break
                        }
                    }
                    "generic" -> {
                        val genericTx = extractTransactionFromRow(rowText, columnInfo, homeCurrency)
                        if (genericTx != null) {
                            transactions.add(genericTx)
                        }
                    }
                }
            }
        }

        return transactions
    }

    /**
     * Detect date column order from headers.
     *
     * Scans the first few rows for transaction-date and value-date keywords,
     * then compares their character positions to determine which date column
     * comes first.  The resolved [DateColumnInfo.transactionDateOrder] is
     * consumed by [extractTransactionFromRow] when choosing between two
     * parsed dates in data rows.
     */
    private fun detectDateColumns(rows: List<String>): DateColumnInfo {
        val headerText = rows.take(5).joinToString(" ").uppercase()

        // Find the *earliest* position of any transaction-date keyword
        var txDatePos: Int = Int.MAX_VALUE
        var hasTxKeyword = false
        for (keyword in TRANSACTION_DATE_KEYWORDS) {
            val pos = headerText.indexOf(keyword.uppercase())
            if (pos >= 0) {
                hasTxKeyword = true
                if (pos < txDatePos) txDatePos = pos
            }
        }

        // Find the *earliest* position of any value-date keyword
        var valDatePos: Int = Int.MAX_VALUE
        var hasValKeyword = false
        for (keyword in VALUE_DATE_KEYWORDS) {
            val pos = headerText.indexOf(keyword.uppercase())
            if (pos >= 0) {
                hasValKeyword = true
                if (pos < valDatePos) valDatePos = pos
            }
        }

        val order = when {
            hasTxKeyword && hasValKeyword && txDatePos < valDatePos ->
                Companion.TransactionDateOrder.FIRST
            hasTxKeyword && hasValKeyword && valDatePos < txDatePos ->
                Companion.TransactionDateOrder.SECOND
            // Only one keyword or identical positions — default to UNKNOWN
            else -> Companion.TransactionDateOrder.UNKNOWN
        }

        return DateColumnInfo(
            hasTransactionDateKeyword = hasTxKeyword,
            hasValueDateKeyword = hasValKeyword,
            transactionDateOrder = order
        )
    }

    private fun groupBlocksIntoRowLists(blocks: List<TextBlock>): List<List<TextBlock>> {
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

        return rows.map { it.sortedBy { block -> block.left } }
    }

    private fun groupBlocksIntoRows(blocks: List<TextBlock>): List<String> {
        return groupBlocksIntoRowLists(blocks).map { rowBlocks ->
            rowBlocks.joinToString(" ") { it.text }
        }
    }

    /**
     * Pre-filter rows to remove headers, footers, page numbers, bank info,
     * pure-number lines, date-only lines, and duplicates.
     *
     * Operates on row strings (text only) and returns the matching subset of
     * [rows] and [rowStrings] with filtered-out rows removed.
     *
     * @param rows The grouped block rows (spatial data preserved).
     * @param rowStrings The string representation of each row.
     * @return A pair of (filteredRows, filteredRowStrings) with noise removed.
     */
    private fun preFilterRows(
        rows: List<List<TextBlock>>,
        rowStrings: List<String>,
        minLineLength: Int = MIN_LINE_LENGTH
    ): Pair<List<List<TextBlock>>, List<String>> {
        if (rows.isEmpty() || rowStrings.isEmpty()) return Pair(rows, rowStrings)

        val keptIndices = mutableSetOf<Int>()
        val seenTexts = mutableSetOf<String>()

        for (i in rowStrings.indices) {
            val line = rowStrings[i].trim()

            // 1. Skip blank / very short lines (headers, page numbers, noise)
            if (line.length < minLineLength) continue

            // 1b. Filter out time-only patterns (πμ/μμ with digits) and pure time-of-day lines.
            //     BUT keep lines that contain a date (transaction data rows have dates).
            val hasDateForStep1b = Regex("""\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?""").containsMatchIn(line)
            if (!hasDateForStep1b && line.matches(Regex(".*[πμ]\\.[μ]\\.\\s*\\d+.*|.*[πμ]μ\\s*\\d+.*|\\d+:\\d+.*"))) continue

            // 2. Skip lines containing bank-specific keywords
            val upper = line.uppercase()
            if (HEADER_KEYWORDS.any { keyword -> upper.contains(keyword.uppercase()) }) continue

            // 3. Skip lines that are pure numbers (card/account numbers) or noise,
            //    UNLESS they also carry a date or amount pattern (possible OCR-mangled
            //    transaction row where the merchant name was not captured).
            val lineHasDateOrAmount = Regex("""\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?""").containsMatchIn(line) ||
                com.yourname.expensetracker.domain.util.CommonPatterns.AMOUNT_REGEX
                    .matcher(line)
                    .let { it.find() && it.group(2) != null }
            if (!line.any { it.isLetter() } && !lineHasDateOrAmount) continue

            // 4. Skip lines with only dates but no amounts
            val hasDatePattern = Regex("""\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?""").containsMatchIn(line)
            val hasAmount = com.yourname.expensetracker.domain.util.CommonPatterns.AMOUNT_REGEX
                .matcher(line)
                .let { it.find() && it.group(2) != null }
            if (hasDatePattern && !hasAmount) continue

            // 5. Skip single-word lines that have no date or amount pattern
            //     (headers, summary labels, noise like "TOTAL", "ΣΥΝΟΛΟ", etc.)
            val wordCount = line.split(Regex("\\s+")).count { it.isNotBlank() }
            if (wordCount <= 1 && !lineHasDateOrAmount) continue

            // 6. Skip exact duplicate lines (keep first occurrence)
            if (line in seenTexts) continue
            seenTexts.add(line)

            keptIndices.add(i)
        }

        val filteredRows = keptIndices.map { rows[it] }
        val filteredStrings = keptIndices.map { rowStrings[it] }
        return Pair(filteredRows, filteredStrings)
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
     * Classify a Revolut statement row description into a [ParsedTransactionType].
     *
     * This helper is intentionally local to statement parsing; standalone
     * `RevolutParser.kt` (notification-based) has its own classification and
     * will be addressed in Batch 6C.
     */
    private fun classifyRevolutStatementType(
        description: String,
        isMoneyInByPosition: Boolean
    ): ParsedTransactionType {
        val upper = description.uppercase()
        return when {
            // Transfers between accounts / people
            upper.contains("TRANSFER TO") ||
            upper.contains("TRANSFER FROM") ||
            upper.contains("RECEIVED FROM") -> ParsedTransactionType.TRANSFER

            // ATM / cash withdrawals
            upper.contains("ATM") ||
            upper.contains("CASH WITHDRAWAL") ||
            upper.contains("WITHDRAW") -> ParsedTransactionType.WITHDRAWAL

            // Refunds, top-ups, promo credits, add-money — all money-in
            upper.contains("REFUND") ||
            upper.contains("TOP-UP") ||
            upper.contains("TOP UP") ||
            upper.contains("TOPUP") ||
            upper.contains("PROMO") ||
            upper.contains("ADD MONEY") ||
            upper.contains("ADD-MONEY") -> ParsedTransactionType.DEPOSIT

            // Positional money-in that didn't match explicit keywords above
            isMoneyInByPosition -> ParsedTransactionType.DEPOSIT

            // Default: outgoing merchant spend
            else -> ParsedTransactionType.PURCHASE
        }
    }

    /**
     * Try to parse a Revolut transaction line.
     * Format: Apr 12, 2023 Tzakmaki Panagiota MoneyOut? MoneyIn? Balance
     * 
     * Uses horizontal positions to distinguish Money Out vs Money In.
     */
    private fun tryParseRevolutTransaction(rowBlocks: List<TextBlock>, homeCurrency: String = "EUR"): ParsedTransaction? {
        if (rowBlocks.isEmpty()) return null
        
        val rowText = rowBlocks.joinToString(" ") { it.text }
        
        // 1. Detect Revolut Date: MMM d, yyyy (e.g. Apr 12, 2023)
        // Match months like Jan, Feb, Mar, Apr, May, Jun, Jul, Aug, Sep, Oct, Nov, Dec
        val months = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"
        val dateRegex = Regex("""(?i)\b($months\s+\d{1,2})[\s,]*(\d{4})\b""")
        val dateMatch = dateRegex.find(rowText)
        if (dateMatch == null) {
            if (BuildConfig.DEBUG) Timber.d("RevolutParser: Date regex failed for row -> $rowText")
            return null
        }
        
        val dateStr = dateMatch.value
        val timestamp = parseRevolutDate(dateStr)
        
        // 2. Identify all amount blocks
        // Revolut amounts always have currency symbol: €12.50 or £10.00
        val amountMatches = Regex("""[€$£]\s*[\d.,]+""").findAll(rowText).map { it.value }.toList()
        
        if (amountMatches.size < 2) {
            if (BuildConfig.DEBUG) Timber.d("RevolutParser: Less than 2 amounts found (${amountMatches.size}) for row -> $rowText")
            return null
        }
        
        // The last is balance, the second-to-last is tx amount
        val txAmountStr = amountMatches[amountMatches.size - 2]
        val balanceStr = amountMatches.last()

        // Strip only the currency symbol and layout whitespace, then delegate
        // to AmountUtils for locale-safe parsing (handles both €1,234.56 and
        // €1.234,56 correctly).
        val rawAmountToken = txAmountStr.replace(Regex("""[€$£\s]"""), "")
        val parsedAmount = AmountUtils.parseAmount(rawAmountToken) ?: run {
            if (BuildConfig.DEBUG) Timber.d("RevolutParser: Failed to parse amount '$txAmountStr' in row -> $rowText")
            return null
        }
        val absAmount = kotlin.math.abs(parsedAmount)
        
        if (absAmount <= 0.0 || !absAmount.isFinite()) {
            if (BuildConfig.DEBUG) Timber.d("RevolutParser: Invalid absolute amount ($absAmount) in row -> $rowText")
            return null
        }
        
        var currency = homeCurrency
        if (txAmountStr.contains("£")) currency = "GBP"
        if (txAmountStr.contains("$")) currency = "USD"

        // 3. Determine if it's Money Out or Money In by spatial position
        // Find the block that contains the transaction amount to determine its X position
        val txAmountBlock = rowBlocks.find { it.text.contains(txAmountStr) } ?: rowBlocks.last()
        val balanceBlock = rowBlocks.find { it.text.contains(balanceStr) } ?: rowBlocks.last()

        // Use relative X position. 
        // Row starts at rowBlocks.first().left, ends at balanceBlock.right
        val rowLeft = rowBlocks.first().left
        val rowRight = balanceBlock.right
        val rowWidth = rowRight - rowLeft
        
        if (rowWidth <= 0) {
            if (BuildConfig.DEBUG) Timber.d("RevolutParser: Invalid row width ($rowWidth) in row -> $rowText")
            return null
        }
        
        val relativeX = (txAmountBlock.left - rowLeft).toFloat() / rowWidth
        
        // Layout: Date (0-15%), Description (15-60%), Money out (60-75%), Money In (75-90%), Balance (90-100%)
        val isMoneyInByPosition = relativeX > 0.75f

        // 4. Classify transaction type using description keywords + positional hint
        val type = classifyRevolutStatementType(rowText, isMoneyInByPosition)
        
        // Merchant is usually between the date and the first amount
        // Use the index of the MatchResult to correctly handle variable spacing
        val dateEndIndex = dateMatch.range.last + 1
        val firstAmountIndex = rowText.indexOf(txAmountStr)
        
        var merchant = if (firstAmountIndex > dateEndIndex) {
            rowText.substring(dateEndIndex, firstAmountIndex).trim()
        } else {
            "Unknown Merchant"
        }
        
        // Clean up merchant (remove "To:", "Card:", "Reference:", etc.)
        merchant = merchant.replace(Regex("""(?i)\b(To|From|Card|Reference|Fee)\s*:?\s*.*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            
        // Specific Revolut prefixes — strip for display but do NOT strip before
        // classification (classifyRevolutStatementType runs on raw rowText above).
        merchant = merchant.replace(Regex("""(?i)\bTransfer to\s+"""), "")
        merchant = merchant.replace(Regex("""(?i)\bTransfer from\s+"""), "")
        merchant = merchant.replace(Regex("""(?i)\bTop-up by\s+"""), "")

        if (merchant.isBlank()) merchant = "Revolut Transaction"

        val tx = ParsedTransaction(
            amount = absAmount,
            currency = currency,
            merchant = merchantCleaner.clean(merchant),
            type = type,
            confidence = 0.95f,
            date = timestamp
        )
        if (BuildConfig.DEBUG) Timber.d("RevolutParser: Successfully parsed -> ${tx.merchant} | $absAmount | ${tx.type}")
        return tx
    }

    private fun parseRevolutDate(dateStr: String): Long? {
        return try {
            // Normalize spaces and commas
            val normalized = dateStr.replace(Regex("""\s*,\s*|\s+"""), " ").trim()
            val formatter = DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US)
            LocalDate.parse(normalized, formatter)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Try to parse a Greek National Bank transaction line.
     *
     * Supports two formats:
     *
     * **Old format** (traditional bank statement):
     *   DATE TIME VALUE_DATE STORE_CODE TX_CODE MERCHANT X/P AMOUNT
     *   Example: 15/03/2025 10:30:00 17/03/2025 705 040 SKLAVENITIS ΜΑΡΚΟΠΟΥΛΟ Χ 12,50
     *
     * **New OCR format** (NBG debit card statement):
     *   DATE TIME AM/PM 00 X/P AMOUNT TERMINAL DESCRIPTION
     *   Example: 3/5/2026 4:41:36 μμ 00 Χ -2,9 85016130 ΑΓΟΡΑ (ΕΞΟΥΣΙΟΔΟΤΗΣΗ) - YES STORES
     */
    private fun tryParseGreekNbgTransaction(rowText: String, homeCurrency: String = "EUR"): ParsedTransaction? {
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

            // Detect format: if parts[2] is πμ or μμ → new OCR format
            val isNewFormat = parts.size > 2 && (parts[2] == "πμ" || parts[2] == "μμ")

            if (isNewFormat) {
                // ── New OCR format ────────────────────────────────────────────
                // parts[0]=date, [1]=time, [2]=πμ/μμ, [3]=00, [4]=X/P, [5]=amount,
                // [6]=terminal, [7+]=description
                if (parts.size < 8) return null

                // X/P marker is at index 4
                if (parts[4] != "Χ" && parts[4] != "Π") return null
                val type = when (parts[4]) {
                    "Χ" -> ParsedTransactionType.PURCHASE
                    "Π" -> ParsedTransactionType.DEPOSIT
                    else -> ParsedTransactionType.PURCHASE
                }

                // Amount at index 5 (Greek decimal comma handled by parseEuropeanNumber)
                val amountStr = parts[5]
                val amount = parseEuropeanNumber(amountStr)
                if (amount == null || amount == 0.0) return null

                // Parse date+time with Greek AM/PM
                val dateStr = parts[0]   // d/M/yyyy
                val timeStr = parts[1]   // h:mm:ss
                val amPm = parts[2]      // πμ or μμ
                val timestamp = parseGreekBankDateTimeWithAmPm(dateStr, timeStr, amPm)
                if (timestamp == null) {
                    if (BuildConfig.DEBUG) Timber.tag("BankStatementParser").w("Failed to parse NBG date: $dateStr $timeStr $amPm")
                    return null
                }

                // Merchant: everything from parts[7] onwards
                val rawMerchant = parts.subList(7, parts.size).joinToString(" ").trim()

                // Strip known description prefixes
                val merchant = rawMerchant
                    .replace(Regex("""(?i)^ΑΓΟΡΑ\s*\(ΕΞΟΥΣΙΟΔΟΤΗΣΗ\)\s*[-–]\s*"""), "")
                    .replace(Regex("""(?i)^3D\s+SECURE\s+E-COMMERCE\s+ΑΓΟΡΑ\s*[-–]\s*"""), "")
                    .replace(Regex("""(?i)^ΑΓΟΡΑ\s*[-–]\s*"""), "")
                    .trim()

                if (merchant.isBlank()) {
                    if (BuildConfig.DEBUG) Timber.tag("BankStatementParser").w("Empty merchant after stripping prefix in: $cleanRow")
                    return null
                }

                val cleanedMerchant = merchantCleaner.clean(merchant)

                if (BuildConfig.DEBUG) Timber.tag("BankStatementParser").d("Parsed NBG (new): $cleanedMerchant €${kotlin.math.abs(amount)} ($type)")

                return ParsedTransaction(
                    amount = kotlin.math.abs(amount),
                    currency = homeCurrency,
                    merchant = cleanedMerchant,
                    type = type,
                    confidence = 0.90f,
                    date = timestamp
                )
            } else {
                // ── Old format ───────────────────────────────────────────────
                // parts: DATE TIME VALUE_DATE STORE_CODE TX_CODE MERCHANT X/P AMOUNT
                // Find the Χ or Π indicator
                val typeIndex = parts.indexOfFirst { it == "Χ" || it == "Π" }
                if (typeIndex < 0) return null

                val type = when (parts[typeIndex]) {
                    "Χ" -> ParsedTransactionType.PURCHASE
                    "Π" -> ParsedTransactionType.DEPOSIT
                    else -> ParsedTransactionType.PURCHASE
                }

                // Amount is right after X/P
                if (typeIndex + 1 >= parts.size) return null
                val amountStr = parts[typeIndex + 1]
                val amount = parseEuropeanNumber(amountStr)
                if (amount == null || amount == 0.0) return null

                // Timestamp from first two parts
                val dateStr = parts[0]
                val timeStr = parts[1]
                val timestamp = parseGreekBankDateTime(dateStr, timeStr)

                // Merchant: between codes (after date/time/value-date) and X/P marker
                val merchantStartIndex = parts.drop(2).indexOfFirst { part ->
                    !part.matches(Regex("\\d+")) && !part.matches(Regex("\\d{2}/\\d{2}/\\d{4}"))
                } + 2

                if (merchantStartIndex < 2 || merchantStartIndex >= typeIndex) {
                    if (BuildConfig.DEBUG) Timber.tag("BankStatementParser").w("Could not find merchant in: $cleanRow")
                    return null
                }

                val merchantParts = parts.subList(merchantStartIndex, typeIndex)
                val merchant = merchantParts.joinToString(" ").trim()

                if (merchant.isBlank()) {
                    if (BuildConfig.DEBUG) Timber.tag("BankStatementParser").w("Empty merchant in: $cleanRow")
                    return null
                }

                val cleanedMerchant = merchantCleaner.clean(merchant)

                if (BuildConfig.DEBUG) Timber.tag("BankStatementParser").d("Parsed NBG (old): $cleanedMerchant €${kotlin.math.abs(amount)} ($type)")

                return ParsedTransaction(
                    amount = kotlin.math.abs(amount),
                    currency = homeCurrency,
                    merchant = cleanedMerchant,
                    type = type,
                    confidence = 0.90f,
                    date = timestamp
                )
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Timber.tag("BankStatementParser").w("Failed to parse NBG transaction: ${e.message} | Row: $cleanRow")
            return null
        }
    }
    
    /**
     * Parse Greek bank date and time: DD/MM/YYYY HH:MM:SS
     * Also handles single-digit day/month: d/M/yyyy H:mm:ss
     */
    private fun parseGreekBankDateTime(dateStr: String, timeStr: String): Long? {
        return try {
            // Try strict format first (dd/MM/yyyy HH:mm:ss)
            val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.US)
            LocalDateTime.parse("$dateStr $timeStr", formatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e1: Exception) {
            try {
                // Fallback: flexible single-digit day/month (d/M/yyyy H:mm:ss)
                val flexFormatter = DateTimeFormatter.ofPattern("d/M/yyyy H:mm:ss", Locale.US)
                LocalDateTime.parse("$dateStr $timeStr", flexFormatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (e2: Exception) {
                // Final fallback: try just the date
                parseGreekBankDate(dateStr)
            }
        }
    }

    /**
     * Parse Greek bank date+time with Greek AM/PM (πμ/μμ).
     * Handles format: d/M/yyyy h:mm:ss [πμ|μμ]
     * Example: 3/5/2026 4:41:36 μμ
     */
    private fun parseGreekBankDateTimeWithAmPm(dateStr: String, timeStr: String, amPm: String): Long? {
        return try {
            // Convert Greek AM/PM to English
            val normalizedAmPm = when (amPm.lowercase()) {
                "πμ" -> "AM"
                "μμ" -> "PM"
                else -> return null  // unexpected marker
            }
            val dateTimeStr = "$dateStr $timeStr $normalizedAmPm"
            // d/M/yyyy = flexible day/month (no leading zeros)
            // h:mm:ss a = 12-hour clock with AM/PM
            val formatter = DateTimeFormatter.ofPattern("d/M/yyyy h:mm:ss a", Locale.US)
            LocalDateTime.parse(dateTimeStr, formatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Timber.tag("BankStatementParser").w("Failed to parse NBG date with AM/PM: $dateStr $timeStr $amPm")
            null
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
            val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)
            LocalDate.parse(dateStr, formatter)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTransactionFromRow(rowText: String, columnInfo: DateColumnInfo = DateColumnInfo(false, false), homeCurrency: String = "EUR"): ParsedTransaction? {
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
        
        // 2. Look for amount patterns
        val amountMatcher = com.yourname.expensetracker.domain.util.CommonPatterns.AMOUNT_REGEX.matcher(cleanRow)
        data class AmountCandidate(
            val rawToken: String,
            val parsed: Double,
            val fullMatch: String,
            val leadingCurrency: String?,
            val trailingCurrency: String?,
            val startIndex: Int,
            val score: Int
        )
        val candidates = mutableListOf<AmountCandidate>()
        while (amountMatcher.find()) {
            val rawToken = amountMatcher.group(2)?.replace(" ", "") ?: continue
            val parsed = AmountUtils.parseAmount(rawToken) ?: continue
            if (parsed == 0.0 || !parsed.isFinite()) continue
            val leadingCurrency = amountMatcher.group(1)
            val trailingCurrency = amountMatcher.group(3)
            var score = 0
            if (!leadingCurrency.isNullOrBlank() || !trailingCurrency.isNullOrBlank()) score += 3
            if (rawToken.contains(",") || rawToken.contains(".")) score += 2
            if (rawToken.contains("-") || rawToken.contains("+")) score += 1
            if (amountMatcher.start() > cleanRow.length / 2) score += 1
            candidates.add(
                AmountCandidate(
                    rawToken = rawToken,
                    parsed = parsed,
                    fullMatch = amountMatcher.group(0) ?: continue,
                    leadingCurrency = leadingCurrency,
                    trailingCurrency = trailingCurrency,
                    startIndex = amountMatcher.start(),
                    score = score
                )
            )
        }
        if (candidates.isEmpty()) return null

        // Select the best amount candidate.
        // When multiple candidates share the same heuristic score, prefer the
        // *first* (leftmost) qualifying amount rather than the largest absolute
        // value.  In typical bank statements the rightmost amount column is the
        // running balance; the transaction amount column appears earlier.
        val bestCandidate = if (candidates.size > 1) {
            // Separate the rightmost candidate (likely running balance)
            val rightmost = candidates.maxByOrNull { it.startIndex }!!
            val nonRightmost = candidates.filter { it !== rightmost }
            // Among the remaining, pick by score then leftmost position
            val bestNonRightmost = nonRightmost.maxWithOrNull(
                compareBy<AmountCandidate> { it.score }
                    .thenByDescending { it.startIndex } // prefer later column among non-balance columns
            )
            // Use the non-rightmost winner unless the rightmost has a strictly
            // higher heuristic score (e.g. it has a currency symbol and the
            // others do not).
            if (bestNonRightmost != null && bestNonRightmost.score >= rightmost.score) {
                bestNonRightmost
            } else {
                // Fallback: take highest score, breaking ties by earliest position
                candidates.maxWithOrNull(
                    compareBy<AmountCandidate> { it.score }
                        .thenByDescending { -it.startIndex }
                ) ?: return null
            }
        } else {
            candidates.first()
        }
        
        // Robust European & US decimal parsing
        val rawAmount = bestCandidate.rawToken
        
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
        
        if (absAmount <= 0.0 || !absAmount.isFinite()) return null
        
        // Use more specific currency check
        var currency = homeCurrency
        val currencyGroup = bestCandidate.leadingCurrency ?: bestCandidate.trailingCurrency
        if (currencyGroup != null && currencyGroup.matches(Regex("""^(?:[€$£]|EUR|USD|GBP)$""", RegexOption.IGNORE_CASE))) {
            currency = currencyNormalizer.normalize(currencyGroup)
        }

        // 3. Detect Transaction Type
        val upperRow = cleanRow.uppercase()
        val isPurchase = upperRow.contains("ΑΓΟΡΑ") || upperRow.contains("PURCHASE") || 
                         upperRow.contains("ΧΡΕΩΣΗ") || upperRow.contains("DEBIT") ||
                         upperRow.contains("PAYMENT") || upperRow.contains("CARD")
        
        val isDeposit = upperRow.contains("ΚΑΤΑΘΕΣΗ") || upperRow.contains("DEPOSIT") ||
                        upperRow.contains("ΠΙΣΤΩΣΗ") || upperRow.contains("ΠΙΣΤΩΣH") || upperRow.contains("CREDIT") ||
                        upperRow.contains("REFUND") || upperRow.contains("MISTHODOSIA") ||
                        upperRow.contains("SALARY") || upperRow.contains("WAGES") || upperRow.contains("ΜΙΣΘΟΔΟΣΙΑ")

        val type = when {
            isDeposit -> ParsedTransactionType.DEPOSIT
            isPurchase -> ParsedTransactionType.PURCHASE
            amountStr.contains("-") -> ParsedTransactionType.PURCHASE
            else -> ParsedTransactionType.PURCHASE 
        }

        // 4. Extract dates — use header-derived transaction-date order when
        //    two dates are present.
        val allDates = extractAllDates(cleanRow)

        val dateValue = when (columnInfo.transactionDateOrder) {
            // Header tells us transaction date is the first date column
            Companion.TransactionDateOrder.FIRST ->
                allDates.firstDate ?: allDates.valueDate
            // Header tells us transaction date is the second date column
            Companion.TransactionDateOrder.SECOND ->
                allDates.valueDate ?: allDates.firstDate
            // Unknown — keep legacy behavior (first date preferred)
            Companion.TransactionDateOrder.UNKNOWN ->
                allDates.transactionDate ?: allDates.valueDate ?: allDates.firstDate
        }

        // 5. Extract merchant - remove all dates from the row
        val monthsRegex = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"
        var merchant = cleanRow.replace(bestCandidate.fullMatch, "")
            .replace(Regex("""\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?"""), "") // DD/MM/YYYY
            .replace(Regex("""(?i)\b$monthsRegex\s+\d{1,2}[\s,]*\d{4}\b"""), "") // MMM d, yyyy
            .replace(Regex("""\d{2}:\d{2}(:\d{2})?"""), "")
            .replace(Regex("""(?i)^(AGORA|ΑΓΟΡΑ|PURCHASE|PAYMENT)\s*[:\-]?\s*"""), "")
            .replace(Regex("""(?i)\s*(STO|ΣΤΟ|AT)\s*$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
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

        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)

        val now = timeProvider.now()
        val currentYear = TimePeriodUtils.getYear(now)
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
                        val parsedDate = LocalDate.parse("${d.padStart(2, '0')}/${m.padStart(2, '0')}/$year", dateFormatter)
                        foundDates.add(parsedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
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

        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)

        val now = timeProvider.now()
        val currentYear = TimePeriodUtils.getYear(now)
        val minYear = currentYear - 20 // Allow up to 20 years of historical data

        for (pattern in datePatterns) {
            pattern.find(text)?.let { match ->
                val (d, m, y) = match.destructured
                val year = if (y.length == 2) "20$y" else y
                val yearInt = year.toIntOrNull() ?: 0
                
                if (yearInt in minYear..currentYear) {
                    try {
                        val parsed = LocalDate.parse("${d.padStart(2, '0')}/${m.padStart(2, '0')}/$year", dateFormatter)
                        return parsed.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Timber.d("Failed to parse date: $d/$m/$year")
                    }
                }
            }
        }
        return null
    }
}
