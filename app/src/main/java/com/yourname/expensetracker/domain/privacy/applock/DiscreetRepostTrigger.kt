package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.domain.service.NotificationService
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Bounded outcome of [DiscreetRepostTrigger.onCommittedWrite]. No text, no counts of content.
 */
enum class DiscreetRepostOutcome {
    /** The write did not turn `effectiveDiscreet` from false to true, so no sweep ran. */
    NOT_TRIGGERED,

    /** The sweep ran and returned (per-notification skips are recorded by the service). */
    SWEPT,

    /** The sweep threw a non-cancellation exception; it was recorded and swallowed here. */
    SWEEP_FAILED
}

/**
 * The single edge detector for the discreet re-post sweep (spec 4.6, INV-13, INV-46, AMD-7).
 *
 * Callers invoke [onCommittedWrite] only after a write has returned a committed result, passing the
 * effective discreet value before and after that committed write. The sweep runs exactly when the
 * value turned from false to true:
 * - a failed or rejected write never reaches this class (nothing committed, AE-105);
 * - turning discreet off never re-posts;
 * - a write that leaves discreet effective unchanged (on to on, off to off) never re-posts.
 *
 * Reused by the lock-enable write (S18), which also changes the effective discreet value.
 *
 * Failure isolation: the settings write has already committed, so a sweep failure must not fail
 * it. `CancellationException` always propagates. Any other exception is recorded as
 * `NOTIF_REPOST_FAILED` with the exception class name only (bounded, no message or stack) and the
 * outcome is [DiscreetRepostOutcome.SWEEP_FAILED]. Not a delivery: no counters, claims or cooldowns.
 */
class DiscreetRepostTrigger @Inject constructor(
    private val notificationService: NotificationService,
    private val privacyDiagnostics: PrivacyDiagnosticsEmitter
) {

    suspend fun onCommittedWrite(
        wasEffectiveDiscreet: Boolean,
        isEffectiveDiscreet: Boolean
    ): DiscreetRepostOutcome {
        if (wasEffectiveDiscreet || !isEffectiveDiscreet) return DiscreetRepostOutcome.NOT_TRIGGERED
        return try {
            notificationService.repostActiveAsDiscreet()
            DiscreetRepostOutcome.SWEPT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            privacyDiagnostics.emit(
                PrivacyDiagnosticCode.NOTIF_REPOST_FAILED,
                PrivacyDiagnosticMetadata.builder().exceptionClass(e).build()
            )
            DiscreetRepostOutcome.SWEEP_FAILED
        }
    }
}
