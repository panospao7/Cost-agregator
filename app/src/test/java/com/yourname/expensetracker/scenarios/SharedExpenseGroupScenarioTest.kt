package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.test.runTest
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

/**
 * Scenario tests for shared expense groups — covering group creation, member
 * management, shared expense splitting, reimbursement recording, and shared
 * expense flagging.
 *
 * These tests operate directly on the Room DAOs with an in-memory database,
 * verifying DB state after each operation. Settlement-calculation logic and
 * UI-layer flows are deliberately excluded; this is a DB-contract scenario test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedExpenseGroupScenarioTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var timeProvider: FakeTimeProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        timeProvider = FakeTimeProvider.forDate(2026, 5, 1)

        // Seed a default category so Expense records have a valid category FK.
        runTest {
            db.categoryDao().insert(
                Category(name = "Food & Dining", icon = "🍕", color = "#FF5733")
            )
        }
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ── Helper: create an ExpenseGroup with N members ───────────────────────

    /**
     * Creates an [ExpenseGroup] and [memberNames.size] [GroupMember] rows in a
     * single atomic insert (via [ExpenseGroupDao.insertGroupWithMembers]).
     *
     * @param groupName        Display name for the group.
     * @param memberNames      Names of each member. The first member is designated
     *                         as the "current user" (isCurrentUser = true).
     * @return A pair of (groupId, list of memberIds in insertion order).
     */
    private suspend fun createGroupWithMembers(
        groupName: String,
        memberNames: List<String>
    ): GroupWithMembers {
        val group = ExpenseGroup(
            name = groupName,
            description = "Test group for $groupName",
            createdAt = timeProvider.now()
        )
        val groupId = db.expenseGroupDao().insert(group)
        assertTrue("Group ID should be positive", groupId > 0L)

        val members = memberNames.mapIndexed { index, name ->
            GroupMember(
                groupId = groupId,
                name = name,
                isCurrentUser = index == 0, // first member is the app user
                joinedAt = timeProvider.now(),
                currentUserGroupKey = if (index == 0) groupId else null
            )
        }
        val memberIds = db.groupMemberDao().insertAll(members)
        assertEquals(
            "All members should be inserted",
            memberNames.size,
            memberIds.size
        )

        return GroupWithMembers(groupId, memberIds)
    }

    private data class GroupWithMembers(
        val groupId: Long,
        val memberIds: List<Long>
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: expense group created with members
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `expense group created with members`() = runTest {
        // GIVEN: categories seeded (in setUp)
        val groupName = "Dinner Group"

        // WHEN: create an ExpenseGroup "Dinner Group" with 3 members (payer + 2 others)
        val seed = createGroupWithMembers(groupName, listOf("Alice", "Bob", "Charlie"))

        // THEN: group exists in the DB with the correct name
        val group = db.expenseGroupDao().getById(seed.groupId)
        assertNotNull("ExpenseGroup should exist", group)
        assertEquals("Group name should match", groupName, group!!.name)
        assertTrue("Group should be active", group.isActive)

        // THEN: members exist (count=3)
        val members = db.groupMemberDao().getAllForGroup(seed.groupId)
        assertEquals("Should have exactly 3 members", 3, members.size)

        // THEN: member names match what was inserted
        val memberNames = members.map { it.name }.toSet()
        assertEquals(
            "Member names should match",
            setOf("Alice", "Bob", "Charlie"),
            memberNames
        )

        // THEN: exactly one member is the current user
        val currentUser = db.groupMemberDao().getCurrentUser(seed.groupId)
        assertNotNull("There should be a current user", currentUser)
        assertEquals("Current user should be Alice", "Alice", currentUser!!.name)
        assertEquals(
            "currentUserGroupKey should equal groupId",
            seed.groupId,
            currentUser.currentUserGroupKey
        )

        // THEN: member count via DAO matches
        val count = db.groupMemberDao().getMemberCount(seed.groupId)
        assertEquals("Member count should be 3", 3, count)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: group expense split among members
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `group expense split among members`() = runTest {
        // GIVEN: a group with 3 members
        val seed = createGroupWithMembers("Dinner Group", listOf("Alice", "Bob", "Charlie"))
        assertEquals("Should have 3 members", 3, seed.memberIds.size)

        // WHEN: add a group expense of €90.00 paid by member 1, split equally
        val expense = Expense(
            amount = 90.00,
            currency = "EUR",
            merchant = "Group Dinner",
            transactionType = TransactionType.PURCHASE,
            date = timeProvider.now(),
            isSharedExpense = true,
            myShareAmount = 30.0, // Alice's share = 90 / 3
            source = "group_scenario_test"
        )
        val expenseId = db.expenseDao().insert(expense)
        assertTrue("Expense ID should be positive", expenseId > 0L)

        val groupExpense = GroupExpense(
            groupId = seed.groupId,
            expenseId = expenseId,
            paidById = seed.memberIds[0], // Alice paid
            date = timeProvider.now(),
            description = "Group dinner at restaurant",
            totalAmount = 90.0,
            splitType = SplitType.EQUAL
        )
        val groupExpenseId = db.groupExpenseDao().insert(groupExpense)
        assertTrue("GroupExpense ID should be positive", groupExpenseId > 0L)

        // THEN: expense exists with amount=90.00
        val savedExpense = db.expenseDao().getById(expenseId)
        assertNotNull("Expense should exist", savedExpense)
        assertEquals("Expense amount should be 90.00", 90.00, savedExpense!!.amount, 0.001)
        assertEquals("Expense merchant should match", "Group Dinner", savedExpense.merchant)
        assertTrue("Expense should be flagged as shared", savedExpense.isSharedExpense)

        // THEN: group expense exists with correct metadata
        val savedGroupExpense = db.groupExpenseDao().getById(groupExpenseId)
        assertNotNull("GroupExpense should exist", savedGroupExpense)
        assertEquals("Total amount should be 90.00", 90.00, savedGroupExpense!!.totalAmount, 0.001)
        assertEquals("Split type should be EQUAL", SplitType.EQUAL, savedGroupExpense.splitType)
        assertEquals(
            "Paid by should be Alice (member 1)",
            seed.memberIds[0],
            savedGroupExpense.paidById
        )
        assertEquals("GroupExpense should link to expense", expenseId, savedGroupExpense.expenseId)

        // AND: each member owes €30.00 — the EQUAL split with 3 members / €90
        //   means each share = 90 / 3 = €30. We verify the total and split metadata
        //   correctly imply this split.
        val groupExpenses = db.groupExpenseDao().getExpensesForGroupOnce(seed.groupId)
        assertEquals("Should have exactly 1 group expense", 1, groupExpenses.size)
        assertEquals("Total amount is consistent", 90.00, groupExpenses.single().totalAmount, 0.001)

        // Verify the split would produce 30€ per person: 90 / 3 members
        val memberShare = savedGroupExpense.totalAmount / seed.memberIds.size
        assertEquals("Each member's share should be €30.00", 30.00, memberShare, 0.001)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: reimbursement recorded
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `reimbursement recorded`() = runTest {
        // GIVEN: a group with 3 members and an expense where Alice (member 1) paid €90
        val seed = createGroupWithMembers("Dinner Group", listOf("Alice", "Bob", "Charlie"))

        // Create the shared expense (Alice paid €90, EQUAL split → each owes €30)
        val expense = Expense(
            amount = 90.00,
            currency = "EUR",
            merchant = "Group Dinner",
            transactionType = TransactionType.PURCHASE,
            date = timeProvider.now(),
            isSharedExpense = true,
            myShareAmount = 30.0,
            source = "group_scenario_test"
        )
        val expenseId = db.expenseDao().insert(expense)
        assertTrue("Expense ID should be positive", expenseId > 0L)

        // Create the group_expense link with reimbursable flag
        val groupExpense = GroupExpense(
            groupId = seed.groupId,
            expenseId = expenseId,
            paidById = seed.memberIds[0], // Alice paid
            date = timeProvider.now(),
            description = "Group dinner",
            totalAmount = 90.0,
            splitType = SplitType.EQUAL,
            isReimbursable = true,
            reimbursedAmount = 0.0
        )
        val groupExpenseId = db.groupExpenseDao().insert(groupExpense)
        assertTrue("GroupExpense ID should be positive", groupExpenseId > 0L)

        // Verify initial state: no reimbursement yet
        val before = db.groupExpenseDao().getById(groupExpenseId)!!
        assertEquals("Initial reimbursed amount should be 0.0", 0.0, before.reimbursedAmount, 0.001)
        assertTrue("Expense should be reimbursable", before.isReimbursable)
        assertNull("Expense should not be settled yet", before.settledAt)

        // WHEN: member 2 (Bob) pays member 1 (Alice) a reimbursement of €30
        val updated = before.copy(
            reimbursedAmount = 30.0 // Bob paid back his share
        )
        db.groupExpenseDao().update(updated)

        // THEN: the group expense records the reimbursement
        val saved = db.groupExpenseDao().getById(groupExpenseId)
        assertNotNull("GroupExpense should still exist", saved)
        assertEquals(
            "reimbursedAmount should now be 30.0",
            30.0,
            saved!!.reimbursedAmount,
            0.001
        )
        assertTrue("isReimbursable should remain true", saved.isReimbursable)

        // THEN: the payer's outstanding balance has decreased by €30
        //   (original was 90 owed, now 30 has been reimbursed → 60 still owed)
        val outstanding = saved.totalAmount - saved.reimbursedAmount
        assertEquals("Outstanding balance for Alice should be €60.00", 60.0, outstanding, 0.001)

        // THEN: total expense count in the group is unchanged (still 1)
        val groupExpenses = db.groupExpenseDao().getExpensesForGroupOnce(seed.groupId)
        assertEquals("Should still have 1 group expense", 1, groupExpenses.size)

        // THEN: the member who reimbursed (Bob) is tracked via the reimbursement
        //   on the expense — in real flow a settlement record would also be created,
        //   but at the DB-contract level the reimbursedAmount field is the key signal.
        assertEquals("Total paid by Alice should be 90.0", 90.0, groupExpenses[0].totalAmount, 0.001)
        assertEquals(
            "Alice's remaining receivable is €60",
            60.0,
            groupExpenses[0].totalAmount - groupExpenses[0].reimbursedAmount,
            0.001
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: group expense flagged as shared
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `group expense flagged as shared`() = runTest {
        // GIVEN: a group with 3 members and a group expense
        val seed = createGroupWithMembers("Dinner Group", listOf("Alice", "Bob", "Charlie"))

        // Create an Expense with isSharedExpense = true and myShareAmount set
        val expense = Expense(
            amount = 45.00,
            currency = "EUR",
            merchant = "Group Lunch",
            transactionType = TransactionType.PURCHASE,
            date = timeProvider.now(),
            isSharedExpense = true,
            myShareAmount = 15.0, // 45 / 3
            source = "group_scenario_test"
        )
        val expenseId = db.expenseDao().insert(expense)
        assertTrue("Expense ID should be positive", expenseId > 0L)

        val groupExpense = GroupExpense(
            groupId = seed.groupId,
            expenseId = expenseId,
            paidById = seed.memberIds[0], // Alice paid
            date = timeProvider.now(),
            description = "Group lunch",
            totalAmount = 45.0,
            splitType = SplitType.EQUAL
        )
        db.groupExpenseDao().insert(groupExpense)

        // WHEN: querying the expense via the expense DAO
        val savedExpense = db.expenseDao().getById(expenseId)

        // THEN: isSharedExpense flag is correctly set to true on the Expense row
        assertNotNull("Expense should exist", savedExpense)
        assertTrue("isSharedExpense should be true", savedExpense!!.isSharedExpense)
        assertEquals(
            "myShareAmount should be 15.0 (45/3)",
            15.0,
            savedExpense.myShareAmount!!,
            0.001
        )

        // THEN: the effective amount (for budget calculation) is 15.0 (the user's share)
        assertEquals(
            "effectiveAmount should equal myShareAmount",
            15.0,
            savedExpense.effectiveAmount,
            0.001
        )

        // THEN: groupId is set on the GroupExpense record linking the expense to the group
        val groupExpenses = db.groupExpenseDao().getExpensesForGroupOnce(seed.groupId)
        assertEquals("Should have exactly 1 group expense", 1, groupExpenses.size)
        assertEquals("groupId should match", seed.groupId, groupExpenses[0].groupId)
        assertEquals("expenseId should match", expenseId, groupExpenses[0].expenseId)

        // THEN: querying by expenseId also returns the correct group link
        val groupLink = db.groupExpenseDao().getGroupExpenseForExpense(expenseId)
        assertNotNull("GroupExpense link should be found by expenseId", groupLink)
        assertEquals("groupId should match", seed.groupId, groupLink!!.groupId)
        assertEquals("expenseId should match", expenseId, groupLink.expenseId)
    }
}
