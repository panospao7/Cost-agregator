package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.SplitItemAssignment
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Unit tests for [SplitItemAssignmentDao.markAsPaid].
 *
 * Verifies that the exact caller-supplied timestamp (no wall clock, no
 * DAO-side rounding) is persisted into `paidAt`, and that only the targeted
 * assignment row is mutated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SplitItemAssignmentDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SplitItemAssignmentDao
    private lateinit var expenseDao: ExpenseDao

    @Before
    fun setup() = runTest {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.splitItemAssignmentDao()
        expenseDao = database.expenseDao()

        // Parent row required by the expenseId FK (ON DELETE CASCADE).
        expenseDao.insert(
            Expense(
                id = 100L,
                amount = 120.0,
                merchant = "Dinner",
                transactionType = TransactionType.PURCHASE,
                date = FIXED_NOW,
                createdAt = FIXED_NOW
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun assignment(
        expenseId: Long = 100L,
        participantName: String = "Alice",
        participantIndex: Int = 0,
        assignedAmount: Double = 60.0,
        isPaid: Boolean = false,
        paidAt: Long? = null
    ): SplitItemAssignment = SplitItemAssignment(
        expenseId = expenseId,
        participantName = participantName,
        participantIndex = participantIndex,
        assignedAmount = assignedAmount,
        isPaid = isPaid,
        paidAt = paidAt,
        createdAt = FIXED_NOW
    )

    @Test
    fun `markAsPaid persists the exact supplied timestamp`() = runTest {
        val id = dao.insertAssignment(assignment())

        // Deliberately non-round timestamp that differs from FIXED_NOW.
        val paidTimestamp = FIXED_NOW + 123_456L
        dao.markAsPaid(id, paidTimestamp)

        val paid = dao.getAssignmentsForExpenseSync(100L).single()
        assertTrue(paid.isPaid)
        assertEquals(paidTimestamp, paid.paidAt)
    }

    @Test
    fun `markAsPaid uses caller timestamp rather than creation timestamp`() = runTest {
        val id = dao.insertAssignment(assignment())

        // Timestamp strictly older than createdAt proves the DAO does not
        // substitute its own clock or default to creation time.
        val olderTimestamp = FIXED_NOW - 5_000L
        dao.markAsPaid(id, olderTimestamp)

        val paid = dao.getAssignmentsForExpenseSync(100L).single()
        assertEquals(olderTimestamp, paid.paidAt)
    }

    @Test
    fun `markAsPaid only updates the targeted assignment`() = runTest {
        val firstId = dao.insertAssignment(assignment(participantName = "Alice"))
        val secondId = dao.insertAssignment(
            assignment(participantName = "Bob", participantIndex = 1, assignedAmount = 60.0)
        )

        dao.markAsPaid(firstId, FIXED_NOW + 10L)

        val rows = dao.getAssignmentsForExpenseSync(100L).associateBy { it.id }
        val first = rows.getValue(firstId)
        val second = rows.getValue(secondId)

        assertTrue(first.isPaid)
        assertEquals(FIXED_NOW + 10L, first.paidAt)

        assertFalse(second.isPaid)
        assertNull(second.paidAt)
    }
}
