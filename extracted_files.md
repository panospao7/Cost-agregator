# Extracted Kotlin Files

This document contains the source code of key files from the ExpenseTracker application.

## ExpenseDao.kt

**Path:** `data/database/dao/ExpenseDao.kt`


```kotlin

package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.database.model.ExpenseWithCategoryName
import com.yourname.expensetracker.domain.location.MerchantLocationGrid
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    companion object {
        // ── Canonical spending-type filter (A.10 Batch 1) ────────────────────
        //
        // Single source-of-truth for which transactionType values constitute
        // "user spending" in SQL queries.  Currently PURCHASE only.
        //
        // The Kotlin-side mirror is [DomainTransactionType.isSpending].
        //
        // Use [SPENDING_TYPE] in RawQuery builders, parameter bindings, and
        // Kotlin-side comparisons.
        // Use [SPENDING_TYPE_SQL] / [SPENDING_TYPE_E_SQL] inside @Query
        // annotations via compile-time `${}` interpolation (Kotlin const val),
        // in [RawQuery] dynamic SQL builders, and in parameter bindings.

        /** The single transaction-type value that qualifies as user spending. */
        const val SPENDING_TYPE: String = "PURCHASE"

        /**
         * Room-safe SQL fragment: `transactionType = 'PURCHASE'`.
         *
         * Used in `@Query` annotations via `${SPENDING_TYPE_SQL}` compile-time
         * interpolation (Kotlin `const val` strings are inlined by the compiler
         * before Room's annotation processor runs, exactly like
         * [EFFECTIVE_AMOUNT_SQL]).  Also usable in [RawQuery] dynamic SQL builders.
         */
        const val SPENDING_TYPE_SQL: String = "transactionType = '$SPENDING_TYPE'"

        /** Same predicate with `e.` table-alias prefix, for queries that alias expenses as `e`. */
        const val SPENDING_TYPE_E_SQL: String = "e.transactionType = '$SPENDING_TYPE'"

        /**
         * Single source-of-truth SQL fragment for the user's effective (ownership-adjusted) amount.
         *
         * Mirrors the Kotlin [com.yourname.expensetracker.data.database.entity.Expense.effectiveAmount]
         * getter exactly:
         *  - isNotMine = 1  → 0.0   (excluded entirely)
         *  - isSharedExpense with explicit myShareAmount → myShareAmount
         *  - isSharedExpense with mySharePercentage    → amount * mySharePercentage / 100.0
         *  - otherwise                                 → amount (full ownership)
         *
         * Use [EFFECTIVE_AMOUNT_SQL] when the expenses table is referenced without an alias.
         * Use [EFFECTIVE_AMOUNT_E_SQL] when the expenses table is aliased as `e` in the query.
         *
         * These constants are inlined at compile-time (Kotlin const val) and therefore safe
         * to reference from [androidx.room.RawQuery] dynamic SQL builders.  Room-annotated
         * [@Query] strings must still be literal — see individual query doc comments for the
         * canonical source reference.
         */
        const val EFFECTIVE_AMOUNT_SQL: String =
            "CASE WHEN isNotMine = 1 THEN 0.0 " +
            "WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount " +
            "WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0 " +
            "ELSE amount END"

        /** Same formula with `e.` table alias prefix, for queries that alias expenses as `e`. */
        const val EFFECTIVE_AMOUNT_E_SQL: String =
            "CASE WHEN e.isNotMine = 1 THEN 0.0 " +
            "WHEN e.isSharedExpense = 1 AND e.myShareAmount IS NOT NULL THEN e.myShareAmount " +
            "WHEN e.isSharedExpense = 1 AND e.mySharePercentage IS NOT NULL THEN e.amount * e.mySharePercentage / 100.0 " +
            "ELSE e.amount END"
    }
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: Long): Expense?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(expense: Expense): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAtomic(expense: Expense): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(expenses: List<Expense>)

    @Query("SELECT changes()")
    suspend fun getChanges(): Int

    @Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit")
    fun getAllFlow(limit: Int): Flow<List<Expense>>

    @Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int = 100, offset: Int = 0): List<Expense>

    @Transaction
    @Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit")
    fun getAllWithCategoryFlow(limit: Int = 200): Flow<List<ExpenseWithCategory>>

    @Transaction
    @Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit OFFSET :offset")
    suspend fun getExpensesWithCategoryPaged(limit: Int, offset: Int): List<ExpenseWithCategory>

    /**
     * Dynamic query for filtering, searching, and sorting with pagination.
     * Supports: search query, date range, transaction type, category, ownership, and sorting.
     */
    @Transaction
    @RawQuery
    suspend fun getExpensesDynamic(query: SupportSQLiteQuery): List<ExpenseWithCategory>

    /**
     * Assistant-only uncapped dynamic query entry point.
     *
     * Keep paged/UI callers on [getExpensesDynamic]; this variant exists so the
     * assistant can execute exact full-read queries without inheriting UI page
     * limits from repository call sites.
     */
    @Transaction
    @RawQuery
    suspend fun getAssistantExpensesDynamic(query: SupportSQLiteQuery): List<ExpenseWithCategory>

    /**
     * Assistant-only exact-count dynamic query entry point that must share the
     * same WHERE/filter contract as [getAssistantExpensesDynamic].
     */
    @RawQuery
    suspend fun getAssistantExpenseCountDynamic(query: SupportSQLiteQuery): Int

    @Transaction
    @Query("""
        SELECT * FROM expenses 
        WHERE date >= :startMs AND date < :endMs 
        AND (:type IS NULL OR transactionType = :type)
        AND (:categoryId IS NULL OR categoryId = :categoryId)
        AND (:merchantKey IS NULL OR merchantKey = :merchantKey)
        ORDER BY date DESC
    """)
    fun getExpensesWithCategoryFilteredFlow(
        startMs: Long, 
        endMs: Long, 
        type: String?,
        categoryId: Long?, 
        merchantKey: String?
    ): Flow<List<ExpenseWithCategory>>

    @Transaction
    @Query("SELECT * FROM expenses WHERE date >= :startMs AND date < :endMs ORDER BY date DESC")
    fun getExpensesWithCategoryInPeriodFlow(startMs: Long, endMs: Long): Flow<List<ExpenseWithCategory>>

    @Deprecated("Use getAllFlow(limit) or getPage(limit, offset) to prevent OOM", ReplaceWith("getAllFlow(500)"))
    @Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit")
    suspend fun getAll(limit: Int): List<Expense>

    // ── Uncapped full-data queries (A.9) ────────────────────────────────────
    // These variants intentionally omit LIMIT so that callers with full-data
    // semantics (analytics, export, forecasting, budget) receive the complete
    // dataset.  Use only through repository methods that need completeness;
    // UI/display paths should continue using the bounded variants above.

    /**
     * Return **all** expenses ordered by date descending with no row cap.
     * Intended for repository-level exhaustive reads that must not silently
     * truncate (e.g., analytics, forecasting, cash-flow).
     */
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getAllFlowUncapped(): Flow<List<Expense>>

    fun getAllFlow(): Flow<List<Expense>> = getAllFlowUncapped()

    /**
     * Suspend variant: return **all** expenses with no row cap.
     * Used by [ExpenseRepository] internal paging / snapshot helpers.
     */
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    suspend fun getAllUncapped(): List<Expense>

    suspend fun getAll(): List<Expense> = getAllUncapped()

    /**
     * Return all expenses in a half-open date range **without** a row cap.
     * Preserves the same `isNotMine = 0` ownership filter and
     * `date DESC, id DESC` ordering as the bounded [getExpensesBetween].
     */
    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date < :endDate AND isNotMine = 0 ORDER BY date DESC, id DESC")
    suspend fun getExpensesBetweenUncapped(startDate: Long, endDate: Long): List<Expense>

    suspend fun getExpensesBetween(startDate: Long, endDate: Long): List<Expense> =
        getExpensesBetweenUncapped(startDate, endDate)

    @Query("SELECT * FROM expenses WHERE transactionType = :type AND date >= :startDate AND date < :endDate AND isNotMine = 0 ORDER BY date DESC, id DESC")
    suspend fun getExpensesByTypeBetweenUncapped(startDate: Long, endDate: Long, type: String): List<Expense>

    suspend fun getExpensesByTypeBetween(startDate: Long, endDate: Long, type: String): List<Expense> =
        getExpensesByTypeBetweenUncapped(startDate, endDate, type)

    /**
     * Reactive (Flow) variant of [getExpensesBetweenUncapped].
     * Preserves the same ownership filter and ordering as [getExpensesBetweenFlow].
     */
    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date < :endDate AND isNotMine = 0 ORDER BY date DESC")
    fun getExpensesBetweenFlowUncapped(startDate: Long, endDate: Long): Flow<List<Expense>>

    fun getExpensesBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>> =
        getExpensesBetweenFlowUncapped(startDate, endDate)

    @Query("SELECT * FROM expenses WHERE transactionType = :type AND date >= :startDate AND date < :endDate AND isNotMine = 0 ORDER BY date DESC")
    fun getExpensesByTypeBetweenFlowUncapped(startDate: Long, endDate: Long, type: String): Flow<List<Expense>>

    fun getExpensesByTypeBetweenFlow(startDate: Long, endDate: Long, type: String): Flow<List<Expense>> =
        getExpensesByTypeBetweenFlowUncapped(startDate, endDate, type)
    
    @Query("SELECT * FROM expenses WHERE date >= :since AND isNotMine = 0 ORDER BY date DESC")
    suspend fun getExpensesSince(since: Long): List<Expense>
    
    /**
     * Fetch recent expenses for a specific merchant for subscription detection.
     * Returns all purchases for the merchant since the given timestamp.
     */
    @Query("""
        SELECT * FROM expenses 
        WHERE merchant = :merchant 
        AND date >= :since 
        AND ${SPENDING_TYPE_SQL}
        AND isNotMine = 0
        ORDER BY date ASC
    """)
    suspend fun getRecentExpensesForMerchant(merchant: String, since: Long): List<Expense>

    @Transaction
    @Query("""
        SELECT * FROM expenses
        WHERE merchant = :merchant
        AND date >= :since
        AND ${SPENDING_TYPE_SQL}
        AND isNotMine = 0
        ORDER BY date ASC
    """)
    suspend fun getRecentExpensesWithCategoryForMerchant(merchant: String, since: Long): List<ExpenseWithCategory>
    
    @Query("SELECT SUM(${EFFECTIVE_AMOUNT_SQL}) FROM expenses WHERE ${SPENDING_TYPE_SQL} AND isNotMine = 0")
    fun getTotalSpentFlow(): Flow<Double?>

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(expense: Expense)

    @Query("UPDATE expenses SET categoryId = :categoryId WHERE id = :expenseId")
    suspend fun updateCategory(expenseId: Long, categoryId: Long)

    @Query("UPDATE expenses SET categoryId = :categoryId WHERE merchantKey = :merchantKey")
    suspend fun updateCategoryForMerchant(merchantKey: String, categoryId: Long)

    @Query("UPDATE expenses SET merchant = :newMerchant, merchantKey = :newMerchantKey WHERE merchantKey = :oldMerchantKey")
    suspend fun updateMerchantForMerchant(oldMerchantKey: String, newMerchant: String, newMerchantKey: String)

    @Query("UPDATE expenses SET merchant = :merchant WHERE id = :expenseId")
    suspend fun updateMerchant(expenseId: Long, merchant: String)

    @Query("UPDATE expenses SET merchant = :merchant, merchantKey = :merchantKey WHERE id = :expenseId")
    suspend fun updateMerchantAndKey(expenseId: Long, merchant: String, merchantKey: String)

    @Query("UPDATE expenses SET transactionType = :type WHERE id = :expenseId")
    suspend fun updateTransactionType(expenseId: Long, type: String)

    @Query("UPDATE expenses SET transferDirection = :direction WHERE id = :expenseId")
    suspend fun updateTransferDirection(expenseId: Long, direction: String?)

    @Query("UPDATE expenses SET transferAccountName = :name WHERE id = :expenseId")
    suspend fun updateTransferAccountName(expenseId: Long, name: String?)

    @Query("UPDATE expenses SET isNotMine = :isNotMine WHERE id = :expenseId")
    suspend fun updateIsNotMine(expenseId: Long, isNotMine: Boolean)

    @Query("UPDATE expenses SET ownerName = :name WHERE id = :expenseId")
    suspend fun updateOwnerName(expenseId: Long, name: String?)

    @Query("UPDATE expenses SET isSharedExpense = :isShared WHERE id = :expenseId")
    suspend fun updateIsSharedExpense(expenseId: Long, isShared: Boolean)

    @Query("UPDATE expenses SET sharedWithName = :name WHERE id = :expenseId")
    suspend fun updateSharedWithName(expenseId: Long, name: String?)

    @Query("UPDATE expenses SET mySharePercentage = :percentage WHERE id = :expenseId")
    suspend fun updateMySharePercentage(expenseId: Long, percentage: Int?)

    @Query("UPDATE expenses SET myShareAmount = :amount WHERE id = :expenseId")
    suspend fun updateMyShareAmount(expenseId: Long, amount: Double?)

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses
            WHERE dedupeKey = :dedupeKey
            LIMIT 1
        )
    """)
    suspend fun existsByDedupeKey(dedupeKey: String): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses
            WHERE merchantKey = :merchantKey
              AND date >= :startDate
              AND date < :endDate
              AND amount BETWEEN :minAmount AND :maxAmount
            LIMIT 1
        )
    """)
    suspend fun existsByMerchantKeyInRange(
        merchantKey: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double
    ): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses
            WHERE merchant = :merchant
              AND date >= :startDate
              AND date < :endDate
              AND amount BETWEEN :minAmount AND :maxAmount
            LIMIT 1
        )
    """)
    suspend fun existsByMerchantInRange(
        merchant: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double
    ): Boolean

    @Transaction
    suspend fun isDuplicate(
        amount: Double,
        merchant: String,
        date: Long,
        windowMs: Long = 300000,
        merchantKey: String? = null,
        dedupeKey: String? = null
    ): Boolean {
        if (!dedupeKey.isNullOrBlank() && existsByDedupeKey(dedupeKey)) {
            return true
        }

        val startDate = date - windowMs
        val endDate = date + windowMs + 1
        val minAmount = amount - 0.01
        val maxAmount = amount + 0.01

        val normalizedMerchantKey = merchantKey?.takeIf { it.isNotBlank() }
        return if (normalizedMerchantKey != null) {
            existsByMerchantKeyInRange(
                merchantKey = normalizedMerchantKey,
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount
            ) || existsByMerchantInRange(
                merchant = merchant,
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount
            )
        } else {
            existsByMerchantInRange(
                merchant = merchant,
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount
            )
        }
    }

    @Query("""
        SELECT * FROM expenses
        WHERE merchantKey = :merchantKey
          AND date >= :startDate
          AND date < :endDate
          AND amount BETWEEN :minAmount AND :maxAmount
        ORDER BY date DESC
        LIMIT 1
    """)
    suspend fun getDuplicateCandidateByMerchantKeyInRange(
        merchantKey: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double
    ): Expense?

    @Query("""
        SELECT * FROM expenses
        WHERE merchant = :merchant
          AND date >= :startDate
          AND date < :endDate
          AND amount BETWEEN :minAmount AND :maxAmount
        ORDER BY date DESC
        LIMIT 1
    """)
    suspend fun getDuplicateCandidateByMerchantInRange(
        merchant: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double
    ): Expense?

    @Transaction
    suspend fun getDuplicateCandidateForImport(
        merchantKey: String,
        merchant: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double
    ): Expense? {
        return getDuplicateCandidateByMerchantKeyInRange(
            merchantKey = merchantKey,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount
        ) ?: getDuplicateCandidateByMerchantInRange(
            merchant = merchant,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount
        )
    }
    // ── Currency-aware + type-aware duplicate-candidate queries (A.4) ──

    /**
     * Check existence of a matching expense by **merchantKey** within a time/amount
     * range, restricted to the given currency and compatible transaction type.
     *
     * Type compatibility: if [transactionType] is `'UNKNOWN'`, any type matches;
     * otherwise the existing row must be `UNKNOWN` or equal to [transactionType].
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses
            WHERE merchantKey = :merchantKey
              AND date >= :startDate
              AND date < :endDate
              AND amount BETWEEN :minAmount AND :maxAmount
              AND UPPER(currency) = UPPER(:currency)
              AND (
                  :transactionType = 'UNKNOWN'
                  OR transactionType = 'UNKNOWN'
                  OR transactionType = :transactionType
              )
            LIMIT 1
        )
    """)
    suspend fun existsByMerchantKeyInRangeCurrencyAware(
        merchantKey: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): Boolean

    /**
     * Check existence of a matching expense by **merchantKey prefix containment**
     * within a time/amount range, restricted to the given currency and compatible
     * transaction type.
     *
     * This catches cross-source duplicates where one source includes the store
     * branch/address (e.g. "MASOUTIS RETZIKI 121" → merchantKey "masoutisretziki121")
     * and the other just has the store name (e.g. "Masoutis" → merchantKey "masoutis").
     *
     * Two directions are checked:
     * 1. Existing expense's key is a prefix of the new key (existing "masoutis" ⊂ new "masoutisretziki121")
     * 2. New key is a prefix of the existing expense's key (new "masoutis" ⊂ existing "masoutisretziki121")
     *
 * A **minimum length guard** (`LENGTH(merchantKey) >= 4`) prevents short keys
 * like "a" from spuriously matching everything.
 * The `4` mirrors [DuplicateDetectionPolicy.MIN_MERCHANT_KEY_PREFIX_LENGTH];
 * keep both in sync — Room SQL cannot reference Kotlin constants.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses
            WHERE (
                :merchantKey LIKE merchantKey || '%'
                OR merchantKey LIKE :merchantKey || '%'
            )
            AND LENGTH(merchantKey) >= 4
            AND LENGTH(:merchantKey) >= 4
            AND date >= :startDate
            AND date < :endDate
            AND amount BETWEEN :minAmount AND :maxAmount
            AND UPPER(currency) = UPPER(:currency)
            AND (
                :transactionType = 'UNKNOWN'
                OR transactionType = 'UNKNOWN'
                OR transactionType = :transactionType
            )
            LIMIT 1
        )
    """)
    suspend fun existsByMerchantKeyPrefixInRangeCurrencyAware(
        merchantKey: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): Boolean

    /**
     * Check existence of a matching expense by raw **merchant** name within a
     * time/amount range, restricted to the given currency and compatible
     * transaction type.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses
            WHERE merchant = :merchant
              AND date >= :startDate
              AND date < :endDate
              AND amount BETWEEN :minAmount AND :maxAmount
              AND UPPER(currency) = UPPER(:currency)
              AND (
                  :transactionType = 'UNKNOWN'
                  OR transactionType = 'UNKNOWN'
                  OR transactionType = :transactionType
              )
            LIMIT 1
        )
    """)
    suspend fun existsByMerchantInRangeCurrencyAware(
        merchant: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): Boolean

    /**
     * Fetch the best duplicate candidate by **merchantKey** within a time/amount
     * range, restricted to the given currency and compatible transaction type.
     */
    @Query("""
        SELECT * FROM expenses
        WHERE merchantKey = :merchantKey
          AND date >= :startDate
          AND date < :endDate
          AND amount BETWEEN :minAmount AND :maxAmount
          AND UPPER(currency) = UPPER(:currency)
          AND (
              :transactionType = 'UNKNOWN'
              OR transactionType = 'UNKNOWN'
              OR transactionType = :transactionType
          )
        ORDER BY date DESC
        LIMIT 1
    """)
    suspend fun getDuplicateCandidateByMerchantKeyInRangeCurrencyAware(
        merchantKey: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): Expense?

    /**
     * Fetch the best duplicate candidate by raw **merchant** name within a
     * time/amount range, restricted to the given currency and compatible
     * transaction type.
     */
    @Query("""
        SELECT * FROM expenses
        WHERE merchant = :merchant
          AND date >= :startDate
          AND date < :endDate
          AND amount BETWEEN :minAmount AND :maxAmount
          AND UPPER(currency) = UPPER(:currency)
          AND (
              :transactionType = 'UNKNOWN'
              OR transactionType = 'UNKNOWN'
              OR transactionType = :transactionType
          )
        ORDER BY date DESC
        LIMIT 1
    """)
    suspend fun getDuplicateCandidateByMerchantInRangeCurrencyAware(
        merchant: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): Expense?

    /**
     * Policy-aware duplicate check.
     *
     * Uses [DuplicateDetectionPolicy] constants for the default window and
     * tolerance. Filters by currency (case-insensitive) and compatible
     * transaction type.
     *
     * The check order:
     *  1. merchantKey + time/amount/currency/type
     *  2. raw merchant + time/amount/currency/type
     *
     * NOTE: The type-blind exact dedupe-key short-circuit that formerly appeared
     * here has been intentionally removed. Because the persisted [dedupeKey] does
     * NOT encode the transaction type, the same key can legitimately belong to a
     * PURCHASE and a DEPOSIT/TRANSFER with the same amount/merchant/date/currency.
     * Pre-blocking on an exact key collision would therefore reject valid distinct
     * approvals before currency and type compatibility are ever checked.
     *
     * Race-condition protection is preserved by [ExpenseDao.insertAtomic], which
     * uses [OnConflictStrategy.IGNORE] backed by the unique index on [dedupeKey].
     * A concurrent commit of the same expense between this check and the insert
     * will cause [insertAtomic] to return -1 (0 rows inserted), which the caller
     * then correctly treats as a duplicate.
     *
     * Backward compatibility with mixed old/new dedupe-key rows is maintained by
     * the merchant/amount/date/currency/type range queries below, which catch
     * legacy rows regardless of their key format.
     */
    @Transaction
    suspend fun isDuplicateCurrencyAware(
        amount: Double,
        merchant: String,
        date: Long,
        currency: String,
        transactionType: String,
        windowMs: Long = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
        merchantKey: String? = null,
        dedupeKey: String? = null
    ): Boolean {
        val startDate = date - windowMs
        val endDate = date + windowMs + 1
        val tolerance = DuplicateDetectionPolicy.AMOUNT_TOLERANCE
        val minAmount = amount - tolerance
        val maxAmount = amount + tolerance
        val normalizedCurrency = DuplicateDetectionPolicy.normalizeCurrency(currency)

        val normalizedMerchantKey = merchantKey?.takeIf { it.isNotBlank() }
        return if (normalizedMerchantKey != null) {
            existsByMerchantKeyInRangeCurrencyAware(
                merchantKey = normalizedMerchantKey,
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount,
                currency = normalizedCurrency,
                transactionType = transactionType
            ) || existsByMerchantKeyPrefixInRangeCurrencyAware(
                merchantKey = normalizedMerchantKey,
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount,
                currency = normalizedCurrency,
                transactionType = transactionType
            ) || existsByMerchantInRangeCurrencyAware(
                merchant = merchant,
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount,
                currency = normalizedCurrency,
                transactionType = transactionType
            )
        } else {
            existsByMerchantInRangeCurrencyAware(
                merchant = merchant,
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount,
                currency = normalizedCurrency,
                transactionType = transactionType
            )
        }
    }

    /**
     * Policy-aware duplicate candidate retrieval for import / review flows.
     *
     * Uses [DuplicateDetectionPolicy] for currency normalization and
     * transaction-type compatibility. Falls back from merchantKey to raw
     * merchant name, mirroring [getDuplicateCandidateForImport].
     */
    @Transaction
    suspend fun getDuplicateCandidateForImportCurrencyAware(
        merchantKey: String,
        merchant: String,
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): Expense? {
        val normalizedCurrency = DuplicateDetectionPolicy.normalizeCurrency(currency)
        return getDuplicateCandidateByMerchantKeyInRangeCurrencyAware(
            merchantKey = merchantKey,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = normalizedCurrency,
            transactionType = transactionType
        ) ?: getDuplicateCandidateByMerchantInRangeCurrencyAware(
            merchant = merchant,
            startDate = startDate,
            endDate = endDate,
            minAmount = minAmount,
            maxAmount = maxAmount,
            currency = normalizedCurrency,
            transactionType = transactionType
        )
    }

    /**
     * Fetch all duplicate candidates within a policy-derived time/amount window
     * for a given currency and compatible transaction type.
     *
     * Used by [ExpenseRepository.getDuplicateCandidatesInWindow] to serve
     * use cases and engines that need a full candidate list (not just
     * an existence check).
     */
    @Query("""
        SELECT * FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND amount BETWEEN :minAmount AND :maxAmount
          AND UPPER(currency) = UPPER(:currency)
          AND (
              :transactionType = 'UNKNOWN'
              OR transactionType = 'UNKNOWN'
              OR transactionType = :transactionType
          )
          AND isNotMine = 0
        ORDER BY date DESC
    """)
    suspend fun getDuplicateCandidatesInRange(
        startDate: Long,
        endDate: Long,
        minAmount: Double,
        maxAmount: Double,
        currency: String,
        transactionType: String
    ): List<Expense>

    @Query("""
        SELECT COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND categoryId = :categoryId 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
    """)
    suspend fun getCategorySpentInPeriod(categoryId: Long, startMs: Long, endMs: Long): Double

    @Query("""
        SELECT COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND categoryId = :categoryId 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
    """)
    fun getCategorySpentInPeriodFlow(categoryId: Long, startMs: Long, endMs: Long): Flow<Double>

    @Query("""
        SELECT categoryId, COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) AS total
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
          AND categoryId IN (:categoryIds)
          AND date >= :startMs AND date < :endMs
          AND isNotMine = 0
        GROUP BY categoryId
    """)
    suspend fun getCategorySpentTotalsInPeriod(
        categoryIds: List<Long>,
        startMs: Long,
        endMs: Long
    ): List<CategorySpentTotal>

    // === Merchant Search for Manual Entry ===
    // avgAmount uses the effective (ownership-adjusted) amount via [EFFECTIVE_AMOUNT_SQL] so that
    // the pre-fill suggestion reflects what the user actually paid (not the gross posted amount on shared rows).
    @Query("""
        WITH filtered AS (
            SELECT *
            FROM expenses
            WHERE UPPER(merchant) LIKE '%' || UPPER(:query) || '%'
              AND merchantKey IS NOT NULL
        ),
        stats AS (
            SELECT merchantKey,
                   AVG(${EFFECTIVE_AMOUNT_SQL}) AS avgAmount,
                   COUNT(*) AS txCount,
                   MAX(date) AS latestDate
            FROM filtered
            GROUP BY merchantKey
        ),
        latest AS (
            SELECT f.merchantKey,
                   f.merchant,
                   f.categoryId,
                   f.date,
                   f.id
            FROM filtered f
            JOIN stats s
              ON s.merchantKey = f.merchantKey
             AND s.latestDate = f.date
            WHERE f.id = (
                SELECT MAX(f2.id)
                FROM filtered f2
                WHERE f2.merchantKey = f.merchantKey
                  AND f2.date = f.date
            )
        )
        SELECT l.merchant AS merchant,
               l.categoryId AS categoryId,
               s.avgAmount AS avgAmount,
               s.txCount AS txCount
        FROM stats s
        JOIN latest l ON l.merchantKey = s.merchantKey
        ORDER BY s.txCount DESC,
                 s.latestDate DESC,
                 l.merchant COLLATE NOCASE ASC,
                 l.merchantKey ASC
        LIMIT 10
    """)
    suspend fun searchMerchants(query: String): List<MerchantSuggestion>

    @Query("""
        SELECT DISTINCT merchant
        FROM expenses
        ORDER BY date DESC
        LIMIT 100
    """)
    suspend fun getRecentMerchantNames(): List<String>

    // === Analytics Queries ===

    /**
     * Fetch effective (ownership-adjusted) expense amounts for percentile calculation.
     * Used by [SpendingThresholdCalculator] for adaptive high-amount detection.
     * Only includes purchases owned by the user (not "not mine" or deposits).
     * Shared-expense rows contribute myShareAmount / proportional share, not the full posted amount.
     * Sorted ascending for efficient percentile computation.
     *
     * Uses [EFFECTIVE_AMOUNT_SQL] — the canonical ownership rule.
     */
    @Query("""
        SELECT ${EFFECTIVE_AMOUNT_SQL}
        FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        ORDER BY 1 ASC
    """)
    suspend fun getAmountsForPercentileCalc(startMs: Long, endMs: Long): List<Double>

    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date < :endDate AND isNotMine = 0 ORDER BY date DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun getExpensesBetween(startDate: Long, endDate: Long, limit: Int, offset: Int = 0): List<Expense>

    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date < :endDate AND isNotMine = 0 ORDER BY date ASC, id ASC, merchant COLLATE NOCASE ASC LIMIT :limit OFFSET :offset")
    suspend fun getExpensesBetweenForExport(startDate: Long, endDate: Long, limit: Int, offset: Int = 0): List<Expense>

    @Query("SELECT COUNT(*) FROM expenses WHERE date >= :startDate AND date < :endDate AND isNotMine = 0")
    suspend fun countExpensesBetween(startDate: Long, endDate: Long): Int

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun getTotalCount(): Int

    @Query("""
        SELECT * FROM expenses
        WHERE categoryId = :categoryId
        AND date >= :startDate AND date < :endDate
        AND ${SPENDING_TYPE_SQL}
        AND isNotMine = 0
        ORDER BY date ASC
    """)
    suspend fun getExpensesByCategory(categoryId: Long, startDate: Long, endDate: Long): List<Expense>

    @Query("SELECT * FROM expenses WHERE transactionType = :type AND date >= :startDate AND date < :endDate AND isNotMine = 0 ORDER BY date DESC LIMIT :limit OFFSET :offset")
    suspend fun getExpensesByTypeBetween(startDate: Long, endDate: Long, type: String, limit: Int, offset: Int = 0): List<Expense>

    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date < :endDate AND isNotMine = 0 ORDER BY date DESC LIMIT :limit")
    fun getExpensesBetweenFlow(startDate: Long, endDate: Long, limit: Int): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE transactionType = :type AND date >= :startDate AND date < :endDate AND isNotMine = 0 ORDER BY date DESC LIMIT :limit")
    fun getExpensesByTypeBetweenFlow(startDate: Long, endDate: Long, type: String, limit: Int): Flow<List<Expense>>

    @Query("""
        SELECT SUM(${EFFECTIVE_AMOUNT_SQL}) FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND date >= :startDate AND date < :endDate
        AND isNotMine = 0
    """)
    suspend fun getTotalSpentBetween(startDate: Long, endDate: Long): Double?

    /**
     * Sum effective-amount over a half-open date range with nullable category
     * equality semantics.  Intentionally does **not** narrow by transaction
     * type — that responsibility belongs to A.10.
     *
     * Category matching:
     *  - :categoryId IS NULL  → matches only rows where categoryId IS NULL
     *  - :categoryId non-null → matches rows where categoryId = :categoryId
     *
     * Used by [SharedBudgetManager] to replace uncapped row scans (A.9 Batch 4).
     */
    @Query("""
        SELECT COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND isNotMine = 0
          AND ((:categoryId IS NULL AND categoryId IS NULL) OR categoryId = :categoryId)
    """)
    suspend fun getEffectiveSpentBetweenForCategory(
        startDate: Long,
        endDate: Long,
        categoryId: Long?
    ): Double

    // ── Aggregate helpers for A.9 downstream batches ────────────────────────
    // Later batches (budget, forecasting, shared-budget, tax, multi-currency)
    // will migrate from capped row reads to these aggregate SQL queries.

    /**
     * Total effective spend grouped by currency for a half-open date range.
     * Consumers that only need per-currency totals (multi-currency, tax,
     * shared-budget) should prefer this over fetching all rows.
     */
    @Query("""
        SELECT UPPER(currency) AS currency,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY UPPER(currency)
        ORDER BY total DESC
    """)
    suspend fun getTotalSpentBetweenByCurrency(startDate: Long, endDate: Long): List<CurrencyTotal>

    // ── A.9 Batch 5 — Grouped multi-currency aggregate helpers ────────────
    // These queries group by (dimension + UPPER(currency)) so that
    // MultiCurrencyRepository can convert per-currency sub-totals for each
    // category/merchant/month without an uncapped row scan.
    //
    // PURCHASE-filtered variants are retained for callers (analytics, budget)
    // that intentionally need PURCHASE-only semantics.
    // Type-agnostic variants (prefixed with "getAll...") are used by
    // MultiCurrencyRepository to preserve pre-A.10 semantics.

    /**
     * Effective spend grouped by categoryId + currency for a half-open date range.
     * Each row represents one (categoryId, currency) bucket.
     * **PURCHASE-only** — used by analytics / budget consumers.
     */
    @Query("""
        SELECT categoryId,
               UPPER(currency) AS currency,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY categoryId, UPPER(currency)
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsBetweenByCurrency(startDate: Long, endDate: Long): List<CategoryCurrencyTotal>

    /**
     * Effective spend grouped by merchant + currency for a half-open date range.
     * Each row represents one (merchant, currency) bucket.
     * Uses MIN(merchant) to pick a display name per merchantKey grouping,
     * consistent with [getMerchantTotalsBetween].
     * **PURCHASE-only** — used by analytics / budget consumers.
     */
    @Query("""
        SELECT MIN(merchant) AS merchant,
               UPPER(currency) AS currency,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
          AND merchantKey IS NOT NULL
        GROUP BY merchantKey, UPPER(currency)
        ORDER BY total DESC
    """)
    suspend fun getMerchantTotalsBetweenByCurrency(startDate: Long, endDate: Long): List<MerchantCurrencyTotal>

    /**
     * Effective spend grouped by monthKey + currency for a half-open date range.
     * Each row represents one (month, currency) bucket.
     * **PURCHASE-only** — used by analytics / budget consumers.
     */
    @Query("""
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') AS monthKey,
               UPPER(currency) AS currency,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY monthKey, UPPER(currency)
        ORDER BY monthKey ASC, total DESC
    """)
    suspend fun getMonthlyTotalsBetweenByCurrency(startDate: Long, endDate: Long): List<MonthlyCurrencyTotal>

    // ── A.9 Batch 5 — Type-agnostic aggregate helpers for MultiCurrencyRepository ──
    // These variants intentionally do NOT filter by transactionType, preserving
    // pre-A.10 semantics where MultiCurrencyRepository was type-agnostic.

    /**
     * Total effective spend grouped by currency for a half-open date range.
     * **Type-agnostic** — includes all transaction types (PURCHASE, DEPOSIT,
     * TRANSFER, UNKNOWN, etc.) to match the pre-A.10 contract of
     * [MultiCurrencyRepository].
     */
    @Query("""
        SELECT UPPER(currency) AS currency,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY UPPER(currency)
        ORDER BY total DESC
    """)
    suspend fun getAllSpentBetweenByCurrency(startDate: Long, endDate: Long): List<CurrencyTotal>

    /**
     * Effective spend grouped by categoryId + currency for a half-open date range.
     * **Type-agnostic** — preserves pre-A.10 semantics.  Includes rows with
     * NULL categoryId so that uncategorized expenses are not silently dropped.
     */
    @Query("""
        SELECT categoryId,
               UPPER(currency) AS currency,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY categoryId, UPPER(currency)
        ORDER BY total DESC
    """)
    suspend fun getAllCategoryTotalsBetweenByCurrency(startDate: Long, endDate: Long): List<CategoryCurrencyTotal>

    /**
     * Effective spend grouped by raw merchant name + currency for a half-open date range.
     * **Type-agnostic** — preserves pre-A.10 semantics.
     * Groups by the raw `merchant` column (not `merchantKey`) so that different
     * display labels are never merged into a single bucket, matching the pre-A.9
     * row-scan grouping behaviour where each distinct `expense.merchant` string
     * was its own key in the result map.
     */
    @Query("""
        SELECT merchant AS merchant,
               UPPER(currency) AS currency,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY merchant, UPPER(currency)
        ORDER BY total DESC
    """)
    suspend fun getAllMerchantTotalsBetweenByCurrency(startDate: Long, endDate: Long): List<MerchantCurrencyTotal>

    /**
     * Effective spend grouped by monthKey + currency for a half-open date range.
     * **Type-agnostic** — preserves pre-A.10 semantics.
     */
    @Query("""
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') AS monthKey,
               UPPER(currency) AS currency,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY monthKey, UPPER(currency)
        ORDER BY monthKey ASC, total DESC
    """)
    suspend fun getAllMonthlyTotalsBetweenByCurrency(startDate: Long, endDate: Long): List<MonthlyCurrencyTotal>

    /**
     * Monthly effective-amount totals across the full history (no date param).
     * Useful for forecasting/autopilot engines that need complete monthly
     * aggregates without fetching raw rows.
     */
    @Query("""
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') AS monthKey,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
          AND isNotMine = 0
        GROUP BY monthKey
        ORDER BY monthKey ASC
    """)
    suspend fun getMonthlySpendingTotals(): List<MonthlySpendingTotal>

    /**
     * Monthly effective-amount totals for a half-open date range, no category filter.
     * Used by [BudgetForecastingEngine] for budgets without a categoryId.
     * Replaces capped [getExpensesByTypeBetween] row reads with aggregate SQL (A.9).
     */
    @Query("""
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') AS monthKey,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY monthKey
        ORDER BY monthKey ASC
    """)
    suspend fun getMonthlySpendingTotalsBetween(startDate: Long, endDate: Long): List<MonthlySpendingTotal>

    /**
     * Monthly effective-amount totals for a specific category in a half-open date range.
     * Used by [BudgetForecastingEngine] for category-specific budgets.
     * Replaces raw row reads from [getExpensesByCategory] with aggregate SQL (A.9).
     */
    @Query("""
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') AS monthKey,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
          AND categoryId = :categoryId
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY monthKey
        ORDER BY monthKey ASC
    """)
    suspend fun getMonthlySpendingTotalsByCategoryBetween(categoryId: Long, startDate: Long, endDate: Long): List<MonthlySpendingTotal>

    @Query("""
        SELECT merchantKey as merchantKey, MIN(merchant) as merchant,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as total, COUNT(*) as cnt 
        FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND date >= :startDate AND date < :endDate
        AND isNotMine = 0
        AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        ORDER BY total DESC
    """)
    suspend fun getMerchantTotalsBetween(startDate: Long, endDate: Long): List<MerchantTotal>

    @Query("""
        SELECT categoryId, SUM(${EFFECTIVE_AMOUNT_SQL}) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND date >= :startDate AND date < :endDate
        AND categoryId IS NOT NULL
        AND isNotMine = 0
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsBetween(startDate: Long, endDate: Long): List<CategoryTotal>

    @Query("SELECT COUNT(*) FROM expenses WHERE ${SPENDING_TYPE_SQL} AND isNotMine = 0")
    suspend fun getPurchaseCount(): Int

    @Query("SELECT MIN(date) FROM expenses WHERE isNotMine = 0")
    suspend fun getOldestExpenseDate(): Long?

    @Query(
        """
        SELECT CAST(strftime('%Y%m%d', date/1000, 'unixepoch', 'localtime') AS INTEGER) as dayEpoch,
               MIN(date) as startDate,
               MAX(date) as endDate,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as total,
               COUNT(*) as txCount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
          AND date >= :startMs AND date < :endMs
          AND isNotMine = 0
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
        """
    )
    suspend fun getSpendingDailyTotalsBetween(startMs: Long, endMs: Long): List<DailyTotal>

    // === Tier 1 & 2 Analytics Queries ===

    // Monthly total for a specific month range
    @Query("""
        SELECT COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
    """)
    suspend fun getTotalForPeriod(startMs: Long, endMs: Long): Double

    // Count for a period
    @Query("""
        SELECT COUNT(*) FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
    """)
    suspend fun getCountForPeriod(startMs: Long, endMs: Long): Int

    // Category totals for a period
    @Query("""
        SELECT categoryId, SUM(${EFFECTIVE_AMOUNT_SQL}) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND date >= :startMs AND date < :endMs
        AND categoryId IS NOT NULL
        AND isNotMine = 0
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsForPeriod(startMs: Long, endMs: Long): List<CategoryTotal>

    // Merchant averages (merchants with 2+ transactions)
    // averageAmount, minAmount, maxAmount all use the effective (ownership-adjusted) value
    // via [EFFECTIVE_AMOUNT_SQL].
    @Query("""
        SELECT merchantKey as merchantName, 
               MIN(merchant) as displayName,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(${EFFECTIVE_AMOUNT_SQL}) as averageAmount,
               MIN(${EFFECTIVE_AMOUNT_SQL}) as minAmount,
               MAX(${EFFECTIVE_AMOUNT_SQL}) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE ${SPENDING_TYPE_SQL}
        AND isNotMine = 0
        AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        HAVING transactionCount >= 2
        ORDER BY totalAmount DESC
    """)
    suspend fun getMerchantStats(): List<MerchantStats>

    // All merchant stats (including single-transaction merchants)
    // averageAmount, minAmount, maxAmount all use the effective (ownership-adjusted) value
    // via [EFFECTIVE_AMOUNT_SQL].
    @Query("""
        SELECT merchantKey as merchantName, 
               MIN(merchant) as displayName,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(${EFFECTIVE_AMOUNT_SQL}) as averageAmount,
               MIN(${EFFECTIVE_AMOUNT_SQL}) as minAmount,
               MAX(${EFFECTIVE_AMOUNT_SQL}) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE ${SPENDING_TYPE_SQL}
        AND isNotMine = 0
        AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        ORDER BY totalAmount DESC
    """)
    suspend fun getAllMerchantStats(): List<MerchantStats>

    // Top merchants by total spending for a period
    // averageAmount, minAmount, maxAmount all use the effective (ownership-adjusted) value
    // via [EFFECTIVE_AMOUNT_SQL].
    @Query("""
        SELECT merchantKey as merchantName, 
               MIN(merchant) as displayName,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(${EFFECTIVE_AMOUNT_SQL}) as averageAmount,
               MIN(${EFFECTIVE_AMOUNT_SQL}) as minAmount,
               MAX(${EFFECTIVE_AMOUNT_SQL}) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        ORDER BY totalAmount DESC
        LIMIT :limit
    """)
    suspend fun getTopMerchantsForPeriod(startMs: Long, endMs: Long, limit: Int = 10): List<MerchantStats>

    // Largest single transaction in a period — ordered by effective (ownership-adjusted) amount
    // via [EFFECTIVE_AMOUNT_SQL].
    @Query("""
        SELECT * FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        ORDER BY ${EFFECTIVE_AMOUNT_SQL} DESC
        LIMIT 1
    """)
    suspend fun getLargestExpenseForPeriod(startMs: Long, endMs: Long): Expense?

    // Largest single transaction for a specific merchant in a period — ordered by effective amount
    // via [EFFECTIVE_AMOUNT_SQL].
    @Query("""
        SELECT * FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND date >= :startMs AND date < :endMs
        AND merchantKey = :merchantKey
        AND isNotMine = 0
        ORDER BY ${EFFECTIVE_AMOUNT_SQL} DESC
        LIMIT 1
    """)
    suspend fun getLargestExpenseForMerchant(merchantKey: String, startMs: Long, endMs: Long): Expense?

    // Get recent transactions for a merchant (for AI categorization hints)
    @Transaction
    @Query("""
        SELECT e.*, c.name as categoryName 
        FROM expenses e
        LEFT JOIN categories c ON e.categoryId = c.id
        WHERE e.merchantKey = :merchantKey
        AND ${SPENDING_TYPE_E_SQL}
        AND e.isNotMine = 0
        ORDER BY e.date DESC
        LIMIT :limit
    """)
    suspend fun getRecentTransactionsForMerchant(merchantKey: String, limit: Int = 10): List<ExpenseWithCategoryName>

    // Daily spending totals for a period (for pace calculation)
    @Query("""
        SELECT CAST(strftime('%Y%m%d', date/1000, 'unixepoch', 'localtime') AS INTEGER) as dayEpoch,
               MIN(date) as startDate, MAX(date) as endDate,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE ${SPENDING_TYPE_SQL} 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
    """)
    suspend fun getDailyTotalsForPeriod(startMs: Long, endMs: Long): List<DailyTotal>

    // Recurring candidates: merchants that appear in multiple distinct months
    // averageAmount, minAmount, maxAmount all use the effective (ownership-adjusted) value via
    // [EFFECTIVE_AMOUNT_SQL] so that the variance check (maxAmount - minAmount < averageAmount * 0.15)
    // reflects user-owned share.
    @Query("""
        SELECT merchantKey as merchantName,
               MIN(merchant) as displayName,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(${EFFECTIVE_AMOUNT_SQL}) as averageAmount,
               MIN(${EFFECTIVE_AMOUNT_SQL}) as minAmount,
               MAX(${EFFECTIVE_AMOUNT_SQL}) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE ${SPENDING_TYPE_SQL}
        AND isNotMine = 0
        AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        HAVING transactionCount >= 2 
        AND (maxAmount - minAmount) < (averageAmount * 0.15)
        ORDER BY transactionCount DESC
    """)
    suspend fun getRecurringCandidates(): List<MerchantStats>

    // Day-of-week spending pattern
    // avgAmount uses the effective (ownership-adjusted) amount via [EFFECTIVE_AMOUNT_SQL].
    @Query("""
        SELECT 
            ((CAST(strftime('%w', date/1000, 'unixepoch', 'localtime') AS INTEGER) + 6) % 7) as dayOfWeek,
            SUM(${EFFECTIVE_AMOUNT_SQL}) as total,
            COUNT(*) as txCount,
            AVG(${EFFECTIVE_AMOUNT_SQL}) as avgAmount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY dayOfWeek
        ORDER BY dayOfWeek ASC
    """)
    suspend fun getDayOfWeekPattern(startMs: Long, endMs: Long): List<DayOfWeekTotal>

    // === Deposit/Income Queries ===

    @Query("SELECT * FROM expenses WHERE transactionType = 'DEPOSIT' AND date >= :startDate AND date < :endDate AND isNotMine = 0 ORDER BY date DESC")
    suspend fun getDepositsBetween(startDate: Long, endDate: Long): List<Expense>

    @Query("SELECT * FROM expenses WHERE transactionType = 'DEPOSIT' AND date >= :startDate AND date < :endDate AND isNotMine = 0 ORDER BY date DESC")
    fun getDepositsBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>>

    @Query("SELECT COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) FROM expenses WHERE transactionType = 'DEPOSIT' AND date >= :startMs AND date < :endMs AND isNotMine = 0")
    suspend fun getTotalDepositsForPeriod(startMs: Long, endMs: Long): Double

    @Query("""
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') as month, 
               SUM(${EFFECTIVE_AMOUNT_SQL}) as total, COUNT(*) as count
        FROM expenses 
        WHERE transactionType = 'DEPOSIT'
        AND isNotMine = 0
        GROUP BY month
        ORDER BY month DESC
        LIMIT 12
    """)
    suspend fun getMonthlyDeposits(): List<MonthlyDepositTotal>

    @Query("SELECT COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) FROM expenses WHERE transactionType = 'DEPOSIT' AND isNotMine = 0")
    suspend fun getTotalDeposits(): Double

    // ── Location queries (v28) ────────────────────────────────────────────────

    /**
     * All expenses that have been resolved to coordinates, as a reactive Flow.
     * The ViewModel collects this so the map auto-updates whenever the DB changes.
     */
    @Query("SELECT * FROM expenses WHERE latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY date DESC")
    fun getLocatedExpensesFlow(): Flow<List<Expense>>

    /** Suspend version for one-shot full reads (e.g., analytics). */
    @Query("SELECT * FROM expenses WHERE latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY date DESC")
    suspend fun getLocatedExpenses(): List<Expense>

    /** Explicitly bounded batch read for map/backfill helpers. */
    @Query("SELECT * FROM expenses WHERE latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY date DESC LIMIT :limit")
    suspend fun getLocatedExpensesBatch(limit: Int): List<Expense>

    /** All expenses that still lack coordinates. */
    @Query("SELECT * FROM expenses WHERE latitude IS NULL ORDER BY date DESC LIMIT :limit")
    suspend fun getUnlocatedExpenses(limit: Int): List<Expense>

    /** Count of located expenses — used for stats display. */
    @Query("SELECT COUNT(*) FROM expenses WHERE latitude IS NOT NULL AND longitude IS NOT NULL")
    suspend fun countLocated(): Int

    /** Count of unlocated expenses — used by backfill worker and stats display. */
    @Query("SELECT COUNT(*) FROM expenses WHERE latitude IS NULL")
    suspend fun countUnlocated(): Int

    /**
     * Unlocated expenses that have not yet exceeded the max backfill attempt count.
     * Bug #23 fix: excludes permanently-unresolvable expenses so the worker
     * does not retry them indefinitely.
     */
    @Query("SELECT * FROM expenses WHERE latitude IS NULL AND backfillAttempts < :maxAttempts ORDER BY date DESC LIMIT :limit")
    suspend fun getUnlocatedExpensesForBackfill(limit: Int, maxAttempts: Int = 3): List<Expense>

    /** Increment the backfill attempt counter for an expense that could not be resolved. */
    @Query("UPDATE expenses SET backfillAttempts = backfillAttempts + 1 WHERE id = :expenseId")
    suspend fun incrementBackfillAttempts(expenseId: Long)

    /**
     * Update the location fields for a single expense.
     * Called by [LocationResolver] after a successful geocode, and by the
     * user when they correct a pin.
     * Also resets [backfillAttempts] so that if the location is later cleared,
     * the backfill worker can retry from scratch.
     */
    @Query("""
        UPDATE expenses
        SET latitude = :latitude,
            longitude = :longitude,
            locationSource = :source,
            placeId = :placeId,
            resolvedAddress = :resolvedAddress,
            backfillAttempts = 0
        WHERE id = :expenseId
    """)
    suspend fun updateLocation(
        expenseId: Long,
        latitude: Double,
        longitude: Double,
        source: String,
        placeId: String?,
        resolvedAddress: String? = null
    )

    /** Clear all location fields for an expense (e.g. user removes a pin). 
     *  Also resets backfillAttempts so the backfill worker can retry this expense. */
    @Query("""
        UPDATE expenses
        SET latitude = NULL,
            longitude = NULL,
            locationSource = NULL,
            placeId = NULL,
            resolvedAddress = NULL,
            backfillAttempts = 0
        WHERE id = :expenseId
    """)
    suspend fun clearLocation(expenseId: Long)

    /** Reactive flow of unlocated expenses — used by Map tab unlocated panel. */
    @Query("SELECT * FROM expenses WHERE latitude IS NULL ORDER BY date DESC LIMIT :limit")
    fun getUnlocatedExpensesFlow(limit: Int = 100): Flow<List<Expense>>

    /**
     * Aggregate spend by merchant for expenses that have coordinates.
     * Used by [SpendingHeatmapEngine] to weight heatmap intensity.
     */
    @Query("""
        SELECT merchantKey as merchantKey, MIN(merchant) as merchant,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as total, COUNT(*) as cnt
        FROM expenses
        WHERE latitude IS NOT NULL
          AND ${SPENDING_TYPE_SQL}
          AND isNotMine = 0
          AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        ORDER BY total DESC
    """)
    suspend fun getLocatedMerchantTotals(): List<MerchantTotal>

    /**
     * Expenses within a geographic bounding box.
     * SQLite has no native geo math so we use a lat/lon bounding box pre-filter;
     * callers can apply an exact Haversine filter if needed.
     */
    @Query("""
        SELECT * FROM expenses
        WHERE latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
          AND latitude IS NOT NULL
        ORDER BY date DESC
    """)
    suspend fun getExpensesInBoundingBox(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): List<Expense>

    @Query("""
        SELECT * FROM expenses
        WHERE latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
          AND latitude IS NOT NULL
        ORDER BY date DESC
        LIMIT :limit
    """)
    suspend fun getExpensesInBoundingBoxBatch(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double,
        limit: Int
    ): List<Expense>

    /**
     * Cluster past located expenses for a given merchant into ~5 km grid cells.
     * Uses the shared [MerchantLocationGrid] floor-based grid keys (≈ 5 km cells),
     * including correct negative-coordinate handling.
     * Returns clusters ordered by count DESC so the caller can bias toward the
     * most-common area.
     *
     * NOTE: This grid size (0.045 deg ≈ 5 km) is intentionally much coarser than
     * [LocationInsightsEngine.CLUSTER_RADIUS_DEG] (0.0015 deg ≈ 167 m).
     * - Here we group at city-district scale to find the *area* a merchant is
     *   most often visited in, for biasing geocoding searches.
     * - [LocationInsightsEngine] uses fine-grained clusters to show distinct
     *   map pins for branches of the same chain within a neighbourhood.
     */
    @Query("""
        SELECT
            AVG(latitude)  AS centerLat,
            AVG(longitude) AS centerLon,
            COUNT(*)       AS count
        FROM expenses
        WHERE merchantKey = :merchantKey
          AND latitude IS NOT NULL
          AND longitude IS NOT NULL
        GROUP BY ${MerchantLocationGrid.LATITUDE_BUCKET_SQL}, ${MerchantLocationGrid.LONGITUDE_BUCKET_SQL}
        ORDER BY count DESC
        LIMIT 5
    """)
    suspend fun getMerchantLocationClusters(merchantKey: String): List<LocationCluster>

    // -------------------------------------------------------------------------
    // Merchant key backfill (v32)
    // -------------------------------------------------------------------------

    /**
     * Fetch a batch of expenses whose [merchantKey] has not yet been populated.
     * Used by [com.yourname.expensetracker.data.location.MerchantKeyBackfillWorker].
     */
    @Query("SELECT * FROM expenses WHERE merchantKey IS NULL LIMIT :limit")
    suspend fun getExpensesWithNullMerchantKey(limit: Int): List<Expense>

    /**
     * Write the computed canonical key back for a single expense row.
     */
    @Query("UPDATE expenses SET merchantKey = :merchantKey WHERE id = :expenseId")
    suspend fun updateMerchantKey(expenseId: Long, merchantKey: String)

    // === Monthly/Weekly Totals Dashboard Queries ===

    @Query("""
        SELECT date(date/1000, 'unixepoch', 'localtime', '-6 days', 'weekday 1') as weekKey,
               MIN(date) as startDate,
               MAX(date) as endDate,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as total,
               COUNT(*) as txCount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY weekKey
        ORDER BY weekKey ASC
    """)
    suspend fun getWeeklyTotalsForPeriod(startMs: Long, endMs: Long): List<WeeklyTotal>

    @Query("""
        SELECT strftime('%Y-%m', date/1000, 'unixepoch', 'localtime') as monthKey,
               MIN(date) as startDate,
               MAX(date) as endDate,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as total,
               COUNT(*) as txCount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY monthKey
        ORDER BY monthKey ASC
    """)
    suspend fun getMonthlyTotalsForPeriod(startMs: Long, endMs: Long): List<MonthlyTotal>

    @Query("""
        SELECT CAST(strftime('%Y%m%d', date/1000, 'unixepoch', 'localtime') AS INTEGER) as dayEpoch,
               MIN(date) as startDate,
               MAX(date) as endDate,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as total,
               COUNT(*) as txCount
        FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
    """)
    suspend fun getDailyTotalsWithDatesForPeriod(startMs: Long, endMs: Long): List<DailyTotal>

    @Query("""
        SELECT AVG(daily_total) FROM (
            SELECT SUM(${EFFECTIVE_AMOUNT_SQL}) as daily_total
            FROM expenses
            WHERE ${SPENDING_TYPE_SQL}
            AND date >= :startMs AND date < :endMs
            AND isNotMine = 0
            GROUP BY strftime('%Y-%m-%d', date/1000, 'unixepoch', 'localtime')
        )
    """)
    suspend fun getAverageDailySpend(startMs: Long, endMs: Long): Double?

    @Query("""
        SELECT c.id, c.name, c.icon, c.color,
               SUM(${EFFECTIVE_AMOUNT_E_SQL}) as total,
               COUNT(*) as txCount
        FROM expenses e
        LEFT JOIN categories c ON e.categoryId = c.id
        WHERE ${SPENDING_TYPE_E_SQL}
        AND e.date >= :startMs AND e.date < :endMs
        AND e.isNotMine = 0
        GROUP BY c.id
        ORDER BY total DESC
        LIMIT 10
    """)
    suspend fun getCategoryBreakdown(startMs: Long, endMs: Long): List<CategoryTotalResult>

    // === Business Expense Queries (v41) ===
    
    @Query("""
        SELECT * FROM expenses 
        WHERE isBusinessExpense = 1 
        AND ${SPENDING_TYPE_SQL}
        AND date >= :startDate AND date < :endDate 
        ORDER BY date DESC
    """)
    suspend fun getBusinessExpensesBetween(startDate: Long, endDate: Long): List<Expense>
    
    @Query("""
        SELECT * FROM expenses 
        WHERE isBusinessExpense = 1 
        AND ${SPENDING_TYPE_SQL}
        AND date >= :startDate AND date < :endDate 
        ORDER BY date DESC
    """)
    fun getBusinessExpensesBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>>
    
    @Query("""
        SELECT COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) FROM expenses 
        WHERE isBusinessExpense = 1 
        AND ${SPENDING_TYPE_SQL}
        AND date >= :startDate AND date < :endDate
    """)
    suspend fun getTotalBusinessExpensesBetween(startDate: Long, endDate: Long): Double?
    
    @Query("""
        SELECT businessCategory,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as total,
               COUNT(*) as count 
        FROM expenses 
        WHERE isBusinessExpense = 1 
        AND ${SPENDING_TYPE_SQL}
        AND date >= :startDate AND date < :endDate
        AND businessCategory IS NOT NULL
        GROUP BY businessCategory
        ORDER BY total DESC
    """)
    suspend fun getBusinessExpensesByCategory(startDate: Long, endDate: Long): List<BusinessCategoryTotal>
    
    @Query("""
        SELECT businessProject,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as total,
               COUNT(*) as count 
        FROM expenses 
        WHERE isBusinessExpense = 1 
        AND ${SPENDING_TYPE_SQL}
        AND date >= :startDate AND date < :endDate
        AND businessProject IS NOT NULL
        GROUP BY businessProject
        ORDER BY total DESC
    """)
    suspend fun getBusinessExpensesByProject(startDate: Long, endDate: Long): List<BusinessProjectTotal>
    
    /**
     * Business expenses that require a receipt but have none attached.
     *
     * Receipt linkage is tracked via `scanned_receipts.expenseId`, NOT via
     * `expenses.rawNotificationId` (which records the originating notification,
     * not receipt attachment).  A LEFT JOIN / IS NULL anti-join correctly
     * identifies expenses with no linked receipt row.
     */
    @Query("""
        SELECT e.* FROM expenses e
        LEFT JOIN scanned_receipts sr ON sr.expenseId = e.id
        WHERE e.isBusinessExpense = 1 
        AND ${SPENDING_TYPE_E_SQL}
        AND e.requiresReceipt = 1 
        AND sr.id IS NULL
        AND e.date >= :startDate AND e.date < :endDate
        ORDER BY e.date DESC
    """)
    suspend fun getBusinessExpensesMissingReceipts(startDate: Long, endDate: Long): List<Expense>
}

data class MerchantSuggestion(
    val merchant: String,
    val categoryId: Long?,
    val avgAmount: Double,
    val txCount: Int
)

data class MerchantTotal(
    val merchantKey: String,  // canonical key — used for grouping and DB lookups
    val merchant: String,     // MIN(merchant) — human-readable display name for UI
    val total: Double,
    val cnt: Int
)

data class CategoryTotal(
    val categoryId: Long,
    val total: Double,
    val txCount: Int = 0 
)

data class CategorySpentTotal(
    val categoryId: Long,
    val total: Double
)

data class MerchantStats(
    val merchantName: String,  // canonical merchantKey — used for grouping and DB lookups
    val displayName: String,   // MIN(merchant) — human-readable name for UI display
    val totalAmount: Double,
    val transactionCount: Int,
    val averageAmount: Double,
    val minAmount: Double,
    val maxAmount: Double,
    val firstDate: Long,
    val lastDate: Long
)

data class DailyTotalLegacy(
    val dayEpoch: Long,
    val total: Double,
    val txCount: Int
)

data class DayOfWeekTotal(
    val dayOfWeek: Int,
    val total: Double,
    val txCount: Int,
    val avgAmount: Double
)

data class MonthlyDepositTotal(
    val month: String,
    val total: Double,
    val count: Int
)

/** Represents a cluster of historically located expenses for the same merchant. */
data class LocationCluster(
    val centerLat: Double,
    val centerLon: Double,
    val count: Int
)

// === Monthly/Weekly Totals Dashboard Data Classes ===

data class WeeklyTotal(
    /** Canonical Monday local date key (`yyyy-MM-dd`) for the grouped week. */
    val weekKey: String,
    /** Raw DB values (repository normalizes these to canonical Monday boundaries). */
    val startDate: Long,
    val endDate: Long,
    val total: Double,
    val txCount: Int
)

data class MonthlyTotal(
    val monthKey: String,
    val startDate: Long,
    val endDate: Long,
    val total: Double,
    val txCount: Int
)

data class DailyTotal(
    val dayEpoch: Long,
    val startDate: Long,
    val endDate: Long,
    val total: Double,
    val txCount: Int
)

data class CategoryTotalResult(
    val id: Long?,
    val name: String?,
    val icon: String?,
    val color: String?,
    val total: Double,
    val txCount: Int
)

// === Business Expense Query Results (v41) ===

data class BusinessCategoryTotal(
    val businessCategory: String,
    val total: Double,
    val count: Int
)

data class BusinessProjectTotal(
    val businessProject: String,
    val total: Double,
    val count: Int
)

// === A.9 Aggregate Query Results ===

/** Per-currency spending total for a date range. */
data class CurrencyTotal(
    val currency: String,
    val total: Double,
    val txCount: Int
)

/** Monthly aggregate spending total (no date-range constraint). */
data class MonthlySpendingTotal(
    val monthKey: String,
    val total: Double,
    val txCount: Int
)

// === A.9 Batch 5 — Grouped multi-currency aggregate DTOs ===

/**
 * Category + currency grouped total.
 * Used by [MultiCurrencyRepository] to avoid uncapped row scans when
 * computing per-category totals across multiple currencies.
 */
data class CategoryCurrencyTotal(
    val categoryId: Long?,
    val currency: String,
    val total: Double,
    val txCount: Int
)

/**
 * Merchant + currency grouped total.
 * Used by [MultiCurrencyRepository] to avoid uncapped row scans when
 * computing per-merchant totals across multiple currencies.
 */
data class MerchantCurrencyTotal(
    val merchant: String,
    val currency: String,
    val total: Double,
    val txCount: Int
)

/**
 * Month + currency grouped total.
 * Used by [MultiCurrencyRepository] to avoid uncapped row scans when
 * computing per-month totals across multiple currencies.
 */
data class MonthlyCurrencyTotal(
    val monthKey: String,
    val currency: String,
    val total: Double,
    val txCount: Int
)



```


