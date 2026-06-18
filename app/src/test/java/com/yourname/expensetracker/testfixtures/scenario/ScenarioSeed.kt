package com.yourname.expensetracker.testfixtures.scenario

/**
 * Seed data for a single category to be inserted into the database.
 *
 * Maps directly to [com.yourname.expensetracker.data.database.entity.Category].
 *
 * @property name    Category display name (must be non-blank, max 50 chars).
 * @property icon    Emoji or short icon string (max 10 chars).
 * @property color   Hex color code prefixed with '#' (e.g. "#FF5733").
 * @property isDefault Whether this is a protected default category.
 */
data class CategorySeed(
    val name: String,
    val icon: String = "📁",
    val color: String = "#FF5733",
    val isDefault: Boolean = false
)

/**
 * Seed data for a single expense to be inserted into the database.
 *
 * Maps to [com.yourname.expensetracker.data.database.entity.Expense].
 * The [transactionType] is resolved to the
 * [com.yourname.expensetracker.data.database.entity.TransactionType] enum
 * during seeding: "PURCHASE", "DEPOSIT", "TRANSFER", or custom values.
 *
 * @property amount          Transaction amount in [currency] units.
 * @property currency        ISO-4217 currency code (default "EUR").
 * @property merchant        Merchant display name.
 * @property transactionType Transaction type string ("PURCHASE", "DEPOSIT", "TRANSFER").
 * @property date            Epoch-millisecond timestamp of the transaction.
 * @property categoryName    Name of an existing category to associate, or null.
 * @property notes           Optional free-text notes.
 */
data class ExpenseSeed(
    val amount: Double,
    val currency: String = "EUR",
    val merchant: String,
    val transactionType: String = "PURCHASE",
    val date: Long,
    val categoryName: String? = null,
    val notes: String? = null
)

/**
 * Seed data for a single budget to be inserted into the database.
 *
 * Maps to [com.yourname.expensetracker.data.database.entity.Budget].
 * The [period] is resolved to the
 * [com.yourname.expensetracker.data.database.entity.BudgetPeriod] enum
 * during seeding: "WEEKLY", "MONTHLY", "YEARLY", or "DAILY".
 *
 * @property categoryName Name of an existing category for a category budget,
 *                        or null for an overall budget.
 * @property amount       Budgeted amount in [currency] units.
 * @property period       Budget period string ("WEEKLY", "MONTHLY", "YEARLY", "DAILY").
 * @property startDate    Epoch-millisecond anchor date for the first period.
 * @property currency     ISO-4217 currency code (default "EUR").
 */
data class BudgetSeed(
    val categoryName: String? = null,
    val amount: Double,
    val period: String = "MONTHLY",
    val startDate: Long,
    val currency: String = "EUR"
)

/**
 * Input data simulating a notification that could trigger expense creation
 * via the notification pipeline.
 *
 * This is a stub for future use with
 * [com.yourname.expensetracker.domain.lifecycle.TransactionLifecycleCoordinator]
 * or similar pipeline components.
 *
 * @property packageName  Android package name of the source app.
 * @property title        Notification title (nullable).
 * @property text         Notification text body (nullable).
 * @property bigText      Expanded notification text (nullable).
 * @property subText      Sub-text line of the notification (nullable).
 */
data class NotificationInput(
    val packageName: String,
    val title: String?,
    val text: String?,
    val bigText: String? = null,
    val subText: String? = null
)

/**
 * Complete scenario seed: a collection of categories, expenses, budgets,
 * and an optional fixed "now" timestamp.
 *
 * @property categories  Categories to seed (inserted first).
 * @property expenses    Expenses to seed (resolved against seeded categories).
 * @property budgets     Budgets to seed (resolved against seeded categories).
 * @property fixedNowMs  Fixed timestamp (ms) for "now" in time-sensitive tests.
 *                       0L means "use real clock".
 * @property description Human-readable description of what this scenario tests.
 */
data class ScenarioSeed(
    val categories: List<CategorySeed> = emptyList(),
    val expenses: List<ExpenseSeed> = emptyList(),
    val budgets: List<BudgetSeed> = emptyList(),
    val fixedNowMs: Long = 0L,
    val description: String = ""
)
