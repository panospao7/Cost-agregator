package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.SavingsContributionLedgerDao
import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.savings.SavingsGoalDeleteResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SavingsGoalRepositoryCurrencyTest {
    private val dao = mockk<SavingsGoalDao>()
    private val barrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val history = mockk<SavingsContributionHistoryRepository>()
    private val ledgerDao = mockk<SavingsContributionLedgerDao>()
    private val repository = SavingsGoalRepository(barrier, dao, database, history, ledgerDao)

    @Before
    fun setUp() {
        // withTransaction compiles to the top-level static facade
        // androidx.room.RoomDatabaseKt.withTransaction, so a pass-through stub only
        // intercepts with mockkStatic (same pattern as WarrantyTrackerRepositoryTest).
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        coEvery { history.getAllContributions() } returns emptyList()
        coEvery { ledgerDao.getAll() } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

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
        coEvery { dao.getById(9L) } returns entity
        coEvery { dao.deleteGoal(any()) } returns Unit
        val goal = repository.getSavingsGoals().single()
        assertEquals("EUR", goal.currency)
        assertEquals("LEGACY_DEFAULT", goal.currencyAssumption)
        // Balance 30.0 is non-empty: the delete is now rejected (INV-1) and nothing is deleted.
        assertEquals(
            SavingsGoalDeleteResult.Rejected("SAVINGS_GOAL_DELETE_HAS_BALANCE"),
            repository.deleteSavingsGoal(goal)
        )
        coVerify(exactly = 0) { dao.deleteGoal(any()) }
        coVerify(exactly = 1) { barrier.checkWritesAllowed("SavingsGoalRepository.deleteSavingsGoal") }
    }

    @Test
    fun `legacy assumption goal with zero balance is deleted using the row re-read in the transaction`() = runTest {
        val entity = com.yourname.expensetracker.data.database.entity.SavingsGoal(
            id = 9L, name = "Legacy", targetAmount = 100.0, currentAmount = 0.0,
            currency = "EUR", currencyAssumption = "LEGACY_DEFAULT", createdAt = 500L
        )
        every { dao.getAllGoals() } returns MutableStateFlow(listOf(entity))
        coEvery { dao.getById(9L) } returns entity
        coEvery { dao.deleteGoal(any()) } returns Unit
        val goal = repository.getSavingsGoals().single()
        assertEquals("LEGACY_DEFAULT", goal.currencyAssumption)
        assertEquals(SavingsGoalDeleteResult.Deleted, repository.deleteSavingsGoal(goal))
        coVerify(exactly = 1) { dao.deleteGoal(entity) }
        coVerify(exactly = 1) { barrier.checkWritesAllowed("SavingsGoalRepository.deleteSavingsGoal") }
    }
}
