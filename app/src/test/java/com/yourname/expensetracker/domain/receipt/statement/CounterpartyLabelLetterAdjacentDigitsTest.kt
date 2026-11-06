package com.yourname.expensetracker.domain.receipt.statement

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RA-SI-3: [CounterpartyLabel.scrubLetterAdjacentDigitRuns] masks runs of 9 or more contiguous
 * ASCII digits that touch a letter with a bare "****" and leaves everything else unchanged.
 * All values are invented.
 */
class CounterpartyLabelLetterAdjacentDigitsTest {

    private fun scrub(text: String): String = CounterpartyLabel.scrubLetterAdjacentDigitRuns(text)

    @Test
    fun `digits after a letter prefix are masked to a bare mask`() {
        assertEquals("ACC****", scrub("ACC123456789"))
    }

    @Test
    fun `digits before a letter suffix are masked to a bare mask`() {
        assertEquals("****EUR", scrub("123456789EUR"))
    }

    @Test
    fun `digits between letters are masked and the letters survive`() {
        assertEquals("A****B", scrub("A123456789B"))
    }

    @Test
    fun `no digit of a masked run survives`() {
        val result = scrub("ACC987654321")
        assertEquals("ACC****", result)
        assertEquals(false, result.any { it.isDigit() })
    }

    @Test
    fun `boundary - eight digits next to a letter stay unchanged`() {
        assertEquals("ACC12345678", scrub("ACC12345678"))
        assertEquals("12345678EUR", scrub("12345678EUR"))
    }

    @Test
    fun `boundary - nine digits is the first masked length`() {
        assertEquals("X****", scrub("X123456789"))
        assertEquals("X****", scrub("X1234567890"))
    }

    @Test
    fun `lowercase letter prefix with a long run is masked`() {
        assertEquals("gr****", scrub("gr1234567890123456789012345"))
    }

    @Test
    fun `non ascii letter adjacent to a long run is masked`() {
        assertEquals("ΑΒΓ****", scrub("ΑΒΓ123456789"))
    }

    @Test
    fun `plain digit run with no adjacent letter stays unchanged`() {
        assertEquals("REF 1234567890123", scrub("REF 1234567890123"))
        assertEquals("1234567890", scrub("1234567890"))
    }

    @Test
    fun `digits separated from the letters by space or hyphen stay unchanged`() {
        assertEquals("ACC 123456789", scrub("ACC 123456789"))
        assertEquals("ACC-123456789", scrub("ACC-123456789"))
    }

    @Test
    fun `dates stay unchanged`() {
        assertEquals("Paid on 12/03/2024 at 10:15", scrub("Paid on 12/03/2024 at 10:15"))
        assertEquals("Paid on 2024-03-12", scrub("Paid on 2024-03-12"))
    }

    @Test
    fun `decimal amounts stay unchanged`() {
        assertEquals("TOTAL 12,345.67 EUR", scrub("TOTAL 12,345.67 EUR"))
        assertEquals("TOTAL EUR12,345.67", scrub("TOTAL EUR12,345.67"))
    }

    @Test
    fun `mixed line masks only the account run`() {
        assertEquals(
            "ACC**** on 12/03/2024 paid 12,345.67",
            scrub("ACC123456789 on 12/03/2024 paid 12,345.67")
        )
    }

    @Test
    fun `two separate letter adjacent runs are both masked`() {
        assertEquals("ACC**** and ****EUR", scrub("ACC123456789 and 987654321EUR"))
    }

    @Test
    fun `masked iban output is not altered by the letter adjacent pass`() {
        val masked = CounterpartyLabel.maskIbans("GR96 0000 0000 0000 0000 0000 123")
        assertEquals("****0123", masked)
        assertEquals("****0123", scrub(masked))
    }

    @Test
    fun `iban is masked to last4 by the iban pass before the letter adjacent pass`() {
        val result = scrub(CounterpartyLabel.maskIbans("Paid GR96 0000 0000 0000 0000 0000 123 for rent"))
        assertEquals("Paid ****0123 for rent", result)
    }

    @Test
    fun `empty and short input are returned unchanged`() {
        assertEquals("", scrub(""))
        assertEquals("A1", scrub("A1"))
        assertEquals("SOME SHOP 12,50", scrub("SOME SHOP 12,50"))
    }
}
