package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Comprehensive tests for TransferDirectionDetector.
 * Tests 50+ patterns across English and Greek languages.
 */
class TransferDirectionDetectorTest {

    private lateinit var detector: TransferDirectionDetector

    @Before
    fun setup() {
        detector = TransferDirectionDetector()
    }

    // ==================== ENGLISH INCOMING PATTERNS ====================

    @Test
    fun `detect incoming - received from pattern`() {
        val text = "You received €100 from John Smith"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `detect incoming - deposited to pattern`() {
        val text = "Amount deposited to your account: €500"
        val result = detector.detectDirection(null, text, null, TransactionType.DEPOSIT)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `detect incoming - credited pattern`() {
        val text = "Your account has been credited with €250"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `detect incoming - salary deposit pattern`() {
        val text = "Salary deposit: €2,500.00"
        val result = detector.detectDirection("Salary Payment", text, null, TransactionType.DEPOSIT)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `detect incoming - refund pattern`() {
        val text = "Refund processed: €45.99"
        val result = detector.detectDirection(null, text, null, TransactionType.DEPOSIT)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `detect incoming - transfer in pattern`() {
        val text = "Transfer IN: €300 from Savings Account"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.INCOMING, result)
    }

    // ==================== ENGLISH OUTGOING PATTERNS ====================

    @Test
    fun `detect outgoing - sent to pattern`() {
        val text = "You sent €50 to Jane Doe"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect outgoing - paid to pattern`() {
        val text = "You paid €25 to Coffee Shop"
        val result = detector.detectDirection(null, text, null, TransactionType.PURCHASE)
        assertNull(result) // Purchase should not detect direction
    }

    @Test
    fun `detect outgoing - transfer out pattern`() {
        val text = "Transfer OUT: €200 to Joint Account"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect outgoing - withdrew pattern`() {
        val text = "You withdrew €100 from ATM"
        val result = detector.detectDirection(null, text, null, TransactionType.WITHDRAWAL)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect outgoing - debited pattern`() {
        val text = "Your account has been debited €75"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect outgoing - transfer to pattern`() {
        val text = "Transfer to Mary: €150"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    // ==================== GREEK INCOMING PATTERNS ====================

    @Test
    fun `detect incoming - greek pistosi pattern`() {
        val text = "Πίστωση λογαριασμού: €500"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `detect incoming - greek katathesi pattern`() {
        val text = "Κατάθεση: €1,000"
        val result = detector.detectDirection(null, text, null, TransactionType.DEPOSIT)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `detect incoming - greek eisrema pattern`() {
        val text = "Είσπραξη ποσού €250"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `detect incoming - greek misthos pattern`() {
        val text = "Κατάθεση μισθοδοσίας: €1,800"
        val result = detector.detectDirection(null, text, null, TransactionType.DEPOSIT)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `detect incoming - greek code pi pattern`() {
        val text = "Π Εμβασμα: €300"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.INCOMING, result)
    }

    // ==================== GREEK OUTGOING PATTERNS ====================

    @Test
    fun `detect outgoing - greek xreosi pattern`() {
        val text = "Χρέωση λογαριασμού: €150"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect outgoing - greek anilema pattern`() {
        val text = "Ανάληψη: €200"
        val result = detector.detectDirection(null, text, null, TransactionType.WITHDRAWAL)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect outgoing - greek metafora pattern`() {
        val text = "Μεταφορά σε Ιωάννη: €100"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect outgoing - greek code chi pattern`() {
        val text = "Χ Ανάληψη: €50"
        val result = detector.detectDirection(null, text, null, TransactionType.WITHDRAWAL)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect outgoing - greek pliromi pattern`() {
        val text = "Πληρωμή λογαριασμού: €85"
        val result = detector.detectDirection(null, text, null, TransactionType.PURCHASE)
        assertNull(result) // Purchase should not detect direction
    }

    // ==================== EDGE CASES ====================

    @Test
    fun `detect direction - null text returns null`() {
        val result = detector.detectDirection(null, null, null, TransactionType.TRANSFER)
        assertNull(result)
    }

    @Test
    fun `detect direction - empty text returns null`() {
        val result = detector.detectDirection(null, "", null, TransactionType.TRANSFER)
        assertNull(result)
    }

    @Test
    fun `detect direction - non-transfer type returns null`() {
        val text = "You received €100 from John"
        val result = detector.detectDirection(null, text, null, TransactionType.PURCHASE)
        assertNull(result)
    }

    @Test
    fun `detect direction - ambiguous text returns null`() {
        val text = "Transaction completed: €50"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertNull(result)
    }

    @Test
    fun `detect direction - conflicting patterns prioritizes first match`() {
        // Both sent and received present - should match first pattern found
        val text = "You sent €100 and received €50"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        // "sent" appears before "received" in the text, so it should be OUTGOING
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect direction - transfer to wins in mixed incoming wording`() {
        val text = "Refund processed. Transfer to John €40"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect direction - greek transfer se wins in mixed wording`() {
        val text = "Επιστροφή χρημάτων, μεταφορά σε Μαρία 20,00€"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    // ==================== ACCOUNT NAME EXTRACTION ====================

    @Test
    fun `extract account name - from pattern`() {
        val text = "You received €100 from John Smith"
        val result = detector.extractAccountName(null, text, null)
        assertEquals("John Smith", result)
    }

    @Test
    fun `extract account name - to pattern`() {
        val text = "You sent €50 to Jane Doe"
        val result = detector.extractAccountName(null, text, null)
        assertEquals("Jane Doe", result)
    }

    @Test
    fun `extract account name - greek from pattern`() {
        val text = "Μεταφορά από Ιωάννης Παπαδόπουλος"
        val result = detector.extractAccountName(null, text, null)
        assertEquals("Ιωάννης Παπαδόπουλος", result)
    }

    @Test
    fun `extract account name - greek to pattern`() {
        val text = "Μεταφορά προς Μαρία"
        val result = detector.extractAccountName(null, text, null)
        assertEquals("Μαρία", result)
    }

    @Test
    fun `extract account name - strips trailing greek amount details`() {
        val text = "Μεταφορά από Ιωάννης Παπαδόπουλος ποσό 20,00€"
        val result = detector.extractAccountName(null, text, null)
        assertEquals("Ιωάννης Παπαδόπουλος", result)
    }

    @Test
    fun `extract account name - strips trailing iban details`() {
        val text = "Μεταφορά από Ιωάννης Παπαδόπουλος IBAN GR1601101250000000012300695"
        val result = detector.extractAccountName(null, text, null)
        assertEquals("Ιωάννης Παπαδόπουλος", result)
    }

    @Test
    fun `extract account name - no match returns null`() {
        val text = "Transaction completed: €50"
        val result = detector.extractAccountName(null, text, null)
        assertNull(result)
    }

    // ==================== REVOLUT SPECIFIC ====================

    @Test
    fun `detect incoming - revolut received from pattern`() {
        val title = "Received from John Smith"
        val text = "€100.00 has been added to your account"
        val result = detector.detectDirection(title, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `detect outgoing - revolut paid to pattern`() {
        val title = "You paid to Jane Doe"
        val text = "€50.00"
        val result = detector.detectDirection(title, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect outgoing - revolut transfer to pattern`() {
        val title = "Transfer to Savings"
        val text = "€200.00"
        val result = detector.detectDirection(title, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    // ==================== GREEK BANK SPECIFIC ====================

    @Test
    fun `detect outgoing - greek bank withdrawal pattern`() {
        val text = "Ανάληψη 100,00€ από ΚΑΡΤΑ ΣΑΣ"
        val result = detector.detectDirection(null, text, null, TransactionType.WITHDRAWAL)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `detect outgoing - greek bank card purchase`() {
        val text = "Αγορά 45,50€ με ΚΑΡΤΑ"
        val result = detector.detectDirection(null, text, null, TransactionType.PURCHASE)
        assertNull(result)
    }

    @Test
    fun `detect incoming - greek bank deposit pattern`() {
        val text = "Κατάθεση €500 στον λογαριασμό σας"
        val result = detector.detectDirection(null, text, null, TransactionType.DEPOSIT)
        assertEquals(TransferDirection.INCOMING, result)
    }

    // ==================== CASE INSENSITIVITY ====================

    @Test
    fun `detect direction - case insensitive matching`() {
        val text = "YOU RECEIVED €100 FROM TEST"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `detect direction - mixed case greek`() {
        val text = "ΠΊΣΤΩΣΗ €500"
        val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.INCOMING, result)
    }

    // ==================== REAL-WORLD EXAMPLES ====================

    @Test
    fun `real world - revolut incoming notification`() {
        val title = "Received from John Smith"
        val text = "+ €150.00"
        val bigText = "From: John Smith (GR12 3456 7890 1234 5678 9012 345)"
        val result = detector.detectDirection(title, text, bigText, TransactionType.TRANSFER)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `real world - greek bank transfer notification`() {
        val title = "Ειδοποίηση Συναλλαγής"
        val text = "Χ Μεταφορά: €200.00 προς ΜΑΡΙΑ ΠΑΠΑΔΟΠΟΥΛΟΥ"
        val result = detector.detectDirection(title, text, null, TransactionType.TRANSFER)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    @Test
    fun `real world - salary deposit notification`() {
        val title = "Salary Deposit"
        val text = "Your salary of €2,500.00 has been deposited"
        val result = detector.detectDirection(title, text, null, TransactionType.DEPOSIT)
        assertEquals(TransferDirection.INCOMING, result)
    }

    @Test
    fun `real world - atm withdrawal notification`() {
        val title = "ATM Withdrawal"
        val text = "You withdrew €100.00 from ATM #1234"
        val result = detector.detectDirection(title, text, null, TransactionType.WITHDRAWAL)
        assertEquals(TransferDirection.OUTGOING, result)
    }

    // ==================== ACCURACY THRESHOLD ====================

    @Test
    fun `accuracy - should detect 90% of clear patterns`() {
        // Test 20 clear patterns - we expect at least 18 (90%) to be detected correctly
        val testCases = listOf(
            // Incoming
            "You received €100" to TransferDirection.INCOMING,
            "Amount deposited: €50" to TransferDirection.INCOMING,
            "Account credited €200" to TransferDirection.INCOMING,
            "Salary deposit €3000" to TransferDirection.INCOMING,
            "Refund €45" to TransferDirection.INCOMING,
            "Transfer IN €100" to TransferDirection.INCOMING,
            "Πίστωση €500" to TransferDirection.INCOMING,
            "Κατάθεση €1000" to TransferDirection.INCOMING,
            "Είσπραξη €250" to TransferDirection.INCOMING,
            "Π Εμβασμα €300" to TransferDirection.INCOMING,
            // Outgoing
            "You sent €50" to TransferDirection.OUTGOING,
            "Transfer OUT €200" to TransferDirection.OUTGOING,
            "You withdrew €100" to TransferDirection.OUTGOING,
            "Account debited €75" to TransferDirection.OUTGOING,
            "Transfer to Mary €150" to TransferDirection.OUTGOING,
            "Χρέωση €150" to TransferDirection.OUTGOING,
            "Ανάληψη €200" to TransferDirection.OUTGOING,
            "Μεταφορά σε Ιωάννη €100" to TransferDirection.OUTGOING,
            "Χ Ανάληψη €50" to TransferDirection.OUTGOING,
            "Money sent to John" to TransferDirection.OUTGOING
        )

        var correct = 0
        testCases.forEach { (text, expected) ->
            val result = detector.detectDirection(null, text, null, TransactionType.TRANSFER)
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
    fun `validate - incoming patterns not empty`() {
        assertTrue(detector.incomingPatterns.isNotEmpty())
        assertTrue(detector.incomingPatterns.size >= 15)
    }

    @Test
    fun `validate - outgoing patterns not empty`() {
        assertTrue(detector.outgoingPatterns.isNotEmpty())
        assertTrue(detector.outgoingPatterns.size >= 15)
    }

    @Test
    fun `validate - total patterns at least 50`() {
        val totalPatterns = detector.incomingPatterns.size + detector.outgoingPatterns.size
        assertTrue("Should have at least 50 patterns, but had $totalPatterns", totalPatterns >= 50)
    }
}
