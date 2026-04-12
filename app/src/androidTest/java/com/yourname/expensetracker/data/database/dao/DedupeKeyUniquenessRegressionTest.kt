package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ISSUE-9 regression: Real in-memory Room / DAO-backed test that proves the
 * persisted [Expense.dedupeKey] unique index behaves correctly for:
 *
 *  1. **Incompatible-type approvals do NOT collide** — inserting a DEPOSIT row
 *     when a PURCHASE with the same amount/merchant/date/currency already exists
 *     must succeed, because [DuplicateDetectionPolicy.generateDedupeKeyWithType]
 *     appends a type suffix ("_PURCHASE" vs "_DEPOSIT") making the two keys
 *     distinct on the unique index.
 *
 *  2. **Same-type races ARE blocked** — a second insert attempt for a PURCHASE
 *     with the same dedupeKey (simulating a concurrent approval race) must be
 *     rejected by the index: [ExpenseDao.insertAtomic] returns -1.
 *
 * These tests exercise the *real* SQLite unique index enforced by Room, not
 * mocked DAO behaviour — they are the only path to confirm the fix in ISSUE-1
 * is actually protected by the persistence layer.
 */
@RunWith(AndroidJUnit4::class)
class DedupeKeyUniquenessRegressionTest {

    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao

    // Shared fixture values used across tests
    private val amount = 10.0
    private val merchant = "Acme"
    private val date = 1_700_000_000_000L
    private val currency = "EUR"

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        expenseDao = database.expenseDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeExpense(type: TransactionType): Expense {
        val key = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, type
        )
        return Expense(
            amount = amount,
            currency = currency,
            merchant = merchant,
            transactionType = type,
            date = date,
            dedupeKey = key
        )
    }

    // ── Test 1: incompatible types do NOT collide ────────────────────────────

    /**
     * Proves that a PURCHASE row and a DEPOSIT row with the same
     * amount/merchant/date/currency can both be persisted without a unique-index
     * conflict, because their type-aware dedupeKeys are distinct.
     *
     * Before the ISSUE-1 fix, both rows would have carried the same type-blind
     * key (no "_PURCHASE" / "_DEPOSIT" suffix), so the second insert would
     * silently fail and return -1.
     */
    @Test
    fun incompatibleTypes_insertSuccessfully_noDuplicateKeyCollision() = runBlocking {
        val purchaseExpense = makeExpense(TransactionType.PURCHASE)
        val depositExpense  = makeExpense(TransactionType.DEPOSIT)

        // Sanity: keys must be distinct (the whole point of the type-suffix fix)
        assertNotEquals(
            "PURCHASE and DEPOSIT dedupeKeys must differ to avoid a false unique-index collision",
            purchaseExpense.dedupeKey,
            depositExpense.dedupeKey
        )

        // Both inserts must succeed against the real Room unique index
        val purchaseId = expenseDao.insertAtomic(purchaseExpense)
        assertTrue(
            "First (PURCHASE) insertAtomic must return a valid row-id (> 0), got $purchaseId",
            purchaseId > 0L
        )

        val depositId = expenseDao.insertAtomic(depositExpense)
        assertTrue(
            "Second (DEPOSIT) insertAtomic must succeed despite sharing amount/merchant/date/currency " +
            "with the existing PURCHASE, because the type-aware dedupeKeys are distinct. Got $depositId",
            depositId > 0L
        )

        // Confirm both rows are persisted
        assertEquals(
            "Both the PURCHASE and DEPOSIT rows must be present in the database",
            2,
            expenseDao.getTotalCount()
        )
    }

    // ── Test 2: same-type race is blocked by the unique index ────────────────

    /**
     * Proves that a second [insertAtomic] for a PURCHASE with the same
     * dedupeKey is rejected by the SQLite unique index (IGNORE conflict
     * strategy → returns -1).
     *
     * This simulates the race-condition guard: two concurrent approval workers
     * both pass the isDuplicateCurrencyAware pre-check, but only the first one
     * wins the unique-index race; the second returns -1 and the caller treats
     * the approval as a duplicate.
     */
    @Test
    fun sameType_secondInsert_blockedByUniqueIndex() = runBlocking {
        val purchaseExpense = makeExpense(TransactionType.PURCHASE)

        // First insert wins the race
        val firstId = expenseDao.insertAtomic(purchaseExpense)
        assertTrue(
            "First PURCHASE insertAtomic must succeed, got $firstId",
            firstId > 0L
        )

        // Second insert with the same dedupeKey simulates the race loser
        val secondId = expenseDao.insertAtomic(purchaseExpense.copy(id = 0))
        assertEquals(
            "Second PURCHASE insertAtomic with the same dedupeKey must be rejected " +
            "by the unique index (OnConflictStrategy.IGNORE → returns -1)",
            -1L,
            secondId
        )

        // Only the winning row is in the database
        assertEquals(
            "Only the first PURCHASE row must be present; the duplicate must not have been inserted",
            1,
            expenseDao.getTotalCount()
        )
    }

    // ── Test 3: currency-distinct rows do NOT collide ────────────────────────

    /**
     * Cross-currency guard: a PURCHASE for 10 USD and a PURCHASE for 10 EUR
     * at the same merchant/date must both persist. Different currencies produce
     * different dedupeKeys so no unique-index conflict occurs.
     */
    @Test
    fun differentCurrencies_sameMerchantAndDate_insertSuccessfully() = runBlocking {
        val eurKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, "EUR", TransactionType.PURCHASE
        )
        val usdKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, "USD", TransactionType.PURCHASE
        )

        assertNotEquals("EUR and USD dedupeKeys must differ", eurKey, usdKey)

        val eurExpense = Expense(
            amount = amount, currency = "EUR", merchant = merchant,
            transactionType = TransactionType.PURCHASE, date = date,
            dedupeKey = eurKey
        )
        val usdExpense = Expense(
            amount = amount, currency = "USD", merchant = merchant,
            transactionType = TransactionType.PURCHASE, date = date,
            dedupeKey = usdKey
        )

        val eurId = expenseDao.insertAtomic(eurExpense)
        assertTrue("EUR PURCHASE insertAtomic must succeed, got $eurId", eurId > 0L)

        val usdId = expenseDao.insertAtomic(usdExpense)
        assertTrue(
            "USD PURCHASE insertAtomic must succeed (different currency → distinct key), got $usdId",
            usdId > 0L
        )

        assertEquals(
            "Both EUR and USD rows must be present",
            2,
            expenseDao.getTotalCount()
        )
    }
}
