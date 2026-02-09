

# UI Modernization Plan — Implementation Guide

I've analyzed your entire codebase against the UX report. Here's a structured, phased implementation plan that preserves every existing feature while evolving the UI.

---

## Phase 1: Foundation — Theme & Design System

### 1A. Update `Theme.kt` with Inter font + Tabular Figures + Semantic Colors

```kotlin
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.yourname.expensetracker.R

// === Font Family ===
// Add Inter font files to res/font: inter_regular.ttf, inter_medium.ttf, inter_semibold.ttf, inter_bold.ttf
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

// === Semantic Colors (used across light/dark) ===
object SemanticColors {
    val WarningOrange = Color(0xFFFF9800)
    val ActionIndigo = Color(0xFF5C6BC0)
    val SuccessGreen = Color(0xFF4CAF50)
    val DangerRed = Color(0xFFFF5722)
    val CriticalAmber = Color(0xFFFFC107)
    val NeutralGray = Color(0xFF9E9E9E)

    // Budget health
    val OnTrack = Color(0xFF4CAF50)
    val Warning = Color(0xFFFFC107)
    val Critical = Color(0xFFFF9800)
    val Exceeded = Color(0xFFFF5722)

    // Pace
    val UnderPace = Color(0xFF4CAF50)
    val OnPace = Color(0xFF2196F3)
    val OverPace = Color(0xFFFF5722)

    // Confidence
    fun confidenceColor(confidence: Float): Color = when {
        confidence >= 0.75f -> SuccessGreen
        confidence >= 0.60f -> CriticalAmber
        else -> DangerRed
    }
}

// === Typography with Tabular Lining Figures ===
// Note: Inter supports tabular figures via OpenType feature "tnum"
// In Compose, we use fontFeatureSettings
private fun tabularStyle(
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: androidx.compose.ui.unit.TextUnit = fontSize * 1.4f
) = TextStyle(
    fontFamily = InterFontFamily,
    fontSize = fontSize,
    fontWeight = fontWeight,
    lineHeight = lineHeight,
    fontFeatureSettings = "tnum" // Tabular lining figures
)

val ExpenseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 57.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 64.sp,
        fontFeatureSettings = "tnum"
    ),
    displayMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 45.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 52.sp,
        fontFeatureSettings = "tnum"
    ),
    displaySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 36.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 44.sp,
        fontFeatureSettings = "tnum"
    ),
    headlineLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 32.sp,
        fontFeatureSettings = "tnum"
    ),
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp
    )
)

// === Color Schemes ===
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
    surface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFF49454F)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260),
    surface = Color(0xFFFFFBFE),
    surfaceVariant = Color(0xFFE7E0EC)
)

@Composable
fun ExpenseTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
```

### 1B. Create Atomic BentoCard Component

Create new file: `ui/components/BentoCard.kt`

```kotlin
package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Atomic BentoCard — the building block for the Bento Grid layout.
 * All dashboard widgets wrap in this.
 */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    cornerRadius: Dp = 20.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            onClick = onClick
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    } else {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            colors = CardDefaults.cardColors(containerColor = containerColor),
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    }
}

/**
 * Hero BentoCard — larger, primary-colored, for the main metric
 */
@Composable
fun HeroBentoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    BentoCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        cornerRadius = 24.dp,
        contentPadding = PaddingValues(24.dp),
        content = content
    )
}

/**
 * Compact stat label used inside BentoCards
 */
@Composable
fun StatLabel(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum"
            ),
            color = valueColor
        )
    }
}

/**
 * Amount text with tabular figures
 */
@Composable
fun AmountText(
    amount: Double,
    currency: String = "€",
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displaySmall,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Text(
        text = "$currency${String.format("%.2f", amount)}",
        style = style.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier
    )
}
```

### 1C. Create PulseDot Component

Create: `ui/components/PulseDot.kt`

```kotlin
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
                .background(SemanticColors.NeutralGray, CircleShape)
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
```

### 1D. Create SpendingPaceGauge Component

Create: `ui/components/SpendingPaceGauge.kt`

