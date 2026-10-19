package com.yourname.expensetracker.data.database

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.GroupValidationError
import com.yourname.expensetracker.domain.groups.Result
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectBatchResult
import com.yourname.expensetracker.domain.sideeffect.SideEffectCategory
import com.yourname.expensetracker.domain.sideeffect.SideEffectOutcome
import com.yourname.expensetracker.domain.sideeffect.SideEffectTriggerType
import com.yourname.expensetracker.domain.transaction.lifecycle.RoomTransactionLifecycleEventWriter
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectDispatcher
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

private const val ROLLBACK_TEST_DATE = 1_710_000_000_000L

/** Synthetic fault used to simulate a storage failure part-way through a transaction. */
private class InjectedStorageFault(message: String) : RuntimeException(message)

/**
 * Member DAO that delegates to the REAL Room DAO but throws [InjectedStorageFault]
 * on the [failOnInsertNumber]-th member insert (1-based). Inserts before that
 * number really hit the database, so a rollback test using N >= 2 proves that
 * already-written rows (the group row and the earlier members) are undone.
 *
 * [insertAll] is expanded into per-member inserts so the Nth *member* insert is
 * the failure point regardless of whether the coordinator batches.
 */
private class FaultingGroupMemberDao(
    private val delegate: GroupMemberDao,
    private val failOnInsertNumber: Int
) : GroupMemberDao by delegate {

    var insertAttempts: Int = 0
        private set

    /** Member inserts that really reached the database before the fault fired. */
    var realInsertsCompleted: Int = 0
        private set

    private suspend fun insertOrFault(member: GroupMember): Long {
        insertAttempts += 1
        if (insertAttempts == failOnInsertNumber) {
            throw InjectedStorageFault("synthetic member insert fault #$failOnInsertNumber")
        }
        val rowId = delegate.insert(member)
        realInsertsCompleted += 1
        return rowId
    }

    override suspend fun insert(member: GroupMember): Long = insertOrFault(member)

    override suspend fun insertAll(members: List<GroupMember>): List<Long> =
        members.map { insertOrFault(it) }
}

private enum class GroupExpenseInsertFault {
    /** Really inserts the row through the real DAO, then throws. */
    INSERT_THEN_THROW,

    /** Does not insert and returns 0 (the "insert failed" signal the coordinator checks). */
    RETURN_ZERO_WITHOUT_INSERT,

    /**
     * Reports (through [FaultingGroupExpenseDao.getGroupExpenseForExpense]) that some other
     * group expense already owns the freshly created system expense, simulating a concurrent
     * attach. The insert must never be reached in this mode.
     */
    REPORT_ALREADY_ATTACHED
}

/**
 * Group-expense DAO that delegates to the REAL Room DAO and faults on [insert]
 * (or, for [GroupExpenseInsertFault.REPORT_ALREADY_ATTACHED], on the already-attached lookup).
 * [beforeFault] runs inside the coordinator's transaction right before the fault,
 * so a test can record what was already visible (uncommitted) at the fault point.
 */
private class FaultingGroupExpenseDao(
    private val delegate: GroupExpenseDao,
    private val fault: GroupExpenseInsertFault,
    private val beforeFault: suspend () -> Unit = {}
) : GroupExpenseDao by delegate {

    var insertAttempts: Int = 0
        private set

    /** Group-expense inserts that really reached the database. */
    var realInsertsCompleted: Int = 0
        private set

    /** Times the synthetic "already attached" answer was returned. */
    var alreadyAttachedAnswers: Int = 0
        private set

    override suspend fun getGroupExpenseForExpense(expenseId: Long): GroupExpense? {
        if (fault != GroupExpenseInsertFault.REPORT_ALREADY_ATTACHED) {
            return delegate.getGroupExpenseForExpense(expenseId)
        }
        alreadyAttachedAnswers += 1
        beforeFault()
        // Synthetic, never persisted: the row some other group would own.
        return GroupExpense(
            groupId = 0L,
            expenseId = expenseId,
            paidById = 0L,
            date = ROLLBACK_TEST_DATE,
            description = "synthetic foreign link",
            totalAmount = 1.0
        )
    }

    override suspend fun insert(groupExpense: GroupExpense): Long {
        insertAttempts += 1
        return when (fault) {
            GroupExpenseInsertFault.REPORT_ALREADY_ATTACHED ->
                throw InjectedStorageFault("insert must not be reached after the already-attached answer")
            GroupExpenseInsertFault.INSERT_THEN_THROW -> {
                delegate.insert(groupExpense)
                realInsertsCompleted += 1
                beforeFault()
                throw InjectedStorageFault("synthetic group expense insert fault")
            }
            GroupExpenseInsertFault.RETURN_ZERO_WITHOUT_INSERT -> {
                beforeFault()
                0L
            }
        }
    }
}

private enum class ExpenseReadFault {
    THROW,
    RETURN_NULL
}

/**
 * Expense DAO that delegates to the REAL Room DAO and can inject faults:
 * - [faultWhenRowIsShared]: the first [getById] made through THIS wrapper that returns a row
 *   whose `isSharedExpense` is true (i.e. the reload AFTER the real ownership update; the
 *   earlier lookup of the still-unlinked expense is unaffected, so there is no call-count
 *   coupling) throws or returns null per [getByIdFault]. The lifecycle coordinator is wired
 *   to the real DAO, so its writes and reads are never affected.
 * - [throwAfterClearingFlags]: [clearSharedExpenseFlags] really clears the flags and then throws.
 *
 * [onFault] runs inside the coordinator's transaction right before a [getById] fault fires and
 * [onFlagsCleared] right after [clearSharedExpenseFlags] really cleared the flags (before any
 * throw), so tests can record the uncommitted state that existed at the fault point.
 * [lastObservedSharedFlag] is the most recent real, uncommitted `isSharedExpense` value read
 * through the real DAO by this wrapper: either at the [getById] fault point or right after
 * [clearSharedExpenseFlags] really cleared the flags, whichever happened last.
 */
private class FaultingExpenseDao(
    private val delegate: ExpenseDao,
    private val faultWhenRowIsShared: Boolean = false,
    private val getByIdFault: ExpenseReadFault = ExpenseReadFault.THROW,
    private val throwAfterClearingFlags: Boolean = false,
    private val onFault: suspend (expenseId: Long) -> Unit = {},
    private val onFlagsCleared: suspend (expenseId: Long) -> Unit = {}
) : ExpenseDao by delegate {

    var getByIdCalls: Int = 0
        private set

    /** Number of times the [getById] fault fired (expected to be exactly 1 in fault tests). */
    var getByIdFaultsFired: Int = 0
        private set

    var lastObservedSharedFlag: Boolean? = null
        private set

    private val clearedIds = mutableListOf<Long>()

    /** Expense ids whose shared flags were really cleared through the real DAO. */
    val clearedExpenseIds: List<Long> get() = clearedIds

    override suspend fun getById(id: Long): Expense? {
        getByIdCalls += 1
        val row = delegate.getById(id)
        if (faultWhenRowIsShared && getByIdFaultsFired == 0 && row?.isSharedExpense == true) {
            getByIdFaultsFired += 1
            lastObservedSharedFlag = row.isSharedExpense
            onFault(id)
            when (getByIdFault) {
                ExpenseReadFault.THROW -> throw InjectedStorageFault("synthetic expense read fault")
                ExpenseReadFault.RETURN_NULL -> return null
            }
        }
        return row
    }

    override suspend fun clearSharedExpenseFlags(expenseId: Long) {
        delegate.clearSharedExpenseFlags(expenseId)
        clearedIds += expenseId
        lastObservedSharedFlag = delegate.getById(expenseId)?.isSharedExpense
        onFlagsCleared(expenseId)
        if (throwAfterClearingFlags) {
            throw InjectedStorageFault("synthetic flag clear fault")
        }
    }
}

