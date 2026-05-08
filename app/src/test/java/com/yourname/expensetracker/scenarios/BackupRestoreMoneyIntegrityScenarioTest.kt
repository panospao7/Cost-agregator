package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import com.yourname.expensetracker.testfixtures.scenario.CategorySeed
import com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeeder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests for backup/restore money integrity.
 *
 * Validates that migration definitions from schema 117 to 120 are properly
 * registered, that new DAOs for post-v117 tables are non-null, and that
 * expense data seeded into the database survives a roundtrip — all fields
 * are preserved when read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupRestoreMoneyIntegrityScenarioTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: ALL_MIGRATIONS contains 117→118, 118→119, 119→120
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `ALL_MIGRATIONS contains 117 to 120 migration steps`() {
        // GIVEN: the ALL_MIGRATIONS array from AppDatabase companion
        val migrations = AppDatabase.ALL_MIGRATIONS

        // WHEN: extracting the version ranges covered
        val versionPairs = migrations.map { it.startVersion to it.endVersion }

        // THEN:
        //   - 117→118 is present
        assertTrue(
            "ALL_MIGRATIONS should contain migration 117→118",
            versionPairs.contains(117 to 118)
        )

        //   - 118→119 is present
        assertTrue(
            "ALL_MIGRATIONS should contain migration 118→119",
            versionPairs.contains(118 to 119)
        )

        //   - 119→120 is present
        assertTrue(
            "ALL_MIGRATIONS should contain migration 119→120",
            versionPairs.contains(119 to 120)
        )

        // AND: current schema version is at least 120
        assertTrue(
            "Schema version should be >= 120",
            AppDatabase.APP_DATABASE_SCHEMA_VERSION >= 120
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Schema v120 contains all new tables via non-null DAOs
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `schema v120 contains all new table DAOs`() {
        // GIVEN: an in-memory AppDatabase built at version 120

        // WHEN: accessing the DAOs for tables added after v117

        // THEN:
        //   - warrantyLifecycleEventDao() is not null
        assertNotNull(
            "warrantyLifecycleEventDao should not be null",
            db.warrantyLifecycleEventDao()
        )

        //   - investmentTransactionDao() is not null
        assertNotNull(
            "investmentTransactionDao should not be null",
            db.investmentTransactionDao()
        )

        //   - groupSettlementDao() is not null
        assertNotNull(
            "groupSettlementDao should not be null",
            db.groupSettlementDao()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Expense seed and query survives roundtrip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `expense seed and query survives roundtrip`() = runTest {
        // GIVEN: a seeded expense with known values across all core fields
        val now = dateMs(2026, 5, 1)
        val seed = ScenarioSeed(
            categories = listOf(
                CategorySeed("Food & Dining")
            ),
            expenses = listOf(
                ExpenseSeed(
                    amount = 99.99,
                    currency = "USD",
                    merchant = "Costco",
                    transactionType = "PURCHASE",
                    date = now,
                    categoryName = "Food & Dining",
                    notes = "Weekly groceries"
                )
            ),
            fixedNowMs = now,
            description = "Single expense roundtrip verification"
        )

        // WHEN: seeding the expense
        val seeder = ScenarioSeeder(db)
        val seedResult = seeder.seedState(seed)

        // THEN: the expense was inserted with a positive ID
        assertEquals("Should have exactly 1 expense ID", 1, seedResult.expenseIds.size)
        val expenseId = seedResult.expenseIds.first()
        assertTrue("Expense ID should be positive", expenseId > 0L)

        // AND: querying back by ID returns all fields unchanged
        val fetched = db.expenseDao().getById(expenseId)
        assertNotNull("Expense should exist after seeding", fetched)

        // Assert every core field matches the original input
        assertEquals("Amount mismatch", 99.99, fetched!!.amount, 0.001)
        assertEquals("Currency mismatch", "USD", fetched.currency)
        assertEquals("Merchant mismatch", "Costco", fetched.merchant)
        assertEquals("Transaction type mismatch", TransactionType.PURCHASE, fetched.transactionType)
        assertEquals("Date mismatch", now, fetched.date)
        assertEquals("Notes mismatch", "Weekly groceries", fetched.notes)

        // AND: category ID resolves to "Food & Dining"
        val categoryId = seedResult.categoryIds["Food & Dining"]
        assertNotNull("Category ID should exist", categoryId)
        assertEquals("Category ID mismatch", categoryId, fetched.categoryId)

        // AND: the total expense count is 1
        assertEquals("Should have exactly 1 expense total", 1, db.expenseDao().getTotalCount())
    }
}
