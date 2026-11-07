package com.yourname.expensetracker.data.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * refund-netting S2a at the real Room/SQLite boundary: INV-6, 8, 9, 10, 11 and the acceptance
 * examples AE-1, 2, 4, 7, 8, 9, 10, 20 as DAO totals.
 *
 * "Linking" is simulated by writing `refundNettedAmount` on both rows (the S3 write path owns the
 * real link); the DAO aggregates must then show the netted numbers. Every expected value is hand
 * computed from the spec fixture (home EUR, P1 100 at "Zara" in Nov, D1 40 refund in Dec, ...).
 * Mid-month noon-UTC dates keep the strftime('localtime') month keys stable in any time zone.
 * Synthetic data only.
 */
@Suppress("DEPRECATION")
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RefundNettingDaoSpendingTotalsTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ExpenseDao

    private val nov0 = dateMs(2026, 11, 1)
    private val dec0 = dateMs(2026, 12, 1)
    private val jan0 = dateMs(2027, 1, 1)

    private var clothingId = 0L

    @Before
    fun setUp() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        dao = db.expenseDao()
        clothingId = category("Clothing")
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ── Helpers ──────────────────────────────────────────────────

    private suspend fun category(name: String): Long =
        db.categoryDao().insert(Category(name = name, icon = "C", color = "#112233"))

    private suspend fun insert(
        amount: Double,
        date: Long,
        merchant: String,
        type: TransactionType = TransactionType.PURCHASE,
        categoryId: Long? = null,
        currency: String = "EUR",
        refund: Double = 0.0,
        notMine: Boolean = false,
        shared: Boolean = false,
        pct: Int? = null,
        fixed: Double? = null,
        reason: String? = null
    ): Long = dao.insert(
        Expense(
            amount = amount,
            currency = currency,
            merchant = merchant,
            merchantKey = merchant.lowercase(),
            transactionType = type,
            date = date,
            categoryId = categoryId,
            isNotMine = notMine,
            isSharedExpense = shared,
            mySharePercentage = pct,
            myShareAmount = fixed,
            spendingExclusionReason = reason,
            refundNettedAmount = refund
        )
    ).also { assertTrue("insert failed for $merchant", it > 0L) }

    private suspend fun setRefund(vararg ids: Long, amount: Double) =
        ids.forEach { dao.updateRefundNettedAmount(it, amount) }

    private suspend fun categoryTotal(categoryId: Long, start: Long, end: Long, currency: String = "EUR") =
        dao.getCategoryTotalsBetweenByCurrency(start, end)
            .singleOrNull { it.categoryId == categoryId && it.currency == currency }

    private suspend fun spendTotal(start: Long, end: Long, currency: String = "EUR"): Double =
        dao.getTotalSpentBetweenByCurrency(start, end).filter { it.currency == currency }.sumOf { it.total }

    private suspend fun incomeTotal(start: Long, end: Long, currency: String = "EUR"): Double =
        dao.getDepositTotalsBetweenByCurrency(start, end).filter { it.currency == currency }.sumOf { it.total }

    /** AE fixture: P1 Zara 100 (Nov 3, Clothing), other Nov Clothing 60, D1 40 refund (Dec 2), S salary 2000 (Dec 1). */
    private class Ae1(val p1: Long, val d1: Long)

    private suspend fun seedAe1(): Ae1 {
        val p1 = insert(100.0, dateMs(2026, 11, 3, 12, 0), "Zara", categoryId = clothingId)
        insert(60.0, dateMs(2026, 11, 15, 12, 0), "Other Shop", categoryId = clothingId)
        val d1 = insert(40.0, dateMs(2026, 12, 2, 12, 0), "ZARA", type = TransactionType.DEPOSIT)
        insert(2000.0, dateMs(2026, 12, 1, 12, 0), "Salary Co", type = TransactionType.DEPOSIT)
        return Ae1(p1, d1)
    }

    // ── AE-1 and AE-13 ───────────────────────────────────────────

    @Test
    fun `AE1 INV6 INV8 INV9 INV10 linking a December refund nets November Clothing and December income`() = runTest {
        val ae1 = seedAe1()

        // Control, before the link: November Clothing 160 (10 over a 150 budget), December income 2040.
        assertEquals(160.0, categoryTotal(clothingId, nov0, dec0)!!.total, 0.0)
        assertEquals(2040.0, incomeTotal(dec0, jan0), 0.0)
        val netBefore = incomeTotal(nov0, jan0) - spendTotal(nov0, jan0)
        assertEquals(1880.0, netBefore, 0.0)

        setRefund(ae1.p1, ae1.d1, amount = 40.0)

        val clothing = categoryTotal(clothingId, nov0, dec0)!!
        assertEquals(120.0, clothing.total, 0.0)            // 30 under the 150 budget
        assertEquals(2, clothing.txCount)                    // the purchase still counts
        assertEquals(0.0, spendTotal(dec0, jan0), 0.0)       // December spend is unchanged (none)
        assertEquals(2000.0, incomeTotal(dec0, jan0), 0.0)   // it was 2040
        val monthlyDec = dao.getMonthlyDeposits().single { it.month == "2026-12" }
        assertEquals(2000.0, monthlyDec.total, 0.0)
        // Net over Nov-Dec is conserved: 2000 - 120 == 2040 - 160.
        assertEquals(1880.0, incomeTotal(nov0, jan0) - spendTotal(nov0, jan0), 0.0)
    }

    @Test
    fun `AE13 INV6 unlinking restores November Clothing and December income`() = runTest {
        val ae1 = seedAe1()
        setRefund(ae1.p1, ae1.d1, amount = 40.0)
        assertEquals(120.0, categoryTotal(clothingId, nov0, dec0)!!.total, 0.0)

        setRefund(ae1.p1, ae1.d1, amount = 0.0)

        assertEquals(160.0, categoryTotal(clothingId, nov0, dec0)!!.total, 0.0)
        assertEquals(2040.0, incomeTotal(dec0, jan0), 0.0)
        assertEquals(2040.0, dao.getMonthlyDeposits().single { it.month == "2026-12" }.total, 0.0)
    }

    @Test
    fun `AE2 INV6 a second refund covering the rest takes P1 to zero but it still counts`() = runTest {
        val ae1 = seedAe1()
        val d2 = insert(60.0, dateMs(2026, 12, 8, 12, 0), "ZARA", type = TransactionType.DEPOSIT)
        setRefund(ae1.p1, amount = 100.0)
        setRefund(ae1.d1, amount = 40.0)
        setRefund(d2, amount = 60.0)

        val clothing = categoryTotal(clothingId, nov0, dec0)!!
        assertEquals(60.0, clothing.total, 0.0)    // only the other Clothing spend remains
        assertEquals(2, clothing.txCount)           // P1 still counts in the purchase count
        assertEquals(2000.0, incomeTotal(dec0, jan0), 0.0) // D1 and D2 both 0.0
    }

    @Test
    fun `AE4 INV6 INV8 a refund larger than the purchase leaves the rest of the deposit as income`() = runTest {
        val other = category("Other")
        val p2 = insert(30.0, dateMs(2026, 11, 5, 12, 0), "Shop Two", categoryId = other)
        val d4 = insert(35.0, dateMs(2026, 12, 3, 12, 0), "SHOP TWO", type = TransactionType.DEPOSIT)
        setRefund(p2, d4, amount = 30.0)

        assertEquals(0.0, categoryTotal(other, nov0, dec0)!!.total, 0.0)
        assertEquals(5.0, incomeTotal(dec0, jan0), 0.0)
    }

    // ── AE-7, 8, 9, 10 ───────────────────────────────────────────

    @Test
    fun `AE7 AE8 INV6 shared shares are taken from the net amount in the SQL totals`() = runTest {
        val pctCategory = category("Shared percent")
        val fixedCategory = category("Shared fixed")
        val d6 = insert(40.0, dateMs(2026, 12, 4, 12, 0), "Refund Six", type = TransactionType.DEPOSIT)
        val d7 = insert(40.0, dateMs(2026, 12, 4, 13, 0), "Refund Seven", type = TransactionType.DEPOSIT)
        val p4 = insert(100.0, dateMs(2026, 11, 6, 12, 0), "Shop Four", categoryId = pctCategory, shared = true, pct = 50)
        val p5 = insert(100.0, dateMs(2026, 11, 7, 12, 0), "Shop Five", categoryId = fixedCategory, shared = true, fixed = 30.0)
        // Control before the link: 50.0 and 30.0.
        assertEquals(50.0, categoryTotal(pctCategory, nov0, dec0)!!.total, 0.0)
        assertEquals(30.0, categoryTotal(fixedCategory, nov0, dec0)!!.total, 0.0)

        setRefund(p4, d6, amount = 40.0)
        setRefund(p5, d7, amount = 40.0)

        assertEquals(30.0, categoryTotal(pctCategory, nov0, dec0)!!.total, 0.0)  // (100-40)*50/100
        assertEquals(18.0, categoryTotal(fixedCategory, nov0, dec0)!!.total, 0.0) // 30*60/100
        assertEquals(0.0, incomeTotal(dec0, jan0), 0.0)                           // D6 and D7 add 0
    }

    @Test
    fun `AE9 INV6 INV8 a not-mine purchase stays out and its fully applied deposit leaves income`() = runTest {
        val other = category("Other")
        val p6 = insert(80.0, dateMs(2026, 11, 8, 12, 0), "Shop Six", categoryId = other, notMine = true)
        val d8 = insert(80.0, dateMs(2026, 12, 5, 12, 0), "SHOP SIX", type = TransactionType.DEPOSIT)
        assertEquals(null, categoryTotal(other, nov0, dec0))            // not mine: no spend row at all
        assertEquals(80.0, incomeTotal(dec0, jan0), 0.0)

        setRefund(p6, d8, amount = 80.0)

        assertEquals(null, categoryTotal(other, nov0, dec0))
        assertEquals(0.0, incomeTotal(dec0, jan0), 0.0)                 // income falls by 80
    }

    @Test
    fun `AE10 INV6 a foreign currency purchase nets in its own currency bucket`() = runTest {
        val p7 = insert(100.0, dateMs(2026, 11, 5, 12, 0), "Shop Seven", currency = "USD", categoryId = clothingId)
        val d9 = insert(40.0, dateMs(2026, 12, 1, 12, 0), "SHOP SEVEN", type = TransactionType.DEPOSIT, currency = "USD")
        setRefund(p7, d9, amount = 40.0)

        assertEquals(60.0, spendTotal(nov0, dec0, "USD"), 0.0)
        assertEquals(0.0, incomeTotal(dec0, jan0, "USD"), 0.0)
        assertEquals(0.0, spendTotal(nov0, dec0, "EUR"), 0.0)
    }

    // ── AE-20 ────────────────────────────────────────────────────

    @Test
    fun `AE20 INV6 JPY BHD and imported EUR amounts net at the currency scale`() = runTest {
        val jpy = insert(1000.0, dateMs(2026, 11, 5, 12, 0), "Shop Yen", currency = "JPY", refund = 333.0)
        val bhd = insert(10.125, dateMs(2026, 11, 6, 12, 0), "Shop Dinar", currency = "BHD", refund = 2.005)
        val eur = insert(10.005, dateMs(2026, 11, 7, 12, 0), "Shop Euro", currency = "EUR", refund = 10.0)
        assertTrue(jpy > 0 && bhd > 0 && eur > 0)

        assertEquals(667.0, spendTotal(nov0, dec0, "JPY"), 0.0)
        assertEquals(8.12, spendTotal(nov0, dec0, "BHD"), 1e-9)
        val euro = spendTotal(nov0, dec0, "EUR")
        assertEquals(0.005, euro, 1e-9)
        assertTrue(euro >= 0.0)
    }

    @Test
    fun `AE20 INV6 three refunds summing to exactly 100 leave exactly zero`() = runTest {
        val other = category("Other")
        val p10 = insert(100.0, dateMs(2026, 11, 9, 12, 0), "Shop Ten", categoryId = other, refund = 100.0)
        assertTrue(p10 > 0)

        val bucket = categoryTotal(other, nov0, dec0)!!
        assertEquals(0L, bucket.total.toRawBits())
        assertEquals(1, bucket.txCount)
    }

    // ── INV-8 sweep ──────────────────────────────────────────────

    @Test
    fun `INV8 income minus spend is unchanged by any applied amount between zero and the purchase`() = runTest {
        val other = category("Other")
        val p = insert(100.0, dateMs(2026, 11, 9, 12, 0), "Shop Sweep", categoryId = other)
        val d = insert(150.0, dateMs(2026, 12, 9, 12, 0), "SHOP SWEEP", type = TransactionType.DEPOSIT)
        val gross = incomeTotal(nov0, jan0) - spendTotal(nov0, jan0)
        assertEquals(50.0, gross, 0.0)

        listOf(0.01, 40.0, 99.99, 100.0, 0.0).forEach { applied ->
            setRefund(p, d, amount = applied)
            assertEquals(
                "applied=$applied",
                gross,
                incomeTotal(nov0, jan0) - spendTotal(nov0, jan0),
                1e-9
            )
        }
    }

    // ── INV-9: every aggregate form uses the net value ───────────

    @Test
    fun `INV9 merchant SUM AVG MIN and MAX see the net amounts`() = runTest {
        val a = insert(100.0, dateMs(2026, 11, 3, 12, 0), "Zara Two", refund = 40.0) // 60
        val b = insert(100.0, dateMs(2026, 11, 4, 12, 0), "Zara Two")                  // 100
        assertTrue(a > 0 && b > 0)

        val stats = dao.getAllMerchantStats().single { it.merchantName == "zara two" }

        assertEquals(160.0, stats.totalAmount, 0.0)
        assertEquals(2, stats.transactionCount)
        assertEquals(80.0, stats.averageAmount, 1e-9)
        assertEquals(60.0, stats.minAmount, 0.0)
        assertEquals(100.0, stats.maxAmount, 0.0)
    }

    @Test
    fun `INV9 top merchants and the percentile feed use the net amount`() = runTest {
        insert(100.0, dateMs(2026, 11, 3, 12, 0), "Zara Three", refund = 40.0)
        insert(20.0, dateMs(2026, 11, 3, 13, 0), "Small Shop")

        val top = dao.getTopMerchantsForPeriod(nov0, dec0).single { it.merchantName == "zara three" }
        assertEquals(60.0, top.totalAmount, 0.0)
        assertEquals(60.0, top.maxAmount, 0.0)
        // Ascending feed of effective amounts: the 100.0 gross never appears.
        assertEquals(listOf(20.0, 60.0), dao.getAmountsForPercentileCalc(nov0, dec0))
    }

    @Test
    fun `INV9 day of week and monthly currency totals use the net amount`() = runTest {
        // 06:00 and 06:30 UTC fall on the same local day in every real time zone offset.
        insert(100.0, dateMs(2026, 11, 3, 6, 0), "Zara Four", refund = 40.0)
        insert(20.0, dateMs(2026, 11, 3, 6, 30), "Small Shop Two")

        val dayTotals = dao.getDayOfWeekPattern(nov0, dec0)
        assertEquals(80.0, dayTotals.sumOf { it.total }, 0.0)
        assertEquals(40.0, dayTotals.single().avgAmount, 1e-9)

        val month = dao.getMonthlyTotalsBetweenByCurrency(nov0, dec0).single { it.monthKey == "2026-11" && it.currency == "EUR" }
        assertEquals(80.0, month.total, 0.0)
        assertEquals(2, month.txCount)
    }

    @Test
    fun `INV9 the recurring-candidate variance check works on net amounts`() = runTest {
        // Three 50.00 purchases; one is netted to 30.00. Spread 20 is not below 15% of the 43.33 average.
        insert(50.0, dateMs(2026, 9, 5, 12, 0), "Gym Club")
        insert(50.0, dateMs(2026, 10, 5, 12, 0), "Gym Club")
        val third = insert(50.0, dateMs(2026, 11, 5, 12, 0), "Gym Club")
        assertEquals(listOf("gym club"), dao.getRecurringCandidates().map { it.merchantName }) // control: gross is stable

        setRefund(third, amount = 20.0)
        assertFalse(dao.getRecurringCandidates().any { it.merchantName == "gym club" })

        // A one-unit refund keeps the spread inside the tolerance, and the stats show the net values.
        setRefund(third, amount = 1.0)
        val candidate = dao.getRecurringCandidates().single { it.merchantName == "gym club" }
        assertEquals(149.0, candidate.totalAmount, 0.0)
        assertEquals(49.0, candidate.minAmount, 0.0)
        assertEquals(50.0, candidate.maxAmount, 0.0)
    }

    @Test
    fun `INV9 documented exception the merchant suggestion keeps the inner amount for a flagged row`() = runTest {
        // Unflagged Zara 100 with a 40 refund suggests 60; the flagged row (installment purchase record)
        // keeps its inner ownership amount, exactly as before refund netting. Average (60 + 100) / 2.
        insert(100.0, dateMs(2026, 11, 3, 12, 0), "Zara Five", refund = 40.0)
        insert(100.0, dateMs(2026, 11, 4, 12, 0), "Zara Five", refund = 40.0, reason = "INSTALLMENT_PURCHASE")

        val suggestion = dao.searchMerchants("zara five").single()

        assertEquals(2, suggestion.txCount)
        assertEquals(80.0, suggestion.avgAmount, 1e-9)
    }

    // ── Flagged deposits (guardian minor) ────────────────────────

    /**
     * No production path writes a spending-exclusion reason on a DEPOSIT: the only caller of
     * TransactionLifecycleCoordinator.setSpendingExclusionDbOnly is InstallmentPlanLifecycleCoordinator
     * (around line 1657), and it flags installment PURCHASE rows. The totals still define the
     * behaviour of such a row (a backup or a future path could produce one): it adds 0 to income.
     */
    @Test
    fun `a flagged deposit is excluded from the deposit totals and an unflagged one counts in full`() = runTest {
        insert(500.0, dateMs(2026, 12, 3, 12, 0), "Flagged Deposit", type = TransactionType.DEPOSIT, reason = "INSTALLMENT_REVERSED")
        insert(300.0, dateMs(2026, 12, 4, 12, 0), "Plain Deposit", type = TransactionType.DEPOSIT)

        assertEquals(300.0, incomeTotal(dec0, jan0), 0.0)
        assertEquals(300.0, dao.getMonthlyDeposits().single { it.month == "2026-12" }.total, 0.0)
    }

    @Test
    fun `a flagged deposit with a refund amount still adds zero and an unflagged applied deposit nets`() = runTest {
        val flagged = insert(500.0, dateMs(2026, 12, 3, 12, 0), "Flagged Deposit", type = TransactionType.DEPOSIT, reason = "INSTALLMENT_PURCHASE")
        val plain = insert(300.0, dateMs(2026, 12, 4, 12, 0), "Plain Deposit", type = TransactionType.DEPOSIT)
        setRefund(flagged, amount = 100.0)
        assertEquals(300.0, incomeTotal(dec0, jan0), 0.0)

        setRefund(plain, amount = 100.0)
        assertEquals(200.0, incomeTotal(dec0, jan0), 0.0)
    }

    // ── INV-11: the list stays gross ─────────────────────────────

    @Test
    fun `INV11 the comparable home value and the inner effective amount stay gross for a netted row`() = runTest {
        val id = insert(100.0, dateMs(2026, 11, 3, 12, 0), "Zara Six", refund = 40.0)

        val comparable = db.openHelper.readableDatabase.query(
            "SELECT ${ExpenseDao.COMPARABLE_HOME_VALUE_E_SQL} FROM expenses e WHERE e.id = ?",
            arrayOf<Any?>("EUR", "EUR", id)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getDouble(0)
        }
        val inner = db.openHelper.readableDatabase.query(
            "SELECT ${ExpenseDao.EFFECTIVE_AMOUNT_E_SQL} FROM expenses e WHERE e.id = $id"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getDouble(0)
        }

        assertEquals(100.0, comparable, 0.0)
        assertEquals(100.0, inner, 0.0)
    }
}
