package com.yourname.expensetracker.data.email

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.email.provider.AmazonReceiptParser
import com.yourname.expensetracker.data.email.provider.AppleReceiptParser
import com.yourname.expensetracker.data.email.provider.ParsedEmailReceipt
import com.yourname.expensetracker.data.email.provider.UberReceiptParser
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.ReceiptSource
import com.yourname.expensetracker.domain.usecase.receipt.ProcessReceiptUseCase
import com.yourname.expensetracker.domain.usecase.receipt.ProcessedReceipt
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result types for email receipt processing.
 */
sealed class EmailReceiptResult {
    data class Success(val receiptId: Long, val expenseIds: List<Long>) : EmailReceiptResult()
    data class Duplicate(val existingReceiptId: Long) : EmailReceiptResult()
    data class ParseError(val reason: String) : EmailReceiptResult()
}

/**
 * Service for ingesting email receipts from providers (Amazon, Uber, Apple, etc.)
 * and feeding them into the existing receipt/expense pipeline.
 *
 * Flow:
 * 1. Parse email body for receipt data
 * 2. Extract merchant, amount, date, items
 * 3. Dedupe against existing receipts (merchant+amount+date fingerprint)
 * 4. Create receipt entity with source="email"
 * 5. Trigger existing receipt processing pipeline
 */
