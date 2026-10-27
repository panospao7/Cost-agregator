package com.yourname.expensetracker.ui.applock

import androidx.annotation.StringRes
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.theme.SemanticColors

/** Stable test tags for the app-lock UI (S19). Tags carry no user data. */
object AppLockUiTags {
    const val GATE_NEUTRAL = "applock_gate_neutral"
    const val GATE_SURFACE = "applock_gate_surface"
    const val GATE_CONTENT_SLOT = "applock_gate_content_slot"
    const val UNLOCK_BUTTON = "applock_unlock_button"
    const val STATUS_MESSAGE = "applock_status_message"
    const val NOTICE_BANNER = "applock_notice_banner"
    const val NOTICE_ACTION_SECURITY_SETTINGS = "applock_notice_action_security_settings"
    const val NOTICE_ACTION_APP_LOCK_SETTINGS = "applock_notice_action_app_lock_settings"
    const val NOTICE_ACTION_REVIEW = "applock_notice_action_review"
}

/**
 * A bounded status line for the lock surface (ux-flows 2.3, 9.2). It is a closed set of constant resources: no
 * platform error text, code or exception message ever reaches the screen (INV-35, INV-37).
 */
enum class LockSurfaceMessage(@StringRes val textRes: Int) {
    TRY_AGAIN(R.string.applock_msg_try_again),
    TOO_MANY_ATTEMPTS(R.string.applock_msg_too_many_attempts),
    BIOMETRIC_LOCKED(R.string.applock_msg_biometric_locked),
    UNAVAILABLE_NOW(R.string.applock_msg_unavailable_now),
    SETTING_UNREADABLE(R.string.applock_msg_setting_unreadable)
}

/** What the lock surface draws. */
enum class LockSurfaceMode {
    /** `RESOLVING` (spec 3.6): a neutral surface, no text, no button, nothing to act on. */
    NEUTRAL,

    /** `LOCKED` or `AUTHENTICATING(UNLOCK)`: the "App locked" surface with the Unlock button. */
    LOCK
}

/**
 * The app-lock surface (domain-spec INV-35, ux-flows 2.3). It is composed ALONE: no banner, dialog, snackbar,
 * navigation or content comes with it, and it shows no amounts, counts, badges, notification-derived text or
 * held-link hint.
 *
 * It starts nothing. The Unlock button calls [onUnlock] only when the person taps it; there is no auto-prompt,
 * no timer and no effect that fires [onUnlock] (INV-34). [unlockEnabled] is false while the UNLOCK prompt is in
 * flight, so a double tap cannot start a second prompt (AE-46); the caller turns it back on when the state returns
 * to `LOCKED` (T9, T10, T35, AE-118).
 *
 * Layout is a single scrollable, centred column so that it survives 200% font scale, landscape and small windows.
 * Padding uses start/end so the surface is RTL-safe. The status area reserves two lines so the button never jumps.
 */
@Composable
fun AppLockSurface(
    mode: LockSurfaceMode,
    unlockEnabled: Boolean,
    message: LockSurfaceMessage?,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (mode == LockSurfaceMode.NEUTRAL) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(SemanticColors.BaseNavy)
                .testTag(AppLockUiTags.GATE_NEUTRAL)
        )
        return
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SemanticColors.BaseNavy)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(AppLockUiTags.GATE_SURFACE)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = SemanticColors.PrimaryIndigo,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.applock_lock_title),
                style = MaterialTheme.typography.headlineSmall,
                color = SemanticColors.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.applock_lock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(24.dp))
            Button(
                onClick = onUnlock,
                enabled = unlockEnabled,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag(AppLockUiTags.UNLOCK_BUTTON)
            ) {
                Text(stringResource(R.string.applock_unlock_button))
            }
            Spacer(Modifier.size(16.dp))
            // Reserved status area: two lines, polite live region only while it carries a message.
            val statusModifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .testTag(AppLockUiTags.STATUS_MESSAGE)
                .let { base ->
                    if (message != null) base.semantics { liveRegion = LiveRegionMode.Polite } else base
                }
            Text(
                text = if (message != null) stringResource(message.textRes) else "",
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary,
                textAlign = TextAlign.Center,
                minLines = 2,
                modifier = statusModifier
            )
        }
    }
}
