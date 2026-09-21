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
 * RP-17 17-D migration contract tests for [DatabaseMigrations.MIGRATION_148_149]
 * (bank review identity + connection scope hash).
 *
 * These intentionally do NOT depend on exported schema JSON snapshots — they
 * build a minimal v148-shaped `pending_reviews` table by hand, run the real
 * migration SQL against it, and assert concrete schema AND data behavior:
 *
 *  - both new nullable columns exist after migration;
 *  - the unique index on bankReviewIdentity and the scope index exist;
 *  - legacy rows are preserved with NULL identity (never backfilled);
 *  - NULL identity rows never conflict (non-bank reviews unaffected);
 *  - duplicate non-NULL identity inserts are rejected by the unique index
 *    (the atomic insert-if-absent guarantee for concurrent bank syncs).
 */
@RunWith(AndroidJUnit4::class)
class Migration148to149ContractTest {

    @Test
    fun migration_148_to_149_adds_bank_review_identity_columns_and_indices() {
        withV148Db("contract-148-149.db") { db ->
            seedReview(db, id = 1, packageName = "com.android.messaging")
            seedReview(db, id = 2, packageName = "com.android.messaging")

            DatabaseMigrations.MIGRATION_148_149.migrate(db)

            val columns = columnNames(db, "pending_reviews")
            assertTrue("bankReviewIdentity column missing", "bankReviewIdentity" in columns)
            assertTrue("bankConnectionScopeHash column missing", "bankConnectionScopeHash" in columns)

            assertTrue(hasIndex(db, "index_pending_reviews_bankReviewIdentity"))
            assertTrue(hasIndex(db, "index_pending_reviews_bankConnectionScopeHash"))

            // Legacy rows preserved, identity stays NULL (never fabricated).
            db.query("SELECT COUNT(*) FROM pending_reviews").use {
                it.moveToFirst()
                assertEquals(2, it.getInt(0))
            }
            db.query(
                "SELECT COUNT(*) FROM pending_reviews WHERE bankReviewIdentity IS NOT NULL OR bankConnectionScopeHash IS NOT NULL"
            ).use {
                it.moveToFirst()
                assertEquals("legacy rows must keep NULL bank identity", 0, it.getInt(0))
            }
        }
    }

    @Test
    fun migration_148_to_149_unique_identity_index_rejects_duplicates_but_allows_nulls() {
        withV148Db("contract-148-149-unique.db") { db ->
            seedReview(db, id = 1, packageName = "com.android.messaging")
            DatabaseMigrations.MIGRATION_148_149.migrate(db)

            // Multiple NULL identities never conflict (non-bank reviews).
            db.execSQL(
                "INSERT INTO pending_reviews " +
                    "(id, rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant, " +
                    "suggestedType, suggestedCategoryId, confidence, packageName, createdAt, bankReviewIdentity) " +
                    "VALUES (10, NULL, 1.0, 'EUR', 'A', 'PURCHASE', NULL, 0.5, 'p1', 1, NULL)"
            )
            db.execSQL(
                "INSERT INTO pending_reviews " +
                    "(id, rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant, " +
                    "suggestedType, suggestedCategoryId, confidence, packageName, createdAt, bankReviewIdentity) " +
                    "VALUES (11, NULL, 2.0, 'EUR', 'B', 'PURCHASE', NULL, 0.5, 'p1', 2, NULL)"
            )

            // Same non-NULL bank identity twice must violate the unique index.
            val identity = "hmac_nbg|7|tx_1"
            val insertWithIdentity = { rowId: Int ->
                db.execSQL(
                    "INSERT INTO pending_reviews " +
                        "(id, rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant, " +
                        "suggestedType, suggestedCategoryId, confidence, packageName, createdAt, bankReviewIdentity, bankConnectionScopeHash) " +
                        "VALUES ($rowId, NULL, 3.0, 'EUR', 'C', 'PURCHASE', NULL, 0.5, 'bank.sync.nbg', 3, '$identity', 'scope_7')"
                )
            }
            insertWithIdentity(20)
            var threw = false
            try {
                insertWithIdentity(21)
            } catch (expected: android.database.sqlite.SQLiteConstraintException) {
                threw = true
            }
            assertTrue("duplicate bankReviewIdentity must be rejected by the unique index", threw)

            db.query("SELECT COUNT(*) FROM pending_reviews WHERE bankReviewIdentity = '$identity'").use {
                it.moveToFirst()
                assertEquals("exactly one review per bank identity", 1, it.getInt(0))
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Minimal v148-shaped pending_reviews table (post-MIGRATION_144_145 rebuild shape). */
    private fun createV148PendingReviews(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE pending_reviews (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                rawNotificationId INTEGER,
                scannedReceiptId INTEGER,
                suggestedAmount REAL,
                suggestedCurrency TEXT NOT NULL,
                suggestedMerchant TEXT NOT NULL,
                suggestedMerchantKey TEXT,
                suggestedType TEXT NOT NULL,
                suggestedCategoryId INTEGER,
                suggestedDate INTEGER,
                confidence REAL NOT NULL,
                matchType TEXT,
                explanation TEXT,
                packageName TEXT NOT NULL,
                notificationTitle TEXT,
                notificationText TEXT,
                createdAt INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                suggestedDirection TEXT,
                suggestedAccountName TEXT,
                suggestedLatitude REAL,
                suggestedLongitude REAL,
                extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION',
                FOREIGN KEY(rawNotificationId) REFERENCES raw_notifications(id) ON UPDATE NO ACTION ON DELETE SET NULL,
                FOREIGN KEY(scannedReceiptId) REFERENCES scanned_receipts(id) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
    }

    private fun seedReview(db: SupportSQLiteDatabase, id: Int, packageName: String) {
        db.execSQL(
            "INSERT INTO pending_reviews " +
                "(id, rawNotificationId, suggestedAmount, suggestedCurrency, suggestedMerchant, " +
                "suggestedType, suggestedCategoryId, confidence, packageName, createdAt) " +
                "VALUES ($id, NULL, 5.0, 'EUR', 'Shop', 'PURCHASE', NULL, 0.5, '$packageName', 1)"
        )
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA table_info($table)").use { c ->
            val ni = c.getColumnIndex("name")
            while (c.moveToNext()) names.add(c.getString(ni))
        }
        return names
    }

    private fun hasIndex(db: SupportSQLiteDatabase, indexName: String): Boolean {
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='$indexName'"
        ).use { c ->
            return c.moveToFirst()
        }
    }

    private inline fun withV148Db(
        name: String,
        block: (SupportSQLiteDatabase) -> Unit
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)

        val callback = object : SupportSQLiteOpenHelper.Callback(version = 148) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createV148PendingReviews(db)
            }

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
