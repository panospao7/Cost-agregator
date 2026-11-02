package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.repository.SourceStatsRepository
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * fix-1.0.4 closure P1 (R-SMS-1..4, FL-02): amount choice, deposit typing, bare-amount
 * rejection and ambiguity routing for [SmsParser]. All inputs are synthetic.
 */
class SmsParserAmbiguityTest {

    private lateinit var parser: SmsParser
    private lateinit var router: ConfidenceRouter

    private val smsPackage = "com.google.android.apps.messaging"

    @Before
    fun setup() {
        val currencyNormalizer = mockk<CurrencyNormalizer> {
            every { normalize(any()) } answers { firstArg() ?: "EUR" }
        }
        val merchantCleaner = mockk<MerchantCleaner> {
            every { clean(any()) } answers { firstArg() ?: "Unknown" }
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

    private fun parse(text: String, title: String = "NBG"): ParsedTransaction? =
        parser.parse(title = title, text = text, bigText = null, subText = null, packageName = smsPackage)

    // --- R-SMS-1: purchase amount wins over a trailing / leading balance ---

    @Test
    fun `R-SMS-1 charged purchase followed by Bal picks purchase amount`() = runBlocking {
        val result = parse("Card charged EUR 12.50 at SHOP. Bal EUR 500.00")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
        assertTrue(result.isAmountAmbiguous)
        assertNotEquals(RoutingDecision.AUTO_ACCEPT, router.route(result, smsPackage).decision)
    }

    @Test
    fun `R-SMS-1 charged purchase followed by Bal dot abbreviation picks purchase amount`() {
        val result = parse("Card charged EUR 12.50 at SHOP. Bal. EUR 500.00")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertTrue(result.isAmountAmbiguous)
    }

    @Test
    fun `R-SMS-1 Greek charge followed by abbreviated balance picks charge amount`() {
        val result = parse("Χρέωση 12,50 EUR στο SHOP. Υπόλ. 500,00 EUR")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertEquals(ParsedTransactionType.PURCHASE, result.type)
        assertTrue(result.isAmountAmbiguous)
    }

    @Test
    fun `R-SMS-1 Greek balance first with no purchase keyword adjacent still picks the other amount`() {
        // The purchase keyword only appears after the amounts (it satisfies the SMS gate but is
        // not adjacent to either amount). Without the abbreviation-dot fix "Υπόλ." would not mark
        // 500,00 as a balance and the pool fallback would pick the balance.
        val result = parse("Υπόλ. 500,00 EUR. Κάρτα 12,50 EUR SHOP, χρέωση")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertTrue(result.isAmountAmbiguous)
    }

    @Test
    fun `R-SMS-1 English balance abbreviation first with no adjacent purchase keyword picks other amount`() {
        val result = parse("Bal. EUR 500.00. Card EUR 12.50 SHOP, purchase")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertTrue(result.isAmountAmbiguous)
    }

    @Test
    fun `R-SMS-1 avl and saldo abbreviations are balances`() {
        val avl = parse("Purchase EUR 8.00 at SHOP. Avl EUR 300.00")
        assertNotNull(avl)
        assertEquals(8.00, avl!!.amount, 0.001)

        val saldo = parse("Purchase EUR 9.00 at SHOP. Saldo EUR 400.00")
        assertNotNull(saldo)
        assertEquals(9.00, saldo!!.amount, 0.001)
    }

    @Test
    fun `R-SMS-1 two non-balance amounts without adjacency take the FIRST not the last`() {
        // "Card charged." satisfies the SMS gate but is not adjacent to either amount.
        val result = parse("Card charged. Item EUR 12.50 at SHOP, fee EUR 1.00")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertTrue(result.isAmountAmbiguous)
    }

    @Test
    fun `R-SMS-1 charged keyword prefers the charged amount over another non-balance amount`() {
        val result = parse("Card charged EUR 12.50 at SHOP, fee EUR 1.00")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertTrue(result.isAmountAmbiguous)
    }

    @Test
    fun `R-SMS-1 global is not treated as a bal balance abbreviation`() {
        val sameClause = parse("Purchase global EUR 5.00 at SHOP")
        assertNotNull("global must not be read as a balance label", sameClause)
        assertEquals(5.00, sameClause!!.amount, 0.001)
        assertFalse(sameClause.isAmountAmbiguous)

        val dotted = parse("Purchase at GLOBAL. EUR 5.00")
        assertNotNull("GLOBAL. must not collapse into a Bal. abbreviation", dotted)
        assertEquals(5.00, dotted!!.amount, 0.001)
    }

    // --- R-SMS-2: paid / prepaid must not block DEPOSIT typing ---

    @Test
    fun `R-SMS-2 salary credited with paid by is a deposit not a purchase`() {
        val result = parse("Salary credited EUR 1,500.00, paid by ACME")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(1500.00, result.amount, 0.001)
    }

    @Test
    fun `R-SMS-2 salary credited with paid by without comma after amount is a deposit`() {
        val result = parse("Salary credited EUR 1,500.00 paid by ACME")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(1500.00, result.amount, 0.001)
    }

    @Test
    fun `R-SMS-2 prepaid card credited is a deposit`() {
        val result = parse("Prepaid card credited EUR 50.00")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(50.00, result.amount, 0.001)
    }

    @Test
    fun `R-SMS-2 a message whose only purchase cue is paid or spent is not accepted as a purchase`() {
        assertNull(parse("You paid EUR 5.00 at SHOP"))
        assertNull(parse("You spent EUR 5.00 at SHOP"))
    }

    @Test
    fun `R-SMS-2 control - real purchase keyword with credited still wins as PURCHASE`() {
        val result = parse("Purchase EUR 12.50 at SHOP, account credited with cashback")
        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(12.50, result.amount, 0.001)
    }

    // --- R-SMS-4: no bare (currency-less) amounts ---

    @Test
    fun `R-SMS-4 dated payment reminder without currency is rejected`() {
        assertNull(parse("NBG: card payment due 15.11", title = "NBG"))
    }

    @Test
    fun `R-SMS-4 purchase keyword with currency-less amount is rejected`() {
        assertNull(parse("Purchase 12.50 at COFFEE"))
        assertNull(parse("Αγορά 15,00 στο KATASTIMA"))
    }

    @Test
    fun `R-SMS-4 control - same message with an explicit currency parses`() {
        val result = parse("Purchase 12.50 EUR at COFFEE")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
    }

    // --- FL-02: purchase + balance in either order is ambiguous and routes to review ---

    @Test
    fun `FL-02 purchase then balance is ambiguous and routes to NEEDS_REVIEW`() = runBlocking {
        val result = parse("Purchase EUR 20.00 at SHOP. Available balance EUR 300.00")
        assertNotNull(result)
        assertEquals(20.00, result!!.amount, 0.001)
        assertTrue(result.isAmountAmbiguous)
        assertEquals(RoutingDecision.NEEDS_REVIEW, router.route(result, smsPackage).decision)
    }

    @Test
    fun `FL-02 balance then purchase is ambiguous and routes to NEEDS_REVIEW`() = runBlocking {
        val result = parse("Available balance EUR 300.00. Purchase EUR 20.00 at SHOP")
        assertNotNull(result)
        assertEquals(20.00, result!!.amount, 0.001)
        assertTrue(result.isAmountAmbiguous)
        assertEquals(RoutingDecision.NEEDS_REVIEW, router.route(result, smsPackage).decision)
    }

    @Test
    fun `FL-02 Greek purchase and balance in both orders are ambiguous review items`() = runBlocking {
        val purchaseFirst = parse("Αγορά 15,20 EUR στο SKLAVENITIS. Διαθέσιμο υπόλοιπο 1.245,67 EUR")
        assertNotNull(purchaseFirst)
        assertEquals(15.20, purchaseFirst!!.amount, 0.001)
        assertTrue(purchaseFirst.isAmountAmbiguous)
        assertEquals(RoutingDecision.NEEDS_REVIEW, router.route(purchaseFirst, smsPackage).decision)

        val balanceFirst = parse("Διαθέσιμο υπόλοιπο 1.245,67 EUR. Αγορά 15,20 EUR στο SKLAVENITIS")
        assertNotNull(balanceFirst)
        assertEquals(15.20, balanceFirst!!.amount, 0.001)
        assertTrue(balanceFirst.isAmountAmbiguous)
        assertEquals(RoutingDecision.NEEDS_REVIEW, router.route(balanceFirst, smsPackage).decision)
    }

    @Test
    fun `FL-02 ambiguous parse keeps the clean confidence of 0_85 so the router cap does the demotion`() {
        val result = parse("Card charged EUR 12.50 at SHOP. Bal EUR 500.00")
        assertNotNull(result)
        assertTrue(result!!.isAmountAmbiguous)
        assertEquals(0.85f, result.confidence, 0.0001f)
    }

    @Test
    fun `FL-02 clean single-amount purchase stays 0_85 and is not ambiguous`() = runBlocking {
        val result = parse("Purchase of EUR 12.50 at COFFEE", title = "Revolut")
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertFalse(result.isAmountAmbiguous)
        assertEquals(0.85f, result.confidence, 0.0001f)
        assertEquals(RoutingDecision.AUTO_ACCEPT, router.route(result, smsPackage).decision)
    }
}
