package com.yourname.expensetracker.domain.transaction

import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.provenance.SourceLinkPayload

/**
 * Request object for creating an expense through the TransactionLifecycleCoordinator.
 *
 * Contains all fields that can be specified at creation time, including source tracking,
 * deduplication policy, and idempotency controls.
 *
 * @property merchant Required. Merchant/display name for the expense.
 * @property amount Required. Transaction amount.
 * @property currency Required. ISO-4217 currency code (e.g. "EUR").
 * @property date Required. Transaction timestamp in epoch milliseconds.
 * @property transactionType Required. Type of transaction (PURCHASE, WITHDRAWAL, etc.).
 * @property source Required. Origin/source of this expense creation.
 * @property categoryId Optional. Category assignment.
 * @property notes Optional. User notes.
 * @property paymentMethod Optional. Payment method used.
 * @property isManualEntry Whether this was manually entered by the user.
 * @property transferDirection Optional. Direction for transfer-type expenses.
 * @property transferAccountName Optional. Account name for transfer-type expenses.
 * @property isNotMine Whether this expense belongs to someone else.
 * @property ownerName Optional. Name of the person who owns this expense.
 * @property isSharedExpense Whether this is a shared expense.
 * @property sharedWithName Optional. Name of person shared with.
 * @property mySharePercentage Optional. User's share percentage.
 * @property myShareAmount Optional. User's share amount.
 * @property latitude Optional. Latitude for location enrichment.
 * @property longitude Optional. Longitude for location enrichment.
 * @property locationSource Optional. Source of location data.
 * @property placeId Optional. OSM node ID for re-lookups.
 * @property resolvedAddress Optional. Human-readable resolved address.
 * @property isBusinessExpense Whether this is a business expense.
 * @property businessPurpose Optional. Purpose of business expense.
 * @property businessCategory Optional. Business category (Travel, Meals, etc.).
 * @property businessProject Optional. Project identifier for business expense.
 * @property requiresReceipt Whether a receipt is required for this expense.
 * @property splitTemplateId Optional. Reference to a SplitTemplate.
 * @property splitVisualization Optional. JSON with visual split data.
 * // -- Source link fields --
 * @property rawNotificationId Optional. Linked raw notification ID.
 * @property pendingReviewId Optional. Linked pending review ID.
 * @property scannedReceiptId Optional. Linked scanned receipt ID.
 * @property emailReceiptSourceId Optional. Linked email receipt source ID.
 * @property groupId Optional. Linked expense group ID.
 * @property bankSyncRunId Optional. Linked bank sync run ID.
 * @property bankProviderTransactionIdHash Optional. Hashed provider transaction ID for bank provenance identity.
 * @property bankAccountIdHash Optional. Hashed bank account ID for provenance traceability.
 * @property fileImportRunId Optional. Linked file import run ID (CSV/JSON import provenance).
 * @property csvImportBatchId Optional. CSV import batch identifier.
 * @property csvRowNumber Optional. Row number within CSV import.
 * @property externalFingerprint Optional. External system fingerprint for dedup.
 * @property sourceLinks Optional. Explicit source-link payloads (modern API).
 *   Legacy fields above are automatically mapped by CreateExpenseSourceLinkMapper.
 *   New callsites should prefer sourceLinks directly.
 * // -- Policy fields --
 * @property deduplicationMode Deduplication strategy (default STANDARD).
 * @property skipDeduplication Whether to skip deduplication entirely (default false).
 * @property idempotencyKey Optional. Idempotency key for safe retries.
 */
data class CreateExpenseRequest(
    // ── Required fields ──────────────────────────────────────────────────────────
    val merchant: String,
    val amount: Double,
    val currency: String,
    val date: Long,
    val transactionType: TransactionType,
    val source: ExpenseSource,

    // ── Optional fields ─────────────────────────────────────────────────────────
    val categoryId: Long? = null,
    val notes: String? = null,
    val paymentMethod: PaymentMethod? = null,
    val isManualEntry: Boolean = false,
    val transferDirection: TransferDirection? = null,
    val transferAccountName: String? = null,
    val isNotMine: Boolean = false,
    val ownerName: String? = null,
    val isSharedExpense: Boolean = false,
    val sharedWithName: String? = null,
    val mySharePercentage: Int? = null,
    val myShareAmount: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String? = null,
    val placeId: String? = null,
    val resolvedAddress: String? = null,
    val isBusinessExpense: Boolean = false,
    val businessPurpose: String? = null,
    val businessCategory: String? = null,
    val businessProject: String? = null,
    val requiresReceipt: Boolean = false,
    val splitTemplateId: Long? = null,
    val splitVisualization: String? = null,

    // ── Source link fields ──────────────────────────────────────────────────────
    // These fields are mapped to EntitySourceLink rows by CreateExpenseSourceLinkMapper
    // inside the TransactionLifecycleCoordinator's createExpense transaction.
    val rawNotificationId: Long? = null,
    val pendingReviewId: Long? = null,
    val scannedReceiptId: Long? = null,
    val emailReceiptSourceId: Long? = null,
    val groupId: Long? = null,
    val bankSyncRunId: Long? = null,
    /** P0: Hashed provider transaction ID for deterministic bank provenance identity. */
    val bankProviderTransactionIdHash: String? = null,
    /** P0: Hashed bank account ID for provenance traceability. */
    val bankAccountIdHash: String? = null,
    val fileImportRunId: Long? = null,
    val csvImportBatchId: String? = null,
    val csvRowNumber: Int? = null,
    val externalFingerprint: String? = null,
    /** Explicit source-link payloads. New callsites should prefer this over legacy fields. */
    val sourceLinks: List<SourceLinkPayload> = emptyList(),

    // ── Policy fields ───────────────────────────────────────────────────────────
    val deduplicationMode: DeduplicationMode = DeduplicationMode.STANDARD,
    val skipDeduplication: Boolean = false,
    val idempotencyKey: String? = null,
    /** Optional correlation ID propagated from the triggering input (notification/bank/email). */
    val correlationId: String? = null
)
