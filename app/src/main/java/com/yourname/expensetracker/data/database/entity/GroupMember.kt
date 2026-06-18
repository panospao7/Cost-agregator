package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a member of an expense group.
 *
 * Materialized-key CHECK constraint (applied via migration 106→107):
 *  - Non-current-user (isCurrentUser=0) → currentUserGroupKey IS NULL
 *  - Current user (isCurrentUser=1)     → currentUserGroupKey = groupId
 */
@Entity(
    tableName = "group_members",
    foreignKeys = [
        // DB-8: CASCADE on groupId — deleting an ExpenseGroup removes all its members.
        // If member history needs to be preserved, consider SET_NULL + soft-delete.
        ForeignKey(
            entity = ExpenseGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["groupId", "isCurrentUser"]),
        Index(value = ["groupId", "name"]),
        Index(value = ["currentUserGroupKey"], unique = true)
    ]
)
data class GroupMember(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val groupId: Long,
    val name: String,              // Member name (e.g., "John", "Alice")
    val email: String? = null,     // Optional contact
    @ColumnInfo(defaultValue = "0") val isCurrentUser: Boolean = false, // Is this the app user?
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val joinedAt: Long = 0L,
    /**
     * Materialized invariant key: set to groupId when isCurrentUser=true, else NULL.
     *
     * J3: The CHECK constraint enforces `currentUserGroupKey = groupId` when
     * `isCurrentUser = 1`. For rows where `isCurrentUser = 0` this column is
     * always NULL. Because SQLite allows multiple NULLs in a UNIQUE index, the
     * unique index on `currentUserGroupKey` correctly permits zero or one
     * current-user rows per group while ignoring all non-current-user rows.
     * This is the intended behavior — the constraint is NOT a no-op; it
     * correctly enforces "at most one current user" via the UNIQUE index + NULL
     * exemption.
     */
    val currentUserGroupKey: Long? = null,
    /** Timestamp when this member left the group, or null if still active. */
    val leftAt: Long? = null
)
