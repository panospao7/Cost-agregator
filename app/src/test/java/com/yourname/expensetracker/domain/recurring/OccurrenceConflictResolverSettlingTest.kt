package com.yourname.expensetracker.domain.recurring

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/**
 * FC-PAID-UNLINKED (fix-1.0.4 plan, S1): [OccurrenceConflictResolver.findSettlingExpenses] decides
 * which open occurrences an unlinked purchase already pays. Window is [due - 7 days, due] inside the
 * due month (WEEKLY/BIWEEKLY: 3 days), merchant + same currency + within 10%, one purchase per
 * occurrence, nearest date first. Synthetic data only; pure JVM with an in-memory link reader.
 */
class OccurrenceConflictResolverSettlingTest {

    private class EmptyReader : InstallmentEntryLinkReader {
        override suspend fun linkedEntryIdFor(expenseId: Long): Long? = null
        override suspend fun isPlanPurchase(expenseId: Long): Boolean = false
        override suspend fun linkedExpenseIds(): Set<Long> = emptySet()
        override suspend fun planPurchaseExpenseIds(): Set<Long> = emptySet()
    }

    private val resolver = OccurrenceConflictResolver(RecurringInstallmentEligibility(dagger.Lazy { EmptyReader() }))

    private fun startOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun noonOf(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun occurrence(
        key: String,
        due: Long,
        amount: Double = 400.0,
        currency: String = "EUR",
        frequency: String = "MONTHLY",
        merchant: String = "Landlord"
    ) = RecurringOccurrence(
        sourceType = "RECURRING_RULE",
        sourceId = 1L,
        occurrenceKey = key,
        dueDate = due,
        status = "PLANNED",
        expectedAmount = amount,
        expectedCurrency = currency,
        frequency = frequency,
        merchant = merchant
    )

    private fun purchase(
        id: Long,
        date: Long,
        amount: Double = 400.0,
        currency: String = "EUR",
        merchant: String = "Landlord",
        type: TransactionType = TransactionType.PURCHASE,
        reason: String? = null
    ) = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = merchant,
        transactionType = type,
        date = date,
        createdAt = date,
        source = "test",
        spendingExclusionReason = reason
    )

    private val due27 = startOfDay(2026, 6, 27)
    private val rent = occurrence("rent-jun-27", due27)

    private suspend fun settledKeys(open: List<RecurringOccurrence>, vararg expenses: Expense): Set<String> =
        resolver.findSettlingExpenses(open, expenses.toList()).keys

    @Test
    fun rent400PaidOn25thDueOn27thIsSettled() = runTest(timeout = 60.seconds) {
        val result = resolver.findSettlingExpenses(listOf(rent), listOf(purchase(1L, noonOf(2026, 6, 25))))
        assertEquals(mapOf("rent-jun-27" to 1L), result)
    }

