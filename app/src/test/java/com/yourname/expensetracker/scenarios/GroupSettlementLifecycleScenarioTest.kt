package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.GroupSettlementEntity
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests for the group expense settlement lifecycle.
 *
 * Validates that groups with members can be created and persisted, expenses
 * can be linked with correct split types, and settlements can be recorded
 * and retrieved with the expected amount, currency, and member references.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GroupSettlementLifecycleScenarioTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Create group with members persists correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `create group with members persists correctly`() = runTest {
        // GIVEN: a new expense group "Dinner" with EUR as default currency
        val now = dateMs(2026, 5, 1)
        val groupId = db.expenseGroupDao().insert(
            ExpenseGroup(
                name = "Dinner",
                description = "Team dinner",
                defaultCurrency = "EUR",
                isActive = true,
                createdAt = now,
                createdBy = "me"
            )
        )
        assertTrue("Group ID should be positive", groupId > 0L)

        // AND: 3 members added to the group
        val memberIds = listOf(
            db.groupMemberDao().insert(
                GroupMember(groupId = groupId, name = "Alice", joinedAt = now)
            ),
            db.groupMemberDao().insert(
                GroupMember(groupId = groupId, name = "Bob", joinedAt = now)
            ),
            db.groupMemberDao().insert(
                GroupMember(groupId = groupId, name = "Charlie", joinedAt = now)
            )
        )
        assertTrue("All member IDs should be positive", memberIds.all { it > 0L })

        // WHEN: reading back the group and members
        val group = db.expenseGroupDao().getById(groupId)

        // THEN: group exists with correct properties
        assertNotNull("Group should exist", group)
        assertEquals("Group name should be 'Dinner'", "Dinner", group!!.name)
        assertEquals("Group currency should be EUR", "EUR", group.defaultCurrency)
        assertTrue("Group should be active", group.isActive)

        // AND: member count = 3
        val members = db.groupMemberDao().getAllForGroup(groupId)
        assertEquals("Should have exactly 3 members", 3, members.size)
        assertEquals("Member count via DAO should be 3", 3, db.groupMemberDao().getMemberCount(groupId))

        // AND: all member names match
        val memberNames = members.map { it.name }.sorted()
        assertEquals(listOf("Alice", "Bob", "Charlie"), memberNames)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Add expense to group links correctly
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `add expense to group links correctly`() = runTest {
        // GIVEN: an existing group with 3 members
        val now = dateMs(2026, 5, 1)
        val groupId = db.expenseGroupDao().insert(
            ExpenseGroup(
                name = "Dinner",
                description = "Team dinner",
                defaultCurrency = "EUR",
                isActive = true,
                createdAt = now,
                createdBy = "me"
            )
        )
        val aliceId = db.groupMemberDao().insert(
            GroupMember(groupId = groupId, name = "Alice", joinedAt = now)
        )
        db.groupMemberDao().insert(
            GroupMember(groupId = groupId, name = "Bob", joinedAt = now)
        )
        db.groupMemberDao().insert(
            GroupMember(groupId = groupId, name = "Charlie", joinedAt = now)
        )

        // WHEN: adding a group expense of €90 with EQUAL split, paid by Alice
        val groupExpenseId = db.groupExpenseDao().insert(
            GroupExpense(
                groupId = groupId,
                expenseId = null,
                paidById = aliceId,
                date = now,
                description = "Dinner at Italian restaurant",
                totalAmount = 90.0,
                currency = "EUR",
                splitType = SplitType.EQUAL
            )
        )
        assertTrue("Group expense ID should be positive", groupExpenseId > 0L)

        // THEN: expense is linked to the group
        val fetched = db.groupExpenseDao().getById(groupExpenseId)
        assertNotNull("Group expense should exist", fetched)
        assertEquals("Group expense should reference correct group", groupId, fetched!!.groupId)
        assertEquals("Total amount should be 90.0", 90.0, fetched.totalAmount, 0.001)
        assertEquals("Currency should be EUR", "EUR", fetched.currency)
        assertEquals("Split type should be EQUAL", SplitType.EQUAL, fetched.splitType)
        assertEquals("Paid by should be Alice", aliceId, fetched.paidById)

        // AND: expense count for the group is 1
        assertEquals("Group should have 1 expense", 1, db.groupExpenseDao().getExpenseCount(groupId))

        // AND: expense count paid by Alice is 1
        assertEquals("Alice should have paid 1 expense", 1,
            db.groupExpenseDao().countExpensesPaidByMember(groupId, aliceId))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Settlement recorded and retrievable
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `settlement recorded and retrievable`() = runTest {
        // GIVEN: an existing group with 3 members
        val now = dateMs(2026, 5, 1)
        val groupId = db.expenseGroupDao().insert(
            ExpenseGroup(
                name = "Dinner",
                description = "Team dinner",
                defaultCurrency = "EUR",
                isActive = true,
                createdAt = now,
                createdBy = "me"
            )
        )
        val aliceId = db.groupMemberDao().insert(
            GroupMember(groupId = groupId, name = "Alice", joinedAt = now)
        )
        val bobId = db.groupMemberDao().insert(
            GroupMember(groupId = groupId, name = "Bob", joinedAt = now)
        )
        db.groupMemberDao().insert(
            GroupMember(groupId = groupId, name = "Charlie", joinedAt = now)
        )

        // WHEN: inserting a settlement — Bob pays Alice €30
        val settlementId = db.groupSettlementDao().insert(
            GroupSettlementEntity(
                groupId = groupId,
                fromMemberId = bobId,
                toMemberId = aliceId,
                amount = 30.0,
                currency = "EUR",
                createdAt = now,
                status = "RECORDED",
                notes = "Dinner share"
            )
        )
        assertTrue("Settlement ID should be positive", settlementId > 0L)

        // THEN: settlement exists and is retrievable for the group
        val settlements = db.groupSettlementDao().getSettlementsForGroup(groupId)
        assertEquals("Should have exactly 1 settlement", 1, settlements.size)

        val settlement = settlements.first()
        assertEquals("Settlement amount should be 30.0", 30.0, settlement.amount, 0.001)
        assertEquals("Settlement currency should be EUR", "EUR", settlement.currency)
        assertEquals("From member should be Bob", bobId, settlement.fromMemberId)
        assertEquals("To member should be Alice", aliceId, settlement.toMemberId)
        assertEquals("Status should be RECORDED", "RECORDED", settlement.status)
        assertEquals("Notes should match", "Dinner share", settlement.notes)
        assertEquals("Group ID should match", groupId, settlement.groupId)
    }
}
