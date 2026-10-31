package com.yourname.expensetracker.ui.applock.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.pin.PinFormatPolicy
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Holds the digits of a PIN being typed on [PinPad] (INV-38, S24 condition D).
 *
 * The digits live ONLY in a fixed-size [CharArray]: they are never converted to a String, never logged, and never
 * stored in a Bundle, `SavedStateHandle` or `rememberSaveable`. Create it with [rememberPinEntryBuffer], which uses
 * plain `remember`, so after process death or a recreate the buffer starts empty. Only the digit COUNT is observable
 * by Compose ([length]); digits are never exposed for display.
 */
class PinEntryBuffer(private val capacity: Int = PinFormatPolicy.MAX_LENGTH) {
    private val chars = CharArray(capacity)
    private var lengthState by mutableIntStateOf(0)

    /** Number of digits entered. Snapshot-observable so the dot row recomposes. */
    val length: Int get() = lengthState

    /** Appends one ASCII digit. Returns false (and changes nothing) for a non-digit or when the buffer is full. */
    fun append(digit: Char): Boolean {
        if (digit < '0' || digit > '9') return false
        val n = lengthState
        if (n >= capacity) return false
        chars[n] = digit
        lengthState = n + 1
        return true
    }

    /** Removes the last digit, zero-filling its slot. No-op when empty. */
    fun deleteLast() {
        val n = lengthState
        if (n == 0) return
        chars[n - 1] = ZERO
        lengthState = n - 1
    }

    /** Zero-fills the whole buffer and resets the length to 0. */
    fun clear() {
        chars.fill(ZERO)
        lengthState = 0
    }

    /**
     * Returns a copy of the entered digits, then zero-fills this buffer. The caller owns the returned array and must
     * zero it as soon as it has been used.
     */
    fun consumeCopy(): CharArray {
        val copy = chars.copyOf(lengthState)
        clear()
        return copy
    }

    private companion object {
        const val ZERO: Char = '\u0000'
    }
}

/** Stable test tags for the PIN pad. Tags carry no user data. */
object PinPadTags {
    const val DOT_ROW = "applock_pin_dot_row"
    const val KEY_DELETE = "applock_pin_key_delete"
    const val KEY_FINGERPRINT = "applock_pin_key_fingerprint"
    const val CONFIRM = "applock_pin_confirm"
    const val KEY_PREFIX = "applock_pin_key_"
}

/**
 * Creates a [PinEntryBuffer] that survives recomposition only. It is deliberately NOT saveable (INV-38): after process
 * death or a recreate it starts empty. It is zero-filled when it leaves composition.
 */
@Composable
fun rememberPinEntryBuffer(): PinEntryBuffer {
    val buffer = remember { PinEntryBuffer() }
    DisposableEffect(buffer) { onDispose { buffer.clear() } }
    // The app going to the background clears typed digits (S27 A1).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { buffer.clear() }
    return buffer
}

/**
 * The dot display: at least [PinFormatPolicy.MIN_LENGTH] slots, filled one per entered digit, never any digit.
 * TalkBack reads only "N digits entered"; the individual dots are hidden from accessibility.
 */
@Composable
fun PinDotRow(count: Int, modifier: Modifier = Modifier) {
    val slots = count.coerceIn(PinFormatPolicy.MIN_LENGTH, PinFormatPolicy.MAX_LENGTH)
    val state = pluralStringResource(R.plurals.applock_pin_digits_state, count, count)
    Row(
        modifier = modifier
            .heightIn(min = 24.dp)
            .testTag(PinPadTags.DOT_ROW)
            .clearAndSetSemantics { stateDescription = state },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until slots) {
            val filled = i < count
            Box(
                Modifier
                    .size(if (filled) 14.dp else 12.dp)
                    .clip(CircleShape)
                    .background(if (filled) SemanticColors.PrimaryIndigo else SemanticColors.TextSecondary)
            )
        }
    }
}

private val KEY_SIZE = 64.dp

@Composable
private fun PinKey(
    label: String,
    description: String?,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier
            .size(KEY_SIZE)
            .testTag(tag)
            .semantics { if (description != null) contentDescription = description }
    ) {
        Text(text = label, textAlign = TextAlign.Center)
    }
}

