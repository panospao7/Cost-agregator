package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import timber.log.Timber

@Singleton
class CategoryRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val database: AppDatabase,
    private val categoryDao: CategoryDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val budgetDao: BudgetDao,
    private val categorizationEngine: CategorizationEngine,
    private val hybridExpenseClassifier: dagger.Lazy<HybridExpenseClassifier>,
    // Lazy: breaks the construction cycle; expense category moves go through the
    // transaction lifecycle coordinator only (LEGAL_PATHS category delete/merge/rename).
    private val transactionLifecycleCoordinator: dagger.Lazy<TransactionLifecycleCoordinator>
) {

    val allCategories: Flow<List<Category>> = categoryDao.getAllFlow()

    suspend fun getAll(): List<Category> = categoryDao.getAll()

    /**
     * Seed default categories and merchant dictionary if the categories table is empty.
     *
     * B4 fixes:
     * - Uses [CategoryDao.seedDefaultsIfEmpty] which is @Transaction-annotated,
     *   so the count-check + insertAll is atomic and race-free.
     * - Uses one-shot [CategoryDao.getAll] instead of flow-based `.first()` to
     *   avoid fragile flow semantics inside a seeding path.
     * - Ensures "Uncategorized" category exists for existing users via one-shot read.
     */
    suspend fun ensureDefaultCategories() {
        writeBarrier.checkWritesAllowed("CategoryRepository.ensureDefaultCategories")
        withContext(Dispatchers.IO) {
        try {
            val defaults = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.categoryBlueprints
            val seeded = categoryDao.seedDefaultsIfEmpty(defaults)

            if (seeded) {
                // Seed Merchant Dictionary
                // We need to resolve Category IDs first to map names to IDs
                val categories = categoryDao.getAll()

                // Map: "Groceries" -> 1, "Transport" -> 2
                val categoryIdMap = categories.associate { it.name to it.id }

                val merchantMap = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.getExpandedMap()
                val merchantEntities = merchantMap.mapNotNull { (merchant, categoryName) ->
                   val catId = categoryIdMap[categoryName]
                    if (catId != null) {
                        categorizationEngine.createMerchantCategoryMapping(merchant, catId)
                    } else {
                        null
                    }
                }
                if (merchantEntities.isNotEmpty()) {
                    // We need a bulk insert for speed
                    merchantCategoryDao.insertAll(merchantEntities)
                }
            } else {
                // BUG-012 Fix: Ensure "Uncategorized" exists even for existing users
                val categories = categoryDao.getAll()
                if (categories.none { it.name.equals("Uncategorized", ignoreCase = true) }) {
                    val uncategorized = defaults.find { it.name == "Uncategorized" }
                    if (uncategorized != null) {
                        categoryDao.insert(uncategorized)
                    }
                }
            }

            merchantCategoryDao.getMappingsMissingCanonicalName().forEach { mapping ->
                merchantCategoryDao.updateNormalizedCanonicalName(
                    merchantPattern = mapping.merchantPattern,
                    normalizedCanonicalName = categorizationEngine.normalizedCanonicalNameForMerchant(mapping.merchantPattern)
                )
            }
            categorizationEngine.invalidateAllCaches()
            hybridExpenseClassifier.get().invalidateCategorySnapshot()
        } catch (e: Exception) {
            Timber.e(e, "Failed to seed default categories")
        }
        }
    }

    /**
     * Add a new category with name normalization and case-insensitive duplicate detection.
     *
     * E3-NOW-008: The display name is trimmed but preserves its original case.
     * A normalized lowercase key is used only for case-insensitive duplicate
     * detection (via [CategoryDao.getByName] which uses COLLATE NOCASE).
     * If a category with the same normalized name already exists, the existing
     * category is returned instead of inserting a duplicate.
     *
     * The check-then-insert is delegated to [CategoryDao.getOrInsertByNameNoCase],
     * which is Room-transactional to prevent races.
     *
     * @return the newly created or existing [Category].
     */
    suspend fun addCategory(name: String, icon: String, color: String): Category {
        writeBarrier.checkWritesAllowed("CategoryRepository.addCategory")
        return withContext(Dispatchers.IO) {
            // E3-NOW-008: Store the original display name (trimmed, preserving case).
            // Use a normalized lowercase key only for duplicate detection.
            val displayName = name.trim()

            val category = Category(name = displayName, icon = icon, color = color)
            val saved = categoryDao.getOrInsertByNameNoCase(category)
            categorizationEngine.invalidateCache()
            hybridExpenseClassifier.get().invalidateCategorySnapshot()
            saved
        }
    }

    suspend fun learnMerchantCategory(merchantName: String, categoryId: Long) = withContext(Dispatchers.IO) {
        categorizationEngine.learnMerchantCategory(merchantName, categoryId)
    }
    
    suspend fun getCategoryByName(name: String): Category? = withContext(Dispatchers.IO) {
        categoryDao.getByName(name)
    }

    /**
     * Merges [sourceCategoryId] into [targetCategoryId], reassigning all
     * expenses, budgets, merchant mappings, planned expenses, recurring
     * occurrences, pending reviews, receipt categorizations, spending
     * challenges, and budget adjustment recommendations from the source
     * to the target.
     *
     * After all references are moved, the source category is deleted.
     *
     * **Budget conflict resolution:** if both source and target have an
     * active budget for the same category scope, the source's budget is
     * deactivated to avoid unique constraint violations.
     *
     * Expense rows move through [TransactionLifecycleCoordinator.reassignCategoryBulkDbOnly]
     * (one aggregate BULK_UPDATED event, post-commit side effects after the commit); every other
     * reference moves through [CategoryDao.reassignAllReferencesExceptExpenses]; both run in the
     * same transaction as the source delete (shared with [deleteCategory]).
     *
     * The classifier snapshot is invalidated after the merge so future
     * categorization queries use the updated category set.
     *
     * @param sourceCategoryId The category to merge FROM (will be deleted).
     * @param targetCategoryId The category to merge INTO (preserved).
     * @return The number of expenses that were reassigned.
     * @throws IllegalArgumentException if either ID is invalid or identical.
     */
    suspend fun mergeCategories(sourceCategoryId: Long, targetCategoryId: Long): Int {
        writeBarrier.checkWritesAllowed("CategoryRepository.mergeCategories")
        val expensesMoved = reassignAndDeleteCategory(sourceCategoryId, targetCategoryId, "mergeCategories")
        Timber.i("Merged category %d into %d: %d expenses reassigned", sourceCategoryId, targetCategoryId, expensesMoved)
        return expensesMoved
    }

    /**
     * Shared delete/merge path (single owner of the re-point-then-delete transaction):
     * `runWrite` + `withTransaction { TLC expense move; DAO reference re-point; source delete }`.
     * The post-commit batch runs only AFTER the transaction committed (a failure inside rolls the
     * expense move, its lifecycle event and every reference change back together and the batch is
     * never run). Cancellation is never swallowed.
     *
     * @return The number of expenses moved to [targetCategoryId].
     * @throws IllegalArgumentException if either category is missing, they are identical, or the
     *   source is a default category (DAO protection); the transaction is rolled back.
     */
    private suspend fun reassignAndDeleteCategory(
        sourceCategoryId: Long,
        targetCategoryId: Long,
        operation: String
    ): Int {
        writeBarrier.checkWritesAllowed("CategoryRepository.$operation")
        val coordinator = transactionLifecycleCoordinator.get()
        var batch: PostCommitActionBatch? = null
        val expensesMoved = writeBarrier.runWrite(DatabaseAccessOperation("CategoryRepository.$operation")) {
            database.withTransaction {
                require(sourceCategoryId != targetCategoryId) { "Cannot reassign a category into itself" }
                val source = requireNotNull(categoryDao.getById(sourceCategoryId)) { "Source category not found" }
                require(categoryDao.getById(targetCategoryId) != null) { "Target category not found" }
                val mutation = coordinator.reassignCategoryBulkDbOnly(sourceCategoryId, targetCategoryId, "USER_EDIT")
                categoryDao.reassignAllReferencesExceptExpenses(sourceCategoryId, targetCategoryId)
                categoryDao.delete(source)
                batch = mutation.postCommitActions
                mutation.value
            }
        }
        // Committed: refresh caches first so a cancelled post-commit run can never leave a
        // classifier snapshot that still references the deleted category.
        categorizationEngine.invalidateCache()
        hybridExpenseClassifier.get().invalidateCategorySnapshot()
        batch?.let { coordinator.runPostCommit(it) }
        return expensesMoved
    }

    /**
     * Renames [categoryId] to [name] (trimmed). Default categories cannot be renamed. The
     * case-insensitive duplicate check and the update run in one transaction; renaming a category
     * to a different casing of its own name is allowed. [RenameCategoryResult.InvalidCharacters]
     * also covers names over [MAX_CATEGORY_NAME_LENGTH] characters.
     */
    suspend fun renameCategory(categoryId: Long, name: String): RenameCategoryResult {
        writeBarrier.checkWritesAllowed("CategoryRepository.renameCategory")
        val displayName = name.trim()
        if (displayName.isEmpty()) return RenameCategoryResult.EmptyName
        if (displayName.length > MAX_CATEGORY_NAME_LENGTH || displayName.any { it.isISOControl() }) {
            return RenameCategoryResult.InvalidCharacters
        }
        val result = withContext(Dispatchers.IO) {
            writeBarrier.runWrite(DatabaseAccessOperation("CategoryRepository.renameCategory")) {
                database.withTransaction {
                    val category = categoryDao.getById(categoryId)
                    when {
                        category == null -> RenameCategoryResult.NotFound
                        category.isDefault -> RenameCategoryResult.CannotRenameDefault
                        else -> {
                            val existing = categoryDao.getByName(displayName)
                            if (existing != null && existing.id != categoryId) {
                                RenameCategoryResult.Duplicate
                            } else {
                                categoryDao.updateNameById(categoryId, displayName)
                                RenameCategoryResult.Renamed
                            }
                        }
                    }
                }
            }
        }
        if (result == RenameCategoryResult.Renamed) {
            categorizationEngine.invalidateCache()
            hybridExpenseClassifier.get().invalidateCategorySnapshot()
        }
        return result
    }

    /** Read-only count of expenses assigned to [categoryId] (for the delete confirmation). */
    suspend fun countExpensesForCategory(categoryId: Long): Int = withContext(Dispatchers.IO) {
        categoryDao.countExpensesForCategory(categoryId)
    }

    /**
     * Apply a merchant-category correction with an explicit scope.
     *
     * C11: When user confirms "always categorize as X", they can choose:
     * - [CategoryCorrectionScope.FUTURE_ONLY]: only learn the mapping for future expenses
     * - [CategoryCorrectionScope.BACKFILL_ALL]: backfill all existing expenses for this merchant
     * - [CategoryCorrectionScope.BACKFILL_SELECTED]: backfill selected expenses only
     *
     * ## Current status
     * - `FUTURE_ONLY` is fully implemented via [categorizationEngine.learnMerchantCategory].
     * - `BACKFILL_ALL` and `BACKFILL_SELECTED` are **not yet implemented** because they require
     *   [TransactionLifecycleCoordinator] injection (via Lazy to avoid circular dependency)
     *   and per-expense lifecycle event writing. This is tracked as deferred work.
     *
     * @param merchant The merchant name to correct.
     * @param newCategoryId The target category ID.
     * @param scope The correction scope (default: [FUTURE_ONLY]).
     * @return A [CategoryCorrectionResult] describing what was done.
     */
    suspend fun updateExpenseCategoryBulk(
        merchant: String,
        newCategoryId: Long,
        scope: CategoryCorrectionScope = CategoryCorrectionScope.FUTURE_ONLY
    ): CategoryCorrectionResult = withContext(Dispatchers.IO) {
        when (scope) {
            CategoryCorrectionScope.FUTURE_ONLY -> {
                categorizationEngine.learnMerchantCategory(merchant, newCategoryId)
                CategoryCorrectionResult.Learned(merchant, newCategoryId)
            }
            CategoryCorrectionScope.BACKFILL_ALL,
            CategoryCorrectionScope.BACKFILL_SELECTED -> {
                // C11-DEFERRED: Backfill requires TransactionLifecycleCoordinator injection
                // (via Lazy to avoid circular dependency) and per-expense lifecycle events.
                // For now, log the request and return a clear result.
                Timber.d("C11: BACKFILL scope requested for merchant='$merchant', category=$newCategoryId — backfill not yet implemented")
                CategoryCorrectionResult.BackfillNotYetImplemented(merchant, newCategoryId)
            }
        }
    }

    /**
     * Deletes [categoryId] after moving everything that references it to [replacementId]:
     * expenses (via the lifecycle coordinator), budgets (MERGED into the replacement; a
     * conflicting active source budget is deactivated) and every other reference. Shares one
     * transaction path with [mergeCategories]. Default categories cannot be deleted; the
     * replacement must be an existing category different from the source.
     */
    suspend fun deleteCategory(categoryId: Long, replacementId: Long): DeleteCategoryResult {
        writeBarrier.checkWritesAllowed("CategoryRepository.deleteCategory")
        return withContext(Dispatchers.IO) {
            val category = categoryDao.getById(categoryId)
                ?: return@withContext DeleteCategoryResult.NotFound

            if (category.isDefault) {
                return@withContext DeleteCategoryResult.CannotDeleteDefault
            }
            if (replacementId == categoryId) {
                return@withContext DeleteCategoryResult.SameAsSource
            }
            if (categoryDao.getById(replacementId) == null) {
                return@withContext DeleteCategoryResult.ReplacementInvalid
            }

            try {
                reassignAndDeleteCategory(categoryId, replacementId, "deleteCategory")
            } catch (e: IllegalArgumentException) {
                // A category vanished between the pre-checks and the transaction (rolled back).
                return@withContext if (categoryDao.getById(categoryId) == null) {
                    DeleteCategoryResult.NotFound
                } else {
                    DeleteCategoryResult.ReplacementInvalid
                }
            }
            DeleteCategoryResult.Deleted
        }
    }

    private companion object {
        /** Mirrors the [Category] entity invariant (name at most 50 characters). */
        const val MAX_CATEGORY_NAME_LENGTH = 50
    }
}

