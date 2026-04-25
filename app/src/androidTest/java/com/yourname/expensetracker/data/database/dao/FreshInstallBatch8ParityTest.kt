package com.yourname.expensetracker.data.database.dao

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.database.entity.SplitTemplate
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Batch 8 closure — fresh-install parity behavioral test.**
 *
 * Proves that CHECK constraints and the `expenses.splitTemplateId` FK with
 * `ON DELETE SET NULL` semantics created by [AppDatabase.FRESH_INSTALL_CALLBACK]
 * are enforced on brand-new (in-memory) databases built via
 * [AppDatabase.inMemoryBuilder].
 *
 * Uses raw SQL for invalid inserts that Room DAOs would prevent at compile time,
 * and Room DAOs for valid inserts and FK-semantics verification.
 *
 * Constraints under test (all added by [AppDatabase.MIGRATION_75_76] on upgrade
 * and [AppDatabase.FRESH_INSTALL_CALLBACK] on fresh install):
 *
 * | Table              | CHECK constraint(s)                                              |
 * |--------------------|------------------------------------------------------------------|
 * | pending_reviews    | suggestedAmount > 0; suggestedType IN (known enum set)           |
 * | savings_goals      | targetAmount > 0; currentAmount >= 0                             |
 * | mileage_tracking   | distanceKm > 0; endOdometer >= startOdometer (when both present) |
 * | budgets            | amount > 0; notifyAtWarning > 0 AND <= notifyAtCritical          |
 *
 * | Table    | FK semantics                                              |
 * |----------|-----------------------------------------------------------|
 * | expenses | splitTemplateId → split_templates(id) ON DELETE SET NULL   |
 */
@RunWith(AndroidJUnit4::class)
class FreshInstallBatch8ParityTest {

    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ── pending_reviews CHECK constraints ───────────────────────────────────

