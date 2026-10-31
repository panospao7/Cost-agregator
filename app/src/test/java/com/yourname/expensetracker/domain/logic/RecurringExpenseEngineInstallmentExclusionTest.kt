package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * S6c-3 (INV-29, detection half): the recurring pattern detector never treats an expense that pays an
 * installment entry, or that is a plan purchase record, as evidence of a recurring pattern.
 *
 * Pure JVM. The link reader is an in-memory fake that counts its calls, so "one batch read per call"
 * is asserted directly. The rule lives in [RecurringExpenseEngine.detectPatterns]; the pure detector
 * [RecurringExpenseEngine.detectPatternsFromSnapshots] is the comparison oracle for "no plans".
 */
class RecurringExpenseEngineInstallmentExclusionTest {

    private class FakeReader(
        private val linkedIds: Set<Long> = emptySet(),
        private val planPurchaseIds: Set<Long> = emptySet(),
        private val failure: Throwable? = null
    ) : InstallmentEntryLinkReader {
        var linkedSetCalls = 0
        var planPurchaseSetCalls = 0
        var perExpenseCalls = 0

        override suspend fun linkedEntryIdFor(expenseId: Long): Long? {
            perExpenseCalls++
            return null
        }

        override suspend fun isPlanPurchase(expenseId: Long): Boolean {
            perExpenseCalls++
            return false
        }

        override suspend fun linkedExpenseIds(): Set<Long> {
            linkedSetCalls++
            failure?.let { throw it }
            return linkedIds
        }

        override suspend fun planPurchaseExpenseIds(): Set<Long> {
            planPurchaseSetCalls++
            failure?.let { throw it }
            return planPurchaseIds
        }
    }

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var timeProvider: TimeProvider
    private val now = timestampOf(2026, Calendar.APRIL, 15)

    @Before
    fun setup() {
        expenseRepository = mockk()
        recurringExpenseRepository = mockk()
        timeProvider = mockk()
        coEvery { recurringExpenseRepository.getAll() } returns emptyList()
        every { timeProvider.now() } returns now
    }

    private fun engineWith(reader: FakeReader) = RecurringExpenseEngine(
        expenseRepository,
        recurringExpenseRepository,
        timeProvider,
        RecurringInstallmentEligibility(dagger.Lazy { reader })
    )

