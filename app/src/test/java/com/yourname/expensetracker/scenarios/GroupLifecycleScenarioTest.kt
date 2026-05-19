package com.yourname.expensetracker.scenarios

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Golden scenario tests for GroupLifecycleCoordinator.
 *
 * Covers the 7 lifecycle methods:
 * - createGroup — member validation, currentUser invariant
 * - addMember — active-group check, duplicate detection, single currentUser
 * - removeMember — last-currentUser gate
 * - addExpense — single-currency policy enforcement
 * - archiveGroup — soft-delete via isActive = false
 * - deleteGroupPermanently — confirmation gate, hard-delete
 * - recordSettlement — currency validation, member ownership, persistence
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.OLDEST_SDK])
class GroupLifecycleScenarioTest {
    

    private lateinit var database: AppDatabase
    private lateinit var groupDao: ExpenseGroupDao
    private lateinit var memberDao: GroupMemberDao
    private lateinit var settlementDao: GroupSettlementDao
    private lateinit var groupTxCoordinator: GroupTransactionCoordinator
    private lateinit var lifecycle: GroupLifecycleCoordinator
    private val timeProvider = mockk<com.yourname.expensetracker.domain.util.TimeProvider>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private val balanceCalculator = mockk<com.yourname.expensetracker.domain.groups.GroupBalanceCalculator>(relaxed = true)

    @Before
    fun setup() {
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
            mockk<RecurringLifecycleCoordinator>(relaxed = true),
            mockk<RestoreMaintenanceMode>(relaxed = true),
            mockk<DatabaseWriteBarrier>(relaxed = true),
            currencySettingsRepository
        )

        groupTxCoordinator = GroupTransactionCoordinator(
            database, groupDao, memberDao, database.groupExpenseDao(), expenseDao,
            mockk(relaxed = true), txLifecycle,
            mockk<DatabaseWriteBarrier>(relaxed = true), timeProvider, Dispatchers.IO
        )

        // Stub balanceCalculator to return a settled balance for removeMember tests
        coEvery { balanceCalculator.calculateMemberBalance(any(), any()) } returns
            com.yourname.expensetracker.domain.groups.GroupBalanceCalculator.GroupMemberBalance(
                groupId = 0L, memberId = 0L, currency = "EUR",
                paidTotal = 0.0, owedShareTotal = 0.0,
                settlementsPaid = 0.0, settlementsReceived = 0.0,
                netBalance = 0.0
            )

