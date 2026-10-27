package com.yourname.expensetracker.ui.applock

import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.EffectivePrivacyValues
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The one place a window flag is written, behind a seam so the decision is testable without a real `Window`. */
internal fun interface WindowFlagSink {
    /** Sets (true) or clears (false) `FLAG_SECURE`. Must be idempotent. */
    fun setSecure(secure: Boolean)
}

/**
 * The single writer of `FLAG_SECURE` on the app window (INV-29, INV-53 shell part; S20-b). Nothing calls it yet:
 * the shell integration (S20-c) calls [applyInitial] as the first statement after `super.onCreate` and [bind] once
 * the database gate has passed, so until then the class is dormant.
 *
 * ## What decides the flag
 *
 * `EffectivePrivacyValues.effectiveSecureScreen(loadState, resolving)` and nothing else:
 * - `loadState` is the COMMITTED state from [AppLockSettingsRepository.observeLoadState] (INV-46: no optimistic
 *   value, so an uncommitted write never moves the flag);
 * - `resolving` is `controller.state.value is AppLockState.Resolving`, the one allowed non-settings term. No other
 *   controller state is an input. In particular `Authenticating` of any purpose never sets or clears the flag
 *   (INV-53): an ENABLE prompt opens with the flag off while the committed lock is off and secure-screen is off, and
 *   the flag turns on at the first emission after the write commits (AE-113); a CONFIRM_CHANGE prompt keeps it on, it
 *   is cleared only by a committed weakening, and a write failure leaves it on because the committed state did not
 *   change (AE-114).
 *
 * ## Fail-closed
 *
 * - [applyInitial] sets the flag synchronously, before the first frame and before anything is read (AE-38).
 * - The flag is cleared ONLY by a computed `false` from a real emission. Before the first emission, with a null or
 *   absent state, and while the load-state stream is down (a null state is fed to the decision, which gives `true`),
 *   it stays set.
 * - Re-subscription (INV-47, AE-97): the repository's load-state stream emits `Unreadable(IO_ERROR)` and completes
 *   after an IO read failure, and a stream can also fail or complete cleanly. Whenever the stream ends for any reason
 *   a null state is emitted (fail-closed, so a stale `false` is never kept) and the stream is re-subscribed after a
 *   bounded backoff ([defaultBackoffMillis], capped), so the flag can clear again once a committed read recovers. The
 *   backoff restarts after any emission that is not `Unreadable`.
 *
 * The collection lives in the Activity's `lifecycleScope`: it keeps running while the Activity is stopped (the
 * recents snapshot is taken after `onStop`), and ends at `ON_DESTROY`. A `CancellationException` of this collector
 * is never caught: `.catch {}` rethrows it when the collecting job is cancelled, and `delay` and `emitAll` propagate
 * it. A `CancellationException` thrown by an upstream source while the collector is still active is NOT a
 * cancellation of this collector; `.catch` treats it as a source failure and maps it to "set" (fail-closed).
 */
@Singleton
class SecureWindowController internal constructor(
    private val repository: AppLockSettingsRepository,
    private val controller: AppLockController,
    private val backoffMillis: (failures: Int) -> Long
) {

    @Inject
    constructor(
        repository: AppLockSettingsRepository,
        controller: AppLockController
    ) : this(repository, controller, { failures -> defaultBackoffMillis(failures) })

    /** Sets `FLAG_SECURE` on [window] now (synchronous, idempotent). Call it before `setContent`. */
    fun applyInitial(window: Window) {
        applyInitial(WindowSink(window))
    }

    /**
     * Starts following the committed state for [activity]'s window until the Activity is destroyed. Call it once per
     * Activity instance; a second call would only collect twice and write the same value.
     */
    fun bind(activity: ComponentActivity) {
        val sink = WindowSink(activity.window)
        activity.lifecycleScope.launch { follow(sink) }
    }

    internal fun applyInitial(sink: WindowFlagSink) {
        sink.setSecure(true)
    }

    /** Collects [secureFlow] into [sink] until cancelled. Test seam; the production caller is [bind]. */
    internal suspend fun follow(sink: WindowFlagSink) {
        secureFlow().distinctUntilChanged().collect { secure -> sink.setSecure(secure) }
    }

    /**
     * The committed load states, re-subscribed with a bounded backoff (INV-47, AE-97). Each time the repository
     * stream ends (an IO failure after its terminal `Unreadable(IO_ERROR)`, a thrown error, or a clean completion)
     * a null is emitted, which the decision maps to "set", so the stream being down never leaves a stale clear. A
     * source that fails by exception is treated the same way. Only cancellation of this collector ends the loop.
     */
    private fun committedLoadStates(): Flow<AppLockSettingsLoadState?> = flow {
        var failures = 0
        while (true) {
            emitAll(
                flow<AppLockSettingsLoadState?> { emitAll(repository.observeLoadState()) }
                    .onEach { if (it !is AppLockSettingsLoadState.Unreadable) failures = 0 }
                    .catch { /* the null below is the fail-closed input; this collector's own cancellation is rethrown */ }
            )
            emit(null)
            delay(backoffMillis(failures).coerceAtLeast(MIN_BACKOFF_MS))
            failures++
        }
    }

    /**
     * The computed flag value per real emission. A failing or throwing source maps to the fail-closed input (a null
     * load state, or `resolving = true`), never to a clear.
     */
    internal fun secureFlow(): Flow<Boolean> {
        val loadStates: Flow<AppLockSettingsLoadState?> = committedLoadStates()
        val resolving: Flow<Boolean> =
            flow { emitAll(controller.state.map { it is AppLockState.Resolving }) }
                .catch { emit(true) }
                .distinctUntilChanged()
        return combine(loadStates, resolving) { loadState, isResolving -> decide(loadState, isResolving) }
    }

    internal class WindowSink(private val window: Window) : WindowFlagSink {
        override fun setSecure(secure: Boolean) {
            if (secure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    internal companion object {
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MIN_BACKOFF_MS = 1L
        private const val MAX_DOUBLINGS = 5

        /**
         * The default re-subscription delay after [failures] consecutive ended streams (0 for the first): 1 s,
         * doubling, capped at 30 s. Never zero or negative, so a stream that ends at once cannot spin.
         */
        fun defaultBackoffMillis(failures: Int): Long =
            (BASE_BACKOFF_MS shl failures.coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(MAX_BACKOFF_MS)

        /**
         * The pure decision (INV-29, INV-53): `secureScreen OR appLockEnabled OR Unreadable OR null OR resolving`.
         * Only the committed [loadState] and the `Resolving` flag are inputs.
         */
        fun decide(loadState: AppLockSettingsLoadState?, resolving: Boolean): Boolean =
            EffectivePrivacyValues.effectiveSecureScreen(loadState, resolving)
    }
}
