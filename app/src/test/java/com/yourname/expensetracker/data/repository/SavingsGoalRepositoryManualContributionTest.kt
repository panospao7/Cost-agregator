package com.yourname.expensetracker.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.SavingsContributionLedgerDao
import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.data.database.entity.SavingsContributionLedgerEntry
import com.yourname.expensetracker.data.database.entity.SavingsGoal as SavingsGoalEntity
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.savings.ManualContributionResult
import com.yourname.expensetracker.domain.savings.ManualContributionSnapshot
import com.yourname.expensetracker.domain.savings.SAVINGS_GOAL_DELETE_HAS_BALANCE
import com.yourname.expensetracker.domain.savings.SAVINGS_GOAL_DELETE_HAS_HISTORY
import com.yourname.expensetracker.domain.savings.SavingsGoalDeleteEligibility
import com.yourname.expensetracker.domain.savings.SavingsGoalDeleteResult
import com.yourname.expensetracker.domain.usecase.savings.GoalAllocation
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Real in-memory Room tests for [SavingsGoalRepository.recordManualContribution] and the readers
 * that must see its "manual" ledger rows (getAllContributions, hasSweepBetween, goal deletion).
 *
 * The transaction is the real Room transaction: rollback is proven by forcing failures AFTER the real
 * balance update (and, where stated, after the real ledger insert) and re-reading both tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SavingsGoalRepositoryManualContributionTest {

    private lateinit var database: AppDatabase
    private lateinit var historyScope: CoroutineScope
    private lateinit var historyFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var history: SavingsContributionHistoryRepository
    private val writeBarrier = mockk<DatabaseWriteBarrier>()
    private val timeProvider = FakeTimeProvider(NOW)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        every { writeBarrier.checkWritesAllowed(any<String>()) } just runs
        historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        historyFile = Files.createTempFile("savings-manual-contribution-history", ".preferences_pb").toFile()
        dataStore = PreferenceDataStoreFactory.create(scope = historyScope, produceFile = { historyFile })
        history = SavingsContributionHistoryRepository(dataStore, timeProvider, database.savingsContributionLedgerDao())
    }

    @After
    fun tearDown() {
        database.close()
        historyScope.cancel()
        historyFile.delete()
    }

    // ---- MC-1 success -------------------------------------------------------------------

    @Test
    fun `MC-1 success commits balance and exactly one manual ledger row together`() = runTest {
        insertGoal(1L, 100.0)

        val result = repository().recordManualContribution(1L, 25.55, snapshot(100.0), "manual-v1:k1", NOW)

        assertEquals(ManualContributionResult.Recorded, result)
        assertEquals(100.0 + 25.55, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        val row = database.savingsContributionLedgerDao().getAll().single()
        assertEquals("manual", row.source)
        assertNull(row.sweepPlanId)
        assertEquals(25.55.toRawBits(), row.amount.toRawBits())
        assertEquals("EUR", row.currency)
        assertEquals(1L, row.goalId)
        assertEquals("manual-v1:k1", row.idempotencyKey)
        assertEquals(NOW, row.timestamp)
        verify(exactly = 1) { writeBarrier.checkWritesAllowed("SavingsGoalRepository.recordManualContribution") }
    }

    @Test
    fun `MC-1 ledger currency equals the goal stored currency for a non-home goal`() = runTest {
        insertGoal(2L, 10.0, currency = "USD")

        val result = repository().recordManualContribution(2L, 5.0, snapshot(10.0, currency = "USD"), "manual-v1:usd", NOW)

        assertEquals(ManualContributionResult.Recorded, result)
        assertEquals("USD", database.savingsContributionLedgerDao().getAll().single().currency)
        assertEquals(15.0, database.savingsGoalDao().getById(2L)!!.currentAmount, 0.0)
    }

    @Test
    fun `MC-1 control two separate actions with distinct keys and refreshed snapshots both apply`() = runTest {
        insertGoal(1L, 100.0)
        val repository = repository()

        assertEquals(
            ManualContributionResult.Recorded,
            repository.recordManualContribution(1L, 50.0, snapshot(100.0), "manual-v1:a", NOW)
        )
        assertEquals(
            ManualContributionResult.Recorded,
            repository.recordManualContribution(1L, 75.0, snapshot(150.0), "manual-v1:b", NOW + 1)
        )

        assertEquals(225.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(
            listOf("manual-v1:a", "manual-v1:b"),
            database.savingsContributionLedgerDao().getAll().map { it.idempotencyKey }
        )
    }

    // ---- MC-2 rollback ------------------------------------------------------------------

    @Test
    fun `MC-2 a real unique-key conflict on the ledger insert rolls the balance update back`() = runTest {
        insertGoal(1L, 100.0)
        // A row with this key already exists; the lookup is hidden so the transaction proceeds to the
        // balance update, and the REAL unique index then rejects the insert (IGNORE returns -1).
        database.savingsContributionLedgerDao().insert(ledgerRow("manual-v1:race", goalId = 1L, amount = 25.0))
        val ledgerDao = FaultyLedgerDao(database.savingsContributionLedgerDao()).apply { hideKeyLookup = true }
        val before = tables()

        val result = repository(ledgerDao = ledgerDao).recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:race", NOW)

        assertTrue(result is ManualContributionResult.Rejected)
        assertEquals(1, ledgerDao.insertCalls)
        assertEquals(100.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(before, tables())
    }

    @Test
    fun `MC-2 a failure after the real ledger insert rolls back both the balance and the row`() = runTest {
        insertGoal(1L, 100.0)
        val ledgerDao = FaultyLedgerDao(database.savingsContributionLedgerDao()).apply {
            failAfterRealInsert = IllegalStateException(SECRET)
        }
        val before = tables()

        val result = repository(ledgerDao = ledgerDao).recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:k", NOW)

        assertTrue(result is ManualContributionResult.Rejected)
        assertEquals(1, ledgerDao.insertCalls)
        assertEquals(before, tables())
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
        assertEquals(100.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
    }

    // ---- MC-3 snapshot mismatch ---------------------------------------------------------

    @Test
    fun `MC-3 a stale snapshot writes nothing and returns the typed SnapshotMismatch`() = runTest {
        insertGoal(1L, 100.0)
        val before = tables()
        val staleSnapshots = mapOf(
            "stale current amount" to snapshot(50.0),
            "stale target amount" to snapshot(100.0, target = 999.0),
            "other currency" to snapshot(100.0, currency = "USD")
        )

        for ((label, stale) in staleSnapshots) {
            val result = repository().recordManualContribution(1L, 25.0, stale, "manual-v1:$label", NOW)
            assertEquals(label, ManualContributionResult.SnapshotMismatch, result)
            assertEquals(label, before, tables())
        }
    }

    @Test
    fun `MC-3 an absent goal is a SnapshotMismatch and writes nothing`() = runTest {
        insertGoal(1L, 100.0)
        val before = tables()

        val result = repository().recordManualContribution(999L, 25.0, snapshot(100.0), "manual-v1:absent", NOW)

        assertEquals(ManualContributionResult.SnapshotMismatch, result)
        assertEquals(before, tables())
    }

    @Test
    fun `MC-3 a snapshot that went stale after a first contribution cannot apply a second one`() = runTest {
        insertGoal(1L, 100.0)
        val repository = repository()
        assertEquals(
            ManualContributionResult.Recorded,
            repository.recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:first", NOW)
        )
        val afterFirst = tables()

        val result = repository.recordManualContribution(1L, 10.0, snapshot(100.0), "manual-v1:second", NOW + 1)

        assertEquals(ManualContributionResult.SnapshotMismatch, result)
        assertEquals(afterFirst, tables())
    }

    // ---- MC-4 idempotency ---------------------------------------------------------------

    @Test
    fun `MC-4 a repeated key with the same goal and amount is AlreadyRecorded and changes nothing`() = runTest {
        insertGoal(1L, 100.0)
        val repository = repository()
        assertEquals(
            ManualContributionResult.Recorded,
            repository.recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:retry", NOW)
        )
        val afterFirst = tables()
        assertEquals(125.0, afterFirst.goals.single().currentAmount, 0.0)

        // A retry carries the original (now stale) snapshot.
        val retry = repository.recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:retry", NOW + 5)

        assertEquals(ManualContributionResult.AlreadyRecorded, retry)
        assertEquals(afterFirst, tables())
        assertEquals(1, tables().ledger.size)
    }

    @Test
    fun `MC-4 the same key with a different amount or goal is Rejected and writes nothing`() = runTest {
        insertGoal(1L, 100.0)
        insertGoal(2L, 100.0)
        val repository = repository()
        assertEquals(
            ManualContributionResult.Recorded,
            repository.recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:reuse", NOW)
        )
        val afterFirst = tables()

        val differentAmount = repository.recordManualContribution(1L, 30.0, snapshot(125.0), "manual-v1:reuse", NOW + 1)
        val differentGoal = repository.recordManualContribution(2L, 25.0, snapshot(100.0), "manual-v1:reuse", NOW + 1)

        assertTrue(differentAmount is ManualContributionResult.Rejected)
        assertTrue(differentGoal is ManualContributionResult.Rejected)
        assertEquals(afterFirst, tables())
    }

    @Test
    fun `MC-4 the same key already used by a non-manual row is Rejected even with equal goal and amount`() = runTest {
        insertGoal(1L, 100.0)
        database.savingsContributionLedgerDao().insert(ledgerRow("manual-v1:taken", goalId = 1L, amount = 25.0, source = "sweep"))
        val before = tables()

        val result = repository().recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:taken", NOW)

        assertTrue(result is ManualContributionResult.Rejected)
        assertEquals(before, tables())
    }

    // ---- MC-5 barrier, cancellation, other failures -------------------------------------

    @Test
    fun `MC-5 write barrier held rethrows the blocked exception and touches nothing`() = runTest {
        insertGoal(1L, 100.0)
        val blocked = DatabaseAccessBlockedException(
            DatabaseAccessType.WRITE,
            DatabaseAccessOperation("SavingsGoalRepository.recordManualContribution"),
            RestoreMaintenanceMode.Mode.RESTORE_STAGING
        )
        val blockedBarrier = mockk<DatabaseWriteBarrier>()
        every { blockedBarrier.checkWritesAllowed("SavingsGoalRepository.recordManualContribution") } throws blocked
        val goalDao = RecordingGoalDao(database.savingsGoalDao())
        val ledgerDao = FaultyLedgerDao(database.savingsContributionLedgerDao())
        val before = tables()

        try {
            repository(barrier = blockedBarrier, goalDao = goalDao, ledgerDao = ledgerDao)
                .recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:blocked", NOW)
            throw AssertionError("DatabaseAccessBlockedException was swallowed")
        } catch (error: DatabaseAccessBlockedException) {
            assertTrue(error === blocked)
        }

        assertEquals(0, goalDao.snapshotUpdateCalls)
        assertEquals(0, ledgerDao.insertCalls)
        assertEquals(before, tables())
    }

    @Test
    fun `MC-5 blocked exception thrown inside the transaction is rethrown and rolled back`() = runTest {
        insertGoal(1L, 100.0)
        val blocked = DatabaseAccessBlockedException(
            DatabaseAccessType.WRITE,
            DatabaseAccessOperation("SavingsGoalRepository.recordManualContribution"),
            RestoreMaintenanceMode.Mode.RESTORE_STAGING
        )
        val ledgerDao = FaultyLedgerDao(database.savingsContributionLedgerDao()).apply { failAfterRealInsert = blocked }
        val before = tables()

        try {
            repository(ledgerDao = ledgerDao).recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:k", NOW)
            throw AssertionError("DatabaseAccessBlockedException was swallowed")
        } catch (error: DatabaseAccessBlockedException) {
            assertTrue(error === blocked)
        }

        assertEquals(before, tables())
    }

    @Test
    fun `MC-5 cancellation inside the transaction is rethrown unchanged and rolled back`() = runTest {
        insertGoal(1L, 100.0)
        val cancellation = InjectedCancellation()
        val ledgerDao = FaultyLedgerDao(database.savingsContributionLedgerDao()).apply { failAfterRealInsert = cancellation }
        val before = tables()

        try {
            repository(ledgerDao = ledgerDao).recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:k", NOW)
            throw AssertionError("CancellationException was swallowed")
        } catch (error: CancellationException) {
            assertTrue(error === cancellation)
        }

        assertEquals(before, tables())
    }

    @Test
    fun `MC-5 an unexpected failure gives a bounded Rejected code without exception text`() = runTest {
        insertGoal(1L, 100.0)
        val before = tables()
        for (label in listOf("ledger lookup", "balance update", "ledger insert")) {
            val goalDao = RecordingGoalDao(database.savingsGoalDao())
            val ledgerDao = FaultyLedgerDao(database.savingsContributionLedgerDao())
            when (label) {
                "ledger lookup" -> ledgerDao.lookupError = RuntimeException(SECRET)
                "balance update" -> goalDao.snapshotUpdateError = IllegalStateException(SECRET)
                else -> ledgerDao.failAfterRealInsert = IllegalStateException(SECRET)
            }

            val result = repository(goalDao = goalDao, ledgerDao = ledgerDao)
                .recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:fail", NOW)

            assertTrue("$label: $result", result is ManualContributionResult.Rejected)
            assertBoundedCode(label, (result as ManualContributionResult.Rejected).reasonCode)
            assertFalse("$label leaked: $result", result.toString().contains(SECRET))
            assertEquals(label, before, tables())
        }
    }

    // ---- MC-6 invalid inputs ------------------------------------------------------------

    @Test
    fun `MC-6 invalid inputs write nothing and never reach the DAOs`() = runTest {
        insertGoal(1L, 100.0)
        val before = tables()
        val valid = snapshot(100.0)
        val cases = listOf(
            InvalidCase("zero delta", 1L, 0.0, valid, "manual-v1:z", NOW),
            InvalidCase("negative delta", 1L, -5.0, valid, "manual-v1:n", NOW),
            InvalidCase("NaN delta", 1L, Double.NaN, valid, "manual-v1:nan", NOW),
            InvalidCase("+Inf delta", 1L, Double.POSITIVE_INFINITY, valid, "manual-v1:pi", NOW),
            InvalidCase("-Inf delta", 1L, Double.NEGATIVE_INFINITY, valid, "manual-v1:ni", NOW),
            InvalidCase("empty key", 1L, 5.0, valid, "", NOW),
            InvalidCase("blank key", 1L, 5.0, valid, "   ", NOW),
            InvalidCase("zero goal id", 0L, 5.0, valid, "manual-v1:g", NOW),
            InvalidCase("zero timestamp", 1L, 5.0, valid, "manual-v1:t", 0L),
            InvalidCase("NaN snapshot balance", 1L, 5.0, snapshot(Double.NaN), "manual-v1:sb", NOW),
            InvalidCase("unsupported currency", 1L, 5.0, snapshot(100.0, currency = "ZZZ"), "manual-v1:uc", NOW)
        )

        for (case in cases) {
            val label = case.label
            val goalDao = RecordingGoalDao(database.savingsGoalDao())
            val ledgerDao = FaultyLedgerDao(database.savingsContributionLedgerDao())

            val result = repository(goalDao = goalDao, ledgerDao = ledgerDao).recordManualContribution(
                case.goalId, case.delta, case.snapshot, case.key, case.timestamp
            )

            assertTrue("$label: $result", result is ManualContributionResult.Rejected)
            assertBoundedCode(label, (result as ManualContributionResult.Rejected).reasonCode)
            assertEquals("$label touched the goal DAO", 0, goalDao.snapshotUpdateCalls)
            assertEquals("$label touched the ledger DAO", 0, ledgerDao.insertCalls)
            assertEquals(label, before, tables())
        }
    }

    @Test
    fun `MC-6 control the smallest positive amount is accepted`() = runTest {
        insertGoal(1L, 100.0)

        val result = repository().recordManualContribution(1L, 0.01, snapshot(100.0), "manual-v1:min", NOW)

        assertEquals(ManualContributionResult.Recorded, result)
        assertEquals(1, tables().ledger.size)
    }

    // ---- MC-7 getAllContributions -------------------------------------------------------

    @Test
    fun `MC-7 a new manual row and legacy DataStore events each appear once in timestamp order`() = runTest {
        insertGoal(1L, 100.0)
        assertTrue(history.recordContribution(1L, 10.0, NOW - 10_000L, "manual", "EUR"))
        assertTrue(history.recordContribution(1L, 7.0, NOW + 10_000L, "legacy", "EUR"))
        assertEquals(
            ManualContributionResult.Recorded,
            repository().recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:mid", NOW)
        )

        val events = history.getAllContributions()

        assertEquals(
            listOf(
                SavingsContributionEvent(1L, 10.0, NOW - 10_000L, "manual", "EUR"),
                SavingsContributionEvent(1L, 25.0, NOW, "manual", "EUR"),
                SavingsContributionEvent(1L, 7.0, NOW + 10_000L, "legacy", "EUR")
            ),
            events
        )
    }

    @Test
    fun `MC-7 a rolled back contribution leaves no phantom history event`() = runTest {
        insertGoal(1L, 100.0)
        val ledgerDao = FaultyLedgerDao(database.savingsContributionLedgerDao()).apply {
            failAfterRealInsert = IllegalStateException(SECRET)
        }

        repository(ledgerDao = ledgerDao).recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:k", NOW)

        assertTrue(history.getAllContributions().isEmpty())
    }

    // ---- MC-8 hasSweepBetween -----------------------------------------------------------

    @Test
    fun `MC-8 hasSweepBetween ignores manual rows`() = runTest {
        insertGoal(1L, 100.0)
        assertEquals(
            ManualContributionResult.Recorded,
            repository().recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:m", NOW)
        )
        val ledger = database.savingsContributionLedgerDao()

        assertFalse(ledger.hasSweepBetween(NOW - 1_000L, NOW + 1_000L))

        // Control: a sweep row in the same window is seen.
        ledger.insert(ledgerRow("sweep-control", goalId = 1L, amount = 40.0, source = "sweep"))
        assertTrue(ledger.hasSweepBetween(NOW - 1_000L, NOW + 1_000L))
    }

    @Test
    fun `MC-8 a manual contribution does not consume the month sweep`() = runTest {
        insertGoal(1L, 100.0)
        val coordinator = SavingsSweepAcceptanceCoordinator(
            database = database,
            savingsGoalDao = database.savingsGoalDao(),
            savingsSweepPlanDao = database.savingsSweepPlanDao(),
            contributionLedgerDao = database.savingsContributionLedgerDao(),
            monthAcceptanceDao = database.savingsSweepMonthAcceptanceDao(),
            writeBarrier = writeBarrier,
            timeProvider = timeProvider
        )
        assertEquals(
            ManualContributionResult.Recorded,
            repository().recordManualContribution(1L, 25.0, snapshot(100.0), "manual-v1:m", NOW)
        )

        val sweep = coordinator.accept(recommendation(allocation(1L, 25.0, 40.0)))

        assertEquals(SavingsSweepAcceptanceResult.Accepted, sweep)
        assertEquals(65.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(listOf("manual", "sweep"), database.savingsContributionLedgerDao().getAll().map { it.source })
    }

    // ---- MC-9 deletion policy -----------------------------------------------------------

    @Test
    fun `MC-9 a goal with a manual contribution and a balance is HAS_BALANCE`() = runTest {
        insertGoal(1L, 0.0)
        assertEquals(
            ManualContributionResult.Recorded,
            repository().recordManualContribution(1L, 25.0, snapshot(0.0), "manual-v1:m", NOW)
        )
        val before = tables()

        assertEquals(
            SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE),
            repository().getSavingsGoalDeletionEligibility(1L)
        )
        assertEquals(
            SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE),
            repository().deleteSavingsGoal(domainGoal(1L))
        )
        assertEquals(before, tables())
    }

    @Test
    fun `MC-9 a zero balance goal with a manual ledger row is HAS_HISTORY`() = runTest {
        insertGoal(1L, 0.0)
        assertEquals(
            ManualContributionResult.Recorded,
            repository().recordManualContribution(1L, 25.0, snapshot(0.0), "manual-v1:m", NOW)
        )
        database.savingsGoalDao().updateGoalAmount(1L, 0.0)
        val before = tables()
        assertEquals(0.0, before.goals.single().currentAmount, 0.0)

        assertEquals(
            SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY),
            repository().getSavingsGoalDeletionEligibility(1L)
        )
        assertEquals(
            SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY),
            repository().deleteSavingsGoal(domainGoal(1L))
        )
        assertEquals(before, tables())
    }

    @Test
    fun `MC-9 control a rolled back contribution leaves an empty goal deletable`() = runTest {
        insertGoal(1L, 0.0)
        val ledgerDao = FaultyLedgerDao(database.savingsContributionLedgerDao()).apply {
            failAfterRealInsert = IllegalStateException(SECRET)
        }
        repository(ledgerDao = ledgerDao).recordManualContribution(1L, 25.0, snapshot(0.0), "manual-v1:k", NOW)

        assertEquals(SavingsGoalDeleteResult.Deleted, repository().deleteSavingsGoal(domainGoal(1L)))
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun repository(
        barrier: DatabaseWriteBarrier = writeBarrier,
        goalDao: SavingsGoalDao = database.savingsGoalDao(),
        ledgerDao: SavingsContributionLedgerDao = database.savingsContributionLedgerDao()
    ) = SavingsGoalRepository(barrier, goalDao, database, history, ledgerDao)

    private suspend fun insertGoal(id: Long, balance: Double, currency: String = "EUR", target: Double = 1_000.0) {
        database.savingsGoalDao().insertGoal(
            SavingsGoalEntity(
                id = id,
                name = "Test Goal $id",
                targetAmount = target,
                currentAmount = balance,
                protectionLevel = GoalProtectionLevel.WARNING,
                currency = currency,
                currencyAssumption = "EXPLICIT",
                createdAt = NOW
            )
        )
    }

    private fun snapshot(current: Double, target: Double = 1_000.0, currency: String = "EUR") =
        ManualContributionSnapshot(currentAmount = current, targetAmount = target, currency = currency)

    private fun domainGoal(id: Long) = SavingsGoal(
        id = id,
        name = "Test Goal $id",
        targetAmount = 1_000.0,
        currentAmount = 0.0,
        targetDate = null,
        protectionLevel = com.yourname.expensetracker.domain.model.GoalProtectionLevel.WARNING,
        currency = "EUR",
        currencyAssumption = "EXPLICIT",
        createdAt = NOW
    )

    private fun ledgerRow(key: String, goalId: Long, amount: Double, source: String = "manual") =
        SavingsContributionLedgerEntry(
            idempotencyKey = key,
            goalId = goalId,
            amount = amount,
            currency = "EUR",
            timestamp = NOW,
            source = source,
            sweepPlanId = null
        )

    private suspend fun tables() = Tables(
        goals = database.savingsGoalDao().getAllGoals().first().sortedBy { it.id },
        ledger = database.savingsContributionLedgerDao().getAll()
    )

    private fun assertBoundedCode(label: String, code: String) {
        assertTrue("$label: unbounded code '$code'", Regex("^SAVINGS_[A-Z_]+$").matches(code))
        assertFalse("$label: code leaked text", code.contains(SECRET))
    }

    private fun allocation(goalId: Long, currentProgress: Double, amount: Double) = GoalAllocation(
        goalId = goalId,
        goalName = "Test Goal $goalId",
        currentProgress = currentProgress,
        targetAmount = 1_000.0,
        suggestedAllocation = amount,
        allocationPercentage = 0.5,
        currency = "EUR"
    )

    private fun recommendation(vararg allocations: GoalAllocation) = SavingsSweepRecommendation(
        totalUnderspend = maxOf(60.0, allocations.sumOf { it.suggestedAllocation } + 10.0),
        riskBuffer = 10.0,
        safeSweepAmount = allocations.sumOf { it.suggestedAllocation },
        goalAllocations = allocations.toList(),
        computedAt = NOW,
        monthEnd = NOW + 1_000L,
        confidence = 0.9,
        currency = "EUR",
        rateTimestamp = 0L
    )

    private data class Tables(
        val goals: List<SavingsGoalEntity>,
        val ledger: List<SavingsContributionLedgerEntry>
    )

    private class InvalidCase(
        val label: String,
        val goalId: Long,
        val delta: Double,
        val snapshot: ManualContributionSnapshot,
        val key: String,
        val timestamp: Long
    )

    private class InjectedCancellation :CancellationException("cancelled inside transaction")

    /** Delegates to the real DAO; counts snapshot updates; can fail the update itself. */
    private class RecordingGoalDao(private val real: SavingsGoalDao) : SavingsGoalDao by real {
        var snapshotUpdateCalls = 0
        var snapshotUpdateError: Throwable? = null

        override suspend fun addToGoalAmountIfSnapshot(
            goalId: Long,
            expectedCurrentAmount: Double,
            expectedTargetAmount: Double,
            currency: String,
            delta: Double
        ): Int {
            snapshotUpdateCalls++
            snapshotUpdateError?.let { throw it }
            return real.addToGoalAmountIfSnapshot(goalId, expectedCurrentAmount, expectedTargetAmount, currency, delta)
        }
    }

    /**
     * Delegates to the real DAO. [hideKeyLookup] makes the in-transaction key lookup return null so the
     * real unique index is what rejects the insert; [failAfterRealInsert] throws once the real row is in.
     */
    private class FaultyLedgerDao(private val real: SavingsContributionLedgerDao) : SavingsContributionLedgerDao by real {
        var hideKeyLookup = false
        var lookupError: Throwable? = null
        var failAfterRealInsert: Throwable? = null
        var insertCalls = 0

        override suspend fun getByIdempotencyKey(idempotencyKey: String): SavingsContributionLedgerEntry? {
            lookupError?.let { throw it }
            return if (hideKeyLookup) null else real.getByIdempotencyKey(idempotencyKey)
        }

        override suspend fun insert(entry: SavingsContributionLedgerEntry): Long {
            insertCalls++
            val id = real.insert(entry)
            failAfterRealInsert?.let { throw it }
            return id
        }
    }

    private companion object {
        const val NOW = 1_710_000_000_000L
        const val SECRET = "secret text"
    }
}
