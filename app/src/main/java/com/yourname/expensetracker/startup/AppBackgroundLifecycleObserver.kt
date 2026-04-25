package com.yourname.expensetracker.startup

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import dagger.Lazy
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppBackgroundLifecycleObserver @Inject constructor(
    private val transactionClassifier: Lazy<TransactionClassifier>,
    private val budgetMonitor: Lazy<BudgetMonitor>
) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)

        try {
            transactionClassifier.get().onBackground()
            budgetMonitor.get().onBackground()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Timber.e(e, "Error during cleanup")
            }
        }
    }
}
