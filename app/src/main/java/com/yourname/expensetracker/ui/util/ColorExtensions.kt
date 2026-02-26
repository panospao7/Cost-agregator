package com.yourname.expensetracker.ui.util

import androidx.compose.ui.graphics.Color
import android.graphics.Color as AndroidColor

fun String.toComposeColor(): Color = try {
    Color(AndroidColor.parseColor(this))
} catch (e: Exception) {
    Color.Gray
}

fun Color.toHexString(): String {
    val red = (this.red * 255).toInt()
    val green = (this.green * 255).toInt()
    val blue = (this.blue * 255).toInt()
    return String.format("#%02X%02X%02X", red, green, blue)
}

fun String.isValidHexColor(): Boolean {
    return try {
        AndroidColor.parseColor(this)
        true
    } catch (e: Exception) {
        false
    }
}