/**
 * Scope for a merchant-category correction.
 *
 * C11: When a user confirms "always categorize merchant X as category Y",
 * they can choose whether to apply this only to future expenses or to
 * backfill existing matching expenses as well.
 */
enum class CategoryCorrectionScope {
    /** Only learn the mapping for future expenses (no backfill). */
    FUTURE_ONLY,
    /** Backfill all existing expenses matching this merchant. */
    BACKFILL_ALL,
    /** Backfill only selected expenses matching this merchant. */
    BACKFILL_SELECTED
}

sealed class CategoryCorrectionResult {
    /** Mapping learned for future expenses. */
    data class Learned(val merchant: String, val categoryId: Long) : CategoryCorrectionResult()
    /** Backfill is not yet implemented (requires lifecycle coordinator injection). */
    data class BackfillNotYetImplemented(
        val merchant: String,
        val categoryId: Long,
        val affectedCount: Int = 0
    ) : CategoryCorrectionResult()
    /** No action taken (e.g., invalid scope). */
    data class NoOp(val reason: String) : CategoryCorrectionResult()
}

sealed class DeleteCategoryResult {
    data object NotFound : DeleteCategoryResult()
    data object CannotDeleteDefault : DeleteCategoryResult()
    /** The chosen replacement category does not exist. */
    data object ReplacementInvalid : DeleteCategoryResult()
    /** The replacement is the category being deleted. */
    data object SameAsSource : DeleteCategoryResult()
    data object Deleted : DeleteCategoryResult()
}

sealed class RenameCategoryResult {
    data object Renamed : RenameCategoryResult()
    data object EmptyName : RenameCategoryResult()
    /** Control characters, or longer than the 50-character category name limit. */
    data object InvalidCharacters : RenameCategoryResult()
    /** Another category already has this name (case-insensitive). */
    data object Duplicate : RenameCategoryResult()
    data object CannotRenameDefault : RenameCategoryResult()
    data object NotFound : RenameCategoryResult()
}
