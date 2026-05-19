package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.privacy.RetentionPurgeResult
import com.yourname.expensetracker.domain.privacy.RetentionRegistry
import com.yourname.expensetracker.domain.privacy.RetentionTarget
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
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = runCatching {
                val dao = appDatabase.rawNotificationDao()
                var total = 0
                val now = timeProvider.now()
                while (true) {
                    val batch = dao.getUnpurgedRawNotificationsOlderThan(cutoffMs, 100)
                    if (batch.isEmpty()) break
                    for (n in batch) {
                        dao.updateRawContentPurged(
                            id = n.id, rawContentPurgedAt = now,
                            title = null, text = null, bigText = null,
                            subText = null, extrasJson = null, parseResult = null
                        )
                    }
                    total += batch.size
                }
                RetentionPurgeResult(name, total, true)
            }.getOrElse { RetentionPurgeResult(name, 0, false, it.message) }
        },

        object : RetentionTarget {
            override val name = "scanned_receipts.rawOcrText"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = runCatching {
                val dao = appDatabase.scannedReceiptDao()
                var total = 0
                val now = timeProvider.now()
                while (true) {
                    val batch = dao.getUnpurgedScannedReceiptsOlderThan(cutoffMs, 100)
                    if (batch.isEmpty()) break
                    for (r in batch) dao.updateRawOcrTextPurged(r.id, now)
                    total += batch.size
                }
                RetentionPurgeResult(name, total, true)
            }.getOrElse { RetentionPurgeResult(name, 0, false, it.message) }
        },

        object : RetentionTarget {
            override val name = "ai_artifacts"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = runCatching {
                // Note: AiArtifactDao.deleteExpired does not return count; report 0 as best-effort
                appDatabase.aiArtifactDao().deleteExpired(cutoffMs)
                RetentionPurgeResult(name, 0, true)
            }.getOrElse { RetentionPurgeResult(name, 0, false, it.message) }
        },

        object : RetentionTarget {
            override val name = "ai_chat_messages"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = runCatching {
                // Note: AiChatMessageDao.deleteOlderThan does not return count; report 0 as best-effort
                appDatabase.aiChatMessageDao().deleteOlderThan(cutoffMs)
                RetentionPurgeResult(name, 0, true)
            }.getOrElse { RetentionPurgeResult(name, 0, false, it.message) }
        },

        object : RetentionTarget {
            override val name = "email_receipt_sources"
            // PRIV-43B-12: Redact sensitive fields, do NOT delete rows (preserves dedup hashes/links)
            // cutoffMs is the email-specific cutoff (now - 30 days), passed by DataRetentionWorker
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult = runCatching {
                val count = appDatabase.emailReceiptDao().redactSensitiveFieldsOlderThan(cutoffMs)
                RetentionPurgeResult(name, count, true)
            }.getOrElse { RetentionPurgeResult(name, 0, false, it.message) }
        }
    )

    @Provides
    @Singleton
    fun provideRetentionRegistry(targets: Set<@JvmSuppressWildcards RetentionTarget>): RetentionRegistry =
        RetentionRegistry(targets)
}
