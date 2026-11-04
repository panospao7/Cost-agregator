package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpense
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real in-memory Room through the real [NaturalLanguageExpenseQueryRepositoryImpl]: `countsInTotal` is true
 * only for a PURCHASE row that counts as spending, and `isSpendingNeutralised` is true for flagged rows.
 * Deposits and flagged rows stay in the results (marked by the caller), not-mine rows never appear.
 *
 * Fixed UTC dates; the synthetic merchants carry no real data.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NaturalLanguageExpenseQueryRepositoryImplMappingTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: NaturalLanguageExpenseQueryRepositoryImpl

    private val start = dateMs(2026, 5, 1)
    private val end = dateMs(2026, 6, 1)
    private val inRange = dateMs(2026, 5, 10)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        repository = NaturalLanguageExpenseQueryRepositoryImpl(db.expenseDao())
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun expense(
        merchant: String,
        type: TransactionType = TransactionType.PURCHASE,
        isNotMine: Boolean = false,
        exclusionReason: String? = null,
        amount: Double = 10.0
    ) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = type,
        date = inRange,
        isNotMine = isNotMine,
        spendingExclusionReason = exclusionReason
    )

    private suspend fun seed() {
        val dao = db.expenseDao()
        dao.insert(expense("NormalPurchase"))
        dao.insert(expense("Deposit", type = TransactionType.DEPOSIT, amount = 200.0))
        dao.insert(expense("FlaggedPurchase", exclusionReason = "INSTALLMENT_PURCHASE", amount = 300.0))
        dao.insert(expense("Withdrawal", type = TransactionType.WITHDRAWAL))
        dao.insert(expense("Transfer", type = TransactionType.TRANSFER))
        dao.insert(expense("NotMinePurchase", isNotMine = true))
    }

    private fun List<NaturalLanguageExpense>.byMerchant(name: String): NaturalLanguageExpense {
        val found = firstOrNull { it.merchant == name }
        assertNotNull("row $name must be returned", found)
        return found!!
    }

    private suspend fun keysetRows(): List<NaturalLanguageExpense> =
        repository.getExpensesBetweenFilteredKeyset(
            startMs = start, endMs = end, categoryIds = null, merchants = null,
            transactionType = null, keywordSearch = null, limit = 500, cursor = null
        )

    @Test
    fun `countsInTotal is true for a counted purchase`() = runTest {
        seed()

        val row = keysetRows().byMerchant("NormalPurchase")

        assertTrue(row.countsInTotal)
        assertFalse(row.isSpendingNeutralised)
    }

    @Test
    fun `countsInTotal is false for a deposit and the deposit is still returned`() = runTest {
        seed()

        val row = keysetRows().byMerchant("Deposit")

        assertFalse(row.countsInTotal)
        assertFalse(row.isSpendingNeutralised)
        assertEquals(200.0, row.amount, 0.0)
    }

    @Test
    fun `countsInTotal is false and isSpendingNeutralised true for a flagged purchase that is still returned`() =
        runTest {
            seed()

            val row = keysetRows().byMerchant("FlaggedPurchase")

            assertFalse(row.countsInTotal)
            assertTrue(row.isSpendingNeutralised)
            assertEquals("flagged row shows its gross amount", 300.0, row.amount, 0.0)
            assertEquals("flagged row adds nothing", 0.0, row.effectiveAmount, 0.0)
        }

    @Test
    fun `countsInTotal is false for withdrawal and transfer rows`() = runTest {
        seed()

        val rows = keysetRows()

        assertFalse(rows.byMerchant("Withdrawal").countsInTotal)
        assertFalse(rows.byMerchant("Transfer").countsInTotal)
    }

    @Test
    fun `not-mine purchase is not returned`() = runTest {
        seed()

        assertTrue(keysetRows().none { it.merchant == "NotMinePurchase" })
    }

    @Test
    fun `exactly the mine rows are returned and only the normal purchase counts`() = runTest {
        seed()

        val rows = keysetRows()

        assertEquals(
            setOf("NormalPurchase", "Deposit", "FlaggedPurchase", "Withdrawal", "Transfer"),
            rows.map { it.merchant }.toSet()
        )
        assertEquals(listOf("NormalPurchase"), rows.filter { it.countsInTotal }.map { it.merchant })
        assertEquals(listOf("FlaggedPurchase"), rows.filter { it.isSpendingNeutralised }.map { it.merchant })
    }

    @Test
    fun `getExpensesBetween applies the same mapping`() = runTest {
        seed()

        val rows = repository.getExpensesBetween(start, end)

        assertTrue(rows.byMerchant("NormalPurchase").countsInTotal)
        assertFalse(rows.byMerchant("Deposit").countsInTotal)
        assertFalse(rows.byMerchant("FlaggedPurchase").countsInTotal)
        assertTrue(rows.byMerchant("FlaggedPurchase").isSpendingNeutralised)
        assertTrue(rows.none { it.merchant == "NotMinePurchase" })
    }
}
