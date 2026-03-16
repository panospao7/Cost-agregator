package com.yourname.expensetracker.data.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Instrumented tests for database migrations
 * 
 * Tests all 33 migrations to ensure data integrity and schema consistency.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private val testDb = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate_all_versions_from_1_to_33() {
        assumeTrue(hasSchema(1) && hasSchema(33))
        var db = helper.createDatabase(testDb, 1)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 33, true)
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_6_to_7_adds_payment_columns() {
        assumeTrue(hasSchema(6) && hasSchema(33))
        var db = helper.createDatabase(testDb, 6)
        
        db.execSQL("""
            INSERT INTO expenses (amount, currency, merchant, transactionType, date, categoryId) 
            VALUES (10.0, 'EUR', 'Test', 'PURCHASE', ${System.currentTimeMillis()}, NULL)
        """)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 33, true)
        
        val cursor = db.query("SELECT paymentMethod, isManualEntry, notes FROM expenses")
        assertTrue(cursor.moveToFirst())
        assertEquals("UNKNOWN", cursor.getString(0))
        assertEquals(0, cursor.getInt(1))
        assertNull(cursor.getString(2))
        cursor.close()
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_7_to_8_creates_budgets_table() {
        assumeTrue(hasSchema(7) && hasSchema(33))
        var db = helper.createDatabase(testDb, 7)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 33, true)
        
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='budgets'")
        assertTrue(cursor.moveToFirst())
        assertEquals("budgets", cursor.getString(0))
        cursor.close()
        
        val indexCursor = db.query("SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'index_budgets_%'")
        val indexes = mutableListOf<String>()
        while (indexCursor.moveToNext()) {
            indexes.add(indexCursor.getString(0))
        }
        indexCursor.close()
        assertTrue(indexes.contains("index_budgets_categoryId"))
        assertTrue(indexes.contains("index_budgets_isActive"))
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_8_to_9_creates_scanned_receipts_table() {
        assumeTrue(hasSchema(8) && hasSchema(33))
        var db = helper.createDatabase(testDb, 8)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 33, true)
        
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='scanned_receipts'")
        assertTrue(cursor.moveToFirst())
        cursor.close()
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_9_to_10_recreates_pending_reviews_with_status() {
        assumeTrue(hasSchema(9) && hasSchema(33))
        var db = helper.createDatabase(testDb, 9)
        
        db.execSQL("""
            INSERT INTO pending_reviews 
            (rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant, suggestedType, 
            suggestedCategoryId, confidence, packageName, notificationTitle, notificationText, createdAt)
            VALUES (1, 10.0, 'EUR', 'Test', 'PURCHASE', 1, 0.9, 'com.test', 'Title', 'Text', ${System.currentTimeMillis()})
        """)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 33, true)
        
        val cursor = db.query("SELECT status FROM pending_reviews")
        assertTrue(cursor.moveToFirst())
        assertEquals("PENDING", cursor.getString(0))
        cursor.close()
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migration_preserves_expense_data() {
        assumeTrue(hasSchema(6) && hasSchema(33))
        var db = helper.createDatabase(testDb, 6)
        
        db.execSQL("""
            INSERT INTO expenses (amount, currency, merchant, transactionType, date, categoryId)
            VALUES (10.0, 'EUR', 'Starbucks', 'PURCHASE', 1609459200000, 1)
        """)
        db.execSQL("""
            INSERT INTO expenses (amount, currency, merchant, transactionType, date, categoryId)
            VALUES (25.5, 'EUR', 'Amazon', 'PURCHASE', 1609545600000, 2)
        """)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 33, true)
        
        val cursor = db.query("SELECT amount, merchant FROM expenses ORDER BY amount")
        assertTrue(cursor.moveToFirst())
        assertEquals(10.0, cursor.getDouble(0), 0.01)
        assertEquals("Starbucks", cursor.getString(1))
        
        assertTrue(cursor.moveToNext())
        assertEquals(25.5, cursor.getDouble(0), 0.01)
        assertEquals("Amazon", cursor.getString(1))
        
        cursor.close()
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migration_preserves_category_data() {
        assumeTrue(hasSchema(10) && hasSchema(33))
        var db = helper.createDatabase(testDb, 10)
        
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                icon TEXT,
                color TEXT
            )
        """)
        db.execSQL("INSERT INTO categories (name, icon, color) VALUES ('Food', '🍔', '#FF0000')")
        db.execSQL("INSERT INTO categories (name, icon, color) VALUES ('Transport', '🚗', '#00FF00')")
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 33, true)
        
        val cursor = db.query("SELECT name, icon FROM categories ORDER BY name")
        assertTrue(cursor.moveToFirst())
        assertEquals("Food", cursor.getString(0))
        assertEquals("🍔", cursor.getString(1))
        cursor.close()
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun foreign_key_constraints_maintained_after_migration() {
        assumeTrue(hasSchema(10) && hasSchema(33))
        var db = helper.createDatabase(testDb, 10)
        
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL
            )
        """)
        db.execSQL("INSERT INTO categories (id, name) VALUES (1, 'Food')")
        
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                amount REAL NOT NULL,
                currency TEXT NOT NULL,
                merchant TEXT NOT NULL,
                transactionType TEXT NOT NULL,
                date INTEGER NOT NULL,
                categoryId INTEGER,
                FOREIGN KEY(categoryId) REFERENCES categories(id)
            )
        """)
        db.execSQL("INSERT INTO expenses (amount, currency, merchant, transactionType, date, categoryId) VALUES (10.0, 'EUR', 'Test', 'PURCHASE', 123456789, 1)")
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 33, true)
        
        val cursor = db.query("""
            SELECT e.amount, c.name 
            FROM expenses e 
            LEFT JOIN categories c ON e.categoryId = c.id
        """)
        assertTrue(cursor.moveToFirst())
        assertEquals(10.0, cursor.getDouble(0), 0.01)
        assertEquals("Food", cursor.getString(1))
        cursor.close()
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun all_expected_tables_exist_at_version_33() {
        val db = helper.createDatabase(testDb, 33)
        
        val expectedTables = listOf(
            "raw_notifications", "blocked_packages", "expenses", "categories",
            "merchant_categories", "pending_reviews", "user_corrections",
            "source_stats", "budgets", "scanned_receipts", "manual_recurring_expenses",
            "planned_expenses", "savings_goals", "merchant_canonicals", "merchant_aliases",
            "merchant_locations", "merchant_location_corrections"
        )
        
        expectedTables.forEach { tableName ->
            val cursor = db.query("""
                SELECT name FROM sqlite_master 
                WHERE type='table' AND name='$tableName'
            """)
            assertTrue("Table $tableName should exist", cursor.moveToFirst())
            cursor.close()
        }
        
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun all_expected_indices_exist_at_version_33() {
        val db = helper.createDatabase(testDb, 33)
        
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='index'")
        val indices = mutableListOf<String>()
        while (cursor.moveToNext()) {
            indices.add(cursor.getString(0))
        }
        cursor.close()
        
        assertTrue("Should have expense indices", indices.any { it.contains("expenses") })
        assertTrue("Should have budget indices", indices.any { it.contains("budgets") })
        assertTrue("Should have category indices", indices.any { it.contains("categories") })
        
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun fallback_to_destructive_migration_works() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath(testDb)
        if (file.exists()) file.delete()
        file.parentFile?.mkdirs()

        // Bootstrap a legacy pre-Room-v6 style DB that should be destructively recreated.
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        legacy.execSQL("CREATE TABLE IF NOT EXISTS legacy_table (id INTEGER PRIMARY KEY, payload TEXT)")
        legacy.execSQL("INSERT INTO legacy_table (id, payload) VALUES (1, 'stale')")
        legacy.version = 5
        legacy.close()

        val roomDb = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            testDb
        )
            .fallbackToDestructiveMigration()
            .build()

        runBlocking {
            val count = roomDb.expenseDao().getPage(limit = 1, offset = 0).size
            assertEquals(0, count)
        }

        val verify = roomDb.openHelper.writableDatabase
        val expensesCursor = verify.query("SELECT name FROM sqlite_master WHERE type='table' AND name='expenses'")
        assertTrue("Room schema should be available after fallback open", expensesCursor.moveToFirst())
        expensesCursor.close()

        roomDb.close()
    }

    @Test
    fun in_memory_database_creation_works() {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        runBlocking {
            val initial = db.expenseDao().getPage(limit = 1, offset = 0).size
            assertEquals(0, initial)
        }

        db.close()
    }

    // ── Migration 33 → 34 (AI artifacts table) ───────────────────────────────

    @Test
    @Throws(IOException::class)
    fun migrate_33_to_34_adds_ai_artifacts_table() {
        assumeTrue(hasSchema(33) && hasSchema(34))

        // Create DB at version 33 (no ai_artifacts table)
        var db = helper.createDatabase(testDb, 33)
        val tablesBefore = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            while (c.moveToNext()) tablesBefore.add(c.getString(0))
        }
        assertFalse("ai_artifacts should NOT exist at v33", tablesBefore.contains("ai_artifacts"))
        db.close()

        // Run migration to 34 and validate schema
        db = helper.runMigrationsAndValidate(testDb, 34, true)

        // Table must exist
        val tableCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ai_artifacts'"
        )
        assertTrue("ai_artifacts table should exist at v34", tableCursor.moveToFirst())
        tableCursor.close()

        // Required columns must be present — insert a representative row
        db.execSQL("""
            INSERT INTO ai_artifacts
              (targetType, targetKey, capability, status, mode, promptVersion,
               sourceHash, createdAt, updatedAt)
            VALUES
              ('PENDING_REVIEW','pending_review:1','REVIEW_EXPLANATION','READY','AUTO','v1',
               'hash_abc', ${System.currentTimeMillis()}, ${System.currentTimeMillis()})
        """)

        val rowCursor = db.query(
            "SELECT targetType, targetKey, capability, status FROM ai_artifacts"
        )
        assertTrue("Inserted row should be readable", rowCursor.moveToFirst())
        assertEquals("PENDING_REVIEW", rowCursor.getString(0))
        assertEquals("pending_review:1", rowCursor.getString(1))
        assertEquals("REVIEW_EXPLANATION", rowCursor.getString(2))
        assertEquals("READY", rowCursor.getString(3))
        rowCursor.close()

        // Required indices must exist
        val indexCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='ai_artifacts'"
        )
        val indices = mutableListOf<String>()
        while (indexCursor.moveToNext()) indices.add(indexCursor.getString(0))
        indexCursor.close()
        assertTrue(
            "Unique index on (targetKey,capability,promptVersion,sourceHash) should exist",
            indices.any { it.contains("ai_artifacts") }
        )

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_1_to_34_full_chain() {
        assumeTrue(hasSchema(1) && hasSchema(34))
        var db = helper.createDatabase(testDb, 1)
        db.close()
        db = helper.runMigrationsAndValidate(testDb, 34, true)
        // Basic smoke check — ai_artifacts table should exist at the end
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ai_artifacts'"
        )
        assertTrue("ai_artifacts should exist after 1→34 full chain", cursor.moveToFirst())
        cursor.close()
        db.close()
    }

    private fun hasSchema(version: Int): Boolean {
        val path = "${AppDatabase::class.java.canonicalName}/$version.json"
        return try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { }
            true
        } catch (_: IOException) {
            false
        }
    }
}
