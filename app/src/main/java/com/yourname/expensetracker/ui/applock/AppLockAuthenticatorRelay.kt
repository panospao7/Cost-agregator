package com.yourname.expensetracker.ui.applock

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.yourname.expensetracker.domain.privacy.applock.AuthLaunch
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCallback
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthErrorBucket
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthenticator
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands the open attempt to a freshly created adapter (the Activity-bound prompt adapter, after a
 * rotation). Returns true only when the adapter took the attempt over; see
 * `BiometricPromptDeviceAuthenticator.adopt`.
 */
internal fun interface AttemptAdopter {
    fun adopt(attemptId: Long, callback: DeviceAuthCallback): Boolean
}

/**
 * One prompt host's registration with the relay. The prompt host owns it strongly (it lives as long as the
 * Activity); the relay holds it only weakly (rule (a)), so the process-level relay can never keep an
 * Activity alive. Opaque to everything except [AppLockAuthenticatorRelay].
 */
internal class RelayRegistration internal constructor(
    internal val adapter: DeviceAuthenticator,
    internal val adopter: AttemptAdopter
)

/**
 * The process-level [DeviceAuthenticator] the app-lock controller talks to (S20-a, TECH-1 A). The real prompt
 * adapter is bound to one Activity instance, so it cannot be a singleton; this relay is the one binding for
 * the port and forwards to whichever adapter the current prompt host registered.
 *
 * ## Rules (handoff S16 binding, relay (a) to (e))
 *
 * - (a) The adapter is held weakly and the registration is cleared at the host's ON_DESTROY ([unregister]).
 * - (b) With no registered adapter, [authenticate] returns [AuthLaunch.HOST_UNAVAILABLE] and no callback follows.
 * - (c) [cancel] goes to the adapter that launched or adopted that id. If that adapter is gone (its host was
 *   destroyed in a configuration change and nobody adopted yet, or the reference was collected) the relay
 *   delivers `Failed(SYSTEM_CANCELED)` itself, once and synchronously, so a cancel never leaves a callback owed.
 * - (d) Host loss is reported only from the registration that holds the attempt, only when the host was not
 *   changing configurations, and exactly once: [unregister] removes the attempt record first and returns the id
 *   exactly one time, so a late outcome for that id finds no record and is dropped. The prompt host (not the
 *   relay) then tells the controller.
 * - (e) The relay is reachable only through DI (the single `DeviceAuthenticator` binding the controller
 *   consumes) and through the prompt host, which is the only caller of [register] and [unregister]. No worker,
 *   receiver or service may touch it (pinned by a source scan).
 *
 * ## Open attempt record
 *
 * The relay records the open attempt (id, the controller's callback, the holding registration) BEFORE it calls
 * the adapter, because the adapter may deliver an outcome re-entrantly inside `authenticate`. Every outcome the
 * adapter reports passes through a relay-owned callback bound to (record, registration): it is forwarded to the
 * controller at most once, only while the record is still open, the id matches and that registration still
 * holds the attempt. A repeated, late or stale outcome (for example from an adapter that lost the attempt in a
 * rotation) is dropped. At most one attempt is open at a time (INV-34): a second [authenticate] while a record
 * is open returns [AuthLaunch.ALREADY_IN_FLIGHT].
 *
 * ## Rotation (adoption, never the watchdog)
 *
 * [unregister] with `isChangingConfigurations == true` keeps the record but orphans it (no holder). The next
 * [register] adopts an orphaned record at once through the new adapter's [AttemptAdopter], so the recreated
 * Activity can accept the matching success. A registration never adopts a record whose holder is still alive
 * (a newer Activity created before the older one is destroyed, for example a CLEAR_TASK launch). If the new
 * adapter refuses the adoption the relay closes the attempt itself with `Failed(SYSTEM_CANCELED)`.
 *
 * Orphaning also posts one check to the main looper. A relaunch (destroy of the old instance, creation of the
 * new one and its [register]) runs inside one main-thread message, so by the time the check runs a real
 * adoption has already happened and the check does nothing. If the recreated instance never attached, the check
 * closes the still-orphaned record with `Failed(SYSTEM_CANCELED)`, once, instead of leaving it to the
 * controller's watchdog. Each orphaning carries its own sequence number, so a stale check can never close a
 * later orphaning.
 *
 * ## Current registration
 *
 * [register] makes the new registration current; [activate] lets a host that is started again re-assert itself
 * as current (a second Activity instance can otherwise leave an older, visible one unable to launch). [activate]
 * never adopts anything.
 *
 * Main thread only: every entry point other than the outcome callback checks it and fails with an
 * [IllegalStateException] otherwise (a bounded programming error; the controller already hops to the main
 * dispatcher and logs only the class name of an exception).
 */
