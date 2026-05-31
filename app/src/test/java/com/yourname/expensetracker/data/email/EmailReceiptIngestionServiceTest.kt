package com.yourname.expensetracker.data.email

import com.yourname.expensetracker.data.email.provider.AmazonReceiptParser
import com.yourname.expensetracker.data.email.provider.AppleReceiptParser
import com.yourname.expensetracker.data.email.provider.ParsedEmailReceipt
import com.yourname.expensetracker.data.email.provider.ReceiptItem
import com.yourname.expensetracker.data.email.provider.UberReceiptParser
import com.yourname.expensetracker.domain.intelligence.ml.MatchType as MerchantMatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.EmailReceiptData
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.lifecycle.EmailReceiptProcessResult
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmailReceiptIngestionServiceTest {

    private val receiptParser = mockk<ReceiptParser>()
    private val receiptLifecycleCoordinator = mockk<ReceiptLifecycleCoordinator>(relaxed = true)
    private val amazonParser = mockk<AmazonReceiptParser>()
    private val uberParser = mockk<UberReceiptParser>()
    private val appleParser = mockk<AppleReceiptParser>()
    private val merchantNormalizer = mockk<MerchantNormalizer>()
    private val writeBarrier = mockk<com.yourname.expensetracker.data.backup.DatabaseWriteBarrier>(relaxed = true)

    private lateinit var service: EmailReceiptIngestionService

    @Before
    fun setup() {
        service = EmailReceiptIngestionService(
            receiptParser = receiptParser,
            receiptLifecycleCoordinator = receiptLifecycleCoordinator,
            merchantNormalizer = merchantNormalizer,
            writeBarrier = writeBarrier
        )

        // Inject provider parsers (normally created as private fields)
        setPrivateField(service, "amazonParser", amazonParser)
        setPrivateField(service, "uberParser", uberParser)
        setPrivateField(service, "appleParser", appleParser)

        every { receiptParser.lineItemsToJson(any()) } returns "[]"
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
            val raw = firstArg<String>()
            com.yourname.expensetracker.data.database.entity.MerchantCanonical(
                id = 1L,
                normalizedName = raw,
                searchKey = raw.lowercase()
            ).let { canonical ->
                MerchantLookupResult(
                    canonical = canonical,
                    alias = null,
                    confidence = 1.0f,
                    matchType = MerchantMatchType.EXACT_MATCH
                )
            }
        }

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

        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            EmailReceiptProcessResult.Success(receiptId = 501L, expenseIds = listOf(901L))

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

        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            EmailReceiptProcessResult.Success(receiptId = 601L, expenseIds = listOf(1001L))

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

        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            EmailReceiptProcessResult.Duplicate(existingReceiptId = 333L)

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$1234.50 Order Date: March 03, 2026",
            sender = "auto-confirm@amazon.com",
            subject = "order",
            receivedAt = FIXED_NOW,
            messageId = "msg-dedupe-1"
        )

        assertTrue(result is EmailReceiptResult.Duplicate)
        assertEquals(333L, (result as EmailReceiptResult.Duplicate).existingReceiptId)
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

        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            EmailReceiptProcessResult.Duplicate(existingReceiptId = 444L)

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$12.34 Order # 123-456",
            sender = "auto-confirm@amazon.com",
            subject = "order",
            receivedAt = FIXED_NOW,
            messageId = "msg-scan-dup-1"
        )

        assertTrue(result is EmailReceiptResult.Duplicate)
        assertEquals(444L, (result as EmailReceiptResult.Duplicate).existingReceiptId)
    }

    @Test
    fun `processEmailReceipt returns ParseError for malformed email`() = runTest {
        // No parser matches or can parse this body → parseEmailReceipt returns null
        every { amazonParser.parse(any(), any()) } returns null
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
        every { amazonParser.parse(any(), any()) } returns null
        every { uberParser.parse(any(), any()) } returns null
        every { appleParser.parse(any(), any()) } returns null
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

    // -------------------------------------------------------------------------
    // Batch-6 fix: coordinator-level dedup (messageId guard is inside coordinator)
    // -------------------------------------------------------------------------

    @Test
    fun `processEmailReceipt returns Duplicate immediately when nonblank messageId already exists`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 99.99,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "ORD-MID-1",
            confidence = 0.9
        )

        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            EmailReceiptProcessResult.Duplicate(existingReceiptId = 200L)

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$99.99 Order # ORD-MID-1",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-known-1"
        )

        assertTrue("Expected Duplicate but got $result", result is EmailReceiptResult.Duplicate)
        assertEquals(200L, (result as EmailReceiptResult.Duplicate).existingReceiptId)
    }

    @Test
    fun `processEmailReceipt skips messageId guard when messageId is blank`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 55.00,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "ORD-BLANK",
            confidence = 0.9
        )

        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            EmailReceiptProcessResult.Success(receiptId = 700L, expenseIds = listOf(800L))

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$55.00 Order # ORD-BLANK",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "" // blank
        )

        assertTrue("Expected Success for blank messageId but got $result", result is EmailReceiptResult.Success)
    }

    @Test
    fun `processEmailReceipt skips messageId guard when messageId is whitespace only`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 33.00,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "ORD-WS",
            confidence = 0.9
        )

        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            EmailReceiptProcessResult.Success(receiptId = 701L, expenseIds = listOf(801L))

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$33.00 Order # ORD-WS",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "   " // whitespace only – treated as blank
        )

        assertTrue("Expected Success for whitespace messageId but got $result", result is EmailReceiptResult.Success)
    }

    @Test
    fun `processEmailReceipt messageId guard does not fire for unknown nonblank messageId`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 77.77,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "ORD-NEW",
            confidence = 0.9
        )

        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            EmailReceiptProcessResult.Success(receiptId = 750L, expenseIds = listOf(850L))

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$77.77 Order # ORD-NEW",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-brand-new"
        )

        assertTrue("Expected Success when messageId is new but got $result", result is EmailReceiptResult.Success)
    }

    @Test
    fun `insertOrIgnore preserves original row when duplicate emailMessageId is seen`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 42.00,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "ORD-DEDUP-1",
            confidence = 0.9
        )

        // First ingestion — should succeed.
        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returnsMany
            listOf(
                EmailReceiptProcessResult.Success(receiptId = 600L, expenseIds = listOf(900L)),
                EmailReceiptProcessResult.Duplicate(existingReceiptId = 600L)
            )

        val result1 = service.processEmailReceipt(
            emailBody = "Order Total: \$42.00 Order # ORD-DEDUP-1 Order Date: March 03, 2026",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-dedup-test-1"
        )
        assertTrue("First insert should succeed", result1 is EmailReceiptResult.Success)

        // Second ingestion with the same messageId: guard catches it via coordinator → Duplicate
        val result2 = service.processEmailReceipt(
            emailBody = "Order Total: \$42.00 Order # ORD-DEDUP-1 Order Date: March 03, 2026",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-dedup-test-1"
        )

        assertTrue("Second call must be Duplicate", result2 is EmailReceiptResult.Duplicate)
        assertEquals(600L, (result2 as EmailReceiptResult.Duplicate).existingReceiptId)
    }

    @Test
    fun `processEmailReceipt returns ParseError when expense creation yields no ids`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 18.25,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "ORD-NO-EXPENSE",
            confidence = 0.9
        )

        // Coordinator returns Error → service maps to ParseError
        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            EmailReceiptProcessResult.Error("Expense creation failed")

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$18.25 Order # ORD-NO-EXPENSE",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-no-expense"
        )

        assertTrue("Expected ParseError when expense creation fails but got $result", result is EmailReceiptResult.ParseError)
    }

    // -------------------------------------------------------------------------
    // P11-CURRENT-007: content dedup fingerprint must distinguish distinct orders
    // and currencies. The fingerprint is private and surfaced as the 2nd positional
    // argument (fingerprint) passed to coordinator.processEmailReceipt.
    // -------------------------------------------------------------------------

    @Test
    fun `same_merchant_same_amount_same_day_different_order_number_not_duplicate`() = runTest {
        val fingerprints = mutableListOf<String>()
        every { amazonParser.parse(any(), any()) } returnsMany listOf(
            ParsedEmailReceipt(
                merchant = "Amazon",
                amount = 50.00,
                currency = "USD",
                date = FIXED_NOW,
                items = emptyList(),
                orderNumber = "ORDER-A",
                confidence = 0.9
            ),
            ParsedEmailReceipt(
                merchant = "Amazon",
                amount = 50.00,
                currency = "USD",
                date = FIXED_NOW,
                items = emptyList(),
                orderNumber = "ORDER-B",
                confidence = 0.9
            )
        )

        coEvery {
            receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            fingerprints.add(secondArg())
            EmailReceiptProcessResult.Success(1L, emptyList())
        }

        service.processEmailReceipt(
            emailBody = "Order Total: \$50.00 Order # ORDER-A",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-order-a"
        )
        service.processEmailReceipt(
            emailBody = "Order Total: \$50.00 Order # ORDER-B",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-order-b"
        )

        assertEquals(2, fingerprints.size)
        assertNotEquals(
            "Distinct order numbers must produce distinct fingerprints",
            fingerprints[0],
            fingerprints[1]
        )
    }

    @Test
    fun `same_merchant_same_amount_different_currency_not_duplicate`() = runTest {
        val fingerprints = mutableListOf<String>()
        every { amazonParser.parse(any(), any()) } returnsMany listOf(
            ParsedEmailReceipt(
                merchant = "Amazon",
                amount = 50.00,
                currency = "USD",
                date = FIXED_NOW,
                items = emptyList(),
                orderNumber = "ORDER-SAME",
                confidence = 0.9
            ),
            ParsedEmailReceipt(
                merchant = "Amazon",
                amount = 50.00,
                currency = "EUR",
                date = FIXED_NOW,
                items = emptyList(),
                orderNumber = "ORDER-SAME",
                confidence = 0.9
            )
        )

        coEvery {
            receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            fingerprints.add(secondArg())
            EmailReceiptProcessResult.Success(1L, emptyList())
        }

        service.processEmailReceipt(
            emailBody = "Order Total: \$50.00 Order # ORDER-SAME",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-usd"
        )
        service.processEmailReceipt(
            emailBody = "Order Total: €50.00 Order # ORDER-SAME",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-eur"
        )

        assertEquals(2, fingerprints.size)
        assertNotEquals(
            "Different currencies with the same numeric amount must produce distinct fingerprints",
            fingerprints[0],
            fingerprints[1]
        )
    }

    @Test
    fun `same_merchant_same_amount_same_day_same_order_number_same_fingerprint`() = runTest {
        val fingerprints = mutableListOf<String>()
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 50.00,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "ORDER-DUP",
            confidence = 0.9
        )

        coEvery {
            receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            fingerprints.add(secondArg())
            EmailReceiptProcessResult.Success(1L, emptyList())
        }

        service.processEmailReceipt(
            emailBody = "Order Total: \$50.00 Order # ORDER-DUP",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-dup-1"
        )
        service.processEmailReceipt(
            emailBody = "Order Total: \$50.00 Order # ORDER-DUP",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-dup-2"
        )

        assertEquals(2, fingerprints.size)
        assertEquals(
            "Identical merchant/amount/currency/date/order must produce identical fingerprints",
            fingerprints[0],
            fingerprints[1]
        )
    }

    // -------------------------------------------------------------------------
    // P11-CURRENT-009: the parser confidence must be threaded to the coordinator
    // via EmailReceiptData (1st positional arg) instead of being discarded.
    // -------------------------------------------------------------------------

    @Test
    fun `processEmailReceipt threads parser confidence into coordinator emailData`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 1234.50,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "123-456",
            confidence = 0.42
        )

        var capturedEmailData: EmailReceiptData? = null
        coEvery {
            receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            capturedEmailData = firstArg()
            EmailReceiptProcessResult.Success(receiptId = 1L, expenseIds = emptyList())
        }

        service.processEmailReceipt(
            emailBody = "Order Total: \$1234.50 Order Date: March 03, 2026 Order # 123-456",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-confidence-1"
        )

        assertEquals(0.42, capturedEmailData!!.confidence, 0.0)
    }

    // -------------------------------------------------------------------------
    // P11-CURRENT-009 / P11-CURRENT-011: a NeedsReview coordinator outcome must be
    // surfaced honestly through the service instead of a misleading Success.
    // -------------------------------------------------------------------------

    @Test
    fun `low_confidence_coordinator_result_surfaces_NeedsReview`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 1234.50,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "123-456",
            confidence = 0.3
        )

        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            EmailReceiptProcessResult.NeedsReview(receiptId = 55L, reason = "low_confidence", confidence = 0.3)

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$1234.50 Order Date: March 03, 2026 Order # 123-456",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-low-confidence-1"
        )

        assertTrue("Expected NeedsReview but got $result", result is EmailReceiptResult.NeedsReview)
        val needsReview = result as EmailReceiptResult.NeedsReview
        assertEquals(55L, needsReview.receiptId)
        assertEquals("low_confidence", needsReview.reason)
    }

    @Test
    fun `validation_failed_coordinator_result_surfaces_NeedsReview`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 1234.50,
            currency = "USD",
            date = FIXED_NOW,
            items = emptyList(),
            orderNumber = "123-456",
            confidence = 0.9
        )

        coEvery { receiptLifecycleCoordinator.processEmailReceipt(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            EmailReceiptProcessResult.NeedsReview(receiptId = 56L, reason = "validation_failed", confidence = 0.9)

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$1234.50 Order Date: March 03, 2026 Order # 123-456",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-validation-failed-1"
        )

        assertTrue("Expected NeedsReview but got $result", result is EmailReceiptResult.NeedsReview)
        val needsReview = result as EmailReceiptResult.NeedsReview
        assertEquals(56L, needsReview.receiptId)
        assertEquals("validation_failed", needsReview.reason)
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
