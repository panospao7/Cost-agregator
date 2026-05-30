package com.yourname.expensetracker.service.receiptmatching

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptAlreadyClaimedException
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardRequest
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import com.yourname.expensetracker.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class ReceiptMatchingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val receiptRepository: ReceiptRepository,
    private val matcher: ReceiptTransactionMatcher,
    private val receiptLinkService: ReceiptLinkService,
    private val matchService: com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptMatchLifecycleService,
    private val notificationService: com.yourname.expensetracker.domain.service.NotificationService,
    private val executionGuard: WorkerExecutionGuard
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val guardResult = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "receipt_matching",
                allowDuringBackupExport = false
            )
        ) { ctx ->
            try {
                Timber.d("Running automated receipt matching...")

                // Find receipts eligible for matching (UNMATCHED and SUGGESTED)
                val unmatchedReceipts = receiptRepository.getProcessableReceipts()

                var autoMatched = 0
                var suggested = 0

                for (receipt in unmatchedReceipts) {
                    ctx.checkpoint("receipt_matching")
                    ctx.addRowsScanned()
                    // Document-type gating: skip incompatible receipts
                    if (receipt.documentType == ReceiptDocumentType.BANK_STATEMENT.name ||
                        receipt.documentType == ReceiptDocumentType.MANUAL_PLACEHOLDER.name ||
                        receipt.processingStatus == ReceiptProcessingStatus.OCR_FAILED.name) {
                        Timber.d("Skipping receipt matching for receipt ${receipt.id}: documentType=${receipt.documentType}, processingStatus=${receipt.processingStatus}")
                        // P9-P1-08: emit a durable event for the previously-silent skip path
                        safeRecordMatchEvent("MATCH_SKIPPED_DOCUMENT_TYPE for receipt ${receipt.id}") {
                            matchService.recordMatchSkippedDocumentType(receipt.id, receipt.documentType)
                        }
                        ctx.addRowsSkipped()
                        continue
                    }

                    // P9-P1-08: record the match attempt before invoking the matcher
                    safeRecordMatchEvent("MATCH_ATTEMPTED for receipt ${receipt.id}") {
                        matchService.recordMatchAttempted(receipt.id, LOOKBACK_DAYS)
                    }
                    val matchResult = matcher.findBestMatch(receipt, LOOKBACK_DAYS)

                    when (matchResult) {
                        is MatchResult.AutoMatch -> {
                            val linkResult = receiptLinkService.linkReceiptToExpense(
                                receiptId = receipt.id,
                                expenseId = matchResult.transaction.id,
                                linkType = "AUTO_MATCH",
                                source = "MATCHING_WORKER",
                                confidence = matchResult.score.toFloat(),
                                // S6 (P9-P1-07 / NEW-07): atomic compare-and-set claim so an
                                // overlapping run (periodic "receipt_matching" vs. manual
                                // "receipt_matching_run_once") cannot double auto-link the
                                // same receipt. The lease registry is NOT exclusive per-name,
                                // so this per-receipt claim — not the lease — is the load-bearing
                                // safety net.
                                requireUnmatchedClaim = true
                            )
                            if (linkResult.isSuccess) {
                                autoMatched++
                                ctx.addRowsUpdated()
                                notificationService.sendBudgetAlert(
                                    notificationId = com.yourname.expensetracker.domain.util.NotificationIdGenerator.forReceipt(receipt.id),
                                    title = applicationContext.getString(R.string.receipt_matching_auto_matched_title),
                                    message = applicationContext.getString(R.string.receipt_matching_auto_matched_message_format, receipt.parsedMerchant ?: applicationContext.getString(R.string.label_unknown))
                                )
                                ctx.addNotificationsSent()
                            } else {
                                val linkError = linkResult.exceptionOrNull()
                                if (linkError is ReceiptAlreadyClaimedException) {
                                    // S6: a concurrent run already claimed this receipt. This is
                                    // a benign no-op, NOT a link failure: skip the notification
                                    // and do NOT emit AUTO_MATCH_LINK_FAILED (that would be a
                                    // false positive). The other run owns the auto-match + alert.
                                    Timber.d("Receipt ${receipt.id} already claimed by a concurrent matching run; skipping")
                                } else {
                                    val failureMessage = linkError?.message
                                    Timber.w("Auto-match link failed for receipt ${receipt.id}: $failureMessage")
                                    // P9-P1-08: emit a durable event in addition to the log
                                    safeRecordMatchEvent("AUTO_MATCH_LINK_FAILED for receipt ${receipt.id}") {
                                        matchService.recordAutoMatchLinkFailed(
                                            receiptId = receipt.id,
                                            expenseId = matchResult.transaction.id,
                                            reason = failureMessage
                                        )
                                    }
                                }
                            }
                        }
                        is MatchResult.Suggested -> {
                            // Save as suggestion for manual review (no link service call for suggestions)
                            matchService.saveMatchSuggestion(
                                receiptId = receipt.id,
                                suggestedExpenseId = matchResult.transaction.id,
                                confidence = matchResult.score
                            )
                            suggested++
                            ctx.addRowsUpdated()
                        }
                        else -> {
                            // P9-P1-08: emit a durable event for the previously-silent no-match path
                            safeRecordMatchEvent("MATCH_NOT_FOUND for receipt ${receipt.id}") {
                                matchService.recordMatchNotFound(receipt.id)
                            }
                        }
                    }
                }

                Timber.d("Receipt matching complete. Auto-matched: $autoMatched, Suggested: $suggested")
            } catch (e: Exception) {
                Timber.e(e, "Error in receipt matching worker")
                throw e
            }
        }

        return guardResult.toWorkerResult()
    }

    /**
     * P9-P1-08: Runs a diagnostics-event write and isolates its failure from the
     * matching run. An event-log write must never abort matching, so any failure
     * is logged and swallowed here. [kotlinx.coroutines.CancellationException] is
     * rethrown to preserve structured concurrency. This guard wraps ONLY the
     * diagnostics-event writes — real matching/link calls remain unprotected so
     * their failures still propagate.
     */
    private suspend fun safeRecordMatchEvent(description: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to record receipt match diagnostics event: $description")
        }
    }

    private fun Throwable.isPermanentReceiptMatchingFailure(): Boolean {
        if (this is IllegalArgumentException) return true
        if (this !is IllegalStateException) return false

        val normalizedMessage = message?.lowercase().orEmpty()
        return normalizedMessage.contains("conflict") ||
            normalizedMessage.contains("already matched") ||
            normalizedMessage.contains("malformed") ||
            normalizedMessage.contains("invalid") ||
            normalizedMessage.contains("inconsistent")
    }

    companion object {
        private const val WORK_NAME = "receipt_matching"

        /**
         * Lookback window (days) for candidate expense search during receipt matching.
         *
         * Receipts are matched against expenses within ±[LOOKBACK_DAYS] from the
         * receipt date. Configurable here and potentially via [WorkerSpec] in the future.
         */
        private const val LOOKBACK_DAYS = 7

        /**
         * Enqueue a periodic receipt-matching job.
         * Reads interval and constraints from [WorkerSpec.DEFAULTS] for the canonical config.
         */
        fun schedule(context: Context) {
            WorkerSpecScheduler.scheduleFromSpec(context, WORK_NAME, ReceiptMatchingWorker::class.java)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Enqueue a one-shot manual receipt-matching run.
         *
         * ## S6 overlap contract (P9-P1-07 / NEW-07)
         *
         * This one-shot uses the unique name `"${WORK_NAME}_run_once"` while the
         * periodic job uses `"$WORK_NAME"`. WorkManager keeps one-time
         * ([enqueueUniqueWork]) and periodic ([enqueueUniquePeriodicWork], used by
         * [schedule]) work in **separate namespaces**, so sharing a name string does
         * NOT serialize the two — the manual one-shot and the periodic worker CAN run
         * concurrently. Additionally, [WorkerLeaseRegistry] is NOT mutually exclusive
         * per worker name: `acquire(name)` simply records the lease in a map and
         * returns immediately, so acquiring `"$WORK_NAME"` twice does not block.
         *
         * Therefore overlap is made **safe at the data layer** rather than prevented:
         * the AUTO_MATCH path links via
         * [ReceiptLinkService.linkReceiptToExpense] with `requireUnmatchedClaim = true`,
         * an atomic compare-and-set ([ScannedReceiptDao.claimForAutoMatch]) that only
         * transitions a receipt while it is still UNMATCHED/SUGGESTED. If two runs race
         * on the same receipt, exactly one claim succeeds; the loser observes a
         * [ReceiptAlreadyClaimedException], skips its notification, and does not emit a
         * false AUTO_MATCH_LINK_FAILED event. This per-receipt claim is the
         * load-bearing fix.
         *
         * As defense in depth against *stacked manual taps*, this one-shot uses
         * [ExistingWorkPolicy.KEEP]: repeated taps while a manual run is already
         * enqueued/running are ignored rather than queued up.
         *
         * Decision: adding a dedicated `MATCHING` value to
         * [com.yourname.expensetracker.data.database.entity.MatchStatus] is **deferred**
         * — overlap is handled by the atomic per-receipt claim plus this KEEP policy,
         * avoiding the broad churn to `getProcessableReceipts` and its many call sites
         * that a new enum state would require.
         */
        fun runOnce(context: Context) {
            val spec = com.yourname.expensetracker.domain.workers.WorkerSpec.DEFAULTS[WORK_NAME]
            val constraints = spec?.constraints ?: Constraints.NONE
            val backoffPolicy = spec?.backoffPolicy ?: BackoffPolicy.EXPONENTIAL
            val backoffDelay = spec?.backoffDelaySeconds ?: 600L
            val request = OneTimeWorkRequestBuilder<ReceiptMatchingWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(backoffPolicy, backoffDelay, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_run_once",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
