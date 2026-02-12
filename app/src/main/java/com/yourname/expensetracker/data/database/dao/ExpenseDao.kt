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

    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getAllFlow(): Flow<List<Expense>>

    @Transaction
    @Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit")
    fun getAllWithCategoryFlow(limit: Int = 200): Flow<List<ExpenseWithCategory>>

    @Query("SELECT * FROM expenses ORDER BY date DESC")
    suspend fun getAll(): List<Expense>
    
    @Query("SELECT SUM(amount) FROM expenses WHERE transactionType = 'PURCHASE'")
    fun getTotalSpentFlow(): Flow<Double?>

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(expense: Expense)

    @Query("UPDATE expenses SET categoryId = :categoryId WHERE id = :expenseId")
    suspend fun updateCategory(expenseId: Long, categoryId: Long)

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses 
            WHERE ABS(amount - :amount) < 0.001 
            AND merchant = :merchant 
            AND ABS(date - :date) <= :windowMs
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

    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    fun getExpensesBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>>

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
        SELECT merchant, AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, MAX(amount) as maxAmount,
               COUNT(*) as txCount, SUM(amount) as totalAmount
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        HAVING txCount >= 2
        ORDER BY totalAmount DESC
    """)
    suspend fun getMerchantStats(): List<MerchantStats>

    // All merchant stats (including single-transaction merchants)
    @Query("""
        SELECT merchant, AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, MAX(amount) as maxAmount,
               COUNT(*) as txCount, SUM(amount) as totalAmount
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        ORDER BY totalAmount DESC
    """)
    suspend fun getAllMerchantStats(): List<MerchantStats>

    // Top merchants by total spending for a period
    @Query("""
        SELECT merchant, AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, MAX(amount) as maxAmount,
               COUNT(*) as txCount, SUM(amount) as totalAmount
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
        SELECT merchant, 
               AVG(amount) as avgAmount, 
               MIN(amount) as minAmount, 
               MAX(amount) as maxAmount,
               COUNT(*) as txCount, 
               SUM(amount) as totalAmount
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        HAVING txCount >= 2 
        AND (maxAmount - minAmount) < (avgAmount * 0.15)
        ORDER BY txCount DESC
    """)
    suspend fun getRecurringCandidates(): List<MerchantStats>

    // Day-of-week spending pattern
    @Query("""
        SELECT 
            CAST(((date / 1000 + 259200) % 604800) / 86400 AS INTEGER) as dayOfWeek,
            SUM(amount) as total,
            COUNT(*) as txCount,
            AVG(amount) as avgAmount
        FROM expenses
        WHERE transactionType = 'PURCHASE'
        AND date >= :startMs AND date < :endMs
        GROUP BY dayOfWeek
        ORDER BY dayOfWeek ASC
    """)
    suspend fun getDayOfWeekPattern(startMs: Long, endMs: Long): List<DayOfWeekTotal>
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
    val merchant: String,
    val avgAmount: Double,
    val minAmount: Double,
    val maxAmount: Double,
    val txCount: Int,
    val totalAmount: Double
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

