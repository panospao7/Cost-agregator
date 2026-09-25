package com.yourname.expensetracker.ui.screens.groups

import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedExpenseGroupsScreenStateTest {

    @Test
    fun `active member projection excludes departed members while preserving history`() {
        val alice = GroupMember(id = 1L, groupId = 7L, name = "Alice")
        val bob = GroupMember(id = 2L, groupId = 7L, name = "Bob", leftAt = 123L)
        val group = GroupWithDetails(
            group = ExpenseGroup(id = 7L, name = "Trip"),
            members = listOf(alice, bob),
            expenses = emptyList(),
            totalSpent = 0.0,
            memberBalances = emptyMap()
        )

        assertEquals(listOf(alice, bob), group.members)
        assertEquals(listOf(alice), group.activeMembers)
    }

    @Test
    fun `active member projection is empty when all members departed or absent`() {
        val departed = GroupMember(id = 3L, groupId = 8L, name = "Carol", leftAt = 456L)
        val allDeparted = GroupWithDetails(
            group = ExpenseGroup(id = 8L, name = "Old Trip"),
            members = listOf(departed),
            expenses = emptyList(),
            totalSpent = 0.0,
            memberBalances = emptyMap()
        )
        val empty = allDeparted.copy(members = emptyList())

        assertEquals(listOf(departed), allDeparted.members)
        assertTrue(empty.members.isEmpty())
        assertTrue(allDeparted.activeMembers.isEmpty())
        assertTrue(empty.activeMembers.isEmpty())
    }

    @Test
    fun `isSettledBalance treats near-zero values as settled at currency precision`() {
        assertTrue(isSettledBalance(0.004, fractionDigits = 2))
        assertTrue(isSettledBalance(-0.004, fractionDigits = 2))
        assertFalse(isSettledBalance(0.005, fractionDigits = 2))
        assertFalse(isSettledBalance(-0.005, fractionDigits = 2))
    }

    @Test
    fun `roundedBalanceForDisplay rounds to requested fraction digits`() {
        assertEquals(1.24, roundedBalanceForDisplay(1.235, fractionDigits = 2), 0.0)
        assertEquals(-1.24, roundedBalanceForDisplay(-1.235, fractionDigits = 2), 0.0)
        assertEquals(0.01, roundedBalanceForDisplay(0.005, fractionDigits = 2), 0.0)
        assertEquals(-0.01, roundedBalanceForDisplay(-0.005, fractionDigits = 2), 0.0)
        assertEquals(10.0, roundedBalanceForDisplay(9.6, fractionDigits = 0), 0.0)
    }
}
