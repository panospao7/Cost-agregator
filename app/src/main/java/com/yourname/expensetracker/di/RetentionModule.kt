package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.database.AppDatabase
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
 */
@Module
@InstallIn(SingletonComponent::class)
object RetentionModule {

    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideRetentionTargets(
        appDatabase: AppDatabase,
        timeProvider: TimeProvider
    ): Set<RetentionTarget> = setOf(

        object : RetentionTarget {
            override val name = "raw_notifications"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = CancellationSafe.runCatchingCancellable {
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
        },

        object : RetentionTarget {
            override val name = "scanned_receipts.rawOcrText"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = CancellationSafe.runCatchingCancellable {
                // GR-08k1 accessor normalization: DAO-named local (was `dao`).
                val scannedReceiptDao = appDatabase.scannedReceiptDao()
                var total = 0
                val now = timeProvider.now()
                while (true) {
                    val batch = scannedReceiptDao.getUnpurgedScannedReceiptsOlderThan(cutoffMs, 100)
                    if (batch.isEmpty()) break
                    for (r in batch) scannedReceiptDao.updateRawOcrTextPurged(r.id, now)
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
        },

        object : RetentionTarget {
            override val name = "ai_artifacts"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = CancellationSafe.runCatchingCancellable {
                // GR-08k1 accessor normalization: DAO-named local replaces the
                // database-chained receiver (GR-08e precedent).
                val aiArtifactDao = appDatabase.aiArtifactDao()
                val count = aiArtifactDao.deleteExpired(cutoffMs)
                RetentionPurgeResult(name, count, true)
            }.getOrElse {
                    RetentionPurgeResult(
                        targetName = name,
                        rowsPurged = 0,
                        success = false,
                        errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"
                    )
                }
        },

        object : RetentionTarget {
            override val name = "ai_chat_messages"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = CancellationSafe.runCatchingCancellable {
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
        },

        object : RetentionTarget {
            override val name = "email_receipt_sources"
            // PRIV-43B-12: Redact sensitive fields, do NOT delete rows (preserves dedup hashes/links)
            // cutoffMs is the email-specific cutoff (now - 30 days), passed by DataRetentionWorker
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = CancellationSafe.runCatchingCancellable {
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
        },

        object : RetentionTarget {
            override val name = "notification_intake"
            // P8F-01: Null out raw payload text (title/text/bigText/subText/extrasJson) past
            // the retention window — mirrors the raw_notifications target since intake carries
            // the same captured notification content. cutoffMs is the notification cutoff,
            // passed by DataRetentionWorker.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = CancellationSafe.runCatchingCancellable {
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
        },

        object : RetentionTarget {
            override val name = "pipeline_diagnostic_events"
            // P8F-06: Hard-delete old diagnostic rows (free-text message / exceptionMessage /
            // metadataJson can carry PII). cutoffMs is the diagnostics cutoff, passed by
            // DataRetentionWorker.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = CancellationSafe.runCatchingCancellable {
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
        },

        object : RetentionTarget {
            override val name = "pending_reviews.notificationText"
            // PR5: Redact notification text/title in pending reviews past the notification
            // retention window. Preserves structural fields for review queue functionality.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = CancellationSafe.runCatchingCancellable {
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
        },

        object : RetentionTarget {
            override val name = "background_job_runs.errorMessage"
            // PR5: Redact error messages in background job runs older than 30 days.
            // Error messages may contain PII from exception stack traces.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = CancellationSafe.runCatchingCancellable {
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
        },

        object : RetentionTarget {
            override val name = "bank_statement_import_items.merchant"
            // U-PRIVACY-01: Redact raw merchant names from bank statement imports past retention window.
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = CancellationSafe.runCatchingCancellable {
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
    )

    @Provides
    @Singleton
    fun provideRetentionRegistry(targets: Set<@JvmSuppressWildcards RetentionTarget>): RetentionRegistry =
        RetentionRegistry(targets)
}
