package com.yourname.expensetracker.domain.transaction.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R18 slice A: a group-linked expense must not be deleted, and its money / ownership /
 * type fields must not be edited through [TransactionLifecycleCoordinator]. Real in-memory
 * Room database; side-effect collaborators are mocked (same harness as
 * TransactionLifecycleCoordinatorDbContractTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionLifecycleCoordinatorGroupLinkedGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var coordinator: TransactionLifecycleCoordinator
    private var shoppingCategoryId: Long = 0L
    private var groupId: Long = 0L
    private var memberId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        timeProvider = FakeTimeProvider.forDate(2026, 5, 1)

        db.categoryDao().insert(Category(name = "Food & Dining", icon = "F", color = "#FF5733"))
        shoppingCategoryId = db.categoryDao().insert(
            Category(name = "Shopping", icon = "S", color = "#33FF57")
        )
        groupId = db.expenseGroupDao().insert(ExpenseGroup(name = "Trip"))
        memberId = db.groupMemberDao().insert(
            GroupMember(groupId = groupId, name = "Me", isCurrentUser = true)
        )

        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        coEvery {
            writeBarrier.runWrite(
                any<DatabaseAccessOperation>(),
                any<suspend () -> Any?>()
            )
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }

        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        coordinator = TransactionLifecycleCoordinator(
            database = db,
            expenseDao = db.expenseDao(),
            transactionEventDao = db.transactionEventDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = mockk(relaxed = true),
            runner = mockk(relaxed = true),
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            currencySettingsRepository = currencySettingsRepository,
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun seedExpense(merchant: String, linked: Boolean): Long {
        val id = db.expenseDao().insert(
            Expense(
                amount = 40.0,
                currency = "EUR",
                merchant = merchant,
                merchantKey = merchant.lowercase(),
                transactionType = TransactionType.PURCHASE,
                date = timeProvider.now()
            )
        )
        assertTrue(id > 0L)
        if (linked) {
            val groupExpenseId = db.groupExpenseDao().insert(
                GroupExpense(
                    groupId = groupId,
                    expenseId = id,
                    paidById = memberId,
                    date = timeProvider.now(),
                    description = merchant,
                    totalAmount = 40.0,
                    currency = "EUR"
                )
            )
            assertTrue(groupExpenseId > 0L)
        }
        return id
    }

    private suspend fun groupLinkCount(): Int =
        db.groupExpenseDao().getExpensesForGroupOnce(groupId).size

    private suspend fun eventCount(expenseId: Long): Int =
        db.transactionEventDao().getEventsForExpense(expenseId).size

    private suspend fun assertBlockedEdit(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected GroupLinkedExpenseException")
        } catch (e: GroupLinkedExpenseException) {
            assertEquals(GroupLinkedExpenseException.EXPENSE_EDIT_BLOCKED_GROUP_LINKED, e.code)
        }
    }

    @Test
    fun `linked delete fails with bounded code and changes nothing`() = runTest {
        val id = seedExpense("Linked Store", linked = true)
        val expense = db.expenseDao().getById(id)!!

        val result = coordinator.deleteExpense(expense)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is GroupLinkedExpenseException)
        assertEquals(
            GroupLinkedExpenseException.EXPENSE_DELETE_BLOCKED_GROUP_LINKED,
            (error as GroupLinkedExpenseException).code
        )
        assertNotNull("Expense row must survive", db.expenseDao().getById(id))
        assertEquals("group_expenses row must survive", 1, groupLinkCount())
        assertEquals("No DELETED event may be written", 0, eventCount(id))
    }

    @Test
    fun `unlinked delete still succeeds and writes DELETED event`() = runTest {
        val id = seedExpense("Plain Store", linked = false)
        val expense = db.expenseDao().getById(id)!!

        val result = coordinator.deleteExpense(expense)

        assertTrue(result.isSuccess)
        assertNull(db.expenseDao().getById(id))
        val deleted = db.transactionEventDao().getEventsForExpense(id)
            .filter { it.eventType == LifecycleEventType.DELETED.name }
        assertEquals(1, deleted.size)
    }

    @Test
    fun `linked amount edit is blocked and row is unchanged`() = runTest {
        val id = seedExpense("Linked Store", linked = true)
        val existing = db.expenseDao().getById(id)!!

        assertBlockedEdit { coordinator.updateExpense(existing.copy(amount = 99.0)) }

        assertEquals(40.0, db.expenseDao().getById(id)!!.amount, 0.0001)
        assertEquals(0, eventCount(id))
        assertEquals(1, groupLinkCount())
    }

    @Test
    fun `linked currency and share edits are blocked`() = runTest {
        val id = seedExpense("Linked Store", linked = true)
        val existing = db.expenseDao().getById(id)!!

        assertBlockedEdit { coordinator.updateExpense(existing.copy(currency = "USD")) }
        assertBlockedEdit {
            coordinator.updateExpense(existing.copy(isSharedExpense = true, myShareAmount = 10.0))
        }

        val after = db.expenseDao().getById(id)!!
        assertEquals("EUR", after.currency)
        assertFalse(after.isSharedExpense)
        assertEquals(0, eventCount(id))
    }

    @Test
    fun `linked non-money edit is still allowed`() = runTest {
        val id = seedExpense("Linked Store", linked = true)
        val existing = db.expenseDao().getById(id)!!

        coordinator.updateExpense(existing.copy(categoryId = shoppingCategoryId))

        val after = db.expenseDao().getById(id)!!
        assertEquals(shoppingCategoryId, after.categoryId)
        assertEquals(40.0, after.amount, 0.0001)
        assertEquals(1, groupLinkCount())
    }

    @Test
    fun `unlinked amount edit is still allowed`() = runTest {
        val id = seedExpense("Plain Store", linked = false)
        val existing = db.expenseDao().getById(id)!!

        coordinator.updateExpense(existing.copy(amount = 55.0))

        assertEquals(55.0, db.expenseDao().getById(id)!!.amount, 0.0001)
    }

    @Test
    fun `linked type change is blocked`() = runTest {
        val id = seedExpense("Linked Store", linked = true)

        assertBlockedEdit { coordinator.updateType(id, TransactionType.DEPOSIT) }
        assertBlockedEdit {
            coordinator.updateTypeAndTransferDetails(
                expenseId = id,
                newType = TransactionType.TRANSFER,
                transferDirection = TransferDirection.OUTGOING,
                transferAccountName = "Savings"
            )
        }

        assertEquals(TransactionType.PURCHASE, db.expenseDao().getById(id)!!.transactionType)
        assertEquals(0, eventCount(id))
    }

    @Test
    fun `linked updateType with the same type is a NoChange without throw or event`() = runTest {
        val id = seedExpense("Linked Store", linked = true)

        coordinator.updateType(id, TransactionType.PURCHASE)

        assertEquals(TransactionType.PURCHASE, db.expenseDao().getById(id)!!.transactionType)
        assertEquals(0, eventCount(id))
        assertEquals(1, groupLinkCount())
    }

    @Test
    fun `linked transfer-detail-only change with the same type is allowed`() = runTest {
        val id = seedExpense("Linked Store", linked = true)

        coordinator.updateTypeAndTransferDetails(
            expenseId = id,
            newType = TransactionType.PURCHASE,
            transferDirection = TransferDirection.OUTGOING,
            transferAccountName = "Savings"
        )

        val after = db.expenseDao().getById(id)!!
        assertEquals(TransactionType.PURCHASE, after.transactionType)
        assertEquals(TransferDirection.OUTGOING, after.transferDirection)
        assertEquals("Savings", after.transferAccountName)
        assertEquals(40.0, after.amount, 0.0001)
        assertEquals(1, groupLinkCount())
    }

    @Test
    fun `linked non-money updateExpense writes exactly one UPDATED event`() = runTest {
        val id = seedExpense("Linked Store", linked = true)
        val existing = db.expenseDao().getById(id)!!

        coordinator.updateExpense(existing.copy(categoryId = shoppingCategoryId))

        val updated = db.transactionEventDao().getEventsForExpense(id)
            .filter { it.eventType == LifecycleEventType.UPDATED.name }
        assertEquals(1, updated.size)
        assertEquals(1, eventCount(id))
    }

    @Test
    fun `linked updateOwnership with unchanged values does not throw`() = runTest {
        val id = seedExpense("Linked Store", linked = true)

        coordinator.updateOwnership(
            expenseId = id,
            isNotMine = false,
            ownerName = null,
            isSharedExpense = false,
            sharedWithName = null,
            mySharePercentage = null,
            myShareAmount = null
        )

        val after = db.expenseDao().getById(id)!!
        assertFalse(after.isSharedExpense)
        assertEquals(0, eventCount(id))
        assertEquals(1, groupLinkCount())
    }

    @Test
    fun `linked updateOwnership wrapper is blocked and row is unchanged`() = runTest {
        val id = seedExpense("Linked Store", linked = true)

        assertBlockedEdit {
            coordinator.updateOwnership(
                expenseId = id,
                isNotMine = false,
                ownerName = null,
                isSharedExpense = true,
                sharedWithName = "Bob",
                mySharePercentage = null,
                myShareAmount = 20.0
            )
        }

        assertFalse(db.expenseDao().getById(id)!!.isSharedExpense)
        assertEquals(0, eventCount(id))
    }

    @Test
    fun `unlinked updateOwnership wrapper still works`() = runTest {
        val id = seedExpense("Plain Store", linked = false)

        coordinator.updateOwnership(
            expenseId = id,
            isNotMine = false,
            ownerName = null,
            isSharedExpense = true,
            sharedWithName = "Bob",
            mySharePercentage = null,
            myShareAmount = 20.0
        )

        assertTrue(db.expenseDao().getById(id)!!.isSharedExpense)
    }

    @Test
    fun `updateOwnershipDbOnlyV2 used by the group coordinator is unaffected`() = runTest {
        val id = seedExpense("Linked Store", linked = true)

        coordinator.updateOwnershipDbOnlyV2(
            expenseId = id,
            isNotMine = false,
            ownerName = null,
            isSharedExpense = true,
            sharedWithName = null,
            mySharePercentage = null,
            myShareAmount = 20.0,
            reason = "Group expense linking: set shared-expense metadata",
            source = "GROUP_EXPENSE",
            correlationId = null
        )

        val after = db.expenseDao().getById(id)!!
        assertTrue(after.isSharedExpense)
        assertEquals(1, groupLinkCount())
    }
}
