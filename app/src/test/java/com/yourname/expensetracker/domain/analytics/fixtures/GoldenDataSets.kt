package com.yourname.expensetracker.domain.analytics.fixtures

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Golden Test Dataset - Synthetic data for deterministic analytics verification.
 *
 * These datasets are designed to test specific scenarios in isolation,
 * ensuring that analytics engines produce predictable results.
 *
 * All dates are relative to March 2026 (simulating "current month" for tests).
 */
object GoldenDataSets {

    // Reference date: April 1, 2026 (used as "Now" for tests)
    val APRIL_1_2026: Long = LocalDate.of(2026, 4, 1)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    // Test categories for category-related tests (MUST be defined before createExpense calls)
    val testCategories = listOf(
        Category(id = 1L, name = "Food & Dining", icon = "🍽️", color = "#FF5733"),
        Category(id = 2L, name = "Groceries", icon = "🛒", color = "#33FF57"),
        Category(id = 3L, name = "Entertainment", icon = "🎬", color = "#3357FF"),
        Category(id = 4L, name = "Travel", icon = "✈️", color = "#F333FF"),
        Category(id = 5L, name = "Utilities", icon = "💡", color = "#FFD700")
    )

    // Date formatter (must be before createExpense calls)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // March 2026 - simple month with 3 purchases
    val simpleMonthPurchases = listOf(
        createExpense(
            id = 1L,
            date = "2026-03-05",
            amount = 10.0,
            effective = 10.0,
            merchant = "Coffee Shop",
            categoryId = 1L
        ),
        createExpense(
            id = 2L,
            date = "2026-03-15",
            amount = 20.0,
            effective = 20.0,
            merchant = "Grocery Store",
            categoryId = 2L
        ),
        createExpense(
            id = 3L,
            date = "2026-03-25",
            amount = 30.0,
            effective = 30.0,
            merchant = "Restaurant",
            categoryId = 3L
        )
    )

    // Single split transaction where user pays half (100 total, 50 effective)
    val splitTransaction = listOf(
        createExpense(
            id = 4L,
            date = "2026-03-10",
            amount = 100.0,
            effective = 50.0,
            merchant = "Group Dinner",
            categoryId = 3L,
            isSharedExpense = true,
            myShareAmount = 50.0
        )
    )

    // Transactions that should be excluded (isNotMine = true)
    val excludedTransactions = listOf(
        createExpense(
            id = 5L,
            date = "2026-03-12",
            amount = 500.0,
            effective = 0.0, // Excluded
            merchant = "Family Purchase",
            categoryId = 1L,
            isNotMine = true
        ),
        createExpense(
            id = 6L,
            date = "2026-03-20",
            amount = 100.0,
            effective = 100.0,
            merchant = "Personal Item",
            categoryId = 1L
        )
    )

    // Percentage-based split (70% of 1000 = 700 effective)
    val percentageSplit = listOf(
        createExpense(
            id = 7L,
            date = "2026-03-08",
            amount = 1000.0,
            effective = 700.0,
            merchant = "Group Travel",
            categoryId = 4L,
            isSharedExpense = true,
            mySharePercentage = 70
        )
    )

    // Empty dataset for edge case testing
    val emptyDataset: List<Expense> = emptyList()

    // Single transaction dataset
    val singleTransaction = listOf(
        createExpense(
            id = 8L,
            date = "2026-03-01",
            amount = 50.0,
            effective = 50.0,
            merchant = "Coffee",
            categoryId = 1L
        )
    )

    // February 2026 (previous month) for comparison testing
    val previousMonthPurchases = listOf(
        createExpense(
            id = 9L,
            date = "2026-02-05",
            amount = 15.0,
            effective = 15.0,
            merchant = "Coffee Shop",
            categoryId = 1L
        ),
        createExpense(
            id = 10L,
            date = "2026-02-15",
            amount = 25.0,
            effective = 25.0,
            merchant = "Grocery Store",
            categoryId = 2L
        )
    )

