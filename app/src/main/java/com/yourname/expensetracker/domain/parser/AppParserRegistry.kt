package com.yourname.expensetracker.domain.parser

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
class AppParserRegistry(
    private val appParsers: List<AppNotificationParser>,
    private val fallbackParser: GenericTransactionParser
) {
    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // 1. Try app-specific parser first
        val specificParser = appParsers.find { packageName in it.supportedPackages }
        if (specificParser != null) {
            return specificParser.parse(title, text, bigText, subText, packageName)
        }

        // 2. Fallback to generic parser with HIGH threshold
        return fallbackParser.parse(title, text, bigText, subText, packageName)
    }
}
