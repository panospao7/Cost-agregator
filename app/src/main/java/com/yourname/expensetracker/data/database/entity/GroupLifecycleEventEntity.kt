package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "group_lifecycle_events",
    indices = [Index("groupId"), Index("eventType"), Index("createdAt")]
)
data class GroupLifecycleEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val eventType: String,
    val actorMemberId: Long? = null,
    val relatedExpenseId: Long? = null,
    val relatedSettlementId: Long? = null,
    val payloadJson: String? = null,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "GROUP_LIFECYCLE") val source: String = "GROUP_LIFECYCLE"
)

