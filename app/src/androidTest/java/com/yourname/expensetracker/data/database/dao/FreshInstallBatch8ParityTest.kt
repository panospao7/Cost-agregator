package com.yourname.expensetracker.data.database.dao

import android.database.Cursor
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Batch 8 closure - fresh-install parity behavioral test.**
 *
 * Verifies Room-owned fresh-install behavior, including the
 * `expenses.splitTemplateId` FK with `ON DELETE SET NULL` semantics, on
 * brand-new in-memory databases built via [AppDatabase.inMemoryBuilder].
 *
 * Historical migration-only CHECK constraints are covered by
 * [DatabaseMigrationTest]. They were intentionally removed by the 144→145
 * pending_reviews rebuild and are not part of the canonical v149 fresh-install
 * contract because [AppDatabase.FRESH_INSTALL_CALLBACK] is legacy and is not
 * registered.
 *
 * The remaining fresh-install contract is the Room-owned FK:
 *
 * | Table    | FK semantics                                              |
 * |----------|-----------------------------------------------------------|
 * | expenses | splitTemplateId -> split_templates(id) ON DELETE SET NULL |
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

    // -- pending_reviews Room-owned indexes and valid writes --

    @Test
    fun pending_reviews_accepts_valid_insert() {
        val db = database.openHelper.writableDatabase
        // Should not throw for a valid Room-shaped row.
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

    // -- savings_goals valid writes --

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

    // -- mileage_tracking Room-shaped valid writes --

    @Test
    fun mileage_tracking_accepts_valid_insert_with_odometers() {
        val db = database.openHelper.writableDatabase
        // Should not throw for a valid Room-shaped row.
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
        // NULL odometers are valid in the Room entity schema.
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

    // -- budgets Room-shaped valid writes --

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
        // Equal warning and critical thresholds are valid in the Room schema.
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
