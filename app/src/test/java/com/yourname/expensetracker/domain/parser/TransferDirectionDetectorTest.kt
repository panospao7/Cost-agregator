package com.yourname.expensetracker.domain.parser

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Table-driven tests for TransferDirectionDetector.
 * Covers 50+ patterns across English and Greek languages. Every row of every table
 * is one distinct case; failure messages name the row id.
 */
class TransferDirectionDetectorTest {

    private lateinit var detector: TransferDirectionDetector

    @Before
    fun setup() {
        detector = TransferDirectionDetector()
    }

    private data class DirectionCase(
        val id: String,
        val title: String? = null,
        val text: String?,
        val bigText: String? = null,
        val type: ParsedTransactionType,
        val expected: ParsedTransferDirection?
    )

    private data class NameCase(
        val id: String,
        val text: String,
        val expected: String?
    )

    private fun assertDirections(cases: List<DirectionCase>) {
        cases.forEach { c ->
            val result = detector.detectDirection(c.title, c.text, c.bigText, c.type)
            assertEquals("case '${c.id}'", c.expected, result)
        }
    }

    // ==================== ENGLISH + GREEK PATTERNS ====================

    @Test
    fun `detect direction - english and greek incoming and outgoing patterns`() {
        val inc = ParsedTransferDirection.INCOMING
        val out = ParsedTransferDirection.OUTGOING
        val transfer = ParsedTransactionType.TRANSFER
        val deposit = ParsedTransactionType.DEPOSIT
        val withdrawal = ParsedTransactionType.WITHDRAWAL
        val purchase = ParsedTransactionType.PURCHASE
        assertDirections(
            listOf(
                // English incoming
                DirectionCase("en incoming - received from", text = "You received €100 from John Smith", type = transfer, expected = inc),
                DirectionCase("en incoming - deposited to", text = "Amount deposited to your account: €500", type = deposit, expected = inc),
                DirectionCase("en incoming - credited", text = "Your account has been credited with €250", type = transfer, expected = inc),
                DirectionCase("en incoming - salary deposit", title = "Salary Payment", text = "Salary deposit: €2,500.00", type = deposit, expected = inc),
                DirectionCase("en incoming - refund", text = "Refund processed: €45.99", type = deposit, expected = inc),
                DirectionCase("en incoming - transfer in", text = "Transfer IN: €300 from Savings Account", type = transfer, expected = inc),
                // English outgoing
                DirectionCase("en outgoing - sent to", text = "You sent €50 to Jane Doe", type = transfer, expected = out),
                DirectionCase("en outgoing - paid to on PURCHASE yields no direction", text = "You paid €25 to Coffee Shop", type = purchase, expected = null),
                DirectionCase("en outgoing - transfer out", text = "Transfer OUT: €200 to Joint Account", type = transfer, expected = out),
                DirectionCase("en outgoing - withdrew", text = "You withdrew €100 from ATM", type = withdrawal, expected = out),
                DirectionCase("en outgoing - debited", text = "Your account has been debited €75", type = transfer, expected = out),
                DirectionCase("en outgoing - transfer to", text = "Transfer to Mary: €150", type = transfer, expected = out),
                // Greek incoming
                DirectionCase("el incoming - pistosi", text = "Πίστωση λογαριασμού: €500", type = transfer, expected = inc),
                DirectionCase("el incoming - katathesi", text = "Κατάθεση: €1,000", type = deposit, expected = inc),
                DirectionCase("el incoming - eispraxi", text = "Είσπραξη ποσού €250", type = transfer, expected = inc),
                DirectionCase("el incoming - katathesi misthodosias", text = "Κατάθεση μισθοδοσίας: €1,800", type = deposit, expected = inc),
                DirectionCase("el incoming - code Pi", text = "Π Εμβασμα: €300", type = transfer, expected = inc),
                // Greek outgoing
                DirectionCase("el outgoing - xreosi", text = "Χρέωση λογαριασμού: €150", type = transfer, expected = out),
                DirectionCase("el outgoing - analipsi", text = "Ανάληψη: €200", type = withdrawal, expected = out),
                DirectionCase("el outgoing - metafora se", text = "Μεταφορά σε Ιωάννη: €100", type = transfer, expected = out),
                DirectionCase("el outgoing - code Chi", text = "Χ Ανάληψη: €50", type = withdrawal, expected = out),
                DirectionCase("el outgoing - pliromi on PURCHASE yields no direction", text = "Πληρωμή λογαριασμού: €85", type = purchase, expected = null)
            )
        )
    }

