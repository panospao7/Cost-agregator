package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

enum class PendingReviewStatus {
    PENDING,
    PROCESSING,
    APPROVED,
    REJECTED,
    MODIFIED,
    DUPLICATE
}

/**
 * Describes how the review's suggested values were obtained.
 * Used by the approval guard to distinguish real extractions from
 * synthetic placeholders (e.g. [PendingReview.FALLBACK_SUGGESTED_AMOUNT]).
 */
enum class ExtractionState {
    /** Values were successfully extracted from the source (notification/receipt). */
    REAL_EXTRACTION,
    /** Parser could not extract values; synthetic placeholders were used instead. */
    SYNTHETIC_PLACEHOLDER
}

@Entity(
    tableName = "pending_reviews",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ScannedReceipt::class,
            parentColumns = ["id"],
            childColumns = ["scannedReceiptId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"], unique = true),
        Index(value = ["scannedReceiptId"]),
        Index(value = ["status"]),
        Index(value = ["status", "createdAt"]),
        Index(value = ["suggestedMerchantKey"]),
        Index(value = ["status", "suggestedMerchantKey", "suggestedDate"])
    ]
)
data class PendingReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawNotificationId: Long?,
    val scannedReceiptId: Long? = null,
    val suggestedAmount: Double,
    val suggestedCurrency: String,
    val suggestedMerchant: String,
    val suggestedMerchantKey: String? = null,
    val suggestedType: String,          // TransactionType name
    val suggestedCategoryId: Long?,
    val suggestedDate: Long? = null,    // Added in v11 to preserve parsed date
    val confidence: Float,
    val matchType: String? = null,      // How the category was determined (EXACT, CANONICAL, KEYWORD, CONTEXT, etc.)
    val explanation: String? = null,     // Human-readable explanation of how category was inferred
    val packageName: String,
    val notificationTitle: String?,
    val notificationText: String?,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "PENDING") val status: PendingReviewStatus = PendingReviewStatus.PENDING,
    // Transfer direction fields (v24)
    val suggestedDirection: String? = null,    // INCOMING, OUTGOING
    val suggestedAccountName: String? = null,  // Account name from/to
    // Location enrichment (v28) — captured at review-time if device location available
    val suggestedLatitude: Double? = null,
    val suggestedLongitude: Double? = null,
    // Extraction state (v110) — marks whether suggested values are real or synthetic
    @ColumnInfo(defaultValue = "REAL_EXTRACTION")
    val extractionState: ExtractionState = ExtractionState.REAL_EXTRACTION
) {
    @get:Ignore
    val suggestedMoneyAmount: MoneyAmount get() = MoneyAmount(suggestedAmount, CurrencyCode(suggestedCurrency))

    companion object {
        /**
         * Minimum positive sentinel used when the parser cannot extract a total.
         *
         * **UI-PLACEHOLDER ONLY — never becomes a real expense amount.**
         * Must satisfy the v76 CHECK(suggestedAmount > 0) invariant.
         *
         * Reviews with [extractionState] = [ExtractionState.SYNTHETIC_PLACEHOLDER]
         * carry this sentinel value and must be edited by the user before approval.
         */
        const val FALLBACK_SUGGESTED_AMOUNT = 0.01
    }
}
