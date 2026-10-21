package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.entity.AiChatMessageEntity
import com.yourname.expensetracker.data.database.entity.AiChatSessionEntity
import com.yourname.expensetracker.data.database.entity.BackgroundJobRun
import com.yourname.expensetracker.data.database.entity.BankStatementImportItem
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.OperationRun
import com.yourname.expensetracker.data.database.entity.OperationRunEvent
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.PipelineDiagnosticEvent
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.di.RetentionModule
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.privacy.RetentionTarget
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * B6-10b retention coverage gap: row-level tests for the REAL production
 * [RetentionTarget]s of [RetentionModule] that had no row-level purge test
 * (raw_notifications, ai_chat_messages, email_receipt_sources,
 * pending_reviews.notificationText, background_job_runs.errorMessage,
 * bank_statement_import_items.merchant) plus exact-at-cutoff rows for
 * notification_intake, pipeline_diagnostic_events, scanned_receipts.rawOcrText,
 * ai_artifacts (TTL path) and the 30_operation_runs orphan-event path.
 *
 * Every DAO predicate is a strict `< cutoff`, so for each target: a row 1 ms
 * older than the cutoff is purged, a row exactly AT the cutoff is kept and a
 * recent row is kept.
 *
 * These tests are CUTOFF-AGNOSTIC: each target's purge(cutoffMs) receives the
 * cutoff directly, and a single synthetic 30-day cutoff ([cutoffMs]) is used for
 * all of them. The real windows are chosen by DataRetentionWorker.cutoffFor:
 * raw_notifications / notification_intake / scanned_receipts.rawOcrText use the
 * USER-CONFIGURABLE rawNotificationRetentionDays / rawOcrRetentionDays settings
 * (default 30 days); email, ai-chat, diagnostics and the column-scoped
 * text-redaction targets use a fixed 30 days; 30_operation_runs uses 90 days
 * (OPERATION_RUN_RETENTION_DAYS); ai_artifacts uses `now` (TTL path). Window
 * selection is NOT asserted here. The fixed clock [now] is what the production
 * targets stamp as purge time.
 *
 * Timestamp columns that the DAO predicate does not use (raw_notifications.timestamp,
 * notification_intake postTime/createdAt/updatedAt, scanned_receipts parsedDate/updatedAt,
 * pending_reviews.suggestedDate, bank_statement_import_items transactionDate/updatedAt) are
 * deliberately offset on every row (recent on the 1 ms-older row, stale on the kept rows)
 * so a predicate on the wrong column fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RetentionTargetCoverageGapTest {

    private lateinit var database: AppDatabase

    private val now = 1_700_000_000_000L
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = now }

    private val cutoffMs = now - TimeUnit.DAYS.toMillis(30)
    private val oneMsOlder = cutoffMs - 1
    private val atCutoff = cutoffMs

    private val maintenanceMode = mockk<RestoreMaintenanceMode>()

    @Before
    fun setUp() {
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun targetNamed(name: String): RetentionTarget =
        RetentionModule.provideRetentionTargets(database, timeProvider, DatabaseWriteBarrier(maintenanceMode))
            .first { it.name == name }

    // ══ raw_notifications (RetentionModule.kt:49-97) ══════════════════════════

    // `timestamp` is NOT the retention column (the DAO predicate is capturedAt): the 1ms-older row gets
    // a recent timestamp and the kept rows get an old one, so a wrong-column predicate fails both ways.
    private fun rawNotification(fingerprint: String, capturedAt: Long, timestamp: Long = capturedAt) = RawNotification(
        packageName = "com.bank.app",
        appName = "Bank",
        title = "Payment title $fingerprint",
        text = "Payment text $fingerprint",
        bigText = "Big text $fingerprint",
        subText = "Sub text $fingerprint",
        extrasJson = "{\"k\":\"$fingerprint\"}",
        timestamp = timestamp,
        capturedAt = capturedAt,
        isProcessed = true,
        isRelevant = true,
        parseResult = "{\"parsed\":\"$fingerprint\"}",
        dedupeFingerprint = "fp-$fingerprint"
    )

    @Test
    fun `raw_notifications target nulls content of row 1ms older than cutoff and keeps at-cutoff and recent rows`() = runTest {
        val dao = database.rawNotificationDao()
        val oldId = dao.insert(rawNotification("old", oneMsOlder, timestamp = now))
        val atCutoffId = dao.insert(rawNotification("at-cutoff", atCutoff, timestamp = oneMsOlder - 1_000))
        val recentId = dao.insert(rawNotification("recent", now, timestamp = oneMsOlder - 1_000))

        val result = targetNamed("raw_notifications").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals("only the 1ms-older row is counted", 1, result.rowsPurged)
        assertEquals("rows are nulled, never deleted", 3, dao.getAll().size)

        val old = dao.getById(oldId)!!
        assertNull("[1ms older] title", old.title)
        assertNull("[1ms older] text", old.text)
        assertNull("[1ms older] bigText", old.bigText)
        assertNull("[1ms older] subText", old.subText)
        assertNull("[1ms older] extrasJson", old.extrasJson)
        assertNull("[1ms older] parseResult", old.parseResult)
        assertEquals("[1ms older] rawContentPurgedAt is the fixed-clock now", now, old.rawContentPurgedAt)
        assertEquals("[1ms older] structural packageName kept", "com.bank.app", old.packageName)
        assertEquals("[1ms older] capturedAt kept", oneMsOlder, old.capturedAt)

        for ((rowName, id) in listOf("at cutoff" to atCutoffId, "recent" to recentId)) {
            val row = dao.getById(id)!!
            assertNotNull("[$rowName] title kept", row.title)
            assertNotNull("[$rowName] text kept", row.text)
            assertNotNull("[$rowName] bigText kept", row.bigText)
            assertNotNull("[$rowName] subText kept", row.subText)
            assertNotNull("[$rowName] extrasJson kept", row.extrasJson)
            assertNotNull("[$rowName] parseResult kept", row.parseResult)
            assertNull("[$rowName] must not be marked purged", row.rawContentPurgedAt)
        }
    }

    // ══ ai_chat_messages (RetentionModule.kt:185-217; AiChatMessageDao.deleteOlderThan) ══

    private fun chatMessage(sessionId: Long, text: String, createdAt: Long) = AiChatMessageEntity(
        sessionId = sessionId,
        role = AssistantMessageRole.USER,
        kind = AssistantMessageKind.QUERY,
        text = text,
        payloadJson = null,
        createdAt = createdAt
    )

    @Test
    fun `ai_chat_messages target deletes row 1ms older than cutoff and keeps at-cutoff and recent rows`() = runTest {
        val sessionId = database.aiChatSessionDao().insert(
            AiChatSessionEntity(title = "synthetic", createdAt = now, updatedAt = now)
        )
        val messageDao = database.aiChatMessageDao()
        messageDao.insert(chatMessage(sessionId, "1ms older", oneMsOlder))
        messageDao.insert(chatMessage(sessionId, "at cutoff", atCutoff))
        messageDao.insert(chatMessage(sessionId, "recent", now))

        val result = targetNamed("ai_chat_messages").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals("only the 1ms-older message is deleted", 1, result.rowsPurged)
        assertEquals(
            "[at cutoff] and [recent] survive, [1ms older] is gone",
            listOf("at cutoff", "recent"),
            messageDao.getBySession(sessionId).map { it.text }
        )
        assertNotNull("the parent chat session is not deleted", database.aiChatSessionDao().getById(sessionId))
    }

    // ══ email_receipt_sources (RetentionModule.kt:220-254; redact, never delete) ══

    private fun scannedReceipt() = ScannedReceipt(
        imagePath = null,
        rawOcrText = "synthetic ocr",
        parsedTotal = 1.0,
        parsedMerchant = "Synthetic Shop",
        parsedDate = now,
        parsedItems = null,
        parsedTaxAmount = null,
        confidence = 0.9f,
        createdAt = now,
        updatedAt = now
    )

    private fun emailSource(receiptId: Long, tag: String, parsedAt: Long, redacted: Boolean = false) =
        EmailReceiptSource(
            receiptId = receiptId,
            emailSender = if (redacted) null else "sender-$tag@example.test",
            emailSubject = if (redacted) null else "subject $tag",
            emailMessageId = if (redacted) null else "message-id-$tag",
            emailMessageIdHash = "msg-hash-$tag",
            contentFingerprintHash = "content-hash-$tag",
            parsedAt = parsedAt,
            provider = "amazon",
            confidence = 0.8,
            fingerprint = "fp-$tag"
        )

    @Test
    fun `email_receipt_sources target redacts sensitive fields of row 1ms older than cutoff and keeps hashes and other rows`() = runTest {
        val receiptId = database.scannedReceiptDao().insert(scannedReceipt())
        val dao = database.emailReceiptDao()
        val oldId = dao.insert(emailSource(receiptId, "old", oneMsOlder))
        val atCutoffId = dao.insert(emailSource(receiptId, "at-cutoff", atCutoff))
        val recentId = dao.insert(emailSource(receiptId, "recent", now))
        // Already redacted older row: the SQL excludes it (all three fields NULL), so it is not counted.
        val alreadyRedactedId = dao.insert(emailSource(receiptId, "already", oneMsOlder, redacted = true))

        val result = targetNamed("email_receipt_sources").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals("only the unredacted 1ms-older row is counted", 1, result.rowsPurged)
        assertEquals("rows are redacted, never deleted", 4, dao.getAll().size)

        val old = dao.getById(oldId)!!
        assertNull("[1ms older] emailSender", old.emailSender)
        assertNull("[1ms older] emailSubject", old.emailSubject)
        assertNull("[1ms older] emailMessageId", old.emailMessageId)
        assertEquals("[1ms older] emailMessageIdHash kept for dedup", "msg-hash-old", old.emailMessageIdHash)
        assertEquals("[1ms older] contentFingerprintHash kept", "content-hash-old", old.contentFingerprintHash)
        assertEquals("[1ms older] fingerprint kept", "fp-old", old.fingerprint)
        assertEquals("[1ms older] provider kept", "amazon", old.provider)
        assertEquals("[1ms older] receipt link kept", receiptId, old.receiptId)
        assertEquals("[1ms older] parsedAt kept", oneMsOlder, old.parsedAt)

        for ((rowName, id) in listOf("at cutoff" to atCutoffId, "recent" to recentId)) {
            val row = dao.getById(id)!!
            assertNotNull("[$rowName] emailSender kept", row.emailSender)
            assertNotNull("[$rowName] emailSubject kept", row.emailSubject)
            assertNotNull("[$rowName] emailMessageId kept", row.emailMessageId)
        }
        assertNull("[already redacted] stays redacted", dao.getById(alreadyRedactedId)!!.emailSender)
    }

    // ══ pending_reviews.notificationText (RetentionModule.kt:352-386) ═════════

    // suggestedDate is NOT the retention column (the DAO predicate is createdAt); callers offset it.
    private fun pendingReview(
        tag: String,
        createdAt: Long,
        redacted: Boolean = false,
        suggestedDate: Long = createdAt,
    ) = PendingReview(
        rawNotificationId = null,
        suggestedDate = suggestedDate,
        suggestedAmount = 12.5,
        suggestedCurrency = "EUR",
        suggestedMerchant = "Merchant $tag",
        suggestedType = "EXPENSE",
        suggestedCategoryId = null,
        confidence = 0.9f,
        packageName = "com.bank.app",
        notificationTitle = if (redacted) null else "Title $tag",
        notificationText = if (redacted) null else "Text $tag",
        createdAt = createdAt
    )

    @Test
    fun `pending_reviews notificationText target nulls title and text of row 1ms older than cutoff and keeps structural fields`() = runTest {
        val dao = database.pendingReviewDao()
        val oldId = dao.insert(pendingReview("old", oneMsOlder, suggestedDate = now))
        val atCutoffId = dao.insert(pendingReview("at-cutoff", atCutoff, suggestedDate = oneMsOlder - 1_000))
        val recentId = dao.insert(pendingReview("recent", now, suggestedDate = oneMsOlder - 1_000))
        val alreadyRedactedId = dao.insert(pendingReview("already", oneMsOlder, redacted = true))

        val result = targetNamed("pending_reviews.notificationText").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals("only the unredacted 1ms-older row is counted", 1, result.rowsPurged)

        val old = dao.getById(oldId)!!
        assertNull("[1ms older] notificationTitle", old.notificationTitle)
        assertNull("[1ms older] notificationText", old.notificationText)
        assertEquals("[1ms older] suggestedMerchant kept", "Merchant old", old.suggestedMerchant)
        assertEquals("[1ms older] suggestedAmount kept", 12.5, old.suggestedAmount!!, 0.0)
        assertEquals("[1ms older] status kept", com.yourname.expensetracker.data.database.entity.PendingReviewStatus.PENDING, old.status)
        assertEquals("[1ms older] createdAt kept", oneMsOlder, old.createdAt)

        for ((rowName, id) in listOf("at cutoff" to atCutoffId, "recent" to recentId)) {
            val row = dao.getById(id)!!
            assertNotNull("[$rowName] notificationTitle kept", row.notificationTitle)
            assertNotNull("[$rowName] notificationText kept", row.notificationText)
        }
        assertNotNull("[already redacted] row still exists", dao.getById(alreadyRedactedId))
    }

    // ══ background_job_runs.errorMessage (RetentionModule.kt:388-423) ═════════

    private fun jobRun(tag: String, startedAt: Long, errorMessage: String?) = BackgroundJobRun(
        workerName = "worker-$tag",
        startedAt = startedAt,
        finishedAt = startedAt + 10,
        status = "FAILED",
        errorMessage = errorMessage,
        errorClass = "SyntheticException"
    )

    @Test
    fun `background_job_runs errorMessage target nulls message of row 1ms older than cutoff and keeps other columns`() = runTest {
        val dao = database.backgroundJobRunDao()
        val oldId = dao.insert(jobRun("old", oneMsOlder, "synthetic secret old"))
        val atCutoffId = dao.insert(jobRun("at-cutoff", atCutoff, "synthetic secret at cutoff"))
        val recentId = dao.insert(jobRun("recent", now, "synthetic secret recent"))
        val noMessageId = dao.insert(jobRun("no-message", oneMsOlder, null))

        val result = targetNamed("background_job_runs.errorMessage").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals("only the old row that HAS a message is counted", 1, result.rowsPurged)

        val old = dao.getById(oldId)!!
        assertNull("[1ms older] errorMessage", old.errorMessage)
        assertEquals("[1ms older] status kept", "FAILED", old.status)
        assertEquals("[1ms older] errorClass kept", "SyntheticException", old.errorClass)
        assertEquals("[1ms older] workerName kept", "worker-old", old.workerName)

        assertEquals("[at cutoff] message kept", "synthetic secret at cutoff", dao.getById(atCutoffId)!!.errorMessage)
        assertEquals("[recent] message kept", "synthetic secret recent", dao.getById(recentId)!!.errorMessage)
        assertNull("[no message] stays null", dao.getById(noMessageId)!!.errorMessage)
    }

    // ══ bank_statement_import_items.merchant (RetentionModule.kt:425-459) ═════

    // transactionFingerprint is the production merchantKey (BankStatementLifecycleProcessor stores
    // MerchantKeyGenerator output: lowercase alphanumerics of the merchant name) - a realistic
    // PLAINTEXT merchant key, not an opaque token. transactionDate/updatedAt are NOT the retention
    // column (the DAO predicate is createdAt); callers offset them via [otherTimestamp].
    private fun importItem(
        runId: Long,
        index: Int,
        merchantKey: String,
        createdAt: Long,
        merchant: String?,
        otherTimestamp: Long = createdAt,
    ) =
        BankStatementImportItem(
            runId = runId,
            itemIndex = index,
            transactionFingerprint = merchantKey,
            status = BankStatementImportItem.STATUS_CREATED_REVIEW,
            merchant = merchant,
            amount = 9.99,
            currency = "EUR",
            transactionDate = otherTimestamp,
            createdAt = createdAt,
            updatedAt = otherTimestamp
        )

    @Test
    fun `bank_statement_import_items merchant target nulls merchant of row 1ms older than cutoff and keeps other columns`() = runTest {
        val dao = database.bankStatementImportItemDao()
        val runId = 1L
        val stale = oneMsOlder - 1_000
        dao.insert(importItem(runId, 0, "syntheticmerchantold", oneMsOlder, "Synthetic Merchant Old", otherTimestamp = now))
        dao.insert(importItem(runId, 1, "syntheticmerchantatcutoff", atCutoff, "Synthetic Merchant At Cutoff", otherTimestamp = stale))
        dao.insert(importItem(runId, 2, "syntheticmerchantrecent", now, "Synthetic Merchant Recent", otherTimestamp = stale))
        dao.insert(importItem(runId, 3, "syntheticnomerchant", oneMsOlder, null, otherTimestamp = now))

        val result = targetNamed("bank_statement_import_items.merchant").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals("only the old row that HAS a merchant is counted", 1, result.rowsPurged)

        val rows = dao.getByRunId(runId).associateBy { it.itemIndex }
        assertEquals("rows are redacted, never deleted", 4, rows.size)
        val old = rows.getValue(0)
        assertNull("[1ms older] merchant", old.merchant)
        // KNOWN DEFECT PF-1 — flip to assertNull/hash when fixed
        // The retention target nulls only `merchant`; the plaintext merchant key survives in
        // transactionFingerprint, so the merchant identity is still recoverable. Pinned as-is.
        assertEquals(
            "[1ms older] KNOWN DEFECT PF-1 — plaintext merchant key kept in transactionFingerprint",
            "syntheticmerchantold",
            old.transactionFingerprint
        )
        assertEquals("[1ms older] amount kept", 9.99, old.amount!!, 0.0)
        assertEquals("[1ms older] status kept", BankStatementImportItem.STATUS_CREATED_REVIEW, old.status)
        assertEquals("[at cutoff] merchant kept", "Synthetic Merchant At Cutoff", rows.getValue(1).merchant)
        assertEquals("[recent] merchant kept", "Synthetic Merchant Recent", rows.getValue(2).merchant)
        assertNull("[no merchant] stays null", rows.getValue(3).merchant)
    }

    // ══ exact-at-cutoff: notification_intake (RetentionModule.kt:256-301) ═════

    // postTime/createdAt/updatedAt are NOT the retention column (the DAO predicate is capturedAt);
    // callers offset them via [otherTimestamp] so a wrong-column predicate fails.
    private fun intakeRow(fingerprint: String, capturedAt: Long, otherTimestamp: Long = capturedAt) = NotificationIntakeEntity(
        packageName = "com.bank.app",
        appName = "Bank",
        notificationKeyHash = "keyhash-$fingerprint",
        postTime = otherTimestamp,
        capturedAt = capturedAt,
        source = "LISTENER",
        correlationId = "corr-$fingerprint",
        dedupeFingerprint = fingerprint,
        contentHash = "hash-$fingerprint",
        title = "Payment of \$50",
        text = "You spent \$50 at Store",
        bigText = "big body",
        subText = "sub",
        extrasJson = "{\"k\":\"v\"}",
        rawStorageMode = "STORE_RAW",
        payloadMode = "RAW",
        status = "DONE",
        createdAt = otherTimestamp,
        updatedAt = otherTimestamp
    )

    @Test
    fun `notification_intake target keeps plaintext and ciphertext rows exactly at cutoff and purges 1ms older`() = runTest {
        val dao = database.notificationIntakeDao()
        val staleOther = oneMsOlder - 1_000
        val oldId = dao.insertOrIgnore(intakeRow("old-fp", oneMsOlder, otherTimestamp = now))
        val atCutoffId = dao.insertOrIgnore(intakeRow("at-cutoff-fp", atCutoff, otherTimestamp = staleOther))
        val atCutoffTransientId = dao.insertOrIgnore(
            intakeRow("at-cutoff-transient-fp", atCutoff, otherTimestamp = staleOther).copy(
                title = null,
                text = null,
                bigText = null,
                subText = null,
                extrasJson = null,
                rawStorageMode = "STORE_REDACTED",
                payloadMode = "TRANSIENT",
                transientPayloadCiphertext = "cipher-at-cutoff",
                transientPayloadNonce = "nonce-at-cutoff",
                transientPayloadVersion = 1
            )
        )

        val result = targetNamed("notification_intake").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals("only the 1ms-older row is counted", 1, result.rowsPurged)

        val old = dao.getById(oldId)!!
        assertNull("[1ms older] title", old.title)
        assertNull("[1ms older] text", old.text)
        assertEquals("[1ms older] rawPayloadPurgedAt is fixed-clock now", now, old.rawPayloadPurgedAt)
        assertEquals("[1ms older] transientPayloadPurgedAt is fixed-clock now", now, old.transientPayloadPurgedAt)

        val atCutoffRow = dao.getById(atCutoffId)!!
        assertNotNull("[at cutoff] title kept", atCutoffRow.title)
        assertNotNull("[at cutoff] text kept", atCutoffRow.text)
        assertNull("[at cutoff] rawPayloadPurgedAt stays null", atCutoffRow.rawPayloadPurgedAt)
        assertEquals("[at cutoff] updatedAt untouched", staleOther, atCutoffRow.updatedAt)
        assertEquals("[at cutoff] capturedAt kept", atCutoff, atCutoffRow.capturedAt)

        val atCutoffTransient = dao.getById(atCutoffTransientId)!!
        assertEquals("[at cutoff transient] ciphertext kept", "cipher-at-cutoff", atCutoffTransient.transientPayloadCiphertext)
        assertEquals("[at cutoff transient] nonce kept", "nonce-at-cutoff", atCutoffTransient.transientPayloadNonce)
        assertEquals("[at cutoff transient] transientPayloadVersion kept", 1, atCutoffTransient.transientPayloadVersion)
        assertNull("[at cutoff transient] transientPayloadPurgedAt stays null", atCutoffTransient.transientPayloadPurgedAt)
    }

    // ══ exact-at-cutoff: pipeline_diagnostic_events (RetentionModule.kt:303-349) ══

    @Test
    fun `pipeline_diagnostic_events target keeps row exactly at cutoff and deletes 1ms older`() = runTest {
        val dao = database.pipelineDiagnosticEventDao()
        fun event(timestamp: Long, message: String) = PipelineDiagnosticEvent(
            pipeline = "notification",
            stage = "parse",
            outcome = "DONE",
            message = message,
            timestamp = timestamp
        )
        dao.insert(event(oneMsOlder, "1ms older"))
        dao.insert(event(atCutoff, "at cutoff"))
        dao.insert(event(now, "recent"))

        val result = targetNamed("pipeline_diagnostic_events").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals("only the 1ms-older event is deleted", 1, result.rowsPurged)
        assertEquals(
            "[at cutoff] and [recent] survive, [1ms older] is gone",
            setOf("at cutoff", "recent"),
            dao.getRecent(50).map { it.message }.toSet()
        )
    }

    // ══ exact-at-cutoff: scanned_receipts.rawOcrText (RetentionModule.kt:100-140) ══

    // parsedDate/updatedAt are NOT the retention column (the DAO predicate is createdAt); callers
    // offset them via [otherTimestamp] so a wrong-column predicate fails.
    private fun ocrReceipt(createdAt: Long, otherTimestamp: Long = createdAt) = ScannedReceipt(
        imagePath = null,
        rawOcrText = "TOTAL 12.50 MERCHANT SYNTHETIC",
        parsedTotal = 12.5,
        parsedMerchant = "Synthetic Shop",
        parsedDate = otherTimestamp,
        parsedItems = """[{"desc":"coffee","price":12.5}]""",
        parsedTaxAmount = null,
        confidence = 0.9f,
        createdAt = createdAt,
        updatedAt = otherTimestamp,
        parseFailureReason = "IllegalStateException: free text"
    )

    @Test
    fun `scanned_receipts rawOcrText target keeps row exactly at cutoff and purges 1ms older`() = runTest {
        val dao = database.scannedReceiptDao()
        val oldId = dao.insert(ocrReceipt(oneMsOlder, otherTimestamp = now))
        val atCutoffId = dao.insert(ocrReceipt(atCutoff, otherTimestamp = oneMsOlder - 1_000))

        val result = targetNamed("scanned_receipts.rawOcrText").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals("only the 1ms-older receipt is counted", 1, result.rowsPurged)

        val old = dao.getById(oldId)!!
        assertEquals("[1ms older] rawOcrText purged sentinel", "", old.rawOcrText)
        assertNull("[1ms older] parsedItems", old.parsedItems)
        assertNull("[1ms older] parsedMerchant", old.parsedMerchant)
        assertNull("[1ms older] parseFailureReason", old.parseFailureReason)
        assertEquals("[1ms older] rawOcrTextPurgedAt is fixed-clock now", now, old.rawOcrTextPurgedAt)

        val kept = dao.getById(atCutoffId)!!
        assertEquals("[at cutoff] rawOcrText kept", "TOTAL 12.50 MERCHANT SYNTHETIC", kept.rawOcrText)
        assertNotNull("[at cutoff] parsedItems kept", kept.parsedItems)
        assertEquals("[at cutoff] parsedMerchant kept", "Synthetic Shop", kept.parsedMerchant)
        assertNotNull("[at cutoff] parseFailureReason kept", kept.parseFailureReason)
        assertNull("[at cutoff] rawOcrTextPurgedAt stays null", kept.rawOcrTextPurgedAt)
    }

    // ══ exact-at-cutoff: ai_artifacts TTL path (RetentionModule.kt:142-182) ═══

    private fun aiArtifact(sourceHash: String, expiresAt: Long?) = AiArtifactEntity(
        targetType = AiTargetType.PENDING_REVIEW,
        targetKey = "pending_review:1-$sourceHash",
        capability = AiCapability.REVIEW_EXPLANATION,
        status = AiArtifactStatus.READY,
        mode = AiMode.AUTO,
        promptVersion = "v1",
        sourceHash = sourceHash,
        createdAt = now,
        updatedAt = now,
        expiresAt = expiresAt
    )

    @Test
    fun `ai_artifacts target keeps artifact whose expiresAt equals now and deletes expiresAt one ms earlier`() = runTest {
        val dao = database.aiArtifactDao()
        // DataRetentionWorker routes "ai_artifacts" -> now as the cutoff (non-null-expiry path: expiresAt < now).
        dao.upsert(aiArtifact("expired_1ms_ago", expiresAt = now - 1))
        dao.upsert(aiArtifact("expires_exactly_now", expiresAt = now))
        dao.upsert(aiArtifact("expires_1ms_ahead", expiresAt = now + 1))

        val result = targetNamed("ai_artifacts").purge(now)

        assertTrue(result.success)
        assertEquals("only the artifact expired 1ms ago is deleted", 1, result.rowsPurged)
        assertEquals(
            setOf("expires_exactly_now", "expires_1ms_ahead"),
            dao.getAll().map { it.sourceHash }.toSet()
        )
    }

    // ══ exact-at-cutoff: 30_operation_runs orphan events (RetentionModule.kt:542-591) ══

    private fun opRun(correlationId: String, status: String, startedAt: Long, finishedAt: Long?) = OperationRun(
        correlationId = correlationId,
        operationType = "TEST_OP",
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt
    )

    private fun opEvent(runId: Long?, correlationId: String, occurredAt: Long) = OperationRunEvent(
        operationRunId = runId,
        correlationId = correlationId,
        operationType = "TEST_OP",
        stage = "stage",
        eventType = "EVENT",
        outcome = "DONE",
        severity = "INFO",
        occurredAt = occurredAt
    )

    @Test
    fun `operation_runs target keeps orphan events exactly at cutoff and deletes null-parent and dangling-parent orphans 1ms older`() = runTest {
        val eventDao = database.operationRunEventDao()
        val danglingParentId = 999_999L
        eventDao.insert(opEvent(null, "null-parent-1ms-older", oneMsOlder))
        eventDao.insert(opEvent(null, "null-parent-at-cutoff", atCutoff))
        eventDao.insert(opEvent(danglingParentId, "dangling-1ms-older", oneMsOlder))
        eventDao.insert(opEvent(danglingParentId, "dangling-at-cutoff", atCutoff))

        val result = targetNamed("30_operation_runs").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals("rowsPurged is the orphan total", 2, result.rowsPurged)
        assertEquals(2, result.detailCounts["orphanEvents"])
        assertEquals(0, result.detailCounts["childEvents"])
        assertEquals(0, result.detailCounts["parentRuns"])
        assertTrue("[null-parent 1ms older] gone", eventDao.getByCorrelationId("null-parent-1ms-older").isEmpty())
        assertTrue("[dangling 1ms older] gone", eventDao.getByCorrelationId("dangling-1ms-older").isEmpty())
        assertEquals("[null-parent at cutoff] kept", 1, eventDao.getByCorrelationId("null-parent-at-cutoff").size)
        assertEquals("[dangling at cutoff] kept", 1, eventDao.getByCorrelationId("dangling-at-cutoff").size)
    }

    @Test
    fun `operation_runs target keeps terminal run finished exactly at cutoff with its events and deletes run finished 1ms older`() = runTest {
        val runDao = database.operationRunDao()
        val eventDao = database.operationRunEventDao()
        val oldRunId = runDao.insert(opRun("run-1ms-older", "SUCCESS", startedAt = oneMsOlder - 100, finishedAt = oneMsOlder))
        val atCutoffRunId = runDao.insert(opRun("run-at-cutoff", "SUCCESS", startedAt = atCutoff - 100, finishedAt = atCutoff))
        eventDao.insert(opEvent(oldRunId, "child-of-1ms-older", oneMsOlder))
        // The kept run's event is itself older than the cutoff; it has a live parent so it is not an orphan.
        eventDao.insert(opEvent(atCutoffRunId, "child-of-at-cutoff", oneMsOlder))

        val result = targetNamed("30_operation_runs").purge(cutoffMs)

        assertTrue(result.success)
        assertEquals("1 child + 1 parent", 2, result.rowsPurged)
        assertEquals(1, result.detailCounts["childEvents"])
        assertEquals(1, result.detailCounts["parentRuns"])
        assertEquals(0, result.detailCounts["orphanEvents"])
        assertNull("[run 1ms older] gone", runDao.getById(oldRunId))
        assertTrue("[child of 1ms older] gone", eventDao.getByCorrelationId("child-of-1ms-older").isEmpty())
        assertNotNull("[run at cutoff] kept", runDao.getById(atCutoffRunId))
        assertEquals("[child of at cutoff] kept", 1, eventDao.getByCorrelationId("child-of-at-cutoff").size)
    }
}
