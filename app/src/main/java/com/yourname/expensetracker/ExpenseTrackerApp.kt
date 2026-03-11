package com.yourname.expensetracker

import android.app.Application
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.data.location.LocationBackfillWorker
import com.yourname.expensetracker.data.location.MerchantKeyBackfillWorker
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ExpenseTrackerApp : Application(), Configuration.Provider {
    
    @Inject
    lateinit var transactionClassifier: TransactionClassifier
    
    @Inject
    lateinit var budgetMonitor: BudgetMonitor

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // WorkManager requires this when using Hilt-injected workers
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    
    override fun onCreate() {
        super.onCreate()
        
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            
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
        
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleObserver(transactionClassifier, budgetMonitor)
        )

        // Schedule the background geocoding backfill (runs on Wi-Fi every 6 hrs)
        LocationBackfillWorker.schedule(this)

        // Schedule the one-time merchantKey column backfill for pre-v32 rows
        MerchantKeyBackfillWorker.schedule(this)
    }
}

class LifecycleObserver(
    private val transactionClassifier: TransactionClassifier,
    private val budgetMonitor: BudgetMonitor
) : androidx.lifecycle.DefaultLifecycleObserver {
    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
        super.onStop(owner)
        try {
            transactionClassifier.cleanup()
            budgetMonitor.cleanup()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Timber.e(e, "Error during cleanup")
            }
        }
    }
}
