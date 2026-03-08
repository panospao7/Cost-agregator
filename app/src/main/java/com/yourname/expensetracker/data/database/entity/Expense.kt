package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
        Index(value = ["latitude", "longitude"])      // Location queries (v28)
    ]
)
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val amount: Double,
    val currency: String = "EUR",
    
    val merchant: String,
    
    val transactionType: TransactionType,
    
    val date: Long,
    
    val rawNotificationId: Long? = null,
    
    
    val categoryId: Long? = null,
    
    val createdAt: Long = System.currentTimeMillis(),

    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    val isManualEntry: Boolean = false,
    val notes: String? = null,

    val dedupeKey: String? = null,

    val transferDirection: TransferDirection? = null,
    val transferAccountName: String? = null,
    val isNotMine: Boolean = false,
    val ownerName: String? = null,
    val isSharedExpense: Boolean = false,
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
    val backfillAttempts: Int = 0,

    // Human-readable resolved address string (v30), e.g. "Σκλαβενίτης, Γλυφάδα, Αττική"
    val resolvedAddress: String? = null
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

        // Minimal Greek → Latin map used only for dedupeKey generation.
        // Keeps the Expense entity self-contained (no Hilt injection needed).
        // Must stay in sync with GreeklishNormalizer.GREEK_TO_LATIN.
        private val GREEK_LATIN = mapOf(
            'α' to "a", 'ά' to "a", 'Α' to "a", 'Ά' to "a",
            'β' to "v", 'Β' to "v",
            'γ' to "g", 'Γ' to "g",
            'δ' to "d", 'Δ' to "d",
            'ε' to "e", 'έ' to "e", 'Ε' to "e", 'Έ' to "e",
            'ζ' to "z", 'Ζ' to "z",
            'η' to "i", 'ή' to "i", 'Η' to "i", 'Ή' to "i",
            'θ' to "th",'Θ' to "th",
            'ι' to "i", 'ί' to "i", 'ϊ' to "i", 'ΐ' to "i", 'Ι' to "i", 'Ί' to "i",
            'κ' to "k", 'Κ' to "k",
            'λ' to "l", 'Λ' to "l",
            'μ' to "m", 'Μ' to "m",
            'ν' to "n", 'Ν' to "n",
            'ξ' to "x", 'Ξ' to "x",
            'ο' to "o", 'ό' to "o", 'Ο' to "o", 'Ό' to "o",
            'π' to "p", 'Π' to "p",
            'ρ' to "r", 'Ρ' to "r",
            'σ' to "s", 'ς' to "s", 'Σ' to "s",
            'τ' to "t", 'Τ' to "t",
            'υ' to "y", 'ύ' to "y", 'ϋ' to "y", 'ΰ' to "y", 'Υ' to "y", 'Ύ' to "y",
            'φ' to "f", 'Φ' to "f",
            'χ' to "ch",'Χ' to "ch",
            'ψ' to "ps",'Ψ' to "ps",
            'ω' to "o", 'ώ' to "o", 'Ω' to "o", 'Ώ' to "o"
        )

        private fun transliterateGreek(text: String): String =
            text.map { c -> GREEK_LATIN[c] ?: c.toString() }.joinToString("")

        fun generateDedupeKey(amount: Double, merchant: String, date: Long): String {
            // Transliterate Greek → Latin so cross-source duplicates (bank Greek name vs
            // Google Wallet Latin name) land in the same dedupeKey bucket.
            val latinMerchant = transliterateGreek(merchant)
            val normalizedMerchant = latinMerchant.lowercase()
                .replace(Regex("[^a-z0-9]"), "")
                .take(20)
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
