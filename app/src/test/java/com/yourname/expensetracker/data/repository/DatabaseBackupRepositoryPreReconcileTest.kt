package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceOperationRunner
import com.yourname.expensetracker.data.backup.RestoreDatabaseOpener
import com.yourname.expensetracker.data.backup.RestoreInternalWriteScope
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.SqliteSnapshotCreator
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.NoOpOperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import org.json.JSONObject
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpFailure
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPreBackupReconciler
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentReconcileReport
import com.yourname.expensetracker.domain.installment.lifecycle.PreBackupReconcileResult
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController
import com.yourname.expensetracker.testfixtures.backup.lowIterationBackupEncryptionService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * S6c-4 (INV-30, owner item 17, handoff F8): the `.costbackup` export reconciles the installment
 * plans in NORMAL mode, immediately before it enters BACKUP_EXPORTING, so a backup taken right after
 * an expense delete whose plan reaction did not run still passes `BackupVerifier`.
 *
 * Real file-backed Room database (the repository reads the live database file), the real backup
 * pipeline (maintenance runner, snapshot creator, verifier, bundle writer), a real plan
 * coordinator and a real [InstallmentPreBackupReconciler]. Only the maintenance-mode state is a
 * mock (a state machine with a trace), and the unreacted delete is simulated by a raw SQL DELETE
 * in the test only. Synthetic data only.
 *
 * The write barrier of the plan coordinator, the maintenance runner and the repository is ONE
 * [DatabaseWriteBarrier] over that mode, so the reconcile really is refused if it ran after the
 * maintenance mode was entered.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseBackupRepositoryPreReconcileTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var mode: RestoreMaintenanceMode
    private lateinit var barrier: DatabaseWriteBarrier

    /** Ordered facts of one run: RECONCILE (plans read by the reconcile), ENTER, EXIT. */
    private val trace = CopyOnWriteArrayList<String>()
    private var currentMode = RestoreMaintenanceMode.Mode.NORMAL

    /** Runs inside the mode's `enter`, i.e. after the pre-reconcile and before the drain. */
    private var afterReconcileHook: (() -> Unit)? = null

    private var categoryId = 0L

    private lateinit var cacheTmp: File
    private lateinit var backupContext: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // TRUNCATE is kept to mirror DatabaseBackupRepositoryImplTest's rollback-journal harness
        // (Room's builder is last-call-wins, so it overrides the WAL mode that
        // AppDatabase.fileBuilder applies). The WAL checkpoint path is NOT exercised here; it is
        // covered by the instrumented BackupWalLockInstrumentedTest. The real JVM blocker was
        // Robolectric's default cacheDir (see the cacheTmp ContextWrapper below), not the journal mode.
        db =AppDatabase.fileBuilder(context)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        db.openHelper.writableDatabase
        // Debug iteration 2: the snapshot copy could not be opened from Robolectric's default cache dir
        // ([14] unable to open database file at DatabaseBackupRepositoryImpl :802). Mirror the passing
        // DatabaseBackupRepositoryImplTest: the repository gets a plain temp directory as its cache dir.
        cacheTmp = createTempDir(prefix = "prereconcile-cache-")
        backupContext = object : android.content.ContextWrapper(context) {
            override fun getCacheDir(): File = cacheTmp
        }

        mode = mockk(relaxed = true)
        every { mode.currentMode() } answers { currentMode }
        every { mode.isWritesAllowed() } answers { currentMode == RestoreMaintenanceMode.Mode.NORMAL }
        every { mode.enter(any()) } answers {
            trace += "ENTER"
            afterReconcileHook?.invoke()
            currentMode = firstArg()
        }
        every { mode.exit(any()) } answers {
            trace += "EXIT"
            currentMode = RestoreMaintenanceMode.Mode.NORMAL
        }
        barrier = DatabaseWriteBarrier(mode)
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        context.deleteDatabase(AppDatabase.DATABASE_NAME)
        File(context.filesDir, "costbackups").deleteRecursively()
        if (::cacheTmp.isInitialized) cacheTmp.deleteRecursively()
    }

    // ------------------------------------------------------------------ item 17

    @Test
    fun a_backup_right_after_an_unreacted_expense_delete_passes_verify_because_the_pre_reconcile_repaired_it() =
        runTest {
            val planId = seedUnreactedDeleteOfAPaidEntry()
            assertEquals("precondition: the delete left an orphan link", 1, orphanEntryLinkCount())

            val handle = CapturingRunHandle()
            val result = newRepository(realReconciler(), runHandle = handle).createCostBackup(
                password = "pre-reconcile-password",
                includeReceiptImages = false,
                redacted = false,
                privacyMode = null
            )

            assertTrue("backup must succeed: ${detail(result)}", result.isSuccess)
            assertTrue(result.getOrThrow().exists())
            assertTrue(
                "no INSTALLMENT_PRE_RECONCILE event on a successful reconcile: ${handle.events.map { it.stage }}",
                handle.events.none { it.stage == "INSTALLMENT_PRE_RECONCILE" }
            )
            // The reconcile (R2) reopened the entry whose expense was gone.
            assertEquals(0, orphanEntryLinkCount())
            val reopened = db.installmentPlanDao().getEntriesForPlan(planId).single { it.seq == 1 }
            assertEquals("SCHEDULED", reopened.status)
            assertNull(reopened.linkedExpenseId)
            assertEquals(RestoreMaintenanceMode.Mode.NORMAL, currentMode)
        }

    @Test
    fun control_without_the_pre_reconcile_the_same_unreacted_delete_fails_the_snapshot_verify() = runTest {
        seedUnreactedDeleteOfAPaidEntry()
        val noRepair = mockk<InstallmentPreBackupReconciler> {
            coEvery { reconcileBeforeBackup() } returns PreBackupReconcileResult.Reconciled
        }

        val result = newRepository(noRepair).createCostBackup(
            password = "pre-reconcile-password",
            includeReceiptImages = false,
            redacted = false,
            privacyMode = null
        )

        assertTrue("expected a verify failure: ${detail(result)}", result.isFailure)
        assertTrue(
            detail(result),
            result.exceptionOrNull()?.message.orEmpty()
                .contains("Orphan installment_entries linked to non-existent expenses")
        )
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, currentMode)
        assertNoBackupArtifacts()
    }

    // ------------------------------------------------------------------ verifier check 3 (R5)

    @Test
    fun a_backup_after_an_unreacted_purchase_delete_passes_because_the_pre_reconcile_detached_the_plan() =
        runTest {
            val planId = seedUnreactedDeleteOfAnAttachedPurchase()
            assertEquals("precondition: the delete left an orphan purchase link", 1, orphanPurchasePlanCount())

            val result = newRepository(realReconciler()).createCostBackup(
                password = "pre-reconcile-password",
                includeReceiptImages = false,
                redacted = false,
                privacyMode = null
            )

            assertTrue("backup must succeed: ${detail(result)}", result.isSuccess)
            assertTrue(result.getOrThrow().exists())
            // The reconcile (R5) detached the plan whose purchase expense was gone.
            assertEquals(0, orphanPurchasePlanCount())
            val detached = db.installmentPlanDao().getPlanById(planId)!!
            assertEquals("STANDALONE", detached.kind)
            assertNull(detached.purchaseExpenseId)
            assertEquals(RestoreMaintenanceMode.Mode.NORMAL, currentMode)
        }

    @Test
    fun control_without_the_pre_reconcile_the_orphan_purchase_plan_fails_the_snapshot_verify() = runTest {
        seedUnreactedDeleteOfAnAttachedPurchase()
        val noRepair = mockk<InstallmentPreBackupReconciler> {
            coEvery { reconcileBeforeBackup() } returns PreBackupReconcileResult.Reconciled
        }

        val result = newRepository(noRepair).createCostBackup(
            password = "pre-reconcile-password",
            includeReceiptImages = false,
            redacted = false,
            privacyMode = null
        )

        assertTrue("expected a verify failure: ${detail(result)}", result.isFailure)
        assertTrue(
            detail(result),
            result.exceptionOrNull()?.message.orEmpty()
                .contains("Orphan installment_plans referencing non-existent purchase expenses")
        )
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, currentMode)
        assertNoBackupArtifacts()
    }

    // ------------------------------------------------------------------ ordering

    @Test
    fun the_reconciler_runs_exactly_once_and_before_the_maintenance_mode_is_entered() = runTest {
        seedUnreactedDeleteOfAPaidEntry()

        val result = newRepository(realReconciler()).createCostBackup(
            password = "pre-reconcile-password",
            includeReceiptImages = false,
            redacted = false,
            privacyMode = null
        )

        assertTrue(detail(result), result.isSuccess)
        assertEquals(listOf("RECONCILE", "ENTER", "EXIT"), trace.toList())
    }

    @Test
    fun a_privacy_denial_never_reaches_the_reconciler() = runTest {
        seedUnreactedDeleteOfAPaidEntry()
        val gate = mockk<PrivacyGate>()
        coEvery { gate.check(any(), any()) } returns PrivacyDecision.Denied("ignored")

        val result = newRepository(realReconciler(), gate).createCostBackup(
            password = "pre-reconcile-password",
            includeReceiptImages = false,
            redacted = false,
            privacyMode = null
        )

        assertTrue("expected a privacy denial: ${detail(result)}", result.isFailure)
        assertTrue("the reconcile is after the privacy gate", trace.isEmpty())
        assertEquals("the unreacted delete is untouched", 1, orphanEntryLinkCount())
    }

    // ------------------------------------------------------------------ fail closed before the snapshot

    @Test
    fun NotReconciled_aborts_before_the_snapshot_with_no_file_and_the_mode_stays_NORMAL() = runTest {
        val incomplete = reconcilerReporting(InstallmentOpResult.Done(InstallmentReconcileReport(plansFailed = 1)))

        val handle = CapturingRunHandle()
        val result = newRepository(incomplete, runHandle = handle).createCostBackup(
            password = "pre-reconcile-password",
            includeReceiptImages = false,
            redacted = false,
            privacyMode = null
        )

        assertTrue("expected an abort: ${detail(result)}", result.isFailure)
        assertEquals(
            "Backup aborted: installment plans could not be reconciled (RECONCILE_INCOMPLETE)",
            result.exceptionOrNull()?.message
        )
        // C3: exactly one INSTALLMENT_PRE_RECONCILE run event, bounded enum name only.
        val preReconcileEvents = handle.events.filter { it.stage == "INSTALLMENT_PRE_RECONCILE" }
        assertEquals(1, preReconcileEvents.size)
        val event = preReconcileEvents.single()
        assertEquals(EventOutcome.FAILED_FINAL, event.outcome)
        assertEquals(DiagnosticReasonCode.VALIDATION_FAILED, event.reasonCode)
        val metadata = JSONObject(event.metadata.toJson())
        assertEquals("metadata carries only the reason key", 1, metadata.length())
        assertEquals("RECONCILE_INCOMPLETE", metadata.getString("reason"))
        assertEquals(listOf("VALIDATION_FAILED"), handle.failedFinalReasons.toList())
        assertEquals(0, handle.successCount.get())
        assertTrue("the maintenance mode is never entered or exited", trace.isEmpty())
        verify(exactly = 0) { mode.enter(any()) }
        verify(exactly = 0) { mode.exit(any()) }
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, currentMode)
        assertNoBackupArtifacts()
    }

    @Test
    fun a_non_Done_reconcile_and_a_reconcile_exception_also_abort_before_the_snapshot() = runTest {
        val notDone = reconcilerReporting(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT))
        val failing = InstallmentPreBackupReconciler(
            dagger.Lazy {
                mockk<InstallmentPlanLifecycleCoordinator> {
                    coEvery { reconcile() } throws IllegalStateException("secret-merchant-text-12345")
                }
            },
            dagger.Lazy { mockk<PostCommitActionRunner>(relaxed = true) }
        )

        for ((reconciler, reason) in listOf(notDone to "NOT_DONE", failing to "EXCEPTION")) {
            val result = newRepository(reconciler).createCostBackup(
                password = "pre-reconcile-password",
                includeReceiptImages = false,
                redacted = false,
                privacyMode = null
            )

            assertTrue("expected an abort ($reason): ${detail(result)}", result.isFailure)
            assertEquals(
                "Backup aborted: installment plans could not be reconciled ($reason)",
                result.exceptionOrNull()?.message
            )
            assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("secret-merchant-text-12345"))
        }
        assertTrue(trace.isEmpty())
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, currentMode)
        assertNoBackupArtifacts()
    }

    @Test
    fun a_non_NORMAL_mode_makes_the_real_reconcile_fail_closed_with_EXCEPTION_and_nothing_is_entered_or_written() =
        runTest {
            seedUnreactedDeleteOfAPaidEntry()
            assertEquals("precondition: the delete left an orphan link", 1, orphanEntryLinkCount())
            currentMode = RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED
            // The barrier used by the real coordinator reads this very mode.
            assertFalse("precondition: the barrier sees the non-NORMAL mode", barrier.writesAllowed())
            try {
                barrier.checkWritesAllowed("precondition")
                fail("the barrier must refuse writes in a non-NORMAL mode")
            } catch (expected: DatabaseAccessBlockedException) {
                // expected
            }

            val result = newRepository(realReconciler()).createCostBackup(
                password = "pre-reconcile-password",
                includeReceiptImages = false,
                redacted = false,
                privacyMode = null
            )

            assertTrue("expected an abort: ${detail(result)}", result.isFailure)
            assertTrue(
                detail(result),
                result.exceptionOrNull()?.message.orEmpty().endsWith("(EXCEPTION)")
            )
            verify(exactly = 0) { mode.enter(any()) }
            verify(exactly = 0) { mode.exit(any()) }
            assertEquals(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED, currentMode)
            assertNoBackupArtifacts()
            assertEquals("the orphan was not repaired", 1, orphanEntryLinkCount())
        }

    @Test
    fun cancellation_during_the_pre_reconcile_propagates_and_leaves_the_mode_NORMAL() = runTest {
        val cancelling = InstallmentPreBackupReconciler(
            dagger.Lazy {
                mockk<InstallmentPlanLifecycleCoordinator> {
                    coEvery { reconcile() } throws CancellationException("test cancellation")
                }
            },
            dagger.Lazy { mockk<PostCommitActionRunner>(relaxed = true) }
        )

        var propagated = false
        try {
            newRepository(cancelling).createCostBackup(
                password = "pre-reconcile-password",
                includeReceiptImages = false,
                redacted = false,
                privacyMode = null
            )
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            // Propagated, not converted into a Result.failure (the type is the contract; the
            // coroutine machinery may copy the instance, so the message is not compared).
            propagated = true
        }

        assertTrue(propagated)
        assertTrue("nothing was entered, so nothing is exited", trace.isEmpty())
        verify(exactly = 0) { mode.enter(any()) }
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, currentMode)
        assertNoBackupArtifacts()
    }

    // ------------------------------------------------------------------ fail closed kept

    @Test
    fun an_orphan_injected_after_the_reconcile_and_before_the_drain_still_fails_verify() = runTest {
        // A healthy plan: nothing for the reconcile to repair.
        val expenseId = seedExpense()
        seedPlan(uuid = "healthy-plan", entryLinks = listOf(expenseId to "PAID", null to "SCHEDULED"))
        // A write that slips in between the reconcile and the drain (owner item 17, option (a)).
        afterReconcileHook = {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE installment_entries SET linkedExpenseId = 987654 WHERE seq = 1"
            )
        }

        val result = newRepository(realReconciler()).createCostBackup(
            password = "pre-reconcile-password",
            includeReceiptImages = false,
            redacted = false,
            privacyMode = null
        )

        assertEquals("the reconcile ran before the injection", listOf("RECONCILE", "ENTER", "EXIT"), trace.toList())
        assertTrue("the verify stays fail-closed: ${detail(result)}", result.isFailure)
        assertTrue(
            detail(result),
            result.exceptionOrNull()?.message.orEmpty()
                .contains("Orphan installment_entries linked to non-existent expenses")
        )
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, currentMode)
        assertNoBackupArtifacts()
    }

    // ------------------------------------------------------------------ harness

    /** Real reconciler over the real coordinator; the plan DAO traces the reconcile's first read. */
    private fun realReconciler(): InstallmentPreBackupReconciler {
        val tracingDao = TracingPlanDao(db.installmentPlanDao()) { trace += "RECONCILE" }
        val facts = InstallmentExpenseFactsReaderImpl(db.expenseDao(), db.recurringOccurrenceDao(), db.categoryDao())
        val coordinator = InstallmentPlanLifecycleCoordinator(
            db,
            tracingDao,
            mockk<TransactionLifecycleCoordinator>(relaxed = true),
            facts,
            barrier,
            FakeTimeProvider.forDate(2027, 3, 12),
            InstallmentCandidateChargeProbeImpl(facts, tracingDao)
        )
        return InstallmentPreBackupReconciler(
            dagger.Lazy { coordinator },
            dagger.Lazy { mockk<PostCommitActionRunner>(relaxed = true) }
        )
    }

    /** Real reconciler over a coordinator mock that returns [result] from `reconcile()`. */
    private fun reconcilerReporting(result: InstallmentOpResult<InstallmentReconcileReport>) =
        InstallmentPreBackupReconciler(
            dagger.Lazy { mockk<InstallmentPlanLifecycleCoordinator> { coEvery { reconcile() } returns result } },
            dagger.Lazy { mockk<PostCommitActionRunner>(relaxed = true) }
        )

    private fun TestScope.newRepository(
        reconciler: InstallmentPreBackupReconciler,
        gate: PrivacyGate = allowAllGate(),
        runHandle: OperationRunHandle = NoOpOperationRunHandle
    ): DatabaseBackupRepositoryImpl {
        val recorder = mockk<OperationRunRecorder>()
        coEvery { recorder.start(any(), any(), any()) } returns runHandle
        val drain = NoOpWorkerDrainController()
        return DatabaseBackupRepositoryImpl(
            context = backupContext,
            database = db,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            privacyGate = gate,
            privacySettingsRepository = mockk(relaxed = true),
            backupEncryptionService = lowIterationBackupEncryptionService(),
            exportAnonymizer = mockk(relaxed = true),
            secureKeyStorage = mockk(relaxed = true),
            receiptAssetStore = mockk(relaxed = true),
            restoreMaintenanceMode = mode,
            restoreJournal = mockk(relaxed = true),
            workerDrain = drain,
            restoreDatabaseOpener = object : RestoreDatabaseOpener {
                override fun openFreshDatabase() = db
            },
            maintenanceOperationRunner = MaintenanceOperationRunner(mode, drain, barrier),
            snapshotCreator = SqliteSnapshotCreator(),
            restoreInternalWriteScope = RestoreInternalWriteScope(mode),
            operationRunRecorder = recorder,
            diagnosticSink = mockk(relaxed = true),
            timeProvider = FakeTimeProvider(1716163200000L),
            installmentPreBackupReconciler = dagger.Lazy { reconciler },
            databaseWriteBarrier = barrier
        )
    }

    private fun allowAllGate(): PrivacyGate {
        val gate = mockk<PrivacyGate>()
        coEvery { gate.check(any(), any()) } returns PrivacyDecision.Allowed
        return gate
    }

    /**
     * A plan whose first entry is PAID against an expense that is then deleted by a raw SQL DELETE
     * (the plan reaction did NOT run). Returns the plan id.
     */
    private suspend fun seedUnreactedDeleteOfAPaidEntry(): Long {
        val gone = seedExpense()
        val planId = seedPlan(uuid = "unreacted-delete", entryLinks = listOf(gone to "PAID", null to "SCHEDULED"))
        db.openHelper.writableDatabase.execSQL("DELETE FROM expenses WHERE id = $gone")
        return planId
    }

    /**
     * A PURCHASE_ATTACHED plan whose purchase expense is then deleted by a raw SQL DELETE (the plan
     * reaction did NOT run). Its entries carry no expense link, so only verifier check 3 (R5) sees it.
     */
    private suspend fun seedUnreactedDeleteOfAnAttachedPurchase(): Long {
        val purchase = seedExpense()
        val planId = seedPlan(
            uuid = "unreacted-purchase-delete",
            entryLinks = listOf(null to "SCHEDULED", null to "SCHEDULED"),
            kind = "PURCHASE_ATTACHED",
            purchaseExpenseId = purchase
        )
        db.openHelper.writableDatabase.execSQL("DELETE FROM expenses WHERE id = $purchase")
        return planId
    }

    /** Failure detail for assertion messages; the data is synthetic, so the trace is safe to print. */
    // Diagnostic: the exception message plus only app frames (Robolectric frames would hide the throwing site).
    private fun detail(result: Result<*>): String? = result.exceptionOrNull()?.let { e ->
        generateSequence(e) { it.cause }.joinToString(" | caused by ") { t ->
            t.javaClass.simpleName + ": " + t.message + " @ " +
                t.stackTrace.filter { "expensetracker" in it.className }.take(12).joinToString(" < ")
        }
    }

    private suspend fun seedExpense(): Long {
        if (categoryId == 0L) {
            categoryId = db.categoryDao().insert(Category(name = "Electronics", icon = "E", color = "#33FF57"))
        }
        val id = db.expenseDao().insert(
            Expense(
                amount = 50.0,
                currency = "EUR",
                merchant = "Synthetic Lender Charge",
                merchantKey = "synthetic lender charge",
                transactionType = TransactionType.PURCHASE,
                date = ZonedDateTime.of(2027, 4, 10, 12, 0, 0, 0, ZoneId.of("Europe/Athens")).toInstant().toEpochMilli(),
                categoryId = categoryId,
                paymentMethod = PaymentMethod.CARD
            )
        )
        assertTrue(id > 0L)
        return id
    }

    /** Standalone plan with one entry per [entryLinks] pair (linked expense id or null, status). */
    private suspend fun seedPlan(
        uuid: String,
        entryLinks: List<Pair<Long?, String>>,
        kind: String = "STANDALONE",
        purchaseExpenseId: Long? = null
    ): Long {
        val now = FakeTimeProvider.forDate(2027, 3, 12).now()
        val firstDue = LocalDate.of(2027, 4, 10).toEpochDay()
        val planId = db.installmentPlanDao().insertPlan(
            InstallmentPlan(
                planUuid = uuid,
                kind = kind,
                purchaseExpenseId = purchaseExpenseId,
                displayName = "Synthetic Plan",
                currency = "EUR",
                splitMethod = "FIXED_PAYMENT",
                fixedPaymentAmount = 50.0,
                installmentCount = entryLinks.size,
                frequency = "MONTHLY",
                firstDueDate = firstDue,
                anchorDayOfMonth = 10,
                residualPlacement = "FIRST",
                categoryId = categoryId,
                paymentMethod = PaymentMethod.CARD,
                fulfilmentMode = "CONFIRM",
                declaredOnDate = LocalDate.of(2027, 3, 12).toEpochDay(),
                status = "ACTIVE",
                createdAt = now,
                updatedAt = now
            )
        )
        db.installmentPlanDao().insertEntries(
            entryLinks.mapIndexed { index, (linkedExpenseId, status) ->
                InstallmentEntry(
                    planId = planId,
                    seq = index + 1,
                    kind = "REGULAR",
                    dueDate = firstDue + 30L * index,
                    scheduledAmount = 50.0,
                    status = status,
                    linkedExpenseId = linkedExpenseId,
                    linkOrigin = if (linkedExpenseId != null) "USER_LINKED" else null,
                    autoRecordConsumed = false,
                    recordGeneration = 0,
                    statusChangedAt = now
                )
            }
        )
        return planId
    }

    private fun orphanEntryLinkCount(): Int =
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM installment_entries WHERE linkedExpenseId IS NOT NULL " +
                "AND linkedExpenseId NOT IN (SELECT id FROM expenses)"
        ).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun orphanPurchasePlanCount(): Int =
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM installment_plans WHERE purchaseExpenseId IS NOT NULL " +
                "AND purchaseExpenseId NOT IN (SELECT id FROM expenses)"
        ).use {
            it.moveToFirst()
            it.getInt(0)
        }

    /** No bundle in the app-private backup folder and no leftover snapshot copy in the cache. */
    private fun assertNoBackupArtifacts() {
        val bundles = File(context.filesDir, "costbackups").listFiles().orEmpty()
        assertTrue("no bundle written: ${bundles.map { it.name }}", bundles.isEmpty())
        val snapshots = cacheTmp.listFiles { f -> f.name.startsWith("costbackup_snapshot_") }.orEmpty()
        assertTrue("no snapshot copy left: ${snapshots.map { it.name }}", snapshots.isEmpty())
    }

    /** One recorded run event; metadata is kept as its JSON form (SafeEventMetadata has no accessor). */
    private data class RecordedEvent(
        val stage: String,
        val outcome: EventOutcome,
        val reasonCode: DiagnosticReasonCode?,
        val metadata: SafeEventMetadata
    )

    /** Capturing run handle: records every event and every terminal call. */
    private class CapturingRunHandle : OperationRunHandle {
        override val runId: Long = -2L
        override val correlationId: String = "capturing"
        val events = CopyOnWriteArrayList<RecordedEvent>()
        val failedFinalReasons = CopyOnWriteArrayList<String>()
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun event(
            stage: String,
            outcome: EventOutcome,
            reasonCode: DiagnosticReasonCode?,
            severity: EventSeverity,
            metadata: SafeEventMetadata,
            entityType: String?,
            entityId: Long?,
            exception: Throwable?,
            isTerminal: Boolean
        ) {
            events += RecordedEvent(stage, outcome, reasonCode, metadata)
        }

        override suspend fun increment(processed: Int, succeeded: Int, failed: Int, skipped: Int, warnings: Int, errors: Int) = Unit
        override suspend fun success() {
            successCount.incrementAndGet()
        }
        override suspend fun partialSuccess(summary: String?) = Unit
        override suspend fun failedFinal(reason: String, error: Throwable?) {
            failedFinalReasons += reason
        }
        override suspend fun failedRetryable(reason: String, error: Throwable?) = Unit
        override suspend fun cancelled(reason: String?) = Unit
    }

    /** Real plan DAO that reports the reconcile's first read (`reconcile()` starts with getAllPlans). */
    private class TracingPlanDao(
        private val delegate: InstallmentPlanDao,
        private val onGetAllPlans: () -> Unit
    ) : InstallmentPlanDao by delegate {
        override suspend fun getAllPlans(): List<InstallmentPlan> {
            onGetAllPlans()
            return delegate.getAllPlans()
        }
    }
}
