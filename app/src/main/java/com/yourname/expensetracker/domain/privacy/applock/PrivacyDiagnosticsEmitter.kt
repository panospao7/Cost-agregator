package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.DiagnosticThrowableSummary
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import com.yourname.expensetracker.domain.util.MonotonicTimeProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Closed metadata keys for privacy diagnostics (spec 10: reason code, host / parameter / cause /
 * field enums, booleans, counts, SDK int, exception class name). [wireName] is the only key text
 * that can reach a stored row. Every wire name passes `EventMetadataSanitizer` unredacted.
 */
enum class PrivacyDiagnosticKey(val wireName: String) {
    HOST("host"),
    OLD_HOST("oldHost"),
    NEW_HOST("newHost"),
    PARAMETER("parameter"),
    CAUSE("cause"),
    FIELD("field"),
    AFTER_UNLOCK("afterUnlock"),
    BANK_SYNC_AVAILABLE("bankSyncAvailable"),
    COUNT("count"),
    SDK_INT("sdkInt"),
    EXCEPTION_CLASS("exceptionClass"),
    SUPPRESSED_REASON_CODE("reasonCode")
}

/**
 * Closed set of privacy diagnostics the app may emit through [PrivacyDiagnosticsEmitter].
 *
 * Each entry fixes the stored [reasonCode], the outcome / severity, and the metadata keys that
 * are allowed for it (spec 5.7 and 10). A key outside [allowedKeys] is dropped at emit time.
 * Later slices add their own entries (lock, PIN) together with their [DiagnosticReasonCode]
 * values; nothing here is derived from runtime text.
 */
