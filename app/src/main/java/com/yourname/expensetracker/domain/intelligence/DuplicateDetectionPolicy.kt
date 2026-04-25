package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import java.util.Locale

/**
 * Canonical duplicate-detection policy.
 *
 * **Single source of truth** for every blocking-duplicate decision in the app.
 * All ingestion / review / AI paths must consume these constants and helpers
 * instead of carrying their own window, tolerance, or scoring rules.
 *
 * This utility is pure domain logic — no DAO / Room / Android framework deps.
 */
object DuplicateDetectionPolicy {

    // ── Constants ────────────────────────────────────────────────────────

    /** Canonical blocking-duplicate time window (5 minutes). */
    const val DUPLICATE_WINDOW_MS: Long = 5 * 60 * 1000L // 300 000 ms

    /** Maximum amount difference that still counts as "the same charge". */
    const val AMOUNT_TOLERANCE: Double = 0.01

    /**
     * Default currency when none is provided.
     * Matches the Room column default on `Expense.currency`.
     */
    const val DEFAULT_CURRENCY: String = "EUR"

    /**
     * Minimum length a merchant key must have to participate in
     * cross-package prefix-based dedup (`LIKE merchantKey || '%'`).
     *
     * Keys shorter than this are too generic (e.g. "a", "car") and
     * would produce false positives.
     *
     * **NOTE:** This value is also hardcoded in the Room `@Query`
     * annotations of `ExpenseDao.existsByMerchantKeyPrefixInRangeCurrencyAware`
     * and `PendingReviewDao.hasPendingDuplicateByMerchantKeyPrefixInRangeTypeAware`
     * because Room SQL strings cannot reference Kotlin constants.
     * If you change this value, you MUST update the SQL `LENGTH(…) >= N`
     * literals in both DAOs.
     */
    const val MIN_MERCHANT_KEY_PREFIX_LENGTH: Int = 4

    // ── Currency normalization ───────────────────────────────────────────

    /**
     * Normalize a currency code to a deterministic canonical form
     * (uppercase, trimmed). Null / blank falls back to [DEFAULT_CURRENCY].
     */
    fun normalizeCurrency(currency: String?): String =
        currency?.trim()?.uppercase(Locale.ROOT)?.ifBlank { DEFAULT_CURRENCY }
            ?: DEFAULT_CURRENCY

    // ── Merchant normalization ───────────────────────────────────────────

    /**
     * Canonical merchant key.
     * Delegates to [MerchantKeyGenerator] — the single merchant-identity source.
     */
    fun normalizeMerchant(merchant: String): String =
        MerchantKeyGenerator.generate(merchant)

    // ── Transaction-type compatibility ───────────────────────────────────

    /**
     * Two transactions can only be considered duplicates if their types are
     * compatible. Purchases match purchases, deposits match deposits, etc.
     *
     * [TransactionType.UNKNOWN] is treated as compatible with anything so
     * that legacy rows without a type are not silently excluded.
     */
    fun areTypesCompatible(a: TransactionType, b: TransactionType): Boolean {
        if (a == TransactionType.UNKNOWN || b == TransactionType.UNKNOWN) return true
        return a == b
    }

    // ── Amounts ──────────────────────────────────────────────────────────

    /** Check whether two amounts are within the shared tolerance. */
    fun areAmountsEqual(a: Double, b: Double): Boolean =
        kotlin.math.abs(a - b) <= AMOUNT_TOLERANCE

    /**
     * Locale-invariant, two-decimal-place formatting for amounts.
     * Used by dedupe-key generation and anywhere else a stable string
     * representation of an amount is needed.
     */
    fun formatAmount(amount: Double): String =
        String.format(Locale.ROOT, "%.2f", amount)

    // ── Time window ──────────────────────────────────────────────────────

    /** Check whether two timestamps fall within the canonical window. */
    fun isWithinWindow(date1: Long, date2: Long, windowMs: Long = DUPLICATE_WINDOW_MS): Boolean =
        kotlin.math.abs(date1 - date2) <= windowMs

    /**
     * Canonical exclusive end-boundary for a duplicate-window range query.
     *
     * Both the expense duplicate check ([ExpenseDao.isDuplicateCurrencyAware]) and
     * the pending-review duplicate check ([PendingReviewDao.hasPendingDuplicateInRangeTypeAware])
     * use SQL `date < :endDate` (exclusive upper bound). To make the inclusive range
     * `[date - windowMs, date + windowMs]` correct under that convention the endDate
     * parameter must be `date + windowMs + 1`.
     *
     * Use this helper everywhere a time-window endDate is passed to a DAO query so
     * that both paths share the same off-by-one-safe boundary calculation.
     *
     * @param date     the event timestamp (epoch ms)
     * @param windowMs the half-width of the duplicate window (default [DUPLICATE_WINDOW_MS])
     * @return         exclusive upper bound for the SQL `< :endDate` predicate
     */
    fun windowEndExclusive(date: Long, windowMs: Long = DUPLICATE_WINDOW_MS): Long =
        date + windowMs + 1L

    // ── Dedupe key generation ────────────────────────────────────────────

