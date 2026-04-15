package com.yourname.expensetracker.data.location.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun `anonymizeForLog returns a non raw token`() {
        val input = "123 Main Street, Athens"

        val anonymized = input.anonymizeForLog()

        assertTrue(anonymized.startsWith("sha256:"))
        assertNotEquals(input, anonymized)
        assertFalse(anonymized.contains(input))
    }

    @Test
    fun `anonymizeForLog is stable within the current process`() {
        val input = "Acme Coffee"

        val first = input.anonymizeForLog()
        val second = input.anonymizeForLog()

        assertEquals(first, second)
    }

    @Test
    fun `anonymizeForLog produces materially different output for distinct inputs`() {
        val first = "Acme Coffee".anonymizeForLog()
        val second = "Acme Coffee Annex".anonymizeForLog()

        assertNotEquals(first, second)
    }
}
