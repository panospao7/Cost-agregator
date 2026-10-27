package com.yourname.expensetracker.ui.applock

import androidx.annotation.VisibleForTesting
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import javax.inject.Inject

/**
 * The per-Activity half of the system prompt wiring (S20-a, TECH-1 A). One instance serves one Activity
 * instance: it is injected unscoped into the Activity and [attach]ed once, in `onCreate`, after
 * `super.onCreate`. Nothing calls it yet (the shell integration, S20-c, does); the class is dormant.
 *
 * What it does for its Activity:
 * - Builds the Activity-bound prompt adapter ([BiometricPromptDeviceAuthenticator]) in `onCreate`, as the
 *   adapter's lifecycle contract requires, and registers it with [AppLockAuthenticatorRelay], which is the one
 *   `DeviceAuthenticator` the controller uses.
 * - Rotation: if an attempt was open when the previous Activity was destroyed in a configuration change, the
 *   relay hands it to the new adapter inside the registration (id and the controller's callback), so a matching
 *   success on the recreated Activity is accepted. It never waits for the controller's watchdog.
 * - Host loss: at ON_DESTROY with `isChangingConfigurations == false`, if this host's adapter still holds an
 *   open attempt with no outcome, it calls [AppLockController.onPromptHostLost] exactly once with that id
 *   (T35). The relay removes the attempt record before returning the id, so a second destroy or a late
 *   outcome cannot report or deliver again. A configuration change never reports loss (spec 3.5).
 * - At ON_START it re-asserts its registration as the relay's current one ([AppLockAuthenticatorRelay.activate]),
 *   so when a second Activity instance exists the one that is started again is the one new attempts reach.
 *   This never adopts an attempt.
 * - It clears the relay registration at ON_DESTROY (rule (a)).
 * - The prompt copy is passed through unchanged: the controller selects one constant `AuthPromptCopy` per
 *   attempt and the relay forwards it to the adapter untouched. The host never sees, builds or stores text.
 *
 * Main thread only (it is driven by the Activity lifecycle).
 */
class AppLockPromptHost @VisibleForTesting internal constructor(
    private val relay: AppLockAuthenticatorRelay,
    private val controller: AppLockController,
    private val adapterFactory: (FragmentActivity) -> BiometricPromptDeviceAuthenticator
) {

    @Inject
    constructor(
        relay: AppLockAuthenticatorRelay,
        controller: AppLockController
    ) : this(relay, controller, { activity -> BiometricPromptDeviceAuthenticator(activity) })

    private var registration: RelayRegistration? = null

    /** True between a successful [attach] and the host's ON_DESTROY. Test hook only. */
    @get:VisibleForTesting
    internal val isAttached: Boolean
        get() = registration != null

    /**
     * Binds this host to [activity]. Call once, from `onCreate` after `super.onCreate`. A second call on the same
     * host is a programming error. An Activity that is already destroyed gets no registration, so the relay never
     * holds an adapter that can no longer be cleared.
     */
    fun attach(activity: FragmentActivity) {
        check(registration == null) { "AppLockPromptHost is already attached" }
        if (activity.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        val adapter = adapterFactory(activity)
        val attached = relay.register(adapter) { attemptId, callback -> adapter.adopt(attemptId, callback) }
        registration = attached
        activity.lifecycle.addObserver(
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        // A started host is the visible one: it becomes the relay's current registration again.
                        Lifecycle.Event.ON_START -> relay.activate(attached)
                        Lifecycle.Event.ON_DESTROY -> {
                            source.lifecycle.removeObserver(this)
                            onHostDestroyed(attached, activity.isChangingConfigurations)
                        }
                        else -> Unit
                    }
                }
            }
        )
    }

    private fun onHostDestroyed(attached: RelayRegistration, isChangingConfigurations: Boolean) {
        registration = null
        // The relay clears the registration, removes the attempt record and returns its id only for a real loss.
        val lostAttemptId = relay.unregister(attached, isChangingConfigurations) ?: return
        controller.onPromptHostLost(lostAttemptId)
    }
}
