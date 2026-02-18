package com.yourname.expensetracker.data.repository

import androidx.room.*
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManualExpenseRepository @Inject constructor(
    private val database: AppDatabase,
    private val expenseDao: com.yourname.expensetracker.data.database.dao.ExpenseDao,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val categorizationEngine: CategorizationEngine,
    private val budgetMonitor: BudgetMonitor,
    private val timeProvider: TimeProvider
) {

    /**
     * Add a manually entered expense
     */
    suspend fun addManualExpense(
        merchant: String,
        amount: Double,
        currency: String = "EUR",
        categoryId: Long?,
        transactionType: TransactionType = TransactionType.PURCHASE,
        paymentMethod: PaymentMethod = PaymentMethod.CASH,
        date: Long = timeProvider.now(),
        notes: String? = null
    ): Result<Long> {
        if (amount > 1000000.0) {
            return Result.Error(message = "Amount exceeds limit")
        }

        return database.withTransaction {
            // 1. Normalize merchant name
            val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = true)
            val normalizedMerchant = lookupResult.canonical.normalizedName

            // 2. Auto-categorize if no category provided
            val finalCategoryId = categoryId ?: hybridClassifier.classify(
                merchantName = normalizedMerchant,
                amount = amount
            ).categoryId.takeIf { it > 0 }

            // 3. Dedup check with tighter window for manual entries (1 minute)
            val isDuplicate = expenseDao.isDuplicate(
                amount = amount,
                merchant = normalizedMerchant,
                date = date,
                windowMs = 60000
            )
            if (isDuplicate) return@withTransaction Result.Duplicate

            // 4. Create expense
            val expense = Expense(
                amount = amount,
                currency = currency,
                merchant = normalizedMerchant,
                transactionType = transactionType,
                date = date,
                rawNotificationId = null,
                categoryId = finalCategoryId,
                paymentMethod = paymentMethod,
                isManualEntry = true,
                notes = notes
            )

            val id = expenseDao.insert(expense)

            // 5. Check budgets
            budgetMonitor.checkBudgets()

            // 6. Learn the merchant→category mapping
            if (finalCategoryId != null && id > 0) {
                merchantCategoryRepository.learnPattern(normalizedMerchant, finalCategoryId)
            }

            Result.Success(id)
        }
    }

    /**
     * Get category ID for a merchant (for auto-fill in manual entry)
     */
    suspend fun getCategoryForMerchant(merchant: String): Long? {
        return categorizationEngine.categorize(merchant)
    }
}