    private fun timestampOf(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun snapshot(
        id: Long,
        merchant: String,
        amount: Double,
        date: Long
    ) = ExpenseSnapshot(
        id = id,
        amount = amount,
        effectiveAmount = amount,
        currency = "EUR",
        merchant = merchant,
        merchantKey = merchant.lowercase(),
        transactionType = DomainTransactionType.PURCHASE,
        date = date,
        categoryId = null,
        isNotMine = false,
        transferDirection = null,
        notes = null
    )

    /** 12 monthly 50.00 purchases, ids 1..12, 2025-05-01 .. 2026-04-01. */
    private fun twelveMonthlyInstallmentShapedSeries(): List<ExpenseSnapshot> =
        (0 until 12).map { index ->
            val monthIndex = Calendar.MAY + index
            snapshot(
                id = (index + 1).toLong(),
                merchant = "Loan Store",
                amount = 50.00,
                date = timestampOf(2025 + monthIndex / 12, monthIndex % 12, 1)
            )
        }

    private val seriesIds: Set<Long> = (1L..12L).toSet()

    @Test
    fun installmentLinkedSeriesProducesNoPattern() = runTest {
        val reader = FakeReader(linkedIds = seriesIds)

        val patterns = engineWith(reader).detectPatterns(twelveMonthlyInstallmentShapedSeries())

        assertTrue("12 x 50.00 installment payments must not be a recurring pattern", patterns.isEmpty())
    }

    @Test
    fun theSameSeriesUnlinkedIsStillDetected() = runTest {
        val reader = FakeReader()

        val patterns = engineWith(reader).detectPatterns(twelveMonthlyInstallmentShapedSeries())

        assertEquals(1, patterns.size)
        assertEquals(RecurrenceFrequency.MONTHLY, patterns.single().frequency)
        assertEquals(50.0, patterns.single().averageAmount, 0.0001)
    }

    @Test
    fun planPurchaseExpensesAreExcluded() = runTest {
        val reader = FakeReader(planPurchaseIds = seriesIds)

        val patterns = engineWith(reader).detectPatterns(twelveMonthlyInstallmentShapedSeries())

        assertTrue("plan purchase records are never recurring evidence", patterns.isEmpty())
    }

    @Test
    fun onlyTheLinkedExpenseLeavesTheSeries() = runTest {
        // Five monthly purchases (Dec..Apr); the April one pays an installment entry.
        val series = (0 until 5).map { index ->
            val monthIndex = Calendar.DECEMBER + index
            snapshot(
                id = (index + 1).toLong(),
                merchant = "Gym",
                amount = 30.0,
                date = timestampOf(2025 + monthIndex / 12, monthIndex % 12, 1)
            )
        }
        val linkedDate = series.last().date
        val reader = FakeReader(linkedIds = setOf(5L))

        val patterns = engineWith(reader).detectPatterns(series)

        assertEquals(1, patterns.size)
        assertFalse(
            "the linked expense is not part of the evidence",
            patterns.single().previousDates.contains(linkedDate)
        )
        assertEquals(4, patterns.single().previousDates.size)
    }

    @Test
    fun withNoPlansTheOutputIsIdenticalToThePureDetector() = runTest {
        val monthly = (0 until 4).map { index ->
            val monthIndex = Calendar.DECEMBER + index
            snapshot(
                id = (index + 1).toLong(),
                merchant = "Netflix",
                amount = 15.99,
                date = timestampOf(2025 + monthIndex / 12, monthIndex % 12, 1)
            )
        }
        val weekly = (0 until 6).map { index ->
            snapshot(
                id = 100L + index,
                merchant = "Coffee Club",
                amount = 20.0,
                date = timestampOf(2026, Calendar.MARCH, 4 + 7 * index)
            )
        }
        val noise = listOf(
            snapshot(id = 200L, merchant = "Kiosk", amount = 3.0, date = timestampOf(2026, Calendar.FEBRUARY, 2)),
            snapshot(id = 201L, merchant = "Kiosk", amount = 90.0, date = timestampOf(2026, Calendar.MARCH, 27))
        )
        val all = monthly + weekly + noise
        val engine = engineWith(FakeReader())

        val viaEntryPoint = engine.detectPatterns(all, setOf("someotherkey"))
        val viaPureDetector = engine.detectPatternsFromSnapshots(all, setOf("someotherkey"))

        assertTrue("fixture must actually detect patterns", viaPureDetector.size >= 2)
        assertEquals(viaPureDetector, viaEntryPoint)
    }

    @Test
    fun oneBatchReadPerCallAndNeverPerExpense() = runTest {
        val reader = FakeReader(linkedIds = setOf(1L))
        val engine = engineWith(reader)

        engine.detectPatterns(twelveMonthlyInstallmentShapedSeries())

        assertEquals(1, reader.linkedSetCalls)
        assertEquals(1, reader.planPurchaseSetCalls)
        assertEquals("no per-expense reads", 0, reader.perExpenseCalls)

        engine.detectPatterns(twelveMonthlyInstallmentShapedSeries())

        assertEquals("each call reads the sets once more, not once per expense", 2, reader.linkedSetCalls)
        assertEquals(2, reader.planPurchaseSetCalls)
        assertEquals(0, reader.perExpenseCalls)
    }

    @Test
    fun nothingIsReadForAnEmptyInput() = runTest {
        val reader = FakeReader()

        val patterns = engineWith(reader).detectPatterns(emptyList())

        assertTrue(patterns.isEmpty())
        assertEquals(0, reader.linkedSetCalls)
        assertEquals(0, reader.planPurchaseSetCalls)
    }

    @Test
    fun getPatternsFromSnapshotsRoutesThroughTheExclusion() = runTest {
        val reader = FakeReader(linkedIds = seriesIds)

        val patterns = engineWith(reader).getPatternsFromSnapshots(twelveMonthlyInstallmentShapedSeries())

        assertTrue(patterns.isEmpty())
        assertEquals(1, reader.linkedSetCalls)
        assertEquals(1, reader.planPurchaseSetCalls)
    }

    @Test
    fun getPatternsReadsTheWindowThenAppliesTheExclusionOnce() = runTest {
        val reader = FakeReader(linkedIds = seriesIds)
        coEvery { expenseRepository.getExpenseSnapshotsSince(any()) } returns twelveMonthlyInstallmentShapedSeries()

        val patterns = engineWith(reader).getPatterns()

        assertTrue(patterns.isEmpty())
        assertEquals(1, reader.linkedSetCalls)
        assertEquals(1, reader.planPurchaseSetCalls)
    }

    @Test
    fun getPatternsForExpenseRowsRoutesThroughTheExclusion() = runTest {
        val reader = FakeReader(planPurchaseIds = seriesIds)
        val rows = twelveMonthlyInstallmentShapedSeries().map { snapshot ->
            Expense(
                id = snapshot.id,
                amount = snapshot.amount,
                currency = snapshot.currency,
                merchant = snapshot.merchant,
                merchantKey = snapshot.merchantKey,
                transactionType = TransactionType.PURCHASE,
                date = snapshot.date,
                createdAt = snapshot.date,
                source = "test"
            )
        }

        val patterns = engineWith(reader).getPatterns(rows)

        assertTrue(patterns.isEmpty())
        assertEquals(1, reader.planPurchaseSetCalls)
    }

    @Test
    fun cancellationFromTheReaderIsRethrown() = runTest {
        val reader = FakeReader(failure = CancellationException("cancelled"))

        try {
            engineWith(reader).detectPatterns(twelveMonthlyInstallmentShapedSeries())
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }
}
