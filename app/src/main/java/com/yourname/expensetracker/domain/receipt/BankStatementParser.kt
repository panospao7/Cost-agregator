package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BankStatementParser @Inject constructor() {

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
        val lastHeight = lastBlock.bottom - lastBlock.top
        val currentHeight = currentBlock.bottom - currentBlock.top
        val avgHeight = (lastHeight + currentHeight) / 2
        
        // Use center point comparison with a threshold based on font size (height)
        val lastCenter = (lastBlock.top + lastBlock.bottom) / 2
        val currentCenter = (currentBlock.top + currentBlock.bottom) / 2
        
        // 60% of average height is a safe overlap threshold for rows
        return kotlin.math.abs(lastCenter - currentCenter) < (avgHeight * 0.6)
    }

    private fun extractTransactionFromRow(rowText: String): ParsedTransaction? {
        // 1. Clean noise
        val cleanRow = rowText.replace('\u00A0', ' ').trim()
        
        // 2. Look for amount patterns
        // Matches -12.50, 1.250,50, etc. optionally followed by currency
        val amountPattern = Pattern.compile(
            """(-?\b\d+[\s.,]?\d{0,3}[\s.,]\d{2})\s*([€$£]|EUR|USD|GBP)?""",
            Pattern.CASE_INSENSITIVE
        )
        val amountMatcher = amountPattern.matcher(cleanRow)
        
        if (!amountMatcher.find()) return null
        
        val amountStr = amountMatcher.group(1)!!.replace(" ", "").replace(",", ".")
        val absAmount = kotlin.math.abs(amountStr.toDoubleOrNull() ?: return null)
        val currency = normalizeCurrency(amountMatcher.group(2) ?: "EUR")

        // 3. Extract logic for merchant
        // Usually merchant is the text that is NOT the amount and NOT a date/time
        var merchant = cleanRow.replace(amountMatcher.group(0)!!, "")
            .replace(Regex("""\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?"""), "") // Date
            .replace(Regex("""\d{2}:\d{2}(:\d{2})?"""), "") // Time
            .replace(Regex("""\s{2,}"""), " ") // Double spaces
            .trim()

        // Basic validation: must have some letters to be a merchant
        if (merchant.isBlank() || !merchant.any { it.isLetter() }) {
            merchant = "Unknown Merchant"
        }

        // Sanity checks: amount shouldn't be zero, merchant shouldn't be too long
        if (absAmount < 0.01) return null
        
        return ParsedTransaction(
            amount = absAmount,
            currency = currency,
            merchant = merchant.take(40).trim(),
            type = if (amountStr.contains("-")) TransactionType.PURCHASE else TransactionType.DEPOSIT,
            confidence = 0.70f // Base confidence for statement parsing
        )
    }

    private fun normalizeCurrency(raw: String): String {
        return when (raw.uppercase().trim()) {
            "€", "EUR" -> "EUR"
            "$", "USD" -> "USD"
            "£", "GBP" -> "GBP"
            else -> "EUR"
        }
    }
}