    // Combined dataset: March + February
    val twoMonthComparison: List<Expense>
        get() = simpleMonthPurchases + previousMonthPurchases

    // Multiple transactions from same merchant (recurring pattern)
    val recurringMerchantTransactions = listOf(
        createExpense(id = 11L, date = "2026-03-01", amount = 4.50, effective = 4.50, merchant = "Starbucks", categoryId = 1L),
        createExpense(id = 12L, date = "2026-03-05", amount = 5.00, effective = 5.00, merchant = "Starbucks", categoryId = 1L),
        createExpense(id = 13L, date = "2026-03-10", amount = 4.75, effective = 4.75, merchant = "Starbucks", categoryId = 1L),
        createExpense(id = 14L, date = "2026-03-15", amount = 4.50, effective = 4.50, merchant = "Starbucks", categoryId = 1L),
        createExpense(id = 15L, date = "2026-03-20", amount = 5.00, effective = 5.00, merchant = "Starbucks", categoryId = 1L)
    )

    // Mixed transaction types (only PURCHASE should count toward spending totals)
    val mixedTransactionTypes = listOf(
        createExpense(
            id = 16L,
            date = "2026-03-01",
            amount = 100.0,
            effective = 100.0,
            merchant = "Salary",
            categoryId = null,
            type = TransactionType.DEPOSIT
        ),
        createExpense(
            id = 17L,
            date = "2026-03-05",
            amount = 50.0,
            effective = 50.0,
            merchant = "ATM",
            categoryId = null,
            type = TransactionType.WITHDRAWAL
        ),
        createExpense(
            id = 18L,
            date = "2026-03-10",
            amount = 30.0,
            effective = 30.0,
            merchant = "Coffee Shop",
            categoryId = 1L,
            type = TransactionType.PURCHASE
        )
    )

    // Dataset with transactions across all days of week for DoW analysis
    val dayOfWeekSpread = listOf(
        // Monday (March 2, 2026)
        createExpense(id = 19L, date = "2026-03-02", amount = 10.0, effective = 10.0, merchant = "Monday Shop", categoryId = 1L),
        // Tuesday (March 3, 2026)
        createExpense(id = 20L, date = "2026-03-03", amount = 20.0, effective = 20.0, merchant = "Tuesday Store", categoryId = 2L),
        // Wednesday (March 4, 2026)
        createExpense(id = 21L, date = "2026-03-04", amount = 30.0, effective = 30.0, merchant = "Wednesday Mart", categoryId = 2L),
        // Thursday (March 5, 2026)
        createExpense(id = 22L, date = "2026-03-05", amount = 40.0, effective = 40.0, merchant = "Thursday Place", categoryId = 3L),
        // Friday (March 6, 2026)
        createExpense(id = 23L, date = "2026-03-06", amount = 50.0, effective = 50.0, merchant = "Friday Venue", categoryId = 3L),
        // Saturday (March 7, 2026)
        createExpense(id = 24L, date = "2026-03-07", amount = 60.0, effective = 60.0, merchant = "Saturday Spot", categoryId = 4L),
        // Sunday (March 8, 2026)
        createExpense(id = 25L, date = "2026-03-08", amount = 70.0, effective = 70.0, merchant = "Sunday Location", categoryId = 4L)
    )

    // Anomaly detection dataset - one transaction significantly higher than merchant average
    val anomalyScenario = listOf(
        createExpense(id = 26L, date = "2026-03-01", amount = 10.0, effective = 10.0, merchant = "Regular Cafe", categoryId = 1L),
        createExpense(id = 27L, date = "2026-03-05", amount = 12.0, effective = 12.0, merchant = "Regular Cafe", categoryId = 1L),
        createExpense(id = 28L, date = "2026-03-10", amount = 9.0, effective = 9.0, merchant = "Regular Cafe", categoryId = 1L),
        createExpense(id = 29L, date = "2026-03-15", amount = 11.0, effective = 11.0, merchant = "Regular Cafe", categoryId = 1L),
        // Anomaly: 5x average
        createExpense(id = 30L, date = "2026-03-20", amount = 150.0, effective = 150.0, merchant = "Regular Cafe", categoryId = 1L)
    )

