package com.yourname.expensetracker.domain.notification.presentation

import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one bounded resolver that turns a [NotificationKind] into a [NotificationPresentationDecision]
 * (TECH-2 option a, INV-6, INV-9, domain-spec 4.2 and 4.5).
 *
 * ## Contract
 *
 * - Reads the settings once per post through [AppLockSettingsRepository.getLoadState], bounded by
 *   [T_READ_MILLIS] (`withTimeoutOrNull`). There is no `runBlocking` and no lock held across the read.
 * - A timeout (null read), `Unreadable(any cause)` or an exception from the read all give DISCREET and
 *   the post proceeds. The matching bounded diagnostic is `NOTIF_POLICY_SETTINGS_UNREADABLE(cause)` or
 *   `NOTIF_POLICY_FALLBACK_EXCEPTION(exception class name only)`.
 * - `CancellationException` is rethrown before the broad catch, so a stopped worker posts nothing and
 *   gets no fallback. The broad catch sits outside the timeout block, never inside it.
 * - Diagnostics are launched on the application scope and never awaited, so a slow or failing writer
 *   cannot delay or alter the post (INV-9). The emitter is best effort and rate limited.
 * - The resolver never decides whether or when to post, and never touches `DeliveryResult`, claim or
 *   mark transitions, cooldowns or metrics (INV-11). It returns a decision and nothing else.
 *
 * S1's repository maps IO failures to `Unreadable(IO_ERROR)` and lets other exceptions propagate; those
 * land in the exception branch here (S4 obligation).
 */
@Singleton
class NotificationPresentationResolver @Inject constructor(
    private val settingsRepository: AppLockSettingsRepository,
    private val diagnostics: PrivacyDiagnosticsEmitter,
    @ApplicationScope private val diagnosticsScope: CoroutineScope
) {

    /**
     * Resolves the presentation of one post of [kind] on a device running [sdkInt]. Always returns a
     * decision, except that it rethrows [CancellationException].
     */
    suspend fun resolve(kind: NotificationKind, sdkInt: Int): NotificationPresentationDecision {
        val loadState: AppLockSettingsLoadState? = try {
            withTimeoutOrNull(T_READ_MILLIS) { settingsRepository.getLoadState() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportFailure(PrivacyDiagnosticCode.NOTIF_POLICY_FALLBACK_EXCEPTION, PrivacyDiagnosticMetadata.builder().apply {
                exceptionClass(e)
            }.build())
            return NotificationContentPolicy.decideFailClosed(
                kind = kind,
                sdkInt = sdkInt,
                reason = NotificationFallbackReason.POLICY_EXCEPTION
            )
        }

        val decision = NotificationContentPolicy.decide(kind, loadState, sdkInt)
        when (loadState) {
            null -> reportUnreadable(SettingsUnreadableCause.TIMEOUT)
            is AppLockSettingsLoadState.Unreadable -> reportUnreadable(loadState.cause)
            is AppLockSettingsLoadState.Loaded, is AppLockSettingsLoadState.FirstRunDefault -> Unit
        }
        return decision
    }

    private fun reportUnreadable(cause: SettingsUnreadableCause) {
        reportFailure(PrivacyDiagnosticCode.NOTIF_POLICY_SETTINGS_UNREADABLE, PrivacyDiagnosticMetadata.builder().apply {
            put(PrivacyDiagnosticKey.CAUSE, cause)
        }.build())
    }

    /** Fire and forget: the post path never awaits the diagnostic. */
    private fun reportFailure(
        code: PrivacyDiagnosticCode,
        metadata: PrivacyDiagnosticMetadata
    ) {
        diagnosticsScope.launch { diagnostics.emit(code, metadata) }
    }

    companion object {
        /** `T_read`: the bound on one settings read (TECH-11), in milliseconds. */
        const val T_READ_MILLIS: Long = 500L
    }
}