---


## Expense.kt

**Path:** `data/database/entity/Expense.kt`


```kotlin

package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = SplitTemplate::class,
            parentColumns = ["id"],
            childColumns = ["splitTemplateId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"]),
        Index(value = ["date"]),
        Index(value = ["transactionType", "date"]),
        Index(value = ["transactionType", "categoryId", "date"]),
        Index(value = ["categoryId", "date"]),
        Index(value = ["amount", "merchant", "date"]),
        Index(value = ["merchant", "date"]),
        Index(value = ["transactionType", "merchant", "date"]),
        Index(value = ["dedupeKey"], unique = true), // Atomic duplicate prevention
        Index(value = ["latitude", "longitude"]),     // Location queries (v28)
        Index(value = ["latitude", "backfillAttempts", "date"]), // Backfill queue optimization
        Index(value = ["merchantKey"]),                // Unified merchant identity key (v32)
        Index(value = ["merchantKey", "date", "amount"]), // Duplicate checks by key + time + amount
        Index(value = ["isBusinessExpense"]),        // Business expense queries (v41)
        Index(value = ["splitTemplateId"])             // FK index for split_templates (v76)
    ]
)
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val amount: Double,
    @ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",
    
    val merchant: String,
    
    val transactionType: TransactionType,
    
    val date: Long,
    
    val rawNotificationId: Long? = null,
    
    
    val categoryId: Long? = null,
    
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(defaultValue = "UNKNOWN") val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    @ColumnInfo(defaultValue = "0") val isManualEntry: Boolean = false,
    val notes: String? = null,

    val dedupeKey: String? = null,

    val transferDirection: TransferDirection? = null,
    val transferAccountName: String? = null,
    @ColumnInfo(defaultValue = "0") val isNotMine: Boolean = false,
    val ownerName: String? = null,
    @ColumnInfo(defaultValue = "0") val isSharedExpense: Boolean = false,
    val sharedWithName: String? = null,
    val mySharePercentage: Int? = null,
    val myShareAmount: Double? = null,

    // Location enrichment (v28) — nullable, resolved asynchronously
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String? = null,  // "MERCHANT_GEOCODE", "DEVICE_GPS", "USER_MANUAL", "OVERPASS_POI"
    val placeId: String? = null,         // OSM node ID for future re-lookups

    // Number of times the backfill worker has tried and failed to geocode this expense (v29).
    // Expenses that reach MAX_BACKFILL_ATTEMPTS are skipped by the worker to prevent
    // indefinite Nominatim calls for unresolvable merchants.
    @ColumnInfo(defaultValue = "0") val backfillAttempts: Int = 0,

    // Human-readable resolved address string (v30), e.g. "Σκλαβενίτης, Γλυφάδα, Αττική"
    val resolvedAddress: String? = null,

    // Canonical merchant identity key (v32) — computed by MerchantKeyGenerator.
    // Nullable on legacy rows; backfilled asynchronously by MerchantKeyBackfillWorker.
    @ColumnInfo(name = "merchantKey")
    val merchantKey: String? = null,

    // Business/Personal separation fields (v41)
    @ColumnInfo(defaultValue = "0") val isBusinessExpense: Boolean = false,
    val businessPurpose: String? = null, // e.g., "Client meeting", "Conference travel"
    val businessCategory: String? = null, // e.g., "Travel", "Meals", "Office Supplies", "Software"
    val businessProject: String? = null,  // For project-based expense tracking
    @ColumnInfo(defaultValue = "0") val requiresReceipt: Boolean = false,  // Flag for tax-deductible expenses needing receipts

    // Enhanced Split Transaction fields (v47)
    val splitTemplateId: Long? = null,  // Reference to SplitTemplate used
    val splitVisualization: String? = null  // JSON with visual split data (pie chart segments, colors, etc.)
) {
    /**
     * The amount that should be counted toward the user's own spending.
     * - If isNotMine: 0.0 (excluded entirely — someone else's charge)
     * - If isSharedExpense + myShareAmount set: the explicit per-person amount
     * - If isSharedExpense + mySharePercentage set: proportional share of the full amount
     * - Otherwise: full amount
     *
     * All calculations (totals, budgets, analytics, forecasting) must use this
     * instead of `amount` to correctly handle shared and not-mine expenses.
     */
    val effectiveAmount: Double
        get() = when {
            isNotMine -> 0.0
            isSharedExpense && myShareAmount != null -> myShareAmount
            isSharedExpense && mySharePercentage != null -> amount * mySharePercentage / 100.0
            else -> amount
        }

    val hasConflictingOwnershipFlags: Boolean
        get() = isNotMine && isSharedExpense

    fun normalizeOwnership(): Expense {
        val normalizedOwnerName = ownerName?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedSharedWithName = sharedWithName?.trim()?.takeIf { it.isNotEmpty() }

        return when {
            isNotMine -> copy(
                ownerName = normalizedOwnerName,
                isSharedExpense = false,
                sharedWithName = null,
                mySharePercentage = null,
                myShareAmount = null
            )

            isSharedExpense -> copy(
                isNotMine = false,
                ownerName = null,
                sharedWithName = normalizedSharedWithName
            )

            else -> copy(
                ownerName = null,
                sharedWithName = null,
                mySharePercentage = null,
                myShareAmount = null
            )
        }
    }
    companion object {
        /**
         * Generate a deduplication key from the core transaction fields.
         *
         * Delegates to [DuplicateDetectionPolicy.generateDedupeKey] which uses
         * [MerchantKeyGenerator] for merchant normalization, locale-invariant
         * amount formatting, and includes the normalized currency code.
         *
         * Key format: `{amount}_{merchantKey}_{dateBucket}_{currency}`
         *
         * **Currency is required** — callers must supply an explicit ISO-4217 code.
         * Omitting currency is no longer allowed on this blocking path; doing so
         * previously caused a silent EUR fallback that masked cross-currency duplicates.
         *
         * @param amount   transaction amount
         * @param merchant raw merchant display name
         * @param date     event timestamp (epoch ms)
         * @param currency ISO-4217 currency code (required; use the expense's actual currency)
         */
        fun generateDedupeKey(
            amount: Double,
            merchant: String,
            date: Long,
            currency: String
        ): String = DuplicateDetectionPolicy.generateDedupeKey(amount, merchant, date, currency)
    }
}

enum class TransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    UNKNOWN
}

enum class TransferDirection {
    INCOMING,
    OUTGOING
}

enum class PaymentMethod {
    CARD,
    CASH,
    BANK_TRANSFER,
    UNKNOWN
}


```


