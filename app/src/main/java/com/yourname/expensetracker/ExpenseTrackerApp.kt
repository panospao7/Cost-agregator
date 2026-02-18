package com.yourname.expensetracker

import android.app.Application
import android.os.StrictMode
import androidx.lifecycle.ProcessLifecycleOwner
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ExpenseTrackerApp : Application() {
    
    @Inject
    lateinit var transactionClassifier: TransactionClassifier
    
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
        
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleObserver(transactionClassifier))
    }
}

class LifecycleObserver(
    private val transactionClassifier: TransactionClassifier
) : androidx.lifecycle.DefaultLifecycleObserver {
    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
        super.onStop(owner)
        transactionClassifier.cleanup()
    }
}
