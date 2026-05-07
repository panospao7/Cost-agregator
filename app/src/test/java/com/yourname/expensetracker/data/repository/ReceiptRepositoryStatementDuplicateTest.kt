package com.yourname.expensetracker.data.repository

import android.net.Uri
import androidx.room.withTransaction
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestrator
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.debug.DebugIssueDetector
import com.yourname.expensetracker.domain.intelligence.CrossSourceDeduplication
import com.yourname.expensetracker.domain.intelligence.ml.ClassificationResult
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptRepositoryStatementDuplicateTest {

    private val scannedReceiptDao = mockk<ScannedReceiptDao>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val ocrService = mockk<ReceiptOcrService>(relaxed = true)
    private val receiptParser = mockk<ReceiptParser>(relaxed = true)
    private val statementParser = mockk<BankStatementParser>(relaxed = true)
    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val hybridClassifier = mockk<HybridExpenseClassifier>(relaxed = true)
    private val crossSourceDeduplication = mockk<CrossSourceDeduplication>(relaxed = true)
    private val debugIssueDetector = mockk<DebugIssueDetector>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val warrantyUseCase = mockk<AutoCreateWarrantyFromReceiptUseCase>(relaxed = true)

    private lateinit var repository: ReceiptRepository

    @Before
    fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(transactionBlock)) } coAnswers {
            transactionBlock.captured.invoke()
        }

        every { timeProvider.now() } returns 1_700_000_000_000L
        coEvery { scannedReceiptDao.getAllFlow() } returns MutableStateFlow(emptyList())
        coEvery { scannedReceiptDao.insert(any()) } returns 100L
        coEvery { debugIssueDetector.detectIssues(any(), any(), any()) } returns emptyList()
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } returns MerchantLookupResult(
            canonical = MerchantCanonical(1L, "Cafe Nero", "cafe nero"),
            alias = null,
            confidence = 0.99f,
            matchType = MatchType.RULE_MATCH
        )
        coEvery { hybridClassifier.classify(any(), any()) } returns ClassificationResult(
            categoryId = 7L,
            categoryName = "Coffee",
            confidence = 0.95f,
            alternatives = emptyList(),
            matchType = MatchType.RULE_MATCH
        )

        repository = ReceiptRepository(
            database = database,
            scannedReceiptDao = scannedReceiptDao,
            expenseDao = expenseDao,
            pendingReviewDao = pendingReviewDao,
            ocrService = ocrService,
            receiptParser = receiptParser,
            statementParser = statementParser,
            categorizationEngine = categorizationEngine,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            crossSourceDeduplication = crossSourceDeduplication,
            debugIssueDetector = debugIssueDetector,
            ioDispatcher = Dispatchers.Unconfined,
            timeProvider = timeProvider,
            receiptLinkService = mockk(relaxed = true),
            coordinator = mockk(relaxed = true),
            assetStore = mockk(relaxed = true),
            currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true),
            receiptLifecycleCoordinator = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `processStatement keeps same merchant date and amount when currencies differ`() = runTest {
        val uri = Uri.parse("content://test/statement.png")
        val transactionDate = 1_700_000_000_000L
        val parsedTransactions = listOf(
            ParsedTransaction(
                amount = 12.34,
                currency = "EUR",
                merchant = "Cafe Nero",
                type = ParsedTransactionType.PURCHASE,
                confidence = 0.93f,
                date = transactionDate
            ),
            ParsedTransaction(
                amount = 12.34,
                currency = "USD",
                merchant = "Cafe Nero",
                type = ParsedTransactionType.PURCHASE,
                confidence = 0.91f,
                date = transactionDate
            )
        )
        val duplicateLookupArgs = mutableListOf<Pair<String, String>>()
        val expenseLookupArgs = mutableListOf<Pair<String, String>>()
        val insertedReviews = mutableListOf<PendingReview>()
        var nextPendingReviewId = 1L

        coEvery { ocrService.processUri(uri) } returns OcrResult(
            fullText = "mock statement",
            blocks = emptyList(),
            savedImagePath = "/tmp/statement.png"
        )
        every { statementParser.parse(any(), any()) } returns parsedTransactions
        coEvery {
            pendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } coAnswers {
            duplicateLookupArgs += (args[6] as String) to (args[7] as String)
            null
        }
        coEvery {
            expenseDao.existsByMerchantKeyInRangeCurrencyAware(
                any(), any(), any(), any(), any(), any(), any()
            )
        } coAnswers {
            expenseLookupArgs += (args[5] as String) to (args[6] as String)
            false
        }
        coEvery {
            expenseDao.existsByMerchantInRangeCurrencyAware(
                any(), any(), any(), any(), any(), any(), any()
            )
        } coAnswers {
            expenseLookupArgs += (args[5] as String) to (args[6] as String)
            false
        }
        coEvery { pendingReviewDao.insert(any()) } coAnswers {
            insertedReviews += firstArg<PendingReview>()
            nextPendingReviewId++
        }

        val result = repository.processStatement(uri)

        assertEquals(2, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(listOf("EUR", "USD"), insertedReviews.map { it.suggestedCurrency })
        assertEquals(listOf("PURCHASE", "PURCHASE"), insertedReviews.map { it.suggestedType })
        assertEquals(
            listOf(
                "EUR" to "PURCHASE",
                "EUR" to "PURCHASE",
                "USD" to "PURCHASE",
                "USD" to "PURCHASE"
            ),
            duplicateLookupArgs
        )
        assertEquals(
            listOf(
                "EUR" to "PURCHASE",
                "EUR" to "PURCHASE",
                "USD" to "PURCHASE",
                "USD" to "PURCHASE"
            ),
            expenseLookupArgs
        )

        coVerify(exactly = 2) { pendingReviewDao.insert(any()) }
    }
}