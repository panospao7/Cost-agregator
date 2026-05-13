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
    fun `insert an occurrence`() = runTest {
        val occurrence = createOccurrence()
        val id = dao.insert(occurrence)

        assertTrue(id > 0)
    }

    @Test
    fun `query by source type and id`() = runTest {
        dao.insert(createOccurrence(sourceType = "RECURRING_RULE", sourceId = 5L))

        val results = dao.getBySource("RECURRING_RULE", 5L)

        assertEquals(1, results.size)
        assertEquals("Netflix", results[0].merchant)
    }

    @Test
    fun `query by source returns empty list for unknown source`() = runTest {
        dao.insert(createOccurrence(sourceType = "RECURRING_RULE", sourceId = 1L))

        val results = dao.getBySource("RECURRING_RULE", 999L)

        assertEquals(0, results.size)
    }

    @Test
    fun `getById returns occurrence`() = runTest {
        val id = dao.insert(createOccurrence())

        val result = dao.getById(id)

        assertNotNull(result)
        assertEquals(id, result.id)
    }

    @Test
    fun `getById returns null for non-existent id`() = runTest {
        val result = dao.getById(999L)

        assertNull(result)
    }

    @Test
    fun `getByKey returns occurrence by unique key`() = runTest {
        val key = "my_unique_key"
        dao.insert(createOccurrence(occurrenceKey = key))

        val result = dao.getByKey(key)

        assertNotNull(result)
        assertEquals(key, result.occurrenceKey)
    }

    @Test
    fun `getByDateRange returns occurrences within range`() = runTest {
        dao.insert(createOccurrence(dueDate = FIXED_NOW, occurrenceKey = "getByDateRange_key1"))
        dao.insert(createOccurrence(dueDate = FIXED_NOW + 86_400_000L, occurrenceKey = "getByDateRange_key2")) // next day
        dao.insert(createOccurrence(dueDate = FIXED_NOW + 172_800_000L, occurrenceKey = "getByDateRange_key3")) // two days later

        val results = dao.getByDateRange(FIXED_NOW, FIXED_NOW + 100_000_000L)

        assertEquals(3, results.size)
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
    fun `update status from PLANNED to PAID`() = runTest {
        val id = dao.insert(createOccurrence(status = "PLANNED", occurrenceKey = "update_status_key1"))

        dao.updateStatus(listOf(id), "PAID", FIXED_NOW + 1000)

        val result = dao.getById(id)
        assertNotNull(result)
        assertEquals("PAID", result.status)
        assertEquals(FIXED_NOW + 1000, result.updatedAt)
    }

    @Test
    fun `update only targets specified ids`() = runTest {
        val id1 = dao.insert(createOccurrence(status = "PLANNED", occurrenceKey = "update_target_key1"))
        val id2 = dao.insert(createOccurrence(status = "PLANNED", occurrenceKey = "update_target_key2"))

        dao.updateStatus(listOf(id1), "PAID", FIXED_NOW)

        assertEquals("PAID", dao.getById(id1)?.status)
        assertEquals("PLANNED", dao.getById(id2)?.status)
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

        assertEquals(3, results.size)
        assertTrue(results[0].dueDate <= results[1].dueDate)
        assertTrue(results[1].dueDate <= results[2].dueDate)
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
