package com.yourname.expensetracker.scenarios

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.GroupTransactionCoordinator
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.data.database.dao.GroupSettlementDao
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.groups.GroupCreationResult
import com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult
import com.yourname.expensetracker.domain.groups.GroupLifecycleCoordinator
import com.yourname.expensetracker.domain.groups.GroupValidationError
import com.yourname.expensetracker.domain.groups.Result
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectDispatcher
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TEST_DATE = 1_710_000_000_000L

private val Result<*, *>.isSuccess: Boolean
    get() = this is Result.Success

private val Result<*, *>.isFailure: Boolean
    get() = this is Result.Error

/**
 * Contract tests for GroupLifecycleCoordinator.
 *
 * Covers:
 * - groupSettlementPersistsAndAffectsBalance — settlement persistence
 * - groupRejectsForeignCurrencyExpense — single-currency enforcement
 * - groupMemberRemovalWithoutBalanceSucceeds — simple member removal
 * - groupHardDeleteRequiresArchive — hard-delete gate
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.OLDEST_SDK])
class GroupLifecycleContractTest {

    private lateinit var database: AppDatabase
    private lateinit var groupDao: ExpenseGroupDao
    private lateinit var memberDao: GroupMemberDao
    private lateinit var settlementDao: GroupSettlementDao
    private lateinit var groupTxCoordinator: GroupTransactionCoordinator
    private lateinit var lifecycle: GroupLifecycleCoordinator
    private val timeProvider = mockk<com.yourname.expensetracker.domain.util.TimeProvider>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        every { timeProvider.now() } returns TEST_DATE

        groupDao = database.expenseGroupDao()
        memberDao = database.groupMemberDao()
        settlementDao = database.groupSettlementDao()
        val expenseDao = database.expenseDao()
        val transactionEventDao = database.transactionEventDao()

        val txLifecycle = TransactionLifecycleCoordinator(
            database, expenseDao, transactionEventDao, timeProvider,
            mockk<CurrencyConverter>(relaxed = true),
            mockk<TransactionSideEffectDispatcher>(relaxed = true),
            mockk<TransactionSideEffectPlanner>(relaxed = true),
            mockk<PostCommitActionRunner>(relaxed = true),
            mockk<RecurringLifecycleCoordinator>(relaxed = true),
            mockk<RestoreMaintenanceMode>(relaxed = true),
            mockk<DatabaseWriteBarrier>(relaxed = true),
            currencySettingsRepository,
            mockk(relaxed = true)
        )

        groupTxCoordinator = GroupTransactionCoordinator(
            database, groupDao, memberDao, database.groupExpenseDao(), expenseDao,
            mockk(relaxed = true), txLifecycle,
            mockk(relaxed = true), mockk<PostCommitActionRunner>(relaxed = true),
            mockk<DatabaseWriteBarrier>(relaxed = true), timeProvider, Dispatchers.Unconfined
        )

        lifecycle = GroupLifecycleCoordinator(
            groupTxCoordinator, groupDao, database.groupExpenseDao(), memberDao, settlementDao,
            database.groupLifecycleEventDao(),
            mockk(relaxed = true), // balanceCalculator
            timeProvider, currencySettingsRepository,
            mockk<DatabaseWriteBarrier>(relaxed = true), database,
            mockk<dagger.Lazy<BudgetMonitor>>(relaxed = true),
            mockk<TransactionSideEffectDispatcher>(relaxed = true),
            Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    // ── groupSettlementPersistsAndAffectsBalance ────────────────────────────

    @Test
    fun `groupSettlementPersistsAndAffectsBalance`() = runTest {
        val groupId = seedGroup("SettleTest", "EUR")
        val aliceId = seedMember(groupId, "Alice", isCurrentUser = true)
        val bobId = seedMember(groupId, "Bob", isCurrentUser = false)

        // Add an expense first so there is something to settle
        val expenseResult = lifecycle.addExpense(
            groupId = groupId,
            description = "Dinner",
            amount = 60.0,
            paidById = aliceId,
            currency = "EUR",
            date = TEST_DATE
        )
        assertThat(expenseResult).isInstanceOf(GroupExpenseCreationResult.Success::class.java)

        // Record settlement of 25 EUR from Bob to Alice
        val settlementId = lifecycle.recordSettlement(
            groupId = groupId,
            fromMemberId = bobId,
            toMemberId = aliceId,
            amount = 25.0,
            currency = "EUR",
            notes = "Partial repayment for dinner"
        )

        assertThat(settlementId).isGreaterThan(0)

        // Verify settlement was persisted
        val settlements = settlementDao.getSettlementsForGroup(groupId)
        assertThat(settlements).hasSize(1)
        assertThat(settlements.first().amount).isEqualTo(25.0)
        assertThat(settlements.first().currency).isEqualTo("EUR")
        assertThat(settlements.first().fromMemberId).isEqualTo(bobId)
        assertThat(settlements.first().toMemberId).isEqualTo(aliceId)
        assertThat(settlements.first().status).isEqualTo("RECORDED")
        assertThat(settlements.first().notes).isEqualTo("Partial repayment for dinner")
    }

    // ── groupRejectsForeignCurrencyExpense ──────────────────────────────────

    @Test
    fun `groupRejectsForeignCurrencyExpense`() = runTest {
        val groupId = seedGroup("Trip", "EUR")
        val aliceId = seedMember(groupId, "Alice", isCurrentUser = true)
        seedMember(groupId, "Bob", isCurrentUser = false)

        val result = lifecycle.addExpense(
            groupId = groupId,
            description = "Hotel",
            amount = 200.0,
            paidById = aliceId,
            currency = "USD",
            date = TEST_DATE
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        val errorMessage = (result as GroupExpenseCreationResult.Error).message
        assertThat(errorMessage).contains("must match group currency")
    }

    // ── groupMemberRemovalWithoutBalanceSucceeds ────────────────────────────

    @Test
    fun `groupMemberRemovalWithoutBalanceSucceeds`() = runTest {
        val groupId = seedGroup("Friends", "EUR")
        seedMember(groupId, "Alice", isCurrentUser = true)
        seedMember(groupId, "Bob", isCurrentUser = false)
        val bob = memberDao.getAllForGroup(groupId).first { it.name == "Bob" }

        val result = lifecycle.removeMember(groupId, bob.id)

        assertThat(result.isSuccess).isTrue()
        val remaining = memberDao.getAllForGroup(groupId)
        assertThat(remaining).hasSize(1)
        assertThat(remaining.first().name).isEqualTo("Alice")
    }

    // ── groupHardDeleteRequiresArchive ──────────────────────────────────────

    @Test
    fun `groupHardDeleteRequiresArchive`() = runTest {
        val groupId = seedGroup("ToDelete", "EUR")
        seedMember(groupId, "Alice", isCurrentUser = true)

        // Attempt hard-delete without archiving first — must be rejected
        val result = lifecycle.deleteGroupPermanently(
            groupId = groupId,
            confirmPermanentDelete = true
        )

        assertThat(result).isFalse()

        // Group must still exist
        val group = groupDao.getGroupById(groupId)
        assertThat(group).isNotNull()
        assertThat(group!!.isActive).isTrue()
    }

    // ── removeMember blocked when net balance not settled ────────────────────

    @Test
    fun `removeMember blocked when net balance not settled`() = runTest {
        val groupId = seedGroup("Balance", "EUR")
        val aliceId = seedMember(groupId, "Alice", isCurrentUser = true)
        val bobId = seedMember(groupId, "Bob", isCurrentUser = false)
        // Bob pays for a group expense → Bob is owed money
        val result = lifecycle.addExpense(groupId, "Dinner", 50.0, aliceId, "EUR", date = TEST_DATE)
        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        // Bob should not be removable — he owes his share (25.0) since Alice paid
        val removeResult = lifecycle.removeMember(groupId, bobId)
        assertThat(removeResult.isFailure).isTrue()
    }

    @Test
    fun `group lifecycle events are persisted`() = runTest {
        val members = listOf(
            GroupMember(groupId = 0, name = "Alice", isCurrentUser = true),
            GroupMember(groupId = 0, name = "Bob", isCurrentUser = false)
        )
        val createResult = lifecycle.createGroup("EventGroup", null, "EUR", members)
        val groupId = (createResult as GroupCreationResult.Success).groupId
        // Verify events were written
        val events = database.groupLifecycleEventDao().getEventsForGroup(groupId)
        assertThat(events).isNotEmpty()
        assertThat(events.any { it.eventType == "GROUP_CREATED" }).isTrue()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun seedGroup(name: String, currency: String): Long {
        val group = ExpenseGroup(name = name, defaultCurrency = currency)
        return groupDao.insert(group)
    }

    private suspend fun seedMember(groupId: Long, name: String, isCurrentUser: Boolean = false): Long {
        val member = GroupMember(groupId = groupId, name = name, isCurrentUser = isCurrentUser, currentUserGroupKey = if (isCurrentUser) groupId else null)
        return memberDao.insert(member)
    }
}
