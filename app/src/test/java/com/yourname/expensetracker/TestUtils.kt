package com.yourname.expensetracker

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Test utilities for creating test data and assertions.
 *
 * These helper functions provide a clean DSL for test data creation
 * and floating-point tolerant assertions.
 */

// ============================================================================
// Test Data Creation Helpers
// ============================================================================

private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val nextTestExpenseId = AtomicLong(System.currentTimeMillis())

/**
 * Creates an Expense object for test purposes.
 *
 * @param date Date string in ISO format (e.g., "2026-03-05")
 * @param amount Raw transaction amount
 * @param effectiveAmount Amount that should count toward user's spending (defaults to amount)
 * @param isNotMine If true, expense is excluded from user's spending
 * @param type Transaction type (default: PURCHASE)
 * @param category Category name (will be mapped to a test category ID if found)
 * @param merchant Merchant name
 * @param id Optional explicit ID (default: auto-generated)
 * @param isSharedExpense If true, this is a split/shared expense
 * @param myShareAmount Fixed share amount for split transactions
 * @param mySharePercentage Percentage share (0-100) for split transactions
 *
 * @return Configured Expense entity
 */
fun createExpense(
    date: String,
    amount: Double,
    effectiveAmount: Double = amount,
    isNotMine: Boolean = false,
    type: TransactionType = TransactionType.PURCHASE,
    category: String? = null,
    merchant: String = "Test Merchant",
    id: Long? = null,
    isSharedExpense: Boolean = false,
    myShareAmount: Double? = null,
    mySharePercentage: Int? = null
): Expense {
    val epochMillis = LocalDate.parse(date, dateFormatter)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    val expense = Expense(
        id = id ?: nextTestExpenseId.incrementAndGet(),
        amount = amount,
        merchant = merchant,
        transactionType = type,
        date = epochMillis,
        categoryId = category?.let { resolveCategoryId(it) },
        paymentMethod = PaymentMethod.CARD,
        createdAt = System.currentTimeMillis(),
        isNotMine = isNotMine,
        isSharedExpense = isSharedExpense,
        myShareAmount = myShareAmount,
        mySharePercentage = mySharePercentage
    )

    // Verify effective amount matches expected
    require(kotlin.math.abs(expense.effectiveAmount - effectiveAmount) < 0.01) {
        "Effective amount mismatch for $merchant: " +
        "expected $effectiveAmount, calculated ${expense.effectiveAmount}"
    }

    return expense
}

/**
 * Resolves a category name to a test category ID.
 * Returns null if category not found.
 */
private fun resolveCategoryId(categoryName: String): Long? {
    return when (categoryName.lowercase()) {
        "food & dining", "food", "dining" -> 1L
        "groceries", "grocery" -> 2L
        "entertainment" -> 3L
        "travel" -> 4L
        "utilities", "utility" -> 5L
        "coffee" -> 1L
        "restaurant", "restaurants" -> 1L
        else -> null
    }
}

// ============================================================================
// Assertion Helpers
// ============================================================================

/**
 * Asserts that two Double values are approximately equal within a tolerance.
 *
 * @param expected Expected value
 * @param actual Actual value
 * @param tolerance Maximum allowed difference (default: 0.01)
 * @param message Optional message to include on failure
 */
fun assertApproxEquals(
    expected: Double,
    actual: Double,
    tolerance: Double = 0.01,
    message: String? = null
) {
    val diff = kotlin.math.abs(expected - actual)
    val fullMessage = buildString {
        if (message != null) append(message)
        append("Expected $expected ±$tolerance, but was $actual (diff: $diff)")
    }
    Assert.assertTrue(fullMessage, diff <= tolerance)
}

fun assertApproxEquals(
    expected: Double,
    actual: Double?,
    tolerance: Double = 0.01,
    message: String? = null
) {
    if (actual == null) {
        Assert.fail(buildString {
            if (message != null) append(message)
            append("Expected $expected ±$tolerance, but actual was null")
        })
    }
    assertApproxEquals(expected, requireNotNull(actual), tolerance, message)
}

fun assertApproxEquals(
    expected: Double?,
    actual: Double?,
    tolerance: Double = 0.01,
    message: String? = null
) {
    if (expected == null || actual == null) {
        Assert.fail(buildString {
            if (message != null) append(message)
            append("Expected and actual values must be non-null; expected=$expected actual=$actual")
        })
    }
    assertApproxEquals(requireNotNull(expected), requireNotNull(actual), tolerance, message)
}

/**
 * Asserts that a money amount, rounded HALF_UP to cents, equals a hard-coded expected
 * cent literal.
 *
 * The actual is rounded via `BigDecimal.valueOf(actual).setScale(2, RoundingMode.HALF_UP)`
 * and must `compareTo == 0` the expected cent value, so it accepts sub-cent deviations
 * (|delta| < 0.005) but rejects an actual that rounds to a different cent (unlike
 * [assertApproxEquals] with its default 0.01 inclusive tolerance, where a whole cent of
 * error passes). Intended for computed money (means, percentages), where the engines keep
 * raw IEEE-754 results (for example `1600.0 / 12` is `133.33333333333334`). Where an
 * amount must be exact (repository pass-through, zero-fill), use [assertMoneyExact]
 * (bit-exact via `java.lang.Double.compare`, so `-0.0` != `0.0`) instead.
 *
 * The expected value must itself be a cent-precision literal (at most 2 decimals,
 * e.g. `133.33`); a literal such as `133.333` is rejected so expectations cannot
 * silently hide precision. Both values must be finite. A `null` actual (unavailable
 * money) never equals an amount, including zero.
 *
 * @param expected Hard-coded expected amount with at most 2 decimals
 * @param actual Actual amount produced by the code under test
 * @param message Optional row/case name included in every failure message
 */
