package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

enum class CategorizationStatus {
    PENDING,      // Not yet analyzed
    ANALYZING,    // AI working
    READY,        // Complete, user reviewed
    CORRECTED,    // User made corrections
    SKIPPED       // User opted out
}

enum class MatchStatus {
    UNMATCHED,      // Not yet matched
    AUTO_MATCHED,   // Automatically matched with high confidence
    SUGGESTED,      // Suggestion for manual review
    MANUALLY_MATCHED, // User confirmed match
    REJECTED        // User rejected all suggestions
}

@Entity(
    tableName = "scanned_receipts",
    foreignKeys = [
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["expenseId"]),
        Index(value = ["createdAt"]),
        Index(name = "index_scanned_receipts_matchStatus", value = ["matchStatus"]),
        Index(value = ["processingStatus"])
    ]
)
data class ScannedReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String?,
    val rawOcrText: String,
    val parsedTotal: Double?,
    val parsedMerchant: String?,
    val parsedDate: Long?,
    /**
     * JSON array of line items (parsed receipt items).
     *
     * ## N1: Receipt line items stored as JSON, not relational
     * This field stores line items as a serialized JSON string rather than in
     * a normalized relational table. This makes it impossible to query, join,
     * or aggregate individual line items at the database level — e.g. "find
     * all receipts that contain 'milk'" requires deserializing every row.
     *
     * A future migration should extract line items into a separate
     * `receipt_line_items` table with columns for description, quantity,
     * unitPrice, totalPrice, and a foreign key back to [ScannedReceipt].
     * Once the relational table is populated, this JSON column can be
     * deprecated and eventually removed.
     */
    val parsedItems: String?,        // JSON array of line items
    val parsedTaxAmount: Double?,
    @ColumnInfo(defaultValue = "'EUR'") val currency: String = "EUR",
    val confidence: Float,
    @ColumnInfo(defaultValue = "NULL") val expenseId: Long? = null,
    @ColumnInfo(defaultValue = "'UNMATCHED'") val matchStatus: MatchStatus = MatchStatus.UNMATCHED,
    @ColumnInfo(defaultValue = "NULL") val matchConfidence: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val suggestedExpenseId: Long? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "'PENDING'") val itemCategorizationStatus: CategorizationStatus = CategorizationStatus.PENDING,

    // NEW Phase 4 fields
    @ColumnInfo(defaultValue = "'UNKNOWN'") val sourceType: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val documentType: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'CAPTURED'") val processingStatus: String = "CAPTURED",
    @ColumnInfo(defaultValue = "NULL") val sourceFingerprint: String? = null,
    @ColumnInfo(defaultValue = "NULL") val imageHash: String? = null,
    @ColumnInfo(defaultValue = "NULL") val textFingerprint: String? = null,
    @ColumnInfo(defaultValue = "NULL") val semanticFingerprint: String? = null,
    @ColumnInfo(defaultValue = "NULL") val ocrConfidence: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val parseFailureReason: String? = null,
    /** Must be set to timeProvider.now() on update. 0L = unset (sentinel). */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,

    // Raw data retention: epoch ms when raw OCR text was purged, null = not yet purged
    @ColumnInfo(defaultValue = "NULL") val rawOcrTextPurgedAt: Long? = null
) {
    @get:Ignore
    val parsedTaxMoneyAmount: MoneyAmount? get() = parsedTaxAmount?.let { MoneyAmount(it, CurrencyCode(currency)) }

    /**
     * RCP-14: Transient flag indicating whether the receipt total and line item
     * totals already include the tax amount. This is NOT persisted in the
     * database; it is set during receipt processing (see
     * [ReceiptLifecycleCoordinator.processReceiptInput]) and carried on the
     * in-memory copy for downstream consumers (ViewModel, UI, expense creation).
     *
     * When `true`:
     * - Use [parsedTotal] directly as the expense amount — do NOT add tax.
     * - Line item totals already include proportional tax.
     * - For a tax-exclusive subtotal (e.g. for business reports):
     *   `subtotal = parsedTotal - parsedTaxAmount`.
     */
    @Ignore
    var taxInclusive: Boolean = false

    @get:Ignore
    val lineItemCount: Int get() {
        if (parsedItems.isNullOrBlank()) return 0
        return try {
            val arr = org.json.JSONArray(parsedItems)
            arr.length()
        } catch (_: Exception) {
            0
        }
    }
}
