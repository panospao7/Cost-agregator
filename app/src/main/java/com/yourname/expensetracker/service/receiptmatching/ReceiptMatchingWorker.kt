package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.receiptmatching.MatchResult
import com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltWorker
class ReceiptMatchingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val receiptRepository: ReceiptRepository,
    private val matcher: ReceiptTransactionMatcher,
    private val notificationService: com.yourname.expensetracker.domain.service.NotificationService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Timber.d("Running automated receipt matching...")
            
            // Find unmatched receipts
            val unmatchedReceipts = receiptRepository.getUnmatchedReceipts()
            
            var autoMatched = 0
            var suggested = 0
            
            for (receipt in unmatchedReceipts) {
                val matchResult = matcher.findBestMatch(receipt)
                
                when (matchResult) {
                    is MatchResult.AutoMatch -> {
                        // Auto-link high confidence matches
                        receiptRepository.linkReceiptToExpense(
                            receiptId = receipt.id,
                            expenseId = matchResult.transaction.id,
                            confidence = matchResult.score
                        )
                        autoMatched++
                        
                        // Notify user of auto-match
                        // HIGH FIX: Use NotificationIdGenerator instead of toInt()
                        notificationService.sendBudgetAlert(
                            notificationId = com.yourname.expensetracker.domain.util.NotificationIdGenerator.forReceipt(receipt.id),
                            title = applicationContext.getString(R.string.receipt_matching_auto_matched_title),
                            message = applicationContext.getString(R.string.receipt_matching_auto_matched_message_format, receipt.parsedMerchant ?: applicationContext.getString(R.string.label_unknown))
                        )
                    }
                    is MatchResult.Suggested -> {
                        // Save as suggestion for manual review
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
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "receipt_matching"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            // Run every 2 hours when app is open
            val request = PeriodicWorkRequestBuilder<ReceiptMatchingWorker>(2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            
            Timber.d("Scheduled receipt matching worker")
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
