package com.yourname.expensetracker.startup

import android.app.Application
import android.os.StrictMode
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.data.backup.RestoreJournal
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.location.LocationBackfillWorker
import com.yourname.expensetracker.data.location.MerchantKeyBackfillWorker
import com.yourname.expensetracker.data.privacy.DataRetentionWorker
import com.yourname.expensetracker.domain.ai.usecase.SyncProactiveBriefingWorkUseCase
import com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorker
import com.yourname.expensetracker.service.reminder.BillReminderWorker
import com.yourname.expensetracker.service.warranty.WarrantyExpirationWorker
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStartupCoordinator @Inject constructor(
    private val backgroundLifecycleObserver: AppBackgroundLifecycleObserver,
    private val syncProactiveBriefingWorkUseCase: SyncProactiveBriefingWorkUseCase,
    private val restoreJournal: RestoreJournal,
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {

    fun initialize(application: Application) {
        configureDebugTools()
        checkRestoreJournal()
        registerLifecycleObserver()
        scheduleStartupWork(application)
        syncProactiveBriefingWork()
    }

    /**
     * Checks for a pending restore journal on startup and handles crash recovery.
     */
    private fun checkRestoreJournal() {
        when (val recovery = restoreJournal.checkAndRecover()) {
            is RestoreJournal.RecoveryResult.NoAction -> {
                // Normal startup — no journal found
            }

            is RestoreJournal.RecoveryResult.CompleteClean -> {
                Timber.w("Startup: found completed restore journal, cleaning up")
            }

            is RestoreJournal.RecoveryResult.CleanedNonDestructive -> {
                val state = recovery.entry.state
                Timber.w("Startup: cleaned up from non-destructive restore state: %s", state)
            }

            is RestoreJournal.RecoveryResult.RecoveredFromSwap -> {
                Timber.e("Startup: detected incomplete restore from state: %s", recovery.entry.state)
                // Don't block startup — the journal recovery is best-effort.
                // The db may need manual recovery.
            }

            is RestoreJournal.RecoveryResult.CriticalRecoveryRequired -> {
                Timber.e("Startup: CRITICAL — safety backup and live DB are both corrupt")
            }
        }

        // Reset maintenance mode to NORMAL on startup if it was left in a non-restart state
        if (restoreMaintenanceMode.currentMode() != RestoreMaintenanceMode.Mode.NORMAL) {
            Timber.w("Startup: resetting maintenance mode from %s to NORMAL", restoreMaintenanceMode.currentMode())
            restoreMaintenanceMode.reset()
        }
    }

    private fun configureDebugTools() {
        if (!BuildConfig.DEBUG) return

        if (Timber.treeCount == 0) {
            Timber.plant(Timber.DebugTree())
        }

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
    }

    private fun registerLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundLifecycleObserver)
    }

    private fun scheduleStartupWork(application: Application) {
        LocationBackfillWorker.schedule(application)
        MerchantKeyBackfillWorker.schedule(application)
        WarrantyExpirationWorker.schedule(application)
        DataRetentionWorker.schedule(application)
        BillReminderWorker.schedule(application)
        ReceiptMatchingWorker.schedule(application)
    }

    private fun syncProactiveBriefingWork() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            syncProactiveBriefingWorkUseCase()
        }
    }
}