---


## PendingReview.kt

**Path:** `data/database/entity/PendingReview.kt`


```kotlin

package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PendingReviewStatus {
    PENDING,
    PROCESSING,
    APPROVED,
    REJECTED,
    MODIFIED,
    DUPLICATE
}

@Entity(
    tableName = "pending_reviews",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ScannedReceipt::class,
            parentColumns = ["id"],
            childColumns = ["scannedReceiptId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"], unique = true),
        Index(value = ["scannedReceiptId"]),
        Index(value = ["status"]),
        Index(value = ["status", "createdAt"]),
        Index(value = ["suggestedMerchantKey"]),
        Index(value = ["status", "suggestedMerchantKey", "suggestedDate"])
    ]
)
data class PendingReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawNotificationId: Long?,
    val scannedReceiptId: Long? = null,
    val suggestedAmount: Double,
    val suggestedCurrency: String,
    val suggestedMerchant: String,
    val suggestedMerchantKey: String? = null,
    val suggestedType: String,          // TransactionType name
    val suggestedCategoryId: Long?,
    val suggestedDate: Long? = null,    // Added in v11 to preserve parsed date
    val confidence: Float,
    val matchType: String? = null,      // How the category was determined (EXACT, CANONICAL, KEYWORD, CONTEXT, etc.)
    val explanation: String? = null,     // Human-readable explanation of how category was inferred
    val packageName: String,
    val notificationTitle: String?,
    val notificationText: String?,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "PENDING") val status: PendingReviewStatus = PendingReviewStatus.PENDING,
    // Transfer direction fields (v24)
    val suggestedDirection: String? = null,    // INCOMING, OUTGOING
    val suggestedAccountName: String? = null,  // Account name from/to
    // Location enrichment (v28) — captured at review-time if device location available
    val suggestedLatitude: Double? = null,
    val suggestedLongitude: Double? = null
)


```


