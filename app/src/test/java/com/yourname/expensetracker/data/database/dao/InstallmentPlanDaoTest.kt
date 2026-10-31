package com.yourname.expensetracker.data.database.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
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
 * S2 persistence contract for [InstallmentPlanDao] on a real in-memory Room database.
 *
 * Covers the storage side of INV-2 (spendingExclusionReason column is covered by the
 * migration tests), INV-3 (one plan per purchase expense), INV-4 (one entry per linked
 * expense) and INV-12 (autoRecordConsumed never resets), plus the compare-and-set
 * semantics of every transition write. The DAO has no production callers in S2, so
 * these are DAO-level tests only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentPlanDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: InstallmentPlanDao

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())
        dao = database.installmentPlanDao()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
    }

    // ── plans: insert and uniqueness (INV-3) ─────────────────────

    @Test
    fun insertPlan_roundTrips_every_field_and_assigns_an_id() = runTest {
        val plan = plan(
            uuid = "uuid-roundtrip",
            purchaseExpenseId = 77L,
            lenderLabel = "Synthetic Lender",
            declaredTotal = 1200.50,
            fixedPaymentAmount = 100.04,
            categoryId = 5L,
            paymentMethod = PaymentMethod.CARD,
            statusReason = "USER_STOP",
            terminalAt = NOW + 5
        )

        val id = dao.insertPlan(plan)

        assertTrue(id > 0)
        assertEquals(plan.copy(id = id), dao.getPlanById(id))
        assertEquals(plan.copy(id = id), dao.getPlanByUuid("uuid-roundtrip"))
        assertEquals(plan.copy(id = id), dao.getPlanByPurchaseExpenseId(77L))
        assertNull(dao.getPlanById(id + 1000))
        assertNull(dao.getPlanByUuid("missing"))
        assertNull(dao.getPlanByPurchaseExpenseId(78L))
    }

    @Test
    fun insertPlan_duplicate_planUuid_throws_and_keeps_the_original_row() = runTest {
        val firstId = dao.insertPlan(plan(uuid = "dup-uuid", displayName = "original"))

        assertConstraintViolation("duplicate planUuid must be rejected") {
            dao.insertPlan(plan(uuid = "dup-uuid", displayName = "intruder", purchaseExpenseId = 9L))
        }

        val all = dao.getAllPlans()
        assertEquals(1, all.size)
        assertEquals(firstId, all.single().id)
        assertEquals("original", all.single().displayName)
    }

    @Test
    fun INV3_insertPlan_duplicate_purchaseExpenseId_throws() = runTest {
        dao.insertPlan(plan(uuid = "p-1", purchaseExpenseId = 42L))

        assertConstraintViolation("a second plan for the same purchase expense must be rejected") {
            dao.insertPlan(plan(uuid = "p-2", purchaseExpenseId = 42L))
        }

        assertEquals(1, dao.getAllPlans().size)
        assertEquals("p-1", dao.getPlanByPurchaseExpenseId(42L)?.planUuid)
    }

    @Test
    fun INV3_insertPlan_allows_many_standalone_plans_with_null_purchaseExpenseId() = runTest {
        dao.insertPlan(plan(uuid = "s-1", kind = KIND_STANDALONE, purchaseExpenseId = null))
        dao.insertPlan(plan(uuid = "s-2", kind = KIND_STANDALONE, purchaseExpenseId = null))
        dao.insertPlan(plan(uuid = "s-3", kind = KIND_STANDALONE, purchaseExpenseId = null))

        assertEquals(3, dao.getAllPlans().size)
    }

    @Test
    fun INV3_insertPlan_allows_different_purchase_expenses() = runTest {
        dao.insertPlan(plan(uuid = "p-a", purchaseExpenseId = 1L))
        dao.insertPlan(plan(uuid = "p-b", purchaseExpenseId = 2L))

        assertEquals(2, dao.getAllPlans().size)
    }

    @Test
    fun getPlansByStatus_filters_and_orders_by_createdAt_then_id() = runTest {
        val late = dao.insertPlan(plan(uuid = "late", createdAt = NOW + 20, status = "ACTIVE"))
        val early = dao.insertPlan(plan(uuid = "early", createdAt = NOW + 10, status = "ACTIVE"))
        dao.insertPlan(plan(uuid = "done", createdAt = NOW + 5, status = "COMPLETED"))

        assertEquals(listOf(early, late), dao.getPlansByStatus("ACTIVE").map { it.id })
        assertEquals(listOf("done"), dao.getPlansByStatus("COMPLETED").map { it.planUuid })
        assertTrue(dao.getPlansByStatus("CANCELLED").isEmpty())
    }

    // ── plans: compare-and-set status ────────────────────────────

    @Test
    fun updatePlanStatusIfCurrent_returns_1_and_applies_when_expected_status_matches() = runTest {
        val id = dao.insertPlan(plan(uuid = "cas-1", status = "ACTIVE", updatedAt = NOW))

        val rows = dao.updatePlanStatusIfCurrent(
            planId = id,
            expectedStatus = "ACTIVE",
            newStatus = "CANCELLED",
            statusReason = "USER_STOP",
            terminalAt = NOW + 100,
            now = NOW + 100
        )

        assertEquals(1, rows)
        val after = dao.getPlanById(id)!!
        assertEquals("CANCELLED", after.status)
        assertEquals("USER_STOP", after.statusReason)
        assertEquals(NOW + 100, after.terminalAt)
        assertEquals(NOW + 100, after.updatedAt)
    }

    @Test
    fun updatePlanStatusIfCurrent_returns_0_and_changes_nothing_when_status_differs() = runTest {
        val id = dao.insertPlan(plan(uuid = "cas-2", status = "ACTIVE", updatedAt = NOW))
        val before = dao.getPlanById(id)!!

        val rows = dao.updatePlanStatusIfCurrent(
            planId = id,
            expectedStatus = "COMPLETED",
            newStatus = "CANCELLED",
            statusReason = "USER_STOP",
            terminalAt = NOW + 100,
            now = NOW + 100
        )

        assertEquals(0, rows)
        assertEquals(before, dao.getPlanById(id))
    }

    @Test
    fun updatePlanStatusIfCurrent_second_call_with_the_stale_expectation_returns_0() = runTest {
        val id = dao.insertPlan(plan(uuid = "cas-3", status = "ACTIVE"))

        assertEquals(1, dao.updatePlanStatusIfCurrent(id, "ACTIVE", "COMPLETED", null, NOW + 1, NOW + 1))
        assertEquals(0, dao.updatePlanStatusIfCurrent(id, "ACTIVE", "CANCELLED", "USER_STOP", NOW + 2, NOW + 2))

        val after = dao.getPlanById(id)!!
        assertEquals("COMPLETED", after.status)
        assertNull(after.statusReason)
        assertEquals(NOW + 1, after.terminalAt)
    }

    @Test
    fun updatePlanStatusIfCurrent_returns_0_for_a_missing_plan() = runTest {
        assertEquals(0, dao.updatePlanStatusIfCurrent(999L, "ACTIVE", "CANCELLED", null, NOW, NOW))
    }

    @Test
    fun updatePlanStatusIfCurrent_can_clear_reason_and_terminalAt_on_reopen() = runTest {
        val id = dao.insertPlan(
            plan(uuid = "cas-4", status = "CANCELLED", statusReason = "USER_STOP", terminalAt = NOW + 1)
        )

        assertEquals(1, dao.updatePlanStatusIfCurrent(id, "CANCELLED", "ACTIVE", null, null, NOW + 9))

        val after = dao.getPlanById(id)!!
        assertEquals("ACTIVE", after.status)
        assertNull(after.statusReason)
        assertNull(after.terminalAt)
        assertEquals(NOW + 9, after.updatedAt)
    }

    // ── entries: insert and uniqueness ───────────────────────────

    @Test
    fun insertEntries_returns_ids_in_order_and_reads_back_sorted_by_dueDate_then_seq() = runTest {
        val planId = dao.insertPlan(plan(uuid = "e-1"))

        val ids = dao.insertEntries(
            listOf(
                entry(planId, seq = 2, dueDate = DAY + 30),
                entry(planId, seq = 1, dueDate = DAY),
                entry(planId, seq = 3, dueDate = DAY + 60, scheduledAmount = 33.34)
            )
        )

        assertEquals(3, ids.size)
        assertEquals(3, ids.toSet().size)
        assertEquals(listOf(1, 2, 3), dao.getEntriesForPlan(planId).map { it.seq })
        assertEquals(33.34, dao.getEntryById(ids[2])!!.scheduledAmount, 0.0)
        assertNull(dao.getEntryById(ids.last() + 1000))
    }

    @Test
    fun insertEntries_duplicate_planId_kind_seq_throws() = runTest {
        val planId = dao.insertPlan(plan(uuid = "e-2"))
        dao.insertEntries(listOf(entry(planId, seq = 1)))

        assertConstraintViolation("duplicate (planId, kind, seq) must be rejected") {
            dao.insertEntries(listOf(entry(planId, seq = 1, dueDate = DAY + 99)))
        }

        assertEquals(1, dao.getEntriesForPlan(planId).size)
    }

    @Test
    fun insertEntries_duplicate_inside_one_batch_rolls_the_whole_batch_back() = runTest {
        val planId = dao.insertPlan(plan(uuid = "e-3"))

        assertConstraintViolation("a batch containing a duplicate key must fail") {
            dao.insertEntries(
                listOf(
                    entry(planId, seq = 1),
                    entry(planId, seq = 2),
                    entry(planId, seq = 2, dueDate = DAY + 1)
                )
            )
        }

        assertTrue("no partial batch may survive", dao.getEntriesForPlan(planId).isEmpty())
    }

    @Test
    fun insertEntries_same_seq_is_allowed_for_a_different_kind_or_a_different_plan() = runTest {
        val planA = dao.insertPlan(plan(uuid = "e-4a"))
        val planB = dao.insertPlan(plan(uuid = "e-4b"))

        dao.insertEntries(
            listOf(
                entry(planA, seq = 1, kind = "REGULAR"),
                entry(planA, seq = 1, kind = "PAYOFF"),
                entry(planB, seq = 1, kind = "REGULAR")
            )
        )

        assertEquals(2, dao.getEntriesForPlan(planA).size)
        assertEquals(1, dao.getEntriesForPlan(planB).size)
    }

    @Test
    fun insertEntries_for_a_missing_plan_violates_the_foreign_key() = runTest {
        assertConstraintViolation("an entry must reference an existing plan") {
            dao.insertEntries(listOf(entry(planId = 12345L, seq = 1)))
        }
    }

    @Test
    fun INV4_insertEntries_duplicate_linkedExpenseId_throws() = runTest {
        val planId = dao.insertPlan(plan(uuid = "inv4-a"))
        dao.insertEntries(listOf(entry(planId, seq = 1, status = "PAID", linkedExpenseId = 500L, linkOrigin = "USER_LINKED")))

        assertConstraintViolation("one expense may be linked to at most one entry") {
            dao.insertEntries(listOf(entry(planId, seq = 2, status = "PAID", linkedExpenseId = 500L, linkOrigin = "USER_LINKED")))
        }

        assertEquals(1, dao.getEntriesForPlan(planId).size)
        assertEquals(1L, dao.getEntryByLinkedExpenseId(500L)!!.seq.toLong())
    }

    @Test
    fun INV4_insertEntries_allows_many_unlinked_entries_with_null_linkedExpenseId() = runTest {
        val planId = dao.insertPlan(plan(uuid = "inv4-b"))

        dao.insertEntries((1..5).map { entry(planId, seq = it, linkedExpenseId = null) })

        assertEquals(5, dao.getEntriesForPlan(planId).size)
        assertNull(dao.getEntryByLinkedExpenseId(0L))
    }

    @Test
    fun INV4_the_unique_linkedExpenseId_spans_different_plans() = runTest {
        val planA = dao.insertPlan(plan(uuid = "inv4-c"))
        val planB = dao.insertPlan(plan(uuid = "inv4-d"))
        dao.insertEntries(listOf(entry(planA, seq = 1, status = "PAID", linkedExpenseId = 600L, linkOrigin = "AUTO_RECORDED")))

        assertConstraintViolation("the same expense must not be linked from two plans") {
            dao.insertEntries(listOf(entry(planB, seq = 1, status = "PAID", linkedExpenseId = 600L, linkOrigin = "AUTO_RECORDED")))
        }
    }

    // ── entries: claimEntryForExpense (SCHEDULED -> PAID) ────────

    @Test
    fun claimEntryForExpense_moves_a_scheduled_unlinked_entry_to_paid_and_returns_1() = runTest {
        val entryId = scheduledEntry("claim-1")

        val rows = dao.claimEntryForExpense(entryId, expenseId = 900L, linkOrigin = "USER_CONFIRMED", markAutoRecordConsumed = false, now = NOW + 7)

        assertEquals(1, rows)
        val after = dao.getEntryById(entryId)!!
        assertEquals("PAID", after.status)
        assertNull(after.statusReason)
        assertEquals(900L, after.linkedExpenseId)
        assertEquals("USER_CONFIRMED", after.linkOrigin)
        assertFalse(after.autoRecordConsumed)
        assertEquals(0, after.recordGeneration)
        assertEquals(NOW + 7, after.statusChangedAt)
        assertEquals(entryId, dao.getEntryByLinkedExpenseId(900L)!!.id)
    }

    @Test
    fun claimEntryForExpense_second_claim_returns_0_and_keeps_the_first_link() = runTest {
        val entryId = scheduledEntry("claim-2")

        assertEquals(1, dao.claimEntryForExpense(entryId, 901L, "AUTO_RECORDED", true, NOW + 1))
        assertEquals(0, dao.claimEntryForExpense(entryId, 902L, "USER_CONFIRMED", false, NOW + 2))

        val after = dao.getEntryById(entryId)!!
        assertEquals("PAID", after.status)
        assertEquals(901L, after.linkedExpenseId)
        assertEquals("AUTO_RECORDED", after.linkOrigin)
        assertEquals(NOW + 1, after.statusChangedAt)
        assertNull(dao.getEntryByLinkedExpenseId(902L))
    }

    @Test
    fun claimEntryForExpense_returns_0_when_the_entry_is_not_scheduled() = runTest {
        val planId = dao.insertPlan(plan(uuid = "claim-3"))
        val ids = dao.insertEntries(
            listOf(
                entry(planId, seq = 1, status = "SKIPPED"),
                entry(planId, seq = 2, status = "CANCELLED"),
                entry(planId, seq = 3, status = "PAID_BEFORE_TRACKING"),
                entry(planId, seq = 4, status = "REVERSED")
            )
        )

        ids.forEachIndexed { index, id ->
            assertEquals("entry ${index + 1}", 0, dao.claimEntryForExpense(id, 910L + index, "USER_LINKED", false, NOW + 1))
        }

        assertTrue(dao.getEntriesForPlan(planId).all { it.linkedExpenseId == null })
        assertEquals(listOf("SKIPPED", "CANCELLED", "PAID_BEFORE_TRACKING", "REVERSED"), dao.getEntriesForPlan(planId).map { it.status })
    }

    @Test
    fun claimEntryForExpense_returns_0_when_a_scheduled_entry_is_already_linked() = runTest {
        val planId = dao.insertPlan(plan(uuid = "claim-4"))
        val id = dao.insertEntries(
            listOf(entry(planId, seq = 1, status = "SCHEDULED", linkedExpenseId = 920L, linkOrigin = "USER_LINKED"))
        ).single()

        assertEquals(0, dao.claimEntryForExpense(id, 921L, "USER_CONFIRMED", false, NOW + 1))

        val after = dao.getEntryById(id)!!
        assertEquals("SCHEDULED", after.status)
        assertEquals(920L, after.linkedExpenseId)
        assertEquals("USER_LINKED", after.linkOrigin)
    }

    @Test
    fun claimEntryForExpense_returns_0_for_a_missing_entry() = runTest {
        assertEquals(0, dao.claimEntryForExpense(4242L, 930L, "USER_CONFIRMED", false, NOW))
    }

    @Test
    fun INV12_claimEntryForExpense_latches_autoRecordConsumed_only_when_asked() = runTest {
        val auto = scheduledEntry("claim-5a")
        val manual = scheduledEntry("claim-5b")

        assertEquals(1, dao.claimEntryForExpense(auto, 940L, "AUTO_RECORDED", true, NOW + 1))
        assertEquals(1, dao.claimEntryForExpense(manual, 941L, "USER_CONFIRMED", false, NOW + 1))

        assertTrue(dao.getEntryById(auto)!!.autoRecordConsumed)
        assertFalse(dao.getEntryById(manual)!!.autoRecordConsumed)
    }

    @Test
    fun INV12_autoRecordConsumed_never_resets_through_claim_release_claim() = runTest {
        val id = scheduledEntry("claim-6")

        assertEquals(1, dao.claimEntryForExpense(id, 950L, "AUTO_RECORDED", true, NOW + 1))
        assertTrue(dao.getEntryById(id)!!.autoRecordConsumed)

        assertEquals(1, dao.releaseEntryLink(id, 950L, "LINKED_EXPENSE_GONE", NOW + 2))
        assertTrue("release must not reset the latch", dao.getEntryById(id)!!.autoRecordConsumed)

        // A later manual claim passes markAutoRecordConsumed = false; the latch must stay set.
        assertEquals(1, dao.claimEntryForExpense(id, 951L, "USER_CONFIRMED", false, NOW + 3))
        assertTrue("a claim with false must not clear the latch", dao.getEntryById(id)!!.autoRecordConsumed)
    }

    @Test
    fun INV4_claimEntryForExpense_with_an_expense_already_linked_elsewhere_throws_and_leaves_the_entry_scheduled() = runTest {
        val first = scheduledEntry("claim-7a")
        val second = scheduledEntry("claim-7b")
        assertEquals(1, dao.claimEntryForExpense(first, 960L, "USER_LINKED", false, NOW + 1))

        assertConstraintViolation("claiming a second entry with the same expense must violate the unique index") {
            dao.claimEntryForExpense(second, 960L, "USER_LINKED", false, NOW + 2)
        }

        val after = dao.getEntryById(second)!!
        assertEquals("SCHEDULED", after.status)
        assertNull(after.linkedExpenseId)
        assertEquals(first, dao.getEntryByLinkedExpenseId(960L)!!.id)
    }

    // ── entries: releaseEntryLink (PAID -> SCHEDULED) ────────────

    @Test
    fun releaseEntryLink_moves_paid_to_scheduled_clears_the_link_and_bumps_recordGeneration() = runTest {
        val id = scheduledEntry("rel-1")
        assertEquals(1, dao.claimEntryForExpense(id, 970L, "AUTO_RECORDED", true, NOW + 1))

        val rows = dao.releaseEntryLink(id, 970L, "LINKED_EXPENSE_GONE", NOW + 5)

        assertEquals(1, rows)
        val after = dao.getEntryById(id)!!
        assertEquals("SCHEDULED", after.status)
        assertEquals("LINKED_EXPENSE_GONE", after.statusReason)
        assertNull(after.linkedExpenseId)
        assertNull(after.linkOrigin)
        assertEquals(1, after.recordGeneration)
        assertTrue(after.autoRecordConsumed)
        assertEquals(NOW + 5, after.statusChangedAt)
        assertNull(dao.getEntryByLinkedExpenseId(970L))
    }

    @Test
    fun releaseEntryLink_accepts_a_null_reason_and_bumps_the_generation_each_cycle() = runTest {
        val id = scheduledEntry("rel-2")

        for (cycle in 1..3) {
            assertEquals(1, dao.claimEntryForExpense(id, 980L + cycle, "USER_CONFIRMED", false, NOW + cycle))
            assertEquals(1, dao.releaseEntryLink(id, 980L + cycle, null, NOW + 10 + cycle))
            assertEquals(cycle, dao.getEntryById(id)!!.recordGeneration)
        }
        assertNull(dao.getEntryById(id)!!.statusReason)
    }

    @Test
    fun releaseEntryLink_returns_0_and_changes_nothing_when_the_entry_is_not_paid() = runTest {
        val scheduled = scheduledEntry("rel-3")
        val before = dao.getEntryById(scheduled)!!

        assertEquals(0, dao.releaseEntryLink(scheduled, 975L, "LINKED_EXPENSE_GONE", NOW + 5))
        assertEquals(before, dao.getEntryById(scheduled))
        assertEquals(0, dao.getEntryById(scheduled)!!.recordGeneration)
        assertEquals(0, dao.releaseEntryLink(31337L, 975L, null, NOW))
    }

    @Test
    fun releaseEntryLink_with_the_wrong_expectedExpenseId_returns_0_and_changes_nothing() = runTest {
        val id = scheduledEntry("rel-3b")
        assertEquals(1, dao.claimEntryForExpense(id, 976L, "USER_CONFIRMED", false, NOW + 1))
        val before = dao.getEntryById(id)!!

        // A stale reconcile for deleted expense 977 must not release the entry now linked to 976.
        assertEquals(0, dao.releaseEntryLink(id, 977L, "LINKED_EXPENSE_GONE", NOW + 5))

        assertEquals(before, dao.getEntryById(id))
        assertEquals("PAID", dao.getEntryById(id)!!.status)
        assertEquals(976L, dao.getEntryById(id)!!.linkedExpenseId)
        assertEquals(0, dao.getEntryById(id)!!.recordGeneration)
        assertEquals(id, dao.getEntryByLinkedExpenseId(976L)!!.id)
    }

    @Test
    fun releaseEntryLink_second_release_is_a_no_op_and_does_not_bump_the_generation_twice() = runTest {
        val id = scheduledEntry("rel-4")
        assertEquals(1, dao.claimEntryForExpense(id, 990L, "USER_CONFIRMED", false, NOW + 1))

        assertEquals(1, dao.releaseEntryLink(id, 990L, "LINKED_EXPENSE_GONE", NOW + 2))
        assertEquals(0, dao.releaseEntryLink(id, 990L, "LINKED_EXPENSE_GONE", NOW + 3))

        val after = dao.getEntryById(id)!!
        assertEquals(1, after.recordGeneration)
        assertEquals(NOW + 2, after.statusChangedAt)
    }

    @Test
    fun INV4_a_released_expense_id_can_be_claimed_by_another_entry() = runTest {
        val first = scheduledEntry("rel-5a")
        val second = scheduledEntry("rel-5b")
        assertEquals(1, dao.claimEntryForExpense(first, 995L, "USER_LINKED", false, NOW + 1))
        assertEquals(1, dao.releaseEntryLink(first, 995L, null, NOW + 2))

        assertEquals(1, dao.claimEntryForExpense(second, 995L, "USER_LINKED", false, NOW + 3))

        assertEquals(second, dao.getEntryByLinkedExpenseId(995L)!!.id)
        assertEquals("SCHEDULED", dao.getEntryById(first)!!.status)
    }

    // ── entries: updateEntryStatusIfCurrent ──────────────────────

    @Test
    fun updateEntryStatusIfCurrent_applies_only_when_expected_status_matches() = runTest {
        val id = scheduledEntry("upd-1")

        assertEquals(0, dao.updateEntryStatusIfCurrent(id, "PAID", "SKIPPED", "USER_SKIP", NOW + 1))
        assertEquals("SCHEDULED", dao.getEntryById(id)!!.status)
        assertNull(dao.getEntryById(id)!!.statusReason)

        assertEquals(1, dao.updateEntryStatusIfCurrent(id, "SCHEDULED", "SKIPPED", "USER_SKIP", NOW + 2))
        val after = dao.getEntryById(id)!!
        assertEquals("SKIPPED", after.status)
        assertEquals("USER_SKIP", after.statusReason)
        assertEquals(NOW + 2, after.statusChangedAt)

        assertEquals(0, dao.updateEntryStatusIfCurrent(id, "SCHEDULED", "CANCELLED", null, NOW + 3))
        assertEquals("SKIPPED", dao.getEntryById(id)!!.status)
        assertEquals(0, dao.updateEntryStatusIfCurrent(8888L, "SCHEDULED", "SKIPPED", null, NOW))
    }

    @Test
    fun updateEntryStatusIfCurrent_to_PAID_returns_0_and_leaves_the_entry_unlinked() = runTest {
        val id = scheduledEntry("upd-2")
        val before = dao.getEntryById(id)!!

        assertEquals(0, dao.updateEntryStatusIfCurrent(id, "SCHEDULED", "PAID", null, NOW + 1))

        assertEquals(before, dao.getEntryById(id))
        assertEquals("SCHEDULED", dao.getEntryById(id)!!.status)
        assertNull(dao.getEntryById(id)!!.linkedExpenseId)
    }

    @Test
    fun updateEntryStatusIfCurrent_from_PAID_returns_0_and_keeps_the_link() = runTest {
        val id = scheduledEntry("upd-3")
        assertEquals(1, dao.claimEntryForExpense(id, 996L, "USER_CONFIRMED", false, NOW + 1))
        val before = dao.getEntryById(id)!!

        assertEquals(0, dao.updateEntryStatusIfCurrent(id, "PAID", "SKIPPED", "USER_SKIP", NOW + 2))
        assertEquals(0, dao.updateEntryStatusIfCurrent(id, "PAID", "SCHEDULED", null, NOW + 3))

        assertEquals(before, dao.getEntryById(id))
        assertEquals("PAID", dao.getEntryById(id)!!.status)
        assertEquals(996L, dao.getEntryById(id)!!.linkedExpenseId)
    }

    // ── cascade ──────────────────────────────────────────────────

    @Test
    fun deletePlanById_cascades_its_entries_and_leaves_other_plans_untouched() = runTest {
        val doomed = dao.insertPlan(plan(uuid = "del-1"))
        val survivor = dao.insertPlan(plan(uuid = "del-2"))
        dao.insertEntries(
            listOf(
                entry(doomed, seq = 1, status = "PAID", linkedExpenseId = 1001L, linkOrigin = "USER_LINKED"),
                entry(doomed, seq = 2),
                entry(survivor, seq = 1)
            )
        )

        assertEquals(1, dao.deletePlanById(doomed))

        assertNull(dao.getPlanById(doomed))
        assertTrue(dao.getEntriesForPlan(doomed).isEmpty())
        assertNull("the cascaded entry's expense link must be gone", dao.getEntryByLinkedExpenseId(1001L))
        assertNotNull(dao.getPlanById(survivor))
        assertEquals(1, dao.getEntriesForPlan(survivor).size)
    }

    @Test
    fun deletePlanById_returns_0_for_a_missing_plan() = runTest {
        dao.insertPlan(plan(uuid = "del-3"))

        assertEquals(0, dao.deletePlanById(4040L))
        assertEquals(1, dao.getAllPlans().size)
    }

    // ── reads: due-date boundary and counts ──────────────────────

    @Test
    fun getEntriesByStatusDueOnOrBefore_includes_the_equal_dueDate_and_excludes_the_next_day() = runTest {
        val planId = dao.insertPlan(plan(uuid = "due-1"))
        dao.insertEntries(
            listOf(
                entry(planId, seq = 1, dueDate = CUTOFF - 1),
                entry(planId, seq = 2, dueDate = CUTOFF),
                entry(planId, seq = 3, dueDate = CUTOFF + 1)
            )
        )

        val due = dao.getEntriesByStatusDueOnOrBefore("SCHEDULED", CUTOFF)

        assertEquals(listOf(1, 2), due.map { it.seq })
        assertTrue(due.any { it.dueDate == CUTOFF })
        assertTrue(due.none { it.dueDate > CUTOFF })
    }

    @Test
    fun getEntriesByStatusDueOnOrBefore_filters_by_status_and_returns_oldest_first_across_plans() = runTest {
        val planA = dao.insertPlan(plan(uuid = "due-2a"))
        val planB = dao.insertPlan(plan(uuid = "due-2b"))
        dao.insertEntries(
            listOf(
                entry(planA, seq = 1, dueDate = CUTOFF),
                entry(planB, seq = 1, dueDate = CUTOFF - 10),
                entry(planA, seq = 2, dueDate = CUTOFF - 5, status = "SKIPPED"),
                entry(planB, seq = 2, dueDate = CUTOFF - 3, status = "PAID", linkedExpenseId = 1100L, linkOrigin = "USER_LINKED")
            )
        )

        val scheduled = dao.getEntriesByStatusDueOnOrBefore("SCHEDULED", CUTOFF)

        assertEquals(listOf(CUTOFF - 10, CUTOFF), scheduled.map { it.dueDate })
        assertEquals(listOf("SKIPPED"), dao.getEntriesByStatusDueOnOrBefore("SKIPPED", CUTOFF).map { it.status })
        assertTrue(dao.getEntriesByStatusDueOnOrBefore("SCHEDULED", CUTOFF - 11).isEmpty())
    }

    @Test
    fun countEntriesForPlanByStatus_counts_only_the_requested_plan_and_status() = runTest {
        val planA = dao.insertPlan(plan(uuid = "cnt-a"))
        val planB = dao.insertPlan(plan(uuid = "cnt-b"))
        dao.insertEntries(
            listOf(
                entry(planA, seq = 1),
                entry(planA, seq = 2),
                entry(planA, seq = 3, status = "SKIPPED"),
                entry(planB, seq = 1)
            )
        )

        assertEquals(2, dao.countEntriesForPlanByStatus(planA, "SCHEDULED"))
        assertEquals(1, dao.countEntriesForPlanByStatus(planA, "SKIPPED"))
        assertEquals(0, dao.countEntriesForPlanByStatus(planA, "PAID"))
        assertEquals(1, dao.countEntriesForPlanByStatus(planB, "SCHEDULED"))
        assertEquals(0, dao.countEntriesForPlanByStatus(7777L, "SCHEDULED"))
    }

    // ── S6b compare-and-set writes ───────────────────────────────

    @Test
    fun updatePaidEntryToReversedIfLinkedTo_reverses_a_paid_entry_and_keeps_the_link() = runTest {
        val id = scheduledEntry("rev-1")
        assertEquals(1, dao.claimEntryForExpense(id, 990L, "USER_CONFIRMED", false, NOW + 1))

        assertEquals(1, dao.updatePaidEntryToReversedIfLinkedTo(id, 990L, NOW + 5))

        val after = dao.getEntryById(id)!!
        assertEquals("REVERSED", after.status)
        assertEquals(990L, after.linkedExpenseId)
        assertNull(after.statusReason)
        assertEquals(NOW + 5, after.statusChangedAt)
        // Second reversal is a lost compare-and-set.
        assertEquals(0, dao.updatePaidEntryToReversedIfLinkedTo(id, 990L, NOW + 6))
    }

    @Test
    fun updatePaidEntryToReversedIfLinkedTo_returns_0_for_the_wrong_expense_or_a_non_paid_entry() = runTest {
        val paid = scheduledEntry("rev-2")
        assertEquals(1, dao.claimEntryForExpense(paid, 991L, "USER_CONFIRMED", false, NOW + 1))
        val paidBefore = dao.getEntryById(paid)!!

        assertEquals(0, dao.updatePaidEntryToReversedIfLinkedTo(paid, 992L, NOW + 5))
        assertEquals(paidBefore, dao.getEntryById(paid))

        val scheduled = scheduledEntry("rev-3")
        val scheduledBefore = dao.getEntryById(scheduled)!!
        assertEquals(0, dao.updatePaidEntryToReversedIfLinkedTo(scheduled, 991L, NOW + 5))
        assertEquals(scheduledBefore, dao.getEntryById(scheduled))
        assertEquals(0, dao.updatePaidEntryToReversedIfLinkedTo(31337L, 991L, NOW))
    }

    @Test
    fun updatePlanDetailsIfEditable_edits_every_field_of_an_active_plan_before_any_payment() = runTest {
        val id = dao.insertPlan(plan(uuid = "det-1", status = "ACTIVE"))
        dao.insertEntries(listOf(entry(id, seq = 1)))

        assertEquals(1, dao.updatePlanDetailsIfEditable(id, "Renamed", "Lender", 5L, "AUTO_RECORD", "USD", NOW + 9))

        val after = dao.getPlanById(id)!!
        assertEquals("Renamed", after.displayName)
        assertEquals("Lender", after.lenderLabel)
        assertEquals(5L, after.categoryId)
        assertEquals("AUTO_RECORD", after.fulfilmentMode)
        assertEquals("USD", after.currency)
        assertEquals(NOW + 9, after.updatedAt)
    }

    @Test
    fun updatePlanDetailsIfEditable_locks_the_currency_once_an_entry_is_paid_or_reversed() = runTest {
        val id = dao.insertPlan(plan(uuid = "det-2", status = "ACTIVE"))
        val paid = dao.insertEntries(listOf(entry(id, seq = 1), entry(id, seq = 2))).first()
        assertEquals(1, dao.claimEntryForExpense(paid, 993L, "USER_CONFIRMED", false, NOW + 1))
        val before = dao.getPlanById(id)!!

        assertEquals(0, dao.updatePlanDetailsIfEditable(id, "X", null, null, "CONFIRM", "USD", NOW + 9))
        assertEquals(before, dao.getPlanById(id))

        // A name edit that keeps the currency still applies.
        assertEquals(1, dao.updatePlanDetailsIfEditable(id, "X", null, null, "CONFIRM", "EUR", NOW + 9))
        assertEquals("X", dao.getPlanById(id)!!.displayName)

        // REVERSED locks the currency too.
        assertEquals(1, dao.updatePaidEntryToReversedIfLinkedTo(paid, 993L, NOW + 10))
        assertEquals(0, dao.updatePlanDetailsIfEditable(id, "X", null, null, "CONFIRM", "USD", NOW + 11))
        assertEquals("EUR", dao.getPlanById(id)!!.currency)
    }

    @Test
    fun updatePlanDetailsIfEditable_on_a_completed_plan_allows_name_lender_category_only() = runTest {
        val id = dao.insertPlan(plan(uuid = "det-3", status = "COMPLETED"))
        val before = dao.getPlanById(id)!!

        assertEquals(0, dao.updatePlanDetailsIfEditable(id, "X", null, null, "AUTO_RECORD", "EUR", NOW + 9))
        assertEquals(0, dao.updatePlanDetailsIfEditable(id, "X", null, null, "CONFIRM", "USD", NOW + 9))
        assertEquals(before, dao.getPlanById(id))

        assertEquals(1, dao.updatePlanDetailsIfEditable(id, "Renamed", "Lender", 5L, "CONFIRM", "EUR", NOW + 9))
        val after = dao.getPlanById(id)!!
        assertEquals("Renamed", after.displayName)
        assertEquals("Lender", after.lenderLabel)
        assertEquals(5L, after.categoryId)
        assertEquals("CONFIRM", after.fulfilmentMode)
        assertEquals("EUR", after.currency)
        assertEquals("COMPLETED", after.status)
    }

    @Test
    fun updatePlanDetailsIfEditable_returns_0_for_terminal_and_missing_plans() = runTest {
        for (status in listOf("CANCELLED", "REFUNDED", "PAID_OFF")) {
            val id = dao.insertPlan(plan(uuid = "det-$status", status = status))
            val before = dao.getPlanById(id)!!

            assertEquals(0, dao.updatePlanDetailsIfEditable(id, "X", null, null, "CONFIRM", "EUR", NOW + 9))
            assertEquals(before, dao.getPlanById(id))
        }
        assertEquals(0, dao.updatePlanDetailsIfEditable(31337L, "X", null, null, "CONFIRM", "EUR", NOW))
    }

    @Test
    fun updatePlanShapeIfActive_applies_to_an_active_plan_only() = runTest {
        val active = dao.insertPlan(plan(uuid = "shape-1", status = "ACTIVE"))
        assertEquals(1, dao.updatePlanShapeIfActive(active, 5, 20, NOW + 9))
        val after = dao.getPlanById(active)!!
        assertEquals(5, after.installmentCount)
        assertEquals(20, after.anchorDayOfMonth)
        assertEquals(NOW + 9, after.updatedAt)

        for (status in listOf("COMPLETED", "CANCELLED", "REFUNDED", "PAID_OFF")) {
            val id = dao.insertPlan(plan(uuid = "shape-$status", status = status))
            val before = dao.getPlanById(id)!!

            assertEquals(0, dao.updatePlanShapeIfActive(id, 5, 20, NOW + 9))
            assertEquals(before, dao.getPlanById(id))
        }
        assertEquals(0, dao.updatePlanShapeIfActive(31337L, 5, 20, NOW))
    }

    // ── batch id reads (S6c-2b) ──────────────────────────────────

    @Test
    fun getLinkedExpenseIds_returns_every_linked_expense_of_any_entry_status_and_skips_unlinked_entries() = runTest {
        val planA = dao.insertPlan(plan(uuid = "ids-a"))
        val planB = dao.insertPlan(plan(uuid = "ids-b", status = "CANCELLED"))
        dao.insertEntries(
            listOf(
                entry(planA, seq = 1, status = "PAID", linkedExpenseId = 501L, linkOrigin = "USER_LINKED"),
                entry(planA, seq = 2, status = "REVERSED", linkedExpenseId = 502L, linkOrigin = "USER_LINKED"),
                entry(planA, seq = 3, status = "SCHEDULED"),
                entry(planB, seq = 1, status = "PAID", linkedExpenseId = 503L, linkOrigin = "AUTO_RECORDED")
            )
        )

        val ids = dao.getLinkedExpenseIds()

        assertEquals(setOf(501L, 502L, 503L), ids.toSet())
        assertEquals("distinct ids, no duplicates", 3, ids.size)
    }

    @Test
    fun getLinkedExpenseIds_is_empty_when_there_are_no_plans_or_no_links() = runTest {
        assertTrue(dao.getLinkedExpenseIds().isEmpty())

        val planId = dao.insertPlan(plan(uuid = "ids-none"))
        dao.insertEntries(listOf(entry(planId, seq = 1), entry(planId, seq = 2)))

        assertTrue(dao.getLinkedExpenseIds().isEmpty())
    }

    @Test
    fun getLinkedExpenseIds_agrees_with_getEntryByLinkedExpenseId_for_every_expense() = runTest {
        val planId = dao.insertPlan(plan(uuid = "ids-agree"))
        dao.insertEntries(
            listOf(
                entry(planId, seq = 1, status = "PAID", linkedExpenseId = 601L, linkOrigin = "USER_LINKED"),
                entry(planId, seq = 2, status = "SCHEDULED")
            )
        )
        val linked = dao.getLinkedExpenseIds().toSet()

        for (expenseId in listOf(600L, 601L, 602L)) {
            val single = dao.getEntryByLinkedExpenseId(expenseId)
            assertEquals(
                "expense $expenseId: batch and single reads must agree",
                single != null,
                expenseId in linked
            )
        }
    }

    @Test
    fun getPlanPurchaseExpenseIds_returns_the_purchase_of_every_plan_status_and_skips_standalone_plans() = runTest {
        dao.insertPlan(plan(uuid = "pp-active", purchaseExpenseId = 701L, status = "ACTIVE"))
        dao.insertPlan(plan(uuid = "pp-cancelled", purchaseExpenseId = 702L, status = "CANCELLED"))
        dao.insertPlan(plan(uuid = "pp-refunded", purchaseExpenseId = 703L, status = "REFUNDED"))
        dao.insertPlan(plan(uuid = "pp-standalone", kind = KIND_STANDALONE, purchaseExpenseId = null))

        val ids = dao.getPlanPurchaseExpenseIds()

        assertEquals(setOf(701L, 702L, 703L), ids.toSet())
        assertEquals("distinct ids, no duplicates", 3, ids.size)
    }

    @Test
    fun getPlanPurchaseExpenseIds_is_empty_without_attached_plans_and_agrees_with_the_single_read() = runTest {
        assertTrue(dao.getPlanPurchaseExpenseIds().isEmpty())
        dao.insertPlan(plan(uuid = "pp-only-standalone", kind = KIND_STANDALONE, purchaseExpenseId = null))
        assertTrue(dao.getPlanPurchaseExpenseIds().isEmpty())

        dao.insertPlan(plan(uuid = "pp-one", purchaseExpenseId = 710L))
        val purchases = dao.getPlanPurchaseExpenseIds().toSet()
        for (expenseId in listOf(709L, 710L, 711L)) {
            val single = dao.getPlanByPurchaseExpenseId(expenseId)
            assertEquals(
                "expense $expenseId: batch and single reads must agree",
                single != null,
                expenseId in purchases
            )
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    private suspend fun scheduledEntry(uuid: String): Long {
        val planId = dao.insertPlan(plan(uuid = uuid))
        return dao.insertEntries(listOf(entry(planId, seq = 1))).single()
    }

    /**
     * Passes only when [block] throws [SQLiteConstraintException]. Any other throwable,
     * including a coroutine cancellation, propagates untouched.
     */
    private suspend fun assertConstraintViolation(message: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (_: SQLiteConstraintException) {
            return
        }
        fail(message)
    }

    private fun plan(
        uuid: String,
        kind: String = KIND_ATTACHED,
        purchaseExpenseId: Long? = null,
        displayName: String = "Synthetic plan",
        lenderLabel: String? = null,
        declaredTotal: Double? = 300.0,
        fixedPaymentAmount: Double? = null,
        categoryId: Long? = null,
        paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
        status: String = "ACTIVE",
        statusReason: String? = null,
        createdAt: Long = NOW,
        updatedAt: Long = NOW,
        terminalAt: Long? = null
    ) = InstallmentPlan(
        planUuid = uuid,
        kind = kind,
        purchaseExpenseId = purchaseExpenseId,
        displayName = displayName,
        lenderLabel = lenderLabel,
        currency = "EUR",
        splitMethod = "EQUAL_SPLIT",
        declaredTotal = declaredTotal,
        fixedPaymentAmount = fixedPaymentAmount,
        installmentCount = 3,
        frequency = "MONTHLY",
        firstDueDate = DAY,
        anchorDayOfMonth = 15,
        residualPlacement = "LAST",
        categoryId = categoryId,
        paymentMethod = paymentMethod,
        fulfilmentMode = "CONFIRM",
        declaredOnDate = DAY - 1,
        status = status,
        statusReason = statusReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
        terminalAt = terminalAt
    )

    private fun entry(
        planId: Long,
        seq: Int,
        kind: String = "REGULAR",
        dueDate: Long = DAY + seq,
        scheduledAmount: Double = 100.0,
        status: String = "SCHEDULED",
        linkedExpenseId: Long? = null,
        linkOrigin: String? = null
    ) = InstallmentEntry(
        planId = planId,
        seq = seq,
        kind = kind,
        dueDate = dueDate,
        scheduledAmount = scheduledAmount,
        status = status,
        linkedExpenseId = linkedExpenseId,
        linkOrigin = linkOrigin,
        statusChangedAt = NOW
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAY = 20_000L
        const val CUTOFF = 20_100L
        const val KIND_ATTACHED = "PURCHASE_ATTACHED"
        const val KIND_STANDALONE = "STANDALONE"
    }
}
