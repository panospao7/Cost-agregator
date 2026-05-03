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
 * ## BUD-28: Category name uniqueness
 * Category names are NOT enforced as unique at the DB level. Two categories
 * with the same name (differing only in case, e.g. "Food" vs "food") can
 * coexist, which leads to UI confusion and double-counting in reports.
 *
 * A future migration should add a UNIQUE index on `name COLLATE NOCASE`
 * to enforce case-insensitive uniqueness, together with a deduplication
 * pass that merges existing duplicates. The [getByName] query already uses
 * exact matching; after the migration it should use `COLLATE NOCASE`.
 *
 * See also [Category.name] normalization in the entity's `init` block.
 */
@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    fun getAllFlow(): Flow<List<Category>>
    
    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): Category?

    @Query("SELECT * FROM categories WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Category>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long
    
    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)
    
    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCount(): Int
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<Category>)

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Category?

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
}
