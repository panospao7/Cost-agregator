package com.yourname.expensetracker.ui.screens.categories

import app.cash.turbine.test
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DeleteCategoryResult
import com.yourname.expensetracker.data.repository.RenameCategoryResult
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.util.blockedWriteException
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Category rename and delete (owner item 144, slice C2): dialog state, result mapping, guards. */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelManageTest : ViewModelTestUtils() {

    private val repository = mockk<CategoryRepository>(relaxed = true)

    private val food = Category(id = 1, name = "Food", icon = "F", color = "#FF0000")
    private val uncategorized = Category(id = 2, name = "Uncategorized", icon = "U", color = "#607D8B", isDefault = true)
    private val travel = Category(id = 3, name = "Travel", icon = "T", color = "#00FF00")

    @Before
    override fun setup() {
        super.setup()
        // Travel is listed before Uncategorized so the default pick is by name, not by position.
        every { repository.allCategories } returns flowOf(listOf(food, travel, uncategorized))
        coEvery { repository.countExpensesForCategory(any()) } returns 0
    }

    private fun msg(resId: Int, vararg args: Any) = UiText.StringResource(resId, args.toList())

    // ---- rename: opening ------------------------------------------------------------------------

    @Test
    fun `requestRename opens a prefilled dialog for a non-default category`() = runTest(testDispatcher) {
        val vm = CategoryViewModel(repository)
        vm.requestRename(food.id)
        advanceUntilIdle()

        assertEquals(RenameDialogState(food.id, "Food"), vm.renameDialog.value)
    }

    @Test
    fun `requestRename on a default category shows the protected message and no dialog`() = runTest(testDispatcher) {
        val vm = CategoryViewModel(repository)
        vm.messages.test {
            vm.requestRename(uncategorized.id)
            advanceUntilIdle()
            assertEquals(msg(R.string.category_manage_default_protected), awaitItem())
        }
        assertNull(vm.renameDialog.value)
    }

    @Test
    fun `requestRename on an unknown category shows not found and no dialog`() = runTest(testDispatcher) {
        val vm = CategoryViewModel(repository)
        vm.messages.test {
            vm.requestRename(999L)
            advanceUntilIdle()
            assertEquals(msg(R.string.category_manage_not_found), awaitItem())
        }
        assertNull(vm.renameDialog.value)
    }

    // ---- rename: result mapping -----------------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.openRename(vm: CategoryViewModel) {
        vm.requestRename(food.id)
        advanceUntilIdle()
    }

    @Test
    fun `Renamed closes the dialog and emits the success message with the trimmed name`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(food.id, any()) } returns RenameCategoryResult.Renamed
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.messages.test {
            vm.renameCategory(food.id, "  Meals ")
            advanceUntilIdle()
            assertEquals(msg(R.string.category_rename_success, "Meals"), awaitItem())
        }
        assertNull(vm.renameDialog.value)
    }

    @Test
    fun `EmptyName keeps the dialog open with the empty-name inline error`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(any(), any()) } returns RenameCategoryResult.EmptyName
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.renameCategory(food.id, " ")
        advanceUntilIdle()

        val state = vm.renameDialog.value!!
        assertEquals(msg(R.string.category_error_name_empty), state.error)
        assertFalse(state.inFlight)
    }

    @Test
    fun `InvalidCharacters keeps the dialog open with the invalid-characters inline error`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(any(), any()) } returns RenameCategoryResult.InvalidCharacters
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.renameCategory(food.id, "bad\u0007")
        advanceUntilIdle()

        val state = vm.renameDialog.value!!
        assertEquals(msg(R.string.category_error_invalid_characters), state.error)
        assertFalse(state.inFlight)
    }

    @Test
    fun `Duplicate keeps the dialog open with the duplicate inline error`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(any(), any()) } returns RenameCategoryResult.Duplicate
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.renameCategory(food.id, "Travel")
        advanceUntilIdle()

        val state = vm.renameDialog.value!!
        assertEquals(msg(R.string.category_rename_error_duplicate), state.error)
        assertFalse(state.inFlight)
    }

    @Test
    fun `CannotRenameDefault closes the dialog and emits the protected message`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(any(), any()) } returns RenameCategoryResult.CannotRenameDefault
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.messages.test {
            vm.renameCategory(food.id, "X")
            advanceUntilIdle()
            assertEquals(msg(R.string.category_manage_default_protected), awaitItem())
        }
        assertNull(vm.renameDialog.value)
    }

    @Test
    fun `rename NotFound closes the dialog and emits the not found message`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(any(), any()) } returns RenameCategoryResult.NotFound
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.messages.test {
            vm.renameCategory(food.id, "X")
            advanceUntilIdle()
            assertEquals(msg(R.string.category_manage_not_found), awaitItem())
        }
        assertNull(vm.renameDialog.value)
    }

    @Test
    fun `an inline error clears when the next rename attempt starts`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(any(), any()) } returnsMany listOf(
            RenameCategoryResult.Duplicate,
            RenameCategoryResult.Renamed
        )
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.renameCategory(food.id, "Travel")
        advanceUntilIdle()
        assertTrue(vm.renameDialog.value!!.error != null)

        vm.renameCategory(food.id, "Trips")
        // Synchronously after the call the retry is in flight with the old error cleared.
        assertEquals(RenameDialogState(food.id, "Food", error = null, inFlight = true), vm.renameDialog.value)
        advanceUntilIdle()
        assertNull(vm.renameDialog.value)
    }

    @Test
    fun `unexpected rename failure keeps the dialog open and emits a generic message`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(any(), any()) } throws IllegalStateException("boom")
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.messages.test {
            vm.renameCategory(food.id, "X")
            advanceUntilIdle()
            assertEquals(msg(R.string.category_manage_failed), awaitItem())
        }
        assertFalse(vm.renameDialog.value!!.inFlight)
    }

    @Test
    fun `rename double tap calls the repository once`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(any(), any()) } coAnswers {
            delay(100)
            RenameCategoryResult.Renamed
        }
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.renameCategory(food.id, "Meals")
        vm.renameCategory(food.id, "Meals")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.renameCategory(food.id, any()) }
    }

    @Test
    fun `rename cancellation is rethrown into the coroutine, not shown as a message`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(any(), any()) } throws CancellationException("cancelled")
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.messages.test {
            vm.renameCategory(food.id, "Meals")
            advanceUntilIdle()
            expectNoEvents()
        }
        // The dialog is not left stuck in flight.
        assertFalse(vm.renameDialog.value!!.inFlight)
    }

    @Test
    fun `blocked rename emits the maintenance message and keeps the dialog open`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(any(), any()) } throws blockedWriteException()
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.writeBlockedMessages.test {
            vm.renameCategory(food.id, "Meals")
            advanceUntilIdle()
            assertEquals(msg(R.string.ui_write_blocked_maintenance), awaitItem())
        }
        val state = vm.renameDialog.value!!
        assertFalse(state.inFlight)
        assertNull(state.error)
    }

    @Test
    fun `dismissRename closes an idle dialog but not one that is in flight`() = runTest(testDispatcher) {
        coEvery { repository.renameCategory(any(), any()) } coAnswers {
            delay(100)
            RenameCategoryResult.Renamed
        }
        val vm = CategoryViewModel(repository)
        openRename(vm)

        vm.renameCategory(food.id, "Meals")
        vm.dismissRename()
        assertTrue(vm.renameDialog.value != null)
        advanceUntilIdle()
        assertNull(vm.renameDialog.value)

        openRename(vm)
        vm.dismissRename()
        assertNull(vm.renameDialog.value)
    }

    // ---- delete: opening, count, picker ---------------------------------------------------------

    @Test
    fun `requestDelete loads the count, excludes the source and defaults to Uncategorized`() = runTest(testDispatcher) {
        coEvery { repository.countExpensesForCategory(food.id) } returns 7
        val vm = CategoryViewModel(repository)
        vm.requestDelete(food.id)
        advanceUntilIdle()

        val state = vm.deleteDialog.value!!
        assertEquals(food.id, state.categoryId)
        assertEquals(ExpenseCountState.Loaded(7), state.expenseCount)
        assertEquals(listOf(travel, uncategorized), state.candidates)
        assertTrue(state.candidates.none { it.id == food.id })
        assertEquals(uncategorized.id, state.replacementId)
        assertTrue(state.canConfirm)
        coVerify(exactly = 1) { repository.countExpensesForCategory(food.id) }
    }

    @Test
    fun `confirm stays disabled while the count is loading`() = runTest(testDispatcher) {
        coEvery { repository.countExpensesForCategory(any()) } coAnswers {
            delay(100)
            3
        }
        val vm = CategoryViewModel(repository)
        vm.requestDelete(food.id)
        runCurrent()

        assertEquals(ExpenseCountState.Loading, vm.deleteDialog.value!!.expenseCount)
        assertFalse(vm.deleteDialog.value!!.canConfirm)

        advanceUntilIdle()
        assertEquals(ExpenseCountState.Loaded(3), vm.deleteDialog.value!!.expenseCount)
        assertTrue(vm.deleteDialog.value!!.canConfirm)
    }

    @Test
    fun `an unavailable count is never shown as zero`() = runTest(testDispatcher) {
        coEvery { repository.countExpensesForCategory(any()) } throws IllegalStateException("db")
        val vm = CategoryViewModel(repository)
        vm.requestDelete(food.id)
        advanceUntilIdle()

        val state = vm.deleteDialog.value!!
        assertEquals(ExpenseCountState.Unavailable, state.expenseCount)
        assertTrue(state.canConfirm)
    }

    @Test
    fun `a loaded count of zero stays zero`() = runTest(testDispatcher) {
        coEvery { repository.countExpensesForCategory(any()) } returns 0
        val vm = CategoryViewModel(repository)
        vm.requestDelete(food.id)
        advanceUntilIdle()

        assertEquals(ExpenseCountState.Loaded(0), vm.deleteDialog.value!!.expenseCount)
    }

    @Test
    fun `requestDelete on a default category shows the protected message and no dialog`() = runTest(testDispatcher) {
        val vm = CategoryViewModel(repository)
        vm.messages.test {
            vm.requestDelete(uncategorized.id)
            advanceUntilIdle()
            assertEquals(msg(R.string.category_manage_default_protected), awaitItem())
        }
        assertNull(vm.deleteDialog.value)
        coVerify(exactly = 0) { repository.countExpensesForCategory(any()) }
    }

    @Test
    fun `requestDelete on an unknown category shows not found and no dialog`() = runTest(testDispatcher) {
        val vm = CategoryViewModel(repository)
        vm.messages.test {
            vm.requestDelete(999L)
            advanceUntilIdle()
            assertEquals(msg(R.string.category_manage_not_found), awaitItem())
        }
        assertNull(vm.deleteDialog.value)
    }

    @Test
    fun `selectReplacement accepts a candidate and ignores the source or unknown ids`() = runTest(testDispatcher) {
        val vm = CategoryViewModel(repository)
        vm.requestDelete(food.id)
        advanceUntilIdle()

        vm.selectReplacement(travel.id)
        assertEquals(travel.id, vm.deleteDialog.value!!.replacementId)

        vm.selectReplacement(food.id)
        assertEquals(travel.id, vm.deleteDialog.value!!.replacementId)

        vm.selectReplacement(999L)
        assertEquals(travel.id, vm.deleteDialog.value!!.replacementId)
    }

    // ---- delete: result mapping -----------------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.openDelete(vm: CategoryViewModel) {
        vm.requestDelete(food.id)
        advanceUntilIdle()
    }

    @Test
    fun `Deleted closes the dialog and emits the success message with both names`() = runTest(testDispatcher) {
        coEvery { repository.deleteCategory(food.id, uncategorized.id) } returns DeleteCategoryResult.Deleted
        val vm = CategoryViewModel(repository)
        openDelete(vm)

        vm.messages.test {
            vm.confirmDelete(food.id, uncategorized.id)
            advanceUntilIdle()
            assertEquals(msg(R.string.category_delete_success, "Food", "Uncategorized"), awaitItem())
        }
        assertNull(vm.deleteDialog.value)
        coVerify(exactly = 1) { repository.deleteCategory(food.id, uncategorized.id) }
    }

    @Test
    fun `delete CannotDeleteDefault closes the dialog and emits the protected message`() = runTest(testDispatcher) {
        coEvery { repository.deleteCategory(any(), any()) } returns DeleteCategoryResult.CannotDeleteDefault
        val vm = CategoryViewModel(repository)
        openDelete(vm)

        vm.messages.test {
            vm.confirmDelete(food.id, uncategorized.id)
            advanceUntilIdle()
            assertEquals(msg(R.string.category_manage_default_protected), awaitItem())
        }
        assertNull(vm.deleteDialog.value)
    }

    @Test
    fun `delete NotFound closes the dialog and emits the not found message`() = runTest(testDispatcher) {
        coEvery { repository.deleteCategory(any(), any()) } returns DeleteCategoryResult.NotFound
        val vm = CategoryViewModel(repository)
        openDelete(vm)

        vm.messages.test {
            vm.confirmDelete(food.id, uncategorized.id)
            advanceUntilIdle()
            assertEquals(msg(R.string.category_manage_not_found), awaitItem())
        }
        assertNull(vm.deleteDialog.value)
    }

    @Test
    fun `ReplacementInvalid and SameAsSource keep the dialog open with the replacement error`() = runTest(testDispatcher) {
        coEvery { repository.deleteCategory(any(), any()) } returnsMany listOf(
            DeleteCategoryResult.ReplacementInvalid,
            DeleteCategoryResult.SameAsSource
        )
        val vm = CategoryViewModel(repository)
        openDelete(vm)

        repeat(2) {
            vm.confirmDelete(food.id, uncategorized.id)
            advanceUntilIdle()
            val state = vm.deleteDialog.value!!
            assertEquals(msg(R.string.category_delete_error_replacement), state.error)
            assertFalse(state.inFlight)
        }
    }

    @Test
    fun `confirmDelete with the source as replacement is ignored`() = runTest(testDispatcher) {
        val vm = CategoryViewModel(repository)
        openDelete(vm)

        vm.confirmDelete(food.id, food.id)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.deleteCategory(any(), any()) }
        assertFalse(vm.deleteDialog.value!!.inFlight)
    }

    @Test
    fun `unexpected delete failure keeps the dialog open and emits a generic message`() = runTest(testDispatcher) {
        coEvery { repository.deleteCategory(any(), any()) } throws IllegalStateException("boom")
        val vm = CategoryViewModel(repository)
        openDelete(vm)

        vm.messages.test {
            vm.confirmDelete(food.id, uncategorized.id)
            advanceUntilIdle()
            assertEquals(msg(R.string.category_manage_failed), awaitItem())
        }
        assertFalse(vm.deleteDialog.value!!.inFlight)
    }

    @Test
    fun `delete double tap calls the repository once`() = runTest(testDispatcher) {
        coEvery { repository.deleteCategory(any(), any()) } coAnswers {
            delay(100)
            DeleteCategoryResult.Deleted
        }
        val vm = CategoryViewModel(repository)
        openDelete(vm)

        vm.confirmDelete(food.id, uncategorized.id)
        vm.confirmDelete(food.id, uncategorized.id)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteCategory(food.id, uncategorized.id) }
    }

    @Test
    fun `replacement cannot change and dismiss is ignored while a delete is in flight`() = runTest(testDispatcher) {
        coEvery { repository.deleteCategory(any(), any()) } coAnswers {
            delay(100)
            DeleteCategoryResult.Deleted
        }
        val vm = CategoryViewModel(repository)
        openDelete(vm)

        vm.confirmDelete(food.id, uncategorized.id)
        vm.selectReplacement(travel.id)
        vm.dismissDelete()

        val state = vm.deleteDialog.value!!
        assertEquals(uncategorized.id, state.replacementId)
        assertTrue(state.inFlight)
        assertFalse(state.canConfirm)
        advanceUntilIdle()
        assertNull(vm.deleteDialog.value)
    }

    @Test
    fun `delete cancellation is rethrown into the coroutine, not shown as a message`() = runTest(testDispatcher) {
        coEvery { repository.deleteCategory(any(), any()) } throws CancellationException("cancelled")
        val vm = CategoryViewModel(repository)
        openDelete(vm)

        vm.messages.test {
            vm.confirmDelete(food.id, uncategorized.id)
            advanceUntilIdle()
            expectNoEvents()
        }
        assertFalse(vm.deleteDialog.value!!.inFlight)
    }

    @Test
    fun `count cancellation does not mark the count unavailable`() = runTest(testDispatcher) {
        coEvery { repository.countExpensesForCategory(any()) } throws CancellationException("cancelled")
        val vm = CategoryViewModel(repository)
        vm.requestDelete(food.id)
        advanceUntilIdle()

        assertEquals(ExpenseCountState.Loading, vm.deleteDialog.value!!.expenseCount)
    }

    @Test
    fun `blocked delete emits the maintenance message and keeps the dialog open`() = runTest(testDispatcher) {
        coEvery { repository.deleteCategory(any(), any()) } throws blockedWriteException()
        val vm = CategoryViewModel(repository)
        openDelete(vm)

        vm.writeBlockedMessages.test {
            vm.confirmDelete(food.id, uncategorized.id)
            advanceUntilIdle()
            assertEquals(msg(R.string.ui_write_blocked_maintenance), awaitItem())
        }
        val state = vm.deleteDialog.value!!
        assertFalse(state.inFlight)
        assertNull(state.error)
    }
}
