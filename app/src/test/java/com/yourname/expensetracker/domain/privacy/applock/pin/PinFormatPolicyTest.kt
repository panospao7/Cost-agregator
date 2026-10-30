package com.yourname.expensetracker.domain.privacy.applock.pin

import org.junit.Assert.assertEquals
import org.junit.Test

/** INV-38 / OD-13 / S24 condition A: PIN format rules. Synthetic PINs only. */
class PinFormatPolicyTest {

    private fun check(pin: String): PinFormatResult = PinFormatPolicy.validate(pin.toCharArray())

    @Test
    fun `INV-38 valid six digit pin is accepted`() {
        assertEquals(PinFormatResult.VALID, check("274915"))
    }

    @Test
    fun `INV-38 valid eight digit pin is accepted`() {
        assertEquals(PinFormatResult.VALID, check("80316482"))
    }

    @Test
    fun `INV-38 lengths 6 through 12 digits with no pattern are accepted`() {
        val base = "274915803164"
        for (len in 6..12) {
            assertEquals("length $len", PinFormatResult.VALID, check(base.take(len)))
        }
    }

    @Test
    fun `INV-38 five digit pin is rejected as too short`() {
        assertEquals(PinFormatResult.TOO_SHORT, check("27491"))
    }

    @Test
    fun `INV-38 empty pin is rejected as too short`() {
        assertEquals(PinFormatResult.TOO_SHORT, PinFormatPolicy.validate(CharArray(0)))
    }

    @Test
    fun `INV-38 thirteen digit pin is rejected as too long`() {
        assertEquals(PinFormatResult.TOO_LONG, check("2749158031648"))
    }

    @Test
    fun `INV-38 near patterns that are not simple sequences are accepted`() {
        // Wrap-around, broken run, period four, and a break in an otherwise repeated block.
        assertEquals(PinFormatResult.VALID, check("890123"))
        assertEquals(PinFormatResult.VALID, check("123465"))
        assertEquals(PinFormatResult.VALID, check("12341234"))
        assertEquals(PinFormatResult.VALID, check("121213"))
        assertEquals(PinFormatResult.VALID, check("111112"))
    }

    @Test
    fun `INV-38 validate does not modify or clear the char array`() {
        val pin = "274915".toCharArray()
        PinFormatPolicy.validate(pin)
        assertEquals("274915", String(pin))
    }

    @Test
    fun `INV-38 non digit characters are rejected`() {
        assertEquals(PinFormatResult.NOT_ASCII_DIGITS, check("27491a"))
        assertEquals(PinFormatResult.NOT_ASCII_DIGITS, check("274 915"))
        assertEquals(PinFormatResult.NOT_ASCII_DIGITS, check("27491-"))
    }

    @Test
    fun `INV-38 non ascii digits are rejected`() {
        // Arabic-Indic digits are Unicode digits but not ASCII digits.
        assertEquals(PinFormatResult.NOT_ASCII_DIGITS, check("٢٧٤٩١٥"))
    }

    @Test
    fun `INV-38 all same digits are rejected`() {
        assertEquals(PinFormatResult.ALL_SAME, check("111111"))
        assertEquals(PinFormatResult.ALL_SAME, check("000000000000"))
    }

    @Test
    fun `INV-38 ascending run is rejected`() {
        assertEquals(PinFormatResult.SEQUENTIAL_RUN, check("123456"))
        assertEquals(PinFormatResult.SEQUENTIAL_RUN, check("0123456789"))
    }

    @Test
    fun `INV-38 descending run is rejected`() {
        assertEquals(PinFormatResult.SEQUENTIAL_RUN, check("987654"))
        assertEquals(PinFormatResult.SEQUENTIAL_RUN, check("9876543210"))
    }

    @Test
    fun `INV-38 period two block is rejected`() {
        assertEquals(PinFormatResult.REPEATED_BLOCK, check("121212"))
        assertEquals(PinFormatResult.REPEATED_BLOCK, check("12121212"))
    }

    @Test
    fun `INV-38 period three block is rejected`() {
        assertEquals(PinFormatResult.REPEATED_BLOCK, check("123123"))
        assertEquals(PinFormatResult.REPEATED_BLOCK, check("147147"))
    }

    @Test
    fun `INV-38 period three block is rejected when length is not a multiple`() {
        assertEquals(PinFormatResult.REPEATED_BLOCK, check("12312312"))
    }
}
