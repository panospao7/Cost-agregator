package com.yourname.expensetracker

import com.yourname.expensetracker.domain.receipt.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

import com.yourname.expensetracker.data.repository.MerchantRulesRepository

class OcrParserTest {

    private val parser = ReceiptParser(MerchantRulesRepository())

    @Test
    fun `test decimal parsing - standard european`() {
        val input = """
            MARKET
            ITEMS 10,00
            TOTAL 45,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(45.50, result.total!!, 0.01)
    }

    @Test
    fun `test decimal parsing - european with thousands separator`() {
        val input = """
            TECH STORE
            LAPTOP 1.250,50
            TOTAL 1.250,50 €
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(1250.50, result.total!!, 0.01)
    }

    @Test
    fun `test decimal parsing - US standard`() {
        val input = """
            DINER US
            BURGER 12.50
            TOTAL 12.50
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(12.50, result.total!!, 0.01)
    }
    
    @Test
    fun `test decimal parsing - US with thousands separator`() {
        val input = """
            CAR DEALER
            DOWNPAYMENT 1,250.00
            TOTAL 1,250.00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(1250.00, result.total!!, 0.01)
    }

    @Test
    fun `test greek normalization - Sigma error`() {
        val input = """
            SUPER MARKET
            GALA 1,20
            EYNONO 1,20
        """.trimIndent()
        val result = parser.parse(input)
        // EYNONO -> ΣΥΝΟΛΟ -> TOTAL_KEY
        assertEquals(1.20, result.total!!, 0.01)
    }
    
    @Test
    fun `test greek normalization - Z error`() {
        val input = """
            CAFE
            COFFEE 2,50
            ZYNOAO 2,50
        """.trimIndent()
        val result = parser.parse(input)
        // ZYNOAO -> ΣΥΝΟΛΟ -> TOTAL_KEY
        assertEquals(2.50, result.total!!, 0.01)
    }
    
    @Test
    fun `test greek normalization - 2 error`() {
        val input = """
            BAKERY
            BREAD 0,90
            2YNONO 0,90
        """.trimIndent()
        val result = parser.parse(input)
        // 2YNONO -> ΣΥΝΟΛΟ -> TOTAL_KEY
        assertEquals(0.90, result.total!!, 0.01)
    }
    
    @Test
    fun `test greek normalization - Lambda error`() {
        val input = """
            STORE
            ITEM 10.00
            IYNOAO 10.00
        """.trimIndent()
        val result = parser.parse(input)
        // IYNOAO -> ΣΥΝΟΛΟ -> TOTAL_KEY
        assertEquals(10.00, result.total!!, 0.01)
    }

    @Test
    fun `test year range expansion`() {
        val input = """
            HISTORY MUSEUM
            TICKET 5.00
            DATE 15/05/2016
            TOTAL 5.00
        """.trimIndent()
        val result = parser.parse(input)
        
        assertNotNull(result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals(2016, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH)) // Month is 0-indexed (May=4)
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
    }
    
    @Test
    fun `test total extraction fallback`() {
        // Receipt where total is not at bottom and no clear keyword
        val input = """
            GAS STATION
            PUMP 1
            45,00 €
            
            THANK YOU
            COME AGAIN
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(45.00, result.total!!, 0.01)
    }
    
    @Test
    fun `test merchant extraction - skip noise`() {
        val input = """
            NOMIMH APODEIXI
            START
            MY SHOP NAME
            ADDRESS 123
            TEL 2101234567
            TOTAL 10.00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals("MY SHOP NAME", result.merchantName)
    }

    @Test
    fun `test greek normalization - Cash keyword`() {
        val input = """
            COFFEE SHOP
            CAPPUCCINO 3,50
            METPHTA 3,50
            ZYNOAO 3,50
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(3.50, result.total!!, 0.01)
        // Verify METPHTA didn't interfere or was recognized as CASH_KEY
        assertTrue(result.lineItems.isEmpty()) // Keywords should be cleaned
    }

    @Test
    fun `test greek normalization - Payable variant`() {
        val input = """
            SUPERMARKET
            ITEM 1 10.00
            NAHPQTEO 10.00
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(10.00, result.total!!, 0.01)
    }

    @Test
    fun `test complex ocr number fix`() {
        val input = """
            STORE
            TOTAL_KEY 4 5 , 5 0
        """.trimIndent()
        val result = parser.parse(input)
        assertEquals(45.50, result.total!!, 0.01)
    }

    @Test
    fun `test date ocr fix - 16-D4-2017`() {
        val input = """
            MARKET
            16-D4-2017
            TOTAL 10.00
        """.trimIndent()
        val result = parser.parse(input)
        assertNotNull(result.date)
        val cal = Calendar.getInstance()
        cal.timeInMillis = result.date!!
        assertEquals(2017, cal.get(Calendar.YEAR))
        assertEquals(3, cal.get(Calendar.MONTH)) // April = 3
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
    }
}
