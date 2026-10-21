package com.yourname.expensetracker.domain.receipt.lifecycle

import android.net.Uri
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BankStatementImportItemDao
import com.yourname.expensetracker.data.database.dao.BankStatementImportRunDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.BankStatementImportItem
import com.yourname.expensetracker.data.database.entity.BankStatementImportRun
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
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.privacy.RawPersistencePolicy
import com.yourname.expensetracker.domain.privacy.RawPersistencePolicyResolver
import com.yourname.expensetracker.domain.privacy.RawSourceType
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.TextBlock
import com.yourname.expensetracker.domain.receipt.statement.CurrencyEvidence
import com.yourname.expensetracker.domain.receipt.statement.StatementLayoutId
import com.yourname.expensetracker.domain.receipt.statement.StatementRow
import com.yourname.expensetracker.domain.receipt.statement.StatementSkipReason
import com.yourname.expensetracker.domain.receipt.statement.StatementTextFixtures
import com.yourname.expensetracker.domain.receipt.statement.TextStatementParseResult
import com.yourname.expensetracker.domain.receipt.statement.TextStatementParser
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Statement import through the real [BankStatementLifecycleProcessor] for the PDF TEXT path
 * (layout parsers, header currency, skipped rows, direction/label, same-run duplicates, ceiling,
 * run status) and the unchanged OCR path. DAOs are mocks, so these tests prove processor behaviour,
 * not Room atomicity. Every name, merchant, IBAN and amount is invented (StatementTextFixtures).
 */
class BankStatementTextImportTest {

    // ── Text path: layouts through the processor ──────────────────────────────────

