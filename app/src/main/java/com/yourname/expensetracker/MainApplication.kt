package com.yourname.expensetracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.yourname.expensetracker.data.rescue.RescueConfig
import com.yourname.expensetracker.domain.workers.WorkerGuardVerifier
import com.yourname.expensetracker.startup.AppStartupDelegate
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var workerGuardVerifier: WorkerGuardVerifier

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // ── PR10: Debug-only static guard verification ─────────────────
        // Fails fast if any CoroutineWorker is unguarded so the developer
        // catches registration gaps immediately, not just in CI.
        if (BuildConfig.DEBUG) {
            val result = workerGuardVerifier.verifyAllWorkersGuarded()
            if (result is WorkerGuardVerifier.VerificationResult.Failure) {
                throw IllegalStateException(
                    "Unguarded workers detected at startup: ${result.unguardedWorkers}. " +
                        "Every CoroutineWorker must be registered in WorkerGuardVerifier.workerRegistry " +
                        "and its work name must appear in WorkerSpecScheduler.listAllWorkerNames()."
                )
            }
        }

        // ── Financial Rescue Path ──────────────────────────────────────
        // When rescue mode is enabled normal startup is skipped so the user
        // can launch RescueActivity manually (e.g. via ADB) to trigger the
        // one-time DB recovery.  The rescue itself runs ONLY from the
        // Activity, not here.
        if (RescueConfig.ENABLE_FINANCIAL_RESCUE) {
            android.util.Log.i("MainApplication", "Financial rescue mode enabled; normal startup skipped")
            return
        }

        AppStartupDelegate.initialize(this)
    }
}
