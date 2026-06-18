package com.yourname.expensetracker.testfixtures.scenario

import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seeds test data into an [AppDatabase] instance for scenario testing.
 *
 * Supports two modes:
 * - **seedState**: Direct DAO inserts for background/state data.
 * - **feedInputs**: Stub for future pipeline-driven input (currently delegates
 *   to [seedState]).
 *
 * The seeder resolves category names to foreign-key IDs automatically,
 * handles enum parsing (TransactionType, BudgetPeriod), and sets sensible
 * defaults for entity fields not covered by seed data classes.
 *
 * ## Fresh-install callback
 * [AppDatabase.Companion.FRESH_INSTALL_CALLBACK] runs every time the
 * database is created and adds partial unique indexes and CHECK constraints.
 * It does **not** seed default categories — that is done by
 * `CategoryRepository.ensureDefaultCategories()` at the repository layer.
 * The seeder always inserts the categories explicitly provided in
 * [ScenarioSeed.categories], so tests have full control over which
 * categories exist.
 *
 * @property db The [AppDatabase] instance to seed into.
 */
class ScenarioSeeder(private val db: AppDatabase) {

    /**
     * IDs returned after seeding, allowing tests to reference
     * created entities without hard-coding primary key values.
     *
     * @property categoryIds  Map from category name → generated database ID.
     * @property expenseIds   List of generated expense IDs (in insertion order).
     * @property budgetIds    List of generated budget IDs (in insertion order).
     */
    data class SeedResult(
        val categoryIds: Map<String, Long>,
        val expenseIds: List<Long>,
        val budgetIds: List<Long>
    )

    /**
     * Mode 1: Direct DB seeding for background/state data.
     *
     * Inserts categories, expenses, and budgets directly via their DAOs.
     * Category names are resolved to IDs for foreign-key columns.
     * Expenses and budgets whose [ExpenseSeed.categoryName] /
     * [BudgetSeed.categoryName] references a non-existent category will
     * have a null [Expense.categoryId] / [Budget.categoryId].
     */
    suspend fun seedState(seed: ScenarioSeed): SeedResult = withContext(Dispatchers.IO) {
        // 1. Insert categories, building a name → ID map
        val categoryIds = mutableMapOf<String, Long>()
        for (catSeed in seed.categories) {
            val category = Category(
                name = catSeed.name,
                icon = catSeed.icon,
                color = catSeed.color,
                isDefault = catSeed.isDefault
            )
            val id = db.categoryDao().insert(category)
            if (id > 0L) {
                // Successful insert — store the new category ID.
                categoryIds[catSeed.name] = id
            } else {
                // OnConflictStrategy.IGNORE — category may already exist
                // from a previous seed or from FRESH_INSTALL_CALLBACK.
                // When a conflict occurs Room returns -1 (not 0), so we
                // use id > 0L to distinguish a real insert from a skip.
                // Look up the existing row by name.
                val existing = db.categoryDao().getByName(catSeed.name)
                if (existing != null) {
                    categoryIds[catSeed.name] = existing.id
                }
            }
        }

        // 2. Insert expenses, resolving categoryName → categoryId
        val expenseIds = mutableListOf<Long>()
        for (expSeed in seed.expenses) {
            val categoryId = expSeed.categoryName?.let { categoryIds[it] }
            val transactionType = parseTransactionType(expSeed.transactionType)
            val expense = Expense(
                amount = expSeed.amount,
                currency = expSeed.currency,
                merchant = expSeed.merchant,
                transactionType = transactionType,
                date = expSeed.date,
                categoryId = categoryId,
                notes = expSeed.notes,
                source = "scenario_seed"
            )
            val id = db.expenseDao().insert(expense)
            if (id != 0L) {
                expenseIds.add(id)
            }
        }

        // 3. Insert budgets, resolving categoryName → categoryId
        val budgetIds = mutableListOf<Long>()
        for (budSeed in seed.budgets) {
            val categoryId = budSeed.categoryName?.let { categoryIds[it] }
            val period = parseBudgetPeriod(budSeed.period)
            val budget = Budget(
                categoryId = categoryId,
                amount = budSeed.amount,
                period = period,
                startDate = budSeed.startDate,
                currency = budSeed.currency
            )
            val id = db.budgetDao().insert(budget)
            if (id != 0L) {
                budgetIds.add(id)
            }
        }

        SeedResult(categoryIds = categoryIds, expenseIds = expenseIds, budgetIds = budgetIds)
    }

    /**
     * Mode 2 (stub): Feed pipeline inputs.
     *
     * Currently delegates to [seedState] for all seed data.
     * In the future this will route expenses through
     * `TransactionLifecycleCoordinator.createExpense()` and notifications
     * through the notification ingestion pipeline, exercising real
     * deduplication, categorization, and validation logic.
     */
    suspend fun feedInputs(seed: ScenarioSeed): SeedResult {
        return seedState(seed)
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Parses a transaction type string into [TransactionType].
     * Defaults to [TransactionType.UNKNOWN] for unrecognized values.
     */
    private fun parseTransactionType(type: String): TransactionType {
        return try {
            TransactionType.valueOf(type.uppercase())
        } catch (e: IllegalArgumentException) {
            TransactionType.UNKNOWN
        }
    }

    /**
     * Parses a budget period string into [BudgetPeriod].
     * Defaults to [BudgetPeriod.MONTHLY] for unrecognized values.
     */
    private fun parseBudgetPeriod(period: String): BudgetPeriod {
        return try {
            BudgetPeriod.valueOf(period.uppercase())
        } catch (e: IllegalArgumentException) {
            BudgetPeriod.MONTHLY
        }
    }
}
