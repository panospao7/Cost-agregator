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

    // ── Neutralisation parity against Expense model (slice S3) ──────────────

    @Test
    fun `flagged foreign row WITH valid snapshot ranks at its snapshot 550_0 not the model effectiveAmount 0_0 in both Kotlin and SQL`() = runTest {
        val neutralisedForeignWithSnapshot = Expense(
            id = 2_001L,
            amount = 600.0,
            currency = "USD",
            merchant = "M2001",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = ComparableHomeValueFixture.BASE_DATE + 2_001_000L,
            baseAmount = 550.0,
            baseCurrency = "EUR",
            exchangeRateUsed = 0.916667,
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        assertTrue(database.expenseDao().insert(neutralisedForeignWithSnapshot) > 0L)

        // The totals-side model value stays 0.0 (list rule differs from the totals rule).
        assertEquals(0.0, neutralisedForeignWithSnapshot.effectiveAmount, 0.0)
        assertEquals(0.0, neutralisedForeignWithSnapshot.normalizedEffectiveAmount!!, 0.0)

        // Kotlin: full snapshot amount.
        val kotlinCv = ComparableHomeValue.comparableValue(neutralisedForeignWithSnapshot, ComparableHomeValueFixture.EUR)
        assertEquals(ComparableValue.Value(550.0), kotlinCv)

        // SQL: 550.0, same as Kotlin.
        val sqlCv = query(
            "SELECT e.id, $COMPARABLE_HOME_VALUE_E_SQL FROM expenses e WHERE e.id = 2001",
            arrayOf("EUR", "EUR")
        ).single().second
        assertNotNull(sqlCv)
        assertEquals(550.0, sqlCv!!, 0.0)
    }

    @Test
    fun `flagged foreign row WITHOUT snapshot has no comparable value in both Kotlin and SQL`() = runTest {
        val neutralisedForeignNoSnapshot = Expense(
            id = 2_002L,
            amount = 600.0,
            currency = "USD",
            merchant = "M2002",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = ComparableHomeValueFixture.BASE_DATE + 2_002_000L,
            baseAmount = 0.0,
            baseCurrency = "EUR",
            exchangeRateUsed = 0.0,
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        assertTrue(database.expenseDao().insert(neutralisedForeignNoSnapshot) > 0L)

        assertEquals(0.0, neutralisedForeignNoSnapshot.effectiveAmount, 0.0)

        // Kotlin: None(NO_SNAPSHOT), not a zero value.
        val kotlinCv = ComparableHomeValue.comparableValue(neutralisedForeignNoSnapshot, ComparableHomeValueFixture.EUR)
        assertEquals(ComparableValue.None(com.yourname.expensetracker.domain.core.money.NoComparableValueReason.NO_SNAPSHOT), kotlinCv)

        // SQL: NULL.
        val sqlCv = query(
            "SELECT e.id, $COMPARABLE_HOME_VALUE_E_SQL FROM expenses e WHERE e.id = 2002",
            arrayOf("EUR", "EUR")
        ).single().second
        assertNull("flagged foreign row without snapshot must be NULL in SQL", sqlCv)
    }

    @Test
    fun `negative controls preserve snapshot amount for ordinary foreign row, None for unrated foreign, effectiveAmount for home, and 0_0 for isNotMine`() = runTest {
        val ordinaryForeignWithSnapshot = Expense(
            id = 2_003L,
            amount = 600.0,
            currency = "USD",
            merchant = "M2003",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = ComparableHomeValueFixture.BASE_DATE + 2_003_000L,
            baseAmount = 550.0,
            baseCurrency = "EUR",
            exchangeRateUsed = 0.916667
        )
        val ordinaryForeignWithoutSnapshot = Expense(
            id = 2_004L,
            amount = 600.0,
            currency = "USD",
            merchant = "M2004",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = ComparableHomeValueFixture.BASE_DATE + 2_004_000L,
            baseAmount = 0.0,
            baseCurrency = "EUR",
            exchangeRateUsed = 0.0
        )
        val ordinaryHomeRow = Expense(
            id = 2_005L,
            amount = 80.0,
            currency = "EUR",
            merchant = "M2005",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = ComparableHomeValueFixture.BASE_DATE + 2_005_000L,
            isSharedExpense = true,
            mySharePercentage = 25
        )
        val notMineRow = Expense(
            id = 2_006L,
            amount = 600.0,
            currency = "USD",
            merchant = "M2006",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = ComparableHomeValueFixture.BASE_DATE + 2_006_000L,
            isNotMine = true,
            baseAmount = 550.0,
            baseCurrency = "EUR",
            exchangeRateUsed = 0.916667
        )

        for (row in listOf(ordinaryForeignWithSnapshot, ordinaryForeignWithoutSnapshot, ordinaryHomeRow, notMineRow)) {
            assertTrue(database.expenseDao().insert(row) > 0L)
        }

        val sqlMap = query(
            "SELECT e.id, $COMPARABLE_HOME_VALUE_E_SQL FROM expenses e WHERE e.id IN (2003, 2004, 2005, 2006)",
            arrayOf("EUR", "EUR")
        ).toMap()

        // Ordinary foreign WITH snapshot -> 550.0
        assertEquals(
            ComparableValue.Value(550.0),
            ComparableHomeValue.comparableValue(ordinaryForeignWithSnapshot, ComparableHomeValueFixture.EUR)
        )
        assertEquals(550.0, sqlMap.getValue(2_003L)!!, 0.0)

        // Ordinary foreign WITHOUT snapshot -> None / NULL
        assertTrue(ComparableHomeValue.comparableValue(ordinaryForeignWithoutSnapshot, ComparableHomeValueFixture.EUR) is ComparableValue.None)
        assertNull(sqlMap.getValue(2_004L))

        // Ordinary home-currency row -> effectiveAmount (20.0)
        assertEquals(
            ComparableValue.Value(ordinaryHomeRow.effectiveAmount),
            ComparableHomeValue.comparableValue(ordinaryHomeRow, ComparableHomeValueFixture.EUR)
        )
        assertEquals(ordinaryHomeRow.effectiveAmount, sqlMap.getValue(2_005L)!!, 0.0)

        // isNotMine row -> 0.0
        assertEquals(
            ComparableValue.Value(notMineRow.effectiveAmount),
            ComparableHomeValue.comparableValue(notMineRow, ComparableHomeValueFixture.EUR)
        )
        assertEquals(0.0, sqlMap.getValue(2_006L)!!, 0.0)
    }

    @Test
    fun `flagged 550_0 foreign snapshot row sorts at 550_0 above ordinary 10_0 row in DESC order`() = runTest {
        val ordinary10 = Expense(
            id = 2_010L,
            amount = 10.0,
            currency = "EUR",
            merchant = "M2010",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = ComparableHomeValueFixture.BASE_DATE + 10_000L,
            baseAmount = 10.0,
            baseCurrency = "EUR",
            exchangeRateUsed = 1.0
        )
        val neutralised550 = Expense(
            id = 2_011L,
            amount = 600.0,
            currency = "USD",
            merchant = "M2011",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            date = ComparableHomeValueFixture.BASE_DATE + 20_000L,
            baseAmount = 550.0,
            baseCurrency = "EUR",
            exchangeRateUsed = 0.916667,
            spendingExclusionReason = "INSTALLMENT_PURCHASE"
        )
        assertTrue(database.expenseDao().insert(ordinary10) > 0L)
        assertTrue(database.expenseDao().insert(neutralised550) > 0L)

        val sqlDescOrder = query(
            "SELECT e.id FROM expenses e WHERE e.id IN (2010, 2011) " +
                "ORDER BY ($COMPARABLE_HOME_VALUE_E_SQL IS NULL) ASC, " +
                "$COMPARABLE_HOME_VALUE_E_SQL DESC, e.date DESC, e.id DESC",
            arrayOf("EUR", "EUR", "EUR", "EUR")
        ).map { it.first }

        val kotlinDescOrder = listOf(ordinary10, neutralised550)
            .sortedWith(ComparableHomeValue.comparator(ComparableHomeValueFixture.EUR, ComparableHomeValue.Direction.DESCENDING))
            .map { it.id }

        // The flagged 550.0 row (2011) ranks above the ordinary 10.0 row (2010) in DESC
        assertEquals(listOf(2_011L, 2_010L), kotlinDescOrder)
        assertEquals(listOf(2_011L, 2_010L), sqlDescOrder)
    }

    // ── Flagged rows compared on their full amount (owner decision) ─────────

    private fun flaggedRow(
        id: Long,
        amount: Double,
        currency: String = "EUR",
        isNotMine: Boolean = false,
        isShared: Boolean = false,
        myShareAmount: Double? = null,
        baseAmount: Double = 0.0,
        rate: Double = 0.0
    ) = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "M$id",
        transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
        date = ComparableHomeValueFixture.BASE_DATE + id * 1_000L,
        isNotMine = isNotMine,
        isSharedExpense = isShared,
        myShareAmount = myShareAmount,
        baseAmount = baseAmount,
        baseCurrency = "EUR",
        exchangeRateUsed = rate,
        spendingExclusionReason = "INSTALLMENT_PURCHASE"
    )

    private fun boundIds(clause: String, bound: Double): Set<Long> =
        query(
            "SELECT e.id FROM expenses e WHERE $COMPARABLE_HOME_VALUE_E_SQL $clause ?",
            arrayOf("EUR", "EUR", bound)
        ).map { it.first }.toSet()

    @Test
    fun `flagged EUR 1200 passes min 1000 and is excluded by max 1000 in SQL and Kotlin`() = runTest {
        val flagged = flaggedRow(id = 3_001L, amount = 1_200.0)
        assertTrue(database.expenseDao().insert(flagged) > 0L)

        assertTrue(boundIds(">=", 1_000.0).contains(3_001L))
        assertFalse(boundIds("<=", 1_000.0).contains(3_001L))
        assertTrue(ComparableHomeValue.withinBounds(flagged, ComparableHomeValueFixture.EUR, 1_000.0, null))
        assertFalse(ComparableHomeValue.withinBounds(flagged, ComparableHomeValueFixture.EUR, null, 1_000.0))
        // Inclusive at the exact amount, in both mirrors.
        assertTrue(boundIds(">=", 1_200.0).contains(3_001L))
        assertTrue(boundIds("<=", 1_200.0).contains(3_001L))
        assertFalse(boundIds(">=", 1_200.01).contains(3_001L))
    }

    @Test
    fun `flagged 1200 ranks above unflagged 500 in DESC and below in ASC in SQL and Kotlin`() = runTest {
        val flagged = flaggedRow(id = 3_002L, amount = 1_200.0)
        val unflagged = flaggedRow(id = 3_003L, amount = 500.0).copy(spendingExclusionReason = null)
        assertTrue(database.expenseDao().insert(flagged) > 0L)
        assertTrue(database.expenseDao().insert(unflagged) > 0L)

        for (direction in ComparableHomeValue.Direction.values()) {
            val keyword = if (direction == ComparableHomeValue.Direction.DESCENDING) "DESC" else "ASC"
            val sqlOrder = query(
                "SELECT e.id FROM expenses e WHERE e.id IN (3002, 3003) " +
                    "ORDER BY ($COMPARABLE_HOME_VALUE_E_SQL IS NULL) ASC, " +
                    "$COMPARABLE_HOME_VALUE_E_SQL $keyword, e.date DESC, e.id DESC",
                arrayOf("EUR", "EUR", "EUR", "EUR")
            ).map { it.first }
            val kotlinOrder = listOf(flagged, unflagged)
                .sortedWith(ComparableHomeValue.comparator(ComparableHomeValueFixture.EUR, direction))
                .map { it.id }
            val expected = if (direction == ComparableHomeValue.Direction.DESCENDING) {
                listOf(3_002L, 3_003L)
            } else {
                listOf(3_003L, 3_002L)
            }
            assertEquals("kotlin $keyword", expected, kotlinOrder)
            assertEquals("sql $keyword", expected, sqlOrder)
        }
    }

    @Test
    fun `flagged shared row with my share 300 of 1200 gives 300 in SQL and Kotlin`() = runTest {
        val flagged = flaggedRow(id = 3_004L, amount = 1_200.0, isShared = true, myShareAmount = 300.0)
        assertTrue(database.expenseDao().insert(flagged) > 0L)

        assertEquals(
            ComparableValue.Value(300.0),
            ComparableHomeValue.comparableValue(flagged, ComparableHomeValueFixture.EUR)
        )
        val sqlCv = query(
            "SELECT e.id, $COMPARABLE_HOME_VALUE_E_SQL FROM expenses e WHERE e.id = 3004",
            arrayOf("EUR", "EUR")
        ).single().second
        assertNotNull(sqlCv)
        assertEquals(300.0, sqlCv!!, 0.0)
    }

    @Test
    fun `flagged not-mine row stays at 0_0 in SQL and Kotlin`() = runTest {
        val flagged = flaggedRow(id = 3_005L, amount = 1_200.0, isNotMine = true)
        assertTrue(database.expenseDao().insert(flagged) > 0L)

        assertEquals(
            ComparableValue.Value(0.0),
            ComparableHomeValue.comparableValue(flagged, ComparableHomeValueFixture.EUR)
        )
        val sqlCv = query(
            "SELECT e.id, $COMPARABLE_HOME_VALUE_E_SQL FROM expenses e WHERE e.id = 3005",
            arrayOf("EUR", "EUR")
        ).single().second
        assertNotNull(sqlCv)
        assertEquals(0.0, sqlCv!!, 0.0)
    }

    @Test
    fun `flagged foreign row with no snapshot is NULL in SQL so a bound excludes it`() = runTest {
        val flagged = flaggedRow(id = 3_006L, amount = 600.0, currency = "USD")
        assertTrue(database.expenseDao().insert(flagged) > 0L)

        assertFalse(boundIds(">=", 0.0).contains(3_006L))
        assertFalse(boundIds("<=", 1_000_000.0).contains(3_006L))
        assertTrue(
            query(
                "SELECT e.id FROM expenses e WHERE e.id = 3006 AND $COMPARABLE_HOME_VALUE_E_SQL IS NULL",
                arrayOf("EUR", "EUR")
            ).isNotEmpty()
        )
    }
}
