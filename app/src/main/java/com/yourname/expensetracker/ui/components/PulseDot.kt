package com.yourname.expensetracker.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.ui.theme.SemanticColors

/**
 * Animated pulse dot that indicates the background service is running.
 */
@Composable
fun PulseDot(
    modifier: Modifier = Modifier,
    color: Color = SemanticColors.SuccessGreen,
    size: Dp = 8.dp,
    isActive: Boolean = true
) {
    if (!isActive) {
        Box(
            modifier = modifier
                .size(size)
                .background(SemanticColors.TextMuted, CircleShape)
        )
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(modifier = modifier) {
        // Outer pulse ring
        Box(
            modifier = Modifier
                .size(size)
                .scale(scale)
                .alpha(alpha)
                .background(color, CircleShape)
        )
        // Inner solid dot
        Box(
            modifier = Modifier
                .size(size)
                .background(color, CircleShape)
        )
    }
}
