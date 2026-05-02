package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "split_item_assignments",
    foreignKeys = [
        // DB-8: CASCADE on expenseId — deleting an expense removes all split assignments.
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