    @Test
    fun paidExactlySevenDaysBeforeIsSettled() = runTest(timeout = 60.seconds) {
        assertEquals(setOf("rent-jun-27"), settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 20))))
    }

    @Test
    fun paidEightDaysBeforeIsNotSettled() = runTest(timeout = 60.seconds) {
        assertTrue(settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 19))).isEmpty())
    }

    @Test
    fun paidAfterTheDueDateIsNotSettled() = runTest(timeout = 60.seconds) {
        assertTrue(settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 28))).isEmpty())
    }

    @Test
    fun paidOnTheDueDayStillSettles() = runTest(timeout = 60.seconds) {
        assertEquals(setOf("rent-jun-27"), settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 27))))
    }

    @Test
    fun coffeeOf4FromTheSameMerchantIsNotSettled() = runTest(timeout = 60.seconds) {
        assertTrue(settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 25), amount = 4.0)).isEmpty())
    }

    @Test
    fun amountSevenAndAHalfPercentAboveIsSettled() = runTest(timeout = 60.seconds) {
        assertEquals(setOf("rent-jun-27"), settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 25), amount = 430.0)))
    }

    @Test
    fun amountTwelveAndAHalfPercentAboveIsNotSettled() = runTest(timeout = 60.seconds) {
        assertTrue(settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 25), amount = 450.0)).isEmpty())
    }

    @Test
    fun otherMerchantIsNotSettled() = runTest(timeout = 60.seconds) {
        assertTrue(settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 25), merchant = "Gym Club")).isEmpty())
    }

    @Test
    fun otherCurrencyWithTheSameNumberIsNotSettled() = runTest(timeout = 60.seconds) {
        assertTrue(settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 25), currency = "USD")).isEmpty())
    }

    @Test
    fun previousCalendarMonthInsideSevenDaysIsNotSettled() = runTest(timeout = 60.seconds) {
        val julyRent = occurrence("rent-jul-03", startOfDay(2026, 7, 3))
        assertTrue(settledKeys(listOf(julyRent), purchase(1L, noonOf(2026, 6, 28))).isEmpty())
    }

    @Test
    fun depositTypeIsNotSettled() = runTest(timeout = 60.seconds) {
        assertTrue(
            settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 25), type = TransactionType.DEPOSIT)).isEmpty()
        )
    }

    @Test
    fun transferTypeIsNotSettled() = runTest(timeout = 60.seconds) {
        assertTrue(
            settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 25), type = TransactionType.TRANSFER)).isEmpty()
        )
    }

    @Test
    fun installmentFlaggedRowIsNotSettled() = runTest(timeout = 60.seconds) {
        assertTrue(
            settledKeys(
                listOf(rent),
                purchase(1L, noonOf(2026, 6, 25), reason = "INSTALLMENT_PURCHASE")
            ).isEmpty()
        )
    }

    @Test
    fun anOccurrenceThatIsNotPlannedIsNeverSettled() = runTest(timeout = 60.seconds) {
        val paid = rent.copy(status = "PAID", linkedExpenseId = 99L)
        val skipped = occurrence("rent-skipped", due27).copy(status = "SKIPPED")
        assertTrue(settledKeys(listOf(paid, skipped), purchase(1L, noonOf(2026, 6, 25))).isEmpty())
    }

    @Test
    fun twoBillsOneMerchantOnePaymentOnlyTheNearestSettles() = runTest(timeout = 60.seconds) {
        val billA = occurrence("bill-24", startOfDay(2026, 6, 24))
        val billB = occurrence("bill-27", due27)
        // Paid on the 23rd: 1 day before bill-24 and 4 days before bill-27.
        val result = resolver.findSettlingExpenses(listOf(billB, billA), listOf(purchase(1L, noonOf(2026, 6, 23))))
        assertEquals(mapOf("bill-24" to 1L), result)
    }

    @Test
    fun onePurchaseNeverSettlesTwoOccurrencesOnTheSameDueDate() = runTest(timeout = 60.seconds) {
        val first = occurrence("twin-a", due27)
        val second = occurrence("twin-b", due27)
        val forward = resolver.findSettlingExpenses(listOf(first, second), listOf(purchase(1L, noonOf(2026, 6, 25))))
        val reversed = resolver.findSettlingExpenses(listOf(second, first), listOf(purchase(1L, noonOf(2026, 6, 25))))
        assertEquals(1, forward.size)
        assertEquals("the pick must not depend on input order", forward, reversed)
    }

    @Test
    fun twoPurchasesAndTwoOccurrencesPairOneToOne() = runTest(timeout = 60.seconds) {
        val billA = occurrence("bill-24", startOfDay(2026, 6, 24))
        val billB = occurrence("bill-27", due27)
        val result = resolver.findSettlingExpenses(
            listOf(billA, billB),
            listOf(purchase(1L, noonOf(2026, 6, 23)), purchase(2L, noonOf(2026, 6, 26)))
        )
        assertEquals(mapOf("bill-24" to 1L, "bill-27" to 2L), result)
    }

    @Test
    fun twoPurchasesForOneOccurrenceTheNearerDateWins() = runTest(timeout = 60.seconds) {
        val result = resolver.findSettlingExpenses(
            listOf(rent),
            listOf(purchase(1L, noonOf(2026, 6, 21)), purchase(2L, noonOf(2026, 6, 26)))
        )
        assertEquals(mapOf("rent-jun-27" to 2L), result)
    }

    @Test
    fun weeklyThreeDaysBeforeIsSettledFourIsNot() = runTest(timeout = 60.seconds) {
        val weekly = occurrence("weekly-27", due27, amount = 100.0, frequency = "WEEKLY", merchant = "Gym Club")
        assertEquals(
            setOf("weekly-27"),
            settledKeys(listOf(weekly), purchase(1L, noonOf(2026, 6, 24), amount = 100.0, merchant = "Gym Club"))
        )
        assertTrue(
            settledKeys(listOf(weekly), purchase(2L, noonOf(2026, 6, 23), amount = 100.0, merchant = "Gym Club")).isEmpty()
        )
    }

    @Test
    fun biweeklyThreeDaysBeforeIsSettledFourIsNot() = runTest(timeout = 60.seconds) {
        val biweekly = occurrence("biweekly-27", due27, amount = 100.0, frequency = "BIWEEKLY", merchant = "Gym Club")
        assertEquals(
            setOf("biweekly-27"),
            settledKeys(listOf(biweekly), purchase(1L, noonOf(2026, 6, 24), amount = 100.0, merchant = "Gym Club"))
        )
        assertTrue(
            settledKeys(listOf(biweekly), purchase(2L, noonOf(2026, 6, 23), amount = 100.0, merchant = "Gym Club")).isEmpty()
        )
    }

    @Test
    fun monthlyFourDaysBeforeIsSettled() = runTest(timeout = 60.seconds) {
        assertEquals(setOf("rent-jun-27"), settledKeys(listOf(rent), purchase(1L, noonOf(2026, 6, 23))))
    }

    @Test
    fun emptyInputsSettleNothing() = runTest(timeout = 60.seconds) {
        assertTrue(resolver.findSettlingExpenses(emptyList(), listOf(purchase(1L, noonOf(2026, 6, 25)))).isEmpty())
        assertTrue(resolver.findSettlingExpenses(listOf(rent), emptyList()).isEmpty())
    }
}
