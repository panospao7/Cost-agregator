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
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.RateRefusalCodes
import com.yourname.expensetracker.domain.transaction.UserPresentRateRequirement
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
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
    private val postCommitActionRunner: PostCommitActionRunner,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val currencySettingsRepository: CurrencySettingsRepository
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
        private const val MAX_CONCURRENT_RECOMMENDATION_JOBS = 2

        /** MEC INV-16: bounded `Result.Error.message` constant; never payload-derived. */
        const val RECURRING_FOREIGN_CURRENCY_ERROR_CODE = "RECURRING_FOREIGN_CURRENCY_NOT_SUPPORTED"
    }

    /** True only when home resolved and [currency] differs from it. */
    private suspend fun isForeignToResolvedHome(currency: String): Boolean {
        val home = when (val resolution = currencySettingsRepository.resolveHomeCurrency()) {
            is HomeCurrencyResolution.Resolved -> resolution.currency.code
            is HomeCurrencyResolution.FirstRunDefault -> resolution.currency.code
            is HomeCurrencyResolution.Failed -> return false
        }
        return currency != home
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
        recurringNote: String? = null,
        entryZone: java.time.ZoneId
    ): Result<Long> {
        writeBarrier.checkWritesAllowed("ManualExpenseRepository.addManualExpense")
        // ── Guard validation (kept for early return with localized messages) ──
        if (amount <= 0) {
            return Result.Error(message = context.getString(R.string.debug_error_amount_greater_than_zero))
        }
        // MEC INV-14 (G2-3): per-currency cap owned by CurrencyAmountPolicy (non-catalog and
        // unparsable codes keep 1,000,000); independent of rate availability.
        val amountCap = CurrencyCode.parse(currency)
            ?.let { CurrencyAmountPolicy.cap(it) }
            ?: CurrencyAmountPolicy.DEFAULT_CAP
        if (amount > amountCap.toDouble()) {
            return Result.Error(message = context.getString(R.string.debug_error_amount_exceeds_limit))
        }
        // MEC INV-16: no recurring rule from a save whose currency differs from the resolved
        // home currency. Bounded error before the coordinator; no expense, no rule. An
        // unresolved home is not rejected here (the coordinator's flagged branch fails closed).
        if (recurrenceFrequency != null && isForeignToResolvedHome(currency)) {
            return Result.Error(message = RECURRING_FOREIGN_CURRENCY_ERROR_CODE)
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
            locationSource = locationSource,
            // MEC setter #1: every manual save is a user-present (flagged) create. The entry zone
            // is the zone captured when the sheet opened; it is never persisted or logged.
            userPresentRate = UserPresentRateRequirement(entryZone)
        )

        // ── Delegate to coordinator (DEFER side effects until outer tx commits) ─
        var insertedExpenseForHook: Expense? = null

        data class ManualExpenseTxOutcome(
            val result: Result<Long>,
            val transactionActions: PostCommitActionBatch
        )

        val txOutcome = database.withTransaction {
            val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
            when (val coordinatorResult = mutation.value) {
                is CreateExpenseResult.Created -> {
                    val id = coordinatorResult.expenseId

                    // Fetch the real persisted expense for downstream hooks
                    // instead of building a synthetic one that may diverge
                    insertedExpenseForHook = expenseDao.getById(id)
                        ?: throw IllegalStateException("Created expense $id was not found after coordinator insert")

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

                    ManualExpenseTxOutcome(Result.Success(id), mutation.postCommitActions)
                }

                is CreateExpenseResult.DuplicateSkipped,
                is CreateExpenseResult.InsertConflict -> {
                    return@withTransaction ManualExpenseTxOutcome(
                        Result.Duplicate,
                        PostCommitActionBatch.empty("manual_dup")
                    )
                }

                is CreateExpenseResult.ValidationFailed -> {
                    // MEC: a flagged refusal surfaces only as a bounded code constant (never the
                    // joined, possibly payload-bearing message). Precedence: typed rate refusal,
                    // then amount-precision codes, then the existing joined message.
                    val refusal = coordinatorResult.rateRefusal
                    val errorMessage = when {
                        refusal != null -> RateRefusalCodes.codeFor(refusal.reason)
                        RateRefusalCodes.AMOUNT_PRECISION_EXCEEDED in coordinatorResult.errors ->
                            RateRefusalCodes.AMOUNT_PRECISION_EXCEEDED
                        RateRefusalCodes.AMOUNT_DIGITS_UNAVAILABLE in coordinatorResult.errors ->
                            RateRefusalCodes.AMOUNT_DIGITS_UNAVAILABLE
                        else -> coordinatorResult.errors.joinToString("; ")
                    }
                    return@withTransaction ManualExpenseTxOutcome(
                        Result.Error(message = errorMessage),
                        PostCommitActionBatch.empty("manual_vf")
                    )
                }

                is CreateExpenseResult.Error -> {
                    return@withTransaction ManualExpenseTxOutcome(
                        Result.Error(
                            message = coordinatorResult.exception.message
                                ?: "Failed to create expense"
                        ),
                        PostCommitActionBatch.empty("manual_err")
                    )
                }
            }
        }

        // ── Deferred lifecycle side effects (now safely post-commit) ──────────
        if (txOutcome.result is Result.Success) {
            postCommitActionRunner.run(txOutcome.transactionActions)
        }
        val result = txOutcome.result

        // ── Source-specific post-transaction side effects ─────────────────────
        if (result is Result.Success) {
            // Non-blocking recommendation generation (fire-and-forget)
            asyncScope.launch {
                recommendationSemaphore.withPermit {
                    try {
                        withTimeoutOrNull(AppConfig.Ai.RECOMMENDATION_JOB_TIMEOUT_MS) {
                            val aiSettings = aiSettingsRepository.settings().first()
                            if (aiSettings.aiEnabled) {
                                val insertedExpense = insertedExpenseForHook ?: return@withTimeoutOrNull

                                // The use case never throws except for cancellation; a Skipped
                                // outcome yields a null artifact and deterministic
                                // recommendations are still generated and saved (INV-18).
                                val outcome = generateTransactionInsightUseCase(insertedExpense)

                                val recommendations = dashboardFollowThroughEngine.generateRecommendations(
                                    transaction = insertedExpense,
                                    aiArtifact = outcome.artifactOrNull,
                                    userId = DEFAULT_RECOMMENDATION_USER_ID
                                )
                                recommendationRepository.saveAll(recommendations)
                            }
                        } ?: Timber.w("Recommendation generation timed out after ${AppConfig.Ai.RECOMMENDATION_JOB_TIMEOUT_MS}ms")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Timber.e("Failed to generate recommendations class=%s", e.javaClass.simpleName)
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
