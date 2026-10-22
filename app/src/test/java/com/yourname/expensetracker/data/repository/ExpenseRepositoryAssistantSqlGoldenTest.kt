package com.yourname.expensetracker.data.repository

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import io.mockk.coEvery
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
 * Golden test (AE-13, INV-11): pins the CURRENT SQL text and bound arguments that
 * `getAssistantExpensesFiltered` / `getAssistantExpenseCountFiltered` hand to the DAO.
 * The assistant keeps raw ownership-adjusted amounts (`RawEffective`): no home-currency
 * argument, no tiebreak, no LIKE escaping. A change to the shared query builder for the
 * Transactions screen must leave every expectation here byte-identical (modulo
 * whitespace inside the SQL, which is source formatting of the `SortOrder` raw strings).
 */
class ExpenseRepositoryAssistantSqlGoldenTest {

    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private lateinit var repository: ExpenseRepository

    @Before
    fun setup() {
        every { expenseDao.getAllFlowUncapped() } returns flowOf(emptyList())
        repository = ExpenseRepository(
            mockk<DatabaseWriteBarrier>(relaxed = true),
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

    // ── Captured query ──────────────────────────────────────────────────────

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

    private fun recordQuery(query: SupportSQLiteQuery): Captured {
        val program = RecordingProgram()
        query.bindTo(program)
        assertEquals("argCount matches bound args", query.argCount, program.bound.size)
        return Captured(query.sql, program.bound.values.toList())
    }

    private suspend fun list(
        block: suspend ExpenseRepository.() -> Unit
    ): Captured {
        val slot = slot<SupportSQLiteQuery>()
        coEvery { expenseDao.getAssistantExpensesDynamic(capture(slot)) } returns emptyList()
        repository.block()
        return recordQuery(slot.captured)
    }

    private suspend fun count(
        block: suspend ExpenseRepository.() -> Unit
    ): Captured {
        val slot = slot<SupportSQLiteQuery>()
        coEvery { expenseDao.getAssistantExpenseCountDynamic(capture(slot)) } returns 0
        repository.block()
        return recordQuery(slot.captured)
    }

    private fun normalized(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    // ── Pinned SQL fragments (literal text, deliberately not the DAO constants) ─

    private val effectiveAmount =
        "CASE WHEN e.isNotMine = 1 THEN 0.0 " +
            "WHEN e.isSharedExpense = 1 AND e.myShareAmount IS NOT NULL THEN e.myShareAmount " +
            "WHEN e.isSharedExpense = 1 AND e.mySharePercentage IS NOT NULL THEN e.amount * e.mySharePercentage / 100.0 " +
            "ELSE e.amount END"

    private fun amountOrder(direction: String) =
        "(CASE WHEN e.isNotMine = 1 THEN 0.0 " +
            "WHEN e.baseAmount > 0 AND e.isSharedExpense = 1 AND e.myShareAmount IS NOT NULL AND e.exchangeRateUsed > 0 " +
            "THEN e.myShareAmount * e.exchangeRateUsed " +
            "WHEN e.baseAmount > 0 AND e.isSharedExpense = 1 AND e.mySharePercentage IS NOT NULL " +
            "THEN e.baseAmount * e.mySharePercentage / 100.0 " +
            "WHEN e.baseAmount > 0 THEN e.baseAmount " +
            "ELSE ($effectiveAmount) END) $direction"

    private val safetyComment =
        "-- Safety invariant: sortOrder.sql comes from the closed SortOrder enum above. " +
            "-- Do not populate it from user input or remote config."

    private val searchClause =
        "(e.merchant LIKE ? OR e.categoryId IN (SELECT id FROM categories WHERE name LIKE ?))"

    private fun expectedList(where: String?, order: String = "e.date DESC"): String =
        listOfNotNull("SELECT e.*", "FROM expenses e", where?.let { "WHERE $it" }, safetyComment, "ORDER BY $order")
            .joinToString(" ")

    private fun expectedCount(where: String?): String =
        listOfNotNull("SELECT COUNT(*)", "FROM expenses e", where?.let { "WHERE $it" }).joinToString(" ")

    private fun assertGolden(expectedSql: String, expectedArgs: List<Any?>, actual: Captured) {
        assertEquals(expectedSql, normalized(actual.sql))
        if (actual.sql.contains("-- Safety invariant")) {
            assertTrue(
                "the safety comment must end its line so ORDER BY is not commented out",
                actual.sql.contains("remote config.\nORDER BY ")
            )
        }
        assertEquals(expectedArgs, actual.args)
        assertFalse("assistant SQL must never reference the row currency", actual.sql.contains("e.currency"))
        assertFalse("assistant SQL must never carry an escape clause", actual.sql.contains("ESCAPE"))
        assertFalse("assistant SQL must never use NULLS LAST", actual.sql.contains("NULLS LAST"))
    }

    // ── getAssistantExpensesFiltered ───────────────────────────────────────

    @Test
    fun `list without filters keeps the exact default text`() = runTest {
        val captured = list { getAssistantExpensesFiltered() }
        assertEquals(
            "SELECT e.*\n" +
                "FROM expenses e\n" +
                "-- Safety invariant: sortOrder.sql comes from the closed SortOrder enum above.\n" +
                "-- Do not populate it from user input or remote config.\n" +
                "ORDER BY e.date DESC",
            captured.sql
        )
        assertEquals(emptyList<Any?>(), captured.args)
    }

    @Test
    fun `list with min only binds the raw effective amount`() = runTest {
        val captured = list { getAssistantExpensesFiltered(minAmount = 15.0) }
        assertGolden(expectedList("$effectiveAmount >= ?"), listOf(15.0), captured)
    }

    @Test
    fun `list with max only binds the raw effective amount`() = runTest {
        val captured = list { getAssistantExpensesFiltered(maxAmount = 25.0) }
        assertGolden(expectedList("$effectiveAmount <= ?"), listOf(25.0), captured)
    }

    @Test
    fun `list with min and max binds both in clause order`() = runTest {
        val captured = list { getAssistantExpensesFiltered(minAmount = 15.0, maxAmount = 25.0) }
        assertGolden(
            expectedList("$effectiveAmount >= ? AND $effectiveAmount <= ?"),
            listOf(15.0, 25.0),
            captured
        )
    }

    @Test
    fun `list with search binds an unescaped LIKE pattern twice`() = runTest {
        val captured = list { getAssistantExpensesFiltered(searchQuery = "Coffee") }
        assertGolden(expectedList(searchClause), listOf("%Coffee%", "%Coffee%"), captured)
    }

    @Test
    fun `list with wildcard characters in search keeps them unescaped`() = runTest {
        val captured = list { getAssistantExpensesFiltered(searchQuery = "50%_off\\") }
        assertGolden(expectedList(searchClause), listOf("%50%_off\\%", "%50%_off\\%"), captured)
    }

    @Test
    fun `list with blank search adds no search clause`() = runTest {
        val captured = list { getAssistantExpensesFiltered(searchQuery = "   ") }
        assertGolden(expectedList(null), emptyList(), captured)
    }

    @Test
    fun `list with category and type filters binds ids and enum names`() = runTest {
        val captured = list {
            getAssistantExpensesFiltered(
                transactionTypes = setOf(TransactionType.PURCHASE, TransactionType.WITHDRAWAL),
                categoryIds = setOf(1L, 2L)
            )
        }
        assertGolden(
            expectedList("e.transactionType IN (?, ?) AND e.categoryId IN (?, ?)"),
            listOf("PURCHASE", "WITHDRAWAL", 1L, 2L),
            captured
        )
    }

    @Test
    fun `list with merchant filter binds generated merchant keys`() = runTest {
        val captured = list { getAssistantExpensesFiltered(merchantNames = setOf("Lidl")) }
        assertGolden(
            expectedList("e.merchantKey IN (?)"),
            listOf(MerchantKeyGenerator.generate("Lidl")),
            captured
        )
    }

    @Test
    fun `list with date range binds start inclusive and end exclusive`() = runTest {
        val captured = list { getAssistantExpensesFiltered(startDate = 100L, endDate = 200L) }
        assertGolden(expectedList("e.date >= ? AND e.date < ?"), listOf(100L, 200L), captured)
    }

    @Test
    fun `list ownership filters emit fixed predicates without arguments`() = runTest {
        val expected = mapOf(
            OwnershipFilter.MINE to "e.isNotMine = 0",
            OwnershipFilter.NOT_MINE to "e.isNotMine = 1",
            OwnershipFilter.SHARED to "e.isSharedExpense = 1",
            OwnershipFilter.TRANSFER to "e.transactionType = 'TRANSFER'"
        )
        for ((filter, predicate) in expected) {
            val captured = list { getAssistantExpensesFiltered(ownershipFilter = filter) }
            assertGolden(expectedList(predicate), emptyList(), captured)
        }
    }

    @Test
    fun `list sort orders keep the pinned ORDER BY text and no tiebreak`() = runTest {
        assertGolden(
            expectedList(null, "e.date ASC"),
            emptyList(),
            list { getAssistantExpensesFiltered(sortOrder = SortOrder.DATE_ASC) }
        )
        assertGolden(
            expectedList(null, amountOrder("DESC")),
            emptyList(),
            list { getAssistantExpensesFiltered(sortOrder = SortOrder.AMOUNT_DESC) }
        )
        assertGolden(
            expectedList(null, amountOrder("ASC")),
            emptyList(),
            list { getAssistantExpensesFiltered(sortOrder = SortOrder.AMOUNT_ASC) }
        )
    }

    @Test
    fun `list with every filter keeps clause order search dates types categories merchants amounts ownership`() = runTest {
        val captured = list {
            getAssistantExpensesFiltered(
                searchQuery = "Coffee",
                startDate = 100L,
                endDate = 200L,
                transactionTypes = setOf(TransactionType.PURCHASE),
                categoryIds = setOf(3L),
                merchantNames = setOf("Lidl"),
                ownershipFilter = OwnershipFilter.SHARED,
                minAmount = 5.0,
                maxAmount = 50.0,
                sortOrder = SortOrder.AMOUNT_DESC
            )
        }
        assertGolden(
            expectedList(
                listOf(
                    searchClause,
                    "e.date >= ?",
                    "e.date < ?",
                    "e.transactionType IN (?)",
                    "e.categoryId IN (?)",
                    "e.merchantKey IN (?)",
                    "$effectiveAmount >= ?",
                    "$effectiveAmount <= ?",
                    "e.isSharedExpense = 1"
                ).joinToString(" AND "),
                amountOrder("DESC")
            ),
            listOf(
                "%Coffee%", "%Coffee%", 100L, 200L, "PURCHASE", 3L,
                MerchantKeyGenerator.generate("Lidl"), 5.0, 50.0
            ),
            captured
        )
    }

    @Test
    fun `list has no pagination`() = runTest {
        val captured = list { getAssistantExpensesFiltered(minAmount = 1.0) }
        assertFalse(captured.sql.contains("LIMIT"))
        assertFalse(captured.sql.contains("OFFSET"))
    }

    // ── getAssistantExpenseCountFiltered ───────────────────────────────────

    @Test
    fun `count without filters is a bare COUNT with no ORDER BY`() = runTest {
        val captured = count { getAssistantExpenseCountFiltered() }
        assertEquals("SELECT COUNT(*)\nFROM expenses e", captured.sql)
        assertEquals(emptyList<Any?>(), captured.args)
    }

    @Test
    fun `count with min and max binds the raw effective amount`() = runTest {
        assertGolden(
            expectedCount("$effectiveAmount >= ?"),
            listOf(15.0),
            count { getAssistantExpenseCountFiltered(minAmount = 15.0) }
        )
        assertGolden(
            expectedCount("$effectiveAmount <= ?"),
            listOf(25.0),
            count { getAssistantExpenseCountFiltered(maxAmount = 25.0) }
        )
        assertGolden(
            expectedCount("$effectiveAmount >= ? AND $effectiveAmount <= ?"),
            listOf(15.0, 25.0),
            count { getAssistantExpenseCountFiltered(minAmount = 15.0, maxAmount = 25.0) }
        )
    }

    @Test
    fun `count with search category and type filters`() = runTest {
        val captured = count {
            getAssistantExpenseCountFiltered(
                searchQuery = "Coffee",
                transactionTypes = setOf(TransactionType.PURCHASE),
                categoryIds = setOf(1L, 2L)
            )
        }
        assertGolden(
            expectedCount("$searchClause AND e.transactionType IN (?) AND e.categoryId IN (?, ?)"),
            listOf("%Coffee%", "%Coffee%", "PURCHASE", 1L, 2L),
            captured
        )
    }

    @Test
    fun `count with every filter matches the list helper where clause`() = runTest {
        val listCaptured = list {
            getAssistantExpensesFiltered(
                searchQuery = "Coffee",
                startDate = 100L,
                endDate = 200L,
                transactionTypes = setOf(TransactionType.PURCHASE),
                categoryIds = setOf(3L),
                merchantNames = setOf("Lidl"),
                ownershipFilter = OwnershipFilter.SHARED,
                minAmount = 5.0,
                maxAmount = 50.0
            )
        }
        val countCaptured = count {
            getAssistantExpenseCountFiltered(
                searchQuery = "Coffee",
                startDate = 100L,
                endDate = 200L,
                transactionTypes = setOf(TransactionType.PURCHASE),
                categoryIds = setOf(3L),
                merchantNames = setOf("Lidl"),
                ownershipFilter = OwnershipFilter.SHARED,
                minAmount = 5.0,
                maxAmount = 50.0
            )
        }
        val where = listOf(
            searchClause,
            "e.date >= ?",
            "e.date < ?",
            "e.transactionType IN (?)",
            "e.categoryId IN (?)",
            "e.merchantKey IN (?)",
            "$effectiveAmount >= ?",
            "$effectiveAmount <= ?",
            "e.isSharedExpense = 1"
        ).joinToString(" AND ")
        assertGolden(
            expectedCount(where),
            listOf("%Coffee%", "%Coffee%", 100L, 200L, "PURCHASE", 3L, MerchantKeyGenerator.generate("Lidl"), 5.0, 50.0),
            countCaptured
        )
        assertEquals(listCaptured.args, countCaptured.args)
    }
}
