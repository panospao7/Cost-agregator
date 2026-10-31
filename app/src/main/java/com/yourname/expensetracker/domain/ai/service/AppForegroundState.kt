package com.yourname.expensetracker.domain.ai.service

/**
 * Platform signal that says whether the app process is currently in the foreground (ai-fixes B2c, spec 1.7).
 *
 * Used by the transaction-insight use case to skip on-device AI work that Android would block while the
 * app is in the background (INV-17). It is a platform signal, not a business rule, and carries no data.
 *
 * Contract: [isAppInForeground] is non-suspending, safe to call from any thread, returns quickly and
 * never throws. Before the first foreground event it returns false, so callers fail safe by skipping.
 */
interface AppForegroundState {
    fun isAppInForeground(): Boolean
}
