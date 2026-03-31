package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val participantIndex: Int = 0,
    val assignedAmount: Double,
    val isPaid: Boolean = false,
    val paidAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
