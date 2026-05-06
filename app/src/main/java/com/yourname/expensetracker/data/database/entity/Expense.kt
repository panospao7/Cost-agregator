package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy

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
        ),
        ForeignKey(
            entity = SplitTemplate::class,
            parentColumns = ["id"],
            childColumns = ["splitTemplateId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"], unique = true),
        Index(value = ["date"]),
        Index(value = ["transactionType", "date"]),
        Index(value = ["transactionType", "categoryId", "date"]),
        Index(value = ["categoryId", "date"]),
        Index(value = ["amount", "merchant", "date"]),
        Index(value = ["merchant", "date"]),
        Index(value = ["transactionType", "merchant", "date"]),
        Index(value = ["dedupeKey"], unique = true), // Atomic duplicate prevention
        Index(value = ["latitude", "longitude"]),     // Location queries (v28)
        Index(value = ["latitude", "backfillAttempts", "date"]), // Backfill queue optimization
        Index(value = ["merchantKey"]),                // Unified merchant identity key (v32)
        Index(value = ["merchantKey", "date", "amount"]), // Duplicate checks by key + time + amount
        Index(value = ["isBusinessExpense"]),        // Business expense queries (v41)
        Index(value = ["splitTemplateId"])             // FK index for split_templates (v76)
    ]
)
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val amount: Double,
    @ColumnInfo(defaultValue = "'EUR'") val currency: String = "EUR",
    
    val merchant: String,
    
    val transactionType: TransactionType,
    
    val date: Long,
    
    /**
     * FK to [RawNotification]. ON DELETE SET NULL means that when the source
     * notification is deleted, this reference is automatically cleared, losing
     * the specific audit trail of which notification produced this expense.
     * The [source] column preserves the fact that it came from a notification
     * but not the specific notification ID.
     */
    val rawNotificationId: Long? = null,
    
    
    val categoryId: Long? = null,
    
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,

    /** Source of this expense. Nullable for legacy rows; backfilled by migration. */
    val source: String? = null,

    /**
     * Payment method used for this expense (CARD, CASH, BANK_TRANSFER, or UNKNOWN).
     *
     * ## WRN-27: Credit-card benefits tied to payment method
     * Credit-card benefits (cashback, extended warranty, price protection, purchase
     * protection, etc.) should be determined by the [paymentMethod] field rather than
     * by merchant-name heuristics. When [paymentMethod] is [PaymentMethod.CARD],
     * downstream features like [PriceProtectionTracker.getCreditCardBenefits] should
     * use the card type (or a card-account lookup via the bank-integration layer) to
     * offer accurate benefit estimates instead of relying on English keyword matching
     * against the merchant name.
     *
     * A future `CardAccount` entity mapping card type → reward program would enable
     * precise benefit computation. Until then, CARD-marked expenses are eligible for
     * generic extended-warranty / price-protection awareness in the UI.
     */
    @ColumnInfo(defaultValue = "'UNKNOWN'") val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
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
    val splitVisualization: String? = null,  // JSON with visual split data (pie chart segments, colors, etc.)

    // Historical conversion snapshot fields (D.19) — Populated at creation and update time by TransactionLifecycleCoordinator when expense currency differs from home currency. Identity values (amount, currency, 1.0) are set when expense currency matches home currency.
    @ColumnInfo(defaultValue = "0.0") val baseAmount: Double = 0.0,
    @ColumnInfo(defaultValue = "'EUR'") val baseCurrency: String = "EUR",
    @ColumnInfo(defaultValue = "0.0") val exchangeRateUsed: Double = 0.0
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
    @get:Ignore
    val moneyAmount: MoneyAmount get() = MoneyAmount(amount, CurrencyCode(currency))

    val effectiveAmount: Double
        get() = when {
            isNotMine -> 0.0
            isSharedExpense && myShareAmount != null -> myShareAmount
            isSharedExpense && mySharePercentage != null -> amount * mySharePercentage / 100.0
            else -> amount
        }

    val hasConflictingOwnershipFlags: Boolean
        get() = isNotMine && isSharedExpense

    fun normalizeOwnership(): Expense {
        val normalizedOwnerName = ownerName?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedSharedWithName = sharedWithName?.trim()?.takeIf { it.isNotEmpty() }

        return when {
            isNotMine -> copy(
                ownerName = normalizedOwnerName,
                isSharedExpense = false,
                sharedWithName = null,
                mySharePercentage = null,
                myShareAmount = null
            )

            isSharedExpense -> copy(
                isNotMine = false,
                ownerName = null,
                sharedWithName = normalizedSharedWithName
            )

            else -> copy(
                ownerName = null,
                sharedWithName = null,
                mySharePercentage = null,
                myShareAmount = null
            )
        }
    }
    companion object {
        /**
         * Generate a deduplication key from the core transaction fields.
         *
         * Delegates to [DuplicateDetectionPolicy.generateDedupeKey] which uses
         * [MerchantKeyGenerator] for merchant normalization, locale-invariant
         * amount formatting, and includes the normalized currency code.
         *
         * Key format: `{amount}_{merchantKey}_{dateBucket}_{currency}`
         *
         * **Currency is required** — callers must supply an explicit ISO-4217 code.
         * Omitting currency is no longer allowed on this blocking path; doing so
         * previously caused a silent EUR fallback that masked cross-currency duplicates.
         *
         * @param amount   transaction amount
         * @param merchant raw merchant display name
         * @param date     event timestamp (epoch ms)
         * @param currency ISO-4217 currency code (required; use the expense's actual currency)
         */
        fun generateDedupeKey(
            amount: Double,
            merchant: String,
            date: Long,
            currency: String
        ): String = DuplicateDetectionPolicy.generateDedupeKey(amount, merchant, date, currency)
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
