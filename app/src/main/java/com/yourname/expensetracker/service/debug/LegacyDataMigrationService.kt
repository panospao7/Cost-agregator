package com.yourname.expensetracker.service.debug

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ManualRecurringExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a single table migration step.
 */
data class TableResult(
    val imported: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0
) {
    val total: Int get() = imported + skipped + failed
}

/**
 * Overall result of a legacy database migration.
 */
data class MigrationResult(
    val categories: TableResult = TableResult(),
    val expenses: TableResult = TableResult(),
    val budgets: TableResult = TableResult(),
    val recurringRules: TableResult = TableResult(),
    val plannedExpenses: TableResult = TableResult(),
    val savingsGoals: TableResult = TableResult()
) {
    val totalImported: Int
        get() = categories.imported + expenses.imported + budgets.imported +
                recurringRules.imported + plannedExpenses.imported + savingsGoals.imported
    val totalSkipped: Int
        get() = categories.skipped + expenses.skipped + budgets.skipped +
                recurringRules.skipped + plannedExpenses.skipped + savingsGoals.skipped
    val totalFailed: Int
        get() = categories.failed + expenses.failed + budgets.failed +
                recurringRules.failed + plannedExpenses.failed + savingsGoals.failed
}

/**
 * Hilt singleton that migrates data from an old (any-version) backup database
 * into the current Room database via the app's lifecycle coordinators and
 * repositories.
 *
 * ## Safety
 * - Each table is wrapped in try-catch so one failure does not block others.
 * - Items that already exist are skipped (by dedupeKey for expenses, by name
 *   for categories).
 * - All timestamps use [TimeProvider.now()] for consistency.
 */
