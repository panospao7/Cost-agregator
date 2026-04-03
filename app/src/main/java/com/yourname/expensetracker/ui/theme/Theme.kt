package com.yourname.expensetracker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// === Semantic Colors (optimized for Midnight Navy) ===
object SemanticColors {
    val BaseNavy = Color(0xFF0F172A)
    val SurfaceLight = Color(0xFF1E293B)
    val PrimaryIndigo = Color(0xFF6366F1)
    val PrimaryLight = Color(0xFF818CF8)
    
    val SuccessGreen = Color(0xFF10B981)
    val WarningOrange = Color(0xFFF97316)
    val DangerRed = Color(0xFFEF4444)
    
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xCC94A3B8) // 80% alpha for better contrast
    
    val GlassSurface = Color(0x661E293B) // 40% alpha SurfaceLight
    val GlassBorder = Color(0x1A94A3B8)   // 10% alpha TextSecondary

    // Budget health
    val OnTrack = SuccessGreen
    val Warning = WarningOrange
    val Critical = DangerRed
    val Exceeded = Color(0xFFFF5722)

    // Pace
    val UnderPace = SuccessGreen
    val OnPace = PrimaryIndigo
    val OverPace = WarningOrange

    // Confidence
    fun confidenceColor(confidence: Float): Color = when {
        confidence >= 0.85f -> SuccessGreen
        confidence >= 0.65f -> WarningOrange
        else -> DangerRed
    }

    // Status palette (UI audit consistency)
    val StatusGreen = Color(0xFF4CAF50)
    val StatusGreenLight = Color(0xFFE8F5E9)
    val StatusYellow = Color(0xFFFF9800)
    val StatusYellowLight = Color(0xFFFFF9C4)
    val StatusOrangeLight = Color(0xFFFFE0B2)
    val StatusRed = Color(0xFFF44336)
    val StatusDarkRed = Color(0xFFB71C1C)
    val StatusGreenAlt = Color(0xFF4CAF50)
    val StatusOrangeAlt = Color(0xFFFFA726)
    val StatusRedAlt = Color(0xFFEF5350)
}

// === Typography with Tabular Lining Figures ===
val ExpenseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 57.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 64.sp,
        fontFeatureSettings = "tnum",
        color = SemanticColors.TextPrimary
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 45.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 52.sp,
        fontFeatureSettings = "tnum",
        color = SemanticColors.TextPrimary
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 36.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 44.sp,
        fontFeatureSettings = "tnum",
        color = SemanticColors.TextPrimary
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 40.sp,
        color = SemanticColors.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 36.sp,
        color = SemanticColors.TextPrimary
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 32.sp,
        fontFeatureSettings = "tnum",
        color = SemanticColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
        color = SemanticColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
        color = SemanticColors.TextPrimary
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
        color = SemanticColors.TextSecondary
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
        color = SemanticColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
        color = SemanticColors.TextPrimary
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
        color = SemanticColors.TextSecondary
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
        color = SemanticColors.TextPrimary
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        color = SemanticColors.TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        color = SemanticColors.TextMuted
    )
)

private val DarkColorScheme = darkColorScheme(
    primary = SemanticColors.PrimaryIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0x336366F1), // PrimaryIndigo @ 20%
    onPrimaryContainer = SemanticColors.PrimaryLight,
    secondary = SemanticColors.SurfaceLight,
    onSecondary = SemanticColors.TextPrimary,
    background = SemanticColors.BaseNavy,
    onBackground = SemanticColors.TextPrimary,
    surface = SemanticColors.SurfaceLight,
    onSurface = SemanticColors.TextPrimary,
    surfaceVariant = SemanticColors.GlassSurface,
    onSurfaceVariant = SemanticColors.TextSecondary,
    outline = SemanticColors.GlassBorder,
    error = SemanticColors.DangerRed
)

private val LightColorScheme = lightColorScheme(
    primary = SemanticColors.PrimaryIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFFF1F5F9),
    onSecondary = Color(0xFF1E293B),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = SemanticColors.DangerRed
)

@Composable
fun ExpenseTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExpenseTypography,
        content = content
    )
}
