package com.yourname.expensetracker.service.warranty

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.NotificationIdGenerator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * HIGH FIX (HIGH-4): Uses NotificationIdGenerator to prevent integer overflow.
 * 
 * Replaces direct toInt() conversion which could overflow for large warranty IDs.
 */
@HiltWorker
class WarrantyExpirationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val warrantyRepository: WarrantyTrackerRepository,
    private val notificationService: NotificationService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Timber.d("Checking for expiring warranties...")
            
            // Check warranties expiring in 7 days
            val expiringIn7Days = warrantyRepository.getWarrantiesExpiringSoon(7)
            expiringIn7Days.forEach { warranty ->
                // HIGH FIX: Use NotificationIdGenerator instead of toInt()
                notificationService.sendBudgetAlert(
                    notificationId = NotificationIdGenerator.forWarranty(warranty.id, 7),
                    title = "⚠️ Warranty Expiring Soon",
                    message = "${warranty.productName} warranty expires in 7 days (${warranty.merchantName})"
                )
            }
            
            // Check warranties expiring in 30 days (less urgent)
            val expiringIn30Days = warrantyRepository.getWarrantiesExpiringSoon(30)
                .filter { it !in expiringIn7Days } // Don't notify twice
            expiringIn30Days.forEach { warranty ->
                // HIGH FIX: Use NotificationIdGenerator with different offset for 30-day alerts
                notificationService.sendBudgetAlert(
                    notificationId = NotificationIdGenerator.forWarranty(warranty.id, 30),
                    title = "📅 Warranty Expiration Reminder",
                    message = "${warranty.productName} warranty expires in 30 days"
                )
            }
            
            Timber.d("Warranty check complete. Found ${expiringIn7Days.size} expiring in 7 days, ${expiringIn30Days.size} in 30 days")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Error checking warranty expirations")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "warranty_expiration_check"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<WarrantyExpirationWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            
            Timber.d("Scheduled warranty expiration worker")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
