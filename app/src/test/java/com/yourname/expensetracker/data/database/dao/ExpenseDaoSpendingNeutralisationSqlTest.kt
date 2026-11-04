package com.yourname.expensetracker.data.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real in-memory Room/SQLite proofs of the SQL predicates that decide whether a flagged
 * (`spendingExclusionReason` set) or not-mine row is visible. A DAO mock re-implementing the predicate
 * cannot catch a deleted or weakened clause; these tests can.
 *
 * - Search keyset: ownership only (`isNotMine = 0`). Flagged rows and deposits are returned, not-mine hidden.
 * - Tab badge count `countExpensesBetween`: ownership only, flagged rows are counted.
 * - Location (map) queries and `getCountForPeriod`: full spending predicate. Flagged and not-mine rows excluded.
 *
 * For the limited queries the excluded rows are the NEWEST, so a missing predicate would fill the limit
 * with them and push the normal row out. Fixed UTC dates and synthetic merchants only.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpenseDaoSpendingNeutralisationSqlTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: ExpenseDao

    private val start = dateMs(2026, 5, 1)
    private val end = dateMs(2026, 6, 1)
    private val normalDate = dateMs(2026, 5, 10)
    private val flaggedDate = dateMs(2026, 5, 20)
    private val notMineDate = dateMs(2026, 5, 21)
    private val depositDate = dateMs(2026, 5, 22)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        dao = db.expenseDao()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun expense(
        merchant: String,
        date: Long,
        type: TransactionType = TransactionType.PURCHASE,
        isNotMine: Boolean = false,
        exclusionReason: String? = null,
        located: Boolean = false
    ) = Expense(
        amount = 10.0,
        currency = "EUR",
        merchant = merchant,
        merchantKey = merchant.lowercase(),
        transactionType = type,
        date = date,
        isNotMine = isNotMine,
        spendingExclusionReason = exclusionReason,
        latitude = if (located) 37.97 else null,
        longitude = if (located) 23.72 else null
    )

    /** Normal (oldest), flagged and not-mine purchase rows, all located or all unlocated. */
    private suspend fun seedFour(located: Boolean) {
        dao.insert(expense("Normal", normalDate, located = located))
        dao.insert(expense("Flagged", flaggedDate, exclusionReason = "INSTALLMENT_PURCHASE", located = located))
        dao.insert(expense("NotMine", notMineDate, isNotMine = true, located = located))
    }

    private fun List<Expense>.merchants(): List<String> = map { it.merchant }

    // ── Search keyset: ownership only ──────────────────────────────────────

    private suspend fun keyset(transactionType: String? = null): List<Expense> =
        dao.getExpensesFilteredKeyset(
            startDate = start, endDate = end,
            categoryIds = emptyList(), categoryFilterEnabled = 0,
            merchantLike = null, transactionType = transactionType, keywordLike = null,
            limit = 500, cursorDate = null, cursorId = null
        )

    @Test
    fun `keyset returns the flagged row and the deposit and hides the not-mine row`() = runTest {
        seedFour(located = false)
        dao.insert(expense("Deposit", depositDate, type = TransactionType.DEPOSIT))

        val merchants = keyset().merchants()

        assertEquals(listOf("Deposit", "Flagged", "Normal"), merchants) // date DESC
        assertTrue("not-mine row must be hidden", "NotMine" !in merchants)
    }

    @Test
    fun `keyset with a PURCHASE type filter keeps the flagged row and drops the deposit`() = runTest {
        seedFour(located = false)
        dao.insert(expense("Deposit", depositDate, type = TransactionType.DEPOSIT))

        assertEquals(listOf("Flagged", "Normal"), keyset("PURCHASE").merchants())
    }

    @Test
    fun `keyset with a DEPOSIT type filter returns only the deposit`() = runTest {
        seedFour(located = false)
        dao.insert(expense("Deposit", depositDate, type = TransactionType.DEPOSIT))

        assertEquals(listOf("Deposit"), keyset("DEPOSIT").merchants())
    }

    @Test
    fun `keyset cursor pages past the flagged row without skipping or repeating it`() = runTest {
        seedFour(located = false)
        val first = dao.getExpensesFilteredKeyset(
            startDate = start, endDate = end,
            categoryIds = emptyList(), categoryFilterEnabled = 0,
            merchantLike = null, transactionType = null, keywordLike = null,
            limit = 1, cursorDate = null, cursorId = null
        )
        assertEquals(listOf("Flagged"), first.merchants())

        val second = dao.getExpensesFilteredKeyset(
            startDate = start, endDate = end,
            categoryIds = emptyList(), categoryFilterEnabled = 0,
            merchantLike = null, transactionType = null, keywordLike = null,
            limit = 1, cursorDate = first.single().date, cursorId = first.single().id
        )
        assertEquals(listOf("Normal"), second.merchants())
    }

    // ── Tab badge count: ownership only, flagged rows are counted ──────────

    @Test
    fun `countExpensesBetween counts the flagged row and the deposit and skips the not-mine row`() = runTest {
        seedFour(located = false)
        dao.insert(expense("Deposit", depositDate, type = TransactionType.DEPOSIT))

        // Normal + Flagged + Deposit = 3; NotMine is not counted.
        assertEquals(3, dao.countExpensesBetween(start, end))
    }

    // ── getCountForPeriod: full spending predicate ─────────────────────────

    @Test
    fun `getCountForPeriod counts the normal purchase and excludes flagged not-mine and deposit rows`() = runTest {
        seedFour(located = false)
        dao.insert(expense("Deposit", depositDate, type = TransactionType.DEPOSIT))

        assertEquals(1, dao.getCountForPeriod(start, end))
    }

    @Test
    fun `getCountForPeriod is zero when the only purchase is flagged`() = runTest {
        dao.insert(expense("Flagged", flaggedDate, exclusionReason = "INSTALLMENT_PURCHASE"))

        assertEquals(0, dao.getCountForPeriod(start, end))
    }

    // ── Location queries: full spending predicate ──────────────────────────

    @Test
    fun `getLocatedExpensesFlow returns the normal row only`() = runTest {
        seedFour(located = true)

        assertEquals(listOf("Normal"), dao.getLocatedExpensesFlow().first().merchants())
    }

    @Test
    fun `getLocatedExpenses returns the normal row only`() = runTest {
        seedFour(located = true)

        assertEquals(listOf("Normal"), dao.getLocatedExpenses().merchants())
    }

    @Test
    fun `getLocatedExpensesBatch returns the normal row only and excluded newer rows do not use the limit`() = runTest {
        seedFour(located = true)

        assertEquals(listOf("Normal"), dao.getLocatedExpensesBatch(limit = 1).merchants())
        assertEquals(listOf("Normal"), dao.getLocatedExpensesBatch(limit = 100).merchants())
    }

    @Test
    fun `getUnlocatedExpenses returns the normal row only and excluded newer rows do not use the limit`() = runTest {
        seedFour(located = false)

        assertEquals(listOf("Normal"), dao.getUnlocatedExpenses(limit = 1).merchants())
        assertEquals(listOf("Normal"), dao.getUnlocatedExpenses(limit = 100).merchants())
    }

    @Test
    fun `getUnlocatedExpensesFlow returns the normal row only`() = runTest {
        seedFour(located = false)

        assertEquals(listOf("Normal"), dao.getUnlocatedExpensesFlow(limit = 100).first().merchants())
        assertEquals(listOf("Normal"), dao.getUnlocatedExpensesFlow(limit = 1).first().merchants())
    }

    @Test
    fun `getExpensesInBoundingBox returns the normal row only`() = runTest {
        seedFour(located = true)

        val rows = dao.getExpensesInBoundingBox(minLat = 37.0, maxLat = 38.0, minLon = 23.0, maxLon = 24.0)

        assertEquals(listOf("Normal"), rows.merchants())
    }

    @Test
    fun `getExpensesInBoundingBox returns nothing outside the box even for the normal row`() = runTest {
        seedFour(located = true)

        val rows = dao.getExpensesInBoundingBox(minLat = 10.0, maxLat = 11.0, minLon = 10.0, maxLon = 11.0)

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `getExpensesInBoundingBoxBatch returns the normal row only and excluded newer rows do not use the limit`() =
        runTest {
            seedFour(located = true)

            val one = dao.getExpensesInBoundingBoxBatch(37.0, 38.0, 23.0, 24.0, limit = 1)
            val many = dao.getExpensesInBoundingBoxBatch(37.0, 38.0, 23.0, 24.0, limit = 100)

            assertEquals(listOf("Normal"), one.merchants())
            assertEquals(listOf("Normal"), many.merchants())
        }

    @Test
    fun `countLocated counts the normal row only`() = runTest {
        seedFour(located = true)

        assertEquals(1, dao.countLocated())
    }

    @Test
    fun `countUnlocated counts the normal row only`() = runTest {
        seedFour(located = false)

        assertEquals(1, dao.countUnlocated())
    }

    @Test
    fun `located merchant totals by currency include the normal merchant only`() = runTest {
        seedFour(located = true)

        val totals = dao.getLocatedMerchantTotalsByCurrency()

        assertEquals(listOf("Normal"), totals.map { it.merchant })
        assertEquals(10.0, totals.single().total, 0.0)
    }
}
