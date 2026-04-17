package com.yourname.expensetracker.ui.screens.challenge

import com.yourname.expensetracker.domain.challenge.ActiveChallengesSnapshot
import com.yourname.expensetracker.domain.challenge.NoSpendStatus
import com.yourname.expensetracker.domain.challenge.SpendingChallenge
import com.yourname.expensetracker.domain.challenge.SpendingChallengeManager
import com.yourname.expensetracker.domain.challenge.ChallengeType
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpendingChallengesViewModelTest : ViewModelTestUtils() {

    private val challengeManager = mockk<SpendingChallengeManager>()

    private lateinit var viewModel: SpendingChallengesViewModel

    @Before
    override fun setup() {
        super.setup()
        coEvery { challengeManager.checkNoSpendStreak() } returns defaultNoSpendStatus()
        coEvery { challengeManager.getActiveChallengesSnapshot() } returns ActiveChallengesSnapshot(emptyList())
    }

    @Test
    fun `init loads active challenges when canonical source exists`() = runTest(testDispatcher) {
        val expectedChallenges = listOf(sampleChallenge())
        coEvery { challengeManager.getActiveChallengesSnapshot() } returns ActiveChallengesSnapshot(
            challenges = expectedChallenges,
            unavailableReason = null
        )

        viewModel = SpendingChallengesViewModel(challengeManager)
        advanceUntilIdle()

        assertEquals(expectedChallenges, viewModel.activeChallenges.value)
        assertTrue(viewModel.challengesAvailability.value.hasCanonicalSource)
        assertNull(viewModel.challengesAvailability.value.unavailableReason)
    }

    @Test
    fun `init keeps canonical source available when persisted source is empty`() = runTest(testDispatcher) {
        coEvery { challengeManager.getActiveChallengesSnapshot() } returns ActiveChallengesSnapshot(
            challenges = emptyList(),
            unavailableReason = null
        )

        viewModel = SpendingChallengesViewModel(challengeManager)
        advanceUntilIdle()

        assertTrue(viewModel.activeChallenges.value.isEmpty())
        assertTrue(viewModel.challengesAvailability.value.hasCanonicalSource)
        assertNull(viewModel.challengesAvailability.value.unavailableReason)
    }

    @Test
    fun `refresh reloads no spend status and active challenge availability`() = runTest(testDispatcher) {
        viewModel = SpendingChallengesViewModel(challengeManager)
        advanceUntilIdle()

        val refreshedStatus = defaultNoSpendStatus().copy(currentStreakDays = 4)
        val refreshedChallenges = listOf(sampleChallenge(id = 2L, name = "Weekend reset"))
        coEvery { challengeManager.checkNoSpendStreak() } returns refreshedStatus
        coEvery { challengeManager.getActiveChallengesSnapshot() } returns ActiveChallengesSnapshot(
            challenges = refreshedChallenges,
            unavailableReason = null
        )

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(refreshedStatus, viewModel.noSpendStatus.value)
        assertEquals(refreshedChallenges, viewModel.activeChallenges.value)
        assertTrue(viewModel.challengesAvailability.value.hasCanonicalSource)
        coVerify(atLeast = 2) { challengeManager.checkNoSpendStreak() }
        coVerify(atLeast = 2) { challengeManager.getActiveChallengesSnapshot() }
    }

    private fun defaultNoSpendStatus() = NoSpendStatus(
        hasNoSpendToday = true,
        currentStreakDays = 2,
        lastSpendDate = 1_700_000_000_000L,
        savedToday = 12.5,
        achievementUnlocked = false
    )

    private fun sampleChallenge(
        id: Long = 1L,
        name: String = "No coffee week"
    ) = SpendingChallenge(
        id = id,
        name = name,
        type = ChallengeType.NO_SPEND,
        startDate = 1_700_000_000_000L,
        endDate = 1_700_086_400_000L,
        targetAmount = null,
        categoryId = null,
        isActive = true,
        progress = 40.0,
        baselineAmount = null,
        baselineStartDate = null,
        baselineEndDate = null,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L
    )
}
