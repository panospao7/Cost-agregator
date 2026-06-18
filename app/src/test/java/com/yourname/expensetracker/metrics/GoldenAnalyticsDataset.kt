package com.yourname.expensetracker.metrics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class GoldenPurchaseExpectation(
    val total: Double,
    val count: Int,
    val dailyAverage: Double
)

data class GoldenCashFlowExpectation(
    val income: Double,
    val expense: Double,
    val net: Double
)

data class GoldenCategoryExpectation(
    val category: String,
    val total: Double,
    val percent: Double
)

data class GoldenScenario(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val transactions: List<Expense>,
    val purchaseExpectation: GoldenPurchaseExpectation? = null,
    val cashFlowExpectation: GoldenCashFlowExpectation? = null,
    val categoryExpectation: List<GoldenCategoryExpectation> = emptyList()
)

object GoldenAnalyticsDataset {

    // Deterministic test baseline: "Now" = 2026-04-01T00:00:00Z
    val nowMs: Long = ms(2026, 4, 1)

    val scenarios: List<GoldenScenario> = listOf(
        scenario1BasicMonthlyTotal(),
        scenario2SplitEffectiveAmount(),
        scenario3MixedTransactionTypes(),
        scenario4HalfOpenDateBoundaries(),
        scenario5EmptyPeriod(),
        scenario6SharedExpenseFiltering(),
        scenario7CategoryBreakdown()
    )

    fun byId(id: String): GoldenScenario = scenarios.first { it.id == id }

    private fun scenario1BasicMonthlyTotal(): GoldenScenario {
        val start = ms(2026, 3, 1)
        val end = ms(2026, 3, 31)
        return GoldenScenario(
            id = "S1_BASIC_MONTHLY_TOTAL",
            startMs = start,
            endMs = end,
            transactions = listOf(
                purchase(10.0, "Food", ms(2026, 3, 5)),
                purchase(20.0, "Food", ms(2026, 3, 15)),
                purchase(30.0, "Food", ms(2026, 3, 25))
            ),
            purchaseExpectation = GoldenPurchaseExpectation(
                total = 60.0,
                count = 3,
                dailyAverage = 2.0 // 60 / 30 days in [Mar 1, Mar 31)
            )
        )
    }

    private fun scenario2SplitEffectiveAmount(): GoldenScenario {
        val start = ms(2026, 3, 1)
        val end = ms(2026, 4, 1)
        return GoldenScenario(
            id = "S2_SPLIT_EFFECTIVE_AMOUNT",
            startMs = start,
            endMs = end,
            transactions = listOf(
                purchase(
                    amount = 100.0,
                    category = "Shared",
                    date = ms(2026, 3, 10),
                    isNotMine = false,
                    isSharedExpense = true,
                    myShareAmount = 50.0
                )
            ),
            purchaseExpectation = GoldenPurchaseExpectation(
                total = 50.0,
                count = 1,
                dailyAverage = 50.0 / 31.0
            )
        )
    }

    private fun scenario3MixedTransactionTypes(): GoldenScenario {
        val start = ms(2026, 3, 1)
        val end = ms(2026, 4, 1)
        return GoldenScenario(
            id = "S3_MIXED_TRANSACTION_TYPES",
            startMs = start,
            endMs = end,
            transactions = listOf(
                purchase(50.0, "Food", ms(2026, 3, 8)),
                deposit(100.0, ms(2026, 3, 9)),
                transfer(20.0, ms(2026, 3, 10))
            ),
            purchaseExpectation = GoldenPurchaseExpectation(
                total = 50.0,
                count = 1,
                dailyAverage = 50.0 / 31.0
            ),
            cashFlowExpectation = GoldenCashFlowExpectation(
                income = 100.0,
                expense = 50.0,
                net = 50.0
            )
        )
    }

    private fun scenario4HalfOpenDateBoundaries(): GoldenScenario {
        val start = ms(2026, 3, 1)
        val end = ms(2026, 4, 1)
        return GoldenScenario(
            id = "S4_HALF_OPEN_BOUNDARIES",
            startMs = start,
            endMs = end,
            transactions = listOf(
                purchase(10.0, "Food", start), // included
                purchase(20.0, "Food", end)    // excluded
            ),
            purchaseExpectation = GoldenPurchaseExpectation(
                total = 10.0,
                count = 1,
                dailyAverage = 10.0 / 31.0
            )
        )
    }

    private fun scenario5EmptyPeriod(): GoldenScenario {
        val start = ms(2026, 2, 1)
        val end = ms(2026, 3, 1)
        return GoldenScenario(
            id = "S5_EMPTY_PERIOD",
            startMs = start,
            endMs = end,
            transactions = emptyList(),
            purchaseExpectation = GoldenPurchaseExpectation(
                total = 0.0,
                count = 0,
                dailyAverage = 0.0
            ),
            cashFlowExpectation = GoldenCashFlowExpectation(
                income = 0.0,
                expense = 0.0,
                net = 0.0
            )
        )
    }

    private fun scenario6SharedExpenseFiltering(): GoldenScenario {
        val start = ms(2026, 3, 1)
        val end = ms(2026, 4, 1)
        return GoldenScenario(
            id = "S6_SHARED_FILTERING",
            startMs = start,
            endMs = end,
            transactions = listOf(
                purchase(40.0, "Food", ms(2026, 3, 3), isNotMine = true),
                purchase(60.0, "Food", ms(2026, 3, 4), isNotMine = false)
            ),
            purchaseExpectation = GoldenPurchaseExpectation(
                total = 60.0,
                count = 1,
                dailyAverage = 60.0 / 31.0
            )
        )
    }

    private fun scenario7CategoryBreakdown(): GoldenScenario {
        val start = ms(2026, 3, 1)
        val end = ms(2026, 4, 1)
        return GoldenScenario(
            id = "S7_CATEGORY_BREAKDOWN",
            startMs = start,
            endMs = end,
            transactions = listOf(
                purchase(30.0, "Food", ms(2026, 3, 11)),
                purchase(20.0, "Transport", ms(2026, 3, 12)),
                purchase(50.0, "Food", ms(2026, 3, 13)),
                // Included so percentages match requested matrix.
                purchase(20.0, "Other", ms(2026, 3, 14))
            ),
            categoryExpectation = listOf(
                GoldenCategoryExpectation("Food", 80.0, 66.6),
                GoldenCategoryExpectation("Transport", 20.0, 16.6),
                GoldenCategoryExpectation("Other", 20.0, 16.6)
            )
        )
    }

    fun periodDays(startMs: Long, endMs: Long): Long {
        val start = LocalDate.ofEpochDay(startMs / 86_400_000L)
        val end = LocalDate.ofEpochDay(endMs / 86_400_000L)
        return ChronoUnit.DAYS.between(start, end)
    }

    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun purchase(
        amount: Double,
        category: String,
        date: Long,
        isNotMine: Boolean = false,
        isSharedExpense: Boolean = false,
        myShareAmount: Double? = null
    ): Expense = Expense(
        amount = amount,
        merchant = category,
        transactionType = TransactionType.PURCHASE,
        date = date,
        isNotMine = isNotMine,
        isSharedExpense = isSharedExpense,
        myShareAmount = myShareAmount
    )

    private fun deposit(amount: Double, date: Long): Expense = Expense(
        amount = amount,
        merchant = "Income",
        transactionType = TransactionType.DEPOSIT,
        date = date
    )

    private fun transfer(amount: Double, date: Long): Expense = Expense(
        amount = amount,
        merchant = "Transfer",
        transactionType = TransactionType.TRANSFER,
        date = date
    )
}
