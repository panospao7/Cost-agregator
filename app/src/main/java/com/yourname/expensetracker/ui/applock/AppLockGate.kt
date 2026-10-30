package com.yourname.expensetracker.ui.applock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.domain.privacy.applock.LockCause
import com.yourname.expensetracker.domain.privacy.applock.EffectiveLockMode
import com.yourname.expensetracker.domain.privacy.applock.LockNotice
import com.yourname.expensetracker.domain.privacy.applock.PinPresentation
// Explicit same-package import kept on purpose: the db_access guard parser resolves the AppLockContent typealias only via an import (S20).
import com.yourname.expensetracker.ui.applock.AppLockContent
import com.yourname.expensetracker.ui.applock.pin.PinLockActions
import com.yourname.expensetracker.ui.applock.pin.PinLockSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The gate's content slot. A named alias (not an inline function type) so the db_guard callable parser, which
 * has no `@` token, resolves every parameter type of the gate and shell composables.
 */
typealias AppLockContent = @Composable () -> Unit

/** What the gate draws for a state (domain-spec 3.6, INV-26, INV-53). */
enum class AppLockGateRender {
    /** `RESOLVING`: the neutral surface, no financial UI. */
    NEUTRAL,

    /** `LOCKED` and `AUTHENTICATING(UNLOCK)`: the app-lock surface only. The content slot is not composed. */
    LOCK_SURFACE,

    /**
     * `DISABLED`, `UNLOCKED`, `INERT`, and `AUTHENTICATING(ENABLE | CONFIRM_CHANGE)` rendered as their origin:
     * the notice slot plus the content slot (INV-53).
     */
    OPEN
}

/**
 * The one mapping from controller state to rendering (spec 3.6, AMD-22). `AUTHENTICATING` is decided by its
 * purpose alone, never by the attempt id, so entering and leaving an open-origin prompt cannot change the branch.
 */
fun AppLockState.toGateRender(): AppLockGateRender = when (this) {
    AppLockState.Resolving -> AppLockGateRender.NEUTRAL
    is AppLockState.Locked -> AppLockGateRender.LOCK_SURFACE
    is AppLockState.Authenticating ->
        if (purpose.isOpenOrigin) AppLockGateRender.OPEN else AppLockGateRender.LOCK_SURFACE
    AppLockState.Disabled, AppLockState.Unlocked, is AppLockState.Inert -> AppLockGateRender.OPEN
}

/**
 * The controller's published pair as a plain interface, so the gate does not depend on the controller, on Hilt or
 * on injection. S20 adapts `AppLockController.state` and `AppLockController.notice` to it.
 */
interface AppLockGateSource {
    val state: StateFlow<AppLockState>
    val notice: StateFlow<LockNotice?>

    /** The PIN presentation (S27). Defaults to the device-auth presentation, so a plain source keeps today's surface. */
    val pin: StateFlow<PinPresentation> get() = DEVICE_AUTH_PIN_PRESENTATION

    /** The PIN lock surface's controller calls, or null when the source has none (no PIN surface is then drawn). */
    val pinActions: PinLockActions? get() = null
}

private val DEVICE_AUTH_PIN_PRESENTATION: StateFlow<PinPresentation> = MutableStateFlow(PinPresentation.INITIAL)

/**
 * The app-lock gate (domain-spec INV-26, INV-27, INV-34, INV-35, INV-51, INV-52, INV-53; ux-flows section 5).
 *
 * It is the sole place that decides whether [content] (`MainScreen`, supplied by S20) is composed:
 * - `RESOLVING`, `LOCKED`, `AUTHENTICATING(UNLOCK)`: [content] is NOT composed and no notice is composed. The
 *   surface is the neutral surface (`RESOLVING`) or the lock surface (the other two). The Unlock button is
 *   disabled while the UNLOCK prompt is in flight and enabled again once the state is back at `LOCKED` (T9, T10,
 *   T35, AE-118). Nothing here launches a prompt: Unlock is a tap, never an effect (INV-34).
 * - `DISABLED`, `UNLOCKED`, `INERT`: the notice slot, then [content].
 * - `AUTHENTICATING(ENABLE | CONFIRM_CHANGE)`: exactly the origin's rendering. The branch is the same one as the
 *   origin's, so [content] is neither disposed nor rebuilt when the prompt opens or closes, composition-scoped
 *   coroutines are kept, `remember` state is kept and `LaunchedEffect(Unit)` does not re-run (INV-53, AE-113,
 *   AE-114). The lock surface is not composed and the notice slot keeps the origin's notice (AE-115).
 *
 * The gate reads [state] and [notice] as parameters. [notice] is composed only inside the open branch, so a notice
 * can never be drawn next to a closed state, whatever instant the two values were sampled at (S18-ii-c ISSUE-1).
 *
 * Starts nothing because of a prompt (INV-53): no clipboard read, no permission prompt, no navigation, no deep-link
 * lookup, no timer. The gate calls its callbacks only from a user tap, never from composition or an effect, and
 * never calls the controller or the authenticator itself (R2: no transition from a collector or a composition).
 * In-app notice actions are not composed while [inAppActionsAllowed] is false (operational lock, INV-52). The
 * operational lock screen itself stays inside [content], unchanged (spec 3.6).
 *
 * [unlockMessage] is an optional bounded status line for the lock surface. When it is null and the lock cause is
 * `SETTINGS_UNREADABLE`, the surface says so (T5). No status line is shown while the prompt is in flight.
 */
