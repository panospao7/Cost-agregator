package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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
) {
    init {
        require(amount.isFinite() && amount > 0) { 
            "Amount must be positive and finite: $amount" 
        }
        require(amount <= 1_000_000) { 
            "Amount exceeds maximum: $amount" 
        }
        require(confidence in 0f..1f) { 
            "Confidence must be between 0 and 1: $confidence" 
        }
        require(merchant.isNotBlank()) { 
            "Merchant cannot be blank" 
        }
        require(currency.matches(Regex("^[A-Z]{3}$"))) { 
            "Currency must be ISO 4217 code (e.g., EUR, USD): $currency" 
        }
        date?.let {
            require(it > 0) { "Date must be positive timestamp" }
            require(it <= System.currentTimeMillis() + 86_400_000) { 
                "Date cannot be in the future" 
            }
        }
    }
}

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
    private val packageToParserMap = mutableMapOf<String, AppNotificationParser>()

    init {
        // Order matters: Specific parsers first
        parsers.add(greekBankParser)
        parsers.add(revolutParser)
        parsers.add(smsParser)
        parsers.add(googleWalletParser)
        
        // Build O(1) lookup map
        parsers.forEach { parser ->
            parser.supportedPackages.forEach { pkg ->
                packageToParserMap[pkg] = parser
            }
        }
    }

    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // 1. O(1) lookup for app-specific parser
        val specificParser = packageToParserMap[packageName]
        if (specificParser != null) {
            return try {
                specificParser.parse(title, text, bigText, subText, packageName)
            } catch (e: Exception) {
                Timber.w(e, "Parser failed for package: $packageName")
                null
            }
        }

        // 2. Fallback to generic parser with HIGH threshold
        return try {
            genericParser.parse(title, text, bigText, subText, packageName)
        } catch (e: Exception) {
            Timber.w(e, "Generic parser failed for package: $packageName")
            null
        }
    }
}
