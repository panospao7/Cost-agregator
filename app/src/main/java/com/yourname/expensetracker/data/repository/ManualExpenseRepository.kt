package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.room.*
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GenerateTransactionInsightUseCase
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.SideEffectMode
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class ManualExpenseRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val expenseDao: com.yourname.expensetracker.data.database.dao.ExpenseDao,
    private val merchantNormalizer: MerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val categorizationEngine: CategorizationEngine,
    private val timeProvider: TimeProvider,
    private val aiSettingsRepository: AiSettingsRepository,
    private val generateTransactionInsightUseCase: GenerateTransactionInsightUseCase,
    private val dashboardFollowThroughEngine: DashboardFollowThroughEngine,
    private val recommendationRepository: RecommendationRepository,
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    /**
     * Recommendation generation is intentionally app-scoped.
     *
     * This work should survive the caller lifecycle (e.g., screen/ViewModel destruction)
     * and complete in the background as best-effort enrichment.
     */
    private val asyncScope: CoroutineScope = applicationScope
    private val recommendationSemaphore = Semaphore(MAX_CONCURRENT_RECOMMENDATION_JOBS)

    companion object {
        // TODO: Replace with actual UserSessionProvider
        private const val DEFAULT_RECOMMENDATION_USER_ID = "default_user"
        private const val RECOMMENDATION_JOB_TIMEOUT_MS = 3_000L
        private const val MAX_CONCURRENT_RECOMMENDATION_JOBS = 2
    }

    /**
     * Add a manually entered expense.
     *
     * Routes the actual INSERT through [TransactionLifecycleCoordinator] for
     * consistent validation, deduplication, event logging, and post-creation
     * side effects (budget monitoring, anomaly alerts, merchant-category pattern
     * learning).  Source-specific side effects that remain here:
     *  - Recurring rule creation
     *  - AI recommendation generation
     */
    suspend fun addManualExpense(
        merchant: String,
        amount: Double,
        currency: String, // Callers must explicitly pass home currency
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
        locationSource: String? = null,
        recurrenceFrequency: RecurrenceFrequency? = null,
        recurringNote: String? = null
    ): Result<Long> {
        writeBarrier.checkWritesAllowed("ManualExpenseRepository.addManualExpense")
        // ── Guard validation (kept for early return with localized messages) ──
        if (amount <= 0) {
            return Result.Error(message = context.getString(R.string.debug_error_amount_greater_than_zero))
        }
        if (amount > 1000000.0) {
            return Result.Error(message = context.getString(R.string.debug_error_amount_exceeds_limit))
        }

        // ── Pre-processing (business logic that stays here for now) ──────────
        // 1. Normalize merchant name
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = true)
        val normalizedMerchant = lookupResult.canonical.normalizedName

        // 2. Auto-categorize if no category provided
        val finalCategoryId = categoryId ?: hybridClassifier.classify(
            merchantName = normalizedMerchant,
            amount = amount
        ).categoryId.takeIf { it > 0 }

        // ── Build request for the coordinator ────────────────────────────────
        val request = CreateExpenseRequest(
            merchant = normalizedMerchant,
            amount = amount,
            currency = currency,
            date = date,
            transactionType = transactionType,
            source = ExpenseSource.MANUAL_ENTRY,
            categoryId = finalCategoryId,
            notes = notes,
            paymentMethod = paymentMethod,
            isManualEntry = true,
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

        // ── Delegate to coordinator (DEFER side effects until outer tx commits) ─
        var insertedExpenseForHook: Expense? = null

        val result = database.withTransaction {
            @Suppress("DEPRECATION_ERROR") // TODO: migrate to createExpenseDbOnly()
            when (val coordinatorResult = transactionLifecycleCoordinator.createExpense(request, SideEffectMode.DEFER)) {
                is CreateExpenseResult.Created -> {
                    val id = coordinatorResult.expenseId

                    // TODO P2-CURRENT-019: This synthetic Expense may diverge from the actual
                    // persisted row (e.g. baseAmount, exchangeRateUsed are missing). Fetch the
                    // real entity via expenseDao.getById(id) or have the coordinator return it.
                    // Build a synthetic Expense for downstream hooks (anomaly, recommendations)
                    insertedExpenseForHook = Expense(
                        id = id,
                        amount = amount,
                        currency = currency,
                        merchant = normalizedMerchant,
                        merchantKey = MerchantKeyGenerator.generate(normalizedMerchant),
                        transactionType = transactionType,
                        date = date,
                        rawNotificationId = null,
                        categoryId = finalCategoryId,
                        createdAt = timeProvider.now(),
                        paymentMethod = paymentMethod,
                        isManualEntry = true,
                        notes = notes,
                        dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                            amount, normalizedMerchant, date, currency, transactionType
                        ),
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
                    ).normalizeOwnership()

                    // ── Source-specific side effects ──

                    // Recurring rule creation
                    if (recurrenceFrequency != null) {
                        val recurringExpense = RecurringExpenseRepository.createRecurringExpenseEntity(
                            merchant = merchant,
                            amount = amount,
                            frequency = recurrenceFrequency,
                            lastDate = date,
                            currency = currency,
                            note = recurringNote
                        )
                        // TODO: Should route through RecurringLifecycleCoordinator for full lifecycle handling
                        val recurringId = recurringExpenseRepository.insert(recurringExpense)
                        if (recurringId <= 0) {
                            throw IllegalStateException("Failed to create recurring expense rule")
                        }
                    }

                    Result.Success(id)
                }

                is CreateExpenseResult.DuplicateSkipped,
                is CreateExpenseResult.InsertConflict -> {
                    return@withTransaction Result.Duplicate
                }

                is CreateExpenseResult.ValidationFailed -> {
                    return@withTransaction Result.Error(
                        message = coordinatorResult.errors.joinToString("; ")
                    )
                }

                is CreateExpenseResult.Error -> {
                    return@withTransaction Result.Error(
                        message = coordinatorResult.exception.message
                            ?: "Failed to create expense"
                    )
                }
            }
        }

        // ── Deferred lifecycle side effects (now safely post-commit) ──────────
        if (result is Result.Success) {
            transactionLifecycleCoordinator.dispatchPostCreationSideEffects(
                result.data,
                ExpenseSource.MANUAL_ENTRY
            )
        }

        // ── Source-specific post-transaction side effects ─────────────────────
        if (result is Result.Success) {
            // Non-blocking recommendation generation (fire-and-forget)
            asyncScope.launch {
                recommendationSemaphore.withPermit {
                    try {
                        withTimeoutOrNull(RECOMMENDATION_JOB_TIMEOUT_MS) {
                            val aiSettings = aiSettingsRepository.settings().first()
                            if (aiSettings.aiEnabled) {
                                val insertedExpense = insertedExpenseForHook ?: return@withTimeoutOrNull

                                val aiArtifact = generateTransactionInsightUseCase(insertedExpense)

                                val recommendations = dashboardFollowThroughEngine.generateRecommendations(
                                    transaction = insertedExpense,
                                    aiArtifact = aiArtifact,
                                    userId = DEFAULT_RECOMMENDATION_USER_ID
                                )
                                recommendationRepository.saveAll(recommendations)
                            }
                        } ?: Timber.w("Recommendation generation timed out after ${RECOMMENDATION_JOB_TIMEOUT_MS}ms")
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to generate recommendations")
                    }
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
