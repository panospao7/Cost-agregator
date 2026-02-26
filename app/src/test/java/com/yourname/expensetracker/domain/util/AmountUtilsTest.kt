package com.yourname.expensetracker.domain.util

import org.junit.Assert.*
import org.junit.Test

class AmountUtilsTest {
    
    @Test
    fun `parseAmount - valid formats parse correctly`() {
        assertEquals(1234.56, AmountUtils.parseAmount("1,234.56")!!, 0.001)
        assertEquals(1234.56, AmountUtils.parseAmount("1.234,56")!!, 0.001)
        assertEquals(1234567.0, AmountUtils.parseAmount("1,234,567")!!, 0.001)
        assertEquals(1.5, AmountUtils.parseAmount("1,50")!!, 0.001)
    }
    
    @Test
    fun `parseAmount - invalid formats return null`() {
        assertNull(AmountUtils.parseAmount("1,23,456"))
        assertNull(AmountUtils.parseAmount("abc"))
        assertNull(AmountUtils.parseAmount(""))
    }
    
    @Test
    fun `parseAmount - handles negative amounts`() {
        assertEquals(-50.0, AmountUtils.parseAmount("-50")!!, 0.001)
        assertEquals(-50.0, AmountUtils.parseAmount("−50")!!, 0.001)
        assertEquals(-50.0, AmountUtils.parseAmount("(50)")!!, 0.001)
    }
    
    @Test
    fun `parseAmount - handles currency symbols`() {
        assertEquals(50.0, AmountUtils.parseAmount("E50")!!, 0.001)
        assertEquals(50.0, AmountUtils.parseAmount("e50")!!, 0.001)
    }
    
    @Test
    fun `isValidAmount - validates correctly`() {
        assertTrue(AmountUtils.isValidAmount(100.0))
        assertTrue(AmountUtils.isValidAmount(0.01))
        assertFalse(AmountUtils.isValidAmount(0.0))
        assertFalse(AmountUtils.isValidAmount(-10.0))
        assertFalse(AmountUtils.isValidAmount(2_000_000.0))
    }
}
