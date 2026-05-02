package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

enum class BudgetPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}

/**
 * Budget entity.
 *
 * Active-budget invariants (enforced transactionally in the DAO/repository
 * layer because Room schema must match generated metadata):
 *  - At most one active overall budget: `UNIQUE(isActive) WHERE isActive = 1 AND categoryId IS NULL`
 *  - At most one active budget per category: `UNIQUE(categoryId) WHERE isActive = 1 AND categoryId IS NOT NULL`
 *
 * Materialized-key CHECK constraint (applied via migration 106→107):
 *  - Inactive (isActive=0)          → activeOverallKey IS NULL AND activeCategoryKey IS NULL
 *  - Active overall (categoryId=NULL) → activeOverallKey = 1 AND activeCategoryKey IS NULL
 *  - Active by category             → activeOverallKey IS NULL AND activeCategoryKey = categoryId
 *
 * ## BUD-7: Category deletion risk
 * The `categoryId` FK uses `SET_NULL` — deleting a Category sets `categoryId = NULL`
 * on its budgets, silently converting category budgets into overall budgets.
 * This is data-loss-prone. A future migration should change this to `RESTRICT`
 * (and add a migration 108→109 to rebuild the FK), but that requires a heavyweight
 * schema migration. For now, callers MUST soft-delete categories or check for
 * orphaned budgets after deletion.
 */
@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["isActive"]),
        Index(value = ["activeOverallKey"], unique = true),
        Index(value = ["activeCategoryKey"], unique = true)
    ]
)
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?,              // null = overall budget
    val amount: Double,
    val period: BudgetPeriod,
    @ColumnInfo(defaultValue = "'ROLLING'") val periodMode: String = "ROLLING", // ROLLING | CALENDAR
    val startDate: Long,                // anchor date for period calculation
    @ColumnInfo(defaultValue = "1") val isActive: Boolean = true,
    @ColumnInfo(defaultValue = "0.75") val notifyAtWarning: Float = 0.75f, // first alert threshold (75%)
    @ColumnInfo(defaultValue = "0.9") val notifyAtCritical: Float = 0.90f,// second alert threshold (90%)
    @ColumnInfo(defaultValue = "0") val rollover: Boolean = false, // carry unspent to next period
    @ColumnInfo(defaultValue = "'EUR'") val currency: String = "EUR",
    @ColumnInfo(defaultValue = "'LEGACY_DEFAULT'") val currencyAssumption: String = "LEGACY_DEFAULT",
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    val lastWarningNotifiedAt: Long? = null,
    val lastCriticalNotifiedAt: Long? = null,
    val lastExceededNotifiedAt: Long? = null,
    /** Materialized invariant key: set to 1 when isActive=true AND categoryId IS NULL, else NULL. */
    val activeOverallKey: Long? = null,
    /** Materialized invariant key: set to categoryId when isActive=true AND categoryId IS NOT NULL, else NULL. */
    val activeCategoryKey: Long? = null
) {
    @get:Ignore
    val moneyAmount: MoneyAmount get() = MoneyAmount(amount, CurrencyCode(currency))
}
