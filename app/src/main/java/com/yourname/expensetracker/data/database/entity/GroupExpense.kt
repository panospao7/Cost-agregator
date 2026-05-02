package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Links an expense to a group with split information.
 */
@Entity(
    tableName = "group_expenses",
    foreignKeys = [
        ForeignKey(
            entity = ExpenseGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        ),
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
    val myShareAmount: Double? = null  // Pre-calculated share for current user (for quick lookup)
)

enum class SplitType {
    EQUAL,          // Split equally among all members
    CUSTOM_AMOUNT,  // Custom amount per person
    CUSTOM_PERCENT, // Custom percentage per person
    UNEQUAL         // One person pays more/less
}
