package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.Instant

/** BL-08: a tie of interval counts must not reject a real monthly bill (engine call site). */
class RecurringExpenseEngineModeTieBreakTest {

    companion object {
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        private val now: Long = Instant.parse("2026-04-10T12:00:00Z").toEpochMilli()
        private const val DAY_MS = 86_400_000L
    }

    private lateinit var engine: RecurringExpenseEngine

    @Before
    fun setup() {
        val timeProvider = mockk<TimeProvider>()
        every { timeProvider.now() } returns now
        val reader = mockk<InstallmentEntryLinkReader>()
        coEvery { reader.linkedExpenseIds() } returns emptySet()
        coEvery { reader.planPurchaseExpenseIds() } returns emptySet()
        val recurringRepo = mockk<RecurringExpenseRepository>()
        coEvery { recurringRepo.getAll() } returns emptyList()
        engine = RecurringExpenseEngine(
            mockk<ExpenseRepository>(),
            recurringRepo,
            timeProvider,
            RecurringInstallmentEligibility(dagger.Lazy { reader })
        )
    }

    /** Purchases whose consecutive gaps are [intervals] days, the last one 5 days before [now]. */
    private fun expenses(intervals: List<Int>): List<Expense> {
        val offsets = intervals.runningFold(0) { acc, gap -> acc + gap }
        val last = offsets.last()
        return offsets.map { offset ->
            val date = now - 5 * DAY_MS - (last - offset) * DAY_MS
            Expense(
                id = 0,
                amount = 12.0,
                currency = "EUR",
                merchant = "Gym",
                merchantKey = "gym",
                transactionType = TransactionType.PURCHASE,
                date = date,
                categoryId = null,
                createdAt = date,
                paymentMethod = PaymentMethod.CARD,
                isManualEntry = false,
                isNotMine = false,
                isSharedExpense = false
            )
        }
    }

    @Test
    fun `intervals 24 30 31 are accepted as monthly with two thirds consistency`() = runTest {
        val patterns = engine.getPatterns(expenses(listOf(24, 30, 31)))

        assertEquals(1, patterns.size)
        assertEquals(RecurrenceFrequency.MONTHLY, patterns.single().frequency)
        assertEquals(2.0 / 3.0, patterns.single().confidence.toDouble(), 1e-6)
    }

    @Test
    fun `intervals 7 7 30 are unchanged and weekly`() = runTest {
        val patterns = engine.getPatterns(expenses(listOf(7, 7, 30)))

        assertEquals(1, patterns.size)
        assertEquals(RecurrenceFrequency.WEEKLY, patterns.single().frequency)
        assertEquals(2.0 / 3.0, patterns.single().confidence.toDouble(), 1e-6)
    }

    @Test
    fun `irregular intervals 3 19 47 are still rejected`() = runTest {
        assertTrue(engine.getPatterns(expenses(listOf(3, 19, 47))).isEmpty())
    }

    @Test
    fun `single interval means two expenses and gives no pattern`() = runTest {
        assertTrue(engine.getPatterns(expenses(listOf(30))).isEmpty())
    }
}
