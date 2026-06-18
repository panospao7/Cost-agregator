package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "group_settlements",
    indices = [
        Index("groupId"),
        Index("fromMemberId"),
        Index("toMemberId")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ExpenseGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GroupMember::class,
            parentColumns = ["id"],
            childColumns = ["fromMemberId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GroupMember::class,
            parentColumns = ["id"],
            childColumns = ["toMemberId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class GroupSettlementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val fromMemberId: Long,
    val toMemberId: Long,
    val amount: Double,
    val currency: String,
    val createdAt: Long,
    val linkedExpenseId: Long? = null,
    val status: String = "RECORDED",  // RECORDED, COMPLETED, CANCELLED
    val notes: String? = null
)
