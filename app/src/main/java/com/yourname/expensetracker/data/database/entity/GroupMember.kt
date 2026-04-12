package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a member of an expense group.
 */
/**
 * Note: the partial unique index enforcing "at most one current user per group"
 * (`index_group_members_groupId_currentUser … WHERE isCurrentUser = 1`) cannot
 * be expressed via Room's @Index annotation, which does not support WHERE clauses.
 * It is applied by [AppDatabase.FRESH_INSTALL_CALLBACK] on fresh installs and by
 * [AppDatabase.MIGRATION_70_71] on upgrades.  The non-unique composite index
 * declared below serves query optimisation only.
 */
@Entity(
    tableName = "group_members",
    foreignKeys = [
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
        Index(value = ["groupId", "name"], unique = true)
    ]
)
data class GroupMember(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val groupId: Long,
    val name: String,              // Member name (e.g., "John", "Alice")
    val email: String? = null,     // Optional contact
    @ColumnInfo(defaultValue = "0") val isCurrentUser: Boolean = false, // Is this the app user?
    val joinedAt: Long = System.currentTimeMillis()
)
