package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity to track when prompts were shown to the user.
 * Used for anti-nag logic - preventing repeated prompts.
 */
@Entity(
    tableName = "prompt_states",
    indices = [
        Index(value = ["promptType", "createdAt"]),
        Index(value = ["promptType", "userAction"])
    ]
)
data class PromptState(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val promptType: String,  // e.g., "LIFESTYLE_SAVINGS", "BUDGET_ALERT"
    val createdAt: Long = System.currentTimeMillis(),
    val userAction: String? = null,  // "ACCEPTED", "DISMISSED", "DEFERRED"
    val actionDetails: String? = null,  // JSON with additional context
    @ColumnInfo(defaultValue = "0")
    val acknowledgedAt: Long? = null
)
