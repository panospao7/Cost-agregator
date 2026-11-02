package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.repository.SourceStatsRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.RoutingResult
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SmsParserTest {
    private lateinit var parser: SmsParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner
    private lateinit var router: ConfidenceRouter

    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } answers { firstArg() ?: "EUR" }
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() ?: "Unknown" }
        }
        parser = SmsParser(currencyNormalizer, merchantCleaner)

        val sourceStatsRepository = mockk<SourceStatsRepository>(relaxed = true)
        val userCorrectionRepository = mockk<UserCorrectionRepository>(relaxed = true)
        val classifier = mockk<TransactionClassifier>(relaxed = true)
        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns 1_700_000_000_000L
        coEvery { sourceStatsRepository.getByPackage(any()) } returns null
        coEvery { userCorrectionRepository.getMerchantStats(any()) } returns
            UserCorrectionDao.MerchantCorrectionStats(total = 0, rejections = 0)
        coEvery { userCorrectionRepository.getPackageStats(any()) } returns
            UserCorrectionDao.PackageCorrectionStats(total = 0, rejections = 0)
        coEvery { userCorrectionRepository.hasPreviousApprovals(any(), any()) } returns false
        coEvery { classifier.getStats() } returns ClassifierStats(0, 0, 0, false)
        coEvery { classifier.predict(any()) } returns 0.5f

        router = ConfidenceRouter(
            writeBarrier = writeBarrier,
            sourceStatsRepository = sourceStatsRepository,
            userCorrectionRepository = userCorrectionRepository,
            classifier = classifier,
            timeProvider = timeProvider
        )
    }

    private suspend fun routeParsed(parsed: ParsedTransaction): RoutingResult =
        router.route(parsed, "com.google.android.apps.messaging")

    @Test
    fun `parse bank SMS with Greek keywords`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 15,00 EUR στο KATASTIMA στις 07/02",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(15.00, result!!.amount, 0.01)
    }

    @Test
    fun `parse bank SMS with Greeklish keywords`() {
        val result = parser.parse(
            title = "Alpha",
            text = "Agora 22,50 EUR sto SUPERMARKET",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(22.50, result!!.amount, 0.01)
    }

    @Test
    fun `reject non-bank sender`() {
        val result = parser.parse(
            title = "John",
            text = "Hey, can you send me 50 EUR?",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `reject bank sender without transaction keywords`() {
        val result = parser.parse(
            title = "NBG",
            text = "Welcome to our new mobile app!",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `reject null title`() {
        val result = parser.parse(
            title = null,
            text = "Αγορά 15,00 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `amount bounds check - too small`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 0,05 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `supports all messaging packages`() {
        val packages = parser.supportedPackages
        assertTrue(packages.contains("com.google.android.apps.messaging"))
        assertTrue(packages.contains("com.samsung.android.messaging"))
        assertTrue(packages.contains("com.android.mms"))
    }

    @Test
    fun `parse outgoing transfer SMS as transfer with outgoing direction`() {
        val result = parser.parse(
            title = "NBG",
            text = "Transfer 120,00 EUR sent to John Doe",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(ParsedTransferDirection.OUTGOING, result.transferDirection)
    }

    // --- Grouped-amount regressions ---

    @Test
    fun `parse grouped US amount in purchase SMS`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 1,234.56 EUR στο IKEA στις 10/04",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull("Grouped US amount should parse", result)
        assertEquals(1234.56, result!!.amount, 0.01)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
    }

    @Test
    fun `parse grouped EU amount in purchase SMS`() {
        val result = parser.parse(
            title = "Alpha",
            text = "Πληρωμή 1.234,56 EUR στο MEDIAMARKT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull("Grouped EU amount should parse", result)
        assertEquals(1234.56, result!!.amount, 0.01)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
    }

    @Test
    fun `parse grouped amount in transfer SMS`() {
        val result = parser.parse(
            title = "NBG",
            text = "Transfer 2,500.00 EUR sent to Maria Papadopoulou",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull("Grouped amount in transfer should parse", result)
        assertEquals(2500.00, result!!.amount, 0.01)
        assertEquals(ParsedTransactionType.TRANSFER, result.type)
        assertEquals(ParsedTransferDirection.OUTGOING, result.transferDirection)
    }

    @Test
    fun `parse grouped amount with currency prefix`() {
        val result = parser.parse(
            title = "Revolut",
            text = "Purchase EUR 1,100.50 at ZARA",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull("Grouped amount after currency prefix should parse", result)
        assertEquals(1100.50, result!!.amount, 0.01)
    }

    // --- Ambiguous transfer direction regressions ---

    @Test
    fun `ambiguous transfer SMS returns null direction`() {
        // "transfer" keyword with no incoming/outgoing directional evidence
        val result = parser.parse(
            title = "NBG",
            text = "Transfer 500,00 EUR account 1234",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertNull("Ambiguous transfer should have null direction", result.transferDirection)
    }

    @Test
    fun `ambiguous deposit SMS returns null direction`() {
        // "deposit" keyword without explicit incoming evidence
        val result = parser.parse(
            title = "NBG",
            text = "Deposit 300,00 EUR account 5678",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertNull("Ambiguous deposit should have null direction", result.transferDirection)
    }

    @Test
    fun `explicit incoming deposit retains INCOMING direction`() {
        val result = parser.parse(
            title = "NBG",
            text = "Deposit 800,00 EUR credited to your account",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(ParsedTransferDirection.INCOMING, result.transferDirection)
    }

    @Test
    fun `explicit incoming transfer retains INCOMING direction`() {
        val result = parser.parse(
            title = "NBG",
            text = "Transfer 450,00 EUR received from John Doe",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(ParsedTransferDirection.INCOMING, result.transferDirection)
    }

    @Test
    fun `ambiguous direction suppresses transferAccountName`() {
        val result = parser.parse(
            title = "NBG",
            text = "Transfer 200,00 EUR account 9876",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertNull("Ambiguous direction should suppress transferAccountName", result!!.transferAccountName)
    }

    // --- FL-02: Balance vs purchase amount & ConfidenceRouter routing ---

    @Test
    fun `clean single-amount purchase SMS extracts exact amount and routes to AUTO_ACCEPT`() = runBlocking {
        val result = parser.parse(
            title = "Revolut",
            text = "Purchase of EUR 12.50 at COFFEE",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)

        val routed = routeParsed(result)
        assertEquals(RoutingDecision.AUTO_ACCEPT, routed.decision)
    }

    @Test
    fun `English bank SMS with balance before purchase extracts purchase amount and does not auto-accept`() = runBlocking {
        val result = parser.parse(
            title = "Eurobank",
            text = "Available balance EUR 1,245.67. Purchase of EUR 12.50 at COFFEE SHOP on 10/04",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)

        val routed = routeParsed(result)
        assertNotEquals(RoutingDecision.AUTO_ACCEPT, routed.decision)
    }

    @Test
    fun `Greek bank SMS with ypoloipo before agora extracts purchase amount and does not auto-accept`() = runBlocking {
        val result = parser.parse(
            title = "NBG",
            text = "Διαθέσιμο υπόλοιπο 1.245,67 EUR. Αγορά 15,20 EUR στο SKLAVENITIS στις 07/02",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(15.20, result!!.amount, 0.001)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)

        val routed = routeParsed(result)
        assertNotEquals(RoutingDecision.AUTO_ACCEPT, routed.decision)
    }

    @Test
    fun `Greek bank SMS with pliromi before ypoloipo extracts payment amount`() {
        val result = parser.parse(
            title = "Eurobank",
            text = "Πληρωμή 42,80 EUR στο ΔΕΗ στις 10/04. Υπόλοιπο 980,15 EUR",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(42.80, result!!.amount, 0.001)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
    }

    @Test
    fun `Greek bank SMS with ypoloipo before xreosi extracts debit amount`() {
        val result = parser.parse(
            title = "Alpha",
            text = "Υπόλοιπο 540,00 EUR. Χρέωση κάρτας 18,90 EUR στο EVEREST στις 11/04",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(18.90, result!!.amount, 0.001)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
    }

    @Test
    fun `balance-only bank SMS does not produce phantom purchase expense`() {
        val greekBalanceOnly = parser.parse(
            title = "NBG",
            text = "Ενημέρωση συναλλαγών: Το διαθέσιμο υπόλοιπο του λογαριασμού σας είναι 1.250,00 EUR",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(greekBalanceOnly)

        val englishBalanceOnly = parser.parse(
            title = "Eurobank",
            text = "Account update: Your available balance is EUR 1,250.00",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(englishBalanceOnly)
    }

    @Test
    fun `multi-amount SMS without explicit balance label does not auto-accept`() = runBlocking {
        val result = parser.parse(
            title = "Revolut",
            text = "Purchase of EUR 12.50 at COFFEE (card limit EUR 500.00)",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        if (result != null) {
            val routed = routeParsed(result)
            assertNotEquals(RoutingDecision.AUTO_ACCEPT, routed.decision)
        }
    }
}
