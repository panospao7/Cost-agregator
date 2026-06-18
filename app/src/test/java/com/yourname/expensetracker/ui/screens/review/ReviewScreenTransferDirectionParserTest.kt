package com.yourname.expensetracker.ui.screens.review

import com.yourname.expensetracker.data.database.entity.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewScreenTransferDirectionParserTest {

    @Test
    fun `parseTransferDirectionOrNull returns enum for valid value`() {
        assertEquals(TransferDirection.INCOMING, parseTransferDirectionOrNull("INCOMING"))
    }

    @Test
    fun `parseTransferDirectionOrNull returns null for invalid value`() {
        assertNull(parseTransferDirectionOrNull("SIDEWAYS"))
    }

    @Test
    fun `parseTransferDirectionOrNull returns null for blank input`() {
        assertNull(parseTransferDirectionOrNull("  "))
        assertNull(parseTransferDirectionOrNull(null))
    }
}
