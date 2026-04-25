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
        TransactionType.entries.forEach { type ->
            val str = converters.fromTransactionType(type)
            assertEquals(type, converters.toTransactionType(str))
        }
    }

    @Test
    fun `invalid string returns UNKNOWN`() {
        assertEquals(TransactionType.UNKNOWN, converters.toTransactionType("INVALID_TYPE"))
    }

    @Test
    fun `empty string returns UNKNOWN`() {
        assertEquals(TransactionType.UNKNOWN, converters.toTransactionType(""))
    }
}
