package com.yourname.expensetracker.ui.screens.review

import com.yourname.expensetracker.data.database.entity.TransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reviewer m2: pure seed-matching rule for bulk approve. An edited direction/account reaches
 * another row only when that row's seed equals the edited row's seed (both blank/null, or both
 * non-blank and equal; direction trimmed + case-insensitive, account trimmed + case-sensitive).
 * Synthetic values only.
 */
class BulkTransferSeedMatchTest {

    private val edited = TransferDirection.INCOMING

    // ---- direction ----

    @Test
    fun `direction null edit returns null even when seeds match`() {
        assertNull(bulkTransferDirectionOrNull(null, "OUTGOING", "OUTGOING"))
        assertNull(bulkTransferDirectionOrNull(null, null, null))
    }

    @Test
    fun `direction equal non-blank seeds pass the edit`() {
        assertEquals(edited, bulkTransferDirectionOrNull(edited, "OUTGOING", "OUTGOING"))
    }

    @Test
    fun `direction seeds compare case-insensitively`() {
        assertEquals(edited, bulkTransferDirectionOrNull(edited, "OUTGOING", "outgoing"))
        assertEquals(edited, bulkTransferDirectionOrNull(edited, "outgoing", "OUTGOING"))
    }

    @Test
    fun `direction seeds are trimmed before comparing`() {
        assertEquals(edited, bulkTransferDirectionOrNull(edited, "  OUTGOING ", "OUTGOING"))
        assertEquals(edited, bulkTransferDirectionOrNull(edited, "OUTGOING", "\tOUTGOING  "))
    }

    @Test
    fun `direction different non-blank seeds return null`() {
        assertNull(bulkTransferDirectionOrNull(edited, "OUTGOING", "INCOMING"))
    }

    @Test
    fun `direction both null seeds match`() {
        assertEquals(edited, bulkTransferDirectionOrNull(edited, null, null))
    }

    @Test
    fun `direction both blank seeds match whether null, empty or whitespace`() {
        assertEquals(edited, bulkTransferDirectionOrNull(edited, "", "   "))
        assertEquals(edited, bulkTransferDirectionOrNull(edited, null, ""))
        assertEquals(edited, bulkTransferDirectionOrNull(edited, "  ", null))
    }

    @Test
    fun `direction one blank and one non-blank never match`() {
        assertNull(bulkTransferDirectionOrNull(edited, null, "OUTGOING"))
        assertNull(bulkTransferDirectionOrNull(edited, "OUTGOING", null))
        assertNull(bulkTransferDirectionOrNull(edited, "", "OUTGOING"))
        assertNull(bulkTransferDirectionOrNull(edited, "OUTGOING", "  "))
    }

    // ---- account ----

    @Test
    fun `account null edit returns null even when seeds match`() {
        assertNull(bulkTransferAccountNameOrNull(null, "Savings", "Savings"))
        assertNull(bulkTransferAccountNameOrNull(null, null, null))
    }

    @Test
    fun `account equal non-blank seeds pass the edit`() {
        assertEquals("Joint", bulkTransferAccountNameOrNull("Joint", "Savings", "Savings"))
    }

    @Test
    fun `account seeds are trimmed before comparing`() {
        assertEquals("Joint", bulkTransferAccountNameOrNull("Joint", " Savings  ", "Savings"))
        assertEquals("Joint", bulkTransferAccountNameOrNull("Joint", "Savings", "\tSavings "))
    }

    @Test
    fun `account seeds compare case-sensitively`() {
        assertNull(bulkTransferAccountNameOrNull("Joint", "Savings", "savings"))
        assertNull(bulkTransferAccountNameOrNull("Joint", "savings", "Savings"))
    }

    @Test
    fun `account different non-blank seeds return null`() {
        assertNull(bulkTransferAccountNameOrNull("Joint", "Savings", "Main"))
    }

    @Test
    fun `account both blank seeds match whether null, empty or whitespace`() {
        assertEquals("Joint", bulkTransferAccountNameOrNull("Joint", null, null))
        assertEquals("Joint", bulkTransferAccountNameOrNull("Joint", "", "   "))
        assertEquals("Joint", bulkTransferAccountNameOrNull("Joint", null, ""))
        assertEquals("Joint", bulkTransferAccountNameOrNull("Joint", "  ", null))
    }

    @Test
    fun `account one blank and one non-blank never match`() {
        assertNull(bulkTransferAccountNameOrNull("Joint", null, "Savings"))
        assertNull(bulkTransferAccountNameOrNull("Joint", "Savings", null))
        assertNull(bulkTransferAccountNameOrNull("Joint", "", "Savings"))
        assertNull(bulkTransferAccountNameOrNull("Joint", "Savings", "  "))
    }

    @Test
    fun `direction and account are judged separately`() {
        assertEquals(edited, bulkTransferDirectionOrNull(edited, "OUTGOING", "OUTGOING"))
        assertNull(bulkTransferAccountNameOrNull("Joint", "Savings", "Main"))
    }
}
