package com.yourname.expensetracker.data.email

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.email.provider.AmazonReceiptParser
import com.yourname.expensetracker.data.email.provider.AppleReceiptParser
import com.yourname.expensetracker.data.email.provider.ParsedEmailReceipt
import com.yourname.expensetracker.data.email.provider.UberReceiptParser
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.privacy.SensitiveHashingService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.EmailReceiptData
import com.yourname.expensetracker.domain.receipt.lifecycle.EmailReceiptProcessResult
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    data class NeedsReview(val receiptId: Long, val reason: String, val confidence: Double? = null) : EmailReceiptResult()
    data class ParseError(val reason: String) : EmailReceiptResult()
}

/**
 * Service for ingesting email receipts from providers (Amazon, Uber, Apple, etc.).
 *
 * This service is a thin **parser/delegate** layer — it does NOT create expenses
 * or receipts directly. All mutation is delegated to [ReceiptLifecycleCoordinator].
 * No post-commit side-effect dispatch happens in this service; the coordinator
 * owns the complete dispatch lifecycle.
 *
 * Flow:
 * 1. Detect provider from sender/subject/body
 * 2. Parse email body for receipt data via provider-specific parsers
 * 3. Compute content-only dedup fingerprint (merchant+amount+date bucket)
 * 4. Delegate to [ReceiptLifecycleCoordinator.processEmailReceipt] which
 *    handles deduplication, receipt save, expense creation, linking,
 *    side-effect planning, diagnostics, and post-commit dispatch atomically
 *
 * If the coordinator returns a non-Success result (Duplicate, Error), the
 * service returns the corresponding error directly — there is NO inline
 * fallback path and NO independent dispatch ability.
 */
