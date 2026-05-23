package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import com.yourname.expensetracker.domain.parser.provenance.ParseProvenance
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.service.NotificationFilter
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
    val type: ParsedTransactionType,
    val confidence: Float, // 0.0 to 1.0
    val date: Long? = null,
    // Transfer direction fields (auto-detected for transfers/deposits)
    val transferDirection: ParsedTransferDirection? = null,
    val transferAccountName: String? = null,
    @Deprecated("Should be set to timeProvider.now() by the caller. Default uses wall clock for backward compat.")
    private val validationNowEpochMs: Long = System.currentTimeMillis()
) {
    /**
     * Helper property to quickly check if this is an incoming transaction
     */
    val isIncoming: Boolean?
        get() = transferDirection?.let { it == ParsedTransferDirection.INCOMING }
    
    /**
     * Helper property to quickly check if this is an outgoing transaction
     */
    val isOutgoing: Boolean?
        get() = transferDirection?.let { it == ParsedTransferDirection.OUTGOING }

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
            require(it <= validationNowEpochMs + 86_400_000) { 
                "Date cannot be in the future" 
            }
        }
        
        // Validate transfer direction consistency
        transferDirection?.let { direction ->
            require(type == ParsedTransactionType.TRANSFER || type == ParsedTransactionType.DEPOSIT) {
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

    /** Human-readable parser identifier for provenance tracking. */
    val parserId: String get() = this::class.simpleName ?: "UnknownParser"

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
    private val aiFallbackParser: com.yourname.expensetracker.domain.ai.service.NotificationFallbackParser,
    private val timeProvider: TimeProvider
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
	 * Parse with provenance tracking. Wraps [parseWithAiFallback] and builds a
	 * [ParseProvenance] describing which parsers were attempted and their outcomes.
	 */
	suspend fun parseWithProvenance(
		title: String?,
		text: String?,
		bigText: String?,
		subText: String?,
		packageName: String
	): ParseOutcome {
		val parsed = parseWithAiFallback(title, text, bigText, subText, packageName)
		val provenance = ParseProvenance(
			source = if (parsed != null) com.yourname.expensetracker.domain.parser.provenance.ParserSource.GENERIC_DETERMINISTIC
			       else com.yourname.expensetracker.domain.parser.provenance.ParserSource.NONE,
			winningParserId = null,
			deterministicAttempted = true,
			deterministicSucceeded = parsed != null,
			aiAttempted = false,
			aiStatus = com.yourname.expensetracker.domain.parser.provenance.AiFallbackStatus.NOT_NEEDED,
			aiProvider = null,
			aiModel = null,
			confidence = parsed?.confidence,
			failureReason = if (parsed == null) com.yourname.expensetracker.domain.parser.provenance.ParseFailureReason.NO_DETERMINISTIC_MATCH else null,
			attempts = emptyList<com.yourname.expensetracker.domain.parser.provenance.ParserAttempt>()
		)
		return if (parsed != null) ParseOutcome.Parsed(parsed, provenance)
		       else ParseOutcome.NoParse(provenance)
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

		// 2. Gate AI fallback by package — only invoke on-device model for
		//    packages that are likely to carry financial notifications.
		//    - Known finance packages: always try AI
		//    - Communication packages (Gmail, SMS, Viber): try AI only if
		//      the content contains financial signals
		//    - All other packages: skip AI (they're unlikely to be financial)
		if (!shouldAttemptAiFallback(packageName, title, text, bigText)) {
			Timber.d("AppParserRegistry: Skipping AI fallback for non-financial package: $packageName")
			return null
		}

		// 3. Try AI fallback for multilingual/unstructured notifications
		Timber.d("AppParserRegistry: Trying AI fallback for package: $packageName")
		return try {
			aiFallbackParser.parse(title, text, bigText, packageName)
		} catch (e: Exception) {
			Timber.w(e, "AI fallback parser failed for package: $packageName")
			null
		}
	}

	/**
	 * Determine whether AI fallback should be attempted for this notification.
	 * Avoids wasting on-device compute on packages unlikely to carry financial data.
	 */
	private fun shouldAttemptAiFallback(
		packageName: String,
		title: String?,
		text: String?,
		bigText: String?
	): Boolean {
		// Known finance packages — always try AI
		if (packageName in NotificationFilter.FINANCE_PACKAGES) return true

		// Communication packages — try AI only if content has financial signals
		if (packageName in NotificationFilter.COMMUNICATION_PACKAGES) {
			val content = listOf(title, text, bigText)
				.joinToString(" ") { it.orEmpty() }
				.lowercase()
			return NotificationFilter.FINANCIAL_KEYWORDS.any { content.contains(it) }
		}

		// All other packages — skip AI
		return false
	}
}