enum class PrivacyDiagnosticCode(
    val reasonCode: DiagnosticReasonCode,
    internal val outcome: EventOutcome,
    internal val severity: EventSeverity,
    val allowedKeys: Set<PrivacyDiagnosticKey>
) {
    // Notification content policy.
    NOTIF_POLICY_SETTINGS_UNREADABLE(
        DiagnosticReasonCode.NOTIF_POLICY_SETTINGS_UNREADABLE, EventOutcome.SKIPPED, EventSeverity.WARNING,
        setOf(PrivacyDiagnosticKey.CAUSE)
    ),
    NOTIF_POLICY_FALLBACK_EXCEPTION(
        DiagnosticReasonCode.NOTIF_POLICY_FALLBACK_EXCEPTION, EventOutcome.FAILED_FINAL, EventSeverity.WARNING,
        setOf(PrivacyDiagnosticKey.EXCEPTION_CLASS)
    ),
    NOTIF_ACTIONS_SUPPRESSED_LOCK(
        DiagnosticReasonCode.NOTIF_ACTIONS_SUPPRESSED_LOCK, EventOutcome.SKIPPED, EventSeverity.INFO,
        setOf(PrivacyDiagnosticKey.SDK_INT)
    ),
    NOTIF_REPOSTED_DISCREET(
        DiagnosticReasonCode.NOTIF_REPOSTED_DISCREET, EventOutcome.COMPLETED, EventSeverity.INFO,
        setOf(PrivacyDiagnosticKey.COUNT)
    ),
    NOTIF_REPOST_FAILED(
        DiagnosticReasonCode.NOTIF_REPOST_FAILED, EventOutcome.FAILED_FINAL, EventSeverity.WARNING,
        setOf(PrivacyDiagnosticKey.EXCEPTION_CLASS)
    ),

    // Deep-link policy (DL_DIAGNOSTICS_SUPPRESSED is emitter-internal, not a caller code).
    DL_APPLIED(
        DiagnosticReasonCode.DL_APPLIED, EventOutcome.COMPLETED, EventSeverity.INFO,
        setOf(PrivacyDiagnosticKey.HOST, PrivacyDiagnosticKey.AFTER_UNLOCK)
    ),
    DL_HELD_LOCKED(
        DiagnosticReasonCode.DL_HELD_LOCKED, EventOutcome.SKIPPED, EventSeverity.INFO,
        setOf(PrivacyDiagnosticKey.HOST)
    ),
    DL_HELD_REPLACED(
        DiagnosticReasonCode.DL_HELD_REPLACED, EventOutcome.SKIPPED, EventSeverity.INFO,
        setOf(PrivacyDiagnosticKey.OLD_HOST, PrivacyDiagnosticKey.NEW_HOST)
    ),
    DL_HELD_EXPIRED(
        DiagnosticReasonCode.DL_HELD_EXPIRED, EventOutcome.DROPPED, EventSeverity.INFO,
        setOf(PrivacyDiagnosticKey.HOST)
    ),
    DL_REJECTED_ACTION(
        DiagnosticReasonCode.DL_REJECTED_ACTION, EventOutcome.BLOCKED, EventSeverity.INFO,
        emptySet()
    ),
    DL_REJECTED_MALFORMED(
        DiagnosticReasonCode.DL_REJECTED_MALFORMED, EventOutcome.BLOCKED, EventSeverity.INFO,
        emptySet()
    ),
    DL_REJECTED_UNKNOWN_HOST(
        DiagnosticReasonCode.DL_REJECTED_UNKNOWN_HOST, EventOutcome.BLOCKED, EventSeverity.INFO,
        emptySet()
    ),
    DL_REJECTED_BANK_HOST(
        DiagnosticReasonCode.DL_REJECTED_BANK_HOST, EventOutcome.BLOCKED, EventSeverity.INFO,
        setOf(PrivacyDiagnosticKey.BANK_SYNC_AVAILABLE)
    ),
    DL_PARAM_UNKNOWN_DROPPED(
        DiagnosticReasonCode.DL_PARAM_UNKNOWN_DROPPED, EventOutcome.DROPPED, EventSeverity.INFO,
        setOf(PrivacyDiagnosticKey.HOST, PrivacyDiagnosticKey.COUNT)
    ),
    DL_PARAM_INVALID_DROPPED(
        DiagnosticReasonCode.DL_PARAM_INVALID_DROPPED, EventOutcome.DROPPED, EventSeverity.INFO,
        setOf(PrivacyDiagnosticKey.HOST, PrivacyDiagnosticKey.PARAMETER)
    ),
    DL_BRIEFING_KEY_NOT_DELIVERED(
        DiagnosticReasonCode.DL_BRIEFING_KEY_NOT_DELIVERED, EventOutcome.SKIPPED, EventSeverity.INFO,
        emptySet()
    ),
    DL_BRIEFING_STATE_UNREADABLE(
        DiagnosticReasonCode.DL_BRIEFING_STATE_UNREADABLE, EventOutcome.SKIPPED, EventSeverity.WARNING,
        emptySet()
    ),
    DL_TARGET_NOT_FOUND(
        DiagnosticReasonCode.DL_TARGET_NOT_FOUND, EventOutcome.SKIPPED, EventSeverity.INFO,
        emptySet()
    ),
    DL_TARGET_READ_BLOCKED(
        DiagnosticReasonCode.DL_TARGET_READ_BLOCKED, EventOutcome.BLOCKED, EventSeverity.WARNING,
        setOf(PrivacyDiagnosticKey.EXCEPTION_CLASS)
    ),

    // App-lock settings durability.
    LOCK_SETTING_PERSIST_FAILED(
        DiagnosticReasonCode.LOCK_SETTING_PERSIST_FAILED, EventOutcome.FAILED_FINAL, EventSeverity.WARNING,
        setOf(PrivacyDiagnosticKey.FIELD)
    ),
    LOCK_SETTINGS_UNREADABLE(
        DiagnosticReasonCode.LOCK_SETTINGS_UNREADABLE, EventOutcome.SKIPPED, EventSeverity.WARNING,
        setOf(PrivacyDiagnosticKey.CAUSE)
    ),
    LOCK_SETTINGS_REVIEW_SAVED(
        DiagnosticReasonCode.LOCK_SETTINGS_REVIEW_SAVED, EventOutcome.COMPLETED, EventSeverity.INFO,
        emptySet()
    ),

    // App-lock controller (S18-ii-c, owner-approvals item 51a, D9): T28, a re-read recovered a transient unreadable
    // store. CAUSE carries the bounded PRIOR unreadable cause, only IO_ERROR or TIMEOUT (the controller never
    // emits a persistent cause here), as an enum name only.
    LOCK_SETTINGS_RECOVERED(
        DiagnosticReasonCode.LOCK_SETTINGS_RECOVERED, EventOutcome.COMPLETED, EventSeverity.INFO,
        setOf(PrivacyDiagnosticKey.CAUSE)
    ),

    // App-lock controller (S16 part (i)): lock shown, unlocked, inert. No metadata: the cause or
    // reason is the code itself. The enable/disable and recovery codes arrive with their slices.
    LOCK_SHOWN_COLD_START(
        DiagnosticReasonCode.LOCK_SHOWN_COLD_START, EventOutcome.BLOCKED, EventSeverity.INFO,
        emptySet()
    ),
    LOCK_SHOWN_TIMEOUT(
        DiagnosticReasonCode.LOCK_SHOWN_TIMEOUT, EventOutcome.BLOCKED, EventSeverity.INFO,
        emptySet()
    ),
    LOCK_SHOWN_SETTINGS_UNREADABLE(
        DiagnosticReasonCode.LOCK_SHOWN_SETTINGS_UNREADABLE, EventOutcome.BLOCKED, EventSeverity.WARNING,
        emptySet()
    ),
    LOCK_UNLOCKED(
        DiagnosticReasonCode.LOCK_UNLOCKED, EventOutcome.COMPLETED, EventSeverity.INFO,
        emptySet()
    ),
    LOCK_INERT_NO_CREDENTIAL(
        DiagnosticReasonCode.LOCK_INERT_NO_CREDENTIAL, EventOutcome.SKIPPED, EventSeverity.WARNING,
        emptySet()
    ),
    LOCK_INERT_HW_UNAVAILABLE(
        DiagnosticReasonCode.LOCK_INERT_HW_UNAVAILABLE, EventOutcome.SKIPPED, EventSeverity.WARNING,
        emptySet()
    ),
    LOCK_INERT_UNKNOWN(
        DiagnosticReasonCode.LOCK_INERT_UNKNOWN, EventOutcome.SKIPPED, EventSeverity.WARNING,
        emptySet()
    ),
    LOCK_INERT_API_UNSUPPORTED(
        DiagnosticReasonCode.LOCK_INERT_API_UNSUPPORTED, EventOutcome.SKIPPED, EventSeverity.WARNING,
        emptySet()
    ),
    LOCK_INERT_SETTINGS_UNREADABLE(
        DiagnosticReasonCode.LOCK_INERT_SETTINGS_UNREADABLE, EventOutcome.SKIPPED, EventSeverity.WARNING,
        emptySet()
    ),

    // App-lock controller (S16 part (ii-a)): UNLOCK prompt outcomes (T9, T10, T35). LOCK_AUTH_CANCELLED
    // and LOCK_AUTH_LOCKOUT carry no metadata. LOCK_AUTH_ERROR carries the bounded prompt error bucket
    // (HW_UNAVAILABLE / TIMEOUT / SYSTEM_CANCELED / OTHER) under the closed CAUSE key, as an enum name only.
    LOCK_AUTH_CANCELLED(
        DiagnosticReasonCode.LOCK_AUTH_CANCELLED, EventOutcome.SKIPPED, EventSeverity.INFO,
        emptySet()
    ),
    LOCK_AUTH_LOCKOUT(
        DiagnosticReasonCode.LOCK_AUTH_LOCKOUT, EventOutcome.BLOCKED, EventSeverity.WARNING,
        emptySet()
    ),
    LOCK_AUTH_ERROR(
        DiagnosticReasonCode.LOCK_AUTH_ERROR, EventOutcome.BLOCKED, EventSeverity.WARNING,
        setOf(PrivacyDiagnosticKey.CAUSE)
    ),

    // App-lock controller (S16 part (ii-a), owner-approvals item 32): a failed capability check at an Unlock
    // tap (spec 10 LOCK_UNAVAILABLE_CAPABILITY), and (S16 part (ii-b)) an ENABLE prompt whose NotAvailable
    // re-check refuses. CAUSE carries the bounded DeviceAuthCapability enum name, never text.
    LOCK_UNAVAILABLE_CAPABILITY(
        DiagnosticReasonCode.LOCK_UNAVAILABLE_CAPABILITY, EventOutcome.SKIPPED, EventSeverity.WARNING,
        setOf(PrivacyDiagnosticKey.CAUSE)
    ),

    // App-lock controller (S18 part (i-a)): the T26 request-time refusals for the two causes that have their
    // own code (spec 10), and the enable / disable results (T17, T18). The refusals carry the bounded
    // DeviceAuthCapability enum name under the closed CAUSE key; LOCK_ENABLED and LOCK_DISABLED carry nothing.
    LOCK_UNAVAILABLE_NOT_SECURE(
        DiagnosticReasonCode.LOCK_UNAVAILABLE_NOT_SECURE, EventOutcome.SKIPPED, EventSeverity.WARNING,
        setOf(PrivacyDiagnosticKey.CAUSE)
    ),
    LOCK_UNAVAILABLE_API(
        DiagnosticReasonCode.LOCK_UNAVAILABLE_API, EventOutcome.SKIPPED, EventSeverity.WARNING,
        setOf(PrivacyDiagnosticKey.CAUSE)
    ),
    LOCK_ENABLED(
        DiagnosticReasonCode.LOCK_ENABLED, EventOutcome.COMPLETED, EventSeverity.INFO,
        emptySet()
    ),
    LOCK_DISABLED(
        DiagnosticReasonCode.LOCK_DISABLED, EventOutcome.COMPLETED, EventSeverity.INFO,
        emptySet()
    )
}

