package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
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
 * S6c-F.2 (owner item 37(a), INV-5, INV-29, decision 46(b)): InsightsEngine.findAnomalies drops, by
 * expense id and across BOTH anomaly paths, every expense the single owner
 * [RecurringInstallmentEligibility] refuses (installment-linked or plan purchase). Baselines are
 * unchanged, the link sets are read once per call, and a reader failure is fail-closed.
 */
class InsightsEngineInstallmentAnomalySuppressionTest {

    companion object {
        @JvmField
        @ClassRule
        val utcZone = PinnedUtcTimeZoneRule()

        private val fixedNow: Long = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
        private const val MERCHANT_KEY = "acme"
        private const val SPIKE_ID = 10L
        private const val SAME_MERCHANT_OTHER_ID = 11L
    }

    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val recurringEngine = mockk<com.yourname.expensetracker.domain.logic.RecurringExpenseEngine>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val anomalyDetector = mockk<AnomalyDetector>(relaxed = true)
    private val reader = mockk<InstallmentEntryLinkReader>()

    @Before
    fun setup() {
        every { timeProvider.now() } returns fixedNow
        coEvery { recurringEngine.getPatternsFromSnapshots(any()) } returns emptyList()
        coEvery { recurringEngine.getPatterns(any()) } returns emptyList()
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 0
        coEvery { expenseRepository.getCategoryTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAllMerchantStats() } returns emptyList()
        coEvery { expenseRepository.getMerchantStats() } returns emptyList()
        coEvery { expenseRepository.getRecurringCandidates() } returns emptyList()
        coEvery { expenseRepository.getTopMerchantsForPeriod(any(), any(), any()) } returns emptyList()
        coEvery { expenseRepository.getLargestExpenseForPeriod(any(), any()) } returns null
        every { anomalyDetector.detect(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { reader.linkedExpenseIds() } returns emptySet()
        coEvery { reader.planPurchaseExpenseIds() } returns emptySet()
    }

    private fun engine() = InsightsEngine(
        expenseRepository = expenseRepository,
        recurringExpenseEngine = recurringEngine,
        timeProvider = timeProvider,
        spendingPaceCalculator = mockk(relaxed = true),
        anomalyDetector = anomalyDetector,
        monthlyComparisonCalculator = MonthlyComparisonCalculator(),
        categoryInsightEngine = mockk(relaxed = true),
        merchantInsightEngine = mockk(relaxed = true),
        dayOfWeekAnalyzer = mockk(relaxed = true),
        installmentEligibility = RecurringInstallmentEligibility(dagger.Lazy { reader })
    )

    private fun snapshot(id: Long, amount: Double, date: String, merchantKey: String = MERCHANT_KEY) = ExpenseSnapshot(
        id = id,
        amount = amount,
        effectiveAmount = amount,
        currency = "EUR",
        merchant = merchantKey,
        merchantKey = merchantKey,
        transactionType = DomainTransactionType.PURCHASE,
        date = Instant.parse(date).toEpochMilli(),
        categoryId = null,
        isNotMine = false,
        transferDirection = null,
        notes = null
    )

    /** Four ~100 EUR history rows (average 100, multiplier 5x) plus a 1000 EUR spike in the current month. */
    private fun fixture(): List<ExpenseSnapshot> = listOf(
        snapshot(1L, 100.0, "2025-12-10T12:00:00Z"),
        snapshot(2L, 100.0, "2026-01-10T12:00:00Z"),
        snapshot(3L, 100.0, "2026-02-10T12:00:00Z"),
        snapshot(4L, 100.0, "2026-02-20T12:00:00Z"),
        snapshot(SPIKE_ID, 1000.0, "2026-03-10T12:00:00Z"),
        snapshot(SAME_MERCHANT_OTHER_ID, 120.0, "2026-03-11T12:00:00Z")
    )

    private fun statisticalAnomaly(id: Long, expenses: List<ExpenseSnapshot>): AnomalyTransaction {
        val source = expenses.first { it.id == id }
        return AnomalyTransaction(
            expense = AnalyticsTransactionSummary(
                id = source.id,
                amount = source.amount,
                effectiveAmount = source.effectiveAmount,
                currency = source.currency,
                merchant = source.merchant,
                date = source.date,
                categoryId = source.categoryId
            ),
            merchantAvg = 100.0,
            deviationMultiple = 3f,
            category = null,
            detectionMethod = AnomalyMethod.IQR,
            displayCurrency = "EUR"
        )
    }

    private suspend fun anomalyIds(expenses: List<ExpenseSnapshot> = fixture()): List<Long> =
        engine().generateInsights(
            categories = emptyList(),
            allExpenses = expenses,
            displayCurrency = "EUR"
        ).anomalies.map { it.expense.id }

    // ---- F-AE-2 ----

    @Test
    fun `control - unlinked spike is flagged by the merchant path`() = runBlocking {
        assertEquals(listOf(SPIKE_ID), anomalyIds())
    }

    @Test
    fun `installment-linked spike produces no anomaly`() = runBlocking {
        coEvery { reader.linkedExpenseIds() } returns setOf(SPIKE_ID)

        assertTrue(anomalyIds().isEmpty())
    }

    @Test
    fun `plan purchase spike produces no anomaly`() = runBlocking {
        coEvery { reader.planPurchaseExpenseIds() } returns setOf(SPIKE_ID)

        assertTrue(anomalyIds().isEmpty())
    }

    @Test
    fun `statistical path is suppressed by id and a non-linked expense at the same merchant is still flagged`() = runBlocking {
        val expenses = fixture()
        every { anomalyDetector.detect(any(), any(), any(), any(), any()) } returns listOf(
            statisticalAnomaly(SPIKE_ID, expenses),
            statisticalAnomaly(SAME_MERCHANT_OTHER_ID, expenses)
        )
        coEvery { reader.linkedExpenseIds() } returns setOf(SPIKE_ID)

        val ids = anomalyIds(expenses)

        assertEquals("only the unlinked same-merchant expense remains", listOf(SAME_MERCHANT_OTHER_ID), ids)
    }

    // ---- F-AE-3 ----

    @Test
    fun `with no plans or with unrelated plans the output is identical`() = runBlocking {
        val expenses = fixture()
        every { anomalyDetector.detect(any(), any(), any(), any(), any()) } returns listOf(
            statisticalAnomaly(SAME_MERCHANT_OTHER_ID, expenses)
        )

        val noPlans = engine().generateInsights(emptyList(), expenses, "EUR").anomalies

        coEvery { reader.linkedExpenseIds() } returns setOf(9_999L)
        coEvery { reader.planPurchaseExpenseIds() } returns setOf(8_888L)
        val unrelatedPlans = engine().generateInsights(emptyList(), expenses, "EUR").anomalies

        assertEquals(setOf(SPIKE_ID, SAME_MERCHANT_OTHER_ID), noPlans.map { it.expense.id }.toSet())
        assertEquals(noPlans, unrelatedPlans)
        val spike = noPlans.first { it.expense.id == SPIKE_ID }
        assertEquals(100.0, spike.merchantAvg, 0.0)
        assertEquals(10f, spike.deviationMultiple, 0.0f)
        assertEquals(AnomalyMethod.MULTIPLIER, spike.detectionMethod)
    }

    // ---- F-AE-4 ----

    @Test
    fun `reader failure gives no anomalies`() = runBlocking {
        coEvery { reader.linkedExpenseIds() } throws IllegalStateException("reader unavailable")

        assertTrue(anomalyIds().isEmpty())
    }

    @Test
    fun `CancellationException from the reader is rethrown`() = runBlocking {
        coEvery { reader.linkedExpenseIds() } throws CancellationException("cancelled")

        var thrown: Throwable? = null
        try {
            anomalyIds()
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull("cancellation must not be turned into an empty anomaly list", thrown)
    }

    @Test
    fun `link sets are read once per call and never per expense`() = runBlocking {
        anomalyIds()

        coVerify(exactly = 1) { reader.linkedExpenseIds() }
        coVerify(exactly = 1) { reader.planPurchaseExpenseIds() }
        coVerify(exactly = 0) { reader.linkedEntryIdFor(any()) }
        coVerify(exactly = 0) { reader.isPlanPurchase(any()) }
    }
}