@Singleton
class EmailReceiptIngestionService(
    private val receiptParser: ReceiptParser,
    private val processReceiptUseCase: ProcessReceiptUseCase,
    private val expenseDao: ExpenseDao,
    private val emailReceiptDao: EmailReceiptDao,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val merchantNormalizer: MerchantNormalizer,
    private val categorizationEngine: CategorizationEngine,
    private val timeProvider: TimeProvider,
    private val transactionRunner: suspend (suspend () -> EmailReceiptResult) -> EmailReceiptResult
) {
    @Inject
    constructor(
        receiptParser: ReceiptParser,
        processReceiptUseCase: ProcessReceiptUseCase,
        expenseDao: ExpenseDao,
        emailReceiptDao: EmailReceiptDao,
        scannedReceiptDao: ScannedReceiptDao,
        merchantNormalizer: MerchantNormalizer,
        categorizationEngine: CategorizationEngine,
        timeProvider: TimeProvider,
        database: AppDatabase
    ) : this(
        receiptParser = receiptParser,
        processReceiptUseCase = processReceiptUseCase,
        expenseDao = expenseDao,
        emailReceiptDao = emailReceiptDao,
        scannedReceiptDao = scannedReceiptDao,
        merchantNormalizer = merchantNormalizer,
        categorizationEngine = categorizationEngine,
        timeProvider = timeProvider,
        transactionRunner = { block -> database.withTransaction { block() } }
    )

    constructor(
        receiptParser: ReceiptParser,
        processReceiptUseCase: ProcessReceiptUseCase,
        expenseDao: ExpenseDao,
        emailReceiptDao: EmailReceiptDao,
        scannedReceiptDao: ScannedReceiptDao,
        merchantNormalizer: MerchantNormalizer,
        categorizationEngine: CategorizationEngine,
        timeProvider: TimeProvider
    ) : this(
        receiptParser = receiptParser,
        processReceiptUseCase = processReceiptUseCase,
        expenseDao = expenseDao,
        emailReceiptDao = emailReceiptDao,
        scannedReceiptDao = scannedReceiptDao,
        merchantNormalizer = merchantNormalizer,
        categorizationEngine = categorizationEngine,
        timeProvider = timeProvider,
        transactionRunner = { block -> block() }
    )

    private val ingestionMutex = Mutex()

    // Provider parsers
    private val amazonParser = AmazonReceiptParser()
    private val uberParser = UberReceiptParser()
    private val appleParser = AppleReceiptParser()

    /**
     * Process an email receipt and create expense entries.
     *
     * @param emailBody The raw email body text (HTML or plain text)
     * @param sender The email sender address
     * @param subject The email subject line
     * @param receivedAt Timestamp when the email was received
     * @param messageId Unique message ID for deduplication
     * @return EmailReceiptResult indicating success, duplicate, or error
     */
    suspend fun processEmailReceipt(
        emailBody: String,
        sender: String,
        subject: String,
        receivedAt: Long,
        messageId: String
    ): EmailReceiptResult = ingestionMutex.withLock {
        try {
            // Step 1: Detect provider
            val provider = detectProvider(sender, subject, emailBody)
            Timber.d("Email receipt detected provider: $provider from sender: $sender")

            // Step 2: Parse email body based on provider
            val parsedReceipt = parseEmailReceipt(emailBody, receivedAt, provider)
                ?: return EmailReceiptResult.ParseError("Could not parse receipt from email")

            // Step 3: Validate parsed data
            if (!validateParsedReceipt(parsedReceipt)) {
                return EmailReceiptResult.ParseError("Invalid receipt data: amount=${parsedReceipt.amount}, merchant=${parsedReceipt.merchant}")
            }

            val normalizedMerchant = merchantNormalizer.normalize(
                parsedReceipt.merchant
            ).canonical.normalizedName

            val fingerprint = createFingerprint(normalizedMerchant, parsedReceipt.amount, parsedReceipt.date)

            transactionRunner {
                // Step 4a: If a nonblank messageId is provided, check it first before any
                // side effects. A nonblank messageId is globally unique (UNIQUE index on
                // emailMessageId); finding it means we have already ingested this exact
                // email, so we can return early without touching scanned_receipts or expenses.
                // Blank messageIds are skipped here and fall through to the fingerprint path
                // so that the existing behaviour for providers that omit message IDs is
                // preserved unchanged.
                if (messageId.isNotBlank()) {
                    val existingByMessageId = emailReceiptDao.getByMessageId(messageId)
                    if (existingByMessageId != null) {
                        Timber.d("Duplicate email receipt by messageId=$messageId, existing receiptId=${existingByMessageId.receiptId}")
                        return@transactionRunner EmailReceiptResult.Duplicate(existingByMessageId.receiptId)
                    }
                }

                // Step 4b: Create fingerprint and check for duplicates
                val existing = emailReceiptDao.getByFingerprint(fingerprint)
                if (existing != null) {
                    Timber.d("Duplicate email receipt found: ${existing.id}")
                    return@transactionRunner EmailReceiptResult.Duplicate(existing.receiptId)
                }

                // Step 5: Also check for existing scanned receipt with same fingerprint
                val existingScanned = findExistingScannedReceipt(fingerprint)
                if (existingScanned != null) {
                    Timber.d("Matching scanned receipt found: ${existingScanned.id}")
                    // Still create email receipt source for tracking, but link to existing
                    val emailSource = EmailReceiptSource(
                        receiptId = existingScanned.id,
                        emailSender = sender,
                        emailSubject = subject,
                        emailMessageId = messageId.takeIf { it.isNotBlank() },
                        parsedAt = timeProvider.now(),
                        provider = provider,
                        confidence = parsedReceipt.confidence,
                        fingerprint = fingerprint
                    )
                    emailReceiptDao.insertOrIgnore(emailSource)
                    return@transactionRunner EmailReceiptResult.Duplicate(existingScanned.id)
                }

                // Step 6: Create scanned receipt entity (minimal, since we have structured data)
                val receiptItemsJson = if (parsedReceipt.items.isNotEmpty()) {
                    receiptParser.lineItemsToJson(
                        parsedReceipt.items.map {
                            ReceiptParser.LineItem(
                                description = it.description,
                                quantity = it.quantity.toDouble(),
                                unitPrice = it.unitPrice,
                                totalPrice = it.totalPrice
                            )
                        }
                    )
                } else null

                val scannedReceipt = ScannedReceipt(
                    imagePath = null, // No image for email receipts
                    rawOcrText = emailBody.take(5000), // Store snippet for reference
                    parsedTotal = parsedReceipt.amount,
                    parsedMerchant = normalizedMerchant,
                    parsedDate = parsedReceipt.date,
                    parsedItems = receiptItemsJson,
                    parsedTaxAmount = null, // Email receipts usually don't show tax separately
                    currency = parsedReceipt.currency,
                    confidence = parsedReceipt.confidence.toFloat(),
                    matchStatus = MatchStatus.UNMATCHED
                )

                val receiptId = scannedReceiptDao.insert(scannedReceipt)
                if (receiptId <= 0) {
                    return@transactionRunner EmailReceiptResult.ParseError("Failed to create receipt record")
                }

                // Step 7: Create email receipt source record
                val emailSource = EmailReceiptSource(
                    receiptId = receiptId,
                    emailSender = sender,
                    emailSubject = subject,
                    emailMessageId = messageId.takeIf { it.isNotBlank() },
                    parsedAt = timeProvider.now(),
                    provider = provider,
                    confidence = parsedReceipt.confidence,
                    fingerprint = fingerprint
                )
                emailReceiptDao.insertOrIgnore(emailSource)

                // Step 8: Route email receipts through the shared receipt processing pipeline
                val processedReceipt = processReceiptUseCase(
                    ReceiptSource.ParsedContent(
                        rawText = emailBody,
                        merchant = parsedReceipt.merchant,
                        amount = parsedReceipt.amount,
                        date = parsedReceipt.date
                    )
                ).getOrElse { error ->
                    throw EmailReceiptExpenseCreationException(
                        message = "Failed to process email receipt through receipt pipeline: $receiptId",
                        cause = error
                    )
                }

                val expenseIds = createExpenseFromReceipt(
                    receiptId = receiptId,
                    processedReceipt = processedReceipt,
                    receipt = parsedReceipt
                )

                if (expenseIds.isEmpty()) {
                    throw EmailReceiptExpenseCreationException("Failed to create expense from email receipt: $receiptId")
                }

                Timber.i("Email receipt processed successfully: receiptId=$receiptId, expenseIds=$expenseIds, provider=$provider")

                EmailReceiptResult.Success(receiptId, expenseIds)
            }

        } catch (e: EmailReceiptExpenseCreationException) {
            Timber.w(e, "Failed to finalize email receipt ingestion from $sender")
            return EmailReceiptResult.ParseError("Failed to create expense from receipt")
        } catch (e: Exception) {
            Timber.e(e, "Error processing email receipt from $sender")
            return EmailReceiptResult.ParseError("Processing error: ${e.message}")
        }
    }

    /**
     * Detect the provider based on sender, subject, and email body.
     */
    private fun detectProvider(sender: String, subject: String, body: String): String {
        return when {
            amazonParser.canParse(sender, subject, body) -> "amazon"
            uberParser.canParse(sender, subject, body) -> "uber"
            appleParser.canParse(sender, subject, body) -> "apple"
            sender.contains("amazon", ignoreCase = true) -> "amazon"
            sender.contains("uber", ignoreCase = true) -> "uber"
            sender.contains("apple", ignoreCase = true) ||
            sender.contains("itunes", ignoreCase = true) -> "apple"
            body.contains("amazon.com", ignoreCase = true) ||
            body.contains("amazon.co.uk", ignoreCase = true) ||
            body.contains("amazon.de", ignoreCase = true) -> "amazon"
            body.contains("uber.com", ignoreCase = true) -> "uber"
            body.contains("apple.com", ignoreCase = true) ||
            body.contains("itunes", ignoreCase = true) -> "apple"
            else -> "unknown"
        }
    }

    /**
     * Parse the email body into structured receipt data based on provider.
     */
    private fun parseEmailReceipt(
        emailBody: String,
        receivedAt: Long,
        provider: String
    ): ParsedEmailReceipt? {
        return when (provider) {
            "amazon" -> amazonParser.parse(emailBody, receivedAt)
            "uber" -> uberParser.parse(emailBody, receivedAt)
            "apple" -> appleParser.parse(emailBody, receivedAt)
            else -> {
                // Try all parsers for unknown provider
                amazonParser.parse(emailBody, receivedAt)
                    ?: uberParser.parse(emailBody, receivedAt)
                    ?: appleParser.parse(emailBody, receivedAt)
            }
        }
    }

    /**
     * Validate that the parsed receipt has the minimum required fields.
     */
    private fun validateParsedReceipt(receipt: ParsedEmailReceipt): Boolean {
        return receipt.amount > 0 && 
               receipt.merchant.isNotBlank() &&
               receipt.date > 0
    }

    /**
     * Create a fingerprint for deduplication.
     * Format: normalized_merchant_amount_date
     */
    private fun createFingerprint(merchant: String, amount: Double, date: Long): String {
        // Round amount to 2 decimal places for consistent fingerprinting
        val roundedAmount = String.format(Locale.US, "%.2f", amount)
        // Use date bucket (5 minute window) for deduplication
        val dateBucket = date / 300_000L // 5 minute buckets
        return "${merchant.lowercase()}_${roundedAmount}_${dateBucket}"
    }

    /**
     * Find existing scanned receipt with matching fingerprint.
     */
    private suspend fun findExistingScannedReceipt(fingerprint: String): ScannedReceipt? {
        // Get recent receipts and check fingerprint manually
        val since = timeProvider.now() - (30L * 24 * 60 * 60 * 1000) // Last 30 days
        val recentReceipts = scannedReceiptDao.getRecentReceipts(since)
        
        return recentReceipts.find { receipt ->
            val merchant = receipt.parsedMerchant ?: return@find false
            val amount = receipt.parsedTotal ?: return@find false
            val date = receipt.parsedDate ?: receipt.createdAt
            val receiptFingerprint = createFingerprint(merchant, amount, date)
            receiptFingerprint == fingerprint
        }
    }

    /**
     * Create expense record from parsed receipt data.
     * Returns list of created expense IDs.
     */
    private suspend fun createExpenseFromReceipt(
        receiptId: Long,
        processedReceipt: ProcessedReceipt,
        receipt: ParsedEmailReceipt
    ): List<Long> {
        val expense = Expense(
            amount = processedReceipt.amount,
            currency = receipt.currency,
            merchant = processedReceipt.merchant,
            merchantKey = MerchantKeyGenerator.generate(processedReceipt.merchant),
            transactionType = TransactionType.PURCHASE,
            date = processedReceipt.date ?: receipt.date,
            categoryId = processedReceipt.categoryId,
            createdAt = timeProvider.now(),
            notes = receipt.items
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "Email receipt: ", separator = ", ") { it.description },
            // Use type-aware key so that PURCHASE vs DEPOSIT/TRANSFER rows never
            // collide on the persisted unique dedupeKey index (ISSUE-8 fix).
            dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                processedReceipt.amount,
                processedReceipt.merchant,
                processedReceipt.date ?: receipt.date,
                receipt.currency,
                TransactionType.PURCHASE
            )
        )

        val expenseId = expenseDao.insertAtomic(expense)
        if (expenseId <= 0) {
            Timber.w("Failed to insert expense for email receipt: $receiptId")
            throw EmailReceiptExpenseCreationException("Failed to insert expense for email receipt: $receiptId")
        }

        val existingReceipt = scannedReceiptDao.getById(receiptId)
            ?: throw EmailReceiptExpenseCreationException("Created receipt missing before linkage: $receiptId")

        scannedReceiptDao.update(
            existingReceipt.copy(
                expenseId = expenseId,
                matchStatus = MatchStatus.AUTO_MATCHED,
                matchConfidence = processedReceipt.categoryConfidence
            )
        )

        return listOf(expenseId)
    }

    private class EmailReceiptExpenseCreationException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    /**
     * Batch process multiple email receipts.
     * Useful for initial import or backfill.
     */
    suspend fun processBatch(
        emails: List<EmailReceiptData>
    ): List<EmailReceiptResult> {
        return emails.map { email ->
            processEmailReceipt(
                emailBody = email.body,
                sender = email.sender,
                subject = email.subject,
                receivedAt = email.receivedAt,
                messageId = email.messageId
            )
        }
    }
}

/**
 * Data class for batch email processing.
 */
data class EmailReceiptData(
    val body: String,
    val sender: String,
    val subject: String,
    val receivedAt: Long,
    val messageId: String
)
