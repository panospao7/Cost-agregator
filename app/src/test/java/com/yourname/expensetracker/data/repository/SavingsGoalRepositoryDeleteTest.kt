package com.yourname.expensetracker.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import com.yourname.expensetracker.data.database.entity.SavingsSweepMonthAcceptance
import com.yourname.expensetracker.data.database.entity.SavingsSweepPlan
import com.yourname.expensetracker.data.database.entity.SavingsGoal as SavingsGoalEntity
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.savings.SAVINGS_GOAL_DELETE_HAS_BALANCE
import com.yourname.expensetracker.domain.savings.SAVINGS_GOAL_DELETE_HAS_HISTORY
import com.yourname.expensetracker.domain.savings.SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE
import com.yourname.expensetracker.domain.savings.SAVINGS_GOAL_DELETE_UNAVAILABLE
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Real in-memory Room + real DataStore tests for [SavingsGoalRepository.deleteSavingsGoal] and
 * [SavingsGoalRepository.getSavingsGoalDeletionEligibility] (savings-goal-delete S2, part 1).
 *
 * Oracle: docs/features/savings-goal-delete/domain-spec.md (AE-1..8, AE-12, AE-15, AE-18, AE-20,
 * INV-1..7, INV-10, INV-12..16, INV-18). The in-flight interleaving tests (AE-9..11, AE-14, AE-17)
 * live in SavingsGoalRepositoryDeleteConcurrencyTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SavingsGoalRepositoryDeleteTest {

    private lateinit var database: AppDatabase
    private lateinit var coordinator: SavingsSweepAcceptanceCoordinator
    private lateinit var historyScope: CoroutineScope
    private lateinit var historyFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var history: SavingsContributionHistoryRepository
    private val writeBarrier = mockk<DatabaseWriteBarrier>()
    private val timeProvider = FakeTimeProvider(1_710_000_000_000L)
    private val historyKey = stringPreferencesKey("savings_contribution_history_json")

    @Before
    fun setUp() {
        ShadowLog.clear()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        every { writeBarrier.checkWritesAllowed(any<String>()) } just runs
        coordinator = SavingsSweepAcceptanceCoordinator(
            database = database,
            savingsGoalDao = database.savingsGoalDao(),
            savingsSweepPlanDao = database.savingsSweepPlanDao(),
            contributionLedgerDao = database.savingsContributionLedgerDao(),
            monthAcceptanceDao = database.savingsSweepMonthAcceptanceDao(),
            writeBarrier = writeBarrier,
            timeProvider = timeProvider
        )
        historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        historyFile = Files.createTempFile("savings-goal-delete-history", ".preferences_pb").toFile()
        dataStore = PreferenceDataStoreFactory.create(scope = historyScope, produceFile = { historyFile })
        // Production wiring (SavingsModule): the history repository sees the ledger DAO.
        history = SavingsContributionHistoryRepository(
            dataStore, timeProvider, database.savingsContributionLedgerDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
        historyScope.cancel()
        historyFile.delete()
    }

    // ---- AE-1 ---------------------------------------------------------------------------

    @Test
    fun `AE-1 empty goal is deleted and no other table or goal changes`() = runTest {
        insertGoal(1L, 0.0)
        insertGoal(2L, 0.0)
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(recommendation(allocation(2L, 0.0, 40.0))))
        val before = tables()
        assertEquals(1, before.ledger.size)
        assertEquals(1, before.plans.size)
        assertEquals(1, before.markers.size)

        val result = repository().deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Deleted, result)
        assertNull(database.savingsGoalDao().getById(1L))
        val after = tables()
        assertEquals(before.ledger, after.ledger)
        assertEquals(before.plans, after.plans)
        assertEquals(before.markers, after.markers)
        assertEquals(before.historyJson, after.historyJson)
        assertEquals(before.goals.filter { it.id == 2L }, after.goals)
        verify(exactly = 1) { writeBarrier.checkWritesAllowed("SavingsGoalRepository.deleteSavingsGoal") }
    }

    // ---- AE-2 ---------------------------------------------------------------------------

    @Test
    fun `AE-2 goal with balance is rejected with HAS_BALANCE and every table is identical`() = runTest {
        insertGoal(1L, 10.0)
        val before = tables()

        val result = repository().deleteSavingsGoal(domainGoal(1L, current = 10.0))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE), result)
        assertEquals(before, tables())
    }

    @Test
    fun `AE-2 negative balance row fails closed as HAS_BALANCE`() = runTest {
        insertGoal(1L, -1.0)
        val before = tables()

        val result = repository().deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE), result)
        assertEquals(before, tables())
    }

    // ---- AE-3 ---------------------------------------------------------------------------

    @Test
    fun `AE-3 goal funded by an accepted sweep is rejected and ledger plan and marker survive`() = runTest {
        insertGoal(1L, 0.0)
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(recommendation(allocation(1L, 0.0, 40.0))))
        val before = tables()
        assertEquals(40.0, before.goals.single().currentAmount, 0.0)
        assertEquals(1, before.ledger.size)
        assertEquals(1, before.plans.size)
        assertEquals(1, before.markers.size)

        val result = repository().deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE), result)
        assertEquals(before, tables())
    }

    @Test
    fun `AE-3 variant balance forced to zero with a ledger row is rejected as HAS_HISTORY`() = runTest {
        insertGoal(1L, 0.0)
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(recommendation(allocation(1L, 0.0, 40.0))))
        database.savingsGoalDao().updateGoalAmount(1L, 0.0)
        val before = tables()
        assertEquals(0.0, before.goals.single().currentAmount, 0.0)
        assertEquals(1, before.ledger.size)

        val result = repository().deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY), result)
        assertEquals(before, tables())
    }

    // ---- AE-4 ---------------------------------------------------------------------------

    @Test
    fun `AE-4 zero balance goal with a DataStore event is rejected as HAS_HISTORY and the event is untouched`() = runTest {
        insertGoal(1L, 0.0)
        assertTrue(history.recordContribution(1L, 10.0, timeProvider.now(), "manual", "EUR"))
        val before = tables()
        assertNotNull(before.historyJson)

        val result = repository().deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY), result)
        assertEquals(before, tables())
        assertEquals(1, history.getAllContributions().count { it.goalId == 1L })
    }

    @Test
    fun `AE-4 control an event for a different goal does not block deleting an empty goal`() = runTest {
        insertGoal(1L, 0.0)
        insertGoal(2L, 10.0)
        assertTrue(history.recordContribution(2L, 10.0, timeProvider.now(), "manual", "EUR"))
        val historyBefore = history.snapshotJson()

        val result = repository().deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Deleted, result)
        assertNull(database.savingsGoalDao().getById(1L))
        assertNotNull(database.savingsGoalDao().getById(2L))
        assertEquals(historyBefore, history.snapshotJson())
    }

    // ---- AE-5 / INV-2 -------------------------------------------------------------------

    @Test
    fun `AE-5 stale snapshot with zero balance cannot delete a row that now has a balance`() = runTest {
        insertGoal(1L, 5.0)
        val before = tables()

        val result = repository().deleteSavingsGoal(domainGoal(1L, current = 0.0))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE), result)
        assertEquals(before, tables())
        assertEquals(5.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
    }

    @Test
    fun `INV-2 only the id of the passed snapshot is used and the entity deleted is the one re-read`() = runTest {
        insertGoal(1L, 0.0)
        val stored = database.savingsGoalDao().getById(1L)!!
        val recording = RecordingGoalDao(database.savingsGoalDao())
        val staleSnapshot = SavingsGoal(
            id = 1L, name = "Stale Snapshot Name", targetAmount = 5.0, currentAmount = 0.0,
            targetDate = 123L, protectionLevel = com.yourname.expensetracker.domain.model.GoalProtectionLevel.STRICT,
            currency = "USD", currencyAssumption = "EXPLICIT", createdAt = 7L
        )

        val result = repository(goalDao = recording).deleteSavingsGoal(staleSnapshot)

        assertEquals(SavingsGoalDeleteResult.Deleted, result)
        assertEquals(listOf(stored), recording.deletedEntities)
        assertNull(database.savingsGoalDao().getById(1L))
    }

    // ---- AE-6 (repository half) ---------------------------------------------------------

    @Test
    fun `AE-6 write barrier held rethrows the blocked exception and reads or writes nothing`() = runTest {
        insertGoal(1L, 0.0)
        val blocked = DatabaseAccessBlockedException(
            DatabaseAccessType.WRITE,
            DatabaseAccessOperation("SavingsGoalRepository.deleteSavingsGoal"),
            RestoreMaintenanceMode.Mode.RESTORE_STAGING
        )
        val blockedBarrier = mockk<DatabaseWriteBarrier>()
        every { blockedBarrier.checkWritesAllowed("SavingsGoalRepository.deleteSavingsGoal") } throws blocked
        val goalDao = RecordingGoalDao(database.savingsGoalDao())
        val ledgerDao = RecordingLedgerDao(database.savingsContributionLedgerDao())
        val countingStore = CountingDataStore(dataStore)
        val countingHistory = SavingsContributionHistoryRepository(countingStore, timeProvider, ledgerDao)
        val before = tables()

        try {
            repository(
                barrier = blockedBarrier,
                goalDao = goalDao,
                ledgerDao = ledgerDao,
                historyRepository = countingHistory
            ).deleteSavingsGoal(domainGoal(1L))
            throw AssertionError("DatabaseAccessBlockedException was swallowed")
        } catch (error: DatabaseAccessBlockedException) {
            assertTrue(error === blocked)
        }

        assertEquals(0, goalDao.getByIdCalls)
        assertEquals(0, goalDao.deleteCalls)
        assertEquals(0, ledgerDao.getAllCalls)
        assertEquals(0, countingStore.dataReads)
        assertEquals(before, tables())
    }

    // ---- AE-7 ---------------------------------------------------------------------------

    @Test
    fun `AE-7 retry after success and an unknown id both return AlreadyAbsent without touching other goals`() = runTest {
        insertGoal(1L, 0.0)
        insertGoal(2L, 0.0)
        val repository = repository()

        assertEquals(SavingsGoalDeleteResult.Deleted, repository.deleteSavingsGoal(domainGoal(1L)))
        val goal2 = database.savingsGoalDao().getById(2L)

        assertEquals(SavingsGoalDeleteResult.AlreadyAbsent, repository.deleteSavingsGoal(domainGoal(1L)))
        assertEquals(SavingsGoalDeleteResult.AlreadyAbsent, repository.deleteSavingsGoal(domainGoal(999L)))
        assertEquals(goal2, database.savingsGoalDao().getById(2L))
    }

    @Test
    fun `AE-7 absent goal stays AlreadyAbsent even when an orphan event names its id`() = runTest {
        insertGoal(2L, 0.0)
        assertTrue(history.recordContribution(999L, 10.0, timeProvider.now(), "manual", "EUR"))
        val before = tables()

        assertEquals(SavingsGoalDeleteResult.AlreadyAbsent, repository().deleteSavingsGoal(domainGoal(999L)))
        assertEquals(before, tables())
    }

    // ---- AE-8 ---------------------------------------------------------------------------

    @Test
    fun `AE-8 deleting an empty goal after a sweep never reopens the consumed month`() = runTest {
        insertGoal(1L, 0.0)
        val first = recommendation(allocation(1L, 0.0, 40.0))
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(first))
        insertGoal(2L, 0.0)
        val markersBefore = database.savingsSweepMonthAcceptanceDao().getAll()
        assertEquals(1, markersBefore.size)

        assertEquals(SavingsGoalDeleteResult.Deleted, repository().deleteSavingsGoal(domainGoal(2L)))

        assertEquals(markersBefore, database.savingsSweepMonthAcceptanceDao().getAll())
        assertEquals(1, database.savingsContributionLedgerDao().getAll().size)
        val recomputed = recommendation(allocation(1L, 40.0, 40.0)).copy(computedAt = first.computedAt + 1L)
        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_MONTH_ALREADY_ACCEPTED"),
            coordinator.accept(recomputed)
        )
        assertEquals(40.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
    }

    // ---- AE-12 / INV-10 -----------------------------------------------------------------

    @Test
    fun `AE-12 corrupt DataStore on an empty goal is HISTORY_UNAVAILABLE and the goal is intact`() = runTest {
        insertGoal(1L, 0.0)
        corruptHistory()
        val before = tables()

        val result = repository().deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE), result)
        assertEquals(before, tables())
    }

    @Test
    fun `AE-12 corrupt DataStore on an absent goal is AlreadyAbsent`() = runTest {
        insertGoal(2L, 0.0)
        corruptHistory()
        val before = tables()

        assertEquals(SavingsGoalDeleteResult.AlreadyAbsent, repository().deleteSavingsGoal(domainGoal(1L)))
        assertEquals(before, tables())
    }

    @Test
    fun `AE-12 corrupt DataStore on a funded goal is HAS_BALANCE`() = runTest {
        insertGoal(1L, 10.0)
        corruptHistory()
        val before = tables()

        val result = repository().deleteSavingsGoal(domainGoal(1L, current = 10.0))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE), result)
        assertEquals(before, tables())
    }

    @Test
    fun `AE-12 corrupt DataStore with a known ledger row on a zero balance goal is HAS_HISTORY`() = runTest {
        insertGoal(1L, 0.0)
        database.savingsContributionLedgerDao().insert(ledgerRow("ae12-known-row", goalId = 1L))
        corruptHistory()
        val before = tables()

        val result = repository().deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY), result)
        assertEquals(before, tables())
    }

    @Test
    fun `AE-12 control readable empty history deletes the same empty goal`() = runTest {
        insertGoal(1L, 0.0)

        assertEquals(SavingsGoalDeleteResult.Deleted, repository().deleteSavingsGoal(domainGoal(1L)))
        assertNull(database.savingsGoalDao().getById(1L))
    }

    // ---- AE-15 / INV-13 / INV-14 (repository half) --------------------------------------

    @Test
    fun `AE-15 DAO failure after the delete rolls back and returns the bounded UNAVAILABLE code`() = runTest {
        insertGoal(1L, 0.0)
        val before = tables()
        val failing = RecordingGoalDao(database.savingsGoalDao()).apply {
            failAfterRealDelete = IllegalStateException(SECRET)
        }

        val result = repository(goalDao = failing).deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_UNAVAILABLE), result)
        assertEquals(1, failing.deleteCalls)
        // The real delete ran inside the transaction and must have been rolled back.
        assertEquals(before, tables())
        assertNoSecretOrGoalNameLeak(result.toString())
    }

    @Test
    fun `AE-15 ledger read failure in the transaction returns UNAVAILABLE and deletes nothing`() = runTest {
        insertGoal(1L, 0.0)
        val before = tables()
        val failing = RecordingLedgerDao(database.savingsContributionLedgerDao()).apply {
            getAllError = RuntimeException(SECRET)
        }

        val result = repository(ledgerDao = failing).deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_UNAVAILABLE), result)
        assertEquals(before, tables())
        assertNoSecretOrGoalNameLeak(result.toString())
    }

    @Test
    fun `AE-15 non-corruption DataStore failure returns UNAVAILABLE rather than HISTORY_UNAVAILABLE`() = runTest {
        insertGoal(1L, 0.0)
        val before = tables()
        val failingHistory = SavingsContributionHistoryRepository(
            FailingDataStore(IOException(SECRET)), timeProvider, database.savingsContributionLedgerDao()
        )

        val result = repository(historyRepository = failingHistory).deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_UNAVAILABLE), result)
        assertEquals(before, tables())
        assertNoSecretOrGoalNameLeak(result.toString())
    }

    @Test
    fun `AE-15 eligibility failure also returns only the bounded UNAVAILABLE code`() = runTest {
        insertGoal(1L, 0.0)
        val failing = RecordingLedgerDao(database.savingsContributionLedgerDao()).apply {
            getAllError = RuntimeException(SECRET)
        }

        val result = repository(ledgerDao = failing).getSavingsGoalDeletionEligibility(1L)

        assertEquals(SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_UNAVAILABLE), result)
        assertNoSecretOrGoalNameLeak(result.toString())
    }

    // ---- INV-12 (basic rethrow; mid-transaction interleavings are in the concurrency class) ----

    @Test
    fun `INV-12 cancellation thrown inside the transaction is rethrown unchanged and rolled back`() = runTest {
        insertGoal(1L, 0.0)
        val before = tables()
        val cancellation = InjectedCancellation("goal delete")
        val failing = RecordingGoalDao(database.savingsGoalDao()).apply { failAfterRealDelete = cancellation }

        try {
            repository(goalDao = failing).deleteSavingsGoal(domainGoal(1L))
            throw AssertionError("CancellationException was swallowed")
        } catch (error: CancellationException) {
            assertTrue(error === cancellation)
        }

        assertEquals(before, tables())
    }

    // ---- AE-18 --------------------------------------------------------------------------

    @Test
    fun `AE-18 ledger row with zero balance is rejected as HAS_HISTORY and the row survives`() = runTest {
        insertGoal(1L, 0.0)
        database.savingsContributionLedgerDao().insert(ledgerRow("ae18-row", goalId = 1L))
        val before = tables()
        assertEquals(1, before.ledger.size)

        val result = repository().deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY), result)
        assertEquals(before, tables())
    }

    @Test
    fun `AE-18 production-wired history repository sees the ledger row for the goal`() = runTest {
        insertGoal(1L, 0.0)
        database.savingsContributionLedgerDao().insert(ledgerRow("ae18-visible", goalId = 1L))

        val events = history.getAllContributions()

        assertEquals(1, events.count { it.goalId == 1L })
    }

    @Test
    fun `AE-18 ledger row is still HAS_HISTORY when the history repository was wired without a ledger DAO`() = runTest {
        insertGoal(1L, 0.0)
        database.savingsContributionLedgerDao().insert(ledgerRow("ae18-null-wired", goalId = 1L))
        val nullWiredHistory = SavingsContributionHistoryRepository(dataStore, timeProvider, null)
        // Precondition: the null-wired history alone cannot see the ledger row.
        assertTrue(nullWiredHistory.getAllContributions().none { it.goalId == 1L })
        val before = tables()

        val result = repository(historyRepository = nullWiredHistory).deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY), result)
        assertEquals(before, tables())
    }

    @Test
    fun `AE-18 control a ledger row for another goal does not block deleting an empty goal`() = runTest {
        insertGoal(1L, 0.0)
        insertGoal(2L, 0.0)
        database.savingsContributionLedgerDao().insert(ledgerRow("ae18-other", goalId = 2L))

        assertEquals(SavingsGoalDeleteResult.Deleted, repository().deleteSavingsGoal(domainGoal(1L)))
        assertNull(database.savingsGoalDao().getById(1L))
        assertEquals(1, database.savingsContributionLedgerDao().getAll().size)
    }

    // ---- INV-15 -------------------------------------------------------------------------

    @Test
    fun `INV-15 deleting an empty goal leaves total saved and every other goal bit-identical`() = runTest {
        insertGoal(1L, 0.0)
        insertGoal(2L, 30.0)
        insertGoal(3L, 0.1 + 0.2)
        val before = tables()
        val totalBefore = before.goals.filter { it.id != 1L }.sumOf { it.currentAmount }

        assertEquals(SavingsGoalDeleteResult.Deleted, repository().deleteSavingsGoal(domainGoal(1L)))

        val after = tables()
        assertEquals(before.goals.filter { it.id != 1L }, after.goals)
        assertEquals(totalBefore, after.goals.sumOf { it.currentAmount }, 0.0)
        assertEquals(
            (0.1 + 0.2).toRawBits(),
            after.goals.single { it.id == 3L }.currentAmount.toRawBits()
        )
    }

    // ---- AE-20 (repository half) --------------------------------------------------------

    @Test
    fun `AE-20 eligibility mutates nothing and returns the same code the delete returns`() = runTest {
        insertGoal(1L, 10.0) // funded
        insertGoal(2L, 0.0) // zero balance + DataStore event
        insertGoal(3L, 0.0) // zero balance + ledger row
        insertGoal(4L, 0.0) // empty
        assertTrue(history.recordContribution(2L, 10.0, timeProvider.now(), "manual", "EUR"))
        database.savingsContributionLedgerDao().insert(ledgerRow("ae20-row", goalId = 3L))
        val goalDao = RecordingGoalDao(database.savingsGoalDao())
        val repository = repository(goalDao = goalDao)
        val before = tables()

        val eligibility = (1L..4L).map { repository.getSavingsGoalDeletionEligibility(it) }
        val absent = repository.getSavingsGoalDeletionEligibility(999L)

        assertEquals(
            listOf(
                SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE),
                SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY),
                SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY),
                SavingsGoalDeleteEligibility.Deletable
            ),
            eligibility
        )
        assertEquals(SavingsGoalDeleteEligibility.AlreadyAbsent, absent)
        assertEquals(0, goalDao.deleteCalls)
        assertEquals(before, tables())

        // The delete must agree with the eligibility answer for every goal.
        for (id in 1L..3L) {
            val deleted = repository.deleteSavingsGoal(domainGoal(id))
            val expected = eligibility[(id - 1).toInt()] as SavingsGoalDeleteEligibility.Rejected
            assertEquals(SavingsGoalDeleteResult.Rejected(expected.reasonCode), deleted)
        }
        assertEquals(before, tables())
        assertEquals(SavingsGoalDeleteResult.Deleted, repository.deleteSavingsGoal(domainGoal(4L)))
        assertEquals(SavingsGoalDeleteEligibility.AlreadyAbsent, repository.getSavingsGoalDeletionEligibility(4L))
    }

    @Test
    fun `AE-20 eligibility with a corrupt DataStore agrees with the delete`() = runTest {
        insertGoal(1L, 0.0)
        corruptHistory()
        val before = tables()
        val repository = repository()

        assertEquals(
            SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE),
            repository.getSavingsGoalDeletionEligibility(1L)
        )
        assertEquals(SavingsGoalDeleteEligibility.AlreadyAbsent, repository.getSavingsGoalDeletionEligibility(999L))
        assertEquals(before, tables())
        assertEquals(
            SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HISTORY_UNAVAILABLE),
            repository.deleteSavingsGoal(domainGoal(1L))
        )
        assertEquals(before, tables())
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun repository(
        barrier: DatabaseWriteBarrier = writeBarrier,
        goalDao: SavingsGoalDao = database.savingsGoalDao(),
        ledgerDao: SavingsContributionLedgerDao = database.savingsContributionLedgerDao(),
        historyRepository: SavingsContributionHistoryRepository = history
    ) = SavingsGoalRepository(barrier, goalDao, database, historyRepository, ledgerDao)

    private suspend fun insertGoal(id: Long, balance: Double) {
        database.savingsGoalDao().insertGoal(
            SavingsGoalEntity(
                id = id,
                name = "Test Goal $id",
                targetAmount = 1_000.0,
                currentAmount = balance,
                protectionLevel = GoalProtectionLevel.WARNING,
                currency = "EUR",
                currencyAssumption = "EXPLICIT",
                createdAt = timeProvider.now()
            )
        )
    }

    private fun domainGoal(id: Long, current: Double = 0.0) = SavingsGoal(
        id = id,
        name = "Test Goal $id",
        targetAmount = 1_000.0,
        currentAmount = current,
        targetDate = null,
        protectionLevel = com.yourname.expensetracker.domain.model.GoalProtectionLevel.WARNING,
        currency = "EUR",
        currencyAssumption = "EXPLICIT",
        createdAt = timeProvider.now()
    )

    private suspend fun corruptHistory() {
        dataStore.edit { it[historyKey] = "{ not valid json" }
    }

    private fun ledgerRow(key: String, goalId: Long) = SavingsContributionLedgerEntry(
        idempotencyKey = key,
        goalId = goalId,
        amount = 40.0,
        currency = "EUR",
        timestamp = timeProvider.now(),
        source = "sweep",
        sweepPlanId = null
    )

    private suspend fun tables() = Tables(
        goals = database.savingsGoalDao().getAllGoals().first().sortedBy { it.id },
        ledger = database.savingsContributionLedgerDao().getAll(),
        plans = database.savingsSweepPlanDao().getAllPlans(),
        markers = database.savingsSweepMonthAcceptanceDao().getAll(),
        historyJson = history.snapshotJson()
    )

    private fun assertNoSecretOrGoalNameLeak(renderedResult: String) {
        assertFalse(renderedResult.contains(SECRET))
        assertFalse(renderedResult.contains("Test Goal"))
        for (entry in ShadowLog.getLogs()) {
            val text = listOfNotNull(entry.msg, entry.tag, entry.throwable?.message).joinToString(" ")
            assertFalse("log leaked secret text: $text", text.contains(SECRET))
            assertFalse("log leaked goal name: $text", text.contains("Test Goal"))
        }
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
        computedAt = timeProvider.now(),
        monthEnd = timeProvider.now() + 1_000L,
        confidence = 0.9,
        currency = "EUR",
        rateTimestamp = 0L
    )

    private data class Tables(
        val goals: List<SavingsGoalEntity>,
        val ledger: List<SavingsContributionLedgerEntry>,
        val plans: List<SavingsSweepPlan>,
        val markers: List<SavingsSweepMonthAcceptance>,
        val historyJson: String?
    )

    private class InjectedCancellation(val checkpoint: String) :
        CancellationException("cancelled inside transaction")

    /** Delegates to the real DAO; records calls; can fail AFTER the real delete to prove rollback. */
    private class RecordingGoalDao(private val real: SavingsGoalDao) : SavingsGoalDao by real {
        var getByIdCalls = 0
        var deleteCalls = 0
        val deletedEntities = mutableListOf<SavingsGoalEntity>()
        var failAfterRealDelete: Throwable? = null

        override suspend fun getById(goalId: Long): SavingsGoalEntity? {
            getByIdCalls++
            return real.getById(goalId)
        }

        override suspend fun deleteGoal(goal: SavingsGoalEntity) {
            deleteCalls++
            deletedEntities += goal
            real.deleteGoal(goal)
            failAfterRealDelete?.let { throw it }
        }
    }

    private class RecordingLedgerDao(private val real: SavingsContributionLedgerDao) :
        SavingsContributionLedgerDao by real {
        var getAllCalls = 0
        var getAllError: Throwable? = null

        override suspend fun getAll(): List<SavingsContributionLedgerEntry> {
            getAllCalls++
            getAllError?.let { throw it }
            return real.getAll()
        }
    }

    private class CountingDataStore(private val real: DataStore<Preferences>) : DataStore<Preferences> by real {
        var dataReads = 0

        override val data: Flow<Preferences>
            get() {
                dataReads++
                return real.data
            }
    }

    private class FailingDataStore(private val error: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw error
    }

    private companion object {
        const val SECRET = "secret text"
    }
}
