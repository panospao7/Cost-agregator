package com.yourname.expensetracker.data.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember

/**
 * Batched aggregate for active groups to avoid per-group DAO fan-out.
 */
data class ExpenseGroupWithDetails(
    @Embedded
    val group: ExpenseGroup,
    @Relation(
        parentColumn = "id",
        entityColumn = "groupId"
    )
    val members: List<GroupMember>,
    @Relation(
        parentColumn = "id",
        entityColumn = "groupId"
    )
    val expenses: List<GroupExpense>
)