/**
 * Real-transaction atomicity proofs for [GroupTransactionCoordinator].
 *
 * REAL in every test: in-memory Room [AppDatabase] (Robolectric), all DAOs, the production
 * [TransactionLifecycleCoordinator] (real `ExpenseDao.insertAtomic`, ownership writes and
 * lifecycle events), [RoomDomainTransactionRunner] and [RoomTransactionLifecycleEventWriter]
 * (the BULK_UPDATED event written by `deleteGroupAtomic` is a real `transaction_events` row;
 * the event writer is constructed with the SAME mocked [DatabaseWriteBarrier] as the
 * coordinator under test, so its barrier check is mocked, not real).
 *
 * SUBSTITUTED: fault-injecting wrappers that delegate to the real DAOs
 * ([FaultingGroupMemberDao], [FaultingGroupExpenseDao], [FaultingExpenseDao]) and are handed
 * only to the coordinator under test.
 *
 * MOCKED (peripheral effects only, every one of them):
 * - the clock ([TimeProvider], fixed instant);
 * - the coordinator-under-test's [DatabaseWriteBarrier] (relaxed; the delete-event fault test
 *   makes its `TransactionLifecycleEventWriter.write` check throw, mimicking a restore that
 *   starts mid-transaction);
 * - [TransactionSideEffectPlanner] twice: one for the group coordinator (post-commit
 *   `planBulkUpdated`) and one for the lifecycle coordinator (`planCreated` / `planUpdated`);
 * - ONE shared [PostCommitActionRunner] handed to BOTH the group coordinator and the lifecycle
 *   coordinator, as production injects a single singleton into both (DiagnosticsModule). Its
 *   stub records `database.inTransaction()` on every call so the success-path controls prove
 *   post-commit actions run only after the Room transaction has ended;
 * - [TransactionSideEffectDispatcher], [RecurringLifecycleCoordinator], [CurrencyConverter],
 *   [CurrencySettingsRepository] (home currency EUR), [RestoreMaintenanceMode] (NORMAL);
 * - the lifecycle coordinator's own `SourceLinkWriter` and `DiagnosticEventWriter` (relaxed),
 *   and its `TransactionValidator` (relaxed, `validateCreate` explicitly returns no errors).
 *
 * REAL and not mocked: the lifecycle coordinator's [DatabaseWriteBarrier] (over a mocked
 * NORMAL-mode [RestoreMaintenanceMode]). Only its allow path is exercised: no test makes the
 * real barrier deny. The deny path is only simulated through the mocked barrier shared by the
 * group coordinator and its event writer. Every row
 * assertion is made through the real DAOs afterwards.
 *
 * Fixture seeding that writes DAOs directly (a linked/unlinked system expense, a group expense)
 * is fixture-only and is NOT a legal write path; it only builds preconditions.
 *
 * Mutations killed by the rollback tests below: removing the `database.withTransaction { }`
 * wrapper in the operation under test, namely
 * `createGroupWithMembersAtomic`, `createGroupWithMembers`, `addMemberToGroup`,
 * `addExpenseWithLink`, `createSystemExpenseAndLinkToGroup` and `deleteGroupAtomic`
 * (locate each by name; line numbers drift). Without the transaction, rows written before
 * the fault are auto-committed and survive, so the "unchanged" / "zero rows" assertions fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.OLDEST_SDK])
class GroupTransactionCoordinatorRollbackTest {

    private lateinit var database: AppDatabase
    private lateinit var groupDao: ExpenseGroupDao
    private lateinit var memberDao: GroupMemberDao
    private lateinit var groupExpenseDao: GroupExpenseDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var transactionEventDao: TransactionEventDao
    private lateinit var lifecycleCoordinator: TransactionLifecycleCoordinator

    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val postCommitActionRunner = mockk<PostCommitActionRunner>(relaxed = true)
    private val groupPlanner = mockk<TransactionSideEffectPlanner>(relaxed = true)
    private val lifecyclePlanner = mockk<TransactionSideEffectPlanner>(relaxed = true)

    /** `database.inTransaction()` observed at each shared-runner call, in call order. */
    private val runnerCallInTransaction = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        every { timeProvider.now() } returns ROLLBACK_TEST_DATE
        groupDao = database.expenseGroupDao()
        memberDao = database.groupMemberDao()
        groupExpenseDao = database.groupExpenseDao()
        expenseDao = database.expenseDao()
        transactionEventDao = database.transactionEventDao()

        val restoreMode = mockk<RestoreMaintenanceMode>(relaxed = true)
        every { restoreMode.isWritesAllowed() } returns true
        every { restoreMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        val currencySettings = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencySettings.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettings.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        // Production injects ONE PostCommitActionRunner singleton into both coordinators, so
        // the same mock is handed to both: every runner check below covers lifecycle effects too.
        coEvery { postCommitActionRunner.run(any()) } coAnswers {
            runnerCallInTransaction += database.inTransaction()
            SideEffectBatchResult(
                correlationId = "test",
                completed = 0,
                skipped = 0,
                failedRetryable = 0,
                failedFinal = 0,
                cancelled = 0,
                outcomes = emptyList()
            )
        }
        val transactionValidator = mockk<TransactionValidator>(relaxed = true)
        every { transactionValidator.validateCreate(any()) } returns emptyList()
        lifecycleCoordinator = TransactionLifecycleCoordinator(
            database = database,
            expenseDao = expenseDao,
            transactionEventDao = transactionEventDao,
            timeProvider = timeProvider,
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            sideEffectDispatcher = mockk<TransactionSideEffectDispatcher>(relaxed = true),
            planner = lifecyclePlanner,
            runner = postCommitActionRunner,
            recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
            // REAL write barrier (over a NORMAL-mode restore mock), not a mock.
            writeBarrier = DatabaseWriteBarrier(restoreMode),
            currencySettingsRepository = currencySettings,
            sourceLinkWriter = mockk<SourceLinkWriter>(relaxed = true),
            transactionValidator = transactionValidator,
            diagnosticEventWriter = mockk<DiagnosticEventWriter>(relaxed = true)
        )
    }

    /**
     * Success-path check that every shared-runner call happened after the Room transaction
     * ended. Fails if post-commit actions are moved inside `withTransaction`.
     */
    private fun assertRunnerCalledOnlyAfterCommit(expectedCalls: Int) {
        assertThat(runnerCallInTransaction).hasSize(expectedCalls)
        assertThat(runnerCallInTransaction.all { inTx -> !inTx }).isTrue()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun coordinatorWith(
        memberDaoOverride: GroupMemberDao = memberDao,
        groupExpenseDaoOverride: GroupExpenseDao = groupExpenseDao,
        expenseDaoOverride: ExpenseDao = expenseDao
    ): GroupTransactionCoordinator =
        GroupTransactionCoordinator(
            database = database,
            groupDao = groupDao,
            memberDao = memberDaoOverride,
            groupExpenseDao = groupExpenseDaoOverride,
            expenseDao = expenseDaoOverride,
            transactionLifecycleEventWriter = RoomTransactionLifecycleEventWriter(
                dao = transactionEventDao,
                sanitizer = EventMetadataSanitizer(),
                timeProvider = timeProvider,
                writeBarrier = writeBarrier
            ),
            transactionLifecycleCoordinator = lifecycleCoordinator,
            transactionSideEffectPlanner = groupPlanner,
            postCommitActionRunner = postCommitActionRunner,
            writeBarrier = writeBarrier,
            timeProvider = timeProvider,
            ioDispatcher = Dispatchers.Unconfined,
            transactionRunner = RoomDomainTransactionRunner(database, timeProvider)
        )

    private fun nonEmptyBatch(): PostCommitActionBatch = PostCommitActionBatch(
        "test",
        listOf(
            PostCommitAction(
                pipeline = AppPipeline.TRANSACTION,
                name = "test_action",
                category = SideEffectCategory.BUDGET,
                triggerType = SideEffectTriggerType.EXPENSE_CREATED,
                targetEntityType = "expense",
                targetEntityId = 42L,
                source = "test",
                correlationId = "test",
                causationId = null,
                idempotencyKey = "key-1",
                execute = { SideEffectOutcome.Completed }
            )
        )
    )

    private suspend fun allGroups(): List<ExpenseGroup> = groupDao.getAll()

    private suspend fun allMembers(): List<GroupMember> = memberDao.getAllFlow().first()

    private suspend fun allEvents(): List<TransactionEvent> = transactionEventDao.getRecentEvents(1000)

    private fun fourMembers(): List<GroupMember> = listOf(
        GroupMember(groupId = 0, name = "Alice", isCurrentUser = true),
        GroupMember(groupId = 0, name = "Bob"),
        GroupMember(groupId = 0, name = "Carol"),
        GroupMember(groupId = 0, name = "Dave")
    )

    /** Everything a group-level mutation may touch, read through the real DAOs. */
    private data class WorldState(
        val group: ExpenseGroup?,
        val members: List<GroupMember>,
        val groupExpenses: List<GroupExpense>,
        val systemExpenses: List<Expense>,
        val events: List<TransactionEvent>
    )

    private suspend fun worldState(groupId: Long): WorldState = WorldState(
        group = groupDao.getGroupById(groupId),
        members = memberDao.getAllForGroup(groupId),
        groupExpenses = groupExpenseDao.getExpensesForGroupOnce(groupId),
        systemExpenses = expenseDao.getAllUncapped(),
        events = allEvents()
    )

    private data class LinkedGroupSeed(val groupId: Long, val payerId: Long)

    private suspend fun seedGroup(): LinkedGroupSeed {
        val groupId = coordinatorWith().createGroupWithMembersAtomic(
            ExpenseGroup(name = "Seed Group", defaultCurrency = "EUR"),
            listOf(GroupMember(groupId = 0, name = "Alice", isCurrentUser = true))
        )
        return LinkedGroupSeed(groupId, memberDao.getAllForGroup(groupId).single().id)
    }

    private data class SharedLinkSeed(val groupId: Long, val expenseId: Long, val groupExpenseId: Long)

    /**
     * Fixture-only direct seed, not a legal write path: a group (created through the
     * coordinator) plus a system expense and group-expense link inserted straight through
     * the DAOs so the expense carries observable shared-expense flags. It deliberately skips
     * the lifecycle coordinator, so it has no CREATED event; it is a precondition only.
     */
    private suspend fun seedGroupWithLinkedSharedExpense(): SharedLinkSeed {
        val groupId = coordinatorWith().createGroupWithMembersAtomic(
            ExpenseGroup(name = "Linked Group", defaultCurrency = "EUR"),
            listOf(
                GroupMember(groupId = 0, name = "Alice", isCurrentUser = true),
                GroupMember(groupId = 0, name = "Bob")
            )
        )
        val payerId = memberDao.getAllForGroup(groupId).first { it.name == "Alice" }.id
        // Fixture-only direct seed, not a legal write path (see KDoc above).
        val expenseId = expenseDao.insert(
            Expense(
                amount = 40.0,
                merchant = "Shared Taxi",
                transactionType = TransactionType.PURCHASE,
                date = ROLLBACK_TEST_DATE,
                isSharedExpense = true,
                sharedWithName = "Bob",
                myShareAmount = 20.0
            )
        )
        // Fixture-only direct seed, not a legal write path: the group-expense link row.
        val groupExpenseId = groupExpenseDao.insert(
            GroupExpense(
                groupId = groupId,
                expenseId = expenseId,
                paidById = payerId,
                date = ROLLBACK_TEST_DATE,
                description = "Shared Taxi",
                totalAmount = 40.0,
                splitType = SplitType.EQUAL
            )
        )
        return SharedLinkSeed(groupId, expenseId, groupExpenseId)
    }

    // ==================== createGroupWithMembersAtomic ====================

    @Test
    fun `atomicity control - createGroupWithMembersAtomic without fault persists group and all members`() = runTest {
        val coordinator = coordinatorWith(memberDao)

        val groupId = coordinator.createGroupWithMembersAtomic(
            ExpenseGroup(name = "Control Group"),
            fourMembers()
        )

        assertThat(allGroups().map { it.id }).containsExactly(groupId)
        val members = allMembers()
        assertThat(members.map { it.name }).containsExactly("Alice", "Bob", "Carol", "Dave")
        assertThat(members.map { it.groupId }.distinct()).containsExactly(groupId)
    }

    @Test
    fun `atomicity control - createGroupWithMembersAtomic with a never-firing fault wrapper persists every member`() = runTest {
        // Pass-through control for the fault wrapper itself: a fault number that is never
        // reached must not change behaviour, so the rollback tests below prove the fault,
        // not the wrapper.
        val passThrough = FaultingGroupMemberDao(memberDao, failOnInsertNumber = 99)
        val coordinator = coordinatorWith(passThrough)

        val groupId = coordinator.createGroupWithMembersAtomic(
            ExpenseGroup(name = "Pass-through Group"),
            fourMembers()
        )

        assertThat(passThrough.realInsertsCompleted).isEqualTo(4)
        assertThat(passThrough.insertAttempts).isEqualTo(4)
        assertThat(allGroups().map { it.id }).containsExactly(groupId)
        assertThat(allMembers().map { it.name }).containsExactly("Alice", "Bob", "Carol", "Dave")
    }

    @Test
    fun `atomicity - createGroupWithMembersAtomic rolls back group and earlier members when the third member insert fails`() = runTest {
        // Pre-existing data must survive untouched.
        // Fixture-only direct seed, not a legal write path: group and member rows.
        val seedGroupId = groupDao.insert(ExpenseGroup(name = "Seed Group"))
        memberDao.insert(GroupMember(groupId = seedGroupId, name = "Seed Member"))
        val groupsBefore = allGroups()
        val membersBefore = allMembers()

        val faulting = FaultingGroupMemberDao(memberDao, failOnInsertNumber = 3)
        val coordinator = coordinatorWith(faulting)

        val thrown = assertFailsWith<InjectedStorageFault> {
            coordinator.createGroupWithMembersAtomic(ExpenseGroup(name = "Doomed Group"), fourMembers())
        }

        assertThat(thrown).hasMessageThat().contains("synthetic member insert fault #3")
        // Precondition for a meaningful proof: two member rows really were written
        // inside the transaction before the fault fired.
        assertThat(faulting.realInsertsCompleted).isEqualTo(2)
        assertThat(faulting.insertAttempts).isEqualTo(3)

        // Zero rows for the failed group, zero of its members; seed rows unchanged.
        assertThat(allGroups()).isEqualTo(groupsBefore)
        assertThat(allGroups().none { it.name == "Doomed Group" }).isTrue()
        assertThat(allMembers()).isEqualTo(membersBefore)
        assertThat(allMembers().map { it.name }).containsExactly("Seed Member")
    }

    @Test
    fun `atomicity boundary - createGroupWithMembersAtomic rolls back when the second member insert fails`() = runTest {
        val faulting = FaultingGroupMemberDao(memberDao, failOnInsertNumber = 2)
        val coordinator = coordinatorWith(faulting)

        assertFailsWith<InjectedStorageFault> {
            coordinator.createGroupWithMembersAtomic(ExpenseGroup(name = "Doomed Group"), fourMembers())
        }

        assertThat(faulting.realInsertsCompleted).isEqualTo(1)
        assertThat(allGroups()).isEmpty()
        assertThat(allMembers()).isEmpty()
    }

    // ==================== createGroupWithMembers (documented Error result) ====================

    @Test
    fun `atomicity control - createGroupWithMembers without fault persists group and all members`() = runTest {
        val coordinator = coordinatorWith(memberDao)

        val result = coordinator.createGroupWithMembers(
            name = "Control Group",
            description = null,
            currency = "EUR",
            members = fourMembers()
        )

        assertThat(result).isInstanceOf(GroupCreationResult.Success::class.java)
        val groupId = (result as GroupCreationResult.Success).groupId
        assertThat(allGroups().map { it.id }).containsExactly(groupId)
        assertThat(allMembers().map { it.name }).containsExactly("Alice", "Bob", "Carol", "Dave")
        assertThat(allMembers().map { it.groupId }.distinct()).containsExactly(groupId)
    }

    @Test
    fun `atomicity - createGroupWithMembers returns Error and leaves zero group and member rows when the third member insert fails`() = runTest {
        val faulting = FaultingGroupMemberDao(memberDao, failOnInsertNumber = 3)
        val coordinator = coordinatorWith(faulting)

        val result = coordinator.createGroupWithMembers(
            name = "Doomed Group",
            description = null,
            currency = "EUR",
            members = fourMembers()
        )

        assertThat(result).isInstanceOf(GroupCreationResult.Error::class.java)
        assertThat((result as GroupCreationResult.Error).message).startsWith("Group creation failed")
        assertThat(faulting.realInsertsCompleted).isEqualTo(2)
        assertThat(allGroups()).isEmpty()
        assertThat(allMembers()).isEmpty()
    }

    @Test
    fun `atomicity - createGroupWithMembers rolls back group and all members when onInsideTransaction fails after every insert`() = runTest {
        val coordinator = coordinatorWith(memberDao)
        var callbackGroupId: Long? = null
        var membersVisibleInsideTransaction: Int? = null

        val result = coordinator.createGroupWithMembers(
            name = "Doomed Group",
            description = null,
            currency = "EUR",
            members = fourMembers(),
            onInsideTransaction = { groupId ->
                callbackGroupId = groupId
                // Uncommitted rows are visible to the callback, proving they were really written.
                membersVisibleInsideTransaction = memberDao.getAllForGroup(groupId).size
                throw InjectedStorageFault("synthetic callback fault")
            }
        )

        assertThat(result).isInstanceOf(GroupCreationResult.Error::class.java)
        // The callback ran after the group and all members were written inside the tx.
        assertThat(callbackGroupId).isNotNull()
        assertThat(membersVisibleInsideTransaction).isEqualTo(4)
        assertThat(allGroups()).isEmpty()
        assertThat(allMembers()).isEmpty()
    }

    // ==================== addMemberToGroup (documented Error result) ====================

    @Test
    fun `atomicity control - addMemberToGroup without fault persists the member and passes its id to the callback`() = runTest {
        val coordinator = coordinatorWith(memberDao)
        val groupId = coordinator.createGroupWithMembersAtomic(
            ExpenseGroup(name = "Existing Group"),
            listOf(GroupMember(groupId = 0, name = "Alice"))
        )
        var callbackMemberId: Long? = null

        val result = coordinator.addMemberToGroup(
            groupId = groupId,
            name = "Bob",
            email = null,
            isCurrentUser = false,
            onInsideTransaction = { memberId -> callbackMemberId = memberId }
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val members = allMembers()
        assertThat(members.map { it.name }).containsExactly("Alice", "Bob")
        assertThat(callbackMemberId).isEqualTo(members.first { it.name == "Bob" }.id)
    }

    @Test
    fun `atomicity - addMemberToGroup rolls back the inserted member when onInsideTransaction fails`() = runTest {
        // Mutation: removing database.withTransaction in addMemberToGroup leaves Bob committed.
        val coordinator = coordinatorWith(memberDao)
        val groupId = coordinator.createGroupWithMembersAtomic(
            ExpenseGroup(name = "Existing Group"),
            listOf(GroupMember(groupId = 0, name = "Alice"))
        )
        val membersBefore = allMembers()
        var callbackMemberId: Long? = null
        var memberVisibleInsideTransaction: Boolean? = null

        val result = coordinator.addMemberToGroup(
            groupId = groupId,
            name = "Bob",
            email = null,
            isCurrentUser = false,
            onInsideTransaction = { memberId ->
                callbackMemberId = memberId
                // The member row really exists (uncommitted) when the fault fires.
                memberVisibleInsideTransaction = memberDao.getById(memberId) != null
                throw InjectedStorageFault("synthetic callback fault")
            }
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).error).isInstanceOf(GroupValidationError.Unknown::class.java)
        // The callback received a real, already-inserted member id before failing.
        assertThat(callbackMemberId).isNotNull()
        assertThat(memberVisibleInsideTransaction).isTrue()
        assertThat(allMembers()).isEqualTo(membersBefore)
        assertThat(memberDao.getById(callbackMemberId!!)).isNull()
    }

    // ==================== deleteGroupAtomic / permanentlyDeleteGroup ====================

    @Test
    fun `atomicity control - permanentlyDeleteGroup without fault removes group rows clears linked flags and commits one BULK_UPDATED event`() = runTest {
        val seed = seedGroupWithLinkedSharedExpense()
        val linkedBefore = expenseDao.getById(seed.expenseId)!!
        assertThat(linkedBefore.isSharedExpense).isTrue()
        assertThat(allEvents()).isEmpty()
        every { groupPlanner.planBulkUpdated(any(), any(), any(), any()) } returns nonEmptyBatch()
        val coordinator = coordinatorWith()

        val result = coordinator.permanentlyDeleteGroup(seed.groupId)

        assertThat(result).isTrue()
        assertThat(groupDao.getGroupById(seed.groupId)).isNull()
        assertThat(memberDao.getAllForGroup(seed.groupId)).isEmpty()
        assertThat(groupExpenseDao.getExpensesForGroupOnce(seed.groupId)).isEmpty()
        // The linked system expense survives but loses its shared-expense metadata.
        val linkedAfter = expenseDao.getById(seed.expenseId)!!
        assertThat(linkedAfter.merchant).isEqualTo(linkedBefore.merchant)
        assertThat(linkedAfter.amount).isEqualTo(linkedBefore.amount)
        assertThat(linkedAfter.isSharedExpense).isFalse()
        assertThat(linkedAfter.myShareAmount).isNull()
        assertThat(linkedAfter.sharedWithName).isNull()
        // A real BULK_UPDATED row was committed.
        val bulkEvents = transactionEventDao.getEventsByType("BULK_UPDATED")
        assertThat(bulkEvents).hasSize(1)
        assertThat(bulkEvents.single().source).isEqualTo("GROUP_HARD_DELETE")
        // Post-commit planning ran exactly once and its batch was handed to the runner.
        verify(exactly = 1) {
            groupPlanner.planBulkUpdated(
                source = "GROUP_HARD_DELETE",
                affectedCount = 1,
                correlationId = any(),
                changedFields = any()
            )
        }
        // The lifecycle coordinator is not on the delete path, so the shared runner's one call
        // is the group coordinator's; it must have happened after the transaction ended.
        coVerify(exactly = 1) { postCommitActionRunner.run(any()) }
        assertRunnerCalledOnlyAfterCommit(expectedCalls = 1)
    }

    @Test
    fun `atomicity - deleteGroupAtomic rolls back deletes when clearing shared flags fails after really clearing them`() = runTest {
        // Mutation: removing database.withTransaction in deleteGroupAtomic commits the
        // group-expense, member and group deletes (and the flag clear) despite the fault.
        val seed = seedGroupWithLinkedSharedExpense()
        val before = worldState(seed.groupId)
        assertThat(before.group).isNotNull()
        assertThat(before.members).hasSize(2)
        assertThat(before.groupExpenses.map { it.id }).containsExactly(seed.groupExpenseId)
        assertThat(before.systemExpenses.single().isSharedExpense).isTrue()
        // A non-empty batch makes the post-commit runner reachable if the rollback ever failed,
        // so the exactly=0 runner check below can fail on its own.
        every { groupPlanner.planBulkUpdated(any(), any(), any(), any()) } returns nonEmptyBatch()

        // Observed (not inferred) uncommitted state at the fault point.
        var groupGoneAtFault: Boolean? = null
        var membersAtFault: Int? = null
        var groupExpensesAtFault: Int? = null
        val faulting = FaultingExpenseDao(
            delegate = expenseDao,
            throwAfterClearingFlags = true,
            onFlagsCleared = {
                groupGoneAtFault = groupDao.getGroupById(seed.groupId) == null
                membersAtFault = memberDao.getAllForGroup(seed.groupId).size
                groupExpensesAtFault = groupExpenseDao.getExpensesForGroupOnce(seed.groupId).size
            }
        )
        val coordinator = coordinatorWith(expenseDaoOverride = faulting)

        assertFailsWith<InjectedStorageFault> { coordinator.deleteGroupAtomic(seed.groupId) }

        // Preconditions for a meaningful proof, observed at the fault point: the flag clear
        // and all three deletes really executed inside the transaction before the fault fired.
        assertThat(faulting.clearedExpenseIds).containsExactly(seed.expenseId)
        assertThat(faulting.lastObservedSharedFlag).isEqualTo(false)
        assertThat(groupGoneAtFault).isTrue()
        assertThat(membersAtFault).isEqualTo(0)
        assertThat(groupExpensesAtFault).isEqualTo(0)

        // Everything is exactly as before: group, members, group expenses, linked flags, events.
        val after = worldState(seed.groupId)
        assertThat(after).isEqualTo(before)
        assertThat(expenseDao.getById(seed.expenseId)!!.isSharedExpense).isTrue()
        assertThat(expenseDao.getById(seed.expenseId)!!.myShareAmount).isEqualTo(20.0)
        assertThat(expenseDao.getById(seed.expenseId)!!.sharedWithName).isEqualTo("Bob")
        // No post-commit work after a rolled-back transaction.
        verify(exactly = 0) { groupPlanner.planBulkUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    /**
     * Mimics a restore starting mid-transaction: the entry guards still pass, but the
     * lifecycle event writer's own barrier check (the one inside the BULK_UPDATED write) throws.
     */
    private fun stubRestoreStartingBeforeBulkEventWrite() {
        every { writeBarrier.checkWritesAllowed("TransactionLifecycleEventWriter.write") } throws
            DatabaseAccessBlockedException(
                accessType = DatabaseAccessType.WRITE,
                operation = DatabaseAccessOperation("TransactionLifecycleEventWriter.write"),
                mode = RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
            )
    }

    @Test
    fun `atomicity - deleteGroupAtomic propagates the failure and rolls back everything when the BULK_UPDATED event write is blocked after the deletes and flag clearing`() = runTest {
        // Mutations killed: (a) wrapping the BULK_UPDATED runInTransaction write in a
        // best-effort try/catch would let the deletes commit and deleteGroupAtomic return
        // normally; (b) moving the event write outside database.withTransaction would commit
        // the deletes before the failing write. Both make the assertions below fail.
        val seed = seedGroupWithLinkedSharedExpense()
        val before = worldState(seed.groupId)
        assertThat(before.group).isNotNull()
        assertThat(before.members).hasSize(2)
        assertThat(before.groupExpenses.map { it.id }).containsExactly(seed.groupExpenseId)
        assertThat(before.systemExpenses.single().isSharedExpense).isTrue()
        assertThat(before.events).isEmpty()
        stubRestoreStartingBeforeBulkEventWrite()
        // Non-empty batch: the exactly=0 runner check can fail on its own if the planner were reached.
        every { groupPlanner.planBulkUpdated(any(), any(), any(), any()) } returns nonEmptyBatch()
        var groupGoneAtFlagClear: Boolean? = null
        var membersAtFlagClear: Int? = null
        var groupExpensesAtFlagClear: Int? = null
        val faulting = FaultingExpenseDao(
            delegate = expenseDao,
            onFlagsCleared = {
                groupGoneAtFlagClear = groupDao.getGroupById(seed.groupId) == null
                membersAtFlagClear = memberDao.getAllForGroup(seed.groupId).size
                groupExpensesAtFlagClear = groupExpenseDao.getExpensesForGroupOnce(seed.groupId).size
            }
        )
        val coordinator = coordinatorWith(expenseDaoOverride = faulting)

        val thrown = assertFailsWith<DatabaseAccessBlockedException> {
            coordinator.deleteGroupAtomic(seed.groupId)
        }

        assertThat(thrown.accessType).isEqualTo(DatabaseAccessType.WRITE)
        assertThat(thrown.operation.name).isEqualTo("TransactionLifecycleEventWriter.write")
        // Preconditions, observed: the three deletes and the flag clear really ran inside the
        // transaction BEFORE the event write was blocked, and the event write was attempted.
        assertThat(faulting.clearedExpenseIds).containsExactly(seed.expenseId)
        assertThat(faulting.lastObservedSharedFlag).isEqualTo(false)
        assertThat(groupGoneAtFlagClear).isTrue()
        assertThat(membersAtFlagClear).isEqualTo(0)
        assertThat(groupExpensesAtFlagClear).isEqualTo(0)
        verify(exactly = 1) { writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.deleteGroupAtomic") }
        verify(exactly = 1) { writeBarrier.checkWritesAllowed("TransactionLifecycleEventWriter.write") }

        // Rows, linked-expense flags and events are exactly as before.
        assertThat(worldState(seed.groupId)).isEqualTo(before)
        assertThat(transactionEventDao.countByType("BULK_UPDATED")).isEqualTo(0)
        val linkedAfter = expenseDao.getById(seed.expenseId)!!
        assertThat(linkedAfter.isSharedExpense).isTrue()
        assertThat(linkedAfter.myShareAmount).isEqualTo(20.0)
        assertThat(linkedAfter.sharedWithName).isEqualTo("Bob")
        verify(exactly = 0) { groupPlanner.planBulkUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    @Test
    fun `atomicity - permanentlyDeleteGroup returns false and keeps every row when the BULK_UPDATED event write is blocked mid-transaction`() = runTest {
        val seed = seedGroupWithLinkedSharedExpense()
        val before = worldState(seed.groupId)
        stubRestoreStartingBeforeBulkEventWrite()
        // Non-empty batch: the exactly=0 runner check can fail on its own if the planner were reached.
        every { groupPlanner.planBulkUpdated(any(), any(), any(), any()) } returns nonEmptyBatch()
        val coordinator = coordinatorWith()

        val result = coordinator.permanentlyDeleteGroup(seed.groupId)

        assertThat(result).isFalse()
        // The blocked event write was really reached (verified once below), not skipped. That the
        // deletes and flag clearing already ran inside the transaction before it is observed in
        // the deleteGroupAtomic BULK_UPDATED-blocked test above, not in this one.
        verify(exactly = 1) { writeBarrier.checkWritesAllowed("TransactionLifecycleEventWriter.write") }
        assertThat(worldState(seed.groupId)).isEqualTo(before)
        assertThat(transactionEventDao.countByType("BULK_UPDATED")).isEqualTo(0)
        assertThat(expenseDao.getById(seed.expenseId)!!.isSharedExpense).isTrue()
        verify(exactly = 0) { groupPlanner.planBulkUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    @Test
    fun `atomicity - permanentlyDeleteGroup returns false and rolls back deletes flag clearing and the BULK_UPDATED event when onInsideTransaction fails`() = runTest {
        // The callback runs AFTER the real BULK_UPDATED event write, so this also proves the
        // lifecycle event is part of the same transaction as the deletes.
        val seed = seedGroupWithLinkedSharedExpense()
        val before = worldState(seed.groupId)
        assertThat(transactionEventDao.countByType("BULK_UPDATED")).isEqualTo(0)
        // Non-empty batch: the exactly=0 runner check can fail on its own if the planner were reached.
        every { groupPlanner.planBulkUpdated(any(), any(), any(), any()) } returns nonEmptyBatch()
        val coordinator = coordinatorWith()
        var groupGoneInside: Boolean? = null
        var membersInside: Int? = null
        var groupExpensesInside: Int? = null
        var bulkEventsInside: Int? = null
        var sharedFlagInside: Boolean? = null

        val result = coordinator.permanentlyDeleteGroup(seed.groupId) {
            groupGoneInside = groupDao.getGroupById(seed.groupId) == null
            membersInside = memberDao.getAllForGroup(seed.groupId).size
            groupExpensesInside = groupExpenseDao.getExpensesForGroupOnce(seed.groupId).size
            bulkEventsInside = transactionEventDao.countByType("BULK_UPDATED")
            sharedFlagInside = expenseDao.getById(seed.expenseId)!!.isSharedExpense
            throw InjectedStorageFault("synthetic callback fault")
        }

        assertThat(result).isFalse()
        // Inside the transaction every write was really visible ...
        assertThat(groupGoneInside).isTrue()
        assertThat(membersInside).isEqualTo(0)
        assertThat(groupExpensesInside).isEqualTo(0)
        assertThat(bulkEventsInside).isEqualTo(1)
        assertThat(sharedFlagInside).isFalse()
        // ... and afterwards none of it survived.
        assertThat(worldState(seed.groupId)).isEqualTo(before)
        assertThat(transactionEventDao.countByType("BULK_UPDATED")).isEqualTo(0)
        assertThat(expenseDao.getById(seed.expenseId)!!.isSharedExpense).isTrue()
        verify(exactly = 0) { groupPlanner.planBulkUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    // ==================== createSystemExpenseAndLinkToGroup ====================

    @Test
    fun `atomicity control - createSystemExpenseAndLinkToGroup without fault commits the system expense its event and the group link`() = runTest {
        val seed = seedGroup()
        coEvery { lifecyclePlanner.planCreated(any(), any(), any()) } returns nonEmptyBatch()
        val coordinator = coordinatorWith()

        val result = coordinator.createSystemExpenseAndLinkToGroup(
            groupId = seed.groupId,
            description = "Atomic Dinner",
            amount = 50.0,
            paidById = seed.payerId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            date = ROLLBACK_TEST_DATE,
            transactionType = TransactionType.PURCHASE,
            notes = null
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        val success = result as GroupExpenseCreationResult.Success
        assertThat(expenseDao.getAllUncapped().map { it.id }).containsExactly(success.expenseId)
        assertThat(groupExpenseDao.getExpensesForGroupOnce(seed.groupId).single().expenseId)
            .isEqualTo(success.expenseId)
        assertThat(transactionEventDao.countByType("CREATED")).isEqualTo(1)
        // createExpenseDbOnlyV2 / DB-only lifecycle APIs hand their batch back instead of
        // running it, so the shared runner is invoked exactly once, by the group coordinator.
        coVerify(exactly = 1) { postCommitActionRunner.run(any()) }
        assertRunnerCalledOnlyAfterCommit(expectedCalls = 1)
    }

    @Test
    fun `atomicity - createSystemExpenseAndLinkToGroup rolls back the system expense and its event when the group expense insert throws after really inserting`() = runTest {
        // Mutation: removing database.withTransaction in createSystemExpenseAndLinkToGroup
        // leaves the system expense, its CREATED event and the group expense committed.
        val seed = seedGroup()
        val before = worldState(seed.groupId)
        coEvery { lifecyclePlanner.planCreated(any(), any(), any()) } returns nonEmptyBatch()
        var systemExpensesInside: Int? = null
        var createdEventsInside: Int? = null
        val faulting = FaultingGroupExpenseDao(
            delegate = groupExpenseDao,
            fault = GroupExpenseInsertFault.INSERT_THEN_THROW,
            beforeFault = {
                systemExpensesInside = expenseDao.getAllUncapped().size
                createdEventsInside = transactionEventDao.countByType("CREATED")
            }
        )
        val coordinator = coordinatorWith(groupExpenseDaoOverride = faulting)

        val result = coordinator.createSystemExpenseAndLinkToGroup(
            groupId = seed.groupId,
            description = "Doomed Dinner",
            amount = 50.0,
            paidById = seed.payerId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            date = ROLLBACK_TEST_DATE,
            transactionType = TransactionType.PURCHASE,
            notes = null
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        assertThat((result as GroupExpenseCreationResult.Error).message)
            .startsWith("Failed to create group expense atomically")
        // Preconditions: the system expense (real insertAtomic + CREATED event) and the group
        // expense row were really written inside the transaction before the fault fired.
        assertThat(systemExpensesInside).isEqualTo(1)
        assertThat(createdEventsInside).isEqualTo(1)
        assertThat(faulting.realInsertsCompleted).isEqualTo(1)

        assertThat(expenseDao.getAllUncapped()).isEmpty()
        assertThat(groupExpenseDao.getExpensesForGroupOnce(seed.groupId)).isEmpty()
        assertThat(transactionEventDao.countByType("CREATED")).isEqualTo(0)
        assertThat(worldState(seed.groupId)).isEqualTo(before)
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    @Test
    fun `atomicity - createSystemExpenseAndLinkToGroup throws GroupExpenseAtomicRollback and rolls back the system expense when the group expense insert returns no row id`() = runTest {
        // Drives the GroupExpenseAtomicRollback branch (groupExpenseId <= 0). If the branch
        // returned a normal Error instead of throwing, Room would commit the orphan system
        // expense and the assertions below would fail.
        val seed = seedGroup()
        val before = worldState(seed.groupId)
        coEvery { lifecyclePlanner.planCreated(any(), any(), any()) } returns nonEmptyBatch()
        var systemExpensesInside: Int? = null
        val faulting = FaultingGroupExpenseDao(
            delegate = groupExpenseDao,
            fault = GroupExpenseInsertFault.RETURN_ZERO_WITHOUT_INSERT,
            beforeFault = { systemExpensesInside = expenseDao.getAllUncapped().size }
        )
        val coordinator = coordinatorWith(groupExpenseDaoOverride = faulting)

        val result = coordinator.createSystemExpenseAndLinkToGroup(
            groupId = seed.groupId,
            description = "Doomed Dinner",
            amount = 50.0,
            paidById = seed.payerId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            date = ROLLBACK_TEST_DATE,
            transactionType = TransactionType.PURCHASE,
            notes = null
        )

        // The rollback signal is unwrapped to its documented public result.
        assertThat(result).isEqualTo(
            GroupExpenseCreationResult.Error("Failed to create group expense link")
        )
        assertThat(faulting.insertAttempts).isEqualTo(1)
        assertThat(faulting.realInsertsCompleted).isEqualTo(0)
        assertThat(systemExpensesInside).isEqualTo(1)

        assertThat(expenseDao.getAllUncapped()).isEmpty()
        assertThat(groupExpenseDao.getExpensesForGroupOnce(seed.groupId)).isEmpty()
        assertThat(worldState(seed.groupId)).isEqualTo(before)
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    @Test
    fun `atomicity - createSystemExpenseAndLinkToGroup rolls back the system expense and its event when the fresh expense is reported as already attached`() = runTest {
        // Drives the "already attached" GroupExpenseAtomicRollback branch. If it returned a
        // normal Error instead of throwing, Room would commit the orphan system expense.
        val seed = seedGroup()
        val before = worldState(seed.groupId)
        coEvery { lifecyclePlanner.planCreated(any(), any(), any()) } returns nonEmptyBatch()
        var systemExpensesInside: Int? = null
        var createdEventsInside: Int? = null
        val faulting = FaultingGroupExpenseDao(
            delegate = groupExpenseDao,
            fault = GroupExpenseInsertFault.REPORT_ALREADY_ATTACHED,
            beforeFault = {
                systemExpensesInside = expenseDao.getAllUncapped().size
                createdEventsInside = transactionEventDao.countByType("CREATED")
            }
        )
        val coordinator = coordinatorWith(groupExpenseDaoOverride = faulting)

        val result = coordinator.createSystemExpenseAndLinkToGroup(
            groupId = seed.groupId,
            description = "Doomed Dinner",
            amount = 50.0,
            paidById = seed.payerId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            date = ROLLBACK_TEST_DATE,
            transactionType = TransactionType.PURCHASE,
            notes = null
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        assertThat((result as GroupExpenseCreationResult.Error).message)
            .contains("already attached to a group expense")
        // Preconditions, observed: the lookup was reached after the system expense and its
        // CREATED event were really written, and the group-expense insert was never attempted.
        assertThat(faulting.alreadyAttachedAnswers).isEqualTo(1)
        assertThat(faulting.insertAttempts).isEqualTo(0)
        assertThat(systemExpensesInside).isEqualTo(1)
        assertThat(createdEventsInside).isEqualTo(1)

        assertThat(expenseDao.getAllUncapped()).isEmpty()
        assertThat(groupExpenseDao.getExpensesForGroupOnce(seed.groupId)).isEmpty()
        assertThat(transactionEventDao.countByType("CREATED")).isEqualTo(0)
        assertThat(worldState(seed.groupId)).isEqualTo(before)
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    // ==================== addExpenseWithLink ====================

    /** Fixture-only direct seed, not a legal write path: an unlinked, non-shared system expense. */
    private suspend fun seedUnlinkedSystemExpense(): Long = expenseDao.insert(
        Expense(
            amount = 75.0,
            merchant = "Link Test",
            transactionType = TransactionType.PURCHASE,
            date = ROLLBACK_TEST_DATE
        )
    )

    @Test
    fun `atomicity control - addExpenseWithLink without fault commits the group link and the ownership update`() = runTest {
        val seed = seedGroup()
        val systemExpenseId = seedUnlinkedSystemExpense()
        coEvery { lifecyclePlanner.planUpdated(any(), any(), any(), any()) } returns nonEmptyBatch()
        val coordinator = coordinatorWith()

        val result = coordinator.addExpenseWithLink(
            groupId = seed.groupId,
            systemExpenseId = systemExpenseId,
            description = "Link Test",
            amount = 75.0,
            paidById = seed.payerId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            customSplitsJson = null,
            date = ROLLBACK_TEST_DATE,
            idempotencyKey = null
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        assertThat(groupExpenseDao.getExpensesForGroupOnce(seed.groupId).single().expenseId)
            .isEqualTo(systemExpenseId)
        val linked = expenseDao.getById(systemExpenseId)!!
        assertThat(linked.isSharedExpense).isTrue()
        assertThat(linked.myShareAmount).isEqualTo(75.0)
        assertThat(transactionEventDao.getEventsForExpense(systemExpenseId)).hasSize(1)
        // updateOwnershipDbOnlyV2 returns its batch instead of running it: one shared-runner
        // call in total, made by the group coordinator after commit.
        coVerify(exactly = 1) { postCommitActionRunner.run(any()) }
        assertRunnerCalledOnlyAfterCommit(expectedCalls = 1)
    }

    @Test
    fun `atomicity - addExpenseWithLink rolls back the group link and the ownership update when the post-update reload fails`() = runTest {
        // Mutation: removing database.withTransaction in addExpenseWithLink commits the group
        // expense row and the real ownership update (isSharedExpense = true) despite the fault.
        val seed = seedGroup()
        val systemExpenseId = seedUnlinkedSystemExpense()
        val before = worldState(seed.groupId)
        val expenseBefore = expenseDao.getById(systemExpenseId)!!
        assertThat(expenseBefore.isSharedExpense).isFalse()
        coEvery { lifecyclePlanner.planUpdated(any(), any(), any(), any()) } returns nonEmptyBatch()
        // The fault fires on the first coordinator read that returns an already-shared row,
        // i.e. the reload after the ownership update (the earlier lookup sees the unlinked
        // row), so it fires after BOTH the group link and the ownership update. No call-count
        // coupling. Event/link counts are recorded at the fault point.
        var eventsAtFault: Int? = null
        var groupExpensesAtFault: Int? = null
        val faulting = FaultingExpenseDao(
            delegate = expenseDao,
            faultWhenRowIsShared = true,
            getByIdFault = ExpenseReadFault.THROW,
            onFault = { expenseId ->
                eventsAtFault = transactionEventDao.getEventsForExpense(expenseId).size
                groupExpensesAtFault = groupExpenseDao.getExpensesForGroupOnce(seed.groupId).size
            }
        )
        val coordinator = coordinatorWith(expenseDaoOverride = faulting)

        val result = coordinator.addExpenseWithLink(
            groupId = seed.groupId,
            systemExpenseId = systemExpenseId,
            description = "Link Test",
            amount = 75.0,
            paidById = seed.payerId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            customSplitsJson = null,
            date = ROLLBACK_TEST_DATE,
            idempotencyKey = null
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        assertThat((result as GroupExpenseCreationResult.Error).message).startsWith("Failed to add expense")
        // Preconditions, observed at fault time: the fault fired exactly once, the ownership
        // update was really applied (uncommitted) with its UPDATED event, and the group
        // expense row was really inserted.
        assertThat(faulting.getByIdFaultsFired).isEqualTo(1)
        assertThat(faulting.lastObservedSharedFlag).isEqualTo(true)
        assertThat(eventsAtFault).isEqualTo(1)
        assertThat(groupExpensesAtFault).isEqualTo(1)

        assertThat(groupExpenseDao.getExpensesForGroupOnce(seed.groupId)).isEmpty()
        assertThat(expenseDao.getById(systemExpenseId)).isEqualTo(expenseBefore)
        assertThat(transactionEventDao.getEventsForExpense(systemExpenseId)).isEmpty()
        assertThat(worldState(seed.groupId)).isEqualTo(before)
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    @Test
    fun `atomicity - addExpenseWithLink rolls back when the reload after the ownership update finds no expense (GroupExpenseAtomicRollback branch)`() = runTest {
        // Drives the "Expense missing after ownership update" GroupExpenseAtomicRollback branch.
        // A normal Error return there would commit the group link and the ownership update.
        val seed = seedGroup()
        val systemExpenseId = seedUnlinkedSystemExpense()
        val before = worldState(seed.groupId)
        val expenseBefore = expenseDao.getById(systemExpenseId)!!
        coEvery { lifecyclePlanner.planUpdated(any(), any(), any(), any()) } returns nonEmptyBatch()
        var eventsAtFault: Int? = null
        var groupExpensesAtFault: Int? = null
        val faulting = FaultingExpenseDao(
            delegate = expenseDao,
            faultWhenRowIsShared = true,
            getByIdFault = ExpenseReadFault.RETURN_NULL,
            onFault = { expenseId ->
                eventsAtFault = transactionEventDao.getEventsForExpense(expenseId).size
                groupExpensesAtFault = groupExpenseDao.getExpensesForGroupOnce(seed.groupId).size
            }
        )
        val coordinator = coordinatorWith(expenseDaoOverride = faulting)

        val result = coordinator.addExpenseWithLink(
            groupId = seed.groupId,
            systemExpenseId = systemExpenseId,
            description = "Link Test",
            amount = 75.0,
            paidById = seed.payerId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            customSplitsJson = null,
            date = ROLLBACK_TEST_DATE,
            idempotencyKey = null
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        assertThat(faulting.getByIdFaultsFired).isEqualTo(1)
        assertThat(faulting.lastObservedSharedFlag).isEqualTo(true)
        assertThat(eventsAtFault).isEqualTo(1)
        assertThat(groupExpensesAtFault).isEqualTo(1)
        assertThat(groupExpenseDao.getExpensesForGroupOnce(seed.groupId)).isEmpty()
        assertThat(expenseDao.getById(systemExpenseId)).isEqualTo(expenseBefore)
        assertThat(worldState(seed.groupId)).isEqualTo(before)
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }

    @Test
    fun `atomicity - addExpenseWithLink rolls back the group expense row when the group expense insert throws after really inserting`() = runTest {
        // Fault immediately after the first write (the group expense insert).
        val seed = seedGroup()
        val systemExpenseId = seedUnlinkedSystemExpense()
        val before = worldState(seed.groupId)
        val expenseBefore = expenseDao.getById(systemExpenseId)!!
        coEvery { lifecyclePlanner.planUpdated(any(), any(), any(), any()) } returns nonEmptyBatch()
        var groupExpensesInside: Int? = null
        val faulting = FaultingGroupExpenseDao(
            delegate = groupExpenseDao,
            fault = GroupExpenseInsertFault.INSERT_THEN_THROW,
            beforeFault = {
                groupExpensesInside = groupExpenseDao.getExpensesForGroupOnce(seed.groupId).size
            }
        )
        val coordinator = coordinatorWith(groupExpenseDaoOverride = faulting)

        val result = coordinator.addExpenseWithLink(
            groupId = seed.groupId,
            systemExpenseId = systemExpenseId,
            description = "Link Test",
            amount = 75.0,
            paidById = seed.payerId,
            currency = "EUR",
            splitType = SplitType.EQUAL,
            customSplitsJson = null,
            date = ROLLBACK_TEST_DATE,
            idempotencyKey = null
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        assertThat((result as GroupExpenseCreationResult.Error).message).startsWith("Failed to add expense")
        assertThat(faulting.realInsertsCompleted).isEqualTo(1)
        assertThat(groupExpensesInside).isEqualTo(1)

        assertThat(groupExpenseDao.getExpensesForGroupOnce(seed.groupId)).isEmpty()
        assertThat(expenseDao.getById(systemExpenseId)).isEqualTo(expenseBefore)
        assertThat(worldState(seed.groupId)).isEqualTo(before)
        coVerify(exactly = 0) { postCommitActionRunner.run(any()) }
    }
}
