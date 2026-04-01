package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"]),
        Index(value = ["transactionType", "date"]),
        Index(value = ["transactionType", "categoryId", "date"]),
        Index(value = ["categoryId", "date"]),
        Index(value = ["amount", "merchant", "date"]),
        Index(value = ["merchant", "date"]),
        Index(value = ["transactionType", "merchant", "date"]),
        Index(value = ["dedupeKey"], unique = true), // Atomic duplicate prevention
        Index(value = ["latitude", "longitude"]),     // Location queries (v28)
        Index(value = ["merchantKey"]),                // Unified merchant identity key (v32)
        Index(value = ["isBusinessExpense"])         // Business expense queries (v41)
    ]
)
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val amount: Double,
    @ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",
    
    val merchant: String,
    
    val transactionType: TransactionType,
    
    val date: Long,
    
    val rawNotificationId: Long? = null,
    
    
    val categoryId: Long? = null,
    
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(defaultValue = "UNKNOWN") val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    @ColumnInfo(defaultValue = "0") val isManualEntry: Boolean = false,
    val notes: String? = null,

    val dedupeKey: String? = null,

    val transferDirection: TransferDirection? = null,
    val transferAccountName: String? = null,
    @ColumnInfo(defaultValue = "0") val isNotMine: Boolean = false,
    val ownerName: String? = null,
    @ColumnInfo(defaultValue = "0") val isSharedExpense: Boolean = false,
    val sharedWithName: String? = null,
    val mySharePercentage: Int? = null,
    val myShareAmount: Double? = null,

    // Location enrichment (v28) — nullable, resolved asynchronously
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String? = null,  // "MERCHANT_GEOCODE", "DEVICE_GPS", "USER_MANUAL", "OVERPASS_POI"
    val placeId: String? = null,         // OSM node ID for future re-lookups

    // Number of times the backfill worker has tried and failed to geocode this expense (v29).
    // Expenses that reach MAX_BACKFILL_ATTEMPTS are skipped by the worker to prevent
    // indefinite Nominatim calls for unresolvable merchants.
    @ColumnInfo(defaultValue = "0") val backfillAttempts: Int = 0,

    // Human-readable resolved address string (v30), e.g. "Σκλαβενίτης, Γλυφάδα, Αττική"
    val resolvedAddress: String? = null,

    // Canonical merchant identity key (v32) — computed by MerchantKeyGenerator.
    // Nullable on legacy rows; backfilled asynchronously by MerchantKeyBackfillWorker.
    @ColumnInfo(name = "merchantKey")
    val merchantKey: String? = null,

    // Business/Personal separation fields (v41)
    @ColumnInfo(defaultValue = "0") val isBusinessExpense: Boolean = false,
    val businessPurpose: String? = null, // e.g., "Client meeting", "Conference travel"
    val businessCategory: String? = null, // e.g., "Travel", "Meals", "Office Supplies", "Software"
    val businessProject: String? = null,  // For project-based expense tracking
    @ColumnInfo(defaultValue = "0") val requiresReceipt: Boolean = false,  // Flag for tax-deductible expenses needing receipts

    // Enhanced Split Transaction fields (v47)
    val splitTemplateId: Long? = null,  // Reference to SplitTemplate used
    val splitVisualization: String? = null  // JSON with visual split data (pie chart segments, colors, etc.)
) {
    /**
     * The amount that should be counted toward the user's own spending.
     * - If isNotMine: 0.0 (excluded entirely — someone else's charge)
     * - If isSharedExpense + myShareAmount set: the explicit per-person amount
     * - If isSharedExpense + mySharePercentage set: proportional share of the full amount
     * - Otherwise: full amount
     *
     * All calculations (totals, budgets, analytics, forecasting) must use this
     * instead of `amount` to correctly handle shared and not-mine expenses.
     */
    val effectiveAmount: Double
        get() = when {
            isNotMine -> 0.0
            isSharedExpense && myShareAmount != null -> myShareAmount
            isSharedExpense && mySharePercentage != null -> amount * mySharePercentage / 100.0
            else -> amount
        }
    companion object {
        private const val DUPLICATE_WINDOW_MS = 300_000L // 5 minutes

        /**
         * Generate a deduplication key from the core transaction fields.
         *
         * Uses [MerchantKeyGenerator] (Greek→Latin diphthong-aware, lowercase,
         * strip [^a-z0-9]) so that the same merchant expressed in different scripts
         * (e.g. bank SMS in Greek vs Google Wallet in Latin) maps to the same bucket.
         *
         * No length cap is applied — the old take(20) caused false-positive
         * duplicate matches between distinct merchants with long common prefixes.
         */
        fun generateDedupeKey(amount: Double, merchant: String, date: Long): String {
            val normalizedMerchant = MerchantKeyGenerator.generate(merchant)
            val roundedAmount = "%.2f".format(amount)
            val dateBucket = date / DUPLICATE_WINDOW_MS
            return "${roundedAmount}_${normalizedMerchant}_$dateBucket"
        }
    }
}

enum class TransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    UNKNOWN
}

enum class TransferDirection {
    INCOMING,
    OUTGOING
}

enum class PaymentMethod {
    CARD,
    CASH,
    BANK_TRANSFER,
    UNKNOWN
}
