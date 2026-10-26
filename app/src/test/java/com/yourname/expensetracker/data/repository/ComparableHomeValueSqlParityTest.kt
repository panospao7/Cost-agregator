package com.yourname.expensetracker.data.repository

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao.Companion.COMPARABLE_HOME_VALUE_E_SQL
import com.yourname.expensetracker.data.database.dao.ExpenseDao.Companion.EFFECTIVE_AMOUNT_E_SQL
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.core.money.ComparableHomeValue
import com.yourname.expensetracker.domain.core.money.ComparableHomeValueFixture
import com.yourname.expensetracker.domain.core.money.ComparableValue
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parity between the Kotlin rule owner ([ComparableHomeValue]) and its SQL mirror
 * ([COMPARABLE_HOME_VALUE_E_SQL]) over the shared [ComparableHomeValueFixture], on a real
 * in-memory Room/SQLite database. Value/NULL membership, values, inclusive bounds and the
 * INV-5 ordering must agree; the home currency is always a bound argument.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComparableHomeValueSqlParityTest {

    private lateinit var database: AppDatabase

    /** Every fixture row once, by id (rows evaluated under two homes are seeded once). */
    private val seededRows: List<Expense> =
        (ComparableHomeValueFixture.cases.map { it.expense } +
            ComparableHomeValueFixture.ae6BackToEur.map { it.expense } +
            ComparableHomeValueFixture.orderingRows)
            .distinctBy { it.id }

    private val allCases: List<ComparableHomeValueFixture.Case> =
        ComparableHomeValueFixture.cases + ComparableHomeValueFixture.ae6BackToEur

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        for (row in seededRows) {
            assertTrue("seed ${row.id}", database.expenseDao().insert(row) > 0L)
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun query(sql: String, args: Array<Any>): List<Pair<Long, Double?>> {
        val rows = mutableListOf<Pair<Long, Double?>>()
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
            while (cursor.moveToNext()) {
                val value = if (cursor.columnCount > 1 && !cursor.isNull(1)) cursor.getDouble(1) else null
                rows.add(cursor.getLong(0) to value)
            }
        }
        return rows
    }

    private fun cvById(home: CurrencyCode): Map<Long, Double?> =
        query(
            "SELECT e.id, $COMPARABLE_HOME_VALUE_E_SQL FROM expenses e",
            arrayOf(home.code, home.code)
        ).toMap()

    // ── Constant shape ──────────────────────────────────────────────────────

    @Test
    fun `constant has exactly two placeholders and embeds the effective amount mirror`() {
        assertEquals(2, COMPARABLE_HOME_VALUE_E_SQL.count { it == '?' })
        assertTrue(COMPARABLE_HOME_VALUE_E_SQL.contains(EFFECTIVE_AMOUNT_E_SQL))
    }

    @Test
    fun `constant never interpolates a currency literal and does not use NULLS LAST`() {
        assertFalse("no quoted literal", COMPARABLE_HOME_VALUE_E_SQL.contains("'"))
        assertFalse(COMPARABLE_HOME_VALUE_E_SQL.contains("NULLS LAST", ignoreCase = true))
    }

    @Test
    fun `first placeholder binds the row currency and second binds the snapshot currency`() {
        val firstQuestion = COMPARABLE_HOME_VALUE_E_SQL.indexOf('?')
        val secondQuestion = COMPARABLE_HOME_VALUE_E_SQL.lastIndexOf('?')
        assertTrue(COMPARABLE_HOME_VALUE_E_SQL.substring(0, firstQuestion).trimEnd().endsWith("e.currency ="))
        assertTrue(COMPARABLE_HOME_VALUE_E_SQL.substring(0, secondQuestion).trimEnd().endsWith("e.baseCurrency ="))

        // Fixture id 38: EUR 100, snapshot (108.0 / USD / 1.08).
        fun cvOf(id: Long, first: String, second: String): Double? =
            query(
                "SELECT e.id, $COMPARABLE_HOME_VALUE_E_SQL FROM expenses e WHERE e.id = $id",
                arrayOf(first, second)
            ).single().second
        assertEquals(108.0, cvOf(38L, "USD", "USD")!!, 0.0)
        assertEquals(100.0, cvOf(38L, "EUR", "EUR")!!, 0.0)
        assertEquals(100.0, cvOf(38L, "EUR", "USD")!!, 0.0)
        assertNull(cvOf(38L, "USD", "EUR"))
    }

    // ── Value / NULL membership and values ─────────────────────────────────

    @Test
    fun `SQL mirror agrees with the fixture expectations under each home currency`() {
        for (home in listOf(ComparableHomeValueFixture.EUR, ComparableHomeValueFixture.USD)) {
            val sqlCv = cvById(home)
            for (case in allCases.filter { it.home == home }) {
                val actual = sqlCv.getValue(case.expense.id)
                if (case.expectedCv == null) {
                    assertNull("${case.label} (SQL)", actual)
                } else {
                    assertNotNull("${case.label} (SQL) expected a value", actual)
                    assertEquals(case.label, case.expectedCv, actual!!, 1e-12)
                }
            }
        }
    }

    @Test
    fun `SQL mirror and Kotlin owner agree row by row over every seeded row`() {
        for (home in listOf(ComparableHomeValueFixture.EUR, ComparableHomeValueFixture.USD)) {
            val sqlCv = cvById(home)
            for (row in seededRows) {
                val kotlinCv = ComparableHomeValue.comparableValue(row, home)
                val sql = sqlCv.getValue(row.id)
                when (kotlinCv) {
                    is ComparableValue.Value -> {
                        assertNotNull("row ${row.id} home ${home.code}: SQL NULL but Kotlin Value", sql)
                        assertEquals("row ${row.id} home ${home.code}", kotlinCv.amountInHome, sql!!, 0.0)
                    }
                    is ComparableValue.None ->
                        assertNull("row ${row.id} home ${home.code}: SQL value but Kotlin None", sql)
                }
            }
        }
    }

    // ── Bounds ──────────────────────────────────────────────────────────────

    @Test
    fun `inclusive min and max bounds select the same ids as the Kotlin owner`() {
        for (home in listOf(ComparableHomeValueFixture.EUR, ComparableHomeValueFixture.USD)) {
            for ((min, max) in ComparableHomeValueFixture.bounds) {
                val clauses = mutableListOf<String>()
                val args = mutableListOf<Any>()
                if (min != null) {
                    clauses.add("$COMPARABLE_HOME_VALUE_E_SQL >= ?")
                    args.add(home.code); args.add(home.code); args.add(min)
                }
                if (max != null) {
                    clauses.add("$COMPARABLE_HOME_VALUE_E_SQL <= ?")
                    args.add(home.code); args.add(home.code); args.add(max)
                }
                val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
                val sqlIds = query("SELECT e.id FROM expenses e $where", args.toTypedArray())
                    .map { it.first }.toSet()
                val kotlinIds = seededRows
                    .filter { ComparableHomeValue.withinBounds(it, home, min, max) }
                    .map { it.id }.toSet()
                assertEquals("home=${home.code} min=$min max=$max", kotlinIds, sqlIds)
            }
        }
    }

    @Test
    fun `AE-26 max 18_40 excludes the unrounded legacy snapshot and min 18_40 includes it`() {
        val sqlMax = query(
            "SELECT e.id FROM expenses e WHERE $COMPARABLE_HOME_VALUE_E_SQL <= ?",
            arrayOf("EUR", "EUR", 18.4)
        ).map { it.first }
        assertFalse(sqlMax.contains(22L))
        val sqlMin = query(
            "SELECT e.id FROM expenses e WHERE $COMPARABLE_HOME_VALUE_E_SQL >= ?",
            arrayOf("EUR", "EUR", 18.4)
        ).map { it.first }
        assertTrue(sqlMin.contains(22L))
    }

    // ── Ordering (INV-5 templates, spec 2.3) ────────────────────────────────

    @Test
    fun `SQL ordering templates match the Kotlin comparator for both directions and homes`() {
        for (home in listOf(ComparableHomeValueFixture.EUR, ComparableHomeValueFixture.USD)) {
            for (direction in ComparableHomeValue.Direction.values()) {
                val keyword = if (direction == ComparableHomeValue.Direction.DESCENDING) "DESC" else "ASC"
                val sqlOrder = query(
                    "SELECT e.id FROM expenses e " +
                        "ORDER BY ($COMPARABLE_HOME_VALUE_E_SQL IS NULL) ASC, " +
                        "$COMPARABLE_HOME_VALUE_E_SQL $keyword, e.date DESC, e.id DESC",
                    arrayOf(home.code, home.code, home.code, home.code)
                ).map { it.first }
                val kotlinOrder = seededRows
                    .sortedWith(ComparableHomeValue.comparator(home, direction))
                    .map { it.id }
                assertEquals("home=${home.code} $keyword", kotlinOrder, sqlOrder)
            }
        }
    }

    @Test
    fun `AE-16 same-date rows order by id with the date sort tiebreak`() = runTest {
        // Rows 7, 8, 9 share one date; they are not part of the seeded set, so insert them here.
        for (row in ComparableHomeValueFixture.sameDateRows) {
            assertTrue(database.expenseDao().insert(row.copy(id = row.id + 1_000L)) > 0L)
        }
        val ids = listOf(1_007L, 1_008L, 1_009L)
        val inList = ids.joinToString(",")
        val desc = query("SELECT e.id FROM expenses e WHERE e.id IN ($inList) ORDER BY e.date DESC, e.id DESC", emptyArray())
        val asc = query("SELECT e.id FROM expenses e WHERE e.id IN ($inList) ORDER BY e.date ASC, e.id ASC", emptyArray())
        assertEquals(listOf(1_009L, 1_008L, 1_007L), desc.map { it.first })
        assertEquals(listOf(1_007L, 1_008L, 1_009L), asc.map { it.first })
    }
}