---


## RawNotification.kt

**Path:** `data/database/entity/RawNotification.kt`


```kotlin

package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "raw_notifications",
    indices = [
        Index(value = ["packageName", "timestamp"]),
        Index(value = ["capturedAt"]),
        Index(value = ["isRelevant"]),
        // Room's schema contract only declares the non-unique covering index
        // below. SQLite treats NULL != NULL, so any stronger dedup guarantees
        // must be handled outside the Room-exported schema contract.
        Index(value = ["packageName", "timestamp", "title", "text"])
    ]
)
data class RawNotification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Source app
    val packageName: String,
    val appName: String?,
    
    // Notification content
    val title: String?,
    val text: String?,
    val bigText: String? = null,          // Expanded notification
    val subText: String? = null,
    
    // Raw extras as JSON string for debugging
    val extrasJson: String? = null,
    
    // Metadata
    val timestamp: Long,           // When notification was posted
    val capturedAt: Long,          // When we captured it
    
    // Processing status
    val isProcessed: Boolean = false,
    val isRelevant: Boolean? = null,  // null = unknown, true = expense, false = ignore
    val parseResult: String? = null    // JSON of parsed data or error message
)


```


---


## DuplicateDetectionPolicy.kt

**Path:** `domain/intelligence/DuplicateDetectionPolicy.kt`


