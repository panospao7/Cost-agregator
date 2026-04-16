package com.yourname.expensetracker.ui.screens.groups

import app.cash.turbine.test
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupDetailsAggregate
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.usecase.AddGroupExpenseUseCase
import com.yourname.expensetracker.domain.groups.usecase.DeleteGroupUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedExpenseGroupsViewModelTest : ViewModelTestUtils() {

    private val groupsRepository = mockk<GroupsRepository>(relaxed = true)
    private val addGroupExpenseUseCase = mockk<AddGroupExpenseUseCase>(relaxed = true)
    private val deleteGroupUseCase = mockk<DeleteGroupUseCase>(relaxed = true)
    private val manualExpenseRepository = mockk<ManualExpenseRepository>(relaxed = true).also { mock ->
        // Inject a TimeProvider into the mock so that Kotlin's $default method
        // for addManualExpense(date = timeProvider.now()) does not NPE.
        val timeProviderMock = mockk<TimeProvider> { every { now() } returns 1_700_000_000_000L }
        try {
            val field = ManualExpenseRepository::class.java.getDeclaredField("timeProvider")
            field.isAccessible = true
            field.set(mock, timeProviderMock)
        } catch (_: Exception) { /* field layout may differ */ }
    }
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)

    private lateinit var viewModel: SharedExpenseGroupsViewModel

    @Before
    override fun setup() {
        super.setup()
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns emptyList()
        viewModel = createViewModel()
    }

    @Test
    fun `initial state is loading then loaded with groups`() = runTest(testDispatcher) {
        val aggregate = createAggregate(groupId = 1L, name = "Trip")
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(aggregate)

        viewModel = createViewModel()

        viewModel.uiState.test {
            val initial = awaitItem()
            assertFalse(initial.isLoading)

            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val loaded = awaitItem()
            assertFalse(loaded.isLoading)
            assertEquals(1, loaded.groups.size)
            assertEquals("Trip", loaded.groups.first().group.name)
            assertNull(loaded.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `add expense triggers state update`() = runTest(testDispatcher) {
        val initialAggregate = createAggregate(groupId = 1L, name = "Trip", memberId = 11L)
        val updatedExpense = createGroupExpense(
            id = 301L,
            groupId = 1L,
            paidById = 11L,
            amount = 24.5
        )
        val updatedAggregate = createAggregate(
            groupId = 1L,
            name = "Trip",
            memberId = 11L,
            expenses = listOf(updatedExpense)
        )

        coEvery {
            groupsRepository.getActiveGroupsWithDetails()
        } returnsMany listOf(listOf(initialAggregate), listOf(updatedAggregate))

        coEvery { groupsRepository.getGroupById(1L) } returns initialAggregate.group
        coEvery { groupsRepository.getMemberById(11L) } returns initialAggregate.members.first()

        // B.4 Batch 2: Mock the new atomic path instead of the old two-step flow
        coEvery {
            addGroupExpenseUseCase.invokeAtomic(
                groupId = any(),
                description = any(),
                amount = any(),
                paidById = any(),
                currency = any(),
                splitType = any(),
                customSplitsJson = any(),
                date = any(),
                transactionType = any(),
                notes = any()
            )
        } returns GroupExpenseCreationResult.Success(groupExpenseId = 301L, expenseId = 900L)

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val beforeAdd = awaitItem()
            assertEquals(0, beforeAdd.groups.first().expenses.size)

            viewModel.addExpense(
                groupId = 1L,
                description = "Dinner",
                amount = 24.5,
                paidById = 11L,
                splitType = SplitType.EQUAL
            )

            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val afterAdd = awaitItem()
            assertFalse(afterAdd.isLoading)
            assertEquals(1, afterAdd.groups.first().expenses.size)
            assertEquals(24.5, afterAdd.groups.first().totalSpent, 0.0)

            cancelAndIgnoreRemainingEvents()
        }

        // B.4 Batch 2: Verify the atomic path was called (not the old two-step flow)
        coVerify(exactly = 1) {
            addGroupExpenseUseCase.invokeAtomic(
                groupId = 1L,
                description = "Dinner",
                amount = 24.5,
                paidById = 11L,
                currency = "EUR",
                splitType = SplitType.EQUAL,
                customSplitsJson = null,
                date = any(),
                transactionType = TransactionType.PURCHASE,
                notes = any()
            )
        }

        // B.4 Batch 2: Verify the old two-step flow is NOT used
        coVerify(exactly = 0) {
            manualExpenseRepository.addManualExpense(
                merchant = any(),
                amount = any(),
                currency = any(),
                categoryId = any(),
                transactionType = any(),
                paymentMethod = any(),
                date = any(),
                notes = any(),
                transferDirection = any(),
                transferAccountName = any(),
                isNotMine = any(),
                ownerName = any(),
                isSharedExpense = any(),
                sharedWithName = any(),
                mySharePercentage = any(),
                myShareAmount = any(),
                latitude = any(),
                longitude = any(),
                locationSource = any()
            )
        }
    }

    @Test
    fun `delete group removes from list`() = runTest(testDispatcher) {
        val group1 = createAggregate(groupId = 1L, name = "Trip")
        val group2 = createAggregate(groupId = 2L, name = "Home")

        coEvery {
            groupsRepository.getActiveGroupsWithDetails()
        } returnsMany listOf(listOf(group1, group2), listOf(group2))
        coEvery { deleteGroupUseCase.invoke(1L) } returns true

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val beforeDelete = awaitItem()
            assertEquals(2, beforeDelete.groups.size)

            viewModel.deleteGroup(1L)
            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val afterDelete = awaitItem()
            assertFalse(afterDelete.isLoading)
            assertEquals(1, afterDelete.groups.size)
            assertEquals(2L, afterDelete.groups.first().group.id)

            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { deleteGroupUseCase.invoke(1L) }
    }

    @Test
    fun `error in repository sets error state`() = runTest(testDispatcher) {
        coEvery {
            groupsRepository.getActiveGroupsWithDetails()
        } throws IllegalStateException("db unavailable")

        viewModel = createViewModel()

        viewModel.uiState.test {
            val initial = awaitItem()
            assertFalse(initial.isLoading)

            advanceUntilIdle()

            val loading = awaitItem()
            assertTrue(loading.isLoading)

            val errorState = awaitItem()
            assertFalse(errorState.isLoading)
            assertTrue(errorState.error?.contains("Failed to load groups: db unavailable") == true)
            assertTrue(errorState.groups.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== B.4 Batch 2 Tests ====================

    @Test
    fun `add expense error sets error state without orphan cleanup`() = runTest(testDispatcher) {
        val aggregate = createAggregate(groupId = 1L, name = "Trip", memberId = 11L)

        coEvery {
            groupsRepository.getActiveGroupsWithDetails()
        } returns listOf(aggregate)
        coEvery { groupsRepository.getGroupById(1L) } returns aggregate.group
        coEvery { groupsRepository.getMemberById(11L) } returns aggregate.members.first()

        // Simulate atomic call failure
        coEvery {
            addGroupExpenseUseCase.invokeAtomic(
                groupId = any(),
                description = any(),
                amount = any(),
                paidById = any(),
                currency = any(),
                splitType = any(),
                customSplitsJson = any(),
                date = any(),
                transactionType = any(),
                notes = any()
            )
        } returns GroupExpenseCreationResult.Error("Group not found or inactive")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // initial loaded state

            viewModel.addExpense(
                groupId = 1L,
                description = "Dinner",
                amount = 24.5,
                paidById = 11L,
                splitType = SplitType.EQUAL
            )

            advanceUntilIdle()

            val errorState = awaitItem()
            assertTrue(errorState.error?.contains("Group not found or inactive") == true)

            // B.4 Batch 2: Verify NO orphan cleanup is attempted (atomic path rolls back automatically)
            coVerify(exactly = 0) { expenseRepository.getExpenseById(any()) }
            coVerify(exactly = 0) { expenseRepository.deleteExpense(any()) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `add expense exception sets error state without orphan cleanup`() = runTest(testDispatcher) {
        val aggregate = createAggregate(groupId = 1L, name = "Trip", memberId = 11L)

        coEvery {
            groupsRepository.getActiveGroupsWithDetails()
        } returns listOf(aggregate)
        coEvery { groupsRepository.getGroupById(1L) } returns aggregate.group
        coEvery { groupsRepository.getMemberById(11L) } returns aggregate.members.first()

        // Simulate atomic call throwing exception
        coEvery {
            addGroupExpenseUseCase.invokeAtomic(
                groupId = any(),
                description = any(),
                amount = any(),
                paidById = any(),
                currency = any(),
                splitType = any(),
                customSplitsJson = any(),
                date = any(),
                transactionType = any(),
                notes = any()
            )
        } throws RuntimeException("DB transaction failed")

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // initial loaded state

            viewModel.addExpense(
                groupId = 1L,
                description = "Dinner",
                amount = 24.5,
                paidById = 11L,
                splitType = SplitType.EQUAL
            )

            advanceUntilIdle()

            val errorState = awaitItem()
            assertTrue(errorState.error?.contains("Failed to add expense") == true)
            assertTrue(errorState.error?.contains("DB transaction failed") == true)

            // B.4 Batch 2: Verify NO orphan cleanup is attempted
            coVerify(exactly = 0) { expenseRepository.getExpenseById(any()) }
            coVerify(exactly = 0) { expenseRepository.deleteExpense(any()) }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh preserves selected group and dialog flags`() = runTest(testDispatcher) {
        val initialAggregate = createAggregate(groupId = 1L, name = "Trip", memberId = 11L)
        val refreshedExpense = createGroupExpense(
            id = 401L,
            groupId = 1L,
            paidById = 11L,
            amount = 50.0
        )
        val refreshedAggregate = createAggregate(
            groupId = 1L,
            name = "Trip",
            memberId = 11L,
            expenses = listOf(refreshedExpense)
        )

        coEvery {
            groupsRepository.getActiveGroupsWithDetails()
        } returnsMany listOf(listOf(initialAggregate), listOf(refreshedAggregate))

        viewModel = createViewModel()
        advanceUntilIdle()

        val initiallySelected = viewModel.uiState.value.groups.first()
        viewModel.selectGroup(initiallySelected)
        viewModel.toggleCreateGroup(true)
        viewModel.toggleAddMember(true)
        viewModel.toggleAddExpense(true)

        viewModel.refresh()
        advanceUntilIdle()

        val refreshedState = viewModel.uiState.value
        assertTrue(refreshedState.creatingGroup)
        assertTrue(refreshedState.addingMember)
        assertTrue(refreshedState.addingExpense)
        assertEquals(1L, refreshedState.selectedGroup?.group?.id)
        assertEquals(1, refreshedState.selectedGroup?.expenses?.size)
        assertEquals(50.0, refreshedState.selectedGroup?.totalSpent, 0.0)
    }

    private fun createViewModel(): SharedExpenseGroupsViewModel {
        return SharedExpenseGroupsViewModel(
            groupsRepository = groupsRepository,
            addGroupExpenseUseCase = addGroupExpenseUseCase,
            deleteGroupUseCase = deleteGroupUseCase,
            manualExpenseRepository = manualExpenseRepository,
            expenseRepository = expenseRepository
        )
    }

    private fun createAggregate(
        groupId: Long,
        name: String,
        memberId: Long = 11L,
        expenses: List<GroupExpense> = emptyList()
    ): GroupDetailsAggregate {
        val group = ExpenseGroup(
            id = groupId,
            name = name,
            description = null,
            defaultCurrency = "EUR"
        )
        val members = listOf(
            GroupMember(
                id = memberId,
                groupId = groupId,
                name = "Alex",
                isCurrentUser = true
            )
        )
        return GroupDetailsAggregate(group = group, members = members, expenses = expenses)
    }

    private fun createGroupExpense(
        id: Long,
        groupId: Long,
        paidById: Long,
        amount: Double
    ): GroupExpense {
        return GroupExpense(
            id = id,
            groupId = groupId,
            expenseId = id + 1000,
            paidById = paidById,
            date = 1_700_000_000_000L,
            description = "Dinner",
            totalAmount = amount,
            currency = "EUR",
            splitType = SplitType.EQUAL
        )
    }
}