    // ==================== EDGE CASES ====================

    @Test
    fun `detect direction - edge cases null empty type ambiguous and priority`() {
        val out = ParsedTransferDirection.OUTGOING
        val transfer = ParsedTransactionType.TRANSFER
        assertDirections(
            listOf(
                DirectionCase("null text returns null", text = null, type = transfer, expected = null),
                DirectionCase("empty text returns null", text = "", type = transfer, expected = null),
                DirectionCase("non-transfer type returns null", text = "You received €100 from John", type = ParsedTransactionType.PURCHASE, expected = null),
                DirectionCase("ambiguous text returns null", text = "Transaction completed: €50", type = transfer, expected = null),
                // "sent" appears before "received" in the text, so it should be OUTGOING
                DirectionCase("conflicting patterns prioritizes first match", text = "You sent €100 and received €50", type = transfer, expected = out),
                DirectionCase("transfer to wins in mixed incoming wording", text = "Refund processed. Transfer to John €40", type = transfer, expected = out),
                DirectionCase("greek transfer se wins in mixed wording", text = "Επιστροφή χρημάτων, μεταφορά σε Μαρία 20,00€", type = transfer, expected = out)
            )
        )
    }

    // ==================== PROVIDER-SPECIFIC + CASE INSENSITIVITY + REAL WORLD ====================

    @Test
    fun `detect direction - revolut greek bank case insensitivity and real world examples`() {
        val inc = ParsedTransferDirection.INCOMING
        val out = ParsedTransferDirection.OUTGOING
        val transfer = ParsedTransactionType.TRANSFER
        assertDirections(
            listOf(
                // Revolut specific
                DirectionCase("revolut incoming - received from", title = "Received from John Smith", text = "€100.00 has been added to your account", type = transfer, expected = inc),
                DirectionCase("revolut outgoing - paid to", title = "You paid to Jane Doe", text = "€50.00", type = transfer, expected = out),
                DirectionCase("revolut outgoing - transfer to", title = "Transfer to Savings", text = "€200.00", type = transfer, expected = out),
                // Greek bank specific
                DirectionCase("greek bank outgoing - withdrawal", text = "Ανάληψη 100,00€ από ΚΑΡΤΑ ΣΑΣ", type = ParsedTransactionType.WITHDRAWAL, expected = out),
                DirectionCase("greek bank - card purchase yields no direction", text = "Αγορά 45,50€ με ΚΑΡΤΑ", type = ParsedTransactionType.PURCHASE, expected = null),
                DirectionCase("greek bank incoming - deposit", text = "Κατάθεση €500 στον λογαριασμό σας", type = ParsedTransactionType.DEPOSIT, expected = inc),
                // Case insensitivity
                DirectionCase("case insensitive matching", text = "YOU RECEIVED €100 FROM TEST", type = transfer, expected = inc),
                DirectionCase("mixed case greek", text = "ΠΊΣΤΩΣΗ €500", type = transfer, expected = inc),
                // Real-world examples
                DirectionCase(
                    "real world - revolut incoming notification",
                    title = "Received from John Smith",
                    text = "+ €150.00",
                    bigText = "From: John Smith (GR12 3456 7890 1234 5678 9012 345)",
                    type = transfer,
                    expected = inc
                ),
                DirectionCase(
                    "real world - greek bank transfer notification",
                    title = "Ειδοποίηση Συναλλαγής",
                    text = "Χ Μεταφορά: €200.00 προς ΜΑΡΙΑ ΠΑΠΑΔΟΠΟΥΛΟΥ",
                    type = transfer,
                    expected = out
                ),
                DirectionCase(
                    "real world - salary deposit notification",
                    title = "Salary Deposit",
                    text = "Your salary of €2,500.00 has been deposited",
                    type = ParsedTransactionType.DEPOSIT,
                    expected = inc
                ),
                DirectionCase(
                    "real world - atm withdrawal notification",
                    title = "ATM Withdrawal",
                    text = "You withdrew €100.00 from ATM #1234",
                    type = ParsedTransactionType.WITHDRAWAL,
                    expected = out
                )
            )
        )
    }

