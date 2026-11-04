package com.yourname.expensetracker.ui.screens.transactions

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Date-header total: signed (spending negative, deposit positive, TRANSFER/UNKNOWN zero),
 * one currency per total, and no raw-amount fallback when a mixed group has no usable snapshot.
 * All data is synthetic.
 */
class DateHeaderTotalTest {

    private val delta = 1e-9

    private fun row(
        amount: Double,
        currency: String = "EUR",
        type: TransactionType = TransactionType.PURCHASE,
        baseAmount: Double = amount,
        baseCurrency: String = "EUR",
        rate: Double = 1.0,
        isNotMine: Boolean = false,
        isShared: Boolean = false,
        sharePercentage: Int? = null,
        shareAmount: Double? = null,
        exclusion: String? = null,
    ) = Expense(
        amount = amount,
        currency = currency,
        merchant = "Synthetic",
        transactionType = type,
        date = 1_700_000_000_000L,
        baseAmount = baseAmount,
        baseCurrency = baseCurrency,
        exchangeRateUsed = rate,
        isNotMine = isNotMine,
        isSharedExpense = isShared,
        mySharePercentage = sharePercentage,
        myShareAmount = shareAmount,
        spendingExclusionReason = exclusion,
    )

    private fun usdRow(
        amount: Double,
        type: TransactionType,
        baseAmount: Double = amount,
        rate: Double = 1.0,
        baseCurrency: String = "EUR",
        isNotMine: Boolean = false,
        isShared: Boolean = false,
        sharePercentage: Int? = null,
        shareAmount: Double? = null,
        exclusion: String? = null,
    ) = row(
        amount = amount, currency = "USD", type = type, baseAmount = baseAmount,
        baseCurrency = baseCurrency, rate = rate, isNotMine = isNotMine, isShared = isShared,
        sharePercentage = sharePercentage, shareAmount = shareAmount, exclusion = exclusion,
    )

    // --- Mixed currency: signed sum in the common base currency ---

