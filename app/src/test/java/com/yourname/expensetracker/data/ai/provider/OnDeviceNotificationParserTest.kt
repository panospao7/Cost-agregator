package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceNotificationParserTest {

    private val parser = OnDeviceNotificationParser(
        router = mockk<AiCapabilityRouter>(relaxed = true),
        settingsRepository = mockk<AiSettingsRepository>(relaxed = true)
    )

    @Test
    fun `parseResponse drops transfer metadata for purchase JSON with direction`() {
        val result = parser.parseResponse(
            text = """{"amount":5.0,"currency":"EUR","merchant":"Σκλαβενίτης","type":"PURCHASE","direction":"OUTGOING","confidence":0.85,"reasoning":"charged at merchant"}""",
            packageName = "com.bank"
        )

        requireNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
        assertNull(result.transferDirection)
        assertNull(result.transferAccountName)
    }

    @Test
    fun `parseResponse keeps transfer metadata for transfer JSON`() {
        val result = parser.parseResponse(
            text = """{"amount":42.5,"currency":"EUR","merchant":"John","type":"TRANSFER","direction":"OUTGOING","confidence":0.9,"reasoning":"money sent to another person"}""",
            packageName = "com.bank"
        )

        requireNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result.type)
        assertEquals(ParsedTransferDirection.OUTGOING, result.transferDirection)
        assertEquals("To: John", result.transferAccountName)
    }

    @Test
    fun `parseResponse keeps transfer metadata for deposit JSON`() {
        val result = parser.parseResponse(
            text = """{"amount":1200.0,"currency":"EUR","merchant":"Employer","type":"DEPOSIT","direction":"INCOMING","confidence":0.95,"reasoning":"salary credited"}""",
            packageName = "com.bank"
        )

        requireNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result.type)
        assertEquals(ParsedTransferDirection.INCOMING, result.transferDirection)
        assertEquals("From: Employer", result.transferAccountName)
    }
}
