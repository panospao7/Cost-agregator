package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

/**
 * Links an expense to a group with split information.
 *
 * ## E1: expenseId unique enforcement
 * The `expenseId` index (line 63) is declared as `unique = true`, which matches
 * the Phase 7 invariant requirement. Only one group_expense row may reference
 * a given expense at any time. This is enforced at the DB level.
 *
 * ## SHR-7: paidById cross-group rule — ENFORCED AT DB LEVEL
 * The `paidById` FK references GroupMember with RESTRICT, which prevents deleting
 * a member who has paid expenses. Additionally, migration 108→109 creates the
 * trigger `enforce_paid_by_same_group` which ABORTs any INSERT where `paidById`
 * references a member whose `groupId` differs from the row's `groupId`:
 * ```
 * CREATE TRIGGER IF NOT EXISTS enforce_paid_by_same_group
 * BEFORE INSERT ON group_expenses
 * BEGIN
 *     SELECT CASE WHEN (
 *         SELECT groupId FROM group_members WHERE id = NEW.paidById
 *     ) != NEW.groupId
 *     THEN RAISE(ABORT, 'paidById must belong to same group') END;
 * END;
 * ```
 * This trigger is verified to exist in MIGRATION_108_109 (lines 6856–6866).
 *
 * ## DB-8: CASCADE audit — GroupExpense
 *
 * ### CASCADE on groupId (line 47)
 * **What gets cascade-deleted:** Deleting an `ExpenseGroup` removes all its
 * `group_expenses`. This can silently erase shared-expense financial history
 * (who paid what, when, split configurations). If the group was archived rather
 * than hard-deleted, cascade is not triggered — only permanent delete is risky.
 *
 * **Appropriateness assessment:** CASCADE is acceptable here because:
 * 1. A group deletion is an intentional user/admin action (groups are archived
 *    first, deleted explicitly later).
 * 2. Group expenses have no meaning without their parent group.
 * 3. The paidById FK uses RESTRICT, so a group cannot be deleted if any member
 *    has outstanding paid expenses.
 *
 * ### CASCADE on expenseId (line 55)
 * **What gets cascade-deleted:** Deleting an `Expense` removes its linkage row
 * in `group_expenses`. The expense row itself is preserved, but the group
 * association (and all split assignments) is lost.
 *
 * **Appropriateness assessment:** CASCADE is appropriate because:
 * 1. The expense exists independently; only the group association is removed.
 * 2. If an expense is deleted entirely, its group links should also be cleaned up.
 * 3. SET_NULL would leave orphaned group_expense rows with null expenseId,
 *    causing confusion in balance calculations.
 *
 * **Migration path if change is needed:** Use SET_NULL on expenseId and filter
 * out rows with null expenseId in all queries. Add a periodic cleanup job for
 * truly orphaned rows (where both expenseId IS NULL and the group is deleted).
 */
@Entity(
    tableName = "group_expenses",
    foreignKeys = [
        // DB-8: CASCADE on groupId — deleting an ExpenseGroup removes all its
        // group_expenses. This can silently erase financial history. If preservation
        // is needed, consider SET_NULL + soft-delete patterns.
        ForeignKey(
            entity = ExpenseGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        ),
        // DB-8: CASCADE on expenseId — deleting an Expense removes its group link.
        // The expense row itself is preserved, but the group association is lost.
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GroupMember::class,
            parentColumns = ["id"],
            childColumns = ["paidById"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["expenseId"], unique = true),
        Index(value = ["paidById"]),
        Index(value = ["groupId", "date"]),
        Index(value = ["isReimbursable"])  // Index for budget offset queries
    ]
)
data class GroupExpense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val groupId: Long,
    val expenseId: Long?,          // Link to actual expense (null for standalone group expenses)
    val paidById: Long,            // Who paid for this expense
    val date: Long,                // When the expense occurred
    val description: String,       // Description for the group context
    val totalAmount: Double,       // Total amount
    @ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",
    @ColumnInfo(defaultValue = "EQUAL") val splitType: SplitType = SplitType.EQUAL, // How to split
    val customSplitsJson: String? = null, // JSON map of memberId -> amount/percentage for custom splits

    // F11: Shared Expenses Budget Offset - Reimbursement tracking
    @ColumnInfo(defaultValue = "0") val isReimbursable: Boolean = false,  // Whether this expense is eligible for reimbursement
    @ColumnInfo(defaultValue = "0.0") val reimbursedAmount: Double = 0.0,  // Amount already reimbursed to payer
    val settledAt: Long? = null,  // When the expense was fully settled (null = pending)
    val myShareAmount: Double? = null,  // Pre-calculated share for current user (for quick lookup)
    /** Application-level idempotency key for duplicate-prevention. Format: "group_expense:{groupId}:{uuid}" */
    val idempotencyKey: String? = null
) {
    @get:Ignore
    val totalMoneyAmount: MoneyAmount get() = MoneyAmount(totalAmount, CurrencyCode(currency))
}

enum class SplitType {
    EQUAL,          // Split equally among all members
    CUSTOM_AMOUNT,  // Custom amount per person
    CUSTOM_PERCENT, // Custom percentage per person
    UNEQUAL         // One person pays more/less
}
