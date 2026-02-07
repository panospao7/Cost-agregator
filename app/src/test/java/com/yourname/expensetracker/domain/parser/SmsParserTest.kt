package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SmsParserTest {
    private lateinit var parser: SmsParser

    @Before
    fun setup() {
        parser = SmsParser()
    }

    @Test
    fun `parse bank SMS with Greek keywords`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 15,00 EUR στο KATASTIMA στις 07/02",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(15.00, result!!.amount, 0.01)
    }

    @Test
    fun `parse bank SMS with Greeklish keywords`() {
        val result = parser.parse(
            title = "Alpha",
            text = "Agora 22,50 EUR sto SUPERMARKET",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(22.50, result!!.amount, 0.01)
    }

    @Test
    fun `reject non-bank sender`() {
        val result = parser.parse(
            title = "John",
            text = "Hey, can you send me 50 EUR?",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `reject bank sender without transaction keywords`() {
        val result = parser.parse(
            title = "NBG",
            text = "Welcome to our new mobile app!",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `reject null title`() {
        val result = parser.parse(
            title = null,
            text = "Αγορά 15,00 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `amount bounds check - too small`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 0,05 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `supports all messaging packages`() {
        val packages = parser.supportedPackages
        assertTrue(packages.contains("com.google.android.apps.messaging"))
        assertTrue(packages.contains("com.samsung.android.messaging"))
        assertTrue(packages.contains("com.android.mms"))
    }
}
