package com.yourname.expensetracker.ui.applock

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.HeldLinkRelease
import com.yourname.expensetracker.domain.privacy.applock.LinkDecision
import com.yourname.expensetracker.ui.MainViewModel
import com.yourname.expensetracker.ui.navigation.DeepLinkApplier
import com.yourname.expensetracker.ui.navigation.DeepLinkPolicyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * S21-b (PRV-16, INV-14, INV-30, INV-31): the one place where a validated deep link meets the app-lock controller.
 * Unscoped, so every Activity instance gets its own router; the held link itself lives in the process-wide
 * controller, in memory only, so a router created after a rotation still releases a link the old one held.
 *
 * ## Arrival ([route])
 *
 * - An accepted link asks the controller for a decision. APPLY_NOW hands the link to [DeepLinkApplier] now;
 *   HOLD only reports the dropped parameters (nothing is looked up, written or navigated while the app is closed).
 * - A rejected link goes to the applier's normal path (Home, or ignored for a non-VIEW action) only when the app is
 *   `DISABLED`, `UNLOCKED` or `INERT`. While it is closed the applier reports the bounded rejection code and
 *   does not navigate, and a link already held is left alone.
 *
 * ## Release ([startReleasing])
 *
 * While the owner is STARTED it watches the controller state together with the controller's release signal. For
 * every emission with an open state it posts one main-thread task (`Dispatchers.Main`, never run inline), so the
 * release is never asked for from inside the publication that triggered it. The task
 * asks the controller, which hands a held link back at most once: an applied link goes to the applier, an expired
 * one opens Home, and nothing is done otherwise (a locked, resolving or backgrounded app keeps the link held).
 *
 * Nothing here holds, logs or persists link content: the link is the controller's validated request, never raw text.
 */
class AppLockDeepLinkRouter @Inject constructor(
    private val controller: AppLockController,
    private val applier: DeepLinkApplier
) {

    /**
     * Routes one policy outcome. Must be called on the main thread. [scope] runs an apply that is due now (the
     * Activity's lifecycle scope in production).
     */
    fun route(result: DeepLinkPolicyResult, scope: CoroutineScope, mainViewModel: MainViewModel) {
        when (result) {
            DeepLinkPolicyResult.NotADeepLink -> Unit
            is DeepLinkPolicyResult.Accepted -> when (controller.onLockQuery(result.request)) {
                LinkDecision.APPLY_NOW -> scope.launch { applier.apply(result, mainViewModel) }
                LinkDecision.HOLD -> applier.onHeld(result)
            }
            is DeepLinkPolicyResult.Rejected -> when (controller.onLockQuery()) {
                is AppLockState.Disabled,
                is AppLockState.Unlocked,
                is AppLockState.Inert -> scope.launch { applier.apply(result, mainViewModel) }
                is AppLockState.Resolving,
                is AppLockState.Locked,
                is AppLockState.Authenticating -> applier.onRejectedWhileClosed(result)
            }
        }
    }

    /**
     * Starts watching for a release opportunity for [owner]'s lifetime (once per Activity instance, from `onCreate`).
     * The watch runs only while [owner] is STARTED and restarts at the next start, which emits the current state
     * again: that is the release opportunity at ON_START. A release task already posted survives a stop; the
     * controller then answers "nothing" because the app is not in the foreground, and the link stays held.
     */
    fun startReleasing(owner: LifecycleOwner, mainViewModel: MainViewModel) {
        val ownerScope = owner.lifecycleScope
        ownerScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(controller.state, controller.releaseOpportunity) { state, _ -> state }
                    .collect { state ->
                        if (state.isOpen()) {
                            ownerScope.launch(Dispatchers.Main) {
                                when (val release = controller.releaseHeldLink()) {
                                    is HeldLinkRelease.Apply -> applier.applyReleased(release, mainViewModel)
                                    HeldLinkRelease.Expired -> applier.applyExpired(mainViewModel)
                                    HeldLinkRelease.None -> Unit
                                }
                            }
                        }
                    }
            }
        }
    }

    private fun AppLockState.isOpen(): Boolean =
        this is AppLockState.Disabled || this is AppLockState.Unlocked || this is AppLockState.Inert
}
