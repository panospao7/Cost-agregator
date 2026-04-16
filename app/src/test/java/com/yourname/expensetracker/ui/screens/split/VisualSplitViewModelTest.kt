package com.yourname.expensetracker.ui.screens.split

import app.cash.turbine.test
import com.google.gson.Gson
import com.yourname.expensetracker.data.database.entity.SplitShare
import com.yourname.expensetracker.data.database.entity.SplitTemplate
import com.yourname.expensetracker.domain.split.EnhancedSplitManager
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisualSplitViewModelTest : ViewModelTestUtils() {

    private val splitManager = mockk<EnhancedSplitManager>(relaxed = true)
    private val gson = mockk<Gson>(relaxed = true)

    private lateinit var viewModel: VisualSplitViewModel

    @Before
    override fun setup() {
        super.setup()
        every { splitManager.getAllTemplates() } returns flowOf(emptyList())
        viewModel = VisualSplitViewModel(splitManager, gson)
    }

    @Test
    fun `initial state shows equal split`() = runTest(testDispatcher) {
        val participants = listOf(
            SplitShare(participantIndex = 0, participantName = "Alice", color = "#FF6B6B"),
            SplitShare(participantIndex = 1, participantName = "Bob", color = "#4ECDC4")
        )
        val expected = EnhancedSplitManager.VisualSplitData(
            totalAmount = 120.0,
            assignedAmount = 120.0,
            remainingAmount = 0.0,
            segments = listOf(
                EnhancedSplitManager.SplitSegment("Alice", 60.0, 50.0, "#FF6B6B", 0),
                EnhancedSplitManager.SplitSegment("Bob", 60.0, 50.0, "#4ECDC4", 1)
            )
        )

        every {
            splitManager.generateVisualSplitData(120.0, participants, SplitTemplate.SplitType.EQUAL)
        } returns expected

        viewModel.currentSplit.test {
            assertEquals(null, awaitItem())

            viewModel.calculateSplit(120.0, participants, SplitTemplate.SplitType.EQUAL)
            advanceUntilIdle()

            assertEquals(expected, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switch to percentage split updates state`() = runTest(testDispatcher) {
        val equalParticipants = listOf(
            SplitShare(participantIndex = 0, participantName = "Alice", color = "#FF6B6B"),
            SplitShare(participantIndex = 1, participantName = "Bob", color = "#4ECDC4")
        )
        val percentageParticipants = listOf(
            SplitShare(participantIndex = 0, participantName = "Alice", percentage = 25.0, color = "#FF6B6B"),
            SplitShare(participantIndex = 1, participantName = "Bob", percentage = 75.0, color = "#4ECDC4")
        )

        val equalData = EnhancedSplitManager.VisualSplitData(
            totalAmount = 200.0,
            assignedAmount = 200.0,
            remainingAmount = 0.0,
            segments = listOf(
                EnhancedSplitManager.SplitSegment("Alice", 100.0, 50.0, "#FF6B6B", 0),
                EnhancedSplitManager.SplitSegment("Bob", 100.0, 50.0, "#4ECDC4", 1)
            )
        )
        val percentageData = EnhancedSplitManager.VisualSplitData(
            totalAmount = 200.0,
            assignedAmount = 200.0,
            remainingAmount = 0.0,
            segments = listOf(
                EnhancedSplitManager.SplitSegment("Alice", 50.0, 25.0, "#FF6B6B", 0),
                EnhancedSplitManager.SplitSegment("Bob", 150.0, 75.0, "#4ECDC4", 1)
            )
        )

        every {
            splitManager.generateVisualSplitData(200.0, equalParticipants, SplitTemplate.SplitType.EQUAL)
        } returns equalData
        every {
            splitManager.generateVisualSplitData(200.0, percentageParticipants, SplitTemplate.SplitType.PERCENTAGE)
        } returns percentageData

        viewModel.currentSplit.test {
            assertEquals(null, awaitItem())

            viewModel.calculateSplit(200.0, equalParticipants, SplitTemplate.SplitType.EQUAL)
            advanceUntilIdle()
            assertEquals(equalData, awaitItem())

            viewModel.calculateSplit(200.0, percentageParticipants, SplitTemplate.SplitType.PERCENTAGE)
            advanceUntilIdle()
            assertEquals(percentageData, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `custom split editing validates input`() = runTest(testDispatcher) {
        val invalidCustom = listOf(
            SplitShare(participantIndex = 0, participantName = "Alice", amount = 40.0, color = "#FF6B6B"),
            SplitShare(participantIndex = 1, participantName = "Bob", amount = 40.0, color = "#4ECDC4")
        )
        val validCustom = listOf(
            SplitShare(participantIndex = 0, participantName = "Alice", amount = 55.0, color = "#FF6B6B"),
            SplitShare(participantIndex = 1, participantName = "Bob", amount = 45.0, color = "#4ECDC4")
        )

        val invalidData = EnhancedSplitManager.VisualSplitData(
            totalAmount = 100.0,
            assignedAmount = 80.0,
            remainingAmount = 20.0,
            segments = listOf(
                EnhancedSplitManager.SplitSegment("Alice", 40.0, 40.0, "#FF6B6B", 0),
                EnhancedSplitManager.SplitSegment("Bob", 40.0, 40.0, "#4ECDC4", 1)
            )
        )
        val validData = EnhancedSplitManager.VisualSplitData(
            totalAmount = 100.0,
            assignedAmount = 100.0,
            remainingAmount = 0.0,
            segments = listOf(
                EnhancedSplitManager.SplitSegment("Alice", 55.0, 55.0, "#FF6B6B", 0),
                EnhancedSplitManager.SplitSegment("Bob", 45.0, 45.0, "#4ECDC4", 1)
            )
        )

        every {
            splitManager.generateVisualSplitData(100.0, invalidCustom, SplitTemplate.SplitType.CUSTOM_AMOUNT)
        } returns invalidData
        every {
            splitManager.generateVisualSplitData(100.0, validCustom, SplitTemplate.SplitType.CUSTOM_AMOUNT)
        } returns validData

        viewModel.currentSplit.test {
            assertEquals(null, awaitItem())

            viewModel.calculateSplit(100.0, invalidCustom, SplitTemplate.SplitType.CUSTOM_AMOUNT)
            advanceUntilIdle()
            assertEquals(20.0, awaitItem()!!.remainingAmount, 0.0)

            viewModel.calculateSplit(100.0, validCustom, SplitTemplate.SplitType.CUSTOM_AMOUNT)
            advanceUntilIdle()
            assertEquals(0.0, awaitItem()!!.remainingAmount, 0.0)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invalid split shows error state`() = runTest(testDispatcher) {
        val invalidParticipants = listOf(
            SplitShare(participantIndex = 0, participantName = "Alice", amount = 70.0, color = "#FF6B6B"),
            SplitShare(participantIndex = 1, participantName = "Bob", amount = 50.0, color = "#4ECDC4")
        )
        val invalidData = EnhancedSplitManager.VisualSplitData(
            totalAmount = 100.0,
            assignedAmount = 120.0,
            remainingAmount = -20.0,
            segments = listOf(
                EnhancedSplitManager.SplitSegment("Alice", 70.0, 70.0, "#FF6B6B", 0),
                EnhancedSplitManager.SplitSegment("Bob", 50.0, 50.0, "#4ECDC4", 1)
            )
        )

        every {
            splitManager.generateVisualSplitData(100.0, invalidParticipants, SplitTemplate.SplitType.CUSTOM_AMOUNT)
        } returns invalidData

        viewModel.currentSplit.test {
            assertEquals(null, awaitItem())

            viewModel.calculateSplit(100.0, invalidParticipants, SplitTemplate.SplitType.CUSTOM_AMOUNT)
            advanceUntilIdle()

            val state = awaitItem()!!
            assertTrue(state.remainingAmount != 0.0)
            assertEquals(-20.0, state.remainingAmount, 0.0)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `build completed split shares matches duplicate names by participant index`() {
        val participants = listOf(
            SplitShare(participantIndex = 0, participantName = "Alex", color = "#FF6B6B"),
            SplitShare(participantIndex = 1, participantName = "Alex", color = "#4ECDC4")
        )
        val splitData = EnhancedSplitManager.VisualSplitData(
            totalAmount = 100.0,
            assignedAmount = 100.0,
            remainingAmount = 0.0,
            segments = listOf(
                EnhancedSplitManager.SplitSegment("Alex", 25.0, 25.0, "#FF6B6B", 0),
                EnhancedSplitManager.SplitSegment("Alex", 75.0, 75.0, "#4ECDC4", 1)
            )
        )

        val completedShares = buildCompletedSplitShares(participants, splitData)

        assertEquals(25.0, completedShares[0].amount, 0.0)
        assertEquals(25.0, completedShares[0].percentage, 0.0)
        assertEquals(75.0, completedShares[1].amount, 0.0)
        assertEquals(75.0, completedShares[1].percentage, 0.0)
    }
}
