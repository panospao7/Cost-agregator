package com.yourname.expensetracker.ui.screens.privacysettings

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.PinStep
import com.yourname.expensetracker.ui.applock.pin.PinDotRow
import com.yourname.expensetracker.ui.applock.pin.PinPad
import com.yourname.expensetracker.ui.applock.pin.coarseRemainingSeconds
import com.yourname.expensetracker.ui.applock.pin.pinDurationText
import com.yourname.expensetracker.ui.applock.pin.rememberPinEntryBuffer
import com.yourname.expensetracker.ui.theme.SemanticColors
import kotlinx.coroutines.delay

/** Stable test tags of [PinConfirmDialog]. Tags carry no user data. */
object PinConfirmTags {
    const val DIALOG = "applock_pin_confirm_dialog"
    const val MESSAGE = "applock_pin_confirm_message"
    const val CANCEL = "applock_pin_confirm_cancel"
}

/**
 * The PIN confirm dialog of a lengthening timeout change or a weakening review-save in effective `APP_PIN` mode (S27b,
 * T30, AMD-2). Title per step, [PinDotRow] and [PinPad], a message line and the delay countdown, and Cancel.
 *
 * The digits live only in the [rememberPinEntryBuffer] buffer (plain `remember`, not saveable, INV-38) and in the array
 * handed to [onSubmit], which the receiver owns and must zero. While a delay is active or the check runs the pad is
 * disabled (and cleared by the pad). The countdown mirrors [PinConfirmUiState.delayRemainingMillis] (the controller's
 * published delay) and moves in coarse steps; a neutral copy is used when [PinConfirmUiState.delayNeutral]. The content
 * scrolls, so a large font scale or a small window never hides the pad.
 */
@Composable
fun PinConfirmDialog(
    state: PinConfirmUiState,
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buffer = rememberPinEntryBuffer()
    var remainingMillis by remember { mutableLongStateOf(state.delayRemainingMillis ?: 0L) }
    var delayEnded by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) {
        if (state.busy) delayEnded = false
    }
    LaunchedEffect(state.delayRemainingMillis) {
        val published = state.delayRemainingMillis
        if (published == null || published <= 0L) {
            remainingMillis = 0L
            return@LaunchedEffect
        }
        delayEnded = false
        val end = SystemClock.elapsedRealtime() + published
        while (true) {
            val left = end - SystemClock.elapsedRealtime()
            if (left <= 0L) {
                remainingMillis = 0L
                delayEnded = true
                break
            }
            remainingMillis = left
            delay(left.coerceAtMost(500L))
        }
    }
    val delayActive = remainingMillis > 0L
    val padEnabled = !state.busy && !delayActive
    val message: String? = when {
        delayActive -> stringResource(
            if (state.delayNeutral) R.string.applock_pin_wait_neutral else R.string.applock_pin_wait,
            pinDurationText(coarseRemainingSeconds(remainingMillis))
        )
        state.busy -> null
        delayEnded -> stringResource(R.string.applock_pin_wait_over)
        state.message == PinConfirmMessage.WRONG -> stringResource(R.string.applock_pin_wrong)
        state.message == PinConfirmMessage.DELAYED -> stringResource(R.string.applock_pin_confirm_unavailable)
        else -> null
    }
    val titleRes = if (state.step == PinStep.TIMEOUT_CONFIRM) {
        R.string.applock_pin_confirm_timeout_title
    } else {
        R.string.applock_pin_confirm_review_title
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PinDotRow(count = buffer.length)
                // Polite live region for the wrong / ended / unavailable lines. The countdown is not a live region: it
                // updates every second near the end and would flood the screen reader (same as the lock surface).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp)
                        .semantics(mergeDescendants = true) {
                            if (!delayActive) liveRegion = LiveRegionMode.Polite
                        }
                        .testTag(PinConfirmTags.MESSAGE),
                    contentAlignment = Alignment.Center
                ) {
                    if (message != null) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (delayActive || delayEnded) {
                                SemanticColors.TextSecondary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            textAlign = TextAlign.Center
                        )
                    }
                }
                PinPad(
                    buffer = buffer,
                    enabled = padEnabled,
                    onConfirm = onSubmit,
                    confirmText = stringResource(R.string.applock_pin_confirm_button)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !state.busy,
                modifier = Modifier.testTag(PinConfirmTags.CANCEL)
            ) {
                Text(stringResource(R.string.applock_pin_confirm_cancel))
            }
        },
        modifier = modifier.testTag(PinConfirmTags.DIALOG)
    )
}
