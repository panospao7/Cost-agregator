package com.yourname.expensetracker.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.SavingsContributionLedgerDao
import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.data.database.dao.SavingsSweepMonthAcceptanceDao
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel
import com.yourname.expensetracker.data.database.entity.SavingsContributionLedgerEntry
import com.yourname.expensetracker.data.database.entity.SavingsSweepMonthAcceptance
import com.yourname.expensetracker.data.database.entity.SavingsSweepPlan
import com.yourname.expensetracker.data.database.entity.SweepPlanStatus
import com.yourname.expensetracker.data.database.entity.SavingsGoal as SavingsGoalEntity
import com.yourname.expensetracker.domain.model.SavingsGoal
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic interleaving tests for [SavingsGoalRepository.deleteSavingsGoal]
 * (savings-goal-delete S2, part 2).
 *
 * Oracle: docs/features/savings-goal-delete/domain-spec.md (AE-9, AE-10, AE-11, AE-14, AE-17;
 * INV-8, INV-9, INV-12, INV-17).
 *
 * Interleavings are forced with gates (a suspended DAO wrapper that signals "entered" and then
 * waits for the test to open it). These tests prove the serialization of the check-and-delete
 * logic against the real [SavingsSweepAcceptanceCoordinator] and the contribution sequence, not
 * SQLite locking. Every await, join and gate is bounded by a real-time timeout. They run on
 * runBlocking (real time) rather than runTest because withTimeout under a virtual-time scheduler
 * would fire as soon as the scheduler is idle while Room threads are still working.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SavingsGoalRepositoryDeleteConcurrencyTest {

    private lateinit var database: AppDatabase
    private lateinit var coordinator: SavingsSweepAcceptanceCoordinator
    private lateinit var historyScope: CoroutineScope
    private lateinit var historyFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var history: SavingsContributionHistoryRepository
    private val writeBarrier = mockk<DatabaseWriteBarrier>()
    private val timeProvider = FakeTimeProvider(1_710_000_000_000L)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        every { writeBarrier.checkWritesAllowed(any<String>()) } just runs
        coordinator = newCoordinator()
        historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        historyFile = Files.createTempFile("savings-goal-delete-concurrency-history", ".preferences_pb").toFile()
        dataStore = PreferenceDataStoreFactory.create(scope = historyScope, produceFile = { historyFile })
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

    // ---- AE-9 (INV-8 i): delete commits first, then the sweep accept ----------------------

    @Test
    fun `AE-9 sequential delete commits then accept is rejected as GOAL_NOT_FOUND and writes nothing`() = runBounded {
        insertGoal(1L, 0.0)
        val recommendation = recommendation(allocation(1L, 0.0, 40.0))

        assertEquals(SavingsGoalDeleteResult.Deleted, repository().deleteSavingsGoal(domainGoal(1L)))
        val accepted = coordinator.accept(recommendation)

        assertEquals(SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_GOAL_NOT_FOUND"), accepted)
        assertNull(database.savingsGoalDao().getById(1L))
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsSweepMonthAcceptanceDao().getAll().isEmpty())
    }

    @Test
    fun `AE-9 accept started while the delete transaction is open is rejected and the batch rolls back`() = runBounded {
        insertGoal(1L, 0.0)
        val recommendation = recommendation(allocation(1L, 0.0, 40.0))
        val goalDao = newGoalProbe()
        val gate = Gate()
        goalDao.onDeleteAfterReal = { gate.pass() }

        val delete = async { repository(goalDao = goalDao).deleteSavingsGoal(domainGoal(1L)) }
        awaitBounded(gate.entered)
        val accept = async { coordinator.accept(recommendation) }
        settle()
        gate.open()

        assertEquals(SavingsGoalDeleteResult.Deleted, awaitBounded(delete))
        assertEquals(
            SavingsSweepAcceptanceResult.Rejected("SAVINGS_SWEEP_GOAL_NOT_FOUND"),
            awaitBounded(accept)
        )
        assertNull(database.savingsGoalDao().getById(1L))
        assertTrue(database.savingsContributionLedgerDao().getAll().isEmpty())
        assertTrue(database.savingsSweepPlanDao().getAllPlans().isEmpty())
        assertTrue(database.savingsSweepMonthAcceptanceDao().getAll().isEmpty())
        assertNoCommittedSweepWithRemovedRows()
    }

    // ---- AE-10 (INV-8 ii): accept commits first, then the delete ---------------------------

    @Test
    fun `AE-10 sequential accept commits then delete is rejected as HAS_BALANCE and every row survives`() = runBounded {
        insertGoal(1L, 0.0)
        assertEquals(SavingsSweepAcceptanceResult.Accepted, coordinator.accept(recommendation(allocation(1L, 0.0, 40.0))))
        val before = tables()

        val result = repository().deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE), result)
        assertEquals(before, tables())
        assertCommittedSweepIntact()
    }

    @Test
    fun `AE-10 delete started while the accept transaction is open is rejected after the accept commits`() = runBounded {
        insertGoal(1L, 0.0)
        val recommendation = recommendation(allocation(1L, 0.0, 40.0))
        val markerDao = ProbeMarkerDao(database.savingsSweepMonthAcceptanceDao())
        val gate = Gate()
        markerDao.onInsertAfterReal = { gate.pass() }

        val accept = async { newCoordinator(markerDao).accept(recommendation) }
        awaitBounded(gate.entered)
        val delete = async { repository().deleteSavingsGoal(domainGoal(1L)) }
        settle()
        gate.open()

        assertEquals(SavingsSweepAcceptanceResult.Accepted, awaitBounded(accept))
        assertEquals(
            SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE),
            awaitBounded(delete)
        )
        assertCommittedSweepIntact()
        assertNoCommittedSweepWithRemovedRows()
    }

    // ---- AE-11 (INV-9): contribution vs delete -----------------------------------------------

    @Test
    fun `AE-11 increment first then delete is rejected as HAS_BALANCE and the one event survives`() = runBounded {
        insertGoal(1L, 0.0)

        assertTrue(contribute(repository(), 1L, 10.0))
        val result = repository().deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE), result)
        assertEquals(10.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(1, history.getAllContributions().count { it.goalId == 1L })
    }

    @Test
    fun `AE-11 delete first then increment returns false and records zero events`() = runBounded {
        insertGoal(1L, 0.0)

        assertEquals(SavingsGoalDeleteResult.Deleted, repository().deleteSavingsGoal(domainGoal(1L)))
        assertTrue(!contribute(repository(), 1L, 10.0))

        assertNull(database.savingsGoalDao().getById(1L))
        assertEquals(0, history.getAllContributions().count { it.goalId == 1L })
        assertNull(history.snapshotJson())
    }

    @Test
    fun `AE-11 delete runs between the committed increment and its history event and is rejected`() = runBounded {
        insertGoal(1L, 0.0)
        val goalDao = newGoalProbe()
        val gate = Gate()
        goalDao.onAddAfterReal = { gate.pass() }

        val contribution = async { contribute(repository(goalDao = goalDao), 1L, 10.0) }
        awaitBounded(gate.entered)
        // Balance 10.0 is committed, the event is not recorded yet. The delete must still refuse.
        val result = repository().deleteSavingsGoal(domainGoal(1L))
        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE), result)
        assertEquals(0, history.getAllContributions().count { it.goalId == 1L })
        gate.open()

        assertTrue(awaitBounded(contribution))
        assertEquals(10.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(1, history.getAllContributions().count { it.goalId == 1L })
    }

    @Test
    fun `AE-11 increment started while the delete transaction is open returns false and records zero events`() = runBounded {
        insertGoal(1L, 0.0)
        val goalDao = newGoalProbe()
        val gate = Gate()
        goalDao.onDeleteAfterReal = { gate.pass() }

        val delete = async { repository(goalDao = goalDao).deleteSavingsGoal(domainGoal(1L)) }
        awaitBounded(gate.entered)
        val contribution = async { contribute(repository(), 1L, 10.0) }
        settle()
        gate.open()

        assertEquals(SavingsGoalDeleteResult.Deleted, awaitBounded(delete))
        assertTrue(!awaitBounded(contribution))
        assertNull(database.savingsGoalDao().getById(1L))
        assertEquals(0, history.getAllContributions().count { it.goalId == 1L })
        assertNull(history.snapshotJson())
    }

    // ---- AE-14 (INV-12): cancellation -----------------------------------------------------

    @Test
    fun `AE-14 cancellation after the delete inside the transaction rolls back and emits nothing`() = runBounded {
        insertGoal(1L, 0.0)
        insertGoal(2L, 0.0)
        val before = tables()
        val emissions = CopyOnWriteArrayList<List<Long>>()
        val collector = launch {
            database.savingsGoalDao().getAllGoals().collect { goals -> emissions.add(goals.map { it.id }) }
        }
        awaitCondition { emissions.isNotEmpty() }
        val goalDao = newGoalProbe()
        val gate = Gate()
        goalDao.onDeleteAfterReal = { gate.pass() }

        val job = async { repository(goalDao = goalDao).deleteSavingsGoal(domainGoal(1L)) }
        awaitBounded(gate.entered)
        assertEquals(1, goalDao.deleteCalls.get())
        job.cancel()

        assertJobCancelled(job)
        settle()
        assertEquals(before, tables())
        assertTrue("delete became visible to an observer: $emissions", emissions.all { 1L in it && 2L in it })
        collector.cancel()
    }

    @Test
    fun `AE-14 cancellation inside the transaction before the delete leaves every table untouched`() = runBounded {
        insertGoal(1L, 0.0)
        val before = tables()
        val goalDao = newGoalProbe()
        val ledgerDao = newLedgerProbe("repo")
        val gate = Gate()
        ledgerDao.onGetAllBefore = { gate.pass() }

        val job = async { repository(goalDao = goalDao, ledgerDao = ledgerDao).deleteSavingsGoal(domainGoal(1L)) }
        awaitBounded(gate.entered)
        job.cancel()

        assertJobCancelled(job)
        assertEquals(0, goalDao.deleteCalls.get())
        assertEquals(before, tables())
        assertNotNull(database.savingsGoalDao().getById(1L))
    }

    @Test
    fun `AE-14 cancellation during the history pre-read touches nothing and emits nothing`() = runBounded {
        insertGoal(1L, 0.0)
        val before = tables()
        val emissions = CopyOnWriteArrayList<List<Long>>()
        val collector = launch {
            database.savingsGoalDao().getAllGoals().collect { goals -> emissions.add(goals.map { it.id }) }
        }
        awaitCondition { emissions.isNotEmpty() }
        val goalDao = newGoalProbe()
        val ledgerDao = newLedgerProbe("repo")
        val store = ProbeDataStore(dataStore, ::inTransaction, newLog())
        val gate = Gate()
        store.onData = { gate.pass() }
        val probedHistory = SavingsContributionHistoryRepository(store, timeProvider, database.savingsContributionLedgerDao())

        val job = async {
            repository(goalDao = goalDao, ledgerDao = ledgerDao, historyRepository = probedHistory)
                .deleteSavingsGoal(domainGoal(1L))
        }
        awaitBounded(gate.entered)
        job.cancel()

        assertJobCancelled(job)
        settle()
        assertEquals(0, goalDao.getByIdCalls.get())
        assertEquals(0, goalDao.deleteCalls.get())
        assertEquals(0, ledgerDao.getAllCalls.get())
        assertEquals(before, tables())
        assertTrue("pre-read cancellation emitted: $emissions", emissions.all { it == listOf(1L) })
        collector.cancel()
    }

    @Test
    fun `AE-14 CancellationException thrown by the pre-read is rethrown unchanged and never becomes a result`() = runBounded {
        insertGoal(1L, 0.0)
        val before = tables()
        val cancellation = InjectedCancellation("history pre-read")
        val goalDao = newGoalProbe()
        val ledgerDao = newLedgerProbe("repo")
        val store = ProbeDataStore(dataStore, ::inTransaction, newLog())
        store.onData = { throw cancellation }
        val probedHistory = SavingsContributionHistoryRepository(store, timeProvider, database.savingsContributionLedgerDao())

        try {
            repository(goalDao = goalDao, ledgerDao = ledgerDao, historyRepository = probedHistory)
                .deleteSavingsGoal(domainGoal(1L))
            throw AssertionError("CancellationException was swallowed")
        } catch (error: CancellationException) {
            assertTrue(error === cancellation)
        }

        assertEquals(0, goalDao.getByIdCalls.get())
        assertEquals(0, goalDao.deleteCalls.get())
        assertEquals(0, ledgerDao.getAllCalls.get())
        assertEquals(before, tables())
    }

    // ---- AE-17 (INV-17): history read outside the transaction, stale pre-read -----------------

    @Test
    fun `AE-17 history read runs while no transaction is open and the fact reads run inside one`() = runBounded {
        insertGoal(1L, 0.0)
        val log = newLog()
        val store = ProbeDataStore(dataStore, ::inTransaction, log)
        val historyLedger = ProbeLedgerDao(database.savingsContributionLedgerDao(), "history", ::inTransaction, log)
        val probedHistory = SavingsContributionHistoryRepository(store, timeProvider, historyLedger)
        val goalDao = ProbeGoalDao(database.savingsGoalDao(), ::inTransaction, log)
        val repoLedger = ProbeLedgerDao(database.savingsContributionLedgerDao(), "repo", ::inTransaction, log)

        val result = repository(goalDao = goalDao, ledgerDao = repoLedger, historyRepository = probedHistory)
            .deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Deleted, result)
        val entries = log.toList()
        val historyEntries = entries.filter { it.startsWith("history.") }
        val factEntries = entries.filter { !it.startsWith("history.") }
        assertTrue("datastore pre-read not observed: $entries", historyEntries.any { it.startsWith("history.datastore") })
        assertTrue("ledger pre-read not observed: $entries", historyEntries.any { it.startsWith("history.ledger") })
        assertTrue("history read ran inside a transaction: $entries", historyEntries.all { it.endsWith("tx=false") })
        // Positive control: the probe really detects an open transaction on the in-transaction reads.
        assertTrue("goal re-read not observed: $entries", factEntries.any { it.startsWith("goal.getById") })
        assertTrue("ledger re-check not observed: $entries", factEntries.any { it.startsWith("repo.ledger") })
        assertTrue("goal delete not observed: $entries", factEntries.any { it.startsWith("goal.deleteGoal") })
        assertTrue("fact read ran outside a transaction: $entries", factEntries.all { it.endsWith("tx=true") })
        assertTrue(
            "history read must finish before the transaction starts: $entries",
            entries.indexOfLast { it.startsWith("history.") } < entries.indexOfFirst { !it.startsWith("history.") }
        )
    }

    @Test
    fun `AE-17 increment committed between the pre-read and the transaction makes the delete HAS_BALANCE`() = runBounded {
        insertGoal(1L, 0.0)
        val log = newLog()
        val historyLedger = ProbeLedgerDao(database.savingsContributionLedgerDao(), "history", ::inTransaction, log)
        val probedHistory = SavingsContributionHistoryRepository(dataStore, timeProvider, historyLedger)
        val hookRan = AtomicInteger()
        val hookInTransaction = CopyOnWriteArrayList<Boolean>()
        historyLedger.onGetAllAfterReal = {
            // The pre-read has already captured "no history" when this runs; the transaction has not started.
            hookRan.incrementAndGet()
            hookInTransaction += inTransaction()
            assertTrue(contribute(repository(), 1L, 10.0))
        }

        val result = repository(historyRepository = probedHistory).deleteSavingsGoal(domainGoal(1L))

        assertEquals(1, hookRan.get())
        assertEquals(listOf(false), hookInTransaction.toList())
        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE), result)
        assertEquals(10.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(1, history.getAllContributions().count { it.goalId == 1L })
    }

    @Test
    fun `AE-17 eligibility also refuses an increment committed between the pre-read and the transaction`() = runBounded {
        insertGoal(1L, 0.0)
        val historyLedger = ProbeLedgerDao(database.savingsContributionLedgerDao(), "history", ::inTransaction, newLog())
        val probedHistory = SavingsContributionHistoryRepository(dataStore, timeProvider, historyLedger)
        historyLedger.onGetAllAfterReal = { assertTrue(contribute(repository(), 1L, 10.0)) }

        val eligibility = repository(historyRepository = probedHistory).getSavingsGoalDeletionEligibility(1L)

        assertEquals(SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_HAS_BALANCE), eligibility)
        assertNotNull(database.savingsGoalDao().getById(1L))
    }

    @Test
    fun `AE-17 ledger row inserted between the pre-read and the transaction makes the delete HAS_HISTORY`() = runBounded {
        insertGoal(1L, 0.0)
        val historyLedger = ProbeLedgerDao(database.savingsContributionLedgerDao(), "history", ::inTransaction, newLog())
        val probedHistory = SavingsContributionHistoryRepository(dataStore, timeProvider, historyLedger)
        val hookRan = AtomicInteger()
        historyLedger.onGetAllAfterReal = {
            hookRan.incrementAndGet()
            database.savingsContributionLedgerDao().insert(ledgerRow("ae17-late-row", goalId = 1L))
        }

        val result = repository(historyRepository = probedHistory).deleteSavingsGoal(domainGoal(1L))

        assertEquals(1, hookRan.get())
        assertEquals(SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_HAS_HISTORY), result)
        assertEquals(0.0, database.savingsGoalDao().getById(1L)!!.currentAmount, 0.0)
        assertEquals(1, database.savingsContributionLedgerDao().getAll().size)
    }

    @Test
    fun `AE-17 control with no interleaving deletes the empty goal through the same probed path`() = runBounded {
        insertGoal(1L, 0.0)
        val historyLedger = ProbeLedgerDao(database.savingsContributionLedgerDao(), "history", ::inTransaction, newLog())
        val probedHistory = SavingsContributionHistoryRepository(dataStore, timeProvider, historyLedger)

        val result = repository(historyRepository = probedHistory).deleteSavingsGoal(domainGoal(1L))

        assertEquals(SavingsGoalDeleteResult.Deleted, result)
        assertEquals(1, historyLedger.getAllCalls.get())
        assertNull(database.savingsGoalDao().getById(1L))
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun runBounded(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(TEST_TIMEOUT_MS) { block() } }
    }

    private fun inTransaction(): Boolean = database.inTransaction()

    private fun newLog(): MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun newGoalProbe() = ProbeGoalDao(database.savingsGoalDao(), ::inTransaction, newLog())

    private fun newLedgerProbe(name: String) =
        ProbeLedgerDao(database.savingsContributionLedgerDao(), name, ::inTransaction, newLog())

    private fun newCoordinator(
        markerDao: SavingsSweepMonthAcceptanceDao = database.savingsSweepMonthAcceptanceDao()
    ) = SavingsSweepAcceptanceCoordinator(
        database = database,
        savingsGoalDao = database.savingsGoalDao(),
        savingsSweepPlanDao = database.savingsSweepPlanDao(),
        contributionLedgerDao = database.savingsContributionLedgerDao(),
        monthAcceptanceDao = markerDao,
        writeBarrier = writeBarrier,
        timeProvider = timeProvider
    )

    private fun repository(
        goalDao: SavingsGoalDao = database.savingsGoalDao(),
        ledgerDao: SavingsContributionLedgerDao = database.savingsContributionLedgerDao(),
        historyRepository: SavingsContributionHistoryRepository = history
    ) = SavingsGoalRepository(writeBarrier, goalDao, database, historyRepository, ledgerDao)

    /**
     * The sequence the ViewModel runs for a quick-add: balance increment first, then the history
     * event only when the increment hit a row. Always records into the shared real history.
     */
    private suspend fun contribute(repository: SavingsGoalRepository, goalId: Long, amount: Double): Boolean {
        val added = repository.incrementSavingsGoalAmount(goalId, amount)
        if (added) {
            assertTrue(history.recordContribution(goalId, amount, timeProvider.now(), "manual", "EUR"))
        }
        return added
    }

    private suspend fun <T> awaitBounded(deferred: Deferred<T>): T =
        withTimeout(JOIN_TIMEOUT_MS) { deferred.await() }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(JOIN_TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    /** Lets the other side reach its blocking point; only affects strength, never correctness. */
    private suspend fun settle() = delay(SETTLE_MS)

    private suspend fun assertJobCancelled(job: Deferred<*>) {
        withTimeout(JOIN_TIMEOUT_MS) { job.join() }
        assertTrue("job was not cancelled", job.isCancelled)
        try {
            job.await()
            throw AssertionError("a cancelled delete produced a result")
        } catch (_: CancellationException) {
            // expected: the cancellation is observed by the job, never mapped to a result
        }
    }

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

    /** After a committed accept for goal 1 (balance 0.0 + 40.0): goal, one ledger row, one plan, one marker. */
    private suspend fun assertCommittedSweepIntact() {
        val goal = database.savingsGoalDao().getById(1L)
        assertNotNull("goal row of a committed sweep was removed", goal)
        assertEquals(40.0, goal!!.currentAmount, 0.0)
        assertEquals(1, database.savingsContributionLedgerDao().getAll().size)
        val plans = database.savingsSweepPlanDao().getAllPlans()
        assertEquals(1, plans.size)
        assertEquals(SweepPlanStatus.ACCEPTED, plans.single().status)
        assertEquals(1, database.savingsSweepMonthAcceptanceDao().getAll().size)
    }

    /**
     * Forbidden state (INV-8): a committed accept whose ledger or plan rows were cascaded away, or whose
     * goal vanished. Either nothing was committed, or the full accept is intact.
     */
    private suspend fun assertNoCommittedSweepWithRemovedRows() {
        val markers = database.savingsSweepMonthAcceptanceDao().getAll()
        val ledger = database.savingsContributionLedgerDao().getAll()
        val plans = database.savingsSweepPlanDao().getAllPlans()
        val goal = database.savingsGoalDao().getById(1L)
        val nothingCommitted = markers.isEmpty() && ledger.isEmpty() && plans.isEmpty()
        val fullyCommitted = markers.size == 1 && ledger.size == 1 && plans.size == 1 &&
            goal != null && goal.currentAmount == 40.0
        assertTrue(
            "forbidden interleaving: markers=${markers.size} ledger=${ledger.size} plans=${plans.size} goalPresent=${goal != null}",
            nothingCommitted || fullyCommitted
        )
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

    /** Extra field prevents stack-trace recovery from copying the exception, so identity stays observable. */
    private class InjectedCancellation(val checkpoint: String) :
        CancellationException("cancelled at a checkpoint")

    /** A suspension point: signals [entered], then waits (bounded) until the test calls [open]. */
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<Unit>()

        suspend fun pass() {
            entered.complete(Unit)
            withTimeout(GATE_TIMEOUT_MS) { release.await() }
        }

        fun open() {
            release.complete(Unit)
        }
    }

    /** Delegates to the real DAO, records calls and the open-transaction state, runs gate hooks. */
    private class ProbeGoalDao(
        private val real: SavingsGoalDao,
        private val inTx: () -> Boolean,
        private val log: MutableList<String>
    ) : SavingsGoalDao by real {
        val getByIdCalls = AtomicInteger()
        val deleteCalls = AtomicInteger()
        @Volatile var onDeleteAfterReal: (suspend () -> Unit)? = null
        @Volatile var onAddAfterReal: (suspend () -> Unit)? = null

        override suspend fun getById(goalId: Long): SavingsGoalEntity? {
            getByIdCalls.incrementAndGet()
            log += "goal.getById tx=${inTx()}"
            return real.getById(goalId)
        }

        override suspend fun deleteGoal(goal: SavingsGoalEntity) {
            deleteCalls.incrementAndGet()
            log += "goal.deleteGoal tx=${inTx()}"
            real.deleteGoal(goal)
            onDeleteAfterReal?.invoke()
        }

        override suspend fun addToGoalAmount(goalId: Long, delta: Double): Int {
            val updated = real.addToGoalAmount(goalId, delta)
            onAddAfterReal?.invoke()
            return updated
        }
    }

    private class ProbeLedgerDao(
        private val real: SavingsContributionLedgerDao,
        private val name: String,
        private val inTx: () -> Boolean,
        private val log: MutableList<String>
    ) : SavingsContributionLedgerDao by real {
        val getAllCalls = AtomicInteger()
        @Volatile var onGetAllBefore: (suspend () -> Unit)? = null
        @Volatile var onGetAllAfterReal: (suspend () -> Unit)? = null

        override suspend fun getAll(): List<SavingsContributionLedgerEntry> {
            getAllCalls.incrementAndGet()
            log += "$name.ledger tx=${inTx()}"
            onGetAllBefore?.invoke()
            val rows = real.getAll()
            onGetAllAfterReal?.invoke()
            return rows
        }
    }

    private class ProbeMarkerDao(private val real: SavingsSweepMonthAcceptanceDao) :
        SavingsSweepMonthAcceptanceDao by real {
        @Volatile var onInsertAfterReal: (suspend () -> Unit)? = null

        override suspend fun insert(acceptance: SavingsSweepMonthAcceptance): Long {
            val id = real.insert(acceptance)
            onInsertAfterReal?.invoke()
            return id
        }
    }

    private class ProbeDataStore(
        private val real: DataStore<Preferences>,
        private val inTx: () -> Boolean,
        private val log: MutableList<String>
    ) : DataStore<Preferences> by real {
        @Volatile var onData: (suspend () -> Unit)? = null

        override val data: Flow<Preferences>
            get() = flow {
                log += "history.datastore tx=${inTx()}"
                onData?.invoke()
                emitAll(real.data)
            }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 120_000L
        const val JOIN_TIMEOUT_MS = 30_000L
        const val GATE_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 200L
        const val POLL_MS = 10L
    }
}
