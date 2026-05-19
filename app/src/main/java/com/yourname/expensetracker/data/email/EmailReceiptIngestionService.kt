package com.yourname.expensetracker.data.email

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.domain.common.sha256Prefix
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.privacy.SensitiveHashingService
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
import com.yourname.expensetracker.domain.receipt.EmailReceiptData
import com.yourname.expensetracker.domain.receipt.lifecycle.EmailReceiptProcessResult
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
 * Service for ingesting email receipts from providers (Amazon, Uber, Apple, etc.).
 *
 * This service is a **detector and delegate** — it does NOT create expenses
 * or receipts directly. All mutation is delegated to [ReceiptLifecycleCoordinator].
 *
 * Flow:
 * 1. Detect provider from sender/subject/body
 * 2. Parse email body for receipt data via provider-specific parsers
 * 3. Compute content-only dedup fingerprint (merchant+amount+date bucket)
 * 4. Delegate to [ReceiptLifecycleCoordinator.processEmailReceipt] which
 *    handles deduplication, receipt save, expense creation, linking, and
 *    diagnostic events atomically
 * 5. Dispatch post-creation side effects for any expenses created
 *
 * If the coordinator returns a non-Success result (Duplicate, Error), the
 * service returns the corresponding error directly — there is NO inline
 * fallback path.
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
    private val diagnosticEventWriter: com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter,
    private val hashingService: SensitiveHashingService,
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
        privacySettingsRepository: PrivacySettingsRepository,
        diagnosticEventWriter: com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter,
        hashingService: SensitiveHashingService
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
        diagnosticEventWriter = diagnosticEventWriter,
        hashingService = hashingService,
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
        diagnosticEventWriter = object : com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter {
            override suspend fun emit(event: com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent) {}
        },
        hashingService = com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService(),
        transactionRunner = { block -> block() }
    )

    private val ingestionMutex = Mutex()

    // Provider parsers
    private val amazonParser = AmazonReceiptParser()
    private val uberParser = UberReceiptParser()
    private val appleParser = AppleReceiptParser()

    /**
     * Process an email receipt through the lifecycle coordinator.
     *
     * This method detects the provider, parses the email body, and delegates
     * all mutation (receipt save, expense creation, linking) to
     * [ReceiptLifecycleCoordinator.processEmailReceipt].
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
        val correlationId = com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()
        try {
            diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                stage = "front_door",
                outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.RECEIVED,
                correlationId = correlationId,
                sourceType = "email",
                metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                    .putHashed("messageId", messageId)
                    .putHashed("sender", sender)
                    .build()
            ))
        } catch (_: Exception) {}
        try {
            writeBarrier.checkWritesAllowed("EmailReceiptIngestionService.processEmailReceipt")
        } catch (e: IllegalStateException) {
            try {
                diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                    stage = "front_door",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.BLOCKED,
                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.RESTORE_BLOCKED,
                    correlationId = correlationId,
                    isTerminal = true
                ))
            } catch (_: Exception) {}
            return@withLock EmailReceiptResult.ParseError(e.message ?: "Database writes blocked during restore")
        }
        try {
            // Step 1: Detect provider
            val provider = detectProvider(sender, subject, emailBody)
            Timber.d("Email receipt detected provider: $provider from sender: $sender")
            try {
                diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                    stage = "provider_detection",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.COMPLETED,
                    correlationId = correlationId,
                    metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                        .put("provider", provider)
                        .build()
                ))
            } catch (_: Exception) {}

            // Step 2: Parse email body based on provider
            val parsedReceipt = parseEmailReceipt(emailBody, receivedAt, provider)
            if (parsedReceipt == null) {
                try {
                    diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                        stage = "parser",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                        reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.PARSER_FAILED,
                        correlationId = correlationId,
                        isTerminal = true
                    ))
                } catch (_: Exception) {}
                return EmailReceiptResult.ParseError("Could not parse receipt from email")
            }

            // Step 3: Validate parsed data
            if (!validateParsedReceipt(parsedReceipt)) {
                try {
                    diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                        pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                        stage = "validation",
                        outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                        reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.VALIDATION_FAILED,
                        correlationId = correlationId,
                        isTerminal = true
                    ))
                } catch (_: Exception) {}
                return EmailReceiptResult.ParseError("Invalid receipt data: amount=${parsedReceipt.amount}, merchant=${parsedReceipt.merchant}")
            }

            val normalizedMerchant = merchantNormalizer.normalize(
                parsedReceipt.merchant
            ).canonical.normalizedName

            val fingerprint = createFingerprint(normalizedMerchant, parsedReceipt.amount, parsedReceipt.date, messageId)
            // PR4: hash messageId for deduplication; never store plaintext in coordinator
            val messageIdHash = hashingService.hmacSha256Prefix(messageId, "emailMessageId") ?: messageId

            val coordinatorEmailData = EmailReceiptData(
                messageId = messageIdHash,   // pass HMAC hash, not plaintext
                from = sender,
                subject = subject,
                body = emailBody,
                receivedAt = receivedAt,
                amount = parsedReceipt.amount,
                merchant = normalizedMerchant,
                currency = parsedReceipt.currency,
                date = parsedReceipt.date,
                items = if (parsedReceipt.items.isNotEmpty()) {
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
            )

            val coordinatorResult = receiptLifecycleCoordinator.processEmailReceipt(
                emailData = coordinatorEmailData,
                fingerprint = fingerprint,
                rawEmailBody = emailBody,
                sender = sender,
                subject = subject,
                messageId = messageId,
                provider = provider,
                correlationId = correlationId  // PRIV-441-11: propagate correlation
            )

            when (coordinatorResult) {
                is EmailReceiptProcessResult.Success -> {
                    // Side effects are dispatched by ReceiptLifecycleCoordinator — do NOT dispatch again here
                    try {
                        diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                            stage = "outcome",
                            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.CREATED,
                            correlationId = correlationId,
                            entityType = "receipt",
                            entityId = coordinatorResult.receiptId,
                            isTerminal = true
                        ))
                    } catch (_: Exception) {}
                    EmailReceiptResult.Success(coordinatorResult.receiptId, coordinatorResult.expenseIds)
                }
                is EmailReceiptProcessResult.Duplicate -> {
                    try {
                        diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                            stage = "dedupe",
                            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.DUPLICATE,
                            reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.DUPLICATE,
                            correlationId = correlationId,
                            entityType = "receipt",
                            entityId = coordinatorResult.existingReceiptId,
                            isTerminal = true
                        ))
                    } catch (_: Exception) {}
                    EmailReceiptResult.Duplicate(coordinatorResult.existingReceiptId)
                }
                is EmailReceiptProcessResult.Error -> {
                    try {
                        diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                            stage = "coordinator",
                            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                            reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.UNKNOWN_ERROR,
                            correlationId = correlationId,
                            metadata = com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata.builder()
                                .put("retryable", false)
                                .build(),
                            isTerminal = true
                        ))
                    } catch (_: Exception) {}
                    EmailReceiptResult.ParseError(coordinatorResult.message)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Error processing email receipt from $sender")
            try {
                diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                    stage = "ingestion",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                    severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.ERROR,
                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.UNKNOWN_ERROR,
                    correlationId = correlationId,
                    sourceType = "email",
                    sourceIdHash = messageId.let { it.sha256Prefix(16) },
                    exception = e,
                    isTerminal = true
                ))
            } catch (_: Exception) {}
            return EmailReceiptResult.ParseError("Processing error")
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
     * Create a hashed fingerprint for deduplication.
     * Hash of: normalized_merchant_amount_date_bucket
     * PR4: Never stores plaintext merchant/amount/date as fingerprint.
     */
    private fun createFingerprint(merchant: String, amount: Double, date: Long, messageId: String = ""): String {
        val roundedAmount = String.format(Locale.US, "%.2f", amount)
        val dateBucket = date / 300_000L
        val raw = "${merchant.lowercase()}_${roundedAmount}_${dateBucket}"
        return hashingService.sha256Prefix(raw, 32) ?: raw.hashCode().toString(16)
    }

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
                sender = email.from,
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