@Composable
fun AppLockGate(
    state: AppLockState,
    notice: LockNotice?,
    inAppActionsAllowed: Boolean,
    onUnlock: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenAppLockSettings: () -> Unit,
    onReviewSettings: () -> Unit,
    modifier: Modifier = Modifier,
    unlockMessage: LockSurfaceMessage? = null,
    pin: PinPresentation = PinPresentation.INITIAL,
    pinActions: PinLockActions? = null,
    content: AppLockContent
) {
    // The gate's own scope: PIN calls started by the PIN surface outlive that surface when an unlock removes it.
    val gateScope = rememberCoroutineScope()
    val context = LocalContext.current
    when (state.toGateRender()) {
        AppLockGateRender.NEUTRAL ->
            AppLockSurface(
                mode = LockSurfaceMode.NEUTRAL,
                unlockEnabled = false,
                message = null,
                onUnlock = onUnlock,
                modifier = modifier
            )

        // Effective APP_PIN: the in-app PIN surface replaces the device-auth surface. Nothing here launches a prompt (INV-34).
        AppLockGateRender.LOCK_SURFACE ->
            if (pin.effectiveLockMode == EffectiveLockMode.APP_PIN && pinActions != null) {
                PinLockSurface(
                    pin = pin,
                    promptInFlight = state is AppLockState.Authenticating,
                    actions = pinActions,
                    scope = gateScope,
                    onOpenAppSettings = { openAppDetailsSettings(context) },
                    modifier = modifier
                )
            } else AppLockSurface(
                mode = LockSurfaceMode.LOCK,
                unlockEnabled = state is AppLockState.Locked,
                message = lockSurfaceMessage(state, unlockMessage),
                onUnlock = onUnlock,
                modifier = modifier
            )

        AppLockGateRender.OPEN ->
            Column(modifier = modifier.fillMaxSize()) {
                // The app is edge-to-edge: the banner pads itself for the top and side system insets, and the
                // content slot consumes exactly those insets only while a banner is composed, so the content does
                // not pad for them twice. The slot is the SAME Box in both cases (conditional modifier only), so
                // toggling the banner never disposes or rebuilds the content (INV-53).
                val bannerInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                )
                if (notice != null) {
                    LockNoticeBanner(
                        notice = notice,
                        inAppActionsAllowed = inAppActionsAllowed,
                        onOpenSecuritySettings = onOpenSecuritySettings,
                        onOpenAppLockSettings = onOpenAppLockSettings,
                        onReviewSettings = onReviewSettings,
                        modifier = Modifier.windowInsetsPadding(bannerInsets)
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .then(if (notice != null) Modifier.consumeWindowInsets(bannerInsets) else Modifier)
                        .testTag(AppLockUiTags.GATE_CONTENT_SLOT)
                ) {
                    content()
                }
            }
    }
}

/**
 * Overload for a plain [AppLockGateSource]. Both flows are collected with `collectAsState`, i.e. on the
 * composition's Main dispatcher, never on `Main.immediate` or `Unconfined` (R2). The collection only reads; it
 * never calls a transition. [AppLockGate] still composes the notice only in an open state.
 */
@Composable
fun AppLockGate(
    source: AppLockGateSource,
    inAppActionsAllowed: Boolean,
    onUnlock: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenAppLockSettings: () -> Unit,
    onReviewSettings: () -> Unit,
    modifier: Modifier = Modifier,
    unlockMessage: LockSurfaceMessage? = null,
    content: AppLockContent
) {
    val state = source.state.collectAsState().value
    val notice = source.notice.collectAsState().value
    val pin = source.pin.collectAsState().value
    AppLockGate(
        state = state,
        notice = notice,
        pin = pin,
        pinActions = source.pinActions,
        inAppActionsAllowed = inAppActionsAllowed,
        onUnlock = onUnlock,
        onOpenSecuritySettings = onOpenSecuritySettings,
        onOpenAppLockSettings = onOpenAppLockSettings,
        onReviewSettings = onReviewSettings,
        modifier = modifier,
        unlockMessage = unlockMessage,
        content = content
    )
}

/**
 * Opens this app's system details screen (the "clear data" route of the forgotten-PIN help, ux-flows 3.11), from a tap.
 * A device without the screen is ignored; no intent content or exception text is logged.
 */
internal fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (notFound: ActivityNotFoundException) {
        // No such screen on this device: nothing to open.
    }
}

/** The status line for the lock surface: none while the prompt is in flight; otherwise the caller's, else T5's. */
internal fun lockSurfaceMessage(state: AppLockState, requested: LockSurfaceMessage?): LockSurfaceMessage? {
    if (state !is AppLockState.Locked) return null
    if (requested != null) return requested
    return if (state.cause == LockCause.SETTINGS_UNREADABLE) LockSurfaceMessage.SETTING_UNREADABLE else null
}
