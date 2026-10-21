package com.yourname.expensetracker.data.database.converter

import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.*
import org.junit.Test

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `converts PURCHASE to string and back`() {
        val str = converters.fromTransactionType(TransactionType.PURCHASE)
        assertEquals("PURCHASE", str)
        assertEquals(TransactionType.PURCHASE, converters.toTransactionType(str))
    }

    @Test
    fun `converts all TransactionTypes roundtrip`() {
        assertTrue("TransactionType.entries must not be empty", TransactionType.entries.isNotEmpty())
        TransactionType.entries.forEach { type ->
            val str = converters.fromTransactionType(type)
            assertEquals(type, converters.toTransactionType(str))
        }
    }

    @Test
    fun `unparseable strings return UNKNOWN`() {
        val inputs = listOf("INVALID_TYPE", "")
        assertEquals("unparseable inputs size", 2, inputs.size)
        inputs.forEach { input ->
            assertEquals(
                "Input '$input' should map to UNKNOWN",
                TransactionType.UNKNOWN,
                converters.toTransactionType(input)
            )
        }
    }
}
