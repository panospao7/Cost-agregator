package com.yourname.expensetracker.ui.applock.pin

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.PinPresentation
import com.yourname.expensetracker.domain.privacy.applock.PinRecovery
import com.yourname.expensetracker.domain.privacy.applock.PinRejection
import com.yourname.expensetracker.domain.privacy.applock.PinUnlockResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayReason
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.ui.theme.SemanticColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The controller calls the PIN lock surface needs (S27 a2). The gate adapts `AppLockController` to it; the surface
 * never sees the controller. [submitUnlockPin] owns and zeroes the array it is given (INV-38).
 */
interface PinLockActions {
    suspend fun submitUnlockPin(pin: CharArray): PinUnlockResult

    suspend fun retryPinRecovery()

    suspend fun requestFingerprintUnlock()
}

/** Stable test tags for the PIN lock surface. Tags carry no user data. */
object PinLockTags {
    const val SURFACE = "applock_pin_lock_surface"
    const val MESSAGE = "applock_pin_lock_message"
    const val UNREADABLE = "applock_pin_lock_unreadable"
    const val RETRY = "applock_pin_lock_retry"
    const val FORGOT = "applock_pin_lock_forgot"
    const val FORGOT_DIALOG = "applock_pin_lock_forgot_dialog"
    const val FORGOT_OPEN_SETTINGS = "applock_pin_lock_forgot_open_settings"
    const val FORGOT_CLOSE = "applock_pin_lock_forgot_close"
}

/** The delay a wrong PIN started, by its bounded tier (ux-flows 4.4 schedule). The failure count is never shown. */
internal fun delayMillisOfTier(tier: PinDelayTier): Long = when (tier) {
    PinDelayTier.NONE -> 0L
    PinDelayTier.S30 -> 30_000L
    PinDelayTier.M1 -> 60_000L
    PinDelayTier.M5 -> 5L * 60_000L
    PinDelayTier.M15 -> 15L * 60_000L
    PinDelayTier.M60 -> 60L * 60_000L
}

/**
 * Whole seconds for the visible countdown, rounded up. Above one minute it moves in 30 s steps (ux-flows 4.4: the
 * visible text updates at coarse steps), at or below one minute in 1 s steps.
 */
internal fun coarseRemainingSeconds(remainingMillis: Long): Long {
    val seconds = (remainingMillis.coerceAtLeast(0L) + 999L) / 1000L
    return if (seconds > 60L) ((seconds + 29L) / 30L) * 30L else seconds
}

/** "1 hour", "4 minutes 30 seconds": zero parts are left out. */
@Composable
internal fun pinDurationText(totalSeconds: Long): String {
    val hours = (totalSeconds / 3600L).toInt()
    val minutes = ((totalSeconds % 3600L) / 60L).toInt()
    val seconds = (totalSeconds % 60L).toInt()
    val parts = mutableListOf<String>()
    if (hours > 0) parts.add(pluralStringResource(R.plurals.applock_pin_duration_hours, hours, hours))
    if (minutes > 0) parts.add(pluralStringResource(R.plurals.applock_pin_duration_minutes, minutes, minutes))
    if (seconds > 0 || parts.isEmpty()) {
        parts.add(pluralStringResource(R.plurals.applock_pin_duration_seconds, seconds, seconds))
    }
    return parts.joinToString(" ")
}

/**
 * Transient, in-memory UI state of one lock surface. Plain `remember` only (INV-38): nothing here is saved, and no
 * field holds a PIN, a digit or a failure count. [deadline] is an `elapsedRealtime` value; 0 means no active delay.
 */
@Stable
internal class PinLockUiState {
    var wrong by mutableStateOf(false)
    var submitting by mutableStateOf(false)
    var delayEnded by mutableStateOf(false)
    var neutralDelay by mutableStateOf(false)
    var remainingMillis by mutableLongStateOf(0L)
    var deadline by mutableLongStateOf(0L)
    var token by mutableIntStateOf(0)

    val delayActive: Boolean get() = remainingMillis > 0L

