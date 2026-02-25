package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import androidx.room.*
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
    
    @Query("SELECT * FROM expenses WHERE date >= :since ORDER BY date DESC")
    suspend fun getExpensesSince(since: Long): List<Expense>
    
    @Query("SELECT SUM(amount) FROM expenses WHERE transactionType = 'PURCHASE'")
    fun getTotalSpentFlow(): Flow<Double?>

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(expense: Expense)

    @Query("UPDATE expenses SET categoryId = :categoryId WHERE id = :expenseId")
    suspend fun updateCategory(expenseId: Long, categoryId: Long)

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
            WHERE transactionType = 'PURCHASE'
            AND ABS(amount - :amount) < 0.01
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
        SELECT COALESCE(SUM(amount), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND categoryId = :categoryId 
        AND date >= :startMs AND date < :endMs
    """)
    suspend fun getCategorySpentInPeriod(categoryId: Long, startMs: Long, endMs: Long): Double

    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND categoryId = :categoryId 
        AND date >= :startMs AND date < :endMs
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

    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getExpensesBetween(startDate: Long, endDate: Long): List<Expense>

    @Query("SELECT * FROM expenses WHERE transactionType = :type AND date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getExpensesByTypeBetween(startDate: Long, endDate: Long, type: String): List<Expense>

    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    fun getExpensesBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE transactionType = :type AND date >= :startDate AND date <= :endDate ORDER BY date DESC")
    fun getExpensesByTypeBetweenFlow(startDate: Long, endDate: Long, type: String): Flow<List<Expense>>

    @Query("""
        SELECT SUM(amount) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
    """)
    suspend fun getTotalSpentBetween(startDate: Long, endDate: Long): Double?

    @Query("""
        SELECT merchant, SUM(amount) as total, COUNT(*) as cnt 
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
        GROUP BY UPPER(merchant)
        ORDER BY total DESC
    """)
    suspend fun getMerchantTotalsBetween(startDate: Long, endDate: Long): List<MerchantTotal>

    @Query("""
        SELECT categoryId, SUM(amount) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
        AND categoryId IS NOT NULL
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsBetween(startDate: Long, endDate: Long): List<CategoryTotal>

    @Query("SELECT COUNT(*) FROM expenses WHERE transactionType = 'PURCHASE'")
    suspend fun getPurchaseCount(): Int

    @Query("SELECT MIN(date) FROM expenses")
    suspend fun getOldestExpenseDate(): Long?

    // === Tier 1 & 2 Analytics Queries ===

    // Monthly total for a specific month range
    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
    """)
    suspend fun getTotalForPeriod(startMs: Long, endMs: Long): Double

    // Count for a period
    @Query("""
        SELECT COUNT(*) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
    """)
    suspend fun getCountForPeriod(startMs: Long, endMs: Long): Int

    // Category totals for a period
    @Query("""
        SELECT categoryId, SUM(amount) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND categoryId IS NOT NULL
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsForPeriod(startMs: Long, endMs: Long): List<CategoryTotal>

    // Merchant averages (merchants with 2+ transactions)
    @Query("""
        SELECT merchant as merchantName, 
               SUM(amount) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        HAVING transactionCount >= 2
        ORDER BY totalAmount DESC
    """)
    suspend fun getMerchantStats(): List<MerchantStats>

    // All merchant stats (including single-transaction merchants)
    @Query("""
        SELECT merchant as merchantName, 
               SUM(amount) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        ORDER BY totalAmount DESC
    """)
    suspend fun getAllMerchantStats(): List<MerchantStats>

    // Top merchants by total spending for a period
    @Query("""
        SELECT merchant as merchantName, 
               SUM(amount) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
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
        ORDER BY amount DESC
        LIMIT 1
    """)
    suspend fun getLargestExpenseForMerchant(merchant: String, startMs: Long, endMs: Long): Expense?

    // Daily spending totals for a period (for pace calculation)
    @Query("""
        SELECT (date / 86400000) as dayEpoch, SUM(amount) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
    """)
    suspend fun getDailyTotalsForPeriod(startMs: Long, endMs: Long): List<DailyTotal>

    // Recurring candidates: merchants that appear in multiple distinct months
    @Query("""
        SELECT merchant as merchantName, 
               SUM(amount) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
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
            SUM(amount) as total,
            COUNT(*) as txCount,
            AVG(amount) as avgAmount
        FROM expenses
        WHERE transactionType = 'PURCHASE'
        AND date >= :startMs AND date < :endMs
        GROUP BY dayOfWeek
        ORDER BY dayOfWeek ASC
    """)
    suspend fun getDayOfWeekPattern(startMs: Long, endMs: Long, timeZoneOffset: Int): List<DayOfWeekTotal>

    // === Deposit/Income Queries ===

    @Query("SELECT * FROM expenses WHERE transactionType = 'DEPOSIT' AND date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getDepositsBetween(startDate: Long, endDate: Long): List<Expense>

    @Query("SELECT * FROM expenses WHERE transactionType = 'DEPOSIT' AND date >= :startDate AND date <= :endDate ORDER BY date DESC")
    fun getDepositsBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM expenses WHERE transactionType = 'DEPOSIT' AND date >= :startMs AND date < :endMs")
    suspend fun getTotalDepositsForPeriod(startMs: Long, endMs: Long): Double

    @Query("""
        SELECT strftime('%Y-%m', date/1000, 'unixepoch') as month, 
               SUM(amount) as total, COUNT(*) as count
        FROM expenses 
        WHERE transactionType = 'DEPOSIT'
        GROUP BY month
        ORDER BY month DESC
        LIMIT 12
    """)
    suspend fun getMonthlyDeposits(): List<MonthlyDepositTotal>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM expenses WHERE transactionType = 'DEPOSIT'")
    suspend fun getTotalDeposits(): Double
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

