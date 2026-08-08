package com.yourname.expensetracker.domain.challenge

import com.yourname.expensetracker.data.database.dao.CurrencyTotal
import com.yourname.expensetracker.data.database.dao.DailyTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.repository.SpendingChallengeRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.capture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class SpendingChallengeManagerTest {

    private val expenseDao = mockk<ExpenseDao>()
    private val repository = mockk<SpendingChallengeRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>()

    private lateinit var manager: SpendingChallengeManager

    @Before
    fun setup() {
        coEvery { repository.getActiveChallenges() } returns emptyList()
        manager = SpendingChallengeManager(
            expenseDao = expenseDao,
            spendingChallengeRepository = repository,
            timeProvider = timeProvider,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun `checkNoSpendStreak uses grouped day query instead of day by day expense reads`() = runTest {
        val now = 1_710_000_000_000L
        every { timeProvider.now() } returns now
        coEvery { expenseDao.getOldestExpenseDate() } returns now - DAY_MS * 2
        coEvery { expenseDao.getSpendingDailyTotalsBetween(any(), any()) } returns listOf(
            DailyTotal(dayEpoch = 20240401, startDate = now - DAY_MS * 2, endDate = now - DAY_MS * 2, total = 12.0, txCount = 1)
        )
        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } returns 90.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 90.0, 1))

        val status = manager.checkNoSpendStreak()

        assertTrue(status.hasNoSpendToday)
        assertEquals(2, status.currentStreakDays)
        coVerify(exactly = 1) { expenseDao.getOldestExpenseDate() }
        coVerify(exactly = 1) { expenseDao.getSpendingDailyTotalsBetween(any(), any()) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetween(any(), any(), any(), any()) }
        coVerify(exactly = 1) { expenseDao.getTotalSpentBetween(any(), any()) }
    }

    @Test
    fun `budget challenge does not complete immediately when under target`() = runTest {
        val now = 1_710_000_000_000L
        every { timeProvider.now() } returns now
        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } returns 0.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 0.0, 0))

        val progress = manager.getChallengeProgress(
            SpendingChallenge(
                id = 1L,
                name = "Stay under",
                type = ChallengeType.BUDGET_LIMIT,
                startDate = now,
                endDate = now + DAY_MS * 7,
                targetAmount = 100.0,
                categoryId = null,
                isActive = true,
                progress = 0.0,
                createdAt = now,
                updatedAt = now
            )
        )

        assertFalse(progress.isCompleted)
        assertFalse(progress.isSuccessful)
        assertEquals(0.0, progress.progressPercent, 0.0001)
        coVerify(exactly = 1) { expenseDao.getTotalSpentBetween(any(), any()) }
    }

    @Test
    fun `reduce spending challenge uses stored baseline and completes only at end`() = runTest {
        val now = 1_710_000_000_000L
        every { timeProvider.now() } returnsMany listOf(now, now, now + DAY_MS * 7)
        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } returnsMany listOf(40.0, 40.0)
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returnsMany listOf(
            listOf(CurrencyTotal("EUR", 40.0, 1)),
            listOf(CurrencyTotal("EUR", 40.0, 1))
        )

        val challenge = SpendingChallenge(
            id = 2L,
            name = "Spend less than last week",
            type = ChallengeType.REDUCE_SPENDING,
            startDate = now,
            endDate = now + DAY_MS * 7,
            targetAmount = 20.0,
            categoryId = null,
            isActive = true,
            progress = 0.0,
            baselineAmount = 80.0,
            baselineStartDate = now - DAY_MS * 7,
            baselineEndDate = now,
            createdAt = now,
            updatedAt = now
        )

        val inFlight = manager.getChallengeProgress(challenge)
        val completed = manager.getChallengeProgress(challenge)

        assertFalse(inFlight.isCompleted)
        assertTrue(completed.isCompleted)
        assertTrue(completed.isSuccessful)
        coVerify(exactly = 2) { expenseDao.getTotalSpentBetween(any(), any()) }
    }

    @Test
    fun `create challenge persists reduce spending baseline period`() = runTest {
        val now = 1_710_000_000_000L
        every { timeProvider.now() } returns now
        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } returns 120.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 120.0, 1))
        coEvery { repository.saveChallenge(any()) } answers { invocation.args[0] as SpendingChallenge }

        val created = manager.createChallenge(
            name = "Cut back",
            type = ChallengeType.REDUCE_SPENDING,
            durationDays = 7,
            targetAmount = 20.0
        )

        assertEquals(120.0, created.baselineAmount ?: 0.0, 0.0001)
        assertEquals(now - DAY_MS * 7, created.baselineStartDate)
        assertEquals(managerTestStartOfDay(now), created.baselineEndDate)
        coVerify(exactly = 1) { repository.saveChallenge(any()) }
    }

    @Test
    fun `checkNoSpendStreak on DST spring-forward date captures DAO start as getStartOfDay and end as raw start plus DAY_MS`() = runTest {
        // 2024-03-31 10:00 local is a DST spring-forward day in many zones.
        // Expected values are derived from TimePeriodUtils/java.time so the
        // assertions stay deterministic regardless of the runner's zone.
        val now = toEpochMs(2024, 3, 31, 10, 0)
        every { timeProvider.now() } returns now
        coEvery { expenseDao.getOldestExpenseDate() } returns null
        val startSlot = slot<Long>()
        val endSlot = slot<Long>()
        coEvery { expenseDao.getSpendingDailyTotalsBetween(capture(startSlot), capture(endSlot)) } returns emptyList()
        coEvery { expenseDao.getTotalSpentBetween(any(), any()) } returns 90.0
        coEvery { expenseDao.getTotalSpentBetweenByCurrency(any(), any()) } returns listOf(CurrencyTotal("EUR", 90.0, 1))

        val status = manager.checkNoSpendStreak()

        val startOfDay = TimePeriodUtils.getStartOfDay(now)
        assertEquals("DAO range start must be the calendar start of day", startOfDay, startSlot.captured)
        assertEquals("DAO range end preserves existing raw DAY_MS semantics", startOfDay + DAY_MS, endSlot.captured)
        assertTrue(status.hasNoSpendToday)
    }

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private fun managerTestStartOfDay(timestamp: Long): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}