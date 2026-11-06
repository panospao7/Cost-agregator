package com.yourname.expensetracker.data.backup

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.migrationTestInstrumentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File
import java.math.BigDecimal

/**
 * UF-46 / S1 fixture F on the REAL Room schema (exported `153.json`, the latest schema), built through
 * `MigrationTestHelper` exactly as `BackupVerifierInstallmentOrphanTest` and
 * `BackupVerifierSavingsLedgerTest` do. Synthetic data only.
 *
 * Rows (domain spec section 7):
 * - E1 PURCHASE EUR 12.5 own
 * - E2 PURCHASE EUR 40.0 shared, mySharePercentage 50, myShareAmount NULL
 * - E3 DEPOSIT EUR 1000.0
 * - E4 PURCHASE USD 9.99, isNotMine = 1
 * - E5 TRANSFER EUR 200.0
 * - E6 UNKNOWN GBP 5.0
 * - E7 (optional, v152+ only) PURCHASE EUR 100.0 carrying a spending-exclusion reason
 * - R1 active EUR 9.0, R2 inactive USD 15.0
 *
 * The expected values below are hand-computed from the spec, never derived from production code.
 */
internal object RealSchemaFixture {

    /** The latest schema (153, refund netting): full BackupVerifier semantic checks need the link table. */
    const val SCHEMA_VERSION = 153

    /** First schema carrying the spending-exclusion column (flagged row E7 is legal from here). */
    const val SPENDING_EXCLUSION_SCHEMA_VERSION = 152

    /** A reason text that is neither of the two orphan-checked installment reasons. */
    const val EXCLUSION_REASON = "FIXTURE_EXCLUSION"

    /** Texts that must never appear in any bounded diagnostic (spec AE-15). */
    val forbiddenValueText: List<String> = listOf("12.5", "1000", "EUR", "USD", "PURCHASE", "SELECT")