```kotlin
package com.yourname.expensetracker.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun SpendingPaceGauge(
    pace: SpendingPace,
    modifier: Modifier = Modifier
) {
    val paceColor = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> SemanticColors.UnderPace
        PaceStatus.ON_PACE -> SemanticColors.OnPace
        PaceStatus.OVER_PACE -> SemanticColors.OverPace
        PaceStatus.NO_BASELINE -> SemanticColors.NeutralGray
    }

    // Animate the sweep angle
    val targetSweep = (pace.pacePercentage / 200f).coerceIn(0f, 1f) * 240f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(1000),
        label = "pace_sweep"
    )

    val statusLabel = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> "Under pace"
        PaceStatus.ON_PACE -> "On track"
        PaceStatus.OVER_PACE -> "Over pace"
        PaceStatus.NO_BASELINE -> "No data yet"
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            val trackColor = MaterialTheme.colorScheme.surfaceVariant

            Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val strokeWidth = 12.dp.toPx()
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

                // Background arc (240° sweep, centered at bottom)
                drawArc(
                    color = trackColor,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Foreground arc
                drawArc(
                    color = paceColor,
                    startAngle = 150f,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // Center text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${pace.pacePercentage.toInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = paceColor
                )
                Text(
                    text = "Day ${pace.daysElapsed}/${pace.daysInMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color = paceColor,
            fontWeight = FontWeight.Medium
        )

        if (pace.projectedTotal > 0) {
            Text(
                text = "Projected: €${String.format("%.0f", pace.projectedTotal)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

---

## Phase 2: Navigation Consolidation (7 tabs → 4 tabs + Debug)

### 2A. Updated `MainActivity.kt` with 4-tab navigation + Smart FAB

```kotlin
package com.yourname.expensetracker.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.ui.screens.analytics.AnalyticsScreen
import com.yourname.expensetracker.ui.screens.budget.BudgetScreen
import com.yourname.expensetracker.ui.screens.categories.CategoryScreen
import com.yourname.expensetracker.ui.screens.debug.DebugScreen
import com.yourname.expensetracker.ui.screens.home.HomeScreen
import com.yourname.expensetracker.ui.screens.review.ReviewScreen
import com.yourname.expensetracker.ui.screens.transactions.TransactionsScreen
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExpenseTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

// Navigation destinations
enum class AppTab(val label: String) {
    DASHBOARD("Home"),
    ACTIVITY("Activity"),
    REVIEW("Review"),
    PLAN("Plan")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(AppTab.DASHBOARD) }

    // Track secondary screens (categories, debug) via a state
    var secondaryScreen by remember { mutableStateOf<String?>(null) }

    val mainViewModel: MainViewModel = hiltViewModel()
    val pendingCount by mainViewModel.pendingReviewCount.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Add expense sheet state
    var showAddExpense by remember { mutableStateOf(false) }

    // Smart FAB: detect clipboard amount
    val clipboardAmount = remember { detectClipboardAmount(context) }

    // Notification permission
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // If a secondary screen is active, show it instead
    if (secondaryScreen != null) {
        when (secondaryScreen) {
            "categories" -> CategoryScreen(onBack = { secondaryScreen = null })
            "debug" -> DebugScreen(onBack = { secondaryScreen = null })
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Smart FAB — changes based on context
            SmartFAB(
                currentTab = selectedTab,
                clipboardAmount = clipboardAmount,
                onAddExpense = { showAddExpense = true },
                pendingCount = pendingCount
            )
        },
        bottomBar = {
            NavigationBar(
                tonalElevation = 0.dp
            ) {
                // 1. Dashboard
                NavigationBarItem(
                    selected = selectedTab == AppTab.DASHBOARD,
                    onClick = { selectedTab = AppTab.DASHBOARD },
                    icon = {
                        Icon(
                            if (selectedTab == AppTab.DASHBOARD) Icons.Filled.Home
                            else Icons.Outlined.Home,
                            contentDescription = "Dashboard"
                        )
                    },
                    label = { Text("Home") }
                )

                // 2. Activity (Transactions)
                NavigationBarItem(
                    selected = selectedTab == AppTab.ACTIVITY,
                    onClick = { selectedTab = AppTab.ACTIVITY },
                    icon = {
                        Icon(
                            if (selectedTab == AppTab.ACTIVITY) Icons.Filled.Receipt
                            else Icons.Outlined.Receipt,
                            contentDescription = "Activity"
                        )
                    },
                    label = { Text("Activity") }
                )

                // 3. Review (Inbox) — with badge
                NavigationBarItem(
                    selected = selectedTab == AppTab.REVIEW,
                    onClick = { selectedTab = AppTab.REVIEW },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (pendingCount > 0) {
                                    Badge { Text("$pendingCount") }
                                }
                            }
                        ) {
                            Icon(
                                if (selectedTab == AppTab.REVIEW) Icons.Filled.Inbox
                                else Icons.Outlined.Inbox,
                                contentDescription = "Review"
                            )
                        }
                    },
                    label = { Text("Review") }
                )

                // 4. Plan (Analytics + Budget combined)
                NavigationBarItem(
                    selected = selectedTab == AppTab.PLAN,
                    onClick = { selectedTab = AppTab.PLAN },
                    icon = {
                        Icon(
                            if (selectedTab == AppTab.PLAN) Icons.Filled.BarChart
                            else Icons.Outlined.BarChart,
                            contentDescription = "Plan"
                        )
                    },
                    label = { Text("Plan") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            // Crossfade between tabs for smooth transitions
            androidx.compose.animation.Crossfade(
                targetState = selectedTab,
                label = "tab_transition"
            ) { tab ->
                when (tab) {
                    AppTab.DASHBOARD -> HomeScreen(
                        onNavigateToCategories = { secondaryScreen = "categories" },
                        onNavigateToDebug = { secondaryScreen = "debug" }
                    )
                    AppTab.ACTIVITY -> TransactionsScreen()
                    AppTab.REVIEW -> ReviewScreen()
                    AppTab.PLAN -> PlanScreen()
                }
            }
        }
    }

    // Add Expense Sheet (full screen modal)
    if (showAddExpense) {
        com.yourname.expensetracker.ui.screens.addexpense.AddExpenseSheet(
            onDismiss = { showAddExpense = false }
        )
    }
}

