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
    private val notificationService: com.yourname.expensetracker.domain.service.NotificationService,
    private val executionGuard: WorkerExecutionGuard
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val guardResult = executionGuard.runGuarded(
            WorkerGuardRequest(
                workerName = "receipt_matching",
                allowDuringBackupExport = false
            )
        ) {
            try {
                Timber.d("Running automated receipt matching...")

                // Find receipts eligible for matching (UNMATCHED and SUGGESTED)
                val unmatchedReceipts = receiptRepository.getProcessableReceipts()

                var autoMatched = 0
                var suggested = 0

                for (receipt in unmatchedReceipts) {
                    // Document-type gating: skip incompatible receipts
                    if (receipt.documentType == ReceiptDocumentType.BANK_STATEMENT.name ||
                        receipt.documentType == ReceiptDocumentType.MANUAL_PLACEHOLDER.name ||
                        receipt.processingStatus == ReceiptProcessingStatus.OCR_FAILED.name) {
                        Timber.d("Skipping receipt matching for receipt ${receipt.id}: documentType=${receipt.documentType}, processingStatus=${receipt.processingStatus}")
                        continue
                    }

                    val matchResult = matcher.findBestMatch(receipt, LOOKBACK_DAYS)

                    when (matchResult) {
                        is MatchResult.AutoMatch -> {
                            val linkResult = receiptLinkService.linkReceiptToExpense(
                                receiptId = receipt.id,
                                expenseId = matchResult.transaction.id,
                                linkType = "AUTO_MATCH",
                                source = "MATCHING_WORKER",
                                confidence = matchResult.score.toFloat()
                            )
                            if (linkResult.isSuccess) {
                                autoMatched++
                                notificationService.sendBudgetAlert(
                                    notificationId = com.yourname.expensetracker.domain.util.NotificationIdGenerator.forReceipt(receipt.id),
                                    title = applicationContext.getString(R.string.receipt_matching_auto_matched_title),
                                    message = applicationContext.getString(R.string.receipt_matching_auto_matched_message_format, receipt.parsedMerchant ?: applicationContext.getString(R.string.label_unknown))
                                )
                            } else {
                                Timber.w("Auto-match link failed for receipt ${receipt.id}: ${linkResult.exceptionOrNull()?.message}")
                            }
                        }
                        is MatchResult.Suggested -> {
                            // Save as suggestion for manual review (no link service call for suggestions)
                            receiptRepository.saveMatchSuggestion(
                                receiptId = receipt.id,
                                suggestedExpenseId = matchResult.transaction.id,
                                confidence = matchResult.score
                            )
                            suggested++
                        }
                        else -> {
                            // No match found, do nothing
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
