package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.BackupVerifier
import com.yourname.expensetracker.data.backup.CostbackupBundle
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.MaintenanceOperationRunner
import com.yourname.expensetracker.data.backup.RestoreDatabaseOpener
import com.yourname.expensetracker.data.backup.RestoreInternalWriteScope
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.SqliteSnapshotCreator
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.privacy.ExportAnonymizer
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.currency.RateEligibilityEvaluator
import com.yourname.expensetracker.domain.diagnostics.NoOpOperationRunHandle
import com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.ATHENS
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.athensMillis
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentCoordinatorFixture.Companion.day
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentOpResult
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanLifecycleCoordinator
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPlanTerms
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPostCommitDispatcher
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentPreBackupReconciler
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentReconcileReport
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentResidualPlacement
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
import com.yourname.expensetracker.domain.transaction.validation.DefaultTransactionDatePolicy
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.workers.NoOpWorkerDrainController
import com.yourname.expensetracker.testfixtures.backup.lowIterationBackupEncryptionService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Loan-installments S6c-5 (AE-12 restore half, decision D-18 option (b)): a database that holds
 * installment plans and entries survives a backup and a restore with the plan stack intact, and
 * the plan reconcile finds NOTHING to repair on the restored database.
 *
 * ## Fixture: bundle round trip, not `restoreCostBackup`
 * A full JVM `restoreCostBackup` is impractical here: it swaps the live database file, writes a
 * restore journal, creates a safety backup, needs a process restart and always decrypts with a
 * production-count PBKDF2 instance (see `lowIterationBackupEncryptionService`). The test therefore
 * covers every data-bearing step of that path with the real classes:
 *
 * 1. the plans, entries and expenses are created through the real plan coordinator and the real
 *    transaction lifecycle coordinator (file-backed Room database);
 * 2. `createCostBackup` runs the export pipeline (the privacy gate and the post-commit runner are
 *    test doubles; S6c-4 pre-reconcile,
 *    maintenance runner, snapshot creator, [ExportAnonymizer] when redacted, `BackupVerifier`,
 *    bundle writer);
 * 3. [CostbackupBundle.extract] decrypts the bundle, [BackupVerifier.verifyQuick] checks the staged
 *    file as restore step 6 does, and the staged file is opened as a FRESH Room database (restore
 *    step 6a), which stands in for the restored live database.
 *
 * What is NOT covered (by design): the file swap, the journal, the safety backup, the post-swap
 * full `BackupVerifier.verify` and the process restart. D-18 option (b) adds no restore hook, so
 * there is no restore-side plan step to exercise: the plan reconcile runs at the next
 * `recordDueEntries` (S7a binding note), and this test proves that running it on restored data is
 * a no-op for a quiescent snapshot.
 *
 * ## Redacted export (observation for S7a)
 * `ExportAnonymizer.sanitizeLoanPlanLabels` blanks `installment_plans.displayName` to the empty
 * string and nulls `lenderLabel`. A restored redacted plan therefore has a BLANK display name. The
 * plan coordinator's due-entry recorder uses that display name as the expense merchant, and
 * `TransactionValidator` rejects a blank merchant (MERCHANT_BLANK). The test pins the CURRENT
 * behaviour at the data and validator level (the recorder itself is not run: the TLC here is built
 * with a mocked validator). S6c-5 does not change it; S7a must handle a blank name on the
 * `recordDueEntries` path before auto-recording a restored redacted plan.
 *
 * Synthetic data only.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentRestoreReconcileTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var mode: RestoreMaintenanceMode
    private lateinit var barrier: DatabaseWriteBarrier
    private lateinit var coordinator: InstallmentPlanLifecycleCoordinator
    private lateinit var cacheTmp: File
    private lateinit var backupContext: Context

    private var currentMode = RestoreMaintenanceMode.Mode.NORMAL
    private var categoryId = 0L
    private var restoredDb: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Rollback-journal mode and a plain temp cache dir mirror DatabaseBackupRepositoryPreReconcileTest:
        // Robolectric's legacy SQLite cannot open the WAL file DB through the snapshot pipeline, and its
        // default cache dir cannot hold the snapshot copy.
        db = AppDatabase.fileBuilder(context)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        db.openHelper.writableDatabase
        cacheTmp = createTempDir(prefix = "restore-reconcile-cache-")
        backupContext = object : android.content.ContextWrapper(context) {
            override fun getCacheDir(): File = cacheTmp
        }

        mode = mockk(relaxed = true)
        every { mode.currentMode() } answers { currentMode }
        every { mode.isWritesAllowed() } answers { currentMode == RestoreMaintenanceMode.Mode.NORMAL }
        every { mode.enter(any()) } answers { currentMode = firstArg() }
        every { mode.exit(any()) } answers { currentMode = RestoreMaintenanceMode.Mode.NORMAL }
        barrier = DatabaseWriteBarrier(mode)

        coordinator = planCoordinatorOn(db, realLifecycle = true)
    }

    @After
    fun tearDown() {
        runCatching { restoredDb?.close() }
        runCatching { db.close() }
        context.deleteDatabase(AppDatabase.DATABASE_NAME)
        context.deleteDatabase(RESTORED_DB_NAME)
        File(context.filesDir, "costbackups").deleteRecursively()
        if (::cacheTmp.isInitialized) cacheTmp.deleteRecursively()
    }

    // ------------------------------------------------------------------ non-redacted

    @Test
    fun a_restored_non_redacted_backup_keeps_every_count_and_month_total_and_the_reconcile_changes_zero_rows() =
        runTest {
            seedScenario()
            val trip = backupAndOpenRestored(redacted = false)
            val restored = trip.restored

            // The backup really holds plans, entries and flagged / installment expenses.
            assertEquals(2, trip.livePlans.size)
            assertTrue(trip.livePlans.all { it.entries.isNotEmpty() })
            assertTrue(trip.livePlans.any { it.entries.any { e -> e.status == "PAID" } })
            assertEquals(false, trip.manifest.options.redacted)

            assertRestoredMatchesLive(trip, redacted = false)

            // Plan labels survive a non-redacted export.
            assertEquals(trip.livePlans, planRows(restored))
            assertTrue(planRows(restored).all { it.displayName.isNotBlank() })

            assertReconcileIsANoOp(trip)
        }

    // ------------------------------------------------------------------ redacted

    @Test
    fun a_restored_redacted_backup_keeps_counts_and_totals_blanks_the_plan_labels_and_the_reconcile_changes_zero_rows() =
        runTest {
            seedScenario()
            val trip = backupAndOpenRestored(redacted = true)
            val restored = trip.restored

            assertEquals(true, trip.manifest.options.redacted)
            // The live database keeps its labels: the anonymiser works on the snapshot copy only.
            assertTrue(trip.livePlans.all { it.displayName.isNotBlank() })

            assertRestoredMatchesLive(trip, redacted = true)

            // CURRENT BEHAVIOUR (observation for S7a): the redacted export blanks the plan labels,
            // the rows and every other plan / entry field survive.
            val restoredPlans = planRows(restored)
            assertTrue(
                "every restored plan has a blank display name: ${restoredPlans.map { it.displayName }}",
                restoredPlans.all { it.displayName == "" }
            )
            assertTrue(restoredPlans.all { it.lenderLabel == null })
            assertEquals(
                trip.livePlans.map { it.copy(displayName = "", lenderLabel = null) },
                restoredPlans
            )
            // The expense rows are NOT redacted: the installment expenses keep their merchant text.
            assertEquals(trip.liveExpenses, expenseRows(restored))

            assertReconcileIsANoOp(trip)

            // The due-entry recorder uses the plan display name as the expense merchant
            // (InstallmentPlanLifecycleCoordinator.recordEntryExpense). With the blank restored name,
            // the real validator rejects that merchant. S7a must handle it on the recordDueEntries path.
            val plan = restoredPlans.first { it.entries.any { e -> e.status == "SCHEDULED" } }
            val entry = plan.entries.first { it.status == "SCHEDULED" }
            val now = 1_716_163_200_000L
            val validator = TransactionValidator(FakeTimeProvider(now), DefaultTransactionDatePolicy())
            val codes = validator.validateCreate(
                CreateExpenseRequest(
                    merchant = plan.displayName,
                    amount = entry.scheduledAmount,
                    currency = plan.currency,
                    date = now - 86_400_000L,
                    transactionType = TransactionType.PURCHASE,
                    source = ExpenseSource.INSTALLMENT_PLAN
                )
            ).map { it.code }
            assertTrue("a blank plan name yields a blank merchant: $codes", "MERCHANT_BLANK" in codes)
        }

    // ------------------------------------------------------------------ shared assertions

    /** Counts, manifest, expense rows, plan rows and per-month totals of the restored database. */
    private suspend fun assertRestoredMatchesLive(trip: RoundTrip, redacted: Boolean) {
        val restored = trip.restored

        // Table counts: equal to the live database at backup time, and to the manifest.
        val restoredCounts = tableCounts(restored)
        assertEquals(trip.liveCounts, restoredCounts)
        for ((table, expected) in trip.manifest.tableCounts) {
            val actual = restoredCounts[table] ?: continue
            assertEquals("manifest count of $table", expected, actual)
        }
        assertTrue("installment_plans is populated", (restoredCounts["installment_plans"] ?: 0) == 2)
        assertTrue("installment_entries is populated", (restoredCounts["installment_entries"] ?: 0) > 0)

        // Expense rows (including the purchase flag) are identical.
        assertEquals(trip.liveExpenses, expenseRows(restored))

        // Plan and entry rows are identical (labels aside for the redacted export).
        val restoredPlans = planRows(restored)
        if (!redacted) {
            assertEquals(trip.livePlans, restoredPlans)
        } else {
            assertEquals(trip.livePlans.map { it.copy(displayName = "", lenderLabel = null) }, restoredPlans)
        }

        // Per-month spending totals (canonical spending predicate) are identical.
        assertEquals(trip.liveMonths, monthlyTotals(restored))
    }

    /** `reconcile()` on the restored database reports 0 for every counter and changes no row. */
    private suspend fun assertReconcileIsANoOp(trip: RoundTrip) {
        val restored = trip.restored
        val before = fingerprint(restored)

        // A relaxed TLC: a reconcile that tried to repair anything would show up in the report.
        val reconciler = planCoordinatorOn(restored, realLifecycle = false)
        val result = reconciler.reconcile()

        val done = result as InstallmentOpResult.Done
        assertEquals("every reconcile counter is 0", InstallmentReconcileReport(), done.value)
        assertTrue("no post-commit batch is planned", done.postCommit.isEmpty())

        val after = fingerprint(restored)
        assertEquals("reconcile changed no row", before, after)
        assertEquals(trip.liveCounts, after.counts)
        assertEquals("per-month totals are equal after the reconcile", trip.liveMonths, after.months)
        assertEquals(trip.liveExpenses, after.expenses)
    }

    // ------------------------------------------------------------------ round trip

    private class RoundTrip(
        val manifest: CostbackupBundle.BackupManifest,
        val restored: AppDatabase,
        val liveCounts: Map<String, Int>,
        val liveMonths: Map<String, Map<String, Double>>,
        val liveExpenses: List<String>,
        val livePlans: List<PlanRow>
    )

    /**
     * Creates a real backup of the live database, extracts it and opens the staged database file as a
     * fresh Room database (the restored database).
     */
    private suspend fun TestScope.backupAndOpenRestored(redacted: Boolean): RoundTrip {
        val result = newRepository().createCostBackup(
            password = PASSWORD,
            includeReceiptImages = false,
            redacted = redacted,
            privacyMode = null
        )
        assertTrue("backup must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val bundle = result.getOrThrow()
        assertTrue(bundle.exists())
        assertEquals(RestoreMaintenanceMode.Mode.NORMAL, currentMode)

        // The live state AFTER the backup (the pre-reconcile ran inside it) is what the snapshot holds.
        val liveCounts = tableCounts(db)
        val liveMonths = monthlyTotals(db)
        val liveExpenses = expenseRows(db)
        val livePlans = planRows(db)
        // Preconditions that make the month comparison meaningful: the purchase is excluded,
        // the installment expenses count.
        assertEquals(mapOf("EUR" to 25.0), liveMonths["2027-03"])
        assertEquals(mapOf("EUR" to 100.0), liveMonths["2027-04"])
        assertEquals(mapOf("EUR" to 100.0), liveMonths["2027-05"])

        val extractDir = File(cacheTmp, "extract-$redacted")
        val extracted = CostbackupBundle.extract(
            bundleFile = bundle,
            outputDir = extractDir,
            password = PASSWORD,
            nowEpochMs = NOW_MS,
            encryptionService = lowIterationBackupEncryptionService()
        ).getOrThrow()
        val manifest = extracted.manifest

        // Restore steps 5 and 6: stage the file and quick-verify it with the manifest counts.
        val stagedFile = context.getDatabasePath(RESTORED_DB_NAME)
        stagedFile.parentFile?.mkdirs()
        extracted.dbFile.copyTo(stagedFile, overwrite = true)
        BackupVerifier.verifyQuick(
            stagedFile,
            BackupVerifier.expectedCountsAfterMigration(manifest.tableCounts, manifest.databaseVersion)
        )

        // Restore step 6a: open the staged file with Room (journal mode TRUNCATE for the JVM harness).
        val restored = AppDatabase.fileBuilder(context, RESTORED_DB_NAME)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        restored.openHelper.writableDatabase
        restoredDb = restored

        return RoundTrip(manifest, restored, liveCounts, liveMonths, liveExpenses, livePlans)
    }

    // ------------------------------------------------------------------ harness

    /** A plan coordinator over [database]. The real TLC is needed to seed; a reconcile uses a relaxed one. */
    private fun planCoordinatorOn(database: AppDatabase, realLifecycle: Boolean): InstallmentPlanLifecycleCoordinator {
        val time = FakeTimeProvider.forDate(2027, 3, 12)
        val lifecycle = if (realLifecycle) {
            val currencySettings = mockk<CurrencySettingsRepository>(relaxed = true)
            coEvery { currencySettings.resolveHomeCurrency() } returns
                HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
            TransactionLifecycleCoordinator(
                database = database,
                expenseDao = database.expenseDao(),
                transactionEventDao = database.transactionEventDao(),
                timeProvider = time,
                currencyConverter = mockk(relaxed = true),
                sideEffectDispatcher = mockk(relaxed = true),
                planner = mockk<TransactionSideEffectPlanner>(relaxed = true),
                runner = mockk(relaxed = true),
                recurringLifecycleCoordinator = mockk(relaxed = true),
                writeBarrier = barrier,
                currencySettingsRepository = currencySettings,
                sourceLinkWriter = mockk(relaxed = true),
                transactionValidator = mockk(relaxed = true),
                diagnosticEventWriter = mockk(relaxed = true),
                rateEligibilityEvaluator = mockk<RateEligibilityEvaluator>()
            )
        } else {
            mockk<TransactionLifecycleCoordinator>(relaxed = true)
        }
        val facts = InstallmentExpenseFactsReaderImpl(
            database.expenseDao(),
            database.recurringOccurrenceDao(),
            database.categoryDao()
        )
        return InstallmentPlanLifecycleCoordinator(
            database,
            database.installmentPlanDao(),
            lifecycle,
            facts,
            barrier,
            time,
            InstallmentCandidateChargeProbeImpl(facts, database.installmentPlanDao())
        )
    }

    private fun TestScope.newRepository(): DatabaseBackupRepositoryImpl {
        val recorder = mockk<OperationRunRecorder>()
        coEvery { recorder.start(any(), any(), any()) } returns NoOpOperationRunHandle
        val gate = mockk<PrivacyGate>()
        coEvery { gate.check(any(), any()) } returns PrivacyDecision.Allowed
        val drain = NoOpWorkerDrainController()
        // The REAL pre-backup reconciler over the real plan coordinator (S6c-4).
        val reconciler = InstallmentPreBackupReconciler(
            dagger.Lazy { coordinator },
            dagger.Lazy { InstallmentPostCommitDispatcher(mockk<PostCommitActionRunner>(relaxed = true)) }
        )
        return DatabaseBackupRepositoryImpl(
            context = backupContext,
            database = db,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            privacyGate = gate,
            privacySettingsRepository = mockk(relaxed = true),
            backupEncryptionService = lowIterationBackupEncryptionService(),
            exportAnonymizer = ExportAnonymizer(),
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
            timeProvider = FakeTimeProvider(NOW_MS),
            installmentPreBackupReconciler = dagger.Lazy { reconciler },
            databaseWriteBarrier = barrier
        )
    }

    // ------------------------------------------------------------------ seeding

    /**
     * Plan A: a purchase-attached plan (600 EUR, 12 x 50.00) with entries 1 and 2 confirmed.
     * Plan B: a standalone plan (6 x 50.00) with entry 1 linked to an existing charge and entry 2 confirmed.
     * Plus one ordinary expense. Spending: March 25.00 (the flagged purchase counts 0), April 100.00,
     * May 100.00.
     */
    private suspend fun seedScenario() {
        categoryId = db.categoryDao().insert(Category(name = "Electronics", icon = "E", color = "#33FF57"))
        insertExpense(25.0, "Synthetic Grocer", athensMillis(2027, 3, 5))

        val purchase = insertExpense(600.0, "Synthetic Electronics", athensMillis(2027, 3, 10, 14, 32))
        val declared = coordinator.declareOnExpense(
            purchase,
            terms("restore-plan-a", "Synthetic Electronics Plan", "Synthetic Lender", InstallmentSplitMethod.EQUAL_SPLIT, 600.0, null, 12),
            ATHENS
        )
        val planA = (declared as InstallmentOpResult.Done).value.planId

        val created = coordinator.createStandalone(
            terms("restore-plan-b", "Synthetic Loan", null, InstallmentSplitMethod.FIXED_PAYMENT, null, 50.0, 6),
            categoryId,
            PaymentMethod.CARD
        )
        val planB = (created as InstallmentOpResult.Done).value.planId

        val today = day(2027, 5, 12)
        val entriesA = db.installmentPlanDao().getEntriesForPlan(planA).sortedBy { it.seq }
        val entriesB = db.installmentPlanDao().getEntriesForPlan(planB).sortedBy { it.seq }
        assertDone(coordinator.confirmEntry(entriesA[0].id, today, ATHENS))
        assertDone(coordinator.confirmEntry(entriesA[1].id, today, ATHENS))
        val charge = insertExpense(50.0, "Synthetic Lender Charge", athensMillis(2027, 4, 10))
        assertDone(coordinator.linkExpense(entriesB[0].id, charge))
        assertDone(coordinator.confirmEntry(entriesB[1].id, today, ATHENS))
    }

    private fun assertDone(result: InstallmentOpResult<*>) {
        assertTrue("expected Done but was $result", result is InstallmentOpResult.Done)
    }

    private fun terms(
        uuid: String,
        name: String,
        lender: String?,
        split: InstallmentSplitMethod,
        total: Double?,
        fixed: Double?,
        count: Int
    ) = InstallmentPlanTerms(
        planUuid = uuid,
        displayName = name,
        lenderLabel = lender,
        currency = "EUR",
        splitMethod = split,
        declaredTotal = total,
        fixedPaymentAmount = fixed,
        installmentCount = count,
        firstDueEpochDay = day(2027, 4, 10),
        residualPlacement = InstallmentResidualPlacement.FIRST,
        fulfilmentMode = InstallmentFulfilmentMode.CONFIRM,
        paidBeforeTrackingCount = 0,
        declaredOnEpochDay = day(2027, 3, 12)
    )

    private suspend fun insertExpense(amount: Double, merchant: String, dateMillis: Long): Long {
        val id = db.expenseDao().insert(
            Expense(
                amount = amount,
                currency = "EUR",
                merchant = merchant,
                merchantKey = merchant.lowercase(),
                transactionType = TransactionType.PURCHASE,
                date = dateMillis,
                categoryId = categoryId,
                paymentMethod = PaymentMethod.CARD
            )
        )
        assertTrue(id > 0L)
        return id
    }

    // ------------------------------------------------------------------ readers

    private data class EntryRow(
        val seq: Int,
        val status: String,
        val linkedExpenseId: Long?,
        val recordGeneration: Int,
        val autoRecordConsumed: Boolean,
        val scheduledAmount: Double
    )

    private data class PlanRow(
        val planUuid: String,
        val kind: String,
        val status: String,
        val purchaseExpenseId: Long?,
        val currency: String,
        val displayName: String,
        val lenderLabel: String?,
        val entries: List<EntryRow>
    )

    private data class StateFingerprint(
        val counts: Map<String, Int>,
        val months: Map<String, Map<String, Double>>,
        val expenses: List<String>,
        val plans: List<PlanRow>
    )

    private suspend fun fingerprint(database: AppDatabase): StateFingerprint {
        val counts = tableCounts(database)
        val months = monthlyTotals(database)
        val expenses = expenseRows(database)
        val plans = planRows(database)
        return StateFingerprint(counts, months, expenses, plans)
    }

    /** Row count of every backup-verified table (-1: the table is absent). */
    private fun tableCounts(database: AppDatabase): Map<String, Int> {
        val sqlite = database.openHelper.readableDatabase
        val counts = LinkedHashMap<String, Int>()
        for (table in BackupVerifier.allTableNames().sorted()) {
            counts[table] = runCatching {
                sqlite.query("SELECT COUNT(*) FROM \"$table\"").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
            }.getOrDefault(-1)
        }
        return counts
    }

    /** Canonical spending (S4a predicate) per calendar month of 2027-03 .. 2027-08, by currency. */
    private suspend fun monthlyTotals(database: AppDatabase): Map<String, Map<String, Double>> {
        val out = LinkedHashMap<String, Map<String, Double>>()
        for (month in 3..8) {
            val start = LocalDate.of(2027, month, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = LocalDate.of(2027, month, 1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val totals = database.expenseDao().getTotalSpentBetweenByCurrency(start, end)
            out["2027-%02d".format(month)] = totals.associate { it.currency to it.total }
        }
        return out
    }

    private suspend fun expenseRows(database: AppDatabase): List<String> {
        val ids = ArrayList<Long>()
        database.openHelper.readableDatabase.query("SELECT id FROM expenses ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        val rows = ArrayList<String>()
        for (id in ids) {
            val expense = database.expenseDao().getById(id)
            assertNotNull("expense $id", expense)
            val e = expense!!
            rows += "${e.id}|${e.merchant}|${e.amount}|${e.currency}|${e.date}|${e.transactionType}|${e.spendingExclusionReason}"
        }
        return rows
    }

    private suspend fun planRows(database: AppDatabase): List<PlanRow> {
        val dao = database.installmentPlanDao()
        val plans = dao.getAllPlans().sortedBy { it.id }
        val rows = ArrayList<PlanRow>()
        for (plan in plans) {
            val stored = dao.getEntriesForPlan(plan.id)
            val entries = stored.sortedBy { it.seq }.map {
                EntryRow(it.seq, it.status, it.linkedExpenseId, it.recordGeneration, it.autoRecordConsumed, it.scheduledAmount)
            }
            rows += PlanRow(
                plan.planUuid,
                plan.kind,
                plan.status,
                plan.purchaseExpenseId,
                plan.currency,
                plan.displayName,
                plan.lenderLabel,
                entries
            )
        }
        return rows
    }

    private companion object {
        const val PASSWORD = "restore-reconcile-password"
        const val NOW_MS = 1_716_163_200_000L
        const val RESTORED_DB_NAME = "restored_snapshot_db"
    }
}
