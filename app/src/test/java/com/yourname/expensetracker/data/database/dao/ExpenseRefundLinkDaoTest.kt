package com.yourname.expensetracker.data.database.dao

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ExpenseRefundLink
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Refund netting S1 (INV-1, INV-4, INV-24): persistence contract of [ExpenseRefundLinkDao] on a real
 * in-memory Room database.
 *
 * - insert / getById / getByRefund / getByPurchase / getForExpense (both sides) / counts;
 * - read order is createdAt ASC then id ASC;
 * - UNIQUE (refundExpenseId, purchaseExpenseId): the identical pair is rejected, never silently replaced;
 *   a purchase may carry many refunds and a refund row may be linked twice to different purchases at the
 *   schema level (the one-purchase limit of v1 is a coordinator rule, not a schema rule);
 * - both foreign keys: unknown expense ids are rejected, and deleting either expense removes its links.
 *
 * Expenses are seeded with raw SQL so the test does not depend on the lifecycle write path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpenseRefundLinkDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ExpenseRefundLinkDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.expenseRefundLinkDao()
        // P1..P3 purchases, D1..D3 deposits (ids 1..3 purchases, 11..13 deposits), 99 unrelated.
        listOf(1L, 2L, 3L).forEach { seedExpense(it, "PURCHASE") }
        listOf(11L, 12L, 13L).forEach { seedExpense(it, "DEPOSIT") }
        seedExpense(99L, "PURCHASE")
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun seedExpense(id: Long, type: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine) " +
                "VALUES ($id, 100.0, 'EUR', 'M$id', 20000, '$type', 0)"
        )
    }

    private fun deleteExpense(id: Long) {
        database.openHelper.writableDatabase.execSQL("DELETE FROM expenses WHERE id = $id")
    }

    private fun link(
        refund: Long,
        purchase: Long,
        applied: Double = 40.0,
        createdAt: Long = 1_000L
    ) = ExpenseRefundLink(
        refundExpenseId = refund,
        purchaseExpenseId = purchase,
        appliedAmount = applied,
        currency = "EUR",
        createdAt = createdAt,
        source = "USER_CONFIRMED"
    )

    /** The plain @Insert (ABORT) surfaces a constraint violation; it never ignores or replaces. */
    private suspend fun assertInsertRejected(link: ExpenseRefundLink) {
        try {
            dao.insert(link)
        } catch (expected: SQLiteException) {
            return
        }
        fail("insert of $link must violate a constraint")
    }

    // ── insert / getById ─────────────────────────────────────────

    @Test
    fun insert_returns_a_generated_id_and_getById_round_trips_every_field() = runTest {
        val id = dao.insert(link(refund = 11, purchase = 1, applied = 33.33, createdAt = 4_242L))

        assertTrue(id > 0)
        assertEquals(
            ExpenseRefundLink(
                id = id,
                refundExpenseId = 11,
                purchaseExpenseId = 1,
                appliedAmount = 33.33,
                currency = "EUR",
                createdAt = 4_242L,
                source = "USER_CONFIRMED"
            ),
            dao.getById(id)
        )
    }

    @Test
    fun getById_of_an_unknown_id_is_null_not_a_default_row() = runTest {
        val id = dao.insert(link(refund = 11, purchase = 1))

        assertNull(dao.getById(id + 1000))
    }

    @Test
    fun deleteById_removes_exactly_one_link_and_reports_zero_for_a_missing_one() = runTest {
        val first = dao.insert(link(refund = 11, purchase = 1))
        val second = dao.insert(link(refund = 12, purchase = 2))

        assertEquals(1, dao.deleteById(first))
        assertNull(dao.getById(first))
        assertEquals(second, dao.getById(second)!!.id)
        // Boundary: deleting the same id again affects no row.
        assertEquals(0, dao.deleteById(first))
        assertEquals(1, dao.countAll())
    }

    // ── getByRefund / getByPurchase / ordering ───────────────────

    @Test
    fun getByRefund_returns_only_that_refund_side_ordered_by_createdAt_then_id() = runTest {
        val late = dao.insert(link(refund = 11, purchase = 1, createdAt = 3_000L))
        val earlyHigherId = dao.insert(link(refund = 11, purchase = 2, createdAt = 1_000L))
        val earlySameTimeNextId = dao.insert(link(refund = 11, purchase = 3, createdAt = 1_000L))
        dao.insert(link(refund = 12, purchase = 1, createdAt = 500L))

        assertEquals(
            listOf(earlyHigherId, earlySameTimeNextId, late),
            dao.getByRefund(11).map { it.id }
        )
        // A purchase id never matches the refund-side query.
        assertEquals(emptyList<ExpenseRefundLink>(), dao.getByRefund(1))
        assertEquals(emptyList<ExpenseRefundLink>(), dao.getByRefund(99))
    }

    @Test
    fun getByPurchase_returns_every_refund_of_a_purchase_ordered_and_excludes_other_purchases() = runTest {
        val second = dao.insert(link(refund = 12, purchase = 1, applied = 20.0, createdAt = 2_000L))
        val first = dao.insert(link(refund = 11, purchase = 1, applied = 30.0, createdAt = 1_000L))
        dao.insert(link(refund = 13, purchase = 2, createdAt = 1_500L))

        val result = dao.getByPurchase(1)

        assertEquals(listOf(first, second), result.map { it.id })
        assertEquals(listOf(30.0, 20.0), result.map { it.appliedAmount })
        // A refund id never matches the purchase-side query.
        assertEquals(emptyList<ExpenseRefundLink>(), dao.getByPurchase(11))
        assertEquals(emptyList<ExpenseRefundLink>(), dao.getByPurchase(99))
    }

    // ── getForExpense (both sides) / counts ──────────────────────

    @Test
    fun getForExpense_matches_either_side_once_per_link_in_createdAt_id_order() = runTest {
        // Expense 2 is the purchase of the first link and the refund side of the second (schema level).
        val asPurchase = dao.insert(link(refund = 11, purchase = 2, createdAt = 2_000L))
        val asRefund = dao.insert(link(refund = 2, purchase = 3, createdAt = 1_000L))
        val unrelated = dao.insert(link(refund = 12, purchase = 1, createdAt = 500L))

        val result = dao.getForExpense(2)

        assertEquals(listOf(asRefund, asPurchase), result.map { it.id })
        assertTrue(unrelated !in result.map { it.id })
        assertEquals(emptyList<ExpenseRefundLink>(), dao.getForExpense(99))
    }

    @Test
    fun getForExpense_of_a_deposit_and_of_a_purchase_returns_the_same_single_link() = runTest {
        val id = dao.insert(link(refund = 11, purchase = 1))

        assertEquals(listOf(id), dao.getForExpense(11).map { it.id })
        assertEquals(listOf(id), dao.getForExpense(1).map { it.id })
    }

    @Test
    fun countAll_and_countForExpense_count_links_not_expenses() = runTest {
        assertEquals(0, dao.countAll())
        assertEquals(0, dao.countForExpense(1))

        dao.insert(link(refund = 11, purchase = 1))
        dao.insert(link(refund = 12, purchase = 1))
        dao.insert(link(refund = 13, purchase = 2))

        assertEquals(3, dao.countAll())
        assertEquals(2, dao.countForExpense(1))
        assertEquals(1, dao.countForExpense(11))
        assertEquals(1, dao.countForExpense(2))
        assertEquals(0, dao.countForExpense(99))
    }

    // ── INV-4: uniqueness and cardinality at the schema level ────

    @Test
    fun INV4_identical_refund_purchase_pair_is_rejected_and_the_original_link_is_kept() = runTest {
        val original = dao.insert(link(refund = 11, purchase = 1, applied = 40.0))

        assertInsertRejected(link(refund = 11, purchase = 1, applied = 99.0))

        assertEquals(1, dao.countAll())
        assertEquals(40.0, dao.getById(original)!!.appliedAmount, 0.0)
    }

    @Test
    fun INV4_a_purchase_may_have_many_refunds_and_distinct_pairs_are_accepted() = runTest {
        val a = dao.insert(link(refund = 11, purchase = 1))
        val b = dao.insert(link(refund = 12, purchase = 1))
        val c = dao.insert(link(refund = 13, purchase = 1))
        // Control for the unique index: reversing the roles is a different (refund, purchase) pair.
        val reversed = dao.insert(link(refund = 1, purchase = 11))

        assertEquals(setOf(a, b, c), dao.getByPurchase(1).map { it.id }.toSet())
        assertTrue(reversed > 0)
        assertEquals(4, dao.countAll())
    }

    // ── Foreign keys ─────────────────────────────────────────────

    @Test
    fun a_link_to_a_missing_refund_or_purchase_expense_is_rejected() = runTest {
        assertInsertRejected(link(refund = 4040, purchase = 1))
        assertInsertRejected(link(refund = 11, purchase = 4040))
        assertEquals(0, dao.countAll())
    }

    @Test
    fun deleting_the_purchase_expense_cascades_its_links_only() = runTest {
        val gone1 = dao.insert(link(refund = 11, purchase = 1))
        val gone2 = dao.insert(link(refund = 12, purchase = 1))
        val kept = dao.insert(link(refund = 13, purchase = 2))

        deleteExpense(1)

        assertNull(dao.getById(gone1))
        assertNull(dao.getById(gone2))
        assertEquals(kept, dao.getById(kept)!!.id)
        assertEquals(1, dao.countAll())
    }

    @Test
    fun deleting_the_refund_expense_cascades_its_links_only() = runTest {
        val gone = dao.insert(link(refund = 11, purchase = 1))
        val kept1 = dao.insert(link(refund = 12, purchase = 1))
        val kept2 = dao.insert(link(refund = 13, purchase = 2))

        deleteExpense(11)

        assertNull(dao.getById(gone))
        assertEquals(setOf(kept1, kept2), setOf(dao.getById(kept1)!!.id, dao.getById(kept2)!!.id))
        assertEquals(2, dao.countAll())
        // The purchase the deleted refund pointed at is untouched.
        assertEquals(listOf(kept1), dao.getByPurchase(1).map { it.id })
    }

    @Test
    fun the_full_expense_wipe_cascades_every_link() = runTest {
        dao.insert(link(refund = 11, purchase = 1))
        dao.insert(link(refund = 12, purchase = 2))
        dao.insert(link(refund = 13, purchase = 3))
        assertEquals(3, dao.countAll())

        database.openHelper.writableDatabase.execSQL("DELETE FROM expenses")

        assertEquals(0, dao.countAll())
    }
}