    fun newHelper(): MigrationTestHelper = MigrationTestHelper(
        migrationTestInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    fun databaseFile(name: String): File =
        InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(name)

    // ── Seeding ──────────────────────────────────────────────────

    private class ExpenseRow(
        val id: Long,
        val type: String,
        val currency: String,
        val amount: String,
        val isNotMine: Int = 0,
        val shared: Int = 0,
        val percentage: String = "NULL",
        val exclusionReason: String? = null
    )

    private fun fixtureRows(withFlaggedPurchase: Boolean): List<ExpenseRow> = buildList {
        add(ExpenseRow(1, "PURCHASE", "EUR", "12.5"))
        add(ExpenseRow(2, "PURCHASE", "EUR", "40.0", shared = 1, percentage = "50"))
        add(ExpenseRow(3, "DEPOSIT", "EUR", "1000.0"))
        add(ExpenseRow(4, "PURCHASE", "USD", "9.99", isNotMine = 1))
        add(ExpenseRow(5, "TRANSFER", "EUR", "200.0"))
        add(ExpenseRow(6, "UNKNOWN", "GBP", "5.0"))
        if (withFlaggedPurchase) add(ExpenseRow(7, "PURCHASE", "EUR", "100.0", exclusionReason = EXCLUSION_REASON))
    }

    /**
     * Inserts fixture F into a database created at [version]. The flagged row E7 needs the schema-152
     * column, so it is only legal for [version] >= 152; below 152 the column is omitted entirely.
     */
    fun seedFixtureF(
        db: SupportSQLiteDatabase,
        version: Int = SCHEMA_VERSION,
        withFlaggedPurchase: Boolean = false,
        reverseInsertOrder: Boolean = false
    ) {
        require(!withFlaggedPurchase || version >= SPENDING_EXCLUSION_SCHEMA_VERSION) { "flagged row needs schema 152" }
        val hasFlagColumn = version >= SPENDING_EXCLUSION_SCHEMA_VERSION
        val rows = fixtureRows(withFlaggedPurchase).let { if (reverseInsertOrder) it.reversed() else it }
        rows.forEach { row ->
            insertExpense(db, row.id, row.type, row.currency, row.amount, row.isNotMine, row.shared,
                row.percentage, row.exclusionReason, hasFlagColumn)
        }
        val recurring = listOf(
            Triple(1L, 1, "EUR" to "9.0"),
            Triple(2L, 0, "USD" to "15.0")
        ).let { if (reverseInsertOrder) it.reversed() else it }
        recurring.forEach { (id, active, money) ->
            insertRecurring(db, id, active, money.first, money.second)
        }
    }

    fun insertExpense(
        db: SupportSQLiteDatabase,
        id: Long,
        type: String,
        currency: String,
        amount: String,
        isNotMine: Int = 0,
        shared: Int = 0,
        percentage: String = "NULL",
        exclusionReason: String? = null,
        hasFlagColumn: Boolean = true
    ) {
        val flagColumn = if (hasFlagColumn) ", spendingExclusionReason" else ""
        val flagValue = if (hasFlagColumn) ", " + (exclusionReason?.let { "'$it'" } ?: "NULL") else ""
        db.execSQL(
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine, " +
                "isSharedExpense, mySharePercentage$flagColumn) " +
                "VALUES ($id, $amount, '$currency', 'M$id', 20000, '$type', $isNotMine, $shared, $percentage$flagValue)"
        )
    }

    fun insertRecurring(db: SupportSQLiteDatabase, id: Long, active: Int, currency: String, amount: String) {
        db.execSQL(
            "INSERT INTO manual_recurring_expenses (id, merchant, amount, currency, frequency, nextDate, " +
                "createdAt, isActive) VALUES ($id, 'R$id', $amount, '$currency', 'MONTHLY', 20000, 1700000000000, $active)"
        )
    }

    /** Creates a real database at [version], seeds fixture F and closes it. Returns the database file. */
    fun createFixtureF(
        helper: MigrationTestHelper,
        name: String,
        version: Int = SCHEMA_VERSION,
        withFlaggedPurchase: Boolean = false,
        reverseInsertOrder: Boolean = false
    ): File {
        val db = helper.createDatabase(name, version)
        try {
            seedFixtureF(db, version, withFlaggedPurchase, reverseInsertOrder)
        } finally {
            db.close()
        }
        return databaseFile(name)
    }

    /** Creates an empty real database at [version] and lets [seed] fill it before closing. */
    fun createSeeded(
        helper: MigrationTestHelper,
        name: String,
        version: Int = SCHEMA_VERSION,
        seed: (SupportSQLiteDatabase) -> Unit
    ): File {
        val db = helper.createDatabase(name, version)
        try {
            db.beginTransaction()
            try {
                seed(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
        return databaseFile(name)
    }

    // ── Raw handle helpers ───────────────────────────────────────

    fun <T> readOnly(file: File, block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use(block)

    /** Foreign keys are off on a raw handle, which models a restored copy. */
    fun mutate(file: File, vararg statements: String) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            statements.forEach { db.execSQL(it) }
        }
    }

    private fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    /** The independent row counts the backup orchestrator would pass as `tableCounts` (plain COUNT(*)). */
    fun tableCounts(db: SQLiteDatabase): Map<String, Int> = mapOf(
        "expenses" to count(db, "expenses"),
        "manual_recurring_expenses" to count(db, "manual_recurring_expenses")
    )

    /** Counts for every required Tier 1 table, for the full `BackupVerifier.verify` entry points. */
    fun manifestCounts(file: File): Map<String, Int> = readOnly(file) { db ->
        BackupVerifier.requiredManifestTables().associateWith { count(db, it) }
    }

    fun capture(file: File): SemanticAggregateSet = readOnly(file) { db -> SemanticAggregates.capture(db, tableCounts(db)) }

    // ── Hand-computed expectations ───────────────────────────────

    class ExpectedBucket(val key: List<String>, val n: Int, val u: Int, val s: BigDecimal) {
        constructor(n: Int, s: BigDecimal, vararg key: String) : this(key.toList(), n, 0, s)
    }

    fun expectedBuckets(id: SemanticAggregateId, withFlaggedPurchase: Boolean = false): List<ExpectedBucket> {
        val purchaseEurRows = if (withFlaggedPurchase) 3 else 2
        val purchaseEurRaw = if (withFlaggedPurchase) BigDecimal("152.5") else BigDecimal("52.5")
        return when (id) {
            SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY -> listOf(
                ExpectedBucket(1, BigDecimal("1000"), "DEPOSIT", "EUR"),
                ExpectedBucket(purchaseEurRows, purchaseEurRaw, "PURCHASE", "EUR"),
                ExpectedBucket(1, BigDecimal(9.99), "PURCHASE", "USD"),
                ExpectedBucket(1, BigDecimal("200"), "TRANSFER", "EUR"),
                ExpectedBucket(1, BigDecimal("5"), "UNKNOWN", "GBP")
            )
            // The flagged row counts in n and adds 0 to s (D-SIS). 12.5 + 40.0 * 50 / 100 = 32.5.
            SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY -> listOf(
                ExpectedBucket(purchaseEurRows, BigDecimal("32.5"), "EUR"),
                ExpectedBucket(1, BigDecimal.ZERO, "USD")
            )
            SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY -> listOf(
                ExpectedBucket(1, BigDecimal("1000"), "EUR")
            )
            SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY -> listOf(
                ExpectedBucket(1, BigDecimal("15"), "0", "USD"),
                ExpectedBucket(1, BigDecimal("9"), "1", "EUR")
            )
        }
    }

    /** Asserts one aggregate bucket by bucket (key, n, u and the exact sum via compareTo), order-insensitive. */
    fun assertBuckets(
        message: String,
        entry: AggregateEntry?,
        expected: List<ExpectedBucket>
    ) {
        assertNotNull("$message: aggregate present", entry)
        val actual = entry!!.buckets.associateBy { it.key }
        assertEquals("$message: bucket keys", expected.map { it.key }.toSet(), actual.keys)
        expected.forEach { want ->
            val got = actual.getValue(want.key)
            val label = "$message ${want.key}"
            assertEquals("$label n", want.n, got.n)
            assertEquals("$label u", want.u, got.u)
            assertTrue(
                "$label s expected ${want.s.toPlainString()} but was ${got.s.toPlainString()}",
                want.s.compareTo(got.s) == 0
            )
        }
    }

    fun assertFixtureF(set: SemanticAggregateSet, withFlaggedPurchase: Boolean = false) {
        assertEquals(SemanticCaptureStatus.CAPTURED, set.status)
        assertEquals(SemanticAggregateId.values().toSet(), set.aggregates.keys)
        SemanticAggregateId.values().forEach { id ->
            assertBuckets(id.name, set.aggregates[id], expectedBuckets(id, withFlaggedPurchase))
        }
    }
}
