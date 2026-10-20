package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Unit tests for [RecurringOccurrenceDao] covering insert, query by source,
 * date range, status, and status update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecurringOccurrenceDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: RecurringOccurrenceDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.recurringOccurrenceDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createOccurrence(
        sourceType: String = "RECURRING_RULE",
        sourceId: Long = 1L,
        occurrenceKey: String = "rule1|20260601|MONTHLY",
        dueDate: Long = FIXED_NOW,
        status: String = "PLANNED",
        linkedExpenseId: Long? = null,
        expectedAmount: Double = 100.0,
        expectedCurrency: String = "EUR",
        paidAt: Long? = null,
        paidAmount: Double? = null,
        paidCurrency: String? = null,
        frequency: String = "MONTHLY",
        merchant: String? = "Netflix",
        categoryId: Long? = null,
        createdAt: Long = FIXED_NOW,
        updatedAt: Long = FIXED_NOW
    ): RecurringOccurrence = RecurringOccurrence(
        sourceType = sourceType,
        sourceId = sourceId,
        occurrenceKey = occurrenceKey,
        dueDate = dueDate,
        status = status,
        linkedExpenseId = linkedExpenseId,
        expectedAmount = expectedAmount,
        expectedCurrency = expectedCurrency,
        paidAt = paidAt,
        paidAmount = paidAmount,
        paidCurrency = paidCurrency,
        frequency = frequency,
        merchant = merchant,
        categoryId = categoryId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `insert persists every field and reads back through getById getByKey and getBySource`() = runTest {
        val occurrence = createOccurrence(
            sourceType = "RECURRING_RULE",
            sourceId = 5L,
            occurrenceKey = "my_unique_key",
            dueDate = FIXED_NOW + 3_000L,
            status = "PAID",
            linkedExpenseId = 77L,
            expectedAmount = 12.34,
            expectedCurrency = "USD",
            paidAt = FIXED_NOW + 4_000L,
            paidAmount = 12.5,
            paidCurrency = "EUR",
            frequency = "WEEKLY",
            merchant = "Netflix",
            categoryId = 3L,
            createdAt = FIXED_NOW + 1_000L,
            updatedAt = FIXED_NOW + 2_000L
        )
        // A row of a different source must not leak into the getBySource result.
        dao.insert(createOccurrence(sourceId = 6L, occurrenceKey = "other_source_key"))

        val id = dao.insert(occurrence)

        assertTrue(id > 0)
        val expected = occurrence.copy(id = id)
        assertEquals(expected, dao.getById(id))
        assertEquals(expected, dao.getByKey("my_unique_key"))
        assertEquals(listOf(expected), dao.getBySource("RECURRING_RULE", 5L))
    }

    @Test
    fun `findEarliestOpenBySource returns earliest unlinked PLANNED row within optional bound`() = runTest {
        val day = 86_400_000L
        dao.insert(createOccurrence(occurrenceKey = "paid", dueDate = FIXED_NOW, status = "PAID", linkedExpenseId = 9L))
        dao.insert(createOccurrence(occurrenceKey = "skipped", dueDate = FIXED_NOW + day, status = "SKIPPED"))
        val earliestId = dao.insert(createOccurrence(occurrenceKey = "open1", dueDate = FIXED_NOW + 2 * day))
        dao.insert(createOccurrence(occurrenceKey = "open2", dueDate = FIXED_NOW + 3 * day))
        dao.insert(createOccurrence(sourceId = 2L, occurrenceKey = "other", dueDate = FIXED_NOW + day))

        assertEquals(earliestId, dao.findEarliestOpenBySource("RECURRING_RULE", 1L, null)!!.id)
        assertEquals(earliestId, dao.findEarliestOpenBySource("RECURRING_RULE", 1L, FIXED_NOW + 2 * day)!!.id)
        assertNull(dao.findEarliestOpenBySource("RECURRING_RULE", 1L, FIXED_NOW + day))
        assertNull(dao.findEarliestOpenBySource("RECURRING_RULE", 404L, null))
    }

    @Test
    fun `getByIds returns only requested rows`() = runTest {
        val a = dao.insert(createOccurrence(occurrenceKey = "a"))
        dao.insert(createOccurrence(occurrenceKey = "b"))
        val c = dao.insert(createOccurrence(occurrenceKey = "c"))

        assertEquals(setOf(a, c), dao.getByIds(listOf(a, c)).map { it.id }.toSet())
        assertTrue(dao.getByIds(listOf(9_999L)).isEmpty())
    }

    @Test
    fun `lookups for missing rows return empty list or null`() = runTest {
        dao.insert(createOccurrence(sourceType = "RECURRING_RULE", sourceId = 1L))

        assertEquals(emptyList(), dao.getBySource("RECURRING_RULE", 999L))
        assertNull(dao.getById(999L))
        assertNull(dao.getByKey("no_such_key"))
    }

    @Test
    fun `getByDateRange returns occurrences within range`() = runTest {
        dao.insert(createOccurrence(dueDate = FIXED_NOW, occurrenceKey = "getByDateRange_key1"))
        dao.insert(createOccurrence(dueDate = FIXED_NOW + 86_400_000L, occurrenceKey = "getByDateRange_key2")) // next day
        dao.insert(createOccurrence(dueDate = FIXED_NOW + 172_800_000L, occurrenceKey = "getByDateRange_key3")) // two days later

        val results = dao.getByDateRange(FIXED_NOW, FIXED_NOW + 172_800_000L)

        assertEquals(2, results.size)
    }

    @Test
    fun `getByDateRange excludes out-of-range occurrences`() = runTest {
        dao.insert(createOccurrence(dueDate = FIXED_NOW, occurrenceKey = "exclude_key1"))
        dao.insert(createOccurrence(dueDate = FIXED_NOW + 999_999_999L, occurrenceKey = "exclude_key2"))

        val results = dao.getByDateRange(FIXED_NOW, FIXED_NOW + 500_000_000L)

        assertEquals(1, results.size)
    }

    @Test
    fun `getByStatus returns occurrences with matching status`() = runTest {
        dao.insert(createOccurrence(status = "PLANNED", occurrenceKey = "status_planned1"))
        dao.insert(createOccurrence(status = "PAID", occurrenceKey = "status_paid1"))
        dao.insert(createOccurrence(status = "PLANNED", occurrenceKey = "status_planned2"))

        val planned = dao.getByStatus("PLANNED")
        assertEquals(2, planned.size)

        val paid = dao.getByStatus("PAID")
        assertEquals(1, paid.size)
    }

    @Test
    fun `updateStatus changes only status and updatedAt of the specified ids`() = runTest {
        val original1 = createOccurrence(status = "PLANNED", occurrenceKey = "update_target_key1")
        val original2 = createOccurrence(status = "PLANNED", occurrenceKey = "update_target_key2")
        val id1 = dao.insert(original1)
        val id2 = dao.insert(original2)

        dao.updateStatus(listOf(id1), "PAID", FIXED_NOW + 1000)

        // Targeted row: new status and exact new updatedAt; every other field unchanged.
        assertEquals(
            original1.copy(id = id1, status = "PAID", updatedAt = FIXED_NOW + 1000),
            dao.getById(id1)
        )
        // Non-targeted row is completely untouched (status and updatedAt included).
        assertEquals(original2.copy(id = id2), dao.getById(id2))
        assertEquals(FIXED_NOW, dao.getById(id2)?.updatedAt)
    }

    @Test
    fun `insert multiple occurrences via insertAll`() = runTest {
        val occurrences = listOf(
            createOccurrence(occurrenceKey = "key1"),
            createOccurrence(occurrenceKey = "key2"),
            createOccurrence(occurrenceKey = "key3")
        )

        dao.insertAll(occurrences)

        val results = dao.getBySource("RECURRING_RULE", 1L)
        assertEquals(3, results.size)
    }

    @Test
    fun `ordering by dueDate asc for getBySource`() = runTest {
        dao.insert(createOccurrence(sourceId = 1L, dueDate = FIXED_NOW + 2000, occurrenceKey = "order_key1"))
        dao.insert(createOccurrence(sourceId = 1L, dueDate = FIXED_NOW, occurrenceKey = "order_key2"))
        dao.insert(createOccurrence(sourceId = 1L, dueDate = FIXED_NOW + 1000, occurrenceKey = "order_key3"))

        val results = dao.getBySource("RECURRING_RULE", 1L)

        // ORDER BY dueDate: FIXED_NOW (key2), FIXED_NOW + 1000 (key3), FIXED_NOW + 2000 (key1).
        assertEquals(
            listOf("order_key2", "order_key3", "order_key1"),
            results.map { it.occurrenceKey }
        )
        assertEquals(
            listOf(FIXED_NOW, FIXED_NOW + 1000, FIXED_NOW + 2000),
            results.map { it.dueDate }
        )
    }

    @Test
    fun `update occurrence entity via update`() = runTest {
        val id = dao.insert(createOccurrence(merchant = "Netflix", status = "PLANNED", occurrenceKey = "update_entity_key"))

        val loaded = dao.getById(id)!!
        val updated = loaded.copy(merchant = "Netflix Premium", updatedAt = FIXED_NOW + 5000)
        dao.update(updated)

        val result = dao.getById(id)
        assertNotNull(result)
        assertEquals("Netflix Premium", result.merchant)
    }
}