```kotlin

package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import java.util.Locale

/**
 * Canonical duplicate-detection policy.
 *
 * **Single source of truth** for every blocking-duplicate decision in the app.
 * All ingestion / review / AI paths must consume these constants and helpers
 * instead of carrying their own window, tolerance, or scoring rules.
 *
 * This utility is pure domain logic — no DAO / Room / Android framework deps.
 */
object DuplicateDetectionPolicy {

    // ── Constants ────────────────────────────────────────────────────────

    /** Canonical blocking-duplicate time window (5 minutes). */
    const val DUPLICATE_WINDOW_MS: Long = 5 * 60 * 1000L // 300 000 ms

    /** Maximum amount difference that still counts as "the same charge". */
    const val AMOUNT_TOLERANCE: Double = 0.01

    /**
     * Default currency when none is provided.
     * Matches the Room column default on `Expense.currency`.
     */
    const val DEFAULT_CURRENCY: String = "EUR"

    /**
     * Minimum length a merchant key must have to participate in
     * cross-package prefix-based dedup (`LIKE merchantKey || '%'`).
     *
     * Keys shorter than this are too generic (e.g. "a", "car") and
     * would produce false positives.
     *
     * **NOTE:** This value is also hardcoded in the Room `@Query`
     * annotations of `ExpenseDao.existsByMerchantKeyPrefixInRangeCurrencyAware`
     * and `PendingReviewDao.hasPendingDuplicateByMerchantKeyPrefixInRangeTypeAware`
     * because Room SQL strings cannot reference Kotlin constants.
     * If you change this value, you MUST update the SQL `LENGTH(…) >= N`
     * literals in both DAOs.
     */
    const val MIN_MERCHANT_KEY_PREFIX_LENGTH: Int = 4

    // ── Currency normalization ───────────────────────────────────────────

    /**
     * Normalize a currency code to a deterministic canonical form
     * (uppercase, trimmed). Null / blank falls back to [DEFAULT_CURRENCY].
     */
    fun normalizeCurrency(currency: String?): String =
        currency?.trim()?.uppercase(Locale.ROOT)?.ifBlank { DEFAULT_CURRENCY }
            ?: DEFAULT_CURRENCY

    // ── Merchant normalization ───────────────────────────────────────────

    /**
     * Canonical merchant key.
     * Delegates to [MerchantKeyGenerator] — the single merchant-identity source.
     */
    fun normalizeMerchant(merchant: String): String =
        MerchantKeyGenerator.generate(merchant)

    // ── Transaction-type compatibility ───────────────────────────────────

    /**
     * Two transactions can only be considered duplicates if their types are
     * compatible. Purchases match purchases, deposits match deposits, etc.
     *
     * [TransactionType.UNKNOWN] is treated as compatible with anything so
     * that legacy rows without a type are not silently excluded.
     */
    fun areTypesCompatible(a: TransactionType, b: TransactionType): Boolean {
        if (a == TransactionType.UNKNOWN || b == TransactionType.UNKNOWN) return true
        return a == b
    }

    // ── Amounts ──────────────────────────────────────────────────────────

    /** Check whether two amounts are within the shared tolerance. */
    fun areAmountsEqual(a: Double, b: Double): Boolean =
        kotlin.math.abs(a - b) <= AMOUNT_TOLERANCE

    /**
     * Locale-invariant, two-decimal-place formatting for amounts.
     * Used by dedupe-key generation and anywhere else a stable string
     * representation of an amount is needed.
     */
    fun formatAmount(amount: Double): String =
        String.format(Locale.ROOT, "%.2f", amount)

    // ── Time window ──────────────────────────────────────────────────────

    /** Check whether two timestamps fall within the canonical window. */
    fun isWithinWindow(date1: Long, date2: Long, windowMs: Long = DUPLICATE_WINDOW_MS): Boolean =
        kotlin.math.abs(date1 - date2) <= windowMs

    /**
     * Canonical exclusive end-boundary for a duplicate-window range query.
     *
     * Both the expense duplicate check ([ExpenseDao.isDuplicateCurrencyAware]) and
     * the pending-review duplicate check ([PendingReviewDao.hasPendingDuplicateInRangeTypeAware])
     * use SQL `date < :endDate` (exclusive upper bound). To make the inclusive range
     * `[date - windowMs, date + windowMs]` correct under that convention the endDate
     * parameter must be `date + windowMs + 1`.
     *
     * Use this helper everywhere a time-window endDate is passed to a DAO query so
     * that both paths share the same off-by-one-safe boundary calculation.
     *
     * @param date     the event timestamp (epoch ms)
     * @param windowMs the half-width of the duplicate window (default [DUPLICATE_WINDOW_MS])
     * @return         exclusive upper bound for the SQL `< :endDate` predicate
     */
    fun windowEndExclusive(date: Long, windowMs: Long = DUPLICATE_WINDOW_MS): Long =
        date + windowMs + 1L

    // ── Dedupe key generation ────────────────────────────────────────────

    /**
     * Generate a deduplication key from the core transaction fields.
     *
     * Format: `{amount}_{merchantKey}_{dateBucket}_{currency}`
     *
     * Uses [MerchantKeyGenerator] for the merchant component,
     * locale-invariant amount formatting, and includes the normalized
     * currency code.
     *
     * The `dateBucket` is `date / DUPLICATE_WINDOW_MS`, giving transactions
     * that fall in the same 5-minute bucket identical keys.
     *
     * **Currency is required** — callers must supply an explicit ISO-4217 code.
     * Omitting currency is not allowed on the canonical blocking path; doing so
     * previously caused a silent EUR fallback that masked cross-currency duplicates.
     *
     * @param amount      transaction amount
     * @param merchant    raw merchant display name
     * @param date        event timestamp (epoch ms)
     * @param currency    ISO-4217 currency code (required; use the expense's actual currency)
     */
    fun generateDedupeKey(
        amount: Double,
        merchant: String,
        date: Long,
        currency: String
    ): String {
        val normalizedMerchant = normalizeMerchant(merchant)
        val roundedAmount = formatAmount(amount)
        val dateBucket = date / DUPLICATE_WINDOW_MS
        val normalizedCurrency = normalizeCurrency(currency)
        return "${roundedAmount}_${normalizedMerchant}_${dateBucket}_$normalizedCurrency"
    }

    /**
     * Generate a deduplication key that encodes the transaction type so that
     * incompatible-type rows (e.g. PURCHASE vs DEPOSIT) never collide on the
     * persisted unique index.
     *
     * Format when type is known: `{amount}_{merchantKey}_{dateBucket}_{currency}_{type}`
     * Format when type is UNKNOWN: `{amount}_{merchantKey}_{dateBucket}_{currency}`
     *   (same as [generateDedupeKey] — preserves backward-compat for UNKNOWN rows)
     *
     * Use this variant whenever inserting a new expense row that will have its
     * dedupeKey persisted in the database. The type-suffix ensures two
     * legitimate transactions that differ only in type are stored with distinct
     * keys and therefore never trigger a spurious unique-index conflict.
     *
     * **Currency is required** — callers must supply an explicit ISO-4217 code.
     * Omitting currency is not allowed on the canonical blocking path; doing so
     * previously caused a silent EUR fallback that masked cross-currency duplicates.
     *
     * @param amount          transaction amount
     * @param merchant        raw merchant display name
     * @param date            event timestamp (epoch ms)
     * @param currency        ISO-4217 currency code (required; use the expense's actual currency)
     * @param transactionType the transaction type (non-null; use UNKNOWN for legacy/unknown rows)
     */
    fun generateDedupeKeyWithType(
        amount: Double,
        merchant: String,
        date: Long,
        currency: String,
        transactionType: TransactionType
    ): String {
        val base = generateDedupeKey(amount, merchant, date, currency)
        // UNKNOWN is compatible with every type — do not append suffix so that
        // UNKNOWN-typed rows remain reachable by the type-blind key and are still
        // caught as duplicates by the range-based isDuplicateCurrencyAware check.
        return if (transactionType == TransactionType.UNKNOWN) base
        else "${base}_${transactionType.name}"
    }

    // ── Candidate scoring / tie-breaks ───────────────────────────────────

    /**
     * Data holder for a scored duplicate candidate.
     */
    data class ScoredCandidate<T>(
        val candidate: T,
        /** Absolute time delta in milliseconds. */
        val timeDeltaMs: Long,
        /** Absolute amount delta. */
        val amountDelta: Double,
        /** Merchant confidence from deterministic similarity (0..1). */
        val merchantConfidence: Float,
        /** Optional location proximity boost. */
        val locationBoost: Float = 0f
    )

    /**
     * Deterministic tie-break comparator among hard-match candidates.
     *
     * Ranking order (ascending = best):
     *  1. Smallest time delta
     *  2. Smallest amount delta
     *  3. Highest merchant confidence (inverted for ascending sort)
     */
    fun <T> rankCandidates(candidates: List<ScoredCandidate<T>>): List<ScoredCandidate<T>> =
        candidates.sortedWith(
            compareBy<ScoredCandidate<T>> { it.timeDeltaMs }
                .thenBy { it.amountDelta }
                .thenByDescending { it.merchantConfidence + it.locationBoost }
        )

    /**
     * Select the best duplicate candidate from a pre-scored list, or null if empty.
     */
    fun <T> bestCandidate(candidates: List<ScoredCandidate<T>>): T? =
        rankCandidates(candidates).firstOrNull()?.candidate

    // ── Full duplicate-eligibility check (for convenience) ───────────────

    /**
     * Convenience: checks amount, currency, type, and time-window compatibility
     * between a new transaction and an existing expense.
     *
     * Does **not** check merchant similarity — the caller should pre-filter or
     * score merchants separately (deterministic + optional AI).
     */
    fun isEligibleCandidate(
        newAmount: Double,
        newCurrency: String?,
        newType: TransactionType,
        newDate: Long,
        existing: Expense,
        windowMs: Long = DUPLICATE_WINDOW_MS
    ): Boolean {
        if (!areAmountsEqual(newAmount, existing.amount)) return false
        if (normalizeCurrency(newCurrency) != normalizeCurrency(existing.currency)) return false
        if (!areTypesCompatible(newType, existing.transactionType)) return false
        if (!isWithinWindow(newDate, existing.date, windowMs)) return false
        return true
    }
}


```


---


## ConfidenceRouter.kt

**Path:** `domain/intelligence/ConfidenceRouter.kt`


```kotlin

package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.repository.SourceStatsRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class RoutingDecision {
    AUTO_ACCEPT,    // High confidence → create expense immediately
    NEEDS_REVIEW,   // Medium confidence → add to review queue
    AUTO_REJECT     // Low confidence → silently drop
}

data class RoutingResult(
    val decision: RoutingDecision,
    val adjustedConfidence: Float,
    val reason: String
)

@Singleton
class ConfidenceRouter @Inject constructor(
    private val sourceStatsRepository: SourceStatsRepository,
    private val userCorrectionRepository: UserCorrectionRepository,
    private val classifier: TransactionClassifier,
    private val timeProvider: TimeProvider
) {
    companion object {
        const val AUTO_ACCEPT_THRESHOLD = 0.85f
        const val REVIEW_THRESHOLD = 0.50f
        const val REVIEW_CONFIDENCE_FLOOR = REVIEW_THRESHOLD
        const val CACHE_TTL = 60_000L // 1 minute
        private const val MAX_CACHE_SIZE = 1000
        
        // ML Thresholds
        private const val ML_CONFIDENT_THRESHOLD = 0.8f
        private const val ML_LIKELY_THRESHOLD = 0.3f
        
        // ML Sample sizes for weight calculation
        private const val ML_SAMPLES_MIN = 20
        private const val ML_SAMPLES_LOW = 50
        private const val ML_SAMPLES_MED = 100
        private const val ML_SAMPLES_HIGH = 200
        
        // Trust Modifiers
        private const val TRUST_MOD_SPAM = 0.1f
        private const val TRUST_MOD_HIGH = 1.1f
        private const val TRUST_MOD_NEUTRAL = 1.0f
        private const val TRUST_MOD_LOW = 0.9f
        private const val TRUST_MOD_BAD = 0.5f
        
        // Trust Score Thresholds
        private const val TRUST_SCORE_HIGH = 0.8f
        private const val TRUST_SCORE_NEUTRAL = 0.4f
        private const val TRUST_SCORE_LOW = 0.15f
        
        // Source Stats Requirement
        private const val MIN_NOTIFICATIONS_FOR_TRUST = 10
        
        // Rejection Thresholds
        private const val MERCHANT_REJECTION_THRESHOLD = 0.5f
        private const val PACKAGE_REJECTION_THRESHOLD = 0.7f
        private const val PREVIOUS_APPROVAL_BOOST = 1.2f
        private const val AUTO_REJECT_PENALTY_PACKAGE = 0.3f
        private const val UNKNOWN_MERCHANT_PENALTY = 0.5f
    }

    // Caches with timestamp: Value -> Timestamp
    private val sourceStatsCache = ConcurrentHashMap<String, Pair<SourceStats?, Long>>()
    private val merchantRejectionCache = ConcurrentHashMap<String, Pair<Float, Long>>()
    private val packageRejectionCache = ConcurrentHashMap<String, Pair<Float, Long>>()
    private val approvalCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    
    // Mutexes to prevent cache stampede (multiple coroutines refreshing same key)
    private val sourceStatsMutex = Mutex()
    private val merchantRejectionMutex = Mutex()
    private val packageRejectionMutex = Mutex()
    private val approvalMutex = Mutex()

    private fun checkCacheSize() {
        if (sourceStatsCache.size > MAX_CACHE_SIZE) sourceStatsCache.clear()
        if (merchantRejectionCache.size > MAX_CACHE_SIZE) merchantRejectionCache.clear()
        if (packageRejectionCache.size > MAX_CACHE_SIZE) packageRejectionCache.clear()
        if (approvalCache.size > MAX_CACHE_SIZE) approvalCache.clear()
    }

    fun invalidateSourceStatsCache(packageName: String) {
        sourceStatsCache.remove(packageName)
        packageRejectionCache.remove(packageName)
    }

    fun invalidateMerchantCache(merchant: String) {
        merchantRejectionCache.remove(merchant.lowercase())
        approvalCache.remove(merchant.lowercase())
    }

    fun invalidateAllCaches() {
        sourceStatsCache.clear()
        merchantRejectionCache.clear()
        packageRejectionCache.clear()
        approvalCache.clear()
    }

    suspend fun route(
        parsed: ParsedTransaction,
        packageName: String,
        notificationText: String? = null
    ): RoutingResult {
        checkCacheSize()
        var adjustedConfidence = parsed.confidence
        val reasons = mutableListOf<String>()

        // 1. ML classifier prediction (if ready and needed)
        // Skip ML if parser is extremely confident (e.g. exact template match) to save resources
        if (notificationText != null && parsed.confidence < 1.0f) {
            val mlPrediction = try {
                withTimeout(5000L) { classifier.predict(notificationText) }
            } catch (e: Exception) {
                Timber.w(e, "ML prediction timed out or failed")
                0.5f // Default to neutral on timeout
            }
            val classifierStats = classifier.getStats()

            if (classifierStats.isReady) {
                // Blend parser confidence with ML prediction
                // Weight: 60% parser, 40% ML (ML gets more weight as it trains more)
                val mlWeight = calculateMlWeight(classifierStats)
                val parserWeight = 1.0f - mlWeight

                adjustedConfidence = parsed.confidence * parserWeight + mlPrediction * mlWeight

                if (mlPrediction < ML_LIKELY_THRESHOLD) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% likely transaction")
                } else if (mlPrediction > ML_CONFIDENT_THRESHOLD) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% confident")
                }
            }
        }

        // 2-5. Adjust based on source trust, merchant history, package history, and previous approvals
        coroutineScope {
            val sourceStatsDeferred = async { getCachedSourceStats(packageName) }
            val merchantRejectionRateDeferred = async { getCachedMerchantRejectionRate(parsed.merchant) }
            val packageRejectionRateDeferred = async { getCachedPackageRejectionRate(packageName) }
            val previouslyApprovedDeferred = async { getCachedHasPreviousApprovals(parsed.merchant, packageName) }

            // 2. Adjust based on source trust score
            val sourceStats = sourceStatsDeferred.await()
            if (sourceStats != null && sourceStats.totalNotifications > MIN_NOTIFICATIONS_FOR_TRUST) {
                val trustModifier = calculateTrustModifier(sourceStats)
                adjustedConfidence *= trustModifier
                if (trustModifier < TRUST_MOD_LOW) {
                    reasons.add("Source trust: ${(sourceStats.trustScore * 100).toInt()}%")
                }
            }

            // 3. Adjust based on user correction history for this merchant
            val merchantRejectionRate = merchantRejectionRateDeferred.await()
            if (merchantRejectionRate > MERCHANT_REJECTION_THRESHOLD) {
                adjustedConfidence *= TRUST_MOD_BAD
                reasons.add("Merchant often rejected")
            }

            // 4. Package rejection rate
            val packageRejectionRate = packageRejectionRateDeferred.await()
            if (packageRejectionRate > PACKAGE_REJECTION_THRESHOLD) {
                adjustedConfidence *= AUTO_REJECT_PENALTY_PACKAGE
                reasons.add("Package mostly rejected")
            }

            // 5. Boost if user has previously approved similar transactions
            val previouslyApproved = previouslyApprovedDeferred.await()
            if (previouslyApproved) {
                adjustedConfidence = (adjustedConfidence * PREVIOUS_APPROVAL_BOOST).coerceAtMost(1.0f)
                reasons.add("Previously approved merchant")
            }
        }

    // 6. Penalty for Unknown merchant
    if (parsed.merchant.isBlank() || parsed.merchant.equals("Unknown", ignoreCase = true)) {
        val prePenaltyConfidence = adjustedConfidence
        adjustedConfidence *= UNKNOWN_MERCHANT_PENALTY

        // Only floor to REVIEW_THRESHOLD when the unknown-merchant penalty ALONE
        // caused the confidence to drop below review. If other penalties (trust,
        // package rejection, etc.) already pushed confidence below REVIEW_THRESHOLD,
        // we must not override those anti-spam signals.
        if (prePenaltyConfidence >= REVIEW_THRESHOLD && adjustedConfidence < REVIEW_THRESHOLD) {
            adjustedConfidence = REVIEW_CONFIDENCE_FLOOR
            reasons.add("Unknown merchant (review floor applied)")
        } else {
            reasons.add("Unknown merchant")
        }
    }

        // Clamp
        adjustedConfidence = adjustedConfidence.coerceIn(0f, 1f)

        // Route
        val decision = when {
            adjustedConfidence >= AUTO_ACCEPT_THRESHOLD -> RoutingDecision.AUTO_ACCEPT
            adjustedConfidence >= REVIEW_THRESHOLD -> RoutingDecision.NEEDS_REVIEW
            else -> RoutingDecision.AUTO_REJECT
        }

        val reason = if (reasons.isEmpty()) {
            "Base confidence: ${(parsed.confidence * 100).toInt()}%"
        } else {
            reasons.joinToString("; ")
        }

        return RoutingResult(decision, adjustedConfidence, reason)
    }

    /**
     * ML weight increases with more training data
     */
    private fun calculateMlWeight(stats: ClassifierStats): Float {
        val totalSamples = stats.totalPositive + stats.totalNegative
        return when {
            totalSamples < ML_SAMPLES_MIN -> 0f       // Not ready
            totalSamples < ML_SAMPLES_LOW -> 0.2f     // Low confidence in ML
            totalSamples < ML_SAMPLES_MED -> 0.35f   // Growing confidence
            totalSamples < ML_SAMPLES_HIGH -> 0.5f    // Moderate
            else -> 0.6f                   // Max capped at 60% (LOG-007)
        }
    }

    private fun calculateTrustModifier(stats: SourceStats): Float {
        return when {
            stats.isLikelySpam -> TRUST_MOD_SPAM // LOG-014: Heavy penalty for spam
            stats.trustScore > TRUST_SCORE_HIGH -> TRUST_MOD_HIGH
            stats.trustScore > TRUST_SCORE_NEUTRAL -> TRUST_MOD_NEUTRAL // 40-80% is neutral
            stats.trustScore > TRUST_SCORE_LOW -> TRUST_MOD_LOW // 15-40% is slight penalty
            else -> TRUST_MOD_BAD // < 15% is heavy penalty
        }
    }

    // === Cached Data Access ===

    private suspend fun getCachedSourceStats(packageName: String): SourceStats? {
        val now = timeProvider.now()
        val cached = sourceStatsCache[packageName]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }
        // Use mutex to prevent cache stampede - only one coroutine refreshes
        return sourceStatsMutex.withLock {
            // Double-check after acquiring lock
            val cachedNow = sourceStatsCache[packageName]
            if (cachedNow != null && now - cachedNow.second < CACHE_TTL) {
                return@withLock cachedNow.first
            }
            val stats = sourceStatsRepository.getByPackage(packageName)
            sourceStatsCache[packageName] = Pair(stats, now)
            stats
        }
    }

    private suspend fun getCachedMerchantRejectionRate(merchant: String): Float {
        val now = timeProvider.now()
        val key = merchant.lowercase()
        val cached = merchantRejectionCache[key]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }

        return merchantRejectionMutex.withLock {
            // Double-check after acquiring lock
            val cachedNow = merchantRejectionCache[key]
            if (cachedNow != null && now - cachedNow.second < CACHE_TTL) {
                return@withLock cachedNow.first
            }

            val stats = userCorrectionRepository.getMerchantStats(merchant)
            val result = if (stats.total < 3) 0f else {
                stats.rejections.toFloat() / stats.total
            }

            merchantRejectionCache[key] = Pair(result, now)
            result
        }
    }

    private suspend fun getCachedPackageRejectionRate(packageName: String): Float {
        val now = timeProvider.now()
        val cached = packageRejectionCache[packageName]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }

        return packageRejectionMutex.withLock {
            // Double-check after acquiring lock
            val cachedNow = packageRejectionCache[packageName]
            if (cachedNow != null && now - cachedNow.second < CACHE_TTL) {
                return@withLock cachedNow.first
            }

            val stats = userCorrectionRepository.getPackageStats(packageName)
            val result = if (stats.total < 5) 0f else {
                stats.rejections.toFloat() / stats.total
            }

            packageRejectionCache[packageName] = Pair(result, now)
            result
        }
    }

    private suspend fun getCachedHasPreviousApprovals(merchant: String, packageName: String): Boolean {
        val now = timeProvider.now()
        val key = "${merchant.lowercase()}|$packageName"
        val cached = approvalCache[key]
        if (cached != null && now - cached.second < CACHE_TTL) {
            return cached.first
        }

        return approvalMutex.withLock {
            // Double-check after acquiring lock
            val cachedNow = approvalCache[key]
            if (cachedNow != null && now - cachedNow.second < CACHE_TTL) {
                return@withLock cachedNow.first
            }

            val result = userCorrectionRepository.hasPreviousApprovals(merchant, packageName)
            approvalCache[key] = Pair(result, now)
            result
        }
    }

    suspend fun ensureSourceStats(packageName: String) {
        val operationTimestamp = timeProvider.now()
        // Optimistic check using cache first to avoid DB read
        val cached = sourceStatsCache[packageName]?.first
        if (cached != null) return

        val existing = sourceStatsRepository.getByPackage(packageName)
        if (existing == null) {
            sourceStatsRepository.insertIfNotExists(
                SourceStats(
                    packageName = packageName,
                    lastSeen = operationTimestamp
                )
            )
        }
        // Update cache
        sourceStatsCache[packageName] = Pair(
            existing ?: SourceStats(packageName = packageName, lastSeen = operationTimestamp),
            operationTimestamp
        )
    }
}


```


