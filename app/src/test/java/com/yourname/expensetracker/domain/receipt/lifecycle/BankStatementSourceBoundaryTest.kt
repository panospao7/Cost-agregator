package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BankStatementImportItemDao
import com.yourname.expensetracker.data.database.dao.BankStatementImportRunDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.BankStatementImportItem
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptRecordWriteResult
import com.yourname.expensetracker.data.repository.ReceiptRecordWriter
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.ai.usecase.CleanTransaction
import com.yourname.expensetracker.domain.ai.usecase.DebugTransaction
import com.yourname.expensetracker.domain.ai.usecase.StatementValidationContracts
import com.yourname.expensetracker.domain.ai.usecase.StatementValidationOutcome
import com.yourname.expensetracker.domain.ai.usecase.ValidateBankStatementTransactionsUseCase
import com.yourname.expensetracker.domain.core.money.CurrencyAssumption
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.privacy.RawPersistencePolicy
import com.yourname.expensetracker.domain.privacy.RawPersistencePolicyResolver
import com.yourname.expensetracker.domain.privacy.RawSourceType
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.TextBlock
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.MerchantCleaner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes the real processor blocks; mocked runner/DAOs do not prove Room atomicity. */
class BankStatementSourceBoundaryTest {
    @Test
    fun explicitSourceCreatesReviewWithoutHomeSettings() = runTest {
        val harness = Harness(null, listOf("10/05 SHOP -12.50 USD"))
        val result = harness.process().getOrThrow()
        assertEquals(1, result.reviewsCreated)
        assertEquals(0, result.duplicatesSkipped)
        assertEquals(listOf("USD"), harness.reviews.map { it.suggestedCurrency })
        assertTrue(harness.savedReceipts.all { it.rawOcrText.isNullOrBlank() })
    }

    @Test
    fun invalidHomeCannotBecomeCurrencyEvidenceForUnknownRows() = runTest {
        for (home in listOf(null, "ZZZ", "EUR")) {
            val harness = Harness(home, listOf("10/05 SHOP -12.50"))
            assertTrue(harness.process().isFailure)
            assertTrue(harness.reviews.isEmpty())
            val item = harness.items.single()
            assertEquals(BankStatementImportItem.STATUS_SKIPPED, item.status)
            assertEquals("CURRENCY_UNKNOWN", item.errorReason)
            assertNull(item.currency)
            harness.verifyNoActionableWork()
        }
    }

    @Test
    fun mixedRowsOnlyCreateExplicitlyDenominatedReviews() = runTest {
        val harness = Harness(null, listOf("10/05 SHOP -12.50 USD", "11/05 SHOP -15.00"))
        // Existing finalization treats skipped items as a failed/partial run.
        assertTrue(harness.process().isFailure)
        assertEquals(listOf("USD"), harness.reviews.map { it.suggestedCurrency })
        assertEquals(1, harness.items.count { it.status == BankStatementImportItem.STATUS_SKIPPED })
        assertEquals(1, harness.items.count { it.status == BankStatementImportItem.STATUS_CREATED_REVIEW })
    }

    @Test
    fun unsupportedValidatedCurrencyCannotReachReviewOrDedupe() = runTest {
        val harness = Harness("EUR", listOf("10/05 SHOP -12.50 USD"))
        coEvery { harness.validator.validateTransactions(any(), any(), any()) } returns
            StatementValidationOutcome.Validated(listOf(
                CleanTransaction(0, "SHOP", 12.5, "ZZZ", harness.clock.now(), 0.9f, "AI_VALIDATED")
            ))
        assertTrue(harness.process().isFailure)
        assertEquals("CURRENCY_UNKNOWN", harness.items.single().errorReason)
        harness.verifyNoActionableWork()
    }

    private class Harness(home: String?, rows: List<String>) {
        val clock = FakeTimeProvider.forDate(2026, 6, 1)
        val items = mutableListOf<BankStatementImportItem>()
        val reviews = mutableListOf<PendingReview>()
        val savedReceipts = mutableListOf<ScannedReceipt>()
        val validator = mockk<ValidateBankStatementTransactionsUseCase>()
        private val pendingDao = mockk<PendingReviewDao>(relaxed = true)
        private val expenseDao = mockk<ExpenseDao>(relaxed = true)
        private val normalizer = mockk<MerchantNormalizer>()
        private val processor: BankStatementLifecycleProcessor
        private val uri = mockk<Uri>(relaxed = true)

