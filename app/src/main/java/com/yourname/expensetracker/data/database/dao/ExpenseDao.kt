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
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(expense: Expense): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAtomic(expense: Expense): Long

    @Query("SELECT changes()")
    suspend fun getChanges(): Int

    @Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit")
    fun getAllFlow(limit: Int = 500): Flow<List<Expense>>

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

    @Transaction
    @Query("""
        SELECT * FROM expenses 
        WHERE date >= :startMs AND date <= :endMs 
        AND (:type IS NULL OR transactionType = :type)
        AND (:categoryId IS NULL OR categoryId = :categoryId)
        AND (:merchant IS NULL OR merchant = :merchant)
        ORDER BY date DESC
    """)
    fun getExpensesWithCategoryFilteredFlow(
        startMs: Long, 
        endMs: Long, 
        type: String?,
        categoryId: Long?, 
        merchant: String?
    ): Flow<List<ExpenseWithCategory>>

    @Transaction
    @Query("SELECT * FROM expenses WHERE date >= :startMs AND date <= :endMs ORDER BY date DESC")
    fun getExpensesWithCategoryInPeriodFlow(startMs: Long, endMs: Long): Flow<List<ExpenseWithCategory>>

    @Deprecated("Use getAllFlow(limit) or getPage(limit, offset) to prevent OOM", ReplaceWith("getAllFlow(500)"))
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    suspend fun getAll(): List<Expense>
    
    @Query("SELECT * FROM expenses WHERE date >= :since AND isNotMine = 0 ORDER BY date DESC")
    suspend fun getExpensesSince(since: Long): List<Expense>
    
    @Query("SELECT SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0 ELSE amount END) FROM expenses WHERE transactionType = 'PURCHASE' AND isNotMine = 0")
    fun getTotalSpentFlow(): Flow<Double?>

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(expense: Expense)

    @Query("UPDATE expenses SET categoryId = :categoryId WHERE id = :expenseId")
    suspend fun updateCategory(expenseId: Long, categoryId: Long)

    @Query("UPDATE expenses SET categoryId = :categoryId WHERE merchant = :merchant")
    suspend fun updateCategoryForMerchant(merchant: String, categoryId: Long)

    @Query("UPDATE expenses SET merchant = :newMerchant WHERE merchant = :oldMerchant")
    suspend fun updateMerchantForMerchant(oldMerchant: String, newMerchant: String)

    @Query("UPDATE expenses SET merchant = :merchant WHERE id = :expenseId")
    suspend fun updateMerchant(expenseId: Long, merchant: String)

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
            WHERE ABS(amount - :amount) < 0.01
            AND ABS(date - :date) <= :windowMs
            AND (
                -- Exact match
                merchant = :merchant 
                OR 
                -- Case-insensitive match
                UPPER(merchant) = UPPER(:merchant)
                OR
                -- Normalized match (remove spaces)
                UPPER(REPLACE(merchant, ' ', '')) = UPPER(REPLACE(:merchant, ' ', ''))
                OR
                -- Substring match
                merchant LIKE '%' || :merchant || '%'
                OR
                :merchant LIKE '%' || merchant || '%'
            )
        )
    """)
    suspend fun isDuplicate(amount: Double, merchant: String, date: Long, windowMs: Long = 300000): Boolean
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                              WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                              ELSE amount END), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND categoryId = :categoryId 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
    """)
    suspend fun getCategorySpentInPeriod(categoryId: Long, startMs: Long, endMs: Long): Double

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                              WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                              ELSE amount END), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND categoryId = :categoryId 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
    """)
    fun getCategorySpentInPeriodFlow(categoryId: Long, startMs: Long, endMs: Long): Flow<Double>

    // === Merchant Search for Manual Entry ===
    @Query("""
        SELECT merchant, categoryId, AVG(amount) as avgAmount, COUNT(*) as txCount
        FROM expenses
        WHERE UPPER(merchant) LIKE '%' || UPPER(:query) || '%'
        GROUP BY UPPER(merchant)
        ORDER BY txCount DESC
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

    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate AND isNotMine = 0 ORDER BY date DESC")
    suspend fun getExpensesBetween(startDate: Long, endDate: Long): List<Expense>

    @Query("SELECT * FROM expenses WHERE transactionType = :type AND date >= :startDate AND date <= :endDate AND isNotMine = 0 ORDER BY date DESC")
    suspend fun getExpensesByTypeBetween(startDate: Long, endDate: Long, type: String): List<Expense>

    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate AND isNotMine = 0 ORDER BY date DESC")
    fun getExpensesBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE transactionType = :type AND date >= :startDate AND date <= :endDate AND isNotMine = 0 ORDER BY date DESC")
    fun getExpensesByTypeBetweenFlow(startDate: Long, endDate: Long, type: String): Flow<List<Expense>>

    @Query("""
        SELECT SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                        WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                        ELSE amount END) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
        AND isNotMine = 0
    """)
    suspend fun getTotalSpentBetween(startDate: Long, endDate: Long): Double?

    @Query("""
        SELECT merchant, SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                                  WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                                  ELSE amount END) as total, COUNT(*) as cnt 
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
        AND isNotMine = 0
        GROUP BY UPPER(merchant)
        ORDER BY total DESC
    """)
    suspend fun getMerchantTotalsBetween(startDate: Long, endDate: Long): List<MerchantTotal>

    @Query("""
        SELECT categoryId, SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                                    WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                                    ELSE amount END) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
        AND categoryId IS NOT NULL
        AND isNotMine = 0
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsBetween(startDate: Long, endDate: Long): List<CategoryTotal>

    @Query("SELECT COUNT(*) FROM expenses WHERE transactionType = 'PURCHASE' AND isNotMine = 0")
    suspend fun getPurchaseCount(): Int

    @Query("SELECT MIN(date) FROM expenses WHERE isNotMine = 0")
    suspend fun getOldestExpenseDate(): Long?

    // === Tier 1 & 2 Analytics Queries ===

    // Monthly total for a specific month range
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                               WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                               ELSE amount END), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
    """)
    suspend fun getTotalForPeriod(startMs: Long, endMs: Long): Double

    // Count for a period
    @Query("""
        SELECT COUNT(*) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
    """)
    suspend fun getCountForPeriod(startMs: Long, endMs: Long): Int

    // Category totals for a period
    @Query("""
        SELECT categoryId, SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                                    WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                                    ELSE amount END) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND categoryId IS NOT NULL
        AND isNotMine = 0
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsForPeriod(startMs: Long, endMs: Long): List<CategoryTotal>

    // Merchant averages (merchants with 2+ transactions)
    @Query("""
        SELECT merchant as merchantName, 
               SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                        WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                        ELSE amount END) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        AND isNotMine = 0
        GROUP BY merchant
        HAVING transactionCount >= 2
        ORDER BY totalAmount DESC
    """)
    suspend fun getMerchantStats(): List<MerchantStats>

    // All merchant stats (including single-transaction merchants)
    @Query("""
        SELECT merchant as merchantName, 
               SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                        WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                        ELSE amount END) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        AND isNotMine = 0
        GROUP BY merchant
        ORDER BY totalAmount DESC
    """)
    suspend fun getAllMerchantStats(): List<MerchantStats>

    // Top merchants by total spending for a period
    @Query("""
        SELECT merchant as merchantName, 
               SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                        WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                        ELSE amount END) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY merchant
        ORDER BY totalAmount DESC
        LIMIT :limit
    """)
    suspend fun getTopMerchantsForPeriod(startMs: Long, endMs: Long, limit: Int = 10): List<MerchantStats>

    // Largest single transaction in a period
    @Query("""
        SELECT * FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        ORDER BY amount DESC
        LIMIT 1
    """)
    suspend fun getLargestExpenseForPeriod(startMs: Long, endMs: Long): Expense?

    // Largest single transaction for a specific merchant in a period
    @Query("""
        SELECT * FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND merchant = :merchant
        AND isNotMine = 0
        ORDER BY amount DESC
        LIMIT 1
    """)
    suspend fun getLargestExpenseForMerchant(merchant: String, startMs: Long, endMs: Long): Expense?

    // Daily spending totals for a period (for pace calculation)
    @Query("""
        SELECT (date / 86400000) as dayEpoch, SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                                                        WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                                                        ELSE amount END) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
    """)
    suspend fun getDailyTotalsForPeriod(startMs: Long, endMs: Long): List<DailyTotal>

    // Recurring candidates: merchants that appear in multiple distinct months
    @Query("""
        SELECT merchant as merchantName, 
               SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                        WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                        ELSE amount END) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        AND isNotMine = 0
        GROUP BY merchant
        HAVING transactionCount >= 2 
        AND (maxAmount - minAmount) < (averageAmount * 0.15)
        ORDER BY transactionCount DESC
    """)
    suspend fun getRecurringCandidates(): List<MerchantStats>

    // Day-of-week spending pattern
    @Query("""
        SELECT 
            CAST((((date + :timeZoneOffset) / 1000 + 259200) % 604800) / 86400 AS INTEGER) as dayOfWeek,
            SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                     WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                     ELSE amount END) as total,
            COUNT(*) as txCount,
            AVG(amount) as avgAmount
        FROM expenses
        WHERE transactionType = 'PURCHASE'
        AND date >= :startMs AND date < :endMs
        AND isNotMine = 0
        GROUP BY dayOfWeek
        ORDER BY dayOfWeek ASC
    """)
    suspend fun getDayOfWeekPattern(startMs: Long, endMs: Long, timeZoneOffset: Int): List<DayOfWeekTotal>

    // === Deposit/Income Queries ===

    @Query("SELECT * FROM expenses WHERE transactionType = 'DEPOSIT' AND date >= :startDate AND date <= :endDate AND isNotMine = 0 ORDER BY date DESC")
    suspend fun getDepositsBetween(startDate: Long, endDate: Long): List<Expense>

    @Query("SELECT * FROM expenses WHERE transactionType = 'DEPOSIT' AND date >= :startDate AND date <= :endDate AND isNotMine = 0 ORDER BY date DESC")
    fun getDepositsBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>>

    @Query("SELECT COALESCE(SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0 ELSE amount END), 0.0) FROM expenses WHERE transactionType = 'DEPOSIT' AND date >= :startMs AND date < :endMs AND isNotMine = 0")
    suspend fun getTotalDepositsForPeriod(startMs: Long, endMs: Long): Double

    @Query("""
        SELECT strftime('%Y-%m', date/1000, 'unixepoch') as month, 
               SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                        WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                        ELSE amount END) as total, COUNT(*) as count
        FROM expenses 
        WHERE transactionType = 'DEPOSIT'
        AND isNotMine = 0
        GROUP BY month
        ORDER BY month DESC
        LIMIT 12
    """)
    suspend fun getMonthlyDeposits(): List<MonthlyDepositTotal>

    @Query("SELECT COALESCE(SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0 ELSE amount END), 0.0) FROM expenses WHERE transactionType = 'DEPOSIT' AND isNotMine = 0")
    suspend fun getTotalDeposits(): Double

    // ── Location queries (v28) ────────────────────────────────────────────────

    /**
     * All expenses that have been resolved to coordinates, as a reactive Flow.
     * The ViewModel collects this so the map auto-updates whenever the DB changes.
     */
    @Query("SELECT * FROM expenses WHERE latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY date DESC")
    fun getLocatedExpensesFlow(): Flow<List<Expense>>

    /** Suspend version for one-shot reads (e.g., analytics). */
    @Query("SELECT * FROM expenses WHERE latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY date DESC")
    suspend fun getLocatedExpenses(): List<Expense>

    /** All expenses that still lack coordinates. */
    @Query("SELECT * FROM expenses WHERE latitude IS NULL ORDER BY date DESC LIMIT :limit")
    suspend fun getUnlocatedExpenses(limit: Int = 500): List<Expense>

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
    suspend fun getUnlocatedExpensesForBackfill(limit: Int = 500, maxAttempts: Int = 3): List<Expense>

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
        SELECT merchant, SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
                                  WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
                                  ELSE amount END) as total, COUNT(*) as cnt
        FROM expenses
        WHERE latitude IS NOT NULL
          AND transactionType = 'PURCHASE'
          AND isNotMine = 0
        GROUP BY merchant
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

    /**
     * Cluster past located expenses for a given merchant into ~5 km grid cells.
     * Uses CAST(lat/0.045 AS INTEGER) and CAST(lon/0.045 AS INTEGER) as grid keys
     * (≈ 5 km cells), matching floor().toLong() used in Kotlin code.
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
        WHERE UPPER(REPLACE(merchant, ' ', '')) = UPPER(REPLACE(:merchantName, ' ', ''))
          AND latitude IS NOT NULL
          AND longitude IS NOT NULL
        GROUP BY CAST(latitude / 0.045 AS INTEGER), CAST(longitude / 0.045 AS INTEGER)
        ORDER BY count DESC
        LIMIT 5
    """)
    suspend fun getMerchantLocationClusters(merchantName: String): List<LocationCluster>

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
}

data class MerchantSuggestion(
    val merchant: String,
    val categoryId: Long?,
    val avgAmount: Double,
    val txCount: Int
)

data class MerchantTotal(
    val merchant: String,
    val total: Double,
    val cnt: Int
)

data class CategoryTotal(
    val categoryId: Long,
    val total: Double,
    val txCount: Int = 0 
)

data class MerchantStats(
    val merchantName: String,
    val totalAmount: Double,
    val transactionCount: Int,
    val averageAmount: Double,
    val minAmount: Double,
    val maxAmount: Double,
    val firstDate: Long,
    val lastDate: Long
)

data class DailyTotal(
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

