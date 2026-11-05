package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransferDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterpartyLabelTest {
    private val ibanLike = Regex("""[A-Z]{2}\d{2}(?:\s?[A-Z0-9]){11,30}""")
    private val iban = StatementTextFixtures.OWN_IBAN

    @Test
    fun `name uses the To and From convention`() {
        val outgoing = CounterpartyLabel.fromName("MARIA EXAMPLE", ParsedTransferDirection.OUTGOING)!!
        assertEquals("MARIA EXAMPLE", outgoing.merchant)
        assertEquals("To: MARIA EXAMPLE", outgoing.accountName)
        val incoming = CounterpartyLabel.fromName("JOHN DOE", ParsedTransferDirection.INCOMING)!!
        assertEquals("From: JOHN DOE", incoming.accountName)
    }

    @Test
    fun `name without letters is not a name`() {
        assertNull(CounterpartyLabel.fromName("*9999", ParsedTransferDirection.INCOMING))
        assertNull(CounterpartyLabel.fromName("   ", ParsedTransferDirection.OUTGOING))
    }

    @Test
    fun `iban only counterparty becomes bank and last four digits`() {
        val label = CounterpartyLabel.fromIban(iban, "SOME BANK SA", ParsedTransferDirection.OUTGOING)
        assertEquals("SOME BANK SA ****1234", label.merchant)
        assertEquals("To: SOME BANK SA ****1234", label.accountName)
        assertFalse(ibanLike.containsMatchIn(label.merchant + label.accountName))
    }

    @Test
    fun `unknown bank falls back to Account`() {
        val label = CounterpartyLabel.fromIban(iban, null, ParsedTransferDirection.INCOMING)
        assertEquals("Account ****1234", label.merchant)
        assertEquals("From: Account ****1234", label.accountName)
        assertEquals("Account ****1234", CounterpartyLabel.fromIban(iban, "   ", ParsedTransferDirection.INCOMING).merchant)
    }

    @Test
    fun `description with a name keeps the name and drops the iban`() {
        val label = CounterpartyLabel.fromDescription("$iban MARIA EXAMPLE", "SOME BANK SA", ParsedTransferDirection.OUTGOING)
        assertEquals("MARIA EXAMPLE", label.merchant)
        assertFalse(label.accountName.contains(iban))
        assertFalse(ibanLike.containsMatchIn(label.merchant + label.accountName))
    }

    @Test
    fun `description that is only an iban uses the masked reference`() {
        val label = CounterpartyLabel.fromDescription(iban, "SOME BANK SA", ParsedTransferDirection.OUTGOING)
        assertEquals("SOME BANK SA ****1234", label.merchant)
        assertFalse(label.merchant.contains(iban))
    }

    @Test
    fun `empty description gets a generic label and never an iban`() {
        val label = CounterpartyLabel.fromDescription("", null, ParsedTransferDirection.INCOMING)
        assertEquals("Counterparty", label.merchant)
        assertEquals("From: Counterparty", label.accountName)
    }

    @Test
    fun `maskIbans masks spaced and contiguous ibans and leaves the rest`() {
        assertEquals("****1234", CounterpartyLabel.maskIbans(iban))
        assertEquals(
            "Paid ****0123 for rent",
            CounterpartyLabel.maskIbans("Paid GR96 0000 0000 0000 0000 0000 123 for rent")
        )
        assertEquals("SOME SHOP 12,50", CounterpartyLabel.maskIbans("SOME SHOP 12,50"))
    }

    @Test
    fun `maskIbans does not swallow the words that follow a known length iban`() {
        assertEquals("****1234 JOHN DOE", CounterpartyLabel.maskIbans("$iban JOHN DOE"))
    }

    @Test
    fun `two ibans separated by one space are both masked`() {
        val first = StatementTextFixtures.OWN_IBAN
        val second = StatementTextFixtures.RECIPIENT_IBAN_1
        assertEquals("****1234 ****5678", CounterpartyLabel.maskIbans("$first $second"))
        assertEquals(
            "from ****1234 to ****5678 done",
            CounterpartyLabel.maskIbans("from $first to $second done")
        )
        assertEquals(
            "****1234 ****5678 ****9999",
            CounterpartyLabel.maskIbans(
                "$first $second ${StatementTextFixtures.RECIPIENT_IBAN_2}"
            )
        )
    }

    @Test
    fun `fromDescription with two ibans keeps neither of them`() {
        val label = CounterpartyLabel.fromDescription(
            "${StatementTextFixtures.OWN_IBAN} ${StatementTextFixtures.RECIPIENT_IBAN_1}",
            "SOME BANK SA",
            ParsedTransferDirection.OUTGOING
        )
        assertEquals("SOME BANK SA ****1234", label.merchant)
        assertFalse(ibanLike.containsMatchIn(label.merchant + label.accountName))
    }

    @Test
    fun `an iban followed by a word does not eat the word`() {
        assertEquals("****1234 RENT", CounterpartyLabel.maskIbans("$iban RENT"))
        assertEquals("pay ****1234 SOME SHOP LTD", CounterpartyLabel.maskIbans("pay $iban SOME SHOP LTD"))
    }

    @Test
    fun `lowercase and short strings are left untouched`() {
        val lowercase = iban.lowercase()
        assertEquals(lowercase, CounterpartyLabel.maskIbans(lowercase))
        assertEquals("GR96 1234", CounterpartyLabel.maskIbans("GR96 1234"))
        assertEquals("AB12", CounterpartyLabel.maskIbans("AB12"))
        assertEquals("", CounterpartyLabel.maskIbans(""))
    }

    @Test
    fun `ibans of countries added to the length table do not swallow following words`() {
        val ibans = listOf(
            "SE45" + "0".repeat(16) + "4321",
            "DK50" + "0".repeat(10) + "6543",
            "NO93" + "0".repeat(7) + "8765",
            "HU42" + "0".repeat(20) + "2468",
            "CZ65" + "0".repeat(16) + "1357",
            "HR12" + "0".repeat(13) + "9753"
        )
        for (value in ibans) {
            val masked = CounterpartyLabel.maskIbans("$value SOME SHOP LTD")
            assertEquals(value.take(2), "****${value.takeLast(4)} SOME SHOP LTD", masked)
        }
    }

    @Test
    fun `scrubLongDigitRuns masks a twelve digit reference and keeps the last four`() {
        assertEquals("****0000/SOME SUBSCRIPTION", CounterpartyLabel.scrubLongDigitRuns("164500000000/SOME SUBSCRIPTION"))
        assertEquals("REF ****6789 SHOP", CounterpartyLabel.scrubLongDigitRuns("REF 1234567890123456789 SHOP"))
    }

    @Test
    fun `scrubLongDigitRuns masks card number like groups separated by spaces or hyphens`() {
        assertEquals("PAY ****3456 NOW", CounterpartyLabel.scrubLongDigitRuns("PAY 1234 5678 9012 3456 NOW"))
        assertEquals("****3456", CounterpartyLabel.scrubLongDigitRuns("1234-5678-9012-3456"))
    }

    @Test
    fun `scrubLongDigitRuns leaves short numbers and amounts untouched`() {
        assertEquals("STORE 12345 12,50", CounterpartyLabel.scrubLongDigitRuns("STORE 12345 12,50"))
        assertEquals("12345678901", CounterpartyLabel.scrubLongDigitRuns("12345678901"))
        assertEquals("1234 5678 901", CounterpartyLabel.scrubLongDigitRuns("1234 5678 901"))
        assertEquals("", CounterpartyLabel.scrubLongDigitRuns(""))
    }

    @Test
    fun `scrubLongDigitRuns masks double spaced and no-break space separated card groups`() {
        assertEquals("AB ****3456", CounterpartyLabel.scrubLongDigitRuns("AB 1234  5678  9012 3456"))
        val nbsp = 160.toChar()
        val narrow = 0x202F.toChar()
        assertEquals("PAY ****3456", CounterpartyLabel.scrubLongDigitRuns("PAY 1234${nbsp}5678${nbsp}9012${nbsp}3456"))
        assertEquals("****3456", CounterpartyLabel.scrubLongDigitRuns("1234${narrow}5678${narrow}9012${narrow}3456"))
    }

    @Test
    fun `fromName masks double spaced card groups at the first call site`() {
        val label = CounterpartyLabel.fromName("SHOP 1234  5678  9012 3456", ParsedTransferDirection.OUTGOING)!!
        assertEquals("SHOP ****3456", label.merchant)
    }

    @Test
    fun `scrubLongDigitRuns keeps short groups with double spaces untouched`() {
        assertEquals("1234  5678  901", CounterpartyLabel.scrubLongDigitRuns("1234  5678  901"))
    }

    @Test
    fun `scrubLongDigitRuns is idempotent`() {
        val once = CounterpartyLabel.scrubLongDigitRuns("164500000000 SHOP")
        assertEquals(once, CounterpartyLabel.scrubLongDigitRuns(once))
    }

    @Test
    fun `fromName never keeps a long digit run`() {
        val label = CounterpartyLabel.fromName("MARIA 123456789012", ParsedTransferDirection.OUTGOING)!!
        assertEquals("MARIA ****9012", label.merchant)
        assertEquals("To: MARIA ****9012", label.accountName)
    }

    @Test
    fun `maskIbans leaves a two letter word and a place name that only look like an iban start`() {
        assertEquals("H&M GR03 THESSALONIKI", CounterpartyLabel.maskIbans("H&M GR03 THESSALONIKI"))
        assertEquals("PAY AB12 SOMEWHERE ELSE", CounterpartyLabel.maskIbans("PAY AB12 SOMEWHERE ELSE"))
        // A real IBAN right after such text is still masked.
        assertEquals("H&M ****1234", CounterpartyLabel.maskIbans("H&M $iban"))
    }

    @Test
    fun `maskIbans does not start an iban inside a word`() {
        assertEquals("SHOPGR96000000000000", CounterpartyLabel.maskIbans("SHOPGR96000000000000"))
        assertEquals("X ****1234", CounterpartyLabel.maskIbans("X $iban"))
    }

    @Test(timeout = 20_000)
    fun `scrubLongDigitRuns handles a 100000 digit run in linear time`() {
        assertEquals("****" + "9".repeat(4), CounterpartyLabel.scrubLongDigitRuns("9".repeat(100_000)))
        assertEquals("AB ****5678 CD", CounterpartyLabel.scrubLongDigitRuns("AB " + "1".repeat(99_996) + "5678 CD"))
    }

    @Test(timeout = 20_000)
    fun `scrubLongDigitRuns handles a 100000 character digit and space run in linear time`() {
        val grouped = "1 ".repeat(50_000)
        val masked = CounterpartyLabel.scrubLongDigitRuns(grouped)
        assertEquals("****1111 ", masked)
        val hyphens = "1-".repeat(50_000)
        assertTrue(CounterpartyLabel.scrubLongDigitRuns(hyphens).startsWith("****1111"))
    }

    @Test(timeout = 20_000)
    fun `scrub functions and maskIbans survive 100000 asterisks and letters`() {
        val stars = "*".repeat(100_000)
        assertEquals(stars, CounterpartyLabel.scrubLongDigitRuns(stars))
        assertEquals(stars, CounterpartyLabel.scrubAccountNumbers(stars))
        assertEquals(stars, CounterpartyLabel.maskIbans(stars))
        val letters = "A".repeat(100_000)
        assertEquals(letters, CounterpartyLabel.maskIbans(letters))
    }

    @Test
    fun `scrubLongDigitRuns keeps its separator rules for runs next to text`() {
        // Three separators break a run; two do not.
        assertEquals("1234   5678   9012   3456", CounterpartyLabel.scrubLongDigitRuns("1234   5678   9012   3456"))
        assertEquals("A ****3456 B", CounterpartyLabel.scrubLongDigitRuns("A 1234  5678  9012  3456 B"))
        assertEquals("12,50 SHOP ****0000", CounterpartyLabel.scrubLongDigitRuns("12,50 SHOP 164500000000"))
    }

    @Test
    fun `scrubAccountNumbers masks nine to eleven digit runs including slash dot and hyphen groups`() {
        assertEquals("MARIA EXAMPLE ****8901", CounterpartyLabel.scrubAccountNumbers("MARIA EXAMPLE 12345678901"))
        assertEquals("****6789", CounterpartyLabel.scrubAccountNumbers("123456789"))
        assertEquals("X ****5678", CounterpartyLabel.scrubAccountNumbers("X 69/1234.5678"))
        assertEquals("X ****5678", CounterpartyLabel.scrubAccountNumbers("X 12-345-678-5678"))
        assertEquals("X ****6789", CounterpartyLabel.scrubAccountNumbers("X 697 123 6789"))
        // Eight digits and amounts stay.
        assertEquals("REF 12345678", CounterpartyLabel.scrubAccountNumbers("REF 12345678"))
        assertEquals("12,50", CounterpartyLabel.scrubAccountNumbers("12,50"))
    }

    @Test
    fun `fromName masks a domestic account number in merchant and account name`() {
        val label = CounterpartyLabel.fromName("MARIA EXAMPLE 12345678901", ParsedTransferDirection.OUTGOING)!!
        assertEquals("MARIA EXAMPLE ****8901", label.merchant)
        assertEquals("To: MARIA EXAMPLE ****8901", label.accountName)
        val described = CounterpartyLabel.fromDescription("JOHN DOE 6971234567", null, ParsedTransferDirection.INCOMING)
        assertEquals("JOHN DOE ****4567", described.merchant)
        assertEquals("From: JOHN DOE ****4567", described.accountName)
    }

    @Test
    fun `scrubLongDigitRuns alone keeps a store number below twelve digits`() {
        assertEquals("SOME SHOP 12345678901", CounterpartyLabel.scrubLongDigitRuns("SOME SHOP 12345678901"))
    }

    @Test
    fun `labels are bounded in length`() {
        val long = "A".repeat(200)
        val label = CounterpartyLabel.fromName(long, ParsedTransferDirection.OUTGOING)!!
        assertEquals(80, label.merchant.length)
        assertTrue(label.accountName.length <= 100)
    }
}
