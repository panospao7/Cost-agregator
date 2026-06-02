package com.yourname.expensetracker.data.rescue

/**
 * Plain-data representation of a rescued category row.
 * Mirrors [com.yourname.expensetracker.data.database.entity.Category]
 * but is NOT a Room entity.
 */
data class RescueCategory(
    val id: Long,
    val name: String,
    val icon: String,
    val color: String,
    val isDefault: Boolean
)

/**
 * Plain-data representation of a rescued expense row.
 * Mirrors [com.yourname.expensetracker.data.database.entity.Expense]
 * but is NOT a Room entity.
 */
data class RescueExpense(
    val id: Long,
    val amount: Double,
    val currency: String,
    val merchant: String,
    val transactionType: String,
    val date: Long,
    val categoryId: Long?,
    val createdAt: Long,
    val source: String?,
    val paymentMethod: String,
    val isManualEntry: Boolean,
    val notes: String?,
    val transferDirection: String?,
    val transferAccountName: String?,
    val isNotMine: Boolean,
    val ownerName: String?,
    val isSharedExpense: Boolean,
    val sharedWithName: String?,
    val mySharePercentage: Int?,
    val myShareAmount: Double?,
    val splitVisualization: String?
)

/**
 * Plain-data representation of a rescued expense_group row.
 * Mirrors [com.yourname.expensetracker.data.database.entity.ExpenseGroup]
 * but is NOT a Room entity.
 */
data class RescueExpenseGroup(
    val id: Long,
    val name: String,
    val description: String?,
    val defaultCurrency: String,
    val isActive: Boolean,
    val createdAt: Long,
    val createdBy: String
)

/**
 * Plain-data representation of a rescued group_member row.
 * Mirrors [com.yourname.expensetracker.data.database.entity.GroupMember]
 * but is NOT a Room entity.
 */
data class RescueGroupMember(
    val id: Long,
    val groupId: Long,
    val name: String,
    val email: String?,
    val isCurrentUser: Boolean,
    val joinedAt: Long
)

/**
 * Plain-data representation of a rescued group_expense row.
 * Mirrors [com.yourname.expensetracker.data.database.entity.GroupExpense]
 * but is NOT a Room entity.
 */
data class RescueGroupExpense(
    val id: Long,
    val groupId: Long,
    val expenseId: Long?,
    val paidById: Long,
    val date: Long,
    val description: String,
    val totalAmount: Double,
    val currency: String,
    val splitType: String,
    val customSplitsJson: String?,
    val isReimbursable: Boolean,
    val reimbursedAmount: Double,
    val settledAt: Long?,
    val myShareAmount: Double?
)

/**
 * Plain-data representation of a rescued split_item_assignment row.
 * Mirrors [com.yourname.expensetracker.data.database.entity.SplitItemAssignment]
 * but is NOT a Room entity.
 */
data class RescueSplitItemAssignment(
    val id: Long,
    val expenseId: Long,
    val receiptItemId: Long?,
    val participantName: String,
    val participantIndex: Int,
    val assignedAmount: Double,
    val isPaid: Boolean,
    val paidAt: Long?,
    val createdAt: Long
)

/**
 * Complete snapshot of all financial data read from the old database.
 * Written to a JSON file before destructive operations for safety.
 */
data class FinancialRescueSnapshot(
    val oldUserVersion: Int,
    val categories: List<RescueCategory>,
    val expenses: List<RescueExpense>,
    val expenseGroups: List<RescueExpenseGroup>,
    val groupMembers: List<RescueGroupMember>,
    val groupExpenses: List<RescueGroupExpense>,
    val splitAssignments: List<RescueSplitItemAssignment>
)
