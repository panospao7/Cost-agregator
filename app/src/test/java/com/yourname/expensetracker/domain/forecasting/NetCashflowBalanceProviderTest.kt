package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.dao.CurrentUserSettlementRow
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupCashFlowReader
import com.yourname.expensetracker.data.repository.GroupCashFlowSnapshot
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.testAnalyticsCurrencyNormalizer
import com.yourname.expensetracker.toExpenseSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P-06-4: [NetCashflowBalanceProvider] returns a figure only for the home currency and only when
 * every input is trustworthy. Anything else is null (unavailable), never 0. Cancellation always
 * propagates (G-CANCEL-02). Synthetic data only.
 */
class NetCashflowBalanceProviderTest {

    private val now = 1_700_000_000_000L
    private val ninetyDaysAgo = now - 90 * TimePeriodUtils.DAY_IN_MILLIS

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var normalizer: AnalyticsCurrencyNormalizer
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var provider: NetCashflowBalanceProvider

    /** [rows] is the transaction count carried by the single EUR source bucket (0 means no rows). */
    private fun aggregate(amount: Double, partial: Boolean = false, rows: Int = 1) = MoneyAggregate(
        displayAmount = amount,
        displayCurrency = CurrencyCode.EUR,
        sourceBuckets = if (rows > 0) {
            listOf(MoneyBucket(CurrencyCode.EUR, amount, rows))
        } else {
            emptyList()
        },
        conversionFailures = emptyList(),
        isPartial = partial
    )

    private fun withdrawal(id: Long, amount: Double, isNotMine: Boolean = false): ExpenseSnapshot =
        Expense(
            id = id,
            amount = amount,
            merchant = "ATM $id",
            transactionType = TransactionType.WITHDRAWAL,
            date = now - 5 * TimePeriodUtils.DAY_IN_MILLIS,
            categoryId = 1L,
            isNotMine = isNotMine
        ).toExpenseSnapshot()

    /** A clean normalization result carrying [included] rows (peripheral normalizer is a stub). */
    private fun cleanNormalization(included: List<ExpenseSnapshot>): AnalyticsNormalizationResult =
        mockk<AnalyticsNormalizationResult> {
            every { severeWarnings } returns emptyList()
            every { excludedCount } returns 0
            every { includedExpenses } returns included
        }

