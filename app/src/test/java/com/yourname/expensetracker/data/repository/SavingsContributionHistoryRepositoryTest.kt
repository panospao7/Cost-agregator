package com.yourname.expensetracker.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

class SavingsContributionHistoryRepositoryTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    @Test
    fun `recorded contributions survive repository recreation and range queries`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val stateFile = createStateFile()
        val april10 = FakeTimeProvider.forDate(2026, 4, 10, 9, 0).now()
        val april14 = FakeTimeProvider.forDate(2026, 4, 14, 18, 30).now()

        val firstHandle = createRepository(stateFile, timeProvider)
        assertTrue(firstHandle.repository.recordContribution(1L, 25.0, april10, "manual"))
        assertTrue(firstHandle.repository.recordContribution(2L, 40.0, april14, "sweep"))
        assertNotNull(firstHandle.repository.snapshotJson())

        firstHandle.scope.cancel()
        scopes.remove(firstHandle.scope)

        val recreatedHandle = createRepository(stateFile, timeProvider)
        val allEvents = recreatedHandle.repository.getAllContributions()
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(timeProvider.now())
        val aprilEvents = recreatedHandle.repository.getContributionsBetween(monthStart, monthEnd)

        assertEquals(2, allEvents.size)
        assertEquals(listOf(april10, april14), allEvents.map { it.timestamp })
        assertEquals(2, aprilEvents.size)
        assertEquals(65.0, aprilEvents.sumOf { it.amount }, 0.0001)
    }

    @Test
    fun `pruning removes stale events but preserves current month and streak history`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), timeProvider)
        val staleTimestamp = FakeTimeProvider.forDate(2023, 12, 1, 8, 0).now()
        val yesterday = FakeTimeProvider.forDate(2026, 4, 14, 8, 0).now()
        val today = FakeTimeProvider.forDate(2026, 4, 15, 8, 0).now()

        handle.repository.recordContribution(1L, 10.0, staleTimestamp, "manual")
        handle.repository.recordContribution(1L, 12.0, yesterday, "manual")
        handle.repository.recordContribution(1L, 15.0, today, "manual")

        val allEvents = handle.repository.getAllContributions()

        assertEquals(2, allEvents.size)
        assertFalse(allEvents.any { it.timestamp == staleTimestamp })
        assertEquals(listOf(yesterday, today), allEvents.map { it.timestamp })
    }

    @Test
    fun `invalid contributions are rejected without persisting`() = runTest {
        val timeProvider = FakeTimeProvider.forDate(2026, 4, 15)
        val handle = createRepository(createStateFile(), timeProvider)
        val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(timeProvider.now())

        assertFalse(handle.repository.recordContribution(0L, 10.0))
        assertFalse(handle.repository.recordContribution(1L, 0.0))
        assertFalse(handle.repository.recordContribution(1L, Double.NaN))

        assertTrue(handle.repository.getContributionsBetween(monthStart, monthEnd).isEmpty())
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
            repository = SavingsContributionHistoryRepository(dataStore, timeProvider),
            scope = scope
        )
    }

    private fun createStateFile(): File {
        return Files.createTempFile("savings-contribution-history", ".preferences_pb").toFile()
    }

    private data class RepositoryHandle(
        val repository: SavingsContributionHistoryRepository,
        val scope: CoroutineScope
    )
}
