package com.yourname.expensetracker.domain.receipt.lifecycle

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.repository.ReceiptInsertResolver
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawPersistencePolicyResolver
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RG12-01 fail-first guard (R0-3, batch R2): the receipt TEXT fingerprint strips every amount and
 * date, so two different purchases from the same merchant template collide on it. The coordinator
 * must not treat such a collision as a duplicate unless something else (image hash, or equal known
 * semantic evidence) confirms it, and the second image must survive.
 *
 * Harness: real in-memory Room, real [RoomDomainTransactionRunner], real [ReceiptInsertResolver],
 * real [ReceiptRepository] and [ReceiptLifecycleCoordinator], REAL [ReceiptDuplicateDetector].
 * Controlled inputs only: OCR text/saved path, parser result, per-URI and per-file hashes.
 * [AssetCleanupCoordinator] is a spy over the real class so every cleanup reason is recorded and the
 * mocked asset store records deletes; no file system is touched.
 *
 * Cases run on the entity-built schema only. The MIGRATION_125_126 / 137_138 unique fingerprint indexes
 * are not declared by the entity and cannot survive Room schema validation, so no valid database carries
 * them (NEW-R2-B, release verification ledger); an upgraded-index variant would test an unreachable state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptDuplicateFingerprintCollisionTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: ReceiptRepository
    private lateinit var coordinator: ReceiptLifecycleCoordinator
    private lateinit var detector: ReceiptDuplicateDetector
    private lateinit var assets: ReceiptAssetStore
    private lateinit var cleanup: AssetCleanupCoordinator
    private var variant = "plain"

    private val ocr = mockk<ReceiptOcrService>()
    private val parser = mockk<ReceiptParser>()
    private val inputValidator = mockk<ReceiptInputValidator>()
    private val planner = mockk<ReceiptSideEffectPlanner>()
    private val postCommit = mockk<PostCommitActionRunner>(relaxed = true)
    private val cleanups = mutableListOf<Pair<String?, String>>()

    private val now = 1_700_000_000_000L

    // A fixed receipt date, distinct from "now"; any non-null value yields a semantic fingerprint.
    private val receiptDate = 1_699_900_000_000L

    /** One controlled scan: its own URI, OCR text, saved image path, hashes and parsed fields. */
    private data class Scan(
        val uri: Uri,
        val text: String,
        val path: String,
        val uriHash: String,
        val fileHash: String,
        val total: Double,
        val date: Long?
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabaseTestFactory.create(context)
        val clock = mockk<TimeProvider>()
        every { clock.now() } returns now
        // R27 S2b: real barrier so the coordinator's runWrite scopes actually execute their blocks.
        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        val barrier = DatabaseWriteBarrier(maintenanceMode)
        val transactions = RoomDomainTransactionRunner(db, clock)
        val insertResolver = ReceiptInsertResolver(db.scannedReceiptDao(), barrier)
        val events = RoomReceiptLifecycleEventWriter(db.receiptEventDao(), EventMetadataSanitizer(), clock, barrier)

        val currency = mockk<CurrencySettingsRepository>()
        every { currency.homeCurrency() } returns flowOf("EUR")
        coEvery { currency.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val privacy = mockk<PrivacySettingsRepository>()
        val settings = PrivacySettings()
        coEvery { privacy.getSettings() } returns settings
        coEvery { privacy.getLoadState() } returns PrivacySettingsLoadState.Loaded(settings)

        assets = mockk()
        every { assets.deleteAsset(any()) } returns true
        coEvery { inputValidator.validate(any()) } returns ReceiptInputValidator.ValidationResult(
            isValid = true, errors = emptyList(), mimeType = "image/jpeg", fileSizeBytes = 100L
        )

        // Normalizer returns a fixed canonical name so the merchant is identical across scans.
        val canonical = mockk<MerchantCanonical>()
        every { canonical.normalizedName } returns MERCHANT
        val lookup = mockk<MerchantLookupResult>()
        every { lookup.canonical } returns canonical
        val normalizer = mockk<MerchantNormalizer>()
        coEvery { normalizer.normalize(any(), any(), any()) } returns lookup

        detector = ReceiptDuplicateDetector(db.scannedReceiptDao(), db.receiptExpenseLinkDao())
        every { planner.planAfterReceiptSaved(any(), any(), any()) } returns
            PostCommitActionBatch.empty("rg12-01-test")

        repository = ReceiptRepository(
            database = db,
            scannedReceiptDao = db.scannedReceiptDao(),
            expenseDao = db.expenseDao(),
            pendingReviewDao = db.pendingReviewDao(),
            ocrService = ocr,
            receiptParser = parser,
            statementParser = mockk(relaxed = true),
            categorizationEngine = mockk(relaxed = true),
            merchantNormalizer = normalizer,
            hybridClassifier = mockk(relaxed = true),
            crossSourceDeduplication = mockk(relaxed = true),
            debugIssueDetector = mockk(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined,
            timeProvider = clock,
            coordinator = mockk(relaxed = true),
            receiptLinkService = mockk(relaxed = true),
            assetStore = assets,
            currencySettingsRepository = currency,
            receiptLifecycleCoordinator = Lazy { coordinator },
            writeBarrier = barrier,
            privacySettingsRepository = privacy,
            receiptEventDao = db.receiptEventDao(),
            receiptInsertResolver = insertResolver,
            pendingReviewSourceLinkService = mockk(relaxed = true)
        )

        cleanup = spyk(AssetCleanupCoordinator(db.scannedReceiptDao(), assets, barrier, transactions))
        coEvery { cleanup.cleanupUncommittedAsset(any(), any(), any()) } coAnswers {
            // Tolerant capture: a non-null reified cast of the spied suspend call's arg threw NPE (harness only).
            cleanups += Pair(args.getOrNull(0) as? String, (args.getOrNull(2) as? String) ?: "<unrecorded>")
            callOriginal()
        }

        coordinator = ReceiptLifecycleCoordinator(
            database = db,
            transactionRunner = transactions,
            receiptLifecycleEventWriter = events,
            receiptRepository = repository,
            receiptLinkService = mockk(relaxed = true),
            assetStore = assets,
            inputValidator = inputValidator,
            scannedReceiptDao = db.scannedReceiptDao(),
            receiptExpenseLinkDao = db.receiptExpenseLinkDao(),
            receiptEventDao = db.receiptEventDao(),
            emailReceiptDao = db.emailReceiptDao(),
            pendingReviewDao = db.pendingReviewDao(),
            pendingReviewSourceLinkService = mockk(relaxed = true),
            timeProvider = clock,
            bankStatementLifecycleProcessor = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            duplicateDetector = detector,
            currencySettingsRepository = currency,
            writeBarrier = barrier,
            transactionLifecycleCoordinator = mockk(relaxed = true),
            postCommitActionRunner = postCommit,
            assetCleanupCoordinator = cleanup,
            merchantNormalizer = mockk(relaxed = true),
            hybridClassifier = mockk(relaxed = true),
            rawPersistencePolicyResolver = RawPersistencePolicyResolver(privacy),
            diagnosticEventWriter = mockk(relaxed = true),
            sourceLinkWriter = mockk(relaxed = true),
            receiptSideEffectPlanner = planner,
            receiptInsertResolver = insertResolver,
            receiptParser = parser
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ---------------------------------------------------------------- harness helpers

    private fun scan(
        n: Int,
        total: Double,
        date: Long?,
        uriHash: String = "rg12-01-image-$n",
        fileHash: String = uriHash
    ): Scan {
        val totalText = String.format(Locale.ROOT, "%.2f", total)
        // Same merchant template for every scan; only the total digits differ between totals.
        val text = "ACME FUEL\nUNLEADED PUMP LINE\nTOTAL EUR $totalText\nTHANK YOU"
        return Scan(
            uri = Uri.parse("content://test/rg12-01-scan-$n.jpg"),
            text = text,
            path = "/test/rg12-01-scan-$n.jpg",
            uriHash = uriHash,
            fileHash = fileHash,
            total = total,
            date = date
        )
    }

    /** Registers the controlled inputs of [scan] (latest stub wins) and runs the real pipeline. */
    private suspend fun process(scan: Scan): ReceiptLifecycleCoordinator.ReceiptProcessOutcome {
        coEvery { assets.computeUriHash(scan.uri) } returns Result.success(scan.uriHash)
        coEvery { assets.computeFileHash(scan.path) } returns Result.success(scan.fileHash)
        coEvery { ocr.processUriWithMime(scan.uri, any()) } returns OcrResult(
            fullText = scan.text,
            blocks = emptyList(),
            savedImagePath = scan.path,
            pagesProcessed = null,
            totalPages = null,
            failedPages = null
        )
        coEvery { parser.parse(scan.text, homeCurrency = any()) } returns ReceiptParser.ParsedReceipt(
            merchantName = MERCHANT,
            total = ReceiptParser.sourceMoneyOrNull(scan.total, "EUR"),
            subtotal = null,
            tax = null,
            date = scan.date,
            currency = "EUR",
            lineItems = emptyList(),
            confidence = 0.9f
        )
        return coordinator.processReceiptInput(
            scan.uri, ReceiptLifecycleCoordinator.ReceiptProcessingOptions(autoMatchExistingExpense = false)
        ).getOrThrow()
    }

    private fun cleanupReasonsFor(path: String): String =
        cleanups.filter { it.first == path }.joinToString(",") { it.second }.ifEmpty { "none" }

    private suspend fun state(
        label: String,
        second: ReceiptLifecycleCoordinator.ReceiptProcessOutcome,
        secondPath: String
    ): String {
        val rows = db.scannedReceiptDao().getCount()
        return "RG12-01 $label: variant=$variant rows=$rows inserted=${second.inserted} " +
            "cleanup=${cleanupReasonsFor(secondPath)}"
    }

    private suspend fun assertPreconditionFingerprints(
        first: Scan,
        second: Scan,
        firstId: Long,
        expectSemanticOnFirst: Boolean
    ) {
        val stored = db.scannedReceiptDao().getById(firstId)
        assertNotNull("RG12-01 PRECONDITION: first row missing", stored)
        assertNotNull("RG12-01 PRECONDITION: first row textFingerprint null", stored!!.textFingerprint)
        if (expectSemanticOnFirst) {
            assertNotNull("RG12-01 PRECONDITION: first row semanticFingerprint null", stored.semanticFingerprint)
        } else {
            assertNull("RG12-01 PRECONDITION: first row semanticFingerprint set", stored.semanticFingerprint)
        }
        assertEquals(
            "RG12-01 PRECONDITION: texts must collide on the production text fingerprint",
            detector.computeTextFingerprintPublic(first.text),
            detector.computeTextFingerprintPublic(second.text)
        )
        assertEquals(
            "RG12-01 PRECONDITION: stored first fingerprint is the production fingerprint of its text",
            detector.computeTextFingerprintPublic(first.text),
            stored.textFingerprint
        )
    }

    /** Different purchase: second scan must be a new row, with its own image intact. */
    private suspend fun assertSecondIsNewPurchase(
        label: String,
        first: ReceiptLifecycleCoordinator.ReceiptProcessOutcome,
        second: ReceiptLifecycleCoordinator.ReceiptProcessOutcome,
        secondScan: Scan
    ) {
        val message = state(label, second, secondScan.path)
        assertEquals(message, 2L, db.scannedReceiptDao().getCount().toLong())
        assertTrue(message, second.inserted)
        assertNotEquals(message, first.savedReceipt.id, second.savedReceipt.id)
        val reasons = cleanupReasonsFor(secondScan.path)
        assertFalse(message, reasons.contains("DUPLICATE"))
        verify(exactly = 0) { assets.deleteAsset(secondScan.path) }
    }

    private fun runVariant(body: suspend () -> Unit) {
        runBlocking { body() }
    }

    // ---------------------------------------------------------------- cases

    // T1. Oracle: purchase A = 12.50 EUR, purchase B = 13.75 EUR, same merchant lines, parsedDate
    // null on both, different image bytes. They are different purchases, so 2 rows, B inserted,
    // B's image kept, no duplicate cleanup. HEAD: text fingerprints are equal (digits stripped) and
    // both semantic fingerprints are null, so B is "duplicate" and B's image is deleted.
    private suspend fun caseT1DifferentTotalsNullDate() {
        val a = scan(1, 12.50, date = null)
        val b = scan(2, 13.75, date = null)
        val first = process(a)
        assertTrue("RG12-01 PRECONDITION: first scan must insert", first.inserted)
        assertPreconditionFingerprints(a, b, first.savedReceipt.id, expectSemanticOnFirst = false)
        val second = process(b)
        assertSecondIsNewPurchase("COLLISION T1", first, second, b)
    }

    @Test
    fun t1_differentTotalsNullDate_secondPurchaseInserted_plainSchema() =
        runVariant { caseT1DifferentTotalsNullDate() }

    // T2 (control). Oracle: identical merchant, total, currency AND date, text identical, different
    // image bytes -> same purchase scanned twice: 1 row, second not inserted, existing id returned,
    // second image cleaned up as POST_OCR_DUPLICATE_DRAFT. Passes at HEAD.
    private suspend fun caseT2IdenticalFieldsWithDate() {
        val a = scan(1, 12.50, date = receiptDate)
        val b = scan(2, 12.50, date = receiptDate)
        val first = process(a)
        assertTrue("RG12-01 PRECONDITION: first scan must insert", first.inserted)
        assertPreconditionFingerprints(a, b, first.savedReceipt.id, expectSemanticOnFirst = true)
        val second = process(b)
        val message = state("CONTROL T2", second, b.path)
        assertEquals(message, 1L, db.scannedReceiptDao().getCount().toLong())
        assertFalse(message, second.inserted)
        assertEquals(message, first.savedReceipt.id, second.savedReceipt.id)
        assertEquals(message, "POST_OCR_DUPLICATE_DRAFT", cleanupReasonsFor(b.path))
    }

    @Test
    fun t2_control_identicalFieldsWithDate_isDuplicate_plainSchema() =
        runVariant { caseT2IdenticalFieldsWithDate() }

    // T3. Oracle: same merchant/total/text, but the first purchase has no date and the second does.
    // A missing date is unknown, not evidence of sameness, so the dated scan cannot be confirmed as
    // a duplicate: 2 rows. HEAD: first semantic is null, so the text hit is accepted as duplicate.
    private suspend fun caseT3FirstUndatedSecondDated() {
        val a = scan(1, 12.50, date = null)
        val b = scan(2, 12.50, date = receiptDate)
        val first = process(a)
        assertTrue("RG12-01 PRECONDITION: first scan must insert", first.inserted)
        assertPreconditionFingerprints(a, b, first.savedReceipt.id, expectSemanticOnFirst = false)
        val second = process(b)
        assertSecondIsNewPurchase("COLLISION T3", first, second, b)
    }

    @Test
    fun t3_firstUndatedSecondDated_secondInserted_plainSchema() =
        runVariant { caseT3FirstUndatedSecondDated() }

    // T3b. Mirror of T3: first dated, second undated (incoming semantic null). Same oracle: 2 rows.
    private suspend fun caseT3bFirstDatedSecondUndated() {
        val a = scan(1, 12.50, date = receiptDate)
        val b = scan(2, 12.50, date = null)
        val first = process(a)
        assertTrue("RG12-01 PRECONDITION: first scan must insert", first.inserted)
        assertPreconditionFingerprints(a, b, first.savedReceipt.id, expectSemanticOnFirst = true)
        val second = process(b)
        assertSecondIsNewPurchase("COLLISION T3b", first, second, b)
    }

    @Test
    fun t3b_firstDatedSecondUndated_secondInserted_plainSchema() =
        runVariant { caseT3bFirstDatedSecondUndated() }

    // T4 (control, pre-OCR). Oracle: identical image bytes (uri hash == stored file hash) -> same
    // file: 1 row, existing id returned, nothing inserted, the existing image is never deleted.
    private suspend fun caseT4IdenticalImagePreOcr() {
        val a = scan(1, 12.50, date = receiptDate, uriHash = "rg12-01-same-bytes")
        val b = scan(2, 12.50, date = receiptDate, uriHash = "rg12-01-same-bytes")
        val first = process(a)
        assertTrue("RG12-01 PRECONDITION: first scan must insert", first.inserted)
        assertEquals(
            "RG12-01 PRECONDITION: first row stores the image hash",
            "rg12-01-same-bytes",
            db.scannedReceiptDao().getById(first.savedReceipt.id)?.imageHash
        )
        val second = process(b)
        val message = state("CONTROL T4", second, b.path)
        assertEquals(message, 1L, db.scannedReceiptDao().getCount().toLong())
        assertFalse(message, second.inserted)
        assertEquals(message, first.savedReceipt.id, second.savedReceipt.id)
        verify(exactly = 0) { assets.deleteAsset(a.path) }
    }

    @Test
    fun t4_control_identicalImageBytes_exactHashDuplicate_plainSchema() =
        runVariant { caseT4IdenticalImagePreOcr() }

    // T4b (control, post-OCR exact hash). Oracle: the URI hash cannot be matched pre-OCR, but the
    // persisted copy of the second scan hashes to the stored image hash -> EXACT_HASH duplicate
    // found after OCR: 1 row, second not inserted, its copy cleaned as EXACT_HASH_DUPLICATE_DRAFT,
    // and the first image is never deleted.
    private suspend fun caseT4bIdenticalImagePostOcr() {
        val a = scan(1, 12.50, date = receiptDate, uriHash = "rg12-01-same-bytes")
        val b = scan(2, 12.50, date = receiptDate, uriHash = "rg12-01-uri-hash-differs", fileHash = "rg12-01-same-bytes")
        val first = process(a)
        assertTrue("RG12-01 PRECONDITION: first scan must insert", first.inserted)
        assertEquals(
            "RG12-01 PRECONDITION: first row stores the image hash",
            "rg12-01-same-bytes",
            db.scannedReceiptDao().getById(first.savedReceipt.id)?.imageHash
        )
        val second = process(b)
        val message = state("CONTROL T4b", second, b.path)
        assertEquals(message, 1L, db.scannedReceiptDao().getCount().toLong())
        assertFalse(message, second.inserted)
        assertEquals(message, first.savedReceipt.id, second.savedReceipt.id)
        assertEquals(message, "EXACT_HASH_DUPLICATE_DRAFT", cleanupReasonsFor(b.path))
        verify(exactly = 0) { assets.deleteAsset(a.path) }
    }

    @Test
    fun t4b_control_identicalPersistedImageBytes_exactHashDuplicate_plainSchema() =
        runVariant { caseT4bIdenticalImagePostOcr() }

    // T5. Oracle: same merchant/date/currency, totals 12.50 vs 13.75 -> different semantic
    // fingerprints, so different purchases: 2 rows, second inserted, image kept (passes at HEAD too:
    // known semantic conflict).
    private suspend fun caseT5DifferentTotalsWithDate() {
        val a = scan(1, 12.50, date = receiptDate)
        val b = scan(2, 13.75, date = receiptDate)
        val first = process(a)
        assertTrue("RG12-01 PRECONDITION: first scan must insert", first.inserted)
        assertPreconditionFingerprints(a, b, first.savedReceipt.id, expectSemanticOnFirst = true)
        val second = process(b)
        assertSecondIsNewPurchase("COLLISION T5", first, second, b)
    }

    @Test
    fun t5_differentTotalsWithDate_secondPurchaseInserted_plainSchema() =
        runVariant { caseT5DifferentTotalsWithDate() }

    private companion object {
        const val MERCHANT = "ACME FUEL"
    }
}
