package com.yourname.expensetracker.domain.groups.usecase

import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.repository.DeleteGroupMemberResult
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupUseCasesTest {

    private val groupsRepository = mockk<GroupsRepository>()
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1000L }

    private val addGroupExpenseUseCase = AddGroupExpenseUseCase(groupsRepository, timeProvider)
    private val deleteGroupUseCase = DeleteGroupUseCase(groupsRepository)
    private val deleteGroupMemberUseCase = DeleteGroupMemberUseCase(groupsRepository)

    @Test
    fun `add group expense delegates to repository with provided arguments`() = runTest {
        coEvery { groupsRepository.getGroupById(1L) } returns ExpenseGroup(id = 1L, name = "Trip", isActive = true)
        coEvery { groupsRepository.getMemberById(2L) } returns GroupMember(id = 2L, groupId = 1L, name = "Alice")
        coEvery {
            groupsRepository.addExpenseWithLink(
                groupId = 1L,
                systemExpenseId = 10L,
                description = "Dinner",
                amount = 45.0,
                paidById = 2L,
                splitType = SplitType.EQUAL,
                customSplitsJson = null,
                date = 1234L
            )
        } returns GroupExpenseCreationResult.Success(groupExpenseId = 99L, expenseId = 10L)

        val result = addGroupExpenseUseCase(
            groupId = 1L,
            systemExpenseId = 10L,
            description = "Dinner",
            amount = 45.0,
            paidById = 2L,
            splitType = SplitType.EQUAL,
            customSplitsJson = null,
            date = 1234L
        )

        assertTrue(result is GroupExpenseCreationResult.Success)
        coVerify(exactly = 1) {
            groupsRepository.addExpenseWithLink(
                groupId = 1L,
                systemExpenseId = 10L,
                description = "Dinner",
                amount = 45.0,
                paidById = 2L,
                splitType = SplitType.EQUAL,
                customSplitsJson = null,
                date = 1234L
            )
        }
    }

    @Test
    fun `add group expense returns error when group is missing`() = runTest {
        coEvery { groupsRepository.getGroupById(1L) } returns null

        val result = addGroupExpenseUseCase(
            groupId = 1L,
            systemExpenseId = 10L,
            description = "Dinner",
            amount = 45.0,
            paidById = 2L,
            splitType = SplitType.EQUAL
        )

        assertTrue(result is GroupExpenseCreationResult.Error)
        result as GroupExpenseCreationResult.Error
        assertEquals("Group not found", result.message)
    }

    @Test
    fun `add group expense returns error when group is inactive`() = runTest {
        coEvery { groupsRepository.getGroupById(1L) } returns ExpenseGroup(id = 1L, name = "Trip", isActive = false)

        val result = addGroupExpenseUseCase(
            groupId = 1L,
            systemExpenseId = 10L,
            description = "Dinner",
            amount = 45.0,
            paidById = 2L,
            splitType = SplitType.EQUAL
        )

        assertTrue(result is GroupExpenseCreationResult.Error)
        result as GroupExpenseCreationResult.Error
        assertEquals("Group not found or inactive", result.message)
    }

    @Test
    fun `add group expense returns error when payer is missing or in another group`() = runTest {
        coEvery { groupsRepository.getGroupById(1L) } returns ExpenseGroup(id = 1L, name = "Trip", isActive = true)
        coEvery { groupsRepository.getMemberById(2L) } returns null

        val missingPayer = addGroupExpenseUseCase(
            groupId = 1L,
            systemExpenseId = 10L,
            description = "Dinner",
            amount = 45.0,
            paidById = 2L,
            splitType = SplitType.EQUAL
        )

        assertTrue(missingPayer is GroupExpenseCreationResult.Error)
        missingPayer as GroupExpenseCreationResult.Error
        assertEquals("Payer not found", missingPayer.message)

        coEvery { groupsRepository.getMemberById(2L) } returns GroupMember(id = 2L, groupId = 9L, name = "Bob")

        val wrongGroupPayer = addGroupExpenseUseCase(
            groupId = 1L,
            systemExpenseId = 10L,
            description = "Dinner",
            amount = 45.0,
            paidById = 2L,
            splitType = SplitType.EQUAL
        )

        assertTrue(wrongGroupPayer is GroupExpenseCreationResult.Error)
        wrongGroupPayer as GroupExpenseCreationResult.Error
        assertEquals("Payer is not a member of this group", wrongGroupPayer.message)
    }

    @Test
    fun `add group expense propagates repository exceptions`() = runTest {
        coEvery { groupsRepository.getGroupById(1L) } returns ExpenseGroup(id = 1L, name = "Trip", isActive = true)
        coEvery { groupsRepository.getMemberById(2L) } returns GroupMember(id = 2L, groupId = 1L, name = "Alice")
        coEvery {
            groupsRepository.addExpenseWithLink(
                groupId = 1L,
                systemExpenseId = 10L,
                description = "Dinner",
                amount = 45.0,
                paidById = 2L,
                splitType = SplitType.EQUAL,
                customSplitsJson = null,
                date = 2222L
            )
        } throws IllegalStateException("db down")

        try {
            addGroupExpenseUseCase(
                groupId = 1L,
                systemExpenseId = 10L,
                description = "Dinner",
                amount = 45.0,
                paidById = 2L,
                splitType = SplitType.EQUAL,
                date = 2222L
            )
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("db down", e.message)
        }
    }

    @Test
    fun `delete group use case delegates and returns repository value`() = runTest {
        coEvery { groupsRepository.deleteGroup(55L) } returns true

        val result = deleteGroupUseCase(55L)

        assertTrue(result)
        coVerify(exactly = 1) { groupsRepository.deleteGroup(55L) }
    }

    @Test
    fun `delete group use case propagates repository exceptions`() = runTest {
        coEvery { groupsRepository.deleteGroup(55L) } throws RuntimeException("delete failed")

        try {
            deleteGroupUseCase(55L)
            throw AssertionError("Expected RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("delete failed", e.message)
        }
    }

    @Test
    fun `delete group member use case delegates and propagates repository result and errors`() = runTest {
        coEvery { groupsRepository.deleteMember(3L, 7L) } returns DeleteGroupMemberResult.CannotDeleteMemberWithExpenses(2)

        val result = deleteGroupMemberUseCase(3L, 7L)

        assertTrue(result is DeleteGroupMemberResult.CannotDeleteMemberWithExpenses)
        result as DeleteGroupMemberResult.CannotDeleteMemberWithExpenses
        assertEquals(2, result.expenseCount)

        coEvery { groupsRepository.deleteMember(3L, 8L) } throws IllegalArgumentException("boom")

        try {
            deleteGroupMemberUseCase(3L, 8L)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("boom", e.message)
        }
    }
}