private val DIGIT_ROWS = listOf("123", "456", "789")

/** Maps a hardware key to an ASCII digit, or null. */
private fun pinDigitFor(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

/**
 * Hardware-keyboard handling for [PinPad] (ux-flows 8). Digits append, Backspace/Delete remove the last digit, Enter
 * is always consumed while enabled, but confirms only once [PinFormatPolicy.MIN_LENGTH] digits are entered (so a short
 * Enter can never fall through to a focused key or view). Everything else is not consumed.
 * Nothing is logged, echoed or announced. Does nothing while [enabled] is false.
 */
private fun handlePinKeyEvent(
    event: KeyEvent,
    enabled: Boolean,
    buffer: PinEntryBuffer,
    confirm: () -> Unit
): Boolean {
    if (!enabled || event.type != KeyEventType.KeyDown) return false
    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
    val digit = pinDigitFor(event.key)
    return when {
        digit != null -> buffer.append(digit).let { true }
        event.key == Key.Backspace || event.key == Key.Delete -> { buffer.deleteLast(); true }
        event.key == Key.Enter || event.key == Key.NumPadEnter -> {
            if (buffer.length >= PinFormatPolicy.MIN_LENGTH) confirm()
            true
        }
        else -> false
    }
}

/**
 * The in-app numeric pad (ux-flows 2.4, UQ-1): fixed digit order, 64dp circular keys, delete, an optional
 * fingerprint key and an explicit confirm button that is enabled from [PinFormatPolicy.MIN_LENGTH] digits and never
 * auto-submits. Digits go straight into [buffer]; nothing is logged or saved.
 *
 * On confirm, [onConfirm] receives a COPY of the digits and [buffer] is zero-filled. The receiver owns the copy and
 * must zero it after use. While [enabled] is false (a delay is active) the keys are disabled and the buffer is cleared.
 */
@Composable
fun PinPad(
    buffer: PinEntryBuffer,
    enabled: Boolean,
    onConfirm: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = stringResource(R.string.applock_unlock_button),
    showFingerprintKey: Boolean = false,
    onFingerprint: () -> Unit = {},
    fingerprintEnabled: Boolean = enabled
) {
    androidx.compose.runtime.LaunchedEffect(enabled) { if (!enabled) buffer.clear() }
    val deleteCd = stringResource(R.string.applock_pin_key_delete_cd)
    val fingerprintCd = stringResource(R.string.applock_pin_key_fingerprint_cd)
    val focusRequester = remember { FocusRequester() }
    // Hardware keyboard (ux-flows 8): focus the pad while it is usable. Failure to take focus is harmless.
    LaunchedEffect(enabled) {
        if (enabled) {
            // Narrow catch, not runCatching: CancellationSafetyArchitectureGuardTest bans raw runCatching in suspend paths.
            try {
                focusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // Not attached yet: focus is best effort.
            }
        }
    }
    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                handlePinKeyEvent(event, enabled, buffer) { onConfirm(buffer.consumeCopy()) }
            }
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        for (row in DIGIT_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (d in row) {
                    PinKey(d.toString(), null, enabled, PinPadTags.KEY_PREFIX + d) { buffer.append(d) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showFingerprintKey) {
                PinKey("☝", fingerprintCd, fingerprintEnabled,PinPadTags.KEY_FINGERPRINT, onFingerprint)
            } else {
                Spacer(Modifier.size(KEY_SIZE))
            }
            PinKey("0", null, enabled, PinPadTags.KEY_PREFIX + "0") { buffer.append('0') }
            PinKey("⌫", deleteCd, enabled, PinPadTags.KEY_DELETE) { buffer.deleteLast() }
        }
        Spacer(Modifier.size(4.dp))
        Button(
            onClick = { onConfirm(buffer.consumeCopy()) },
            enabled = enabled && buffer.length >= PinFormatPolicy.MIN_LENGTH,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(PinPadTags.CONFIRM)
        ) {
            Text(confirmText)
        }
    }
}
