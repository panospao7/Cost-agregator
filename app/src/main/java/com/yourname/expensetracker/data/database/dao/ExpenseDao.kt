package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
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

        /** DEPOSIT transaction type for income aggregation queries. */
        const val DEPOSIT_TYPE_SQL: String = "transactionType = 'DEPOSIT'"

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

    /**
     * Uses IGNORE to prevent silent data loss on conflict. Callers should check return value (0 = skipped).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(expenses: List<Expense>)

    /**
     * Update an existing expense row matched by primary key.
     * All columns are replaced with the values in [expense].
     */
    @Update
    suspend fun update(expense: Expense)

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
    
    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated(
        "Returns raw Double without currency conversion. Use MultiCurrencyRepository for currency-aware aggregation.",
        ReplaceWith("MultiCurrencyRepository", "com.yourname.expensetracker.data.repository.MultiCurrencyRepository")
    )
    @Query("SELECT SUM(${EFFECTIVE_AMOUNT_SQL}) FROM expenses WHERE ${SPENDING_TYPE_SQL} AND isNotMine = 0")
    fun getTotalSpentFlow(): Flow<Double?>

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(expense: Expense)

    @Query("UPDATE expenses SET categoryId = :categoryId WHERE id = :expenseId")
    suspend fun updateCategory(expenseId: Long, categoryId: Long)

    @Query("UPDATE expenses SET categoryId = :categoryId WHERE id = :expenseId")
    suspend fun updateCategoryNullable(expenseId: Long, categoryId: Long?)

    @Query("UPDATE expenses SET categoryId = :categoryId WHERE merchantKey = :merchantKey")
    suspend fun updateCategoryForMerchant(merchantKey: String, categoryId: Long)

@Query("UPDATE expenses SET merchant = :newMerchant, merchantKey = :newMerchantKey, dedupeKey = NULL WHERE merchantKey = :oldMerchantKey")
suspend fun updateMerchantForMerchant(oldMerchantKey: String, newMerchant: String, newMerchantKey: String)

    @Query("UPDATE expenses SET merchant = :merchant WHERE id = :expenseId")
    suspend fun updateMerchant(expenseId: Long, merchant: String)

@Query("UPDATE expenses SET merchant = :merchant, merchantKey = :merchantKey, dedupeKey = :dedupeKey WHERE id = :expenseId")
suspend fun updateMerchantAndKey(expenseId: Long, merchant: String, merchantKey: String, dedupeKey: String)

@Query("UPDATE expenses SET transactionType = :type, dedupeKey = :dedupeKey WHERE id = :expenseId")
suspend fun updateTransactionType(expenseId: Long, type: String, dedupeKey: String)

@Query("UPDATE expenses SET dedupeKey = :dedupeKey WHERE id = :expenseId")
suspend fun updateDedupeKey(expenseId: Long, dedupeKey: String)

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

    @Query("UPDATE expenses SET isSharedExpense = 0, myShareAmount = NULL, mySharePercentage = NULL, sharedWithName = NULL WHERE id = :expenseId")
    suspend fun clearSharedExpenseFlags(expenseId: Long)

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
 * A **minimum length guard** (`LENGTH(merchantKey) >= 8`) prevents short keys
 * like "a" or "unknown" from spuriously matching everything.
 * The `8` mirrors [DuplicateDetectionPolicy.MIN_MERCHANT_KEY_PREFIX_LENGTH];
 * keep both in sync — Room SQL cannot reference Kotlin constants.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses
            WHERE (
                :merchantKey LIKE merchantKey || '%'
                OR merchantKey LIKE :merchantKey || '%'
            )
