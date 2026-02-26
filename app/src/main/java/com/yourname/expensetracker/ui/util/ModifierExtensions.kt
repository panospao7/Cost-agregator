package com.yourname.expensetracker.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

fun Modifier.budgetScale(scale: Float): Modifier = 
    this.then(graphicsLayer(scaleX = scale, scaleY = scale))

fun Modifier.ifTrue(condition: Boolean, transform: Modifier.() -> Modifier): Modifier =
    if (condition) transform() else this
