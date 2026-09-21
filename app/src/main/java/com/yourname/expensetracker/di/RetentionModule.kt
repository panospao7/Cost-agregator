package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.RetentionPurgeResult
import com.yourname.expensetracker.domain.privacy.RetentionRegistry
import com.yourname.expensetracker.domain.privacy.RetentionTarget
import com.yourname.expensetracker.domain.util.CancellationSafe
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import javax.inject.Singleton

/**
 * PRIV-441-12: Hilt module that registers all [RetentionTarget] implementations
 * and provides the injectable [RetentionRegistry].
 *
 * All sensitive data targets must be registered here. [DataRetentionWorker]
 * uses [RetentionRegistry.allTargets] instead of an inline list.
 *
 * RP-14 14c: the policy-level list of REQUIRED targets lives independently in
 * [com.yourname.expensetracker.domain.privacy.RetentionPolicyContract]; the
 * registry coverage test asserts this module's construction list covers it
 * exactly (neither side is derived from the other).
 */
@Module
@InstallIn(SingletonComponent::class)
object RetentionModule {

    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideRetentionTargets(
        appDatabase: AppDatabase,
        timeProvider: TimeProvider,
        writeBarrier: DatabaseWriteBarrier
    ): Set<RetentionTarget> = setOf(

        object : RetentionTarget {
            override val name = "raw_notifications"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                // GR-14u51b: canonical write-barrier admission BEFORE the
                // runCatchingCancellable wrapper — a check inside it would
                // swallow DatabaseAccessBlockedException into a
                // RETENTION_PURGE_FAILED result.  CancellationException
                // propagates; every other failure reports the CONTROLLED
                // CONSTANT "WRITE_BARRIER_BLOCKED" (never e.message).
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.RawNotificationDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    // GR-08k1 accessor normalization: DAO-named local (was `dao`) so the
                    // mutation receiver resolves to exactly one DAO identity.
                    val rawNotificationDao = appDatabase.rawNotificationDao()
                    var total = 0
                    val now = timeProvider.now()
                    while (true) {
                        val batch = rawNotificationDao.getUnpurgedRawNotificationsOlderThan(cutoffMs, 100)
                        if (batch.isEmpty()) break
                        for (n in batch) {
                            rawNotificationDao.updateRawContentPurged(
                                id = n.id, rawContentPurgedAt = now,
                                title = null, text = null, bigText = null,
                                subText = null, extrasJson = null, parseResult = null
                            )
                        }
                        total += batch.size
                    }
                    RetentionPurgeResult(name, total, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "scanned_receipts.rawOcrText"
            // RP-14 P8-002: ONE set-based SQL update clears rawOcrText (→ '',
            // the established purged sentinel for the NOT NULL column),
            // parsedItems, parsedMerchant, parseFailureReason (writers are not
            // limited to controlled codes), and stamps rawOcrTextPurgedAt once.
            // Never materializes OCR/item payloads into Kotlin.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                // GR-14u51b: canonical write-barrier admission before the
                // runCatchingCancellable wrapper (see raw_notifications).
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.ScannedReceiptDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    // GR-08k1 accessor normalization: DAO-named local (was `dao`).
                    val scannedReceiptDao = appDatabase.scannedReceiptDao()
                    val count = scannedReceiptDao.purgeRawOcrText(
                        beforeMs = cutoffMs,
                        nowMs = timeProvider.now()
                    )
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "ai_artifacts"
            // RP-14 P8-004: the cutoff passed for this target is `now`; the
            // null-expiry backstop cutoff is derived HERE in Kotlin from the
            // ONE named AppConfig constant (max legitimate TTL, 30 days) and
            // passed explicitly to the DAO — no SQL-side timestamp arithmetic.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                // GR-14u51b: canonical write-barrier admission before the
                // runCatchingCancellable wrapper (see raw_notifications).
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.AiArtifactDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    // GR-08k1 accessor normalization: DAO-named local replaces the
                    // database-chained receiver (GR-08e precedent).
                    val aiArtifactDao = appDatabase.aiArtifactDao()
                    val count = aiArtifactDao.deleteExpired(
                        now = cutoffMs,
                        nullExpiryCutoff = cutoffMs - AppConfig.Ai.NULL_EXPIRY_BACKSTOP_MS
                    )
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "ai_chat_messages"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                // GR-14u51b: canonical write-barrier admission before the
                // runCatchingCancellable wrapper (see raw_notifications).
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.AiChatMessageDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    // GR-08k1 accessor normalization: DAO-named local replaces the
                    // database-chained receiver (GR-08e precedent).
                    val aiChatMessageDao = appDatabase.aiChatMessageDao()
                    val count = aiChatMessageDao.deleteOlderThan(cutoffMs)
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "email_receipt_sources"
            // PRIV-43B-12: Redact sensitive fields, do NOT delete rows (preserves dedup hashes/links)
            // cutoffMs is the email-specific cutoff (now - 30 days), passed by DataRetentionWorker
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                // GR-14u51b: canonical write-barrier admission before the
                // runCatchingCancellable wrapper (see raw_notifications).
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.EmailReceiptDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    // GR-08k1 accessor normalization: DAO-named local replaces the
                    // database-chained receiver (GR-08e precedent).
                    val emailReceiptDao = appDatabase.emailReceiptDao()
                    val count = emailReceiptDao.redactSensitiveFieldsOlderThan(cutoffMs)
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "notification_intake"
            // P8F-01: Null out raw payload text (title/text/bigText/subText/extrasJson) past
            // the retention window — mirrors the raw_notifications target since intake carries
            // the same captured notification content. cutoffMs is the notification cutoff,
            // passed by DataRetentionWorker.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                // GR-14u51b: canonical write-barrier admission before the
                // runCatchingCancellable wrapper (see raw_notifications).
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.NotificationIntakeDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    // GR-08k1 accessor normalization: DAO-named local (was `dao`) so the
                    // mutation receiver resolves to exactly one DAO identity.
                    val notificationIntakeDao = appDatabase.notificationIntakeDao()
                    var total = 0
                    val now = timeProvider.now()
                    while (true) {
                        val batch = notificationIntakeDao.getUnpurgedIntakeOlderThan(cutoffMs, 100)
                        if (batch.isEmpty()) break
                        for (n in batch) notificationIntakeDao.purgeRawPayload(n.id, now)
                        total += batch.size
                    }
                    RetentionPurgeResult(name, total, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "pipeline_diagnostic_events"
            // P8F-06: Hard-delete old diagnostic rows (free-text message / exceptionMessage /
            // metadataJson can carry PII). cutoffMs is the diagnostics cutoff, passed by
            // DataRetentionWorker.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                // GR-14u51b: canonical write-barrier admission before the
                // runCatchingCancellable wrapper (see raw_notifications).
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.PipelineDiagnosticEventDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    // GR-08k1 accessor normalization: DAO-named local replaces the
                    // database-chained receiver (GR-08e precedent).
                    val pipelineDiagnosticEventDao = appDatabase.pipelineDiagnosticEventDao()
                    val count = pipelineDiagnosticEventDao.deleteOlderThan(cutoffMs)
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "pending_reviews.notificationText"
            // PR5: Redact notification text/title in pending reviews past the notification
            // retention window. Preserves structural fields for review queue functionality.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                // GR-14u51b: canonical write-barrier admission before the
                // runCatchingCancellable wrapper (see raw_notifications).
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.PendingReviewDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    // GR-08k1 accessor normalization: DAO-named local replaces the
                    // database-chained receiver (GR-08e precedent).
                    val pendingReviewDao = appDatabase.pendingReviewDao()
                    val count = pendingReviewDao.redactNotificationTextOlderThan(cutoffMs)
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "background_job_runs.errorMessage"
            // PR5: Redact error messages in background job runs older than 30 days.
            // Error messages may contain PII from exception stack traces.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                // GR-14u51b: canonical write-barrier admission before the
                // runCatchingCancellable wrapper (see raw_notifications).
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.BackgroundJobRunDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    // GR-08k1 accessor normalization: DAO-named local replaces the
                    // database-chained receiver (GR-08e precedent).
                    val backgroundJobRunDao = appDatabase.backgroundJobRunDao()
                    val count = backgroundJobRunDao.redactErrorMessagesOlderThan(cutoffMs)
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "bank_statement_import_items.merchant"
            // U-PRIVACY-01: Redact raw merchant names from bank statement imports past retention window.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                // GR-14u51b: canonical write-barrier admission before the
                // runCatchingCancellable wrapper (see raw_notifications).
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.BankStatementImportItemDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    // GR-08k1 accessor normalization: DAO-named local replaces the
                    // database-chained receiver (GR-08e precedent).
                    val bankStatementImportItemDao = appDatabase.bankStatementImportItemDao()
                    val count = bankStatementImportItemDao.redactMerchantOlderThan(cutoffMs)
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"
                    )
                }
            }
        },

