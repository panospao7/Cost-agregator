package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.CategoryTotal
import com.yourname.expensetracker.data.database.dao.DailyTotal
import com.yourname.expensetracker.data.database.dao.DayOfWeekTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MerchantStats
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.UserCorrection
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpenseRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val merchantNormalizer: MerchantNormalizer
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Shared expenses flow to prevent redundant DB queries
    private val sharedExpenses = expenseDao.getAllFlow(500)
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.WhileSubscribed(5000),
            replay = 1
        )

    fun getAllExpenses(): Flow<List<Expense>> = sharedExpenses

    fun getExpensesWithCategory(limit: Int = 200): Flow<List<ExpenseWithCategory>> =
        expenseDao.getAllWithCategoryFlow(limit)

    fun getExpensesWithCategoryInPeriod(startMs: Long, endMs: Long): Flow<List<ExpenseWithCategory>> =
        expenseDao.getExpensesWithCategoryInPeriodFlow(startMs, endMs)

    fun getExpensesWithCategoryFiltered(
        startMs: Long, 
        endMs: Long, 
        type: TransactionType?,
        categoryId: Long?, 
        merchant: String?
    ): Flow<List<ExpenseWithCategory>> =
        expenseDao.getExpensesWithCategoryFilteredFlow(
            startMs = startMs,
            endMs = endMs,
            type = type?.name,
            categoryId = categoryId,
            merchant = merchant
        )

    suspend fun getExpensesPaged(limit: Int, offset: Int): List<ExpenseWithCategory> =
        expenseDao.getExpensesWithCategoryPaged(limit, offset)

    suspend fun getCountForPeriod(startMs: Long, endMs: Long): Int =
        expenseDao.getCountForPeriod(startMs, endMs)

    suspend fun deleteExpense(expense: Expense) = expenseDao.delete(expense)

    suspend fun updateExpenseCategory(expense: Expense, newCategoryId: Long) {
        expenseDao.updateCategory(expense.id, newCategoryId)
        merchantCategoryRepository.learnPattern(expense.merchant, newCategoryId)

        // Also record as a correction for learning
        val correction = UserCorrection(
            packageName = "manual_edit",
            originalMerchant = expense.merchant,
            correctedMerchant = null,
            originalAmount = expense.amount,
            correctedAmount = null,
            originalCategoryId = expense.categoryId,
            correctedCategoryId = newCategoryId,
            originalType = expense.transactionType.name,
            correctedType = null,
            wasRejected = false,
            wasApproved = true,
            notificationTitle = null,
            notificationText = null
        )
        userCorrectionDao.insert(correction)
    }

    suspend fun updateExpenseMerchant(expense: Expense, newMerchant: String) {
        if (expense.merchant == newMerchant) return
        
        expenseDao.updateMerchant(expense.id, newMerchant)
        
        // Catch the rename for future auto-correction
        merchantNormalizer.learnMerchantAlias(expense.merchant, newMerchant)
        
        // Also learn the category for this brand name
        expense.categoryId?.let { 
            merchantCategoryRepository.learnPattern(newMerchant, it)
        }
    }

    suspend fun updateExpenseType(expense: Expense, newType: TransactionType) {
        if (expense.transactionType == newType) return
        expenseDao.updateTransactionType(expense.id, newType.name)
    }

    suspend fun updateTransferDetails(
        expense: Expense,
        transferDirection: TransferDirection?,
        transferAccountName: String?
    ) {
        expenseDao.updateTransferDirection(expense.id, transferDirection?.name)
        expenseDao.updateTransferAccountName(expense.id, transferAccountName)
    }

    suspend fun updateNotMineDetails(
        expense: Expense,
        isNotMine: Boolean,
        ownerName: String?
    ) {
        expenseDao.updateIsNotMine(expense.id, isNotMine)
        expenseDao.updateOwnerName(expense.id, ownerName)
    }

    suspend fun updateSharedExpenseDetails(
        expense: Expense,
        isSharedExpense: Boolean,
        sharedWithName: String?,
        mySharePercentage: Int?,
        myShareAmount: Double?
    ) {
        expenseDao.updateIsSharedExpense(expense.id, isSharedExpense)
        expenseDao.updateSharedWithName(expense.id, sharedWithName)
        expenseDao.updateMySharePercentage(expense.id, mySharePercentage)
        expenseDao.updateMyShareAmount(expense.id, myShareAmount)
    }

    suspend fun searchMerchants(query: String): List<MerchantSuggestion> {
        if (query.isBlank()) return emptyList<MerchantSuggestion>()
        return expenseDao.searchMerchants(query)
    }

    suspend fun getRecentMerchantNames(): List<String> {
        return expenseDao.getRecentMerchantNames()
    }

    suspend fun deleteAllExpenses() = expenseDao.deleteAll()
    
    fun getTotalSpent(): Flow<Double?> = expenseDao.getTotalSpentFlow()

    // === Analytics Methods ===

    suspend fun getExpensesBetween(startDate: Long, endDate: Long): List<Expense> =
        expenseDao.getExpensesBetween(startDate, endDate)

    suspend fun getExpensesSince(since: Long): List<Expense> =
        expenseDao.getExpensesSince(since)

    fun getExpensesBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>> =
        expenseDao.getExpensesBetweenFlow(startDate, endDate)

    suspend fun getTotalForPeriod(startMs: Long, endMs: Long): Double =
        expenseDao.getTotalForPeriod(startMs, endMs)

    suspend fun getCategoryTotalsForPeriod(startMs: Long, endMs: Long): List<CategoryTotal> =
        expenseDao.getCategoryTotalsForPeriod(startMs, endMs)

    suspend fun getMerchantStats(): List<MerchantStats> =
        expenseDao.getMerchantStats()

    suspend fun getAllMerchantStats(): List<MerchantStats> =
        expenseDao.getAllMerchantStats()

    suspend fun getTopMerchantsForPeriod(startMs: Long, endMs: Long, limit: Int = 10): List<MerchantStats> =
        expenseDao.getTopMerchantsForPeriod(startMs, endMs, limit)

    suspend fun getLargestExpenseForPeriod(startMs: Long, endMs: Long): Expense? =
        expenseDao.getLargestExpenseForPeriod(startMs, endMs)

    suspend fun getLargestExpenseForMerchant(merchant: String, startMs: Long, endMs: Long): Expense? =
        expenseDao.getLargestExpenseForMerchant(merchant, startMs, endMs)

    suspend fun getDailyTotalsForPeriod(startMs: Long, endMs: Long): List<DailyTotal> =
        expenseDao.getDailyTotalsForPeriod(startMs, endMs)

    suspend fun getRecurringCandidates(): List<MerchantStats> =
        expenseDao.getRecurringCandidates()

    suspend fun getDayOfWeekPattern(startMs: Long, endMs: Long, timeZoneOffset: Int): List<DayOfWeekTotal> =
        expenseDao.getDayOfWeekPattern(startMs, endMs, timeZoneOffset)

    // === Deposit/Income Methods ===

    suspend fun getDepositsBetween(startDate: Long, endDate: Long): List<Expense> =
        expenseDao.getDepositsBetween(startDate, endDate)

    fun getDepositsBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>> =
        expenseDao.getDepositsBetweenFlow(startDate, endDate)

    suspend fun getTotalDepositsForPeriod(startMs: Long, endMs: Long): Double =
        expenseDao.getTotalDepositsForPeriod(startMs, endMs)

    suspend fun getMonthlyDeposits(): List<com.yourname.expensetracker.data.database.dao.MonthlyDepositTotal> =
        expenseDao.getMonthlyDeposits()

    suspend fun getTotalDeposits(): Double =
        expenseDao.getTotalDeposits()
}
