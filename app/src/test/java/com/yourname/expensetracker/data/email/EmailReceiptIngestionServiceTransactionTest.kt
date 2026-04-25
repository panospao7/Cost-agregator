package com.yourname.expensetracker.data.email

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class EmailReceiptIngestionServiceTransactionTest {

    private lateinit var database: AppDatabase
    private val receiptParser = mockk<ReceiptParser>()
    private val processReceiptUseCase = mockk<ProcessReceiptUseCase>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>()
    private val categorizationEngine = mockk<CategorizationEngine>()
    private val timeProvider = FakeTimeProvider(FIXED_NOW)

    private lateinit var service: EmailReceiptIngestionService

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()

        service = EmailReceiptIngestionService(
            receiptParser = receiptParser,
            processReceiptUseCase = processReceiptUseCase,
            expenseDao = database.expenseDao(),
            emailReceiptDao = database.emailReceiptDao(),
            scannedReceiptDao = database.scannedReceiptDao(),
            merchantNormalizer = merchantNormalizer,
            categorizationEngine = categorizationEngine,
            timeProvider = timeProvider,
            database = database
        )

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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `processEmailReceipt rolls back receipt and email source when expense creation fails`() = runTest {
        coEvery { categorizationEngine.categorize(any()) } returns CategorizationResult(
            categoryId = 42L,
            categoryName = "Shopping",
            confidence = 0.88,
            matchType = CategorizationMatchType.EXACT,
            explanation = "test"
        )

        val result = service.processEmailReceipt(
            emailBody = "Order Total: \$18.25 Order Date: March 03, 2026 Order # 123-456",
            sender = "auto-confirm@amazon.com",
            subject = "Your Amazon order",
            receivedAt = FIXED_NOW,
            messageId = "msg-transaction-failure"
        )

        assertThat(result).isInstanceOf(EmailReceiptResult.ParseError::class.java)
        assertThat(database.scannedReceiptDao().getCount()).isEqualTo(0)
        assertThat(database.emailReceiptDao().getCount()).isEqualTo(0)
        assertThat(database.expenseDao().getTotalCount()).isEqualTo(0)
    }

    companion object {
        private const val FIXED_NOW = 1_730_000_000_000L
    }
}