    fun startDelay(millis: Long, neutral: Boolean) {
        if (millis <= 0L) return
        deadline = SystemClock.elapsedRealtime() + millis
        remainingMillis = millis
        neutralDelay = neutral
        wrong = false
        delayEnded = false
        token++
    }
}

/** Ticks [PinLockUiState.remainingMillis] down to zero from the deadline, then marks the delay as ended. */
@Composable
internal fun PinDelayTicker(ui: PinLockUiState) {
    LaunchedEffect(ui.token) {
        val end = ui.deadline
        if (end == 0L) return@LaunchedEffect
        while (true) {
            val left = end - SystemClock.elapsedRealtime()
            if (left <= 0L) {
                ui.remainingMillis = 0L
                ui.deadline = 0L
                ui.delayEnded = true
                break
            }
            ui.remainingMillis = left
            delay(left.coerceAtMost(500L))
        }
    }
}

/** Applies a bounded unlock result to the transient UI state. A correct PIN needs nothing: the gate leaves the surface. */
private fun PinLockUiState.applyResult(result: PinUnlockResult) {
    when (result) {
        PinUnlockResult.Unlocked, PinUnlockResult.NotInPinMode, PinUnlockResult.AlreadyInFlight -> Unit
        is PinUnlockResult.Rejected -> when (val reason = result.reason) {
            is PinRejection.Wrong -> {
                val millis = delayMillisOfTier(reason.delay)
                if (millis > 0L) startDelay(millis, false) else wrong = true
            }
            is PinRejection.Delayed ->
                startDelay(reason.remainingMillis, reason.reason == PinDelayReason.ATTEMPT_STATE_UNREADABLE)
            // The recovery view arrives through the published presentation; an unavailable attempt state shows nothing.
            is PinRejection.VerifierUnreadable, PinRejection.Unavailable -> Unit
        }
    }
}

/** Submits one confirmed entry. The array is zeroed in `finally` whatever happens, including a cancelled launch. */
private fun submitPin(
    digits: CharArray,
    ui: PinLockUiState,
    actions: PinLockActions,
    scope: CoroutineScope
) {
    if (ui.submitting) {
        digits.fill('\u0000')
        return
    }
    ui.submitting = true
    ui.wrong = false
    ui.delayEnded = false
    scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            ui.applyResult(actions.submitUnlockPin(digits))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Bounded: nothing is logged or shown; the pad re-enables in finally (A4).
        } finally {
            digits.fill('\u0000')
            ui.submitting = false
        }
    }
}

/** The status line: recovery, then delay, then "you can try again", then a plain wrong-PIN line, else none. */
@Composable
private fun pinLockMessage(ui: PinLockUiState, recovery: PinRecovery?): String? = when {
    recovery != null -> stringResource(R.string.applock_pin_unreadable)
    ui.delayActive -> stringResource(
        if (ui.neutralDelay) R.string.applock_pin_wait_neutral else R.string.applock_pin_wait,
        pinDurationText(coarseRemainingSeconds(ui.remainingMillis))
    )
    ui.delayEnded -> stringResource(R.string.applock_pin_wait_over)
    ui.wrong -> stringResource(R.string.applock_pin_wrong)
    else -> null
}

@Composable
private fun ForgotPinDialog(onOpenAppSettings: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.applock_pin_forgot_title)) },
        text = { Text(stringResource(R.string.applock_pin_forgot_body)) },
        confirmButton = {
            TextButton(onClick = onOpenAppSettings, modifier = Modifier.testTag(PinLockTags.FORGOT_OPEN_SETTINGS)) {
                Text(stringResource(R.string.applock_pin_forgot_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, modifier = Modifier.testTag(PinLockTags.FORGOT_CLOSE)) {
                Text(stringResource(R.string.applock_pin_forgot_close))
            }
        },
        modifier = Modifier.testTag(PinLockTags.FORGOT_DIALOG)
    )
}

