package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.CategoryTotal
import com.yourname.expensetracker.data.database.dao.CategoryTotalResult
import com.yourname.expensetracker.data.database.dao.DailyTotal
import com.yourname.expensetracker.data.database.dao.DayOfWeekTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao.Companion.EFFECTIVE_AMOUNT_E_SQL
import com.yourname.expensetracker.data.database.dao.MerchantStats
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.UserCorrection
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.database.model.ExpenseWithCategoryName
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.MonthlyDepositTotal
import com.yourname.expensetracker.data.database.dao.LocationCluster
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.dao.MonthlyTotal
import com.yourname.expensetracker.data.database.dao.WeeklyTotal
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class SortOrder(val sql: String, val displayName: String) {
    DATE_DESC("e.date DESC", "Newest First"),
    DATE_ASC("e.date ASC", "Oldest First"),
    // Amount sorts use the effective (ownership-adjusted) expression so that shared/not-mine
    // rows are ordered by what the user actually owes, not the gross posted amount.
    // The expression is inlined at query-build time from ExpenseDao.EFFECTIVE_AMOUNT_E_SQL.
    AMOUNT_DESC("($EFFECTIVE_AMOUNT_E_SQL) DESC", "Amount High to Low"),
    AMOUNT_ASC("($EFFECTIVE_AMOUNT_E_SQL) ASC", "Amount Low to High")
}

enum class OwnershipFilter {
    ALL, MINE, NOT_MINE, SHARED, TRANSFER
}

