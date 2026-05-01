package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_packages")
data class BlockedPackage(
    @PrimaryKey
    val packageName: String,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val blockedAt: Long = 0L
)