/**
 * Typed, value-restricted metadata for [PrivacyDiagnosticsEmitter.emit]. The only accepted value
 * types are enum constants, Boolean and Int, plus an exception class simple name derived from a
 * Throwable by `DiagnosticThrowableSummary` (message and stack trace are never read). There is
 * no String-accepting entry point, so caller text can never reach a stored row.
 */
class PrivacyDiagnosticMetadata private constructor(
    internal val entries: Map<PrivacyDiagnosticKey, Any>
) {
    class Builder {
        private val map = LinkedHashMap<PrivacyDiagnosticKey, Any>()

        fun put(key: PrivacyDiagnosticKey, value: Enum<*>): Builder = apply { map[key] = value }

        fun put(key: PrivacyDiagnosticKey, value: Boolean): Builder = apply { map[key] = value }

        /** Counts and SDK ints only; negative values are stored as 0. */
        fun put(key: PrivacyDiagnosticKey, value: Int): Builder = apply { map[key] = value.coerceAtLeast(0) }

        /** Stores the exception CLASS simple name only (bounded). Never the message or stack. */
        fun exceptionClass(error: Throwable): Builder = apply {
            map[PrivacyDiagnosticKey.EXCEPTION_CLASS] = DiagnosticThrowableSummary.className(error)
        }

        fun build(): PrivacyDiagnosticMetadata = PrivacyDiagnosticMetadata(LinkedHashMap(map))
    }

    companion object {
        val EMPTY: PrivacyDiagnosticMetadata = PrivacyDiagnosticMetadata(emptyMap())

        fun builder(): Builder = Builder()
    }
}

