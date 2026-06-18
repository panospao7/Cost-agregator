package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.dao.MerchantCurrencyTotal
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DB-backed tests verifying [ExpenseDao] aggregate query filtering.
 *
 * Tests that:
 * - [ExpenseDao.getBusinessExpensesBetweenByCurrency] excludes non-spending types
 * - [ExpenseDao.getLocatedMerchantTotalsByCurrency] excludes not-mine rows,
 *   excludes deposits/transfers, excludes null merchantKey, includes valid rows
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpenseDaoAggregateFilterTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val now = dateMs(2026, 5, 1)

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)

        // Seed categories
        db.categoryDao().insert(
            com.yourname.expensetracker.data.database.entity.Category(
                name = "Food",
                icon = "\uD83C\uDF54",
                color = "#FF5733"
            )
        )

        // Seed a business PURCHASE expense
        db.expenseDao().insert(
            Expense(
                amount = 100.0, currency = "EUR", merchant = "OfficeSupplyCo",
                transactionType = TransactionType.PURCHASE, date = now,
                isBusinessExpense = true,
                merchantKey = "officesupplyco",
                latitude = 37.7749, longitude = -122.4194
            )
        )

        // Seed a business DEPOSIT expense (should be excluded)
        db.expenseDao().insert(
            Expense(
                amount = 200.0, currency = "EUR", merchant = "ClientPayment",
                transactionType = TransactionType.DEPOSIT, date = now,
                isBusinessExpense = true,
                merchantKey = "clientpayment",
                latitude = 37.7749, longitude = -122.4194
            )
        )

        // Seed a business TRANSFER expense (should be excluded)
        db.expenseDao().insert(
            Expense(
                amount = 300.0, currency = "EUR", merchant = "InternalTransfer",
                transactionType = TransactionType.TRANSFER, date = now,
                isBusinessExpense = true,
                merchantKey = "internaltransfer",
                latitude = 37.7749, longitude = -122.4194
            )
        )

        // Seed a located expense with isNotMine=true (should be excluded)
        db.expenseDao().insert(
            Expense(
                amount = 50.0, currency = "USD", merchant = "NotMineMerchant",
                transactionType = TransactionType.PURCHASE, date = now,
                isNotMine = true,
                merchantKey = "notmine",
                latitude = 40.7128, longitude = -74.0060
            )
        )

        // Seed a located expense with null merchantKey (should be excluded)
        db.expenseDao().insert(
            Expense(
                amount = 25.0, currency = "GBP", merchant = "NoKeyMerchant",
                transactionType = TransactionType.PURCHASE, date = now,
                merchantKey = null,
                latitude = 51.5074, longitude = -0.1278
            )
        )

        // Seed a normal spending located expense (should be included)
        db.expenseDao().insert(
            Expense(
                amount = 75.0, currency = "EUR", merchant = "Supermarket",
                transactionType = TransactionType.PURCHASE, date = now,
                merchantKey = "supermarket",
                latitude = 48.8566, longitude = 2.3522
            )
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ── getBusinessExpensesBetweenByCurrency ─────────────────────────────

    @Test
    fun `getBusinessExpensesBetweenByCurrency excludes non-spending types`() = runTest {
        val result = db.expenseDao().getBusinessExpensesBetweenByCurrency(
            startDate = dateMs(2026, 4, 1),
            endDate = dateMs(2026, 6, 1)
        )

        // Only the PURCHASE row should appear; DEPOSIT and TRANSFER are excluded.
        assertEquals("Should return exactly 1 currency row", 1, result.size)

        val row = result.first()
        assertEquals("Currency should be EUR", "EUR", row.currency)
        assertEquals("Total should be 100.0", 100.0, row.total, 0.001)
        assertEquals("Transaction count should be 1", 1, row.txCount)
    }

    // ── getLocatedMerchantTotalsByCurrency ───────────────────────────────

    @Test
    fun `getLocatedMerchantTotalsByCurrency excludes not-mine rows`() = runTest {
        val result = db.expenseDao().getLocatedMerchantTotalsByCurrency()

        // The "NotMineMerchant" row (isNotMine=true) should not appear.
        val notMineRow = result.find { it.merchant == "NotMineMerchant" }
        assertTrue("NotMine row should be excluded", notMineRow == null)
    }

    @Test
    fun `getLocatedMerchantTotalsByCurrency excludes deposits and transfers`() = runTest {
        val result = db.expenseDao().getLocatedMerchantTotalsByCurrency()

        // "ClientPayment" (DEPOSIT) and "InternalTransfer" (TRANSFER) should not appear.
        val depositRow = result.find { it.merchant == "ClientPayment" }
        val transferRow = result.find { it.merchant == "InternalTransfer" }
        assertTrue("DEPOSIT row should be excluded", depositRow == null)
        assertTrue("TRANSFER row should be excluded", transferRow == null)
    }

    @Test
    fun `getLocatedMerchantTotalsByCurrency excludes null merchantKey`() = runTest {
        val result = db.expenseDao().getLocatedMerchantTotalsByCurrency()

        // "NoKeyMerchant" has merchantKey=null, should be excluded.
        val nullKeyRow = result.find { it.merchant == "NoKeyMerchant" }
        assertTrue("Row with null merchantKey should be excluded", nullKeyRow == null)
    }

    @Test
    fun `getLocatedMerchantTotalsByCurrency includes valid spending rows`() = runTest {
        val result = db.expenseDao().getLocatedMerchantTotalsByCurrency()

        // "OfficeSupplyCo" and "Supermarket" should both appear.
        val officeSupply = result.find { it.merchant == "OfficeSupplyCo" }
        val supermarket = result.find { it.merchant == "Supermarket" }

        assertTrue("OfficeSupplyCo should be included", officeSupply != null)
        assertEquals("OfficeSupplyCo total should be 100.0", 100.0, officeSupply!!.total, 0.001)

        assertTrue("Supermarket should be included", supermarket != null)
        assertEquals("Supermarket total should be 75.0", 75.0, supermarket!!.total, 0.001)

        // Should be exactly 2 valid merchant rows.
        assertEquals("Should have exactly 2 merchant rows", 2, result.size)
    }
}
