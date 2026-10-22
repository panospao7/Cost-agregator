package com.yourname.expensetracker.data.repository

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao.Companion.COMPARABLE_HOME_VALUE_E_SQL as CV
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SQL text and bound-argument contract of the shared builder in the Transactions modes
 * ([AmountSemantics.HomeComparable], [AmountSemantics.AmountOpsDisabled]) and of
 * `countExpensesWithoutHomeValue`, captured from a mocked DAO. The `RawEffective` output is pinned
 * separately by `ExpenseRepositoryAssistantSqlGoldenTest` (unchanged).
 */
class ExpenseRepositoryAmountSemanticsSqlTest {

    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private lateinit var repository: ExpenseRepository

    private val eur = CurrencyCode.EUR
    private val usd = CurrencyCode.USD

    @Before
    fun setup() {
        every { expenseDao.getAllFlowUncapped() } returns flowOf(emptyList())
        repository = ExpenseRepository(
            mockk(relaxed = true),
            mockk<AppDatabase>(relaxed = true),
            expenseDao,
            mockk<UserCorrectionDao>(relaxed = true),
            mockk<PendingReviewDao>(relaxed = true),
            mockk<MerchantCategoryRepository>(relaxed = true),
            mockk<MerchantNormalizer>(relaxed = true),
            mockk<TransferDirectionAnalytics>(relaxed = true),
            mockk<TransactionLifecycleCoordinator>(relaxed = true),
            mockk(relaxed = true)
        )
    }

    private data class Captured(val sql: String, val args: List<Any?>)

    private class RecordingProgram : SupportSQLiteProgram {
        val bound = sortedMapOf<Int, Any?>()
        override fun bindNull(index: Int) { bound[index] = null }
        override fun bindLong(index: Int, value: Long) { bound[index] = value }
        override fun bindDouble(index: Int, value: Double) { bound[index] = value }
        override fun bindString(index: Int, value: String) { bound[index] = value }
        override fun bindBlob(index: Int, value: ByteArray) { bound[index] = value }
        override fun clearBindings() { bound.clear() }
        override fun close() = Unit
    }

    private fun record(query: SupportSQLiteQuery): Captured {
        val program = RecordingProgram()
        query.bindTo(program)
        assertEquals("argCount matches bound args", query.argCount, program.bound.size)
        val captured = Captured(query.sql, program.bound.values.toList())
        assertEquals("placeholders match args", captured.sql.count { it == '?' }, captured.args.size)
        return captured
    }

