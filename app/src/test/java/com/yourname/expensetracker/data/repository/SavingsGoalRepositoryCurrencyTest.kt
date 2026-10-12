package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SavingsGoalRepositoryCurrencyTest {
    private val dao = mockk<SavingsGoalDao>()
    private val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val repository = SavingsGoalRepository(barrier, dao)

    @Test
    fun `created goals preserve computation currency and provenance after repository round trip`() = runTest {
        val stored = MutableStateFlow(emptyList<com.yourname.expensetracker.data.database.entity.SavingsGoal>())
        every { dao.getAllGoals() } returns stored
        coEvery { dao.insertGoal(any()) } answers {
            val entity = firstArg<com.yourname.expensetracker.data.database.entity.SavingsGoal>()
            stored.value = stored.value + entity
            entity.id
        }
        val goals = listOf("USD", "EUR", "GBP").mapIndexed { index, currency ->
            SavingsGoal(index + 1L, "Goal $index", 200.0, 40.0, 1000L,
                GoalProtectionLevel.WARNING, currency, "EXPLICIT_HOME_CURRENCY", 500L)
        }
        for (goal in goals) assertEquals(goal.id, repository.createSavingsGoal(goal))
        assertEquals(goals, repository.observeSavingsGoals().first())
        assertEquals(goals, repository.getSavingsGoals())
        assertEquals(listOf("USD", "EUR", "GBP"), stored.value.map { it.currency })
        coVerify(exactly = 3) { barrier.checkWritesAllowed("SavingsGoalRepository.createSavingsGoal") }
    }

    @Test
    fun uncertainAndUnknownStoredProvenanceIsPreservedRatherThanPromoted() = runTest {
        for (provenance in listOf("UNKNOWN", "", "ASSUMED_HOME_CURRENCY", "ASSUMED_LEGACY_EUR", "unrecognized")) {
            val entity = com.yourname.expensetracker.data.database.entity.SavingsGoal(
                id = 9L, name = "Uncertain", targetAmount = 100.0, currentAmount = 0.0,
                currency = "EUR", currencyAssumption = provenance, createdAt = 500L
            )
            every { dao.getAllGoals() } returns MutableStateFlow(listOf(entity))
            val goal = repository.getSavingsGoals().single()
            assertEquals(provenance, goal.currencyAssumption)
            org.junit.Assert.assertNull(
                com.yourname.expensetracker.domain.savings.SavingsGoalCurrencyPolicy.knownCurrencyOrNull(goal)
            )
        }
    }

    @Test
    fun `legacy assumption remains explicitly legacy on reads and deletion`() = runTest {
        val entity = com.yourname.expensetracker.data.database.entity.SavingsGoal(
            id = 9L, name = "Legacy", targetAmount = 100.0, currentAmount = 30.0,
            currency = "EUR", currencyAssumption = "LEGACY_DEFAULT", createdAt = 500L
        )
        every { dao.getAllGoals() } returns MutableStateFlow(listOf(entity))
        coEvery { dao.deleteGoal(any()) } returns Unit
        val goal = repository.getSavingsGoals().single()
        assertEquals("EUR", goal.currency)
        assertEquals("LEGACY_DEFAULT", goal.currencyAssumption)
        repository.deleteSavingsGoal(goal)
        coVerify(exactly = 1) { dao.deleteGoal(entity) }
        coVerify(exactly = 1) { barrier.checkWritesAllowed("SavingsGoalRepository.deleteSavingsGoal") }
    }
}
