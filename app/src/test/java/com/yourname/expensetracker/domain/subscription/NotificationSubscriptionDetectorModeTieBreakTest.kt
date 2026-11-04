package com.yourname.expensetracker.domain.subscription

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.util.PinnedUtcTimeZoneRule
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.Instant

/** BL-08: a tie of interval counts must not under-score a real monthly bill (detector call site). */
class NotificationSubscriptionDetectorModeTieBreakTest {

    companion object {
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        private val now: Long = Instant.parse("2026-04-10T12:00:00Z").toEpochMilli()
        private const val DAY_MS = 86_400_000L
    }

    private lateinit var detector: NotificationSubscriptionDetector

    @Before
    fun setup() {
        val merchantNormalizer = mockk<MerchantNormalizer>()
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } throws IllegalStateException("normalizer unavailable")
        val timeProvider = mockk<TimeProvider>()
        every { timeProvider.now() } returns now
        val reader = mockk<InstallmentEntryLinkReader>()
        coEvery { reader.linkedExpenseIds() } returns emptySet()
        coEvery { reader.planPurchaseExpenseIds() } returns emptySet()
        detector = NotificationSubscriptionDetector(
            merchantNormalizer = merchantNormalizer,
            timeProvider = timeProvider,
            installmentEligibility = RecurringInstallmentEligibility(dagger.Lazy { reader })
        )
    }

    /** Purchases whose consecutive gaps are [intervals] days; the last one is dated [now] (recentness 1.0). */
    private fun transactions(intervals: List<Int>): List<ExpenseWithCategory> {
        val offsets = intervals.runningFold(0) { acc, gap -> acc + gap }
        val last = offsets.last()
        return offsets.mapIndexed { index, offset ->
            ExpenseWithCategory(
                expense = Expense(
                    id = (index + 1).toLong(),
                    amount = 10.0,
                    currency = "EUR",
                    merchant = "Gym",
                    transactionType = TransactionType.PURCHASE,
                    date = now - (last - offset) * DAY_MS
                ),
                category = null
            )
        }
    }

    @Test
    fun `intervals 24 30 31 are monthly with two thirds interval consistency`() = runBlocking {
        val result = detector.detectSubscriptions(transactions(listOf(24, 30, 31)))

        assertEquals(1, result.size)
        assertEquals("monthly", result.single().detectedInterval)
        // 0.4 * 2/3 + 0.4 * 1.0 + 0.2 * 1.0 (the first-entry rule would give 1/3 and 0.7333).
        assertEquals(0.4 * (2.0 / 3.0) + 0.4 + 0.2, result.single().confidence, 1e-9)
    }

    @Test
    fun `intervals 7 7 30 are unchanged and weekly`() = runBlocking {
        val result = detector.detectSubscriptions(transactions(listOf(7, 7, 30)))

        assertEquals(1, result.size)
        assertEquals("weekly", result.single().detectedInterval)
        assertEquals(0.4 * (2.0 / 3.0) + 0.4 + 0.2, result.single().confidence, 1e-9)
    }

    @Test
    fun `irregular intervals 3 19 47 are still rejected`() = runBlocking {
        assertTrue(detector.detectSubscriptions(transactions(listOf(3, 19, 47))).isEmpty())
    }

    @Test
    fun `single interval means two transactions and gives no candidate`() = runBlocking {
        assertTrue(detector.detectSubscriptions(transactions(listOf(30))).isEmpty())
    }
}
