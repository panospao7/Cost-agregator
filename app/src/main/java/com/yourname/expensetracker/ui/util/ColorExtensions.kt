package com.yourname.expensetracker.ui.util

import androidx.compose.ui.graphics.Color
import android.graphics.Color as AndroidColor
import kotlin.math.roundToInt

// S2-014: Strict hex-only regex — rejects named colors like "red"
private val HexColorRegex = Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$")

// S2-013: Null-safe API — callers choose fallback explicitly
fun String.toComposeColorOrNull(): Color? =
    runCatching { Color(AndroidColor.parseColor(this)) }.getOrNull()

fun String.toComposeColorOrDefault(default: Color = Color.Gray): Color =
    toComposeColorOrNull() ?: default

@Deprecated(
    "Use toComposeColorOrNull() or toComposeColorOrDefault() to handle invalid colors explicitly",
    ReplaceWith("toComposeColorOrDefault()")
)
fun String.toComposeColor(): Color = toComposeColorOrDefault()

// S2-015: Proper alpha support and clamped rounding
private fun Float.toByteInt(): Int = (coerceIn(0f, 1f) * 255f).roundToInt()

fun Color.toHexString(includeAlpha: Boolean = false): String {
    val r = red.toByteInt()
    val g = green.toByteInt()
    val b = blue.toByteInt()
    val a = alpha.toByteInt()
    return if (includeAlpha) "#%02X%02X%02X%02X".format(a, r, g, b)
    else "#%02X%02X%02X".format(r, g, b)
}

// S2-014: Hex-only validation — named colors like "red" are rejected
fun String.isValidHexColor(): Boolean = matches(HexColorRegex)