---


## TransactionClassifier.kt

**Path:** `domain/intelligence/TransactionClassifier.kt`


```kotlin

// domain/intelligence/TransactionClassifier.kt
package com.yourname.expensetracker.domain.intelligence

import android.content.Context
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import timber.log.Timber

/**
 * Lightweight on-device text classifier using Naive Bayes.
 * No TensorFlow needed. Learns from user corrections.
 */
@Singleton
open class TransactionClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userCorrectionRepository: UserCorrectionRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Single synchronization owner for all job-handle read/cancel/replace operations.
    // A plain JVM monitor is used (rather than the coroutine Mutex) because lifecycle
    // methods are non-suspend functions that must also cancel both jobs safely.
    private val jobLock = Any()
    private var saveJob: Job? = null
    private var retrainJob: Job? = null

    /**
     * Non-destructive lifecycle callback for routine app backgrounding.
     *
     * Cancels any pending save/retrain jobs without killing the parent scope,
     * so that future [scheduleSave] and [retrainFromCorrections] calls can
     * still launch new coroutines in the same process.
     */
    fun onBackground() {
        synchronized(jobLock) {
            saveJob?.cancel()
            saveJob = null
            retrainJob?.cancel()
            retrainJob = null
        }
    }

    /**
     * Permanently cancels this classifier's coroutine scope.
     *
     * After this call, no further coroutines can be launched.
     * Use only when the classifier instance is truly being disposed of
     * (e.g., in tests or when the process is being terminated).
     */
    fun destroy() {
        onBackground()
        scope.cancel()
    }

    /**
     * Legacy cleanup method. Preserved for backward compatibility.
     *
     * Calling this method permanently cancels the classifier's scope,
     * which means no future save/retrain work can be scheduled.
     * Prefer [onBackground] for routine app backgrounding and
     * [destroy] for true scope disposal.
     */
    @Deprecated(
        message = "Use onBackground() for routine backgrounding or destroy() for permanent disposal",
        replaceWith = ReplaceWith("onBackground()")
    )
    fun cleanup() {
        destroy()
    }

    companion object {
        private const val MODEL_FILE = "naive_bayes_model.json"
        private const val MODEL_VERSION = 1
        private const val MIN_TRAINING_SAMPLES = 20
        private const val LAPLACE_SMOOTHING = 1.0

        private val regexNonAlphanumeric = Regex("[^a-zα-ωά-ώ0-9€$£ ]")
        private val regexWhitespace = Regex("\\s+")
        private val regexDecimalAmount = Regex("""\d+[.,]\d{2}""")
        private val regexCurrencySymbol = Regex("""[€$£]""")
        private val regexCurrencyCode = Regex("""(?i)(EUR|USD|GBP)""")
        private val regexPaymentKeyword = Regex("""(?i)(paid|payment|purchase|charged|debit)""")
        private val regexGreekPaymentKeyword = Regex("""(?i)(πληρωμ|αγορ|χρέωσ|συναλλαγ)""")
        private val regexPromoKeyword = Regex("""(?i)(offer|discount|promo|sale|free|δωρεάν|προσφορά|έκπτωση)""")
        private val regexOtpKeyword = Regex("""(?i)(otp|code|verify|κωδικός)""")
        private val regexBalanceKeyword = Regex("""(?i)(balance|υπόλοιπο)""")
    }

    private val mutex = Mutex()
    private val positiveWordCounts = mutableMapOf<String, Int>()
    private val negativeWordCounts = mutableMapOf<String, Int>()
    private var totalPositive = 0
    private var totalNegative = 0
    private val vocabulary = mutableSetOf<String>()
    private var vocabularySize = 0

    private val positiveBigramCounts = mutableMapOf<String, Int>()
    private val negativeBigramCounts = mutableMapOf<String, Int>()

    private val _stats = MutableStateFlow(
        ClassifierStats(0, 0, 0, false)
    )
    val stats: StateFlow<ClassifierStats> = _stats.asStateFlow()

    private val isLoaded = AtomicBoolean(false)
    private var lastTrainingCount = 0

    suspend fun initialize() {
        if (isLoaded.get()) return
        mutex.withLock {
            if (!isLoaded.get()) {

                if (loadFromDisk()) {
                    isLoaded.set(true)
                    _stats.value = getStatsInternal()
                    Timber.d("Loaded ML model")
                }

                val correctionCount = userCorrectionRepository.getCount()
                if (correctionCount > lastTrainingCount && correctionCount >= MIN_TRAINING_SAMPLES) {
                    retrainFromCorrectionsInternal()
                }

                isLoaded.set(true)
            }
        }
    }

    open suspend fun predict(text: String): Float {
        if (!isLoaded.get()) initialize()

        if (totalPositive + totalNegative < MIN_TRAINING_SAMPLES) {
            return 0.5f 
        }

        val features = extractFeatures(text)
        return mutex.withLock {
            calculateProbability(features)
        }
    }

    suspend fun train(text: String, isTransaction: Boolean) {
        val newStats: ClassifierStats
        mutex.withLock {
            val features = extractFeatures(text)
            addTrainingSample(features, isTransaction)
            newStats = getStatsInternal()
            scheduleSave()
        }
        // Emit outside lock to prevent potential deadlock
        _stats.value = newStats
    }

    fun retrainFromCorrections() {
        val newJob = scope.launch {
            delay(2000) // Debounce for 2 seconds
            mutex.withLock {
                retrainFromCorrectionsInternal()
            }
        }
        synchronized(jobLock) {
            retrainJob?.cancel()
            retrainJob = newJob
        }
    }

    private suspend fun retrainFromCorrectionsInternal() {
        val corrections = userCorrectionRepository.getAll()
        if (corrections.size < MIN_TRAINING_SAMPLES) {
            Timber.d("Not enough corrections to train")
            return
        }

        positiveWordCounts.clear()
        negativeWordCounts.clear()
        positiveBigramCounts.clear()
        negativeBigramCounts.clear()
        totalPositive = 0
        totalNegative = 0

        // LOG-012 Fix: Balance dataset
        val positiveCorrections = corrections.filter { it.wasApproved }
        val negativeCorrections = corrections.filter { it.wasRejected }
        
        // Cap negatives to 3x positives to prevent skew
        val maxNegatives = (positiveCorrections.size * 3).coerceAtLeast(MIN_TRAINING_SAMPLES)
        val selectedNegatives = negativeCorrections.shuffled().take(maxNegatives)
        
        val trainingSet = positiveCorrections + selectedNegatives
        
        for (correction in trainingSet) {
            val text = buildTrainingText(correction)
            if (text.isNotBlank()) {
                val features = extractFeatures(text)
                if (correction.wasRejected) {
                    addTrainingSample(features, isTransaction = false)
                } else if (correction.wasApproved) {
                    addTrainingSample(features, isTransaction = true)
                }
            }
        }

        vocabularySize = vocabulary.size
        lastTrainingCount = corrections.size

        scheduleSave()
        Timber.d("Retrained ML model")
    }

    private fun scheduleSave() {
        val newJob = scope.launch {
            delay(2000)
            saveToDisk()
        }
        synchronized(jobLock) {
            saveJob?.cancel()
            saveJob = newJob
        }
    }

    // Internal helper to get stats without locking (caller must hold mutex)
    private fun getStatsInternal(): ClassifierStats {
        return ClassifierStats(
            totalPositive = totalPositive,
            totalNegative = totalNegative,
            vocabularySize = vocabularySize,
            isReady = totalPositive + totalNegative >= MIN_TRAINING_SAMPLES
        )
    }

    // Public suspend version that acquires lock
    open suspend fun getStats(): ClassifierStats {
        return mutex.withLock {
            getStatsInternal()
        }
    }

    private fun addTrainingSample(features: FeatureSet, isTransaction: Boolean) {
        if (isTransaction) {
            totalPositive++
            features.words.forEach {
                positiveWordCounts[it] = (positiveWordCounts[it] ?: 0) + 1
                vocabulary.add(it)
            }
            features.bigrams.forEach {
                positiveBigramCounts[it] = (positiveBigramCounts[it] ?: 0) + 1
            }
        } else {
            totalNegative++
            features.words.forEach {
                negativeWordCounts[it] = (negativeWordCounts[it] ?: 0) + 1
                vocabulary.add(it)
            }
            features.bigrams.forEach {
                negativeBigramCounts[it] = (negativeBigramCounts[it] ?: 0) + 1
            }
        }
        vocabularySize = vocabulary.size
    }

    private fun calculateProbability(features: FeatureSet): Float {
        val total = totalPositive + totalNegative
        if (total == 0) return 0.5f

        // Guard against ln(0) which returns -Infinity
        // If a class has 0 samples, we treat its prior probability as extremely low (-20.0 in log space ~= 2e-9)
        var logProbPos = if (totalPositive > 0) ln(totalPositive.toDouble() / total) else -20.0
        var logProbNeg = if (totalNegative > 0) ln(totalNegative.toDouble() / total) else -20.0

        val vocabSize = vocabularySize.coerceAtLeast(1)

        for (word in features.words) {
            val posCount = (positiveWordCounts[word] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val negCount = (negativeWordCounts[word] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val posDenom = totalPositive.toDouble() + vocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative.toDouble() + vocabSize * LAPLACE_SMOOTHING

            logProbPos += ln(posCount / posDenom)
            logProbNeg += ln(negCount / negDenom)
        }

        val bigramWeight = 0.5
        val bigramVocabSize = (positiveBigramCounts.keys + negativeBigramCounts.keys).toSet().size.coerceAtLeast(1)

        for (bigram in features.bigrams) {
            val posCount = (positiveBigramCounts[bigram] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val negCount = (negativeBigramCounts[bigram] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val posDenom = totalPositive.toDouble() + bigramVocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative.toDouble() + bigramVocabSize * LAPLACE_SMOOTHING

            logProbPos += bigramWeight * ln(posCount / posDenom)
            logProbNeg += bigramWeight * ln(negCount / negDenom)
        }

        val logOdds = logProbPos - logProbNeg
        val clampedLogOdds = logOdds.coerceIn(-20.0, 20.0)
        return (1.0 / (1.0 + Math.exp(-clampedLogOdds))).toFloat()
    }

    private fun extractFeatures(text: String): FeatureSet {
        val normalized = text.lowercase()
            .replace(regexNonAlphanumeric, " ")
            .replace(regexWhitespace, " ")
            .trim()

        val words = normalized.split(" ")
            .filter { it.length >= 2 }
            .toMutableList()

        if (regexDecimalAmount.containsMatchIn(text)) {
            words.add("__HAS_DECIMAL_AMOUNT__")
        }
        if (regexCurrencySymbol.containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_SYMBOL__")
        }
        if (regexCurrencyCode.containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_CODE__")
        }
        if (regexPaymentKeyword.containsMatchIn(text)) {
            words.add("__HAS_PAYMENT_KEYWORD__")
        }
        if (regexGreekPaymentKeyword.containsMatchIn(text)) {
            words.add("__HAS_GREEK_PAYMENT_KEYWORD__")
        }
        if (regexPromoKeyword.containsMatchIn(text)) {
            words.add("__HAS_PROMO_KEYWORD__")
        }
        if (regexOtpKeyword.containsMatchIn(text)) {
            words.add("__HAS_OTP_KEYWORD__")
        }
        if (regexBalanceKeyword.containsMatchIn(text)) {
            words.add("__HAS_BALANCE_KEYWORD__")
        }

        val actualWords = normalized.split(" ").filter { it.length >= 2 }
        val bigrams = if (actualWords.size >= 2) {
            actualWords.zipWithNext().map { (a, b) -> "${a}_$b" }
        } else {
            emptyList()
        }

        return FeatureSet(words, bigrams)
    }

    private fun buildTrainingText(
        correction: com.yourname.expensetracker.data.database.entity.UserCorrection
    ): String {
        return listOfNotNull(
            correction.notificationTitle,
            correction.notificationText,
            correction.originalMerchant
        ).joinToString(" ")
    }

    private suspend fun saveToDisk() {
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("version", MODEL_VERSION)
                    mutex.withLock {
                        put("totalPositive", totalPositive)
                        put("totalNegative", totalNegative)
                        put("vocabularySize", vocabularySize)
                        put("lastTrainingCount", lastTrainingCount)

                        put("positiveWords", JSONObject().apply {
                            positiveWordCounts.forEach { (k, v) -> put(k, v) }
                        })
                        put("negativeWords", JSONObject().apply {
                            negativeWordCounts.forEach { (k, v) -> put(k, v) }
                        })
                        put("positiveBigrams", JSONObject().apply {
                            positiveBigramCounts.forEach { (k, v) -> put(k, v) }
                        })
                        put("negativeBigrams", JSONObject().apply {
                            negativeBigramCounts.forEach { (k, v) -> put(k, v) }
                        })
                    }
                }

                File(context.filesDir, MODEL_FILE).writeText(json.toString())
            } catch (e: Exception) {
                Timber.e("Failed to save ML model")
            }
        }
    }

    private fun loadFromDisk(): Boolean {
        return try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists()) return false

            val json = JSONObject(file.readText())
            val version = json.optInt("version", 0)
            if (version != MODEL_VERSION) {
                Timber.w("ML model version mismatch")
                return false
            }

            totalPositive = json.getInt("totalPositive")
            totalNegative = json.getInt("totalNegative")
            vocabularySize = json.optInt("vocabularySize", 0)
            lastTrainingCount = json.optInt("lastTrainingCount", 0)

            val posWords = json.getJSONObject("positiveWords")
            positiveWordCounts.clear()
            posWords.keys().forEach { key ->
                val count = posWords.getInt(key)
                positiveWordCounts[key] = count
                vocabulary.add(key)
            }

            val negWords = json.getJSONObject("negativeWords")
            negativeWordCounts.clear()
            negWords.keys().forEach { key ->
                val count = negWords.getInt(key)
                negativeWordCounts[key] = count
                vocabulary.add(key)
            }

            json.optJSONObject("positiveBigrams")?.let { posBi ->
                positiveBigramCounts.clear()
                posBi.keys().forEach { key ->
                    positiveBigramCounts[key] = posBi.getInt(key)
                }
            }
            json.optJSONObject("negativeBigrams")?.let { negBi ->
                negativeBigramCounts.clear()
                negBi.keys().forEach { key ->
                    negativeBigramCounts[key] = negBi.getInt(key)
                }
            }

            vocabularySize = vocabulary.size
            true
        } catch (e: Exception) {
            Timber.e("Failed to load ML model")
            false
        }
    }
}

data class FeatureSet(
    val words: List<String>,
    val bigrams: List<String>
)

data class ClassifierStats(
    val totalPositive: Int,
    val totalNegative: Int,
    val vocabularySize: Int,
    val isReady: Boolean
)


```


