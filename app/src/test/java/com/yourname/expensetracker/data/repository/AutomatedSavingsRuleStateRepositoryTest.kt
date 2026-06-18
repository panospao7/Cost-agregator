package com.yourname.expensetracker.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutomatedSavingsRuleStateRepositoryTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    @Test
    fun `weekly reservation is idempotent and survives repository recreation`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val stateFile = createStateFile()
        val firstHandle = createRepository(stateFile, timeProvider)
        val weekStart = TimePeriodUtils.getWeekRange(timeProvider.now()).first

        assertTrue(firstHandle.repository.reserveWeeklyNoSpendReward("rule-1", weekStart))
        assertFalse(firstHandle.repository.reserveWeeklyNoSpendReward("rule-1", weekStart))

        firstHandle.scope.cancel()
        scopes.remove(firstHandle.scope)

        val recreatedHandle = createRepository(stateFile, timeProvider)
        assertTrue(recreatedHandle.repository.hasWeeklyNoSpendRewardReservation("rule-1", weekStart))
        assertFalse(recreatedHandle.repository.reserveWeeklyNoSpendReward("rule-1", weekStart))
    }

    @Test
    fun `monthly cap consumption is atomic across concurrent updates`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), timeProvider)
        val yearMonth = AutomatedSavingsRuleStateRepository.buildYearMonthKey(timeProvider.now())

        val allowedAmounts = awaitAll(
            async {
                handle.repository.consumeMonthlyAmountWithinCap(
                    ruleStableKey = "rule-2",
                    yearMonth = yearMonth,
                    requestedAmount = 8.0,
                    maximumPerMonth = 10.0
                )
            },
            async {
                handle.repository.consumeMonthlyAmountWithinCap(
                    ruleStableKey = "rule-2",
                    yearMonth = yearMonth,
                    requestedAmount = 5.0,
                    maximumPerMonth = 10.0
                )
            }
        )

        assertEquals(10.0, allowedAmounts.sum(), 0.0001)
        assertEquals(10.0, handle.repository.getMonthlyConsumed("rule-2", yearMonth), 0.0001)
    }

    @Test
    fun `writes prune obsolete weekly and monthly entries`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 1, 15)
        val handle = createRepository(createStateFile(), timeProvider)
        val oldWeekStart = TimePeriodUtils.getWeekRange(timeProvider.now()).first
        val oldMonth = AutomatedSavingsRuleStateRepository.buildYearMonthKey(timeProvider.now())

        handle.repository.reserveWeeklyNoSpendReward("rule-3", oldWeekStart)
        handle.repository.consumeMonthlyAmountWithinCap("rule-3", oldMonth, 4.0, 50.0)

        timeProvider.setTime(FakeTimeProvider.forDate(2026, 4, 15).now())
        val currentWeekStart = TimePeriodUtils.getWeekRange(timeProvider.now()).first
        val currentMonth = AutomatedSavingsRuleStateRepository.buildYearMonthKey(timeProvider.now())

        handle.repository.reserveWeeklyNoSpendReward("rule-4", currentWeekStart)
        handle.repository.consumeMonthlyAmountWithinCap("rule-4", currentMonth, 6.0, 50.0)

        assertFalse(handle.repository.hasWeeklyNoSpendRewardReservation("rule-3", oldWeekStart))
        assertEquals(0.0, handle.repository.getMonthlyConsumed("rule-3", oldMonth), 0.0001)
        assertTrue(handle.repository.hasWeeklyNoSpendRewardReservation("rule-4", currentWeekStart))
        assertEquals(6.0, handle.repository.getMonthlyConsumed("rule-4", currentMonth), 0.0001)
    }

    @Test
    fun `state serializes to datastore json`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), timeProvider)
        val weekStart = TimePeriodUtils.getWeekRange(timeProvider.now()).first
        val yearMonth = AutomatedSavingsRuleStateRepository.buildYearMonthKey(timeProvider.now())

        handle.repository.reserveWeeklyNoSpendReward("rule-5", weekStart)
        handle.repository.consumeMonthlyAmountWithinCap("rule-5", yearMonth, 3.0, 10.0)

        val snapshot = handle.repository.snapshotJson()

        assertNotNull(snapshot)
        assertTrue(snapshot!!.contains("rule-5"))
    }

    @Test
    fun `weekly reservation and monthly cap update atomically`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), timeProvider)
        val weekStart = TimePeriodUtils.getWeekRange(timeProvider.now()).first
        val yearMonth = AutomatedSavingsRuleStateRepository.buildYearMonthKey(timeProvider.now())

        val firstResult = handle.repository.reserveWeeklyNoSpendRewardWithinMonthlyCap(
            ruleStableKey = "rule-6",
            weekStart = weekStart,
            yearMonth = yearMonth,
            requestedAmount = 10.0,
            maximumPerMonth = 5.0
        )

        val secondResult = handle.repository.reserveWeeklyNoSpendRewardWithinMonthlyCap(
            ruleStableKey = "rule-6",
            weekStart = weekStart,
            yearMonth = yearMonth,
            requestedAmount = 10.0,
            maximumPerMonth = 5.0
        )

        assertTrue(firstResult.reserved)
        assertEquals(5.0, firstResult.allowedAmount, 0.0001)
        assertFalse(secondResult.reserved)
        assertEquals(0.0, secondResult.allowedAmount, 0.0001)
        assertTrue(handle.repository.hasWeeklyNoSpendRewardReservation("rule-6", weekStart))
        assertEquals(5.0, handle.repository.getMonthlyConsumed("rule-6", yearMonth), 0.0001)
    }

    @Test
    fun `weekly reservation is not consumed when monthly cap blocks reward`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), timeProvider)
        val weekStart = TimePeriodUtils.getWeekRange(timeProvider.now()).first
        val yearMonth = AutomatedSavingsRuleStateRepository.buildYearMonthKey(timeProvider.now())

        handle.repository.consumeMonthlyAmountWithinCap("rule-7", yearMonth, 5.0, 5.0)

        val blockedResult = handle.repository.reserveWeeklyNoSpendRewardWithinMonthlyCap(
            ruleStableKey = "rule-7",
            weekStart = weekStart,
            yearMonth = yearMonth,
            requestedAmount = 10.0,
            maximumPerMonth = 5.0
        )

        assertFalse(blockedResult.reserved)
        assertEquals(0.0, blockedResult.allowedAmount, 0.0001)
        assertFalse(handle.repository.hasWeeklyNoSpendRewardReservation("rule-7", weekStart))
        assertEquals(5.0, handle.repository.getMonthlyConsumed("rule-7", yearMonth), 0.0001)
    }

    private fun createRepository(
        stateFile: File,
        timeProvider: FakeTimeProvider
    ): RepositoryHandle {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { stateFile }
        )
        return RepositoryHandle(
            repository = AutomatedSavingsRuleStateRepository(dataStore, timeProvider),
            scope = scope
        )
    }

    private fun createStateFile(): File {
        return Files.createTempFile("automated-savings-rule-state", ".preferences_pb").toFile()
    }

    private data class RepositoryHandle(
        val repository: AutomatedSavingsRuleStateRepository,
        val scope: CoroutineScope
    )
}
