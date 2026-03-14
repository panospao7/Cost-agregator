package com.yourname.expensetracker.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration contract tests that do not depend on exported JSON snapshots.
 * These validate concrete schema/data behavior for critical legacy migrations.
 */
@RunWith(AndroidJUnit4::class)
class MigrationContractTest {

    @Test
    fun migration_6_to_7_adds_payment_columns_with_defaults() {
        withTestDb("migration-contract-6-7.db", version = 6) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS expenses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    amount REAL NOT NULL,
                    currency TEXT NOT NULL,
                    merchant TEXT NOT NULL,
                    transactionType TEXT NOT NULL,
                    date INTEGER NOT NULL,
                    categoryId INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO expenses (amount, currency, merchant, transactionType, date, categoryId) VALUES (12.5, 'EUR', 'Shop', 'PURCHASE', 1000, NULL)"
            )

            AppDatabase.MIGRATION_6_7.migrate(db)

            val colCursor = db.query("PRAGMA table_info(expenses)")
            val columns = mutableSetOf<String>()
            while (colCursor.moveToNext()) {
                columns.add(colCursor.getString(1))
            }
            colCursor.close()

            assertTrue(columns.contains("paymentMethod"))
            assertTrue(columns.contains("isManualEntry"))
            assertTrue(columns.contains("notes"))

            val row = db.query("SELECT paymentMethod, isManualEntry, notes FROM expenses")
            assertTrue(row.moveToFirst())
            assertEquals("UNKNOWN", row.getString(0))
            assertEquals(0, row.getInt(1))
            assertEquals(null, row.getString(2))
            row.close()
        }
    }

    @Test
    fun migration_7_to_8_creates_budgets_table_and_indices() {
        withTestDb("migration-contract-7-8.db", version = 7) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS categories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    icon TEXT,
                    color TEXT
                )
                """.trimIndent()
            )

            AppDatabase.MIGRATION_7_8.migrate(db)

            val tableCursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='budgets'")
            assertTrue(tableCursor.moveToFirst())
            tableCursor.close()

            val indexCursor = db.query("SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'index_budgets_%'")
            val indexes = mutableSetOf<String>()
            while (indexCursor.moveToNext()) {
                indexes.add(indexCursor.getString(0))
            }
            indexCursor.close()

            assertTrue(indexes.contains("index_budgets_categoryId"))
            assertTrue(indexes.contains("index_budgets_isActive"))
        }
    }

    @Test
    fun migration_8_to_9_creates_scanned_receipts_table_and_indices() {
        withTestDb("migration-contract-8-9.db", version = 8) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS expenses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    amount REAL NOT NULL,
                    currency TEXT NOT NULL,
                    merchant TEXT NOT NULL,
                    transactionType TEXT NOT NULL,
                    date INTEGER NOT NULL,
                    categoryId INTEGER
                )
                """.trimIndent()
            )

            AppDatabase.MIGRATION_8_9.migrate(db)

            val tableCursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='scanned_receipts'")
            assertTrue(tableCursor.moveToFirst())
            tableCursor.close()

            val indexCursor = db.query("SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'index_scanned_receipts_%'")
            val indexes = mutableSetOf<String>()
            while (indexCursor.moveToNext()) {
                indexes.add(indexCursor.getString(0))
            }
            indexCursor.close()

            assertTrue(indexes.contains("index_scanned_receipts_expenseId"))
            assertTrue(indexes.contains("index_scanned_receipts_createdAt"))
        }
    }

    @Test
    fun migration_9_to_10_recreates_pending_reviews_and_preserves_status() {
        withTestDb("migration-contract-9-10.db", version = 9) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS raw_notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scanned_receipts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pending_reviews (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    rawNotificationId INTEGER,
                    suggestedAmount REAL NOT NULL,
                    suggestedCurrency TEXT NOT NULL,
                    suggestedMerchant TEXT NOT NULL,
                    suggestedType TEXT NOT NULL,
                    suggestedCategoryId INTEGER,
                    confidence REAL NOT NULL,
                    packageName TEXT NOT NULL,
                    notificationTitle TEXT,
                    notificationText TEXT,
                    createdAt INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING'
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO pending_reviews (
                    rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant,
                    suggestedType, suggestedCategoryId, confidence, packageName,
                    notificationTitle, notificationText, createdAt, status
                ) VALUES (1, 10.0, 'EUR', 'Merchant', 'PURCHASE', NULL, 0.8, 'pkg', 'title', 'text', 1234, 'PENDING')
                """.trimIndent()
            )

            AppDatabase.MIGRATION_9_10.migrate(db)

            val statusCursor = db.query("SELECT status FROM pending_reviews")
            assertTrue(statusCursor.moveToFirst())
            assertEquals("PENDING", statusCursor.getString(0))
            statusCursor.close()

            val colCursor = db.query("PRAGMA table_info(pending_reviews)")
            val columns = mutableSetOf<String>()
            while (colCursor.moveToNext()) {
                columns.add(colCursor.getString(1))
            }
            colCursor.close()
            assertTrue(columns.contains("scannedReceiptId"))
        }
    }

    private inline fun withTestDb(
        name: String,
        version: Int,
        block: (SupportSQLiteDatabase) -> Unit
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)

        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(callback)
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        try {
            block(db)
        } finally {
            db.close()
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