/**
 * The full-screen lock surface for effective `APP_PIN` mode while `LOCKED` (ux-flows 2.4, 4.4; INV-38, INV-40).
 *
 * Title, [PinDotRow] and [PinPad]; a wrong-PIN line; a countdown while a delay is active (the pad is disabled and
 * cleared, the text moves in coarse steps and is not a live region); and, for an unreadable verifier, no pad but a
 * retry action (only when [PinRecovery.retryOffered]) and the forgotten-PIN help. There is no device-credential
 * fallback. The fingerprint key shows only when [PinPresentation.canOfferFingerprint] is true; it is a tap, never an
 * effect (INV-34). All controller calls run in the gate's [scope], so a successful unlock that removes this surface
 * from composition cannot cancel the controller's post-unlock work.
 *
 * The digits live only in the [PinEntryBuffer] (plain `remember`, zeroed on dispose) and in the array handed to
 * [PinLockActions.submitUnlockPin]; nothing is a String, saved or logged. [promptInFlight] disables the pad while a
 * fingerprint prompt is open.
 */
@Composable
fun PinLockSurface(
    pin: PinPresentation,
    promptInFlight: Boolean,
    actions: PinLockActions,
    scope: CoroutineScope,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ui = remember { PinLockUiState() }
    val buffer = rememberPinEntryBuffer()
    var forgotOpen by remember { mutableStateOf(false) }
    PinDelayTicker(ui)
    LaunchedEffect(pin.delayRemainingMillis) {
        val published = pin.delayRemainingMillis
        if (published != null && published > 0L && !ui.delayActive) ui.startDelay(published, pin.delayNeutral)
    }
    val recovery = pin.recovery
    val padEnabled = !promptInFlight && !ui.submitting && !ui.delayActive && recovery == null
    val fingerprintEnabled = !promptInFlight && !ui.submitting
    val message = pinLockMessage(ui, recovery)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SemanticColors.BaseNavy)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(PinLockTags.SURFACE)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Lock, null, tint = SemanticColors.PrimaryIndigo, modifier = Modifier.size(48.dp))
            Spacer(Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.applock_pin_enter_title),
                style = MaterialTheme.typography.headlineSmall,
                color = SemanticColors.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.size(16.dp))
            if (recovery == null) PinDotRow(count = buffer.length)
            Spacer(Modifier.size(8.dp))
            // B5: the delay start is announced once. During the delay the spoken text stays the one captured when the
            // delay began, so the countdown ticks are not re-announced; the visible text still counts down.
            val delayAnnouncement = remember(ui.token) { arrayOfNulls<String>(1) }
            if (ui.delayActive && message != null && delayAnnouncement[0] == null) delayAnnouncement[0] = message
            val frozenText = if (ui.delayActive) delayAnnouncement[0] else null
            val live = if (message != null) {
                Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    if (frozenText != null) contentDescription = frozenText
                }
            } else {
                Modifier
            }
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary,
                textAlign = TextAlign.Center,
                minLines = 2,
                modifier = live.widthIn(max = 360.dp).fillMaxWidth()
                    .testTag(if (recovery != null) PinLockTags.UNREADABLE else PinLockTags.MESSAGE)
            )
            Spacer(Modifier.size(16.dp))
            if (recovery == null) {
                PinPad(
                    buffer = buffer,
                    enabled = padEnabled,
                    onConfirm = { digits -> submitPin(digits, ui, actions, scope) },
                    showFingerprintKey = pin.canOfferFingerprint,
                    onFingerprint = {
                        scope.launch {
                            try {
                                actions.requestFingerprintUnlock()
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                            }
                        }
                    },
                    fingerprintEnabled = fingerprintEnabled // allowed during a PIN delay (INV-39)
                )
            } else if (recovery.retryOffered) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                actions.retryPinRecovery()
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                            }
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(PinLockTags.RETRY)
                ) { Text(stringResource(R.string.applock_pin_retry)) }
            }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = { forgotOpen = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(PinLockTags.FORGOT)) {
                Text(stringResource(R.string.applock_pin_forgot))
            }
        }
    }
    if (forgotOpen) {
        ForgotPinDialog(onOpenAppSettings = onOpenAppSettings, onClose = { forgotOpen = false })
    }
}
