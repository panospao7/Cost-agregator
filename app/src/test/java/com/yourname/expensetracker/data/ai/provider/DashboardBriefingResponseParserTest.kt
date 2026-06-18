package com.yourname.expensetracker.data.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardBriefingResponseParserTest {

    @Test
    fun `parseResponse parses valid bounded confidence`() {
        val result = DashboardBriefingResponseParser.parseResponse(
            """{"title":"Today","text":"All good","tone":"neutral","confidence":0.7}"""
        )

        assertNotNull(result)
        assertEquals(0.7f, result!!.confidence!!, 0.001f)
    }

    @Test
    fun `parseResponse returns null for non-finite confidence`() {
        val result = DashboardBriefingResponseParser.parseResponse(
            """{"title":"Today","text":"All good","tone":"neutral","confidence":"NaN"}"""
        )

        assertNull(result)
    }

    @Test
    fun `parseResponse returns null for out-of-range confidence`() {
        val result = DashboardBriefingResponseParser.parseResponse(
            """{"title":"Today","text":"All good","tone":"neutral","confidence":1.5}"""
        )

        assertNull(result)
    }
}
