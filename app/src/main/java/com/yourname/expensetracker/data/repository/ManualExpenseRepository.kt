package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.room.*
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GenerateTransactionInsightUseCase
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManualExpenseRepository @Inject constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val expenseDao: com.yourname.expensetracker.data.database.dao.ExpenseDao,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val categorizationEngine: CategorizationEngine,
    private val budgetMonitor: BudgetMonitor,
    private val timeProvider: TimeProvider,
    private val aiSettingsRepository: AiSettingsRepository,
    private val generateTransactionInsightUseCase: GenerateTransactionInsightUseCase,
    private val dashboardFollowThroughEngine: DashboardFollowThroughEngine,
    private val recommendationRepository: RecommendationRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val asyncScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    companion object {
        // TODO: Replace with actual UserSessionProvider
        private const val DEFAULT_RECOMMENDATION_USER_ID = "default_user"
    }

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
            return Result.Error(message = context.getString(R.string.debug_error_amount_greater_than_zero))
        }
        if (amount > 1000000.0) {
            return Result.Error(message = context.getString(R.string.debug_error_amount_exceeds_limit))
        }

        var insertedExpenseForHook: Expense? = null

        val result = database.withTransaction {
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
                merchantKey = MerchantKeyGenerator.generate(normalizedMerchant),
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
                locationSource = locationSource
            )

            val id = expenseDao.insertAtomic(expense)

            if (id <= 0) {
                return@withTransaction Result.Duplicate
            }

            insertedExpenseForHook = expense.copy(id = id)

            // 4. Check budgets
            budgetMonitor.checkBudgets()

            // 5. Learn the merchant→category mapping
            finalCategoryId?.let { merchantCategoryRepository.learnPattern(normalizedMerchant, it) }

            Result.Success(id)
        }

        if (result is Result.Success) {
            // Non-blocking recommendation generation (fire-and-forget)
            asyncScope.launch {
                try {
                    val aiSettings = aiSettingsRepository.settings().first()
                    if (aiSettings.aiEnabled) {
                        val insertedExpense = insertedExpenseForHook ?: return@launch
                        
                        // Generate AI insight for the transaction (with 3s timeout)
                        val aiArtifact = generateTransactionInsightUseCase(insertedExpense)
                        
                        // Generate recommendations (with or without AI text)
                        val recommendations = dashboardFollowThroughEngine.generateRecommendations(
                            transaction = insertedExpense,
                            aiArtifact = aiArtifact,
                            userId = DEFAULT_RECOMMENDATION_USER_ID
                        )
                        recommendationRepository.saveAll(recommendations)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to generate recommendations")
                }
            }
        }

        return result
    }

    /**
     * Get category ID for a merchant (for auto-fill in manual entry)
     */
    suspend fun getCategoryForMerchant(merchant: String): Long? {
        return categorizationEngine.categorize(merchant).categoryId
    }
}
