package com.yourname.expensetracker.data.email

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.EmailReceiptDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.email.provider.AmazonReceiptParser
import com.yourname.expensetracker.data.email.provider.AppleReceiptParser
import com.yourname.expensetracker.data.email.provider.ParsedEmailReceipt
import com.yourname.expensetracker.data.email.provider.ReceiptItem
import com.yourname.expensetracker.data.email.provider.UberReceiptParser
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.categorization.CategorizationResult
import com.yourname.expensetracker.domain.categorization.MatchType as CategorizationMatchType
import com.yourname.expensetracker.domain.intelligence.ml.MatchType as MerchantMatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.usecase.receipt.ProcessReceiptUseCase
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmailReceiptIngestionServiceTest {

    private val receiptParser = mockk<ReceiptParser>()
    private val processReceiptUseCase = mockk<ProcessReceiptUseCase>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>()
    private val emailReceiptDao = mockk<EmailReceiptDao>()
    private val scannedReceiptDao = mockk<ScannedReceiptDao>()
    private val amazonParser = mockk<AmazonReceiptParser>()
    private val uberParser = mockk<UberReceiptParser>()
    private val appleParser = mockk<AppleReceiptParser>()
    private val merchantNormalizer = mockk<MerchantNormalizer>()
    private val categorizationEngine = mockk<CategorizationEngine>()
    private val timeProvider = FakeTimeProvider(FIXED_NOW)

    private lateinit var service: EmailReceiptIngestionService

    @Before
    fun setup() {
        service = EmailReceiptIngestionService(
            receiptParser = receiptParser,
            processReceiptUseCase = processReceiptUseCase,
            expenseDao = expenseDao,
            emailReceiptDao = emailReceiptDao,
            scannedReceiptDao = scannedReceiptDao,
            merchantNormalizer = merchantNormalizer,
            categorizationEngine = categorizationEngine,
            timeProvider = timeProvider
        )

        setPrivateField(service, "amazonParser", amazonParser)
        setPrivateField(service, "uberParser", uberParser)
        setPrivateField(service, "appleParser", appleParser)

        every { receiptParser.lineItemsToJson(any()) } returns "[]"
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
            val raw = firstArg<String>()
            MerchantLookupResult(
                canonical = MerchantCanonical(
                    id = 1L,
                    normalizedName = raw,
                    searchKey = raw.lowercase()
                ),
                alias = null,
                confidence = 1.0f,
                matchType = MerchantMatchType.EXACT_MATCH
            )
        }
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = 42L,
            categoryName = "Shopping",
            confidence = 0.88,
            matchType = CategorizationMatchType.EXACT,
            explanation = "test"
        )

        every { amazonParser.canParse(any(), any(), any()) } answers {
            firstArg<String>().contains("amazon", ignoreCase = true)
        }
        every { uberParser.canParse(any(), any(), any()) } answers {
            firstArg<String>().contains("uber", ignoreCase = true)
        }
        every { appleParser.canParse(any(), any(), any()) } answers {
            firstArg<String>().contains("apple", ignoreCase = true)
        }
    }

    @Test
    fun `processEmailReceipt detects Amazon provider and creates expense`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 1234.50,
            currency = "USD",
            date = FIXED_NOW,
            items = listOf(ReceiptItem("Test item", 1, 1234.50, 1234.50)),
            orderNumber = "123-456",
            confidence = 0.9
        )

        val scannedSlot = slot<ScannedReceipt>()
        val emailSourceSlot = slot<EmailReceiptSource>()
        val expenseSlot = slot<Expense>()

        coEvery { emailReceiptDao.getByFingerprint(any()) } returns null
        coEvery { scannedReceiptDao.getRecentReceipts(any()) } returns emptyList()
        coEvery { scannedReceiptDao.insert(capture(scannedSlot)) } returns 501L
        coEvery { emailReceiptDao.insert(capture(emailSourceSlot)) } returns 1L
        coEvery { expenseDao.insertAtomic(capture(expenseSlot)) } returns 901L
        coEvery { scannedReceiptDao.getById(501L) } returns null

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$1234.50 Order Date: March 03, 2026 Order # 123-456",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-amazon-1"
        )

        assertTrue(result is EmailReceiptResult.Success)
        val success = result as EmailReceiptResult.Success
        assertEquals(501L, success.receiptId)
        assertEquals(listOf(901L), success.expenseIds)

        assertEquals("amazon", emailSourceSlot.captured.provider)
        assertNull(scannedSlot.captured.imagePath)
        assertApproxEquals(1234.50, expenseSlot.captured.amount, 0.0001)
        assertEquals("Amazon", expenseSlot.captured.merchant)
    }

    @Test
    fun `processEmailReceipt detects Uber and Apple providers`() = runTest {
        every { uberParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Uber",
            amount = 23.40,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "UBER-123",
            confidence = 0.9
        )
        every { appleParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Apple",
            amount = 5.99,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "999-888",
            confidence = 0.9
        )

        coEvery { emailReceiptDao.getByFingerprint(any()) } returns null
        coEvery { scannedReceiptDao.getRecentReceipts(any()) } returns emptyList()
        coEvery { scannedReceiptDao.insert(any()) } returnsMany listOf(601L, 602L)

        val providerSlots = mutableListOf<EmailReceiptSource>()
        coEvery { emailReceiptDao.insert(any()) } answers {
            providerSlots += firstArg<EmailReceiptSource>()
            providerSlots.size.toLong()
        }
        coEvery { expenseDao.insertAtomic(any()) } returnsMany listOf(1001L, 1002L)
        coEvery { scannedReceiptDao.getById(any()) } returns null

        val uberResult = service.processEmailReceipt(
            emailBody = "Total \$23.40 Trip ID UBER-123 Trip date: March 05, 2026",
            sender = "receipts@uber.com",
            subject = "Your Uber trip receipt",
            receivedAt = FIXED_NOW,
            messageId = "msg-uber-1"
        )
        val appleResult = service.processEmailReceipt(
            emailBody = "Total \$5.99 Document No: 999-888 Date: March 06, 2026",
            sender = "receipts@apple.com",
            subject = "Your Apple receipt",
            receivedAt = FIXED_NOW,
            messageId = "msg-apple-1"
        )

        assertTrue(uberResult is EmailReceiptResult.Success)
        assertTrue(appleResult is EmailReceiptResult.Success)
        assertEquals(listOf("uber", "apple"), providerSlots.map { it.provider })
    }

    @Test
    fun `processEmailReceipt deduplicates by locale-safe fingerprint using Locale_US formatting`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 1234.50,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "123-456",
            confidence = 0.9
        )

        val fingerprintSlot = slot<String>()
        coEvery { emailReceiptDao.getByFingerprint(capture(fingerprintSlot)) } returns EmailReceiptSource(
            id = 12L,
            receiptId = 333L,
            emailSender = "old@amazon.com",
            emailSubject = "old",
            emailMessageId = "old-msg",
            parsedAt = FIXED_NOW - 5_000L,
            provider = "amazon",
            confidence = 0.9,
            fingerprint = "x"
        )

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$1234.50 Order Date: March 03, 2026",
            sender = "auto-confirm@amazon.com",
            subject = "order",
            receivedAt = FIXED_NOW,
            messageId = "msg-dedupe-1"
        )

        assertTrue(result is EmailReceiptResult.Duplicate)
        assertEquals(333L, (result as EmailReceiptResult.Duplicate).existingReceiptId)
        assertTrue(fingerprintSlot.captured.contains("amazon_1234.50_"))

        coVerify(exactly = 0) { scannedReceiptDao.insert(any()) }
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }

    @Test
    fun `processEmailReceipt links duplicate when matching scanned receipt exists`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 12.34,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "123-456",
            confidence = 0.9
        )

        coEvery { emailReceiptDao.getByFingerprint(any()) } returns null
        coEvery { scannedReceiptDao.getRecentReceipts(any()) } returns listOf(
            ScannedReceipt(
                id = 444L,
                imagePath = "/tmp/receipt.jpg",
                rawOcrText = "old",
                parsedTotal = 12.34,
                parsedMerchant = "Amazon",
                parsedDate = FIXED_NOW,
                parsedItems = null,
                parsedTaxAmount = null,
                confidence = 0.9f,
                createdAt = FIXED_NOW - 2_000L
            )
        )
        val emailSourceSlot = slot<EmailReceiptSource>()
        coEvery { emailReceiptDao.insert(capture(emailSourceSlot)) } returns 1L

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$12.34 Order # 123-456",
            sender = "auto-confirm@amazon.com",
            subject = "order",
            receivedAt = FIXED_NOW,
            messageId = "msg-scan-dup-1"
        )

        assertTrue(result is EmailReceiptResult.Duplicate)
        assertEquals(444L, (result as EmailReceiptResult.Duplicate).existingReceiptId)
        assertEquals(444L, emailSourceSlot.captured.receiptId)

        coVerify(exactly = 0) { scannedReceiptDao.insert(any()) }
        coVerify(exactly = 0) { expenseDao.insertAtomic(any()) }
    }

    @Test
    fun `processEmailReceipt returns ParseError for malformed email`() = runTest {
        val result = service.processEmailReceipt(
            emailBody = "<html><body>broken receipt without total and structure</body>",
            sender = "auto-confirm@amazon.com",
            subject = "Order update",
            receivedAt = FIXED_NOW,
            messageId = "msg-malformed-1"
        )

        assertTrue(result is EmailReceiptResult.ParseError)
        assertTrue((result as EmailReceiptResult.ParseError).reason.contains("parse", ignoreCase = true))
    }

    @Test
    fun `processEmailReceipt returns ParseError for unknown provider with unparsable body`() = runTest {
        val result = service.processEmailReceipt(
            emailBody = "hello there no receipt here",
            sender = "random@unknown.org",
            subject = "misc",
            receivedAt = FIXED_NOW,
            messageId = "msg-unknown-1"
        )

        assertTrue(result is EmailReceiptResult.ParseError)
        assertTrue((result as EmailReceiptResult.ParseError).reason.contains("parse", ignoreCase = true))
    }

    companion object {
        private const val FIXED_NOW = 1_730_000_000_000L
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any) {
        val field = target::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
