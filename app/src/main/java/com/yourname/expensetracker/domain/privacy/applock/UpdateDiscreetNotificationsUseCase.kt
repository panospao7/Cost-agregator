package com.yourname.expensetracker.domain.privacy.applock

import javax.inject.Inject

/**
 * Bounded outcome of [UpdateDiscreetNotificationsUseCase]. Carries no settings text.
 */
sealed interface UpdateDiscreetNotificationsResult {
    /**
     * The write is durable (INV-46). [discreetNotifications] is the committed value and [repost]
     * says whether the in-place re-post sweep ran after it.
     */
    data class Committed(
        val discreetNotifications: Boolean,
        val repost: DiscreetRepostOutcome
    ) : UpdateDiscreetNotificationsResult

    /** Refused without writing (INV-44, the store was unreadable). No sweep ran. */
    data class Rejected(val reason: AppLockWriteRejection) : UpdateDiscreetNotificationsResult

    /** The commit failed; the previous committed value is in force (AE-105). No sweep ran. */
    data class Failed(val field: AppLockSettingField) : UpdateDiscreetNotificationsResult
}

/**
 * The write use case for the discreet-notifications setting (INV-13, INV-46, AE-105).
 *
 * The only writer is [AppLockSettingsRepository] (INV-1). The sequence is:
 * 1. read the committed effective discreet value before the write;
 * 2. write through the repository;
 * 3. on [AppLockSettingsWriteResult.Committed] only, hand the before and after effective values to
 *    [DiscreetRepostTrigger], which sweeps only on a false to true edge. A failed or rejected write
 *    triggers nothing; a failed write records `LOCK_SETTING_PERSIST_FAILED(DISCREET)` here, because
 *    the repository leaves diagnostics to its caller.
 *
 * Turning discreet off never re-posts. Writing true while already true is not an edge, so it does
 * not sweep. A sweep failure does not change the committed result. `CancellationException`
 * propagates from every step.
 *
 * Caller contract (S12): invoke this off the Main thread from an application-scoped job, or wrap the
 * write and the trigger in `withContext(NonCancellable)`. That defers cancellation until the sweep ends
 * without swallowing it, so a committed ON cannot miss its sweep because the caller was cancelled
 * between the commit and the trigger.
 *
 * An unreadable before-read counts as "was off" (a committed ON then sweeps); only a Loaded or
 * FirstRunDefault before-read is trusted.
 *
 * Residual: the before value is a separate read, not part of the repository's atomic edit. In 1a
 * this use case is the only writer of the field, so the read and the write cannot interleave with
 * another writer of it. A later writer of the same effective value (S18) must pass its own
 * before and after values to [DiscreetRepostTrigger].
 */
class UpdateDiscreetNotificationsUseCase @Inject constructor(
    private val repository: AppLockSettingsRepository,
    private val trigger: DiscreetRepostTrigger,
    private val privacyDiagnostics: PrivacyDiagnosticsEmitter
) {

    suspend operator fun invoke(enabled: Boolean): UpdateDiscreetNotificationsResult {
        // Trust the before value only when the store was readable. An unreadable before-read is not evidence
        // that the notifications were already discreet, so it counts as "was off": a committed ON then sweeps,
        // and a redundant sweep is silent and content-free.
        val wasEffectiveDiscreet = when (val before = repository.getLoadState()) {
            is AppLockSettingsLoadState.Loaded,
            is AppLockSettingsLoadState.FirstRunDefault -> EffectivePrivacyValues.effectiveDiscreet(before)
            is AppLockSettingsLoadState.Unreadable -> false
        }
        return when (val result = repository.setDiscreetNotifications(enabled)) {
            is AppLockSettingsWriteResult.Committed -> {
                val isEffectiveDiscreet = EffectivePrivacyValues.effectiveDiscreet(
                    AppLockSettingsLoadState.Loaded(result.settings)
                )
                val repost = trigger.onCommittedWrite(wasEffectiveDiscreet, isEffectiveDiscreet)
                UpdateDiscreetNotificationsResult.Committed(result.settings.discreetNotifications, repost)
            }
            is AppLockSettingsWriteResult.Rejected ->
                UpdateDiscreetNotificationsResult.Rejected(result.reason)
            is AppLockSettingsWriteResult.Failed -> {
                privacyDiagnostics.emit(
                    PrivacyDiagnosticCode.LOCK_SETTING_PERSIST_FAILED,
                    PrivacyDiagnosticMetadata.builder()
                        .put(PrivacyDiagnosticKey.FIELD, result.field)
                        .build()
                )
                UpdateDiscreetNotificationsResult.Failed(result.field)
            }
        }
    }
}
