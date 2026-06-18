package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class ExpenseDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var scannedReceiptDao: ScannedReceiptDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        expenseDao = database.expenseDao()
        scannedReceiptDao = database.scannedReceiptDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeExpense(
        amount: Double = 10.0,
        merchant: String = "Test",
        date: Long = System.currentTimeMillis()
    ) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date
    )

    @Test
    fun insertAndRetrieve() = runBlocking {
        val expense = makeExpense()
        val id = expenseDao.insert(expense)
        assertTrue(id > 0)

        val all = expenseDao.getAll()
        assertEquals(1, all.size)
        assertEquals(10.0, all[0].amount, 0.01)
    }

    @Test
    fun getAllFlowEmitsUpdates() = runBlocking {
        expenseDao.insert(makeExpense(merchant = "A"))
        expenseDao.insert(makeExpense(merchant = "B"))

        val expenses = expenseDao.getAllFlow().first()
        assertEquals(2, expenses.size)
    }

    @Test
    fun deleteExpense() = runBlocking {
        val expense = makeExpense()
        val id = expenseDao.insert(expense)
        val inserted = expenseDao.getAll().first()
        expenseDao.delete(inserted)
        assertEquals(0, expenseDao.getAll().size)
    }

    @Test
    fun deleteAllExpenses() = runBlocking {
        expenseDao.insert(makeExpense(merchant = "A"))
        expenseDao.insert(makeExpense(merchant = "B"))
        expenseDao.deleteAll()
        assertEquals(0, expenseDao.getAll().size)
    }

    @Test
    fun getTotalSpentFlowOnlyCountsPurchases() = runBlocking {
        expenseDao.insert(makeExpense(amount = 10.0))
        expenseDao.insert(Expense(
            amount = 100.0, currency = "EUR", merchant = "Deposit",
            transactionType = TransactionType.DEPOSIT,
            date = System.currentTimeMillis()
        ))

        val total = expenseDao.getTotalSpentFlow().first()
        assertEquals(10.0, total!!, 0.01)
    }

    @Test
    fun isDuplicateDetectsWithinWindow() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now))

        val isDupe = expenseDao.isDuplicate(10.0, "Shop", now, 300000)
        assertTrue(isDupe)
    }

    @Test
    fun isDuplicateIgnoresOutsideWindow() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now - 600000))

        val isDupe = expenseDao.isDuplicate(10.0, "Shop", now, 300000)
        assertFalse(isDupe)
    }

    @Test
    fun isDuplicateIgnoresDifferentMerchant() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop A", date = now))

        val isDupe = expenseDao.isDuplicate(10.0, "Shop B", now, 300000)
        assertFalse(isDupe)
    }

    @Test
    fun isDuplicateIgnoresDifferentAmount() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now))

        val isDupe = expenseDao.isDuplicate(20.0, "Shop", now, 300000)
        assertFalse(isDupe)
    }

    @Test
    fun updateCategory() = runBlocking {
        val id = expenseDao.insert(makeExpense())
        val categoryId = database.categoryDao().insert(
            Category(name = "Food", icon = "🍔", color = "#FF0000")
        )
        expenseDao.updateCategory(id, categoryId)

        val updated = expenseDao.getAll().first()
        assertEquals(categoryId, updated.categoryId)
    }

    @Test
    fun getExpensesBetweenReturnsCorrectRange() = runBlocking {
        val now = 1_700_000_000_000L
        val twoDaysAgo = now - 2L * 86_400_000L
        val oneDayAgo = now - 86_400_000L
        expenseDao.insert(makeExpense(date = twoDaysAgo))
        expenseDao.insert(makeExpense(date = oneDayAgo))
        expenseDao.insert(makeExpense(date = now))

        val between = expenseDao.getExpensesBetween(now - 3L * 86_400_000L, oneDayAgo + 1L)
        assertEquals(2, between.size)
    }

    @Test
    fun getWeeklyTotalsForPeriod_returnsCanonicalLocalWeekBoundariesFromRepositoryMapping() = runBlocking {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))

            val weekMonday = localDateAtHourMs(2026, 4, 20, 0)
            val weekEnd = TimePeriodUtils.addDays(weekMonday, 7)

            expenseDao.insert(makeExpense(amount = 12.0, date = localDateAtHourMs(2026, 4, 20, 9)))
            expenseDao.insert(makeExpense(amount = 8.0, date = localDateAtHourMs(2026, 4, 22, 11)))

            val weekly = expenseDao.getWeeklyTotalsForPeriod(weekMonday, weekEnd)

            assertEquals(1, weekly.size)
            assertEquals("2026-04-20", weekly.first().weekKey)

            // DAO now emits the canonical Monday date key; the repository converts
            // this key to local-midnight epoch boundaries using TimePeriodUtils.
            val canonicalStart = LocalDate.parse(weekly.first().weekKey)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val canonicalEnd = TimePeriodUtils.addDays(canonicalStart, 7)

            assertEquals(TimePeriodUtils.getStartOfWeek(localDateAtHourMs(2026, 4, 22, 12)), canonicalStart)
            assertEquals(TimePeriodUtils.getEndOfWeek(localDateAtHourMs(2026, 4, 22, 12)), canonicalEnd)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun getWeeklyTotalsForPeriod_crossYearWeekProducesSingleBucket() = runBlocking {
        val start = localDateAtHourMs(2025, 12, 29, 0)
        val end = localDateAtHourMs(2026, 1, 5, 0)

        expenseDao.insert(makeExpense(amount = 10.0, date = localDateAtHourMs(2025, 12, 29, 9)))
        expenseDao.insert(makeExpense(amount = 20.0, date = localDateAtHourMs(2025, 12, 31, 15)))
        expenseDao.insert(makeExpense(amount = 30.0, date = localDateAtHourMs(2026, 1, 2, 10)))
        expenseDao.insert(makeExpense(amount = 40.0, date = localDateAtHourMs(2026, 1, 4, 20)))

        val weekly = expenseDao.getWeeklyTotalsForPeriod(start, end)

        assertEquals(1, weekly.size)
        assertEquals("2025-12-29", weekly.first().weekKey)
        assertEquals(100.0, weekly.first().total, 0.01)
        assertEquals(4, weekly.first().txCount)
    }

    @Test
    fun getMerchantLocationClusters_matches_floor_buckets_for_negative_coordinates() = runBlocking {
        expenseDao.insert(
            makeExpense(merchant = "SouthWest").copy(
                merchantKey = "southwest",
                latitude = -33.86,
                longitude = -151.20
            )
        )
        expenseDao.insert(
            makeExpense(merchant = "SouthWest").copy(
                merchantKey = "southwest",
                latitude = -33.87,
                longitude = -151.19
            )
        )
        expenseDao.insert(
            makeExpense(merchant = "SouthWest").copy(
                merchantKey = "southwest",
                latitude = -34.20,
                longitude = -151.60
            )
        )

        val clusters = expenseDao.getMerchantLocationClusters("southwest")

        assertEquals(2, clusters.size)
        assertEquals(2, clusters.first().count)
    }

    @Test
    fun purchaseCountOnlyCountsPurchases() = runBlocking {
        expenseDao.insert(makeExpense())
        expenseDao.insert(Expense(
            amount = 50.0, currency = "EUR", merchant = "ATM",
            transactionType = TransactionType.WITHDRAWAL,
            date = System.currentTimeMillis()
        ))

        assertEquals(1, expenseDao.getPurchaseCount())
    }

    @Test
    fun ignoreConflictOnDuplicateInsert() = runBlocking {
        val expense = makeExpense()
        val id1 = expenseDao.insert(expense)
        val id2 = expenseDao.insert(expense.copy(id = id1)) // Same ID
        // IGNORE strategy: id2 should be -1 (not inserted)
        assertEquals(-1L, id2)
        assertEquals(1, expenseDao.getAll().size)
    }

    // ── Shared-expense regression tests (A.1 effectiveAmount standardization) ────────────────

    /**
     * A shared purchase with an explicit myShareAmount should contribute only myShareAmount
     * to the total, not the full posted amount.
     */
    @Test
    fun getTotalSpent_sharedExpense_withExplicitShareAmount_usesShareAmount() = runBlocking {
        val now = System.currentTimeMillis()
        // Full bill = €100, user's share = €40
        expenseDao.insert(Expense(
            amount = 100.0, currency = "EUR", merchant = "Restaurant",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, myShareAmount = 40.0
        ))
        val total = expenseDao.getTotalSpentFlow().first()
        assertEquals("Shared expense with explicit share amount should contribute 40.0",
            40.0, total!!, 0.01)
    }

    /**
     * A shared purchase with mySharePercentage should contribute the proportional amount.
     */
    @Test
    fun getTotalSpent_sharedExpense_withSharePercentage_usesProportionalAmount() = runBlocking {
        val now = System.currentTimeMillis()
        // Full bill = €80, user's share = 50% → €40
        expenseDao.insert(Expense(
            amount = 80.0, currency = "EUR", merchant = "Cafe",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, mySharePercentage = 50
        ))
        val total = expenseDao.getTotalSpentFlow().first()
        assertEquals("Shared expense with 50% share should contribute 40.0",
            40.0, total!!, 0.01)
    }

    /**
     * An isNotMine purchase must contribute 0.0 to all totals (not the full amount).
     */
    @Test
    fun getTotalSpent_isNotMine_contributesZero() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(Expense(
            amount = 200.0, currency = "EUR", merchant = "Someone Else",
            transactionType = TransactionType.PURCHASE, date = now,
            isNotMine = true
        ))
        // isNotMine=1 rows are filtered out by the WHERE isNotMine=0 clause
        val total = expenseDao.getTotalSpentFlow().first()
        // Should be null (no qualifying rows) or 0, not 200
        val safeTotal = total ?: 0.0
        assertEquals("isNotMine expense must not contribute to totals", 0.0, safeTotal, 0.01)
    }

    /**
     * A deposit (income) should not be affected by effective amount; getTotalDepositsForPeriod
     * should use the effective amount for shared deposit rows.
     */
    @Test
    fun getTotalDepositsForPeriod_normalDeposit_usesFullAmount() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(Expense(
            amount = 1000.0, currency = "EUR", merchant = "Salary",
            transactionType = TransactionType.DEPOSIT, date = now
        ))
        val start = now - 60_000L
        val end = now + 60_000L
        val total = expenseDao.getTotalDepositsForPeriod(start, end)
        assertEquals("Normal deposit should contribute full amount", 1000.0, total, 0.01)
    }

    /**
     * getTotalForPeriod with mixed regular, shared (share amount), shared (percentage), and
     * isNotMine rows — verifies the aggregate uses effective amounts throughout.
     */
    @Test
    fun getTotalForPeriod_mixedOwnership_sumsEffectiveAmounts() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        // Regular purchase: contributes full 50.0
        expenseDao.insert(makeExpense(amount = 50.0).copy(date = now))
        // Shared with explicit share: contributes 30.0 (not 100.0)
        expenseDao.insert(Expense(
            amount = 100.0, currency = "EUR", merchant = "SharedFixed",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, myShareAmount = 30.0
        ))
        // Shared with percentage: contributes 25.0 (50% of 50.0)
        expenseDao.insert(Expense(
            amount = 50.0, currency = "EUR", merchant = "SharedPct",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, mySharePercentage = 50
        ))
        // isNotMine: contributes 0 (filtered by WHERE isNotMine=0)
        expenseDao.insert(Expense(
            amount = 999.0, currency = "EUR", merchant = "NotMine",
            transactionType = TransactionType.PURCHASE, date = now,
            isNotMine = true
        ))

        val total = expenseDao.getTotalForPeriod(start, end)
        // Expected: 50.0 + 30.0 + 25.0 = 105.0
        assertEquals("Mixed ownership rows should sum to 105.0 effective total",
            105.0, total, 0.01)
    }

    /**
     * getCategorySpentInPeriod — shared-expense budget regression.
     * Verifies that budget spend calculations use effective amounts for shared rows.
     */
    @Test
    fun getCategorySpentInPeriod_sharedExpense_usesEffectiveAmount() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        val categoryId = database.categoryDao().insert(
            Category(name = "Food", icon = "🍽", color = "#FF5733")
        )

        // Shared purchase with explicit share amount
        expenseDao.insert(Expense(
            amount = 120.0, currency = "EUR", merchant = "Restaurant",
            transactionType = TransactionType.PURCHASE, date = now,
            categoryId = categoryId,
            isSharedExpense = true, myShareAmount = 40.0
        ))
        // isNotMine purchase in same category — must not add to budget spend
        expenseDao.insert(Expense(
            amount = 80.0, currency = "EUR", merchant = "CafeMine",
            transactionType = TransactionType.PURCHASE, date = now,
            categoryId = categoryId,
            isNotMine = true
        ))

        val spent = expenseDao.getCategorySpentInPeriod(categoryId, start, end)
        assertEquals("Budget spend for category must use effectiveAmount: 40.0",
            40.0, spent, 0.01)
    }

    /**
     * getAmountsForPercentileCalc — percentile inputs must be effective amounts, not raw.
     */
    @Test
    fun getAmountsForPercentileCalc_sharedExpense_returnsEffectiveAmounts() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        // Regular purchase: 100.0
        expenseDao.insert(makeExpense(amount = 100.0).copy(date = now))
        // Shared with explicit share: effective = 40.0 (not 200.0)
        expenseDao.insert(Expense(
            amount = 200.0, currency = "EUR", merchant = "SharedShop",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, myShareAmount = 40.0
        ))

        val amounts = expenseDao.getAmountsForPercentileCalc(start, end)
        assertEquals("Should have 2 amounts (both rows are mine)", 2, amounts.size)
        // Sorted ascending: [40.0, 100.0]
        assertEquals("First amount should be 40.0 (effective share)", 40.0, amounts[0], 0.01)
        assertEquals("Second amount should be 100.0 (full ownership)", 100.0, amounts[1], 0.01)
    }

    /**
     * getTotalBusinessExpensesBetween — must use effective amount for shared business rows.
     */
    @Test
    fun getTotalBusinessExpensesBetween_sharedExpense_usesEffectiveAmount() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        // Regular business expense: contributes 150.0
        expenseDao.insert(Expense(
            amount = 150.0, currency = "EUR", merchant = "Client Lunch",
            transactionType = TransactionType.PURCHASE, date = now,
            isBusinessExpense = true
        ))
        // Shared business expense: contributes 25.0 (50% of 50.0), not 50.0
        expenseDao.insert(Expense(
            amount = 50.0, currency = "EUR", merchant = "Conference",
            transactionType = TransactionType.PURCHASE, date = now,
            isBusinessExpense = true,
            isSharedExpense = true, mySharePercentage = 50
        ))
        // isNotMine business expense: contributes 0.0
        expenseDao.insert(Expense(
            amount = 300.0, currency = "EUR", merchant = "Colleague Expense",
            transactionType = TransactionType.PURCHASE, date = now,
            isBusinessExpense = true, isNotMine = true
        ))

        val total = expenseDao.getTotalBusinessExpensesBetween(start, end) ?: 0.0
        // Expected: 150.0 + 25.0 + 0.0 = 175.0
        assertEquals("Business expense total must use effective amounts: 175.0",
            175.0, total, 0.01)
    }

    // ── A.10 Batch 1 — Canonical spending-filter tests ──────────────────────

    /**
     * Verify [DomainTransactionType.isSpending] returns true only for PURCHASE.
     * This is the in-memory single source-of-truth for spending semantics.
     */
    @Test
    fun domainTransactionType_isSpending_onlyTrueForPurchase() {
        assertTrue("PURCHASE must be spending", DomainTransactionType.PURCHASE.isSpending)
        assertFalse("WITHDRAWAL must NOT be spending", DomainTransactionType.WITHDRAWAL.isSpending)
        assertFalse("TRANSFER must NOT be spending", DomainTransactionType.TRANSFER.isSpending)
        assertFalse("DEPOSIT must NOT be spending", DomainTransactionType.DEPOSIT.isSpending)
        assertFalse("UNKNOWN must NOT be spending", DomainTransactionType.UNKNOWN.isSpending)
    }

    /**
     * Verify [ExpenseDao.SPENDING_TYPE] matches the string name of [TransactionType.PURCHASE].
     * This keeps the SQL constant and the entity enum in sync.
     */
    @Test
    fun spendingTypeConstant_matchesPurchaseEnumName() {
        assertEquals(
            "SPENDING_TYPE must equal TransactionType.PURCHASE.name",
            TransactionType.PURCHASE.name,
            ExpenseDao.SPENDING_TYPE
        )
    }

    /**
     * Verify [ExpenseDao.SPENDING_TYPE_SQL] is actually built from [ExpenseDao.SPENDING_TYPE].
     * This proves the SQL fragment is not a separate hardcoded string — changing
     * [SPENDING_TYPE] will automatically update every @Query that interpolates
     * [SPENDING_TYPE_SQL].
     */
    @Test
    fun spendingTypeSqlConstant_derivedFromSpendingType() {
        assertEquals(
            "SPENDING_TYPE_SQL must be 'transactionType = \\'<SPENDING_TYPE>\\''",
            "transactionType = '${ExpenseDao.SPENDING_TYPE}'",
            ExpenseDao.SPENDING_TYPE_SQL
        )
    }

    /**
     * Verify [ExpenseDao.SPENDING_TYPE_E_SQL] is the alias-prefixed form of [SPENDING_TYPE_SQL].
     * Used in queries that alias the expenses table as `e`.
     */
    @Test
    fun spendingTypeESqlConstant_derivedFromSpendingType() {
        assertEquals(
            "SPENDING_TYPE_E_SQL must be 'e.transactionType = \\'<SPENDING_TYPE>\\''",
            "e.transactionType = '${ExpenseDao.SPENDING_TYPE}'",
            ExpenseDao.SPENDING_TYPE_E_SQL
        )
    }

    // ── Helper: insert one expense per TransactionType ──

    /**
     * Insert one row per [TransactionType] at the given date, each with
     * the specified amount, and return the total count inserted.
     */
    private suspend fun insertAllTransactionTypes(
        amount: Double,
        date: Long,
        categoryId: Long? = null,
        merchantKeyPrefix: String = "merchant"
    ): Int {
        var count = 0
        for (type in TransactionType.values()) {
            expenseDao.insert(
                Expense(
                    amount = amount,
                    currency = "EUR",
                    merchant = "${type.name}_$merchantKeyPrefix",
                    merchantKey = "${type.name.lowercase()}_${merchantKeyPrefix}",
                    transactionType = type,
                    date = date,
                    categoryId = categoryId
                )
            )
            count++
        }
        return count
    }

    /**
     * getTotalSpentFlow must only sum PURCHASE rows.
     * Inserts one row per TransactionType; only the PURCHASE row's amount should appear.
     */
    @Test
    fun getTotalSpentFlow_excludesNonPurchaseTypes() = runBlocking {
        val now = System.currentTimeMillis()
        val typeCount = insertAllTransactionTypes(amount = 20.0, date = now)
        assertTrue("Precondition: should have rows for all types", typeCount >= 5)

        val total = expenseDao.getTotalSpentFlow().first()
        assertEquals(
            "getTotalSpentFlow must include only PURCHASE (20.0), not all types",
            20.0, total!!, 0.01
        )
    }

    /**
     * getTotalSpentBetween must only sum PURCHASE rows in the date range.
     * Verifies WITHDRAWAL, TRANSFER, DEPOSIT, UNKNOWN are excluded.
     */
    @Test
    fun getTotalSpentBetween_excludesNonPurchaseTypes() = runBlocking {
        val now = 1_700_000_000_000L
        val start = now - 1000L
        val end = now + 1000L
        insertAllTransactionTypes(amount = 15.0, date = now)

        val total = expenseDao.getTotalSpentBetween(start, end)
        assertEquals(
            "getTotalSpentBetween must include only PURCHASE (15.0)",
            15.0, total ?: 0.0, 0.01
        )
    }

    /**
     * getTotalForPeriod must only sum PURCHASE rows.
     * Mixes all five TransactionTypes with different amounts per type.
     */
    @Test
    fun getTotalForPeriod_excludesNonPurchaseTypes() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        // PURCHASE: 42.0
        expenseDao.insert(Expense(
            amount = 42.0, currency = "EUR", merchant = "Shop",
            transactionType = TransactionType.PURCHASE, date = now
        ))
        // DEPOSIT: 500.0 — should NOT appear
        expenseDao.insert(Expense(
            amount = 500.0, currency = "EUR", merchant = "Payroll",
            transactionType = TransactionType.DEPOSIT, date = now
        ))
        // WITHDRAWAL: 100.0 — should NOT appear
        expenseDao.insert(Expense(
            amount = 100.0, currency = "EUR", merchant = "ATM",
            transactionType = TransactionType.WITHDRAWAL, date = now
        ))
        // TRANSFER: 200.0 — should NOT appear
        expenseDao.insert(Expense(
            amount = 200.0, currency = "EUR", merchant = "Transfer",
            transactionType = TransactionType.TRANSFER, date = now
        ))
        // UNKNOWN: 75.0 — should NOT appear
        expenseDao.insert(Expense(
            amount = 75.0, currency = "EUR", merchant = "Mystery",
            transactionType = TransactionType.UNKNOWN, date = now
        ))

        val total = expenseDao.getTotalForPeriod(start, end)
        assertEquals(
            "getTotalForPeriod must include only PURCHASE (42.0)",
            42.0, total, 0.01
        )
    }

    /**
     * getCountForPeriod must count only PURCHASE rows.
     */
    @Test
    fun getCountForPeriod_excludesNonPurchaseTypes() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L
        insertAllTransactionTypes(amount = 10.0, date = now)

        val count = expenseDao.getCountForPeriod(start, end)
        assertEquals(
            "getCountForPeriod must count only PURCHASE rows (1)",
            1, count
        )
    }

    /**
     * getCategorySpentInPeriod must only sum PURCHASE rows for the given category.
     * Inserts mixed types with the same categoryId; only PURCHASE should contribute.
     */
    @Test
    fun getCategorySpentInPeriod_excludesNonPurchaseTypes() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        val categoryId = database.categoryDao().insert(
            Category(name = "Groceries", icon = "🛒", color = "#00FF00")
        )

        // PURCHASE in category: 35.0 — should contribute
        expenseDao.insert(Expense(
            amount = 35.0, currency = "EUR", merchant = "Supermarket",
            transactionType = TransactionType.PURCHASE, date = now,
            categoryId = categoryId
        ))
        // DEPOSIT in same category: 200.0 — should NOT contribute
        expenseDao.insert(Expense(
            amount = 200.0, currency = "EUR", merchant = "Refund",
            transactionType = TransactionType.DEPOSIT, date = now,
            categoryId = categoryId
        ))
        // WITHDRAWAL in same category: 50.0 — should NOT contribute
        expenseDao.insert(Expense(
            amount = 50.0, currency = "EUR", merchant = "ATM Cash",
            transactionType = TransactionType.WITHDRAWAL, date = now,
            categoryId = categoryId
        ))

        val spent = expenseDao.getCategorySpentInPeriod(categoryId, start, end)
        assertEquals(
            "getCategorySpentInPeriod must sum only PURCHASE rows (35.0)",
            35.0, spent, 0.01
        )
    }

    /**
     * getAmountsForPercentileCalc must only include PURCHASE rows.
     * Inserts mixed types; only the PURCHASE amount should appear in the result list.
     */
    @Test
    fun getAmountsForPercentileCalc_excludesNonPurchaseTypes() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        // PURCHASE: 55.0
        expenseDao.insert(Expense(
            amount = 55.0, currency = "EUR", merchant = "Shop",
            transactionType = TransactionType.PURCHASE, date = now
        ))
        // DEPOSIT: 1000.0 — must NOT appear
        expenseDao.insert(Expense(
            amount = 1000.0, currency = "EUR", merchant = "Salary",
            transactionType = TransactionType.DEPOSIT, date = now
        ))
        // TRANSFER: 300.0 — must NOT appear
        expenseDao.insert(Expense(
            amount = 300.0, currency = "EUR", merchant = "Savings",
            transactionType = TransactionType.TRANSFER, date = now
        ))

        val amounts = expenseDao.getAmountsForPercentileCalc(start, end)
        assertEquals("Only 1 PURCHASE amount should be in the list", 1, amounts.size)
        assertEquals("The single amount should be 55.0", 55.0, amounts[0], 0.01)
    }

    /**
     * Combined test: spending aggregates with mixed types AND shared-expense
     * effectiveAmount semantics. Proves that the spending filter (PURCHASE-only)
     * and the effective-amount CASE expression work together correctly.
     */
    @Test
    fun spendingAggregates_mixedTypesAndSharedExpenses_correctEffectiveAmounts() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        // PURCHASE, regular: effective = 80.0
        expenseDao.insert(Expense(
            amount = 80.0, currency = "EUR", merchant = "RegularShop",
            transactionType = TransactionType.PURCHASE, date = now
        ))
        // PURCHASE, shared with explicit share: effective = 30.0 (not 100.0)
        expenseDao.insert(Expense(
            amount = 100.0, currency = "EUR", merchant = "SharedDinner",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, myShareAmount = 30.0
        ))
        // PURCHASE, shared with percentage: effective = 20.0 (25% of 80.0)
        expenseDao.insert(Expense(
            amount = 80.0, currency = "EUR", merchant = "SharedTrip",
            transactionType = TransactionType.PURCHASE, date = now,
            isSharedExpense = true, mySharePercentage = 25
        ))
        // PURCHASE, isNotMine: effective = 0.0 (filtered by isNotMine=0)
        expenseDao.insert(Expense(
            amount = 500.0, currency = "EUR", merchant = "NotMineShop",
            transactionType = TransactionType.PURCHASE, date = now,
            isNotMine = true
        ))
        // DEPOSIT: 2000.0 — NOT a spending type, must be excluded
        expenseDao.insert(Expense(
            amount = 2000.0, currency = "EUR", merchant = "Salary",
            transactionType = TransactionType.DEPOSIT, date = now
        ))
        // WITHDRAWAL: 100.0 — NOT a spending type, must be excluded
        expenseDao.insert(Expense(
            amount = 100.0, currency = "EUR", merchant = "ATM",
            transactionType = TransactionType.WITHDRAWAL, date = now
        ))
        // TRANSFER: 500.0 — NOT a spending type, must be excluded
        expenseDao.insert(Expense(
            amount = 500.0, currency = "EUR", merchant = "Savings",
            transactionType = TransactionType.TRANSFER, date = now
        ))
        // UNKNOWN: 75.0 — NOT a spending type, must be excluded
        expenseDao.insert(Expense(
            amount = 75.0, currency = "EUR", merchant = "Mystery",
            transactionType = TransactionType.UNKNOWN, date = now
        ))

        // Expected effective total: 80.0 + 30.0 + 20.0 = 130.0
        val totalForPeriod = expenseDao.getTotalForPeriod(start, end)
        assertEquals(
            "getTotalForPeriod: only PURCHASE effective amounts (80+30+20=130)",
            130.0, totalForPeriod, 0.01
        )

        val totalBetween = expenseDao.getTotalSpentBetween(start, end)
        assertEquals(
            "getTotalSpentBetween: only PURCHASE effective amounts (130)",
            130.0, totalBetween ?: 0.0, 0.01
        )

        // getCountForPeriod: 3 qualifying PURCHASE rows (isNotMine excluded by isNotMine=0)
        val count = expenseDao.getCountForPeriod(start, end)
        assertEquals(
            "getCountForPeriod: 3 PURCHASE rows (isNotMine excluded)",
            3, count
        )

        // getAmountsForPercentileCalc: should return [20.0, 30.0, 80.0] sorted ASC
        val amounts = expenseDao.getAmountsForPercentileCalc(start, end)
        assertEquals("3 effective amounts for percentile", 3, amounts.size)
        assertEquals("Sorted ASC: first = 20.0", 20.0, amounts[0], 0.01)
        assertEquals("Sorted ASC: second = 30.0", 30.0, amounts[1], 0.01)
        assertEquals("Sorted ASC: third = 80.0", 80.0, amounts[2], 0.01)
    }

    /**
     * getPurchaseCount must only count PURCHASE rows; other types are excluded.
     */
    @Test
    fun getPurchaseCount_excludesNonPurchaseTypes() = runBlocking {
        val now = System.currentTimeMillis()
        // 2 PURCHASEs
        expenseDao.insert(makeExpense(amount = 10.0, date = now))
        expenseDao.insert(makeExpense(amount = 20.0, date = now))
        // 1 DEPOSIT
        expenseDao.insert(Expense(
            amount = 500.0, currency = "EUR", merchant = "Payroll",
            transactionType = TransactionType.DEPOSIT, date = now
        ))
        // 1 WITHDRAWAL
        expenseDao.insert(Expense(
            amount = 60.0, currency = "EUR", merchant = "ATM",
            transactionType = TransactionType.WITHDRAWAL, date = now
        ))

        assertEquals(
            "getPurchaseCount must return 2 (PURCHASE only)",
            2, expenseDao.getPurchaseCount()
        )
    }

    /**
     * Generic range query getExpensesBetween must NOT be narrowed to PURCHASE-only.
     * It should return all types in the date range (preserving generic semantics).
     */
    @Test
    fun getExpensesBetween_returnsAllTypes_notNarrowedToSpending() = runBlocking {
        val now = 1_700_000_000_000L
        val start = now - 1000L
        val end = now + 1000L

        // Insert one of each type
        val count = insertAllTransactionTypes(amount = 10.0, date = now)

        // getExpensesBetween is a generic range query — it filters by isNotMine=0
        // but does NOT filter by transactionType. All rows should be returned.
        val results = expenseDao.getExpensesBetween(start, end)
        assertEquals(
            "getExpensesBetween must return all $count types (generic, not spending-filtered)",
            count, results.size
        )
    }

    // ── B.4 Batch 9 — Business-expense receipt-detection query correctness ──

    private fun makeReceipt(
        expenseId: Long? = null,
        rawOcrText: String = "TOTAL 12.50",
        createdAt: Long = System.currentTimeMillis()
    ) = ScannedReceipt(
        imagePath = null,
        rawOcrText = rawOcrText,
        parsedTotal = 12.50,
        parsedMerchant = "Test Store",
        parsedDate = createdAt,
        parsedItems = null,
        parsedTaxAmount = null,
        confidence = 0.90f,
        expenseId = expenseId,
        matchStatus = if (expenseId != null) MatchStatus.AUTO_MATCHED else MatchStatus.UNMATCHED,
        createdAt = createdAt
    )

    private fun localDateAtHourMs(year: Int, month: Int, day: Int, hour: Int): Long =
        LocalDate.of(year, month, day)
            .atTime(hour, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    /**
     * A business expense with requiresReceipt=1 and NO linked scanned_receipt
     * must appear in [getBusinessExpensesMissingReceipts].
     */
    @Test
    fun getBusinessExpensesMissingReceipts_noReceipt_appearsInResults() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        expenseDao.insert(Expense(
            amount = 50.0, currency = "EUR", merchant = "Client Lunch",
            transactionType = TransactionType.PURCHASE, date = now,
            isBusinessExpense = true, requiresReceipt = true
        ))

        val missing = expenseDao.getBusinessExpensesMissingReceipts(start, end)
        assertEquals("Business expense with no receipt should appear", 1, missing.size)
        assertEquals("Client Lunch", missing[0].merchant)
    }

    /**
     * A business expense with requiresReceipt=1 that HAS a linked scanned_receipt
     * must NOT appear in [getBusinessExpensesMissingReceipts].
     */
    @Test
    fun getBusinessExpensesMissingReceipts_withReceipt_doesNotAppear() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        val expenseId = expenseDao.insert(Expense(
            amount = 75.0, currency = "EUR", merchant = "Conference Hotel",
            transactionType = TransactionType.PURCHASE, date = now,
            isBusinessExpense = true, requiresReceipt = true
        ))

        // Link a receipt to this expense
        scannedReceiptDao.insert(makeReceipt(expenseId = expenseId))

        val missing = expenseDao.getBusinessExpensesMissingReceipts(start, end)
        assertEquals(
            "Business expense WITH a linked receipt should NOT appear",
            0, missing.size
        )
    }

    /**
     * Regression: the old query used `rawNotificationId IS NULL` as a proxy
     * for "missing receipt". An expense that has a rawNotificationId but no
     * linked scanned_receipt must STILL appear in the missing list.
     * This proves the LEFT JOIN anti-join against scanned_receipts is correct,
     * and that rawNotificationId is irrelevant to receipt attachment.
     */
    @Test
    fun getBusinessExpensesMissingReceipts_withRawNotificationId_butNoReceipt_stillAppears() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now - 1000L
        val end = now + 1000L

        // Insert a RawNotification parent so the FK is valid
        val notifId = database.rawNotificationDao().insert(
            com.yourname.expensetracker.data.database.entity.RawNotification(
                packageName = "com.test.bank",
                appName = "Test Bank",
                title = "Payment",
                text = "You spent 50.00 at Office Supply",
                timestamp = now,
                capturedAt = now
            )
        )

        // Business expense with rawNotificationId set but NO receipt
        expenseDao.insert(Expense(
            amount = 50.0, currency = "EUR", merchant = "Office Supply",
            transactionType = TransactionType.PURCHASE, date = now,
            isBusinessExpense = true, requiresReceipt = true,
            rawNotificationId = notifId
        ))

        val missing = expenseDao.getBusinessExpensesMissingReceipts(start, end)
        assertEquals(
            "Expense with rawNotificationId but no receipt must still appear as missing",
            1, missing.size
        )
        assertEquals("Office Supply", missing[0].merchant)
    }
}
