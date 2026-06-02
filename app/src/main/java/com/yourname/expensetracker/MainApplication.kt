package com.yourname.expensetracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.yourname.expensetracker.data.rescue.FinancialRescueCoordinator
import com.yourname.expensetracker.data.rescue.RescueConfig
import com.yourname.expensetracker.startup.AppStartupDelegate
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // ── Financial Rescue Path ──────────────────────────────────────
        // When rescue mode is enabled the coordinator runs on every launch
        // until the flag is set back to false.  After the first successful
        // run the rescue is marked done and subsequent calls are no-ops,
        // but normal startup is still skipped as a safety measure so the
        // user can verify data integrity before re-enabling the app.
        if (RescueConfig.ENABLE_FINANCIAL_RESCUE) {
            val coordinator = FinancialRescueCoordinator(this)
            // Run rescue off the main thread to avoid ANR
            Thread {
                val result = coordinator.runRescueIfNeeded()
                android.util.Log.i("MainApplication",
                    "Financial rescue result: $result")
            }.start()
            return  // Skip normal startup — flag must be set to false manually
        }

        AppStartupDelegate.initialize(this)
    }
}
