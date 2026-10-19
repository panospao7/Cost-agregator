package com.yourname.expensetracker.service

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.navigation.DomainOwnershipFilter
import com.yourname.expensetracker.domain.model.navigation.DomainTransactionFilter
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for TransactionFilterSerializer.
 * Tests JSON serialization/deserialization and versioning.
 *
 * Table-driven: every row carries a name that is echoed in assertion messages.
 */
class TransactionFilterSerializerTest {

    private lateinit var serializer: TransactionFilterSerializer

    @Before
    fun setup() {
        serializer = TransactionFilterSerializer()
    }

    private data class ParseRow(
        val name: String,
        val json: String,
        val expected: DomainTransactionFilter
    )

    /** The seven persisted fields, compared exactly (no tolerance: all expected values are exact). */
    private fun fields(filter: DomainTransactionFilter): List<Any?> = listOf(
        filter.categoryId,
        filter.merchantName,
        filter.transactionType,
        filter.dateRange,
        filter.ownership,
        filter.minAmount,
        filter.maxAmount
    )

    @Test
    fun `serialize and deserialize round-trip preserves all fields, including all-null`() {
        val rows = listOf(
            "all fields populated" to DomainTransactionFilter(
                categoryId = 123L,
                merchantName = "Test Merchant",
                transactionType = DomainTransactionType.PURCHASE,
                dateRange = Pair(1000000L, 2000000L),
                ownership = DomainOwnershipFilter.ALL,
                minAmount = 10.0,
                maxAmount = 100.0
            ),
            "all fields null" to DomainTransactionFilter(
                categoryId = null,
                merchantName = null,
                transactionType = null,
                dateRange = null,
                ownership = null,
                minAmount = null,
                maxAmount = null
            )
        )
        assertEquals("size guard", 2, rows.size)
        for ((name, filter) in rows) {
            val deserialized = serializer.deserialize(serializer.serialize(filter))
            assertNotNull("row '$name' deserialized", deserialized)
            assertEquals("row '$name' fields", fields(filter), fields(deserialized!!))
        }
    }

    @Test
    fun `serialize includes version field`() {
        val filter = DomainTransactionFilter(categoryId = 123L)

        val json = serializer.serialize(filter)

        assertTrue(json.contains("\"version\":1"))
    }

    @Test
    fun `deserialize is tolerant of missing, unknown, invalid and boundary values`() {
        val rows = listOf(
            ParseRow(
                "missing optional fields",
                """{"version":1,"categoryId":123}""",
                DomainTransactionFilter(categoryId = 123L)
            ),
            ParseRow(
                "missing version field",
                """{"categoryId":123,"merchantName":"Test"}""",
                DomainTransactionFilter(categoryId = 123L, merchantName = "Test")
            ),
            ParseRow(
                "unknown fields ignored for forward compatibility",
                """{"version":1,"categoryId":123,"unknownField":"value","futureFeature":true}""",
                DomainTransactionFilter(categoryId = 123L)
            ),
            ParseRow(
                "invalid transaction type becomes null",
                """{"version":1,"transactionType":"INVALID_TYPE"}""",
                DomainTransactionFilter()
            ),
            ParseRow(
                "invalid ownership filter becomes null",
                """{"version":1,"ownership":"INVALID_OWNERSHIP"}""",
                DomainTransactionFilter()
            ),
            ParseRow(
                "dateRange with both start and end",
                """{"version":1,"dateRangeStart":1000000,"dateRangeEnd":2000000}""",
                DomainTransactionFilter(dateRange = Pair(1000000L, 2000000L))
            ),
            ParseRow(
                "dateRange null when only start is present",
                """{"version":1,"dateRangeStart":1000000}""",
                DomainTransactionFilter()
            ),
            ParseRow(
                "zero values are preserved, not treated as absent",
                """{"version":1,"categoryId":0,"minAmount":0.0,"maxAmount":0.0}""",
                DomainTransactionFilter(categoryId = 0L, minAmount = 0.0, maxAmount = 0.0)
            ),
            ParseRow(
                "future version still parses",
                """{"version":2,"categoryId":123,"merchantName":"Future Feature"}""",
                DomainTransactionFilter(categoryId = 123L, merchantName = "Future Feature")
            )
        )
        assertEquals("size guard", 9, rows.size)
        for (row in rows) {
            val filter = serializer.deserialize(row.json)
            assertNotNull("row '${row.name}' deserialized", filter)
            assertEquals("row '${row.name}' fields", fields(row.expected), fields(filter!!))
        }
    }

    @Test
    fun `deserialize returns null for malformed JSON and empty string`() {
        val rows = listOf(
            "malformed JSON" to """{"invalid json structure""",
            "empty string" to ""
        )
        assertEquals("size guard", 2, rows.size)
        for ((name, json) in rows) {
            assertNull("row '$name'", serializer.deserialize(json))
        }
    }

    @Test
    fun `isValid is true for serialized filter and false for malformed JSON`() {
        val validJson = serializer.serialize(DomainTransactionFilter(categoryId = 123L))
        assertTrue("serialized filter", serializer.isValid(validJson))
        assertFalse("malformed JSON", serializer.isValid("""{"invalid json"""))
    }
}
