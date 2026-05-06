package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.Category
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for [Category] entities.
 *
 * ## BUD-28: Category name uniqueness (applied)
 * Category names are enforced as unique at the DB level via a UNIQUE index
 * on `name COLLATE NOCASE` (see MIGRATION_112_113). The [getByName] and
 * [existsByName] queries both use `COLLATE NOCASE` for case-insensitive lookups.
 *
 * See also [Category.normalizedName] for the computed lowercase+trimmed form.
 */
@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    fun getAllFlow(): Flow<List<Category>>
    
    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): Category?

    @Query("SELECT * FROM categories WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Category>

    /**
     * Direct insert that bypasses category name normalization.
     * Prefer [com.yourname.expensetracker.data.repository.CategoryRepository.normalizeAndInsert]
     * to ensure consistent naming.
     */
    @Deprecated("Use CategoryRepository.normalizeAndInsert() instead to apply name normalization")
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long
    
    @Update
    suspend fun update(category: Category)

    /**
     * BUD-30: Protected default categories. Before deleting, checks if the
     * category has `isDefault == true` and throws if so to prevent accidental
     * removal of system-default categories.
     *
     * Since Room's @Delete annotation cannot contain guard logic, this is
     * implemented as a @Transaction method that first reads the category
     * from the database and validates it.
     */
    @Transaction
    suspend fun delete(category: Category) {
        val existing = getById(category.id)
        require(existing != null) { "Category not found: id=${category.id}" }
        require(!existing.isDefault) { 
            "Cannot delete default category '${existing.name}' (id=${existing.id}). " +
            "Default categories are protected from deletion."
        }
        deleteInternal(category)
    }

    @Delete
    suspend fun deleteInternal(category: Category)
    
    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCount(): Int
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<Category>)

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): Category?

    @Query("SELECT COUNT(*) FROM categories WHERE name = :name COLLATE NOCASE")
    suspend fun existsByName(name: String): Int

    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    suspend fun getAll(): List<Category>

    /**
     * Atomically seed defaults if the table is empty.
     * Returns true if categories were actually inserted, false if the table was non-empty.
     *
     * B4: replaces the racy getCount() → insertAll() two-step in [CategoryRepository].
     * The @Transaction annotation ensures the check-then-insert is serialized under
     * Room's transaction lock, preventing duplicate seeding under concurrency.
     */
    @Transaction
    suspend fun seedDefaultsIfEmpty(defaults: List<Category>): Boolean {
        if (getCount() > 0) return false
        insertAll(defaults)
        return true
    }

    // ── Category merge helpers (used by mergeCategories) ─────────────────────

    @Query("UPDATE expenses SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignExpensesToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("""
        UPDATE budgets SET isActive = 0, activeOverallKey = NULL, activeCategoryKey = NULL
        WHERE categoryId = :sourceCategoryId AND isActive = 1
        AND EXISTS (SELECT 1 FROM budgets b2 WHERE b2.categoryId = :targetCategoryId AND b2.isActive = 1)
    """)
    suspend fun deactivateConflictingBudgets(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("""
        UPDATE budgets SET 
            categoryId = :targetCategoryId,
            activeCategoryKey = CASE WHEN isActive = 1 THEN :targetCategoryId ELSE NULL END
        WHERE categoryId = :sourceCategoryId
    """)
    suspend fun reassignBudgetsToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE budget_adjustment_recommendations SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignBudgetAdjustmentsToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE merchant_categories SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignMerchantCategoriesToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE pending_reviews SET suggestedCategoryId = :targetCategoryId WHERE suggestedCategoryId = :sourceCategoryId")
    suspend fun reassignPendingReviewsToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE receipt_item_categorizations SET suggestedCategoryId = :targetCategoryId WHERE suggestedCategoryId = :sourceCategoryId")
    suspend fun reassignReceiptItemCategorizationsToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE recurring_occurrences SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignRecurringOccurrencesToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE planned_expenses SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignPlannedExpensesToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE spending_challenges SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignSpendingChallengesToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE merchant_canonicals SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignMerchantCanonicalsToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    /**
     * Merges [sourceCategoryId] into [targetCategoryId]: all expenses, budgets,
     * merchant mappings, planned expenses, recurring occurrences, pending reviews,
     * receipt categorizations, spending challenges, and budget adjustment
     * recommendations pointing to the source are reassigned to the target.
     *
     * After all references are moved, the source category is deleted.
     *
     * **Budget conflict resolution:** if both the source and target have an
     * active budget for the same category scope, the source's budget is
     * deactivated (isActive = 0) to avoid violating the unique constraint on
     * `(categoryId) WHERE isActive = 1`.
     *
     * The entire operation runs inside a single database transaction so it
     * either fully succeeds or rolls back.
     *
     * @param sourceCategoryId The ID of the category to merge from (will be deleted).
     * @param targetCategoryId The ID of the category to merge into (preserved).
     * @return The number of expenses reassigned.
     * @throws IllegalArgumentException if either ID is invalid or they are the same.
     */
    @Transaction
    suspend fun mergeCategories(sourceCategoryId: Long, targetCategoryId: Long): Int {
        require(sourceCategoryId != targetCategoryId) {
            "Cannot merge a category into itself (id=$sourceCategoryId)"
        }
        require(getById(sourceCategoryId) != null) {
            "Source category not found: id=$sourceCategoryId"
        }
        require(getById(targetCategoryId) != null) {
            "Target category not found: id=$targetCategoryId"
        }

        // 1. Reassign expenses (primary concern)
        val expensesMoved = reassignExpensesToCategory(sourceCategoryId, targetCategoryId)

        // 2. Handle budgets: deactivate conflicting source budgets first, then move remaining
        deactivateConflictingBudgets(sourceCategoryId, targetCategoryId)
        reassignBudgetsToCategory(sourceCategoryId, targetCategoryId)

        // 3. Move all other referencing tables
        reassignBudgetAdjustmentsToCategory(sourceCategoryId, targetCategoryId)
        reassignMerchantCategoriesToCategory(sourceCategoryId, targetCategoryId)
        reassignPendingReviewsToCategory(sourceCategoryId, targetCategoryId)
        reassignReceiptItemCategorizationsToCategory(sourceCategoryId, targetCategoryId)
        reassignRecurringOccurrencesToCategory(sourceCategoryId, targetCategoryId)
        reassignPlannedExpensesToCategory(sourceCategoryId, targetCategoryId)
        reassignSpendingChallengesToCategory(sourceCategoryId, targetCategoryId)
        reassignMerchantCanonicalsToCategory(sourceCategoryId, targetCategoryId)

        // 4. Delete the source category (now unreferenced)
        val sourceCategory = getById(sourceCategoryId)!!
        delete(sourceCategory)

        return expensesMoved
    }
}