@Singleton
class AppLockAuthenticatorRelay @VisibleForTesting internal constructor(
    private val onMainThread: () -> Boolean,
    private val postToMain: (Runnable) -> Unit
) : DeviceAuthenticator {

    @Inject
    constructor() : this(
        { Looper.myLooper() === Looper.getMainLooper() },
        { task -> Handler(Looper.getMainLooper()).post(task) }
    )

    /** Test seam for the main-thread check only; orphan checks go to the real main looper. */
    @VisibleForTesting
    internal constructor(onMainThread: () -> Boolean) : this(
        onMainThread,
        { task -> Handler(Looper.getMainLooper()).post(task) }
    )

    /**
     * The open attempt. [holder] is null when it is orphaned (the holding host was destroyed in a rotation);
     * [orphanSeq] counts the orphanings so a posted check closes only the orphaning it was posted for.
     */
    private class OpenAttempt(
        val attemptId: Long,
        val callback: DeviceAuthCallback,
        var holder: WeakReference<RelayRegistration>?
    ) {
        var orphanSeq: Int = 0
    }

    private var current: WeakReference<RelayRegistration>? = null
    private var open: OpenAttempt? = null

    /** The id of the open attempt, or null when none is open. Test hook only. */
    @get:VisibleForTesting
    internal val openAttemptId: Long?
        get() = open?.attemptId

    /** True while a registration is held and still reachable. Test hook only. */
    @get:VisibleForTesting
    internal val hasRegistration: Boolean
        get() = current?.get() != null

    /** True while the open attempt has no live holder, so the next [register] adopts it. Test hook only. */
    @get:VisibleForTesting
    internal val isOpenAttemptOrphaned: Boolean
        get() = open?.let { it.holder?.get() == null } ?: false

    override fun authenticate(attemptId: Long, copy: AuthPromptCopy, callback: DeviceAuthCallback): AuthLaunch {
        requireMainThread()
        val registration = current?.get() ?: return AuthLaunch.HOST_UNAVAILABLE
        if (open != null) return AuthLaunch.ALREADY_IN_FLIGHT
        val record = OpenAttempt(attemptId, callback, WeakReference(registration))
        open = record // open BEFORE the adapter call: an outcome may arrive re-entrantly
        var result: AuthLaunch? = null
        try {
            val launch = registration.adapter.authenticate(attemptId, copy, RelayCallback(record, registration))
            result = launch
            return launch
        } finally {
            // Nothing was launched (or the adapter threw): no callback follows, so the record must not stay open.
            if (result != AuthLaunch.LAUNCHED && open === record) open = null
        }
    }

    override fun cancel(attemptId: Long) {
        requireMainThread()
        val record = open ?: return
        if (record.attemptId != attemptId) return // stale id: ignored
        val holder = record.holder?.get()
        if (holder == null) {
            closeByRelay(record)
            return
        }
        try {
            holder.adapter.cancel(attemptId)
        } finally {
            // The adapter delivers Failed(SYSTEM_CANCELED) through the relay callback, which closes the record. If it
            // did not (or threw), the relay still owes the synchronous outcome of a cancel, exactly once.
            if (open === record) closeByRelay(record)
        }
    }

    /**
     * Registers [adapter] as the current prompt adapter of a newly created host (called by the prompt host in
     * onCreate, after super.onCreate). Replaces the previous registration as the current one. If an orphaned
     * attempt is open, hands it to the new adapter through [adopter] before returning.
     */
    internal fun register(adapter: DeviceAuthenticator, adopter: AttemptAdopter): RelayRegistration {
        requireMainThread()
        val registration = RelayRegistration(adapter, adopter)
        current = WeakReference(registration)
        val record = open
        if (record != null && record.holder?.get() == null) {
            // Holder first: an outcome delivered re-entrantly by the adoption must pass the holder check.
            record.holder = WeakReference(registration)
            val adopted = adopter.adopt(record.attemptId, RelayCallback(record, registration))
            if (!adopted && open === record) closeByRelay(record)
        }
        return registration
    }

    /**
     * Makes [registration] the current one again (called by the prompt host at its ON_START, so the visible host is
     * the one new attempts go to when several Activity instances exist). It never adopts an orphaned attempt and
     * never touches the open record.
     */
    internal fun activate(registration: RelayRegistration) {
        requireMainThread()
        current = WeakReference(registration)
    }

    /**
     * Clears [registration] at the host's ON_DESTROY (rule (a)). Returns the id of the attempt the host lost, or
     * null. A non-null result is returned only when this registration holds the open attempt AND the host was not
     * changing configurations (rule (d)); the record is removed before returning, so the id is returned at most
     * once and any later outcome for it is dropped. When the host was changing configurations the attempt is
     * orphaned instead, for the next [register] to adopt.
     */
    internal fun unregister(registration: RelayRegistration, isChangingConfigurations: Boolean): Long? {
        requireMainThread()
        if (current?.get() === registration) current = null
        val record = open ?: return null
        if (record.holder?.get() !== registration) return null
        if (isChangingConfigurations) {
            record.holder = null
            record.orphanSeq += 1
            val seq = record.orphanSeq
            // A relaunch adopts inside one main-thread message, so this runs after any real adoption.
            postToMain(Runnable { closeIfStillOrphaned(record, seq) })
            return null
        }
        open = null
        return record.attemptId
    }

    /** The posted orphan check: closes [record] only if it is still open and still in the orphaning numbered [seq]. */
    private fun closeIfStillOrphaned(record: OpenAttempt, seq: Int) {
        if (open !== record) return
        if (record.orphanSeq != seq) return
        if (record.holder?.get() != null) return // adopted: a real adoption always wins
        closeByRelay(record)
    }

    /** Closes [record] with the relay's own `Failed(SYSTEM_CANCELED)`: record first, then the callback. */
    private fun closeByRelay(record: OpenAttempt) {
        if (open !== record) return
        open = null
        record.callback.onOutcome(record.attemptId, DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED))
    }

    private fun requireMainThread() {
        check(onMainThread()) { "AppLockAuthenticatorRelay must be used on the main thread" }
    }

    /** The only callback an adapter ever sees; bound to one (record, registration) pair. */
    private inner class RelayCallback(
        private val record: OpenAttempt,
        private val registration: RelayRegistration
    ) : DeviceAuthCallback {
        override fun onOutcome(attemptId: Long, outcome: DeviceAuthOutcome) {
            if (open !== record) return // already closed (outcome, cancel, host loss): drop
            if (attemptId != record.attemptId) return
            if (record.holder?.get() !== registration) return // an adapter that no longer holds the attempt: drop
            open = null
            record.callback.onOutcome(attemptId, outcome)
        }
    }
}
