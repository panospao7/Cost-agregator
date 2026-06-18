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
                        emailMessageId TEXT DEFAULT NULL,
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

    // ── MIGRATION_72_73 contract: merchant identity / location / correction ──

    /**
     * Validates MIGRATION_72_73:
     *  - Duplicate merchant_canonicals by searchKey are deduped (largest id wins).
     *  - searchKey index becomes UNIQUE.
     *  - Duplicate merchant_aliases by normalizedKey are deduped (largest id wins).
     *  - normalizedKey index becomes UNIQUE.
     *  - merchant_locations with NULL areaKey are backfilled to 'global'.
     *  - Collisions after backfill are deduped (higher hitCount wins, tie-break largest id).
     *  - areaKey column becomes NOT NULL.
     */
    @Test
    fun migration_72_to_73_deduplicates_and_enforces_uniqueness() {
        withTestDb("migration-contract-72-73.db", version = 72) { db ->

            // ── minimal v72 schema for tables in scope ────────────────────────
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS categories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    icon TEXT NOT NULL,
                    color TEXT NOT NULL,
                    isDefault INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS merchant_canonicals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    normalizedName TEXT NOT NULL,
                    searchKey TEXT NOT NULL,
                    categoryId INTEGER,
                    totalOccurrences INTEGER NOT NULL DEFAULT 0,
                    totalSpent REAL NOT NULL DEFAULT 0.0,
                    isVerified INTEGER NOT NULL DEFAULT 0,
                    logoUrl TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_canonicals_normalizedName ON merchant_canonicals (normalizedName)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_canonicals_searchKey ON merchant_canonicals (searchKey)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_canonicals_categoryId ON merchant_canonicals (categoryId)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS merchant_aliases (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    rawName TEXT NOT NULL,
                    normalizedKey TEXT NOT NULL,
                    canonicalId INTEGER NOT NULL,
                    occurrenceCount INTEGER NOT NULL DEFAULT 1,
                    isUserDefined INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    lastUsedAt INTEGER NOT NULL,
                    FOREIGN KEY(canonicalId) REFERENCES merchant_canonicals(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_aliases_rawName ON merchant_aliases (rawName)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_aliases_normalizedKey ON merchant_aliases (normalizedKey)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_aliases_canonicalId ON merchant_aliases (canonicalId)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS merchant_locations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    normalizedMerchantName TEXT NOT NULL,
                    areaKey TEXT,
                    displayName TEXT NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    source TEXT NOT NULL,
                    osmId TEXT,
                    displayAddress TEXT,
                    confidence REAL NOT NULL DEFAULT 1.0,
                    lastResolvedAt INTEGER NOT NULL,
                    hitCount INTEGER NOT NULL DEFAULT 1
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_locations_normalizedMerchantName_areaKey ON merchant_locations (normalizedMerchantName, areaKey)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_locations_lastResolvedAt ON merchant_locations (lastResolvedAt)")

            // ── seed: duplicate searchKey canonicals ──────────────────────────
            val now = 1700000000000L
            db.execSQL("INSERT INTO merchant_canonicals (id, normalizedName, searchKey, totalOccurrences, totalSpent, createdAt, updatedAt) VALUES (1, 'McDonalds A', 'mcdonalds', 10, 100.0, $now, $now)")
            db.execSQL("INSERT INTO merchant_canonicals (id, normalizedName, searchKey, totalOccurrences, totalSpent, createdAt, updatedAt) VALUES (2, 'McDonalds B', 'mcdonalds', 20, 200.0, $now, $now)")
            db.execSQL("INSERT INTO merchant_canonicals (id, normalizedName, searchKey, totalOccurrences, totalSpent, createdAt, updatedAt) VALUES (3, 'Starbucks', 'starbucks', 5, 50.0, $now, $now)")

            // ── seed: duplicate normalizedKey aliases ─────────────────────────
            // canonicalId=2 (will survive) and canonicalId=3 (unique key)
            db.execSQL("INSERT INTO merchant_aliases (id, rawName, normalizedKey, canonicalId, occurrenceCount, createdAt, lastUsedAt) VALUES (10, 'MCDONALDS #1', 'mcdonalds', 1, 5, $now, $now)")
            db.execSQL("INSERT INTO merchant_aliases (id, rawName, normalizedKey, canonicalId, occurrenceCount, createdAt, lastUsedAt) VALUES (11, 'MCDONALDS #2', 'mcdonalds', 2, 8, $now, $now)")
            // Note: rawName is unique, so we use different rawNames but same normalizedKey
            // After dedup on normalizedKey, id=11 should survive (largest id)
            db.execSQL("INSERT INTO merchant_aliases (id, rawName, normalizedKey, canonicalId, occurrenceCount, createdAt, lastUsedAt) VALUES (12, 'STARBUCKS', 'starbucks', 3, 3, $now, $now)")

            // ── seed: merchant_locations with NULL areaKey ────────────────────
            // Two rows for same normalizedMerchantName: one NULL, one 'global'
            // After backfill, they collide — higher hitCount wins.
            db.execSQL("INSERT INTO merchant_locations (id, normalizedMerchantName, areaKey, displayName, latitude, longitude, source, lastResolvedAt, hitCount) VALUES (100, 'mcdonalds', NULL, 'McD-null', 37.9, 23.7, 'NOMINATIM', $now, 5)")
            db.execSQL("INSERT INTO merchant_locations (id, normalizedMerchantName, areaKey, displayName, latitude, longitude, source, lastResolvedAt, hitCount) VALUES (101, 'mcdonalds', 'global', 'McD-global', 37.9, 23.7, 'NOMINATIM', $now, 3)")
            // A standalone NULL row with no collision
            db.execSQL("INSERT INTO merchant_locations (id, normalizedMerchantName, areaKey, displayName, latitude, longitude, source, lastResolvedAt, hitCount) VALUES (102, 'starbucks', NULL, 'Starbucks', 37.95, 23.75, 'NOMINATIM', $now, 1)")

            // ── run migration ────────────────────────────────────────────────
            AppDatabase.MIGRATION_72_73.migrate(db)

            // ── assertions ───────────────────────────────────────────────────

            // 1. merchant_canonicals: only id=2 survives for 'mcdonalds', id=3 untouched
            val mcCanonicals = db.query("SELECT id FROM merchant_canonicals WHERE searchKey = 'mcdonalds'")
            assertEquals(1, mcCanonicals.count)
            mcCanonicals.moveToFirst()
            assertEquals(2L, mcCanonicals.getLong(0))
            mcCanonicals.close()

            val sbCanonicals = db.query("SELECT id FROM merchant_canonicals WHERE searchKey = 'starbucks'")
            assertEquals(1, sbCanonicals.count)
            sbCanonicals.close()

            // searchKey index is now unique
            fun isUniqueIndex(indexName: String): Boolean {
                db.query("SELECT sql FROM sqlite_master WHERE type='index' AND name='$indexName'").use {
                    if (!it.moveToFirst()) return false
                    return it.getString(0)?.contains("UNIQUE") == true
                }
            }
            assertTrue("searchKey index must be unique", isUniqueIndex("index_merchant_canonicals_searchKey"))

            // 2. merchant_aliases: only id=11 survives for 'mcdonalds', id=12 untouched
            val mcAliases = db.query("SELECT id FROM merchant_aliases WHERE normalizedKey = 'mcdonalds'")
            assertEquals(1, mcAliases.count)
            mcAliases.moveToFirst()
            assertEquals(11L, mcAliases.getLong(0))
            mcAliases.close()

            assertTrue("normalizedKey index must be unique", isUniqueIndex("index_merchant_aliases_normalizedKey"))

            // 3. merchant_locations: NULL areaKey rows backfilled to 'global'
            val nullAreaRows = db.query("SELECT COUNT(*) FROM merchant_locations WHERE areaKey IS NULL")
            nullAreaRows.moveToFirst()
            assertEquals(0, nullAreaRows.getInt(0))
            nullAreaRows.close()

            // Collision resolved: 'mcdonalds'+'global' — hitCount=5 wins (id=100)
            val mcdLocations = db.query("SELECT id, hitCount FROM merchant_locations WHERE normalizedMerchantName = 'mcdonalds' AND areaKey = 'global'")
            assertEquals(1, mcdLocations.count)
            mcdLocations.moveToFirst()
            assertEquals(100L, mcdLocations.getLong(0))
            assertEquals(5, mcdLocations.getInt(1))
            mcdLocations.close()

            // 'starbucks' row backfilled to 'global', no collision
            val sbLocations = db.query("SELECT id FROM merchant_locations WHERE normalizedMerchantName = 'starbucks' AND areaKey = 'global'")
            assertEquals(1, sbLocations.count)
            sbLocations.close()

            // areaKey is NOT NULL in the rebuilt table
            val colInfo = db.query("PRAGMA table_info(merchant_locations)")
            while (colInfo.moveToNext()) {
                val colName = colInfo.getString(colInfo.getColumnIndex("name"))
                if (colName == "areaKey") {
                    val notNull = colInfo.getInt(colInfo.getColumnIndex("notnull"))
                    assertEquals("areaKey must be NOT NULL", 1, notNull)
                }
            }
            colInfo.close()
        }
    }

    @Test
    fun migration_77_to_78_adds_category_alertedAt_index_on_anomaly_alerts() {
        withTestDb("migration-contract-77-78.db", version = 77) { db ->
            // Create anomaly_alerts table matching the v77 schema (without category+alertedAt index)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS anomaly_alerts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    expenseId INTEGER NOT NULL,
                    merchant TEXT NOT NULL,
                    category TEXT,
                    amount REAL NOT NULL,
                    anomalyReason TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    alertedAt INTEGER NOT NULL,
                    dismissed INTEGER NOT NULL DEFAULT 0,
                    dismissedAt INTEGER,
                    userFeedback TEXT
                )
                """.trimIndent()
            )

            // Seed a row to confirm data survives the index creation
            db.execSQL(
                "INSERT INTO anomaly_alerts (expenseId, merchant, category, amount, anomalyReason, severity, alertedAt) " +
                    "VALUES (1, 'Shop', 'food', 99.99, 'spike', 'HIGH', 1000)"
            )

            AppDatabase.MIGRATION_77_78.migrate(db)

            // Verify the index exists
            val idx = db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='index_anomaly_alerts_category_alertedAt'"
            )
            assertTrue("index_anomaly_alerts_category_alertedAt must exist", idx.moveToFirst())
            idx.close()

            // Verify existing data is intact
            val row = db.query("SELECT category, amount FROM anomaly_alerts WHERE id = 1")
            assertTrue(row.moveToFirst())
            assertEquals("food", row.getString(0))
            assertEquals(99.99, row.getDouble(1), 0.001)
            row.close()
        }
    }

    @Test
    fun migration_78_to_79_adds_toCurrency_index_on_exchange_rates() {
        withTestDb("migration-contract-78-79.db", version = 78) { db ->
            // Create exchange_rates table matching the v78 schema (without toCurrency index)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS exchange_rates (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fromCurrency TEXT NOT NULL,
                    toCurrency TEXT NOT NULL,
                    rate REAL NOT NULL,
                    lastUpdated INTEGER NOT NULL,
                    source TEXT NOT NULL DEFAULT 'manual'
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_exchange_rates_fromCurrency_toCurrency " +
                    "ON exchange_rates (fromCurrency, toCurrency)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_exchange_rates_lastUpdated " +
                    "ON exchange_rates (lastUpdated)"
            )

            // Seed a row to confirm data survives the index creation
            db.execSQL(
                "INSERT INTO exchange_rates (fromCurrency, toCurrency, rate, lastUpdated, source) " +
                    "VALUES ('USD', 'EUR', 0.92, 1700000000000, 'api')"
            )

            AppDatabase.MIGRATION_78_79.migrate(db)

            // Verify the index exists
            val idx = db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='index_exchange_rates_toCurrency'"
            )
            assertTrue("index_exchange_rates_toCurrency must exist", idx.moveToFirst())
            idx.close()

            // Verify existing data is intact
            val row = db.query("SELECT fromCurrency, toCurrency, rate FROM exchange_rates WHERE id = 1")
            assertTrue(row.moveToFirst())
            assertEquals("USD", row.getString(0))
            assertEquals("EUR", row.getString(1))
            assertEquals(0.92, row.getDouble(2), 0.001)
            row.close()
        }
    }

    @Test
    fun migration_79_to_80_creates_spending_challenges_table() {
        withTestDb("migration-contract-79-80.db", version = 79) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS categories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    icon TEXT NOT NULL,
                    color TEXT NOT NULL,
                    isDefault INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )

            AppDatabase.MIGRATION_79_80.migrate(db)

            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='spending_challenges'").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_spending_challenges_isActive_endDate'").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        }
    }

    @Test
    fun migration_91_to_92_heals_email_receipt_sources_default_contract() {
        withTestDb("migration-contract-91-92.db", version = 91) { db ->
            db.execSQL(
                """
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
                """.trimIndent()
            )

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
                "INSERT INTO scanned_receipts (id, imagePath, rawOcrText, confidence, createdAt) VALUES (1, NULL, 'ocr', 0.9, 1705000000001)"
            )
            db.execSQL(
                """
                INSERT INTO email_receipt_sources (
                    id, receiptId, emailSender, emailSubject, emailMessageId, parsedAt, provider, confidence, fingerprint
                ) VALUES (1, 1, 'legacy@test.com', 'Legacy', '   ', 1705000000002, 'GMAIL', 0.8, 'fp-legacy')
                """.trimIndent()
            )

            AppDatabase.MIGRATION_91_92.migrate(db)

            db.query("PRAGMA table_info(email_receipt_sources)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                var sawEmailMessageId = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "emailMessageId") {
                        sawEmailMessageId = true
                        assertEquals("NULL", cursor.getString(defaultIndex))
                    }
                }
                assertTrue(sawEmailMessageId)
            }

            db.query("SELECT emailMessageId FROM email_receipt_sources WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }

            db.query("PRAGMA foreign_key_list(email_receipt_sources)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("scanned_receipts", cursor.getString(cursor.getColumnIndexOrThrow("table")))
                assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            }

            fun indexExists(name: String): Boolean {
                db.query("SELECT 1 FROM sqlite_master WHERE type='index' AND name='$name'").use {
                    return it.moveToFirst()
                }
            }

            assertTrue(indexExists("index_email_receipt_sources_receiptId"))
            assertTrue(indexExists("index_email_receipt_sources_emailMessageId"))
            assertTrue(indexExists("index_email_receipt_sources_provider_parsedAt"))
            assertTrue(indexExists("index_email_receipt_sources_parsedAt"))
            assertTrue(indexExists("index_email_receipt_fingerprint"))
        }
    }

    // ── Pipeline 4 migration tests ──────────────────────────────────────────

    @Test
    fun migration_139_to_140_canonicalizes_occurrence_keys() {
        withTestDb("migration-139-140.db", version = 139) { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS manual_recurring_expenses (id INTEGER PRIMARY KEY AUTOINCREMENT, merchant TEXT NOT NULL, amount REAL NOT NULL, currency TEXT DEFAULT 'EUR', frequency TEXT NOT NULL, nextDate INTEGER NOT NULL, createdAt INTEGER NOT NULL DEFAULT 0, categoryId INTEGER DEFAULT NULL, isSubscription INTEGER DEFAULT 0, subscriptionCategory TEXT DEFAULT NULL, usageTargetPerMonth INTEGER DEFAULT NULL, cancellationUrl TEXT DEFAULT NULL, isActive INTEGER DEFAULT 1)")
            db.execSQL("CREATE TABLE IF NOT EXISTS recurring_occurrences (id INTEGER PRIMARY KEY AUTOINCREMENT, sourceType TEXT NOT NULL, sourceId INTEGER NOT NULL, occurrenceKey TEXT NOT NULL UNIQUE, dueDate INTEGER NOT NULL, status TEXT NOT NULL, linkedExpenseId INTEGER, expectedAmount REAL NOT NULL, expectedCurrency TEXT NOT NULL, paidAt INTEGER, paidAmount REAL, paidCurrency TEXT, frequency TEXT NOT NULL, merchant TEXT, categoryId INTEGER, createdAt INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ro_source ON recurring_occurrences(sourceType, sourceId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS recurring_reminder_deliveries (id INTEGER PRIMARY KEY AUTOINCREMENT, occurrenceId INTEGER NOT NULL, reminderWindow TEXT NOT NULL, scheduledAt INTEGER NOT NULL, status TEXT NOT NULL, lastSentAt INTEGER, dismissedAt INTEGER, snoozedUntil INTEGER, notificationId INTEGER, createdAt INTEGER NOT NULL DEFAULT 0, claimedAt INTEGER, lastAttemptAt INTEGER, attemptCount INTEGER NOT NULL DEFAULT 0, failureReason TEXT, updatedAt INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_rrd_occ_win ON recurring_reminder_deliveries(occurrenceId, reminderWindow)")
            db.execSQL("CREATE TABLE IF NOT EXISTS recurring_lifecycle_events (id INTEGER PRIMARY KEY AUTOINCREMENT, occurrenceId INTEGER, eventType TEXT NOT NULL, occurredAt INTEGER NOT NULL, oldStatus TEXT, newStatus TEXT, metadata TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS planned_expenses (id INTEGER PRIMARY KEY AUTOINCREMENT, description TEXT NOT NULL, amount REAL NOT NULL, currency TEXT DEFAULT 'EUR', currencyAssumption TEXT DEFAULT 'LEGACY_DEFAULT', date INTEGER NOT NULL, categoryId INTEGER, isRecurring INTEGER DEFAULT 0, priority TEXT DEFAULT 'LIKELY', createdAt INTEGER NOT NULL DEFAULT 0, sourceOccurrenceKey TEXT, sourceRecurringRuleId INTEGER, status TEXT DEFAULT 'PLANNED', linkedActualExpenseId INTEGER, merchantKey TEXT, updatedAt INTEGER NOT NULL DEFAULT 0, openSourceOccurrenceKey TEXT UNIQUE)")

            // Legacy old-format key + new-format duplicate (PAID wins over PLANNED)
            db.execSQL("INSERT INTO recurring_occurrences (sourceType, sourceId, occurrenceKey, dueDate, status, linkedExpenseId, expectedAmount, expectedCurrency, frequency, merchant, createdAt, updatedAt) VALUES ('RECURRING_RULE', 1, '1|1700000000|MONTHLY', 1700000000, 'PAID', 100, 5.0, 'EUR', 'MONTHLY', 'Spotify', 1000, 1000)")
            db.execSQL("INSERT INTO recurring_occurrences (sourceType, sourceId, occurrenceKey, dueDate, status, expectedAmount, expectedCurrency, frequency, merchant, createdAt, updatedAt) VALUES ('RECURRING_RULE', 1, 'RECURRING_RULE|1|1700000000|MONTHLY', 1700000000, 'PLANNED', 5.0, 'EUR', 'MONTHLY', 'Spotify', 1000, 1000)")

            AppDatabase.MIGRATION_139_140.migrate(db)

            val cursor = db.query("SELECT occurrenceKey FROM recurring_occurrences")
            val keys = mutableListOf<String>()
            while (cursor.moveToNext()) keys.add(cursor.getString(0))
            cursor.close()
            assertTrue("Must have canonical keys", keys.all { it.startsWith("RECURRING_RULE|") })
            assertTrue("Duplicate rows deduped", keys.size == 1)
        }
    }

    @Test
    fun migration_140_to_141_adds_fk_and_preserves_data() {
        withTestDb("migration-140-141.db", version = 140) { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS recurring_occurrences (id INTEGER PRIMARY KEY AUTOINCREMENT, sourceType TEXT NOT NULL, sourceId INTEGER NOT NULL, occurrenceKey TEXT NOT NULL UNIQUE, dueDate INTEGER NOT NULL, status TEXT NOT NULL, linkedExpenseId INTEGER, expectedAmount REAL NOT NULL, expectedCurrency TEXT NOT NULL, paidAt INTEGER, paidAmount REAL, paidCurrency TEXT, frequency TEXT NOT NULL, merchant TEXT, categoryId INTEGER, createdAt INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO recurring_occurrences (sourceType, sourceId, occurrenceKey, dueDate, status, expectedAmount, expectedCurrency, frequency, merchant, createdAt, updatedAt) VALUES ('RECURRING_RULE', 1, 'RECURRING_RULE|1|1800000000|MONTHLY', 1800000000, 'PLANNED', 10.0, 'EUR', 'MONTHLY', 'Netflix', 1000, 1000)")
            db.execSQL("CREATE TABLE IF NOT EXISTS recurring_reminder_deliveries (id INTEGER PRIMARY KEY AUTOINCREMENT, occurrenceId INTEGER NOT NULL, reminderWindow TEXT NOT NULL, scheduledAt INTEGER NOT NULL, status TEXT NOT NULL, lastSentAt INTEGER, dismissedAt INTEGER, snoozedUntil INTEGER, notificationId INTEGER, createdAt INTEGER NOT NULL DEFAULT 0, claimedAt INTEGER, lastAttemptAt INTEGER, attemptCount INTEGER NOT NULL DEFAULT 0, failureReason TEXT, updatedAt INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_rrd_occ_win ON recurring_reminder_deliveries(occurrenceId, reminderWindow)")
            db.execSQL("INSERT INTO recurring_reminder_deliveries (occurrenceId, reminderWindow, scheduledAt, status, createdAt, updatedAt, notificationId) VALUES (1, 'DUE_DAY', 1800000000, 'SCHEDULED', 1000, 1000, 42)")

            AppDatabase.MIGRATION_140_141.migrate(db)

            val rem = db.query("SELECT occurrenceId, reminderWindow, notificationId FROM recurring_reminder_deliveries")
            assertTrue(rem.moveToFirst())
            assertEquals(1, rem.getLong(0))
            assertEquals("DUE_DAY", rem.getString(1))
            assertEquals(42, rem.getInt(2))
            rem.close()

            var fkRejected = false
            try { db.execSQL("INSERT INTO recurring_reminder_deliveries (occurrenceId, reminderWindow, scheduledAt, status, createdAt, updatedAt) VALUES (999, 'DUE_DAY', 1800000000, 'SCHEDULED', 1000, 1000)") } catch (_: Exception) { fkRejected = true }
            assertTrue("FK rejects orphan insert", fkRejected)

            db.execSQL("DELETE FROM recurring_occurrences WHERE id = 1")
            val cnt = db.query("SELECT COUNT(*) FROM recurring_reminder_deliveries")
            cnt.moveToFirst()
            assertEquals(0, cnt.getInt(0))
            cnt.close()
        }
    }

    // ── Pipeline 6 migration: budget_forecasts recreate (141 -> 142) ────────

    /**
     * Builds the v141 budget_forecasts schema (with the legacy ON DELETE RESTRICT
     * FK to budgets) plus a minimal budgets parent table, then seeds one budget
     * and one forecast row. Shared setup for the 141->142 contract tests.
     */
    private fun seedV141BudgetForecastSchema(db: SupportSQLiteDatabase) {
        // Minimal v141 budgets parent (only columns needed for the FK + seeding).
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS budgets (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                categoryId INTEGER,
                amount REAL NOT NULL,
                period TEXT NOT NULL,
                periodMode TEXT NOT NULL DEFAULT 'ROLLING',
                startDate INTEGER NOT NULL,
                isActive INTEGER NOT NULL DEFAULT 1,
                notifyAtWarning REAL NOT NULL DEFAULT 0.75,
                notifyAtCritical REAL NOT NULL DEFAULT 0.9,
                rollover INTEGER NOT NULL DEFAULT 0,
                rolloverDeficitTracking INTEGER NOT NULL DEFAULT 0,
                currency TEXT NOT NULL DEFAULT 'EUR',
                currencyAssumption TEXT NOT NULL DEFAULT 'LEGACY_DEFAULT',
                createdAt INTEGER NOT NULL,
                lastWarningNotifiedAt INTEGER,
                lastCriticalNotifiedAt INTEGER,
                lastExceededNotifiedAt INTEGER,
                activeOverallKey INTEGER,
                activeCategoryKey INTEGER
            )
            """.trimIndent()
        )

        // v141 budget_forecasts: 16 persisted columns + ON DELETE RESTRICT FK.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS budget_forecasts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                budgetId INTEGER NOT NULL,
                forecastDate INTEGER NOT NULL,
                targetPeriodStart INTEGER NOT NULL,
                targetPeriodEnd INTEGER NOT NULL,
                predictedSpending REAL NOT NULL,
                predictedRemaining REAL NOT NULL,
                confidenceScore REAL NOT NULL,
                riskLevel TEXT NOT NULL,
                overspendProbability REAL NOT NULL,
                recommendationsJson TEXT,
                actualSpending REAL,
                forecastAccuracy REAL,
                currency TEXT NOT NULL DEFAULT 'EUR',
                isActive INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(budgetId) REFERENCES budgets(id) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_budgetId ON budget_forecasts (budgetId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_forecastDate ON budget_forecasts (forecastDate)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_isActive ON budget_forecasts (isActive)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_budget_forecasts_budgetId_targetPeriodStart_forecastDate " +
                "ON budget_forecasts (budgetId, targetPeriodStart, forecastDate)"
        )

        // Seed: one budget (id=1) and one forecast row referencing it.
        db.execSQL(
            "INSERT INTO budgets (id, categoryId, amount, period, startDate, createdAt) " +
                "VALUES (1, NULL, 500.0, 'MONTHLY', 1700000000000, 1700000000000)"
        )
        db.execSQL(
            """
            INSERT INTO budget_forecasts (
                id, budgetId, forecastDate, targetPeriodStart, targetPeriodEnd,
                predictedSpending, predictedRemaining, confidenceScore, riskLevel,
                overspendProbability, recommendationsJson, actualSpending,
                forecastAccuracy, currency, isActive, createdAt
            ) VALUES (
                1, 1, 1700000000000, 1700000000000, 1702592000000,
                420.5, 79.5, 0.85, 'MEDIUM',
                0.3, '["trim dining"]', NULL,
                NULL, 'EUR', 1, 1700000000000
            )
            """.trimIndent()
        )
    }

    @Test
    fun migrate_141_to_142_preserves_forecasts() {
        withTestDb("migration-contract-141-142-preserve.db", version = 141) { db ->
            seedV141BudgetForecastSchema(db)

            AppDatabase.MIGRATION_141_142.migrate(db)

            val row = db.query(
                """
                SELECT id, budgetId, forecastDate, targetPeriodStart, targetPeriodEnd,
                       predictedSpending, predictedRemaining, confidenceScore, riskLevel,
                       overspendProbability, recommendationsJson, actualSpending,
                       forecastAccuracy, currency, isActive, createdAt
                FROM budget_forecasts WHERE id = 1
                """.trimIndent()
            )
            assertTrue("seeded forecast row must survive the migration", row.moveToFirst())
            assertEquals(1L, row.getLong(0))
            assertEquals(1L, row.getLong(1))
            assertEquals(1700000000000L, row.getLong(2))
            assertEquals(1700000000000L, row.getLong(3))
            assertEquals(1702592000000L, row.getLong(4))
            assertEquals(420.5, row.getDouble(5), 0.0001)
            assertEquals(79.5, row.getDouble(6), 0.0001)
            assertEquals(0.85, row.getDouble(7), 0.0001)
            assertEquals("MEDIUM", row.getString(8))
            assertEquals(0.3, row.getDouble(9), 0.0001)
            assertEquals("[\"trim dining\"]", row.getString(10))
            assertTrue("actualSpending must remain NULL", row.isNull(11))
            assertTrue("forecastAccuracy must remain NULL", row.isNull(12))
            assertEquals("EUR", row.getString(13))
            assertEquals(1, row.getInt(14))
            assertEquals(1700000000000L, row.getLong(15))
            row.close()

            // All four indices must exist with the exact names Room expects.
            fun indexExists(name: String): Boolean {
                db.query("SELECT 1 FROM sqlite_master WHERE type='index' AND name='$name'").use {
                    return it.moveToFirst()
                }
            }
            assertTrue(indexExists("index_budget_forecasts_budgetId"))
            assertTrue(indexExists("index_budget_forecasts_forecastDate"))
            assertTrue(indexExists("index_budget_forecasts_isActive"))
            assertTrue(indexExists("index_budget_forecasts_budgetId_targetPeriodStart_forecastDate"))
        }
    }

    @Test
    fun migrate_141_to_142_adds_quality_columns_with_defaults() {
        withTestDb("migration-contract-141-142-defaults.db", version = 141) { db ->
            seedV141BudgetForecastSchema(db)

            AppDatabase.MIGRATION_141_142.migrate(db)

            // New columns present.
            val colCursor = db.query("PRAGMA table_info(budget_forecasts)")
            val columns = mutableSetOf<String>()
            while (colCursor.moveToNext()) {
                columns.add(colCursor.getString(1))
            }
            colCursor.close()
            assertTrue(columns.contains("isPartial"))
            assertTrue(columns.contains("excludedExpenseCount"))
            assertTrue(columns.contains("qualityWarningsJson"))
            assertTrue(columns.contains("rateBasis"))

            // Legacy row gets safe defaults.
            val defaults = db.query(
                "SELECT isPartial, excludedExpenseCount, qualityWarningsJson, rateBasis FROM budget_forecasts WHERE id = 1"
            )
            assertTrue(defaults.moveToFirst())
            assertEquals("isPartial defaults to 0", 0, defaults.getInt(0))
            assertEquals("excludedExpenseCount defaults to 0", 0, defaults.getInt(1))
            assertTrue("qualityWarningsJson defaults to NULL", defaults.isNull(2))
            assertTrue("rateBasis defaults to NULL", defaults.isNull(3))
            defaults.close()

            // New columns are writable.
            db.execSQL(
                "UPDATE budget_forecasts SET isPartial = 1, excludedExpenseCount = 3, " +
                    "qualityWarningsJson = '[\"low_sample\"]', rateBasis = 'DAILY_AVG' WHERE id = 1"
            )
            val written = db.query(
                "SELECT isPartial, excludedExpenseCount, qualityWarningsJson, rateBasis FROM budget_forecasts WHERE id = 1"
            )
            assertTrue(written.moveToFirst())
            assertEquals(1, written.getInt(0))
            assertEquals(3, written.getInt(1))
            assertEquals("[\"low_sample\"]", written.getString(2))
            assertEquals("DAILY_AVG", written.getString(3))
            written.close()
        }
    }

    @Test
    fun migrate_141_to_142_relaxes_budget_fk_to_cascade() {
        withTestDb("migration-contract-141-142-cascade.db", version = 141) { db ->
            seedV141BudgetForecastSchema(db)

            AppDatabase.MIGRATION_141_142.migrate(db)

            // Enforce FK constraints for the cascade behavior check.
            db.execSQL("PRAGMA foreign_keys=ON")

            // Sanity: forecast row exists before the parent delete.
            val before = db.query("SELECT COUNT(*) FROM budget_forecasts WHERE budgetId = 1")
            before.moveToFirst()
            assertEquals(1, before.getInt(0))
            before.close()

            // Deleting the parent budget must cascade-purge its forecasts.
            db.execSQL("DELETE FROM budgets WHERE id = 1")

            val after = db.query("SELECT COUNT(*) FROM budget_forecasts WHERE budgetId = 1")
            after.moveToFirst()
            assertEquals("forecast must be cascade-deleted with its budget", 0, after.getInt(0))
            after.close()

            // No dangling FK references remain.
            val fkViolations = db.query("PRAGMA foreign_key_check")
            assertFalse("no FK violations after cascade delete", fkViolations.moveToFirst())
            fkViolations.close()
        }
    }

    // ── S9 / P9-P1-09 (PR6): warranty_reminder_deliveries (142 -> 143) ───────

    /**
     * Builds the minimal v142 `warranties` parent table (only the columns referenced by
     * the FK + needed for seeding) and inserts one warranty (id=1). Shared setup for the
     * 142->143 warranty-reminder-deliveries contract tests.
     */
    private fun seedV142WarrantyParent(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS warranties (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                receiptId INTEGER,
                expenseId INTEGER,
                productName TEXT NOT NULL,
                merchantName TEXT NOT NULL,
                purchaseDate INTEGER NOT NULL,
                warrantyDurationMonths INTEGER NOT NULL,
                warrantyEndDate INTEGER NOT NULL,
                warrantyType TEXT NOT NULL DEFAULT 'MANUFACTURER',
                supportPhone TEXT,
                supportEmail TEXT,
                warrantyDocumentUrl TEXT,
                notes TEXT,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                claimedAt INTEGER,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                autoDetected INTEGER NOT NULL DEFAULT 0,
                extractionConfidence REAL NOT NULL DEFAULT 0.0,
                extractionSource TEXT NOT NULL DEFAULT 'manual',
                needsReview INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO warranties (id, productName, merchantName, purchaseDate, " +
                "warrantyDurationMonths, warrantyEndDate, createdAt, updatedAt) " +
                "VALUES (1, 'Laptop', 'Tech Store', 1700000000000, 24, 1800000000000, " +
                "1700000000000, 1700000000000)"
        )
    }

    @Test
    fun migrate_142_to_143_creates_warranty_reminder_deliveries_with_expected_shape() {
        withTestDb("migration-contract-142-143-shape.db", version = 142) { db ->
            seedV142WarrantyParent(db)

            AppDatabase.MIGRATION_142_143.migrate(db)

            // Expected columns present.
            val colCursor = db.query("PRAGMA table_info(warranty_reminder_deliveries)")
            val columns = mutableSetOf<String>()
            val notNullColumns = mutableSetOf<String>()
            while (colCursor.moveToNext()) {
                val colName = colCursor.getString(colCursor.getColumnIndexOrThrow("name"))
                columns.add(colName)
                if (colCursor.getInt(colCursor.getColumnIndexOrThrow("notnull")) == 1) {
                    notNullColumns.add(colName)
                }
            }
            colCursor.close()

            val expected = setOf(
                "id", "warrantyId", "windowDays", "expiryDate", "status",
                "claimedAt", "lastAttemptAt", "attemptCount", "notificationId",
                "failureReason", "createdAt", "updatedAt"
            )
            assertEquals("column set must match the entity exactly", expected, columns)

            // NOT NULL columns must match what Room generates from the entity.
            assertTrue(notNullColumns.contains("id"))
            assertTrue(notNullColumns.contains("warrantyId"))
            assertTrue(notNullColumns.contains("windowDays"))
            assertTrue(notNullColumns.contains("expiryDate"))
            assertTrue(notNullColumns.contains("status"))
            assertTrue(notNullColumns.contains("attemptCount"))
            assertTrue(notNullColumns.contains("createdAt"))
            assertTrue(notNullColumns.contains("updatedAt"))
            // Nullable columns must NOT be NOT NULL.
            assertFalse(notNullColumns.contains("claimedAt"))
            assertFalse(notNullColumns.contains("lastAttemptAt"))
            assertFalse(notNullColumns.contains("notificationId"))
            assertFalse(notNullColumns.contains("failureReason"))

            // Indices exist with the exact names Room expects.
            fun indexExists(name: String): Boolean {
                db.query("SELECT 1 FROM sqlite_master WHERE type='index' AND name='$name'").use {
                    return it.moveToFirst()
                }
            }
            assertTrue(
                indexExists("index_warranty_reminder_deliveries_warrantyId_windowDays_expiryDate")
            )
            assertTrue(indexExists("index_warranty_reminder_deliveries_warrantyId"))

            // A seed row can be inserted and read back.
            db.execSQL(
                "INSERT INTO warranty_reminder_deliveries " +
                    "(warrantyId, windowDays, expiryDate, status, attemptCount, createdAt, updatedAt) " +
                    "VALUES (1, 7, 1800000000000, 'SCHEDULED', 0, 1700000000000, 1700000000000)"
            )
            db.query("SELECT status FROM warranty_reminder_deliveries WHERE warrantyId = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals("SCHEDULED", it.getString(0))
            }
        }
    }

    @Test
    fun migrate_142_to_143_enforces_unique_key() {
        withTestDb("migration-contract-142-143-unique.db", version = 142) { db ->
            seedV142WarrantyParent(db)

            AppDatabase.MIGRATION_142_143.migrate(db)

            db.execSQL(
                "INSERT INTO warranty_reminder_deliveries " +
                    "(warrantyId, windowDays, expiryDate, status, attemptCount, createdAt, updatedAt) " +
                    "VALUES (1, 7, 1800000000000, 'SCHEDULED', 0, 1700000000000, 1700000000000)"
            )

            // Same (warrantyId, windowDays, expiryDate) must be rejected by the UNIQUE index.
            var uniqueRejected = false
            try {
                db.execSQL(
                    "INSERT INTO warranty_reminder_deliveries " +
                        "(warrantyId, windowDays, expiryDate, status, attemptCount, createdAt, updatedAt) " +
                        "VALUES (1, 7, 1800000000000, 'SENT', 1, 1700000000000, 1700000000000)"
                )
            } catch (_: Exception) {
                uniqueRejected = true
            }
            assertTrue("UNIQUE (warrantyId, windowDays, expiryDate) must reject duplicate", uniqueRejected)

            // A different window for the same warranty is allowed.
            db.execSQL(
                "INSERT INTO warranty_reminder_deliveries " +
                    "(warrantyId, windowDays, expiryDate, status, attemptCount, createdAt, updatedAt) " +
                    "VALUES (1, 30, 1800000000000, 'SCHEDULED', 0, 1700000000000, 1700000000000)"
            )
            db.query("SELECT COUNT(*) FROM warranty_reminder_deliveries").use {
                it.moveToFirst()
                assertEquals(2, it.getInt(0))
            }
        }
    }

    @Test
    fun migrate_142_to_143_warranty_fk_cascade_and_rejection() {
        withTestDb("migration-contract-142-143-fk.db", version = 142) { db ->
            seedV142WarrantyParent(db)

            AppDatabase.MIGRATION_142_143.migrate(db)

            // Enforce FK constraints for the cascade behavior check.
            db.execSQL("PRAGMA foreign_keys=ON")

            db.execSQL(
                "INSERT INTO warranty_reminder_deliveries " +
                    "(warrantyId, windowDays, expiryDate, status, attemptCount, createdAt, updatedAt) " +
                    "VALUES (1, 7, 1800000000000, 'SCHEDULED', 0, 1700000000000, 1700000000000)"
            )

            // Orphan insert (no such warranty) must be rejected by the FK.
            var fkRejected = false
            try {
                db.execSQL(
                    "INSERT INTO warranty_reminder_deliveries " +
                        "(warrantyId, windowDays, expiryDate, status, attemptCount, createdAt, updatedAt) " +
                        "VALUES (999, 7, 1800000000000, 'SCHEDULED', 0, 1700000000000, 1700000000000)"
                )
            } catch (_: Exception) {
                fkRejected = true
            }
            assertTrue("FK rejects orphan warranty reference", fkRejected)

            // Deleting the parent warranty must cascade-delete its deliveries.
            db.execSQL("DELETE FROM warranties WHERE id = 1")
            db.query("SELECT COUNT(*) FROM warranty_reminder_deliveries WHERE warrantyId = 1").use {
                it.moveToFirst()
                assertEquals("delivery must be cascade-deleted with its warranty", 0, it.getInt(0))
            }

            // No dangling FK references remain.
            val fkViolations = db.query("PRAGMA foreign_key_check")
            assertFalse("no FK violations after cascade delete", fkViolations.moveToFirst())
            fkViolations.close()
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