    @Test
    fun transfersStatementCreatesTransferReviewsWithDirectionLabelAndNoIban() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.EUROBANK_TRANSFERS_PAGES)
        val result = harness.process().getOrThrow()

        assertEquals(3, result.reviewsCreated)
        assertEquals(3, harness.reviews.size)
        assertTrue(harness.reviews.all { it.suggestedType == "TRANSFER" })
        assertTrue(harness.reviews.all { it.suggestedDirection == "OUTGOING" })
        assertEquals(
            listOf("To: SOME BANK SA ****5678", "To: EUROBANK TEST SA ****7778", "To: SOME BANK SA ****2468"),
            harness.reviews.map { it.suggestedAccountName }
        )
        assertTrue(harness.reviews.all { it.suggestedDate != null && it.suggestedDate!! > 0L })
        assertNoIbanOrLongDigitRun(harness)
    }

    @Test
    fun skippedRowsAreLedgerItemsWithBoundedReasonAndNoRowContent() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.EUROBANK_TRANSFERS_PAGES)
        val result = harness.process().getOrThrow()

        // 3 parsed rows occupy indexes 0..2; the two layout skips follow at 3 and 4.
        val skipped = harness.items.filter { it.status == BankStatementImportItem.STATUS_SKIPPED }
            .sortedBy { it.itemIndex }
        assertEquals(listOf(3, 4), skipped.map { it.itemIndex })
        assertEquals(listOf("CANCELLED", "UNKNOWN_STATUS"), skipped.map { it.errorReason })
        assertTrue(skipped.all { it.merchant == null && it.amount == null && it.transactionDate == null })
        assertEquals(5, harness.items.map { it.itemIndex }.toSet().size)

        assertEquals(2, result.skippedCount)
        assertEquals(mapOf("CANCELLED" to 1, "UNKNOWN_STATUS" to 1), result.skippedByReason)
        // Skipped rows never reach AI validation; on a recognised layout neither do TRANSFER rows
        // (this statement holds three transfers), so there is no candidate and no AI call at all.
        assertTrue(harness.validatorCalls.isEmpty())
    }

    @Test
    fun recognisedLayoutExcludesTransferRowsFromAiCandidatesButStillImportsThem() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.NBG_PAGES)
        val result = harness.process().getOrThrow()

        val candidates = harness.validatorCalls.single()
        assertTrue(candidates.none { it.type == "TRANSFER" })
        // Dense ids over the remaining candidates, mapped back to parser rows by the processor.
        assertEquals(candidates.indices.toList(), candidates.map { it.candidateId })
        val transferReviews = harness.reviews.count { it.suggestedType == "TRANSFER" }
        assertTrue(transferReviews > 0)
        // Every transfer is still imported (parser-only) next to the candidates the AI saw.
        assertEquals(12, result.reviewsCreated)
        assertEquals(candidates.size + transferReviews, harness.reviews.size)
        val created = harness.items.filter { it.status == BankStatementImportItem.STATUS_CREATED_REVIEW }
        assertEquals(created.size, created.map { it.itemIndex }.toSet().size)
        assertNoIbanOrLongDigitRun(harness)
    }

    @Test
    fun everyAiCandidateMerchantIsScrubbedOfIbansAndLongDigitRuns() = runTest {
        val dirty = parsedRow(
            "PAY ${StatementTextFixtures.RECIPIENT_IBAN_1} 164500000000 SHOP", 5.0,
            StatementTextFixtures.day(2031, 11, 9)
        )
        val harness = Harness(
            textPages = listOf(listOf("unused")),
            textParser = parserReturning(TextStatementParseResult(StatementLayoutId.EUROBANK_CARD, listOf(dirty)))
        )
        harness.process().getOrThrow()

        val merchant = harness.validatorCalls.single().single().merchant
        assertFalse(merchant, merchant.contains("GR11"))
        assertFalse(merchant, Regex("""\d{12}""").containsMatchIn(merchant))
        assertTrue(merchant, merchant.contains("****"))
    }

    @Test
    fun ocrPathCandidateMerchantIsScrubbedToo() = runTest {
        val harness = Harness(ocrRows = listOf("10/05/2031 PAY 164500000000 SHOP -12.50 USD"))
        harness.process().getOrThrow()

        val merchants = harness.validatorCalls.single().map { it.merchant }
        assertTrue(merchants.isNotEmpty())
        assertTrue(merchants.none { Regex("""\d{12}""").containsMatchIn(it) })
    }

    @Test
    fun recognisedLayoutWithOnlySkippedRowsCreatesTheRunAndCarriesTheSkippedCounts() = runTest {
        val harness = Harness(
            textPages = listOf(listOf("unused")),
            textParser = parserReturning(
                TextStatementParseResult(
                    StatementLayoutId.EUROBANK_TRANSFERS,
                    listOf(
                        StatementRow.Skipped(StatementSkipReason.CANCELLED, 1),
                        StatementRow.Skipped(StatementSkipReason.CANCELLED, 1),
                        StatementRow.Skipped(StatementSkipReason.NOT_SETTLED, 2)
                    )
                )
            )
        )
        val result = harness.process()

        // Policy unchanged: nothing created and everything skipped is a FAILED run ...
        assertTrue(result.isFailure)
        assertEquals(BankStatementImportRun.STATUS_FAILED, harness.finalStatusOrNull())
        // ... but it is a real ledger run, not the bare "No transactions found" failure.
        coVerify(exactly = 1) { harness.runs.insert(any()) }
        assertEquals(3, harness.items.count { it.status == BankStatementImportItem.STATUS_SKIPPED })
        assertTrue(harness.reviews.isEmpty())
        assertTrue(harness.validatorCalls.isEmpty())
        val failure = result.exceptionOrNull()
        assertTrue(failure.toString(), failure is StatementAllRowsSkippedException)
        val counts = (failure as StatementAllRowsSkippedException).result
        assertEquals(3, counts.skippedCount)
        assertEquals(mapOf("CANCELLED" to 2, "NOT_SETTLED" to 1), counts.skippedByReason)
        assertEquals(0, counts.reviewsCreated)
        assertEquals("STATEMENT_ALL_ROWS_SKIPPED", failure.message)
    }

    @Test
    fun aStatementWithNeitherRowsNorSkipsStillFailsWithoutARun() = runTest {
        val harness = Harness(
            textPages = listOf(listOf("unused")),
            textParser = parserReturning(TextStatementParseResult(StatementLayoutId.EUROBANK_CARD, emptyList()))
        )
        val result = harness.process()

        assertTrue(result.isFailure)
        assertEquals("No transactions found in bank statement", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { harness.runs.insert(any()) }
    }

    @Test
    fun skippedRowsNextToReviewsCompleteWithSkipsAndReturnSuccess() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.EUROBANK_TRANSFERS_PAGES)
        val result = harness.process()

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrThrow().transactionsFound)
        assertEquals(2, result.getOrThrow().pagesProcessed)
        assertEquals(2, result.getOrThrow().totalPages)
        harness.verifyFinalized(
            status = BankStatementImportRun.STATUS_COMPLETED_WITH_SKIPS,
            totalItems = 5, processedItems = 5, created = 3, failedPlusSkipped = 2
        )
    }

    @Test
    fun headerCurrencyRowsAreNotSkippedAndIdenticalRowsInOneRunAreSeparateReviews() = runTest {
        // No home currency at all: the EUR declared once in the card header is the source evidence.
        val harness = Harness(textPages = StatementTextFixtures.EUROBANK_CARD_PAGES, home = null)
        val result = harness.process().getOrThrow()

        assertEquals(7, result.reviewsCreated)
        assertEquals(0, result.duplicatesSkipped)
        assertEquals(0, result.skippedCount)
        assertTrue(harness.reviews.all { it.suggestedCurrency == "EUR" })
        assertTrue(harness.items.none { it.errorReason == "CURRENCY_UNKNOWN" })
        // The three identical "SOME SHOP -3,00" rows on one day each create their own review.
        assertEquals(3, harness.reviews.count { it.suggestedMerchant == "SOME SHOP" && it.suggestedAmount == 3.0 })
        // Credit printed as an unsigned positive amount is an incoming deposit.
        val refund = harness.reviews.single { it.suggestedType == "DEPOSIT" }
        assertEquals(12.5, refund.suggestedAmount!!, 0.0001)
        assertEquals("INCOMING", refund.suggestedDirection)
        assertTrue(harness.reviews.all { it.suggestedDate != null })
        harness.verifyFinalized(
            status = BankStatementImportRun.STATUS_COMPLETED,
            totalItems = 7, processedItems = 7, created = 7, failedPlusSkipped = 0
        )
    }

    @Test
    fun crossRunPendingDuplicateIsStillDetected() = runTest {
        val seeded = pendingReview(
            id = 500L, receiptId = 99L, merchant = "OTHER SHOP LTD", amount = 2.10,
            date = StatementTextFixtures.day(2031, 11, 10)
        )
        val harness = Harness(
            textPages = StatementTextFixtures.EUROBANK_CARD_PAGES, home = null, seededPending = listOf(seeded)
        )
        val result = harness.process().getOrThrow()

        assertEquals(6, result.reviewsCreated)
        assertEquals(1, result.duplicatesSkipped)
        val duplicate = harness.items.single { it.status == BankStatementImportItem.STATUS_DUPLICATE_PENDING_REVIEW }
        assertEquals(500L, duplicate.pendingReviewId)
        assertEquals(BankStatementImportRun.STATUS_COMPLETED_WITH_SKIPS, harness.finalStatusOrNull())
    }

    @Test
    fun accountStatementKeepsDirectionsLabelsAndSkipsTheOrphanTail() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.NBG_PAGES)
        val result = harness.process().getOrThrow()

        assertEquals(12, result.reviewsCreated)
        assertEquals(1, result.skippedCount)
        assertEquals(mapOf("NO_DATE" to 1), result.skippedByReason)
        // Two identical card purchases (same merchant, amount and day) both survive.
        assertEquals(2, harness.reviews.count { it.suggestedMerchant == "SOME SHOP" && it.suggestedAmount == 3.70 })
        val incoming = harness.reviews.first { it.suggestedAccountName == "From: MARIA EXAMPLE" }
        assertEquals("TRANSFER", incoming.suggestedType)
        assertEquals("INCOMING", incoming.suggestedDirection)
        val outgoing = harness.reviews.first { it.suggestedAccountName == "To: JOHN DOE SMITH EXAMPLE" }
        assertEquals("OUTGOING", outgoing.suggestedDirection)
        assertEquals(1250.0, outgoing.suggestedAmount!!, 0.0001)
        // IBAN-only counterparty: masked label, never the IBAN.
        assertTrue(harness.reviews.any { it.suggestedAccountName == "To: Account ****1234" })
        assertTrue(harness.reviews.all { it.suggestedDate != null && it.suggestedDate!! > 0L })
        assertNoIbanOrLongDigitRun(harness)
    }

    @Test
    fun walletStatementKeepsRepeatedTopUpsAndCountsPendingSectionsAsSkipped() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.REVOLUT_PAGES)
        val result = harness.process().getOrThrow()

        assertEquals(11, result.reviewsCreated)
        assertEquals(mapOf("NOT_SETTLED" to 2), result.skippedByReason)
        // The fixture holds FOUR identical EUR12.00 top-ups: three on Mar 10 and a fourth on Mar 18.
        // All four are real transactions and each creates its own review (the code was right; the
        // old expectation of 3 only matched the same-day trio).
        val topUps = harness.reviews.filter { it.suggestedMerchant == "Revolut top-up" }
        assertEquals(4, topUps.size)
        assertEquals(3, topUps.count { it.suggestedDate == StatementTextFixtures.day(2031, 3, 10) })
        assertEquals(1, topUps.count { it.suggestedDate == StatementTextFixtures.day(2031, 3, 18) })
        assertTrue(topUps.all { it.suggestedAmount == 12.0 && it.suggestedType == "DEPOSIT" })
        assertTrue(harness.reviews.all { it.suggestedCurrency == "EUR" })
        assertNoIbanOrLongDigitRun(harness)
    }

    @Test
    fun currencyExchangeRowIsATransferReviewWithAnAccountNameSoItCanBeApproved() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.REVOLUT_PAGES)
        harness.process().getOrThrow()

        val exchange = harness.reviews.single { it.suggestedMerchant == "Currency exchange" }
        assertEquals("TRANSFER", exchange.suggestedType)
        assertEquals("INCOMING", exchange.suggestedDirection)
        assertEquals("Currency exchange", exchange.suggestedAccountName)
    }

    // ── AI authority and privacy of the AI boundary ───────────────────────────────

    @Test
    fun recognisedLayoutKeepsParserAmountDateAndRowsEvenWhenTheAiCorrectsOrRejects() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.EUROBANK_CARD_PAGES, home = null)
        harness.validate = { candidates ->
            StatementValidationOutcome.Validated(candidates.map { tx ->
                when {
                    tx.amount == 2.10 -> CleanTransaction(
                        tx.candidateId, "OTHER SHOP CORRECTED", 99.0, "USD",
                        StatementTextFixtures.day(2031, 1, 1), 0.9f,
                        StatementValidationContracts.SOURCE_AI_CORRECTED
                    )
                    tx.amount == 12.5 -> CleanTransaction(
                        tx.candidateId, tx.merchant, tx.amount, tx.currency, tx.date, tx.confidence,
                        StatementValidationContracts.SOURCE_AI_REJECTED
                    )
                    else -> CleanTransaction(
                        tx.candidateId, tx.merchant, tx.amount, tx.currency, tx.date, tx.confidence,
                        StatementValidationContracts.SOURCE_PARSER_ONLY
                    )
                }
            })
        }
        val result = harness.process().getOrThrow()

        // The AI rejection does not drop the layout row, and nothing is recorded as AI_REJECTED.
        assertEquals(7, result.reviewsCreated)
        assertEquals(0, result.skippedCount)
        assertTrue(harness.items.none { it.errorReason == "AI_REJECTED" })
        val refund = harness.reviews.single { it.suggestedType == "DEPOSIT" }
        assertEquals(12.5, refund.suggestedAmount!!, 0.0001)
        assertEquals("INCOMING", refund.suggestedDirection)
        assertEquals(StatementTextFixtures.day(2031, 11, 9), refund.suggestedDate)

        // The AI-corrected amount, currency and date are ignored; only the merchant correction is used.
        val corrected = harness.reviews.single { it.suggestedMerchant == "OTHER SHOP CORRECTED" }
        assertEquals(2.10, corrected.suggestedAmount!!, 0.0001)
        assertEquals("EUR", corrected.suggestedCurrency)
        assertEquals(StatementTextFixtures.day(2031, 11, 10), corrected.suggestedDate)
        assertTrue(harness.reviews.none { it.suggestedAmount == 99.0 })
    }

    @Test
    fun transferRowKeepsItsParserMerchantAndLabelWhateverMerchantTheAiReturns() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.EUROBANK_TRANSFERS_PAGES)
        harness.validate = { candidates ->
            StatementValidationOutcome.Validated(candidates.map { tx ->
                CleanTransaction(
                    tx.candidateId, "FAKE SHOP", 999.0, tx.currency, tx.date, 0.9f,
                    StatementValidationContracts.SOURCE_AI_CORRECTED
                )
            })
        }
        harness.process().getOrThrow()

        assertEquals(
            listOf("SOME BANK SA ****5678", "EUROBANK TEST SA ****7778", "SOME BANK SA ****2468"),
            harness.reviews.map { it.suggestedMerchant }
        )
        assertEquals(listOf(11.0, 6.75, 8.25), harness.reviews.map { it.suggestedAmount })
        assertTrue(harness.reviews.none { it.suggestedMerchant == "FAKE SHOP" })
    }

    @Test
    fun aiMerchantCorrectionOnANonTransferRowIsScrubbedOfIbanAndDigitRuns() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.EUROBANK_CARD_PAGES, home = null)
        harness.validate = { candidates ->
            StatementValidationOutcome.Validated(candidates.map { tx ->
                if (tx.amount == 2.10) {
                    CleanTransaction(
                        tx.candidateId, "PAY ${StatementTextFixtures.RECIPIENT_IBAN_1} 164500000000 SHOP",
                        tx.amount, tx.currency, tx.date, 0.9f, StatementValidationContracts.SOURCE_AI_CORRECTED
                    )
                } else {
                    CleanTransaction(
                        tx.candidateId, tx.merchant, tx.amount, tx.currency, tx.date, tx.confidence,
                        StatementValidationContracts.SOURCE_PARSER_ONLY
                    )
                }
            })
        }
        harness.process().getOrThrow()

        val corrected = harness.reviews.single { it.suggestedMerchant!!.startsWith("PAY ") }
        assertFalse(corrected.suggestedMerchant!!, corrected.suggestedMerchant!!.contains("GR11"))
        assertFalse(Regex("""\d{12}""").containsMatchIn(corrected.suggestedMerchant!!))
        assertTrue(corrected.suggestedMerchant!!.contains("****"))
        assertEquals(2.10, corrected.suggestedAmount!!, 0.0001)
    }

    @Test
    fun transferRowsNeverReachTheMerchantNormalizerButPurchasesStillDo() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.NBG_PAGES)
        harness.process().getOrThrow()

        val transferMerchants = harness.reviews.filter { it.suggestedType == "TRANSFER" }.map { it.suggestedMerchant!! }
        assertTrue(transferMerchants.isNotEmpty())
        for (merchant in transferMerchants) {
            assertFalse("transfer label was normalised", merchant in harness.normalizedNames)
        }
        coVerify(exactly = 0) { harness.normalizer.normalize(match { it in transferMerchants }, true, any()) }
        // Non-transfer rows keep the existing normalisation (alias/merchant learning).
        assertTrue(harness.normalizedNames.contains("SOME SHOP"))
    }

    @Test
    fun aiValidationTextIsScrubbedOfIbansIncludingSpacedOnesAndLongDigitRuns() = runTest {
        val harness = Harness(
            textPages = StatementTextFixtures.EUROBANK_TRANSFERS_PAGES,
            extraFullText = "\nPAY GR96 0000 0000 0000 0000 0000 123 REF 164500000000 END"
        )
        harness.process().getOrThrow()

        val text = harness.validatorTexts.single()
        assertTrue(text.isNotBlank())
        assertFalse(text.contains(StatementTextFixtures.OWN_IBAN))
        assertFalse(text.contains(StatementTextFixtures.RECIPIENT_IBAN_1))
        assertFalse(text, text.contains("GR96 0000"))
        assertFalse(text, text.contains("164500000000"))
        assertFalse(text, Regex("""\d{12}""").containsMatchIn(text))
    }

    @Test
    fun aiValidationTextIsCappedBeforeItLeavesTheProcessor() = runTest {
        val harness = Harness(
            ocrRows = listOf("10/05/2031 SHOP -12.50 USD"), extraFullText = "x".repeat(300_000)
        )
        harness.process().getOrThrow()

        assertTrue(harness.validatorTexts.single().length <= STATEMENT_AI_TEXT_SLICE)
    }

    // ── Boundary scrub, direction source, NO_DATE, ceiling ────────────────────────

    @Test
    fun aiCorrectedMerchantCannotCarryAnIbanAndDirectionStillComesFromTheParsedRow() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.EUROBANK_TRANSFERS_PAGES)
        harness.validate = { candidates ->
            StatementValidationOutcome.Validated(candidates.map { tx ->
                CleanTransaction(
                    candidateId = tx.candidateId,
                    merchant = "PAY ${StatementTextFixtures.RECIPIENT_IBAN_1} 164500000000 SHOP",
                    amount = tx.amount, currency = tx.currency, date = tx.date, confidence = 0.9f,
                    source = StatementValidationContracts.SOURCE_AI_CORRECTED
                )
            })
        }
        harness.process().getOrThrow()

        assertEquals(3, harness.reviews.size)
        val stored = harness.reviews.map { it.suggestedMerchant } + harness.items.mapNotNull { it.merchant }
        assertTrue(stored.isNotEmpty())
        for (text in stored) {
            assertFalse(text, text.contains("GR11"))
            assertFalse(text, Regex("""\d{12}""").containsMatchIn(text))
            assertTrue(text, text.contains("****"))
        }
        // AI output carries no direction: it stays the parser's OUTGOING with the parsed label.
        assertTrue(harness.reviews.all { it.suggestedDirection == "OUTGOING" })
        assertTrue(harness.reviews.all { it.suggestedAccountName!!.startsWith("To: ") })
    }

    @Test
    fun layoutSkippedFutureDatedRowIsLedgeredWithTheBoundedUnreasonableDateCode() = runTest {
        val dated = parsedRow("OK SHOP", 5.0, StatementTextFixtures.day(2031, 11, 9))
        val harness = Harness(
            textPages = listOf(listOf("unused")),
            textParser = parserReturning(
                TextStatementParseResult(
                    StatementLayoutId.EUROBANK_CARD,
                    listOf(dated, StatementRow.Skipped(StatementSkipReason.UNREASONABLE_DATE, 1))
                )
            )
        )
        val result = harness.process().getOrThrow()

        val skipped = harness.items.single { it.status == BankStatementImportItem.STATUS_SKIPPED }
        assertEquals("UNREASONABLE_DATE", skipped.errorReason)
        assertNull(skipped.transactionDate)
        assertEquals(mapOf("UNREASONABLE_DATE" to 1), result.skippedByReason)
    }

    @Test
    fun textRowWithoutDateIsSkippedBeforeAiAndNeverDatedNow() = runTest {
        val dated = parsedRow("OK SHOP", 5.0, StatementTextFixtures.day(2031, 11, 9))
        val dateless = parsedRow("NO DATE SHOP", 7.0, null)
        val harness = Harness(
            textPages = listOf(listOf("unused")),
            textParser = parserReturning(
                TextStatementParseResult(StatementLayoutId.EUROBANK_CARD, listOf(dateless, dated))
            )
        )
        val result = harness.process().getOrThrow()

        val skipped = harness.items.single { it.status == BankStatementImportItem.STATUS_SKIPPED }
        assertEquals(0, skipped.itemIndex)
        assertEquals("NO_DATE", skipped.errorReason)
        assertNull(skipped.transactionDate)
        assertEquals(mapOf("NO_DATE" to 1), result.skippedByReason)

        // Only the dated row is an AI candidate (dense id 0) and the review keeps its own date.
        val candidates = harness.validatorCalls.single()
        assertEquals(1, candidates.size)
        assertEquals(0, candidates.single().candidateId)
        assertTrue(candidates.none { it.date == 0L })
        val review = harness.reviews.single()
        assertEquals(StatementTextFixtures.day(2031, 11, 9), review.suggestedDate)
        assertTrue(review.suggestedDate != harness.clock.now())
        assertEquals(1, harness.items.single { it.status == BankStatementImportItem.STATUS_CREATED_REVIEW }.itemIndex)
    }

    @Test
    fun textRowAboveTheAmountCeilingIsSkippedBeforeAi() = runTest {
        val pages = listOf(
            StatementTextFixtures.EUROBANK_CARD_PAGES[0].take(StatementTextFixtures.EUROBANK_CARD_PAGES[0].indexOf(
                "11/11/2031 13/11/2031 SOME SHOP -3,00"
            )) + listOf(
                "11/11/2031 13/11/2031 SOME SHOP -3,00",
                "10/11/2031 11/11/2031 BIG PURCHASE -150.000,00"
            )
        )
        val harness = Harness(textPages = pages, home = null)
        val result = harness.process().getOrThrow()

        assertEquals(1, result.reviewsCreated)
        assertEquals(mapOf("AMOUNT_ABOVE_CEILING" to 1), result.skippedByReason)
        assertEquals(1, harness.validatorCalls.single().size)
        assertTrue(harness.reviews.none { (it.suggestedAmount ?: 0.0) > STATEMENT_ITEM_AMOUNT_CEILING })
    }

    @Test
    fun ceilingAlsoAppliesOnTheOcrPath() = runTest {
        val harness = Harness(
            ocrRows = listOf("10/05/2031 SHOP -150000.00 USD", "11/05/2031 SHOP -15.00 USD")
        )
        val result = harness.process().getOrThrow()

        assertEquals(1, result.reviewsCreated)
        assertEquals(mapOf("AMOUNT_ABOVE_CEILING" to 1), result.skippedByReason)
        val skipped = harness.items.single { it.status == BankStatementImportItem.STATUS_SKIPPED }
        assertEquals("AMOUNT_ABOVE_CEILING", skipped.errorReason)
        assertEquals(BankStatementImportRun.STATUS_COMPLETED_WITH_SKIPS, harness.finalStatusOrNull())
    }

    @Test
    fun ceilingConstantIsOneHundredThousand() {
        assertEquals(100_000.0, STATEMENT_ITEM_AMOUNT_CEILING, 0.0)
    }

    // ── Routing: unknown layout, failed layout, cancellation, OCR path ────────────

    @Test
    fun unknownTextLayoutFallsBackToTheGenericParserWithARequiredDate() = runTest {
        val harness = Harness(
            textPages = listOf(listOf("10/05/2031 SHOP -12.50 EUR", "SHOP -13.50 EUR"))
        )
        val result = harness.process().getOrThrow()

        // The dateless generic row is dropped (requireDate), never imported with "now".
        assertEquals(1, result.transactionsFound)
        assertEquals(1, result.reviewsCreated)
        assertNotNull(harness.reviews.single().suggestedDate)
        assertTrue(harness.reviews.single().suggestedDate != harness.clock.now())
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun failedLayoutFailsCleanlyWithoutGenericFallbackOrRunLedger() = runTest {
        val harness = Harness(
            textPages = listOf(listOf("10/05/2031 SHOP -12.50 EUR")),
            textParser = parserReturning(
                TextStatementParseResult(StatementLayoutId.NBG_ACCOUNT, emptyList(), failed = true)
            )
        )
        val result = harness.process()

        assertTrue(result.isFailure)
        assertEquals("STATEMENT_LAYOUT_FAILED", result.exceptionOrNull()?.message)
        assertTrue(harness.reviews.isEmpty())
        assertTrue(harness.items.isEmpty())
        coVerify(exactly = 0) { harness.runs.insert(any()) }
        assertTrue(harness.validatorCalls.isEmpty())
    }

    @Test
    fun cancellationInsideTheTextParserPropagates() = runTest {
        val parser = mockk<TextStatementParser>()
        every { parser.parse(any(), any()) } throws kotlinx.coroutines.CancellationException("TEST_CANCELLED")
        val harness = Harness(textPages = listOf(listOf("unused")), textParser = parser)
        try {
            harness.process()
            fail("CancellationException must propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("TEST_CANCELLED", e.message)
        }
        assertTrue(harness.reviews.isEmpty())
    }

    @Test
    fun ocrPathRowWithoutADateStillGetsTheImportTimeAndTheNewRunStatusApplies() = runTest {
        // No pdfTextPages: the generic parser runs as before, a null date is still dated "now" (the
        // import time), and an unknown currency is still skipped as CURRENCY_UNKNOWN; the skipped row
        // next to a created review now ends COMPLETED_WITH_SKIPS.
        val harness = Harness(ocrRows = listOf("SHOP -12.50 USD", "11/05/2031 SHOP -15.00"))
        val result = harness.process()

        assertTrue(result.isSuccess)
        val review = harness.reviews.single()
        assertEquals("USD", review.suggestedCurrency)
        assertEquals(harness.clock.now(), review.suggestedDate)
        assertEquals("CURRENCY_UNKNOWN", harness.items.single { it.status == BankStatementImportItem.STATUS_SKIPPED }.errorReason)
        assertEquals(mapOf("CURRENCY_UNKNOWN" to 1), result.getOrThrow().skippedByReason)
        assertEquals(BankStatementImportRun.STATUS_COMPLETED_WITH_SKIPS, harness.finalStatusOrNull())
    }

    @Test
    fun allSkippedRunStillFails() = runTest {
        val harness = Harness(ocrRows = listOf("11/05/2031 SHOP -15.00"))
        val result = harness.process()
        assertTrue(result.isFailure)
        assertTrue(harness.reviews.isEmpty())
        assertEquals(BankStatementImportRun.STATUS_FAILED, harness.finalStatusOrNull())
    }

    // ── Raw text persistence cap ──────────────────────────────────────────────────

    @Test
    fun storedStatementTextIsCappedBeforeSanitising() = runTest {
        val rows = listOf("10/05/2031 SHOP -12.50 USD")
        val harness = Harness(
            ocrRows = rows, rawMode = RawStorageMode.STORE_RAW, extraFullText = "x".repeat(300_000)
        )
        harness.process().getOrThrow()

        val stored = harness.savedReceipts.single().rawOcrText
        assertNotNull(stored)
        assertEquals(STATEMENT_RAW_PERSIST_CAP, stored!!.length)
        assertEquals(200_000, STATEMENT_RAW_PERSIST_CAP)
    }

    @Test
    fun statementTextIsNotStoredUnderTheFailClosedPolicy() = runTest {
        val harness = Harness(textPages = StatementTextFixtures.EUROBANK_CARD_PAGES, home = null)
        harness.process().getOrThrow()
        assertTrue(harness.savedReceipts.all { it.rawOcrText.isNullOrBlank() })
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private fun assertNoIbanOrLongDigitRun(harness: Harness) {
        val texts = harness.reviews.flatMap { listOfNotNull(it.suggestedMerchant, it.suggestedAccountName) } +
            harness.items.mapNotNull { it.merchant }
        assertTrue(texts.isNotEmpty())
        for (text in texts) {
            assertFalse(text, Regex("""[A-Z]{2}\d{2}(?:\s?[A-Z0-9]){11,30}""").containsMatchIn(text))
            assertFalse(text, Regex("""\d{12}""").containsMatchIn(text))
        }
    }

    private fun pendingReview(
        id: Long, receiptId: Long, merchant: String, amount: Double, date: Long
    ) = PendingReview(
        id = id, rawNotificationId = null, scannedReceiptId = receiptId, suggestedAmount = amount,
        suggestedCurrency = "EUR", suggestedMerchant = merchant,
        suggestedMerchantKey = MerchantKeyGenerator.generate(merchant), suggestedType = "PURCHASE",
        suggestedCategoryId = null, suggestedDate = date, confidence = 0.9f, packageName = "statement.import",
        notificationTitle = null, notificationText = null, createdAt = 1L
    )

    private fun parsedRow(merchant: String, amount: Double, date: Long?): StatementRow.Parsed =
        StatementRow.Parsed(
            tx = ParsedTransaction(
                amount = amount, currency = "EUR", merchant = merchant, type = ParsedTransactionType.PURCHASE,
                confidence = 0.9f, date = date, currencyAssumption = CurrencyAssumption.PARSED_FROM_SOURCE
            ),
            currencyEvidence = CurrencyEvidence.STATEMENT_HEADER
        )

    private fun parserReturning(result: TextStatementParseResult): TextStatementParser {
        val parser = mockk<TextStatementParser>()
        every { parser.parse(any(), any()) } returns result
        return parser
    }

    /** Real processor over mocked DAOs; mirrors the BankStatementSourceBoundaryTest harness. */
    private class Harness(
        textPages: List<List<String>>? = null,
        ocrRows: List<String> = emptyList(),
        home: String? = null,
        textParser: TextStatementParser? = null,
        seededPending: List<PendingReview> = emptyList(),
        rawMode: RawStorageMode = RawStorageMode.DO_NOT_STORE,
        extraFullText: String = ""
    ) {
        // After every fixture date (2031) so the processor's own date sanity check passes.
        val clock = FakeTimeProvider.forDate(2032, 1, 15)
        val items = mutableListOf<BankStatementImportItem>()
        val reviews = mutableListOf<PendingReview>()
        val savedReceipts = mutableListOf<ScannedReceipt>()
        val validatorCalls = mutableListOf<List<DebugTransaction>>()
        /** Raw text handed to AI validation, one entry per call. */
        val validatorTexts = mutableListOf<String>()
        /** Names passed to the merchant normalizer (auto-create learning), in call order. */
        val normalizedNames = mutableListOf<String>()
        val normalizer = mockk<MerchantNormalizer>()
        val runs = mockk<BankStatementImportRunDao>(relaxed = true)
        var validate: ((List<DebugTransaction>) -> StatementValidationOutcome)? = null
        private val finalizations = mutableListOf<Finalization>()
        private val processor: BankStatementLifecycleProcessor
        private val uri = mockk<Uri>(relaxed = true)

        init {
            val maintenanceMode = mockk<RestoreMaintenanceMode>()
            every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
            val barrier = DatabaseWriteBarrier(maintenanceMode)
            val settings = mockk<CurrencySettingsRepository>()
            every { settings.homeCurrency() } returns (home?.let { flowOf(it) } ?: emptyFlow())
            val cleaner = MerchantCleaner()
            val genericParser = BankStatementParser(CurrencyNormalizer(), cleaner, clock, settings)
            val parser = textParser ?: TextStatementParser(cleaner, clock)

            val lines = textPages?.flatten() ?: ocrRows
            val ocr = OcrResult(
                fullText = lines.joinToString("\n") + extraFullText,
                blocks = lines.filter { it.isNotBlank() }.mapIndexed { index, text ->
                    TextBlock(text.trim(), null, 10, 100 + index * 40, 300, 120 + index * 40)
                },
                savedImagePath = "",
                pagesProcessed = textPages?.size,
                totalPages = textPages?.size,
                pdfTextPages = textPages
            )
            val receiptRepository = mockk<ReceiptRepository>()
            coEvery { receiptRepository.runStatementOcr(any()) } returns ocr

            val assetStore = mockk<ReceiptAssetStore>()
            coEvery { assetStore.computeUriHash(any()) } returns Result.failure(IllegalStateException("TEST_NO_HASH"))
            coEvery { assetStore.computeFileHash(any()) } returns Result.failure(IllegalStateException("TEST_NO_HASH"))

            val runner = mockk<DomainTransactionRunner>()
            val context = TransactionContext(correlationId = "test", occurredAt = clock.now())
            coEvery { runner.runInTransaction<Any>(any(), any(), any(), any(), any(), any()) } coAnswers {
                arg<suspend (TransactionContext) -> Any>(5)(context)
            }
            coEvery { runs.insert(any()) } returns 1L
            coEvery {
                runs.finalize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } coAnswers {
                finalizations += Finalization(
                    status = arg<String>(1), totalItems = arg<Int>(3), processedItems = arg<Int>(4),
                    createdReviewCount = arg<Int>(5), failedItemCount = arg<Int>(8)
                )
            }

            val itemDao = mockk<BankStatementImportItemDao>()
            coEvery { itemDao.insert(any()) } answers {
                items += firstArg<BankStatementImportItem>()
                items.size.toLong()
            }
            coEvery { itemDao.countByRunAndStatus(any(), any()) } answers {
                items.count { it.status == secondArg<String>() }
            }

            val pendingDao = mockk<PendingReviewDao>(relaxed = true)
            coEvery { pendingDao.insert(any()) } answers {
                reviews += firstArg<PendingReview>().copy(id = (reviews.size + 1).toLong())
                reviews.size.toLong()
            }
            // Emulates the production query: newest PENDING review with the same merchant key,
            // inside the date window, amount tolerance, currency and a compatible type.
            coEvery {
                pendingDao.getPendingDuplicateCandidateInRangeTypeAware(
                    any(), any(), any(), any(), any(), any(), any(), any()
                )
            } answers {
                val key = arg<String>(0)
                val start = arg<Long>(2)
                val end = arg<Long>(3)
                val min = arg<Double>(4)
                val max = arg<Double>(5)
                val currency = arg<String>(6)
                val type = arg<String>(7)
                (seededPending + reviews.toList()).filter {
                    it.suggestedMerchantKey == key &&
                        (it.suggestedDate ?: Long.MIN_VALUE) in start until end &&
                        (it.suggestedAmount ?: Double.NaN) in min..max &&
                        it.suggestedCurrency.equals(currency, ignoreCase = true) &&
                        (type == "UNKNOWN" || it.suggestedType == "UNKNOWN" || it.suggestedType == type)
                }.lastOrNull()
            }

            val writer = mockk<ReceiptRecordWriter>()
            coEvery { writer.insertOrResolve(any()) } answers {
                val receipt = firstArg<ScannedReceipt>().copy(id = 7L)
                savedReceipts += receipt
                ReceiptRecordWriteResult.Inserted(receipt)
            }

            coEvery { normalizer.normalize(any(), any(), any()) } coAnswers {
                val name = firstArg<String>()
                normalizedNames += name
                val lookup = mockk<MerchantLookupResult>()
                every { lookup.canonical } returns MerchantCanonical(
                    normalizedName = name, searchKey = name.lowercase(),
                    createdAt = clock.now(), updatedAt = clock.now()
                )
                lookup
            }

            val policy = mockk<RawPersistencePolicyResolver>()
            coEvery { policy.forSource(any()) } returns RawPersistencePolicy(
                rawMode, RawSourceType.BANK_STATEMENT, allowParsedAmountDateCurrency = true
            )

            val validator = mockk<ValidateBankStatementTransactionsUseCase>()
            coEvery { validator.validateTransactions(any(), any(), any()) } answers {
                val candidates = secondArg<List<DebugTransaction>>()
                validatorTexts += firstArg<String>()
                validatorCalls += candidates
                validate?.invoke(candidates) ?: StatementValidationOutcome.Validated(candidates.map { tx ->
                    CleanTransaction(
                        tx.candidateId, tx.merchant, tx.amount,
                        if (tx.currencyAssumption == CurrencyAssumption.PARSED_FROM_SOURCE) tx.currency
                        else StatementValidationContracts.CURRENCY_UNKNOWN,
                        tx.date, tx.confidence, StatementValidationContracts.SOURCE_PARSER_ONLY
                    )
                })
            }

            processor = BankStatementLifecycleProcessor(
                database = mockk<AppDatabase>(relaxed = true), transactionRunner = runner,
                receiptRepository = receiptRepository, scannedReceiptDao = mockk(relaxed = true),
                receiptLifecycleEventWriter = mockk(relaxed = true), receiptLinkService = mockk(relaxed = true),
                timeProvider = clock, bankStatementParser = genericParser, textStatementParser = parser,
                pendingReviewDao = pendingDao, expenseDao = mockk<ExpenseDao>(relaxed = true),
                merchantNormalizer = normalizer, hybridClassifier = mockk(relaxed = true),
                duplicateDetector = mockk(relaxed = true), assetStore = assetStore, transactionValidator = validator,
                recurringExpenseRepository = mockk(relaxed = true), writeBarrier = barrier,
                rawPersistencePolicyResolver = policy, bankStatementImportRunDao = runs,
                bankStatementImportItemDao = itemDao, receiptRecordWriter = writer
            )
        }

        suspend fun process() = processor.processBankStatement(uri)

        /** Status passed to the (single) run finalize call, or null when the run was never finalized. */
        fun finalStatusOrNull(): String? = finalizations.lastOrNull()?.status

        fun verifyFinalized(
            status: String, totalItems: Int, processedItems: Int, created: Int, failedPlusSkipped: Int
        ) {
            val finalization = finalizations.single()
            assertEquals(status, finalization.status)
            assertEquals(totalItems, finalization.totalItems)
            assertEquals(processedItems, finalization.processedItems)
            assertEquals(created, finalization.createdReviewCount)
            assertEquals(failedPlusSkipped, finalization.failedItemCount)
        }
    }

    private data class Finalization(
        val status: String,
        val totalItems: Int,
        val processedItems: Int,
        val createdReviewCount: Int,
        val failedItemCount: Int
    )
}
