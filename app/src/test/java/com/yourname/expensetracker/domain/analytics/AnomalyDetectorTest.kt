package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import io.mockk.mockk
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar

class AnomalyDetectorTest {

    private val detector = AnomalyDetector(mockk(relaxed = true))

    @Test
    fun `shared expenses use effective amount for anomaly detection`() {
        val now = 1745270400000L
        val month = monthPeriodFor(now)
        val category = AnalyticsCategoryRef(id = 1L, name = "Shared", icon = "group", color = "#FFFFFF")

        val expenses = listOf(
            sharedExpense(id = 1, amount = 500.0, myShareAmount = 10.0, date = now - 1_000),
            sharedExpense(id = 2, amount = 600.0, myShareAmount = 11.0, date = now - 2_000),
            sharedExpense(id = 3, amount = 700.0, myShareAmount = 12.0, date = now - 3_000),
            sharedExpense(id = 4, amount = 800.0, myShareAmount = 13.0, date = now - 4_000),
            // Raw amount is extreme, but user share remains in-range.
            sharedExpense(id = 5, amount = 5000.0, myShareAmount = 14.0, date = now - 5_000)
        )

        val anomalies = detector.detect(
            monthPeriod = month,
            categoryMap = mapOf(1L to category),
            allExpenses = expenses.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        assertTrue("No anomaly expected when effective amounts are in-range", anomalies.isEmpty())
    }

    @Test
    fun `anomaly_detector_false_positive_guard_on_tight_distribution`() {
        val month = monthPeriodFor(ms(2026, 4, 10))
        val category = AnalyticsCategoryRef(id = 7L, name = "Groceries", icon = "cart", color = "#00FF00")

        val amounts = listOf(100.0, 101.0, 99.5, 100.5, 99.8, 100.2, 101.1, 99.9)
        val expenses = amounts.mapIndexed { idx, amount ->
            Expense(
                id = (idx + 1).toLong(),
                amount = amount,
                merchant = "Grocer",
                transactionType = TransactionType.PURCHASE,
                date = ms(2026, 4, idx + 1),
                categoryId = 7L
            )
        }

        val anomalies = detector.detect(
            monthPeriod = month,
            categoryMap = mapOf(7L to category),
            allExpenses = expenses.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        assertTrue("Tight distribution should not produce false positives", anomalies.isEmpty())
    }

    @Test
    fun `anomaly_detector_false_negative_guard_for_extreme_contextual_outlier`() {
        val month = monthPeriodFor(ms(2026, 4, 15))
        val category = AnalyticsCategoryRef(id = 8L, name = "Transport", icon = "bus", color = "#0000FF")

        // Four Wednesday-morning transactions in same context, one extreme outlier.
        val base = listOf(12.0, 13.0, 14.0)
        val contextual = base.mapIndexed { idx, amount ->
            Expense(
                id = (100 + idx).toLong(),
                amount = amount,
                merchant = "Metro",
                transactionType = TransactionType.PURCHASE,
                date = msAt(2026, 4, 1 + (idx * 7), 9, 0), // consecutive Wednesdays, 09:00
                categoryId = 8L
            )
        } + Expense(
            id = 199L,
            amount = 120.0,
            merchant = "Metro",
            transactionType = TransactionType.PURCHASE,
            date = msAt(2026, 4, 22, 9, 0),
            categoryId = 8L
        ) + listOf(
            // add more in-category samples to ensure well-sampled category
            Expense(id = 200L, amount = 11.5, merchant = "Metro", transactionType = TransactionType.PURCHASE, date = msAt(2026, 4, 2, 14, 0), categoryId = 8L),
            Expense(id = 201L, amount = 12.5, merchant = "Metro", transactionType = TransactionType.PURCHASE, date = msAt(2026, 4, 3, 18, 0), categoryId = 8L)
        )

        val anomalies = detector.detect(
            monthPeriod = month,
            categoryMap = mapOf(8L to category),
            allExpenses = contextual.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        assertTrue("Extreme contextual outlier should be detected", anomalies.any { it.expense.id == 199L })
        assertEquals(1, anomalies.count { it.expense.id == 199L })
    }

    @Test
    fun `zero dispersion baseline still flags obvious spike`() {
        val month = monthPeriodFor(ms(2026, 4, 20))
        val category = AnalyticsCategoryRef(id = 9L, name = "Food", icon = "fork", color = "#FFAA00")

        val amounts = listOf(10.0, 10.0, 10.0, 10.0, 100.0)
        val expenses = amounts.mapIndexed { idx, amount ->
            Expense(
                id = (idx + 1).toLong(),
                amount = amount,
                merchant = "Flat Baseline",
                transactionType = TransactionType.PURCHASE,
                date = ms(2026, 4, idx + 1),
                categoryId = 9L
            )
        }

        val anomalies = detector.detect(
            monthPeriod = month,
            categoryMap = mapOf(9L to category),
            allExpenses = expenses.map { it.toSnapshot() },
            displayCurrency = "EUR"
        )

        assertTrue("Flat baseline spike should be detected", anomalies.any { it.expense.id == 5L })
    }

    private fun sharedExpense(id: Long, amount: Double, myShareAmount: Double, date: Long): Expense =
        Expense(
            id = id,
            amount = amount,
            currency = "EUR",
            merchant = "Shared Merchant",
            transactionType = TransactionType.PURCHASE,
            date = date,
            categoryId = 1L,
            isSharedExpense = true,
            myShareAmount = myShareAmount
        )

    private fun monthPeriodFor(now: Long): MonthPeriod {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        return MonthPeriod(
            year = Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.YEAR),
            month = Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.MONTH),
            startMs = start,
            endMs = end
        )
    }

    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun msAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDate.of(year, month, day)
            .atTime(LocalTime.of(hour, minute))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun Expense.toSnapshot(): ExpenseSnapshot = ExpenseSnapshot(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        currency = currency,
        merchant = merchant,
        merchantKey = merchantKey,
        transactionType = when (transactionType) {
            TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        },
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        transferDirection = when (transferDirection) {
            com.yourname.expensetracker.data.database.entity.TransferDirection.INCOMING -> DomainTransferDirection.INCOMING
            com.yourname.expensetracker.data.database.entity.TransferDirection.OUTGOING -> DomainTransferDirection.OUTGOING
            null -> null
        },
        notes = notes
    )
}