    // Complex scenario: multiple edge cases combined
    val complexScenario = listOf(
        // Normal purchase
        createExpense(id = 31L, date = "2026-03-01", amount = 25.0, effective = 25.0, merchant = "Coffee", categoryId = 1L),
        // Split with fixed amount
        createExpense(id = 32L, date = "2026-03-05", amount = 80.0, effective = 40.0, merchant = "Dinner", categoryId = 3L, isSharedExpense = true, myShareAmount = 40.0),
        // Excluded (not mine)
        createExpense(id = 33L, date = "2026-03-10", amount = 200.0, effective = 0.0, merchant = "Gift", categoryId = 1L, isNotMine = true),
        // Split with percentage
        createExpense(id = 34L, date = "2026-03-15", amount = 300.0, effective = 150.0, merchant = "Trip", categoryId = 4L, isSharedExpense = true, mySharePercentage = 50),
        // Normal purchase
        createExpense(id = 35L, date = "2026-03-20", amount = 45.0, effective = 45.0, merchant = "Lunch", categoryId = 3L),
        // Deposit (should not count toward spending)
        createExpense(id = 36L, date = "2026-03-25", amount = 1000.0, effective = 1000.0, merchant = "Salary", categoryId = null, type = TransactionType.DEPOSIT)
    )

    /**
     * Creates an Expense object for test data.
     */
    private fun createExpense(
        id: Long = 0L,
        date: String,
        amount: Double,
        effective: Double = amount,
        merchant: String = "Test Merchant",
        categoryId: Long? = null,
        type: TransactionType = TransactionType.PURCHASE,
        isNotMine: Boolean = false,
        isSharedExpense: Boolean = false,
        myShareAmount: Double? = null,
        mySharePercentage: Int? = null
    ): Expense {
        val epochMillis = LocalDate.parse(date, dateFormatter)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        return Expense(
            id = id,
            amount = amount,
            merchant = merchant,
            transactionType = type,
            date = epochMillis,
            categoryId = categoryId,
            paymentMethod = PaymentMethod.CARD,
            isNotMine = isNotMine,
            isSharedExpense = isSharedExpense,
            myShareAmount = myShareAmount,
            mySharePercentage = mySharePercentage
        ).also {
            // Verify the effective amount calculation matches expected
            require(it.effectiveAmount == effective) {
                "Effective amount mismatch: expected $effective, got ${it.effectiveAmount}"
            }
        }
    }

    /**
     * Helper function exposed for custom test data creation.
     * Creates an Expense with specified parameters for analytics testing.
     */
    fun createExpense(
        date: String,
        amount: Double,
        effectiveAmount: Double = amount,
        isNotMine: Boolean = false,
        type: TransactionType = TransactionType.PURCHASE,
        category: String? = null,
        merchant: String = "Test Merchant",
        isSharedExpense: Boolean = false,
        myShareAmount: Double? = null,
        mySharePercentage: Int? = null
    ): Expense {
        val epochMillis = LocalDate.parse(date, dateFormatter)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val categoryId = category?.let { catName ->
            testCategories.find { it.name.equals(catName, ignoreCase = true) }?.id
        }

        return Expense(
            id = System.currentTimeMillis(), // Generate unique ID
            amount = amount,
            merchant = merchant,
            transactionType = type,
            date = epochMillis,
            categoryId = categoryId,
            paymentMethod = PaymentMethod.CARD,
            isNotMine = isNotMine,
            isSharedExpense = isSharedExpense,
            myShareAmount = myShareAmount,
            mySharePercentage = mySharePercentage
        )
    }
}
