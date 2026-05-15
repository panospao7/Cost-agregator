package com.yourname.expensetracker.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

// S2-016: Clamp scale to safe range; reject NaN/infinite
fun Modifier.budgetScale(scale: Float): Modifier {
    val safeScale = if (scale.isFinite()) scale.coerceIn(0.8f, 1.2f) else 1f
    return graphicsLayer(scaleX = safeScale, scaleY = safeScale)
}

fun Modifier.ifTrue(condition: Boolean, transform: Modifier.() -> Modifier): Modifier =
    if (condition) transform() else this