@Singleton
class LegacyDataMigrationService @Inject constructor(
    private val categoryDao: CategoryDao,
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
    private val budgetRepository: BudgetRepository,
    private val manualRecurringExpenseRepository: ManualRecurringExpenseRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val savingsGoalRepository: SavingsGoalRepository,
    private val timeProvider: TimeProvider
) {

    /**
     * Migrate all supported tables from an old SQLite database at [backupDbPath].
     *
     * The database is opened read-only. Each table is processed sequentially.
     * Check [MigrationResult] for per-table and aggregate counts.
     */
    suspend fun migrateFromBackup(backupDbPath: String): MigrationResult {
        val db = try {
            SQLiteDatabase.openDatabase(backupDbPath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Failed to open legacy database: $backupDbPath")
            return MigrationResult()
        }

        return try {
            // Step 1: Categories — build ID mapping for downstream tables
            val categoryResult = migrateCategories(db)
            val oldToNewCategoryId = categoryResult.idMap

            // Steps 2-6: Remaining tables (some need the category ID mapping)
            val expenseResult = migrateExpenses(db, oldToNewCategoryId)
            val budgetResult = migrateBudgets(db, oldToNewCategoryId)
            val recurringResult = migrateRecurringRules(db)
            val plannedResult = migratePlannedExpenses(db, oldToNewCategoryId)
            val savingsResult = migrateSavingsGoals(db)

            MigrationResult(
                categories = categoryResult.tableResult,
                expenses = expenseResult,
                budgets = budgetResult,
                recurringRules = recurringResult,
                plannedExpenses = plannedResult,
                savingsGoals = savingsResult
            )
        } finally {
            try {
                db.close()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Error closing legacy database")
            }
        }
    }

    // ── Categories ───────────────────────────────────────────────────────

    /**
     * Migrates old categories into the current database.
     * Skips categories whose name already exists (case-insensitive).
     *
     * Returns the mapping from old category IDs to new category IDs so that
     * downstream tables (expenses, budgets, planned expenses) can re-target
     * their foreign keys.
     */
    private suspend fun migrateCategories(db: SQLiteDatabase): CategoryMigrationOutput {
        val oldToNewId = mutableMapOf<Long, Long>()
        var imported = 0
        var skipped = 0
        var failed = 0

        try {
            // Pre-load all existing categories by lowercased name for O(1) lookups
            val existingByName = categoryDao.getAll()
                .associateBy { it.name.lowercase() }
                .toMutableMap()

            db.rawQuery("SELECT * FROM categories", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val oldId = cursor.getLong("id")
                    val name = cursor.getString("name") ?: continue
                    val icon = cursor.getString("icon") ?: "📦"
                    val color = cursor.getString("color") ?: "#9E9E9E"
                    val isDefault = cursor.getInt("isDefault") != 0

                    try {
                        val existing = existingByName[name.lowercase()]
                        if (existing != null) {
                            oldToNewId[oldId] = existing.id
                            skipped++
                            continue
                        }

                        val category = Category(
                            name = name,
                            icon = icon,
                            color = color,
                            isDefault = isDefault
                        )
                        val newId = categoryDao.insert(category)
                        if (newId > 0) {
                            oldToNewId[oldId] = newId
                            // Update local cache so duplicates within the same batch
                            // are also caught
                            existingByName[name.lowercase()] = category.copy(id = newId)
                            imported++
                        } else {
                            failed++
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.w(e, "Failed to migrate category: $name")
                        failed++
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Failed to migrate categories table")
        }

        return CategoryMigrationOutput(
            idMap = oldToNewId,
            tableResult = TableResult(imported = imported, skipped = skipped, failed = failed)
        )
    }

    // ── Expenses ─────────────────────────────────────────────────────────

    private suspend fun migrateExpenses(
        db: SQLiteDatabase,
        oldCategoryIdMap: Map<Long, Long>
    ): TableResult {
        var imported = 0
        var skipped = 0
        var failed = 0

        try {
            db.rawQuery("SELECT * FROM expenses", null).use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        val request = buildExpenseRequest(cursor, oldCategoryIdMap)
                        @Suppress("DEPRECATION_ERROR") // TODO: migrate to createExpenseStandalone()
                        val outcome = transactionLifecycleCoordinator.createExpense(request)
                        when (outcome) {
                            is CreateExpenseResult.Created -> imported++
                            is CreateExpenseResult.DuplicateSkipped,
                            is CreateExpenseResult.InsertConflict -> skipped++
                            is CreateExpenseResult.ValidationFailed,
                            is CreateExpenseResult.Error -> {
                                Timber.w("Expense migration skipped " +
                                    "[${outcome::class.simpleName}] " +
                                    "merchant=${request.merchant} amount=${request.amount}")
                                failed++
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.w(e, "Failed to migrate expense row")
                        failed++
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Failed to migrate expenses table")
        }

        return TableResult(imported = imported, skipped = skipped, failed = failed)
    }

    private fun buildExpenseRequest(
        cursor: Cursor,
        oldCategoryIdMap: Map<Long, Long>
    ): CreateExpenseRequest {
        val now = timeProvider.now()
        val date = cursor.getLong("date")
        val validDate = if (date in 1L..9_999_999_999_999L) date else now

        val oldCategoryId = cursor.getLongOrNull("categoryId")
        val newCategoryId = oldCategoryId?.let { oldCategoryIdMap[it] }

        val transactionType = parseTransactionType(cursor.getString("transactionType"))
        val paymentMethod = parsePaymentMethod(cursor.getString("paymentMethod"))
        val transferDirection = parseTransferDirection(
            cursor.getStringOrNull("transferDirection")
        )

        return CreateExpenseRequest(
            merchant = cursor.getString("merchant") ?: "Unknown",
            amount = cursor.getDouble("amount"),
            currency = cursor.getString("currency") ?: "EUR",
            date = validDate,
            transactionType = transactionType,
            source = ExpenseSource.MIGRATION,
            categoryId = newCategoryId,
            notes = cursor.getStringOrNull("notes"),
            isManualEntry = cursor.getInt("isManualEntry") != 0,
            paymentMethod = paymentMethod,
            transferDirection = transferDirection,
            transferAccountName = cursor.getStringOrNull("transferAccountName"),
            isNotMine = cursor.getInt("isNotMine") != 0,
            ownerName = cursor.getStringOrNull("ownerName"),
            isSharedExpense = cursor.getInt("isSharedExpense") != 0,
            sharedWithName = cursor.getStringOrNull("sharedWithName"),
            mySharePercentage = cursor.getIntOrNull("mySharePercentage"),
            myShareAmount = cursor.getDoubleOrNull("myShareAmount"),
            latitude = cursor.getDoubleOrNull("latitude"),
            longitude = cursor.getDoubleOrNull("longitude"),
            locationSource = cursor.getStringOrNull("locationSource"),
            placeId = cursor.getStringOrNull("placeId"),
            resolvedAddress = cursor.getStringOrNull("resolvedAddress"),
            deduplicationMode = DeduplicationMode.BULK_IMPORT,
            skipDeduplication = false
        )
    }

    // ── Budgets ──────────────────────────────────────────────────────────

    private suspend fun migrateBudgets(
        db: SQLiteDatabase,
        oldCategoryIdMap: Map<Long, Long>
    ): TableResult {
        var imported = 0
        var skipped = 0
        var failed = 0

        try {
            db.rawQuery("SELECT * FROM budgets", null).use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        val budget = buildBudgetEntity(cursor, oldCategoryIdMap)
                        val outcome = budgetRepository.addBudget(budget)
                        when (outcome) {
                            is Result.Success -> imported++
                            is Result.Error -> {
                                Timber.w("Budget migration failed: ${outcome.message}")
                                failed++
                            }
                            is Result.Duplicate -> skipped++
                            Result.Loading -> {
                                // Should not happen for a suspend call; treat as failure
                                failed++
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.w(e, "Failed to migrate budget row")
                        failed++
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Failed to migrate budgets table")
        }

        return TableResult(imported = imported, skipped = skipped, failed = failed)
    }

    private fun buildBudgetEntity(
        cursor: Cursor,
        oldCategoryIdMap: Map<Long, Long>
    ): Budget {
        val now = timeProvider.now()
        val oldCategoryId = cursor.getLongOrNull("categoryId")
        val newCategoryId = oldCategoryId?.let { oldCategoryIdMap[it] }
        val periodStr = cursor.getString("period") ?: "MONTHLY"
        val period = try {
            BudgetPeriod.valueOf(periodStr.uppercase())
        } catch (_: IllegalArgumentException) {
            BudgetPeriod.MONTHLY
        }

        return Budget(
            categoryId = newCategoryId,
            amount = cursor.getDouble("amount"),
            period = period,
            startDate = cursor.getLong("startDate"),
            isActive = cursor.getInt("isActive") != 0,
            notifyAtWarning = cursor.getFloat("notifyAtWarning"),
            notifyAtCritical = cursor.getFloat("notifyAtCritical"),
            rollover = cursor.getInt("rollover") != 0,
            createdAt = now,
            lastWarningNotifiedAt = cursor.getLongOrNull("lastWarningNotifiedAt"),
            lastCriticalNotifiedAt = cursor.getLongOrNull("lastCriticalNotifiedAt"),
            lastExceededNotifiedAt = cursor.getLongOrNull("lastExceededNotifiedAt")
        )
    }

    // ── Recurring Rules (manual_recurring_expenses) ──────────────────────

    private suspend fun migrateRecurringRules(db: SQLiteDatabase): TableResult {
        var imported = 0
        var skipped = 0
        var failed = 0

        try {
            db.rawQuery("SELECT * FROM manual_recurring_expenses", null).use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        val rule = buildRecurringEntity(cursor)
                        manualRecurringExpenseRepository.insert(rule)
                        imported++
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.w(e, "Failed to migrate recurring rule")
                        failed++
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Failed to migrate manual_recurring_expenses table")
        }

        return TableResult(imported = imported, skipped = skipped, failed = failed)
    }

    private fun buildRecurringEntity(cursor: Cursor): ManualRecurringExpense {
        val now = timeProvider.now()
        val frequencyStr = cursor.getString("frequency") ?: "MONTHLY"
        val frequency = try {
            RecurrenceFrequency.valueOf(frequencyStr.uppercase())
        } catch (_: IllegalArgumentException) {
            RecurrenceFrequency.MONTHLY
        }

        return ManualRecurringExpense(
            merchant = cursor.getString("merchant") ?: "",
            amount = cursor.getDouble("amount"),
            currency = cursor.getString("currency") ?: "EUR",
            frequency = frequency,
            nextDate = cursor.getLong("nextDate"),
            note = cursor.getStringOrNull("note"),
            createdAt = now,
            isActive = true
        )
    }

    // ── Planned Expenses ─────────────────────────────────────────────────

    private suspend fun migratePlannedExpenses(
        db: SQLiteDatabase,
        oldCategoryIdMap: Map<Long, Long>
    ): TableResult {
        var imported = 0
        var skipped = 0
        var failed = 0

        try {
            db.rawQuery("SELECT * FROM planned_expenses", null).use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        val planned = buildPlannedExpenseEntity(cursor, oldCategoryIdMap)
                        val id = plannedExpenseRepository.addPlannedExpense(planned)
                        if (id > 0) {
                            imported++
                        } else {
                            skipped++
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.w(e, "Failed to migrate planned expense")
                        failed++
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Failed to migrate planned_expenses table")
        }

        return TableResult(imported = imported, skipped = skipped, failed = failed)
    }

    private fun buildPlannedExpenseEntity(
        cursor: Cursor,
        oldCategoryIdMap: Map<Long, Long>
    ): PlannedExpense {
        val now = timeProvider.now()
        val oldCategoryId = cursor.getLongOrNull("categoryId")
        val newCategoryId = oldCategoryId?.let { oldCategoryIdMap[it] }
        val priority = parsePlannedExpensePriority(cursor.getString("priority"))

        return PlannedExpense(
            description = cursor.getString("description") ?: "",
            amount = cursor.getDouble("amount"),
            date = cursor.getLong("date"),
            categoryId = newCategoryId,
            isRecurring = cursor.getInt("isRecurring") != 0,
            priority = priority,
            createdAt = now,
            updatedAt = now,
            status = "PLANNED"
        )
    }

    // ── Savings Goals ────────────────────────────────────────────────────

    private suspend fun migrateSavingsGoals(db: SQLiteDatabase): TableResult {
        var imported = 0
        var skipped = 0
        var failed = 0

        try {
            db.rawQuery("SELECT * FROM savings_goals", null).use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        val goal = buildSavingsGoalEntity(cursor)
                        savingsGoalRepository.addGoal(goal)
                        imported++
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.w(e, "Failed to migrate savings goal")
                        failed++
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Failed to migrate savings_goals table")
        }

        return TableResult(imported = imported, skipped = skipped, failed = failed)
    }

    private fun buildSavingsGoalEntity(cursor: Cursor): SavingsGoal {
        val now = timeProvider.now()
        val protectionStr = cursor.getString("protectionLevel") ?: "WARNING"
        val protection = try {
            GoalProtectionLevel.valueOf(protectionStr.uppercase())
        } catch (_: IllegalArgumentException) {
            GoalProtectionLevel.WARNING
        }

        return SavingsGoal(
            name = cursor.getString("name") ?: "",
            targetAmount = cursor.getDouble("targetAmount"),
            currentAmount = cursor.getDouble("currentAmount"),
            targetDate = cursor.getLongOrNull("targetDate"),
            protectionLevel = protection,
            createdAt = now
        )
    }

    // ── Parsing helpers ──────────────────────────────────────────────────

    private fun parseTransactionType(value: String?): TransactionType {
        if (value == null) return TransactionType.UNKNOWN
        return try {
            TransactionType.valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            TransactionType.UNKNOWN
        }
    }

    private fun parsePaymentMethod(value: String?): PaymentMethod? {
        if (value == null) return null
        return try {
            PaymentMethod.valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            PaymentMethod.UNKNOWN
        }
    }

    private fun parseTransferDirection(value: String?): TransferDirection? {
        if (value == null) return null
        return try {
            TransferDirection.valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parsePlannedExpensePriority(value: String?): PlannedExpensePriority {
        if (value == null) return PlannedExpensePriority.LIKELY
        return try {
            PlannedExpensePriority.valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            PlannedExpensePriority.LIKELY
        }
    }

    // ── Cursor helpers ───────────────────────────────────────────────────

    private fun Cursor.getLong(columnName: String): Long {
        return getLong(getColumnIndexOrThrow(columnName))
    }

    private fun Cursor.getLongOrNull(columnName: String): Long? {
        val idx = getColumnIndex(columnName)
        if (idx < 0 || isNull(idx)) return null
        return getLong(idx)
    }

    private fun Cursor.getInt(columnName: String): Int {
        return getInt(getColumnIndexOrThrow(columnName))
    }

    private fun Cursor.getString(columnName: String): String {
        return getString(getColumnIndexOrThrow(columnName))
    }

    private fun Cursor.getStringOrNull(columnName: String): String? {
        val idx = getColumnIndex(columnName)
        if (idx < 0 || isNull(idx)) return null
        return getString(idx)
    }

    private fun Cursor.getDouble(columnName: String): Double {
        return getDouble(getColumnIndexOrThrow(columnName))
    }

    private fun Cursor.getDoubleOrNull(columnName: String): Double? {
        val idx = getColumnIndex(columnName)
        if (idx < 0 || isNull(idx)) return null
        return getDouble(idx)
    }

    private fun Cursor.getFloat(columnName: String): Float {
        return getFloat(getColumnIndexOrThrow(columnName))
    }

    private fun Cursor.getIntOrNull(columnName: String): Int? {
        val idx = getColumnIndex(columnName)
        if (idx < 0 || isNull(idx)) return null
        return getInt(idx)
    }

    /**
     * Output of the category migration step.
     *
     * @property idMap Mapping from old category IDs to new (current DB) category IDs.
     * @property tableResult Counts for the categories table.
     */
    private class CategoryMigrationOutput(
        val idMap: Map<Long, Long>,
        val tableResult: TableResult
    )
}
