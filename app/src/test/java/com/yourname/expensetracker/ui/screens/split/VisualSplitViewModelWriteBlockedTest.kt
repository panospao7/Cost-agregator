package com.yourname.expensetracker.ui.screens.split

import app.cash.turbine.test
import com.google.gson.Gson
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.SplitShare
import com.yourname.expensetracker.data.database.entity.SplitTemplate
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.split.EnhancedSplitManager
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

/** R18b: template writes rejected by the maintenance write barrier emit the shared message. */
@OptIn(ExperimentalCoroutinesApi::class)
class VisualSplitViewModelWriteBlockedTest : ViewModelTestUtils() {

    private val splitManager = mockk<EnhancedSplitManager>(relaxed = true)
    private lateinit var viewModel: VisualSplitViewModel

    private val blockedMessage = UiText.StringResource(R.string.ui_write_blocked_maintenance)

    @Before
    override fun setup() {
        super.setup()
        every { splitManager.getAllTemplates() } returns flowOf(emptyList())
        viewModel = VisualSplitViewModel(splitManager, mockk<Gson>(relaxed = true))
    }

    @Test
    fun `blocked createTemplate emits maintenance message without crashing`() = runTest(testDispatcher) {
        coEvery { splitManager.createTemplate(any(), any(), any(), any()) } throws blockedWriteException()

        viewModel.writeBlockedMessages.test {
            viewModel.createTemplate(
                "Flat", listOf(SplitShare(0, "A"), SplitShare(1, "B")), SplitTemplate.SplitType.EQUAL
            )
            advanceUntilIdle()
            assertEquals(blockedMessage, awaitItem())
        }
    }

    @Test
    fun `blocked createTemplate does not emit templateCreated`() = runTest(testDispatcher) {
        coEvery { splitManager.createTemplate(any(), any(), any(), any()) } throws blockedWriteException()

        viewModel.templateCreated.test {
            viewModel.createTemplate(
                "Flat", listOf(SplitShare(0, "A"), SplitShare(1, "B")), SplitTemplate.SplitType.EQUAL
            )
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    @Test
    fun `successful createTemplate emits templateCreated`() = runTest(testDispatcher) {
        coEvery { splitManager.createTemplate(any(), any(), any(), any()) } returns 3L

        viewModel.templateCreated.test {
            viewModel.createTemplate(
                "Flat", listOf(SplitShare(0, "A"), SplitShare(1, "B")), SplitTemplate.SplitType.EQUAL
            )
            advanceUntilIdle()
            assertEquals(Unit, awaitItem())
        }
    }

    @Test
    fun `blocked setDefaultTemplate emits maintenance message`() = runTest(testDispatcher) {
        coEvery { splitManager.setDefaultTemplate(any()) } throws blockedWriteException()

        viewModel.writeBlockedMessages.test {
            viewModel.setDefaultTemplate(5L)
            advanceUntilIdle()
            assertEquals(blockedMessage, awaitItem())
        }
    }

    @Test
    fun `blocked deleteTemplate emits maintenance message`() = runTest(testDispatcher) {
        coEvery { splitManager.deleteTemplate(any()) } throws blockedWriteException()

        viewModel.writeBlockedMessages.test {
            viewModel.deleteTemplate(mockk<SplitTemplate>(relaxed = true))
            advanceUntilIdle()
            assertEquals(blockedMessage, awaitItem())
        }
    }
}
