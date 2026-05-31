package com.yourname.expensetracker.data.email

import com.yourname.expensetracker.data.email.provider.AmazonReceiptParser
import com.yourname.expensetracker.data.email.provider.AppleReceiptParser
import com.yourname.expensetracker.data.email.provider.ParsedEmailReceipt
import com.yourname.expensetracker.data.email.provider.ReceiptItem
import com.yourname.expensetracker.data.email.provider.UberReceiptParser
import com.yourname.expensetracker.domain.intelligence.ml.MatchType as MerchantMatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.receipt.lifecycle.EmailReceiptProcessResult
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the real delegation contract of [EmailReceiptIngestionService]:
 * the service parses the email and delegates ALL mutation to
 * [ReceiptLifecycleCoordinator.processEmailReceipt] exactly once, without
 * running any transaction of its own. When the coordinator reports an error,
 * the service surfaces it as [EmailReceiptResult.ParseError].
 */
class EmailReceiptIngestionServiceTransactionTest {

    private val receiptParser = mockk<ReceiptParser>()
    private val receiptLifecycleCoordinator = mockk<ReceiptLifecycleCoordinator>()
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
    fun `processEmailReceipt delegates to coordinator exactly once and does not run its own transaction`() = runTest {
        every { amazonParser.parse(any(), any()) } returns ParsedEmailReceipt(
            merchant = "Amazon",
            amount = 18.25,
            currency = "USD",
            date = FIXED_NOW,
            items = listOf(ReceiptItem("Test item", 1, 18.25, 18.25)),
            orderNumber = "123-456",
            confidence = 0.9
        )

        // Coordinator owns the transaction. When it reports an error, the
        // service must surface ParseError — it has NO inline fallback and runs
        // no transaction of its own.
        coEvery {
            receiptLifecycleCoordinator.processEmailReceipt(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns EmailReceiptProcessResult.Error("boom")

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$18.25 Order Date: March 03, 2026 Order # 123-456",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-transaction-failure"
        )

        // (a) Coordinator error is surfaced as ParseError.
        assertTrue(
            "Expected ParseError when coordinator returns Error but got $result",
            result is EmailReceiptResult.ParseError
        )

        // (b) Service delegates the mutation exactly once — proving it does not
        // run an independent transaction or write path.
        coVerify(exactly = 1) {
            receiptLifecycleCoordinator.processEmailReceipt(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
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