        init {
            val settings = mockk<CurrencySettingsRepository>()
            every { settings.homeCurrency() } returns (home?.let { flowOf(it) } ?: emptyFlow())
            val cleaner = mockk<MerchantCleaner>()
            every { cleaner.clean(any()) } answers { firstArg() }
            val parser = BankStatementParser(CurrencyNormalizer(), cleaner, clock, settings)
            val receiptRepository = mockk<ReceiptRepository>()
            coEvery { receiptRepository.runStatementOcr(any()) } returns OcrResult(
                rows.joinToString("\n"), rows.mapIndexed { index, text ->
                    TextBlock(text, null, 10, 100 + index * 40, 300, 120 + index * 40)
                }, ""
            )
            val assetStore = mockk<ReceiptAssetStore>()
            coEvery { assetStore.computeUriHash(any()) } returns Result.failure(IllegalStateException("TEST_NO_HASH"))
            coEvery { assetStore.computeFileHash(any()) } returns Result.failure(IllegalStateException("TEST_NO_HASH"))
            val runner = mockk<DomainTransactionRunner>()
            val context = TransactionContext(correlationId = "test", occurredAt = clock.now())
            coEvery { runner.runInTransaction<Any>(any(), any(), any(), any(), any(), any()) } coAnswers {
                arg<suspend (TransactionContext) -> Any>(5)(context)
            }
            val runs = mockk<BankStatementImportRunDao>(relaxed = true)
            coEvery { runs.insert(any()) } returns 1L
            val itemDao = mockk<BankStatementImportItemDao>()
            coEvery { itemDao.insert(any()) } answers {
                items += firstArg<BankStatementImportItem>()
                items.size.toLong()
            }
            coEvery { itemDao.countByRunAndStatus(any(), any()) } answers {
                items.count { it.status == secondArg<String>() }
            }
            coEvery { pendingDao.insert(any()) } answers {
                reviews += firstArg<PendingReview>()
                reviews.size.toLong()
            }
            // Relaxed nullable entity queries fabricate a non-null duplicate mock.
            coEvery { pendingDao.getPendingDuplicateCandidateInRangeTypeAware(
                any(), any(), any(), any(), any(), any(), any(), any()
            ) } returns null
            val writer = mockk<ReceiptRecordWriter>()
            coEvery { writer.insertOrResolve(any()) } answers {
                val receipt = firstArg<ScannedReceipt>().copy(id = 7L)
                savedReceipts += receipt
                ReceiptRecordWriteResult.Inserted(receipt)
            }
            val lookup = mockk<MerchantLookupResult>()
            every { lookup.canonical } returns MerchantCanonical(
                normalizedName = "SHOP", searchKey = "shop", createdAt = clock.now(), updatedAt = clock.now()
            )
            coEvery { normalizer.normalize(any(), any(), any()) } returns lookup
            val policy = mockk<RawPersistencePolicyResolver>()
            coEvery { policy.forSource(any()) } returns RawPersistencePolicy(
                RawStorageMode.DO_NOT_STORE, RawSourceType.BANK_STATEMENT,
                allowParsedAmountDateCurrency = true, allowParsedMerchant = true,
                allowParsedItems = false, allowExternalIdHash = true, allowDebugBody = false
            )
            coEvery { validator.validateTransactions(any(), any(), any()) } answers {
                StatementValidationOutcome.Validated(secondArg<List<DebugTransaction>>().map { tx ->
                    CleanTransaction(tx.candidateId, tx.merchant, tx.amount,
                        if (tx.currencyAssumption == CurrencyAssumption.PARSED_FROM_SOURCE) tx.currency
                        else StatementValidationContracts.CURRENCY_UNKNOWN,
                        tx.date, tx.confidence, StatementValidationContracts.SOURCE_PARSER_ONLY)
                })
            }
            processor = BankStatementLifecycleProcessor(
                database = mockk<AppDatabase>(relaxed = true), transactionRunner = runner,
                receiptRepository = receiptRepository, scannedReceiptDao = mockk(relaxed = true),
                receiptLifecycleEventWriter = mockk(relaxed = true), receiptLinkService = mockk(relaxed = true),
                timeProvider = clock, bankStatementParser = parser, pendingReviewDao = pendingDao,
                expenseDao = expenseDao, merchantNormalizer = normalizer, hybridClassifier = mockk(relaxed = true),
                duplicateDetector = mockk(relaxed = true), assetStore = assetStore, transactionValidator = validator,
                recurringExpenseRepository = mockk(relaxed = true), writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
                rawPersistencePolicyResolver = policy, bankStatementImportRunDao = runs,
                bankStatementImportItemDao = itemDao, receiptRecordWriter = writer
            )
        }

        suspend fun process() = processor.processBankStatement(uri)

        suspend fun verifyNoActionableWork() {
            assertTrue(reviews.isEmpty())
            coVerify(exactly = 0) { pendingDao.insert(any()) }
            coVerify(exactly = 0) { normalizer.normalize(any(), any(), any()) }
            coVerify(exactly = 0) { pendingDao.getPendingDuplicateCandidateInRangeTypeAware(
                any(), any(), any(), any(), any(), any(), any(), any()
            ) }
            coVerify(exactly = 0) { expenseDao.existsByMerchantKeyInRangeCurrencyAware(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { expenseDao.existsByMerchantInRangeCurrencyAware(any(), any(), any(), any(), any(), any(), any()) }
        }
    }
}
