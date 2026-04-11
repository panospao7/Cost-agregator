package com.yourname.expensetracker.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // ── MIGRATION_69_70 fallback contract ──────────────────────────────────────

    /**
     * Validates that MIGRATION_69_70 completes successfully when
     * BankTokenCipher throws (simulating Keystore unavailability).
     *
     * The test seam [AppDatabase.tokenEncryptionOverrideForTest] is set to a
     * throwing lambda so the fallback path is exercised deterministically,
     * regardless of whether the real Keystore is available in the test environment.
     *
     * Contract assertions:
     *  - The migration transaction commits (no crash/rollback).
     *  - Bank tokens are preserved as-is (not nulled).
     *  - tokenEncryptionVersion stays 0 (encryption was never applied).
     *  - All structural changes (indices, email_receipt_sources rebuild) still apply.
     */
    @Test
    fun migration_69_to_70_completes_with_data_preserved_when_keystore_unavailable() {
        // Force the encryption step to throw so we deterministically cover the fallback.
        AppDatabase.tokenEncryptionOverrideForTest = { _ ->
            throw RuntimeException("Simulated Keystore failure for test")
        }
        try {
            withTestDb("migration-contract-69-70.db", version = 69) { db ->

                // ── minimal v69 schema ──────────────────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS expenses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        amount REAL NOT NULL DEFAULT 0,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        merchant TEXT NOT NULL DEFAULT '',
                        transactionType TEXT NOT NULL DEFAULT 'PURCHASE',
                        date INTEGER NOT NULL DEFAULT 0,
                        rawNotificationId INTEGER,
                        categoryId INTEGER,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        paymentMethod TEXT NOT NULL DEFAULT 'UNKNOWN',
                        isManualEntry INTEGER NOT NULL DEFAULT 0,
                        notes TEXT,
                        dedupeKey TEXT,
                        transferDirection TEXT,
                        transferAccountName TEXT,
                        isNotMine INTEGER NOT NULL DEFAULT 0,
                        ownerName TEXT,
                        isSharedExpense INTEGER NOT NULL DEFAULT 0,
                        sharedWithName TEXT,
                        mySharePercentage INTEGER,
                        myShareAmount REAL,
                        latitude REAL,
                        longitude REAL,
                        locationSource TEXT,
                        placeId TEXT,
                        backfillAttempts INTEGER NOT NULL DEFAULT 0,
                        resolvedAddress TEXT,
                        merchantKey TEXT,
                        isBusinessExpense INTEGER NOT NULL DEFAULT 0,
                        businessPurpose TEXT,
                        businessCategory TEXT,
                        businessProject TEXT,
                        requiresReceipt INTEGER NOT NULL DEFAULT 0,
                        splitTemplateId INTEGER,
                        splitVisualization TEXT
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scanned_receipts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        imagePath TEXT,
                        rawOcrText TEXT NOT NULL DEFAULT '',
                        parsedTotal REAL,
                        parsedMerchant TEXT,
                        parsedDate INTEGER,
                        parsedItems TEXT,
                        parsedTaxAmount REAL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        confidence REAL NOT NULL DEFAULT 0,
                        expenseId INTEGER,
                        matchStatus TEXT NOT NULL DEFAULT 'UNMATCHED',
                        matchConfidence REAL,
                        suggestedExpenseId INTEGER,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        itemCategorizationStatus TEXT NOT NULL DEFAULT 'PENDING'
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bank_connections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bankId TEXT NOT NULL,
                        bankName TEXT NOT NULL,
                        countryCode TEXT NOT NULL,
                        accessToken TEXT,
                        refreshToken TEXT,
                        tokenEncryptionVersion INTEGER NOT NULL DEFAULT 0,
                        tokenExpiry INTEGER,
                        isActive INTEGER NOT NULL DEFAULT 0,
                        isConnected INTEGER NOT NULL DEFAULT 0,
                        lastSync INTEGER,
                        lastSyncStatus TEXT NOT NULL DEFAULT 'NEVER',
                        autoSync INTEGER NOT NULL DEFAULT 1,
                        syncFrequency TEXT NOT NULL DEFAULT 'DAILY',
                        defaultCategoryId INTEGER,
                        lastError TEXT,
                        lastErrorTime INTEGER,
                        consecutiveErrors INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS email_receipt_sources (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        receiptId INTEGER NOT NULL,
                        emailSender TEXT NOT NULL,
                        emailSubject TEXT NOT NULL,
                        emailMessageId TEXT,
                        parsedAt INTEGER NOT NULL,
                        provider TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        fingerprint TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_reviews (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        rawNotificationId INTEGER,
                        scannedReceiptId INTEGER,
                        suggestedAmount REAL NOT NULL DEFAULT 0,
                        suggestedCurrency TEXT NOT NULL DEFAULT 'EUR',
                        suggestedMerchant TEXT NOT NULL DEFAULT '',
                        suggestedType TEXT NOT NULL DEFAULT 'PURCHASE',
                        suggestedCategoryId INTEGER,
                        suggestedDate INTEGER,
                        confidence REAL NOT NULL DEFAULT 0,
                        matchType TEXT,
                        explanation TEXT,
                        packageName TEXT NOT NULL DEFAULT '',
                        notificationTitle TEXT,
                        notificationText TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        suggestedDirection TEXT,
                        suggestedAccountName TEXT,
                        suggestedLatitude REAL,
                        suggestedLongitude REAL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS spending_personality_profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        personalityType TEXT NOT NULL DEFAULT '',
                        confidence REAL NOT NULL DEFAULT 0,
                        featureScoresJson TEXT NOT NULL DEFAULT '{}',
                        explanationJson TEXT NOT NULL DEFAULT '[]',
                        coachingTipsJson TEXT NOT NULL DEFAULT '[]',
                        lastUpdated INTEGER NOT NULL DEFAULT 0,
                        analysisPeriodStart INTEGER NOT NULL DEFAULT 0,
                        analysisPeriodEnd INTEGER NOT NULL DEFAULT 0,
                        transactionCount INTEGER NOT NULL DEFAULT 0,
                        isViewed INTEGER NOT NULL DEFAULT 0,
                        viewedAt INTEGER,
                        isActive INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS manual_recurring_expenses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        merchant TEXT NOT NULL,
                        amount REAL NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        frequency TEXT NOT NULL,
                        nextDate INTEGER NOT NULL,
                        note TEXT,
                        createdAt INTEGER NOT NULL,
                        isSubscription INTEGER NOT NULL DEFAULT 1,
                        subscriptionCategory TEXT,
                        usageTargetPerMonth INTEGER,
                        cancellationUrl TEXT,
                        isActive INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())

                // ── seed data ───────────────────────────────────────────────────
                db.execSQL("""
                    INSERT INTO bank_connections (bankId, bankName, countryCode, accessToken, refreshToken, createdAt)
                    VALUES ('test-bank', 'Test Bank', 'GR', 'plaintext-access', 'plaintext-refresh', 1705000000000)
                """)

                db.execSQL("""
                    INSERT INTO scanned_receipts (id, imagePath, rawOcrText, confidence, createdAt)
                    VALUES (1, '/img/r1.jpg', 'ocr', 0.9, 1705000000001)
                """)

                db.execSQL("""
                    INSERT INTO email_receipt_sources (
                        receiptId, emailSender, emailSubject, emailMessageId, parsedAt, provider, confidence, fingerprint
                    ) VALUES (1, 'sender@test.com', 'Subject', '   ', 1705000000002, 'GMAIL', 0.8, 'fp1')
                """)

                // ── run MIGRATION_69_70 directly ────────────────────────────────
                AppDatabase.MIGRATION_69_70.migrate(db)

                // ── structural assertions ────────────────────────────────────────

                // 1. bank_connections: fallback path — encryption threw, so tokens must
                //    survive as plaintext and tokenEncryptionVersion must stay 0.
                val bankCursor = db.query(
                    "SELECT bankName, accessToken, refreshToken, tokenEncryptionVersion FROM bank_connections WHERE bankId='test-bank'"
                )
                assertTrue("bank_connection row must survive migration", bankCursor.moveToFirst())
                assertEquals("Test Bank", bankCursor.getString(0))

                val accessToken = bankCursor.getString(1)
                val refreshToken = bankCursor.getString(2)
                val encVersion = bankCursor.getInt(3)
                bankCursor.close()

                // Tokens must not be lost.
                assertNotNull("accessToken must not be lost", accessToken)
                assertNotNull("refreshToken must not be lost", refreshToken)

                // Encryption was forced to throw — tokens must remain plaintext.
                assertFalse(
                    "accessToken must not carry enc:v1: prefix when encryption was forced to fail",
                    accessToken.startsWith("enc:v1:")
                )
                assertEquals(
                    "tokenEncryptionVersion must be 0 when encryption was forced to fail",
                    0, encVersion
                )

                // 2. email_receipt_sources: blank messageId normalised to NULL
                val emailCursor = db.query(
                    "SELECT emailMessageId FROM email_receipt_sources WHERE emailSender='sender@test.com'"
                )
                assertTrue(emailCursor.moveToFirst())
                assertTrue("blank emailMessageId must be NULL after migration", emailCursor.isNull(0))
                emailCursor.close()

                // 3. Required indices added
                fun indexExists(name: String): Boolean {
                    db.query("SELECT 1 FROM sqlite_master WHERE type='index' AND name='$name'").use {
                        return it.moveToFirst()
                    }
                }
                assertTrue(indexExists("index_expenses_date"))
                assertTrue(indexExists("index_expenses_merchantKey_date_amount"))
                assertTrue(indexExists("index_manual_recurring_expenses_isActive_nextDate"))
                assertTrue(indexExists("index_spending_personality_profiles_isActive"))
                assertTrue(indexExists("index_bank_connections_bankId"))

                // 4. No FK violations
                val fkViolations = db.query("PRAGMA foreign_key_check")
                assertFalse("no FK violations after migration", fkViolations.moveToFirst())
                fkViolations.close()
            }
        } finally {
            // Always restore the seam so other tests are not affected.
            AppDatabase.tokenEncryptionOverrideForTest = null
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