    @Test
    fun pending_reviews_rejects_zero_suggestedAmount() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO pending_reviews (
                    rawNotificationId, suggestedAmount, suggestedCurrency,
                    suggestedMerchant, suggestedType, suggestedCategoryId,
                    confidence, packageName, notificationTitle, notificationText,
                    createdAt, status
                ) VALUES (
                    NULL, 0.0, 'EUR',
                    'Test', 'PURCHASE', NULL,
                    0.8, 'com.test', 'title', 'text',
                    ${System.currentTimeMillis()}, 'PENDING'
                )
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for suggestedAmount = 0")
        } catch (_: Exception) {
            // expected — CHECK(suggestedAmount > 0) fires
        }
    }

    @Test
    fun pending_reviews_rejects_negative_suggestedAmount() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO pending_reviews (
                    rawNotificationId, suggestedAmount, suggestedCurrency,
                    suggestedMerchant, suggestedType, suggestedCategoryId,
                    confidence, packageName, notificationTitle, notificationText,
                    createdAt, status
                ) VALUES (
                    NULL, -1.0, 'EUR',
                    'Test', 'PURCHASE', NULL,
                    0.8, 'com.test', 'title', 'text',
                    ${System.currentTimeMillis()}, 'PENDING'
                )
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for suggestedAmount = -1")
        } catch (_: Exception) {
            // expected — CHECK(suggestedAmount > 0) fires
        }
    }

    @Test
    fun pending_reviews_rejects_invalid_suggestedType() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO pending_reviews (
                    rawNotificationId, suggestedAmount, suggestedCurrency,
                    suggestedMerchant, suggestedType, suggestedCategoryId,
                    confidence, packageName, notificationTitle, notificationText,
                    createdAt, status
                ) VALUES (
                    NULL, 10.0, 'EUR',
                    'Test', 'REFUND', NULL,
                    0.8, 'com.test', 'title', 'text',
                    ${System.currentTimeMillis()}, 'PENDING'
                )
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for suggestedType = 'REFUND'")
        } catch (_: Exception) {
            // expected — CHECK(suggestedType IN (...)) fires
        }
    }

    @Test
    fun pending_reviews_accepts_valid_insert() {
        val db = database.openHelper.writableDatabase
        // Should not throw — all values satisfy CHECKs
        db.execSQL(
            """
            INSERT INTO pending_reviews (
                rawNotificationId, suggestedAmount, suggestedCurrency,
                suggestedMerchant, suggestedType, suggestedCategoryId,
                confidence, packageName, notificationTitle, notificationText,
                createdAt, status
            ) VALUES (
                NULL, 25.50, 'EUR',
                'Supermarket', 'PURCHASE', NULL,
                0.9, 'com.bank', 'Payment', 'Paid 25.50',
                ${System.currentTimeMillis()}, 'PENDING'
            )
            """.trimIndent()
        )
        // If we reach here, the insert succeeded as expected
    }

    @Test
    fun pending_reviews_rawNotificationId_index_is_unique_on_fresh_install() {
        val db = database.openHelper.writableDatabase

        fun isUniqueIndex(indexName: String): Boolean {
            db.query("SELECT sql FROM sqlite_master WHERE type='index' AND name='$indexName'").use { cursor: Cursor ->
                if (!cursor.moveToFirst()) return false
                return cursor.getString(0)?.contains("UNIQUE") == true
            }
        }

        assertTrue(
            "Fresh-install pending_reviews rawNotificationId index must be unique",
            isUniqueIndex("index_pending_reviews_rawNotificationId")
        )
    }

    @Test
    fun pending_reviews_rejects_duplicate_non_null_rawNotificationId_on_fresh_install() {
        val db = database.openHelper.writableDatabase

        db.execSQL(
            """
            INSERT INTO raw_notifications (
                id, packageName, timestamp, capturedAt, isProcessed
            ) VALUES (
                1, 'com.test.bank', 1700000000000, 1700000000000, 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO pending_reviews (
                rawNotificationId, suggestedAmount, suggestedCurrency,
                suggestedMerchant, suggestedType, suggestedCategoryId,
                confidence, packageName, notificationTitle, notificationText,
                createdAt, status
            ) VALUES (
                1, 10.0, 'EUR',
                'Test', 'PURCHASE', NULL,
                0.8, 'com.test', 'title', 'text',
                ${System.currentTimeMillis()}, 'PENDING'
            )
            """.trimIndent()
        )

        try {
            db.execSQL(
                """
                INSERT INTO pending_reviews (
                    rawNotificationId, suggestedAmount, suggestedCurrency,
                    suggestedMerchant, suggestedType, suggestedCategoryId,
                    confidence, packageName, notificationTitle, notificationText,
                    createdAt, status
                ) VALUES (
                    1, 11.0, 'EUR',
                    'Test Duplicate', 'PURCHASE', NULL,
                    0.9, 'com.test', 'title', 'text',
                    ${System.currentTimeMillis()}, 'PENDING'
                )
                """.trimIndent()
            )
            fail("Expected unique constraint violation for duplicate non-null rawNotificationId on fresh install")
        } catch (_: Exception) {
            // expected — UNIQUE index on rawNotificationId fires
        }
    }

    @Test
    fun pending_reviews_allows_multiple_null_rawNotificationId_on_fresh_install() {
        val db = database.openHelper.writableDatabase

        db.execSQL(
            """
            INSERT INTO pending_reviews (
                rawNotificationId, suggestedAmount, suggestedCurrency,
                suggestedMerchant, suggestedType, suggestedCategoryId,
                confidence, packageName, notificationTitle, notificationText,
                createdAt, status
            ) VALUES (
                NULL, 12.0, 'EUR',
                'Null One', 'PURCHASE', NULL,
                0.8, 'com.test', 'title', 'text',
                ${System.currentTimeMillis()}, 'PENDING'
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            INSERT INTO pending_reviews (
                rawNotificationId, suggestedAmount, suggestedCurrency,
                suggestedMerchant, suggestedType, suggestedCategoryId,
                confidence, packageName, notificationTitle, notificationText,
                createdAt, status
            ) VALUES (
                NULL, 13.0, 'EUR',
                'Null Two', 'PURCHASE', NULL,
                0.9, 'com.test', 'title', 'text',
                ${System.currentTimeMillis()}, 'PENDING'
            )
            """.trimIndent()
        )
    }

    // ── savings_goals CHECK constraints ─────────────────────────────────────

    @Test
    fun savings_goals_rejects_zero_targetAmount() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO savings_goals (name, targetAmount, currentAmount, protectionLevel, createdAt)
                VALUES ('Vacation', 0.0, 0.0, 'WARNING', ${System.currentTimeMillis()})
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for targetAmount = 0")
        } catch (_: Exception) {
            // expected — CHECK(targetAmount > 0) fires
        }
    }

    @Test
    fun savings_goals_rejects_negative_targetAmount() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO savings_goals (name, targetAmount, currentAmount, protectionLevel, createdAt)
                VALUES ('Vacation', -100.0, 0.0, 'WARNING', ${System.currentTimeMillis()})
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for targetAmount = -100")
        } catch (_: Exception) {
            // expected — CHECK(targetAmount > 0) fires
        }
    }

    @Test
    fun savings_goals_rejects_negative_currentAmount() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO savings_goals (name, targetAmount, currentAmount, protectionLevel, createdAt)
                VALUES ('Vacation', 1000.0, -50.0, 'WARNING', ${System.currentTimeMillis()})
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for currentAmount = -50")
        } catch (_: Exception) {
            // expected — CHECK(currentAmount >= 0) fires
        }
    }

    @Test
    fun savings_goals_accepts_valid_insert() = runBlocking {
        val dao = database.savingsGoalDao()
        val id = dao.insertGoal(
            SavingsGoal(
                name = "Emergency Fund",
                targetAmount = 5000.0,
                currentAmount = 0.0,
                createdAt = System.currentTimeMillis()
            )
        )
        assertTrue("Valid savings goal should insert successfully", id > 0)
    }

    // ── mileage_tracking CHECK constraints ──────────────────────────────────

    @Test
    fun mileage_tracking_rejects_zero_distanceKm() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO mileage_tracking (
                    date, distanceKm, isBusinessTrip, tripPurpose,
                    deductionRatePerKm, createdAt
                ) VALUES (
                    ${System.currentTimeMillis()}, 0.0, 1, 'Client visit',
                    0.30, ${System.currentTimeMillis()}
                )
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for distanceKm = 0")
        } catch (_: Exception) {
            // expected — CHECK(distanceKm > 0) fires
        }
    }

    @Test
    fun mileage_tracking_rejects_negative_distanceKm() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO mileage_tracking (
                    date, distanceKm, isBusinessTrip, tripPurpose,
                    deductionRatePerKm, createdAt
                ) VALUES (
                    ${System.currentTimeMillis()}, -5.0, 1, 'Client visit',
                    0.30, ${System.currentTimeMillis()}
                )
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for distanceKm = -5")
        } catch (_: Exception) {
            // expected — CHECK(distanceKm > 0) fires
        }
    }

    @Test
    fun mileage_tracking_rejects_inverted_odometers() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO mileage_tracking (
                    date, startOdometer, endOdometer, distanceKm,
                    isBusinessTrip, tripPurpose, deductionRatePerKm, createdAt
                ) VALUES (
                    ${System.currentTimeMillis()}, 50000.0, 49000.0, 10.0,
                    1, 'Client visit', 0.30, ${System.currentTimeMillis()}
                )
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for endOdometer < startOdometer")
        } catch (_: Exception) {
            // expected — CHECK(endOdometer IS NULL OR startOdometer IS NULL OR endOdometer >= startOdometer) fires
        }
    }

    @Test
    fun mileage_tracking_accepts_valid_insert_with_odometers() {
        val db = database.openHelper.writableDatabase
        // Should not throw — all values satisfy CHECKs
        db.execSQL(
            """
            INSERT INTO mileage_tracking (
                date, startOdometer, endOdometer, distanceKm,
                isBusinessTrip, tripPurpose, deductionRatePerKm, createdAt
            ) VALUES (
                ${System.currentTimeMillis()}, 49000.0, 49050.0, 50.0,
                1, 'Site inspection', 0.30, ${System.currentTimeMillis()}
            )
            """.trimIndent()
        )
        // If we reach here, the insert succeeded as expected
    }

    @Test
    fun mileage_tracking_accepts_null_odometers() {
        val db = database.openHelper.writableDatabase
        // NULL odometers should pass the CHECK — the constraint only applies when both are present
        db.execSQL(
            """
            INSERT INTO mileage_tracking (
                date, startOdometer, endOdometer, distanceKm,
                isBusinessTrip, tripPurpose, deductionRatePerKm, createdAt
            ) VALUES (
                ${System.currentTimeMillis()}, NULL, NULL, 25.0,
                1, 'Meeting', 0.30, ${System.currentTimeMillis()}
            )
            """.trimIndent()
        )
    }

    // ── budgets CHECK constraints ───────────────────────────────────────────

    @Test
    fun budgets_rejects_zero_amount() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO budgets (
                    categoryId, amount, period, periodMode, startDate,
                    isActive, notifyAtWarning, notifyAtCritical, rollover, createdAt
                ) VALUES (
                    NULL, 0.0, 'MONTHLY', 'ROLLING', ${System.currentTimeMillis()},
                    1, 0.75, 0.9, 0, ${System.currentTimeMillis()}
                )
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for amount = 0")
        } catch (_: Exception) {
            // expected — CHECK(amount > 0) fires
        }
    }

    @Test
    fun budgets_rejects_negative_amount() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO budgets (
                    categoryId, amount, period, periodMode, startDate,
                    isActive, notifyAtWarning, notifyAtCritical, rollover, createdAt
                ) VALUES (
                    NULL, -100.0, 'MONTHLY', 'ROLLING', ${System.currentTimeMillis()},
                    1, 0.75, 0.9, 0, ${System.currentTimeMillis()}
                )
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for amount = -100")
        } catch (_: Exception) {
            // expected — CHECK(amount > 0) fires
        }
    }

    @Test
    fun budgets_rejects_warning_greater_than_critical() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO budgets (
                    categoryId, amount, period, periodMode, startDate,
                    isActive, notifyAtWarning, notifyAtCritical, rollover, createdAt
                ) VALUES (
                    NULL, 500.0, 'MONTHLY', 'ROLLING', ${System.currentTimeMillis()},
                    1, 0.95, 0.80, 0, ${System.currentTimeMillis()}
                )
                """.trimIndent()
            )
            fail("Expected CHECK constraint violation for notifyAtWarning (0.95) > notifyAtCritical (0.80)")
        } catch (_: Exception) {
            // expected — CHECK(notifyAtWarning <= notifyAtCritical) fires
        }
    }

    @Test
    fun budgets_accepts_valid_insert() = runBlocking {
        val dao = database.budgetDao()
        val id = dao.insert(
            Budget(
                categoryId = null,
                amount = 1000.0,
                period = BudgetPeriod.MONTHLY,
                startDate = System.currentTimeMillis(),
                isActive = true,
                notifyAtWarning = 0.75f,
                notifyAtCritical = 0.90f
            )
        )
        assertTrue("Valid budget should insert successfully", id > 0)
    }

    @Test
    fun budgets_accepts_equal_warning_and_critical() {
        val db = database.openHelper.writableDatabase
        // warning == critical is valid (CHECK is <=, not <)
        db.execSQL(
            """
            INSERT INTO budgets (
                categoryId, amount, period, periodMode, startDate,
                isActive, notifyAtWarning, notifyAtCritical, rollover, createdAt
            ) VALUES (
                NULL, 200.0, 'WEEKLY', 'ROLLING', ${System.currentTimeMillis()},
                0, 0.80, 0.80, 0, ${System.currentTimeMillis()}
            )
            """.trimIndent()
        )
        // If we reach here, the insert succeeded as expected
    }

    // ── expenses.splitTemplateId FK ON DELETE SET NULL ───────────────────────

    @Test
    fun expense_splitTemplateId_becomes_null_when_template_deleted() = runBlocking {
        val templateDao = database.splitTemplateDao()
        val expenseDao = database.expenseDao()

        // 1. Insert a split template
        val template = SplitTemplate(
            name = "50/50 Split",
            totalSplits = 2,
            shares = """[{"participantIndex":0,"participantName":"Me","percentage":50.0},{"participantIndex":1,"participantName":"Partner","percentage":50.0}]""",
            description = "Even split",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val templateId = templateDao.insertTemplate(template)
        assertTrue("Template should insert successfully", templateId > 0)

        // 2. Insert an expense referencing the template
        val expense = Expense(
            amount = 100.0,
            currency = "EUR",
            merchant = "Restaurant",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            splitTemplateId = templateId
        )
        val expenseId = expenseDao.insert(expense)
        assertTrue("Expense should insert successfully", expenseId > 0)

        // 3. Verify the expense has the splitTemplateId set
        val beforeDelete = expenseDao.getById(expenseId)
        assertTrue(
            "Expense should reference the template before deletion",
            beforeDelete != null && beforeDelete.splitTemplateId == templateId
        )

        // 4. Delete the template
        val templateToDelete = templateDao.getTemplateById(templateId)!!
        templateDao.deleteTemplate(templateToDelete)

        // 5. Verify the expense's splitTemplateId is now NULL (ON DELETE SET NULL)
        val afterDelete = expenseDao.getById(expenseId)
        assertNull(
            "Expense's splitTemplateId should be NULL after template deletion (ON DELETE SET NULL)",
            afterDelete?.splitTemplateId
        )
    }

    @Test
    fun expense_splitTemplateId_rejects_nonexistent_template() {
        val db = database.openHelper.writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO expenses (
                    amount, currency, merchant, transactionType, date,
                    createdAt, paymentMethod, isManualEntry, isNotMine,
                    isSharedExpense, isBusinessExpense, requiresReceipt,
                    backfillAttempts, splitTemplateId
                ) VALUES (
                    50.0, 'EUR', 'Shop', 'PURCHASE', ${System.currentTimeMillis()},
                    ${System.currentTimeMillis()}, 'CARD', 0, 0,
                    0, 0, 0,
                    0, 999999
                )
                """.trimIndent()
            )
            fail("Expected FK constraint violation for non-existent splitTemplateId")
        } catch (_: Exception) {
            // expected — FK to split_templates(id) fires
        }
    }
}
