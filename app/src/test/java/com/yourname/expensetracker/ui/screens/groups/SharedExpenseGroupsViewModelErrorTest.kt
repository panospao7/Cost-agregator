package com.yourname.expensetracker.ui.screens.groups

import android.util.Log
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupDetailsAggregate
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.data.repository.ManualExpenseRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.groups.usecase.AddGroupExpenseUseCase
import com.yourname.expensetracker.domain.groups.usecase.AddGroupMemberUseCase
import com.yourname.expensetracker.domain.groups.usecase.DeleteGroupUseCase
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * J-18-1: failures never put exception text into UI state or logs; mutation failures keep the
 * loaded list (transient); a load failure keeps the full-screen error, cleared by retry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedExpenseGroupsViewModelErrorTest : ViewModelTestUtils() {

    private val sentinel = "SENTINEL_SQL_SECRET_9f3a"
    private val groupsRepository = mockk<GroupsRepository>(relaxed = true)
    private val addGroupMemberUseCase = mockk<AddGroupMemberUseCase>(relaxed = true)
    private val addGroupExpenseUseCase = mockk<AddGroupExpenseUseCase>(relaxed = true)
    private val deleteGroupUseCase = mockk<DeleteGroupUseCase>(relaxed = true)
    private val logLines = mutableListOf<String>()

    private val aggregate = GroupDetailsAggregate(
        group = ExpenseGroup(id = 1L, name = "Trip-$sentinel-name", description = null, defaultCurrency = "EUR"),
        members = listOf(GroupMember(id = 11L, groupId = 1L, name = "Alex", isCurrentUser = true)),
        expenses = emptyList()
    )

    @Before
    override fun setup() {
        super.setup()
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } answers { logLines += "${firstArg<String>()} ${secondArg<String>()}"; 0 }
        every { Log.e(any<String>(), any<String>()) } answers { logLines += "${firstArg<String>()} ${secondArg<String>()}"; 0 }
        every { Log.w(any<String>(), any<Throwable>()) } answers { logLines += "THROWABLE"; 0 }
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } answers { logLines += "THROWABLE"; 0 }
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } answers { logLines += "THROWABLE"; 0 }
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(aggregate)
        coEvery { groupsRepository.getGroupById(1L) } returns aggregate.group
    }

    @After
    fun tearDownLogMock() {
        unmockkStatic(Log::class)
    }

    private fun createViewModel(): SharedExpenseGroupsViewModel {
        val currencyRepo = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencyRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { currencyRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        return SharedExpenseGroupsViewModel(
            groupsRepository = groupsRepository,
            addGroupMemberUseCase = addGroupMemberUseCase,
            addGroupExpenseUseCase = addGroupExpenseUseCase,
            deleteGroupUseCase = deleteGroupUseCase,
            manualExpenseRepository = mockk<ManualExpenseRepository>(relaxed = true),
            expenseRepository = mockk<ExpenseRepository>(relaxed = true),
            currencySettingsRepository = currencyRepo,
        )
    }

    private fun assertSanitizedTransientFailure(viewModel: SharedExpenseGroupsViewModel, expected: String) {
        val state = viewModel.uiState.value
        assertEquals(expected, state.error)
        assertFalse(state.error!!.contains(sentinel))
        assertFalse(state.loadFailed)
        assertEquals(1, state.groups.size)
        assertFalse(state.isLoading)
        assertTrue(logLines.isNotEmpty())
        assertTrue(logLines.any { it.contains("IllegalStateException") })
        logLines.forEach {
            assertFalse(it.contains(sentinel))
            assertFalse(it.contains("Trip-"))
            assertFalse(it.contains("Alex"))
            assertTrue(it != "THROWABLE")
        }
        viewModel.clearError()
        assertNull(viewModel.uiState.value.error)
        assertEquals(1, viewModel.uiState.value.groups.size)
    }

    @Test
    fun `create group exception is sanitized and keeps loaded list`() = runTest(testDispatcher) {
        coEvery { groupsRepository.createGroup(any(), any(), any(), any()) } throws IllegalStateException(sentinel)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.createGroup("Trip-$sentinel-name", null, "EUR")
        advanceUntilIdle()

        assertSanitizedTransientFailure(viewModel, "Failed to create group")
    }

    @Test
    fun `add member exception is sanitized and keeps loaded list`() = runTest(testDispatcher) {
        coEvery { addGroupMemberUseCase.invoke(any(), any(), any(), any()) } throws IllegalStateException(sentinel)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addMember(1L, "Alex", null)
        advanceUntilIdle()

        assertSanitizedTransientFailure(viewModel, "Failed to add member")
    }

    @Test
    fun `add expense exception is sanitized and keeps loaded list`() = runTest(testDispatcher) {
        coEvery {
            addGroupExpenseUseCase.invokeAtomic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException(sentinel)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.addExpense(1L, "Dinner", 10.0, 11L, SplitType.EQUAL)
        advanceUntilIdle()

        assertSanitizedTransientFailure(viewModel, "Failed to add expense")
    }

    @Test
    fun `delete group exception is sanitized and keeps loaded list`() = runTest(testDispatcher) {
        coEvery { deleteGroupUseCase.invoke(1L) } throws IllegalStateException(sentinel)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteGroup(1L)
        advanceUntilIdle()

        assertSanitizedTransientFailure(viewModel, "Failed to delete group")
    }

    @Test
    fun `load failure keeps full screen error without exception text and retry clears it`() = runTest(testDispatcher) {
        coEvery { groupsRepository.getActiveGroupsWithDetails() } throws IllegalStateException(sentinel)
        val viewModel = createViewModel()
        advanceUntilIdle()

        val failed = viewModel.uiState.value
        assertEquals("Failed to load group balances", failed.error)
        assertTrue(failed.loadFailed)
        assertTrue(failed.groups.isEmpty())
        assertTrue(logLines.any { it.contains("IllegalStateException") })
        logLines.forEach {
            assertFalse(it.contains(sentinel))
            assertTrue(it != "THROWABLE")
        }

        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(aggregate)
        viewModel.refresh()
        advanceUntilIdle()

        val recovered = viewModel.uiState.value
        assertNull(recovered.error)
        assertFalse(recovered.loadFailed)
        assertEquals(1, recovered.groups.size)
    }

    @Test
    fun `mutation failure after failed load is not a load failure`() = runTest(testDispatcher) {
        coEvery { groupsRepository.getActiveGroupsWithDetails() } throws IllegalStateException(sentinel)
        coEvery { groupsRepository.createGroup(any(), any(), any(), any()) } throws IllegalStateException(sentinel)
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)

        viewModel.createGroup("g", null, "EUR")
        advanceUntilIdle()

        assertEquals("Failed to create group", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loadFailed)
    }

    @Test
    fun `cancellation propagates without error state or log`() = runTest(testDispatcher) {
        val cancel = CancellationException(sentinel)
        coEvery { groupsRepository.createGroup(any(), any(), any(), any()) } throws cancel
        coEvery { addGroupMemberUseCase.invoke(any(), any(), any(), any()) } throws cancel
        coEvery {
            addGroupExpenseUseCase.invokeAtomic(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws cancel
        coEvery { deleteGroupUseCase.invoke(1L) } throws cancel
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.groups.firstOrNull())

        viewModel.createGroup("g", null, "EUR")
        viewModel.addMember(1L, "Bob", null)
        viewModel.addExpense(1L, "Dinner", 10.0, 11L, SplitType.EQUAL)
        viewModel.deleteGroup(1L)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loadFailed)
        assertEquals(1, viewModel.uiState.value.groups.size)
        assertTrue(logLines.isEmpty())
    }
}
