package com.yourname.expensetracker.data.database.entity

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
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["expenseId"]),
        Index(value = ["paidById"]),
        Index(value = ["groupId", "date"])
    ]
)
data class GroupExpense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val groupId: Long,
    val expenseId: Long,          // Link to actual expense
    val paidById: Long,            // Who paid for this expense
    val date: Long,                // When the expense occurred
    val description: String,       // Description for the group context
    val totalAmount: Double,       // Total amount
    val currency: String = "EUR",
    val splitType: SplitType = SplitType.EQUAL, // How to split
    val customSplitsJson: String? = null // JSON map of memberId -> amount/percentage for custom splits
)

enum class SplitType {
    EQUAL,          // Split equally among all members
    CUSTOM_AMOUNT,  // Custom amount per person
    CUSTOM_PERCENT, // Custom percentage per person
    UNEQUAL         // One person pays more/less
}
