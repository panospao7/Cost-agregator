package com.yourname.expensetracker.ui.applock

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yourname.expensetracker.data.backup.AppOperationalState
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.AuthPurpose
import com.yourname.expensetracker.domain.privacy.applock.LockNotice
import com.yourname.expensetracker.ui.applock.AppLockContent
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * The controller's published pair, handed to the gate untouched (S19 CF-1). Both properties ARE the controller's own
 * `StateFlow` instances: no `stateIn`, no `map`, no copy with an open initial value, so the gate can never see a
 * value the controller did not publish.
 */
internal class ControllerGateSource(controller: AppLockController) : AppLockGateSource {
    override val state: StateFlow<AppLockState> = controller.state
    override val notice: StateFlow<LockNotice?> = controller.notice
}

/**
 * Whether the in-app notice actions may be offered or followed (INV-52). Only the two normal modes allow them; every
 * restore, restart or critical-recovery mode forbids them. The `when` has NO else, so a new operational state does
 * not compile until it is classified here (fail-closed).
 */
internal fun AppOperationalState.allowsInAppLockActions(): Boolean = when (this) {
    AppOperationalState.Normal -> true
    AppOperationalState.BackupExporting -> true
    is AppOperationalState.RestoreInProgress -> false
    AppOperationalState.RestartRequiredAfterRestore -> false
    is AppOperationalState.CriticalRecoveryRequired -> false
}

/**
 * The Security settings hand-off of the "turn on a screen lock" notice (S19 CF-3). It sends a constant
 * `ACTION_SECURITY_SETTINGS` intent with no extras, directly from the tap, and never through the MainViewModel
 * navigation channel. A device without such a screen is handled with one bounded, constant Timber reason: no
 * exception, message, class name or intent content is logged, and no `PrivacyDiagnosticCode` is involved.
 */
internal object SecuritySettingsLauncher {

    /** The only text ever logged for a failed hand-off. */
    internal const val REASON_ACTIVITY_NOT_FOUND = "applock_security_settings_unavailable:ACTIVITY_NOT_FOUND"

    internal fun intent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)

    /** @return true when the activity was started, false when the device has no such screen. */
    internal fun open(context: Context, start: (Intent) -> Unit = { context.startActivity(it) }): Boolean =
        try {
            start(intent())
            true
        } catch (notFound: ActivityNotFoundException) {
            Timber.w(REASON_ACTIVITY_NOT_FOUND)
            false
        }
}

/**
 * The status line of one lock episode (S19 CF-4, aligned by G1c), derived only from the bounded published
 * [AppLockState] values. The controller publishes no end-kind (cancel, lockout, error), so the shell cannot say
 * anything honest about an ended UNLOCK prompt: every UNLOCK-prompt end returns silently to the lock surface
 * (`LOCKED`, Unlock enabled, no auto-reprompt) and this function yields no line for that transition. The
 * `LockSurfaceMessage.TRY_AGAIN` entry and its string are kept, unused here. A specific line for non-cancel
 * failures (lockout, transient) waits for a bounded published end-kind (CF-4b, deferred).
 *
 * The line is cleared whenever the state is not `LOCKED` and when `LOCKED` is entered from anywhere but the same
 * `LOCKED` cause (a new episode). A `SETTINGS_UNREADABLE` lock keeps the surface's own T5 line instead.
 */
internal fun nextUnlockMessage(
    previous: AppLockState?,
    current: AppLockState,
    held: LockSurfaceMessage?
): LockSurfaceMessage? {
    if (current !is AppLockState.Locked) return null
    if (previous == current) return held
    return when {
        previous is AppLockState.Authenticating && previous.purpose == AuthPurpose.UNLOCK -> null
        previous is AppLockState.Locked && previous.cause == current.cause -> held
        else -> null
    }
}

/**
 * Holds the previous state and the held line for one shell. [messageFor] is idempotent for the same state, so a
 * repeated composition with the same input changes nothing. Main thread only (composition).
 */
internal class UnlockMessageTracker {
    private var previous: AppLockState? = null
    private var message: LockSurfaceMessage? = null

    fun messageFor(current: AppLockState): LockSurfaceMessage? {
        if (current == previous) return message
        message = nextUnlockMessage(previous, current, message)
        previous = current
        return message
    }
}