    // ==================== ACCOUNT NAME EXTRACTION ====================

    @Test
    fun `extract account name - table of from to greek trailing details and no match`() {
        val cases = listOf(
            NameCase("from pattern", "You received €100 from John Smith", "John Smith"),
            NameCase("to pattern", "You sent €50 to Jane Doe", "Jane Doe"),
            NameCase("greek from pattern", "Μεταφορά από Ιωάννης Παπαδόπουλος", "Ιωάννης Παπαδόπουλος"),
            NameCase("greek to pattern", "Μεταφορά προς Μαρία", "Μαρία"),
            NameCase("strips trailing greek amount details", "Μεταφορά από Ιωάννης Παπαδόπουλος ποσό 20,00€", "Ιωάννης Παπαδόπουλος"),
            NameCase("strips trailing iban details", "Μεταφορά από Ιωάννης Παπαδόπουλος IBAN GR1601101250000000012300695", "Ιωάννης Παπαδόπουλος"),
            NameCase("no match returns null", "Transaction completed: €50", null)
        )
        cases.forEach { c ->
            val result = detector.extractAccountName(null, c.text, null)
            assertEquals("case '${c.id}'", c.expected, result)
        }
    }

    // ==================== ACCURACY THRESHOLD ====================

    @Test
    fun `accuracy - should detect 90% of clear patterns`() {
        // Test 20 clear patterns - we expect at least 18 (90%) to be detected correctly
        val testCases = listOf(
            // Incoming
            "You received €100" to ParsedTransferDirection.INCOMING,
            "Amount deposited: €50" to ParsedTransferDirection.INCOMING,
            "Account credited €200" to ParsedTransferDirection.INCOMING,
            "Salary deposit €3000" to ParsedTransferDirection.INCOMING,
            "Refund €45" to ParsedTransferDirection.INCOMING,
            "Transfer IN €100" to ParsedTransferDirection.INCOMING,
            "Πίστωση €500" to ParsedTransferDirection.INCOMING,
            "Κατάθεση €1000" to ParsedTransferDirection.INCOMING,
            "Είσπραξη €250" to ParsedTransferDirection.INCOMING,
            "Π Εμβασμα €300" to ParsedTransferDirection.INCOMING,
            // Outgoing
            "You sent €50" to ParsedTransferDirection.OUTGOING,
            "Transfer OUT €200" to ParsedTransferDirection.OUTGOING,
            "You withdrew €100" to ParsedTransferDirection.OUTGOING,
            "Account debited €75" to ParsedTransferDirection.OUTGOING,
            "Transfer to Mary €150" to ParsedTransferDirection.OUTGOING,
            "Χρέωση €150" to ParsedTransferDirection.OUTGOING,
            "Ανάληψη €200" to ParsedTransferDirection.OUTGOING,
            "Μεταφορά σε Ιωάννη €100" to ParsedTransferDirection.OUTGOING,
            "Χ Ανάληψη €50" to ParsedTransferDirection.OUTGOING,
            "Money sent to John" to ParsedTransferDirection.OUTGOING
        )

        var correct = 0
        testCases.forEach { (text, expected) ->
            val result = detector.detectDirection(null, text, null, ParsedTransactionType.TRANSFER)
            if (result == expected) correct++
        }

        val accuracy = correct.toFloat() / testCases.size
        assertTrue(
            "Accuracy should be >= 90%, but was ${accuracy * 100}%",
            accuracy >= 0.90f
        )
    }

    // ==================== PATTERN VALIDATION PROPERTIES ====================

    @Test
    fun `validate - incoming and outgoing pattern sets are populated`() {
        assertTrue("incoming patterns not empty", detector.incomingPatterns.isNotEmpty())
        assertTrue("incoming patterns at least 15", detector.incomingPatterns.size >= 15)
        assertTrue("outgoing patterns not empty", detector.outgoingPatterns.isNotEmpty())
        assertTrue("outgoing patterns at least 15", detector.outgoingPatterns.size >= 15)
        val totalPatterns = detector.incomingPatterns.size + detector.outgoingPatterns.size
        assertTrue("Should have at least 50 patterns, but had $totalPatterns", totalPatterns >= 50)
    }
}