    @Before
    fun setUp() {
        multiCurrencyRepository = mockk()
        expenseRepository = mockk()
        normalizer = mockk()
        currencySettingsRepository = mockk()
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(1000.0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(250.5)
        coEvery { expenseRepository.getExpensesBetween(ninetyDaysAgo, now) } returns emptyList()
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns cleanNormalization(emptyList())
        // DR-01: old tests run with an empty group reader so their results are unchanged.
        groupCashFlowReader = mockk()
        coEvery { groupCashFlowReader.read(any(), any(), any()) } returns snapshot()
        currencyConverter = mockk()
        provider = NetCashflowBalanceProvider(
            multiCurrencyRepository,
            expenseRepository,
            normalizer,
            currencySettingsRepository,
            FakeTimeProvider(now),
            groupCashFlowReader,
            currencyConverter
        )
    }

    private lateinit var groupCashFlowReader: GroupCashFlowReader
    private lateinit var currencyConverter: CurrencyConverter

    private fun snapshot(
        nonPayer: Set<Long> = emptySet(),
        payer: Set<Long> = emptySet(),
        settlements: List<CurrentUserSettlementRow> = emptyList(),
        nonPayerFailed: Boolean = false,
        payerFailed: Boolean = false,
        settlementFailed: Boolean = false
    ) = GroupCashFlowSnapshot(nonPayer, payer, settlements, nonPayerFailed, payerFailed, settlementFailed)

    private fun settlement(
        amount: Double,
        paidByCurrentUser: Boolean,
        currency: String = "EUR",
        linkedExpenseId: Long? = null
    ) = CurrentUserSettlementRow(
        id = 1L,
        amount = amount,
        currency = currency,
        createdAt = now - 3 * TimePeriodUtils.DAY_IN_MILLIS,
        linkedExpenseId = linkedExpenseId,
        paidByCurrentUser = paidByCurrentUser
    )

    // ── Control: the home currency returns the real figure ──────────────────

    @Test
    fun `home currency returns deposits minus purchases over the 90 day window`() = runTest {
        assertEquals(749.5, provider.currentBalance("EUR")!!, 0.0)

        coVerify(exactly = 1) { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) }
        coVerify(exactly = 1) { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) }
    }

    @Test
    fun `home currency match is case insensitive`() = runTest {
        assertEquals(749.5, provider.currentBalance("eur")!!, 0.0)
    }

    @Test
    fun `withdrawals reduce the home currency figure`() = runTest {
        val withdrawals = listOf(withdrawal(1L, 100.0), withdrawal(2L, 49.5))
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns cleanNormalization(withdrawals)

        // 1000 - 250.5 - (100 + 49.5) = 600
        assertEquals(600.0, provider.currentBalance("EUR")!!, 0.0)
    }

    @Test
    fun `not-mine withdrawals do not reduce the figure`() = runTest {
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns
            cleanNormalization(listOf(withdrawal(1L, 100.0, isNotMine = true)))

        assertEquals(749.5, provider.currentBalance("EUR")!!, 0.0)
    }

    @Test
    fun `deposits 1000 and purchases 1500 is unavailable not a clamped zero`() = runTest {
        // Owner-approved change: this test used to pin the clamp to 0.0 for a negative net.
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(1000.0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(1500.0)

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `deposits 1500 and purchases 1000 returns 500`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(1500.0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(1000.0)

        assertEquals(500.0, provider.currentBalance("EUR")!!, 0.0)
    }

    @Test
    fun `zero deposit purchase and withdrawal rows is unavailable not zero`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `net exactly zero with rows present is a real zero balance`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(500.0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns aggregate(500.0)

        val balance = provider.currentBalance("EUR")

        assertNotNull(balance)
        assertEquals(0.0, balance!!, 0.0)
    }

    @Test
    fun `a withdrawal row alone counts as a cashflow row`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns
            aggregate(100.0, rows = 0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns
            cleanNormalization(listOf(withdrawal(1L, 100.0)))

        val balance = provider.currentBalance("EUR")

        assertNotNull(balance)
        assertEquals(0.0, balance!!, 0.0)
    }

    @Test
    fun `negative net caused by a withdrawal is unavailable`() = runTest {
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns
            cleanNormalization(listOf(withdrawal(1L, 900.0)))

        // 1000 - 250.5 - 900 < 0
        assertNull(provider.currentBalance("EUR"))
    }

    // ── Unavailable (null), never 0 ─────────────────────────────────────────

    @Test
    fun `non home currency is unavailable and reads nothing`() = runTest {
        assertNull(provider.currentBalance("USD"))

        coVerify(exactly = 0) { multiCurrencyRepository.getHomeCurrencyDepositTotal(any(), any()) }
        coVerify(exactly = 0) { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) }
        coVerify(exactly = 0) { expenseRepository.getExpensesBetween(any(), any()) }
    }

    @Test
    fun `unresolved home currency is unavailable`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Failed("HOME_CURRENCY_UNAVAILABLE")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `home currency resolution failure is unavailable not zero`() = runTest {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } throws IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `deposit read failure is unavailable not zero`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } throws
            IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `purchase read failure is unavailable not the deposit total`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } throws
            IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `both reads failing is unavailable not zero`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } throws
            IllegalStateException("boom")
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } throws
            IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `partial deposit total is unavailable`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns
            aggregate(1000.0, partial = true)

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `partial purchase total is unavailable`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(250.5, partial = true)

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `withdrawal read failure is unavailable`() = runTest {
        coEvery { expenseRepository.getExpensesBetween(ninetyDaysAgo, now) } throws IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `withdrawal normalization failure is unavailable`() = runTest {
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } throws IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `excluded withdrawal rows are unavailable`() = runTest {
        // One input row that did not survive normalization: excludedCount = 1 - 0.
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns AnalyticsNormalizationResult(
            homeCurrency = "EUR",
            normalizedExpenses = emptyList(),
            includedExpenses = emptyList(),
            warnings = emptyList(),
            latestRateTimestamp = null,
            totalInputCount = 1
        )

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `severe withdrawal warnings are unavailable`() = runTest {
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns mockk<AnalyticsNormalizationResult> {
            every { severeWarnings } returns listOf(mockk<AnalyticsConversionWarning>())
            every { excludedCount } returns 0
            every { includedExpenses } returns emptyList()
        }

        assertNull(provider.currentBalance("EUR"))
    }

    // ── Unconvertible rows: only an own WITHDRAWAL may block (REAL normalizer, no rate store entries) ──

    private fun entity(
        id: Long,
        type: TransactionType,
        currency: String,
        amount: Double = 100.0,
        isNotMine: Boolean = false
    ): Expense = Expense(
        id = id,
        amount = amount,
        merchant = "Synthetic $id",
        transactionType = type,
        date = now - 5 * TimePeriodUtils.DAY_IN_MILLIS,
        categoryId = 1L,
        currency = currency,
        isNotMine = isNotMine
    )

    /** Provider wired to the REAL [AnalyticsCurrencyNormalizer]; the empty rate store converts nothing but identity. */
    private fun providerWithRealNormalizer(rows: List<Expense>): NetCashflowBalanceProvider {
        coEvery { expenseRepository.getExpensesBetween(ninetyDaysAgo, now) } returns rows
        return NetCashflowBalanceProvider(
            multiCurrencyRepository,
            expenseRepository,
            testAnalyticsCurrencyNormalizer(),
            currencySettingsRepository,
            FakeTimeProvider(now),
            groupCashFlowReader,
            currencyConverter
        )
    }

    @Test
    fun `non-vacuity - the unconvertible JPY row really fails conversion in the real normalizer`() = runTest {
        val rows = listOf(
            entity(1L, TransactionType.WITHDRAWAL, "EUR"),
            entity(2L, TransactionType.TRANSFER, "JPY")
        )

        val result = testAnalyticsCurrencyNormalizer().normalizeExpenses(rows, "EUR")

        // Unfiltered, the JPY transfer is excluded for a missing rate; this is the failure the provider must not inherit.
        assertEquals(1, result.excludedCount)
        assertEquals(1, result.includedExpenses.size)
        assertEquals(1L, result.includedExpenses.single().id)
        assertEquals(true, result.severeWarnings.isNotEmpty() || result.warnings.isNotEmpty())
    }

    @Test
    fun `unconvertible TRANSFER plus convertible WITHDRAWAL returns a number not null`() = runTest {
        val provider = providerWithRealNormalizer(
            listOf(
                entity(1L, TransactionType.WITHDRAWAL, "EUR", amount = 100.0),
                entity(2L, TransactionType.TRANSFER, "JPY", amount = 5000.0)
            )
        )

        // 1000 - 250.5 - 100 = 649.5; the JPY transfer is neutral and never reaches conversion.
        assertEquals(649.5, provider.currentBalance("EUR")!!, 0.0)
    }

    @Test
    fun `unconvertible WITHDRAWAL is unavailable not a number`() = runTest {
        val rows = listOf(
            entity(1L, TransactionType.WITHDRAWAL, "EUR", amount = 100.0),
            entity(2L, TransactionType.WITHDRAWAL, "JPY", amount = 5000.0)
        )
        // Non-vacuity: the real normalizer excludes exactly the JPY withdrawal for conversion.
        val direct = testAnalyticsCurrencyNormalizer().normalizeExpenses(rows, "EUR")
        assertEquals(1, direct.excludedCount)

        assertNull(providerWithRealNormalizer(rows).currentBalance("EUR"))
    }

    @Test
    fun `unconvertible not-mine WITHDRAWAL does not block the figure`() = runTest {
        val rows = listOf(
            entity(1L, TransactionType.WITHDRAWAL, "EUR", amount = 100.0),
            entity(2L, TransactionType.WITHDRAWAL, "JPY", amount = 5000.0, isNotMine = true)
        )

        assertEquals(649.5, providerWithRealNormalizer(rows).currentBalance("EUR")!!, 0.0)
    }

    // ── Cancellation propagates unchanged ───────────────────────────────────

    @Test
    fun `cancellation from home currency resolution propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } throws cancellation

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
    }

    @Test
    fun `cancellation from deposit read propagates and skips the purchase read`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } throws cancellation

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
        coVerify(exactly = 0) { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) }
    }

    @Test
    fun `cancellation from purchase read propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } throws cancellation

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
    }

    @Test
    fun `cancellation from withdrawal read propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { expenseRepository.getExpensesBetween(ninetyDaysAgo, now) } throws cancellation

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
    }

    @Test
    fun `cancellation from withdrawal normalization propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } throws cancellation

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
    }

    // ── DR-01 group cash flow: payer front, non-payer share, settlements ─────

    /** Group-linked purchase: a 3000 bill of which the user's share is 500. */
    private fun groupBill(id: Long, currency: String = "EUR") = entity(
        id, TransactionType.PURCHASE, currency, amount = 3000.0
    ).copy(isSharedExpense = true, myShareAmount = 500.0)

    /** The plain purchase total holds only the 500 share; deposits are [deposits]. */
    private fun givenTotals(deposits: Double, purchaseShare: Double = 500.0, purchaseRows: Int = 1) {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns aggregate(deposits)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(purchaseShare, rows = purchaseRows)
    }

    private suspend fun balanceFor(rows: List<Expense>, snapshot: GroupCashFlowSnapshot): Double? {
        coEvery { groupCashFlowReader.read(any(), any(), any()) } returns snapshot
        return providerWithRealNormalizer(rows).currentBalance("EUR")
    }

    @Test
    fun `DR-01 payer fronting 3000 with share 500 and deposits 3600 gives 600`() = runTest {
        givenTotals(deposits = 3600.0)

        assertEquals(600.0, balanceFor(listOf(groupBill(10L)), snapshot(payer = setOf(10L)))!!, 0.0001)
    }

    @Test
    fun `DR-01 payer fronting 3000 with deposits 2600 is unavailable not negative or zero`() = runTest {
        givenTotals(deposits = 2600.0)

        assertNull(balanceFor(listOf(groupBill(10L)), snapshot(payer = setOf(10L))))
    }

    @Test
    fun `DR-01 non-payer row at share 500 on a 3000 bill leaves deposits 2600 as 2600`() = runTest {
        givenTotals(deposits = 2600.0)

        assertEquals(2600.0, balanceFor(listOf(groupBill(11L)), snapshot(nonPayer = setOf(11L)))!!, 0.0001)
    }

    @Test
    fun `DR-01 payer plus received settlement 2500 from deposits 3600 gives 3100`() = runTest {
        givenTotals(deposits = 3600.0)

        val balance = balanceFor(
            listOf(groupBill(10L)),
            snapshot(payer = setOf(10L), settlements = listOf(settlement(2500.0, paidByCurrentUser = false)))
        )

        assertEquals(3100.0, balance!!, 0.0001)
    }

    @Test
    fun `DR-01 non-payer plus paid settlement 500 from deposits 2600 gives 2100`() = runTest {
        givenTotals(deposits = 2600.0)

        val balance = balanceFor(
            listOf(groupBill(11L)),
            snapshot(nonPayer = setOf(11L), settlements = listOf(settlement(500.0, paidByCurrentUser = true)))
        )

        assertEquals(2100.0, balance!!, 0.0001)
    }

    @Test
    fun `DR-01 non-payer row with a settlement linked to it still counts the settlement`() = runTest {
        givenTotals(deposits = 2600.0)

        val balance = balanceFor(
            listOf(groupBill(11L)),
            snapshot(
                nonPayer = setOf(11L),
                settlements = listOf(settlement(500.0, paidByCurrentUser = true, linkedExpenseId = 11L))
            )
        )

        assertEquals(2100.0, balance!!, 0.0001)
    }

    @Test
    fun `DR-01 settlement linked to a counted payer row is not counted twice`() = runTest {
        givenTotals(deposits = 3600.0)

        val balance = balanceFor(
            listOf(groupBill(10L)),
            snapshot(
                payer = setOf(10L),
                settlements = listOf(settlement(2500.0, paidByCurrentUser = false, linkedExpenseId = 10L))
            )
        )

        assertEquals(600.0, balance!!, 0.0001)
    }

    @Test
    fun `DR-01 non-payer group read failure is unavailable`() = runTest {
        assertNull(balanceFor(emptyList(), snapshot(nonPayerFailed = true)))
    }

    @Test
    fun `DR-01 payer group read failure is unavailable`() = runTest {
        assertNull(balanceFor(emptyList(), snapshot(payerFailed = true)))
    }

    @Test
    fun `DR-01 settlement group read failure is unavailable`() = runTest {
        assertNull(balanceFor(emptyList(), snapshot(settlementFailed = true)))
    }

    @Test
    fun `DR-01 expense re-read failure while adjusting groups is unavailable`() = runTest {
        coEvery { groupCashFlowReader.read(any(), any(), any()) } returns snapshot(payer = setOf(10L))
        coEvery { expenseRepository.getExpensesBetween(ninetyDaysAgo, now) } returns emptyList() andThenThrows
            IllegalStateException("boom")

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `DR-01 unconvertible payer row is unavailable not a number`() = runTest {
        givenTotals(deposits = 3600.0)
        val jpyBill = groupBill(10L, currency = "JPY")
        // Non-vacuity: the real normalizer really excludes this row for a missing rate.
        assertEquals(1, testAnalyticsCurrencyNormalizer().normalizeExpenses(listOf(jpyBill), "EUR").excludedCount)

        assertNull(balanceFor(listOf(jpyBill), snapshot(payer = setOf(10L))))
    }

    @Test
    fun `DR-01 unconvertible non-payer row is unavailable not a number`() = runTest {
        givenTotals(deposits = 2600.0)

        assertNull(balanceFor(listOf(groupBill(11L, currency = "JPY")), snapshot(nonPayer = setOf(11L))))
    }

    @Test
    fun `DR-01 unconvertible settlement is unavailable not zero`() = runTest {
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
        } returns ConversionOutcome.Failed(
            originalAmount = 100.0,
            originalCurrency = "JPY",
            targetCurrency = "EUR",
            rateBasis = RateBasis.TRANSACTION_DATE,
            failureType = ConversionFailureType.MISSING_RATE,
            message = "synthetic"
        )

        assertNull(
            balanceFor(emptyList(), snapshot(settlements = listOf(settlement(100.0, false, currency = "JPY"))))
        )
    }

    @Test
    fun `DR-01 settlement converter throwing is unavailable`() = runTest {
        coEvery { currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("boom")

        assertNull(
            balanceFor(emptyList(), snapshot(settlements = listOf(settlement(100.0, false, currency = "USD"))))
        )
    }

    @Test
    fun `DR-01 foreign currency received settlement converts at the latest rate like the totals`() = runTest {
        // PIN CHANGED: the settlement used to convert at its own date (TRANSACTION_DATE). The plain
        // totals convert at the LATEST rate, so the settlement joins that basis (atMillis = null).
        // The numeric concern (a converted settlement is added to the net) is unchanged.
        val converted = mockk<ConversionOutcome.Converted> { every { convertedAmount } returns 90.0 }
        coEvery { currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any()) } returns converted
        val usd = settlement(100.0, paidByCurrentUser = false, currency = "USD")

        // 1000 - 250.5 + 90 = 839.5
        assertEquals(839.5, balanceFor(emptyList(), snapshot(settlements = listOf(usd)))!!, 0.0001)
        coVerify(exactly = 1) {
            currencyConverter.convertOutcome(
                amount = 100.0,
                fromCurrency = "USD",
                toCurrency = "EUR",
                rateBasis = RateBasis.LATEST_AVAILABLE,
                atMillis = null,
                stalePolicy = any()
            )
        }
        coVerify(exactly = 0) {
            currencyConverter.convertOutcome(any(), any(), any(), RateBasis.TRANSACTION_DATE, any(), any())
        }
    }

    private fun usdShare(id: Long, share: Double): Expense =
        entity(id, TransactionType.PURCHASE, "USD", amount = share)
            .copy(isSharedExpense = true, myShareAmount = share)

    @Test
    fun `DR-01 non-payer USD share 1000 adds back EUR 1000 at the latest rate with no phantom EUR 100`() = runTest {
        // Latest rate 1.00, transaction-date rate 0.90: the totals hold the share at EUR 1000, so the
        // add-back must also be EUR 1000 (a date-rate add-back of 900 would leave a phantom -100).
        val latest = mockk<ConversionOutcome.Converted> { every { convertedAmount } returns 1000.0 }
        val dated = mockk<ConversionOutcome.Converted> { every { convertedAmount } returns 900.0 }
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), RateBasis.LATEST_AVAILABLE, any(), any())
        } returns latest
        coEvery {
            currencyConverter.convertOutcome(any(), any(), any(), RateBasis.TRANSACTION_DATE, any(), any())
        } returns dated
        givenTotals(deposits = 3000.0, purchaseShare = 1000.0)

        val balance = balanceFor(listOf(usdShare(12L, 1000.0)), snapshot(nonPayer = setOf(12L)))

        // 3000 - 1000 + 1000 = 3000 (not 2900).
        assertEquals(3000.0, balance!!, 0.0001)
    }

    @Test
    fun `DR-01 a lone non-payer row of EUR 50 with no deposits is unavailable not a zero balance`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(50.0, rows = 1)

        val row = entity(13L, TransactionType.PURCHASE, "EUR", amount = 50.0)

        assertNull(balanceFor(listOf(row), snapshot(nonPayer = setOf(13L))))
    }

    @Test
    fun `DR-01 the same lone non-payer row of EUR 50 with a deposit of 100 returns a value`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns
            aggregate(100.0, rows = 1)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(50.0, rows = 1)

        val row = entity(13L, TransactionType.PURCHASE, "EUR", amount = 50.0)

        // 100 - 50 + 50 = 100
        assertEquals(100.0, balanceFor(listOf(row), snapshot(nonPayer = setOf(13L)))!!, 0.0001)
    }

    @Test
    fun `DR-01 a not-mine non-payer row is not a cash row so alone it is unavailable`() = runTest {
        // The totals never counted a not-mine row, so removing it must not hide the empty cashflow.
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)

        val row = entity(14L, TransactionType.PURCHASE, "EUR", amount = 50.0, isNotMine = true)

        assertNull(balanceFor(listOf(row), snapshot(nonPayer = setOf(14L))))
    }

    @Test
    fun `DR-01 cancellation from the group reader propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { groupCashFlowReader.read(any(), any(), any()) } throws cancellation

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
    }

    @Test
    fun `DR-01 cancellation from the settlement converter propagates`() = runTest {
        val cancellation = CancellationException("cancelled")
        coEvery { currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any()) } throws cancellation
        coEvery { groupCashFlowReader.read(any(), any(), any()) } returns
            snapshot(settlements = listOf(settlement(100.0, false, currency = "USD")))

        assertSame(cancellation, runCatching { provider.currentBalance("EUR") }.exceptionOrNull())
    }

    // ── Group WITHDRAWAL rule and purchase adjustment (normalizer input is captured) ──

    /** Mocked normalizer that records every input list and returns it unchanged as included rows. */
    private fun captureNormalizerInput(): MutableList<List<Expense>> {
        val captured = mutableListOf<List<Expense>>()
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } answers {
            val rows = firstArg<List<Expense>>()
            captured += rows
            cleanNormalization(rows.map { it.toExpenseSnapshot() })
        }
        return captured
    }

    private fun givenRows(rows: List<Expense>, snapshot: GroupCashFlowSnapshot) {
        coEvery { expenseRepository.getExpensesBetween(ninetyDaysAgo, now) } returns rows
        coEvery { groupCashFlowReader.read(any(), any(), any()) } returns snapshot
    }

    private fun noPlainRows(deposits: Double) {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns
            aggregate(deposits)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)
    }

    @Test
    fun `DR-01 non-payer USD 1000 withdrawal never reaches the normalizer and leaves deposits 2000 as 2000`() = runTest {
        // Latest rate 1.00 vs date rate 0.90 must not matter: the converter is unstubbed (strict),
        // so any rate lookup would turn into a null balance. No phantom 100.
        noPlainRows(deposits = 2000.0)
        val captured = captureNormalizerInput()
        givenRows(
            listOf(entity(20L, TransactionType.WITHDRAWAL, "USD", amount = 1000.0)),
            snapshot(nonPayer = setOf(20L))
        )

        val balance = provider.currentBalance("EUR")

        assertEquals(2000.0, balance!!, 0.0)
        assertEquals(1, captured.size)
        assertTrue(captured.single().none { it.id == 20L })
        coVerify(exactly = 0) { currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `DR-01 non-payer withdrawal with no rate does not withhold the balance`() = runTest {
        givenTotals(deposits = 1000.0, purchaseShare = 250.0)
        val captured = captureNormalizerInput()
        val jpyRow = entity(21L, TransactionType.WITHDRAWAL, "JPY", amount = 5000.0)
        givenRows(listOf(jpyRow), snapshot(nonPayer = setOf(21L)))

        assertEquals(750.0, provider.currentBalance("EUR")!!, 0.0)
        assertTrue(captured.single().none { it.id == 21L })

        // Control: the same JPY withdrawal as a REGULAR row stays unconvertible and withholds.
        assertNull(balanceFor(listOf(jpyRow), snapshot()))
    }

    @Test
    fun `DR-01 payer withdrawal reaches the normalizer at the full amount and the net uses it`() = runTest {
        noPlainRows(deposits = 200.0)
        val captured = captureNormalizerInput()
        val payerRow = entity(22L, TransactionType.WITHDRAWAL, "EUR", amount = 90.0)
            .copy(isSharedExpense = true, myShareAmount = 30.0)
        givenRows(listOf(payerRow), snapshot(payer = setOf(22L)))

        val balance = provider.currentBalance("EUR")

        val seen = captured.single().single { it.id == 22L }
        assertEquals(90.0, seen.effectiveAmount, 0.0)
        assertFalse(seen.isSharedExpense)
        assertNull(seen.myShareAmount)
        assertNull(seen.mySharePercentage)
        assertFalse(seen.isNotMine)
        // 200 - 90 = 110 (not 200 - 30).
        assertEquals(110.0, balance!!, 0.0001)
    }

    @Test
    fun `DR-01 payer not-mine withdrawal reaches the normalizer as mine at the full amount`() = runTest {
        noPlainRows(deposits = 200.0)
        val captured = captureNormalizerInput()
        val payerRow = entity(23L, TransactionType.WITHDRAWAL, "EUR", amount = 90.0, isNotMine = true)
        givenRows(listOf(payerRow), snapshot(payer = setOf(23L)))

        val balance = provider.currentBalance("EUR")

        val seen = captured.single().single { it.id == 23L }
        assertFalse(seen.isNotMine)
        assertEquals(90.0, seen.effectiveAmount, 0.0)
        assertEquals(110.0, balance!!, 0.0001)
    }

    @Test
    fun `DR-01 plain not-mine withdrawal still does not reach the normalizer`() = runTest {
        noPlainRows(deposits = 200.0)
        val captured = captureNormalizerInput()
        givenRows(
            listOf(entity(24L, TransactionType.WITHDRAWAL, "EUR", amount = 90.0, isNotMine = true)),
            snapshot()
        )

        val balance = provider.currentBalance("EUR")

        assertTrue(captured.single().none { it.id == 24L })
        assertEquals(200.0, balance!!, 0.0)
    }

    @Test
    fun `DR-01 snapshot read failure is unavailable and the withdrawal path is not asked to normalize`() = runTest {
        givenRows(
            listOf(entity(27L, TransactionType.WITHDRAWAL, "EUR", amount = 90.0)),
            snapshot(payerFailed = true)
        )

        assertNull(provider.currentBalance("EUR"))
        coVerify(exactly = 0) { normalizer.normalizeExpenses(any(), any()) }
        coVerify(exactly = 0) { expenseRepository.getExpensesBetween(any(), any()) }
    }

    @Test
    fun `DR-01 a lone non-payer withdrawal with no deposits or purchases is unavailable not zero`() = runTest {
        coEvery { multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now) } returns
            aggregate(0.0, rows = 0)
        captureNormalizerInput()
        givenRows(
            listOf(entity(25L, TransactionType.WITHDRAWAL, "EUR", amount = 100.0)),
            snapshot(nonPayer = setOf(25L))
        )

        assertNull(provider.currentBalance("EUR"))
    }

    @Test
    fun `DR-01 non-payer not-mine purchase row adds nothing so deposits 100 stay 100`() = runTest {
        noPlainRows(deposits = 100.0)
        val row = entity(26L, TransactionType.PURCHASE, "EUR", amount = 50.0, isNotMine = true)

        assertEquals(100.0, balanceFor(listOf(row), snapshot(nonPayer = setOf(26L)))!!, 0.0)
    }

    @Test
    fun `DR-01 non-payer spending-excluded purchase row adds nothing so deposits 100 stay 100`() = runTest {
        noPlainRows(deposits = 100.0)
        val row = entity(26L, TransactionType.PURCHASE, "EUR", amount = 50.0)
            .copy(spendingExclusionReason = "SYNTHETIC_EXCLUSION")

        assertEquals(100.0, balanceFor(listOf(row), snapshot(nonPayer = setOf(26L)))!!, 0.0)
    }

    @Test
    fun `DR-01 two non-payer USD purchase rows convert as one bucket exactly once`() = runTest {
        val converted = mockk<ConversionOutcome.Converted> { every { convertedAmount } returns 900.0 }
        coEvery { currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any()) } returns converted
        // The plain purchase total counted both rows, as the real total would.
        givenTotals(deposits = 3000.0, purchaseShare = 900.0, purchaseRows = 2)

        val balance = balanceFor(
            listOf(usdShare(30L, 300.0), usdShare(31L, 700.0)),
            snapshot(nonPayer = setOf(30L, 31L))
        )

        // 3000 - 900 + 900 = 3000
        assertEquals(3000.0, balance!!, 0.0001)
        coVerify(exactly = 1) {
            currencyConverter.convertOutcome(
                amount = 1000.0,
                fromCurrency = "USD",
                toCurrency = "EUR",
                rateBasis = RateBasis.LATEST_AVAILABLE,
                atMillis = null,
                stalePolicy = any()
            )
        }
        coVerify(exactly = 1) { currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `DR-01 unsupported currency in the non-payer purchase bucket is unavailable not an exception`() = runTest {
        givenTotals(deposits = 3000.0, purchaseShare = 900.0)
        val unsupported = entity(32L, TransactionType.PURCHASE, "ZZZ", amount = 100.0)
            .copy(isSharedExpense = true, myShareAmount = 100.0)

        assertNull(
            balanceFor(listOf(unsupported, usdShare(33L, 50.0)), snapshot(nonPayer = setOf(32L, 33L)))
        )
    }

    @Test
    fun `MoneyAggregateBuilder throwing on withdrawals is unavailable not an escape`() = runTest {
        coEvery { normalizer.normalizeExpenses(any(), "EUR") } returns
            cleanNormalization(listOf(withdrawal(1L, 100.0)))
        io.mockk.mockkObject(MoneyAggregateBuilder)
        try {
            every { MoneyAggregateBuilder.fromHomeCurrencyAmounts(amounts = any(), homeCurrency = any()) } throws
                IllegalStateException("boom")

            assertNull(provider.currentBalance("EUR"))
        } finally {
            io.mockk.unmockkObject(MoneyAggregateBuilder)
        }
    }
}
