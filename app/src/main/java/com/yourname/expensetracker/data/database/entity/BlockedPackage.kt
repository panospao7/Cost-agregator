package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_packages")
data class BlockedPackage(
    @PrimaryKey
    val packageName: String,
    val blockedAt: Long = System.currentTimeMillis()
)