    private suspend fun page(
        sortOrder: SortOrder,
        semantics: AmountSemantics,
        searchQuery: String? = null,
        startDate: Long? = null,
        endDate: Long? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Captured {
        val slot = slot<SupportSQLiteQuery>()
        coEvery { expenseDao.getExpensesDynamic(capture(slot)) } returns emptyList()
        repository.getExpensesPagedDynamic(
            limit = limit,
            offset = offset,
            searchQuery = searchQuery,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            sortOrder = sortOrder,
            amountSemantics = semantics
        )
        return record(slot.captured)
    }

    private suspend fun count(filter: ExpenseCountFilter, home: CurrencyCode): Captured {
        val slot = slot<SupportSQLiteQuery>()
        coEvery { expenseDao.countExpensesWithoutHomeValueDynamic(capture(slot)) } returns 0
        repository.countExpensesWithoutHomeValue(filter, home)
        return record(slot.captured)
    }

    // ── HomeComparable WHERE ────────────────────────────────────────────────

    @Test
    fun `min bound binds home twice then the bound`() = runTest {
        val c = page(SortOrder.DATE_DESC, AmountSemantics.HomeComparable(eur), minAmount = 15.0)
        assertTrue(c.sql.contains("WHERE $CV >= ?\n"))
        assertEquals(listOf<Any?>("EUR", "EUR", 15.0, 50L, 0L), c.args)
    }

    @Test
    fun `max bound binds home twice then the bound`() = runTest {
        val c = page(SortOrder.DATE_DESC, AmountSemantics.HomeComparable(usd), maxAmount = 25.0)
        assertTrue(c.sql.contains("WHERE $CV <= ?\n"))
        assertEquals(listOf<Any?>("USD", "USD", 25.0, 50L, 0L), c.args)
    }

    @Test
    fun `min and max use the comparable value and never the raw effective amount alone`() = runTest {
        val c = page(SortOrder.DATE_DESC, AmountSemantics.HomeComparable(eur), minAmount = 1.0, maxAmount = 2.0)
        assertTrue(c.sql.contains("WHERE $CV >= ? AND $CV <= ?\n"))
        assertEquals(listOf<Any?>("EUR", "EUR", 1.0, "EUR", "EUR", 2.0, 50L, 0L), c.args)
    }

    @Test
    fun `home currency is never interpolated into the SQL text`() = runTest {
        val c = page(SortOrder.AMOUNT_DESC, AmountSemantics.HomeComparable(usd), minAmount = 1.0)
        assertFalse(c.sql.contains("USD"))
        assertFalse(c.sql.contains("'"))
        assertFalse(c.sql.contains("NULLS LAST"))
    }

    // ── HomeComparable ORDER BY ─────────────────────────────────────────────

    @Test
    fun `amount descending template with four home arguments and tiebreak`() = runTest {
        val c = page(SortOrder.AMOUNT_DESC, AmountSemantics.HomeComparable(eur))
        assertTrue(
            c.sql.endsWith("ORDER BY ($CV IS NULL) ASC, $CV DESC, e.date DESC, e.id DESC\nLIMIT ? OFFSET ?")
        )
        assertEquals(listOf<Any?>("EUR", "EUR", "EUR", "EUR", 50L, 0L), c.args)
    }

    @Test
    fun `amount ascending template keeps date descending tiebreak`() = runTest {
        val c = page(SortOrder.AMOUNT_ASC, AmountSemantics.HomeComparable(eur))
        assertTrue(
            c.sql.endsWith("ORDER BY ($CV IS NULL) ASC, $CV ASC, e.date DESC, e.id DESC\nLIMIT ? OFFSET ?")
        )
        assertEquals(listOf<Any?>("EUR", "EUR", "EUR", "EUR", 50L, 0L), c.args)
    }

    @Test
    fun `date templates carry an id tiebreak and no home arguments`() = runTest {
        val desc = page(SortOrder.DATE_DESC, AmountSemantics.HomeComparable(eur))
        assertTrue(desc.sql.endsWith("ORDER BY e.date DESC, e.id DESC\nLIMIT ? OFFSET ?"))
        assertEquals(listOf<Any?>(50L, 0L), desc.args)
        val asc = page(SortOrder.DATE_ASC, AmountSemantics.HomeComparable(eur))
        assertTrue(asc.sql.endsWith("ORDER BY e.date ASC, e.id ASC\nLIMIT ? OFFSET ?"))
        assertEquals(listOf<Any?>(50L, 0L), asc.args)
    }

    @Test
    fun `args follow textual placeholder order where then order by then limit and offset`() = runTest {
        val c = page(
            SortOrder.AMOUNT_DESC, AmountSemantics.HomeComparable(eur),
            searchQuery = "Coffee", startDate = 100L, endDate = 200L,
            minAmount = 15.0, maxAmount = 25.0, limit = 20, offset = 40
        )
        assertEquals(
            listOf<Any?>(
                "%Coffee%", "%Coffee%", 100L, 200L,
                "EUR", "EUR", 15.0, "EUR", "EUR", 25.0,
                "EUR", "EUR", "EUR", "EUR",
                20L, 40L
            ),
            c.args
        )
    }

    // ── Search escaping (G2-4) ──────────────────────────────────────────────

    @Test
    fun `non raw modes escape percent underscore and backslash and declare ESCAPE`() = runTest {
        for (semantics in listOf(AmountSemantics.HomeComparable(eur), AmountSemantics.AmountOpsDisabled)) {
            val c = page(SortOrder.DATE_DESC, semantics, searchQuery = "50%_off\\")
            assertTrue(
                c.sql.contains(
                    "(e.merchant LIKE ? ESCAPE '\\' OR e.categoryId IN (SELECT id FROM categories WHERE name LIKE ? ESCAPE '\\'))"
                )
            )
            assertEquals(listOf<Any?>("%50\\%\\_off\\\\%", "%50\\%\\_off\\\\%", 50L, 0L), c.args)
        }
    }

    @Test
    fun `raw effective keeps the unescaped pattern and no ESCAPE clause`() = runTest {
        val c = page(SortOrder.DATE_DESC, AmountSemantics.RawEffective, searchQuery = "50%_off\\")
        assertFalse(c.sql.contains("ESCAPE"))
        assertEquals(listOf<Any?>("%50%_off\\%", "%50%_off\\%", 50L, 0L), c.args)
    }

    // ── AmountOpsDisabled ───────────────────────────────────────────────────

    @Test
    fun `amount ops disabled emits no amount SQL and no amount arguments`() = runTest {
        for (sort in listOf(SortOrder.AMOUNT_DESC, SortOrder.AMOUNT_ASC)) {
            val c = page(sort, AmountSemantics.AmountOpsDisabled, minAmount = 15.0, maxAmount = 25.0)
            assertFalse(c.sql.contains("e.currency"))
            assertFalse(c.sql.contains("e.baseAmount"))
            assertFalse(c.sql.contains("e.amount"))
            assertFalse(c.sql.contains("CASE"))
            assertFalse(c.sql.contains("WHERE"))
            assertTrue(c.sql.endsWith("ORDER BY e.date DESC, e.id DESC\nLIMIT ? OFFSET ?"))
            assertEquals(listOf<Any?>(50L, 0L), c.args)
        }
    }

    @Test
    fun `amount ops disabled forces date descending and keeps the non amount filters`() = runTest {
        val c = page(
            SortOrder.DATE_ASC, AmountSemantics.AmountOpsDisabled,
            startDate = 100L, minAmount = 15.0
        )
        assertTrue(c.sql.contains("WHERE e.date >= ?\n"))
        assertTrue(c.sql.endsWith("ORDER BY e.date DESC, e.id DESC\nLIMIT ? OFFSET ?"))
        assertEquals(listOf<Any?>(100L, 50L, 0L), c.args)
    }

    // ── countExpensesWithoutHomeValue ───────────────────────────────────────

    @Test
    fun `empty filter count is the IS NULL predicate only`() = runTest {
        val c = count(ExpenseCountFilter(), eur)
        assertEquals("SELECT COUNT(*)\nFROM expenses e\nWHERE $CV IS NULL", c.sql)
        assertEquals(listOf<Any?>("EUR", "EUR"), c.args)
    }

    @Test
    fun `count shares the non amount where, ignores sort and paging, and binds home last`() = runTest {
        val c = count(
            ExpenseCountFilter(
                searchQuery = "Coffee",
                startDate = 100L,
                endDate = 200L,
                transactionType = TransactionType.PURCHASE,
                categoryId = 3L,
                merchantName = "Lidl",
                ownershipFilter = OwnershipFilter.SHARED
            ),
            usd
        )
        assertTrue(c.sql.startsWith("SELECT COUNT(*)\nFROM expenses e\nWHERE "))
        assertTrue(c.sql.contains("ESCAPE '\\'"))
        assertTrue(c.sql.endsWith("AND e.isSharedExpense = 1 AND $CV IS NULL"))
        assertFalse(c.sql.contains("ORDER BY"))
        assertFalse(c.sql.contains("LIMIT"))
        assertFalse(c.sql.contains(">= ? AND $CV"))
        assertEquals(
            listOf<Any?>(
                "%Coffee%", "%Coffee%", 100L, 200L, "PURCHASE", 3L,
                MerchantKeyGenerator.generate("Lidl"), "USD", "USD"
            ),
            c.args
        )
    }

    @Test
    fun `count goes through the dedicated DAO method only`() = runTest {
        count(ExpenseCountFilter(), eur)
        coVerify(exactly = 1) { expenseDao.countExpensesWithoutHomeValueDynamic(any()) }
        coVerify(exactly = 0) { expenseDao.getAssistantExpenseCountDynamic(any()) }
        coVerify(exactly = 0) { expenseDao.getExpensesDynamic(any()) }
    }
}