/**
 * Whether Back must leave the app instead of reaching anything behind the gate (S19 decision 4): true for every state
 * the gate renders as the neutral surface (`RESOLVING`) or the lock surface (`LOCKED`, `AUTHENTICATING(UNLOCK)`),
 * derived from the gate's own mapping so the two cannot drift. A closed gate composes no content, so no content
 * Back handler exists to run.
 */
internal fun AppLockState.backLeavesTheApp(): Boolean = toGateRender() != AppLockGateRender.OPEN

/**
 * Moves the Activity's task to the back (Back on a closed gate). Unwraps `ContextWrapper`s to find the Activity;
 * returns false when the context holds none (nothing to move).
 */
internal fun Context.moveTaskToBackIfActivity(): Boolean {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.moveTaskToBack(true)
        current = current.baseContext
    }
    return false
}

/** The key under which the protected content's `rememberSaveable` state is kept while the gate removes it (T14). */
internal const val APP_LOCK_SHELL_CONTENT_KEY = "applock_shell_content"

/**
 * The Activity-side app-lock shell (S20-c): wires the controller to [AppLockGate] and hosts `MainScreen` as [content].
 * It is the only caller of the gate and `MainScreen` is composed only from here.
 *
 * - The gate reads the controller's own flows through [ControllerGateSource] with the source overload (CF-1, CF-2).
 * - Unlock calls `onUnlockTapped()` from the button tap only (INV-34); nothing here calls the controller from
 *   composition.
 * - The content sits in a saveable state holder, so its `rememberSaveable` state survives the gate removing it while
 *   LOCKED and restores when it returns (T14). The gate itself keeps INV-53 for open-origin prompts.
 * - Back while the gate is closed (`RESOLVING`, `LOCKED`, `AUTHENTICATING(UNLOCK)`) moves the task to the back instead
 *   of reaching anything behind the gate (S19 decision 4).
 * - The notice actions re-check the operational state at tap time, so a restore that began after the banner was drawn
 *   cannot be followed (CF-3); the in-app actions are composed only while the state allows them (INV-52).
 */
@Composable
fun AppLockShell(
    controller: AppLockController,
    operationalState: StateFlow<AppOperationalState>,
    navigateTo: (NavigationDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: AppLockContent
) {
    val context = LocalContext.current
    val source = remember(controller) { ControllerGateSource(controller) }
    AppLockShell(
        source = source,
        operationalState = operationalState,
        onUnlockTapped = { controller.onUnlockTapped() },
        onOpenSecuritySettings = { SecuritySettingsLauncher.open(context) },
        onOpenAppLockSettings = { navigateTo(NavigationDestination.PrivacySettings) },
        onBackWhileClosed = { context.moveTaskToBackIfActivity() },
        modifier = modifier,
        content = content
    )
}

/** The shell over a plain [AppLockGateSource] and plain callbacks (the testable core of the overload above). */
@Composable
internal fun AppLockShell(
    source: AppLockGateSource,
    operationalState: StateFlow<AppOperationalState>,
    onUnlockTapped: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenAppLockSettings: () -> Unit,
    onBackWhileClosed: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: AppLockContent
) {
    val operational =operationalState.collectAsState().value
    val tracker = remember(source) { UnlockMessageTracker() }
    val gateState = source.state.collectAsState().value
    val unlockMessage = tracker.messageFor(gateState)
    // Back on the neutral or lock surface leaves the app (S19 decision 4). Composed before the gate, and enabled only
    // while the gate is closed, so it never competes with the content's own Back handling (none exists then).
    BackHandler(enabled = gateState.backLeavesTheApp()) { onBackWhileClosed() }
    val stateHolder = rememberSaveableStateHolder()
    // Tap-time re-check: the value read here is the current one, not the one the banner was composed with.
    val openAppLockSettingsChecked = {
        if (operationalState.value.allowsInAppLockActions()) onOpenAppLockSettings()
    }
    AppLockGate(
        source = source,
        inAppActionsAllowed = operational.allowsInAppLockActions(),
        onUnlock = onUnlockTapped,
        onOpenSecuritySettings = onOpenSecuritySettings,
        onOpenAppLockSettings = openAppLockSettingsChecked,
        onReviewSettings = openAppLockSettingsChecked,
        modifier = modifier,
        unlockMessage = unlockMessage
    ) {
        stateHolder.SaveableStateProvider(APP_LOCK_SHELL_CONTENT_KEY) { content() }
    }
}
