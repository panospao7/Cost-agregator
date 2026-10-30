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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * S6c-F.2 (owner item 37(c), INV-5, INV-29, decision 46(b)): the notification subscription detector
 * never turns an installment-linked or plan-purchase expense into a subscription candidate. The
 * exclusion is one batch read through [RecurringInstallmentEligibility], applied after the size
 * check, with MIN_TRANSACTIONS re-checked on what remains.
 */
class NotificationSubscriptionDetectorInstallmentExclusionTest {

    companion object {
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        private val firstDate: Long = Instant.parse("2026-01-05T12:00:00Z").toEpochMilli()
        private const val DAY_MS = 86_400_000L
    }

    private val merchantNormalizer = mockk<MerchantNormalizer>()
    private val timeProvider = mockk<TimeProvider>()
    private val reader = mockk<InstallmentEntryLinkReader>()
    private lateinit var detector: NotificationSubscriptionDetector

    @Before
    fun setup() {
        // The detector falls back to the lower-cased merchant when the normalizer fails; that keeps
        // grouping deterministic without a canonical-merchant fixture.
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } throws IllegalStateException("normalizer unavailable")
        every { timeProvider.now() } returns firstDate + 3 * 30 * DAY_MS
        coEvery { reader.linkedExpenseIds() } returns emptySet()
        coEvery { reader.planPurchaseExpenseIds() } returns emptySet()
        detector = NotificationSubscriptionDetector(
            merchantNormalizer = merchantNormalizer,
            timeProvider = timeProvider,
            installmentEligibility = RecurringInstallmentEligibility(dagger.Lazy { reader })
        )
    }

    /** [count] monthly (30-day) 10.00 EUR purchases at one merchant, ids 1..count. */
    private fun monthly(count: Int): List<ExpenseWithCategory> = (1..count).map { index ->
        ExpenseWithCategory(
            expense = Expense(
                id = index.toLong(),
                amount = 10.0,
                currency = "EUR",
                merchant = "Loan Store",
                transactionType = TransactionType.PURCHASE,
                date = firstDate + (index - 1) * 30 * DAY_MS
            ),
            category = null
        )
    }

    @Test
    fun `control - three unlinked monthly expenses give one candidate`() = runBlocking {
        val result = detector.detectSubscriptions(monthly(3))

        assertEquals(1, result.size)
        assertEquals("monthly", result.single().detectedInterval)
        assertEquals(3, result.single().transactionCount)
    }

    @Test
    fun `three installment-linked monthly expenses give no candidate`() = runBlocking {
        coEvery { reader.linkedExpenseIds() } returns setOf(1L, 2L, 3L)

        assertTrue(detector.detectSubscriptions(monthly(3)).isEmpty())
    }

    @Test
    fun `plan purchase expenses give no candidate`() = runBlocking {
        coEvery { reader.planPurchaseExpenseIds() } returns setOf(1L, 2L, 3L)

        assertTrue(detector.detectSubscriptions(monthly(3)).isEmpty())
    }

    @Test
    fun `fewer than MIN_TRANSACTIONS after filtering gives empty before any grouping`() = runBlocking {
        coEvery { reader.linkedExpenseIds() } returns setOf(1L)

        val result = detector.detectSubscriptions(monthly(NotificationSubscriptionDetector.MIN_TRANSACTIONS))

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { merchantNormalizer.normalize(any(), any(), any()) }
    }

    @Test
    fun `unlinked expenses still form a candidate when a linked one is filtered out`() = runBlocking {
        coEvery { reader.linkedExpenseIds() } returns setOf(4L)

        val result = detector.detectSubscriptions(monthly(4))

        assertEquals(1, result.size)
        assertEquals("only the three unlinked expenses are counted", 3, result.single().transactionCount)
    }

    @Test
    fun `no reads happen when there are fewer than MIN_TRANSACTIONS or an empty list`() = runBlocking {
        assertTrue(detector.detectSubscriptions(emptyList()).isEmpty())
        assertTrue(detector.detectSubscriptions(monthly(NotificationSubscriptionDetector.MIN_TRANSACTIONS - 1)).isEmpty())

        coVerify(exactly = 0) { reader.linkedExpenseIds() }
        coVerify(exactly = 0) { reader.planPurchaseExpenseIds() }
    }

    @Test
    fun `link sets are read once per call and never per expense`() = runBlocking {
        detector.detectSubscriptions(monthly(5))

        coVerify(exactly = 1) { reader.linkedExpenseIds() }
        coVerify(exactly = 1) { reader.planPurchaseExpenseIds() }
        coVerify(exactly = 0) { reader.linkedEntryIdFor(any()) }
        coVerify(exactly = 0) { reader.isPlanPurchase(any()) }
    }

    @Test
    fun `CancellationException from the reader propagates`() = runBlocking {
        coEvery { reader.linkedExpenseIds() } throws CancellationException("cancelled")

        var thrown: Throwable? = null
        try {
            detector.detectSubscriptions(monthly(3))
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull("cancellation must not be swallowed into an empty result", thrown)
    }
}
