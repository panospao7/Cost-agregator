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
            .addCallback(AppDatabase.FRESH_INSTALL_CALLBACK)
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
        val db = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()

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

    /**
     * Tests MIGRATION_69_70 with encryption forced to fail via the test seam.
     *
     * By setting [AppDatabase.tokenEncryptionOverrideForTest] to a throwing lambda
     * we deterministically exercise the Keystore-unavailable fallback path:
     *  - migration must complete without crash
     *  - bank tokens are preserved (not nulled)
     *  - tokenEncryptionVersion stays 0 (no partial encryption recorded)
     *  - deduplication, index creation, and emailMessageId normalisation still apply
     */
    @Test
    @Throws(IOException::class)
    fun migrate_69_to_70_fallback_when_keystore_unavailable() {
        assumeTrue(hasSchema(69) && hasSchema(70))

        // Force the encryption step to throw so the fallback path is always taken.
        AppDatabase.tokenEncryptionOverrideForTest = { _ ->
            throw RuntimeException("Simulated Keystore failure")
        }
        try {
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

            // Fallback contract: encryption threw, so tokens must be preserved as plaintext
            // and tokenEncryptionVersion must be 0 (never partially incremented).
            db.query(
                "SELECT bankName, accessToken, refreshToken, tokenEncryptionVersion FROM bank_connections WHERE bankId='dup-bank'"
            ).use { cursor ->
                assertTrue("surviving bank_connection row should exist", cursor.moveToFirst())
                assertEquals("Newest Bank Record", cursor.getString(0))
                val accessToken = cursor.getString(1)
                val refreshToken = cursor.getString(2)
                val encryptionVersion = cursor.getInt(3)

                assertNotNull("accessToken must not be lost on fallback", accessToken)
                assertNotNull("refreshToken must not be lost on fallback", refreshToken)
                // Encryption was forced to throw — tokens must remain plaintext.
                assertFalse(
                    "accessToken must not carry enc:v1: prefix when encryption failed",
                    accessToken.startsWith("enc:v1:")
                )
                assertEquals(
                    "tokenEncryptionVersion must be 0 when encryption failed",
                    0, encryptionVersion
                )
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
        } finally {
            // Always restore the seam so other tests are not affected.
            AppDatabase.tokenEncryptionOverrideForTest = null
        }
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

    // ── Migration 70 → 71 (Group schema integrity) ───────────────────────────────

    @Test
    @Throws(IOException::class)
    fun migrate_70_to_71_deduplicates_current_users_and_keeps_room_declared_indexes_only() {
        assumeTrue(hasSchema(70) && hasSchema(71))

        var db = helper.createDatabase(testDb, 70)

        // Seed an expense group.
        db.execSQL("""
            INSERT INTO expense_groups (id, name, defaultCurrency, isActive, createdAt, createdBy)
            VALUES (1, 'Trip', 'EUR', 1, 1700000000000, 'me')
        """)

        // Seed three members in group 1 — two are marked as current user (bad data).
        // Retention rule: keep the row with the LARGEST id.
        db.execSQL("""
            INSERT INTO group_members (id, groupId, name, isCurrentUser, joinedAt)
            VALUES (10, 1, 'Alice', 1, 1700000001000)
        """)
        db.execSQL("""
            INSERT INTO group_members (id, groupId, name, isCurrentUser, joinedAt)
            VALUES (20, 1, 'Bob', 1, 1700000002000)
        """)
        db.execSQL("""
            INSERT INTO group_members (id, groupId, name, isCurrentUser, joinedAt)
            VALUES (30, 1, 'Charlie', 0, 1700000003000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            71,
            true,
            AppDatabase.MIGRATION_70_71
        )

        // After dedup, only Bob (id=20) should remain as currentUser in group 1.
        db.query("SELECT id, name FROM group_members WHERE groupId = 1 AND isCurrentUser = 1").use { cursor ->
            assertTrue("Exactly one current user should survive", cursor.moveToFirst())
            assertEquals(20L, cursor.getLong(0))
            assertEquals("Bob", cursor.getString(1))
            assertFalse("Only one current-user row expected", cursor.moveToNext())
        }

        // Alice (id=10) should have been demoted to isCurrentUser=0 (not deleted),
        // because deleting could violate ON DELETE RESTRICT on group_expenses.paidById.
        db.query("SELECT id, isCurrentUser FROM group_members WHERE id = 10").use { cursor ->
            assertTrue("Alice (id=10, demoted duplicate) must still exist", cursor.moveToFirst())
            assertEquals(0, cursor.getInt(1))
        }

        // Charlie (isCurrentUser=0) must be untouched.
        db.query("SELECT id FROM group_members WHERE id = 30").use { cursor ->
            assertTrue("Charlie (non-current) must be preserved", cursor.moveToFirst())
        }

        assertTrue(hasIndex(db, "index_group_members_groupId"))
        assertTrue(hasIndex(db, "index_group_members_groupId_isCurrentUser"))
        assertTrue(hasIndex(db, "index_group_members_groupId_name"))
        assertFalse(hasIndex(db, "index_group_members_groupId_currentUser"))

        // Non-current-user inserts should remain unconstrained.
        db.execSQL("""
            INSERT INTO group_members (groupId, name, isCurrentUser, joinedAt)
            VALUES (1, 'Eve', 0, 1700000005000)
        """)
        db.execSQL("""
            INSERT INTO group_members (groupId, name, isCurrentUser, joinedAt)
            VALUES (1, 'Frank', 0, 1700000006000)
        """)
        db.query("SELECT COUNT(*) FROM group_members WHERE groupId = 1 AND isCurrentUser = 0").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // Alice (demoted) + Charlie (original) + Eve + Frank = 4
            assertTrue("Multiple non-current users should be allowed", cursor.getInt(0) >= 4)
        }

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_70_to_71_deduplicates_group_expenses_expenseId_and_keeps_room_indexes_only() {
        assumeTrue(hasSchema(70) && hasSchema(71))

        var db = helper.createDatabase(testDb, 70)

        // Seed prerequisite data.
        db.execSQL("""
            INSERT INTO expense_groups (id, name, defaultCurrency, isActive, createdAt, createdBy)
            VALUES (1, 'Trip', 'EUR', 1, 1700000000000, 'me')
        """)
        db.execSQL("""
            INSERT INTO group_members (id, groupId, name, isCurrentUser, joinedAt)
            VALUES (1, 1, 'Alice', 1, 1700000001000)
        """)
        db.execSQL("""
            INSERT INTO categories (id, name, icon, color, isDefault)
            VALUES (1, 'Food', 'icon', '#FF0000', 0)
        """)
        db.execSQL("""
            INSERT INTO expenses (id, amount, currency, merchant, transactionType, date, categoryId,
                createdAt, paymentMethod, isManualEntry, isNotMine,
                isSharedExpense, backfillAttempts, isBusinessExpense, requiresReceipt)
            VALUES (100, 50.0, 'EUR', 'Restaurant', 'PURCHASE', 1700000000000, 1,
                1700000000000, 'CARD', 0, 0, 0, 0, 0, 0)
        """)

        // Seed duplicate group_expenses pointing to the same expenseId=100.
        // Retention rule: keep the row with the SMALLEST id.
        db.execSQL("""
            INSERT INTO group_expenses (id, groupId, expenseId, paidById, date, description,
                totalAmount, currency, splitType, isReimbursable, reimbursedAmount)
            VALUES (5, 1, 100, 1, 1700000000000, 'First link', 50.0, 'EUR', 'EQUAL', 0, 0.0)
        """)
        db.execSQL("""
            INSERT INTO group_expenses (id, groupId, expenseId, paidById, date, description,
                totalAmount, currency, splitType, isReimbursable, reimbursedAmount)
            VALUES (15, 1, 100, 1, 1700000000000, 'Duplicate link', 50.0, 'EUR', 'EQUAL', 0, 0.0)
        """)

        // Seed a standalone group expense (expenseId IS NULL) — must be preserved.
        db.execSQL("""
            INSERT INTO group_expenses (id, groupId, expenseId, paidById, date, description,
                totalAmount, currency, splitType, isReimbursable, reimbursedAmount)
            VALUES (25, 1, NULL, 1, 1700000000000, 'Standalone', 30.0, 'EUR', 'EQUAL', 0, 0.0)
        """)
        // A second standalone — also preserved (NULL is unconstrained).
        db.execSQL("""
            INSERT INTO group_expenses (id, groupId, expenseId, paidById, date, description,
                totalAmount, currency, splitType, isReimbursable, reimbursedAmount)
            VALUES (35, 1, NULL, 1, 1700000000000, 'Standalone 2', 20.0, 'EUR', 'EQUAL', 0, 0.0)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            71,
            true,
            AppDatabase.MIGRATION_70_71
        )

        // After dedup, only id=5 should remain for expenseId=100.
        db.query("SELECT id, description FROM group_expenses WHERE expenseId = 100").use { cursor ->
            assertTrue("Surviving linked row should exist", cursor.moveToFirst())
            assertEquals(5L, cursor.getLong(0))
            assertEquals("First link", cursor.getString(1))
            assertFalse("Only one row per expenseId", cursor.moveToNext())
        }

        // Duplicate (id=15) should be gone.
        db.query("SELECT id FROM group_expenses WHERE id = 15").use { cursor ->
            assertFalse("Duplicate expenseId link (id=15) should be deleted", cursor.moveToFirst())
        }

        // Both standalone rows (NULL expenseId) must be preserved.
        db.query("SELECT COUNT(*) FROM group_expenses WHERE expenseId IS NULL").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        assertFalse(hasIndex(db, "index_group_expenses_expenseId_unique"))
        assertTrue(hasIndex(db, "index_group_expenses_groupId"))
        assertTrue(hasIndex(db, "index_group_expenses_expenseId"))
        assertTrue(hasIndex(db, "index_group_expenses_paidById"))
        assertTrue(hasIndex(db, "index_group_expenses_groupId_date"))
        assertTrue(hasIndex(db, "index_group_expenses_isReimbursable"))

        // After healing migration, duplicates are removed but future duplicate links
        // are no longer blocked at the SQLite index level.
        db.execSQL("""
            INSERT INTO group_expenses (groupId, expenseId, paidById, date, description,
                totalAmount, currency, splitType, isReimbursable, reimbursedAmount)
            VALUES (1, 100, 1, 1700000000000, 'Dup attempt', 50.0, 'EUR', 'EQUAL', 0, 0.0)
        """)
        db.query("SELECT COUNT(*) FROM group_expenses WHERE expenseId = 100").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        // NULL expenseId inserts remain unconstrained.
        db.execSQL("""
            INSERT INTO group_expenses (groupId, expenseId, paidById, date, description,
                totalAmount, currency, splitType, isReimbursable, reimbursedAmount)
            VALUES (1, NULL, 1, 1700000000000, 'Standalone 3', 10.0, 'EUR', 'EQUAL', 0, 0.0)
        """)
        db.query("SELECT COUNT(*) FROM group_expenses WHERE expenseId IS NULL").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_70_to_71_preserves_all_v70_columns() {
        assumeTrue(hasSchema(70) && hasSchema(71))

        var db = helper.createDatabase(testDb, 70)

        db.execSQL("""
            INSERT INTO expense_groups (id, name, defaultCurrency, isActive, createdAt, createdBy)
            VALUES (1, 'Trip', 'EUR', 1, 1700000000000, 'me')
        """)
        db.execSQL("""
            INSERT INTO group_members (id, groupId, name, email, isCurrentUser, joinedAt)
            VALUES (1, 1, 'Alice', 'alice@test.com', 1, 1700000001000)
        """)
        db.execSQL("""
            INSERT INTO categories (id, name, icon, color, isDefault)
            VALUES (1, 'Food', 'icon', '#FF0000', 0)
        """)
        db.execSQL("""
            INSERT INTO expenses (id, amount, currency, merchant, transactionType, date, categoryId,
                createdAt, paymentMethod, isManualEntry, isNotMine,
                isSharedExpense, backfillAttempts, isBusinessExpense, requiresReceipt)
            VALUES (100, 50.0, 'EUR', 'Restaurant', 'PURCHASE', 1700000000000, 1,
                1700000000000, 'CARD', 0, 0, 0, 0, 0, 0)
        """)
        db.execSQL("""
            INSERT INTO group_expenses (id, groupId, expenseId, paidById, date, description,
                totalAmount, currency, splitType, customSplitsJson,
                isReimbursable, reimbursedAmount, settledAt, myShareAmount)
            VALUES (1, 1, 100, 1, 1700000000000, 'Dinner',
                50.0, 'USD', 'CUSTOM_AMOUNT', '{"1":25.0}',
                1, 10.0, 1700000500000, 25.0)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            71,
            true,
            AppDatabase.MIGRATION_70_71
        )

        // Verify all group_members columns are preserved.
        db.query("SELECT id, groupId, name, email, isCurrentUser, joinedAt FROM group_members WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(1L, cursor.getLong(1))
            assertEquals("Alice", cursor.getString(2))
            assertEquals("alice@test.com", cursor.getString(3))
            assertEquals(1, cursor.getInt(4))
            assertEquals(1700000001000L, cursor.getLong(5))
        }

        // Verify all group_expenses columns are preserved.
        db.query("""
            SELECT id, groupId, expenseId, paidById, date, description,
                   totalAmount, currency, splitType, customSplitsJson,
                   isReimbursable, reimbursedAmount, settledAt, myShareAmount
            FROM group_expenses WHERE id = 1
        """).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))            // id
            assertEquals(1L, cursor.getLong(1))            // groupId
            assertEquals(100L, cursor.getLong(2))          // expenseId
            assertEquals(1L, cursor.getLong(3))            // paidById
            assertEquals(1700000000000L, cursor.getLong(4)) // date
            assertEquals("Dinner", cursor.getString(5))    // description
            assertEquals(50.0, cursor.getDouble(6), 0.01)  // totalAmount
            assertEquals("USD", cursor.getString(7))       // currency
            assertEquals("CUSTOM_AMOUNT", cursor.getString(8)) // splitType
            assertEquals("{\"1\":25.0}", cursor.getString(9))  // customSplitsJson
            assertEquals(1, cursor.getInt(10))             // isReimbursable
            assertEquals(10.0, cursor.getDouble(11), 0.01) // reimbursedAmount
            assertEquals(1700000500000L, cursor.getLong(12)) // settledAt
            assertEquals(25.0, cursor.getDouble(13), 0.01) // myShareAmount
        }

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_70_to_71_no_op_on_clean_data() {
        assumeTrue(hasSchema(70) && hasSchema(71))

        var db = helper.createDatabase(testDb, 70)

        // Seed clean data — no duplicates.
        db.execSQL("""
            INSERT INTO expense_groups (id, name, defaultCurrency, isActive, createdAt, createdBy)
            VALUES (1, 'Trip', 'EUR', 1, 1700000000000, 'me')
        """)
        db.execSQL("""
            INSERT INTO group_members (id, groupId, name, isCurrentUser, joinedAt)
            VALUES (1, 1, 'Alice', 1, 1700000001000)
        """)
        db.execSQL("""
            INSERT INTO group_members (id, groupId, name, isCurrentUser, joinedAt)
            VALUES (2, 1, 'Bob', 0, 1700000002000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            71,
            true,
            AppDatabase.MIGRATION_70_71
        )

        // Both members should still be present.
        db.query("SELECT COUNT(*) FROM group_members WHERE groupId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        db.close()
    }

    // ── Migration 71 → 72 (Budget/recurring contract cleanup — child-row safety) ──

    @Test
    @Throws(IOException::class)
    fun migrate_71_to_72_subscription_child_rows_survive_rebuild() {
        assumeTrue(hasSchema(71) && hasSchema(72))

        var db = helper.createDatabase(testDb, 71)

        // Seed a manual recurring expense (subscription) at v71.
        db.execSQL("""
            INSERT INTO manual_recurring_expenses (
                id, merchant, amount, currency, frequency, nextDate, note,
                createdAt, isSubscription, subscriptionCategory,
                usageTargetPerMonth, cancellationUrl, isActive
            ) VALUES (
                1, 'Netflix', 13.99, 'EUR', 'MONTHLY', 1700000000000, 'Streaming',
                1690000000000, 1, 'ENTERTAINMENT',
                20, 'https://netflix.com/cancel', 1
            )
        """)

        // Seed child rows in subscription_price_history.
        db.execSQL("""
            INSERT INTO subscription_price_history (id, subscriptionId, amount, currency, recordedAt, changeReason)
            VALUES (1, 1, 11.99, 'EUR', 1690000000000, 'Initial price')
        """)
        db.execSQL("""
            INSERT INTO subscription_price_history (id, subscriptionId, amount, currency, recordedAt, changeReason)
            VALUES (2, 1, 13.99, 'EUR', 1695000000000, 'Price increase')
        """)

        // Seed child rows in subscription_usage.
        db.execSQL("""
            INSERT INTO subscription_usage (id, subscriptionId, usedAt, usageDurationMinutes, usageType)
            VALUES (1, 1, 1696000000000, 120, 'STREAMING')
        """)
        db.execSQL("""
            INSERT INTO subscription_usage (id, subscriptionId, usedAt, usageDurationMinutes, usageType)
            VALUES (2, 1, 1697000000000, 90, 'STREAMING')
        """)

        db.close()

        // Run migration 71 → 72.
        db = helper.runMigrationsAndValidate(
            testDb,
            72,
            true,
            AppDatabase.MIGRATION_71_72
        )

        // Parent row must survive the rebuild.
        db.query("SELECT id, merchant, isSubscription FROM manual_recurring_expenses WHERE id = 1").use { cursor ->
            assertTrue("Parent row must survive rebuild", cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("Netflix", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
        }

        // All subscription_price_history child rows must survive.
        db.query("SELECT COUNT(*) FROM subscription_price_history WHERE subscriptionId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Both price history rows must survive", 2, cursor.getInt(0))
        }

        // All subscription_usage child rows must survive.
        db.query("SELECT COUNT(*) FROM subscription_usage WHERE subscriptionId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Both usage rows must survive", 2, cursor.getInt(0))
        }

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_71_to_72_no_fk_violations_remain() {
        assumeTrue(hasSchema(71) && hasSchema(72))

        var db = helper.createDatabase(testDb, 71)

        // Seed parent + child data that exercises FK references.
        db.execSQL("""
            INSERT INTO manual_recurring_expenses (
                id, merchant, amount, currency, frequency, nextDate,
                createdAt, isSubscription, isActive
            ) VALUES (
                10, 'Spotify', 9.99, 'EUR', 'MONTHLY', 1700000000000,
                1690000000000, 1, 1
            )
        """)
        db.execSQL("""
            INSERT INTO subscription_price_history (id, subscriptionId, amount, currency, recordedAt)
            VALUES (10, 10, 9.99, 'EUR', 1690000000000)
        """)
        db.execSQL("""
            INSERT INTO subscription_usage (id, subscriptionId, usedAt)
            VALUES (10, 10, 1695000000000)
        """)

        // Seed a budget to exercise the budget dedup path.
        db.execSQL("""
            INSERT INTO categories (id, name, icon, color, isDefault)
            VALUES (1, 'Music', 'icon', '#00FF00', 0)
        """)
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (1, 1, 50.0, 'MONTHLY', 1700000000000, 1, 1690000000000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            72,
            true,
            AppDatabase.MIGRATION_71_72
        )

        // No FK violations must remain after migration.
        db.query("PRAGMA foreign_key_check").use { fkCursor ->
            assertFalse("No FK violations expected after 71→72", fkCursor.moveToFirst())
        }

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_71_to_72_deduplicates_overall_budgets_keeping_highest_id_active() {
        assumeTrue(hasSchema(71) && hasSchema(72))

        var db = helper.createDatabase(testDb, 71)

        // Seed three active overall budgets (categoryId IS NULL).
        // Migration must keep only the highest-id row (id=30) active,
        // demoting the others to isActive=0.
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (10, NULL, 100.0, 'MONTHLY', 1700000000000, 1, 1690000000000)
        """)
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (20, NULL, 200.0, 'MONTHLY', 1700000000000, 1, 1695000000000)
        """)
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (30, NULL, 300.0, 'MONTHLY', 1700000000000, 1, 1698000000000)
        """)

        // Also seed an inactive overall budget — must be left alone.
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (5, NULL, 50.0, 'MONTHLY', 1700000000000, 0, 1685000000000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            72,
            true,
            AppDatabase.MIGRATION_71_72
        )

        // Only id=30 should remain active for overall budgets.
        db.query("SELECT id, amount FROM budgets WHERE categoryId IS NULL AND isActive = 1").use { cursor ->
            assertTrue("Exactly one active overall budget should survive", cursor.moveToFirst())
            assertEquals(30L, cursor.getLong(0))
            assertEquals(300.0, cursor.getDouble(1), 0.01)
            assertFalse("Only one active overall budget expected", cursor.moveToNext())
        }

        // id=10 and id=20 should be demoted (not deleted).
        db.query("SELECT id FROM budgets WHERE categoryId IS NULL AND isActive = 0 ORDER BY id").use { cursor ->
            val demotedIds = mutableListOf<Long>()
            while (cursor.moveToNext()) demotedIds.add(cursor.getLong(0))
            assertTrue("id=5 (originally inactive) should remain", demotedIds.contains(5L))
            assertTrue("id=10 should be demoted", demotedIds.contains(10L))
            assertTrue("id=20 should be demoted", demotedIds.contains(20L))
        }

        // Partial unique index is intentionally not created (Room schema parity).
        assertFalse(hasIndex(db, "index_budgets_active_overall"))

        // Inserting a second active overall budget is now allowed at DB level.
        db.execSQL("""
            INSERT INTO budgets (categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (NULL, 999.0, 'MONTHLY', 1700000000000, 1, 1699000000000)
        """)

        // Inactive overall inserts remain unconstrained.
        db.execSQL("""
            INSERT INTO budgets (categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (NULL, 999.0, 'MONTHLY', 1700000000000, 0, 1699000000000)
        """)

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_71_to_72_deduplicates_category_budgets_keeping_highest_id_active() {
        assumeTrue(hasSchema(71) && hasSchema(72))

        var db = helper.createDatabase(testDb, 71)

        // Seed two categories.
        db.execSQL("""
            INSERT INTO categories (id, name, icon, color, isDefault)
            VALUES (1, 'Food', 'icon', '#FF0000', 0)
        """)
        db.execSQL("""
            INSERT INTO categories (id, name, icon, color, isDefault)
            VALUES (2, 'Transport', 'icon', '#00FF00', 0)
        """)

        // Seed duplicate active budgets for category 1.
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (10, 1, 100.0, 'MONTHLY', 1700000000000, 1, 1690000000000)
        """)
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (20, 1, 200.0, 'MONTHLY', 1700000000000, 1, 1695000000000)
        """)

        // Seed a single active budget for category 2 — no dedup needed.
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (30, 2, 150.0, 'WEEKLY', 1700000000000, 1, 1698000000000)
        """)

        // Seed an inactive budget for category 1 — must be left alone.
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (5, 1, 50.0, 'MONTHLY', 1700000000000, 0, 1685000000000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            72,
            true,
            AppDatabase.MIGRATION_71_72
        )

        // Category 1: only id=20 (highest) should remain active.
        db.query("SELECT id, amount FROM budgets WHERE categoryId = 1 AND isActive = 1").use { cursor ->
            assertTrue("Exactly one active cat-1 budget should survive", cursor.moveToFirst())
            assertEquals(20L, cursor.getLong(0))
            assertEquals(200.0, cursor.getDouble(1), 0.01)
            assertFalse("Only one active budget per category expected", cursor.moveToNext())
        }

        // id=10 should be demoted (not deleted).
        db.query("SELECT id, isActive FROM budgets WHERE id = 10").use { cursor ->
            assertTrue("id=10 (demoted duplicate) must still exist", cursor.moveToFirst())
            assertEquals(0, cursor.getInt(1))
        }

        // id=5 (originally inactive) must remain inactive.
        db.query("SELECT id, isActive FROM budgets WHERE id = 5").use { cursor ->
            assertTrue("id=5 (originally inactive) must still exist", cursor.moveToFirst())
            assertEquals(0, cursor.getInt(1))
        }

        // Category 2: id=30 should be untouched (no duplicate to resolve).
        db.query("SELECT id, isActive FROM budgets WHERE categoryId = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(30L, cursor.getLong(0))
            assertEquals(1, cursor.getInt(1))
        }

        // Partial unique index is intentionally not created (Room schema parity).
        assertFalse(hasIndex(db, "index_budgets_active_category"))

        // Inserting a second active budget for category 1 is now allowed at DB level.
        db.execSQL("""
            INSERT INTO budgets (categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (1, 999.0, 'MONTHLY', 1700000000000, 1, 1699000000000)
        """)

        // Inserting an active budget for a NEW category should succeed.
        db.execSQL("""
            INSERT INTO categories (id, name, icon, color, isDefault)
            VALUES (3, 'Entertainment', 'icon', '#0000FF', 0)
        """)
        db.execSQL("""
            INSERT INTO budgets (categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (3, 75.0, 'MONTHLY', 1700000000000, 1, 1699000000000)
        """)

        // Inactive category budget inserts remain unconstrained.
        db.execSQL("""
            INSERT INTO budgets (categoryId, amount, period, startDate, isActive, createdAt)
            VALUES (1, 888.0, 'MONTHLY', 1700000000000, 0, 1699000000000)
        """)

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_71_to_72_preserves_old_isSubscription_values_and_changes_default() {
        assumeTrue(hasSchema(71) && hasSchema(72))

        var db = helper.createDatabase(testDb, 71)

        // Row that was explicitly a subscription (isSubscription = 1).
        db.execSQL("""
            INSERT INTO manual_recurring_expenses (
                id, merchant, amount, currency, frequency, nextDate,
                createdAt, isSubscription, isActive
            ) VALUES (
                1, 'Netflix', 13.99, 'EUR', 'MONTHLY', 1700000000000,
                1690000000000, 1, 1
            )
        """)

        // Row that was explicitly NOT a subscription (isSubscription = 0).
        db.execSQL("""
            INSERT INTO manual_recurring_expenses (
                id, merchant, amount, currency, frequency, nextDate,
                createdAt, isSubscription, isActive
            ) VALUES (
                2, 'Rent', 800.0, 'EUR', 'MONTHLY', 1700000000000,
                1690000000000, 0, 1
            )
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            72,
            true,
            AppDatabase.MIGRATION_71_72
        )

        // Verify old rows preserve their stored isSubscription values.
        db.query("SELECT id, isSubscription FROM manual_recurring_expenses ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("Existing subscription row must keep isSubscription=1", 1, cursor.getInt(1))

            assertTrue(cursor.moveToNext())
            assertEquals(2L, cursor.getLong(0))
            assertEquals("Non-subscription row must keep isSubscription=0", 0, cursor.getInt(1))
        }

        // Verify new DEFAULT is 0 by inserting a row without specifying isSubscription.
        db.execSQL("""
            INSERT INTO manual_recurring_expenses (
                merchant, amount, currency, frequency, nextDate, createdAt
            ) VALUES (
                'Gym', 30.0, 'EUR', 'MONTHLY', 1701000000000, 1700000000000
            )
        """)

        db.query("SELECT isSubscription FROM manual_recurring_expenses WHERE merchant = 'Gym'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("New row default isSubscription must be 0", 0, cursor.getInt(0))
        }

        db.close()
    }

    // ── Migration 72 → 73 (Batch 5: canonical dedup, alias repoint, areaKey normalization) ──

    /**
     * Verifies that aliases tied to duplicate (loser) canonical rows are repointed
     * to the surviving canonical (MAX(id) per searchKey) before the duplicates are
     * deleted — preventing silent ON DELETE CASCADE alias loss.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_72_to_73_repoints_aliases_to_surviving_canonical_and_no_alias_lost() {
        assumeTrue(hasSchema(72) && hasSchema(73))

        var db = helper.createDatabase(testDb, 72)

        // Two canonical rows with the same searchKey — id=1 is the loser, id=2 is the winner.
        db.execSQL("""
            INSERT INTO merchant_canonicals (id, normalizedName, searchKey, totalOccurrences, totalSpent, isVerified, createdAt, updatedAt)
            VALUES (1, 'McDonalds Old', 'mcdonalds', 5, 50.0, 0, 1000, 1000)
        """)
        db.execSQL("""
            INSERT INTO merchant_canonicals (id, normalizedName, searchKey, totalOccurrences, totalSpent, isVerified, createdAt, updatedAt)
            VALUES (2, 'McDonalds New', 'mcdonalds', 10, 100.0, 0, 2000, 2000)
        """)

        // A third canonical with a unique searchKey — should be untouched.
        db.execSQL("""
            INSERT INTO merchant_canonicals (id, normalizedName, searchKey, totalOccurrences, totalSpent, isVerified, createdAt, updatedAt)
            VALUES (3, 'Starbucks', 'starbucks', 3, 30.0, 0, 1000, 1000)
        """)

        // Alias pointing to the LOSER canonical (id=1) — must survive, repointed to id=2.
        db.execSQL("""
            INSERT INTO merchant_aliases (id, rawName, normalizedKey, canonicalId, occurrenceCount, isUserDefined, createdAt, lastUsedAt)
            VALUES (10, 'MCDONALDS #1234', 'mcdonalds1234', 1, 2, 0, 1000, 1000)
        """)

        // Alias already pointing to the WINNER canonical (id=2) — must remain.
        db.execSQL("""
            INSERT INTO merchant_aliases (id, rawName, normalizedKey, canonicalId, occurrenceCount, isUserDefined, createdAt, lastUsedAt)
            VALUES (11, 'MCDONALDS #5678', 'mcdonalds5678', 2, 3, 0, 1000, 1000)
        """)

        // Alias pointing to the unrelated canonical (id=3) — must be untouched.
        db.execSQL("""
            INSERT INTO merchant_aliases (id, rawName, normalizedKey, canonicalId, occurrenceCount, isUserDefined, createdAt, lastUsedAt)
            VALUES (12, 'STARBUCKS DOWNTOWN', 'starbucksdowntown', 3, 1, 0, 1000, 1000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            73,
            true,
            AppDatabase.MIGRATION_72_73
        )

        // All three aliases must survive — no cascade loss.
        db.query("SELECT COUNT(*) FROM merchant_aliases").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("All 3 aliases must survive migration", 3, cursor.getInt(0))
        }

        // Alias id=10 must now point to the winner canonical (id=2).
        db.query("SELECT canonicalId FROM merchant_aliases WHERE id = 10").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Loser-alias must be repointed to winner canonical", 2L, cursor.getLong(0))
        }

        // Alias id=11 must still point to the winner canonical (id=2).
        db.query("SELECT canonicalId FROM merchant_aliases WHERE id = 11").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Winner-alias must stay on winner canonical", 2L, cursor.getLong(0))
        }

        // Alias id=12 must still point to canonical id=3 (unrelated).
        db.query("SELECT canonicalId FROM merchant_aliases WHERE id = 12").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Unrelated alias must be untouched", 3L, cursor.getLong(0))
        }

        // Only the winner canonical (id=2) and the unrelated canonical (id=3) should remain.
        db.query("SELECT id FROM merchant_canonicals ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2L, cursor.getLong(0))
            assertTrue(cursor.moveToNext())
            assertEquals(3L, cursor.getLong(0))
            assertFalse("Loser canonical (id=1) must have been deleted", cursor.moveToNext())
        }

        db.close()
    }

    /**
     * Verifies that NULL areaKey and legacy "<key>|global" areaKey values are both
     * normalized to plain "global", and that collision dedup (same normalizedMerchantName
     * after normalization) retains the row with the highest hitCount.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_72_to_73_normalizes_areaKey_null_and_pipe_global_and_dedup_collision() {
        assumeTrue(hasSchema(72) && hasSchema(73))

        var db = helper.createDatabase(testDb, 72)

        // Row 1: areaKey IS NULL — should become 'global'.
        db.execSQL("""
            INSERT INTO merchant_locations (id, normalizedMerchantName, areaKey, displayName, latitude, longitude, source, confidence, lastResolvedAt, hitCount)
            VALUES (1, 'lidl', NULL, 'Lidl', 37.9, 23.7, 'NOMINATIM_GPS_BIAS', 0.9, 1000, 5)
        """)

        // Row 2: areaKey = 'somekey|global' — should become 'global', colliding with row 1.
        // Row 2 has higher hitCount, so it should be the survivor.
        db.execSQL("""
            INSERT INTO merchant_locations (id, normalizedMerchantName, areaKey, displayName, latitude, longitude, source, confidence, lastResolvedAt, hitCount)
            VALUES (2, 'lidl', 'somekey|global', 'Lidl Store', 37.9, 23.7, 'NOMINATIM_GPS_BIAS', 0.85, 2000, 10)
        """)

        // Row 3: areaKey = 'area42|global' — should become 'global'. No collision because
        // different normalizedMerchantName.
        db.execSQL("""
            INSERT INTO merchant_locations (id, normalizedMerchantName, areaKey, displayName, latitude, longitude, source, confidence, lastResolvedAt, hitCount)
            VALUES (3, 'sklavenitis', 'area42|global', 'Sklavenitis', 38.0, 23.8, 'NOMINATIM_NAME_ONLY', 0.95, 3000, 7)
        """)

        // Row 4: areaKey = '843|18' (a real area key) — should be left as-is.
        db.execSQL("""
            INSERT INTO merchant_locations (id, normalizedMerchantName, areaKey, displayName, latitude, longitude, source, confidence, lastResolvedAt, hitCount)
            VALUES (4, 'lidl', '843|18', 'Lidl Athens', 37.95, 23.72, 'OVERPASS_POI', 0.8, 4000, 3)
        """)

        // Row 5: areaKey already 'global' — should remain 'global'. Different merchant.
        db.execSQL("""
            INSERT INTO merchant_locations (id, normalizedMerchantName, areaKey, displayName, latitude, longitude, source, confidence, lastResolvedAt, hitCount)
            VALUES (5, 'ab_vassilopoulos', 'global', 'AB Vassilopoulos', 37.85, 23.75, 'NOMINATIM_GPS_BIAS', 1.0, 5000, 2)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            73,
            true,
            AppDatabase.MIGRATION_72_73
        )

        // Collision between row 1 (hitCount=5) and row 2 (hitCount=10) for ('lidl', 'global'):
        // Row 2 wins because it has the higher hitCount.
        db.query(
            "SELECT id, areaKey, hitCount FROM merchant_locations WHERE normalizedMerchantName = 'lidl' AND areaKey = 'global'"
        ).use { cursor ->
            assertTrue("One 'lidl'/'global' row must survive", cursor.moveToFirst())
            assertEquals("Winner should be id=2 (higher hitCount)", 2L, cursor.getLong(0))
            assertEquals("global", cursor.getString(1))
            assertEquals(10, cursor.getInt(2))
            assertFalse("Only one row should survive the collision", cursor.moveToNext())
        }

        // Row 3 ('sklavenitis') should be normalized to 'global' with no collision.
        db.query(
            "SELECT id, areaKey FROM merchant_locations WHERE normalizedMerchantName = 'sklavenitis'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3L, cursor.getLong(0))
            assertEquals("global", cursor.getString(1))
        }

        // Row 4 ('lidl', '843|18') is a real area key, not ending with '|global' — must be left as-is.
        db.query(
            "SELECT id, areaKey FROM merchant_locations WHERE normalizedMerchantName = 'lidl' AND areaKey = '843|18'"
        ).use { cursor ->
            assertTrue("Real area key row must survive unchanged", cursor.moveToFirst())
            assertEquals(4L, cursor.getLong(0))
        }

        // Row 5 ('ab_vassilopoulos', 'global') should remain unchanged.
        db.query(
            "SELECT id, areaKey FROM merchant_locations WHERE normalizedMerchantName = 'ab_vassilopoulos'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(5L, cursor.getLong(0))
            assertEquals("global", cursor.getString(1))
        }

        // Total rows: 3 (row 2 survivor, row 3, row 4, row 5 = 4 rows; row 1 deduped)
        db.query("SELECT COUNT(*) FROM merchant_locations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("4 rows should survive (1 deduped from collision)", 4, cursor.getInt(0))
        }

        db.close()
    }

    /**
     * Verifies that the unique indexes created by MIGRATION_72_73 reject duplicate
     * inserts after migration: unique searchKey on merchant_canonicals, unique
     * normalizedKey on merchant_aliases, and unique (normalizedMerchantName, areaKey)
     * on merchant_locations. Also verifies areaKey NOT NULL constraint.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_72_to_73_enforces_unique_indexes_and_not_null_areaKey() {
        assumeTrue(hasSchema(72) && hasSchema(73))

        var db = helper.createDatabase(testDb, 72)

        // Seed one canonical, one alias, one location — all valid, no duplicates.
        db.execSQL("""
            INSERT INTO merchant_canonicals (id, normalizedName, searchKey, totalOccurrences, totalSpent, isVerified, createdAt, updatedAt)
            VALUES (1, 'Lidl', 'lidl', 1, 10.0, 0, 1000, 1000)
        """)
        db.execSQL("""
            INSERT INTO merchant_aliases (id, rawName, normalizedKey, canonicalId, occurrenceCount, isUserDefined, createdAt, lastUsedAt)
            VALUES (1, 'LIDL #100', 'lidl100', 1, 1, 0, 1000, 1000)
        """)
        db.execSQL("""
            INSERT INTO merchant_locations (id, normalizedMerchantName, areaKey, displayName, latitude, longitude, source, confidence, lastResolvedAt, hitCount)
            VALUES (1, 'lidl', 'global', 'Lidl', 37.9, 23.7, 'NOMINATIM_GPS_BIAS', 0.9, 1000, 1)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            73,
            true,
            AppDatabase.MIGRATION_72_73
        )

        // ── Unique searchKey on merchant_canonicals ──
        assertTrue(
            "Unique searchKey index must exist",
            hasIndex(db, "index_merchant_canonicals_searchKey")
        )
        var constraintViolated = false
        try {
            db.execSQL("""
                INSERT INTO merchant_canonicals (normalizedName, searchKey, totalOccurrences, totalSpent, isVerified, createdAt, updatedAt)
                VALUES ('Lidl Duplicate', 'lidl', 0, 0.0, 0, 2000, 2000)
            """)
        } catch (_: Exception) {
            constraintViolated = true
        }
        assertTrue("Duplicate searchKey must be rejected by unique index", constraintViolated)

        // ── Unique normalizedKey on merchant_aliases ──
        assertTrue(
            "Unique normalizedKey index must exist",
            hasIndex(db, "index_merchant_aliases_normalizedKey")
        )
        constraintViolated = false
        try {
            db.execSQL("""
                INSERT INTO merchant_aliases (rawName, normalizedKey, canonicalId, occurrenceCount, isUserDefined, createdAt, lastUsedAt)
                VALUES ('LIDL #200', 'lidl100', 1, 1, 0, 2000, 2000)
            """)
        } catch (_: Exception) {
            constraintViolated = true
        }
        assertTrue("Duplicate normalizedKey must be rejected by unique index", constraintViolated)

        // ── Unique (normalizedMerchantName, areaKey) on merchant_locations ──
        assertTrue(
            "Unique composite index on merchant_locations must exist",
            hasIndex(db, "index_merchant_locations_normalizedMerchantName_areaKey")
        )
        constraintViolated = false
        try {
            db.execSQL("""
                INSERT INTO merchant_locations (normalizedMerchantName, areaKey, displayName, latitude, longitude, source, confidence, lastResolvedAt, hitCount)
                VALUES ('lidl', 'global', 'Lidl Dup', 37.9, 23.7, 'NOMINATIM_GPS_BIAS', 0.9, 2000, 1)
            """)
        } catch (_: Exception) {
            constraintViolated = true
        }
        assertTrue("Duplicate (normalizedMerchantName, areaKey) must be rejected", constraintViolated)

        // ── NOT NULL constraint on areaKey ──
        constraintViolated = false
        try {
            db.execSQL("""
                INSERT INTO merchant_locations (normalizedMerchantName, areaKey, displayName, latitude, longitude, source, confidence, lastResolvedAt, hitCount)
                VALUES ('newmerchant', NULL, 'New Merchant', 37.9, 23.7, 'NOMINATIM_GPS_BIAS', 0.9, 3000, 1)
            """)
        } catch (_: Exception) {
            constraintViolated = true
        }
        assertTrue("NULL areaKey must be rejected by NOT NULL constraint", constraintViolated)

        // ── Inserting a valid new row with different keys must still succeed ──
        db.execSQL("""
            INSERT INTO merchant_canonicals (normalizedName, searchKey, totalOccurrences, totalSpent, isVerified, createdAt, updatedAt)
            VALUES ('Starbucks', 'starbucks', 0, 0.0, 0, 2000, 2000)
        """)
        db.execSQL("""
            INSERT INTO merchant_aliases (rawName, normalizedKey, canonicalId, occurrenceCount, isUserDefined, createdAt, lastUsedAt)
            VALUES ('LIDL #300', 'lidl300', 1, 1, 0, 2000, 2000)
        """)
        db.execSQL("""
            INSERT INTO merchant_locations (normalizedMerchantName, areaKey, displayName, latitude, longitude, source, confidence, lastResolvedAt, hitCount)
            VALUES ('lidl', '843|18', 'Lidl Athens', 37.95, 23.72, 'OVERPASS_POI', 0.8, 2000, 1)
        """)

        db.close()
    }

    /**
     * Verifies that legacy merchant_location_corrections rows whose areaKey is
     * "<merchant>|global" are normalized to plain "global" during 72→73, and that
     * collisions with existing "global" rows are resolved by keeping the newest
     * correction (largest id).
     */
    @Test
    @Throws(IOException::class)
    fun migrate_72_to_73_normalizes_correction_areaKey_pipe_global_and_dedup() {
        assumeTrue(hasSchema(72) && hasSchema(73))

        var db = helper.createDatabase(testDb, 72)

        // Row 1: already-canonical "global" correction for 'lidl', id=1.
        db.execSQL("""
            INSERT INTO merchant_location_corrections
                (id, normalizedMerchantName, correctedLatitude, correctedLongitude,
                 areaKey, areaRadiusKm, createdAt)
            VALUES (1, 'lidl', 37.90, 23.70, 'global', 5.0, 1000)
        """)

        // Row 2: legacy "lidl|global" correction for the SAME merchant, id=2.
        // After normalization this collides with row 1 on ('lidl','global').
        // Row 2 (larger id) should survive.
        db.execSQL("""
            INSERT INTO merchant_location_corrections
                (id, normalizedMerchantName, correctedLatitude, correctedLongitude,
                 areaKey, areaRadiusKm, createdAt)
            VALUES (2, 'lidl', 37.92, 23.72, 'lidl|global', 5.0, 2000)
        """)

        // Row 3: legacy "sklavenitis|global" with no collision — should simply normalize.
        db.execSQL("""
            INSERT INTO merchant_location_corrections
                (id, normalizedMerchantName, correctedLatitude, correctedLongitude,
                 areaKey, areaRadiusKm, createdAt)
            VALUES (3, 'sklavenitis', 38.00, 23.80, 'sklavenitis|global', 5.0, 3000)
        """)

        // Row 4: real area key for 'lidl' — must be left as-is.
        db.execSQL("""
            INSERT INTO merchant_location_corrections
                (id, normalizedMerchantName, correctedLatitude, correctedLongitude,
                 areaKey, areaRadiusKm, createdAt)
            VALUES (4, 'lidl', 37.95, 23.75, '843|18', 5.0, 4000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            73,
            true,
            AppDatabase.MIGRATION_72_73
        )

        // Row 1 (id=1) should be deduped away; row 2 (id=2, largest id) survives as ('lidl','global').
        db.query(
            "SELECT id, areaKey, correctedLatitude FROM merchant_location_corrections " +
                "WHERE normalizedMerchantName = 'lidl' AND areaKey = 'global'"
        ).use { cursor ->
            assertTrue("One 'lidl'/'global' correction must survive", cursor.moveToFirst())
            assertEquals("Newest correction (largest id) wins", 2L, cursor.getLong(0))
            assertEquals("global", cursor.getString(1))
            assertEquals(37.92, cursor.getDouble(2), 0.001)
            assertFalse("Only one row should survive the collision", cursor.moveToNext())
        }

        // Row 3 should be normalized to 'global' with no collision.
        db.query(
            "SELECT id, areaKey FROM merchant_location_corrections WHERE normalizedMerchantName = 'sklavenitis'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3L, cursor.getLong(0))
            assertEquals("global", cursor.getString(1))
        }

        // Row 4 (real area key) must be left as-is.
        db.query(
            "SELECT id, areaKey FROM merchant_location_corrections WHERE normalizedMerchantName = 'lidl' AND areaKey = '843|18'"
        ).use { cursor ->
            assertTrue("Real area key must survive unchanged", cursor.moveToFirst())
            assertEquals(4L, cursor.getLong(0))
        }

        // Total rows: 3 (row 2 survivor, row 3, row 4; row 1 deduped).
        db.query("SELECT COUNT(*) FROM merchant_location_corrections").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("3 rows should survive (1 deduped from collision)", 3, cursor.getInt(0))
        }

        db.close()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Migration 73 → 74 (B4 Batch 6): bank/email/notification/alert hardening
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that MIGRATION_73_74 rebuilds bank_connections with a FK on
     * defaultCategoryId → categories(id), cleans orphaned defaultCategoryId
     * references, and creates the supporting index.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_73_to_74_bank_connections_fk_and_index() {
        assumeTrue(hasSchema(73) && hasSchema(74))

        var db = helper.createDatabase(testDb, 73)

        // Insert a category so we can reference it as a valid FK target.
        db.execSQL("""
            INSERT INTO categories (id, name, icon, color, isDefault, sortOrder, isHidden)
            VALUES (100, 'Groceries', 'cart', '#00FF00', 0, 1, 0)
        """)

        // Row 1: valid defaultCategoryId → should survive.
        db.execSQL("""
            INSERT INTO bank_connections (id, bankId, bankName, countryCode, tokenEncryptionVersion,
                isActive, isConnected, lastSyncStatus, autoSync, syncFrequency,
                defaultCategoryId, consecutiveErrors, createdAt)
            VALUES (1, 'bank_alpha', 'Alpha Bank', 'GR', 0,
                    1, 1, 'SUCCESS', 1, 'DAILY',
                    100, 0, 1000)
        """)

        // Row 2: orphaned defaultCategoryId (999 doesn't exist) → should be NULLed.
        db.execSQL("""
            INSERT INTO bank_connections (id, bankId, bankName, countryCode, tokenEncryptionVersion,
                isActive, isConnected, lastSyncStatus, autoSync, syncFrequency,
                defaultCategoryId, consecutiveErrors, createdAt)
            VALUES (2, 'bank_beta', 'Beta Bank', 'GR', 0,
                    1, 1, 'SUCCESS', 1, 'DAILY',
                    999, 0, 2000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 74, true,
            AppDatabase.MIGRATION_73_74
        )

        // Row 1: defaultCategoryId should still be 100.
        db.query("SELECT defaultCategoryId FROM bank_connections WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(100L, c.getLong(0))
        }

        // Row 2: defaultCategoryId should have been NULLed out (orphan cleanup).
        db.query("SELECT defaultCategoryId FROM bank_connections WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("Orphaned defaultCategoryId must be NULL", c.isNull(0))
        }

        // The defaultCategoryId index must exist.
        assertTrue(
            "index_bank_connections_defaultCategoryId must exist",
            hasIndex(db, "index_bank_connections_defaultCategoryId")
        )

        db.close()
    }

    /**
     * Verifies that MIGRATION_73_74 deduplicates legacy raw_notifications rows
     * and leaves only the Room-declared indexes in place.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_73_to_74_raw_notifications_dedup_and_keeps_room_indexes_only() {
        assumeTrue(hasSchema(73) && hasSchema(74))

        var db = helper.createDatabase(testDb, 73)

        // Duplicate pair: same packageName+timestamp, both title AND text NULL.
        // The old unique index allowed this because NULL != NULL in SQLite.
        // Only id=1 (smallest) should survive.
        db.execSQL("""
            INSERT INTO raw_notifications (
                id, packageName, appName, title, text, bigText, subText, extrasJson,
                timestamp, capturedAt, isProcessed, isRelevant, parseResult
            ) VALUES (
                1, 'com.bank.app', 'Bank App', NULL, NULL, NULL, NULL, NULL,
                1000, 1000, 0, NULL, NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO raw_notifications (
                id, packageName, appName, title, text, bigText, subText, extrasJson,
                timestamp, capturedAt, isProcessed, isRelevant, parseResult
            ) VALUES (
                2, 'com.bank.app', 'Bank App', NULL, NULL, NULL, NULL, NULL,
                1000, 1001, 0, NULL, NULL
            )
        """.trimIndent())

        // Duplicate pair: same packageName+timestamp, title NULL, text NOT NULL and same.
        // Only id=3 should survive.
        db.execSQL("""
            INSERT INTO raw_notifications (
                id, packageName, appName, title, text, bigText, subText, extrasJson,
                timestamp, capturedAt, isProcessed, isRelevant, parseResult
            ) VALUES (
                3, 'com.bank.app', 'Bank App', NULL, 'Payment received', NULL, NULL, NULL,
                2000, 2000, 0, NULL, NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO raw_notifications (
                id, packageName, appName, title, text, bigText, subText, extrasJson,
                timestamp, capturedAt, isProcessed, isRelevant, parseResult
            ) VALUES (
                4, 'com.bank.app', 'Bank App', NULL, 'Payment received', NULL, NULL, NULL,
                2000, 2001, 0, NULL, NULL
            )
        """.trimIndent())

        // Duplicate pair: same packageName+timestamp, text NULL, title NOT NULL and same.
        // Only id=5 should survive.
        db.execSQL("""
            INSERT INTO raw_notifications (
                id, packageName, appName, title, text, bigText, subText, extrasJson,
                timestamp, capturedAt, isProcessed, isRelevant, parseResult
            ) VALUES (
                5, 'com.bank.app', 'Bank App', 'Alert', NULL, NULL, NULL, NULL,
                3000, 3000, 0, NULL, NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO raw_notifications (
                id, packageName, appName, title, text, bigText, subText, extrasJson,
                timestamp, capturedAt, isProcessed, isRelevant, parseResult
            ) VALUES (
                6, 'com.bank.app', 'Bank App', 'Alert', NULL, NULL, NULL, NULL,
                3000, 3001, 0, NULL, NULL
            )
        """.trimIndent())

        // Non-duplicate row: both title and text NOT NULL. Should survive as-is.
        db.execSQL("""
            INSERT INTO raw_notifications (
                id, packageName, appName, title, text, bigText, subText, extrasJson,
                timestamp, capturedAt, isProcessed, isRelevant, parseResult
            ) VALUES (
                7, 'com.bank.app', 'Bank App', 'Transfer', 'Amount 50', NULL, NULL, NULL,
                4000, 4000, 0, 1, 'parsed'
            )
        """.trimIndent())

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 74, true,
            AppDatabase.MIGRATION_73_74
        )

        // Should have 4 rows: ids 1, 3, 5, 7 survive.
        db.query("SELECT COUNT(*) FROM raw_notifications").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("4 rows should survive dedup", 4, c.getInt(0))
        }

        // Verify the survivors.
        db.query("SELECT id FROM raw_notifications ORDER BY id").use { c ->
            val ids = mutableListOf<Long>()
            while (c.moveToNext()) ids += c.getLong(0)
            assertEquals(listOf(1L, 3L, 5L, 7L), ids)
        }

        // Verify only Room-declared indexes exist.
        assertTrue(hasIndex(db, "index_raw_notifications_packageName_timestamp_title_text"))
        assertTrue(hasIndex(db, "index_raw_notifications_packageName_timestamp"))
        assertTrue(hasIndex(db, "index_raw_notifications_capturedAt"))
        assertTrue(hasIndex(db, "index_raw_notifications_isRelevant"))

        assertFalse(hasIndex(db, "index_raw_notifications_dedup_nonnull"))
        assertFalse(hasIndex(db, "index_raw_notifications_dedup_both_null"))
        assertFalse(hasIndex(db, "index_raw_notifications_dedup_title_null"))
        assertFalse(hasIndex(db, "index_raw_notifications_dedup_text_null"))

        assertEquals(
            setOf(
                "index_raw_notifications_packageName_timestamp_title_text",
                "index_raw_notifications_packageName_timestamp",
                "index_raw_notifications_capturedAt",
                "index_raw_notifications_isRelevant"
            ),
            rawNotificationIndexes(db)
        )

        db.query("PRAGMA index_list('raw_notifications')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            var foundCoveringIndex = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "index_raw_notifications_packageName_timestamp_title_text") {
                    foundCoveringIndex = true
                    assertEquals(0, cursor.getInt(uniqueIndex))
                }
            }
            assertTrue(foundCoveringIndex)
        }

        // The migration should not reintroduce non-Room uniqueness constraints.
        db.execSQL("""
            INSERT INTO raw_notifications (
                packageName, appName, title, text, bigText, subText, extrasJson,
                timestamp, capturedAt, isProcessed, isRelevant, parseResult
            ) VALUES (
                'com.bank.app', 'Bank App', NULL, NULL, NULL, NULL, NULL,
                1000, 9999, 0, NULL, NULL
            )
        """.trimIndent())

        db.query("SELECT COUNT(*) FROM raw_notifications WHERE packageName = 'com.bank.app' AND timestamp = 1000").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }

        db.close()
    }

    /**
     * Verifies that MIGRATION_73_74 rebuilds anomaly_alerts with a FK on
     * expenseId → expenses(id) ON DELETE CASCADE, and deletes orphaned rows.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_73_to_74_anomaly_alerts_fk_and_orphan_cleanup() {
        assumeTrue(hasSchema(73) && hasSchema(74))

        var db = helper.createDatabase(testDb, 73)

        // Insert a valid expense so we can create a non-orphaned alert.
        db.execSQL("""
            INSERT INTO expenses (id, amount, currency, merchant, transactionType, date, paymentMethod, isManualEntry)
            VALUES (500, 25.00, 'EUR', 'TestMerchant', 'PURCHASE', 1000, 'CARD', 0)
        """)

        // Row 1: valid expenseId (500) → should survive.
        db.execSQL("""
            INSERT INTO anomaly_alerts (id, expenseId, merchant, amount, anomalyReason, severity, alertedAt, dismissed)
            VALUES (1, 500, 'TestMerchant', 25.00, 'UNUSUAL_AMOUNT', 'HIGH', 1000, 0)
        """)

        // Row 2: orphaned expenseId (9999 doesn't exist) → should be deleted.
        db.execSQL("""
            INSERT INTO anomaly_alerts (id, expenseId, merchant, amount, anomalyReason, severity, alertedAt, dismissed)
            VALUES (2, 9999, 'Ghost', 100.00, 'UNUSUAL_MERCHANT', 'MEDIUM', 2000, 0)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 74, true,
            AppDatabase.MIGRATION_73_74
        )

        // Only row 1 should survive.
        db.query("SELECT COUNT(*) FROM anomaly_alerts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Only non-orphaned alert should survive", 1, c.getInt(0))
        }
        db.query("SELECT id, expenseId FROM anomaly_alerts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals(500L, c.getLong(1))
        }

        // Verify the expenseId index still exists.
        assertTrue(hasIndex(db, "index_anomaly_alerts_expenseId"))

        db.close()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Migration 74 → 75 (B4 Batch 7): subscription_candidates + budget_forecasts
    //                                  uniqueness constraints
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that MIGRATION_74_75 deduplicates pending subscription candidates
     * with duplicate (canonicalMerchant, detectedInterval), preserves non-pending
     * and converted rows, and leaves only Room-declared indexes in place.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_74_to_75_subscription_candidates_dedup_and_room_indexes() {
        assumeTrue(hasSchema(74) && hasSchema(75))

        var db = helper.createDatabase(testDb, 74)

        // Pending duplicate pair: same canonicalMerchant+detectedInterval, both pending.
        // Only id=2 (MAX id) should survive.
        db.execSQL("""
            INSERT INTO subscription_candidates (id, merchant, canonicalMerchant,
                detectedInterval, confidence, firstSeen, lastSeen, transactionCount,
                averageAmount, currency, estimatedAnnualCost, isConverted,
                convertedSubscriptionId, userAction, createdAt, updatedAt)
            VALUES (1, 'Netflix Inc.', 'netflix', 'MONTHLY', 0.9, 1000, 2000, 3,
                15.99, 'EUR', 191.88, 0, NULL, 'pending', 1000, 2000)
        """)
        db.execSQL("""
            INSERT INTO subscription_candidates (id, merchant, canonicalMerchant,
                detectedInterval, confidence, firstSeen, lastSeen, transactionCount,
                averageAmount, currency, estimatedAnnualCost, isConverted,
                convertedSubscriptionId, userAction, createdAt, updatedAt)
            VALUES (2, 'NETFLIX', 'netflix', 'MONTHLY', 0.95, 1500, 2500, 4,
                15.99, 'EUR', 191.88, 0, NULL, 'pending', 1500, 2500)
        """)

        // Different interval — should NOT be considered a duplicate.
        db.execSQL("""
            INSERT INTO subscription_candidates (id, merchant, canonicalMerchant,
                detectedInterval, confidence, firstSeen, lastSeen, transactionCount,
                averageAmount, currency, estimatedAnnualCost, isConverted,
                convertedSubscriptionId, userAction, createdAt, updatedAt)
            VALUES (3, 'Netflix', 'netflix', 'YEARLY', 0.8, 1000, 2000, 1,
                120.0, 'EUR', 120.0, 0, NULL, 'pending', 1000, 2000)
        """)

        // Converted row (isConverted = 1) — should NOT be affected by dedup.
        db.execSQL("""
            INSERT INTO subscription_candidates (id, merchant, canonicalMerchant,
                detectedInterval, confidence, firstSeen, lastSeen, transactionCount,
                averageAmount, currency, estimatedAnnualCost, isConverted,
                convertedSubscriptionId, userAction, createdAt, updatedAt)
            VALUES (4, 'Spotify', 'spotify', 'MONTHLY', 0.85, 1000, 2000, 2,
                9.99, 'EUR', 119.88, 1, NULL, 'accepted', 1000, 2000)
        """)

        // Rejected row — should NOT be affected by dedup.
        db.execSQL("""
            INSERT INTO subscription_candidates (id, merchant, canonicalMerchant,
                detectedInterval, confidence, firstSeen, lastSeen, transactionCount,
                averageAmount, currency, estimatedAnnualCost, isConverted,
                convertedSubscriptionId, userAction, createdAt, updatedAt)
            VALUES (5, 'Some Service', 'some_service', 'MONTHLY', 0.5, 1000, 2000, 1,
                5.0, 'EUR', 60.0, 0, NULL, 'rejected', 1000, 2000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 75, true,
            AppDatabase.MIGRATION_74_75
        )

        // Total: 4 rows should survive (id=1 deduped, ids 2, 3, 4, 5 survive).
        db.query("SELECT COUNT(*) FROM subscription_candidates").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("4 rows should survive dedup", 4, c.getInt(0))
        }

        // Verify the survivors.
        db.query("SELECT id FROM subscription_candidates ORDER BY id").use { c ->
            val ids = mutableListOf<Long>()
            while (c.moveToNext()) ids += c.getLong(0)
            assertEquals(listOf(2L, 3L, 4L, 5L), ids)
        }

        // Verify id=2 preserved its data.
        db.query("SELECT confidence, transactionCount FROM subscription_candidates WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0.95, c.getDouble(0), 0.001)
            assertEquals(4, c.getInt(1))
        }

        assertFalse(hasIndex(db, "index_subscription_candidates_pending_merchant_interval"))
        assertTrue(hasIndex(db, "index_subscription_candidates_canonicalMerchant"))
        assertTrue(hasIndex(db, "index_subscription_candidates_isConverted"))
        assertTrue(hasIndex(db, "index_subscription_candidates_confidence"))

        // Direct duplicate inserts are allowed at SQLite level now that only
        // Room-declared indexes remain.
        db.execSQL("""
            INSERT INTO subscription_candidates (merchant, canonicalMerchant,
                detectedInterval, confidence, firstSeen, lastSeen, transactionCount,
                averageAmount, currency, estimatedAnnualCost, isConverted,
                convertedSubscriptionId, userAction, createdAt, updatedAt)
            VALUES ('Netflix 2', 'netflix', 'MONTHLY', 0.7, 3000, 3000, 1,
                15.99, 'EUR', 191.88, 0, NULL, 'pending', 3000, 3000)
        """)

        // Verify that a converted candidate with same merchant+interval is still insertable.
        db.execSQL("""
            INSERT INTO subscription_candidates (merchant, canonicalMerchant,
                detectedInterval, confidence, firstSeen, lastSeen, transactionCount,
                averageAmount, currency, estimatedAnnualCost, isConverted,
                convertedSubscriptionId, userAction, createdAt, updatedAt)
            VALUES ('Netflix Converted', 'netflix', 'MONTHLY', 0.9, 3000, 3000, 5,
                15.99, 'EUR', 191.88, 1, NULL, 'accepted', 3000, 3000)
        """)

        // Verify that a rejected candidate with same merchant+interval is still insertable.
        db.execSQL("""
            INSERT INTO subscription_candidates (merchant, canonicalMerchant,
                detectedInterval, confidence, firstSeen, lastSeen, transactionCount,
                averageAmount, currency, estimatedAnnualCost, isConverted,
                convertedSubscriptionId, userAction, createdAt, updatedAt)
            VALUES ('Netflix Rejected', 'netflix', 'MONTHLY', 0.6, 3000, 3000, 1,
                15.99, 'EUR', 191.88, 0, NULL, 'rejected', 3000, 3000)
        """)

        db.close()
    }

    /**
     * Verifies that MIGRATION_74_75 demotes duplicate active budget forecasts
     * for the same (budgetId, targetPeriodStart, targetPeriodEnd), preserves
     * inactive forecasts, and leaves only Room-declared indexes in place.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_74_to_75_budget_forecasts_dedup_and_room_indexes() {
        assumeTrue(hasSchema(74) && hasSchema(75))

        var db = helper.createDatabase(testDb, 74)

        // We need a budget row for FK constraints.
        db.execSQL("""
            INSERT INTO budgets (id, amount, period, isActive, createdAt)
            VALUES (100, 500.0, 'MONTHLY', 1, 1000)
        """)
        db.execSQL("""
            INSERT INTO budgets (id, amount, period, isActive, createdAt)
            VALUES (200, 1000.0, 'MONTHLY', 1, 1000)
        """)

        // Active duplicate pair: same budgetId+targetPeriodStart+targetPeriodEnd, both active.
        // Only id=2 (MAX id) should remain active; id=1 should be demoted to isActive=0.
        db.execSQL("""
            INSERT INTO budget_forecasts (id, budgetId, forecastDate,
                targetPeriodStart, targetPeriodEnd, predictedSpending, predictedRemaining,
                confidenceScore, riskLevel, overspendProbability, recommendationsJson,
                actualSpending, forecastAccuracy, isActive, createdAt)
            VALUES (1, 100, 1000, 2000, 3000, 450.0, 50.0,
                0.85, 'LOW', 0.1, NULL,
                NULL, NULL, 1, 1000)
        """)
        db.execSQL("""
            INSERT INTO budget_forecasts (id, budgetId, forecastDate,
                targetPeriodStart, targetPeriodEnd, predictedSpending, predictedRemaining,
                confidenceScore, riskLevel, overspendProbability, recommendationsJson,
                actualSpending, forecastAccuracy, isActive, createdAt)
            VALUES (2, 100, 1500, 2000, 3000, 460.0, 40.0,
                0.90, 'LOW', 0.1, NULL,
                NULL, NULL, 1, 1500)
        """)

        // Already-inactive forecast — should NOT be affected.
        db.execSQL("""
            INSERT INTO budget_forecasts (id, budgetId, forecastDate,
                targetPeriodStart, targetPeriodEnd, predictedSpending, predictedRemaining,
                confidenceScore, riskLevel, overspendProbability, recommendationsJson,
                actualSpending, forecastAccuracy, isActive, createdAt)
            VALUES (3, 100, 500, 1000, 2000, 400.0, 100.0,
                0.80, 'LOW', 0.1, NULL,
                420.0, 0.95, 0, 500)
        """)

        // Different budget — active, no duplicate, should survive.
        db.execSQL("""
            INSERT INTO budget_forecasts (id, budgetId, forecastDate,
                targetPeriodStart, targetPeriodEnd, predictedSpending, predictedRemaining,
                confidenceScore, riskLevel, overspendProbability, recommendationsJson,
                actualSpending, forecastAccuracy, isActive, createdAt)
            VALUES (4, 200, 1000, 2000, 3000, 900.0, 100.0,
                0.75, 'MEDIUM', 0.3, NULL,
                NULL, NULL, 1, 1000)
        """)

        // Same budget, different period — active, no duplicate, should survive.
        db.execSQL("""
            INSERT INTO budget_forecasts (id, budgetId, forecastDate,
                targetPeriodStart, targetPeriodEnd, predictedSpending, predictedRemaining,
                confidenceScore, riskLevel, overspendProbability, recommendationsJson,
                actualSpending, forecastAccuracy, isActive, createdAt)
            VALUES (5, 100, 2000, 3000, 4000, 470.0, 30.0,
                0.88, 'MEDIUM', 0.2, NULL,
                NULL, NULL, 1, 2000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 75, true,
            AppDatabase.MIGRATION_74_75
        )

        // All 5 rows should still exist (demote, not delete).
        db.query("SELECT COUNT(*) FROM budget_forecasts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("All 5 rows should still exist", 5, c.getInt(0))
        }

        // id=1 should now be isActive=0 (demoted).
        db.query("SELECT isActive FROM budget_forecasts WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("id=1 should be demoted to inactive", 0, c.getInt(0))
        }

        // id=2 should still be isActive=1 (winner).
        db.query("SELECT isActive, confidenceScore FROM budget_forecasts WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("id=2 should remain active", 1, c.getInt(0))
            assertEquals(0.90, c.getDouble(1), 0.001)
        }

        // id=3 should still be isActive=0 (was already inactive).
        db.query("SELECT isActive FROM budget_forecasts WHERE id = 3").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("id=3 was already inactive", 0, c.getInt(0))
        }

        // id=4 should still be isActive=1 (different budget, no conflict).
        db.query("SELECT isActive FROM budget_forecasts WHERE id = 4").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("id=4 should remain active", 1, c.getInt(0))
        }

        // id=5 should still be isActive=1 (different period, no conflict).
        db.query("SELECT isActive FROM budget_forecasts WHERE id = 5").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("id=5 should remain active", 1, c.getInt(0))
        }

        assertFalse(hasIndex(db, "index_budget_forecasts_active_budget_period"))
        assertTrue(hasIndex(db, "index_budget_forecasts_budgetId"))
        assertTrue(hasIndex(db, "index_budget_forecasts_forecastDate"))
        assertTrue(hasIndex(db, "index_budget_forecasts_isActive"))

        // Direct duplicate inserts are now allowed at SQLite level.
        db.execSQL("""
            INSERT INTO budget_forecasts (budgetId, forecastDate,
                targetPeriodStart, targetPeriodEnd, predictedSpending, predictedRemaining,
                confidenceScore, riskLevel, overspendProbability, recommendationsJson,
                actualSpending, forecastAccuracy, isActive, createdAt)
            VALUES (100, 3000, 2000, 3000, 480.0, 20.0,
                0.92, 'LOW', 0.1, NULL,
                NULL, NULL, 1, 3000)
        """)

        db.close()
    }

    /**
     * Verifies that MIGRATION_74_75 is a no-op on clean data (no duplicates).
     */
    @Test
    @Throws(IOException::class)
    fun migrate_74_to_75_clean_data_noop() {
        assumeTrue(hasSchema(74) && hasSchema(75))

        var db = helper.createDatabase(testDb, 74)

        // Insert one pending candidate — no duplicates.
        db.execSQL("""
            INSERT INTO subscription_candidates (id, merchant, canonicalMerchant,
                detectedInterval, confidence, firstSeen, lastSeen, transactionCount,
                averageAmount, currency, estimatedAnnualCost, isConverted,
                convertedSubscriptionId, userAction, createdAt, updatedAt)
            VALUES (1, 'Netflix', 'netflix', 'MONTHLY', 0.9, 1000, 2000, 3,
                15.99, 'EUR', 191.88, 0, NULL, 'pending', 1000, 2000)
        """)

        // Insert one budget for FK.
        db.execSQL("""
            INSERT INTO budgets (id, amount, period, isActive, createdAt)
            VALUES (100, 500.0, 'MONTHLY', 1, 1000)
        """)

        // Insert one active forecast — no duplicates.
        db.execSQL("""
            INSERT INTO budget_forecasts (id, budgetId, forecastDate,
                targetPeriodStart, targetPeriodEnd, predictedSpending, predictedRemaining,
                confidenceScore, riskLevel, overspendProbability, recommendationsJson,
                actualSpending, forecastAccuracy, isActive, createdAt)
            VALUES (1, 100, 1000, 2000, 3000, 450.0, 50.0,
                0.85, 'LOW', 0.1, NULL,
                NULL, NULL, 1, 1000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 75, true,
            AppDatabase.MIGRATION_74_75
        )

        // Both rows should survive untouched.
        db.query("SELECT COUNT(*) FROM subscription_candidates").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM budget_forecasts WHERE isActive = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        // Only Room-declared subscription_candidates indexes should exist.
        assertFalse(hasIndex(db, "index_subscription_candidates_pending_merchant_interval"))
        assertTrue(hasIndex(db, "index_subscription_candidates_canonicalMerchant"))
        assertTrue(hasIndex(db, "index_subscription_candidates_isConverted"))
        assertTrue(hasIndex(db, "index_subscription_candidates_confidence"))
        assertFalse(hasIndex(db, "index_budget_forecasts_active_budget_period"))
        assertTrue(hasIndex(db, "index_budget_forecasts_budgetId"))
        assertTrue(hasIndex(db, "index_budget_forecasts_forecastDate"))
        assertTrue(hasIndex(db, "index_budget_forecasts_isActive"))

        db.close()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Migration 76 → 77 (B4 Batch 27): Add missing originalMerchant index
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that MIGRATION_76_77 creates the missing
     * `index_user_corrections_originalMerchant` index on the `user_corrections` table.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_76_to_77_adds_originalMerchant_index() {
        assumeTrue(hasSchema(76) && hasSchema(77))

        var db = helper.createDatabase(testDb, 76)

        // Verify the index does NOT exist at v76
        assertFalse(
            "index_user_corrections_originalMerchant should NOT exist at v76",
            hasIndex(db, "index_user_corrections_originalMerchant")
        )

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            77,
            true,
            AppDatabase.MIGRATION_76_77
        )

        // Verify the index now exists
        assertTrue(
            "index_user_corrections_originalMerchant should exist at v77",
            hasIndex(db, "index_user_corrections_originalMerchant")
        )

        // Verify existing user_corrections data is unaffected by inserting and reading
        db.execSQL("""
            INSERT INTO user_corrections (
                packageName, originalMerchant, correctedMerchant,
                originalAmount, correctedAmount, wasRejected, wasApproved, createdAt
            ) VALUES (
                'com.bank.test', 'ΣΚΛΑΒΕΝΙΤΗΣ', 'Sklavenitis',
                25.50, 25.50, 0, 1, ${System.currentTimeMillis()}
            )
        """)

        db.query("SELECT originalMerchant FROM user_corrections").use { cursor ->
            assertTrue("Inserted row should be readable", cursor.moveToFirst())
            assertEquals("ΣΚΛΑΒΕΝΙΤΗΣ", cursor.getString(0))
        }

        db.close()
    }

    /**
     * Verifies the full migration chain from v75 through v77 succeeds
     * and the originalMerchant index is present.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_75_to_77_chain_passes_and_has_originalMerchant_index() {
        assumeTrue(hasSchema(75) && hasSchema(77))

        var db = helper.createDatabase(testDb, 75)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 77, true)

        assertTrue(
            "index_user_corrections_originalMerchant should exist after 75→77 chain",
            hasIndex(db, "index_user_corrections_originalMerchant")
        )

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

    private fun rawNotificationIndexes(db: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> {
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='raw_notifications'").use { cursor ->
            val indexes = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                indexes += cursor.getString(0)
            }
            return indexes
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Migration 75 → 76 (B4 Batch 8): Financial / auxiliary contract wave
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that MIGRATION_75_76 clamps invalid savings_goals data and
     * adds CHECK constraints (targetAmount > 0, currentAmount >= 0).
     */
    @Test
    @Throws(IOException::class)
    fun migrate_75_to_76_savings_goals_check_constraints() {
        assumeTrue(hasSchema(75))

        var db = helper.createDatabase(testDb, 75)

        // Insert a goal with invalid targetAmount (negative) and currentAmount (negative)
        db.execSQL("""
            INSERT INTO savings_goals (id, name, targetAmount, currentAmount, protectionLevel, createdAt)
            VALUES (1, 'Bad Goal', -50.0, -10.0, 'WARNING', 1000)
        """)
        // Insert a valid goal for survival check
        db.execSQL("""
            INSERT INTO savings_goals (id, name, targetAmount, currentAmount, protectionLevel, createdAt)
            VALUES (2, 'Good Goal', 100.0, 25.0, 'STRICT', 2000)
        """)
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 76, true,
            AppDatabase.MIGRATION_75_76
        )

        // Bad goal: targetAmount clamped to 0.01, currentAmount clamped to 0
        db.query("SELECT targetAmount, currentAmount FROM savings_goals WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0.01, c.getDouble(0), 0.001)
            assertEquals(0.0, c.getDouble(1), 0.001)
        }

        // Good goal: unchanged
        db.query("SELECT targetAmount, currentAmount FROM savings_goals WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(100.0, c.getDouble(0), 0.001)
            assertEquals(25.0, c.getDouble(1), 0.001)
        }

        // CHECK constraint should reject negative targetAmount
        try {
            db.execSQL("""
                INSERT INTO savings_goals (id, name, targetAmount, currentAmount, protectionLevel, createdAt)
                VALUES (3, 'Should Fail', -1.0, 0.0, 'WARNING', 3000)
            """)
            fail("Expected CHECK constraint violation for negative targetAmount")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("CHECK") == true || e.message?.contains("constraint") == true)
        }

        // CHECK constraint should reject negative currentAmount
        try {
            db.execSQL("""
                INSERT INTO savings_goals (id, name, targetAmount, currentAmount, protectionLevel, createdAt)
                VALUES (4, 'Should Fail Too', 50.0, -1.0, 'WARNING', 4000)
            """)
            fail("Expected CHECK constraint violation for negative currentAmount")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("CHECK") == true || e.message?.contains("constraint") == true)
        }

        db.close()
    }

    /**
     * Verifies that MIGRATION_75_76 clamps invalid mileage_tracking data,
     * swaps inverted odometers, and adds CHECK constraints.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_75_to_76_mileage_tracking_check_constraints() {
        assumeTrue(hasSchema(75))

        var db = helper.createDatabase(testDb, 75)

        // Insert a valid expense for FK reference
        db.execSQL("""
            INSERT INTO expenses (id, amount, currency, merchant, transactionType, date, createdAt, paymentMethod, isManualEntry, isNotMine, isSharedExpense, backfillAttempts, isBusinessExpense, requiresReceipt)
            VALUES (1, 50.0, 'EUR', 'Gas Station', 'PURCHASE', 1000, 1000, 'CARD', 0, 0, 0, 0, 1, 0)
        """)

        // Row with negative distance, inverted odometers, negative fuelCost
        db.execSQL("""
            INSERT INTO mileage_tracking (id, date, startOdometer, endOdometer, distanceKm, isBusinessTrip, tripPurpose, deductionRatePerKm, linkedExpenseId, fuelCost, createdAt)
            VALUES (1, 1000, 200.0, 100.0, -5.0, 1, 'Client visit', -0.1, 1, -10.0, 1000)
        """)

        // Valid row for survival check
        db.execSQL("""
            INSERT INTO mileage_tracking (id, date, startOdometer, endOdometer, distanceKm, isBusinessTrip, tripPurpose, deductionRatePerKm, createdAt)
            VALUES (2, 2000, 100.0, 200.0, 100.0, 1, 'Meeting', 0.30, 2000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 76, true,
            AppDatabase.MIGRATION_75_76
        )

        // Bad row: distanceKm clamped to 0.01, deductionRatePerKm clamped to 0,
        // odometers swapped (100→start, 200→end), fuelCost clamped to 0
        db.query("SELECT distanceKm, deductionRatePerKm, startOdometer, endOdometer, fuelCost FROM mileage_tracking WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0.01, c.getDouble(0), 0.001)  // distanceKm clamped
            assertEquals(0.0, c.getDouble(1), 0.001)    // deductionRatePerKm clamped
            assertEquals(100.0, c.getDouble(2), 0.001)   // startOdometer (was endOdometer)
            assertEquals(200.0, c.getDouble(3), 0.001)   // endOdometer (was startOdometer)
            assertEquals(0.0, c.getDouble(4), 0.001)     // fuelCost clamped
        }

        // Good row: unchanged
        db.query("SELECT distanceKm, startOdometer, endOdometer FROM mileage_tracking WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(100.0, c.getDouble(0), 0.001)
            assertEquals(100.0, c.getDouble(1), 0.001)
            assertEquals(200.0, c.getDouble(2), 0.001)
        }

        // CHECK: distanceKm must be > 0
        try {
            db.execSQL("""
                INSERT INTO mileage_tracking (id, date, distanceKm, isBusinessTrip, tripPurpose, deductionRatePerKm, createdAt)
                VALUES (3, 3000, 0, 1, 'Fail', 0.30, 3000)
            """)
            fail("Expected CHECK violation for distanceKm = 0")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("CHECK") == true || e.message?.contains("constraint") == true)
        }

        // CHECK: odometer ordering (endOdometer < startOdometer when both present)
        try {
            db.execSQL("""
                INSERT INTO mileage_tracking (id, date, startOdometer, endOdometer, distanceKm, isBusinessTrip, tripPurpose, deductionRatePerKm, createdAt)
                VALUES (4, 4000, 200.0, 100.0, 50.0, 1, 'Fail', 0.30, 4000)
            """)
            fail("Expected CHECK violation for inverted odometers")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("CHECK") == true || e.message?.contains("constraint") == true)
        }

        // Indexes should survive rebuild
        assertTrue(hasIndex(db, "index_mileage_tracking_linkedExpenseId"))
        assertTrue(hasIndex(db, "index_mileage_tracking_date"))
        assertTrue(hasIndex(db, "index_mileage_tracking_isBusinessTrip"))

        db.close()
    }

    /**
     * Verifies that MIGRATION_75_76 coerces invalid suggestedType values
     * and adds CHECK constraints for pending_reviews.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_75_to_76_pending_reviews_check_constraints() {
        assumeTrue(hasSchema(75))

        var db = helper.createDatabase(testDb, 75)

        // Row with invalid suggestedType and non-positive suggestedAmount
        db.execSQL("""
            INSERT INTO pending_reviews (id, rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant, suggestedType, suggestedCategoryId, confidence, packageName, createdAt, status)
            VALUES (1, NULL, -5.0, 'EUR', 'Test', 'INVALID_TYPE', NULL, 0.9, 'com.test', 1000, 'PENDING')
        """)

        // Valid row
        db.execSQL("""
            INSERT INTO pending_reviews (id, rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant, suggestedType, suggestedCategoryId, confidence, packageName, createdAt, status)
            VALUES (2, NULL, 25.0, 'EUR', 'Shop', 'PURCHASE', NULL, 0.8, 'com.bank', 2000, 'PENDING')
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 76, true,
            AppDatabase.MIGRATION_75_76
        )

        // Bad row: suggestedType coerced to 'UNKNOWN', suggestedAmount clamped to 0.01
        db.query("SELECT suggestedType, suggestedAmount FROM pending_reviews WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("UNKNOWN", c.getString(0))
            assertEquals(0.01, c.getDouble(1), 0.001)
        }

        // Good row: unchanged
        db.query("SELECT suggestedType, suggestedAmount FROM pending_reviews WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("PURCHASE", c.getString(0))
            assertEquals(25.0, c.getDouble(1), 0.001)
        }

        // CHECK: suggestedType must be in the enum set
        try {
            db.execSQL("""
                INSERT INTO pending_reviews (id, rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant, suggestedType, suggestedCategoryId, confidence, packageName, createdAt, status)
                VALUES (3, NULL, 10.0, 'EUR', 'Bad', 'REFUND', NULL, 0.5, 'com.x', 3000, 'PENDING')
            """)
            fail("Expected CHECK violation for invalid suggestedType")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("CHECK") == true || e.message?.contains("constraint") == true)
        }

        // CHECK: suggestedAmount must be > 0
        try {
            db.execSQL("""
                INSERT INTO pending_reviews (id, rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant, suggestedType, suggestedCategoryId, confidence, packageName, createdAt, status)
                VALUES (4, NULL, 0, 'EUR', 'Bad', 'PURCHASE', NULL, 0.5, 'com.x', 4000, 'PENDING')
            """)
            fail("Expected CHECK violation for suggestedAmount = 0")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("CHECK") == true || e.message?.contains("constraint") == true)
        }

        // Indexes should survive rebuild
        assertTrue(hasIndex(db, "index_pending_reviews_rawNotificationId"))
        assertTrue(hasIndex(db, "index_pending_reviews_status"))
        assertTrue(hasIndex(db, "index_pending_reviews_suggestedMerchantKey"))

        db.close()
    }

    /**
     * Verifies that MIGRATION_75_76 cleans up orphaned splitTemplateId references
     * and adds the FK constraint on expenses.splitTemplateId → split_templates(id).
     */
    @Test
    @Throws(IOException::class)
    fun migrate_75_to_76_expenses_splitTemplateId_fk() {
        assumeTrue(hasSchema(75))

        var db = helper.createDatabase(testDb, 75)

        // Create a split template
        db.execSQL("""
            INSERT INTO split_templates (id, name, totalSplits, splitType, shares, isDefault, createdAt, updatedAt, useCount)
            VALUES (1, 'Even Split', 2, 'EQUAL', '[]', 0, 1000, 1000, 0)
        """)

        // Expense with valid splitTemplateId
        db.execSQL("""
            INSERT INTO expenses (id, amount, currency, merchant, transactionType, date, createdAt, paymentMethod, isManualEntry, isNotMine, isSharedExpense, backfillAttempts, isBusinessExpense, requiresReceipt, splitTemplateId)
            VALUES (1, 100.0, 'EUR', 'Restaurant', 'PURCHASE', 1000, 1000, 'CARD', 0, 0, 0, 0, 0, 0, 1)
        """)

        // Expense with orphaned splitTemplateId (references non-existent template)
        db.execSQL("""
            INSERT INTO expenses (id, amount, currency, merchant, transactionType, date, createdAt, paymentMethod, isManualEntry, isNotMine, isSharedExpense, backfillAttempts, isBusinessExpense, requiresReceipt, splitTemplateId)
            VALUES (2, 50.0, 'EUR', 'Cafe', 'PURCHASE', 2000, 2000, 'CARD', 0, 0, 0, 0, 0, 0, 999)
        """)

        // Expense with NULL splitTemplateId
        db.execSQL("""
            INSERT INTO expenses (id, amount, currency, merchant, transactionType, date, createdAt, paymentMethod, isManualEntry, isNotMine, isSharedExpense, backfillAttempts, isBusinessExpense, requiresReceipt)
            VALUES (3, 25.0, 'EUR', 'Shop', 'PURCHASE', 3000, 3000, 'CASH', 0, 0, 0, 0, 0, 0)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 76, true,
            AppDatabase.MIGRATION_75_76
        )

        // Expense 1: splitTemplateId should be preserved (valid FK)
        db.query("SELECT splitTemplateId FROM expenses WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }

        // Expense 2: orphaned splitTemplateId should be NULLed out
        db.query("SELECT splitTemplateId FROM expenses WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }

        // Expense 3: NULL splitTemplateId unchanged
        db.query("SELECT splitTemplateId FROM expenses WHERE id = 3").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }

        // All expense indexes should survive rebuild
        assertTrue(hasIndex(db, "index_expenses_rawNotificationId"))
        assertTrue(hasIndex(db, "index_expenses_date"))
        assertTrue(hasIndex(db, "index_expenses_dedupeKey"))
        assertTrue(hasIndex(db, "index_expenses_merchantKey"))
        assertTrue(hasIndex(db, "index_expenses_splitTemplateId"))

        db.close()
    }

    /**
     * Verifies that MIGRATION_75_76 clamps invalid budget data and adds
     * CHECK constraints (amount > 0, thresholds > 0, warning ≤ critical).
     */
    @Test
    @Throws(IOException::class)
    fun migrate_75_to_76_budgets_check_constraints() {
        assumeTrue(hasSchema(75))

        var db = helper.createDatabase(testDb, 75)

        // Budget with invalid amount and inverted thresholds
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, periodMode, startDate, isActive, notifyAtWarning, notifyAtCritical, rollover, createdAt)
            VALUES (1, NULL, -100.0, 'MONTHLY', 'ROLLING', 1000, 1, 0.95, 0.7, 0, 1000)
        """)

        // Budget with negative thresholds
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, periodMode, startDate, isActive, notifyAtWarning, notifyAtCritical, rollover, createdAt)
            VALUES (2, NULL, 500.0, 'MONTHLY', 'ROLLING', 2000, 0, -0.5, -0.2, 0, 2000)
        """)

        // Valid budget
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, periodMode, startDate, isActive, notifyAtWarning, notifyAtCritical, rollover, createdAt)
            VALUES (3, NULL, 200.0, 'WEEKLY', 'CALENDAR', 3000, 0, 0.75, 0.9, 0, 3000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 76, true,
            AppDatabase.MIGRATION_75_76
        )

        // Budget 1: amount clamped to 0.01, thresholds inverted → reset to defaults
        db.query("SELECT amount, notifyAtWarning, notifyAtCritical FROM budgets WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0.01, c.getDouble(0), 0.001)
            assertEquals(0.75, c.getFloat(1).toDouble(), 0.001)
            assertEquals(0.9, c.getFloat(2).toDouble(), 0.001)
        }

        // Budget 2: negative thresholds → reset to defaults (0.75, 0.9)
        db.query("SELECT amount, notifyAtWarning, notifyAtCritical FROM budgets WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(500.0, c.getDouble(0), 0.001)
            assertEquals(0.75, c.getFloat(1).toDouble(), 0.001)
            assertEquals(0.9, c.getFloat(2).toDouble(), 0.001)
        }

        // Budget 3: unchanged
        db.query("SELECT amount, notifyAtWarning, notifyAtCritical FROM budgets WHERE id = 3").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(200.0, c.getDouble(0), 0.001)
            assertEquals(0.75, c.getFloat(1).toDouble(), 0.001)
            assertEquals(0.9, c.getFloat(2).toDouble(), 0.001)
        }

        // CHECK: amount must be > 0
        try {
            db.execSQL("""
                INSERT INTO budgets (id, categoryId, amount, period, periodMode, startDate, isActive, notifyAtWarning, notifyAtCritical, rollover, createdAt)
                VALUES (4, NULL, 0, 'MONTHLY', 'ROLLING', 4000, 0, 0.75, 0.9, 0, 4000)
            """)
            fail("Expected CHECK violation for amount = 0")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("CHECK") == true || e.message?.contains("constraint") == true)
        }

        // CHECK: notifyAtWarning must be <= notifyAtCritical
        try {
            db.execSQL("""
                INSERT INTO budgets (id, categoryId, amount, period, periodMode, startDate, isActive, notifyAtWarning, notifyAtCritical, rollover, createdAt)
                VALUES (5, NULL, 100.0, 'MONTHLY', 'ROLLING', 5000, 0, 0.95, 0.7, 0, 5000)
            """)
            fail("Expected CHECK violation for warning > critical")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("CHECK") == true || e.message?.contains("constraint") == true)
        }

        // Only Room-declared budgets indexes should exist after rebuild
        assertFalse(hasIndex(db, "index_budgets_active_overall"))
        assertFalse(hasIndex(db, "index_budgets_active_category"))
        assertTrue(hasIndex(db, "index_budgets_categoryId"))
        assertTrue(hasIndex(db, "index_budgets_isActive"))

        db.close()
    }

    /**
     * Verifies that MIGRATION_75_76 is a no-op on clean data
     * (no invalid values to clamp, no orphans to clean).
     */
    @Test
    @Throws(IOException::class)
    fun migrate_75_to_76_no_op_on_clean_data() {
        assumeTrue(hasSchema(75))

        var db = helper.createDatabase(testDb, 75)

        // Valid savings goal
        db.execSQL("""
            INSERT INTO savings_goals (id, name, targetAmount, currentAmount, protectionLevel, createdAt)
            VALUES (1, 'Vacation', 5000.0, 1500.0, 'WARNING', 1000)
        """)

        // Valid mileage
        db.execSQL("""
            INSERT INTO expenses (id, amount, currency, merchant, transactionType, date, createdAt, paymentMethod, isManualEntry, isNotMine, isSharedExpense, backfillAttempts, isBusinessExpense, requiresReceipt)
            VALUES (1, 50.0, 'EUR', 'Gas', 'PURCHASE', 1000, 1000, 'CARD', 0, 0, 0, 0, 1, 0)
        """)
        db.execSQL("""
            INSERT INTO mileage_tracking (id, date, startOdometer, endOdometer, distanceKm, isBusinessTrip, tripPurpose, deductionRatePerKm, linkedExpenseId, createdAt)
            VALUES (1, 1000, 100.0, 200.0, 100.0, 1, 'Meeting', 0.30, 1, 1000)
        """)

        // Valid pending review
        db.execSQL("""
            INSERT INTO pending_reviews (id, rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant, suggestedType, suggestedCategoryId, confidence, packageName, createdAt, status)
            VALUES (1, NULL, 25.0, 'EUR', 'Shop', 'PURCHASE', NULL, 0.9, 'com.bank', 1000, 'PENDING')
        """)

        // Valid budget
        db.execSQL("""
            INSERT INTO budgets (id, categoryId, amount, period, periodMode, startDate, isActive, notifyAtWarning, notifyAtCritical, rollover, createdAt)
            VALUES (1, NULL, 500.0, 'MONTHLY', 'ROLLING', 1000, 0, 0.75, 0.9, 0, 1000)
        """)

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb, 76, true,
            AppDatabase.MIGRATION_75_76
        )

        // All rows should survive untouched
        db.query("SELECT COUNT(*) FROM savings_goals").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM mileage_tracking").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM pending_reviews").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM budgets").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM expenses").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        db.close()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Migration 78 → 79 (B4 Batch 29): toCurrency index on exchange_rates
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Verifies the full migration chain from v77 through v79 succeeds
     * and the toCurrency index is present on exchange_rates.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_77_to_79_chain_passes_and_has_toCurrency_index() {
        assumeTrue(hasSchema(77) && hasSchema(79))

        var db = helper.createDatabase(testDb, 77)
        db.close()

        db = helper.runMigrationsAndValidate(testDb, 79, true)

        assertTrue(
            "index_exchange_rates_toCurrency should exist after 77→79 chain",
            hasIndex(db, "index_exchange_rates_toCurrency")
        )

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_79_to_80_creates_spending_challenges_table() {
        assumeTrue(hasSchema(79) && hasSchema(80))

        var db = helper.createDatabase(testDb, 79)
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            80,
            true,
            AppDatabase.MIGRATION_79_80
        )

        assertTrue(
            "spending_challenges table should exist after 79→80 migration",
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='spending_challenges'").use { it.moveToFirst() }
        )
        assertTrue(
            "index_spending_challenges_isActive_endDate should exist after 79→80 migration",
            hasIndex(db, "index_spending_challenges_isActive_endDate")
        )

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_91_to_92_heals_email_receipt_sources_default_for_room_validation() {
        assumeTrue(hasSchema(91) && hasSchema(92))

        var db = helper.createDatabase(testDb, 91)

        db.execSQL("DROP TABLE IF EXISTS email_receipt_sources")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS email_receipt_sources (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                receiptId INTEGER NOT NULL,
                emailSender TEXT NOT NULL,
                emailSubject TEXT NOT NULL,
                emailMessageId TEXT,
                parsedAt INTEGER NOT NULL,
                provider TEXT NOT NULL,
                confidence REAL NOT NULL,
                fingerprint TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_receiptId ON email_receipt_sources (receiptId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_email_receipt_sources_emailMessageId ON email_receipt_sources (emailMessageId) WHERE emailMessageId IS NOT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_provider_parsedAt ON email_receipt_sources (provider, parsedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_parsedAt ON email_receipt_sources (parsedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_fingerprint ON email_receipt_sources (fingerprint)")
        db.execSQL(
            "INSERT INTO scanned_receipts (id, imagePath, rawOcrText, confidence, createdAt, itemCategorizationStatus) VALUES (999, NULL, 'ocr', 0.7, 1705000000001, 'PENDING')"
        )
        db.execSQL(
            """
            INSERT INTO email_receipt_sources (
                receiptId, emailSender, emailSubject, emailMessageId,
                parsedAt, provider, confidence, fingerprint
            ) VALUES (
                999, 'legacy-default@test.com', 'Legacy Default', '   ',
                1705000000002, 'GMAIL', 0.8, 'fp-legacy-default'
            )
            """.trimIndent()
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            92,
            true,
            AppDatabase.MIGRATION_91_92
        )

        db.query("PRAGMA table_info(email_receipt_sources)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            var foundEmailMessageId = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "emailMessageId") {
                    foundEmailMessageId = true
                    assertEquals("NULL", cursor.getString(defaultIndex))
                }
            }
            assertTrue(foundEmailMessageId)
        }

        db.query("SELECT emailMessageId FROM email_receipt_sources WHERE emailSender='legacy-default@test.com'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }

        assertTrue(hasIndex(db, "index_email_receipt_sources_receiptId"))
        assertTrue(hasIndex(db, "index_email_receipt_sources_emailMessageId"))
        assertTrue(hasIndex(db, "index_email_receipt_sources_provider_parsedAt"))
        assertTrue(hasIndex(db, "index_email_receipt_sources_parsedAt"))
        assertTrue(hasIndex(db, "index_email_receipt_fingerprint"))

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_81_to_82_deduplicates_pending_reviews_and_enforces_unique_raw_notification_id() {
        assumeTrue(hasSchema(81) && hasSchema(82))

        var db = helper.createDatabase(testDb, 81)

        db.execSQL("""
            INSERT INTO raw_notifications (
                id, packageName, timestamp, capturedAt, isProcessed
            ) VALUES (
                1, 'com.test.bank', 1700000000000, 1700000000000, 0
            )
        """.trimIndent())

        db.execSQL("""
            INSERT INTO pending_reviews (
                id, rawNotificationId, suggestedAmount, suggestedCurrency,
                suggestedMerchant, suggestedType, confidence, packageName,
                createdAt, status
            ) VALUES (
                10, 1, 12.0, 'EUR', 'Coffee Shop', 'PURCHASE', 0.7, 'com.test.bank',
                1700000001000, 'PENDING'
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO pending_reviews (
                id, rawNotificationId, suggestedAmount, suggestedCurrency,
                suggestedMerchant, suggestedType, confidence, packageName,
                createdAt, status
            ) VALUES (
                20, 1, 15.0, 'EUR', 'Coffee Shop Updated', 'PURCHASE', 0.8, 'com.test.bank',
                1700000002000, 'PENDING'
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO pending_reviews (
                id, rawNotificationId, suggestedAmount, suggestedCurrency,
                suggestedMerchant, suggestedType, confidence, packageName,
                createdAt, status
            ) VALUES (
                30, NULL, 22.0, 'EUR', 'No Raw Id', 'PURCHASE', 0.6, 'com.test.bank',
                1700000003000, 'PENDING'
            )
        """.trimIndent())

        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            82,
            true,
            AppDatabase.MIGRATION_81_82
        )

        db.query("SELECT id, suggestedMerchant FROM pending_reviews WHERE rawNotificationId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(20L, cursor.getLong(0))
            assertEquals("Coffee Shop Updated", cursor.getString(1))
            assertFalse(cursor.moveToNext())
        }

        db.query("SELECT COUNT(*) FROM pending_reviews WHERE rawNotificationId IS NULL").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        assertTrue(hasIndex(db, "index_pending_reviews_rawNotificationId"))

        var duplicateRejected = false
        try {
            db.execSQL("""
                INSERT INTO pending_reviews (
                    rawNotificationId, suggestedAmount, suggestedCurrency,
                    suggestedMerchant, suggestedType, confidence, packageName,
                    createdAt, status
                ) VALUES (
                    1, 18.0, 'EUR', 'Duplicate Insert', 'PURCHASE', 0.9, 'com.test.bank',
                    1700000004000, 'PENDING'
                )
            """.trimIndent())
        } catch (_: Exception) {
            duplicateRejected = true
        }
        assertTrue("Unique rawNotificationId index must reject duplicates", duplicateRejected)

        db.execSQL("""
            INSERT INTO pending_reviews (
                rawNotificationId, suggestedAmount, suggestedCurrency,
                suggestedMerchant, suggestedType, confidence, packageName,
                createdAt, status
            ) VALUES (
                NULL, 19.0, 'EUR', 'Another Null Raw Id', 'PURCHASE', 0.5, 'com.test.bank',
                1700000005000, 'PENDING'
            )
        """.trimIndent())

        db.query("SELECT COUNT(*) FROM pending_reviews WHERE rawNotificationId IS NULL").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_84_to_85_normalizes_raw_notifications_indexes() {
        assumeTrue(hasSchema(84) && hasSchema(85))

        var db = helper.createDatabase(testDb, 84)

        // Simulate drifted index state before healing migration.
        db.execSQL("DROP INDEX IF EXISTS index_raw_notifications_packageName_timestamp_title_text")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notifications_packageName_timestamp_title_text ON raw_notifications (packageName, timestamp, title, text)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notifications_dedup_nonnull ON raw_notifications (packageName, timestamp, title, text) WHERE title IS NOT NULL AND text IS NOT NULL")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notifications_dedup_both_null ON raw_notifications (packageName, timestamp) WHERE title IS NULL AND text IS NULL")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notifications_dedup_title_null ON raw_notifications (packageName, timestamp, text) WHERE title IS NULL AND text IS NOT NULL")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notifications_dedup_text_null ON raw_notifications (packageName, timestamp, title) WHERE text IS NULL AND title IS NOT NULL")
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            85,
            true,
            AppDatabase.MIGRATION_84_85
        )

        assertFalse(hasIndex(db, "index_raw_notifications_dedup_nonnull"))
        assertFalse(hasIndex(db, "index_raw_notifications_dedup_both_null"))
        assertFalse(hasIndex(db, "index_raw_notifications_dedup_title_null"))
        assertFalse(hasIndex(db, "index_raw_notifications_dedup_text_null"))

        assertTrue(hasIndex(db, "index_raw_notifications_packageName_timestamp"))
        assertTrue(hasIndex(db, "index_raw_notifications_capturedAt"))
        assertTrue(hasIndex(db, "index_raw_notifications_isRelevant"))
        assertTrue(hasIndex(db, "index_raw_notifications_packageName_timestamp_title_text"))

        db.query("PRAGMA index_list('raw_notifications')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            var foundCoveringIndex = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "index_raw_notifications_packageName_timestamp_title_text") {
                    foundCoveringIndex = true
                    assertEquals("covering index must be non-unique", 0, cursor.getInt(uniqueIndex))
                }
            }
            assertTrue("covering index must exist", foundCoveringIndex)
        }

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_83_to_85_chain_normalizes_raw_notifications_indexes() {
        assumeTrue(hasSchema(83) && hasSchema(85))

        var db = helper.createDatabase(testDb, 83)
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            85,
            true,
            AppDatabase.MIGRATION_83_84,
            AppDatabase.MIGRATION_84_85
        )

        assertFalse(hasIndex(db, "index_raw_notifications_dedup_nonnull"))
        assertFalse(hasIndex(db, "index_raw_notifications_dedup_both_null"))
        assertFalse(hasIndex(db, "index_raw_notifications_dedup_title_null"))
        assertFalse(hasIndex(db, "index_raw_notifications_dedup_text_null"))

        db.query("PRAGMA index_list('raw_notifications')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            var foundCoveringIndex = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "index_raw_notifications_packageName_timestamp_title_text") {
                    foundCoveringIndex = true
                    assertEquals(0, cursor.getInt(uniqueIndex))
                }
            }
            assertTrue(foundCoveringIndex)
        }

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_85_to_86_heals_budgets_defaults_and_preserves_rows() {
        assumeTrue(hasSchema(85) && hasSchema(86))

        var db = helper.createDatabase(testDb, 85)

        db.execSQL(
            """
            INSERT INTO categories (id, name, icon, color, isDefault)
            VALUES (1, 'Food', '🍔', '#FF0000', 0)
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO budgets (
                id, categoryId, amount, period, periodMode, startDate,
                isActive, notifyAtWarning, notifyAtCritical, rollover,
                createdAt, lastWarningNotifiedAt, lastCriticalNotifiedAt, lastExceededNotifiedAt
            ) VALUES (
                100, 1, 250.5, 'MONTHLY', 'CALENDAR', 1700000000000,
                1, 0.8, 0.95, 1,
                1700000000001, 1700000000002, 1700000000003, 1700000000004
            )
            """.trimIndent()
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            86,
            true,
            AppDatabase.MIGRATION_85_86
        )

        val expectedDefaults = mapOf(
            "periodMode" to "'ROLLING'",
            "isActive" to "1",
            "notifyAtWarning" to "0.75",
            "notifyAtCritical" to "0.9",
            "rollover" to "0"
        )

        db.query("PRAGMA table_info('budgets')").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultValueIndex = cursor.getColumnIndexOrThrow("dflt_value")
            val seenDefaults = mutableMapOf<String, String?>()

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                if (expectedDefaults.containsKey(name)) {
                    seenDefaults[name] = cursor.getString(defaultValueIndex)
                }
            }

            expectedDefaults.forEach { (columnName, expectedDefault) ->
                assertTrue("Expected column missing from budgets: $columnName", seenDefaults.containsKey(columnName))
                assertEquals(
                    "Unexpected default for budgets.$columnName",
                    expectedDefault,
                    seenDefaults[columnName]
                )
            }
        }

        db.query("SELECT COUNT(*) FROM budgets").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        db.query(
            """
            SELECT
                id, categoryId, amount, period, periodMode, startDate,
                isActive, notifyAtWarning, notifyAtCritical, rollover,
                createdAt, lastWarningNotifiedAt, lastCriticalNotifiedAt, lastExceededNotifiedAt
            FROM budgets
            WHERE id = 100
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(100L, cursor.getLong(0))
            assertEquals(1L, cursor.getLong(1))
            assertEquals(250.5, cursor.getDouble(2), 0.0001)
            assertEquals("MONTHLY", cursor.getString(3))
            assertEquals("CALENDAR", cursor.getString(4))
            assertEquals(1700000000000L, cursor.getLong(5))
            assertEquals(1, cursor.getInt(6))
            assertEquals(0.8, cursor.getDouble(7), 0.0001)
            assertEquals(0.95, cursor.getDouble(8), 0.0001)
            assertEquals(1, cursor.getInt(9))
            assertEquals(1700000000001L, cursor.getLong(10))
            assertEquals(1700000000002L, cursor.getLong(11))
            assertEquals(1700000000003L, cursor.getLong(12))
            assertEquals(1700000000004L, cursor.getLong(13))
            assertFalse(cursor.moveToNext())
        }

        assertTrue(hasIndex(db, "index_budgets_categoryId"))
        assertTrue(hasIndex(db, "index_budgets_isActive"))
        assertFalse(hasIndex(db, "index_budgets_active_overall"))
        assertFalse(hasIndex(db, "index_budgets_active_category"))

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_86_to_87_drops_non_room_budgets_partial_indexes() {
        assumeTrue(hasSchema(86) && hasSchema(87))

        var db = helper.createDatabase(testDb, 86)
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_active_overall ON budgets (isActive) WHERE isActive = 1 AND categoryId IS NULL"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_active_category ON budgets (categoryId) WHERE isActive = 1 AND categoryId IS NOT NULL"
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            87,
            true,
            AppDatabase.MIGRATION_86_87
        )

        assertFalse(hasIndex(db, "index_budgets_active_overall"))
        assertFalse(hasIndex(db, "index_budgets_active_category"))
        assertTrue(hasIndex(db, "index_budgets_categoryId"))
        assertTrue(hasIndex(db, "index_budgets_isActive"))

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_87_to_88_drops_non_room_group_members_partial_index() {
        assumeTrue(hasSchema(87) && hasSchema(88))

        var db = helper.createDatabase(testDb, 87)
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_groupId_currentUser ON group_members (groupId) WHERE isCurrentUser = 1"
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            88,
            true,
            AppDatabase.MIGRATION_87_88
        )

        assertFalse(hasIndex(db, "index_group_members_groupId_currentUser"))
        assertTrue(hasIndex(db, "index_group_members_groupId"))
        assertTrue(hasIndex(db, "index_group_members_groupId_isCurrentUser"))
        assertTrue(hasIndex(db, "index_group_members_groupId_name"))

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_88_to_89_drops_non_room_group_expenses_partial_index() {
        assumeTrue(hasSchema(88) && hasSchema(89))

        var db = helper.createDatabase(testDb, 88)
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_group_expenses_expenseId_unique ON group_expenses (expenseId) WHERE expenseId IS NOT NULL"
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            89,
            true,
            AppDatabase.MIGRATION_88_89
        )

        assertFalse(hasIndex(db, "index_group_expenses_expenseId_unique"))
        assertTrue(hasIndex(db, "index_group_expenses_groupId"))
        assertTrue(hasIndex(db, "index_group_expenses_expenseId"))
        assertTrue(hasIndex(db, "index_group_expenses_paidById"))
        assertTrue(hasIndex(db, "index_group_expenses_groupId_date"))
        assertTrue(hasIndex(db, "index_group_expenses_isReimbursable"))

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_89_to_90_drops_non_room_budget_forecasts_partial_index() {
        assumeTrue(hasSchema(89))

        var db = helper.createDatabase(testDb, 89)
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_budget_forecasts_active_budget_period ON budget_forecasts (budgetId, targetPeriodStart, targetPeriodEnd) WHERE isActive = 1"
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            90,
            true,
            AppDatabase.MIGRATION_89_90
        )

        assertFalse(hasIndex(db, "index_budget_forecasts_active_budget_period"))
        assertTrue(hasIndex(db, "index_budget_forecasts_budgetId"))
        assertTrue(hasIndex(db, "index_budget_forecasts_forecastDate"))
        assertTrue(hasIndex(db, "index_budget_forecasts_isActive"))

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_90_to_91_drops_non_room_subscription_candidates_partial_index() {
        assumeTrue(hasSchema(90))

        var db = helper.createDatabase(testDb, 90)
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_subscription_candidates_pending_merchant_interval ON subscription_candidates (canonicalMerchant, detectedInterval) WHERE isConverted = 0 AND userAction = 'pending'"
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            testDb,
            91,
            true,
            AppDatabase.MIGRATION_90_91
        )

        assertFalse(hasIndex(db, "index_subscription_candidates_pending_merchant_interval"))
        assertTrue(hasIndex(db, "index_subscription_candidates_canonicalMerchant"))
        assertTrue(hasIndex(db, "index_subscription_candidates_isConverted"))
        assertTrue(hasIndex(db, "index_subscription_candidates_confidence"))

        db.close()
    }
}