---


## HybridExpenseClassifier.kt

**Path:** `domain/intelligence/ml/HybridExpenseClassifier.kt`


```kotlin

package com.yourname.expensetracker.domain.intelligence.ml

import android.content.Context
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid Expense Classifier for CATEGORIZATION.
 * Strategy priority: Merchant Dictionary -> ML prediction -> Fallback.
 * Uses CategorizationEngine as single source of truth for merchant->category mapping.
 */
@Singleton
class HybridExpenseClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val categoryRepository: CategoryRepository,
    private val categorizationEngine: CategorizationEngine,
    private val nbClassifier: ExpenseCategoryClassifier,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val TAG = "HybridClassifier"
        val RULE_CONFIDENCE = com.yourname.expensetracker.domain.util.AppConstants.Confidence.RULE_BASED
        val ML_THRESHOLD = com.yourname.expensetracker.domain.util.AppConstants.Confidence.ML_PREDICTION
    }

    private val featureExtractor = FeatureExtractor()
    private val initMutex = Mutex()
    private var initialized = false
    @Volatile
    private var categorySnapshot: List<Category> = emptyList()

    suspend fun initialize() {
        initMutex.withLock {
            if (!initialized) {
                refreshCategorySnapshot()
                initialized = true
            }
        }
    }

    suspend fun invalidateCategorySnapshot() {
        initMutex.withLock {
            categorySnapshot = categoryRepository.getAll()
            initialized = true
        }
    }

    suspend fun classify(
        merchantName: String,
        amount: Double,
        notificationTitle: String? = null,
        notificationText: String? = null,
        packageName: String = ""
    ): ClassificationResult = withContext(Dispatchers.Default) {
        
        if (!initialized) initialize()
        val categories = currentCategories()

        val merchantNormalized = merchantName.trim()
        if (merchantNormalized.isBlank() &&
            notificationTitle.isNullOrBlank() &&
            notificationText.isNullOrBlank()
        ) {
            return@withContext fallbackResult()
        }
        
        val features = featureExtractor.extractFromNotification(
            title = notificationTitle,
            text = notificationText,
            packageName = packageName,
            amount = amount,
            merchant = merchantNormalized,
            eventTimeMillis = timeProvider.now()
        )

        // 1. Merchant Dictionary (single source of truth)
        val dictionaryResult = classifyWithMerchantDictionary(merchantNormalized)
        if (dictionaryResult != null) {
            return@withContext dictionaryResult
        }

        // 2. ML Prediction — the classifier itself decides whether it has
        //    enough data (including persisted on-disk state loaded at cold start).
        //    No external isReady() gate: classify() returns an empty list when
        //    the model is not usable, preserving fallback semantics.
        try {
            val mlResults = nbClassifier.classify(features)
            if (mlResults.isNotEmpty()) {
                val best = mlResults.first()
                // Use > for strict boundary; >= ensures exactly-at-threshold is accepted
                if (best.score >= ML_THRESHOLD) {
                    val category = categories.find { it.id == best.categoryId }
                    return@withContext ClassificationResult(
                        categoryId = best.categoryId,
                        categoryName = category?.name ?: "Unknown",
                        confidence = best.score.coerceIn(0.0f, 1.0f),
                        alternatives = mlResults.take(3).map { res ->
                            res.copy(
                                categoryName = categories.find { it.id == res.categoryId }?.name ?: "Unknown",
                                score = res.score.coerceIn(0.0f, 1.0f)
                            )
                        },
                        matchType = MatchType.ML_PREDICTION
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "ML classifier failed, using fallback")
        }

        // 3. Fallback (dictionary miss, ML unavailable, or model not yet trained)
        fallbackResult()
    }

    /**
     * Uses CategorizationEngine (merchant dictionary) for categorization.
     * This is the single source of truth for merchant->category mapping.
     */
    private suspend fun classifyWithMerchantDictionary(merchantName: String): ClassificationResult? {
        val result = categorizationEngine.categorize(merchantName)
        val categories = currentCategories()
        
        if (result.categoryId != null) {
            val category = categories.find { it.id == result.categoryId }
            if (category != null) {
                return ClassificationResult(
                    categoryId = category.id,
                    categoryName = category.name,
                    confidence = result.confidence.toFloat().coerceIn(0.0f, 1.0f),
                    matchType = MatchType.RULE_MATCH
                )
            }
        }
        return null
    }

    private fun fallbackResult(): ClassificationResult {
        val categories = categorySnapshot
        val defaultCategory = categories.find { it.name.equals("Uncategorized", ignoreCase = true) }
            ?: categories.find { it.name.contains("Other", ignoreCase = true) }
            ?: categories.firstOrNull()
        return ClassificationResult(
            categoryId = defaultCategory?.id ?: -1,
            categoryName = defaultCategory?.name ?: "Uncategorized",
            confidence = 0.0f,
            matchType = MatchType.FALLBACK
        )
    }
    
    // Keep for backward compatibility during migration
    @Suppress("UnusedPrivateMember")
    private fun classifyWithRules(features: ExpenseFeatures): ClassificationResult? {
        return null // No longer used - replaced by classifyWithMerchantDictionary
    }

    suspend fun learnFromCorrection(
        merchantName: String,
        correctCategoryId: Long,
        amount: Double = 0.0,
        packageName: String = ""
    ) {
        val features = featureExtractor.extractFromNotification(
            title = null,
            text = null,
            packageName = packageName,
            amount = amount,
            merchant = merchantName,
            eventTimeMillis = timeProvider.now()
        )
        nbClassifier.train(features, correctCategoryId)
        
        // Also learn in CategorizationEngine for future dictionary lookups
        categorizationEngine.learnMerchantCategory(merchantName, correctCategoryId)
        invalidateCategorySnapshot()
    }

    private suspend fun refreshCategorySnapshot() {
        categorySnapshot = categoryRepository.getAll()
    }

    private suspend fun currentCategories(): List<Category> {
        val latest = categoryRepository.getAll()
        categorySnapshot = latest
        return latest
    }
}


```


---


## MerchantNormalizer.kt

**Path:** `domain/intelligence/ml/MerchantNormalizer.kt`


```kotlin

package com.yourname.expensetracker.domain.intelligence.ml

import android.content.Context
import timber.log.Timber
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.StringBKTree
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a merchant lookup operation
 */
data class MerchantLookupResult(
    val canonical: MerchantCanonical,
    val alias: MerchantAlias?,
    val confidence: Float,
    val matchType: MatchType
)

/**
 * Advanced Merchant Name Normalization System.
 */
@Singleton
class MerchantNormalizer @Inject constructor(
    private val repository: MerchantNormalizationRepository,
    private val merchantRules: MerchantRulesRepository,
    private val greeklishNormalizer: GreeklishNormalizer,
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val TAG = "MerchantNormalizer"
        private val COMMON_IGNORE_WORDS = listOf("THE", "A", "AN", "OF", "AND", "OR", "&")
    }

    private var bkTree: StringBKTree? = null
    private val treeMutex = Mutex()
    private var lastTreeRebuild = 0L
    private val TREE_REBUILD_INTERVAL = 300_000L // 5 minutes

    suspend fun normalize(
        rawName: String,
        autoCreate: Boolean = true,
        categoryId: Long? = null
    ): MerchantLookupResult = withContext(Dispatchers.Default) {
        if (rawName.isBlank()) {
            return@withContext createPlaceholder("Unknown", "unknown", categoryId)
        }
        
        // Input validation - prevent memory issues with extremely long names
        val sanitized = if (rawName.length > 200) {
            rawName.take(200)
        } else {
            rawName
        }
        
        val cleaned = cleanMerchantName(sanitized)
        val normalizedKey = createSearchKey(cleaned)
        
        // 1. Alias match
        repository.getAliasByNormalizedKey(normalizedKey)?.let { alias ->
            val canonical = repository.getCanonicalById(alias.canonicalId)
            if (canonical != null) {
                return@withContext MerchantLookupResult(
                    canonical = canonical,
                    alias = alias,
                    confidence = if (alias.isUserDefined) 1.0f else 0.95f,
                    matchType = if (alias.isUserDefined) MatchType.USER_DEFINED else MatchType.ALIAS_MATCH
                )
            }
        }
        
        // 2. Exact canonical match
        repository.getCanonicalBySearchKey(normalizedKey)?.let { canonical ->
            return@withContext MerchantLookupResult(
                canonical = canonical,
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.EXACT_MATCH
            )
        }
        
        // 3. Fuzzy matching - use result but don't auto-learn to avoid incorrect aliases
        val fuzzyResult = fuzzyMatch(cleaned, normalizedKey)
        if (fuzzyResult != null && fuzzyResult.confidence >= 0.95f) {
            // Only auto-learn very high confidence fuzzy matches (95%+)
            repository.linkAliasToCanonical(rawName, normalizedKey, fuzzyResult.canonical.id, isUserDefined = false, timestamp = timeProvider.now())
            return@withContext fuzzyResult
        } else if (fuzzyResult != null) {
            // Lower confidence fuzzy matches - use result but don't auto-learn
            return@withContext fuzzyResult
        }
        
        // 4. Create new
        if (autoCreate) {
            val newCanonical = createNewMerchant(cleaned, normalizedKey, categoryId)
            repository.linkAliasToCanonical(rawName, normalizedKey, newCanonical.id, isUserDefined = false, timestamp = timeProvider.now())
            invalidateTreeCache()
            
            return@withContext MerchantLookupResult(
                canonical = newCanonical,
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.NEW_MERCHANT
            )
        } else {
            return@withContext createPlaceholder(cleaned, normalizedKey, categoryId)
        }
    }

    /**
     * Learns a manual mapping from a cryptic POS name to a user-defined brand name.
     */
    suspend fun learnMerchantAlias(rawName: String, brandName: String) = withContext(Dispatchers.IO) {
        if (rawName.isBlank() || brandName.isBlank()) return@withContext
        if (rawName.equals(brandName, ignoreCase = true)) return@withContext

        // 1. Ensure the brand name exists as a canonical merchant
        val brandLookup = normalize(brandName, autoCreate = true)
        val brandId = brandLookup.canonical.id

        // 2. Repoint all aliases belonging to the old canonical merchant to the new one
        val oldSearchKey = createSearchKey(cleanMerchantName(rawName))
        val oldCanonical = repository.getCanonicalBySearchKey(oldSearchKey)
        
        if (oldCanonical != null && oldCanonical.id != brandId) {
            val aliases = repository.getAliasesForCanonical(oldCanonical.id)
            aliases.forEach { alias ->
                repository.updateAlias(alias.copy(
                    canonicalId = brandId,
                    isUserDefined = true,
                    lastUsedAt = timeProvider.now()
                ))
            }
        }

        // 3. Link the original POS name to this brand ID (just in case it wasn't a canonical)
        val rawNameKey = createSearchKey(cleanMerchantName(rawName))
        repository.linkAliasToCanonical(rawName, rawNameKey, brandId, isUserDefined = true, timestamp = timeProvider.now())
        
        Timber.i("Learned alias: $rawName -> $brandName")
        invalidateTreeCache()
    }

    fun cleanMerchantName(rawName: String): String {
        return merchantRules.cleanMerchantName(rawName)
    }
    private fun createSearchKey(name: String): String = MerchantKeyGenerator.generate(name)

    private suspend fun fuzzyMatch(cleaned: String, normalizedKey: String): MerchantLookupResult? {
        val tree = getOrBuildTree()
        val maxDist = if (normalizedKey.length < 6) 1 else 2
        
        val matches = tree.search(normalizedKey, maxDist)
        if (matches.isEmpty()) return null

        data class RankedCandidate(
            val canonical: MerchantCanonical,
            val distance: Int,
            val similarity: Float
        )

        val ranked = matches.mapNotNull { (searchKey, distance) ->
            val canonical = repository.getCanonicalBySearchKey(searchKey) ?: return@mapNotNull null
            RankedCandidate(
                canonical = canonical,
                distance = distance,
                similarity = StringDistanceUtils.jaroWinklerSimilarity(normalizedKey, searchKey).toFloat()
            )
        }.sortedWith(
            compareBy<RankedCandidate> { it.distance }
                .thenByDescending { it.similarity }
                .thenByDescending { it.canonical.totalOccurrences }
                .thenByDescending { it.canonical.isVerified }
                .thenBy { it.canonical.normalizedName.lowercase() }
                .thenBy { it.canonical.id }
        )

        val best = ranked.firstOrNull() ?: return null
        
        return MerchantLookupResult(
            canonical = best.canonical,
            alias = null,
            confidence = best.similarity,
            matchType = if (best.distance == 0) MatchType.EXACT_MATCH else MatchType.FUZZY_MATCH
        )
    }

    private val creationMutex = Mutex()

    private suspend fun createNewMerchant(cleaned: String, key: String, catId: Long?): MerchantCanonical = creationMutex.withLock {
        // Double-check existence inside the lock to prevent redundant insertion attempts
        repository.getCanonicalBySearchKey(key)?.let { return it }

        val canonical = MerchantCanonical(
            normalizedName = formatDisplayName(cleaned),
            searchKey = key,
            categoryId = catId,
            totalOccurrences = 1,
            isVerified = false
        )
        
        val id = repository.insertCanonical(canonical)
        
        if (id == -1L) {
            // Insertion failed (likely already exists), retrieve the existing ID
            return repository.getCanonicalBySearchKey(key)
                ?: throw IllegalStateException("Failed to create or retrieve merchant: $key")
        }
        
        return canonical.copy(id = id)
    }


    private fun createPlaceholder(cleaned: String, key: String, catId: Long?): MerchantLookupResult {
        return MerchantLookupResult(
            canonical = MerchantCanonical(normalizedName = cleaned, searchKey = key, categoryId = catId),
            alias = null, confidence = 0.0f, matchType = MatchType.NEW_MERCHANT
        )
    }

    private fun formatDisplayName(name: String): String {
        return name.split(" ").joinToString(" ") { word ->
            if (word.uppercase() in COMMON_IGNORE_WORDS) word.lowercase()
            else word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private suspend fun getOrBuildTree(): StringBKTree {
        return treeMutex.withLock {
            val now = timeProvider.now()
            if (bkTree == null || now - lastTreeRebuild > TREE_REBUILD_INTERVAL) {
                val tree = StringBKTree.create()
                repository.getTopMerchants(1000).forEach { tree.insert(it.searchKey) }
                bkTree = tree
                lastTreeRebuild = now
            }
            bkTree!!
        }
    }

    private suspend fun invalidateTreeCache() = treeMutex.withLock { bkTree = null }
}


```


---


## NotificationFallbackParser.kt

**Path:** `domain/ai/service/NotificationFallbackParser.kt`


```kotlin

package com.yourname.expensetracker.domain.ai.service

import com.yourname.expensetracker.domain.parser.ParsedTransaction

/**
 * Interface for AI-powered notification parsing fallback.
 * 
 * This service is invoked when all deterministic parsers fail to parse a notification.
 * It uses on-device AI (Gemini Nano via ML Kit) to extract transaction details
 * from notifications in any language or unstructured format.
 * 
 * The AI is advisory only - it returns structured data that must still pass through
 * the ConfidenceRouter for final routing decisions.
 * 
 * Implementation note: This should only use on-device AI (not cloud) for privacy
 * and latency reasons when processing notifications.
 */
interface NotificationFallbackParser {
    /**
     * Attempt to parse notification using AI when deterministic parsers fail.
     * 
     * This method should:
     * 1. Check if AI is enabled and available
     * 2. Build an appropriate prompt for the notification
     * 3. Call the on-device model
     * 4. Parse the AI response into structured data
     * 5. Return null if AI unavailable or unable to parse
     * 
     * @param title Notification title
     * @param text Notification text
     * @param bigText Expanded notification text (bigText)
     * @param packageName Source app package name
     * @return ParsedTransaction if AI successfully parsed, null otherwise
     */
    suspend fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        packageName: String
    ): ParsedTransaction?
}


```


---


## OnDeviceNotificationParser.kt

**Path:** `data/ai/provider/OnDeviceNotificationParser.kt`


