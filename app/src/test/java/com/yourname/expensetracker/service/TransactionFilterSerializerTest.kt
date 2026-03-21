package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.OwnershipFilter
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for TransactionFilterSerializer.
 * Tests JSON serialization/deserialization and versioning.
 */
class TransactionFilterSerializerTest {

    private lateinit var serializer: TransactionFilterSerializer

    @Before
    fun setup() {
        serializer = TransactionFilterSerializer()
    }

    @Test
    fun `serialize and deserialize round-trip preserves all fields`() {
        val filter = TransactionFilter(
            categoryId = 123L,
            merchantName = "Test Merchant",
            transactionType = TransactionType.PURCHASE,
            dateRange = Pair(1000000L, 2000000L),
            ownership = OwnershipFilter.ALL,
            minAmount = 10.0,
            maxAmount = 100.0
        )

        val json = serializer.serialize(filter)
        val deserialized = serializer.deserialize(json)

        assertNotNull(deserialized)
        deserialized?.let {
            assertEquals(filter.categoryId, it.categoryId)
            assertEquals(filter.merchantName, it.merchantName)
            assertEquals(filter.transactionType, it.transactionType)
            assertEquals(filter.dateRange, it.dateRange)
            assertEquals(filter.ownership, it.ownership)
            assertEquals(filter.minAmount, it.minAmount)
            assertEquals(filter.maxAmount, it.maxAmount)
        }
    }

    @Test
    fun `serialize handles null fields gracefully`() {
        val filter = TransactionFilter(
            categoryId = null,
            merchantName = null,
            transactionType = null,
            dateRange = null,
            ownership = null,
            minAmount = null,
            maxAmount = null
        )

        val json = serializer.serialize(filter)
        val deserialized = serializer.deserialize(json)

        assertNotNull(deserialized)
        deserialized?.let {
            assertNull(it.categoryId)
            assertNull(it.merchantName)
            assertNull(it.transactionType)
            assertNull(it.dateRange)
            assertNull(it.ownership)
            assertNull(it.minAmount)
            assertNull(it.maxAmount)
        }
    }

    @Test
    fun `serialize includes version field`() {
        val filter = TransactionFilter(categoryId = 123L)

        val json = serializer.serialize(filter)

        assertTrue(json.contains("\"version\":1"))
    }

    @Test
    fun `deserialize handles missing optional fields`() {
        val json = """{"version":1,"categoryId":123}"""

        val filter = serializer.deserialize(json)

        assertNotNull(filter)
        filter?.let {
            assertEquals(123L, it.categoryId)
            assertNull(it.merchantName)
            assertNull(it.transactionType)
            assertNull(it.dateRange)
            assertNull(it.ownership)
            assertNull(it.minAmount)
            assertNull(it.maxAmount)
        }
    }

    @Test
    fun `deserialize handles missing version field`() {
        val json = """{"categoryId":123,"merchantName":"Test"}"""

        val filter = serializer.deserialize(json)

        assertNotNull(filter)
        filter?.let {
            assertEquals(123L, it.categoryId)
            assertEquals("Test", it.merchantName)
        }
    }

    @Test
    fun `deserialize ignores unknown fields for forward compatibility`() {
        val json = """{"version":1,"categoryId":123,"unknownField":"value","futureFeature":true}"""

        val filter = serializer.deserialize(json)

        assertNotNull(filter)
        filter?.let {
            assertEquals(123L, it.categoryId)
        }
    }

    @Test
    fun `deserialize handles invalid transaction type gracefully`() {
        val json = """{"version":1,"transactionType":"INVALID_TYPE"}"""

        val filter = serializer.deserialize(json)

        assertNotNull(filter)
        filter?.let {
            assertNull(it.transactionType)
        }
    }

    @Test
    fun `deserialize handles invalid ownership filter gracefully`() {
        val json = """{"version":1,"ownership":"INVALID_OWNERSHIP"}"""

        val filter = serializer.deserialize(json)

        assertNotNull(filter)
        filter?.let {
            assertNull(it.ownership)
        }
    }

    @Test
    fun `deserialize returns null for malformed JSON`() {
        val json = """{"invalid json structure"""

        val filter = serializer.deserialize(json)

        assertNull(filter)
    }

    @Test
    fun `deserialize returns null for empty string`() {
        val filter = serializer.deserialize("")

        assertNull(filter)
    }

    @Test
    fun `isValid returns true for valid JSON`() {
        val filter = TransactionFilter(categoryId = 123L)
        val json = serializer.serialize(filter)

        assertTrue(serializer.isValid(json))
    }

    @Test
    fun `isValid returns false for invalid JSON`() {
        val json = """{"invalid json"""

        assertFalse(serializer.isValid(json))
    }

    @Test
    fun `deserialize handles dateRange with both start and end`() {
        val json = """{"version":1,"dateRangeStart":1000000,"dateRangeEnd":2000000}"""

        val filter = serializer.deserialize(json)

        assertNotNull(filter)
        filter?.let {
            assertNotNull(it.dateRange)
            assertEquals(1000000L, it.dateRange!!.first)
            assertEquals(2000000L, it.dateRange!!.second)
        }
    }

    @Test
    fun `deserialize returns null dateRange when only start is present`() {
        val json = """{"version":1,"dateRangeStart":1000000}"""

        val filter = serializer.deserialize(json)

        assertNotNull(filter)
        filter?.let {
            assertNull(it.dateRange)
        }
    }

    @Test
    fun `deserialize handles zero values correctly`() {
        val json = """{"version":1,"categoryId":0,"minAmount":0.0,"maxAmount":0.0}"""

        val filter = serializer.deserialize(json)

        assertNotNull(filter)
        filter?.let {
            assertEquals(0L, it.categoryId)
            assertEquals(0.0, it.minAmount!!, 0.001)
            assertEquals(0.0, it.maxAmount!!, 0.001)
        }
    }

    @Test
    fun `version migration from future version still works`() {
        val json = """{"version":2,"categoryId":123,"merchantName":"Future Feature"}"""

        val filter = serializer.deserialize(json)

        assertNotNull(filter)
        filter?.let {
            assertEquals(123L, it.categoryId)
            assertEquals("Future Feature", it.merchantName)
        }
    }
}