@Singleton
class ExpenseRepository @Inject constructor(
    private val database: AppDatabase,
    private val expenseDao: ExpenseDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val pendingReviewDao: PendingReviewDao,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val merchantNormalizer: MerchantNormalizer,
    private val transferDirectionAnalytics: TransferDirectionAnalytics
) {
    data class DebugExpenseSnapshot(
        val expenses: List<Expense>
    )
    // Mutex to prevent race conditions in category learning
    private val categoryUpdateMutex = Mutex()
    // Direct flow without sharing - each collector gets its own subscription
    // This prevents memory leaks from orphaned scopes
    fun getAllExpenses(): Flow<List<Expense>> = expenseDao.getAllFlow(500)

    fun getExpensesWithCategory(limit: Int = 200): Flow<List<ExpenseWithCategory>> =
        expenseDao.getAllWithCategoryFlow(limit)

    fun getExpensesWithCategoryInPeriod(startMs: Long, endMs: Long): Flow<List<ExpenseWithCategory>> =
        expenseDao.getExpensesWithCategoryInPeriodFlow(startMs, endMs)

    fun getExpensesWithCategoryFiltered(
        startMs: Long, 
        endMs: Long, 
        type: TransactionType?,
        categoryId: Long?, 
        merchantKey: String?
    ): Flow<List<ExpenseWithCategory>> =
        expenseDao.getExpensesWithCategoryFilteredFlow(
            startMs = startMs,
            endMs = endMs,
            type = type?.name,
            categoryId = categoryId,
            merchantKey = merchantKey
        )

    suspend fun getExpensesPaged(limit: Int, offset: Int): List<ExpenseWithCategory> =
        expenseDao.getExpensesWithCategoryPaged(limit, offset)

    /**
     * Dynamic query for filtering, searching, and sorting with pagination.
     */
    suspend fun getExpensesPagedDynamic(
        limit: Int,
        offset: Int,
        searchQuery: String? = null,
        startDate: Long? = null,
        endDate: Long? = null,
        transactionType: TransactionType? = null,
        categoryId: Long? = null,
        merchantName: String? = null,
        ownershipFilter: OwnershipFilter = OwnershipFilter.ALL,
        minAmount: Double? = null,
        maxAmount: Double? = null,
        sortOrder: SortOrder = SortOrder.DATE_DESC
    ): List<ExpenseWithCategory> {
        val args = mutableListOf<Any>()
        val whereClauses = mutableListOf<String>()

        // Search query (merchant or category name)
        if (!searchQuery.isNullOrBlank()) {
            whereClauses.add("(e.merchant LIKE ? OR e.categoryId IN (SELECT id FROM categories WHERE name LIKE ?))")
            val searchPattern = "%$searchQuery%"
            args.add(searchPattern)
            args.add(searchPattern)
        }

        // Date range
        if (startDate != null) {
            whereClauses.add("e.date >= ?")
            args.add(startDate)
        }
        if (endDate != null) {
            whereClauses.add("e.date < ?")
            args.add(endDate)
        }

        // Transaction type
        if (transactionType != null) {
            whereClauses.add("e.transactionType = ?")
            args.add(transactionType.name)
        }

        // Category filter
        if (categoryId != null) {
            whereClauses.add("e.categoryId = ?")
            args.add(categoryId)
        }

        // Merchant filter
        if (!merchantName.isNullOrBlank()) {
            whereClauses.add("e.merchantKey = ?")
            args.add(MerchantKeyGenerator.generate(merchantName))
        }

        // Effective amount range filter — uses the canonical ownership-adjusted SQL expression
        // from ExpenseDao.EFFECTIVE_AMOUNT_E_SQL (e. alias prefix) as the single source of truth.
        val effectiveAmountExpr = EFFECTIVE_AMOUNT_E_SQL
        if (minAmount != null) {
            whereClauses.add("$effectiveAmountExpr >= ?")
            args.add(minAmount)
        }
        if (maxAmount != null) {
            whereClauses.add("$effectiveAmountExpr <= ?")
            args.add(maxAmount)
        }

        // Ownership filter
        when (ownershipFilter) {
            OwnershipFilter.MINE -> {
                whereClauses.add("e.isNotMine = 0")
            }
            OwnershipFilter.NOT_MINE -> {
                whereClauses.add("e.isNotMine = 1")
            }
            OwnershipFilter.SHARED -> {
                whereClauses.add("e.isSharedExpense = 1")
            }
            OwnershipFilter.TRANSFER -> {
                whereClauses.add("e.transactionType = 'TRANSFER'")
            }
            OwnershipFilter.ALL -> { /* No filter */ }
        }

        // Build WHERE clause
        val whereClause = if (whereClauses.isNotEmpty()) {
            "WHERE ${whereClauses.joinToString(" AND ")}"
        } else {
            ""
        }

        // Build the full SQL query - let Room handle the @Relation for category
        // by not selecting category columns here (use simple SELECT from expenses)
        val sql = """
            SELECT e.id, e.amount, e.currency, e.merchant, e.transactionType, e.date, 
                   e.rawNotificationId, e.categoryId, e.createdAt, e.paymentMethod, 
                   e.isManualEntry, e.notes, e.dedupeKey, e.transferDirection, 
                   e.transferAccountName, e.isNotMine, e.ownerName, 
                   e.isSharedExpense, e.sharedWithName, e.mySharePercentage, e.myShareAmount,
                   e.latitude, e.longitude, e.locationSource, e.placeId,
                   e.backfillAttempts, e.resolvedAddress, e.merchantKey
            FROM expenses e
            $whereClause
            -- Safety invariant: sortOrder.sql comes from the closed SortOrder enum above.
            -- Do not populate it from user input or remote config.
            ORDER BY ${sortOrder.sql}
            LIMIT ? OFFSET ?
        """.trimIndent()

        args.add(limit)
        args.add(offset)

        val argArray = args.toTypedArray()
        val query = SimpleSQLiteQuery(sql, argArray)
        return expenseDao.getExpensesDynamic(query)
    }

    suspend fun getCountForPeriod(startMs: Long, endMs: Long): Int =
        expenseDao.getCountForPeriod(startMs, endMs)

    suspend fun getExpenseById(id: Long): Expense? =
        expenseDao.getById(id)

    suspend fun deleteExpense(expense: Expense) = expenseDao.delete(expense)

    suspend fun updateExpenseCategory(expense: Expense, newCategoryId: Long) {
        categoryUpdateMutex.withLock {
            database.withTransaction {
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
        }
    }

    /**
     * Overload to update category by expense ID directly.
     */
    suspend fun updateExpenseCategory(expenseId: Long, categoryId: Long?) {
        if (categoryId == null) return
        categoryUpdateMutex.withLock {
            expenseDao.updateCategory(expenseId, categoryId)
        }
    }

    suspend fun updateExpenseCategoryBulk(merchant: String, newCategoryId: Long) {
        categoryUpdateMutex.withLock {
            val merchantKey = MerchantKeyGenerator.generate(merchant)
            expenseDao.updateCategoryForMerchant(merchantKey, newCategoryId)
            merchantCategoryRepository.learnPattern(merchant, newCategoryId)

            // Record as a bulk correction for learning
            val correction = UserCorrection(
                packageName = "bulk_edit",
                originalMerchant = merchant,
                correctedMerchant = null,
                originalAmount = 0.0,
                correctedAmount = null,
                originalCategoryId = null,
                correctedCategoryId = newCategoryId,
                originalType = null,
                correctedType = null,
                wasRejected = false,
                wasApproved = true,
                notificationTitle = "Bulk category update",
                notificationText = "Applied to all transactions for $merchant"
            )
            userCorrectionDao.insert(correction)
        }
    }

    suspend fun updateExpenseMerchantBulk(oldMerchant: String, newMerchant: String) {
        if (oldMerchant == newMerchant) return

        val oldMerchantKey = MerchantKeyGenerator.generate(oldMerchant)
        val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)
        expenseDao.updateMerchantForMerchant(oldMerchantKey, newMerchant, newMerchantKey)
        merchantNormalizer.learnMerchantAlias(oldMerchant, newMerchant)
    }

    suspend fun updateExpenseMerchant(expense: Expense, newMerchant: String, applyToAll: Boolean = false) {
        if (expense.merchant == newMerchant) return
        
        val oldMerchant = expense.merchant
        
        if (applyToAll) {
            // Update all approved expenses with this name
            val oldMerchantKey = MerchantKeyGenerator.generate(oldMerchant)
            val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)
            database.withTransaction {
                expenseDao.updateMerchantForMerchant(oldMerchantKey, newMerchant, newMerchantKey)
                // Also update any pending reviews with this name
                pendingReviewDao.bulkRenameMerchant(oldMerchantKey, oldMerchant, newMerchant, newMerchantKey)
            }
        } else {
            // Just update this single record
            val newMerchantKey = MerchantKeyGenerator.generate(newMerchant)
            expenseDao.updateMerchantAndKey(expense.id, newMerchant, newMerchantKey)
        }
        
        // Catch the rename for future auto-correction
        merchantNormalizer.learnMerchantAlias(oldMerchant, newMerchant)
        
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
        database.withTransaction {
            expenseDao.updateTransferDirection(expense.id, transferDirection?.name)
            expenseDao.updateTransferAccountName(expense.id, transferAccountName)
        }

        if (transferDirection != null) {
            transferDirectionAnalytics.recordUserCorrection(
                transferId = expense.id,
                fromDirection = expense.transferDirection,
                toDirection = transferDirection
            )
        }
    }

    suspend fun updateNotMineDetails(
        expense: Expense,
        isNotMine: Boolean,
        ownerName: String?
    ) {
        database.withTransaction {
            expenseDao.updateIsNotMine(expense.id, isNotMine)
            expenseDao.updateOwnerName(expense.id, ownerName)
        }
    }

    suspend fun updateSharedExpenseDetails(
        expense: Expense,
        isSharedExpense: Boolean,
        sharedWithName: String?,
        mySharePercentage: Int?,
        myShareAmount: Double?
    ) {
        database.withTransaction {
            expenseDao.updateIsSharedExpense(expense.id, isSharedExpense)
            expenseDao.updateSharedWithName(expense.id, sharedWithName)
            expenseDao.updateMySharePercentage(expense.id, mySharePercentage)
            expenseDao.updateMyShareAmount(expense.id, myShareAmount)
        }
    }

    suspend fun searchMerchants(query: String): List<MerchantSuggestion> {
        if (query.isBlank()) return emptyList<MerchantSuggestion>()
        return expenseDao.searchMerchants(query)
    }

    suspend fun getRecentMerchantNames(): List<String> {
        return expenseDao.getRecentMerchantNames()
    }

    suspend fun getRecentTransactionsForMerchant(merchantKey: String, limit: Int = 10): List<ExpenseWithCategoryName> {
        return expenseDao.getRecentTransactionsForMerchant(merchantKey, limit)
    }

    suspend fun deleteAllExpenses() = expenseDao.deleteAll()

    suspend fun createDebugSnapshot(): DebugExpenseSnapshot {
        return DebugExpenseSnapshot(expenses = expenseDao.getAll(limit = 20_000))
    }

    suspend fun restoreDebugSnapshot(snapshot: DebugExpenseSnapshot) {
        database.withTransaction {
            expenseDao.deleteAll()
            if (snapshot.expenses.isNotEmpty()) {
                expenseDao.insertAll(snapshot.expenses)
            }
        }
    }
    
    fun getTotalSpent(): Flow<Double?> = expenseDao.getTotalSpentFlow()

    // === Analytics Methods ===

    suspend fun getExpensesBetween(startDate: Long, endDate: Long): List<Expense> =
        expenseDao.getExpensesBetween(startDate, endDate)

    suspend fun getExpensesBetweenPaged(
        startDate: Long,
        endDate: Long,
        limit: Int,
        offset: Int
    ): List<Expense> = expenseDao.getExpensesBetween(startDate, endDate, limit, offset)

    suspend fun getExpensesBetweenPagedForDeterministicExport(
        startDate: Long,
        endDate: Long,
        limit: Int,
        offset: Int
    ): List<Expense> = expenseDao.getExpensesBetweenForExport(startDate, endDate, limit, offset)

    suspend fun countExpensesBetween(startDate: Long, endDate: Long): Int =
        expenseDao.countExpensesBetween(startDate, endDate)

    suspend fun getExpensesSince(since: Long): List<Expense> =
        expenseDao.getExpensesSince(since)

    fun getExpensesBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>> =
        expenseDao.getExpensesBetweenFlow(startDate, endDate)

    suspend fun getTotalForPeriod(startMs: Long, endMs: Long): Double =
        expenseDao.getTotalForPeriod(startMs, endMs)

    suspend fun getTransactionCountForPeriod(startMs: Long, endMs: Long): Int =
        expenseDao.getCountForPeriod(startMs, endMs)

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

    suspend fun getWeeklyTotalsForPeriod(startMs: Long, endMs: Long): List<WeeklyTotal> =
        expenseDao.getWeeklyTotalsForPeriod(startMs, endMs)

    suspend fun getRecurringCandidates(): List<MerchantStats> =
        expenseDao.getRecurringCandidates()

    suspend fun getDayOfWeekPattern(startMs: Long, endMs: Long): List<DayOfWeekTotal> =
        expenseDao.getDayOfWeekPattern(startMs, endMs)

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

    // ── Location methods (v28) ────────────────────────────────────────────────

    /** Reactive flow of located expenses — collected by SpendingMapViewModel. */
    fun getLocatedExpenses() = expenseDao.getLocatedExpensesFlow()

    suspend fun getUnlocatedExpenses(limit: Int = 500) = expenseDao.getUnlocatedExpenses(limit)

    /** Returns unlocated expenses that haven't exceeded the max backfill attempt threshold. */
    suspend fun getUnlocatedExpensesForBackfill(limit: Int = 500) =
        expenseDao.getUnlocatedExpensesForBackfill(limit)

    suspend fun incrementBackfillAttempts(expenseId: Long) =
        expenseDao.incrementBackfillAttempts(expenseId)

    suspend fun countLocatedExpenses() = expenseDao.countLocated()

    suspend fun countUnlocatedExpenses() = expenseDao.countUnlocated()

    suspend fun updateExpenseLocation(
        expenseId: Long,
        latitude: Double,
        longitude: Double,
        source: String,
        placeId: String?,
        address: String? = null
    ) = expenseDao.updateLocation(expenseId, latitude, longitude, source, placeId, address)

    suspend fun clearExpenseLocation(expenseId: Long) = expenseDao.clearLocation(expenseId)

    /** Reactive flow of unlocated expenses — used by Map tab unlocated panel. */
    fun getUnlocatedExpensesFlow(limit: Int = 100) = expenseDao.getUnlocatedExpensesFlow(limit)

    suspend fun getLocatedMerchantTotals() = expenseDao.getLocatedMerchantTotals()

    suspend fun getExpensesInBoundingBox(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ) = expenseDao.getExpensesInBoundingBox(minLat, maxLat, minLon, maxLon)

    /**
     * Returns location clusters for a merchant based on historically located expenses.
     * Uses case-insensitive, whitespace-normalized matching so variant raw
     * merchant strings (from different SMS sources) still cluster together.
     * Used by Feature A (Merchant Location Affinity) in the resolver.
     */
    suspend fun getMerchantLocationClusters(merchantKey: String): List<LocationCluster> =
        expenseDao.getMerchantLocationClusters(merchantKey)

    // === Backfill methods (MerchantKeyBackfillWorker) ===

    suspend fun getExpensesWithNullMerchantKey(limit: Int = 500) =
        expenseDao.getExpensesWithNullMerchantKey(limit)

    suspend fun updateMerchantKey(expenseId: Long, merchantKey: String) =
        expenseDao.updateMerchantKey(expenseId, merchantKey)

    // === Monthly/Weekly Totals Dashboard Methods ===

    suspend fun getDailyTotalsWithDatesForPeriod(startMs: Long, endMs: Long): List<DailyTotal> =
        expenseDao.getDailyTotalsWithDatesForPeriod(startMs, endMs)

    suspend fun getMonthlyTotalsForPeriod(startMs: Long, endMs: Long): List<MonthlyTotal> =
        expenseDao.getMonthlyTotalsForPeriod(startMs, endMs)

    suspend fun getCategoryBreakdown(startMs: Long, endMs: Long): List<CategoryTotalResult> =
        expenseDao.getCategoryBreakdown(startMs, endMs)

    suspend fun getAverageDailySpend(startMs: Long, endMs: Long): Double? =
        expenseDao.getAverageDailySpend(startMs, endMs)
}
