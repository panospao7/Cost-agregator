package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Refund netting S3 (INV-17 candidate scope, INV-19): SQL contract of
 * [ExpenseDao.getRefundCandidatePurchasesBetween] on a real in-memory Room database.
 *
 * - returns PURCHASE rows in the half-open range [start, end), including not-mine rows
 *   (ownership never affects refund eligibility);
 * - excludes DEPOSIT rows, out-of-range rows and rows with a spendingExclusionReason;
 * - orders by date DESC then id DESC.
 *
 * Rows are seeded with raw SQL so the test does not depend on the lifecycle write path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RefundCandidateQueryTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ExpenseDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.expenseDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun seed(
        id: Long,
        type: String = "PURCHASE",
        date: Long = 20_000L,
        notMine: Boolean = false,
        exclusionReason: String? = null
    ) {
        val reason = exclusionReason?.let { "'$it'" } ?: "NULL"
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine, " +
                "spendingExclusionReason) VALUES ($id, 50.0, 'EUR', 'M$id', $date, '$type', " +
                "${if (notMine) 1 else 0}, $reason)"
        )
    }

    private suspend fun ids(start: Long = 10_000L, end: Long = 30_000L): List<Long> =
        dao.getRefundCandidatePurchasesBetween(start, end).map { it.id }

    @Test
    fun `INV-17 control - a plain purchase in range is returned`() = runTest {
        seed(1L)

        assertEquals(listOf(1L), ids())
    }

    @Test
    fun `INV-17 a not-mine purchase in range is returned because ownership never affects eligibility`() = runTest {
        seed(1L, notMine = false)
        seed(2L, notMine = true)

        assertEquals(listOf(2L, 1L), ids())
    }

    @Test
    fun `INV-17 deposit rows are excluded`() = runTest {
        seed(1L, type = "PURCHASE")
        seed(2L, type = "DEPOSIT")
        seed(3L, type = "DEPOSIT", notMine = true)

        assertEquals(listOf(1L), ids())
    }

    @Test
    fun `INV-17 out-of-range rows are excluded and the range is half-open`() = runTest {
        seed(1L, date = 9_999L)
        seed(2L, date = 10_000L)
        seed(3L, date = 29_999L)
        seed(4L, date = 30_000L)
        seed(5L, date = 40_000L)

        assertEquals(listOf(3L, 2L), ids(start = 10_000L, end = 30_000L))
    }

    @Test
    fun `INV-19 rows with a spendingExclusionReason are excluded`() = runTest {
        seed(1L)
        seed(2L, exclusionReason = "INSTALLMENT_PURCHASE")
        seed(3L, exclusionReason = "REVERSED", notMine = true)

        assertEquals(listOf(1L), ids())
    }

    @Test
    fun `INV-17 results are ordered by date descending then id descending`() = runTest {
        seed(1L, date = 20_000L)
        seed(2L, date = 25_000L)
        seed(3L, date = 20_000L)
        seed(4L, date = 15_000L)

        assertEquals(listOf(2L, 3L, 1L, 4L), ids())
    }

    @Test
    fun `INV-17 an empty range returns an empty list`() = runTest {
        seed(1L)

        assertEquals(emptyList<Long>(), ids(start = 100_000L, end = 200_000L))
    }
}
