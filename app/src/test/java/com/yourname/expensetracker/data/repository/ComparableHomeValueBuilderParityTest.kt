package com.yourname.expensetracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.core.money.ComparableHomeValue
import com.yourname.expensetracker.domain.core.money.ComparableHomeValueFixture
import com.yourname.expensetracker.domain.core.money.ComparableValue
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import io.mockk.mockk
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
 * Builder-driven parity: the shared query builder in [AmountSemantics.HomeComparable] mode, run
 * against a real in-memory Room/SQLite database through the real DAO, must agree with the Kotlin
 * rule owner ([ComparableHomeValue]) over the shared [ComparableHomeValueFixture]. Covers AE-1..10,
 * 15, 16, 17, 26 (list), AE-8 (paging concatenation), the without-home-value count (AE-1, 2, 6, 9),
 * wildcard escaping, and the AmountOpsDisabled fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComparableHomeValueBuilderParityTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ExpenseRepository

    private val eur = ComparableHomeValueFixture.EUR
    private val usd = ComparableHomeValueFixture.USD

    /** AE-16 rows are seeded with shifted ids because ids 7..9 are fixture rows. */
    private val sameDateRows: List<Expense> =
        ComparableHomeValueFixture.sameDateRows.map { it.copy(id = it.id + 1_000L) }

    private val universe: List<Expense> =
        (ComparableHomeValueFixture.cases.map { it.expense } +
            ComparableHomeValueFixture.ae6BackToEur.map { it.expense } +
            ComparableHomeValueFixture.orderingRows)
            .distinctBy { it.id } + sameDateRows

    private val byId: Map<Long, Expense> = universe.associateBy { it.id }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = ExpenseRepository(
            mockk(relaxed = true),
            database,
            database.expenseDao(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk<MerchantCategoryRepository>(relaxed = true),
            mockk<MerchantNormalizer>(relaxed = true),
            mockk<TransferDirectionAnalytics>(relaxed = true),
            mockk<TransactionLifecycleCoordinator>(relaxed = true),
            mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seed(rows: List<Expense>) {
        database.clearAllTables()
        for (row in rows) assertTrue("seed ${row.id}", database.expenseDao().insert(row) > 0L)
    }

    private suspend fun listIds(
        home: CurrencyCode,
        sort: SortOrder,
        min: Double? = null,
        max: Double? = null,
        limit: Int = 10_000,
        offset: Int = 0
    ): List<Long> = repository.getExpensesPagedDynamic(
        limit = limit,
        offset = offset,
        minAmount = min,
        maxAmount = max,
        sortOrder = sort,
        amountSemantics = AmountSemantics.HomeComparable(home)
    ).map { it.expense.id }

    private suspend fun countNone(
        home: CurrencyCode,
        filter: ExpenseCountFilter = ExpenseCountFilter()
    ): Int = repository.countExpensesWithoutHomeValue(filter, home)

    private fun kotlinOrder(sort: SortOrder, home: CurrencyCode): Comparator<Expense> = when (sort) {
        SortOrder.AMOUNT_DESC -> ComparableHomeValue.comparator(home, ComparableHomeValue.Direction.DESCENDING)
        SortOrder.AMOUNT_ASC -> ComparableHomeValue.comparator(home, ComparableHomeValue.Direction.ASCENDING)
        SortOrder.DATE_DESC -> compareByDescending<Expense> { it.date }.thenByDescending { it.id }
        SortOrder.DATE_ASC -> compareBy<Expense> { it.date }.thenBy { it.id }
    }

    private fun expectedIds(home: CurrencyCode, sort: SortOrder, min: Double?, max: Double?): List<Long> =
        universe
            .filter { ComparableHomeValue.withinBounds(it, home, min, max) }
            .sortedWith(kotlinOrder(sort, home))
            .map { it.id }

    private fun rows(vararg ids: Long): List<Expense> = ids.map { byId.getValue(it) }

    // ── Whole-fixture parity (AE-1..10, 15, 17, 26) ─────────────────────────

    @Test
    fun `builder list equals Kotlin owner for every home sort and bound pair`() = runTest {
        seed(universe)
        val bounds = ComparableHomeValueFixture.bounds + (null to null)
        for (home in listOf(eur, usd)) {
            for (sort in SortOrder.values()) {
                for ((min, max) in bounds) {
                    assertEquals(
                        "home=${home.code} sort=$sort min=$min max=$max",
                        expectedIds(home, sort, min, max),
                        listIds(home, sort, min, max)
                    )
                }
            }
        }
    }

    @Test
    fun `AE-15 union rows give identical id sequences for the four sorts`() = runTest {
        seed(universe)
        for (sort in SortOrder.values()) {
            assertEquals(
                "sort=$sort",
                universe.sortedWith(kotlinOrder(sort, eur)).map { it.id },
                listIds(eur, sort)
            )
        }
    }

    // ── Concrete scenarios ──────────────────────────────────────────────────

    @Test
    fun `AE-1 no rate foreign rows sort last and are excluded by min`() = runTest {
        seed(rows(1, 2, 3, 4))
        // E2 25, E1 15, then the two None rows by date desc (ids 4, 3).
        assertEquals(listOf(2L, 1L, 4L, 3L), listIds(eur, SortOrder.AMOUNT_DESC))
        assertEquals(listOf(1L, 2L, 4L, 3L), listIds(eur, SortOrder.AMOUNT_ASC))
        assertEquals(listOf(2L, 1L), listIds(eur, SortOrder.AMOUNT_DESC, min = 15.0))
        assertEquals(2, countNone(eur))
    }

    @Test
    fun `AE-1 count of one for a single unranked row`() = runTest {
        seed(rows(1, 2, 3))
        assertEquals(1, countNone(eur))
    }

    @Test
    fun `AE-2 converted row compares by snapshot and count is zero`() = runTest {
        seed(rows(1, 2, 5))
        // C1 18.40 and E1 15 are both at or below 19; E2 25 is excluded.
        assertEquals(listOf(5L, 1L), listIds(eur, SortOrder.AMOUNT_DESC, max = 19.0))
        assertEquals(listOf(2L), listIds(eur, SortOrder.AMOUNT_DESC, min = 19.0))
        assertEquals(0, countNone(eur))
    }

    @Test
    fun `AE-3 and AE-4 legacy default snapshot compares only in the home currency`() = runTest {
        seed(rows(30, 31))
        assertEquals(listOf(30L), listIds(usd, SortOrder.AMOUNT_DESC, min = 50.0))
        assertEquals(listOf(30L, 31L), listIds(usd, SortOrder.AMOUNT_DESC))
        assertEquals(1, countNone(usd))
    }

    @Test
    fun `AE-6 home switch re-ranks rows and counts one unranked row each way`() = runTest {
        seed(rows(32, 33, 34))
        assertEquals(listOf(34L, 33L, 32L), listIds(usd, SortOrder.AMOUNT_DESC))
        assertEquals(1, countNone(usd))
        assertEquals(listOf(32L, 33L, 34L), listIds(eur, SortOrder.AMOUNT_DESC))
        assertEquals(1, countNone(eur))
    }

    @Test
    fun `AE-9 not mine rows are comparable at zero and count is zero`() = runTest {
        seed(rows(12, 13))
        assertEquals(listOf(12L, 13L), listIds(eur, SortOrder.AMOUNT_ASC, min = 0.0))
        assertEquals(listOf(13L), listIds(eur, SortOrder.AMOUNT_ASC, min = 0.01))
        assertEquals(0, countNone(eur))
    }

    @Test
    fun `AE-10 shared foreign rows compare by their share`() = runTest {
        seed(rows(14, 15, 16, 17))
        assertEquals(listOf(14L, 17L), listIds(eur, SortOrder.AMOUNT_DESC, max = 25.0))
        assertEquals(listOf(15L, 14L, 17L, 16L), listIds(eur, SortOrder.AMOUNT_DESC))
    }

    @Test
    fun `AE-17 negative home currency amount and lowercase currency`() = runTest {
        seed(rows(18, 19, 35))
        assertEquals(listOf(18L), listIds(eur, SortOrder.AMOUNT_DESC, min = -5.0, max = -5.0))
        // Under USD: row 19 is a USD row (-5), the lowercase "usd" row 35 and EUR row 18 are None.
        assertEquals(listOf(19L, 35L, 18L), listIds(usd, SortOrder.AMOUNT_DESC))
        assertEquals(1, countNone(usd, ExpenseCountFilter(searchQuery = "M35")))
    }

    @Test
    fun `AE-26 max 18_40 excludes the unrounded legacy snapshot`() = runTest {
        seed(rows(5, 22))
        assertEquals(listOf(5L), listIds(eur, SortOrder.AMOUNT_DESC, max = 18.4))
        assertEquals(listOf(22L, 5L), listIds(eur, SortOrder.AMOUNT_DESC, min = 18.4))
    }

    // ── AE-16 and AE-8 ──────────────────────────────────────────────────────

    @Test
    fun `AE-16 same date rows order by id in both date directions across pages`() = runTest {
        seed(sameDateRows)
        assertEquals(listOf(1_009L, 1_008L, 1_007L), listIds(eur, SortOrder.DATE_DESC))
        assertEquals(listOf(1_007L, 1_008L, 1_009L), listIds(eur, SortOrder.DATE_ASC))
        assertEquals(
            listOf(1_009L, 1_008L, 1_007L),
            listIds(eur, SortOrder.DATE_DESC, limit = 2, offset = 0) +
                listIds(eur, SortOrder.DATE_DESC, limit = 2, offset = 2)
        )
        assertEquals(
            listOf(1_007L, 1_008L, 1_009L),
            listIds(eur, SortOrder.DATE_ASC, limit = 2, offset = 0) +
                listIds(eur, SortOrder.DATE_ASC, limit = 2, offset = 2)
        )
    }

    @Test
    fun `AE-8 concatenated pages contain each id once in full order for every sort`() = runTest {
        seed(universe)
        val pageSize = 7
        for (home in listOf(eur, usd)) {
            for (sort in SortOrder.values()) {
                val pages = mutableListOf<Long>()
                var offset = 0
                while (true) {
                    val page = listIds(home, sort, limit = pageSize, offset = offset)
                    pages += page
                    if (page.size < pageSize) break
                    offset += pageSize
                }
                assertEquals("home=${home.code} sort=$sort ids once", pages.distinct(), pages)
                assertEquals(
                    "home=${home.code} sort=$sort order",
                    expectedIds(home, sort, null, null),
                    pages
                )
            }
        }
    }

    // ── Count over the whole fixture ────────────────────────────────────────

    @Test
    fun `count equals the number of Kotlin None rows under each home and filter`() = runTest {
        seed(universe)
        fun noneCount(home: CurrencyCode, predicate: (Expense) -> Boolean): Int =
            universe.filter(predicate).count { ComparableHomeValue.comparableValue(it, home) is ComparableValue.None }
        for (home in listOf(eur, usd)) {
            assertEquals(noneCount(home) { true }, countNone(home))
            assertEquals(
                noneCount(home) { !it.isNotMine },
                countNone(home, ExpenseCountFilter(ownershipFilter = OwnershipFilter.MINE))
            )
            assertEquals(
                noneCount(home) { it.isNotMine },
                countNone(home, ExpenseCountFilter(ownershipFilter = OwnershipFilter.NOT_MINE))
            )
            assertEquals(
                noneCount(home) { it.isSharedExpense },
                countNone(home, ExpenseCountFilter(ownershipFilter = OwnershipFilter.SHARED))
            )
            assertEquals(
                noneCount(home) { it.merchant.contains("M1", ignoreCase = true) },
                countNone(home, ExpenseCountFilter(searchQuery = "M1"))
            )
            assertEquals(
                noneCount(home) { it.date >= ComparableHomeValueFixture.BASE_DATE + 20_000L },
                countNone(home, ExpenseCountFilter(startDate = ComparableHomeValueFixture.BASE_DATE + 20_000L))
            )
        }
    }

    // ── Wildcard escape (G2-4) ──────────────────────────────────────────────

    private fun merchantRow(id: Long, merchant: String, currency: String = "EUR") = Expense(
        id = id,
        amount = 10.0,
        currency = currency,
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = ComparableHomeValueFixture.BASE_DATE + id * 1_000L
    )

    private suspend fun seedWildcardRows() = seed(
        listOf(
            merchantRow(1, "50%_off", currency = "USD"), // no snapshot, foreign: None under EUR
            merchantRow(2, "50xxoff"),
            merchantRow(3, "a\\b"),
            merchantRow(4, "plain")
        )
    )

    private suspend fun searchIds(search: String, semantics: AmountSemantics): Set<Long> =
        repository.getExpensesPagedDynamic(
            limit = 100,
            offset = 0,
            searchQuery = search,
            amountSemantics = semantics
        ).map { it.expense.id }.toSet()

    @Test
    fun `search treats percent underscore and backslash as literals in non raw modes`() = runTest {
        seedWildcardRows()
        for (semantics in listOf(AmountSemantics.HomeComparable(eur), AmountSemantics.AmountOpsDisabled)) {
            assertEquals(setOf(1L), searchIds("%", semantics))
            assertEquals(setOf(1L), searchIds("_", semantics))
            assertEquals(setOf(3L), searchIds("\\", semantics))
            assertEquals(setOf(1L), searchIds("50%_off", semantics))
            assertEquals(setOf(4L), searchIds("lain", semantics))
        }
    }

    @Test
    fun `raw effective search keeps wildcards active for the assistant`() = runTest {
        seedWildcardRows()
        val ids = repository.getAssistantExpensesFiltered(searchQuery = "%").map { it.expense.id }.toSet()
        assertEquals(setOf(1L, 2L, 3L, 4L), ids)
    }

    @Test
    fun `count search escapes wildcards too`() = runTest {
        seedWildcardRows()
        assertEquals(1, countNone(eur, ExpenseCountFilter(searchQuery = "%")))
        assertEquals(0, countNone(eur, ExpenseCountFilter(searchQuery = "plain")))
    }

    // ── AmountOpsDisabled ───────────────────────────────────────────────────

    @Test
    fun `amount ops disabled ignores bounds and falls back to date descending`() = runTest {
        seed(universe)
        val ids = repository.getExpensesPagedDynamic(
            limit = 10_000,
            offset = 0,
            minAmount = 1_000_000.0,
            maxAmount = -1.0,
            sortOrder = SortOrder.AMOUNT_ASC,
            amountSemantics = AmountSemantics.AmountOpsDisabled
        ).map { it.expense.id }
        assertEquals(expectedIds(eur, SortOrder.DATE_DESC, null, null), ids)
    }
}
