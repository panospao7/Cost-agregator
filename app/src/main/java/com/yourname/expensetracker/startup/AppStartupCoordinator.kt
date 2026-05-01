package com.yourname.expensetracker.startup

import android.app.Application
import android.os.StrictMode
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.data.location.LocationBackfillWorker
import com.yourname.expensetracker.data.location.MerchantKeyBackfillWorker
import com.yourname.expensetracker.data.privacy.DataRetentionWorker
import com.yourname.expensetracker.domain.ai.usecase.SyncProactiveBriefingWorkUseCase
import com.yourname.expensetracker.service.warranty.WarrantyExpirationWorker
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStartupCoordinator @Inject constructor(
    private val backgroundLifecycleObserver: AppBackgroundLifecycleObserver,
    private val syncProactiveBriefingWorkUseCase: SyncProactiveBriefingWorkUseCase
) {

    fun initialize(application: Application) {
        configureDebugTools()
        registerLifecycleObserver()
        scheduleStartupWork(application)
        syncProactiveBriefingWork()
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
    }

    private fun syncProactiveBriefingWork() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            syncProactiveBriefingWorkUseCase()
        }
    }
}
