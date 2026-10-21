package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.GroupSettlementEntity
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario test for the group settlement lifecycle.
 *
 * Group creation with members and group-expense linking (group id, amount, currency,
 * split type, payer, expense count, per-member paid count) are covered by
 * [SharedExpenseGroupScenarioTest]; this class keeps the settlement round-trip, which
 * is the only place the group settlement DAO is exercised in scenarios.
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
    // Settlement recorded and retrievable
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
