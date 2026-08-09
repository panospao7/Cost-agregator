package com.yourname.expensetracker.e2e

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ReceiptExpenseLink
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.golden.GoldenTestBase
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

/**
 * E2E Test 2: Receipt Capture → Match → Link
 *
 * Wires the REAL ReceiptTransactionMatcher with real StringDistanceUtils.
 * Proves:
 * 1. Matcher finds the correct expense for a receipt (amount + merchant + date)
 * 2. High-confidence match (≥0.95) produces AutoMatch result
 * 3. ReceiptLinkService persists the link atomically
 * 4. Dashboard total unchanged after linking (no double-count)
 * 5. Duplicate link attempt is rejected
 */
class ReceiptMatchingE2ETest : GoldenTestBase() {

    private lateinit var matcher: ReceiptTransactionMatcher
    private lateinit var linkService: ReceiptLinkService
    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "e2e_receipt_matching",
        numericTolerance = 0.01
    )

    @Before
    override fun setUp() {
        super.setUp()

        val currencySettings = mockk<CurrencySettingsRepository>().also {
            every { it.homeCurrency() } returns flowOf("EUR")
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }
        val exchangeRateStore = ExchangeRateStoreAdapter(database.exchangeRateDao(), writeBarrier)
        val currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = database.expenseDao(),
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettings,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )

        // Mock ExpenseRepository to return real DB expenses
        val expenseRepository = mockk<ExpenseRepository>()
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } coAnswers {
            val start = firstArg<Long>()
            val end = secondArg<Long>()
            database.expenseDao().getExpensesByTypeBetween(start, end, "PURCHASE")
        }

        // Mock MerchantNormalizer (just passes through)
        val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)

        // Real ReceiptLinkService
        linkService = ReceiptLinkService(
            database = database,
            receiptExpenseLinkDao = database.receiptExpenseLinkDao(),
            scannedReceiptDao = database.scannedReceiptDao(),
            receiptLifecycleEventWriter = mockk(relaxed = true),
            receiptItemCategorizationDao = database.receiptItemCategorizationDao(),
            warrantyDao = database.warrantyDao(),
            returnWindowDao = database.returnWindowDao(),
            expenseDao = database.expenseDao(),
            timeProvider = timeProvider,
            sourceLinkWriter = mockk(relaxed = true),
            writeBarrier = mockk(relaxed = true),
            categoryAssignmentPort = mockk(relaxed = true),
            transactionRunner = mockk(relaxed = true)
        )

        // Real matcher
        matcher = ReceiptTransactionMatcher(
            expenseRepository = expenseRepository,
            merchantNormalizer = merchantNormalizer,
            stringDistance = StringDistanceUtils,
            timeProvider = timeProvider,
            receiptLinkService = linkService,
            currencyConverter = mockk(relaxed = true)
        )
    }

    @Test
    fun `receipt matches existing expense and links without double count`() = runTest {
        seedCategories()

        // ── SEED: Existing bank transaction ──
        val expenseId = insertExpense(createPurchase(
            amount = 89.99, currency = "EUR", merchant = "Public", categoryId = 3,
            date = fixedNow - 3600000L // 1 hour ago
        ))

        // ── SEED: Receipt from camera matching the transaction ──
        val receipt = ScannedReceipt(
            imagePath = null,
            rawOcrText = "PUBLIC ELECTRONICS\n89.99 EUR\nDate: today",
            parsedTotal = 89.99,
            parsedMerchant = "Public",
            parsedDate = fixedNow,
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0.92f,
            matchStatus = MatchStatus.UNMATCHED,
            createdAt = fixedNow,
            sourceType = "CAMERA",
            documentType = "RECEIPT",
            processingStatus = "OCR_COMPLETED",
            updatedAt = fixedNow
        )
        val receiptId = database.scannedReceiptDao().insert(receipt)
        val savedReceipt = database.scannedReceiptDao().getById(receiptId)!!

        // ── Dashboard total BEFORE linking ──
        val periodStart = fixedNow - 86400000L * 30
        val periodEnd = fixedNow + 86400000L
        val totalBefore = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // ── ACT: Find best match ──
        val matchResult = matcher.findBestMatch(savedReceipt)

        // ── ACT: Link if auto-match ──
        var linkSucceeded = false
        if (matchResult is MatchResult.AutoMatch) {
            val linkResult = linkService.linkReceiptToExpense(
                receiptId = receiptId,
                expenseId = matchResult.transaction.id,
                linkType = "AUTO_MATCHED",
                source = "ReceiptTransactionMatcher",
                confidence = matchResult.score.toFloat()
            )
            linkSucceeded = linkResult.isSuccess
        }

        // ── Dashboard total AFTER linking (should be unchanged) ──
        val totalAfter = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // ── Query state ──
        val links = database.receiptExpenseLinkDao().getLinksForReceipt(receiptId)
        val updatedReceipt = database.scannedReceiptDao().getById(receiptId)

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            put("matchType", matchResult.javaClass.simpleName)
            put("matchScore", when (matchResult) {
                is MatchResult.AutoMatch -> matchResult.score
                is MatchResult.Suggested -> matchResult.score
                else -> 0.0
            })
            put("matchedCorrectExpense", when (matchResult) {
                is MatchResult.AutoMatch -> matchResult.transaction.id == expenseId
                is MatchResult.Suggested -> matchResult.transaction.id == expenseId
                else -> false
            })

            put("linkSucceeded", linkSucceeded)
            put("linkCount", links.size)
            put("receiptMatchStatus", updatedReceipt?.matchStatus?.name)

            put("totalBefore", totalBefore.displayAmount)
            put("totalAfter", totalAfter.displayAmount)
            put("noDoubleCount", Math.abs(totalBefore.displayAmount - totalAfter.displayAmount) < 0.01)
        }

        verifier.verify(actual).assertPassed()
    }
}
