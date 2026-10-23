package com.yourname.expensetracker.data.rescue

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.util.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A10 / INV-30 (loan installments): the financial rescue copies the spendingExclusionReason flag
 * TOGETHER with both plan tables. Copying the flag without the plans (or the reverse) would turn a
 * purchase record back into full spending next to its installment payments (double count).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FinancialRescueInstallmentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val coordinator = FinancialRescueCoordinator(
        context,
        object : TimeProvider {
            override fun now(): Long = 1_700_000_000_000L
        }
    )

    private fun oldDatabase(withInstallmentSchema: Boolean): File {
        val file = File(tempFolder.root, if (withInstallmentSchema) "old152.db" else "old151.db")
        val flagColumn = if (withInstallmentSchema) ", spendingExclusionReason TEXT" else ""
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                "CREATE TABLE expenses (id INTEGER PRIMARY KEY, amount REAL NOT NULL, merchant TEXT NOT NULL, " +
                    "date INTEGER NOT NULL, currency TEXT, transactionType TEXT$flagColumn)"
            )
            if (withInstallmentSchema) {
                db.execSQL(
                    "INSERT INTO expenses (id, amount, merchant, date, currency, transactionType, spendingExclusionReason) " +
                        "VALUES (1, 600.0, 'Purchase', 20000, 'EUR', 'PURCHASE', 'INSTALLMENT_PURCHASE')"
                )
                db.execSQL(
                    "INSERT INTO expenses (id, amount, merchant, date, currency, transactionType, spendingExclusionReason) " +
                        "VALUES (2, 50.0, 'Payment', 20030, 'EUR', 'PURCHASE', NULL)"
                )
                db.execSQL(
                    "INSERT INTO expenses (id, amount, merchant, date, currency, transactionType, spendingExclusionReason) " +
                        "VALUES (3, 10.0, 'Odd', 20040, 'EUR', 'PURCHASE', 'NOT_A_REAL_REASON')"
                )
                // Orphan purchase flag: no plan references expense 4 (rescue must clear it).
                db.execSQL(
                    "INSERT INTO expenses (id, amount, merchant, date, currency, transactionType, spendingExclusionReason) " +
                        "VALUES (4, 80.0, 'OrphanPurchase', 20050, 'EUR', 'PURCHASE', 'INSTALLMENT_PURCHASE')"
                )
                // Orphan reversed flag: no REVERSED entry links expense 5 (owner 2026-10-24: rescue clears it).
                db.execSQL(
                    "INSERT INTO expenses (id, amount, merchant, date, currency, transactionType, spendingExclusionReason) " +
                        "VALUES (5, 30.0, 'OrphanReversed', 20060, 'EUR', 'PURCHASE', 'INSTALLMENT_REVERSED')"
                )
                // Referenced reversed flag: a REVERSED entry (id 72) links expense 6, so the flag stays.
                db.execSQL(
                    "INSERT INTO expenses (id, amount, merchant, date, currency, transactionType, spendingExclusionReason) " +
                        "VALUES (6, 40.0, 'ReferencedReversed', 20070, 'EUR', 'PURCHASE', 'INSTALLMENT_REVERSED')"
                )
                db.execSQL(
                    "CREATE TABLE installment_plans (id INTEGER PRIMARY KEY, planUuid TEXT NOT NULL, kind TEXT NOT NULL, " +
                        "purchaseExpenseId INTEGER, displayName TEXT NOT NULL, lenderLabel TEXT, currency TEXT NOT NULL, " +
                        "splitMethod TEXT NOT NULL, installmentCount INTEGER NOT NULL, frequency TEXT NOT NULL, " +
                        "firstDueDate INTEGER NOT NULL, anchorDayOfMonth INTEGER NOT NULL, residualPlacement TEXT NOT NULL, " +
                        "paymentMethod TEXT NOT NULL, fulfilmentMode TEXT NOT NULL, declaredOnDate INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO installment_plans (id, planUuid, kind, purchaseExpenseId, displayName, lenderLabel, " +
                        "currency, splitMethod, installmentCount, frequency, firstDueDate, anchorDayOfMonth, " +
                        "residualPlacement, paymentMethod, fulfilmentMode, declaredOnDate, status, createdAt, updatedAt) " +
                        "VALUES (7, 'plan-uuid-7', 'PURCHASE_ATTACHED', 1, 'Laptop', 'Bank', 'EUR', 'EQUAL_SPLIT', 12, " +
                        "'MONTHLY', 20030, 15, 'LAST', 'UNKNOWN', 'CONFIRM', 20000, 'ACTIVE', 1700000000000, 1700000000000)"
                )
                db.execSQL(
                    "CREATE TABLE installment_entries (id INTEGER PRIMARY KEY, planId INTEGER NOT NULL, seq INTEGER NOT NULL, " +
                        "kind TEXT NOT NULL, dueDate INTEGER NOT NULL, scheduledAmount REAL NOT NULL, status TEXT NOT NULL, " +
                        "linkedExpenseId INTEGER, autoRecordConsumed INTEGER NOT NULL, recordGeneration INTEGER NOT NULL, " +
                        "statusChangedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO installment_entries (id, planId, seq, kind, dueDate, scheduledAmount, status, linkedExpenseId, " +
                        "autoRecordConsumed, recordGeneration, statusChangedAt) " +
                        "VALUES (70, 7, 1, 'REGULAR', 20030, 50.0, 'PAID', 2, 0, 0, 1700000000000)"
                )
                db.execSQL(
                    "INSERT INTO installment_entries (id, planId, seq, kind, dueDate, scheduledAmount, status, linkedExpenseId, " +
                        "autoRecordConsumed, recordGeneration, statusChangedAt) " +
                        "VALUES (71, 7, 2, 'REGULAR', 20060, 50.0, 'SCHEDULED', NULL, 0, 0, 1700000000000)"
                )
                db.execSQL(
                    "INSERT INTO installment_entries (id, planId, seq, kind, dueDate, scheduledAmount, status, linkedExpenseId, " +
                        "autoRecordConsumed, recordGeneration, statusChangedAt) " +
                        "VALUES (72, 7, 3, 'REGULAR', 20070, 40.0, 'REVERSED', 6, 0, 0, 1700000000000)"
                )
                // Entry whose plan row does not exist: skipped on import so the FK cannot fail the rescue.
                db.execSQL(
                    "INSERT INTO installment_entries (id, planId, seq, kind, dueDate, scheduledAmount, status, linkedExpenseId, " +
                        "autoRecordConsumed, recordGeneration, statusChangedAt) " +
                        "VALUES (99, 404, 1, 'REGULAR', 20060, 50.0, 'SCHEDULED', NULL, 0, 0, 1700000000000)"
                )
            } else {
                db.execSQL(
                    "INSERT INTO expenses (id, amount, merchant, date, currency, transactionType) " +
                        "VALUES (1, 20.0, 'Plain', 20000, 'EUR', 'PURCHASE')"
                )
            }
        } finally {
            db.close()
        }
        return file
    }

    private fun readSnapshot(file: File): FinancialRescueSnapshot = coordinator.readOldDatabaseSnapshot(file)

    @Test
    fun snapshot_reads_the_flag_and_both_plan_tables_together() {
        val snapshot = readSnapshot(oldDatabase(withInstallmentSchema = true))

        assertEquals("INSTALLMENT_PURCHASE", snapshot.expenses.single { it.id == 1L }.spendingExclusionReason)
        assertNull(snapshot.expenses.single { it.id == 2L }.spendingExclusionReason)
        assertEquals(
            "an unknown reason must never be carried as a flag",
            null,
            snapshot.expenses.single { it.id == 3L }.spendingExclusionReason
        )
        assertEquals(listOf("installment_plans", "installment_entries"), snapshot.installmentTables.map { it.table })
        assertEquals(1, snapshot.installmentTables.first { it.table == "installment_plans" }.rows.size)
        assertEquals(4, snapshot.installmentTables.first { it.table == "installment_entries" }.rows.size)
    }

    @Test
    fun snapshot_from_a_pre_152_database_has_no_flag_and_no_plan_tables() {
        val snapshot = readSnapshot(oldDatabase(withInstallmentSchema = false))

        assertNull(snapshot.expenses.single().spendingExclusionReason)
        assertTrue(snapshot.installmentTables.isEmpty())
    }

    @Test
    fun rescue_import_restores_the_flag_and_both_plan_tables_together() {
        val snapshot = readSnapshot(oldDatabase(withInstallmentSchema = true))
        // The live database path must be empty: Room creates a fresh latest-schema database there.
        val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        live.parentFile?.mkdirs()
        listOf(live, File(live.path + "-wal"), File(live.path + "-shm")).forEach { it.delete() }

        coordinator.createFreshRoomDatabaseAndImport(snapshot)

        SQLiteDatabase.openDatabase(live.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(
                "INSTALLMENT_PURCHASE",
                db.rawQuery("SELECT spendingExclusionReason FROM expenses WHERE id = 1", null).use {
                    it.moveToFirst(); it.getString(0)
                }
            )
            assertEquals(
                0,
                db.rawQuery(
                    "SELECT COUNT(*) FROM expenses WHERE id NOT IN (1, 6) AND spendingExclusionReason IS NOT NULL",
                    null
                ).use { it.moveToFirst(); it.getInt(0) }
            )
            assertEquals(
                1,
                db.rawQuery("SELECT COUNT(*) FROM installment_plans WHERE id = 7 AND purchaseExpenseId = 1", null)
                    .use { it.moveToFirst(); it.getInt(0) }
            )
            assertEquals(
                3,
                db.rawQuery("SELECT COUNT(*) FROM installment_entries WHERE planId = 7", null)
                    .use { it.moveToFirst(); it.getInt(0) }
            )
            assertEquals(
                "an entry without its plan is not imported",
                0,
                db.rawQuery("SELECT COUNT(*) FROM installment_entries WHERE id = 99", null)
                    .use { it.moveToFirst(); it.getInt(0) }
            )
            assertEquals(
                2,
                db.rawQuery("SELECT linkedExpenseId FROM installment_entries WHERE id = 70", null)
                    .use { it.moveToFirst(); it.getInt(0) }
            )
        }
    }

    @Test
    fun rescue_import_clears_orphan_purchase_and_reversed_flags_but_keeps_referenced_flags() {
        val snapshot = readSnapshot(oldDatabase(withInstallmentSchema = true))
        val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        live.parentFile?.mkdirs()
        listOf(live, File(live.path + "-wal"), File(live.path + "-shm")).forEach { it.delete() }

        coordinator.createFreshRoomDatabaseAndImport(snapshot)

        SQLiteDatabase.openDatabase(live.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            fun flagOf(id: Int): String? = db.rawQuery(
                "SELECT spendingExclusionReason FROM expenses WHERE id = $id",
                null
            ).use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }

            assertEquals("a flag referenced by a plan stays", "INSTALLMENT_PURCHASE", flagOf(1))
            assertNull("an orphan purchase flag is cleared by the rescue", flagOf(4))
            assertNull("an orphan reversed flag is cleared by the rescue (owner 2026-10-24)", flagOf(5))
            assertEquals(
                "a reversed flag referenced by a REVERSED entry stays",
                "INSTALLMENT_REVERSED",
                flagOf(6)
            )
        }
    }

    @Test
    fun rescue_import_clears_orphan_purchase_flag_even_without_installment_tables() {
        val snapshot = readSnapshot(oldDatabase(withInstallmentSchema = true))
            .copy(installmentTables = emptyList())
        val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        live.parentFile?.mkdirs()
        listOf(live, File(live.path + "-wal"), File(live.path + "-shm")).forEach { it.delete() }

        coordinator.createFreshRoomDatabaseAndImport(snapshot)

        SQLiteDatabase.openDatabase(live.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(
                "with no plans every purchase flag is an orphan",
                0,
                db.rawQuery(
                    "SELECT COUNT(*) FROM expenses WHERE spendingExclusionReason = 'INSTALLMENT_PURCHASE'",
                    null
                ).use { it.moveToFirst(); it.getInt(0) }
            )
            assertEquals(
                "with no entries every reversed flag is an orphan",
                0,
                db.rawQuery(
                    "SELECT COUNT(*) FROM expenses WHERE spendingExclusionReason = 'INSTALLMENT_REVERSED'",
                    null
                ).use { it.moveToFirst(); it.getInt(0) }
            )
        }
    }
}