/**
 * Smart FAB that adapts to context:
 * - On Dashboard: "Add Expense"
 * - If clipboard has amount: "Paste €XX.XX"
 * - On Review tab: hidden (actions are inline)
 */
@Composable
fun SmartFAB(
    currentTab: AppTab,
    clipboardAmount: String?,
    onAddExpense: () -> Unit,
    pendingCount: Int
) {
    // Hide on Review tab (actions are inline)
    if (currentTab == AppTab.REVIEW) return

    if (clipboardAmount != null && currentTab == AppTab.DASHBOARD) {
        // Extended FAB with clipboard hint
        ExtendedFloatingActionButton(
            onClick = onAddExpense,
            icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
            text = { Text("Paste $clipboardAmount") },
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    } else {
        FloatingActionButton(
            onClick = onAddExpense,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Expense")
        }
    }
}

/**
 * Detect if clipboard contains an amount (e.g., "12.50" or "€45.00")
 */
fun detectClipboardAmount(context: Context): String? {
    return try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: return null
            val regex = Regex("""[€$£]?\s*(\d+[.,]\d{2})""")
            val match = regex.find(text)
            match?.let { "€${it.groupValues[1]}" }
        } else null
    } catch (e: Exception) {
        null
    }
}

/**
 * Combined Plan screen — Analytics + Budget in one tab with segment toggle
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen() {
    var selectedSegment by remember { mutableIntStateOf(0) }
    val segments = listOf("Analytics", "Budgets")

    Column(modifier = Modifier.fillMaxSize()) {
        // Segment control at top
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            segments.forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = segments.size
                    ),
                    onClick = { selectedSegment = index },
                    selected = selectedSegment == index
                ) {
                    Text(label)
                }
            }
        }

        // Content
        when (selectedSegment) {
            0 -> AnalyticsScreen()
            1 -> BudgetScreen()
        }
    }
}
```

---

## Phase 3: Dashboard Bento Grid Redesign

### 3A. Update `HomeViewModel.kt` to emit state widgets

```kotlin
package com.yourname.expensetracker.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import java.util.Calendar
import javax.inject.Inject

// === State Widget sealed class for Bento Grid ===
sealed class DashboardWidget {
    data class SafeToSpend(
        val amount: Double,
        val totalBudget: Double?,
        val daysRemaining: Int
    ) : DashboardWidget()

    data class SpendingPaceWidget(
        val pace: SpendingPace
    ) : DashboardWidget()

    data class PendingReviewAlert(
        val count: Int
    ) : DashboardWidget()

    data class PeriodSummary(
        val todaySpent: Double,
        val weekSpent: Double,
        val monthSpent: Double
    ) : DashboardWidget()

    data class TopCategories(
        val categories: List<CategorySpending>
    ) : DashboardWidget()

    data class BudgetHealthWidget(
        val statuses: List<BudgetStatus>,
        val summary: String?
    ) : DashboardWidget()

    data class RecentTransactions(
        val expenses: List<Expense>
    ) : DashboardWidget()

    data class NaturalLanguageInsight(
        val text: String,
        val icon: String
    ) : DashboardWidget()
}

data class CategorySpending(
    val category: Category,
    val total: Double,
    val percentage: Float
)

data class DashboardState(
    val widgets: List<DashboardWidget> = emptyList(),
    val totalSpent: Double = 0.0,
    val transactionCount: Int = 0,
    val isServiceRunning: Boolean = true, // For pulse dot
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val insightsEngine: InsightsEngine
) : ViewModel() {

    val dashboard: StateFlow<DashboardState> = combine(
        repository.getAllExpenses(),
        categoryRepository.allCategories,
        budgetRepository.getBudgetStatuses(),
        repository.getPendingReviewCount()
    ) { expenses, categories, budgetStatuses, pendingCount ->

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // Time boundaries
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis

        val tempCal = cal.clone() as Calendar
        tempCal.firstDayOfWeek = Calendar.MONDAY
        tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (tempCal.timeInMillis > todayStart) tempCal.add(Calendar.DAY_OF_YEAR, -7)
        val weekStart = tempCal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = cal.timeInMillis

        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        val categoryMap = categories.associateBy { it.id }
        val totalSpent = purchases.sumOf { it.amount }
        val monthSpent = purchases.filter { it.date >= monthStart }.sumOf { it.amount }
        val weekSpent = purchases.filter { it.date >= weekStart }.sumOf { it.amount }
        val todaySpent = purchases.filter { it.date >= todayStart }.sumOf { it.amount }

        // Days remaining in month
        val daysInMonth = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val daysRemaining = daysInMonth - dayOfMonth

        // Overall budget (if set)
        val overallBudget = budgetStatuses.find { it.budget.categoryId == null }
        val safeToSpend = overallBudget?.remainingAmount ?: (0.0) // fallback if no budget

        // Category totals
        val categoryTotals = purchases
            .groupBy { it.categoryId }
            .mapNotNull { (catId, exps) ->
                val cat = catId?.let { categoryMap[it] } ?: return@mapNotNull null
                val catTotal = exps.sumOf { it.amount }
                CategorySpending(
                    category = cat,
                    total = catTotal,
                    percentage = if (totalSpent > 0) (catTotal / totalSpent * 100).toFloat() else 0f
                )
            }
            .sortedByDescending { it.total }

        // Spending pace
        val currentMonth = insightsEngine.getMonthPeriod(now)
        val previousMonth = insightsEngine.getMonthPeriod(now, -1)
        // Build a simplified pace from available data
        val projectedTotal = if (dayOfMonth > 0)
            monthSpent * daysInMonth.toDouble() / dayOfMonth else monthSpent
        val previousMonthTotal = purchases
            .filter { it.date >= previousMonth.startMs && it.date < previousMonth.endMs }
            .sumOf { it.amount }
        val pace = SpendingPace(
            currentMonthSpent = monthSpent,
            daysElapsed = dayOfMonth,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = if (previousMonthTotal > 0) previousMonthTotal else null,
            averageMonthlyTotal = null,
            pacePercentage = if (previousMonthTotal > 0) {
                val expected = previousMonthTotal * dayOfMonth / daysInMonth
                (monthSpent / expected * 100).toFloat()
            } else 0f,
            paceStatus = when {
                previousMonthTotal <= 0 -> PaceStatus.NO_BASELINE
                monthSpent < previousMonthTotal * dayOfMonth / daysInMonth * 0.9 -> PaceStatus.UNDER_PACE
                monthSpent > previousMonthTotal * dayOfMonth / daysInMonth * 1.1 -> PaceStatus.OVER_PACE
                else -> PaceStatus.ON_PACE
            }
        )

        // Natural language insight
        val insightText = buildNaturalLanguageInsight(
            monthSpent, previousMonthTotal, todaySpent, purchases.size
        )

        // Budget summary
        val exceeded = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        val budgetSummary = if (budgetStatuses.isNotEmpty()) {
            if (exceeded > 0) "$exceeded budgets exceeded!" else "All budgets on track"
        } else null

        // === Build widget list ===
        val widgets = mutableListOf<DashboardWidget>()

        // Hero: Safe-to-Spend (or total if no budget)
        widgets.add(
            DashboardWidget.SafeToSpend(
                amount = if (overallBudget != null) safeToSpend else monthSpent,
                totalBudget = overallBudget?.budget?.amount,
                daysRemaining = daysRemaining
            )
        )

        // Spending Pace
        if (pace.paceStatus != PaceStatus.NO_BASELINE) {
            widgets.add(DashboardWidget.SpendingPaceWidget(pace))
        }

        // Pending Review Alert
        if (pendingCount > 0) {
            widgets.add(DashboardWidget.PendingReviewAlert(pendingCount))
        }

        // Natural language insight
        if (insightText != null) {
            widgets.add(DashboardWidget.NaturalLanguageInsight(insightText.first, insightText.second))
        }

        // Period summary
        widgets.add(DashboardWidget.PeriodSummary(todaySpent, weekSpent, monthSpent))

        // Budget health
        if (budgetStatuses.isNotEmpty()) {
            widgets.add(DashboardWidget.BudgetHealthWidget(budgetStatuses, budgetSummary))
        }

        // Top categories
        if (categoryTotals.isNotEmpty()) {
            widgets.add(DashboardWidget.TopCategories(categoryTotals.take(5)))
        }

        // Recent transactions
        if (purchases.isNotEmpty()) {
            widgets.add(DashboardWidget.RecentTransactions(purchases.take(5)))
        }

        DashboardState(
            widgets = widgets,
            totalSpent = totalSpent,
            transactionCount = purchases.size,
            isLoading = false
        )
    }
        .debounce(300)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    private fun buildNaturalLanguageInsight(
        monthSpent: Double,
        previousMonthTotal: Double,
        todaySpent: Double,
        txCount: Int
    ): Pair<String, String>? {
        if (previousMonthTotal > 0) {
            val diff = monthSpent - previousMonthTotal
            return if (diff < 0) {
                Pair(
                    "You've spent €${String.format("%.0f", -diff)} less than last month so far.",
                    "📉"
                )
            } else if (diff > previousMonthTotal * 0.2) {
                Pair(
                    "Spending is €${String.format("%.0f", diff)} higher than last month.",
                    "📈"
                )
            } else null
        }
        if (txCount > 0 && todaySpent > 0) {
            return Pair(
                "You've spent €${String.format("%.2f", todaySpent)} today across $txCount transactions.",
                "💡"
            )
        }
        return null
    }
}
```

### 3B. Updated `HomeScreen.kt` with Bento Grid

```kotlin
package com.yourname.expensetracker.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToCategories: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {}
) {
    val state by viewModel.dashboard.collectAsState()

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header with pulse dot
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Dashboard",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PulseDot(isActive = state.isServiceRunning)
                    Text(
                        "Syncing",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Settings gear for debug/categories
                    IconButton(onClick = onNavigateToDebug) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Render widgets from ViewModel
        items(state.widgets) { widget ->
            when (widget) {
                is DashboardWidget.SafeToSpend -> SafeToSpendCard(widget)
                is DashboardWidget.SpendingPaceWidget -> PaceCard(widget)
                is DashboardWidget.PendingReviewAlert -> ReviewAlertCard(widget)
                is DashboardWidget.NaturalLanguageInsight -> InsightCard(widget)
                is DashboardWidget.PeriodSummary -> PeriodSummaryRow(widget)
                is DashboardWidget.BudgetHealthWidget -> BudgetHealthCard(widget)
                is DashboardWidget.TopCategories -> TopCategoriesCard(widget)
                is DashboardWidget.RecentTransactions -> RecentCard(widget)
            }
        }

        // Footer spacer
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

// === Widget Composables ===

@Composable
fun SafeToSpendCard(widget: DashboardWidget.SafeToSpend) {
    HeroBentoCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (widget.totalBudget != null) "Safe to Spend" else "Spent This Month",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        AmountText(
            amount = widget.amount,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (widget.totalBudget != null) {
                Text(
                    "of €${String.format("%.0f", widget.totalBudget)} budget",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
            }
            Text(
                "${widget.daysRemaining} days left",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun PaceCard(widget: DashboardWidget.SpendingPaceWidget) {
    BentoCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Spending Pace",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        SpendingPaceGauge(
            pace = widget.pace,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
fun ReviewAlertCard(widget: DashboardWidget.PendingReviewAlert) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = SemanticColors.ActionIndigo.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Inbox,
                contentDescription = null,
                tint = SemanticColors.ActionIndigo,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${widget.count} transactions need review",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.ActionIndigo
                )
                Text(
                    "Swipe to approve or reject",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun InsightCard(widget: DashboardWidget.NaturalLanguageInsight) {
    BentoCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(widget.icon, fontSize = 24.sp)
            Text(
                widget.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun PeriodSummaryRow(widget: DashboardWidget.PeriodSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BentoCard(modifier = Modifier.weight(1f)) {
            StatLabel("Today", "€${String.format("%.2f", widget.todaySpent)}")
        }
        BentoCard(modifier = Modifier.weight(1f)) {
            StatLabel("Week", "€${String.format("%.2f", widget.weekSpent)}")
        }
        BentoCard(modifier = Modifier.weight(1f)) {
            StatLabel("Month", "€${String.format("%.2f", widget.monthSpent)}")
        }
    }
}

@Composable
fun BudgetHealthCard(widget: DashboardWidget.BudgetHealthWidget) {
    val onTrack = widget.statuses.count { it.healthStatus == BudgetHealthStatus.ON_TRACK }
    val warning = widget.statuses.count {
        it.healthStatus == BudgetHealthStatus.WARNING || it.healthStatus == BudgetHealthStatus.CRITICAL
    }
    val exceeded = widget.statuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }

    BentoCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Budget Status",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            StatusPill("✅", onTrack, SemanticColors.OnTrack)
            StatusPill("⚠️", warning, SemanticColors.Warning)
            StatusPill("🔴", exceeded, SemanticColors.Exceeded)
        }
        if (widget.summary != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                widget.summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (exceeded > 0) SemanticColors.Exceeded
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusPill(emoji: String, count: Int, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(emoji, fontSize = 16.sp)
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
fun TopCategoriesCard(widget: DashboardWidget.TopCategories) {
    BentoCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Top Categories",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(12.dp))
        widget.categories.forEach { catSpending ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val catColor = remember(catSpending.category.color) {
                    try { Color(android.graphics.Color.parseColor(catSpending.category.color)) }
                    catch (e: Exception) { Color.Gray }
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(catColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(catSpending.category.icon, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        catSpending.category.name,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { catSpending.percentage / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = catColor,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "€${String.format("%.2f", catSpending.total)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun RecentCard(widget: DashboardWidget.RecentTransactions) {
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }

    BentoCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Recent",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        widget.expenses.forEach { expense ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            expense.merchant,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (expense.isManualEntry) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("✏️", fontSize = 12.sp)
                        }
                    }
                    Text(
                        dateFormat.format(Date(expense.date)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "€${String.format("%.2f", expense.amount)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold
                )
            }
            if (expense != widget.expenses.last()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}
```

---

## Phase 4: Review Screen with Swipe Actions + Trust Signal

### 4A. Updated `ReviewScreen.kt` with swipe gestures

Add to your `build.gradle`:
```groovy
implementation "me.saket.swipe:swipe:1.2.0"
```

Or use Material3's `SwipeToDismissBox`. Here's the approach using built-in Material3:

```kotlin
// In ReviewScreen.kt, update the ReviewCard section in the items block:

items(pendingReviews, key = { it.id }) { review ->
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    // Swipe right = Approve
                    viewModel.approveReview(review.id)
                    true
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    // Swipe left = Reject
                    viewModel.rejectReview(review.id)
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> SemanticColors.SuccessGreen
                    SwipeToDismissBoxValue.EndToStart -> SemanticColors.DangerRed
                    else -> Color.Transparent
                },
                label = "swipe_bg"
            )
            val icon = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Check
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Close
                else -> Icons.Default.Check
            }
            val alignment = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color, RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment
            ) {
                Icon(icon, contentDescription = null, tint = Color.White)
            }
        },
        content = {
            ReviewCard(
                review = review,
                onApprove = { viewModel.approveReview(review.id) },
                onReject = { viewModel.rejectReview(review.id) },
                onEdit = { editingReview = review }
            )
        }
    )
}
```

In the `ReviewCard`, add the **Trust Signal** (raw notification expandable):

```kotlin
// Add inside ReviewCard, after the notification preview section:

// Trust Signal: expandable raw notification
var showRawNotification by remember { mutableStateOf(false) }

Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { showRawNotification = !showRawNotification },
    verticalAlignment = Alignment.CenterVertically
) {
    Text(
        "🔍 Show original notification",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

AnimatedVisibility(visible = showRawNotification) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "Raw notification from ${review.packageName}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            review.notificationTitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            review.notificationText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
```

---

## Phase 5: Enhanced Analytics with Natural Language

### 5A. Update `AnalyticsScreen.kt` — add natural language header

In the `TotalSpentHeader` composable, add after the percentage change:

```kotlin
// Natural language summary instead of just numbers
val comparisonText = when {
    state.changePercent != null && state.changePercent > 0 ->
        "You spent €${String.format("%.0f", state.currentTotal - (state.previousTotal ?: 0.0))} more than last ${state.selectedPeriod.name.lowercase()}."
    state.changePercent != null && state.changePercent < 0 ->
        "You spent €${String.format("%.0f", (state.previousTotal ?: 0.0) - state.currentTotal)} less than last ${state.selectedPeriod.name.lowercase()}."
    else -> "${state.transactionCount} transactions this ${state.selectedPeriod.name.lowercase()}"
}

Spacer(modifier = Modifier.height(4.dp))
Text(
    text = comparisonText,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
)
```

---

## Phase 6: Haptic Feedback

Create: `ui/util/HapticFeedback.kt`

```kotlin
package com.yourname.expensetracker.ui.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object HapticFeedback {
    
    fun tick(context: Context) {
        vibrate(context, 10L, VibrationEffect.EFFECT_TICK)
    }

    fun confirm(context: Context) {
        vibrate(context, 30L, VibrationEffect.EFFECT_CLICK)
    }

    fun reject(context: Context) {
        vibrate(context, 50L, VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    fun success(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val vibrator = getVibrator(context) ?: return
            vibrator.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            )
        } else {
            vibrate(context, 30L)
        }
    }

    private fun vibrate(context: Context, durationMs: Long, effectId: Int? = null) {
        val vibrator = getVibrator(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (effectId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(effectId))
            } else {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
```

Use in Review actions:

```kotlin
// In ReviewScreen, when calling approve/reject:
val context = LocalContext.current

onApprove = {
    HapticFeedback.success(context)
    viewModel.approveReview(review.id)
}

onReject = {
    HapticFeedback.reject(context)
    viewModel.rejectReview(review.id)
}
```

---

## Migration Notes

### What's Preserved (All Existing Functionality)
- ✅ All DAOs, entities, repositories — **untouched**
- ✅ `NotificationCaptureService` — **untouched**
- ✅ Parser registry, all parsers — **untouched**
- ✅ `CategorizationEngine`, `ConfidenceRouter`, `TransactionClassifier` — **untouched**
- ✅ `BudgetMonitor`, `InsightsEngine` — **untouched**
- ✅ `AddExpenseSheet` and `AddExpenseViewModel` — **untouched**
- ✅ `ReviewViewModel` — **untouched**
- ✅ `DebugScreen`/`DebugViewModel` — preserved, moved to secondary screen
- ✅ `CategoryScreen`/`CategoryViewModel` — preserved, moved to secondary screen
- ✅ `BudgetScreen`/`BudgetViewModel` — preserved, moved inside Plan tab

### What Changed
1. **Theme.kt** — Added Inter font, tabular figures, semantic colors
2. **MainActivity.kt** — 7 tabs → 4 tabs + secondary screens + Smart FAB
3. **HomeScreen.kt** — Bento Grid layout with widget-based rendering
4. **HomeViewModel.kt** — Emits `DashboardWidget` sealed classes
5. **ReviewScreen.kt** — Added swipe gestures + trust signal expansion
6. **New components**: `BentoCard`, `PulseDot`, `SpendingPaceGauge`, `AmountText`, `HapticFeedback`

### Font Files Needed
Download Inter from Google Fonts and add to `res/font/`:
- `inter_regular.ttf`
- `inter_medium.ttf`
- `inter_semibold.ttf`
- `inter_bold.ttf`

### Implementation Order
1. Add font files → Update `Theme.kt`
2. Create component files (`BentoCard`, `PulseDot`, etc.)
3. Update `HomeViewModel` → `HomeScreen`
4. Update `MainActivity` (navigation)
5. Update `ReviewScreen` (swipe + trust)
6. Add haptic feedback
7. Update `AnalyticsScreen` (natural language)