        // ── RP-14 (D14): retention perimeter completion ───────────────────────
        // Numeric name prefixes (10_..50_) guarantee the lexicographic order the
        // worker sorts by — most importantly, transaction-event snapshot nulling
        // (10_) runs before transaction-event row deletion (20_). Each cutoff is
        // a single named constant in DataRetentionWorker.

        object : RetentionTarget {
            override val name = "10_transaction_events.snapshots"
            // P8-001 stage 1 (30d): null beforeSnapshot/afterSnapshot for older
            // events of ALL event types, set-based. Success commits independently
            // of stage 2 (`20_transaction_events.rows`).
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.TransactionEventDao.nullSnapshots")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    val transactionEventDao = appDatabase.transactionEventDao()
                    val count = transactionEventDao.nullSnapshotsOlderThan(cutoffMs)
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}",
                        errorCode = "RETENTION_PURGE_FAILED",
                        errorClass = it::class.simpleName,
                        isTransient = it is android.database.sqlite.SQLiteException ||
                            it is java.io.IOException
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "20_transaction_events.rows"
            // P8-001 stage 2 (365d): hard-delete transaction-event rows. Runs
            // after stage 1; a failure here never rolls back a committed stage 1.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.TransactionEventDao.deleteOlderThan")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    val transactionEventDao = appDatabase.transactionEventDao()
                    val count = transactionEventDao.deleteOlderThan(cutoffMs)
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}",
                        errorCode = "RETENTION_PURGE_FAILED",
                        errorClass = it::class.simpleName,
                        isTransient = it is android.database.sqlite.SQLiteException ||
                            it is java.io.IOException
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "30_operation_runs"
            // P8-003 (90d): one DAO transaction deletes child events then parent
            // runs for terminal runs past the cutoff (RUNNING rows never purged);
            // orphan events are deleted separately. Compound target reports the
            // child/parent/orphan breakdown via detailCounts; rowsPurged stays
            // the total.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.OperationRunDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    val operationRunDao = appDatabase.operationRunDao()
                    val operationRunEventDao = appDatabase.operationRunEventDao()
                    val counts = operationRunDao.purgeTerminalRunsWithEvents(cutoffMs)
                    val orphans = operationRunEventDao.deleteOrphanEventsOlderThan(cutoffMs)
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = counts.childEventsDeleted + counts.parentRunsDeleted + orphans,
                        success = true,
                        detailCounts = mapOf(
                            "childEvents" to counts.childEventsDeleted,
                            "parentRuns" to counts.parentRunsDeleted,
                            "orphanEvents" to orphans
                        )
                    )
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}",
                        errorCode = "RETENTION_PURGE_FAILED",
                        errorClass = it::class.simpleName,
                        isTransient = it is android.database.sqlite.SQLiteException ||
                            it is java.io.IOException
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "40_receipt_events"
            // P8-003 (90d): hard-delete receipt events past the cutoff by
            // occurredAt. Count-only, idempotent.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.ReceiptEventDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    val receiptEventDao = appDatabase.receiptEventDao()
                    val count = receiptEventDao.deleteOlderThan(cutoffMs)
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}",
                        errorCode = "RETENTION_PURGE_FAILED",
                        errorClass = it::class.simpleName,
                        isTransient = it is android.database.sqlite.SQLiteException ||
                            it is java.io.IOException
                    )
                }
            }
        },

        object : RetentionTarget {
            override val name = "50_privacy_audit_events"
            // P8-007 (180d, COMPLIANCE-FLAGGED — see DataRetentionWorker's named
            // constant): bound the privacy-audit accountability ledger. Count-only
            // and purge-safe; the cutoff requires compliance sign-off to change.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                try {
                    writeBarrier.checkWritesAllowed("RetentionModule.PrivacyAuditDao.purge")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "WRITE_BARRIER_BLOCKED",
                        isTransient = true,
                        errorCode = "WRITE_BARRIER_BLOCKED"
                    )
                }
                return CancellationSafe.runCatchingCancellable {
                    val privacyAuditDao = appDatabase.privacyAuditDao()
                    val count = privacyAuditDao.deleteOlderThan(cutoffMs)
                    RetentionPurgeResult(name, count, true)
                }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}",
                        errorCode = "RETENTION_PURGE_FAILED",
                        errorClass = it::class.simpleName,
                        isTransient = it is android.database.sqlite.SQLiteException ||
                            it is java.io.IOException
                    )
                }
            }
        }
    )

    @Provides
    @Singleton
    fun provideRetentionRegistry(targets: Set<@JvmSuppressWildcards RetentionTarget>): RetentionRegistry =
        RetentionRegistry(targets)
}