/**
 * Single privacy-pipeline diagnostics front door (TECH-8, OD-12a, INV-18).
 *
 * Delegates every write to [NotificationDiagnosticEmitter] (restore-safe: it falls back to the
 * maintenance sink when Room writes are denied) with [AppPipeline.PRIVACY] and a constant stage.
 * Events never carry an entity, source, hash or Throwable; the stored metadata is built only
 * from [PrivacyDiagnosticKey] entries that [PrivacyDiagnosticCode.allowedKeys] permits.
 *
 * ## Rate limit (DIAG_RATE)
 *
 * At most one durable event per [PrivacyDiagnosticCode] per [RATE_WINDOW_NANOS] (60 s) per
 * process, measured on [MonotonicTimeProvider] (wall-clock jumps do not matter). Calls inside
 * the window are counted, not written. The first call for that code after the window expires
 * first writes one [DiagnosticReasonCode.DL_DIAGNOSTICS_SUPPRESSED] row (metadata: the
 * suppressed code and the suppressed count), then writes itself and opens a new window. The
 * count is memory-only: it is a lower bound if the process dies, and a window with no later
 * call for the same code never produces a summary.
 *
 * The window is per code, not per metadata value: a second `LOCK_AUTH_ERROR` bucket emitted within 60 s
 * of the first is counted but not written (accepted, privacy review F5).
 *
 * ## Contract for callers
 *
 * Best effort: a writer or sink failure is logged (class name only) and swallowed;
 * [CancellationException] always propagates. Callers on the resolve / lock / unlock path must
 * launch this from a non-blocking scope and never await it before deciding or rendering.
 */
