package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PendingReviewStatus
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.model.Result as ReviewResult
import com.yourname.expensetracker.domain.provenance.PendingReviewPromotionResult
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkPromoter
import com.yourname.expensetracker.domain.provenance.SourceLinkPayload
import com.yourname.expensetracker.domain.provenance.SourceLinkWriteResult
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.provenance.ExpenseSourceFields
import com.yourname.expensetracker.domain.provenance.TargetEntityType
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.SideEffectBatchResult
import com.yourname.expensetracker.domain.transaction.CategoryAssignmentOutcome
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.DefaultExpenseCategoryAssignmentService
import com.yourname.expensetracker.domain.transaction.ExpenseCategoryAssignmentPort
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.transaction.lifecycle.RoomTransactionLifecycleEventWriter
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEvent
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEventWriter
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R0-2 / R1 (RG11-H2 end-to-end, V11-NEW-1): permanent regression test for the silent
 * nested-transaction rollback (success reported while every row was rolled back).
 *
 * REAL Room (production in-memory builder), REAL [ReviewQueueRepository],
 * [ReceiptLinkService], [DefaultExpenseCategoryAssignmentService],
 * [TransactionLifecycleCoordinator], [RoomDomainTransactionRunner] and real DAOs.
 * Only non-DB peripherals are faked: maintenance mode, receipt/diagnostic event writers,
 * post-commit runner, source-link writer/promoter, classifier/ML/budget collaborators,
 * clock, currency settings.
 *
 * Scenario (hand-derived oracle):
 *  - categories C and D exist; receipt R has three item categorizations
 *      item1: suggested=C, userCorrected=C
 *      item2: suggested=C, userCorrected=C
 *      item3: suggested=D, userCorrected=null
 *  - CategoryDao.mergeCategories(C -> D) reassigns suggestedCategoryId only
 *    (CategoryDao.kt:150), then deletes C. After the merge the rows are
 *      item1: suggested=D, userCorrected=C(missing)
 *      item2: suggested=D, userCorrected=C(missing)
 *      item3: suggested=D, userCorrected=null
 *    Majority key = userCorrected ?: suggested -> {C:2, D:1} -> C, which no longer exists.
 *  - Second, non-merge stale seed (NONEXISTENT_ID): both userCorrectedCategoryId values
 *    of item1/item2 point at an id that never existed (majority key = that id).
 *  - Before R1, linking issued UPDATE expenses SET categoryId=<stale> on an uncategorized
 *    expense; the Expense.categoryId FK was violated inside the nested assignment
 *    transaction, which DefaultExpenseCategoryAssignmentService swallowed (Failed) and
 *    ReceiptLinkService ignored, so the outermost commit silently rolled everything back.
 *  - After R1 the assignment service checks the category first and returns
 *    SkippedCategoryMissing; the link commits and the expense stays uncategorized.
 *
 * Invariant asserted for every operation: the operation must NEVER report success while
 * its rows are absent (token SILENT_ROLLBACK).
 *  - success => expense, link and (for approval) review APPROVED all committed
 *  - failure => nothing committed, the review is still PENDING, and no post-commit run
 * For the stale seeds the delegating recorder additionally pins the assignment outcome to
 * exactly [SkippedCategoryMissing] and the expense must stay uncategorized.
 * Messages carry counts, flags and result class names only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(RestrictedExpenseDaoMutation::class)
class ReviewQueueRepositoryNestedRollbackTest {

    private val now = 1_700_000_000_000L

    private lateinit var database: AppDatabase
    private lateinit var postCommitRunner: RecordingPostCommitRunner
    private lateinit var assignmentRecorder: RecordingCategoryAssignmentPort
    private lateinit var linkService: ReceiptLinkService
    private lateinit var repository: ReviewQueueRepository
    private lateinit var receiptCoordinator: ReceiptLifecycleCoordinator
    private lateinit var promoter: FakePromoter
    private lateinit var lifecycleWriter: FaultableLifecycleWriter

    private class RecordingPostCommitRunner : PostCommitActionRunner {
        var runs = 0
        override suspend fun run(batch: PostCommitActionBatch): SideEffectBatchResult {
            runs++
            return SideEffectBatchResult("test", 0, 0, 0, 0, 0, emptyList())
        }
    }

    /** Delegates to the REAL assignment service and records every outcome it returns. */
    private class RecordingCategoryAssignmentPort(
        var delegate: ExpenseCategoryAssignmentPort
    ) : ExpenseCategoryAssignmentPort {
        val outcomes = mutableListOf<CategoryAssignmentOutcome>()

        override suspend fun assignCategoryIfUnset(
            expenseId: Long,
            categoryId: Long,
            source: String,
            correlationId: String?
        ): CategoryAssignmentOutcome =
            delegate.assignCategoryIfUnset(expenseId, categoryId, source, correlationId)
                .also { outcomes += it }

        override suspend fun dispatchAssignedCategorySideEffects(
            expenseId: Long,
            source: String,
            correlationId: String?
        ) = delegate.dispatchAssignedCategorySideEffects(expenseId, source, correlationId)
    }

