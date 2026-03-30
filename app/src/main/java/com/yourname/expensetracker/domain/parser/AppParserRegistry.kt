package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result from an app-specific parser. Higher confidence = more certain it's a real transaction.
 * 
 * @param amount Transaction amount (always positive)
 * @param currency ISO 4217 currency code (e.g., "EUR", "USD")
 * @param merchant Merchant or counterparty name
 * @param type Transaction type (PURCHASE, TRANSFER, DEPOSIT, etc.)
 * @param confidence Detection confidence (0.0 to 1.0)
 * @param date Transaction timestamp (null if not detected)
 * @param transferDirection For transfers/deposits: INCOMING (received) or OUTGOING (sent)
 * @param transferAccountName The account name or counterparty (e.g., "From: Checking" or "To: John")
 * @param isIncoming Helper flag derived from transferDirection
 */
data class ParsedTransaction(
    val amount: Double,
    val currency: String,
    val merchant: String,
    val type: TransactionType,
    val confidence: Float, // 0.0 to 1.0
    val date: Long? = null,
    // Transfer direction fields (auto-detected for transfers/deposits)
    val transferDirection: TransferDirection? = null,
    val transferAccountName: String? = null
) {
    /**
     * Helper property to quickly check if this is an incoming transaction
     */
    val isIncoming: Boolean?
        get() = transferDirection?.let { it == TransferDirection.INCOMING }
    
    /**
     * Helper property to quickly check if this is an outgoing transaction
     */
    val isOutgoing: Boolean?
        get() = transferDirection?.let { it == TransferDirection.OUTGOING }

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
        
        // Validate transfer direction consistency
        transferDirection?.let { direction ->
            require(type == TransactionType.TRANSFER || type == TransactionType.DEPOSIT) {
                "TransferDirection should only be set for TRANSFER or DEPOSIT types, not $type"
            }
        }
        
        // Validate transfer account name length
        transferAccountName?.let { name ->
            require(name.length <= 100) {
                "Transfer account name too long (max 100 chars): ${name.take(50)}..."
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
    private val genericParser: GenericTransactionParser,
    private val aiFallbackParser: com.yourname.expensetracker.domain.ai.service.NotificationFallbackParser
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
            val specificResult = try {
                specificParser.parse(title, text, bigText, subText, packageName)
            } catch (e: Exception) {
                Timber.w(e, "Parser failed for package: $packageName")
                null
            }
            if (specificResult != null) {
                return specificResult
            }
        }

        // 2. Fallback to generic parser when package parser fails or cannot parse this format.
        val genericResult = try {
            genericParser.parse(title, text, bigText, subText, packageName)
        } catch (e: Exception) {
            Timber.w(e, "Generic parser failed for package: $packageName")
            null
        }
        
        return genericResult
    }
    
    /**
     * Parse with AI fallback. This is a suspend function that can use AI when
     * deterministic parsers fail.
     * 
     * Use this in NotificationProcessingPipeline for better multilingual support.
     */
    suspend fun parseWithAiFallback(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // 1. Try deterministic parsers first
        val deterministicResult = parse(title, text, bigText, subText, packageName)
        if (deterministicResult != null) {
            return deterministicResult
        }
        
        // 2. Try AI fallback for multilingual/unstructured notifications
        Timber.d("AppParserRegistry: Trying AI fallback for package: $packageName")
        return try {
            aiFallbackParser.parse(title, text, bigText, packageName)
        } catch (e: Exception) {
            Timber.w(e, "AI fallback parser failed for package: $packageName")
            null
        }
    }
}