fun assertMoneyEquals(expected: Double, actual: Double, message: String? = null) {
    val prefix = if (message != null) "$message: " else ""
    Assert.assertTrue("${prefix}expected must be finite, was $expected", expected.isFinite())
    Assert.assertTrue("${prefix}actual must be finite, was $actual", actual.isFinite())
    val exactExpected = BigDecimal.valueOf(expected)
    val expectedCents = exactExpected.setScale(2, RoundingMode.HALF_UP)
    Assert.assertTrue(
        "${prefix}expected $expected is not a cent-precision literal (max 2 decimals)",
        exactExpected.compareTo(expectedCents) == 0
    )
    val actualCents = BigDecimal.valueOf(actual).setScale(2, RoundingMode.HALF_UP)
    Assert.assertTrue(
        "${prefix}expected $expectedCents but was $actualCents (raw actual: $actual)",
        expectedCents.compareTo(actualCents) == 0
    )
}

/** Null-aware overload of [assertMoneyEquals]: a `null` actual always fails. */
fun assertMoneyEquals(expected: Double, actual: Double?, message: String? = null) {
    if (actual == null) {
        Assert.fail("${if (message != null) "$message: " else ""}expected $expected but amount was null (unavailable)")
    }
    assertMoneyEquals(expected, requireNotNull(actual), message)
}

/**
 * Asserts that a money amount is bit-exact (zero tolerance). Use for amounts that must
 * not be recomputed or rounded: pass-through values, zero-fill, and values that must not
 * be converted. A `null` actual (unavailable money) always fails, including for zero.
 *
 * Equality is decided by [java.lang.Double.compare], so it is stricter than a delta of
 * `0.0`: negative zero (`-0.0`) is NOT equal to `0.0` (production clamps must yield
 * positive zero), and `NaN` equals `NaN` (`Double.compare` treats NaN as equal to
 * itself). Callers expecting zero must
 * therefore receive exactly `+0.0`.
 *
 * @param expected Hard-coded expected amount
 * @param actual Actual amount produced by the code under test
 * @param message Optional row/case name included in every failure message
 */
fun assertMoneyExact(expected: Double, actual: Double?, message: String? = null) {
    if (actual == null) {
        Assert.fail("${if (message != null) "$message: " else ""}expected $expected but amount was null (unavailable)")
    }
    val actualValue = requireNotNull(actual)
    if (java.lang.Double.compare(expected, actualValue) != 0) {
        // Same message format as Assert.assertEquals(message, expected, actual, 0.0).
        val prefix = if (message != null && message.isNotEmpty()) "$message " else ""
        Assert.fail("${prefix}expected:<$expected> but was:<$actualValue>")
    }
}

/**
 * Asserts that two Float values are approximately equal within a tolerance.
 *
 * @param expected Expected value
 * @param actual Actual value
 * @param tolerance Maximum allowed difference (default: 0.1f)
 * @param message Optional message to include on failure
 */
fun assertApproxEquals(
    expected: Float,
    actual: Float,
    tolerance: Float = 0.1f,
    message: String? = null
) {
    val diff = kotlin.math.abs(expected - actual)
    val fullMessage = buildString {
        if (message != null) append(message)
        append("Expected $expected ±$tolerance, but was $actual (diff: $diff)")
    }
    Assert.assertTrue(fullMessage, diff <= tolerance)
}

fun assertApproxEquals(
    expected: Float,
    actual: Float?,
    tolerance: Float = 0.1f,
    message: String? = null
) {
    if (actual == null) {
        Assert.fail(buildString {
            if (message != null) append(message)
            append("Expected $expected ±$tolerance, but actual was null")
        })
    }
    assertApproxEquals(expected, requireNotNull(actual), tolerance, message)
}

fun assertApproxEquals(
    expected: Float?,
    actual: Float?,
    tolerance: Float = 0.1f,
    message: String? = null
) {
    if (expected == null || actual == null) {
        Assert.fail(buildString {
            if (message != null) append(message)
            append("Expected and actual values must be non-null; expected=$expected actual=$actual")
        })
    }
    assertApproxEquals(requireNotNull(expected), requireNotNull(actual), tolerance, message)
}

// ============================================================================
// Date Helpers
// ============================================================================

/**
 * Converts an ISO date string to epoch milliseconds.
 */
fun dateToMillis(date: String): Long {
    return LocalDate.parse(date, dateFormatter)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

/**
 * Gets the start of a month in epoch milliseconds.
 */
fun startOfMonth(year: Int, month: Int): Long {
    return LocalDate.of(year, month, 1)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

/**
 * Gets the half-open end of a month in epoch milliseconds.
 *
 * Returns the start of the **next** month (exclusive upper bound),
 * consistent with the `[startInclusive, endExclusive)` convention.
 *
 * A timestamp `t` belongs to this month when:
 * ```
 * t >= startOfMonth(year, month) && t < endOfMonth(year, month)
 * ```
 */
fun endOfMonth(year: Int, month: Int): Long {
    return LocalDate.of(year, month, 1)
        .plusMonths(1)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

// ============================================================================
// Category Helpers
// ============================================================================

/**
 * Standard test categories for consistent testing.
 */
val TEST_CATEGORIES = listOf(
    Category(id = 1L, name = "Food & Dining", icon = "🍽️", color = "#FF5733"),
    Category(id = 2L, name = "Groceries", icon = "🛒", color = "#33FF57"),
    Category(id = 3L, name = "Entertainment", icon = "🎬", color = "#3357FF"),
    Category(id = 4L, name = "Travel", icon = "✈️", color = "#F333FF"),
    Category(id = 5L, name = "Utilities", icon = "💡", color = "#FFD700")
)