@Singleton
class EmailReceiptIngestionService @Inject constructor(
    private val receiptParser: ReceiptParser,
    private val receiptLifecycleCoordinator: ReceiptLifecycleCoordinator,
    private val merchantNormalizer: MerchantNormalizer,
    private val writeBarrier: DatabaseWriteBarrier,
    private val diagnosticEventWriter: com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter,
    private val hashingService: SensitiveHashingService
) {
    constructor(
        receiptParser: ReceiptParser,
        receiptLifecycleCoordinator: ReceiptLifecycleCoordinator,
        merchantNormalizer: MerchantNormalizer,
        writeBarrier: DatabaseWriteBarrier
    ) : this(
        receiptParser = receiptParser,
        receiptLifecycleCoordinator = receiptLifecycleCoordinator,
        merchantNormalizer = merchantNormalizer,
        writeBarrier = writeBarrier,
        diagnosticEventWriter = object : com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter {
            override suspend fun emit(event: com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent) {}
        },
        hashingService = com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService()
    )

    // P11-PR1 (NEW-P11-001): Semaphore(3) allows bounded concurrency instead of full serialization.
    // Verified 2026-06-01: replaces the original single-Mutex which blocked all concurrent
    // processing during batch ingestion. Three concurrent emails may now process in parallel.
    private val ingestionSemaphore = Semaphore(3)

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
    ): EmailReceiptResult = ingestionSemaphore.withPermit {
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
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
        try {
            writeBarrier.checkWritesAllowed("EmailReceiptIngestionService.processEmailReceipt")
        } catch (e: com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
            try {
                diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                    stage = "front_door",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.BLOCKED,
                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.RESTORE_BLOCKED,
                    correlationId = correlationId,
                    isTerminal = true
                ))
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            return@withPermit EmailReceiptResult.ParseError(e.message ?: "Database writes blocked during restore")
        }
        try {
            // Step 1: Detect provider
            val provider = detectProvider(sender, subject, emailBody)
            Timber.d("Email receipt detected provider: $provider")
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
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }

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
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
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
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                return EmailReceiptResult.ParseError("Invalid receipt data")
            }

            val normalizedMerchant = merchantNormalizer.normalize(
                parsedReceipt.merchant
            ).canonical.normalizedName

            // P11-P1-01: extract sender domain for finer dedup granularity
            val senderDomain = sender.substringAfterLast("@").substringBefore(">").trim().lowercase(Locale.US)
            val fingerprint = createFingerprint(
                provider = provider,
                merchant = normalizedMerchant,
                amount = parsedReceipt.amount,
                currency = parsedReceipt.currency,
                date = parsedReceipt.date,
                orderNumber = parsedReceipt.orderNumber,
                senderDomain = senderDomain
            )
            // PRIV-FB58-01: fail closed if HMAC fails — never fall back to plaintext messageId
            val messageIdHash = hashingService.hmacSha256Prefix(messageId, "emailMessageId")
                ?: return@withPermit EmailReceiptResult.ParseError("Failed to hash messageId — cannot proceed safely")

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
                } else null,
                confidence = parsedReceipt.confidence
            )

            val coordinatorResult = receiptLifecycleCoordinator.processEmailReceipt(
                emailData = coordinatorEmailData,
                fingerprint = fingerprint,
                rawEmailBody = emailBody,
                sender = sender,
                subject = subject,
                messageId = messageIdHash,  // PRIV-FB58-01: pass hash, not raw messageId
                provider = provider,
                correlationId = correlationId
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
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
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
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
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
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                    EmailReceiptResult.ParseError(coordinatorResult.message)
                }
                is EmailReceiptProcessResult.NeedsReview -> {
                    try {
                        diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                            pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                            stage = "outcome",
                            outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.NEEDS_REVIEW,
                            correlationId = correlationId,
                            entityType = "receipt",
                            entityId = coordinatorResult.receiptId,
                            isTerminal = true
                        ))
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                    EmailReceiptResult.NeedsReview(coordinatorResult.receiptId, coordinatorResult.reason, coordinatorResult.confidence)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Error processing email receipt (correlationId=$correlationId)")
            try {
                diagnosticEventWriter.emit(com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent(
                    pipeline = com.yourname.expensetracker.domain.diagnostics.AppPipeline.EMAIL,
                    stage = "ingestion",
                    outcome = com.yourname.expensetracker.domain.diagnostics.EventOutcome.FAILED_FINAL,
                    severity = com.yourname.expensetracker.domain.diagnostics.EventSeverity.ERROR,
                    reasonCode = com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode.UNKNOWN_ERROR,
                    correlationId = correlationId,
                    sourceType = "email",
                    sourceIdHash = hashingService.hmacSha256Prefix(messageId, "emailMessageId", 16),
                    exception = e,
                    isTerminal = true
                ))
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
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
     * Create a hashed fingerprint for content-based deduplication.
     *
     * Composition (all normalized, then hashed — never stored as plaintext):
     *   provider + normalized_merchant + rounded_amount + currency + 1-hour date bucket + orderNumber
     *
     * P11-CURRENT-007: The previous fingerprint used only merchant + amount + date
     * bucket, which collapsed two DISTINCT orders from the same merchant for the
     * same amount on the same day into a single false-positive duplicate, and made
     * different currencies with the same numeric amount collide. Incorporating
     * [provider], [currency], and [orderNumber] makes the fingerprint strictly more
     * specific: when [orderNumber] is present it is the strongest distinguishing
     * token (different order numbers → different fingerprints); when absent the
     * fingerprint degrades gracefully to provider + merchant + amount + currency +
     * date bucket, which is still more specific than the old scheme, so it cannot
     * introduce NEW false-positive collapses — it can only split previously-collapsed
     * distinct receipts apart, which is the intended fix.
     */
    private fun createFingerprint(
        provider: String,
        merchant: String,
        amount: Double,
        currency: String,
        date: Long,
        orderNumber: String?,
        senderDomain: String
    ): String {
        val roundedAmount = String.format(Locale.US, "%.2f", amount)
        // P11-P1-01: 1-hour date bucket (was 5-minute) for consistent dedup granularity
        val dateBucket = date / 3_600_000L
        val normalizedCurrency = currency.trim().uppercase(Locale.US)
        val normalizedProvider = provider.trim().lowercase(Locale.US)
        val normalizedOrder = orderNumber?.trim()?.lowercase(Locale.US).orEmpty()
        val normalizedDomain = senderDomain.trim().lowercase(Locale.US)
        // P11-P1-01: include senderDomain so receipts from different domains
        // (e.g. amazon.com vs amazon.co.uk) produce distinct fingerprints
        val raw = "${normalizedProvider}_${merchant.lowercase(Locale.US)}_${roundedAmount}_${normalizedCurrency}_${normalizedDomain}_${dateBucket}_${normalizedOrder}"
        return hashingService.sha256Prefix(raw, 32) ?: ""
    }

    /**
     * Batch process multiple email receipts.
     * Useful for initial import or backfill.
     *
     * Accepts the canonical domain [EmailReceiptData] (the same model the
     * coordinator consumes). Only the transport fields (body/from/subject/
     * receivedAt/messageId) are read here; financial fields are re-parsed.
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
