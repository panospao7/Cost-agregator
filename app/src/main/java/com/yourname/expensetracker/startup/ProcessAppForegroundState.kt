package com.yourname.expensetracker.startup

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.yourname.expensetracker.domain.ai.service.AppForegroundState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-level foreground flag (ai-fixes B2c, spec 1.7).
 *
 * Registered on the process lifecycle only by [AppStartupCoordinator.initialize], next to
 * [AppBackgroundLifecycleObserver]. onStart sets the flag, onStop clears it. Repeated events are
 * idempotent. Until the first onStart the flag is false (fail-safe: callers skip).
 */
@Singleton
class ProcessAppForegroundState @Inject constructor() : AppForegroundState, DefaultLifecycleObserver {

    @Volatile
    private var foreground: Boolean = false

    override fun isAppInForeground(): Boolean = foreground

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
    }
}
