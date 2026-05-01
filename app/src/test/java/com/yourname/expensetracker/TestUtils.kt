package com.yourname.expensetracker

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        id = id ?: System.currentTimeMillis(),
        amount = amount,
        merchant = merchant,
        transactionType = type,
        date = epochMillis,
        categoryId = category?.let { resolveCategoryId(it) },
        paymentMethod = PaymentMethod.CARD,
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

/**
 * Asserts that an actual value is within a percentage of expected value.
 *
 * @param expected Expected value
 * @param actual Actual value
 * @param percentTolerance Percentage tolerance (default: 1.0 = 1%)
 * @param message Optional message to include on failure
 */
fun assertWithinPercent(
    expected: Double,
    actual: Double,
    percentTolerance: Double = 1.0,
    message: String? = null
) {
    val tolerance = expected * (percentTolerance / 100.0)
    val diff = kotlin.math.abs(expected - actual)
    val fullMessage = buildString {
        if (message != null) append(message)
        append("Expected $expected ±$percentTolerance%, but was $actual (diff: ${diff / expected * 100}%)")
    }
    Assert.assertTrue(fullMessage, diff <= tolerance)
}

/**
 * Asserts that an expense's effective amount matches expected.
 */
fun assertEffectiveAmount(
    expense: Expense,
    expected: Double,
    tolerance: Double = 0.01
) {
    assertApproxEquals(
        expected = expected,
        actual = expense.effectiveAmount,
        tolerance = tolerance,
        message = "Expense '${expense.merchant}' effective amount: "
    )
}

/**
 * Asserts that a list of expenses has the expected total effective amount.
 */
fun assertTotalEffective(
    expenses: List<Expense>,
    expected: Double,
    tolerance: Double = 0.01,
    message: String? = null
) {
    val actual = expenses.sumOf { it.effectiveAmount }
    val fullMessage = message ?: "Total effective amount: "
    assertApproxEquals(
        expected = expected,
        actual = actual,
        tolerance = tolerance,
        message = fullMessage
    )
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

/**
 * Gets the inclusive end of a month (last millisecond) in epoch milliseconds.
 *
 * **Deprecated**: Returns `23:59:59.999999999` of the last day of the month
 * (inclusive end), which violates the half-open
 * `[startInclusive, endExclusive)` convention.
 *
 * Use [endOfMonth] instead, which returns the half-open exclusive end
 * (start of the next month).
 */
@Deprecated(
    message = "Returns inclusive end (last millisecond). Use endOfMonth() which returns half-open exclusive end.",
    replaceWith = ReplaceWith("endOfMonth(year, month)")
)
fun endOfMonthInclusive(year: Int, month: Int): Long {
    val lastDay = LocalDate.of(year, month, 1)
        .plusMonths(1)
        .minusDays(1)
    return lastDay
        .atTime(23, 59, 59, 999_999_999)
        .atZone(ZoneId.systemDefault())
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

/**
 * Gets a test category by ID.
 */
fun getTestCategory(id: Long): Category? {
    return TEST_CATEGORIES.find { it.id == id }
}

/**
 * Gets a test category by name.
 */
fun getTestCategory(name: String): Category? {
    return TEST_CATEGORIES.find { it.name.equals(name, ignoreCase = true) }
}
