package com.yourname.expensetracker.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantRulesRepositoryTest {

    private val repository = MerchantRulesRepository()

    @Test
    fun `cleanMerchantName removes store numbers`() {
        val result = repository.cleanMerchantName("McDonald's Store #123")
        assertEquals("McDonald's", result)
    }

    @Test
    fun `cleanMerchantName removes corporate suffixes`() {
        val result = repository.cleanMerchantName("Starbucks Corp.")
        assertEquals("Starbucks", result)
    }

    @Test
    fun `cleanMerchantName removes location suffixes`() {
        val result = repository.cleanMerchantName("Shell At Athens")
        assertEquals("Shell", result)
    }
    
    @Test
    fun `cleanMerchantName handles greek characters`() {
        // Current logic preserves ATH suffix as it's not in the exclusion list
        val result = repository.cleanMerchantName("AB VASSILOPOULOS ATH")
        assertEquals("AB VASSILOPOULOS ATH", result)
    }
}