@Singleton
class PrivacyDiagnosticsEmitter @Inject constructor(
    private val delegate: NotificationDiagnosticEmitter,
    private val monotonicTimeProvider: MonotonicTimeProvider
) {

    private class WindowState(var startNanos: Long, var suppressed: Int)

    private val stateLock = Any()
    private val windows = HashMap<PrivacyDiagnosticCode, WindowState>()

    /** Emits [code] with [metadata], subject to the per-code rate limit. */
    suspend fun emit(
        code: PrivacyDiagnosticCode,
        metadata: PrivacyDiagnosticMetadata = PrivacyDiagnosticMetadata.EMPTY
    ) {
        val flushedSuppressed = admit(code) ?: return
        if (flushedSuppressed > 0) {
            safeEmit(suppressedEvent(code, flushedSuppressed))
        }
        safeEmit(buildEvent(code, metadata))
    }

    /**
     * Rate-limit decision. Returns null when the event is suppressed (and counted), otherwise
     * the number of events suppressed in the window that just ended (0 if none). Never suspends.
     */
    private fun admit(code: PrivacyDiagnosticCode): Int? = synchronized(stateLock) {
        val now = monotonicTimeProvider.nowNanos()
        val window = windows[code]
        if (window == null) {
            windows[code] = WindowState(startNanos = now, suppressed = 0)
            return@synchronized 0
        }
        // A monotonic clock never goes backwards; if it ever did, treat the window as expired
        // so counting cannot stall.
        val elapsed = now - window.startNanos
        if (elapsed < 0L || elapsed >= RATE_WINDOW_NANOS) {
            val flushed = window.suppressed
            window.startNanos = now
            window.suppressed = 0
            return@synchronized flushed
        }
        if (window.suppressed < Int.MAX_VALUE) window.suppressed++
        null
    }

    private fun buildEvent(code: PrivacyDiagnosticCode, metadata: PrivacyDiagnosticMetadata): DiagnosticEvent {
        val builder = SafeEventMetadata.builder()
        for ((key, value) in metadata.entries) {
            if (key in code.allowedKeys) builder.put(key.wireName, value)
        }
        return DiagnosticEvent(
            pipeline = AppPipeline.PRIVACY,
            stage = STAGE,
            outcome = code.outcome,
            severity = code.severity,
            reasonCode = code.reasonCode,
            metadata = builder.build(),
            isTerminal = true
        )
    }

    private fun suppressedEvent(code: PrivacyDiagnosticCode, count: Int): DiagnosticEvent =
        DiagnosticEvent(
            pipeline = AppPipeline.PRIVACY,
            stage = STAGE,
            outcome = EventOutcome.SKIPPED,
            severity = EventSeverity.INFO,
            reasonCode = DiagnosticReasonCode.DL_DIAGNOSTICS_SUPPRESSED,
            metadata = SafeEventMetadata.builder()
                .put(PrivacyDiagnosticKey.SUPPRESSED_REASON_CODE.wireName, code.reasonCode)
                .put(PrivacyDiagnosticKey.COUNT.wireName, count)
                .build(),
            isTerminal = true
        )

    private suspend fun safeEmit(event: DiagnosticEvent) {
        try {
            delegate.emit(event)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Class name only: never the message or stack trace.
            Timber.w(
                "Privacy diagnostic emit failed: reason=%s class=%s",
                event.reasonCode,
                DiagnosticThrowableSummary.className(t)
            )
        }
    }

    companion object {
        /** DIAG_RATE window: 60 s on the monotonic clock. */
        const val RATE_WINDOW_NANOS: Long = 60L * 1_000_000_000L

        /** Constant stage for every privacy event; never caller-supplied. */
        const val STAGE: String = "privacy"
    }
}
