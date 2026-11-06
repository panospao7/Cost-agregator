package com.yourname.expensetracker.ui.screens.categories

import app.cash.turbine.test
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.util.blockedWriteException
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** R18 review: category writes rejected by the maintenance write barrier surface the bounded message. */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelWriteBlockedTest : ViewModelTestUtils() {

    private val repository = mockk<CategoryRepository>(relaxed = true)
    private val blockedMessage = UiText.StringResource(R.string.ui_write_blocked_maintenance)

    @Before
    override fun setup() {
        super.setup()
        every { repository.allCategories } returns flowOf(emptyList())
    }

    @Test
    fun `blocked addCategory emits maintenance message without crashing`() = runTest(testDispatcher) {
        val viewModel = CategoryViewModel(repository)
        advanceUntilIdle()
        coEvery { repository.addCategory(any(), any(), any()) } throws blockedWriteException()

        viewModel.writeBlockedMessages.test {
            viewModel.addCategory("Food", "icon", "#FFFFFF")
            advanceUntilIdle()
            assertEquals(blockedMessage, awaitItem())
        }
    }

    @Test
    fun `blocked renameCategory emits maintenance message without crashing`() = runTest(testDispatcher) {
        val category = Category(id = 1, name = "Food", icon = "F", color = "#FF0000")
        every { repository.allCategories } returns flowOf(listOf(category))
        val viewModel = CategoryViewModel(repository)
        viewModel.requestRename(category.id)
        advanceUntilIdle()
        coEvery { repository.renameCategory(any(), any()) } throws blockedWriteException()

        viewModel.writeBlockedMessages.test {
            viewModel.renameCategory(category.id, "Meals")
            advanceUntilIdle()
            assertEquals(blockedMessage, awaitItem())
        }
        // The dialog stays open and is not stuck in flight, so the user can retry.
        assertEquals(RenameDialogState(category.id, "Food"), viewModel.renameDialog.value)
    }

    @Test
    fun `blocked confirmDelete emits maintenance message without crashing`() = runTest(testDispatcher) {
        val source = Category(id = 1, name = "Food", icon = "F", color = "#FF0000")
        val fallback = Category(id = 2, name = "Uncategorized", icon = "U", color = "#607D8B", isDefault = true)
        every { repository.allCategories } returns flowOf(listOf(source, fallback))
        coEvery { repository.countExpensesForCategory(any()) } returns 2
        val viewModel = CategoryViewModel(repository)
        viewModel.requestDelete(source.id)
        advanceUntilIdle()
        coEvery { repository.deleteCategory(any(), any()) } throws blockedWriteException()

        viewModel.writeBlockedMessages.test {
            viewModel.confirmDelete(source.id, fallback.id)
            advanceUntilIdle()
            assertEquals(blockedMessage, awaitItem())
        }
        assertEquals(false, viewModel.deleteDialog.value?.inFlight)
    }

    @Test
    fun `blocked init ensureDefaultCategories does not crash`()= runTest(testDispatcher) {
        coEvery { repository.ensureDefaultCategories() } throws blockedWriteException()

        // Construction launches the seeding write; the block must be absorbed, not thrown.
        val viewModel = CategoryViewModel(repository)
        advanceUntilIdle()

        assertEquals(emptyList<Any>(), viewModel.categories.value)
    }
}
