package com.yourname.expensetracker.data.email

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
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
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.ReceiptSource
import com.yourname.expensetracker.domain.receipt.ReceiptSourceType
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.SideEffectMode
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.usecase.receipt.ProcessReceiptUseCase
import com.yourname.expensetracker.domain.usecase.receipt.ProcessedReceipt
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawContentSanitizer
import com.yourname.expensetracker.domain.privacy.RawStorageMode
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
    private val receiptLifecycleCoordinator: ReceiptLifecycleCoordinator,
    private val receiptLinkService: ReceiptLinkService,
    private val merchantNormalizer: MerchantNormalizer,
    private val categorizationEngine: CategorizationEngine,
    private val timeProvider: TimeProvider,
    private val coordinator: TransactionLifecycleCoordinator,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val writeBarrier: DatabaseWriteBarrier,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val transactionRunner: suspend (suspend () -> EmailReceiptResult) -> EmailReceiptResult
) {
    @Inject
    constructor(
        receiptParser: ReceiptParser,
        processReceiptUseCase: ProcessReceiptUseCase,
        expenseDao: ExpenseDao,
        emailReceiptDao: EmailReceiptDao,
        receiptLifecycleCoordinator: ReceiptLifecycleCoordinator,
        receiptLinkService: ReceiptLinkService,
        merchantNormalizer: MerchantNormalizer,
        categorizationEngine: CategorizationEngine,
        timeProvider: TimeProvider,
        coordinator: TransactionLifecycleCoordinator,
        database: AppDatabase,
        restoreMaintenanceMode: RestoreMaintenanceMode,
        writeBarrier: DatabaseWriteBarrier,
        privacySettingsRepository: PrivacySettingsRepository
    ) : this(
        receiptParser = receiptParser,
        processReceiptUseCase = processReceiptUseCase,
        expenseDao = expenseDao,
        emailReceiptDao = emailReceiptDao,
        receiptLifecycleCoordinator = receiptLifecycleCoordinator,
        receiptLinkService = receiptLinkService,
        merchantNormalizer = merchantNormalizer,
        categorizationEngine = categorizationEngine,
        timeProvider = timeProvider,
        coordinator = coordinator,
        restoreMaintenanceMode = restoreMaintenanceMode,
        writeBarrier = writeBarrier,
        privacySettingsRepository = privacySettingsRepository,
        transactionRunner = { block -> database.withTransaction { block() } }
    )

    constructor(
        receiptParser: ReceiptParser,
        processReceiptUseCase: ProcessReceiptUseCase,
        expenseDao: ExpenseDao,
        emailReceiptDao: EmailReceiptDao,
        receiptLifecycleCoordinator: ReceiptLifecycleCoordinator,
        receiptLinkService: ReceiptLinkService,
        merchantNormalizer: MerchantNormalizer,
        categorizationEngine: CategorizationEngine,
        timeProvider: TimeProvider,
        coordinator: TransactionLifecycleCoordinator,
        restoreMaintenanceMode: RestoreMaintenanceMode,
        writeBarrier: DatabaseWriteBarrier,
        privacySettingsRepository: PrivacySettingsRepository
    ) : this(
        receiptParser = receiptParser,
        processReceiptUseCase = processReceiptUseCase,
        expenseDao = expenseDao,
        emailReceiptDao = emailReceiptDao,
        receiptLifecycleCoordinator = receiptLifecycleCoordinator,
        receiptLinkService = receiptLinkService,
        merchantNormalizer = merchantNormalizer,
        categorizationEngine = categorizationEngine,
        timeProvider = timeProvider,
        coordinator = coordinator,
        restoreMaintenanceMode = restoreMaintenanceMode,
        writeBarrier = writeBarrier,
        privacySettingsRepository = privacySettingsRepository,
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
            writeBarrier.checkWritesAllowed("EmailReceiptIngestionService.processEmailReceipt")
        } catch (e: IllegalStateException) {
            return@withLock EmailReceiptResult.ParseError(e.message ?: "Database writes blocked during restore")
        }
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

            val fingerprint = createFingerprint(normalizedMerchant, parsedReceipt.amount, parsedReceipt.date, messageId)

            val result = transactionRunner {
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
                val mode = privacySettingsRepository.getSettings().rawOcrStorageMode
                val sanitizedSender = RawContentSanitizer.sanitizeEmailSender(sender, mode) ?: ""
                val sanitizedSubject = RawContentSanitizer.sanitizeEmailSubject(subject, mode) ?: ""
                val existingScanned = findExistingScannedReceipt(fingerprint)
                if (existingScanned != null) {
                    Timber.d("Matching scanned receipt found: ${existingScanned.id}")
                    // Still create email receipt source for tracking, but link to existing
                    val emailSource = EmailReceiptSource(
                        receiptId = existingScanned.id,
                        emailSender = sanitizedSender,
                        emailSubject = sanitizedSubject,
                        emailMessageId = messageId.takeIf { it.isNotBlank() },
                        parsedAt = timeProvider.now(),
                        provider = provider,
                        confidence = parsedReceipt.confidence,
                        fingerprint = fingerprint
                    )
                    val sourceId = emailReceiptDao.insertOrIgnore(emailSource)
                    if (sourceId == -1L) Timber.w("EmailReceiptSource insert conflict for receipt %d", existingScanned.id)
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
                    rawOcrText = RawContentSanitizer.sanitizeRawOcr(emailBody.take(5000), mode),
                    parsedTotal = parsedReceipt.amount,
                    parsedMerchant = normalizedMerchant,
                    parsedDate = parsedReceipt.date,
                    parsedItems = receiptItemsJson,
                    parsedTaxAmount = null, // Email receipts usually don't show tax separately
                    currency = parsedReceipt.currency,
                    confidence = parsedReceipt.confidence.toFloat(),
                    matchStatus = MatchStatus.UNMATCHED,
                    sourceType = ReceiptSourceType.EMAIL.name,
                    documentType = ReceiptDocumentType.EMAIL_RECEIPT.name
                )

                val receiptId = receiptLifecycleCoordinator.saveEmailReceipt(scannedReceipt)
                if (receiptId <= 0) {
                    return@transactionRunner EmailReceiptResult.ParseError("Failed to create receipt record")
                }

                // Step 7: Create email receipt source record
                val emailSource = EmailReceiptSource(
                    receiptId = receiptId,
                    emailSender = sanitizedSender,
                    emailSubject = sanitizedSubject,
                    emailMessageId = messageId.takeIf { it.isNotBlank() },
                    parsedAt = timeProvider.now(),
                    provider = provider,
                    confidence = parsedReceipt.confidence,
                    fingerprint = fingerprint
                )
                val sourceId = emailReceiptDao.insertOrIgnore(emailSource)
                if (sourceId == -1L) Timber.w("EmailReceiptSource insert conflict for receipt %d", receiptId)

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
                    receipt = parsedReceipt,
                    messageId = messageId
                )

                if (expenseIds.isEmpty()) {
                    throw EmailReceiptExpenseCreationException("Failed to create expense from email receipt: $receiptId")
                }

                Timber.i("Email receipt processed successfully: receiptId=$receiptId, expenseIds=$expenseIds, provider=$provider")

                EmailReceiptResult.Success(receiptId, expenseIds)
            }

            if (result is EmailReceiptResult.Success) {
                for (expenseId in result.expenseIds) {
                    try {
                        coordinator.dispatchPostCreationSideEffects(expenseId, ExpenseSource.EMAIL_RECEIPT)
                    } catch (e: Exception) {
                        Timber.w(e, "Non-critical: failed to dispatch post-creation side effects for expense $expenseId")
                    }
                }
            }

            result
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
    private fun createFingerprint(merchant: String, amount: Double, date: Long, messageId: String = ""): String {
        val roundedAmount = String.format(Locale.US, "%.2f", amount)
        val dateBucket = date / 300_000L
        // Content-only dedup fingerprint: forwarded/re-sent receipts with different
        // message IDs still match by content (merchant + amount + date bucket)
        return "${merchant.lowercase()}_${roundedAmount}_${dateBucket}"
    }

    private fun createSourceFingerprint(merchant: String, amount: Double, date: Long, messageId: String): String {
        val contentFp = createFingerprint(merchant, amount, date)
        return "${contentFp}_${messageId}"
    }

    /**
     * Find existing scanned receipt with matching fingerprint.
     */
    private suspend fun findExistingScannedReceipt(fingerprint: String): ScannedReceipt? {
        // Get recent receipts and check fingerprint manually
        val since = TimePeriodUtils.addDays(timeProvider.now(), -30) // Last 30 days
        val recentReceipts = receiptLifecycleCoordinator.getRecentReceipts(since)
        
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
        receipt: ParsedEmailReceipt,
        messageId: String
    ): List<Long> {
        // Route through the transaction lifecycle coordinator so that
        // a CREATED event is written and deduplication is handled consistently.
        val request = CreateExpenseRequest(
            merchant = processedReceipt.merchant,
            amount = processedReceipt.amount,
            currency = receipt.currency,
            date = processedReceipt.date ?: receipt.date,
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.EMAIL_RECEIPT,
            categoryId = processedReceipt.categoryId,
            idempotencyKey = messageId.takeUnless { it.isBlank() },
            notes = receipt.items
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "Email receipt: ", separator = ", ") { it.description }
        )

        val expenseId = when (val result = coordinator.createExpense(request, SideEffectMode.DEFER)) {
            is CreateExpenseResult.Created -> result.expenseId
            is CreateExpenseResult.DuplicateSkipped -> {
                Timber.d("Email receipt matched existing expense %d", result.existingExpenseId)
                result.existingExpenseId
            }
            else -> {
                Timber.w("Failed to create expense for email receipt: $receiptId, result=$result")
                throw EmailReceiptExpenseCreationException("Failed to create expense for email receipt: $receiptId")
            }
        }

        val linkResult = receiptLinkService.linkReceiptToExpense(
            receiptId = receiptId,
            expenseId = expenseId,
            linkType = "EMAIL_RECEIPT",
            source = ExpenseSource.EMAIL_RECEIPT.name,
            confidence = processedReceipt.categoryConfidence
        )
        if (linkResult.isFailure) {
            val msg = "Email receipt link failed for receipt $receiptId → expense $expenseId: ${linkResult.exceptionOrNull()?.message}"
            Timber.w(msg)
            throw EmailReceiptExpenseCreationException(msg, linkResult.exceptionOrNull())
        }

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