    @Test
    fun mixedEurPurchase10AndUsdDeposit30_totalsPlus20InBaseCurrency() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(30.0, TransactionType.DEPOSIT, baseAmount = 30.0, rate = 1.0),
            ),
        )
        assertNotNull(total)
        assertEquals(20.0, total!!.amount, delta)
        assertEquals("EUR", total.currency)
    }

    @Test
    fun mixedRealisticRate_usesSnapshotNotRawAmount() {
        // USD 30 deposit recorded as EUR 27 (rate 0.9); EUR 10 purchase.
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(30.0, TransactionType.DEPOSIT, baseAmount = 27.0, rate = 0.9),
            ),
        )
        assertEquals(17.0, total!!.amount, delta)
        assertEquals("EUR", total.currency)
    }

    @Test
    fun mixedGroup_purchaseAndWithdrawalAreNegative() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(20.0, TransactionType.WITHDRAWAL, baseAmount = 18.0, rate = 0.9),
            ),
        )
        assertEquals(-28.0, total!!.amount, delta)
    }

    @Test
    fun mixedGroup_transferAndUnknownContributeZero() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(50.0, TransactionType.TRANSFER, baseAmount = 45.0, rate = 0.9),
                usdRow(70.0, TransactionType.UNKNOWN, baseAmount = 63.0, rate = 0.9),
            ),
        )
        assertEquals(-10.0, total!!.amount, delta)
    }

    @Test
    fun mixedGroup_neutralisedRowAddsZero() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(
                    300.0, TransactionType.PURCHASE, baseAmount = 270.0, rate = 0.9,
                    exclusion = "INSTALLMENT_PURCHASE",
                ),
            ),
        )
        assertEquals(-10.0, total!!.amount, delta)
    }

    @Test
    fun singleCurrency_flaggedFullAmountRowAddsZeroToSpendingTotal() {
        // The flagged row prints its full amount, but the header total stays spending-only.
        val total = dateHeaderTotal(
            listOf(
                row(1200.0, "EUR", TransactionType.PURCHASE, exclusion = "INSTALLMENT_PURCHASE"),
                row(500.0, "EUR", TransactionType.PURCHASE),
            ),
        )
        assertEquals(-500.0, total!!.amount, delta)
    }

    @Test
    fun mixedGroup_notMineRowAddsZero() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(300.0, TransactionType.PURCHASE, baseAmount = 270.0, rate = 0.9, isNotMine = true),
            ),
        )
        assertEquals(-10.0, total!!.amount, delta)
    }

    @Test
    fun mixedGroup_sharedPercentageUsesShareOfSnapshot() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(
                    100.0, TransactionType.PURCHASE, baseAmount = 90.0, rate = 0.9,
                    isShared = true, sharePercentage = 50,
                ),
            ),
        )
        assertEquals(-55.0, total!!.amount, delta)
    }

    @Test
    fun mixedGroup_sharedAmountUsesShareTimesRate() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(
                    100.0, TransactionType.PURCHASE, baseAmount = 90.0, rate = 0.9,
                    isShared = true, shareAmount = 40.0,
                ),
            ),
        )
        assertEquals(-46.0, total!!.amount, delta)
    }

    @Test
    fun mixedGroup_netZeroIsZeroNotNull() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(12.0, TransactionType.DEPOSIT, baseAmount = 10.0, rate = 0.8333333333),
            ),
        )
        assertNotNull(total)
        assertEquals(0.0, total!!.amount, 1e-6)
        assertEquals("EUR", total.currency)
    }

    // --- Mixed currency: no total (no raw fallback) ---

    @Test
    fun mixedBaseCurrencies_giveNoTotal() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(30.0, TransactionType.DEPOSIT, baseAmount = 30.0, rate = 1.0, baseCurrency = "USD"),
            ),
        )
        assertNull(total)
    }

    @Test
    fun mixedGroup_zeroBaseAmountGivesNoTotal() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(30.0, TransactionType.DEPOSIT, baseAmount = 0.0, rate = 1.0),
            ),
        )
        assertNull(total)
    }

    @Test
    fun mixedGroup_negativeBaseAmountGivesNoTotal() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(30.0, TransactionType.DEPOSIT, baseAmount = -30.0, rate = 1.0),
            ),
        )
        assertNull(total)
    }

    @Test
    fun mixedGroup_missingRateSnapshotGivesNoTotal() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(30.0, TransactionType.DEPOSIT, baseAmount = 30.0, rate = 0.0),
            ),
        )
        assertNull(total)
    }

    // --- Same-currency group ---

    @Test
    fun sameCurrency_signsFollowTransactionType() {
        fun single(type: TransactionType) = dateHeaderTotal(listOf(row(25.0, "EUR", type)))!!
        assertEquals(-25.0, single(TransactionType.PURCHASE).amount, delta)
        assertEquals(-25.0, single(TransactionType.WITHDRAWAL).amount, delta)
        assertEquals(25.0, single(TransactionType.DEPOSIT).amount, delta)
        assertEquals(0.0, single(TransactionType.TRANSFER).amount, delta)
        assertEquals(0.0, single(TransactionType.UNKNOWN).amount, delta)
        assertEquals("EUR", single(TransactionType.PURCHASE).currency)
    }

    @Test
    fun sameCurrency_neutralisedAndNotMineAddZero() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                row(300.0, "EUR", TransactionType.PURCHASE, exclusion = "INSTALLMENT_PURCHASE"),
                row(400.0, "EUR", TransactionType.PURCHASE, isNotMine = true),
            ),
        )
        assertEquals(-10.0, total!!.amount, delta)
    }

    @Test
    fun sameCurrency_sharedRowUsesShare() {
        val pct = dateHeaderTotal(
            listOf(row(100.0, "EUR", TransactionType.PURCHASE, isShared = true, sharePercentage = 25)),
        )
        val abs = dateHeaderTotal(
            listOf(row(100.0, "EUR", TransactionType.PURCHASE, isShared = true, shareAmount = 30.0)),
        )
        assertEquals(-25.0, pct!!.amount, delta)
        assertEquals(-30.0, abs!!.amount, delta)
    }

    @Test
    fun sameCurrency_withoutSnapshot_stillTotalsInGroupCurrency() {
        // No snapshot needed: the group currency is used directly.
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "USD", TransactionType.PURCHASE, baseAmount = 0.0, rate = 0.0),
                row(30.0, "USD", TransactionType.DEPOSIT, baseAmount = 0.0, rate = 0.0),
            ),
        )
        assertEquals(20.0, total!!.amount, delta)
        assertEquals("USD", total.currency)
    }

    @Test
    fun sameCurrencyGroup_equalsMixedPathValueForIdenticalAmounts() {
        val same = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                row(30.0, "EUR", TransactionType.DEPOSIT),
            ),
        )
        val mixed = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                usdRow(30.0, TransactionType.DEPOSIT, baseAmount = 30.0, rate = 1.0),
            ),
        )
        assertEquals(20.0, same!!.amount, delta)
        assertEquals(20.0, mixed!!.amount, delta)
        assertEquals(same.currency, mixed.currency)
    }

    @Test
    fun sameCurrency_netZeroIsZero() {
        val total = dateHeaderTotal(
            listOf(
                row(10.0, "EUR", TransactionType.PURCHASE),
                row(10.0, "EUR", TransactionType.DEPOSIT),
            ),
        )
        assertNotNull(total)
        assertEquals(0.0, total!!.amount, delta)
    }
}