    /**
     * Real failing nested write (FK violation inside a nested withTransaction) that reports
     * Failed, exactly like the pre-R1 production service did. Mirrors the private fake in
     * ReceiptLinkServiceNestedFailureTest (duplicated here to avoid a new shared file).
     */
    private class FailingNestedWritePort(
        private val database: AppDatabase,
        private val missingCategoryId: Long
    ) : ExpenseCategoryAssignmentPort {
        var assignCalls = 0
        var failedReturns = 0
        var dispatchCalls = 0

        override suspend fun assignCategoryIfUnset(
            expenseId: Long,
            categoryId: Long,
            source: String,
            correlationId: String?
        ): CategoryAssignmentOutcome {
            assignCalls++
            return try {
                database.withTransaction {
                    database.expenseDao().updateCategory(expenseId, missingCategoryId)
                    CategoryAssignmentOutcome.Assigned
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedReturns++
                CategoryAssignmentOutcome.Failed("synthetic nested failure")
            }
        }

        override suspend fun dispatchAssignedCategorySideEffects(
            expenseId: Long,
            source: String,
            correlationId: String?
        ) {
            dispatchCalls++
        }
    }

    private class FakeSourceLinkWriter : SourceLinkWriter {
        override suspend fun linkExpense(
            expenseId: Long,
            payload: SourceLinkPayload,
            correlationId: String?
        ): SourceLinkWriteResult = SourceLinkWriteResult.Created(1L)

        override suspend fun linkTarget(
            targetType: TargetEntityType,
            targetId: Long,
            payload: SourceLinkPayload,
            correlationId: String?
        ): SourceLinkWriteResult = SourceLinkWriteResult.Created(1L)

        override suspend fun linkExpenseSourcesFromRequest(
            expenseId: Long,
            requestSourceFields: ExpenseSourceFields,
            correlationId: String?
        ): List<SourceLinkWriteResult> = emptyList()
    }

    private class FakePromoter : PendingReviewSourceLinkPromoter {
        var result: PendingReviewPromotionResult = PendingReviewPromotionResult(0, 0, 0, 0)

        override suspend fun promotePendingReviewLinksToExpense(
            pendingReviewId: Long,
            expenseId: Long,
            correlationId: String?,
            source: ExpenseSource
        ): PendingReviewPromotionResult = result
    }

    /** Delegates to the REAL lifecycle writer; can be told to fail SOURCE_LINKED writes. */
    private class FaultableLifecycleWriter(
        private val delegate: TransactionLifecycleEventWriter
    ) : TransactionLifecycleEventWriter {
        var failSourceLinked = false
        val sourceLinkedWrites = mutableListOf<TransactionLifecycleEvent>()

        override suspend fun write(context: TransactionContext, event: TransactionLifecycleEvent) {
            if (event.eventType == LifecycleEventType.SOURCE_LINKED.name) {
                sourceLinkedWrites += event
                if (failSourceLinked) throw IllegalStateException("synthetic lifecycle writer failure")
            }
            delegate.write(context, event)
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION_ERROR")
        @Deprecated("Use write(context, event)", level = DeprecationLevel.ERROR)
        override suspend fun write(event: TransactionLifecycleEvent) {
            throw UnsupportedOperationException("legacy write is not supported in this test")
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabaseTestFactory.create(context)
        val timeProvider = FakeTimeProvider(now)

        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        val writeBarrier = DatabaseWriteBarrier(maintenanceMode)

        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        postCommitRunner = RecordingPostCommitRunner()
        val sourceLinkWriter = FakeSourceLinkWriter()

        val txCoordinator = TransactionLifecycleCoordinator(
            database = database,
            expenseDao = database.expenseDao(),
            transactionEventDao = database.transactionEventDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = mockk(relaxed = true),
            runner = postCommitRunner,
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            currencySettingsRepository = currencySettingsRepository,
            sourceLinkWriter = sourceLinkWriter,
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true)
        )

        val categoryAssignment = DefaultExpenseCategoryAssignmentService(
            database = database,
            expenseDao = database.expenseDao(),
            categoryDao = database.categoryDao(),
            writeBarrier = writeBarrier,
            timeProvider = timeProvider,
            transactionEventDao = database.transactionEventDao(),
            transactionLifecycleCoordinator = txCoordinator
        )
        assignmentRecorder = RecordingCategoryAssignmentPort(categoryAssignment)

        val transactionRunner = RoomDomainTransactionRunner(database, timeProvider)
        promoter = FakePromoter()
        lifecycleWriter = FaultableLifecycleWriter(
            RoomTransactionLifecycleEventWriter(
                database.transactionEventDao(),
                EventMetadataSanitizer(),
                timeProvider,
                writeBarrier
            )
        )

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
            writeBarrier = writeBarrier,
            sourceLinkWriter = sourceLinkWriter,
            categoryAssignmentPort = assignmentRecorder,
            transactionRunner = transactionRunner
        )

        repository = ReviewQueueRepository(
            writeBarrier = writeBarrier,
            database = database,
            pendingReviewDao = database.pendingReviewDao(),
            rawNotificationDao = database.rawNotificationDao(),
            expenseDao = database.expenseDao(),
            sourceStatsDao = database.sourceStatsDao(),
            receiptLinkService = linkService,
            userCorrectionDao = database.userCorrectionDao(),
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            classifier = mockk(relaxed = true),
            budgetMonitor = mockk(relaxed = true),
            parserRegistry = mockk(relaxed = true),
            timeProvider = timeProvider,
            confidenceRouter = mockk(relaxed = true),
            transactionLifecycleCoordinator = txCoordinator,
            pendingReviewSourceLinkService = mockk(relaxed = true),
            pendingReviewSourceLinkPromoter = promoter,
            transactionRunner = transactionRunner,
            transactionLifecycleEventWriter = lifecycleWriter,
            postCommitActionRunner = postCommitRunner
        )

        receiptCoordinator = ReceiptLifecycleCoordinator(
            database = database,
            transactionRunner = transactionRunner,
            receiptLifecycleEventWriter = mockk(relaxed = true),
            receiptRepository = mockk(relaxed = true),
            receiptLinkService = linkService,
            assetStore = mockk(relaxed = true),
            inputValidator = mockk(relaxed = true),
            scannedReceiptDao = database.scannedReceiptDao(),
            receiptExpenseLinkDao = database.receiptExpenseLinkDao(),
            receiptEventDao = database.receiptEventDao(),
            emailReceiptDao = mockk(relaxed = true),
            pendingReviewDao = database.pendingReviewDao(),
            pendingReviewSourceLinkService = mockk(relaxed = true),
            timeProvider = timeProvider,
            bankStatementLifecycleProcessor = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            duplicateDetector = mockk(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            writeBarrier = writeBarrier,
            transactionLifecycleCoordinator = txCoordinator,
            postCommitActionRunner = postCommitRunner,
            assetCleanupCoordinator = mockk(relaxed = true),
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            rawPersistencePolicyResolver = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true),
            sourceLinkWriter = sourceLinkWriter,
            receiptSideEffectPlanner = mockk(relaxed = true),
            receiptInsertResolver = mockk(relaxed = true),
            receiptParser = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    // ---------------------------------------------------------------- fixtures

    /** NONE = control (C exists); MERGED_AWAY = C merged into D; NONEXISTENT_ID = never-existing id. */
    private enum class Seed { NONE, MERGED_AWAY, NONEXISTENT_ID }

    private val neverExistingCategoryId = 987_654L

    private class Fixture(
        val categoryC: Long,
        val categoryD: Long,
        val receiptId: Long,
        /** The id that userCorrectedCategoryId of item1/item2 points at (the majority key). */
        val majorityCategoryId: Long
    )

    private fun foreignKeysPragma(): String =
        database.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0).toString() else "unknown"
        }

    private fun item(receiptId: Long, n: Int, suggested: Long, userCorrected: Long?) =
        ReceiptItemCategorization(
            receiptId = receiptId,
            itemDescription = "Item $n",
            itemAmount = 4.0,
            suggestedCategoryId = suggested,
            suggestedCategoryName = null,
            confidence = 0.9f,
            aiRationale = null,
            alternativeCategoriesJson = null,
            userCorrectedCategoryId = userCorrected,
            userCorrectedCategoryName = null,
            userCorrectedAt = if (userCorrected != null) now else null,
            taxAmount = null,
            createdAt = now,
            updatedAt = now
        )

    /**
     * Seeds categories C and D, the scanned receipt R and its three item rows (see the
     * class comment). For [Seed.MERGED_AWAY] the REAL CategoryDao.mergeCategories(C -> D)
     * runs (it also deletes C through the real DAO delete path). For [Seed.NONEXISTENT_ID]
     * item1/item2 userCorrectedCategoryId point at an id that never existed (no merge).
     */
    private suspend fun seed(mode: Seed): Fixture {
        val c = database.categoryDao().insert(Category(name = "Synthetic C", icon = "C", color = "#112233"))
        val d = database.categoryDao().insert(Category(name = "Synthetic D", icon = "D", color = "#445566"))
        assertTrue("fixture: category inserts failed", c > 0L && d > 0L && c != d)
        val majorityCategoryId = if (mode == Seed.NONEXISTENT_ID) neverExistingCategoryId else c
        if (mode == Seed.NONEXISTENT_ID) {
            assertNull(
                "fixture: never-existing category id must not exist",
                database.categoryDao().getById(neverExistingCategoryId)
            )
        }

        val receiptId = database.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "synthetic",
                parsedTotal = 12.0,
                parsedMerchant = "Synthetic Merchant",
                parsedDate = now,
                parsedItems = "[]",
                parsedTaxAmount = null,
                confidence = 0.9f,
                createdAt = now,
                updatedAt = now,
                sourceType = "CAMERA",
                documentType = "RETAIL_RECEIPT"
            )
        )
        assertTrue("fixture: receipt insert failed", receiptId > 0L)