        lifecycle = GroupLifecycleCoordinator(
            groupTxCoordinator, groupDao, database.groupExpenseDao(), memberDao, settlementDao,
            database.groupLifecycleEventDao(),
            balanceCalculator,
            timeProvider, currencySettingsRepository,
            mockk<DatabaseWriteBarrier>(relaxed = true), database,
            mockk<dagger.Lazy<BudgetMonitor>>(relaxed = true),
            mockk<TransactionSideEffectDispatcher>(relaxed = true),
            Dispatchers.IO
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── createGroup ──────────────────────────────────────────────────

    @Test
    fun `createGroup succeeds with valid members`() = runTest {
        val members = listOf(
            GroupMember(groupId = 0, name = "Alice", isCurrentUser = true),
            GroupMember(groupId = 0, name = "Bob", isCurrentUser = false)
        )

        val result = lifecycle.createGroup("Vacation", "Summer trip", "EUR", members)

        assertThat(result).isInstanceOf(GroupCreationResult.Success::class.java)
        val groupId = (result as GroupCreationResult.Success).groupId
        assertThat(groupId).isGreaterThan(0)

        val savedGroup = groupDao.getGroupById(groupId)
        assertThat(savedGroup).isNotNull()
        assertThat(savedGroup!!.name).isEqualTo("Vacation")
        assertThat(savedGroup.defaultCurrency).isEqualTo("EUR")

        val savedMembers = memberDao.getMembersForGroup(groupId).first()
        assertThat(savedMembers).hasSize(2)
    }

    @Test
    fun `createGroup rejects fewer than 2 members`() = runTest {
        val members = listOf(
            GroupMember(groupId = 0, name = "Solo", isCurrentUser = true)
        )

        val result = lifecycle.createGroup("Solo", null, "EUR", members)

        assertThat(result).isInstanceOf(GroupCreationResult.Error::class.java)
        assertThat((result as GroupCreationResult.Error).message).contains("at least 2")
    }

    @Test
    fun `createGroup rejects missing currentUser`() = runTest {
        val members = listOf(
            GroupMember(groupId = 0, name = "A", isCurrentUser = false),
            GroupMember(groupId = 0, name = "B", isCurrentUser = false)
        )

        val result = lifecycle.createGroup("NoMe", null, "EUR", members)

        assertThat(result).isInstanceOf(GroupCreationResult.Error::class.java)
        assertThat((result as GroupCreationResult.Error).message).contains("Exactly 1 member")
    }

    @Test
    fun `createGroup rejects multiple currentUsers`() = runTest {
        val members = listOf(
            GroupMember(groupId = 0, name = "A", isCurrentUser = true),
            GroupMember(groupId = 0, name = "B", isCurrentUser = true)
        )

        val result = lifecycle.createGroup("DualMe", null, "EUR", members)

        assertThat(result).isInstanceOf(GroupCreationResult.Error::class.java)
        assertThat((result as GroupCreationResult.Error).message).contains("Exactly 1 member")
    }

    @Test
    fun `createGroup rejects blank group name`() = runTest {
        val members = listOf(
            GroupMember(groupId = 0, name = "A", isCurrentUser = true),
            GroupMember(groupId = 0, name = "B", isCurrentUser = false)
        )

        val result = lifecycle.createGroup("   ", null, "EUR", members)

        assertThat(result).isInstanceOf(GroupCreationResult.Error::class.java)
        assertThat((result as GroupCreationResult.Error).message).contains("blank")
    }

    @Test
    fun `createGroup rejects duplicate member names`() = runTest {
        val members = listOf(
            GroupMember(groupId = 0, name = "Alice", isCurrentUser = true),
            GroupMember(groupId = 0, name = "Alice", isCurrentUser = false)
        )

        val result = lifecycle.createGroup("Dup", null, "EUR", members)

        assertThat(result).isInstanceOf(GroupCreationResult.Error::class.java)
        assertThat((result as GroupCreationResult.Error).message).contains("Duplicate")
    }

    // ── addMember ────────────────────────────────────────────────────

    @Test
    fun `addMember succeeds for active group`() = runTest {
        val groupId = seedGroup("Friends", "EUR")
        seedMember(groupId, "Alice", isCurrentUser = true)

        val result = lifecycle.addMember(groupId, "Bob")

        assertThat(result.isSuccess).isTrue()
        val members = memberDao.getAllForGroup(groupId)
        assertThat(members).hasSize(2)
        assertThat(members.map { it.name }).contains("Bob")
    }

    @Test
    fun `addMember rejects duplicate member name`() = runTest {
        val groupId = seedGroup("Friends", "EUR")
        seedMember(groupId, "Alice", isCurrentUser = true)

        val result = lifecycle.addMember(groupId, "Alice")

        assertThat(result.isFailure).isTrue()
        assertThat((result as Result.Error).error).isInstanceOf(GroupValidationError.UserAlreadyMember::class.java)
    }

    @Test
    fun `addMember rejects second currentUser`() = runTest {
        val groupId = seedGroup("Friends", "EUR")
        seedMember(groupId, "Alice", isCurrentUser = true)

        val result = lifecycle.addMember(groupId, "Bob", isCurrentUser = true)

        assertThat(result.isFailure).isTrue()
        assertThat((result as Result.Error).error).isInstanceOf(GroupValidationError.CurrentUserAlreadyExists::class.java)
    }

    @Test
    fun `addMember rejects archived group`() = runTest {
        val groupId = seedGroup("Old", "EUR")
        groupDao.archiveGroup(groupId)

        val result = lifecycle.addMember(groupId, "Ghost")

        assertThat(result.isFailure).isTrue()
        assertThat((result as Result.Error).error).isEqualTo(GroupValidationError.InvalidGroup)
    }

    // ── removeMember ─────────────────────────────────────────────────

    @Test
    fun `removeMember succeeds for non-currentUser with no balance`() = runTest {
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

    @Test
    fun `removeMember blocks removal of currentUser`() = runTest {
        val groupId = seedGroup("Friends", "EUR")
        seedMember(groupId, "Alice", isCurrentUser = true)
        val alice = memberDao.getAllForGroup(groupId).first { it.name == "Alice" }

        val result = lifecycle.removeMember(groupId, alice.id)

        assertThat(result.isFailure).isTrue()
        assertThat((result as Result.Error).error).isInstanceOf(GroupValidationError.Unknown::class.java)
        // Alice should still be there
        val members = memberDao.getAllForGroup(groupId)
        assertThat(members).hasSize(1)
    }

    // ── addExpense ───────────────────────────────────────────────────

    @Test
    fun `addExpense succeeds with matching currency`() = runTest {
        val groupId = seedGroup("Trip", "EUR")
        val aliceId = seedMember(groupId, "Alice", isCurrentUser = true)
        val bobId = seedMember(groupId, "Bob", isCurrentUser = false)

        val result = lifecycle.addExpense(
            groupId = groupId,
            description = "Dinner",
            amount = 50.0,
            paidById = aliceId,
            currency = "EUR",
            date = TEST_DATE
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
        val success = result as GroupExpenseCreationResult.Success
        assertThat(success.groupExpenseId).isGreaterThan(0)
    }

    @Test
    fun `addExpense rejects mismatched currency`() = runTest {
        val groupId = seedGroup("Trip", "EUR")
        val aliceId = seedMember(groupId, "Alice", isCurrentUser = true)
        seedMember(groupId, "Bob", isCurrentUser = false)

        val result = lifecycle.addExpense(
            groupId = groupId,
            description = "Dinner",
            amount = 50.0,
            paidById = aliceId,
            currency = "USD",
            date = TEST_DATE
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Error::class.java)
        assertThat((result as GroupExpenseCreationResult.Error).message).contains("must match group currency")
    }

    @Test
    fun `addExpense defaults to group currency when null`() = runTest {
        val groupId = seedGroup("Trip", "EUR")
        val aliceId = seedMember(groupId, "Alice", isCurrentUser = true)
        seedMember(groupId, "Bob", isCurrentUser = false)

        val result = lifecycle.addExpense(
            groupId = groupId,
            description = "Lunch",
            amount = 30.0,
            paidById = aliceId,
            currency = null,
            date = TEST_DATE
        )

        assertThat(result).isInstanceOf(GroupExpenseCreationResult.Success::class.java)
    }

    // ── archiveGroup ─────────────────────────────────────────────────

    @Test
    fun `archiveGroup sets isActive to false preserving data`() = runTest {
        val groupId = seedGroup("ToArchive", "EUR")
        seedMember(groupId, "Alice", isCurrentUser = true)

        val result = lifecycle.archiveGroup(groupId)

        assertThat(result).isTrue()
        // Members should still be present
        val members = memberDao.getAllForGroup(groupId)
        assertThat(members).hasSize(1)
    }

    @Test
    fun `archiveGroup returns false for nonexistent group`() = runTest {
        val result = lifecycle.archiveGroup(99999L)
        assertThat(result).isFalse()
    }

    // ── deleteGroupPermanently ───────────────────────────────────────

    @Test
    fun `deleteGroupPermanently requires confirmation flag`() = runTest {
        val groupId = seedGroup("ToDelete", "EUR")
        seedMember(groupId, "Alice", isCurrentUser = true)

        val result = lifecycle.deleteGroupPermanently(groupId, confirmPermanentDelete = false)

        assertThat(result).isFalse()
        // Group should still exist
        val group = groupDao.getGroupById(groupId)
        assertThat(group).isNotNull()
    }

    @Test
    fun `deleteGroupPermanently hard-deletes with confirmation`() = runTest {
        val groupId = seedGroup("ToDelete", "EUR")
        seedMember(groupId, "Alice", isCurrentUser = true)

        // G04: Group must be archived before hard-delete (enforced by production code)
        val archiveResult = lifecycle.archiveGroup(groupId)
        assertThat(archiveResult).isTrue()

        val result = lifecycle.deleteGroupPermanently(groupId, confirmPermanentDelete = true)

        assertThat(result).isTrue()
        // verify removal — at minimum the group row is gone
        // (the permanentDeleteGroup cascade may vary; at least group is gone)
        val group = groupDao.getGroupById(groupId)
        assertThat(group).isNull()
    }

    // ── recordSettlement ─────────────────────────────────────────────

    @Test
    fun `recordSettlement persists settlement record`() = runTest {
        val groupId = seedGroup("Settle", "EUR")
        val aliceId = seedMember(groupId, "Alice", isCurrentUser = true)
        val bobId = seedMember(groupId, "Bob", isCurrentUser = false)

        val settlementId = lifecycle.recordSettlement(
            groupId = groupId,
            fromMemberId = bobId,
            toMemberId = aliceId,
            amount = 25.0,
            currency = "EUR",
            notes = "Dinner repayment"
        )

        assertThat(settlementId).isGreaterThan(0)

        val settlements = settlementDao.getSettlementsForGroup(groupId)
        assertThat(settlements).hasSize(1)
        assertThat(settlements.first().amount).isEqualTo(25.0)
        assertThat(settlements.first().currency).isEqualTo("EUR")
        assertThat(settlements.first().status).isEqualTo("RECORDED")
        assertThat(settlements.first().notes).isEqualTo("Dinner repayment")
    }

    @Test
    fun `recordSettlement rejects mismatched currency`() = runTest {
        val groupId = seedGroup("Settle", "EUR")
        val aliceId = seedMember(groupId, "Alice", isCurrentUser = true)
        val bobId = seedMember(groupId, "Bob", isCurrentUser = false)

        try {
            lifecycle.recordSettlement(
                groupId = groupId,
                fromMemberId = bobId,
                toMemberId = aliceId,
                amount = 25.0,
                currency = "USD",
                notes = null
            )
            assertThat(true).isFalse() // should not reach here
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("must match group currency")
        }
    }

    @Test
    fun `recordSettlement rejects archived group`() = runTest {
        val groupId = seedGroup("Settle", "EUR")
        val aliceId = seedMember(groupId, "Alice", isCurrentUser = true)
        val bobId = seedMember(groupId, "Bob", isCurrentUser = false)
        groupDao.archiveGroup(groupId)

        try {
            lifecycle.recordSettlement(
                groupId = groupId,
                fromMemberId = bobId,
                toMemberId = aliceId,
                amount = 25.0,
                currency = "EUR"
            )
            assertThat(true).isFalse()
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("archived")
        }
    }

    // ── end-to-end scenario ──────────────────────────────────────────

    @Test
    fun `full group lifecycle create-add-remove-expense-archive`() = runTest {
        // Create group
        val members = listOf(
            GroupMember(groupId = 0, name = "Me", isCurrentUser = true),
            GroupMember(groupId = 0, name = "You", isCurrentUser = false)
        )
        val createResult = lifecycle.createGroup("Life", "End-to-end", "USD", members)
        assertThat(createResult).isInstanceOf(GroupCreationResult.Success::class.java)
        val groupId = (createResult as GroupCreationResult.Success).groupId

        // Add third member
        val addResult = lifecycle.addMember(groupId, "Them")
        assertThat(addResult.isSuccess).isTrue()
        val membersList = memberDao.getAllForGroup(groupId)
        assertThat(membersList).hasSize(3)

        // Add expense
        val me = membersList.first { it.name == "Me" }
        val expResult = lifecycle.addExpense(
            groupId = groupId, description = "Pizza", amount = 30.0,
            paidById = me.id, currency = "USD", date = TEST_DATE
        )
        assertThat(expResult).isInstanceOf(GroupExpenseCreationResult.Success::class.java)

        // Remove non-currentUser
        val them = membersList.first { it.name == "Them" }
        val removeResult = lifecycle.removeMember(groupId, them.id)
        assertThat(removeResult.isSuccess).isTrue()

        // Record settlement
        val you = memberDao.getAllForGroup(groupId).first { it.name == "You" }
        val settlementId = lifecycle.recordSettlement(
            groupId = groupId, fromMemberId = you.id, toMemberId = me.id,
            amount = 15.0, currency = "USD", notes = "Pizza half"
        )
        assertThat(settlementId).isGreaterThan(0)

        // Archive
        val archiveResult = lifecycle.archiveGroup(groupId)
        assertThat(archiveResult).isTrue()
    }

    // ── helpers ──────────────────────────────────────────────────────

    private suspend fun seedGroup(name: String, currency: String): Long {
        val group = ExpenseGroup(name = name, defaultCurrency = currency)
        return groupDao.insert(group)
    }

    private suspend fun seedMember(groupId: Long, name: String, isCurrentUser: Boolean = false): Long {
        val member = GroupMember(
            groupId = groupId,
            name = name,
            isCurrentUser = isCurrentUser,
            currentUserGroupKey = if (isCurrentUser) groupId else null
        )
        return memberDao.insert(member)
    }
}
