package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ## DB-8: CASCADE audit — SplitItemAssignment
 *
 * ### CASCADE on expenseId (line 17)
 * **What gets cascade-deleted:** Deleting a row from the `expenses` table removes
 * ALL `split_item_assignments` rows that reference it. This erases the per-person
 * split amounts, payment status, and participant assignment history for that expense.
 *
 * **Appropriateness assessment:** CASCADE is acceptable because:
 * 1. Split assignments are dependent child records — they have no meaning without
 *    the parent expense.
 * 2. Expense deletion is a deliberate user action (or data cleanup), and preserving
 *    orphaned split assignments would cause confusing state (e.g., showing splits
 *    for a non-existent expense).
 * 3. The alternative (RESTRICT) would prevent expense deletion until all split
 *    assignments are manually removed, adding unnecessary friction.
 *
 * **Migration path if change is needed:** Change to `onDelete = ForeignKey.RESTRICT`
 * and require callers to delete split assignments before the expense. Add a
 * `deleteExpenseWithAssignments(expenseId)` repository method for atomic cleanup.
 */
@Entity(
    tableName = "split_item_assignments",
    foreignKeys = [
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["expenseId"]),
        Index(value = ["receiptItemId"])
    ]
)
data class SplitItemAssignment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val expenseId: Long,
    val receiptItemId: Long? = null, // If splitting by receipt items
    val participantName: String,
    @ColumnInfo(defaultValue = "0") val participantIndex: Int = 0,
    val assignedAmount: Double,
    @ColumnInfo(defaultValue = "0") val isPaid: Boolean = false,
    val paidAt: Long? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L
)