AND LENGTH(merchantKey) >= 8
AND LENGTH(:merchantKey) >= 8
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
     * Fetch the best duplicate candidate by **merchantKey prefix containment**
     * within a time/amount range, restricted to the given currency and compatible
     * transaction type.
     *
     * Mirrors [existsByMerchantKeyPrefixInRangeCurrencyAware] but returns the full
     * [Expense] row instead of a Boolean.  Catches cross-source duplicates where
     * one source includes the store branch/address and the other has just the
     * store name.
     */
    @Query("""
        SELECT * FROM expenses
        WHERE (
            :merchantKey LIKE merchantKey || '%'
            OR merchantKey LIKE :merchantKey || '%'
        )
        AND LENGTH(merchantKey) >= 8
        AND LENGTH(:merchantKey) >= 8
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
    suspend fun getDuplicateCandidateByMerchantKeyPrefixInRangeCurrencyAware(
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
    // Must match DuplicateDetectionPolicy.windowEndExclusive(date, windowMs).
    // Kept as arithmetic because DAOs cannot call Kotlin utility methods in SQL.
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
     * Policy-aware duplicate ID retrieval.
     *
     * Mirrors [isDuplicateCurrencyAware] using the SAME three-tier matching
     * criteria (exact merchantKey → prefix containment → raw merchant), but
     * returns the existing expense ID instead of a boolean.
     *
     * Returns the ID of the first matching expense, or `null` if no duplicate
     * is found within the configured time/amount window.
     *
     * @see isDuplicateCurrencyAware
     */
    @Transaction
    suspend fun findDuplicateIdCurrencyAware(
        amount: Double,
        merchant: String,
        date: Long,
        currency: String,
        transactionType: String,
        windowMs: Long = DuplicateDetectionPolicy.DUPLICATE_WINDOW_MS,
        merchantKey: String? = null,
        dedupeKey: String? = null
    ): Long? {
        val startDate = date - windowMs
        // Must match DuplicateDetectionPolicy.windowEndExclusive(date, windowMs).
        // Kept as arithmetic because DAOs cannot call Kotlin utility methods in SQL.
        val endDate = date + windowMs + 1
        val tolerance = DuplicateDetectionPolicy.AMOUNT_TOLERANCE
        val minAmount = amount - tolerance
        val maxAmount = amount + tolerance
        val normalizedCurrency = DuplicateDetectionPolicy.normalizeCurrency(currency)

        val normalizedMerchantKey = merchantKey?.takeIf { it.isNotBlank() }
        return if (normalizedMerchantKey != null) {
            getDuplicateCandidateByMerchantKeyInRangeCurrencyAware(
                merchantKey = normalizedMerchantKey,
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount,
                currency = normalizedCurrency,
                transactionType = transactionType
            )?.id
                ?: getDuplicateCandidateByMerchantKeyPrefixInRangeCurrencyAware(
                    merchantKey = normalizedMerchantKey,
                    startDate = startDate,
                    endDate = endDate,
                    minAmount = minAmount,
                    maxAmount = maxAmount,
                    currency = normalizedCurrency,
                    transactionType = transactionType
                )?.id
                ?: getDuplicateCandidateByMerchantInRangeCurrencyAware(
                    merchant = merchant,
                    startDate = startDate,
                    endDate = endDate,
                    minAmount = minAmount,
                    maxAmount = maxAmount,
                    currency = normalizedCurrency,
                    transactionType = transactionType
                )?.id
        } else {
            getDuplicateCandidateByMerchantInRangeCurrencyAware(
                merchant = merchant,
                startDate = startDate,
                endDate = endDate,
                minAmount = minAmount,
                maxAmount = maxAmount,
                currency = normalizedCurrency,
                transactionType = transactionType
            )?.id
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

    // ── Duplicate resolution queries ────────────────────────────────────────

    /**
     * Find the ID of an existing expense that matches the given duplicate-criteria fields.
     * Returns the first matching ID or null if none found.
     */
    @Query("""
        SELECT id FROM expenses
        WHERE merchantKey = :merchantKey
          AND ABS(amount - :amount) < 0.01
          AND ABS(date - :date) < 300000
          AND currency = :currency
          AND transactionType = :transactionType
        LIMIT 1
    """)
    suspend fun findDuplicateId(
        merchantKey: String?,
        amount: Double,
        date: Long,
        currency: String,
        transactionType: TransactionType
    ): Long?

    /**
     * Find the ID of an expense with a matching dedupeKey.
     * Used for STRICT_EXTERNAL_ID deduplication mode.
     */
    @Query("SELECT id FROM expenses WHERE dedupeKey = :dedupeKey LIMIT 1")
    suspend fun findIdByDedupeKey(dedupeKey: String): Long?

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated(
        "Returns raw Double without currency conversion. Use MultiCurrencyRepository.getHomeCurrencyPurchaseTotal() for currency-safe aggregation.",
        ReplaceWith("multiCurrencyRepository.getHomeCurrencyPurchaseTotal(start, end).displayAmount")
    )
    @Query("""
        SELECT COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
        AND categoryId = :categoryId
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        """)
    suspend fun getCategorySpentInPeriod(categoryId: Long, startMs: Long, endMs: Long): Double

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Unsafe: raw SUM across mixed currencies. Use currency-aware aggregation path instead.")
    @Query("""
        SELECT COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) FROM expenses
        WHERE ${SPENDING_TYPE_SQL}
        AND categoryId = :categoryId
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        """)
    fun getCategorySpentInPeriodFlow(categoryId: Long, startMs: Long, endMs: Long): Flow<Double>

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated(
        "Returns raw Double without currency conversion. Use MultiCurrencyRepository.getHomeCurrencyPurchaseTotal() for currency-safe aggregation.",
        ReplaceWith("multiCurrencyRepository.getHomeCurrencyPurchaseTotal(start, end).displayAmount")
    )
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

    /**
     * Keyset-paginated (cursor-based) export query. Uses the last row's date+id
     * as the cursor instead of OFFSET, providing a consistent snapshot that is
     * immune to insertions/deletions on earlier pages.
     *
     * @param startDate  Start of the date range (inclusive).
     * @param endDate    End of the date range (exclusive).
     * @param limit      Maximum number of rows to return.
     * @param lastDate   Date of the last row from the previous page (null for first page).
     * @param lastId     Id of the last row from the previous page (null for first page).
     */
    @Query("""
        SELECT * FROM expenses
        WHERE date >= :startDate AND date < :endDate AND isNotMine = 0
        AND (:lastDate IS NULL OR (date, id) > (:lastDate, :lastId))
        ORDER BY date ASC, id ASC, merchant COLLATE NOCASE ASC
        LIMIT :limit
    """)
    suspend fun getExpensesBetweenForExportKeyset(
        startDate: Long,
        endDate: Long,
        limit: Int,
        lastDate: Long? = null,
        lastId: Long? = null
    ): List<Expense>

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

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated(
        "Returns raw Double without currency conversion. Use MultiCurrencyRepository.getHomeCurrencyPurchaseTotal() for currency-safe aggregation.",
        ReplaceWith("multiCurrencyRepository.getHomeCurrencyPurchaseTotal(start, end).displayAmount")
    )
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
    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    /**
     * Returns deposit totals grouped by currency for the given date range.
     * Used by [MultiCurrencyRepository.getHomeCurrencyDepositTotal] for
     * currency-aware income aggregation.
     */
    @Query("""
        SELECT UPPER(COALESCE(currency, 'EUR')) AS currency,
               SUM(${EFFECTIVE_AMOUNT_SQL}) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE ${DEPOSIT_TYPE_SQL}
          AND date >= :startDate AND date < :endDate
          AND isNotMine = 0
        GROUP BY UPPER(currency)
        ORDER BY total DESC
    """)
    suspend fun getDepositTotalsBetweenByCurrency(startDate: Long, endDate: Long): List<CurrencyTotal>

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
    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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
    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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
    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated(
        "Returns raw Double without currency conversion. Use MultiCurrencyRepository.getHomeCurrencyPurchaseTotal() for currency-safe aggregation.",
        ReplaceWith("multiCurrencyRepository.getHomeCurrencyPurchaseTotal(start, end).displayAmount")
    )
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
    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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
    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    /** All expenses that still lack coordinates (partial or missing). */
    @Query("SELECT * FROM expenses WHERE latitude IS NULL OR longitude IS NULL ORDER BY date DESC LIMIT :limit")
    suspend fun getUnlocatedExpenses(limit: Int): List<Expense>

    /** Count of located expenses — used for stats display. */
    @Query("SELECT COUNT(*) FROM expenses WHERE latitude IS NOT NULL AND longitude IS NOT NULL")
    suspend fun countLocated(): Int

    /** Count of unlocated expenses (partial or missing coordinates). */
    @Query("SELECT COUNT(*) FROM expenses WHERE latitude IS NULL OR longitude IS NULL")
    suspend fun countUnlocated(): Int

    /**
     * Unlocated expenses that have not yet exceeded the max backfill attempt count.
     * Bug #23 fix: excludes permanently-unresolvable expenses so the worker
     * does not retry them indefinitely.
     */
    @Query("SELECT * FROM expenses WHERE (latitude IS NULL OR longitude IS NULL) AND backfillAttempts < :maxAttempts ORDER BY date DESC LIMIT :limit")
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

    /**
     * Conditionally set location for an expense — only updates if latitude
     * and longitude are still NULL. Used by [LocationBackfillWorker] to
     * avoid overwriting locations that the user set manually between the
     * fetch and the write (race condition guard).
     *
     * @return the number of rows affected (0 means the expense was already
     *         located, likely by the user).
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
          AND latitude IS NULL
          AND longitude IS NULL
    """)
    suspend fun conditionallySetLocation(
        expenseId: Long,
        latitude: Double,
        longitude: Double,
        source: String,
        placeId: String?,
        resolvedAddress: String? = null
    ): Int

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

    /** Reactive flow of unlocated expenses (partial or missing coordinates). */
    @Query("SELECT * FROM expenses WHERE latitude IS NULL OR longitude IS NULL ORDER BY date DESC LIMIT :limit")
    fun getUnlocatedExpensesFlow(limit: Int = 100): Flow<List<Expense>>

    /**
     * Aggregate spend by merchant for expenses that have coordinates.
     * Used by [SpendingHeatmapEngine] to weight heatmap intensity.
     */
    @Deprecated("Use getLocatedMerchantTotalsByCurrency() for multi-currency safety")
    @Query("""
        SELECT merchantKey as merchantKey, MIN(merchant) as merchant,
               SUM(${EFFECTIVE_AMOUNT_SQL}) as total, COUNT(*) as cnt
        FROM expenses
        WHERE latitude IS NOT NULL
          AND longitude IS NOT NULL
          AND ${SPENDING_TYPE_SQL}
          AND isNotMine = 0
          AND merchantKey IS NOT NULL
        GROUP BY merchantKey
        ORDER BY total DESC
    """)
    suspend fun getLocatedMerchantTotals(): List<MerchantTotal>

    /**
     * Per-currency aggregate spend by merchant for expenses that have coordinates.
     * Used by [SpendingHeatmapEngine] to weight heatmap intensity per currency.
     */
    @Query("""
        SELECT merchant AS merchant,
               UPPER(COALESCE(currency, 'EUR')) AS currency,
               SUM(COALESCE(CASE 
                   WHEN isNotMine = 1 THEN 0.0
                   WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                   WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                   ELSE amount END, 0)) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE latitude IS NOT NULL AND longitude IS NOT NULL
        GROUP BY merchantKey, UPPER(COALESCE(currency, 'EUR'))
        ORDER BY total DESC
    """)
    suspend fun getLocatedMerchantTotalsByCurrency(): List<MerchantCurrencyTotal>

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
     * Fetch all expenses matching a given merchant key.
     * Used by [com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator.bulkUpdateMerchant]
     * to recompute individual dedupeKeys during a bulk merchant rename.
     */
    @Query("SELECT * FROM expenses WHERE merchantKey = :merchantKey")
    suspend fun getExpensesByMerchantKey(merchantKey: String): List<Expense>

    /**
     * Write the computed canonical key back for a single expense row.
     */
    @Query("UPDATE expenses SET merchantKey = :merchantKey WHERE id = :expenseId")
    suspend fun updateMerchantKey(expenseId: Long, merchantKey: String)

    // === Monthly/Weekly Totals Dashboard Queries ===

    /**
     * Weekly spending totals for a date range, grouped by ISO Monday-start week.
     *
     * **MIN(date) / MAX(date) are raw data boundaries**, not calendar-aligned period
     * boundaries. The SQLite `date(..., 'weekday 1')` formula computes a Monday date
     * key for grouping, but MIN/MAX within each group reflect the actual transaction
     * timestamps, which may differ from the canonical Monday midnight boundaries.
     *
     * The repository layer ([ExpenseRepository]) and the [TotalsAggregationEngine]
     * normalise these raw values to canonical Monday boundaries
     * ([TimePeriodUtils.getStartOfWeek]) — the DAO deliberately keeps the raw
     * values so the normalisation logic lives in one place.
     *
     * @see WeeklyTotal.startDate, WeeklyTotal.endDate — raw approximations,
     *      repository normalises these.
     */
    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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
    
    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Use getBusinessExpensesBetweenByCurrency() for multi-currency safety")
    @Query("""
        SELECT COALESCE(SUM(${EFFECTIVE_AMOUNT_SQL}), 0.0) FROM expenses 
        WHERE isBusinessExpense = 1 
        AND ${SPENDING_TYPE_SQL}
        AND date >= :startDate AND date < :endDate
    """)
    suspend fun getTotalBusinessExpensesBetween(startDate: Long, endDate: Long): Double?

    @Query("""
        SELECT UPPER(COALESCE(currency, 'EUR')) AS currency,
               SUM(COALESCE(CASE 
                   WHEN isNotMine = 1 THEN 0.0
                   WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                   WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                   ELSE amount END, 0)) AS total,
               COUNT(*) AS txCount
        FROM expenses
        WHERE isBusinessExpense = 1 AND date >= :startDate AND date < :endDate
        GROUP BY UPPER(COALESCE(currency, 'EUR'))
        ORDER BY total DESC
    """)
    suspend fun getBusinessExpensesBetweenByCurrency(startDate: Long, endDate: Long): List<CurrencyTotal>
    
    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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
    
    // TODO (P5-P1-5): Remove after all callers migrate to MultiCurrencyRepository
    @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
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

