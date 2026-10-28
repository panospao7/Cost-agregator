package com.yourname.expensetracker.domain.receipt.statement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatementAmountParserTest {
    private val noBreakSpace = Char(0xA0)
    private val narrowNoBreakSpace = Char(0x202F)
    private val figureSpace = Char(0x2007)
    private val unicodeMinus = Char(0x2212)

    @Test
    fun `parses european and english grouped amounts with signs`() {
        assertEquals(1234.56, StatementAmountParser.parse("1.234,56")!!, 0.0001)
        assertEquals(-7.41, StatementAmountParser.parse("-7,41")!!, 0.0001)
        assertEquals(1234.56, StatementAmountParser.parse("€1,234.56")!!, 0.0001)
        assertEquals(-3.0, StatementAmountParser.parse("-€3.00")!!, 0.0001)
        assertEquals(10.0, StatementAmountParser.parse("+10,00")!!, 0.0001)
        assertEquals(-4.5, StatementAmountParser.parse("${unicodeMinus}4,50")!!, 0.0001)
    }

    @Test
    fun `no-break narrow and figure spaces inside a token are ignored`() {
        assertEquals(1234.56, StatementAmountParser.parse("1${noBreakSpace}234,56")!!, 0.0001)
        assertEquals(1234.56, StatementAmountParser.parse("1${narrowNoBreakSpace}234,56")!!, 0.0001)
        assertEquals(1234.56, StatementAmountParser.parse("1${figureSpace}234,56")!!, 0.0001)
        assertEquals("a b c d", StatementText.clean("a${noBreakSpace}b${narrowNoBreakSpace}c${figureSpace}d"))
    }

    @Test
    fun `rejects text and malformed tokens without throwing`() {
        assertNull(StatementAmountParser.parse(""))
        assertNull(StatementAmountParser.parse("SHOP"))
        assertNull(StatementAmountParser.parse("12/05"))
        assertNull(StatementAmountParser.parse("1.2.3,4,5"))
    }

    @Test
    fun `finds symbol marked amounts in reading order and ignores bare numbers`() {
        val found = StatementAmountParser.findCurrencyAmounts("Some Cafe €6.40 €15.05 and 5 more \$7.10")
        assertEquals(listOf(6.40, 15.05, 7.10), found.map { it.value })
        assertEquals(listOf("EUR", "EUR", "USD"), found.map { it.currency })
    }

    @Test
    fun `single header currency requires exactly one`() {
        assertEquals("EUR", StatementAmountParser.singleHeaderCurrency(listOf("Limit: 1.500,00 €")))
        assertEquals("EUR", StatementAmountParser.singleHeaderCurrency(listOf("EUR Statement")))
        assertNull(StatementAmountParser.singleHeaderCurrency(listOf("€ and £")))
        assertNull(StatementAmountParser.singleHeaderCurrency(listOf("no currency here")))
        assertNull(StatementAmountParser.singleHeaderCurrency(listOf("EUROBANK")))
    }
}
