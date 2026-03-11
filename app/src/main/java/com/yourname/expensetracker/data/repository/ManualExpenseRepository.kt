package com.yourname.expensetracker.data.repository

import androidx.room.*
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
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
        notes: String? = null,
        transferDirection: TransferDirection? = null,
        transferAccountName: String? = null,
        isNotMine: Boolean = false,
        ownerName: String? = null,
        isSharedExpense: Boolean = false,
        sharedWithName: String? = null,
        mySharePercentage: Int? = null,
        myShareAmount: Double? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        locationSource: String? = null
    ): Result<Long> {
        if (amount <= 0) {
            return Result.Error(message = "Amount must be greater than zero")
        }
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

            // 3. Atomic insert with dedupe key
            val expense = Expense(
                amount = amount,
                currency = currency,
                merchant = normalizedMerchant,
                transactionType = transactionType,
                date = date,
                rawNotificationId = null,
                categoryId = finalCategoryId,
                createdAt = System.currentTimeMillis(),
                paymentMethod = paymentMethod,
                isManualEntry = true,
                notes = notes,
                dedupeKey = Expense.generateDedupeKey(amount, normalizedMerchant, date),
                transferDirection = transferDirection,
                transferAccountName = transferAccountName,
                isNotMine = isNotMine,
                ownerName = ownerName,
                isSharedExpense = isSharedExpense,
                sharedWithName = sharedWithName,
                mySharePercentage = mySharePercentage,
                myShareAmount = myShareAmount,
                latitude = latitude,
                longitude = longitude,
                locationSource = locationSource,
                merchantKey = MerchantKeyGenerator.generate(normalizedMerchant)
            )

            val id = expenseDao.insertAtomic(expense)

            if (id <= 0) {
                return@withTransaction Result.Duplicate
            }

            // 4. Check budgets
            budgetMonitor.checkBudgets()

            // 5. Learn the merchant→category mapping
            finalCategoryId?.let { merchantCategoryRepository.learnPattern(normalizedMerchant, it) }

            Result.Success(id)
        }
    }

    /**
     * Get category ID for a merchant (for auto-fill in manual entry)
     */
    suspend fun getCategoryForMerchant(merchant: String): Long? {
        return categorizationEngine.categorize(merchant).categoryId
    }
}
