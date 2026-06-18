package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "source_stats_events",
    foreignKeys = [
        ForeignKey(entity = Expense::class, parentColumns = ["id"], childColumns = ["expenseId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = RawNotification::class, parentColumns = ["id"], childColumns = ["rawNotificationId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [
        Index(value = ["packageName"]),
        Index(value = ["eventType"]),
        Index(value = ["timestamp"]),
        Index(value = ["expenseId"]),
        Index(value = ["rawNotificationId"])
    ]
)
data class SourceStatsEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val eventType: String, // ACCEPTED, DUPLICATE, REJECTED, PENDING
    val timestamp: Long,
    val expenseId: Long? = null,
    val rawNotificationId: Long? = null,
    val metadata: String? = null
)