        val items = database.receiptItemCategorizationDao()
        items.insert(item(receiptId, 1, suggested = c, userCorrected = majorityCategoryId))
        items.insert(item(receiptId, 2, suggested = c, userCorrected = majorityCategoryId))
        items.insert(item(receiptId, 3, suggested = d, userCorrected = null))

        if (mode == Seed.MERGED_AWAY) {
            database.categoryDao().mergeCategories(c, d)
        }
        return Fixture(c, d, receiptId, majorityCategoryId)
    }

    /**
     * Stale-seed expectation (post-R1): the real assignment service was consulted exactly
     * once and returned SkippedCategoryMissing (never Failed), and the expense stays
     * uncategorized. Messages carry the outcome class names only.
     */
    private suspend fun assertStaleCategorySkipped(expenseId: Long, seedName: String) {
        assertEquals(
            "assignment outcomes for seed=$seedName",
            listOf<CategoryAssignmentOutcome>(CategoryAssignmentOutcome.SkippedCategoryMissing),
            assignmentRecorder.outcomes
        )
        assertNull(
            "expense must stay uncategorized for seed=$seedName",
            database.expenseDao().getById(expenseId)!!.categoryId
        )
    }

    private suspend fun insertPendingReview(receiptId: Long): Long {
        val id = database.pendingReviewDao().insert(
            PendingReview(
                rawNotificationId = null,
                scannedReceiptId = receiptId,
                suggestedAmount = 25.0,
                suggestedCurrency = "EUR",
                suggestedMerchant = "Synthetic Merchant",
                suggestedType = TransactionType.PURCHASE.name,
                suggestedCategoryId = null,
                confidence = 0.9f,
                packageName = "com.example.synthetic",
                notificationTitle = null,
                notificationText = null,
                createdAt = now
            )
        )
        assertTrue("fixture: pending review insert failed", id > 0L)
        assertEquals(
            "fixture: review must start PENDING",
            PendingReviewStatus.PENDING,
            database.pendingReviewDao().getById(id)!!.status
        )
        return id
    }

    private suspend fun insertUncategorizedExpense(): Long {
        val id = database.expenseDao().insert(
            Expense(
                amount = 25.0,
                merchant = "Synthetic Merchant",
                transactionType = TransactionType.PURCHASE,
                date = now
            )
        )
        assertTrue("fixture: expense insert failed", id > 0L)
        assertNull("fixture: expense must start uncategorized", database.expenseDao().getById(id)!!.categoryId)
        return id
    }

    private fun createRequest(receiptId: Long) = CreateExpenseRequest(
        merchant = "Synthetic Merchant",
        amount = 25.0,
        currency = "EUR",
        date = now,
        transactionType = TransactionType.PURCHASE,
        source = ExpenseSource.RECEIPT_SCAN,
        scannedReceiptId = receiptId,
        isManualEntry = true
    )

    // ------------------------------------------------------------ observation

    private class Observation(
        val expenses: Int,
        val links: Int,
        val reviewStatus: PendingReviewStatus?,
        val postCommitRuns: Int
    )

    private suspend fun observe(receiptId: Long, reviewId: Long?): Observation = Observation(
        expenses = database.expenseDao().getTotalCount(),
        links = database.receiptExpenseLinkDao().getLinksForReceipt(receiptId).size,
        reviewStatus = reviewId?.let { database.pendingReviewDao().getById(it)?.status },
        postCommitRuns = postCommitRunner.runs
    )

    /**
     * Never-success-while-rows-absent invariant.
     * [expensesBefore] is the number of expense rows that exist before the operation;
     * [createsExpense] says whether a successful operation adds exactly one.
     */
    private fun assertNeverSuccessWhileRowsAbsent(
        op: String,
        resultType: String,
        reportedSuccess: Boolean,
        reportedFailure: Boolean,
        expensesBefore: Int,
        createsExpense: Boolean,
        obs: Observation
    ) {
        val ctx = "op=$op, result=$resultType, foreign_keys=${foreignKeysPragma()}, " +
            "postCommitRuns=${obs.postCommitRuns}"
        val counts = "expenses=${obs.expenses}, links=${obs.links}, review=${obs.reviewStatus ?: "n/a"}"
        val expectedExpensesOnSuccess = expensesBefore + if (createsExpense) 1 else 0

        if (!reportedSuccess && !reportedFailure) {
            fail("UNEXPECTED RESULT TYPE: $counts [$ctx]")
        }
        if (reportedSuccess) {
            if (obs.reviewStatus == PendingReviewStatus.PENDING || obs.links == 0) {
                fail("SILENT_ROLLBACK: success reported, review ${obs.reviewStatus ?: "n/a"}, " +
                    "expenses=${obs.expenses}, links=${obs.links} [$ctx]")
            }
            if (obs.expenses != expectedExpensesOnSuccess || obs.links != 1) {
                fail("INCONSISTENT SUCCESS: $counts, want expenses=$expectedExpensesOnSuccess links=1 [$ctx]")
            }
            if (obs.reviewStatus != null && obs.reviewStatus != PendingReviewStatus.APPROVED) {
                fail("INCONSISTENT SUCCESS: review not APPROVED, $counts [$ctx]")
            }
        } else {
            if (obs.expenses != expensesBefore || obs.links != 0) {
                fail("FAILURE LEFT RESIDUE: $counts, want expenses=$expensesBefore links=0 [$ctx]")
            }
            if (obs.reviewStatus != null && obs.reviewStatus != PendingReviewStatus.PENDING) {
                fail("FAILURE LEFT RESIDUE: review not PENDING, $counts [$ctx]")
            }
            if (obs.postCommitRuns != 0) {
                fail("FAILURE RAN POST-COMMIT ACTIONS: $counts [$ctx]")
            }
        }
    }

    // ------------------------------------------------------- V11-NEW-1 decision

    /**
     * V11-NEW-1 part 1. Oracle: after mergeCategories(C -> D) the two rows whose
     * userCorrectedCategoryId was C still hold C, C is gone from categories, and all three
     * suggestedCategoryId values are D (the only column the merge reassigns).
     */
    @Test
    fun v11New1_staleUserCorrectedReferenceExistsAfterMerge() = runTest {
        val f = seed(Seed.MERGED_AWAY)

        val rows = database.receiptItemCategorizationDao().getByReceiptId(f.receiptId)
        assertEquals("fixture: item row count", 3, rows.size)
        assertTrue(
            "FIXTURE: merge did not reassign suggestedCategoryId",
            rows.all { it.suggestedCategoryId == f.categoryD }
        )
        assertNull("FIXTURE: merged-away category C still exists", database.categoryDao().getById(f.categoryC))
        assertNotNull("FIXTURE: target category D missing", database.categoryDao().getById(f.categoryD))

        val stillPointingAtC = rows.count { it.userCorrectedCategoryId == f.categoryC }
        if (stillPointingAtC != 2) {
            fail("V11-NEW-1 REFUTED: merge reassigned userCorrectedCategoryId " +
                "(rowsStillPointingAtMergedAwayCategory=$stillPointingAtC, expected 2)")
        }
        // The stale id is not just unreferenced data: it is the majority key (C:2 vs D:1).
        val staleReferenceExists = database.categoryDao().getById(rows.first { it.userCorrectedCategoryId != null }
            .userCorrectedCategoryId!!) == null
        assertTrue("V11-NEW-1 REFUTED: majority category id still exists in categories", staleReferenceExists)
    }

    /**
     * V11-NEW-1 part 2 (does the FK trigger fire?). Oracle: with foreign_keys enforced,
     * UPDATE expenses SET categoryId = <merged-away id> throws a constraint exception and
     * leaves the expense uncategorized. Decided separately from the rollback tests.
     */
    @Test
    fun v11New1_foreignKeyViolationFiresWhenAssigningMergedAwayCategory() = runTest {
        val f = seed(Seed.MERGED_AWAY)
        val expenseId = insertUncategorizedExpense()

        val failure = runCatching { database.expenseDao().updateCategory(expenseId, f.categoryC) }
            .exceptionOrNull()

        if (failure == null) {
            fail("V11-NEW-1 FK NOT TRIGGERED: update to merged-away category id did not throw " +
                "(foreign_keys=${foreignKeysPragma()}, " +
                "expenseCategoryId=${database.expenseDao().getById(expenseId)!!.categoryId})")
        }
        assertNull(
            "failed UPDATE must leave the expense uncategorized (failure=${failure!!.javaClass.simpleName})",
            database.expenseDao().getById(expenseId)!!.categoryId
        )
    }

    // ----------------------------------------------------- approveReview (UI entry)

    /**
     * approveReview with a stale majority category must never report success while the
     * review is PENDING / the rows are absent. Oracle (post-R1): Success, 1 expense,
     * 1 link, review APPROVED, assignment outcome SkippedCategoryMissing, expense uncategorized.
     */
    private suspend fun approveReviewWithStaleSeed(mode: Seed) {
        val f = seed(mode)
        val reviewId = insertPendingReview(f.receiptId)

        val result = repository.approveReview(reviewId)

        assertNeverSuccessWhileRowsAbsent(
            op = "approveReview",
            resultType = result.javaClass.simpleName,
            reportedSuccess = result is ReviewResult.Success,
            reportedFailure = result is ReviewResult.Error,
            expensesBefore = 0,
            createsExpense = true,
            obs = observe(f.receiptId, reviewId)
        )
        assertTrue("approveReview must succeed for seed=$mode (result=${result.javaClass.simpleName})", result is ReviewResult.Success)
        assertStaleCategorySkipped((result as ReviewResult.Success).data, mode.name)
    }

    @Test
    fun approveReview_mergedAwayMajorityCategory_neverReportsSuccessWhileRowsAbsent() = runTest {
        approveReviewWithStaleSeed(Seed.MERGED_AWAY)
    }

    @Test
    fun approveReview_nonexistentMajorityCategory_neverReportsSuccessWhileRowsAbsent() = runTest {
        approveReviewWithStaleSeed(Seed.NONEXISTENT_ID)
    }

    /**
     * Normal control (must pass before and after the fix): category C still exists, so the
     * nested category assignment succeeds. Oracle: Success, exactly 1 expense categorized C,
     * 1 link, review APPROVED.
     */
    @Test
    fun approveReview_control_existingMajorityCategory_commitsExpenseLinkAndApproval() = runTest {
        val f = seed(Seed.NONE)
        val reviewId = insertPendingReview(f.receiptId)

        val result = repository.approveReview(reviewId)

        val obs = observe(f.receiptId, reviewId)
        val ctx = "result=${result.javaClass.simpleName}, expenses=${obs.expenses}, links=${obs.links}, " +
            "review=${obs.reviewStatus}, foreign_keys=${foreignKeysPragma()}"
        assertTrue("CONTROL BROKEN: expected Success [$ctx]", result is ReviewResult.Success)
        assertEquals("CONTROL BROKEN: expenses [$ctx]", 1, obs.expenses)
        assertEquals("CONTROL BROKEN: links [$ctx]", 1, obs.links)
        assertEquals("CONTROL BROKEN: review [$ctx]", PendingReviewStatus.APPROVED, obs.reviewStatus)
        val expenseId = (result as ReviewResult.Success).data
        assertEquals(
            "CONTROL BROKEN: majority category C not assigned [$ctx]",
            f.categoryC,
            database.expenseDao().getById(expenseId)!!.categoryId
        )
    }

    /**
     * Failed-outcome abort (reviewer finding 1). The category port performs a REAL failing
     * nested Room write (FK violation) and returns Failed; the real approveReview must then
     * return the repository error type and roll back everything (never a silent success).
     * Oracle: Error (not Success), review PENDING, 0 expenses, 0 receipt links, 0 post-commit
     * runs, 0 dispatches; the port was invoked exactly once and returned Failed exactly once
     * (so the test cannot pass vacuously).
     */
    @Test
    fun approveReview_failedCategoryAssignmentOutcome_returnsErrorAndRollsBackEverything() = runTest {
        val f = seed(Seed.NONE)
        val reviewId = insertPendingReview(f.receiptId)
        val failingPort = FailingNestedWritePort(database, neverExistingCategoryId)
        assertNull(
            "fixture: never-existing category id must not exist",
            database.categoryDao().getById(neverExistingCategoryId)
        )
        assignmentRecorder.delegate = failingPort

        val result = repository.approveReview(reviewId)

        val obs = observe(f.receiptId, reviewId)
        val ctx = "result=${result.javaClass.simpleName}, expenses=${obs.expenses}, " +
            "links=${obs.links}, review=${obs.reviewStatus}, postCommitRuns=${obs.postCommitRuns}, " +
            "portCalls=${failingPort.assignCalls}, failedReturns=${failingPort.failedReturns}"
        assertEquals("port must be invoked exactly once [$ctx]", 1, failingPort.assignCalls)
        assertEquals("port must return Failed exactly once [$ctx]", 1, failingPort.failedReturns)
        assertEquals(
            "recorded outcome must be exactly [Failed] [$ctx]",
            listOf("Failed"),
            assignmentRecorder.outcomes.map { it.javaClass.simpleName }
        )
        assertTrue("approveReview must return Error, not Success [$ctx]", result is ReviewResult.Error)
        assertEquals("review must still be PENDING [$ctx]", PendingReviewStatus.PENDING, obs.reviewStatus)
        assertEquals("no expense may remain [$ctx]", 0, obs.expenses)
        assertEquals("no receipt link may remain [$ctx]", 0, obs.links)
        assertEquals("post-commit runner must not run [$ctx]", 0, obs.postCommitRuns)
        assertEquals("no category dispatch [$ctx]", 0, failingPort.dispatchCalls)
    }

    // ------------------------------------------- SOURCE_LINKED via the lifecycle writer

    /**
     * promotion.inserted > 0: exactly one SOURCE_LINKED promotion event is written through
     * the lifecycle writer (legal path) with the approved expense id, REVIEW_APPROVAL source,
     * the promotion metadata keys, and the runner clock as occurredAt.
     */
    @Test
    fun approveReview_promotionInserted_writesExactlyOneSourceLinkedEventThroughWriter() = runTest {
        val f = seed(Seed.NONE)
        val reviewId = insertPendingReview(f.receiptId)
        promoter.result = PendingReviewPromotionResult(attempted = 3, inserted = 2, alreadyExists = 1, failed = 0)

        val result = repository.approveReview(reviewId)

        assertTrue("approveReview must succeed (result=${result.javaClass.simpleName})", result is ReviewResult.Success)
        val expenseId = (result as ReviewResult.Success).data
        assertEquals("writer must see exactly one SOURCE_LINKED write", 1, lifecycleWriter.sourceLinkedWrites.size)
        val persisted = database.transactionEventDao().getEventsForExpense(expenseId)
            .filter { it.eventType == LifecycleEventType.SOURCE_LINKED.name && it.reason == "Source links promoted from pending review" }
        assertEquals("exactly one persisted promotion event", 1, persisted.size)
        val event = persisted.single()
        assertEquals(expenseId, event.expenseId)
        assertEquals(ExpenseSource.REVIEW_APPROVAL.name, event.source)
        assertEquals(now, event.occurredAt)
        val meta = org.json.JSONObject(event.metadata!!)
        assertEquals("PENDING_REVIEW_SOURCE_PROMOTION", meta.getString("operation"))
        assertEquals(reviewId, meta.getLong("pendingReviewId"))
        assertEquals(3, meta.getInt("attempted"))
        assertEquals(2, meta.getInt("inserted"))
        assertEquals(1, meta.getInt("alreadyExists"))
    }

    /** promotion.inserted == 0: no SOURCE_LINKED promotion event is written. */
    @Test
    fun approveReview_promotionNothingInserted_writesNoSourceLinkedEvent() = runTest {
        val f = seed(Seed.NONE)
        val reviewId = insertPendingReview(f.receiptId)
        promoter.result = PendingReviewPromotionResult(attempted = 1, inserted = 0, alreadyExists = 1, failed = 0)

        val result = repository.approveReview(reviewId)

        assertTrue("approveReview must succeed", result is ReviewResult.Success)
        assertEquals("no SOURCE_LINKED write expected", 0, lifecycleWriter.sourceLinkedWrites.size)
    }

    /**
     * A failing SOURCE_LINKED write must roll back the whole approval (no try/catch around the
     * writer): Error result, review PENDING, no expense, no link, no post-commit run, no event.
     */
    @Test
    fun approveReview_sourceLinkedWriterFailure_rollsBackApprovalAndReturnsError() = runTest {
        val f = seed(Seed.NONE)
        val reviewId = insertPendingReview(f.receiptId)
        promoter.result = PendingReviewPromotionResult(attempted = 1, inserted = 1, alreadyExists = 0, failed = 0)
        lifecycleWriter.failSourceLinked = true

        val result = repository.approveReview(reviewId)

        val obs = observe(f.receiptId, reviewId)
        val ctx = "result=${result.javaClass.simpleName}, expenses=${obs.expenses}, links=${obs.links}, " +
            "review=${obs.reviewStatus}, postCommitRuns=${obs.postCommitRuns}"
        assertEquals("writer must be invoked exactly once [$ctx]", 1, lifecycleWriter.sourceLinkedWrites.size)
        assertTrue("approveReview must return Error [$ctx]", result is ReviewResult.Error)
        assertEquals("review must still be PENDING [$ctx]", PendingReviewStatus.PENDING, obs.reviewStatus)
        assertEquals("no expense may remain [$ctx]", 0, obs.expenses)
        assertEquals("no receipt link may remain [$ctx]", 0, obs.links)
        assertEquals("post-commit runner must not run [$ctx]", 0, obs.postCommitRuns)
        assertEquals(
            "no SOURCE_LINKED event may remain [$ctx]",
            0,
            database.transactionEventDao().countByType(LifecycleEventType.SOURCE_LINKED.name)
        )
    }

    // -------------------------------------------------- standalone linkReceiptToExpense

    /**
     * Standalone link (outermost transaction is the link transaction itself; the
     * assignment is its nested child). Must not return success with the link absent.
     * Oracle (post-R1): success, 1 link, expense still the one seeded row, uncategorized,
     * assignment outcome SkippedCategoryMissing.
     */
    private suspend fun standaloneLinkWithStaleSeed(mode: Seed) {
        val f = seed(mode)
        val expenseId = insertUncategorizedExpense()

        val result = linkService.linkReceiptToExpense(
            receiptId = f.receiptId,
            expenseId = expenseId,
            linkType = "DIRECT_SAVE",
            source = "TEST"
        )

        assertNeverSuccessWhileRowsAbsent(
            op = "linkReceiptToExpense",
            resultType = if (result.isSuccess) "Success" else "Failure",
            reportedSuccess = result.isSuccess,
            reportedFailure = result.isFailure,
            expensesBefore = 1,
            createsExpense = false,
            obs = observe(f.receiptId, null)
        )
        assertTrue("standalone link must succeed for seed=$mode", result.isSuccess)
        assertStaleCategorySkipped(expenseId, mode.name)
    }

    @Test
    fun standaloneLink_mergedAwayMajorityCategory_neverReportsSuccessWhileLinkAbsent() = runTest {
        standaloneLinkWithStaleSeed(Seed.MERGED_AWAY)
    }

    @Test
    fun standaloneLink_nonexistentMajorityCategory_neverReportsSuccessWhileLinkAbsent() = runTest {
        standaloneLinkWithStaleSeed(Seed.NONEXISTENT_ID)
    }

    /** Normal control: existing category, standalone link commits the link and the category. */
    @Test
    fun standaloneLink_control_existingMajorityCategory_commitsLinkAndCategory() = runTest {
        val f = seed(Seed.NONE)
        val expenseId = insertUncategorizedExpense()

        val result = linkService.linkReceiptToExpense(
            receiptId = f.receiptId,
            expenseId = expenseId,
            linkType = "DIRECT_SAVE",
            source = "TEST"
        )

        val obs = observe(f.receiptId, null)
        val ctx = "success=${result.isSuccess}, expenses=${obs.expenses}, links=${obs.links}, " +
            "foreign_keys=${foreignKeysPragma()}"
        assertTrue("CONTROL BROKEN: expected success [$ctx]", result.isSuccess)
        assertEquals("CONTROL BROKEN: links [$ctx]", 1, obs.links)
        assertEquals(
            "CONTROL BROKEN: majority category C not assigned [$ctx]",
            f.categoryC,
            database.expenseDao().getById(expenseId)!!.categoryId
        )
    }

    // ----------------------------------------------- createExpenseAndLinkReceipt (variant)

    /**
     * User-entered expense + receipt link in one outer transaction
     * (ReceiptLifecycleCoordinator.createExpenseAndLinkReceipt). Must not return success
     * with the expense or link absent. Oracle (post-R1): success, 1 expense, 1 link,
     * uncategorized, assignment outcome SkippedCategoryMissing.
     */
    private suspend fun createExpenseAndLinkWithStaleSeed(mode: Seed) {
        val f = seed(mode)

        val result = receiptCoordinator.createExpenseAndLinkReceipt(createRequest(f.receiptId))

        assertNeverSuccessWhileRowsAbsent(
            op = "createExpenseAndLinkReceipt",
            resultType = if (result.isSuccess) "Success" else "Failure",
            reportedSuccess = result.isSuccess,
            reportedFailure = result.isFailure,
            expensesBefore = 0,
            createsExpense = true,
            obs = observe(f.receiptId, null)
        )
        assertTrue("create+link must succeed for seed=$mode", result.isSuccess)
        assertStaleCategorySkipped(result.getOrThrow(), mode.name)
    }

    @Test
    fun createExpenseAndLinkReceipt_mergedAwayMajorityCategory_neverReportsSuccessWhileRowsAbsent() = runTest {
        createExpenseAndLinkWithStaleSeed(Seed.MERGED_AWAY)
    }

    @Test
    fun createExpenseAndLinkReceipt_nonexistentMajorityCategory_neverReportsSuccessWhileRowsAbsent() = runTest {
        createExpenseAndLinkWithStaleSeed(Seed.NONEXISTENT_ID)
    }

    /** Normal control for the create+link path with an existing category. */
    @Test
    fun createExpenseAndLinkReceipt_control_existingMajorityCategory_commitsExpenseAndLink() = runTest {
        val f = seed(Seed.NONE)

        val result = receiptCoordinator.createExpenseAndLinkReceipt(createRequest(f.receiptId))

        val obs = observe(f.receiptId, null)
        val ctx = "success=${result.isSuccess}, expenses=${obs.expenses}, links=${obs.links}, " +
            "foreign_keys=${foreignKeysPragma()}"
        assertTrue("CONTROL BROKEN: expected success [$ctx]", result.isSuccess)
        assertEquals("CONTROL BROKEN: expenses [$ctx]", 1, obs.expenses)
        assertEquals("CONTROL BROKEN: links [$ctx]", 1, obs.links)
        assertEquals(
            "CONTROL BROKEN: majority category C not assigned [$ctx]",
            f.categoryC,
            database.expenseDao().getById(result.getOrThrow())!!.categoryId
        )
    }
}
