package com.yourname.expensetracker.domain.quickadd

import java.time.LocalDate
import java.time.ZoneId

/**
 * Value objects of the quick-add parser (C1). Pure Kotlin: no Android, logging, data or AI types.
 *
 * Privacy: the parse result never carries the raw line, the token list, a currency code or a
 * foreign amount. [QuickAddInput] and [MerchantText] redact themselves in `toString()` so an
 * accidental log or state dump cannot leak line content.
 */

/** A picker category: only what exact-name matching needs. */
data class CategoryRef(val id: Long, val name: String)

/**
 * Transient parser input. Never stored.
 *
 * @property line the typed line (at most [QuickAddParser.MAX_LINE_LENGTH] chars; longer lines are rejected)
 * @property nowEpochMs "now" at Fill time (the parser never reads a clock)
 * @property zone the zone used for all calendar arithmetic (the parser never reads the system zone)
 * @property homeCurrency non-null ISO code of the home currency
 * @property categories the picker list at Fill time
 */
data class QuickAddInput(
    val line: String,
    val nowEpochMs: Long,
    val zone: ZoneId,
    val homeCurrency: String,
    val categories: List<CategoryRef>
) {
    override fun toString(): String = "QuickAddInput(redacted)"
}

/** One field result is exactly one of [Value], [Flagged] or [Absent]. */
sealed interface FieldParse<out T> {
    data class Value<out T>(val value: T) : FieldParse<T>

    data class Flagged(val reason: FlagReason) : FieldParse<Nothing>

    data object Absent : FieldParse<Nothing>
}

/**
 * Canonical amount text in the home currency: matches `^\d{1,7}\.\d{2}$`, value in
 * [0.01, 1000000.00], produced without rounding or truncation.
 */
data class AmountText(val text: String)

/** A resolved date: [epochMs] is always on [localDate] in the input zone. */
data class ResolvedDate(val localDate: LocalDate, val epochMs: Long)

/** 1..100 chars, trimmed, non-blank. Redacts itself in `toString()`. */
data class MerchantText(val text: String) {
    override fun toString(): String = "MerchantText(length=${text.length})"
}

/** Payment methods the parser can produce. Mapped to the data-layer enum by the caller (C). */
enum class QuickAddPayment { CARD, CASH }

enum class CategorySource { EXACT_NAME, LEARNED }

data class CategoryMatch(val categoryId: Long, val source: CategorySource)

/** Closed, bounded flag reasons. The precedence inside a field is the declaration order. */
enum class FlagReason {
    AMOUNT_FOREIGN_CURRENCY,
    AMOUNT_QUANTITY,
    AMOUNT_MULTIPLE_NUMBERS,
    AMOUNT_AMBIGUOUS_SEPARATOR,
    AMOUNT_UNCLEAR,
    AMOUNT_NOT_POSITIVE,
    AMOUNT_OVER_LIMIT,
    DATE_AMBIGUOUS,
    DATE_UNSUPPORTED,
    DATE_INVALID,
    DATE_FUTURE,
    PAYMENT_CONFLICT,
    CATEGORY_AMBIGUOUS
}

/** Closed set of whole-line hints. */
enum class LineHint {
    FOREIGN_CURRENCY_NOT_SUPPORTED,
    TIME_IGNORED,
    LINE_TOO_LONG,
    NOTHING_RECOGNISED
}

/**
 * Parser output. No raw line, no tokens, no currency code, no foreign amount.
 * The merchant field is never [FieldParse.Flagged].
 */
data class QuickAddParseResult(
    val amount: FieldParse<AmountText>,
    val date: FieldParse<ResolvedDate>,
    val merchant: FieldParse<MerchantText>,
    val payment: FieldParse<QuickAddPayment>,
    val category: FieldParse<CategoryMatch>,
    val lineHints: Set<LineHint>
)