```kotlin

package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.yourname.expensetracker.domain.ai.model.NotificationParseResult
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.NotificationFallbackParser
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device AI notification parser using ML Kit GenAI (Gemini Nano).
 * 
 * This is a fallback parser that activates when all deterministic parsers fail.
 * It uses on-device AI to extract transaction details from notifications in
 * any language or unstructured format.
 * 
 * Key features:
 * - Privacy-first: Processes entirely on-device (no cloud for notifications)
 * - Multilingual: Handles Greek, English, and other languages
 * - Fast: <500ms typical response time
 * - Graceful degradation: Returns null if AI unavailable
 */
@Singleton
class OnDeviceNotificationParser @Inject constructor(
    private val router: AiCapabilityRouter,
    private val settingsRepository: AiSettingsRepository
) : NotificationFallbackParser {

    @Volatile
    private var cachedModel: GenerativeModel? = null

    private fun getOrCreateModel(): GenerativeModel {
        cachedModel?.let { return it }
        return synchronized(this) {
            cachedModel ?: Generation.getClient().also { cachedModel = it }
        }
    }

    override suspend fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        packageName: String
    ): ParsedTransaction? {
        // Check if AI is enabled and on-device is available
        val settings = settingsRepository.settings().first()
        val decision = router.decide(AiCapability.NOTIFICATION_PARSE, settings)
        
        if (decision.route != com.yourname.expensetracker.domain.ai.model.AiRoute.ON_DEVICE) {
            Timber.d("OnDeviceNotificationParser: AI not available (route=${decision.route}), skipping")
            return null
        }

        // Combine notification fields
        val fullText = buildString {
            title?.let { append(it).append(" ") }
            text?.let { append(it).append(" ") }
            bigText?.let { append(it) }
        }.trim()

        if (fullText.isBlank()) {
            Timber.d("OnDeviceNotificationParser: Empty notification text, skipping")
            return null
        }

        // Truncate if too long
        val truncatedText = if (fullText.length > AppConfig.Ai.MAX_NOTIFICATION_TEXT_CHARS_FOR_AI) {
            fullText.take(AppConfig.Ai.MAX_NOTIFICATION_TEXT_CHARS_FOR_AI) + "..."
        } else {
            fullText
        }

        return try {
            val model = getOrCreateModel()
            val request = buildRequest(truncatedText, packageName)
            val response = model.generateContent(request)
            val responseText = response.candidates.firstOrNull()?.text ?: return null
            parseResponse(responseText, packageName)
        } catch (e: GenAiException) {
            Timber.w(e, "OnDeviceNotificationParser: GenAI error (code=%d)", e.errorCode)
            null
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceNotificationParser: unexpected error")
            null
        }
    }

    private fun buildRequest(notificationText: String, packageName: String): GenerateContentRequest {
        val prompt = buildPrompt(notificationText, packageName)
        val builder = GenerateContentRequest.builder(TextPart(prompt))
        builder.temperature = AppConfig.Ai.ON_DEVICE_NOTIFICATION_TEMPERATURE
        builder.maxOutputTokens = AppConfig.Ai.ON_DEVICE_NOTIFICATION_MAX_TOKENS
        return builder.build()
    }

    internal fun buildPrompt(notificationText: String, packageName: String): String {
        return buildString {
            appendLine("You are a financial transaction parser. Extract transaction details from the notification below.")
            appendLine("The notification may be in any language (Greek, English, etc.).")
            appendLine()
            appendLine("Notification source: $packageName")
            appendLine("Notification text: \"$notificationText\"")
            appendLine()
            appendLine("Extract these fields and return ONLY a JSON object:")
            appendLine("{")
            appendLine("  \"amount\": number (required, positive, no currency symbol),")
            appendLine("  \"currency\": string (ISO code: EUR, USD, GBP, etc., default EUR),")
            appendLine("  \"merchant\": string (merchant name or \"Unknown\" if unclear),")
            appendLine("  \"type\": string (one of: PURCHASE, TRANSFER, DEPOSIT, WITHDRAWAL, default PURCHASE),")
            appendLine("  \"direction\": string (one of: INCOMING, OUTGOING, or null; only set for TRANSFER or DEPOSIT, otherwise null),")
            appendLine("  \"confidence\": number (0.0-1.0, your confidence in this parsing),")
            appendLine("  \"reasoning\": string (brief explanation of how you interpreted the notification)")
            appendLine("}")
            appendLine()
            appendLine("Guidelines:")
            appendLine("- amount: Always positive number (5.0, not -5.0)")
            appendLine("- type PURCHASE: Buying something at a store/merchant")
            appendLine("- type TRANSFER: Moving money between accounts/people")
            appendLine("- type DEPOSIT: Receiving money (salary, refund, etc.)")
            appendLine("- type WITHDRAWAL: Taking cash from ATM")
            appendLine("- direction INCOMING: Use only when type is DEPOSIT or incoming TRANSFER")
            appendLine("- direction OUTGOING: Use only when type is outgoing TRANSFER")
            appendLine("- For PURCHASE or WITHDRAWAL, set direction to null")
            appendLine("- For Greek: χρεωθήκατε = charged (usually PURCHASE, direction null), πιστώθηκε = credited (DEPOSIT, direction INCOMING)")
            appendLine()
            appendLine("Example for 'χρεωθήκατε 5€ στο Σκλαβενίτη':")
            appendLine("{\"amount\":5.0,\"currency\":\"EUR\",\"merchant\":\"Σκλαβενίτης\",\"type\":\"PURCHASE\",\"direction\":null,\"confidence\":0.85,\"reasoning\":\"Greek word 'χρεωθήκατε' means 'charged', indicating a purchase\"}")
        }
    }

    internal fun parseResponse(text: String, packageName: String): ParsedTransaction? {
        val jsonText = extractFirstJsonObject(text.trim()) ?: return null
        return try {
            val json = JSONObject(jsonText)
            
            // Required fields
            val amount = json.optDouble("amount", -1.0)
            if (amount <= 0) {
                Timber.w("OnDeviceNotificationParser: Invalid or missing amount")
                return null
            }

            val currency = json.optString("currency", "EUR").uppercase()
            if (currency.length != 3) {
                Timber.w("OnDeviceNotificationParser: Invalid currency code: $currency")
                return null
            }

            val merchant = json.optString("merchant", "Unknown").ifBlank { "Unknown" }
            
            // Parse transaction type
            val typeStr = json.optString("type", "PURCHASE").uppercase()
            val transactionType = try {
                ParsedTransactionType.valueOf(typeStr)
            } catch (e: IllegalArgumentException) {
                Timber.w("OnDeviceNotificationParser: Unknown transaction type: $typeStr, defaulting to PURCHASE")
                ParsedTransactionType.PURCHASE
            }

            // Parse direction
            val directionStr = json.optString("direction", "")
            val direction = when {
                directionStr.isBlank() -> null
                directionStr.equals("INCOMING", ignoreCase = true) -> ParsedTransferDirection.INCOMING
                directionStr.equals("OUTGOING", ignoreCase = true) -> ParsedTransferDirection.OUTGOING
                else -> null
            }

            val transferDirection = when (transactionType) {
                ParsedTransactionType.TRANSFER,
                ParsedTransactionType.DEPOSIT -> direction
                else -> null
            }

            // Parse confidence
            val confidence = json.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f)

            // Log the reasoning for debugging
            val reasoning = json.optString("reasoning", "")
            if (reasoning.isNotBlank()) {
                Timber.d("OnDeviceNotificationParser: AI reasoning - $reasoning")
            }

            ParsedTransaction(
                amount = amount,
                currency = currency,
                merchant = merchant,
                type = transactionType,
                confidence = confidence,
                transferDirection = transferDirection,
                transferAccountName = transferDirection?.let {
                    when (it) {
                        ParsedTransferDirection.INCOMING -> "From: $merchant"
                        ParsedTransferDirection.OUTGOING -> "To: $merchant"
                    }
                }
            )
        } catch (e: Exception) {
            Timber.w(e, "OnDeviceNotificationParser: JSON parse failure")
            null
        }
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }
}


```


---


## NotificationCaptureService.kt

**Path:** `service/NotificationCaptureService.kt`


```kotlin

package com.yourname.expensetracker.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import timber.log.Timber
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.receiver.ServiceRestartReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject

internal fun computeNotificationContentHash(
    title: String?,
    text: String?,
    bigText: String?
): Int = (title.orEmpty() + text.orEmpty() + bigText.orEmpty()).hashCode()

internal fun resolveEffectiveBigText(
    bigText: String?,
    infoText: String?,
    summaryText: String?
): String? = bigText?.takeIf { it.isNotBlank() }
    ?: infoText?.takeIf { it.isNotBlank() }
    ?: summaryText?.takeIf { it.isNotBlank() }

internal class NotificationServiceWorkTracker {
    private val lock = Any()
    private val inFlightJobs = linkedSetOf<Job>()
    private var acceptingNewWork = true

    fun launch(scope: CoroutineScope, block: suspend () -> Unit): Job? {
        val job = synchronized(lock) {
            if (!acceptingNewWork) return null
            scope.launch { block() }.also { launched ->
                inFlightJobs += launched
            }
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                inFlightJobs.remove(job)
            }
        }
        return job
    }

    suspend fun stopAcceptingAndDrain(timeoutMs: Long): Boolean {
        synchronized(lock) {
            acceptingNewWork = false
        }
        val drained = withTimeoutOrNull<Boolean>(timeoutMs) {
            while (true) {
                val snapshot = synchronized(lock) { inFlightJobs.toList() }
                if (snapshot.isEmpty()) {
                    break
                }
                snapshot.joinAll()
            }
            true
        }
        return drained ?: false
    }
}

@AndroidEntryPoint
class NotificationCaptureService : NotificationListenerService() {

    @Inject
    lateinit var repository: NotificationRepository

    @Inject
    lateinit var timeProvider: com.yourname.expensetracker.domain.util.TimeProvider

    @Inject
    lateinit var diagnostics: com.yourname.expensetracker.domain.debug.ServiceDiagnostics

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val workTracker = NotificationServiceWorkTracker()
    
    @Volatile
    private var pendingRefresh = false
    
    @Volatile
    private var isRunning = false
    
    @Volatile
    private var isListenerConnected = false

    @Volatile
    private var isShuttingDown = false
    
    // Thread-safe, bounded deduplication cache (INS-005)
    private val processedNotifications = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 500 // Limit to 500 entries
            }
        }
    )
    private val processCount = java.util.concurrent.atomic.AtomicInteger(0)

    companion object {
        private const val TAG = "NotificationCapture"
        const val ACTION_REFRESH_NOTIFICATIONS = "com.yourname.expensetracker.REFRESH_NOTIFICATIONS"
        const val ACTION_RESTART_SERVICE = "com.yourname.expensetracker.RESTART_SERVICE"
        private const val FOREGROUND_ID = 1001
        private const val CHANNEL_ID = "expense_tracker_service"
        private const val DEDUP_WINDOW_MS = 5000L
        private const val CACHE_CLEANUP_THRESHOLD = 50
        private const val CACHE_MAX_AGE_MS = 60_000L
        private const val SHUTDOWN_DRAIN_TIMEOUT_MS = 2_000L
        private const val RESTART_INTERVAL_MS = 900_000L // Restart no more than every 15 minutes
        
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        diagnostics.recordServiceStart()
        createNotificationChannel()
        try {
            scheduleRestartAlarm()
        } catch (e: Exception) {
            Timber.e(e, "Failed to schedule restart alarm, continuing without")
        }
    }

    private fun scheduleRestartAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, ServiceRestartReceiver::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + RESTART_INTERVAL_MS,
                RESTART_INTERVAL_MS,
                pendingIntent
            )
            Timber.d("Scheduled restart alarm every ${RESTART_INTERVAL_MS}ms")
        } catch (e: Exception) {
            Timber.e(e, "Failed to schedule restart alarm")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Expense Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors transactions in background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        if (intent?.action == ACTION_REFRESH_NOTIFICATIONS) {
            Timber.d("Refresh action received")            // If already connected, refresh immediately, otherwise set flag for onListenerConnected
            if (isListenerConnected) {
                refreshActiveNotifications()
            } else {
                pendingRefresh = true
            }
        }
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerConnected = true
        Timber.d("NotificationListener connected! Starting foreground service.")
        startForegroundWithNotification()
        // Refresh active notifications after connection is established
        if (pendingRefresh) {
            pendingRefresh = false
            refreshActiveNotifications()
        }
    }
    
    private fun startForegroundWithNotification() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Expense Tracker Active")
                .setContentText("Monitoring your transactions")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .build()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // Android 14+ (API 34) requires foregroundServiceType to include "location"
                    // when reading device location from a foreground service.
                    // We combine DATA_SYNC + LOCATION so both capabilities are declared.
                    val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                    startForeground(FOREGROUND_ID, notification, serviceType)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start foreground with type DATA_SYNC|LOCATION, fallback to generic")
                    startForeground(FOREGROUND_ID, notification)
                }
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
        } catch (e: Exception) {
            Timber.e(e, "CRITICAL: Failed to start foreground service")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListenerConnected = false
        diagnostics.recordListenerDisconnected()
        Timber.w("NotificationListener disconnected - attempting rebind")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotificationCaptureService::class.java))
        }
        
        // Restart foreground service to ensure we stay alive while waiting for rebind
        if (isRunning) {
            Timber.d("Restarting foreground service after disconnect")
            startForegroundWithNotification()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName
        
        // Extract notification data — preserve nullability until fallback resolution
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        // Preserve nullability through filter evaluation; normalization happens only
        // inside the filter/hash helpers so fallback decisions remain intact.
        if (!NotificationFilter.shouldCapture(
                packageName,
                title,
                text,
                bigText
            )) return
        
        // Better deduplication using notification key + content
        // sbn.key is unique to the notification slot
        // contentHash ensures we catch updates to the same notification if content differs
        // Normalize at the hash boundary only — use orEmpty() here, not earlier
        val contentHash = computeNotificationContentHash(title, text, bigText)
        val dedupeKey = "${sbn.key}:$contentHash"
        val now = timeProvider.now()
        
        val lastProcessed = processedNotifications[dedupeKey]
        if (lastProcessed != null && (now - lastProcessed) < DEDUP_WINDOW_MS) {
            // Already processed this exact content recently
            return
        }
        
        // Update cache
        processedNotifications[dedupeKey] = now
        cleanupCacheIfNeeded()

        if (isShuttingDown) return
        workTracker.launch(serviceScope) {
            processNotification(sbn, packageName, title, text, bigText, extras)
        }
    }
    
    private fun cleanupCacheIfNeeded() {
        if (processCount.incrementAndGet() >= CACHE_CLEANUP_THRESHOLD) {
            processCount.set(0)
            val now = timeProvider.now()
            processedNotifications.entries.removeIf { 
                now - it.value > CACHE_MAX_AGE_MS 
            }
        }
    }

    private suspend fun processNotification(
        sbn: StatusBarNotification,
        packageName: String,
        title: String?,
        text: String?,
        bigText: String?,
        extras: android.os.Bundle
    ) {
        if (repository.isPackageBlocked(packageName)) {
            Timber.d("Ignoring blocked package: $packageName")
            return
        }
        
        // Extract additional useful data for banking apps (sometimes hidden here)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        
        // Combine text for robust parsing - some apps put the real info in odd places.
        // Treat blank bigText (null or whitespace-only) as absent so infoText/summaryText can win.
        val effectiveBigText = resolveEffectiveBigText(bigText, infoText, summaryText)

        val extrasJson = try {
            buildExtrasJson(extras)
        } catch (e: Exception) {
            "{\"error\": \"${e.message}\"}"
        }

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        val rawNotification = RawNotification(
            packageName = packageName,
            appName = appName,
            title = title,
            text = text,
            bigText = effectiveBigText,
            subText = subText,
            extrasJson = extrasJson,
            timestamp = sbn.postTime,
            capturedAt = timeProvider.now()
        )

        try {
            repository.processAndSave(rawNotification)
            Timber.d("Processed notification from: $packageName")
        } catch (e: Exception) {
            Timber.e(e, "Failed to process notification")
        }
    }

    private fun refreshActiveNotifications() {
        Timber.d("Manual refresh triggered")
        try {
            val activeNotifications = activeNotifications
            Timber.d("Found ${activeNotifications.size} active notifications")
            activeNotifications.forEach { sbn ->
                // Bypass deduplication cache for manual refresh
                processNotificationBypassDedupe(sbn)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error refreshing active notifications")
        }
    }

    private fun processNotificationBypassDedupe(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        // Preserve nullability — do not collapse null to empty before fallback decisions
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        if (!NotificationFilter.shouldCapture(
                packageName,
                title,
                text,
                bigText
            )) {
            Timber.d("Skipping (shouldCapture=false): $packageName")
            return
        }
        
        Timber.d("Processing notification from: $packageName, title: $title")

        if (isShuttingDown) return
        workTracker.launch(serviceScope) {
            processNotification(sbn, packageName, title, text, bigText, extras)
        }
    }

    private fun buildExtrasJson(extras: android.os.Bundle): String {
        return try {
            val json = org.json.JSONObject()
            val sensitiveKeys = setOf(
                // Android system keys that contain personal data
                "android.largeIcon", "android.picture", "android.icon",
                "android.wearable.EXTENSIONS", "android.people.list",
                // Financial/personal data keys
                "account_number", "account", "card_number", "card_last_four", 
                "balance", "amount", "cvv", "pin", "password",
                "iban", "transaction_id", "reference_number",
                "full_name", "email", "phone", "address"
            )
            for (key in extras.keySet()) {
                if (sensitiveKeys.any { key.equals(it, ignoreCase = true) }) continue
                @Suppress("DEPRECATION")
                val value = extras.get(key)
                if (value != null) {
                    val valueStr = value.toString()
                    // Basic sanity: skip extremely large strings that are likely bitmaps
                    if (valueStr.length < 2000) {
                        json.put(key, valueStr)
                    }
                }
            }
            json.toString()
        } catch (e: Exception) {
            Timber.e(e, "Failed to build extras JSON")
            return "{}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isShuttingDown = true
        cancelRestartAlarm()
        diagnostics.recordServiceKilled()
        Timber.d("Service destroyed")
        // Cancel all in-flight work without blocking the main thread.
        // Previously used runBlocking { workTracker.stopAcceptingAndDrain() } which could
        // cause ForegroundServiceDidNotStopInTimeException by blocking the main thread
        // past the system's foreground service stop timeout.
        //
        // We do not use workTracker.stopAcceptingAndDrain() here because it is a suspend
        // function that would require runBlocking (blocking main) or a separate scope
        // (whose drain would be moot since serviceJob.cancel() below cancels all child
        // coroutines anyway). isShuttingDown=true above prevents new work from being
        // accepted, and serviceJob.cancel() handles cleanup of in-flight coroutines.
        serviceJob.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // Explicitly stop self so the system knows the service is done immediately.
        stopSelf()
    }

    private fun cancelRestartAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, ServiceRestartReceiver::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            Timber.d("Cancelled restart alarm")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cancel restart alarm")
        }
    }
}


```


---

