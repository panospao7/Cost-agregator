package com.yourname.expensetracker.service.receiptmatching

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.receipt.ReceiptDocumentType
import com.yourname.expensetracker.domain.receipt.ReceiptProcessingStatus
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import com.yourname.expensetracker.domain.workers.WorkerSpec
import com.yourname.expensetracker.domain.workers.WorkerSpecScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class ReceiptMatchingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val receiptRepository: ReceiptRepository,
    private val matcher: ReceiptTransactionMatcher,
    private val receiptLinkService: ReceiptLinkService,
    private val notificationService: com.yourname.expensetracker.domain.service.NotificationService,
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Defense-in-depth: block writes during restore maintenance mode
        if (!restoreMaintenanceMode.isWritesAllowed()) {
            Timber.w("Writes blocked during restore mode, skipping")
            return Result.success()
        }

        // WorkerSpec gate: check if this worker is enabled
        val spec = WorkerSpec.DEFAULTS[WORK_NAME] ?: return Result.success()
        if (!spec.enabled) {
            Timber.w("Worker $WORK_NAME disabled by spec, skipping")
            return Result.success()
        }

        return try {
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
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Error in receipt matching worker")
            if (e.isPermanentReceiptMatchingFailure()) {
                Result.failure()
            } else {
                Result.retry()
            }
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

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReceiptMatchingWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
