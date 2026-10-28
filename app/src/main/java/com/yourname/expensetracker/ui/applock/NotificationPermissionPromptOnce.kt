package com.yourname.expensetracker.ui.applock

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The once-per-process claim for the POST_NOTIFICATIONS auto-prompt (R2, AE-39; S20-b). `MainActivity` (S20-c)
 * starts the R24 effect with `if (!notificationPromptOnce.tryClaim()) return@LaunchedEffect`, so a shell
 * recomposition, a lock and unlock, or a recreated Activity never prompts a second time in the same process.
 *
 * It is NOT persisted and decides nothing else: R24's `NotificationPromptPolicy` (should the prompt show) and
 * `NotificationPromptStore` (once per install) stay the deciders. This only caps repeats inside one process, and a
 * new process starts unclaimed (the policy and the store still apply then).
 */
@Singleton
class NotificationPermissionPromptOnce @Inject constructor() {

    private val claimed = AtomicBoolean(false)

    /** True for exactly the first caller in this process, false for every later (or concurrent losing) caller. */
    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)
}
