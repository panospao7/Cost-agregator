package com.yourname.expensetracker.data.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.GroupSettlementEntity
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DR-01: real in-memory Room/SQLite proofs of the two read-only cash-flow probes
 * [GroupExpenseDao.getNonPayerLinkedExpenseIdsBetween] and
 * [GroupSettlementDao.getCurrentUserSettlementsBetween]. A DAO mock cannot catch a deleted or
 * weakened JOIN/WHERE clause; these tests can. Both ranges are half-open [start, end).
 * Synthetic names and fixed dates only.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GroupCashFlowProbesSqlTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var groupExpenseDao: GroupExpenseDao
    private lateinit var settlementDao: GroupSettlementDao

    private val start = dateMs(2026, 5, 1)
    private val end = dateMs(2026, 6, 1)
    private val inRange = dateMs(2026, 5, 10)

    private var groupId = 0L
    private var meId = 0L
    private var friendAId = 0L
    private var friendBId = 0L

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        expenseDao = db.expenseDao()
        groupExpenseDao = db.groupExpenseDao()
        settlementDao = db.groupSettlementDao()

        groupId = db.expenseGroupDao().insert(ExpenseGroup(name = "Synthetic Trip", createdAt = 1L))
        meId = db.groupMemberDao().insert(
            GroupMember(
                groupId = groupId, name = "Me", isCurrentUser = true,
                joinedAt = 1L, currentUserGroupKey = groupId
            )
        )
        friendAId = db.groupMemberDao().insert(GroupMember(groupId = groupId, name = "Friend A", joinedAt = 1L))
        friendBId = db.groupMemberDao().insert(GroupMember(groupId = groupId, name = "Friend B", joinedAt = 1L))
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** Inserts a personal expense (the user's share) and links it to a group expense paid by [paidById]. */
    private suspend fun linkedExpense(merchant: String, date: Long, paidById: Long): Long {
        val expenseId = expenseDao.insert(
            Expense(
                amount = 30.0,
                currency = "EUR",
                merchant = merchant,
                merchantKey = merchant.lowercase(),
                transactionType = TransactionType.PURCHASE,
                date = date
            )
        )
        groupExpenseDao.insert(
            GroupExpense(
                groupId = groupId,
                expenseId = expenseId,
                paidById = paidById,
                date = date,
                description = merchant,
                totalAmount = 90.0
            )
        )
        return expenseId
    }

    private suspend fun settle(
        from: Long,
        to: Long,
        createdAt: Long = inRange,
        status: String = "RECORDED",
        amount: Double = 30.0,
        currency: String = "EUR",
        linkedExpenseId: Long? = null
    ): Long = settlementDao.insert(
        GroupSettlementEntity(
            groupId = groupId,
            fromMemberId = from,
            toMemberId = to,
            amount = amount,
            currency = currency,
            createdAt = createdAt,
            linkedExpenseId = linkedExpenseId,
            status = status
        )
    )

    // ── getNonPayerLinkedExpenseIdsBetween ────────────────────────────────────

    @Test
    fun `non-payer probe includes an expense linked to a group expense a friend paid`() = runTest {
        val id = linkedExpense("DinnerFriendPaid", inRange, paidById = friendAId)

        assertEquals(listOf(id), groupExpenseDao.getNonPayerLinkedExpenseIdsBetween(start, end))
    }

    @Test
    fun `non-payer probe excludes an expense the current user paid`() = runTest {
        linkedExpense("DinnerIPaid", inRange, paidById = meId)

        assertTrue(groupExpenseDao.getNonPayerLinkedExpenseIdsBetween(start, end).isEmpty())
    }

    @Test
    fun `non-payer probe separates payer and non-payer rows in one range`() = runTest {
        val friendA = linkedExpense("PaidByA", inRange, paidById = friendAId)
        linkedExpense("PaidByMe", inRange + 1_000L, paidById = meId)
        val friendB = linkedExpense("PaidByB", inRange + 2_000L, paidById = friendBId)

        assertEquals(
            setOf(friendA, friendB),
            groupExpenseDao.getNonPayerLinkedExpenseIdsBetween(start, end).toSet()
        )
    }

    @Test
    fun `non-payer probe excludes expenses outside the range`() = runTest {
        linkedExpense("TooEarly", start - 1L, paidById = friendAId)
        linkedExpense("TooLate", end + 1L, paidById = friendAId)

        assertTrue(groupExpenseDao.getNonPayerLinkedExpenseIdsBetween(start, end).isEmpty())
    }

    @Test
    fun `non-payer probe range is half-open - start included, end excluded`() = runTest {
        val atStart = linkedExpense("AtStart", start, paidById = friendAId)
        val lastMs = linkedExpense("LastMs", end - 1L, paidById = friendAId)
        linkedExpense("AtEnd", end, paidById = friendAId)

        assertEquals(
            setOf(atStart, lastMs),
            groupExpenseDao.getNonPayerLinkedExpenseIdsBetween(start, end).toSet()
        )
    }

    @Test
    fun `non-payer probe never returns an unlinked personal expense`() = runTest {
        expenseDao.insert(
            Expense(
                amount = 12.0, currency = "EUR", merchant = "Unlinked", merchantKey = "unlinked",
                transactionType = TransactionType.PURCHASE, date = inRange
            )
        )

        assertTrue(groupExpenseDao.getNonPayerLinkedExpenseIdsBetween(start, end).isEmpty())
    }

    // ── getPayerLinkedExpenseIdsBetween ───────────────────────────────────────

    @Test
    fun `payer probe includes an expense linked to a group expense the current user paid`() = runTest {
        val id = linkedExpense("DinnerIPaidPayer", inRange, paidById = meId)

        assertEquals(listOf(id), groupExpenseDao.getPayerLinkedExpenseIdsBetween(start, end))
    }

    @Test
    fun `payer probe excludes a row another member paid`() = runTest {
        linkedExpense("PaidByFriendA", inRange, paidById = friendAId)
        linkedExpense("PaidByFriendB", inRange + 1_000L, paidById = friendBId)

        assertTrue(groupExpenseDao.getPayerLinkedExpenseIdsBetween(start, end).isEmpty())
    }

    @Test
    fun `payer probe and non-payer probe partition linked rows with no overlap`() = runTest {
        val mine = linkedExpense("PaidByMePartition", inRange, paidById = meId)
        val theirs = linkedExpense("PaidByAPartition", inRange + 1_000L, paidById = friendAId)

        assertEquals(listOf(mine), groupExpenseDao.getPayerLinkedExpenseIdsBetween(start, end))
        assertEquals(listOf(theirs), groupExpenseDao.getNonPayerLinkedExpenseIdsBetween(start, end))
    }

    @Test
    fun `payer probe never returns an unlinked personal expense`() = runTest {
        expenseDao.insert(
            Expense(
                amount = 12.0, currency = "EUR", merchant = "UnlinkedPayer", merchantKey = "unlinkedpayer",
                transactionType = TransactionType.PURCHASE, date = inRange
            )
        )

        assertTrue(groupExpenseDao.getPayerLinkedExpenseIdsBetween(start, end).isEmpty())
    }

    @Test
    fun `payer probe excludes expenses outside the range`() = runTest {
        linkedExpense("TooEarlyPayer", start - 1L, paidById = meId)
        linkedExpense("TooLatePayer", end + 1L, paidById = meId)

        assertTrue(groupExpenseDao.getPayerLinkedExpenseIdsBetween(start, end).isEmpty())
    }

    @Test
    fun `payer probe range is half-open - start included, end excluded`() = runTest {
        val atStart = linkedExpense("AtStartPayer", start, paidById = meId)
        val lastMs = linkedExpense("LastMsPayer", end - 1L, paidById = meId)
        linkedExpense("AtEndPayer", end, paidById = meId)

        assertEquals(
            setOf(atStart, lastMs),
            groupExpenseDao.getPayerLinkedExpenseIdsBetween(start, end).toSet()
        )
    }

    // ── getCurrentUserSettlementsBetween ──────────────────────────────────────

    @Test
    fun `settlements probe returns a paid row and a received row with the right direction`() = runTest {
        val paid = settle(from = meId, to = friendAId, amount = 30.0, linkedExpenseId = 77L)
        val received = settle(from = friendBId, to = meId, amount = 20.0, createdAt = inRange + 1_000L)

        val rows = settlementDao.getCurrentUserSettlementsBetween(start, end).associateBy { it.id }

        assertEquals(setOf(paid, received), rows.keys)
        val paidRow = rows.getValue(paid)
        assertTrue(paidRow.paidByCurrentUser)
        assertEquals(30.0, paidRow.amount, 0.0)
        assertEquals("EUR", paidRow.currency)
        assertEquals(inRange, paidRow.createdAt)
        assertEquals(77L, paidRow.linkedExpenseId)
        val receivedRow = rows.getValue(received)
        assertFalse(receivedRow.paidByCurrentUser)
        assertEquals(20.0, receivedRow.amount, 0.0)
        assertEquals(null, receivedRow.linkedExpenseId)
    }

    @Test
    fun `settlements probe ignores a settlement where neither party is the current user`() = runTest {
        settle(from = friendAId, to = friendBId)

        assertTrue(settlementDao.getCurrentUserSettlementsBetween(start, end).isEmpty())
    }

    /**
     * Doc contract: exactly one party is the current user. A row whose two parties are both the
     * current user (a self settlement) is not a cash event and must be ignored.
     */
    @Test
    fun `settlements probe ignores a settlement where both parties are the current user`() = runTest {
        settle(from = meId, to = meId)

        assertTrue(settlementDao.getCurrentUserSettlementsBetween(start, end).isEmpty())
    }

    @Test
    fun `settlements probe counts RECORDED and COMPLETED but excludes CANCELLED`() = runTest {
        val recorded = settle(from = meId, to = friendAId, status = "RECORDED")
        val completed = settle(from = friendAId, to = meId, status = "COMPLETED", createdAt = inRange + 1_000L)
        settle(from = meId, to = friendBId, status = "CANCELLED", createdAt = inRange + 2_000L)
        settle(from = friendBId, to = meId, status = "CANCELLED", createdAt = inRange + 3_000L)

        assertEquals(
            setOf(recorded, completed),
            settlementDao.getCurrentUserSettlementsBetween(start, end).map { it.id }.toSet()
        )
    }

    @Test
    fun `settlements probe range is half-open on createdAt - start included, end excluded`() = runTest {
        val atStart = settle(from = meId, to = friendAId, createdAt = start)
        val lastMs = settle(from = friendAId, to = meId, createdAt = end - 1L)
        settle(from = meId, to = friendAId, createdAt = end)
        settle(from = meId, to = friendAId, createdAt = start - 1L)

        assertEquals(
            setOf(atStart, lastMs),
            settlementDao.getCurrentUserSettlementsBetween(start, end).map { it.id }.toSet()
        )
    }
}