    /**
     * Generate a deduplication key from the core transaction fields.
     *
     * Format: `{amount}_{merchantKey}_{dateBucket}_{currency}`
     *
     * Uses [MerchantKeyGenerator] for the merchant component,
     * locale-invariant amount formatting, and includes the normalized
     * currency code.
     *
     * The `dateBucket` is `date / DUPLICATE_WINDOW_MS`, giving transactions
     * that fall in the same 5-minute bucket identical keys.
     *
     * **Currency is required** — callers must supply an explicit ISO-4217 code.
     * Omitting currency is not allowed on the canonical blocking path; doing so
     * previously caused a silent EUR fallback that masked cross-currency duplicates.
     *
     * @param amount      transaction amount
     * @param merchant    raw merchant display name
     * @param date        event timestamp (epoch ms)
     * @param currency    ISO-4217 currency code (required; use the expense's actual currency)
     */
    fun generateDedupeKey(
        amount: Double,
        merchant: String,
        date: Long,
        currency: String
    ): String {
        val normalizedMerchant = normalizeMerchant(merchant)
        val roundedAmount = formatAmount(amount)
        val dateBucket = date / DUPLICATE_WINDOW_MS
        val normalizedCurrency = normalizeCurrency(currency)
        return "${roundedAmount}_${normalizedMerchant}_${dateBucket}_$normalizedCurrency"
    }

    /**
     * Generate a deduplication key that encodes the transaction type so that
     * incompatible-type rows (e.g. PURCHASE vs DEPOSIT) never collide on the
     * persisted unique index.
     *
     * Format when type is known: `{amount}_{merchantKey}_{dateBucket}_{currency}_{type}`
     * Format when type is UNKNOWN: `{amount}_{merchantKey}_{dateBucket}_{currency}`
     *   (same as [generateDedupeKey] — preserves backward-compat for UNKNOWN rows)
     *
     * Use this variant whenever inserting a new expense row that will have its
     * dedupeKey persisted in the database. The type-suffix ensures two
     * legitimate transactions that differ only in type are stored with distinct
     * keys and therefore never trigger a spurious unique-index conflict.
     *
     * **Currency is required** — callers must supply an explicit ISO-4217 code.
     * Omitting currency is not allowed on the canonical blocking path; doing so
     * previously caused a silent EUR fallback that masked cross-currency duplicates.
     *
     * @param amount          transaction amount
     * @param merchant        raw merchant display name
     * @param date            event timestamp (epoch ms)
     * @param currency        ISO-4217 currency code (required; use the expense's actual currency)
     * @param transactionType the transaction type (non-null; use UNKNOWN for legacy/unknown rows)
     */
    fun generateDedupeKeyWithType(
        amount: Double,
        merchant: String,
        date: Long,
        currency: String,
        transactionType: TransactionType
    ): String {
        val base = generateDedupeKey(amount, merchant, date, currency)
        // UNKNOWN is compatible with every type — do not append suffix so that
        // UNKNOWN-typed rows remain reachable by the type-blind key and are still
        // caught as duplicates by the range-based isDuplicateCurrencyAware check.
        return if (transactionType == TransactionType.UNKNOWN) base
        else "${base}_${transactionType.name}"
    }

    // ── Candidate scoring / tie-breaks ───────────────────────────────────

    /**
     * Data holder for a scored duplicate candidate.
     */
    data class ScoredCandidate<T>(
        val candidate: T,
        /** Absolute time delta in milliseconds. */
        val timeDeltaMs: Long,
        /** Absolute amount delta. */
        val amountDelta: Double,
        /** Merchant confidence from deterministic similarity (0..1). */
        val merchantConfidence: Float,
        /** Optional location proximity boost. */
        val locationBoost: Float = 0f
    )

    /**
     * Deterministic tie-break comparator among hard-match candidates.
     *
     * Ranking order (ascending = best):
     *  1. Smallest time delta
     *  2. Smallest amount delta
     *  3. Highest merchant confidence (inverted for ascending sort)
     */
    fun <T> rankCandidates(candidates: List<ScoredCandidate<T>>): List<ScoredCandidate<T>> =
        candidates.sortedWith(
            compareBy<ScoredCandidate<T>> { it.timeDeltaMs }
                .thenBy { it.amountDelta }
                .thenByDescending { it.merchantConfidence + it.locationBoost }
        )

    /**
     * Select the best duplicate candidate from a pre-scored list, or null if empty.
     */
    fun <T> bestCandidate(candidates: List<ScoredCandidate<T>>): T? =
        rankCandidates(candidates).firstOrNull()?.candidate

    // ── Full duplicate-eligibility check (for convenience) ───────────────

    /**
     * Convenience: checks amount, currency, type, and time-window compatibility
     * between a new transaction and an existing expense.
     *
     * Does **not** check merchant similarity — the caller should pre-filter or
     * score merchants separately (deterministic + optional AI).
     */
    fun isEligibleCandidate(
        newAmount: Double,
        newCurrency: String?,
        newType: TransactionType,
        newDate: Long,
        existing: Expense,
        windowMs: Long = DUPLICATE_WINDOW_MS
    ): Boolean {
        if (!areAmountsEqual(newAmount, existing.amount)) return false
        if (normalizeCurrency(newCurrency) != normalizeCurrency(existing.currency)) return false
        if (!areTypesCompatible(newType, existing.transactionType)) return false
        if (!isWithinWindow(newDate, existing.date, windowMs)) return false
        return true
    }
}
