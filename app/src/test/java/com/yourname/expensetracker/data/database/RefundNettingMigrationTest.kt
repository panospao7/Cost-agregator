package com.yourname.expensetracker.data.database

import android.database.Cursor
import android.database.sqlite.SQLiteException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.yourname.expensetracker.data.backup.RealSchemaFixture
import com.yourname.expensetracker.data.backup.SemanticCaptureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * INV-24 / AE-23 (refund netting): `MIGRATION_152_153` is additive only.
 *
 * - every pre-existing expense reads `refundNettedAmount` 0.0 and every pre-existing column value is
 *   bit-identical after the migration;
 * - `expense_refund_links` exists and is empty, with both foreign keys (CASCADE) and both indices;
 * - the spending and income aggregates are identical before and after (checked with plain SQL sums and with the
 *   backup semantic fingerprint).
 *
 * Synthetic data only. The 152 database is built from the exported Room schema through the migration helper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RefundNettingMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        migrationTestInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun AE23_every_existing_row_gets_zero_and_all_prior_values_and_aggregates_are_unchanged() {
        val name = "refund-netting-ae23"
        val old = helper.createDatabase(name, 152)
        seedRepresentativeExpenses(old)
        val priorColumns = columnNames(old, "expenses")
        val rowsBefore = dumpRows(old, priorColumns)
        val aggregatesBefore = plainAggregates(old)
        old.close()
        val file = RealSchemaFixture.databaseFile(name)
        val fingerprintBefore = RealSchemaFixture.capture(file)
        assertEquals(SemanticCaptureStatus.CAPTURED, fingerprintBefore.status)
        assertEquals(8, rowsBefore.size)

        val upgraded = helper.runMigrationsAndValidate(name, 153, true, DatabaseMigrations.MIGRATION_152_153)
        try {
            // Every existing row reads 0.0 (control: the column exists, so the query itself is valid).
            assertEquals(8, scalarLong(upgraded, "SELECT COUNT(*) FROM expenses"))
            assertEquals(8, scalarLong(upgraded, "SELECT COUNT(*) FROM expenses WHERE refundNettedAmount = 0.0"))
            upgraded.query("SELECT refundNettedAmount FROM expenses ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    assertEquals(0.0, cursor.getDouble(0), 0.0)
                    assertEquals(Cursor.FIELD_TYPE_FLOAT, cursor.getType(0))
                }
            }
            // No pre-existing column value changed, bit for bit (boxed Double.equals compares the raw bits).
            assertEquals(rowsBefore, dumpRows(upgraded, priorColumns))
            // Plain SQL spending/income style sums are identical before vs after.
            assertEquals(aggregatesBefore, plainAggregates(upgraded))
        } finally {
            upgraded.close()
        }
        // The production semantic fingerprint (spending/income buckets) is unchanged by the migration.
        assertEquals(fingerprintBefore, RealSchemaFixture.capture(file))
    }

    @Test
    fun AE23_expense_refund_links_exists_empty_with_both_foreign_keys_and_both_indices() {
        val name = "refund-netting-schema"
        val old = helper.createDatabase(name, 152)
        seedRepresentativeExpenses(old)
        assertEquals(
            0,
            scalarLong(old, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'expense_refund_links'")
        )
        old.close()

        val upgraded = helper.runMigrationsAndValidate(name, 153, true, DatabaseMigrations.MIGRATION_152_153)
        try {
            assertEquals(
                1,
                scalarLong(upgraded, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'expense_refund_links'")
            )
            assertEquals(0, scalarLong(upgraded, "SELECT COUNT(*) FROM expense_refund_links"))

            val columns = mutableMapOf<String, Triple<String, Int, String?>>()
            upgraded.query("PRAGMA table_info(expense_refund_links)").use { c ->
                while (c.moveToNext()) {
                    columns[c.getString(c.getColumnIndexOrThrow("name"))] = Triple(
                        c.getString(c.getColumnIndexOrThrow("type")),
                        c.getInt(c.getColumnIndexOrThrow("notnull")),
                        c.getString(c.getColumnIndexOrThrow("dflt_value"))
                    )
                }
            }
            assertEquals(
                setOf("id", "refundExpenseId", "purchaseExpenseId", "appliedAmount", "currency", "createdAt", "source"),
                columns.keys
            )
            assertEquals("REAL", columns.getValue("appliedAmount").first)
            assertEquals("TEXT", columns.getValue("currency").first)
            assertEquals("TEXT", columns.getValue("source").first)
            listOf("refundExpenseId", "purchaseExpenseId", "appliedAmount", "currency", "createdAt", "source").forEach {
                assertEquals("$it must be NOT NULL", 1, columns.getValue(it).second)
            }

            // Two foreign keys to expenses(id), both ON DELETE CASCADE.
            val foreignKeys = mutableListOf<List<String>>()
            upgraded.query("PRAGMA foreign_key_list(expense_refund_links)").use { c ->
                while (c.moveToNext()) {
                    foreignKeys += listOf(
                        c.getString(c.getColumnIndexOrThrow("from")),
                        c.getString(c.getColumnIndexOrThrow("table")),
                        c.getString(c.getColumnIndexOrThrow("to")),
                        c.getString(c.getColumnIndexOrThrow("on_delete"))
                    )
                }
            }
            assertEquals(
                setOf(
                    listOf("refundExpenseId", "expenses", "id", "CASCADE"),
                    listOf("purchaseExpenseId", "expenses", "id", "CASCADE")
                ),
                foreignKeys.toSet()
            )
            assertEquals(2, foreignKeys.size)

            // Unique (refund, purchase) index and a plain purchase index.
            val indices = mutableMapOf<String, Int>()
            upgraded.query("PRAGMA index_list(expense_refund_links)").use { c ->
                while (c.moveToNext()) {
                    indices[c.getString(c.getColumnIndexOrThrow("name"))] = c.getInt(c.getColumnIndexOrThrow("unique"))
                }
            }
            assertEquals(1, indices["index_expense_refund_links_refundExpenseId_purchaseExpenseId"])
            assertEquals(0, indices["index_expense_refund_links_purchaseExpenseId"])
            assertEquals(
                listOf("refundExpenseId", "purchaseExpenseId"),
                indexColumns(upgraded, "index_expense_refund_links_refundExpenseId_purchaseExpenseId")
            )
            assertEquals(
                listOf("purchaseExpenseId"),
                indexColumns(upgraded, "index_expense_refund_links_purchaseExpenseId")
            )
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun INV24_new_column_is_real_not_null_default_zero_so_new_rows_start_unlinked() {
        val name = "refund-netting-column"
        helper.createDatabase(name, 152).close()
        val upgraded = helper.runMigrationsAndValidate(name, 153, true, DatabaseMigrations.MIGRATION_152_153)
        try {
            var found = false
            upgraded.query("PRAGMA table_info(expenses)").use { c ->
                while (c.moveToNext()) {
                    if (c.getString(c.getColumnIndexOrThrow("name")) == "refundNettedAmount") {
                        found = true
                        assertEquals("REAL", c.getString(c.getColumnIndexOrThrow("type")))
                        assertEquals(1, c.getInt(c.getColumnIndexOrThrow("notnull")))
                        assertEquals(0.0, c.getString(c.getColumnIndexOrThrow("dflt_value")).toDouble(), 0.0)
                    }
                }
            }
            assertTrue("refundNettedAmount column must exist after the migration", found)

            // A row inserted without naming the column reads 0.0 (the default), never NULL.
            RealSchemaFixture.insertExpense(upgraded, 1, "PURCHASE", "EUR", "10.0")
            upgraded.query("SELECT refundNettedAmount FROM expenses WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0.0, c.getDouble(0), 0.0)
            }
            // Boundary: an explicit NULL is rejected by the NOT NULL constraint.
            assertThrows(SQLiteException::class.java) {
                upgraded.execSQL("UPDATE expenses SET refundNettedAmount = NULL WHERE id = 1")
            }
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun INV4_unique_refund_purchase_pair_is_enforced_on_the_migrated_table() {
        val name = "refund-netting-unique"
        val old = helper.createDatabase(name, 152)
        RealSchemaFixture.insertExpense(old, 1, "PURCHASE", "EUR", "100.0")
        RealSchemaFixture.insertExpense(old, 2, "DEPOSIT", "EUR", "40.0")
        RealSchemaFixture.insertExpense(old, 3, "PURCHASE", "EUR", "50.0")
        old.close()
        val upgraded = helper.runMigrationsAndValidate(name, 153, true, DatabaseMigrations.MIGRATION_152_153)
        try {
            upgraded.execSQL("PRAGMA foreign_keys = ON")
            upgraded.execSQL(linkInsert(1, refund = 2, purchase = 1))
            // Control: the same refund against a different purchase, and another refund on the same purchase,
            // are different pairs and are allowed (the schema is many-to-many).
            upgraded.execSQL(linkInsert(2, refund = 2, purchase = 3))
            assertEquals(2, scalarLong(upgraded, "SELECT COUNT(*) FROM expense_refund_links"))
            // Boundary: the identical pair is rejected.
            assertThrows(SQLiteException::class.java) {
                upgraded.execSQL(linkInsert(3, refund = 2, purchase = 1))
            }
            assertEquals(2, scalarLong(upgraded, "SELECT COUNT(*) FROM expense_refund_links"))
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun INV24_foreign_keys_cascade_from_either_expense_and_reject_unknown_ids() {
        val name = "refund-netting-fk"
        val old = helper.createDatabase(name, 152)
        RealSchemaFixture.insertExpense(old, 1, "PURCHASE", "EUR", "100.0")
        RealSchemaFixture.insertExpense(old, 2, "DEPOSIT", "EUR", "40.0")
        RealSchemaFixture.insertExpense(old, 3, "PURCHASE", "EUR", "50.0")
        RealSchemaFixture.insertExpense(old, 4, "DEPOSIT", "EUR", "20.0")
        old.close()
        val upgraded = helper.runMigrationsAndValidate(name, 153, true, DatabaseMigrations.MIGRATION_152_153)
        try {
            upgraded.execSQL("PRAGMA foreign_keys = ON")
            upgraded.execSQL(linkInsert(1, refund = 2, purchase = 1))
            upgraded.execSQL(linkInsert(2, refund = 4, purchase = 3))

            // Boundary: a link to a non-existent expense is rejected on either side.
            assertThrows(SQLiteException::class.java) { upgraded.execSQL(linkInsert(9, refund = 99, purchase = 1)) }
            assertThrows(SQLiteException::class.java) { upgraded.execSQL(linkInsert(9, refund = 2, purchase = 99)) }

            // Deleting the purchase side cascades; the other link is untouched.
            upgraded.execSQL("DELETE FROM expenses WHERE id = 1")
            assertEquals(0, scalarLong(upgraded, "SELECT COUNT(*) FROM expense_refund_links WHERE id = 1"))
            assertEquals(1, scalarLong(upgraded, "SELECT COUNT(*) FROM expense_refund_links WHERE id = 2"))
            // Deleting the refund side cascades too.
            upgraded.execSQL("DELETE FROM expenses WHERE id = 4")
            assertEquals(0, scalarLong(upgraded, "SELECT COUNT(*) FROM expense_refund_links"))
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun AE23_empty_152_database_migrates_without_error_and_stays_empty() {
        val name = "refund-netting-empty"
        helper.createDatabase(name, 152).close()
        val upgraded = helper.runMigrationsAndValidate(name, 153, true, DatabaseMigrations.MIGRATION_152_153)
        try {
            assertEquals(0, scalarLong(upgraded, "SELECT COUNT(*) FROM expenses"))
            assertEquals(0, scalarLong(upgraded, "SELECT COUNT(*) FROM expense_refund_links"))
            assertEquals(153L, scalarLong(upgraded, "PRAGMA user_version"))
        } finally {
            upgraded.close()
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    /**
     * 8 rows: own and shared purchases, a foreign purchase (isNotMine), a deposit pair, a transfer, an
     * installment-neutralised purchase (spending exclusion reason set) and a repeating-decimal amount.
     */
    private fun seedRepresentativeExpenses(db: SupportSQLiteDatabase) {
        RealSchemaFixture.insertExpense(db, 1, "PURCHASE", "EUR", "12.5")
        RealSchemaFixture.insertExpense(db, 2, "PURCHASE", "EUR", "40.0", shared = 1, percentage = "50")
        RealSchemaFixture.insertExpense(db, 3, "DEPOSIT", "EUR", "1000.0")
        RealSchemaFixture.insertExpense(db, 4, "DEPOSIT", "EUR", "40.0")
        RealSchemaFixture.insertExpense(db, 5, "PURCHASE", "USD", "9.99", isNotMine = 1)
        RealSchemaFixture.insertExpense(db, 6, "TRANSFER", "EUR", "200.0")
        RealSchemaFixture.insertExpense(
            db, 7, "PURCHASE", "EUR", "100.0", exclusionReason = RealSchemaFixture.EXCLUSION_REASON
        )
        RealSchemaFixture.insertExpense(db, 8, "PURCHASE", "EUR", "0.1")
    }

    private fun linkInsert(id: Long, refund: Long, purchase: Long): String =
        "INSERT INTO expense_refund_links (id, refundExpenseId, purchaseExpenseId, appliedAmount, currency, " +
            "createdAt, source) VALUES ($id, $refund, $purchase, 10.0, 'EUR', 1700000000000, 'USER_CONFIRMED')"

    private fun scalarLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { c ->
            assertTrue(c.moveToFirst())
            c.getLong(0)
        }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    private fun indexColumns(db: SupportSQLiteDatabase, index: String): List<String> =
        db.query("PRAGMA index_info($index)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    /** Every listed column of every expense row, typed (Long, Double, String or null), ordered by id. */
    private fun dumpRows(db: SupportSQLiteDatabase, columns: List<String>): List<List<Any?>> {
        val select = columns.joinToString(", ") { "`$it`" }
        return db.query("SELECT $select FROM expenses ORDER BY id").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        columns.indices.map { i ->
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                else -> error("unexpected blob column")
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * Plain SQL sums per type and currency: row count, raw sum, sum of the rows that carry no exclusion reason
     * and sum of the rows that are not flagged as someone else's. Doubles are kept as raw bit patterns.
     */
    private fun plainAggregates(db: SupportSQLiteDatabase): List<List<Any?>> =
        db.query(
            "SELECT transactionType, currency, COUNT(*), SUM(amount), " +
                "SUM(CASE WHEN spendingExclusionReason IS NULL THEN amount ELSE 0.0 END), " +
                "SUM(CASE WHEN isNotMine = 0 THEN amount ELSE 0.0 END) " +
                "FROM expenses GROUP BY transactionType, currency ORDER BY transactionType, currency"
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        listOf(
                            c.getString(0),
                            c.getString(1),
                            c.getLong(2),
                            c.getDouble(3).toRawBits(),
                            c.getDouble(4).toRawBits(),
                            c.getDouble(5).toRawBits()
                        )
                    )
                }
            }
        }
}
