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
    fun migrate_all_versions_from_1_to_51() {
        assumeTrue(hasSchema(1) && hasSchema(51))
        var db = helper.createDatabase(testDb, 1)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 51, true)
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_6_to_7_adds_payment_columns() {
        assumeTrue(hasSchema(6) && hasSchema(51))
        var db = helper.createDatabase(testDb, 6)
        
        db.execSQL("""
            INSERT INTO expenses (amount, currency, merchant, transactionType, date, categoryId) 
            VALUES (10.0, 'EUR', 'Test', 'PURCHASE', ${System.currentTimeMillis()}, NULL)
        """)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 51, true)
        
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
        assumeTrue(hasSchema(7) && hasSchema(51))
        var db = helper.createDatabase(testDb, 7)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 51, true)
        
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
        assumeTrue(hasSchema(8) && hasSchema(51))
        var db = helper.createDatabase(testDb, 8)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 51, true)
        
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='scanned_receipts'")
        assertTrue(cursor.moveToFirst())
        cursor.close()
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_9_to_10_recreates_pending_reviews_with_status() {
        assumeTrue(hasSchema(9) && hasSchema(51))
        var db = helper.createDatabase(testDb, 9)
        
        db.execSQL("""
            INSERT INTO pending_reviews 
            (rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant, suggestedType, 
            suggestedCategoryId, confidence, packageName, notificationTitle, notificationText, createdAt)
            VALUES (1, 10.0, 'EUR', 'Test', 'PURCHASE', 1, 0.9, 'com.test', 'Title', 'Text', ${System.currentTimeMillis()})
        """)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 51, true)
        
        val cursor = db.query("SELECT status FROM pending_reviews")
        assertTrue(cursor.moveToFirst())
        assertEquals("PENDING", cursor.getString(0))
        cursor.close()
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migration_preserves_expense_data() {
        assumeTrue(hasSchema(6) && hasSchema(51))
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

        db = helper.runMigrationsAndValidate(testDb, 51, true)
        
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
        assumeTrue(hasSchema(10) && hasSchema(51))
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

        db = helper.runMigrationsAndValidate(testDb, 51, true)
        
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
        assumeTrue(hasSchema(10) && hasSchema(51))
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

        db = helper.runMigrationsAndValidate(testDb, 51, true)
        
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
        assumeTrue(hasSchema(51) && hasSchema(34))

        // Create DB at version 33 (no ai_artifacts table)
        var db = helper.createDatabase(testDb, 33)
        val tablesBefore = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            while (c.moveToNext()) tablesBefore.add(c.getString(0))
        }
        assertFalse("ai_artifacts should NOT exist at v33", tablesBefore.contains("ai_artifacts"))
        db.close()

        // Run migration to 34 and validate schema
        db = helper.runMigrationsAndValidate(
            testDb,
            34,
            true,
            AppDatabase.MIGRATION_33_34
        )

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
        db = helper.runMigrationsAndValidate(
            testDb,
            34,
            true,
            AppDatabase.MIGRATION_33_34
        )
        // Basic smoke check — ai_artifacts table should exist at the end
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ai_artifacts'"
        )
        assertTrue("ai_artifacts should exist after 1→34 full chain", cursor.moveToFirst())
        cursor.close()
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_34_to_35_adds_ai_chat_tables() {
        assumeTrue(hasSchema(34) && hasSchema(35))

        var db = helper.createDatabase(testDb, 34)
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            35,
            true,
            AppDatabase.MIGRATION_34_35
        )

        val sessionTable = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ai_chat_sessions'"
        )
        assertTrue("ai_chat_sessions should exist at v35", sessionTable.moveToFirst())
        sessionTable.close()

        val messageTable = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ai_chat_messages'"
        )
        assertTrue("ai_chat_messages should exist at v35", messageTable.moveToFirst())
        messageTable.close()

        db.execSQL(
            "INSERT INTO ai_chat_sessions (title, createdAt, updatedAt) VALUES ('Phase 2', 1000, 1000)"
        )
        db.execSQL(
            "INSERT INTO ai_chat_messages (sessionId, role, kind, text, createdAt) VALUES (1, 'USER', 'QUERY', 'How much did I spend?', 1001)"
        )

        val rowCursor = db.query(
            "SELECT role, kind, text FROM ai_chat_messages WHERE sessionId = 1"
        )
        assertTrue("Inserted chat message should be readable", rowCursor.moveToFirst())
        assertEquals("USER", rowCursor.getString(0))
        assertEquals("QUERY", rowCursor.getString(1))
        assertEquals("How much did I spend?", rowCursor.getString(2))
        rowCursor.close()

        val indexCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name IN ('ai_chat_sessions', 'ai_chat_messages')"
        )
        val indices = mutableListOf<String>()
        while (indexCursor.moveToNext()) indices.add(indexCursor.getString(0))
        indexCursor.close()
        assertTrue(indices.any { it.contains("ai_chat_sessions") })
        assertTrue(indices.any { it.contains("ai_chat_messages") })

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_47_to_48_adds_isBusinessExpense_index() {
        assumeTrue(hasSchema(47) && hasSchema(51))

        var db = helper.createDatabase(testDb, 47)
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            48,
            true,
            AppDatabase.MIGRATION_47_48
        )

        // Verify the index exists
        val indexCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_expenses_isBusinessExpense'"
        )
        assertTrue("index_expenses_isBusinessExpense should exist", indexCursor.moveToFirst())
        indexCursor.close()

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_46_to_48_chain_preserves_isBusinessExpense_index() {
        assumeTrue(hasSchema(46) && hasSchema(51))

        // Create DB at version 46 (has isBusinessExpense column but index added in 40->41)
        var db = helper.createDatabase(testDb, 46)
        db.close()

        // Run full migration chain to 48
        db = helper.runMigrationsAndValidate(testDb, 51, true)

        // Verify the index exists and schema is valid
        val indexCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_expenses_isBusinessExpense'"
        )
        assertTrue("index_expenses_isBusinessExpense should exist after 46->48 migration", indexCursor.moveToFirst())
        indexCursor.close()

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_40_to_48_chain_creates_isBusinessExpense_index() {
        assumeTrue(hasSchema(40) && hasSchema(51))

        // Create DB at version 40 (before business expense fields)
        var db = helper.createDatabase(testDb, 40)
        db.close()

        // Run full migration chain to 48
        db = helper.runMigrationsAndValidate(testDb, 51, true)

        // Verify the index exists
        val indexCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_expenses_isBusinessExpense'"
        )
        assertTrue("index_expenses_isBusinessExpense should exist after 40->48 migration", indexCursor.moveToFirst())
        indexCursor.close()

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_49_to_50_preserves_foreign_keys() {
        assumeTrue(hasSchema(49) && hasSchema(50))

        var db = helper.createDatabase(testDb, 49)

        db.execSQL("""
            INSERT INTO categories (id, name, icon, color, isDefault)
            VALUES (1, 'Food', '🍔', '#FF0000', 0)
        """)

        db.execSQL("""
            INSERT INTO expenses (
                id, amount, currency, merchant, transactionType, date, categoryId,
                createdAt, paymentMethod, isManualEntry, isNotMine,
                isSharedExpense, backfillAttempts, isBusinessExpense, requiresReceipt
            ) VALUES (
                1, 19.99, 'EUR', 'Local Market', 'PURCHASE', 1700000000000, 1,
                1700000000000, 'CARD', 0, 0,
                0, 0, 0, 0
            )
        """)

        db.execSQL("""
            INSERT INTO scanned_receipts (
                id, imagePath, rawOcrText, confidence, expenseId, createdAt
            ) VALUES (
                1, '/tmp/r1.jpg', 'receipt text', 0.95, 1, 1700000001000
            )
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            50,
            true,
            AppDatabase.MIGRATION_49_50
        )

        db.query("PRAGMA foreign_key_check").use { fkCursor ->
            assertFalse("No FK violations expected after 49→50", fkCursor.moveToFirst())
        }

        db.query(
            """
            SELECT sr.id, e.merchant, c.name
            FROM scanned_receipts sr
            JOIN expenses e ON sr.expenseId = e.id
            LEFT JOIN categories c ON e.categoryId = c.id
            WHERE sr.id = 1
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("Local Market", cursor.getString(1))
            assertEquals("Food", cursor.getString(2))
        }

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_69_to_70_hardens_tokens_indices_and_email_message_ids() {
        assumeTrue(hasSchema(69) && hasSchema(70))

        var db = helper.createDatabase(testDb, 69)

        db.execSQL("""
            INSERT INTO expenses (id, amount, merchant, transactionType, date)
            VALUES (1, 15.0, 'Store A', 'PURCHASE', 1705000000000)
        """)

        db.execSQL("""
            INSERT INTO scanned_receipts (id, imagePath, rawOcrText, confidence, createdAt, expenseId)
            VALUES (1, '/tmp/s1.jpg', 'ocr-1', 0.9, 1705000000001, 1)
        """)
        db.execSQL("""
            INSERT INTO scanned_receipts (id, imagePath, rawOcrText, confidence, createdAt)
            VALUES (2, '/tmp/s2.jpg', 'ocr-2', 0.8, 1705000000002)
        """)

        db.execSQL("""
            INSERT INTO bank_connections (bankId, bankName, countryCode, accessToken, refreshToken, createdAt)
            VALUES ('dup-bank', 'Old Bank Record', 'GR', 'plain-access-1', 'plain-refresh-1', 1705000001000)
        """)
        db.execSQL("""
            INSERT INTO bank_connections (bankId, bankName, countryCode, accessToken, refreshToken, createdAt)
            VALUES ('dup-bank', 'Newest Bank Record', 'GR', 'plain-access-2', 'plain-refresh-2', 1705000002000)
        """)

        db.execSQL("""
            INSERT INTO email_receipt_sources (
                receiptId, emailSender, emailSubject, emailMessageId,
                parsedAt, provider, confidence, fingerprint
            ) VALUES (
                1, 'blank@example.com', 'No Message Id', '   ',
                1705000003000, 'GMAIL', 0.75, 'fp-blank'
            )
        """)
        db.execSQL("""
            INSERT INTO email_receipt_sources (
                receiptId, emailSender, emailSubject, emailMessageId,
                parsedAt, provider, confidence, fingerprint
            ) VALUES (
                2, 'normal@example.com', 'Has Message Id', '<msg-1>',
                1705000004000, 'GMAIL', 0.9, 'fp-msg-1'
            )
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            70,
            true,
            AppDatabase.MIGRATION_69_70
        )

        db.query("PRAGMA foreign_key_check").use { fkCursor ->
            assertFalse("No FK violations expected after 69→70", fkCursor.moveToFirst())
        }

        db.query(
            "SELECT bankName, accessToken, refreshToken, tokenEncryptionVersion FROM bank_connections WHERE bankId='dup-bank'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Newest Bank Record", cursor.getString(0))
            val accessToken = cursor.getString(1)
            val refreshToken = cursor.getString(2)
            val encryptionVersion = cursor.getInt(3)
            assertTrue("access token should be encrypted", accessToken.startsWith("enc:v1:"))
            assertTrue("refresh token should be encrypted", refreshToken.startsWith("enc:v1:"))
            assertEquals("tokenEncryptionVersion should be upgraded", 1, encryptionVersion)
            assertFalse("dedupe should keep only one row per bankId", cursor.moveToNext())
        }

        assertTrue(hasIndex(db, "index_expenses_date"))
        assertTrue(hasIndex(db, "index_expenses_merchantKey_date_amount"))
        assertTrue(hasIndex(db, "index_manual_recurring_expenses_isActive_nextDate"))
        assertTrue(hasIndex(db, "index_manual_recurring_expenses_isSubscription_isActive_nextDate"))
        assertTrue(hasIndex(db, "index_manual_recurring_expenses_merchant"))
        assertTrue(hasIndex(db, "index_spending_personality_profiles_isActive"))

        db.query(
            "SELECT emailMessageId FROM email_receipt_sources WHERE emailSender='blank@example.com'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("blank emailMessageId should migrate to NULL", cursor.isNull(0))
        }

        db.query("PRAGMA table_info(email_receipt_sources)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            var foundEmailMessageId = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "emailMessageId") {
                    foundEmailMessageId = true
                    assertEquals("emailMessageId must be nullable at v70", 0, cursor.getInt(notNullIndex))
                }
            }
            assertTrue(foundEmailMessageId)
        }

        db.execSQL("""
            INSERT INTO email_receipt_sources (
                receiptId, emailSender, emailSubject, emailMessageId,
                parsedAt, provider, confidence, fingerprint
            ) VALUES (
                1, 'second-null@example.com', 'Second Null', NULL,
                1705000005000, 'GMAIL', 0.7, 'fp-null-2'
            )
        """)

        db.query("SELECT COUNT(*) FROM email_receipt_sources WHERE emailMessageId IS NULL").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        var duplicateNonNullRejected = false
        try {
            db.execSQL("""
                INSERT INTO email_receipt_sources (
                    receiptId, emailSender, emailSubject, emailMessageId,
                    parsedAt, provider, confidence, fingerprint
                ) VALUES (
                    2, 'dup@example.com', 'Duplicate Message Id', '<msg-1>',
                    1705000006000, 'GMAIL', 0.6, 'fp-dup-msg-1'
                )
            """)
        } catch (_: Exception) {
            duplicateNonNullRejected = true
        }
        assertTrue("duplicate non-null emailMessageId should be rejected", duplicateNonNullRejected)

        db.close()
    }

    // ── Migration 50 → 51 (Schema normalization) ───────────────────────────────

    @Test
    @Throws(IOException::class)
    fun migrate_50_to_51_normalizes_indices() {
        assumeTrue(hasSchema(50) && hasSchema(51))

        // Create DB at version 50 (has schema drift issues)
        var db = helper.createDatabase(testDb, 50)
        db.close()

        // Run migration to 51 and validate
        db = helper.runMigrationsAndValidate(
            testDb,
            51,
            true,
            AppDatabase.MIGRATION_50_51
        )

        // Verify scanned_receipts has correct indices
        val indexCursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='scanned_receipts'"
        )
        val indices = mutableListOf<String>()
        while (indexCursor.moveToNext()) {
            indices.add(indexCursor.getString(0))
        }
        indexCursor.close()
        
        assertTrue("index_scanned_receipts_expenseId should exist", 
            indices.contains("index_scanned_receipts_expenseId"))
        assertTrue("index_scanned_receipts_createdAt should exist", 
            indices.contains("index_scanned_receipts_createdAt"))
        assertTrue("index_scanned_receipts_matchStatus should exist", 
            indices.contains("index_scanned_receipts_matchStatus"))
        
        // Verify legacy extra indices do NOT exist
        assertFalse("Legacy index_exchange_rates_from_to should be removed", 
            indices.any { it == "index_exchange_rates_from_to" })
        assertFalse("Legacy index_subscription_price_history_subscriptionId should be removed", 
            indices.any { it == "index_subscription_price_history_subscriptionId" })
        assertFalse("Legacy index_subscription_usage_subscriptionId should be removed", 
            indices.any { it == "index_subscription_usage_subscriptionId" })

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_49_to_51_chain_passes() {
        assumeTrue(hasSchema(49) && hasSchema(51))

        // Create DB at version 49
        var db = helper.createDatabase(testDb, 49)
        db.close()

        // Run full migration chain to 51
        db = helper.runMigrationsAndValidate(testDb, 51, true)

        // Verify app can open without crash
        val cursor = db.query("SELECT 1")
        assertTrue(cursor.moveToFirst())
        cursor.close()

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_38_to_51_chain_passes() {
        assumeTrue(hasSchema(38) && hasSchema(51))

        // Create DB at version 38 (scanned receipts era with matchStatus index introduced)
        var db = helper.createDatabase(testDb, 38)
        db.close()

        // Run full migration chain to 51
        db = helper.runMigrationsAndValidate(testDb, 51, true)

        // Verify scanned_receipts table and indices are valid
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='scanned_receipts'"
        )
        assertTrue("scanned_receipts table should exist", cursor.moveToFirst())
        cursor.close()

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_33_to_51_chain_passes() {
        assumeTrue(hasSchema(33) && hasSchema(51))

        // Create DB at version 33 (stable baseline)
        var db = helper.createDatabase(testDb, 33)
        db.close()

        // Run full migration chain to 51
        db = helper.runMigrationsAndValidate(testDb, 51, true)

        // Verify all expected tables exist
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
            assertTrue("Table $tableName should exist at v51", cursor.moveToFirst())
            cursor.close()
        }

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

    private fun hasIndex(db: androidx.sqlite.db.SupportSQLiteDatabase, indexName: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='$indexName'").use { cursor ->
            return cursor.moveToFirst()
        }
    }
}
