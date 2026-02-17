# 1 Presentation Layer

## Table of Contents
1. [app\src\main\java\com\yourname\expensetracker\ui\MainActivity.kt](#appsrcmainjavacomyournameexpensetrackeruimainactivitykt)
2. [app\src\main\java\com\yourname\expensetracker\ui\MainViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruimainviewmodelkt)
3. [app\src\main\java\com\yourname\expensetracker\ui\components\BentoCard.kt](#appsrcmainjavacomyournameexpensetrackeruicomponentsbentocardkt)
4. [app\src\main\java\com\yourname\expensetracker\ui\components\BudgetBlockPartyCard.kt](#appsrcmainjavacomyournameexpensetrackeruicomponentsbudgetblockpartycardkt)
5. [app\src\main\java\com\yourname\expensetracker\ui\components\ChartMarker.kt](#appsrcmainjavacomyournameexpensetrackeruicomponentschartmarkerkt)
6. [app\src\main\java\com\yourname\expensetracker\ui\components\FinancialWeatherCard.kt](#appsrcmainjavacomyournameexpensetrackeruicomponentsfinancialweathercardkt)
7. [app\src\main\java\com\yourname\expensetracker\ui\components\ForecastTimeline.kt](#appsrcmainjavacomyournameexpensetrackeruicomponentsforecasttimelinekt)
8. [app\src\main\java\com\yourname\expensetracker\ui\components\PulseDot.kt](#appsrcmainjavacomyournameexpensetrackeruicomponentspulsedotkt)
9. [app\src\main\java\com\yourname\expensetracker\ui\components\SpendingPaceGauge.kt](#appsrcmainjavacomyournameexpensetrackeruicomponentsspendingpacegaugekt)
10. [app\src\main\java\com\yourname\expensetracker\ui\components\SpendingTrendChart.kt](#appsrcmainjavacomyournameexpensetrackeruicomponentsspendingtrendchartkt)
11. [app\src\main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseSheet.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpensesheetkt)
12. [app\src\main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpenseviewmodelkt)
13. [app\src\main\java\com\yourname\expensetracker\ui\screens\analytics\AdvancedAnalyticsScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensanalyticsadvancedanalyticsscreenkt)
14. [app\src\main\java\com\yourname\expensetracker\ui\screens\analytics\AdvancedAnalyticsViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensanalyticsadvancedanalyticsviewmodelkt)
15. [app\src\main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsscreenkt)
16. [app\src\main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsviewmodelkt)
17. [app\src\main\java\com\yourname\expensetracker\ui\screens\budget\BudgetScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensbudgetbudgetscreenkt)
18. [app\src\main\java\com\yourname\expensetracker\ui\screens\budget\BudgetViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensbudgetbudgetviewmodelkt)
19. [app\src\main\java\com\yourname\expensetracker\ui\screens\categories\CategoryScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreenscategoriescategoryscreenkt)
20. [app\src\main\java\com\yourname\expensetracker\ui\screens\categories\CategoryViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruiscreenscategoriescategoryviewmodelkt)
21. [app\src\main\java\com\yourname\expensetracker\ui\screens\debug\DebugDataStorage.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensdebugdebugdatastoragekt)
22. [app\src\main\java\com\yourname\expensetracker\ui\screens\debug\DebugIssueDetector.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensdebugdebugissuedetectorkt)
23. [app\src\main\java\com\yourname\expensetracker\ui\screens\debug\DebugScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensdebugdebugscreenkt)
24. [app\src\main\java\com\yourname\expensetracker\ui\screens\debug\DebugViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensdebugdebugviewmodelkt)
25. [app\src\main\java\com\yourname\expensetracker\ui\screens\debug\DebugViewerScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensdebugdebugviewerscreenkt)
26. [app\src\main\java\com\yourname\expensetracker\ui\screens\home\HomeScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreenshomehomescreenkt)
27. [app\src\main\java\com\yourname\expensetracker\ui\screens\home\HomeViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruiscreenshomehomeviewmodelkt)
28. [app\src\main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanscreenkt)
29. [app\src\main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanviewmodelkt)
30. [app\src\main\java\com\yourname\expensetracker\ui\screens\recurring\RecurringExpensesScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensrecurringrecurringexpensesscreenkt)
31. [app\src\main\java\com\yourname\expensetracker\ui\screens\review\ReviewScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensreviewreviewscreenkt)
32. [app\src\main\java\com\yourname\expensetracker\ui\screens\review\ReviewViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruiscreensreviewreviewviewmodelkt)
33. [app\src\main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionFilter.kt](#appsrcmainjavacomyournameexpensetrackeruiscreenstransactionstransactionfilterkt)
34. [app\src\main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsScreen.kt](#appsrcmainjavacomyournameexpensetrackeruiscreenstransactionstransactionsscreenkt)
35. [app\src\main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsViewModel.kt](#appsrcmainjavacomyournameexpensetrackeruiscreenstransactionstransactionsviewmodelkt)
36. [app\src\main\java\com\yourname\expensetracker\ui\theme\Theme.kt](#appsrcmainjavacomyournameexpensetrackeruithemethemekt)
37. [app\src\main\java\com\yourname\expensetracker\ui\util\HapticFeedback.kt](#appsrcmainjavacomyournameexpensetrackeruiutilhapticfeedbackkt)

---

## app\src\main\java\com\yourname\expensetracker\ui\MainActivity.kt <a name="appsrcmainjavacomyournameexpensetrackeruimainactivitykt"></a>
```kotlin
package com.yourname.expensetracker.ui

import android.Manifest
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.ui.screens.analytics.AnalyticsScreen
import com.yourname.expensetracker.ui.screens.budget.BudgetScreen
import com.yourname.expensetracker.ui.screens.home.HomeScreen
import com.yourname.expensetracker.ui.screens.review.ReviewScreen
import com.yourname.expensetracker.ui.screens.transactions.TransactionsScreen
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import com.yourname.expensetracker.ui.util.HapticType
import com.yourname.expensetracker.ui.util.rememberHapticFeedback
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "expensetracker") {
            when (data.host) {
                "dashboard" -> mainViewModel.navigateToTab(0)
                "activity" -> mainViewModel.navigateToTab(1)
                "review" -> mainViewModel.navigateToTab(2)
                "plan" -> mainViewModel.navigateToTab(3)
                "add" -> mainViewModel.navigateToTab(0)
                "analytics" -> mainViewModel.navigateToTab(4)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }

    val mainViewModel: MainViewModel = hiltViewModel()
    val pendingCount by mainViewModel.pendingReviewCount.collectAsState()

    // Drill-down filter state
    var activeTransactionFilter by remember { mutableStateOf<com.yourname.expensetracker.ui.screens.transactions.TransactionFilter?>(null) }

    LaunchedEffect(Unit) {
        mainViewModel.navigationRequest.collect { tabIndex ->
            selectedTab = tabIndex
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != 
                PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val haptic = rememberHapticFeedback()

    var showAddExpense by rememberSaveable { mutableStateOf(false) }
    var showScanReceipt by rememberSaveable { mutableStateOf(false) }
    var showRecurringExpenses by rememberSaveable { mutableStateOf(false) }
    var isFabExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // ... (rest of bottomBar)
            NavigationBar(
                tonalElevation = 0.dp // Cleaner Bento look
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { 
                        if (selectedTab != 0) haptic(HapticType.Standard)
                        selectedTab = 0 
                    },
                    icon = { Icon(Icons.Rounded.GridView, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { 
                        if (selectedTab != 1) haptic(HapticType.Standard)
                        selectedTab = 1 
                        // Clear drill-down filter when manually navigating to Activity
                        activeTransactionFilter = null
                    },
                    icon = { Icon(Icons.Rounded.History, contentDescription = "Activity") },
                    label = { Text("Activity") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { 
                        if (selectedTab != 2) haptic(HapticType.Standard)
                        selectedTab = 2 
                    },
                    icon = { 
                        BadgedBox(
                            badge = {
                                if (pendingCount > 0) {
                                    Badge { Text("$pendingCount") }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.FactCheck, contentDescription = "Review")
                        }
                    },
                    label = { Text("Review") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { 
                        if (selectedTab != 3) haptic(HapticType.Standard)
                        selectedTab = 3 
                    },
                    icon = { Icon(Icons.Rounded.PieChart, contentDescription = "Plan") },
                    label = { Text("Plan") }
                )
            }
        },
        floatingActionButton = {
            val reviewViewModel: com.yourname.expensetracker.ui.screens.review.ReviewViewModel = hiltViewModel()
            SmartFAB(
                selectedTab = selectedTab,
                isExpanded = isFabExpanded,
                onToggleExpand = { isFabExpanded = !isFabExpanded },
                onAddExpense = { 
                    showAddExpense = true 
                    isFabExpanded = false
                },
                onScanReceipt = {
                    showScanReceipt = true
                    isFabExpanded = false
                },
                onApproveAll = { reviewViewModel.approveAll() }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding)
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "TabTransition"
            ) { targetTab ->
                when (targetTab) {
                    0 -> HomeScreen(
                        onNavigateToReview = { selectedTab = 2 },
                        onNavigateToRecurring = { showRecurringExpenses = true },
                        onNavigateToTransactions = { filter ->
                            activeTransactionFilter = filter
                            selectedTab = 1
                        }
                    )
                    1 -> TransactionsScreen(
                        onNavigateToAnalytics = { selectedTab = 4 },
                        initialFilter = activeTransactionFilter
                    )
                    2 -> ReviewScreen()
                    3 -> BudgetScreen()
                    4 -> com.yourname.expensetracker.ui.screens.analytics.AdvancedAnalyticsScreen(
                        onNavigateBack = { selectedTab = 1 },
                        onNavigateToTransactions = { filter ->
                            activeTransactionFilter = filter
                            selectedTab = 1
                        }
                    )
                }
            }

            if (showAddExpense) {
                val clipboardManager = LocalClipboardManager.current
                var initialAmount by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val text = clipboardManager.getText()?.text ?: ""
                    // Match currency symbols or numbers with context to avoid matching years
                    val regex = Regex("""(?:€|$|EUR)?\s*(\d{1,6}[\.,]\d{2})\s*(?:€|$|EUR)?""")
                    val match = regex.find(text)
                    if (match != null) {
                        val value = match.groupValues[1].replace(",", ".").toDoubleOrNull()
                        if (value != null && value in 0.01..100000.0) {
                            initialAmount = match.groupValues[1]
                        }
                    }
                }

                com.yourname.expensetracker.ui.screens.addexpense.AddExpenseSheet(
                    onDismiss = { showAddExpense = false },
                    initialAmount = initialAmount
                )
            }

            if (showScanReceipt) {
                com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen(
                    onDismiss = { showScanReceipt = false }
                )
            }

            if (showRecurringExpenses) {
                com.yourname.expensetracker.ui.screens.recurring.RecurringExpensesScreen(
                    onNavigateBack = { showRecurringExpenses = false },
                    onNavigateToTransactions = { filter ->
                        activeTransactionFilter = filter
                        // Close recurring screen since it's an overlay
                        showRecurringExpenses = false
                        selectedTab = 1
                    }
                )
            }
        }
    }
}

@Composable
fun SmartFAB(
    selectedTab: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onAddExpense: () -> Unit,
    onScanReceipt: () -> Unit,
    onApproveAll: () -> Unit
) {
    val haptic = rememberHapticFeedback()
    // Use native ClipboardManager to listen for changes
    val context = LocalContext.current
    val clipboardManager = remember {
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }
    var clipboardAmount by remember { mutableStateOf<String?>(null) }

    // Helper to check clipboard content
    fun checkClipboard() {
        try {
            if (clipboardManager.hasPrimaryClip()) {
                val item = clipboardManager.primaryClip?.getItemAt(0)
                val text = item?.text?.toString() ?: ""
                val regex = Regex("""(?:€|$|EUR)?\s*(\d{1,6}[\.,]\d{2})\s*(?:€|$|EUR)?""")
                val match = regex.find(text)
                if (match != null) {
                    val value = match.groupValues[1].replace(",", ".").toDoubleOrNull()
                    if (value != null && value in 0.01..100000.0) {
                        clipboardAmount = match.groupValues[1]
                    } else {
                        clipboardAmount = null
                    }
                } else {
                    clipboardAmount = null
                }
            } else {
                clipboardAmount = null
            }
        } catch (e: Exception) {
            // Ignore clipboard errors
        }
    }

    // Listen for clipboard changes while this composable is active
    DisposableEffect(clipboardManager) {
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            checkClipboard()
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        // Initial check
        checkClipboard()

        onDispose {
            clipboardManager.removePrimaryClipChangedListener(listener)
        }
    }

    // Also check on resume to handle background changes
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                checkClipboard()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val (icon, label) = when (selectedTab) {
        2 -> Pair(Icons.Rounded.CheckCircle, "Approve All")
        else -> {
            if (clipboardAmount != null) {
                Pair(Icons.Rounded.ContentPaste, "Add €$clipboardAmount")
            } else {
                Pair(Icons.Rounded.Add, "Add Expense")
            }
        }
    }

    Column(horizontalAlignment = Alignment.End) {
        // Speed Dial Actions
        AnimatedVisibility(
            visible = isExpanded && selectedTab != 2,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = { 
                        haptic(HapticType.Standard)
                        onScanReceipt() 
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ReceiptLong, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Receipt")
                    }
                }

                SmallFloatingActionButton(
                    onClick = { 
                        haptic(HapticType.Standard)
                        onAddExpense() 
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Manual")
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { 
                haptic(HapticType.Heavy)
                if (selectedTab == 2) {
                    onApproveAll()
                } else {
                    onToggleExpand()
                }
            },
            icon = { 
                Icon(
                    if (isExpanded && selectedTab != 2) Icons.Rounded.Close else icon, 
                    contentDescription = label
                ) 
            },
            text = { Text(if (isExpanded && selectedTab != 2) "Close" else label) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\MainViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruimainviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: NotificationRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _navigationRequest = kotlinx.coroutines.flow.MutableSharedFlow<Int>(replay = 1)
    val navigationRequest = _navigationRequest.asSharedFlow()

    val pendingReviewCount: StateFlow<Int> = repository
        .getPendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun navigateToTab(tabIndex: Int) {
        viewModelScope.launch {
            _navigationRequest.emit(tabIndex)
        }
    }

    fun isNotificationServiceEnabled(): Boolean {
        val packageName = context.packageName
        val flat = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return flat != null && flat.contains(packageName)
    }
}


```

---

## app\src\main\java\com\yourname\expensetracker\ui\components\BentoCard.kt <a name="appsrcmainjavacomyournameexpensetrackeruicomponentsbentocardkt"></a>
```kotlin
package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.util.Currency

/**
 * Atomic BentoCard — the building block for the Bento Grid layout.
 * Features: Glassmorphism (semi-transparency + hairline border).
 */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    containerColor: Color = SemanticColors.GlassSurface,
    cornerRadius: Dp = 24.dp, // Modern, rounder look
    contentPadding: PaddingValues = PaddingValues(16.dp),
    border: BorderStroke = BorderStroke(1.dp, SemanticColors.GlassBorder),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(cornerRadius),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = border,
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
            border = border
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    }
}

/**
 * Hero BentoCard — larger, primary-colored gradient, for the main metric.
 */
@Composable
fun HeroBentoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // Gradient for a more vibrant Hero card
    val heroGradient = remember {
        Brush.linearGradient(
            colors = listOf(
                SemanticColors.PrimaryIndigo.copy(alpha = 0.4f),
                SemanticColors.PrimaryLight.copy(alpha = 0.2f)
            )
        )
    }

    BentoCard(
        modifier = modifier.background(heroGradient, RoundedCornerShape(28.dp)),
        containerColor = Color.Transparent, // Overridden by custom modifier or nested content if needed
        cornerRadius = 28.dp,
        contentPadding = PaddingValues(24.dp),
        border = BorderStroke(1.dp, SemanticColors.PrimaryLight.copy(alpha = 0.2f))
    ) {
        // We use a Surface/Box inside if we want a complex gradient background, 
        // but for now, the BentoCard's containerColor is our base.
        // Let's refine the BentoCard to support custom backgrounds better or just use containerColor.
        content()
    }
}

/**
 * Compact stat label used inside BentoCards.
 */
@Composable
fun StatLabel(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = SemanticColors.TextPrimary
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextSecondary
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
 * Amount text with tabular figures and premium weights.
 */
@Composable
fun AmountText(
    amount: Double,
    currency: String = Currency.getInstance("EUR").symbol,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displaySmall,
    color: Color = SemanticColors.TextPrimary
) {
    Text(
        text = "$currency${String.format("%.2f", amount)}",
        style = style.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.ExtraBold, // More premium weight
        color = color,
        modifier = modifier
    )
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\components\BudgetBlockPartyCard.kt <a name="appsrcmainjavacomyournameexpensetrackeruicomponentsbudgetblockpartycardkt"></a>
```kotlin
package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.ui.theme.SemanticColors

enum class BlockStatus {
    UNDER_BUDGET, // Time to Party (Green)
    OVER_BUDGET,  // Party Pooper (Red)
    FUTURE,       // TBD (Gray)
    TODAY,        // Active (Blue)
    BILL_DAY      // Bills (White Outline)
}

data class DayBudgetStatus(
    val dayOfMonth: Int,
    val date: Long,
    val actualSpent: Double,
    val targetBudget: Double,
    val isToday: Boolean,
    val status: BlockStatus,
    // Drill-Down Data
    val baseTarget: Double = 0.0,
    val recurringImpact: Double = 0.0,
    val plannedImpact: Double = 0.0,
    val recurringItems: List<String> = emptyList(),
    val plannedItems: List<String> = emptyList(),
    val topTransactions: List<com.yourname.expensetracker.data.database.entity.Expense> = emptyList()
)

@Composable
fun BudgetBlockPartyCard(
    days: List<DayBudgetStatus>,
    modifier: Modifier = Modifier
) {
    var selectedDay by remember { mutableStateOf<DayBudgetStatus?>(null) }

    if (selectedDay != null) {
        DayAtAGlanceDialog(
            day = selectedDay!!,
            onDismiss = { selectedDay = null }
        )
    }

    BentoCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp)
    ) {
        Text(
            "BUDGET BLOCK PARTY",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            days.chunked(7).forEach { week ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    week.forEach { day ->
                         Box(modifier = Modifier.weight(1f)) {
                             DayBlock(day, onClick = { selectedDay = day })
                         }
                    }
                    // Fill remaining space if last week is short
                    if (week.size < 7) {
                        repeat(7 - week.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DayBlock(day: DayBudgetStatus, onClick: () -> Unit) {
    val isBillDay = day.status == BlockStatus.BILL_DAY
    val color = when (day.status) {
        BlockStatus.UNDER_BUDGET -> SemanticColors.SuccessGreen
        BlockStatus.OVER_BUDGET -> SemanticColors.DangerRed
        BlockStatus.TODAY -> SemanticColors.PrimaryIndigo
        BlockStatus.FUTURE -> SemanticColors.GlassBorder.copy(alpha = 0.5f)
        BlockStatus.BILL_DAY -> Color.Transparent
    }

    val borderModifier = if (isBillDay) {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
    } else Modifier

    Box(
        modifier = Modifier
            .aspectRatio(1.2f)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = if (day.status == BlockStatus.FUTURE) 0.2f else if (isBillDay) 0f else 0.9f))
            .then(borderModifier)
            .clickable(enabled = day.status != BlockStatus.FUTURE, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (day.status != BlockStatus.FUTURE) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${day.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                if (isBillDay) {
                     Text(
                        text = "💸",
                        fontSize = 8.sp,
                        modifier = Modifier.alpha(0.8f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayAtAGlanceDialog(
    day: DayBudgetStatus,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()) }
    val dateStr = dateFormat.format(java.util.Date(day.date))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SemanticColors.BaseNavy,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = dateStr.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = SemanticColors.TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (day.status == BlockStatus.UNDER_BUDGET) "Under Budget" else "Over Budget",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (day.status == BlockStatus.UNDER_BUDGET) SemanticColors.SuccessGreen else SemanticColors.DangerRed,
                        fontWeight = FontWeight.Black
                    )
                }

                // Balance badge
                val balance = day.targetBudget - day.actualSpent
                val balanceColor = if (balance >= 0) SemanticColors.SuccessGreen else SemanticColors.DangerRed
                Surface(
                    color = balanceColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = (if (balance >= 0) "+" else "") + "€${String.format("%.2f", balance)}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = balanceColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 🎯 Target Breakdown
            Text("TARGET BREAKDOWN", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = SemanticColors.GlassSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Base Allowance", color = SemanticColors.TextPrimary)
                        Text("€${String.format("%.2f", day.baseTarget)}", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                    }
                    if (day.recurringImpact > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Recurring (${day.recurringItems.joinToString(", ")})", color = SemanticColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("+€${String.format("%.2f", day.recurringImpact)}", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                        }
                    }
                    if (day.plannedImpact > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Planned (${day.plannedItems.joinToString(", ")})", color = SemanticColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("+€${String.format("%.2f", day.plannedImpact)}", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SemanticColors.GlassBorder)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Target", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                        Text("€${String.format("%.2f", day.targetBudget)}", fontWeight = FontWeight.Bold, color = SemanticColors.PrimaryIndigo)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 💸 Actual Spending
            Text("WHAT HAPPENED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = SemanticColors.GlassSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                     Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Spent", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                        Text("€${String.format("%.2f", day.actualSpent)}", fontWeight = FontWeight.Bold, color = SemanticColors.TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (day.topTransactions.isNotEmpty()) {
                        day.topTransactions.forEach { exp ->
                             Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(exp.merchant, color = SemanticColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                Text("€${String.format("%.2f", exp.amount)}", color = SemanticColors.TextPrimary, fontSize = 13.sp)
                            }
                        }
                    } else if (day.actualSpent > 0) {
                        Text("No specific transactions found.", style = MaterialTheme.typography.bodySmall, color = SemanticColors.TextSecondary)
                    } else {
                        Text("No spending recorded.", style = MaterialTheme.typography.bodySmall, color = SemanticColors.TextSecondary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onDismiss, // Ideally navigate to transactions filtered by day, but that requires hoisting nav logic. Keep simple for now.
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.GlassSurface, contentColor = SemanticColors.TextPrimary),
                border = androidx.compose.foundation.BorderStroke(1.dp, SemanticColors.GlassBorder)
            ) {
                Text("Close")
            }
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\components\ChartMarker.kt <a name="appsrcmainjavacomyournameexpensetrackeruicomponentschartmarkerkt"></a>
```kotlin
package com.yourname.expensetracker.ui.components

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.component.lineComponent
import com.patrykandpatrick.vico.compose.component.overlayingComponent
import com.patrykandpatrick.vico.compose.component.shapeComponent
import com.patrykandpatrick.vico.compose.component.textComponent
import com.patrykandpatrick.vico.compose.dimensions.dimensionsOf
import com.patrykandpatrick.vico.core.component.marker.MarkerComponent
import com.patrykandpatrick.vico.core.component.shape.DashedShape
import com.patrykandpatrick.vico.core.component.shape.LineComponent
import com.patrykandpatrick.vico.core.component.shape.ShapeComponent
import com.patrykandpatrick.vico.core.component.shape.Shapes
import com.patrykandpatrick.vico.core.marker.Marker
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun rememberMarker(): Marker {
    val labelBackgroundColor = MaterialTheme.colorScheme.surface
    val labelBackground = remember(labelBackgroundColor) {
        ShapeComponent(Shapes.pillShape, labelBackgroundColor.toArgb()).setShadow(
            radius = 4f,
            dy = 2f,
            applyElevationOverlay = true,
        )
    }

    val label = textComponent(
        color = MaterialTheme.colorScheme.onSurface,
        background = labelBackground,
        padding = dimensionsOf(8.dp, 4.dp),
        typeface = Typeface.MONOSPACE,
    )

    val indicator = shapeComponent(Shapes.pillShape, SemanticColors.PrimaryIndigo)

    val guidelineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val guideline = lineComponent(
        color = guidelineColor,
        thickness = 2.dp,
        shape = DashedShape(Shapes.pillShape, 2f, 4f),
    )

    return remember(label, indicator, guideline) {
        object : MarkerComponent(label, indicator, guideline) {
            init {
                indicatorSizeDp = 6f
                onApplyEntryColor = { entryColor ->
                    indicator.color = withAlpha(entryColor, 255)
                    labelBackground.color = withAlpha(entryColor, 34) // ~13% opacity
                }
            }
        }
    }
}

private fun withAlpha(color: Int, alpha: Int): Int {
    return (color and 0x00FFFFFF) or (alpha shl 24)
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\components\FinancialWeatherCard.kt <a name="appsrcmainjavacomyournameexpensetrackeruicomponentsfinancialweathercardkt"></a>
```kotlin
package com.yourname.expensetracker.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.data.repository.WeatherState
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.UpcomingItem
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.EventNote

@Composable
fun FinancialWeatherCard(
    state: WeatherState,
    headline: String,
    summary: String,
    icon: String,
    totalCommitted: Double,
    totalLikely: Double,
    discretionaryBudget: Double,
    pastSpendingPoints: List<Double> = emptyList(),
    projectedSpendingPoints: List<Double> = emptyList(),
    upcomingItems: List<UpcomingItem> = emptyList(),
    details: List<com.yourname.expensetracker.domain.model.NarrativeSection> = emptyList(),
    totalRecurringCount: Int = 0,
    onManageClick: () -> Unit = {},
    onPlanClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val gradient = when (state) {
        WeatherState.CLEAR_SKIES -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF4CAF50).copy(alpha = 0.1f),
                Color(0xFF4CAF50).copy(alpha = 0.05f)
            )
        )
        WeatherState.PARTLY_CLOUDY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF8BC34A).copy(alpha = 0.1f),
                Color(0xFF8BC34A).copy(alpha = 0.05f)
            )
        )
        WeatherState.CLOUDY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFC107).copy(alpha = 0.1f),
                Color(0xFFFFC107).copy(alpha = 0.05f)
            )
        )
        WeatherState.RAINY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF9800).copy(alpha = 0.12f),
                Color(0xFFFF9800).copy(alpha = 0.06f)
            )
        )
        WeatherState.STORMY -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF5722).copy(alpha = 0.15f),
                Color(0xFFFF5722).copy(alpha = 0.05f)
            )
        )
        WeatherState.UNKNOWN -> Brush.verticalGradient(
            colors = listOf(
                SemanticColors.GlassSurface,
                SemanticColors.GlassSurface
            )
        )
    }

    val textColor = when (state) {
        WeatherState.CLEAR_SKIES -> SemanticColors.SuccessGreen
        WeatherState.PARTLY_CLOUDY -> Color(0xFF8BC34A)
        WeatherState.CLOUDY -> SemanticColors.WarningOrange
        WeatherState.RAINY -> Color(0xFFFF9800)
        WeatherState.STORMY -> SemanticColors.DangerRed
        WeatherState.UNKNOWN -> SemanticColors.TextSecondary
    }

    BentoCard(
        modifier = modifier.background(gradient, RoundedCornerShape(24.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Weather Icon Circle
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(textColor.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = icon, fontSize = 28.sp)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "FINANCIAL WEATHER",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = headline.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = textColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextPrimary,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Forecast Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ForecastMetric("COMMITTED", totalCommitted, SemanticColors.TextSecondary)
                ForecastMetric("LIKELY", totalLikely, SemanticColors.TextSecondary)
                ForecastMetric("AVAILABLE", discretionaryBudget, textColor)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Forecast Trajectory Chart (Full Width)
            ForecastTimeline(
                pastPoints = pastSpendingPoints,
                projectedPoints = projectedSpendingPoints,
                budgetLimit = totalCommitted + totalLikely + discretionaryBudget,
                modifier = Modifier.fillMaxWidth()
            )

            if (details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))

                OutlinedButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticColors.PrimaryIndigo
                    )
                ) {
                    Text(
                        text = if (expanded) "HIDE BREAKDOWN" else "SEE BREAKDOWN",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        details.forEach { section ->
                            DetailSection(section)
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = SemanticColors.GlassBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // Management Section
            if (upcomingItems.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "UPCOMING (NEXT 30 DAYS)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 0.5.sp
                    )

                    Row {
                        TextButton(
                            onClick = onPlanClick,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "PLAN", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        TextButton(
                            onClick = onManageClick,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                "MANAGE ALL", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                upcomingItems.take(3).forEach { item ->
                    UpcomingRow(item)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$totalRecurringCount RECURRING ITEMS TRACKED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 0.5.sp
                    )

                    Row {
                        TextButton(
                            onClick = onPlanClick,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "PLAN", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        TextButton(
                            onClick = onManageClick,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                "MANAGE RECURRING", 
                                style = MaterialTheme.typography.labelSmall,
                                color = SemanticColors.PrimaryIndigo
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ForecastMetric(label: String, amount: Double, color: Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.TextMuted,
            letterSpacing = 0.5.sp
        )
        Text(
            text = "€${String.format(Locale.US, "%.0f", amount)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
fun UpcomingRow(item: UpcomingItem) {
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    val daysUntil = ((item.date - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt()

    val dateLabel = when {
        daysUntil <= 0 -> "Today"
        daysUntil == 1 -> "Tomorrow"
        else -> dateFormat.format(Date(item.date))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // Distinction Icon
            val icon = if (item is UpcomingItem.Recurring) Icons.Default.Repeat else Icons.Default.EventNote
            val badgeText = if (item is UpcomingItem.Recurring) {
                item.pattern.frequency.name.lowercase().capitalize()
            } else {
                "Planned"
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(SemanticColors.GlassBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, 
                    contentDescription = null, 
                    modifier = Modifier.size(16.dp),
                    tint = SemanticColors.TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SemanticColors.TextPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (daysUntil <= 1) SemanticColors.WarningOrange else SemanticColors.TextSecondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "• $badgeText",
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextMuted
                    )
                }
            }
        }

        Text(
            text = "€${String.format(Locale.US, "%.0f", item.amount)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = SemanticColors.TextPrimary
        )
    }
}

@Composable
fun DetailSection(section: com.yourname.expensetracker.domain.model.NarrativeSection) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(SemanticColors.PrimaryIndigo.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = section.icon, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = section.title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.TextSecondary,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        section.items.forEach { item ->
            Row(
                modifier = Modifier
                    .padding(start = 36.dp, bottom = 4.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextMuted,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextPrimary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\components\ForecastTimeline.kt <a name="appsrcmainjavacomyournameexpensetrackeruicomponentsforecasttimelinekt"></a>
```kotlin
package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.ChartEntryModel
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun ForecastTimeline(
    pastPoints: List<Double>,
    projectedPoints: List<Double>,
    budgetLimit: Double,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "FORECAST TRAJECTORY",
            style = MaterialTheme.typography.labelSmall,
            color = SemanticColors.TextMuted,
            letterSpacing = 0.5.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (pastPoints.isEmpty() && projectedPoints.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("No data available", style = MaterialTheme.typography.labelSmall)
            }
            return
        }

        // Vico model creation - Optimized: wrap in remember to avoid allocation spikes
        val chartEntryModel: ChartEntryModel = remember(pastPoints, projectedPoints, budgetLimit) {
            val pastEntries = pastPoints.mapIndexed { index, value -> 
                FloatEntry(index.toFloat(), value.toFloat()) 
            }
            val projectionEntries = projectedPoints.mapIndexed { index, value -> 
                FloatEntry((pastPoints.size + index).toFloat(), value.toFloat()) 
            }
            val budgetLimitEntries = listOf(
                FloatEntry(0f, budgetLimit.toFloat()),
                FloatEntry((pastPoints.size + projectionEntries.size).toFloat(), budgetLimit.toFloat())
            )
            entryModelOf(pastEntries, projectionEntries, budgetLimitEntries)
        }

        val lineSpecs = remember {
            listOf(
                LineChart.LineSpec(
                    lineColor = SemanticColors.PrimaryIndigo.toArgb(),
                ),
                LineChart.LineSpec(
                    lineColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.3f).toArgb(),
                ),
                LineChart.LineSpec(
                    lineColor = SemanticColors.WarningOrange.copy(alpha = 0.5f).toArgb(),
                    lineThicknessDp = 1f
                )
            )
        }

        Chart(
            chart = lineChart(lines = lineSpecs),
            model = chartEntryModel,
            startAxis = rememberStartAxis(),
            bottomAxis = rememberBottomAxis(),
            chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = true),
            marker = rememberMarker(),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
             LegendItem("Actual", SemanticColors.PrimaryIndigo)
             Spacer(modifier = Modifier.width(16.dp))
             LegendItem("Projected", SemanticColors.PrimaryIndigo.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = SemanticColors.TextSecondary)
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\components\PulseDot.kt <a name="appsrcmainjavacomyournameexpensetrackeruicomponentspulsedotkt"></a>
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

```

---

## app\src\main\java\com\yourname\expensetracker\ui\components\SpendingPaceGauge.kt <a name="appsrcmainjavacomyournameexpensetrackeruicomponentsspendingpacegaugekt"></a>
```kotlin
package com.yourname.expensetracker.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
        PaceStatus.UNDER_PACE -> SemanticColors.SuccessGreen
        PaceStatus.ON_PACE -> SemanticColors.PrimaryIndigo
        PaceStatus.OVER_PACE -> SemanticColors.WarningOrange
        PaceStatus.NO_BASELINE -> SemanticColors.TextMuted
    }

    // Animate the sweep angle (240 degree range)
    val maxPacePercent = 200f
    val targetSweep = (pace.pacePercentage / maxPacePercent).coerceIn(0f, 1f) * 240f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(800), // More responsive
        label = "pace_sweep_${pace.paceStatus}"
    )

    val statusLabel = when (pace.paceStatus) {
        PaceStatus.UNDER_PACE -> "Under pace"
        PaceStatus.ON_PACE -> "On track"
        PaceStatus.OVER_PACE -> "Over pace"
        PaceStatus.NO_BASELINE -> "Calculating..."
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(130.dp), // Slightly larger
            contentAlignment = Alignment.Center
        ) {
            val trackColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f)

            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val strokeWidth = 10.dp.toPx()
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

                // Background arc
                drawArc(
                    color = trackColor,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Foreground arc (Current Pace)
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

            // Center metric
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${pace.pacePercentage.toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = SemanticColors.TextPrimary
                )
                Text(
                    text = "Day ${pace.daysElapsed}/${pace.daysInMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            color = paceColor.copy(alpha = 0.15f),
            shape = CircleShape
        ) {
            Text(
                text = statusLabel.uppercase(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = paceColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\components\SpendingTrendChart.kt <a name="appsrcmainjavacomyournameexpensetrackeruicomponentsspendingtrendchartkt"></a>
```kotlin
package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.yourname.expensetracker.ui.theme.SemanticColors

@Composable
fun SpendingTrendChart(
    currentMonthData: List<Float>,
    previousMonthData: List<Float>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "TREND", 
                    style = MaterialTheme.typography.labelSmall, 
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextSecondary,
                    letterSpacing = 1.sp
                )
                Text(
                    "This month vs Last", 
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (currentMonthData.isEmpty() && previousMonthData.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No data", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val chartEntryModel = remember(currentMonthData, previousMonthData) {
                entryModelOf(
                    currentMonthData.mapIndexed { index, value -> entryOf(index, value) }, 
                    previousMonthData.mapIndexed { index, value -> entryOf(index, value) }
                )
            }

            Chart(
                chart = lineChart(
                    lines = listOf(
                        com.patrykandpatrick.vico.compose.chart.line.lineSpec(lineColor = SemanticColors.PrimaryIndigo),
                        com.patrykandpatrick.vico.compose.chart.line.lineSpec(lineColor = SemanticColors.TextMuted.copy(alpha = 0.5f))
                    )
                ),
                model = chartEntryModel,
                startAxis = rememberStartAxis(
                    label = null,
                    tick = null,
                    guideline = null,
                    axis = null
                ),
                bottomAxis = rememberBottomAxis(
                    label = null,
                    tick = null,
                    guideline = null,
                    axis = null
                ),
                chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = true),
                marker = rememberMarker(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseSheet.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpensesheetkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.addexpense

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.util.Currency

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    onDismiss: () -> Unit,
    initialAmount: String? = null,
    initialMerchant: String? = null,
    viewModel: AddExpenseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    // Handle save result
    LaunchedEffect(state.saveResult) {
        when (state.saveResult) {
            is SaveResult.Success -> {
                viewModel.reset()
                onDismiss()
            }
            else -> { /* handled in UI */ }
        }
    }

    // Set initial values once
    LaunchedEffect(Unit) {
        if (initialAmount != null || initialMerchant != null) {
            viewModel.setInitialValues(initialAmount, initialMerchant)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar
            TopAppBar(
                title = { Text(stringResource(com.yourname.expensetracker.R.string.add_expense_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(com.yourname.expensetracker.R.string.close_content_description))
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.save() },
                        enabled = !state.isSaving,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(com.yourname.expensetracker.R.string.save_button))
                        }
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // === Merchant Field with Autocomplete ===
                MerchantFieldWithSuggestions(
                    merchant = state.merchant,
                    onMerchantChange = { viewModel.updateMerchant(it) },
                    suggestions = state.suggestions,
                    showSuggestions = state.showSuggestions,
                    onSuggestionSelected = { viewModel.selectSuggestion(it) },
                    onDismissSuggestions = { viewModel.dismissSuggestions() },
                    error = state.merchantError,
                    categories = categories,
                    onNextFocus = { focusManager.moveFocus(FocusDirection.Down) }
                )

                // === Amount Field ===
                OutlinedTextField(
                    value = state.amount,
                    onValueChange = { viewModel.updateAmount(it) },
                    label = { Text(stringResource(com.yourname.expensetracker.R.string.amount_label)) },
                    placeholder = { Text(stringResource(com.yourname.expensetracker.R.string.amount_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    isError = state.amountError != null,
                    supportingText = state.amountError?.let { { Text(it) } },
                    leadingIcon = { Text(Currency.getInstance("EUR").symbol, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.fillMaxWidth()
                )

                // === Payment Method ===
                Text(
                    stringResource(com.yourname.expensetracker.R.string.payment_method_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PaymentMethodChip(
                        label = stringResource(com.yourname.expensetracker.R.string.payment_method_card),
                        selected = state.paymentMethod == PaymentMethod.CARD,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.CARD) },
                        modifier = Modifier.weight(1f)
                    )
                    PaymentMethodChip(
                        label = stringResource(com.yourname.expensetracker.R.string.payment_method_cash),
                        selected = state.paymentMethod == PaymentMethod.CASH,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.CASH) },
                        modifier = Modifier.weight(1f)
                    )
                    PaymentMethodChip(
                        label = stringResource(com.yourname.expensetracker.R.string.payment_method_transfer),
                        selected = state.paymentMethod == PaymentMethod.BANK_TRANSFER,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.BANK_TRANSFER) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // === Category Selector ===
                Text(
                    stringResource(com.yourname.expensetracker.R.string.category_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                CategoryGrid(
                    categories = categories,
                    selectedId = state.selectedCategoryId,
                    onSelect = { viewModel.selectCategory(it) }
                )

                // === Date Picker ===
                DateSelector(
                    dateMs = state.date,
                    onDateSelected = { viewModel.updateDate(it) }
                )

                // === Transaction Type (collapsible) ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleTransactionType() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(com.yourname.expensetracker.R.string.transaction_type_prefix, state.transactionType.name.lowercase()
                            .replaceFirstChar { it.uppercase() }),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (state.showTransactionType) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(com.yourname.expensetracker.R.string.toggle_content_description)
                    )
                }

                AnimatedVisibility(visible = state.showTransactionType) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransactionType.values().filter { it != TransactionType.UNKNOWN }.forEach { type ->
                            FilterChip(
                                selected = state.transactionType == type,
                                onClick = { viewModel.selectTransactionType(type) },
                                label = {
                                    Text(
                                        type.name.lowercase().replaceFirstChar { it.uppercase() },
                                        fontSize = 12.sp
                                    )
                                }
                            )
                        }
                    }
                }

                // === Notes (collapsible) ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleNotes() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(com.yourname.expensetracker.R.string.notes_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (state.showNotes) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(com.yourname.expensetracker.R.string.toggle_content_description)
                    )
                }

                AnimatedVisibility(visible = state.showNotes) {
                    OutlinedTextField(
                        value = state.notes,
                        onValueChange = { viewModel.updateNotes(it) },
                        label = { Text(stringResource(com.yourname.expensetracker.R.string.notes_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }

                // === Recurring Options ===
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(com.yourname.expensetracker.R.string.repeat_transaction_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = state.isRecurring,
                        onCheckedChange = { viewModel.toggleRecurring() }
                    )
                }

                AnimatedVisibility(visible = state.isRecurring) {
                    Column {
                        Text(
                            stringResource(com.yourname.expensetracker.R.string.frequency_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Simple horizontal scroll for frequencies
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.yourname.expensetracker.domain.model.RecurrenceFrequency.values()
                                .filter { it != com.yourname.expensetracker.domain.model.RecurrenceFrequency.IRREGULAR }
                                .forEach { freq ->
                                    FilterChip(
                                        selected = state.recurrenceFrequency == freq,
                                        onClick = { viewModel.setRecurrenceFrequency(freq) },
                                        label = { 
                                            Text(freq.name.lowercase().replaceFirstChar { it.uppercase() }) 
                                        }
                                    )
                                }
                        }
                    }
                }

                // === Error Messages ===
                when (val result = state.saveResult) {
                    is SaveResult.Duplicate -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(com.yourname.expensetracker.R.string.error_duplicate_transaction),
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    is SaveResult.Error -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "❌ ${result.message}",
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    else -> {}
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun MerchantFieldWithSuggestions(
    merchant: String,
    onMerchantChange: (String) -> Unit,
    suggestions: List<MerchantSuggestion>,
    showSuggestions: Boolean,
    onSuggestionSelected: (MerchantSuggestion) -> Unit,
    onDismissSuggestions: () -> Unit,
    error: String?,
    categories: List<Category>,
    onNextFocus: () -> Unit
) {
    val categoryMap = remember(categories) { categories.associateBy { it.id } }

    Column {
        OutlinedTextField(
            value = merchant,
            onValueChange = onMerchantChange,
            label = { Text(stringResource(com.yourname.expensetracker.R.string.merchant_label)) },
            placeholder = { Text(stringResource(com.yourname.expensetracker.R.string.merchant_placeholder)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNextFocus() }),
            modifier = Modifier.fillMaxWidth()
        )

        // Suggestions dropdown
        AnimatedVisibility(visible = showSuggestions && suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column {
                    suggestions.forEach { suggestion ->
                        val category = suggestion.categoryId?.let { categoryMap[it] }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestionSelected(suggestion) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Category icon
                                if (category != null) {
                                    val catColor = remember(category.color) {
                                        try {
                                            Color(android.graphics.Color.parseColor(category.color))
                                        } catch (e: Exception) {
                                            Color.Gray
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(catColor, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(category.icon, fontSize = 16.sp)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        suggestion.merchant,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        buildString {
                                            if (category != null) append(category.name)
                                            if (suggestion.txCount > 0) {
                                                if (isNotEmpty()) append(" · ")
                                                append(stringResource(com.yourname.expensetracker.R.string.visits_suffix_format, suggestion.txCount))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Text(
                                    stringResource(com.yourname.expensetracker.R.string.avg_amount_format, suggestion.avgAmount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        if (suggestion != suggestions.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PaymentMethodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                color = if (selected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CategoryGrid(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    // Wrapping flow layout using multiple rows
    val chunked = remember(categories) { categories.chunked(4) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunked.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { category ->
                    val isSelected = selectedId == category.id
                    val catColor = remember(category.color) {
                        try {
                            Color(android.graphics.Color.parseColor(category.color))
                        } catch (e: Exception) {
                            Color.Gray
                        }
                    }
                    Surface(
                        onClick = { onSelect(category.id) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) catColor.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(
                            2.dp, catColor
                        ) else null
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(category.icon, fontSize = 20.sp)
                            Text(
                                category.name,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Fill remaining space in last row
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelector(
    dateMs: Long,
    onDateSelected: (Long) -> Unit
) {
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, dd MMM yyyy, HH:mm", Locale.getDefault()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = dateMs
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDatePicker = true },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DateRange,
            contentDescription = stringResource(com.yourname.expensetracker.R.string.date_label),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                stringResource(com.yourname.expensetracker.R.string.date_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                dateFormat.format(Instant.ofEpochMilli(dateMs).atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedDate ->
                            // Preserve time of day, just change the date
                            val calOld = Calendar.getInstance().apply { timeInMillis = dateMs }
                            val calNew = Calendar.getInstance().apply { timeInMillis = selectedDate }
                            calNew.set(Calendar.HOUR_OF_DAY, calOld.get(Calendar.HOUR_OF_DAY))
                            calNew.set(Calendar.MINUTE, calOld.get(Calendar.MINUTE))
                            calNew.set(Calendar.SECOND, calOld.get(Calendar.SECOND))
                            onDateSelected(calNew.timeInMillis)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(com.yourname.expensetracker.R.string.ok_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(com.yourname.expensetracker.R.string.cancel_button))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpenseviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.addexpense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.model.OperationResult
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.isActive

data class AddExpenseState(
    val merchant: String = "",
    val amount: String = "",
    val selectedCategoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val transactionType: TransactionType = TransactionType.PURCHASE,
    val date: Long = System.currentTimeMillis(),
    val notes: String = "",
    val showNotes: Boolean = false,
    val showTransactionType: Boolean = false,
    val isRecurring: Boolean = false,
    val recurrenceFrequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    val suggestions: List<MerchantSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,
    val isSaving: Boolean = false,
    val saveResult: SaveResult? = null,
    val merchantError: String? = null,
    val amountError: String? = null
)

sealed class SaveResult {
    object Success : SaveResult()
    object Duplicate : SaveResult()
    data class Error(val message: String) : SaveResult()
}

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val recurringExpenseDao: RecurringExpenseDao
) : ViewModel() {

    private val _state = MutableStateFlow(AddExpenseState())
    val state: StateFlow<AddExpenseState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var searchJob: Job? = null

    fun updateMerchant(value: String) {
        val sanitized = value.take(100) // Max 100 chars
        _state.update {
            it.copy(
                merchant = sanitized,
                merchantError = null,
                saveResult = null
            )
        }

        // Debounced search
        searchJob?.cancel()
        if (sanitized.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300)
                if (!isActive) return@launch

                val suggestions = repository.searchMerchants(sanitized)

                if (!isActive) return@launch

                _state.update {
                    it.copy(
                        suggestions = suggestions,
                        showSuggestions = suggestions.isNotEmpty()
                    )
                }
            }
        } else {
            _state.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
        }
    }

    fun selectSuggestion(suggestion: MerchantSuggestion) {
        _state.update {
            it.copy(
                merchant = suggestion.merchant,
                selectedCategoryId = suggestion.categoryId ?: it.selectedCategoryId,
                amount = if (it.amount.isBlank()) String.format("%.2f", suggestion.avgAmount) else it.amount,
                suggestions = emptyList(),
                showSuggestions = false,
                merchantError = null
            )
        }
    }

    fun dismissSuggestions() {
        _state.update { it.copy(showSuggestions = false) }
    }

    fun updateAmount(value: String) {
        // Only allow valid decimal input
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }
        _state.update {
            it.copy(
                amount = filtered,
                amountError = null,
                saveResult = null
            )
        }
    }

    fun selectCategory(categoryId: Long) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        _state.update { it.copy(paymentMethod = method) }
    }

    fun selectTransactionType(type: TransactionType) {
        _state.update { it.copy(transactionType = type) }
    }

    fun updateDate(dateMs: Long) {
        _state.update { it.copy(date = dateMs) }
    }

    fun updateNotes(value: String) {
        _state.update { it.copy(notes = value.take(500)) } // Max 500 chars
    }

    fun toggleNotes() {
        _state.update { it.copy(showNotes = !it.showNotes) }
    }

    fun toggleTransactionType() {
        _state.update { it.copy(showTransactionType = !it.showTransactionType) }
    }

    fun toggleRecurring() {
        _state.update { it.copy(isRecurring = !it.isRecurring) }
    }

    fun setRecurrenceFrequency(frequency: RecurrenceFrequency) {
        _state.update { it.copy(recurrenceFrequency = frequency) }
    }

    fun save() {
        val currentState = _state.value

        // Validate
        val merchantTrimmed = currentState.merchant.trim()
        if (merchantTrimmed.isBlank()) {
            _state.update { it.copy(merchantError = "Merchant name is required") }
            return
        }

        val amountStr = currentState.amount.replace(",", ".")
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _state.update { it.copy(amountError = "Enter a valid amount") }
            return
        }

        if (amount > 1_000_000) { // Reasonable upper limit
            _state.update { it.copy(amountError = "Amount is too large") }
            return
        }

        // Normalize to 2 decimal places
        val normalizedAmount = java.math.BigDecimal(amount)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .toDouble()

        _state.update { it.copy(isSaving = true, saveResult = null) }

        viewModelScope.launch {
            try {
                // 1. Save the actual transaction
                val result = repository.addManualExpense(
                    merchant = merchantTrimmed,
                    amount = normalizedAmount,
                    currency = "EUR",
                    categoryId = currentState.selectedCategoryId,
                    transactionType = currentState.transactionType,
                    paymentMethod = currentState.paymentMethod,
                    date = currentState.date,
                    notes = currentState.notes.takeIf { it.isNotBlank() }
                )

                when (result) {
                    is OperationResult.Success -> {
                        // 2. If recurring, save the rule
                        if (currentState.isRecurring) {
                            saveRecurringRule(merchantTrimmed, normalizedAmount, currentState.recurrenceFrequency, currentState.date)
                        }

                        _state.update {
                            it.copy(isSaving = false, saveResult = SaveResult.Success)
                        }
                    }
                    is OperationResult.Duplicate -> {
                        _state.update {
                            it.copy(isSaving = false, saveResult = SaveResult.Duplicate)
                        }
                    }
                    is OperationResult.Error -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveResult = SaveResult.Error(result.message)
                            )
                        }
                    }
                }

            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveResult = SaveResult.Error(e.message ?: "Unknown error")
                    )
                }
            }
        }
    }

    private suspend fun saveRecurringRule(
        merchant: String, 
        amount: Double, 
        frequency: RecurrenceFrequency, 
        lastDate: Long
    ) {
        // Calculate next date based on frequency using java.time for accuracy (DST/Leap years)
        val lastLocalDate = java.time.Instant.ofEpochMilli(lastDate)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()

        val nextLocalDate = when (frequency) {
            RecurrenceFrequency.WEEKLY -> lastLocalDate.plusWeeks(1)
            RecurrenceFrequency.BIWEEKLY -> lastLocalDate.plusWeeks(2)
            RecurrenceFrequency.MONTHLY -> lastLocalDate.plusMonths(1)
            RecurrenceFrequency.QUARTERLY -> lastLocalDate.plusMonths(3)
            RecurrenceFrequency.SEMI_ANNUALLY -> lastLocalDate.plusMonths(6)
            RecurrenceFrequency.ANNUALLY -> lastLocalDate.plusYears(1)
            RecurrenceFrequency.IRREGULAR -> lastLocalDate // Should not happen for recurring rule
            else -> lastLocalDate.plusDays(frequency.days.toLong()) // Fallback
        }

        val nextDate = nextLocalDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        val rule = ManualRecurringExpense(
            merchant = merchant,
            amount = amount,
            currency = "EUR",
            frequency = frequency,
            nextDate = nextDate,
            note = "Created from manual entry"
        )
        recurringExpenseDao.insert(rule)
    }

    fun reset() {
        _state.value = AddExpenseState()
    }

    fun setInitialValues(amount: String? = null, merchant: String? = null) {
        _state.update { 
            it.copy(
                amount = amount ?: it.amount,
                merchant = merchant ?: it.merchant
            )
        }
    }

    fun clearSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\analytics\AdvancedAnalyticsScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensanalyticsadvancedanalyticsscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.analytics

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.analytics.*
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.util.Locale

import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedAnalyticsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTransactions: (TransactionFilter) -> Unit,
    viewModel: AdvancedAnalyticsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    androidx.activity.compose.BackHandler {
        onNavigateBack()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { 
                        Column {
                            Text("Advanced Analytics", fontWeight = FontWeight.Bold)
                            Text(
                                text = when(uiState.selectedPeriod) {
                                    AnalyticsPeriod.WEEK -> "Weekly Analysis"
                                    AnalyticsPeriod.MONTH -> "Monthly Deep Dive"
                                    AnalyticsPeriod.QUARTER -> "Quarterly Review"
                                    AnalyticsPeriod.YEAR -> "Yearly Overview"
                                    AnalyticsPeriod.CUSTOM -> "Custom Range"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background
                    )
                )
                // Linear loader that doesn't push content down
                if (uiState.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = SemanticColors.PrimaryIndigo,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    ) { padding ->
        // Helper for refresh state
        val pullRefreshState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier.padding(padding)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Error banner as first item so it scrolls
                    if (uiState.error != null) {
                        item(key = "error_banner") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SemanticColors.DangerRed.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Text(uiState.error ?: "", color = SemanticColors.DangerRed, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    // 1. Period Selector
                    item(key = "period_selector") { 
                        PeriodSelector(
                            selected = uiState.selectedPeriod, 
                            onSelect = { viewModel.setPeriod(it) }
                        ) 
                    }

                    // 2. Statistical Highlights (Bento Grid)
                    uiState.statisticalInsights?.let { stats ->
                        item(key = "stats_highlights") { StatisticalHighlights(stats) }
                    }

                    // 3. Category Deep Dive
                    if (uiState.categoryAnalytics.isNotEmpty()) {
                        item(key = "header_category") { AnalyticsSectionHeader("Category Breakdown", "Budget vs Actual & Trends") }
                        items(
                            items = uiState.categoryAnalytics,
                            key = { it.category.id },
                            contentType = { "CategoryItem" }
                        ) { item ->
                            EnhancedCategoryItem(
                                item = item,
                                onClick = {
                                    onNavigateToTransactions(
                                        TransactionFilter(
                                            categoryId = item.category.id,
                                            dateRange = viewModel.getCurrentDateRange()
                                        )
                                    )
                                }
                            )
                        }
                    }

                    // 4. Spending Patterns
                    uiState.spendingPatterns?.let { patterns ->
                        item(key = "header_patterns") { AnalyticsSectionHeader("Spending Habits", "When & how you spend") }
                        item(key = "card_patterns") { SpendingPatternsCard(patterns) }
                    }

                    // 5. Merchant Intelligence
                    if (uiState.merchantAnalytics.isNotEmpty()) {
                        item(key = "header_merchant") { AnalyticsSectionHeader("Merchant Intelligence", "Top places & loyalty stats") }
                        items(
                            items = uiState.merchantAnalytics,
                            key = { it.merchant },
                            contentType = { "MerchantItem" }
                        ) { item ->
                            EnhancedMerchantItem(
                                item = item,
                                onClick = {
                                    onNavigateToTransactions(
                                        TransactionFilter(
                                            merchantName = item.merchant,
                                            dateRange = viewModel.getCurrentDateRange()
                                        )
                                    )
                                }
                            )
                        }
                    }

                    item(key = "spacer_bottom") { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
fun PeriodSelector(selected: AnalyticsPeriod, onSelect: (AnalyticsPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AnalyticsPeriod.values().forEach { period ->
            if (period != AnalyticsPeriod.CUSTOM) { // Skip custom for now as UI complexity is higher
                val isSelected = selected == period
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(period) }
                        .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = period.name.lowercase().titleCase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun StatisticalHighlights(stats: StatisticalInsights) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Daily Average Card
            BentoCard(modifier = Modifier.weight(1f)) {
                Text("DAILY AVERAGE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                AmountText(stats.averageDailySpend, style = MaterialTheme.typography.headlineMedium)
            }

            // Largest Transaction Card
            BentoCard(modifier = Modifier.weight(1f)) {
                Text("LARGEST SPEND", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                stats.largestTransaction?.let { 
                    AmountText(it.amount, style = MaterialTheme.typography.headlineMedium)
                    Text(it.merchant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Volatility / Consistency
        BentoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SPENDING CONSISTENCY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SemanticColors.TextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            stats.volatilityIndex < 30 -> "Very Consistent"
                            stats.volatilityIndex < 60 -> "Normal Variable"
                            else -> "Highly Volatile"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                CircularProgressIndicator(
                    progress = { stats.volatilityIndex / 100f },
                    modifier = Modifier.size(48.dp),
                    color = if (stats.volatilityIndex < 30) SemanticColors.SuccessGreen else SemanticColors.WarningOrange,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
fun EnhancedCategoryItem(
    item: EnhancedCategoryAnalytics,
    onClick: () -> Unit
) {
    val categoryColor = remember(item.category.color) {
        try { Color(android.graphics.Color.parseColor(item.category.color)) } 
        catch (e: Exception) { Color.Gray }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(categoryColor.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(item.category.icon, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.category.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("${item.transactionCount} transactions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    AmountText(item.totalSpent, style = MaterialTheme.typography.titleMedium)
                    item.changePercent?.let { change ->
                        Text(
                            text = "${if (change > 0) "+" else ""}${String.format("%.1f", change)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (change > 0) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                        )
                    }
                }
            }

            // Budget bar if exists
            item.budgetAmount?.let { budget ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { (item.totalSpent / budget).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = when(item.budgetStatus) {
                            BudgetHealthStatus.EXCEEDED -> SemanticColors.DangerRed
                            BudgetHealthStatus.CRITICAL -> SemanticColors.WarningOrange
                            else -> categoryColor
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${item.budgetUtilizationPercent?.toInt()}% of budget",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Sparkline (Mini chart)
            if (item.sparklineData.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                val chartModel = remember(item.category.id, item.sparklineData) {
                    entryModelOf(item.sparklineData.mapIndexed { index, value -> FloatEntry(index.toFloat(), value.toFloat()) })
                }
                Chart(
                    chart = lineChart(),
                    model = chartModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                )
            }
        }
    }
}

@Composable
fun EnhancedMerchantItem(
    item: EnhancedMerchantAnalytics,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(item.merchant.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.merchant, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${item.visitFrequency} visitor • ${item.consistencyRating} spend", 
                        style = MaterialTheme.typography.labelSmall, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    AmountText(item.totalSpent, style = MaterialTheme.typography.titleMedium)
                    item.priceChangePercent?.let { change ->
                        Text(
                            text = "Prices: ${if (change > 0) "+" else ""}${String.format("%.1f", change)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (change > 0) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatMicro("Avg Visit", item.averagePerVisit)
                StatMicro("Loyalty", "${item.loyaltyScore.toInt()}/100")
                item.predictedNextVisitDate?.let { 
                    val daysUntil = ((it - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt()
                    StatMicro("Next Expected", if (daysUntil <= 0) "Soon" else "$daysUntil days")
                }
            }
        }
    }
}

@Composable
fun SpendingPatternsCard(analysis: SpendingPatternAnalysis) {
    BentoCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = SemanticColors.PrimaryIndigo, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "You spend ${String.format("%.1fx", analysis.weekendVsWeekday.weekendToWeekdayRatio)} more on weekends",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Day of Week Bar Chart (Simplified)
            Row(
                modifier = Modifier.fillMaxWidth().height(100.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                val maxSpend = analysis.dayOfWeekStats.values.maxOfOrNull { it.totalSpent } ?: 1.0

                (0..6).forEach { dayIndex ->
                    val stat = analysis.dayOfWeekStats[dayIndex]
                    val heightRatio = ((stat?.totalSpent ?: 0.0) / maxSpend).toFloat().coerceAtLeast(0.1f)

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .weight(1f, fill = false)
                                .fillMaxHeight(heightRatio)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    if (dayIndex == analysis.mostActiveDayIndex) SemanticColors.PrimaryIndigo 
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stat?.dayName?.take(1) ?: "",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Detected Patterns List
            if (analysis.detectedPatterns.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))

                analysis.detectedPatterns.forEach { pattern ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                            modifier = Modifier.size(6.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                pattern.type.name.replace("_", " ").lowercase().titleCase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                pattern.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatMicro(label: String, value: Any) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value is Double) {
            AmountText(value, style = MaterialTheme.typography.labelMedium)
        } else {
            Text(value.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AnalyticsSectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ... (other code)

// Extension for capitalizing string
fun String.titleCase() = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\analytics\AdvancedAnalyticsViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensanalyticsadvancedanalyticsviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.domain.analytics.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import javax.inject.Inject

@HiltViewModel
class AdvancedAnalyticsViewModel @Inject constructor(
    private val analyticsEngine: AdvancedAnalyticsEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalyticsUiState())
    val uiState: StateFlow<AnalyticsUiState> = _uiState.asStateFlow()

    init {
        // Initial load
        loadData(AnalyticsPeriod.MONTH)
    }

    fun setPeriod(period: AnalyticsPeriod) {
        if (_uiState.value.selectedPeriod == period) return
        loadData(period)
    }

    fun refresh() {
        loadData(uiState.value.selectedPeriod, isRefresh = true)
    }

    private fun loadData(period: AnalyticsPeriod, isRefresh: Boolean = false) {
        viewModelScope.launch {
            // 1. Start loading, keep old data
            _uiState.update { 
                it.copy(
                    isLoading = !isRefresh, 
                    isRefreshing = isRefresh,
                    selectedPeriod = period, 
                    error = null
                ) 
            }

            try {
                // 2. Resolve PeriodRange (fast)
                val range = analyticsEngine.getPeriodRange(period)

                // 3. Fetch all analytics in parallel (Async)
                // We use async to avoid sequential blocking
                val categoryDeferred = async { analyticsEngine.getCategoryAnalytics(range) }
                val merchantDeferred = async { analyticsEngine.getMerchantAnalytics(range, limit = 20) }
                val patternsDeferred = async { analyticsEngine.getSpendingPatterns(range) }
                val statsDeferred = async { analyticsEngine.getStatisticalInsights(range) }

                val categoryData = categoryDeferred.await()
                val merchantData = merchantDeferred.await()
                val patternsData = patternsDeferred.await()
                val statsData = statsDeferred.await()

                // 4. Update state with new data
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        categoryAnalytics = categoryData,
                        merchantAnalytics = merchantData,
                        spendingPatterns = patternsData,
                        statisticalInsights = statsData
                    )
                }

            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false, 
                        isRefreshing = false,
                        error = "Failed to load data: ${e.message}"
                    ) 
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun getCurrentDateRange(): Pair<Long, Long> {
        val range = analyticsEngine.getPeriodRange(uiState.value.selectedPeriod, computeComparison = false)
        return Pair(range.startMs, range.endMs)
    }
}

data class AnalyticsUiState(
    val selectedPeriod: AnalyticsPeriod = AnalyticsPeriod.MONTH,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val categoryAnalytics: List<EnhancedCategoryAnalytics> = emptyList(),
    val merchantAnalytics: List<EnhancedMerchantAnalytics> = emptyList(),
    val spendingPatterns: SpendingPatternAnalysis? = null,
    val statisticalInsights: StatisticalInsights? = null
)
```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.analytics

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.analytics.*
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Financial Insights", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Period Selector (Top Level)
                item { PeriodSelector(state.selectedPeriod) { viewModel.selectPeriod(it) } }

                // 2. Main Hero Bento: Total Spent + Change
                item { TotalSpentHero(state) }

                // 3. AI Insights (Natural Language)
                if (state.insights.isNotEmpty()) {
                    item { NaturalLanguageInsightBento(state.insights.first()) }
                }

                // 4. Daily Spending Chart
                item { SpendingChartBento(state) }

                // 5. Category Breakdown
                if (state.categoryBreakdown.isNotEmpty()) {
                    item { SectionHeader("Breakdown by Category") }
                    items(state.categoryBreakdown) { CategoryItem(it) }
                }

                // 6. Deep Insights Carousel
                if (state.insights.size > 1) {
                    item { SectionHeader("Deep Insights") }
                    item { InsightsRow(state.insights.drop(1)) }
                }

                // 7. Merchant Breakdown
                if (state.merchantBreakdown.isNotEmpty()) {
                    item { SectionHeader("Top Merchants") }
                    items(state.merchantBreakdown.take(8)) { MerchantItem(it) }
                }

                // 8. Recurring
                if (state.recurring.isNotEmpty()) {
                    item { SectionHeader("Subscription Detection") }
                    items(state.recurring) { RecurringItem(it) }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun TotalSpentHero(state: AnalyticsState) {
    HeroBentoCard {
        Column {
            Text(
                text = "${state.selectedPeriod.name} Total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            AmountText(
                amount = state.currentTotal,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            state.changePercent?.let { change ->
                val isIncrease = change > 0
                val color = if (isIncrease) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                val icon = if (isIncrease) "📈" else "📉"

                Surface(
                    color = Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(icon, fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${if (change > 0) "+" else ""}${String.format("%.1f", change)}% vs last period",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "${state.transactionCount} transactions recorded in this period.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun NaturalLanguageInsightBento(insight: SpendingInsight) {
    BentoCard(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(insight.icon, fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = insight.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = insight.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
fun SpendingChartBento(state: AnalyticsState) {
    BentoCard {
        Column {
            Text(
                "Spending Distribution",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (state.dailyTotals.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text("Insufficient data for visualization", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                // Optimized: convert map to entries once and remember
                val chartEntryModel = remember(state.dailyTotals) {
                    val entries = state.dailyTotals.values.map { it.toFloat() }
                    entryModelOf(*entries.toTypedArray())
                }

                Chart(
                    chart = columnChart(),
                    model = chartEntryModel,
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(),
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )
            }
        }
    }
}

@Composable
fun PeriodSelector(selected: TimePeriod, onSelect: (TimePeriod) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(TimePeriod.values()) { period ->
            FilterChip(
                selected = selected == period,
                onClick = { onSelect(period) },
                label = { Text(period.name.lowercase().capitalize()) },
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun InsightsRow(insights: List<SpendingInsight>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 4.dp)
    ) {
        items(insights) { insight ->
            Card(
                modifier = Modifier.width(260.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(insight.icon, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            insight.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        insight.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryItem(item: CategoryBreakdown) {
    val categoryColor = remember(item.category.color) {
        try { Color(android.graphics.Color.parseColor(item.category.color)) } 
        catch (e: Exception) { Color.Gray }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(categoryColor.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(item.category.icon, fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.category.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("€${String.format("%.2f", item.total)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { item.percentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = categoryColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${item.percentage.toInt()}% of total spending",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun MerchantItem(item: MerchantBreakdown) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(item.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("${item.transactionCount} visits", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("€${String.format("%.2f", item.totalSpent)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RecurringItem(item: RecurringCandidate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("🔄", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.merchant, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("Estimated every ${item.intervalDays} days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("€${String.format("%.2f", item.amount)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(item.confidence.let { if (it > 0.8) "High confidence" else "Plausible" }, style = MaterialTheme.typography.labelSmall, color = if (item.confidence > 0.8) SemanticColors.SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Extension to help with capitalizing names
fun String.capitalize() = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.analytics.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class AnalyticsState(
    val selectedPeriod: TimePeriod = TimePeriod.MONTH,
    val currentTotal: Double = 0.0,
    val previousTotal: Double? = null,
    val changePercent: Float? = null,
    val transactionCount: Int = 0,
    val categoryBreakdown: List<CategoryBreakdown> = emptyList(),
    val merchantBreakdown: List<MerchantBreakdown> = emptyList(),
    val dailyTotals: Map<String, Double> = emptyMap(),
    val insights: List<SpendingInsight> = emptyList(),
    val recurring: List<RecurringCandidate> = emptyList(),
    val isLoading: Boolean = true
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val insightsEngine: InsightsEngine,
    private val recurringExpenseEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine,
    private val analyticsRepository: com.yourname.expensetracker.data.repository.AnalyticsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()

    private val _selectedPeriod = MutableStateFlow(TimePeriod.MONTH)

    init {
        viewModelScope.launch {
            combine(
                repository.getAllExpenses(),
                categoryRepository.allCategories,
                _selectedPeriod
            ) { expenses, categories, period ->
                Triple(expenses, categories, period)
            }
            .debounce(300)
            .flowOn(Dispatchers.Default)
            .collectLatest { (expenses, categories, period) ->
                _state.update { it.copy(isLoading = true, selectedPeriod = period) }
                computeAnalytics(expenses, categories, period)
            }
        }
    }

    fun selectPeriod(period: TimePeriod) {
        _selectedPeriod.value = period
    }

    private suspend fun computeAnalytics(
        allExpenses: List<Expense>,
        categories: List<Category>,
        period: TimePeriod
    ) {
        val purchases = allExpenses.filter { it.transactionType == TransactionType.PURCHASE }
        val now = System.currentTimeMillis()
        val categoryMap = categories.associateBy { it.id }

        // Calculate date ranges
        val (currentStart, currentEnd) = getPeriodRange(period, now)
        val periodLength = currentEnd - currentStart
        val previousStart = currentStart - periodLength
        val previousEnd = currentStart

        // Current period expenses
        val currentExpenses = purchases.filter { it.date in currentStart..currentEnd }
        val previousExpenses = purchases.filter { it.date in previousStart..previousEnd }

        // Use Repository for Totals and Trends
        // We collect ONE item from the flow since we are in a triggered block
        val summary = analyticsRepository.getSpendingSummary(currentStart, currentEnd).first()
        val catBreakdown = analyticsRepository.getCategoryBreakdown(currentStart, currentEnd).first()

        val currentTotal = summary.totalSpent
        val previousTotal = summary.previousTotalSpent ?: 0.0
        val changePercent = summary.changePercent

        // Category breakdown
        val categoryBreakdown = catBreakdown // Repo returns Domain model directly

        // Merchant breakdown (Still manual for now, or move to Repo later)
        val merchantBreakdown = currentExpenses
            .groupBy { it.merchant.uppercase() }
            .map { (_, exps) ->
                val totalAmount = exps.sumOf { it.amount }
                MerchantBreakdown(
                    name = exps.first().merchant,
                    totalSpent = totalAmount,
                    transactionCount = exps.size,
                    averageTransaction = totalAmount / exps.size,
                    categoryId = exps.firstOrNull()?.categoryId
                )
            }
            .sortedByDescending { it.totalSpent }

        // Daily totals for chart
        // Repo returns daily history as list of floats (daily totals)
        // InsightsEngine.buildDailyTotals previously returned Map<String, Double>
        // We need to check what the UI expects.
        // AnalyticsViewModel State: val dailyTotals: Map<String, Double>
        // We need to map Repo's list back to a Map if the UI depends on it. 
        // Or refactor UI. Let's look at `dailyTotals` usage in `AnalyticsScreen` later.
        // For now, let's keep using `insightsEngine` for `dailyTotals` to avoid breaking specific UI graph formatting 
        // OR map the repo data. 
        // Actually, `insightsEngine.buildDailyTotals` probably formats dates as keys. 
        // The repo returns just values. 
        // Let's stick to `insightsEngine.buildDailyTotals` for `dailyTotals` UNTIL we update the UI to accept a list.
        // But we SHOULD upgrade `getPeriodRange` to use Utils.
        val chartDays = when (period) {
            TimePeriod.TODAY -> 1
            TimePeriod.WEEK -> 7
            TimePeriod.MONTH -> 30
            TimePeriod.YEAR -> 365
            TimePeriod.ALL -> {
                val oldest = purchases.minOfOrNull { it.date } ?: now
                ((now - oldest) / 86_400_000L).toInt().coerceIn(7, 365)
            }
        }
        val dailyTotals = insightsEngine.buildDailyTotals(currentExpenses, chartDays)

        // Insights
        val insightsSnapshot = insightsEngine.generateInsights(categories, allExpenses)
        val insights = insightsEngine.getLegacyInsights(insightsSnapshot)

        // Recurring (use the list from snapshot but mapped to legacy if needed, or just legacy detection)
        // Refactor: Use RecurringExpenseEngine directly to ensure consistent detection (LOG-020)
        // We filter purchases for relevance but the engine can handle the full list too. 
        // Note: The engine normally looks at 12 months. 'allExpenses' here might be limited by 'period' if we passed filtered list?
        // Actually generateInsights receives 'allExpenses' (usually full list or large subset).
        // Let's assume 'allExpenses' passed to computeAnalytics is sufficient.
        val patterns = recurringExpenseEngine.getPatterns(allExpenses)

        val recurring = patterns.map { pattern ->
             RecurringCandidate(
                 merchant = pattern.merchantName,
                 amount = pattern.averageAmount,
                 intervalDays = pattern.periodVarianceDays, // Mapping variance or calculating interval? 
                 // RecurringPattern stores frequency enum, not raw days. We need to map back for UI if it expects days.
                 // Actually RecurringCandidate.intervalDays seems to act as "average interval".
                 // Let's approximate from Frequency.
                 occurrences = 0, // RecurringPattern doesn't expose raw count easily in this model unless we add it. 
                 // For now, let's keep it 0 or map frequency.days
                 nextExpectedDate = pattern.nextExpectedDate,
                 confidence = pattern.confidence
             )
        }.toMutableList()

        // Fix: RecurringCandidate needs 'occurrences' and 'intervalDays'. 
        // The new engine abstracts this. If the UI relies on it, we might need to expose it in RecurringPattern or calculate it.
        // For now, let's map frequency days.
        patterns.forEachIndexed { index, p ->
            recurring[index] = recurring[index].copy(
                intervalDays = p.frequency.days,
                occurrences = 3 // Minimum required by engine, placeholder
            )
        }

        _state.update {
            it.copy(
                selectedPeriod = period,
                currentTotal = currentTotal,
                previousTotal = if (previousTotal > 0) previousTotal else null,
                changePercent = changePercent,
                transactionCount = currentExpenses.size,
                categoryBreakdown = categoryBreakdown,
                merchantBreakdown = merchantBreakdown,
                dailyTotals = dailyTotals,
                insights = insights,
                recurring = recurring,
                isLoading = false
            )
        }
    }

    private fun getPeriodRange(period: TimePeriod, now: Long): Pair<Long, Long> {
        return when (period) {
            TimePeriod.TODAY -> {
                val start = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
                Pair(start, now)
            }
            TimePeriod.WEEK -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getLastNDaysRange(7)
            TimePeriod.MONTH -> com.yourname.expensetracker.domain.util.TimePeriodUtils.getMonthRange(0) // Current month
            TimePeriod.YEAR -> {
                 // Start of year logic wasn't in Utils yet, let's keep local or add to Utils.
                 // Utils had getMonthRange.
                 val cal = Calendar.getInstance()
                 cal.timeInMillis = now
                 cal.set(Calendar.DAY_OF_YEAR, 1)
                 cal.set(Calendar.HOUR_OF_DAY, 0)
                 cal.set(Calendar.MINUTE, 0)
                 cal.set(Calendar.SECOND, 0)
                 cal.set(Calendar.MILLISECOND, 0)
                 Pair(cal.timeInMillis, now)
            }
            TimePeriod.ALL -> Pair(0L, now)
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\budget\BudgetScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensbudgetbudgetscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BudgetScreen(
    viewModel: BudgetViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val categories by viewModel.categories.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<BudgetStatus?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Budgets") },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Budget")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { BudgetSummaryCard(uiState.budgets) }

                if (uiState.suggestions.isNotEmpty()) {
                    item { SuggestionsBanner(uiState.suggestions, categories, onAdd = { viewModel.addBudget(it) }) }
                }

                if (uiState.budgets.isEmpty()) {
                    item { EmptyBudgetsState { showAddDialog = true } }
                } else {
                    items(uiState.budgets) { budgetStatus ->
                        BudgetCard(
                            status = budgetStatus,
                            dateFormat = dateFormat,
                            onEdit = { editingBudget = budgetStatus },
                            onToggle = { isActive -> viewModel.toggleBudget(budgetStatus.budget.id, isActive) },
                            onDelete = { viewModel.deleteBudget(it) }
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }

        if (showAddDialog) {
            AddEditBudgetDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { viewModel.addBudget(it) },
                categories = categories
            )
        }

        editingBudget?.let { status ->
            AddEditBudgetDialog(
                initialBudget = status.budget,
                onDismiss = { editingBudget = null },
                onConfirm = { viewModel.updateBudget(it) },
                categories = categories
            )
        }
    }
}

@Composable
fun BudgetSummaryCard(budgets: List<BudgetStatus>) {
    val onTrack = budgets.count { it.healthStatus == BudgetHealthStatus.ON_TRACK }
    val warning = budgets.count { it.healthStatus == BudgetHealthStatus.WARNING || it.healthStatus == BudgetHealthStatus.CRITICAL }
    val exceeded = budgets.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            SummaryItem("On Track", onTrack, Color(0xFF4CAF50))
            SummaryItem("Warning", warning, Color(0xFFFFC107))
            SummaryItem("Exceeded", exceeded, Color(0xFFFF5722))
        }
    }
}

@Composable
fun SummaryItem(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun BudgetCard(
    status: BudgetStatus,
    dateFormat: SimpleDateFormat,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: (Budget) -> Unit
) {
    val progressColor = when (status.healthStatus) {
        BudgetHealthStatus.ON_TRACK -> Color(0xFF4CAF50)
        BudgetHealthStatus.WARNING -> Color(0xFFFFC107)
        BudgetHealthStatus.CRITICAL -> Color(0xFFFF9800)
        BudgetHealthStatus.EXCEEDED -> Color(0xFFFF5722)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.category?.icon ?: "💰",
                    fontSize = 24.sp
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        status.category?.name ?: "Overall Budget",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        "${status.budget.period.name.lowercase().capitalize()} • Starts ${dateFormat.format(Date(status.budget.startDate))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = status.budget.isActive,
                    onCheckedChange = onToggle,
                    modifier = Modifier.budgetScale(0.8f)
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "€${"%.2f".format(status.spentAmount)} spent",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Text(
                    "€${"%.2f".format(status.budget.amount)} limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { status.percentUsed.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = progressColor.copy(alpha = 0.2f)
            )

            if (status.percentUsed > 1f) {
                Text(
                    "€${"%.2f".format(status.spentAmount - status.budget.amount)} over budget",
                    color = Color(0xFFFF5722),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    "€${"%.2f".format(status.remainingAmount)} remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun SuggestionsBanner(
    suggestions: List<BudgetSuggestion>,
    categories: List<Category>,
    onAdd: (Budget) -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val suggestion = suggestions.getOrNull(currentIndex) ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Smart Suggestion", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "You spend a lot on ${suggestion.categoryName}. How about a monthly budget of €${"%.0f".format(suggestion.suggestedAmount)}?",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { if (currentIndex < suggestions.size - 1) currentIndex++ else currentIndex = 0 }) {
                    Text("Skip")
                }
                Button(onClick = {
                    onAdd(Budget(
                        categoryId = suggestion.categoryId,
                        amount = suggestion.suggestedAmount,
                        period = BudgetPeriod.MONTHLY,
                        startDate = System.currentTimeMillis()
                    ))
                }) {
                    Text("Create Budget")
                }
            }
        }
    }
}

@Composable
fun EmptyBudgetsState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("No budgets set yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Track your spending by category to save more money.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(onClick = onAdd) {
            Text("Set Your First Budget")
        }
    }
}

@Composable
fun AddEditBudgetDialog(
    initialBudget: Budget? = null,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (Budget) -> Unit
) {
    var amount by remember { mutableStateOf(initialBudget?.amount?.toString() ?: "") }
    var selectedCategory by remember { mutableStateOf(initialBudget?.categoryId) }
    var period by remember { mutableStateOf(initialBudget?.period ?: BudgetPeriod.MONTHLY) }
    var rollover by remember { mutableStateOf(initialBudget?.rollover ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialBudget == null) "Create Budget" else "Edit Budget") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Budget Amount (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )

                Text("Category", style = MaterialTheme.typography.labelMedium)
                CategorySelector(
                    categories = categories,
                    selectedId = selectedCategory,
                    onSelect = { selectedCategory = it }
                )

                Text("Period", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BudgetPeriod.values().forEach { p ->
                        FilterChip(
                            selected = period == p,
                            onClick = { period = p },
                            label = { Text(p.name.lowercase().capitalize(), fontSize = 12.sp) }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rollover, onCheckedChange = { rollover = it })
                    Text("Rollover unspent amount", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (amt > 0) {
                        val budgetToSave = initialBudget?.copy(
                            categoryId = selectedCategory,
                            amount = amt,
                            period = period,
                            rollover = rollover
                        ) ?: Budget(
                            categoryId = selectedCategory,
                            amount = amt,
                            period = period,
                            startDate = System.currentTimeMillis(),
                            rollover = rollover
                        )
                        onConfirm(budgetToSave)
                        onDismiss()
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySelector(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text("Overall") }
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedId == category.id,
                onClick = { onSelect(category.id) },
                label = { Text("${category.icon} ${category.name}") }
            )
        }
    }
}

// Extension to avoid repetitive logic
fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

// Helper for UI scaling using graphicsLayer for better performance
fun Modifier.budgetScale(scale: Float): Modifier = this.then(Modifier.graphicsLayer(scaleX = scale, scaleY = scale))

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\budget\BudgetViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensbudgetbudgetviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BudgetUiState(
    val budgets: List<BudgetStatus> = emptyList(),
    val suggestions: List<BudgetSuggestion> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BudgetUiState(isLoading = true))
    val uiState: StateFlow<BudgetUiState> = _uiState.asStateFlow()

    val categories = categoryRepository.allCategories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                budgetRepository.getBudgetStatuses(),
                flow { emit(budgetRepository.getSuggestions()) }
            ) { statuses, suggestions ->
                BudgetUiState(
                    budgets = statuses,
                    suggestions = suggestions,
                    isLoading = false
                )
            }.catch { e ->
                _uiState.emit(BudgetUiState(error = e.message, isLoading = false))
            }.collect {
                _uiState.emit(it)
            }
        }
    }

    fun addBudget(budget: Budget) {
        if (!validateThresholds(budget)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = budgetRepository.addBudget(budget)
            when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun updateBudget(budget: Budget) {
        if (!validateThresholds(budget)) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = budgetRepository.updateBudget(budget)
            when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }

    private fun validateThresholds(budget: Budget): Boolean {
        if (budget.notifyAtWarning <= 0f || budget.notifyAtWarning >= 1f) {
            _uiState.update { it.copy(error = "Warning threshold must be between 0 and 1") }
            return false
        }
        if (budget.notifyAtCritical <= budget.notifyAtWarning || budget.notifyAtCritical >= 1.05f) {
            _uiState.update { it.copy(error = "Critical threshold must be between warning and 100%") }
            return false
        }
        return true
    }

    fun deleteBudget(budget: Budget) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = budgetRepository.deleteBudget(budget)
             when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun toggleBudget(id: Long, isActive: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
             val result = budgetRepository.toggleBudget(id, isActive)
             when (result) {
                is com.yourname.expensetracker.domain.model.Result.Success -> {
                    _uiState.update { it.copy(isLoading = false) }
                }
                is com.yourname.expensetracker.domain.model.Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
                else -> {}
            }
        }
    }

    fun refreshSuggestions() {
        viewModelScope.launch {
            val suggestions = budgetRepository.getSuggestions()
            _uiState.update { it.copy(suggestions = suggestions) }
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\categories\CategoryScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreenscategoriescategoryscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    onDismiss: () -> Unit,
    viewModel: CategoryViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Categories") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Category")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { category ->
                CategoryItem(category)
            }
        }

        if (showAddDialog) {
            AddCategoryDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { name, icon, color ->
                    viewModel.addCategory(name, icon, color)
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
fun CategoryItem(category: Category) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val color = remember(category.color) {
                try {
                    Color(android.graphics.Color.parseColor(category.color))
                } catch (e: Exception) {
                    Color.Gray
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(category.icon, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(category.name, style = MaterialTheme.typography.bodyLarge)
            if (category.isDefault) {
                Spacer(modifier = Modifier.weight(1f))
                Text("Default", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("📦") } // Default icon
    var color by remember { mutableStateOf("#607D8B") } // Default color
    var isNameError by remember { mutableStateOf(false) }

    // Simple list of preset icons/colors could be added here for better UX

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it
                        if (it.isNotBlank()) isNameError = false
                    },
                    label = { Text("Name") },
                    isError = isNameError,
                    supportingText = { if (isNameError) Text("Name cannot be empty") },
                    singleLine = true
                )
                // In a real app, use a proper picker. For now, text fields or presets.
                OutlinedTextField(
                    value = icon,
                    onValueChange = { if (it.length <= 2) icon = it }, // Limit to emoji size
                    label = { Text("Icon (Emoji)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    if (name.isNotBlank()) {
                        onAdd(name, icon, color)
                    } else {
                        isNameError = true
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\categories\CategoryViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreenscategoriescategoryviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repository: CategoryRepository
) : ViewModel() {

    val categories: StateFlow<List<Category>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Seed default categories on first run
        viewModelScope.launch {
            repository.ensureDefaultCategories()
        }
    }

    fun addCategory(name: String, icon: String, color: String) {
        viewModelScope.launch {
            repository.addCategory(name, icon, color)
        }
    }

    // Future: delete, edit
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\debug\DebugDataStorage.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensdebugdebugdatastoragekt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles persistence of debug data to file storage.
 * Saves the most recent bank statement import debug data so it can be
 * reviewed even after app restart.
 */
@Singleton
class DebugDataStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val file = File(context.filesDir, "last_debug_data.json")

    /**
     * Save debug data to file
     */
    fun save(debugData: DebugData) {
        try {
            file.writeText(debugData.toJson())
            android.util.Log.d("DebugDataStorage", "Saved debug data to ${file.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("DebugDataStorage", "Failed to save debug data: ${e.message}")
        }
    }

    /**
     * Load debug data from file
     */
    fun load(): DebugData? {
        if (!file.exists()) {
            android.util.Log.d("DebugDataStorage", "No saved debug data found")
            return null
        }

        return try {
            val json = file.readText()
            parseDebugDataFromJson(json)
        } catch (e: Exception) {
            android.util.Log.e("DebugDataStorage", "Failed to load debug data: ${e.message}")
            null
        }
    }

    /**
     * Clear saved debug data
     */
    fun clear() {
        if (file.exists()) {
            file.delete()
            android.util.Log.d("DebugDataStorage", "Cleared debug data")
        }
    }

    /**
     * Parse JSON back into DebugData object
     */
    private fun parseDebugDataFromJson(json: String): DebugData? {
        return try {
            val root = org.json.JSONObject(json)

            // Extract metadata
            val metadata = root.optJSONObject("metadata")
            val processingTimeMs = metadata?.optLong("processingTimeMs") ?: 0L
            val parserUsed = metadata?.optString("parserUsed") ?: "Unknown"

            // Extract transactions
            val transactionsArray = root.optJSONArray("transactions") ?: org.json.JSONArray()
            val transactions = mutableListOf<com.yourname.expensetracker.domain.parser.ParsedTransaction>()
            for (i in 0 until transactionsArray.length()) {
                val txObj = transactionsArray.getJSONObject(i)
                transactions.add(com.yourname.expensetracker.domain.parser.ParsedTransaction(
                    amount = txObj.optDouble("amount", 0.0),
                    currency = txObj.optString("currency", "EUR"),
                    merchant = txObj.optString("merchant", ""),
                    type = com.yourname.expensetracker.data.database.entity.TransactionType.valueOf(
                        txObj.optString("type", "PURCHASE")
                    ),
                    confidence = txObj.optDouble("confidence", 0.0).toFloat(),
                    date = if (txObj.isNull("date")) null else txObj.optLong("date")
                ))
            }

            // Extract issues
            val issuesRoot = root.optJSONObject("issues")
            val issuesArray = issuesRoot?.optJSONArray("details") ?: org.json.JSONArray()
            val issues = mutableListOf<DebugIssue>()
            for (i in 0 until issuesArray.length()) {
                val issueObj = issuesArray.getJSONObject(i)
                issues.add(DebugIssue(
                    severity = IssueSeverity.valueOf(issueObj.optString("severity", "INFO")),
                    category = issueObj.optString("category", ""),
                    message = issueObj.optString("message", ""),
                    transactionIndex = if (issueObj.isNull("transactionIndex")) null else issueObj.optInt("transactionIndex"),
                    suggestion = if (issueObj.isNull("suggestion")) null else issueObj.optString("suggestion")
                ))
            }

            // Extract logs
            val logsArray = root.optJSONArray("parsingLogs") ?: org.json.JSONArray()
            val logs = mutableListOf<String>()
            for (i in 0 until logsArray.length()) {
                logs.add(logsArray.getString(i))
            }

            // Extract raw text preview (we don't store full raw text for space, but we could)
            // For now, use the preview or just empty string if not available
            val rawTextObj = root.optJSONObject("rawText")
            val rawText = rawTextObj?.optString("preview") ?: ""

            DebugData(
                rawText = rawText,
                parsedTransactions = transactions,
                parsingLogs = logs,
                processingTimeMs = processingTimeMs,
                parserUsed = parserUsed,
                issues = issues
            )
        } catch (e: Exception) {
            android.util.Log.e("DebugDataStorage", "Failed to parse JSON: ${e.message}")
            null
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\debug\DebugIssueDetector.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensdebugdebugissuedetectorkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.debug

import com.yourname.expensetracker.domain.parser.ParsedTransaction

/**
 * Severity levels for debug issues
 */
enum class IssueSeverity {
    CRITICAL,  // Missing required data, parsing failures
    WARNING,   // Low confidence, unusual patterns
    INFO       // Performance metrics, suggestions
}

/**
 * Represents a detected issue in the parsing/processing
 */
data class DebugIssue(
    val severity: IssueSeverity,
    val category: String,  // e.g., "MISSING_FIELD", "LOW_CONFIDENCE", "OCR_QUALITY"
    val message: String,
    val transactionIndex: Int? = null,  // null for global issues
    val suggestion: String? = null
)

/**
 * Detects and categorizes issues in debug data
 */
object DebugIssueDetector {

    fun detectIssues(
        rawText: String,
        transactions: List<ParsedTransaction>,
        processingTimeMs: Long
    ): List<DebugIssue> {
        val issues = mutableListOf<DebugIssue>()

        // Critical: No transactions parsed
        if (transactions.isEmpty()) {
            issues.add(DebugIssue(
                severity = IssueSeverity.CRITICAL,
                category = "PARSING_FAILURE",
                message = "No transactions were parsed from the document",
                suggestion = "Check if the document format is supported or try re-scanning with better quality"
            ))
        }

        // Check each transaction
        transactions.forEachIndexed { index, tx ->
            // Critical: Missing required fields
            if (tx.merchant.isBlank()) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.CRITICAL,
                    category = "MISSING_FIELD",
                    message = "Transaction #${index + 1}: Missing merchant name",
                    transactionIndex = index,
                    suggestion = "Verify OCR quality or manually enter merchant name"
                ))
            }

            if (tx.amount <= 0.0) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.CRITICAL,
                    category = "INVALID_AMOUNT",
                    message = "Transaction #${index + 1}: Invalid amount (${tx.amount})",
                    transactionIndex = index,
                    suggestion = "Check number format in source document"
                ))
            }

            // Warning: Low confidence
            if (tx.confidence < 0.70f) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "LOW_CONFIDENCE",
                    message = "Transaction #${index + 1}: Low confidence (${(tx.confidence * 100).toInt()}%)",
                    transactionIndex = index,
                    suggestion = "Manually verify merchant name and amount"
                ))
            }

            // Warning: Missing date
            if (tx.date == null) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "MISSING_DATE",
                    message = "Transaction #${index + 1}: Missing transaction date",
                    transactionIndex = index,
                    suggestion = "Date will default to current time"
                ))
            }

            // Warning: Unusual amount (too large or suspiciously round)
            if (tx.amount > 10000.0) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "UNUSUAL_AMOUNT",
                    message = "Transaction #${index + 1}: Unusually large amount (€${tx.amount})",
                    transactionIndex = index,
                    suggestion = "Verify this is not a decimal separator error"
                ))
            }
        }

        // OCR Quality checks
        val lineCount = rawText.lines().size
        val charCount = rawText.length

        if (lineCount < 5) {
            issues.add(DebugIssue(
                severity = IssueSeverity.WARNING,
                category = "OCR_QUALITY",
                message = "Very short OCR output ($lineCount lines)",
                suggestion = "Document may not have been fully scanned"
            ))
        }

        if (charCount < 100) {
            issues.add(DebugIssue(
                severity = IssueSeverity.WARNING,
                category = "OCR_QUALITY",
                message = "Very little text extracted ($charCount characters)",
                suggestion = "Try re-scanning with better lighting or higher resolution"
            ))
        }

        // Check for special characters indicating OCR errors
        val specialCharCount = rawText.count { it == '�' || it == '?' }
        if (specialCharCount > charCount * 0.05) {  // More than 5% special chars
            issues.add(DebugIssue(
                severity = IssueSeverity.WARNING,
                category = "OCR_QUALITY",
                message = "High number of unrecognized characters detected",
                suggestion = "OCR quality may be poor, consider re-scanning"
            ))
        }

        // Info: Processing performance
        if (processingTimeMs > 5000) {
            issues.add(DebugIssue(
                severity = IssueSeverity.INFO,
                category = "PERFORMANCE",
                message = "Processing took ${processingTimeMs / 1000.0}s",
                suggestion = "Consider using PDF format for faster processing"
            ))
        } else {
            issues.add(DebugIssue(
                severity = IssueSeverity.INFO,
                category = "PERFORMANCE",
                message = "Processing completed in ${processingTimeMs}ms"
            ))
        }

        // Info: Success summary
        val successCount = transactions.count { it.confidence >= 0.70f }
        if (successCount > 0) {
            issues.add(DebugIssue(
                severity = IssueSeverity.INFO,
                category = "SUMMARY",
                message = "Successfully parsed $successCount/${transactions.size} transactions with good confidence"
            ))
        }

        return issues
    }

    fun getIssueCounts(issues: List<DebugIssue>): Map<IssueSeverity, Int> {
        return issues.groupingBy { it.severity }.eachCount()
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\debug\DebugScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensdebugdebugscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.debug

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onDismiss: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val notifications by viewModel.filteredNotifications.collectAsState()
    val count by viewModel.notificationCount.collectAsState()
    val packages by viewModel.packages.collectAsState()
    val selectedFilter by viewModel.selectedPackageFilter.collectAsState()

    var expandedNotificationId by remember { mutableStateOf<Long?>(null) }
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug: Notifications ($count)") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearAll() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear all")
                    }
                }
            )
        }
    ) { padding ->
        // Root list for the entire screen to ensure scrolling
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // 1. Permission Button
            item {
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Open Notification Access Settings")
                }
            }

            // 2. Mass Simulation Section
            item {
                val isSimulating by viewModel.isSimulating.collectAsState()
                var simulationCount by remember { mutableStateOf(50f) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "🧪 Mass Simulation",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Quantity: ${simulationCount.toInt()}")
                        Slider(
                            value = simulationCount,
                            onValueChange = { simulationCount = it },
                            valueRange = 10f..500f,
                            steps = 9
                        )

                        Button(
                            onClick = { viewModel.simulateMassData(simulationCount.toInt()) },
                            enabled = !isSimulating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSimulating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Generate ${simulationCount.toInt()} Transactions")
                            }
                        }
                    }
                }
            }

            // 3. Test & Sync Buttons
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Button(
                        onClick = { viewModel.simulateTestNotification() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("Simulate Single Purchase (€12.50)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.triggerManualSync(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("Sync Active Notifications")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.resetExpenses() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Reset All Expenses")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.resetBudgets() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    ) {
                        Text("Reset All Budgets")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.resetSourceStats() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        )
                    ) {
                        Text("Reset Trust Scores")
                    }
                }
            }

            // 4. ML Stats
            item {
                val classifierStats by viewModel.classifierStats.collectAsState()
                val sourceStatsList by viewModel.sourceStats.collectAsState()

                Spacer(modifier = Modifier.height(16.dp))
                MlStatsSection(
                    classifierStats = classifierStats,
                    sourceStats = sourceStatsList,
                    onRetrain = { viewModel.retrainClassifier() }
                )
            }

            // 5. Filters
            item {
                if (packages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedFilter == null,
                                onClick = { viewModel.setPackageFilter(null) },
                                label = { Text("All") }
                            )
                        }
                        items(packages) { pkg ->
                            FilterChip(
                                selected = selectedFilter == pkg,
                                onClick = { viewModel.setPackageFilter(pkg) },
                                label = { 
                                    Text(
                                        pkg.split(".").lastOrNull() ?: pkg,
                                        maxLines = 1
                                    ) 
                                }
                            )
                        }
                    }
                }
            }

            // 6. Blocked Apps
            item {
                val blockedApps by viewModel.blockedPackages.collectAsState()
                if (blockedApps.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Blocked Apps:",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(blockedApps) { blocked ->
                            InputChip(
                                selected = true,
                                onClick = { viewModel.unblockPackage(blocked.packageName) },
                                label = { 
                                    Text(
                                        blocked.packageName.split(".").lastOrNull() ?: blocked.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    ) 
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Unblock",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            // 7. Notification List
            if (notifications.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No notifications captured yet")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Make sure notification access is enabled",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Captured Notifications (${notifications.size})",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(notifications.take(100), key = { it.id }) { notification ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        NotificationCard(
                            notification = notification,
                            dateFormat = dateFormat,
                            isExpanded = expandedNotificationId == notification.id,
                            onClick = {
                                expandedNotificationId = 
                                    if (expandedNotificationId == notification.id) null 
                                    else notification.id
                            },
                            onMarkRelevant = { viewModel.markAsRelevant(notification.id, true) },
                            onMarkIrrelevant = { viewModel.markAsRelevant(notification.id, false) },
                            onBlockPackage = { viewModel.blockPackage(notification.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationCard(
    notification: RawNotification,
    dateFormat: SimpleDateFormat,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onMarkRelevant: () -> Unit,
    onMarkIrrelevant: () -> Unit,
    onBlockPackage: () -> Unit
) {
    val relevanceColor = when (notification.isRelevant) {
        true -> Color(0xFF4CAF50).copy(alpha = 0.1f)
        false -> Color(0xFFF44336).copy(alpha = 0.1f)
        null -> Color.Transparent
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .background(relevanceColor)
                .padding(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = notification.appName ?: notification.packageName.split(".").last(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = dateFormat.format(Date(notification.capturedAt)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Title
            notification.title?.let {
                Text(
                    text = it,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Text
            val displayText = notification.bigText ?: notification.text
            displayText?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expanded content
            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Package name
                Text(
                    text = "Package: ${notification.packageName}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )

                // SubText if present
                notification.subText?.let {
                    Text(
                        text = "SubText: $it",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // Extras JSON
                notification.extrasJson?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Extras:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = it,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // Action buttons
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = onMarkRelevant,
                        label = { Text("Expense ✓", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                        }
                    )
                    AssistChip(
                        onClick = onMarkIrrelevant,
                        label = { Text("Ignore ✗", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    AssistChip(
                        onClick = onBlockPackage,
                        label = { Text("Block App", fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete, 
                                null, 
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    )
                }
            }
        }
    }
}
@Composable
fun MlStatsSection(
    classifierStats: com.yourname.expensetracker.domain.intelligence.ClassifierStats,
    sourceStats: List<com.yourname.expensetracker.data.database.entity.SourceStats>,
    onRetrain: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🧠 ML Classifier",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Status: ${if (classifierStats.isReady) "✅ Active" else "⏳ Training"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Positive samples: ${classifierStats.totalPositive}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Negative samples: ${classifierStats.totalNegative}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Vocabulary: ${classifierStats.vocabularySize} words",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = onRetrain,
                    enabled = classifierStats.totalPositive + classifierStats.totalNegative >= 20
                ) {
                    Text("Retrain", fontSize = 12.sp)
                }
            }

            // Source trust scores
            if (sourceStats.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "📊 Source Trust Scores",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))

                sourceStats.take(5).forEach { stats ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stats.packageName.split(".").lastOrNull() ?: stats.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${stats.acceptedAsExpense}/${stats.totalNotifications} (D:${stats.duplicates})",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val trustColor = when {
                            stats.trustScore > 0.7f -> Color(0xFF4CAF50)
                            stats.trustScore > 0.3f -> Color(0xFFFFC107)
                            else -> Color(0xFFFF5722)
                        }
                        Text(
                            text = "${(stats.trustScore * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = trustColor
                        )
                    }
                }
            }
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\debug\DebugViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensdebugdebugviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository,
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
    private val notificationSeeder: com.yourname.expensetracker.domain.debug.NotificationSeeder
) : ViewModel() {

    val notifications: StateFlow<List<RawNotification>> = repository
        .getRecentNotifications(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), emptyList())

    val notificationCount: StateFlow<Int> = repository
        .getCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), 0)

    val packages: StateFlow<List<String>> = repository
        .getAllPackages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), emptyList())

    val blockedPackages: StateFlow<List<com.yourname.expensetracker.data.database.entity.BlockedPackage>> = repository
        .getBlockedPackages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), emptyList())

    val totalSpent: StateFlow<Double> = repository
        .getTotalSpent()
        .map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), 0.0)

    val sourceStats: StateFlow<List<com.yourname.expensetracker.data.database.entity.SourceStats>> = repository
        .getSourceStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), emptyList())

    val classifierStats: StateFlow<com.yourname.expensetracker.domain.intelligence.ClassifierStats> = repository
        .getClassifierStatsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), com.yourname.expensetracker.domain.intelligence.ClassifierStats(0, 0, 0, false))

    private val _selectedPackageFilter = MutableStateFlow<String?>(null)
    val selectedPackageFilter: StateFlow<String?> = _selectedPackageFilter

    val filteredNotifications: StateFlow<List<RawNotification>> = combine(
        notifications,
        _selectedPackageFilter
    ) { notifs, filter ->
        if (filter == null) notifs
        else notifs.filter { it.packageName == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(30000), emptyList())

    fun setPackageFilter(packageName: String?) {
        _selectedPackageFilter.value = packageName
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.deleteAll()
        }
    }

    fun resetExpenses() {
        viewModelScope.launch {
            repository.deleteAllExpenses()
        }
    }

    fun resetBudgets() {
        viewModelScope.launch {
            budgetRepository.deleteAll()
        }
    }

    fun markAsRelevant(id: Long, isRelevant: Boolean) {
        viewModelScope.launch {
            repository.markAsRelevant(id, isRelevant)
        }
    }

    fun blockPackage(packageName: String) {
        viewModelScope.launch {
            repository.blockPackage(packageName)
        }
    }

    fun unblockPackage(packageName: String) {
        viewModelScope.launch {
            repository.unblockPackage(packageName)
        }
    }

    fun retrainClassifier() {
        viewModelScope.launch {
            repository.retrainClassifier()
        }
    }

    fun resetSourceStats() {
        viewModelScope.launch {
            repository.resetSourceStats()
        }
    }

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating

    fun simulateMassData(count: Int) {
        viewModelScope.launch {
            _isSimulating.value = true

            // 1. Ensure categories exist
            categoryRepository.ensureDefaultCategories()

            // 2. Pre-seed mappings so categorization works
            val cats = categoryRepository.allCategories.first()
            val catMap = cats.associate { it.name to it.id }

            notificationSeeder.categories.forEach { (catName, merchants) ->
                val catId = catMap[catName]
                if (catId != null) {
                    merchants.forEach { merchant ->
                        try {
                            categoryRepository.learnMerchantCategory(merchant, catId)
                        } catch (e: Exception) {
                            // Ignore duplicates
                        }
                    }
                }
            }

            // 3. Generate data
            val simulated = notificationSeeder.generate(count)
            repository.processAndSaveAll(simulated)
            _isSimulating.value = false
        }
    }

    fun simulateTestNotification() {
        viewModelScope.launch {
            val fakeNotification = RawNotification(
                packageName = "com.test.bank",
                appName = "Test Bank",
                title = "Purchase Alert",
                text = "You paid €12.50 at Amazon",
                timestamp = System.currentTimeMillis(),
                capturedAt = System.currentTimeMillis()
            )
            repository.processAndSave(fakeNotification)
        }
    }

    fun triggerManualSync(context: android.content.Context) {
        val intent = android.content.Intent(context, com.yourname.expensetracker.service.NotificationCaptureService::class.java).apply {
            action = com.yourname.expensetracker.service.NotificationCaptureService.ACTION_REFRESH_NOTIFICATIONS
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\debug\DebugViewerScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensdebugdebugviewerscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.debug

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.text.SimpleDateFormat
import java.util.*

/**
 * Debug viewer for OCR and parsing results.
 * Shows raw text, parsed data, and parsing logs in a 3-tab interface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugViewerScreen(
    debugData: DebugData,
    onClose: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Viewer") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val issueCounts = remember(debugData.issues) {
                debugData.issues.groupingBy { it.severity }.eachCount()
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Raw Text") },
                    icon = { Icon(Icons.Default.TextFields, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Parsed Data") },
                    icon = { Icon(Icons.Default.TableChart, null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Logs") },
                    icon = { Icon(Icons.Default.BugReport, null) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Issues")
                            val totalIssues = (issueCounts[IssueSeverity.CRITICAL] ?: 0) + 
                                            (issueCounts[IssueSeverity.WARNING] ?: 0)
                            if (totalIssues > 0) {
                                Spacer(Modifier.width(4.dp))
                                Badge { Text(totalIssues.toString()) }
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.Warning, null) }
                )
            }

            when (selectedTab) {
                0 -> RawTextTab(debugData)
                1 -> ParsedDataTab(debugData)
                2 -> LogsTab(debugData)
                3 -> IssuesTab(debugData.issues)
            }
        }
    }
}

@Composable
private fun RawTextTab(debugData: DebugData) {
    var searchText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text("Search in text...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            singleLine = true
        )

        // Stats
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Characters: ${debugData.rawText.length} | Lines: ${debugData.rawText.lines().size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row {
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(debugData.rawText))
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy All")
                }

                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(debugData.toJson()))
                    }
                ) {
                    Icon(Icons.Default.Code, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy as JSON")
                }
            }
        }

        HorizontalDivider()

        // Text content
        val filteredLines = remember(debugData.rawText, searchText) {
            if (searchText.isBlank()) {
                debugData.rawText.lines()
            } else {
                debugData.rawText.lines().filter { it.contains(searchText, ignoreCase = true) }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(filteredLines.withIndex().toList()) { (index, line) ->
                Text(
                    text = "${index + 1}: $line",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = if (searchText.isNotBlank() && line.contains(searchText, ignoreCase = true)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

@Composable
private fun ParsedDataTab(debugData: DebugData) {
    val clipboardManager = LocalClipboardManager.current

    if (debugData.parsedTransactions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Warning,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                Text("No transactions parsed", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Summary card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Parsing Summary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatChip("Total", debugData.parsedTransactions.size.toString())
                        StatChip(
                            "Purchases",
                            debugData.parsedTransactions.count { it.type.name == "PURCHASE" }.toString()
                        )
                        StatChip(
                            "Deposits",
                            debugData.parsedTransactions.count { it.type.name == "DEPOSIT" }.toString()
                        )
                        StatChip(
                            "Avg Confidence",
                            "${(debugData.parsedTransactions.map { it.confidence }.average() * 100).toInt()}%"
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(debugData.toJson()))
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.Code, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy as JSON")
                    }
                }
            }
        }

        // Transaction cards
        items(debugData.parsedTransactions) { tx ->
            TransactionDebugCard(tx)
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TransactionDebugCard(tx: ParsedTransaction) {
    val confidenceColor = when {
        tx.confidence >= 0.9f -> Color(0xFF4CAF50) // Green
        tx.confidence >= 0.7f -> Color(0xFFFFA726) // Orange
        else -> Color(0xFFEF5350) // Red
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (tx.confidence < 0.7f) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tx.merchant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${tx.currency} ${tx.amount}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (tx.type.name == "PURCHASE") Color.Red else Color.Green
                )
            }

            Spacer(Modifier.height(8.dp))

            // Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    DetailRow("Type", tx.type.name)
                    tx.date?.let {
                        DetailRow("Date", formatDate(it))
                    }
                }

                // Confidence badge
                Surface(
                    color = confidenceColor,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "${(tx.confidence * 100).toInt()}%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun LogsTab(debugData: DebugData) {
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with Copy as JSON button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(debugData.toJson()))
                }
            ) {
                Icon(Icons.Default.Code, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy as JSON")
            }
        }

        if (debugData.parsingLogs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = Color(0xFF4CAF50)
                )
                Spacer(Modifier.height(8.dp))
                Text("No parsing errors", style = MaterialTheme.typography.titleMedium)
            }
        }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(debugData.parsingLogs) { log ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Warning,
                        null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        log,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun IssuesTab(issues: List<DebugIssue>) {
    if (issues.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = Color(0xFF4CAF50)
                )
                Spacer(Modifier.height(8.dp))
                Text("No issues detected", style = MaterialTheme.typography.titleMedium)
                Text("All transactions parsed successfully", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    val groupedIssues = remember(issues) {
        issues.groupBy { it.severity }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Critical issues
        groupedIssues[IssueSeverity.CRITICAL]?.let { criticalIssues ->
            item {
                Text(
                    "❌ Critical (${criticalIssues.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            items(criticalIssues) { issue ->
                IssueCard(issue, Color(0xFFEF5350))
            }
        }

        // Warnings
        groupedIssues[IssueSeverity.WARNING]?.let { warnings ->
            item {
                Text(
                    "⚠️ Warnings (${warnings.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFA726)
                )
            }
            items(warnings) { issue ->
                IssueCard(issue, Color(0xFFFFA726))
            }
        }

        // Info
        groupedIssues[IssueSeverity.INFO]?.let { infoIssues ->
            item {
                Text(
                    "💡 Info (${infoIssues.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(infoIssues) { issue ->
                IssueCard(issue, MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun IssueCard(issue: DebugIssue, accentColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = accentColor.copy(alpha = 0.1f)
        ),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        issue.message,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )

                    issue.transactionIndex?.let { index ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Transaction #${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    color = accentColor.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        issue.category,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
            }

            issue.suggestion?.let { suggestion ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Lightbulb,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = accentColor
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        suggestion,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * Data class to hold debug information for display.
 */
data class DebugData(
    val rawText: String,
    val parsedTransactions: List<ParsedTransaction>,
    val parsingLogs: List<String> = emptyList(),
    val processingTimeMs: Long = 0,
    val parserUsed: String = "Unknown",
    val issues: List<DebugIssue> = emptyList()
) {
    /**
     * Export debug data as structured JSON for AI analysis
     */
    fun toJson(): String {
        val issueCounts = issues.groupingBy { it.severity }.eachCount()

        return buildString {
            appendLine("{")
            appendLine("  \"metadata\": {")
            appendLine("    \"timestamp\": \"${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(java.util.Date())}\",")
            appendLine("    \"processingTimeMs\": $processingTimeMs,")
            appendLine("    \"parserUsed\": \"$parserUsed\"")
            appendLine("  },")
            appendLine("  \"rawText\": {")
            appendLine("    \"lineCount\": ${rawText.lines().size},")
            appendLine("    \"characterCount\": ${rawText.length},")
            appendLine("    \"preview\": \"${rawText.take(200).replace("\"", "\\\"").replace("\n", "\\n")}...\"")
            appendLine("  },")
            appendLine("  \"transactions\": [")
            parsedTransactions.forEachIndexed { index, tx ->
                appendLine("    {")
                appendLine("      \"index\": $index,")
                appendLine("      \"merchant\": \"${tx.merchant.replace("\"", "\\\"")}\",")
                appendLine("      \"amount\": ${tx.amount},")
                appendLine("      \"currency\": \"${tx.currency}\",")
                appendLine("      \"confidence\": ${tx.confidence},")
                appendLine("      \"type\": \"${tx.type.name}\",")
                appendLine("      \"date\": ${tx.date ?: "null"},")
                val txIssues = issues.filter { it.transactionIndex == index }
                appendLine("      \"issues\": [${txIssues.joinToString { "\"${it.category}\"" }}]")
                append("    }")
                if (index < parsedTransactions.size - 1) appendLine(",")
                else appendLine()
            }
            appendLine("  ],")
            appendLine("  \"issues\": {")
            appendLine("    \"critical\": ${issueCounts[IssueSeverity.CRITICAL] ?: 0},")
            appendLine("    \"warnings\": ${issueCounts[IssueSeverity.WARNING] ?: 0},")
            appendLine("    \"info\": ${issueCounts[IssueSeverity.INFO] ?: 0},")
            appendLine("    \"details\": [")
            issues.forEachIndexed { index, issue ->
                appendLine("      {")
                appendLine("        \"severity\": \"${issue.severity.name}\",")
                appendLine("        \"category\": \"${issue.category}\",")
                appendLine("        \"message\": \"${issue.message.replace("\"", "\\\"")}\",")
                appendLine("        \"transactionIndex\": ${issue.transactionIndex ?: "null"},")
                appendLine("        \"suggestion\": ${if (issue.suggestion != null) "\"${issue.suggestion.replace("\"", "\\\"")}\"" else "null"}")
                append("      }")
                if (index < issues.size - 1) appendLine(",")
                else appendLine()
            }
            appendLine("    ]")
            appendLine("  },")
            appendLine("  \"parsingLogs\": [")
            parsingLogs.forEachIndexed { index, log ->
                append("    \"${log.replace("\"", "\\\"")}\"")
                if (index < parsingLogs.size - 1) appendLine(",")
                else appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }
    }
}


```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\home\HomeScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreenshomehomescreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.ui.components.*
import com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.domain.util.TimePeriodUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToReview: () -> Unit,
    onNavigateToRecurring: () -> Unit,
    onNavigateToTransactions: (TransactionFilter) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.dashboard.collectAsState()

    var showQuickSettings by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var showAddPlannedExpenseDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(isActive = state.isServiceRunning)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "DASHBOARD", 
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleEditMode() }) {
                        Icon(
                            if (state.isEditMode) Icons.Rounded.Check else Icons.Rounded.EditAttributes, 
                            contentDescription = "Edit Layout",
                            tint = if (state.isEditMode) SemanticColors.SuccessGreen else SemanticColors.TextSecondary
                        )
                    }
                    IconButton(onClick = { showQuickSettings = true }) {
                        Icon(
                            Icons.Rounded.Settings, 
                            contentDescription = "Settings",
                            tint = SemanticColors.TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SemanticColors.PrimaryIndigo)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(
                    items = state.widgets,
                    key = { HomeViewModel.getWidgetId(it) },
                    span = { widget ->
                        GridItemSpan(if (isFullSpan(widget)) 2 else 1)
                    },
                    contentType = { it.javaClass.simpleName }
                ) { widget ->
                    WidgetWrapper(
                        widget = widget,
                        isEditMode = state.isEditMode,
                        onMoveUp = { viewModel.moveWidget(HomeViewModel.getWidgetId(widget), true) },
                        onMoveDown = { viewModel.moveWidget(HomeViewModel.getWidgetId(widget), false) },
                        onToggleVisibility = { viewModel.toggleWidgetVisibility(HomeViewModel.getWidgetId(widget)) }
                    ) {
                        when (widget) {
                            is DashboardWidget.SafeToSpend -> {
                                HeroBentoCard {
                                    Text(
                                        text = "SAFE TO SPEND",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.PrimaryLight,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    AmountText(
                                        amount = widget.amount,
                                        style = MaterialTheme.typography.displayMedium,
                                        color = SemanticColors.TextPrimary
                                    )
                                    if (widget.totalBudget != null) {
                                        LinearProgressIndicator(
                                            progress = { ((widget.totalBudget - widget.amount) / widget.totalBudget).toFloat().coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp)
                                                .height(8.dp)
                                                .clip(CircleShape),
                                            color = SemanticColors.PrimaryIndigo,
                                            trackColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)
                                        )
                                        Text(
                                            "${widget.daysRemaining} DAYS REMAINING",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SemanticColors.TextSecondary,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }
                            is DashboardWidget.BudgetBlockParty -> {
                                BudgetBlockPartyCard(
                                    days = widget.days,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            is DashboardWidget.SpendingPaceWidget -> {
                                BentoCard {
                                    Text(
                                        "PACE", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    SpendingPaceGauge(pace = widget.pace)
                                }
                            }
                            is DashboardWidget.PendingReviewAlert -> {
                                val badgeColor = if (widget.count > 0) SemanticColors.WarningOrange else SemanticColors.TextMuted
                                BentoCard(
                                    containerColor = if (widget.count > 0) 
                                        SemanticColors.WarningOrange.copy(alpha = 0.05f) 
                                        else SemanticColors.GlassSurface,
                                    border = BorderStroke(
                                        1.dp, 
                                        if (widget.count > 0) SemanticColors.WarningOrange.copy(alpha = 0.3f) 
                                        else SemanticColors.GlassBorder
                                    ),
                                    onClick = onNavigateToReview
                                ) {
                                    Text(
                                        "REVIEW", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            "${widget.count}", 
                                            style = MaterialTheme.typography.displaySmall, 
                                            fontWeight = FontWeight.ExtraBold, 
                                            color = badgeColor
                                        )
                                        Text(
                                            "PENDING", 
                                            style = MaterialTheme.typography.labelSmall, 
                                            color = badgeColor,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }
                            }
                            is DashboardWidget.SpendingTrend -> {
                                BentoCard(
                                    modifier = Modifier.clickable {
                                        onNavigateToTransactions(
                                            TransactionFilter(
                                                dateRange = TimePeriodUtils.getMonthRange(System.currentTimeMillis())
                                            )
                                        )
                                    }
                                ) {
                                    SpendingTrendChart(
                                        currentMonthData = widget.currentMonthData,
                                        previousMonthData = widget.previousMonthData
                                    )
                                }
                            }
                            is DashboardWidget.NaturalLanguageInsight -> {
                                BentoCard(
                                    containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                                    border = BorderStroke(1.dp, SemanticColors.PrimaryIndigo.copy(alpha = 0.2f))
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            modifier = Modifier.size(40.dp),
                                            shape = CircleShape,
                                            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(widget.icon, fontSize = 20.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = widget.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = SemanticColors.TextPrimary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            is DashboardWidget.PeriodSummary -> {
                                BentoCard {
                                    Text(
                                        "PERIOD SUMMARY", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        StatLabel("TODAY", "€${String.format("%.2f", widget.todaySpent)}", modifier = Modifier.weight(1f))
                                        StatLabel("WEEK", "€${String.format("%.2f", widget.weekSpent)}", modifier = Modifier.weight(1f))
                                        StatLabel("MONTH", "€${String.format("%.2f", widget.monthSpent)}", modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            is DashboardWidget.BudgetHealthWidget -> {
                                BentoCard {
                                    Text(
                                        "BUDGET HEALTH", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        widget.summary ?: "ALL BUDGETS ON TRACK", 
                                        style = MaterialTheme.typography.titleMedium, 
                                        fontWeight = FontWeight.Bold,
                                        color = if (widget.summary?.contains("exceeded", ignoreCase = true) == true) SemanticColors.DangerRed else SemanticColors.SuccessGreen
                                    )
                                }
                            }
                            is DashboardWidget.TopCategories -> {
                                BentoCard {
                                    Text(
                                        "TOP CATEGORIES", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    widget.categories.forEach { CategorySpendingRow(it) }
                                }
                            }
                            is DashboardWidget.RecentTransactions -> {
                                BentoCard {
                                    Text(
                                        "RECENT ACTIVITY", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        fontWeight = FontWeight.Bold,
                                        color = SemanticColors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    widget.expenses.forEach { RecentExpenseRow(it) }
                                }
                            }
                            is DashboardWidget.FinancialWeatherWidget -> {
                                FinancialWeatherCard(
                                    state = widget.weather.state,
                                    headline = widget.weather.headline,
                                    summary = widget.weather.summary,
                                    icon = widget.weather.icon,
                                    totalCommitted = widget.weather.totalCommitted,
                                    totalLikely = widget.weather.totalLikely,
                                    discretionaryBudget = widget.weather.discretionaryBudget,
                                    pastSpendingPoints = widget.weather.pastSpendingPoints,
                                    projectedSpendingPoints = widget.weather.projectedSpendingPoints,
                                    upcomingItems = widget.weather.upcomingItems,
                                    totalRecurringCount = widget.weather.totalRecurringCount,
                                    details = widget.weather.details,
                                    onManageClick = onNavigateToRecurring,
                                    onPlanClick = { showAddPlannedExpenseDialog = true }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showQuickSettings) {
            QuickSettingsDialog(
                onDismiss = { showQuickSettings = false },
                onNavigateToCategories = { 
                    showQuickSettings = false
                    showCategories = true 
                },
                onNavigateToDebug = {
                    showQuickSettings = false
                    showDebug = true
                }
            )
        }

        if (showCategories) {
            com.yourname.expensetracker.ui.screens.categories.CategoryScreen(
                onDismiss = { showCategories = false }
            )
        }

        if (showDebug) {
            com.yourname.expensetracker.ui.screens.debug.DebugScreen(
                onDismiss = { showDebug = false }
            )
        }

        if (showAddPlannedExpenseDialog) {
            AddPlannedExpenseDialog(
                onDismiss = { showAddPlannedExpenseDialog = false },
                onConfirm = { desc, amount, date, catId, priority ->
                    viewModel.addPlannedExpense(desc, amount, date, catId, priority)
                    showAddPlannedExpenseDialog = false
                }
            )
        }
    }
}

@Composable
fun WidgetWrapper(
    widget: DashboardWidget,
    isEditMode: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleVisibility: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        content()

        if (isEditMode) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(24.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onMoveUp) {
                        Icon(Icons.Rounded.ArrowUpward, "Move Up", tint = Color.White)
                    }
                    IconButton(onClick = onToggleVisibility) {
                        Icon(Icons.Rounded.VisibilityOff, "Hide", tint = Color.White)
                    }
                    IconButton(onClick = onMoveDown) {
                        Icon(Icons.Rounded.ArrowDownward, "Move Down", tint = Color.White)
                    }
                }
            }
        }
    }
}

// getWidgetId removed - using HomeViewModel.getWidgetId instead

private fun isFullSpan(widget: DashboardWidget): Boolean = when (widget) {
    is DashboardWidget.SpendingPaceWidget,
    is DashboardWidget.PendingReviewAlert -> false
    else -> true
}

@Composable
fun QuickSettingsDialog(
    onDismiss: () -> Unit,
    onNavigateToCategories: () -> Unit,
    onNavigateToDebug: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick Settings") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Categories") },
                    leadingContent = { Text("🏷️") },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onNavigateToCategories() }
                )
                ListItem(
                    headlineContent = { Text("Debug Menu") },
                    leadingContent = { Text("🛠️") },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onNavigateToDebug() }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun PeriodCard(label: String, amount: Double, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                "€${String.format("%.2f", amount)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun CategorySpendingRow(item: CategorySpending) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val categoryColor = remember(item.category.color) {
            try { Color(android.graphics.Color.parseColor(item.category.color)) } 
            catch (e: Exception) { Color.Gray }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(categoryColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(item.category.icon, fontSize = 18.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.category.name, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { item.percentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = categoryColor,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "€${String.format("%.2f", item.total)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${String.format("%.0f", item.percentage)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RecentExpenseRow(expense: Expense) {
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(expense.merchant, style = MaterialTheme.typography.bodyMedium)
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
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BudgetSummaryWidget(onTrack: Int, warning: Int, exceeded: Int, summary: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Budget Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                StatusBadge("On Track", onTrack, Color(0xFF4CAF50))
                StatusBadge("Warning", warning, Color(0xFFFFC107))
                StatusBadge("Exceeded", exceeded, Color(0xFFFF5722))
            }
            if (summary != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exceeded > 0) Color(0xFFFF5722) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun StatusBadge(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(count.toString(), fontWeight = FontWeight.Bold, color = color)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlannedExpenseDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Double, Long, Long?, PlannedExpensePriority) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(PlannedExpensePriority.LIKELY) }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "PLAN AN EXPENSE", 
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = SemanticColors.PrimaryIndigo
            ) 
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("What are you planning?") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SemanticColors.PrimaryIndigo,
                        unfocusedBorderColor = SemanticColors.GlassBorder
                    )
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SemanticColors.PrimaryIndigo,
                        unfocusedBorderColor = SemanticColors.GlassBorder
                    )
                )

                Column {
                    Text(
                        "PRIORITY", 
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PlannedExpensePriority.values().forEach { p ->
                            FilterChip(
                                selected = priority == p,
                                onClick = { priority = p },
                                label = { Text(p.name) }
                            )
                        }
                    }
                }

                // Date Selector
                DateSelector(
                    dateMs = date,
                    onDateSelected = { date = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (description.isNotBlank() && amt > 0) {
                        onConfirm(description, amt, date, null, priority)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SemanticColors.PrimaryIndigo)
            ) {
                Text("ADD TO FORECAST")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = SemanticColors.TextSecondary)
            }
        },
        containerColor = SemanticColors.BaseNavy,
        shape = RoundedCornerShape(28.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelector(
    dateMs: Long,
    onDateSelected: (Long) -> Unit
) {
    val dateFormat = remember { java.text.SimpleDateFormat("EEE, dd MMM yyyy", java.util.Locale.getDefault()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = dateMs
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDatePicker = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DateRange,
            contentDescription = "Date",
            tint = SemanticColors.PrimaryIndigo
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                "Date",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = SemanticColors.TextSecondary
            )
            Text(
                dateFormat.format(java.util.Date(dateMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextPrimary
            )
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedDate ->
                            // Preserve time of day (roughly, or just set to noon to avoid timezone issues/start of day)
                            // Here we just use the selected date (which is usually UTC midnight) + current time offset if needed?
                            // Material3 DatePicker returns UTC start of day. 
                            // Let's just use it as is, or add current time component if we cared about exact time.
                            // For forecast, date is most important.
                            onDateSelected(selectedDate)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK", color = SemanticColors.PrimaryIndigo)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = SemanticColors.TextSecondary)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\home\HomeViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreenshomehomeviewmodelkt"></a>
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
import com.yourname.expensetracker.data.repository.DashboardRepository
import com.yourname.expensetracker.data.database.model.DashboardWidgetConfig
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.FinancialWeather
import com.yourname.expensetracker.data.repository.WeatherState
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

// === State Widget sealed class for Bento Grid ===
sealed class DashboardWidget {
    data class SafeToSpend(
        val amount: Double,
        val totalBudget: Double?,
        val daysRemaining: Int
    ) : DashboardWidget()

    data class BudgetBlockParty(
        val days: List<com.yourname.expensetracker.ui.components.DayBudgetStatus>
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

    data class SpendingTrend(
        val currentMonthData: List<Float>,
        val previousMonthData: List<Float>
    ) : DashboardWidget()

    data class FinancialWeatherWidget(
        val weather: FinancialWeather
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
    val isEditMode: Boolean = false,
    val isLoading: Boolean = true
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val dashboardRepository: DashboardRepository,
    private val insightsEngine: InsightsEngine,
    private val financialWeatherRepository: FinancialWeatherRepository,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val analyticsRepository: com.yourname.expensetracker.data.repository.AnalyticsRepository,
    private val synthesisEngine: SynthesisEngine,
    private val savingsGoalDao: com.yourname.expensetracker.data.database.dao.SavingsGoalDao
) : ViewModel() {

    private val isEditMode = MutableStateFlow(false)

    init {
        // Recover from destructive migration items if needed
        viewModelScope.launch {
            categoryRepository.ensureDefaultCategories()
        }
    }

    // Split flows to avoid 5-arg limit
    private val baseDataFlow = combine(
        repository.getAllExpenses().catch { emit(emptyList()) },
        categoryRepository.allCategories.catch { emit(emptyList()) },
        budgetRepository.getBudgetStatuses().catch { emit(emptyList()) }
    ) { expenses, categories, budgetStatuses ->
        Triple(expenses, categories, budgetStatuses)
    }

    private val planningDataFlow = combine(
        repository.getPendingReviewCount().catch { emit(0) },
        financialWeatherRepository.getFinancialWeather().catch { 
             emit(FinancialWeather(
                state = WeatherState.UNKNOWN,
                headline = "Weather Unavailable",
                summary = "We couldn't calculate your financial outlook right now.",
                icon = "❓",
                riskLevel = 0,
                totalCommitted = 0.0,
                totalLikely = 0.0,
                predictedDiscretionary = 0.0,
                discretionaryBudget = 0.0
            ))
        },
        financialWeatherRepository.getAllRecurringPatterns().catch { emit(emptyList()) },
        financialWeatherRepository.getAllPlannedExpenses().catch { emit(emptyList()) }
    ) { pendingCount, weather, recurring, planned ->
        Quadruple(pendingCount, weather, recurring, planned)
    }

    private val dataFlow = combine(
        baseDataFlow,
        planningDataFlow,
        savingsGoalDao.getAllGoals().catch { emit(emptyList()) }
    ) { base, planning, goalEntities ->
        val goals = goalEntities.map { entity ->
            SavingsGoal(
                id = entity.id,
                name = entity.name,
                targetAmount = entity.targetAmount,
                currentAmount = entity.currentAmount,
                targetDate = entity.targetDate,
                protectionLevel = when(entity.protectionLevel) {
                    com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.STRICT -> GoalProtectionLevel.STRICT
                    com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.WARNING -> GoalProtectionLevel.WARNING
                    com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.TRACKING -> GoalProtectionLevel.TRACKING
                }
            )
        }

        EightData(
            expenses = base.first,
            categories = base.second,
            budgetStatuses = base.third,
            pendingCount = planning.first,
            weather = planning.second,
            recurringPatterns = planning.third,
            plannedExpenses = planning.fourth,
            goals = goals
        )
    }
    .debounce(300)

    // Optimized: Process heavy data via AnalyticsRepository
    private val processedDataFlow = combine(
        dataFlow,
        analyticsRepository.getSpendingSummary(
             com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(System.currentTimeMillis()),
             com.yourname.expensetracker.domain.util.TimePeriodUtils.getEndOfMonth(System.currentTimeMillis())
        ),
        analyticsRepository.getCategoryBreakdown(
             com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(System.currentTimeMillis()),
             com.yourname.expensetracker.domain.util.TimePeriodUtils.getEndOfMonth(System.currentTimeMillis())
        )
    ) { data, summary, categoryBreakdown ->
        val (expenses, categories, budgetStatuses, pendingCount, weather, recurringPatterns, plannedExpenses, goals) = data

        val now = System.currentTimeMillis()
        val todayStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
        val weekStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfWeek(now)

        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        val weekSpent = purchases.filter { it.date >= weekStart }.sumOf { it.amount }
        val todaySpent = purchases.filter { it.date >= todayStart }.sumOf { it.amount }

        val totalSpent = summary.totalSpent
        val monthSpent = totalSpent 
        val txCount = summary.transactionCount
        val previousMonthTotal = summary.previousTotalSpent ?: 0.0

        val calendar = java.util.Calendar.getInstance()
        val daysInMonth = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val daysRemaining = daysInMonth - dayOfMonth

        // Overall budget
        val overallBudget = budgetStatuses.find { it.budget.categoryId == null }
        val safeToSpend = weather.discretionaryBudget 

        val totalBudgetAmount = overallBudget?.budget?.amount ?: 0.0

        // === Budget Block Party Logic (Refactored to SynthesisEngine) ===
        val monthStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(now)
        val currentDayIdx = ((now - monthStart) / 86400000L).toInt().coerceAtLeast(0)

        // RE-CALCULATE FORECAST FOR BLOCK PARTY (Centralized Logic)
        val currentPace = insightsEngine.getSpendingPaceSuspend(expenses)

        // We need pastSumDaily for the forecast
        val purchasesThisMonth = expenses.filter { 
            it.transactionType == TransactionType.PURCHASE && it.date >= monthStart
        }
        val amountByDay = DoubleArray(currentDayIdx + 1)
        purchasesThisMonth.forEach { exp ->
            val dayIndex = ((exp.date - monthStart) / 86400000L).toInt()
            if (dayIndex in amountByDay.indices) amountByDay[dayIndex] += exp.amount
        }
        var runningTotal = 0.0
        val pastSumDaily = amountByDay.map { runningTotal += it; runningTotal }

        val forecast = synthesisEngine.synthesize(
            pastSumDaily = pastSumDaily,
            recurringPatterns = recurringPatterns,
            plannedExpenses = plannedExpenses,
            savingsGoals = goals,
            budgetStatuses = budgetStatuses,
            spendingPace = currentPace
        )

        // Call centralized Block Party logic
        val domainBlocks = synthesisEngine.calculateBlockPartyData(
            forecast = forecast,
            expenses = expenses,
            dailySpending = summary.dailyHistory,
            budgetLimit = totalBudgetAmount
        )

        // Map domain to UI models
        val blockPartyDays = domainBlocks.map { domain ->
            com.yourname.expensetracker.ui.components.DayBudgetStatus(
                dayOfMonth = domain.dayOfMonth,
                date = domain.date,
                actualSpent = domain.actualSpent,
                targetBudget = domain.targetBudget,
                isToday = domain.isToday,
                status = when(domain.status) {
                    BlockPartyStatus.UNDER_BUDGET -> com.yourname.expensetracker.ui.components.BlockStatus.UNDER_BUDGET
                    BlockPartyStatus.OVER_BUDGET -> com.yourname.expensetracker.ui.components.BlockStatus.OVER_BUDGET
                    BlockPartyStatus.FUTURE -> com.yourname.expensetracker.ui.components.BlockStatus.FUTURE
                    BlockPartyStatus.TODAY -> com.yourname.expensetracker.ui.components.BlockStatus.TODAY
                    BlockPartyStatus.BILL_DAY -> com.yourname.expensetracker.ui.components.BlockStatus.BILL_DAY
                },
                baseTarget = domain.baseTarget,
                recurringImpact = domain.recurringImpact,
                plannedImpact = domain.plannedImpact,
                recurringItems = domain.recurringItems,
                plannedItems = domain.plannedItems,
                topTransactions = domain.topTransactions
            )
        }

        // Category Totals
        val categoryTotals = categoryBreakdown.map { 
             CategorySpending(it.category, it.total, it.percentage) 
        }

        val baseline = overallBudget?.budget?.amount ?: if (previousMonthTotal > 0) previousMonthTotal else null

        // Handle Day 1 Noise (LOG-005 Fix)
        val dayOfMonthCoerced = dayOfMonth.coerceAtLeast(1)
        val projectedTotal = if (dayOfMonth == 1) {
            if (baseline != null) (baseline * 0.7) + (monthSpent * 0.3 * daysInMonth)
            else monthSpent * daysInMonth
        } else {
            monthSpent * daysInMonth.toDouble() / dayOfMonth
        }

        // Pace Percentage
        val pacePercentage = if (baseline != null && baseline > 0) {
            val expected = baseline * dayOfMonthCoerced / daysInMonth
            val calculated = (monthSpent / expected * 100).toFloat()
            if (calculated.isFinite()) calculated else 0f
        } else 0f

        val pace = SpendingPace(
            currentMonthSpent = monthSpent,
            daysElapsed = dayOfMonth,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = if (previousMonthTotal > 0) previousMonthTotal else null,
            averageMonthlyTotal = null,
            pacePercentage = pacePercentage,
            paceStatus = when {
                baseline == null || baseline <= 0 -> PaceStatus.NO_BASELINE
                pacePercentage < 90f -> PaceStatus.UNDER_PACE
                pacePercentage > 110f -> PaceStatus.OVER_PACE
                else -> PaceStatus.ON_PACE
            }
        )

        // Trend
        val trend = DashboardWidget.SpendingTrend(
            currentMonthData = summary.dailyHistory,
            previousMonthData = summary.previousDailyHistory
        )

        // Natural language insight
        val insightText = buildNaturalLanguageInsight(
            monthSpent, previousMonthTotal, todaySpent, summary.transactionCount
        )

        // Budget summary
        val exceeded = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        val budgetSummary = if (budgetStatuses.isNotEmpty()) {
            if (exceeded > 0) "$exceeded budgets exceeded!" else "All budgets on track"
        } else null

        // === Build widget list ===
        val widgets = mutableListOf<DashboardWidget>()

        widgets.add(DashboardWidget.FinancialWeatherWidget(weather))

        // Hero
        widgets.add(
            DashboardWidget.SafeToSpend(
                amount = if (overallBudget != null) safeToSpend else monthSpent,
                totalBudget = overallBudget?.budget?.amount,
                daysRemaining = daysRemaining
            )
        )

        // Block Party (New)
        if (blockPartyDays.isNotEmpty()) {
            widgets.add(DashboardWidget.BudgetBlockParty(blockPartyDays))
        }

        // Spending Pace
        if (pace.paceStatus != PaceStatus.NO_BASELINE) {
            widgets.add(DashboardWidget.SpendingPaceWidget(pace))
        }

        // Spending Trend
        widgets.add(trend)

        if (pendingCount > 0) widgets.add(DashboardWidget.PendingReviewAlert(pendingCount))
        if (insightText != null) widgets.add(DashboardWidget.NaturalLanguageInsight(insightText.first, insightText.second))
        widgets.add(DashboardWidget.PeriodSummary(todaySpent, weekSpent, monthSpent))
        if (budgetStatuses.isNotEmpty()) widgets.add(DashboardWidget.BudgetHealthWidget(budgetStatuses, budgetSummary))
        if (categoryTotals.isNotEmpty()) widgets.add(DashboardWidget.TopCategories(categoryTotals.take(5)))
        if (purchases.isNotEmpty()) widgets.add(DashboardWidget.RecentTransactions(purchases.take(5)))

        CompiledDashboardData(
            allWidgets = widgets,
            totalSpent = totalSpent,
            txCount = txCount
        )
    }
    .flowOn(Dispatchers.Default) 
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CompiledDashboardData(emptyList(), 0.0, 0))

    val dashboard: StateFlow<DashboardState> = combine(
        processedDataFlow,
        isEditMode,
        dashboardRepository.configFlow
    ) { compiledData, editMode, configList ->

        // === Apply Custom Layout ===
        val sortedWidgets = configList
            .filter { it.isVisible || editMode } // Show all in edit mode, otherwise filter
            .mapNotNull { conf ->
                compiledData.allWidgets.find { w -> getWidgetId(w) == conf.id }
            }

        DashboardState(
            widgets = sortedWidgets,
            totalSpent = compiledData.totalSpent,
            transactionCount = compiledData.txCount,
            isEditMode = editMode,
            isLoading = false
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    fun toggleEditMode() {
        isEditMode.value = !isEditMode.value
    }

    fun moveWidget(widgetId: String, moveUp: Boolean) {
        val currentConfig = dashboardRepository.getDashboardConfig().toMutableList()
        val index = currentConfig.indexOfFirst { it.id == widgetId }
        if (index == -1) return

        val newIndex = if (moveUp) index - 1 else index + 1
        if (newIndex !in currentConfig.indices) return

        val temp = currentConfig[index]
        currentConfig[index] = currentConfig[newIndex].copy(order = index)
        currentConfig[newIndex] = temp.copy(order = newIndex)

        dashboardRepository.saveDashboardConfig(currentConfig.sortedBy { it.order })
        // Trigger recomposition by refreshing dashboard flow (implicitly via combining with a triggered state if needed)
        // Here we can just nudge the isEditMode or use a dedicated Refresh trigger
        // isEditMode.value = isEditMode.value 
    }

    fun toggleWidgetVisibility(widgetId: String) {
        val currentConfig = dashboardRepository.getDashboardConfig().map {
            if (it.id == widgetId) it.copy(isVisible = !it.isVisible) else it
        }
        dashboardRepository.saveDashboardConfig(currentConfig)
        // isEditMode.value = isEditMode.value
    }

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

    fun addPlannedExpense(
        description: String,
        amount: Double,
        date: Long,
        categoryId: Long?,
        priority: PlannedExpensePriority
    ) {
        viewModelScope.launch {
            plannedExpenseRepository.addPlannedExpense(
                com.yourname.expensetracker.data.database.entity.PlannedExpense(
                    description = description,
                    amount = amount,
                    date = date,
                    categoryId = categoryId,
                    priority = priority
                )
            )
        }
    }

    companion object {
        fun getWidgetId(widget: DashboardWidget): String = when (widget) {
            is DashboardWidget.SafeToSpend -> "safe_to_spend"
            is DashboardWidget.SpendingPaceWidget -> "spending_pace"
            is DashboardWidget.PendingReviewAlert -> "review_alert"
            is DashboardWidget.SpendingTrend -> "spending_trend"
            is DashboardWidget.NaturalLanguageInsight -> "insight"
            is DashboardWidget.PeriodSummary -> "period_summary"
            is DashboardWidget.BudgetHealthWidget -> "budget_health"
            is DashboardWidget.TopCategories -> "top_categories"
            is DashboardWidget.RecentTransactions -> "recent_transactions"
            is DashboardWidget.FinancialWeatherWidget -> "financial_weather"
            is DashboardWidget.BudgetBlockParty -> "budget_block_party"
        }
    }
}

data class FiveData(
    val expenses: List<Expense>,
    val categories: List<Category>,
    val budgetStatuses: List<BudgetStatus>,
    val pendingCount: Int,
    val weather: FinancialWeather
)

data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

data class EightData(
    val expenses: List<Expense>,
    val categories: List<Category>,
    val budgetStatuses: List<BudgetStatus>,
    val pendingCount: Int,
    val weather: FinancialWeather,
    val recurringPatterns: List<com.yourname.expensetracker.domain.model.RecurringPattern>,
    val plannedExpenses: List<PlannedExpense>,
    val goals: List<SavingsGoal>
)

data class CompiledDashboardData(
    val allWidgets: List<DashboardWidget>,
    val totalSpent: Double,
    val txCount: Int
)

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.receiptscan

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.ui.screens.addexpense.CategoryGrid
import com.yourname.expensetracker.ui.screens.addexpense.DateSelector
import com.yourname.expensetracker.ui.screens.addexpense.PaymentMethodChip
import kotlinx.coroutines.delay
import java.util.Currency

private fun getCurrencySymbol(currencyCode: String?): String {
    return try { Currency.getInstance(currencyCode ?: "EUR").symbol } catch(e: Exception) { "€" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScanScreen(
    onDismiss: () -> Unit,
    viewModel: ReceiptScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showDebugViewer by remember { mutableStateOf(false) }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) viewModel.processPhoto()
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.processGalleryImage(it) }
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = viewModel.createTempPhotoUri()
            cameraLauncher.launch(uri)
        }
    }

    // Handle done step - auto-dismiss
    LaunchedEffect(state.step) {
        if (state.step == ScanStep.DONE) {
            delay(1500)
            onDismiss()
        }
    }

    // Debug viewer dialog
    if (showDebugViewer && state.debugData != null) {
        com.yourname.expensetracker.ui.screens.debug.DebugViewerScreen(
            debugData = state.debugData!!,
            onClose = { showDebugViewer = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            ScanStep.CAPTURE -> "Scan Receipt"
                            ScanStep.PROCESSING -> "Processing..."
                            ScanStep.REVIEW -> "Review & Save"
                            ScanStep.DONE -> "Saved!"
                            ScanStep.ERROR -> "Error"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, "Close")
                    }
                },
                actions = {
                    // Debug button (only show in review/error steps)
                    if ((state.step == ScanStep.REVIEW || state.step == ScanStep.ERROR) && state.debugData != null) {
                        IconButton(onClick = { showDebugViewer = true }) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = "Debug Info"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.step) {
                ScanStep.CAPTURE -> CaptureStep(
                    imageUri = state.imageUri,
                    onCameraClick = {
                        val hasCameraPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasCameraPermission) {
                            val uri = viewModel.createTempPhotoUri()
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onGalleryClick = {
                        galleryLauncher.launch(arrayOf("image/*", "application/pdf"))
                    }
                )

                ScanStep.PROCESSING -> ProcessingStep()

                ScanStep.REVIEW -> ReviewStep(
                    state = state,
                    categories = categories,
                    viewModel = viewModel
                )

                ScanStep.DONE -> DoneStep()

                ScanStep.ERROR -> ErrorStep(
                    errorMessage = state.errorMessage ?: "Unknown error",
                    onRetry = { viewModel.retry() }
                )
            }
        }
    }
}

@Composable
private fun CaptureStep(
    imageUri: Uri?,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    Spacer(modifier = Modifier.height(32.dp))

    // Image preview area
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = "Receipt preview",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🧾", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Take a photo or select from gallery",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Action buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onCameraClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("📷 Camera")
        }
        OutlinedButton(
            onClick = onGalleryClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("🖼️ Gallery")
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Tips
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "📌 Tips for best results:",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("• Place receipt on a flat, dark surface", style = MaterialTheme.typography.bodySmall)
            Text("• Ensure good lighting with no shadows", style = MaterialTheme.typography.bodySmall)
            Text("• Capture the entire receipt in frame", style = MaterialTheme.typography.bodySmall)
            Text("• Keep the camera steady", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProcessingStep() {
    Spacer(modifier = Modifier.height(80.dp))
    CircularProgressIndicator(
        modifier = Modifier.size(64.dp),
        strokeWidth = 4.dp
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        "Scanning receipt...",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Reading text and extracting details",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(
    state: ReceiptScanState,
    categories: List<Category>,
    viewModel: ReceiptScanViewModel
) {
    val parsed = state.parsedReceipt

    // Image preview (small)
    if (state.imageUri != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            AsyncImage(
                model = state.imageUri,
                contentDescription = "Receipt",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Confidence indicator
    ConfidenceIndicator(confidence = state.ocrConfidence)

    Spacer(modifier = Modifier.height(16.dp))

    // Merchant
    OutlinedTextField(
        value = state.editMerchant,
        onValueChange = { viewModel.updateMerchant(it) },
        label = { Text("Merchant") },
        placeholder = { Text("Store name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Amount
    OutlinedTextField(
        value = state.editAmount,
        onValueChange = { viewModel.updateAmount(it) },
        label = { Text("Total Amount") },
        leadingIcon = { 
            Text(getCurrencySymbol(parsed?.currency), fontSize = 18.sp, fontWeight = FontWeight.Bold) 
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Date
    DateSelector(
        dateMs = state.editDate,
        onDateSelected = { viewModel.updateDate(it) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Payment Method
    Text(
        "Payment Method",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PaymentMethodChip(
            label = "💳 Card",
            selected = state.paymentMethod == PaymentMethod.CARD,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CARD) },
            modifier = Modifier.weight(1f)
        )
        PaymentMethodChip(
            label = "💵 Cash",
            selected = state.paymentMethod == PaymentMethod.CASH,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CASH) },
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Category
    Text(
        "Category",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    CategoryGrid(
        categories = categories,
        selectedId = state.selectedCategoryId,
        onSelect = { viewModel.selectCategory(it) }
    )

    // Line items preview
    if (parsed?.lineItems?.isNotEmpty() == true) {
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Detected Items (${parsed.lineItems.size})",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(8.dp))

                parsed.lineItems.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            item.description,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "${getCurrencySymbol(parsed.currency)}${String.format("%.2f", item.totalPrice)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (index < parsed.lineItems.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }

                // Tax if detected
                parsed.tax?.let { tax ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Tax/VAT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${getCurrencySymbol(parsed.currency)}${String.format("%.2f", tax)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    // Notes
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = state.notes,
        onValueChange = { viewModel.updateNotes(it) },
        label = { Text("Notes (optional)") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 3
    )

    // Raw OCR toggle
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.toggleRawText() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Raw OCR Text",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            if (state.showRawText) Icons.Default.KeyboardArrowUp
            else Icons.Default.KeyboardArrowDown,
            contentDescription = "Toggle",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    AnimatedVisibility(visible = state.showRawText) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Text(
                text = state.rawOcrText.ifBlank { "No text detected" },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }

    // Error messages
    state.errorMessage?.let { error ->
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "⚠️ $error",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    when (state.saveResult) {
        is SaveReceiptResult.Duplicate -> {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "⚠️ A similar transaction already exists",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        is SaveReceiptResult.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "❌ ${(state.saveResult as SaveReceiptResult.Error).message}",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        else -> {}
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Save button
    Button(
        onClick = { viewModel.saveExpense() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = !state.isSaving,
        shape = RoundedCornerShape(12.dp)
    ) {
        if (state.isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Text("💾 Save Expense", fontSize = 16.sp)
        }
    }

    Spacer(modifier = Modifier.height(32.dp))
}

@Composable
private fun ConfidenceIndicator(confidence: Float) {
    val percentage = (confidence * 100).toInt()
    val color = when {
        confidence >= 0.7f -> Color(0xFF4CAF50)
        confidence >= 0.4f -> Color(0xFFFFC107)
        else -> Color(0xFFFF5722)
    }
    val label = when {
        confidence >= 0.7f -> "High confidence"
        confidence >= 0.4f -> "Medium confidence"
        else -> "Low confidence - please verify"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "$label ($percentage%)",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun DoneStep() {
    Spacer(modifier = Modifier.height(80.dp))
    Text("✅", fontSize = 72.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Expense saved!",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Your receipt has been processed and saved.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ErrorStep(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Spacer(modifier = Modifier.height(80.dp))
    Text("❌", fontSize = 64.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Something went wrong",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        errorMessage,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onRetry,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("🔄 Try Again")
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.model.OperationResult
import com.yourname.expensetracker.ui.screens.debug.DebugData
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ScanStep {
    CAPTURE,
    PROCESSING,
    REVIEW,
    DONE,
    ERROR
}

data class ReceiptScanState(
    val step: ScanStep = ScanStep.CAPTURE,
    val imageUri: Uri? = null,
    val tempCameraUri: Uri? = null,
    val parsedReceipt: ReceiptParser.ParsedReceipt? = null,
    val receiptId: Long? = null,
    val rawOcrText: String = "",
    val showRawText: Boolean = false,

    // Editable fields
    val editMerchant: String = "",
    val editAmount: String = "",
    val editDate: Long = System.currentTimeMillis(),
    val selectedCategoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val notes: String = "",

    // Meta
    val ocrConfidence: Float = 0f,
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
    val saveResult: SaveReceiptResult? = null,

    // Debug data
    val debugData: DebugData? = null
)

sealed class SaveReceiptResult {
    data object Success : SaveReceiptResult()
    data object Duplicate : SaveReceiptResult()
    data class Error(val message: String) : SaveReceiptResult()
}

@HiltViewModel
class ReceiptScanViewModel @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val categoryRepository: CategoryRepository,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(ReceiptScanState(
        tempCameraUri = savedStateHandle.get<Uri>("temp_uri")
    ))
    val state: StateFlow<ReceiptScanState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Create a URI for camera to write photo to
     */
    fun createTempPhotoUri(): Uri {
        val uri = receiptRepository.createTempPhotoUri()
        savedStateHandle["temp_uri"] = uri
        _state.update { it.copy(tempCameraUri = uri) }
        return uri
    }

    /**
     * Called after camera successfully captures a photo
     */
    fun processPhoto() {
        val uri = _state.value.tempCameraUri ?: return
        processImageUri(uri)
    }

    /**
     * Called when user selects image from gallery
     */
    fun processGalleryImage(uri: Uri) {
        processImageUri(uri)
    }

    private fun processImageUri(uri: Uri) {
        _state.update {
            it.copy(
                step = ScanStep.PROCESSING,
                imageUri = uri,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val parsingLogs = mutableListOf<String>()

            try {
                // Manual scans do NOT auto-create review items (User confirms in this UI)
                val (receipt, parsed) = receiptRepository.processReceipt(uri, autoCreateReview = false)

                val processingTime = System.currentTimeMillis() - startTime

                // Create debug data
                val debugData = DebugData(
                    rawText = receipt.rawOcrText,
                    parsedTransactions = listOfNotNull(
                        parsed.total?.let { total ->
                            ParsedTransaction(
                                amount = total,
                                currency = "EUR",
                                merchant = parsed.merchantName ?: "Unknown",
                                type = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
                                confidence = parsed.confidence,
                                date = parsed.date
                            )
                        }
                    ),
                    parsingLogs = if (parsed.confidence < 0.7f) {
                        listOf("Low confidence parsing (${(parsed.confidence * 100).toInt()}%)")
                    } else emptyList(),
                    processingTimeMs = processingTime,
                    parserUsed = "ReceiptParser"
                )

                _state.update {
                    it.copy(
                        step = ScanStep.REVIEW,
                        imageUri = Uri.fromFile(java.io.File(receipt.imagePath)),
                        parsedReceipt = parsed,
                        receiptId = receipt.id,
                        rawOcrText = receipt.rawOcrText,
                        editMerchant = parsed.merchantName ?: "",
                        editAmount = parsed.total?.let { total ->
                            String.format("%.2f", total)
                        } ?: "",
                        editDate = parsed.date ?: System.currentTimeMillis(),
                        ocrConfidence = parsed.confidence,
                        selectedCategoryId = null, // Will be auto-detected on save
                        debugData = debugData
                    )
                }
            } catch (e: Exception) {
                parsingLogs.add("OCR Error: ${e.message}")

                try {
                    val (receipt, parsed) = receiptRepository.saveManualReceiptRecord(uri)

                    val debugData = DebugData(
                        rawText = "",
                        parsedTransactions = emptyList(),
                        parsingLogs = parsingLogs,
                        processingTimeMs = System.currentTimeMillis() - startTime,
                        parserUsed = "Manual (OCR Failed)"
                    )

                    _state.update {
                        it.copy(
                            step = ScanStep.REVIEW,
                            imageUri = uri,
                            parsedReceipt = parsed,
                            receiptId = receipt.id,
                            errorMessage = "OCR Failed: ${e.message}. You can enter details manually.",
                            debugData = debugData
                        )
                    }
                } catch (fallbackError: Exception) {
                    parsingLogs.add("Fallback Error: ${fallbackError.message}")

                    val debugData = DebugData(
                        rawText = "",
                        parsedTransactions = emptyList(),
                        parsingLogs = parsingLogs,
                        processingTimeMs = System.currentTimeMillis() - startTime,
                        parserUsed = "Failed"
                    )

                    _state.update {
                        it.copy(
                            step = ScanStep.ERROR,
                            errorMessage = "Total failure: ${fallbackError.message}",
                            debugData = debugData
                        )
                    }
                }
            }
        }
    }

    fun updateMerchant(value: String) {
        _state.update { it.copy(editMerchant = value) }
    }

    fun updateAmount(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }
        _state.update { it.copy(editAmount = filtered) }
    }

    fun updateDate(dateMs: Long) {
        _state.update { it.copy(editDate = dateMs) }
    }

    fun selectCategory(categoryId: Long) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        _state.update { it.copy(paymentMethod = method) }
    }

    fun updateNotes(value: String) {
        _state.update { it.copy(notes = value) }
    }

    fun toggleRawText() {
        _state.update { it.copy(showRawText = !it.showRawText) }
    }

    fun saveExpense() {
        val currentState = _state.value

        // Validate
        val merchant = currentState.editMerchant.trim()
        if (merchant.isBlank()) {
            _state.update {
                it.copy(errorMessage = "Merchant name is required")
            }
            return
        }

        val amount = currentState.editAmount.replace(",", ".").toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _state.update {
                it.copy(errorMessage = "Enter a valid amount")
            }
            return
        }

        val receiptId = currentState.receiptId ?: return

        _state.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val result = receiptRepository.createExpenseFromReceipt(
                    receiptId = receiptId,
                    merchant = merchant,
                    amount = amount,
                    currency = "EUR",
                    categoryId = currentState.selectedCategoryId,
                    date = currentState.editDate,
                    paymentMethod = currentState.paymentMethod,
                    notes = currentState.notes.takeIf { it.isNotBlank() }
                )

                when (result) {
                    is OperationResult.Success -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                step = ScanStep.DONE,
                                saveResult = SaveReceiptResult.Success
                            )
                        }
                    }
                    is OperationResult.Duplicate -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveResult = SaveReceiptResult.Duplicate
                            )
                        }
                    }
                    is OperationResult.Error -> {
                        _state.update {
                            it.copy(
                                isSaving = false,
                                saveResult = SaveReceiptResult.Error(result.message)
                            )
                        }
                    }
                }

            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveResult = SaveReceiptResult.Error(
                            e.message ?: "Unknown error"
                        )
                    )
                }
            }
        }
    }

    fun retry() {
        _state.update {
            ReceiptScanState()  // Reset to initial state
        }
    }

    fun reset() {
        _state.update { ReceiptScanState() }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\recurring\RecurringExpensesScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensrecurringrecurringexpensesscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.recurring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurringPattern
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter

@HiltViewModel
class RecurringExpensesViewModel @Inject constructor(
    private val repository: FinancialWeatherRepository,
    private val recurringExpenseDao: RecurringExpenseDao,
    private val plannedExpenseDao: com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
) : ViewModel() {

    // Helper flow to trigger updates
    private val refreshTrigger = MutableStateFlow(0)

    val patterns: StateFlow<List<RecurringPattern>> = combine(
        repository.getFinancialWeather(), // This already emits on db changes if set up correctly, but let's see
        refreshTrigger
    ) { weather, _ ->
        // We actually need the full list, not just upcomingBills from weather.
        // But repository exposes upcomingBills via weather. 
        // Ideally we expose all patterns separately.
        // For now, let's assume we add a method to Repo or use Engine directly if needed.
        // To be simpler, let's expose patterns from Repo.
        emptyList<RecurringPattern>() // Placeholder until Repo updated
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Better approach: Expose patterns flow from Repository
    val allPatterns = repository.getAllRecurringPatterns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val plannedExpenses = repository.getAllPlannedExpenses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteManualRule(pattern: RecurringPattern) {
        viewModelScope.launch {
            if (pattern.id != null) {
                recurringExpenseDao.deleteById(pattern.id)
                refreshTrigger.value += 1
            } else {
                // Legacy fallback: Delete by merchant name if ID is missing (e.g. old local data)
                val rules = recurringExpenseDao.getAll()
                val rule = rules.find { it.merchant == pattern.merchantName }
                if (rule != null) {
                    recurringExpenseDao.delete(rule)
                    refreshTrigger.value += 1
                }
            }
        }
    }

    fun confirmPattern(pattern: RecurringPattern) {
        viewModelScope.launch {
            val manual = ManualRecurringExpense(
                merchant = pattern.merchantName,
                amount = pattern.averageAmount,
                currency = pattern.currency,
                frequency = pattern.frequency,
                nextDate = pattern.nextExpectedDate,
                note = "Detected and confirmed by user"
            )
            recurringExpenseDao.insert(manual)
            refreshTrigger.value += 1
        }
    }

    fun deletePlannedExpense(planned: com.yourname.expensetracker.domain.model.PlannedExpense) {
        viewModelScope.launch {
            plannedExpenseDao.deletePlannedExpenseById(planned.id)
            refreshTrigger.value += 1
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringExpensesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTransactions: (TransactionFilter) -> Unit = {}, // Default for preview/safety
    viewModel: RecurringExpensesViewModel = hiltViewModel()
) {
    val patterns by viewModel.allPatterns.collectAsState()
    val planned by viewModel.plannedExpenses.collectAsState()

    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Recurring", "Planned")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Upcoming") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            if (selectedTabIndex == 0) {
                // Recurring Tab
                if (patterns.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No recurring expenses found.")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(patterns) { pattern ->
                            RecurringExpenseItem(
                                pattern = pattern,
                                onDelete = { viewModel.deleteManualRule(pattern) },
                                onConfirm = { viewModel.confirmPattern(pattern) },
                                onMerchantClick = { 
                                    onNavigateToTransactions(
                                        TransactionFilter(merchantName = pattern.merchantName)
                                    ) 
                                }
                            )
                        }

                    }
                }
            } else {
                // Planned Tab
                if (planned.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No planned expenses found.")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(planned) { item ->
                            PlannedExpenseItem(
                                expense = item,
                                onDelete = { viewModel.deletePlannedExpense(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlannedExpenseItem(
    expense: com.yourname.expensetracker.domain.model.PlannedExpense,
    onDelete: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.description,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "€${String.format("%.2f", expense.amount)} • ${expense.priority.name.lowercase().capitalize()}",
                    style = MaterialTheme.typography.bodyMedium
                )
                val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault()) }
                Text(
                    text = "Date: ${dateFormat.format(Instant.ofEpochMilli(expense.date).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete, 
                    contentDescription = "Delete Planned",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun RecurringExpenseItem(
    pattern: RecurringPattern,
    onDelete: () -> Unit,
    onConfirm: () -> Unit,
    onMerchantClick: () -> Unit = {}
) {

    val isManual = pattern.id != null || pattern.confidence >= 0.99f

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pattern.merchantName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onMerchantClick() }
                )
                Text(
                    text = "${String.format("%.2f", pattern.averageAmount)} ${pattern.currency} • ${pattern.frequency.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodyMedium
                )
                val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault()) }
                Text(
                    text = "Next: ${dateFormat.format(Instant.ofEpochMilli(pattern.nextExpectedDate).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isManual) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete, 
                        contentDescription = "Delete Rule",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                SuggestionChip(
                    onClick = onConfirm,
                    label = { Text("Confirm Pattern") }
                )
            }

        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\review\ReviewScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensreviewreviewscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.review

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.ui.components.AmountText
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.ui.util.HapticType
import com.yourname.expensetracker.ui.util.rememberHapticFeedback
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.*
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import com.yourname.expensetracker.ui.screens.debug.DebugViewerScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val pendingReviews by viewModel.pendingReviews.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var editingReview by remember { mutableStateOf<PendingReview?>(null) }
    // Guard against double-swipes/rapid-fire actions
    val processingIds = remember { mutableStateListOf<Long>() }
    val haptic = rememberHapticFeedback()

    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isBatchProcessing by viewModel.isBatchProcessing.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()

    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var showDebugMenu by remember { mutableStateOf(false) }
    var debugInfoDialogText by remember { mutableStateOf<String?>(null) }
    var showDebugViewer by remember { mutableStateOf(false) }
    val debugData by viewModel.debugData.collectAsState()

    val batchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.processBatch(uris)
        }
    }

    val statementLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.processStatement(it) }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        "REVIEW QUEUE ($pendingCount)", 
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = SemanticColors.TextPrimary
                    ) 
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                ),
                actions = {
                    // Debug viewer button (show when debug data is available)
                    if (debugData != null) {
                        IconButton(onClick = { showDebugViewer = true }) {
                            Icon(Icons.Rounded.BugReport, "View Debug Data")
                        }
                    }

                    Box {
                        IconButton(onClick = { showDebugMenu = !showDebugMenu }) {
                            Icon(Icons.Rounded.MoreVert, "Debug Options")
                        }
                        DropdownMenu(
                            expanded = showDebugMenu,
                            onDismissRequest = { showDebugMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Mass Insert (Batch)") },
                                onClick = {
                                    showDebugMenu = false
                                    batchLauncher.launch(arrayOf("image/*", "application/pdf"))
                                },
                                leadingIcon = { Icon(Icons.Rounded.Layers, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Import Bank Statement") },
                                onClick = {
                                    showDebugMenu = false
                                    statementLauncher.launch(arrayOf("image/*", "application/pdf"))
                                },
                                leadingIcon = { Icon(Icons.Rounded.ReceiptLong, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Export Parser Data") },
                                onClick = {
                                    showDebugMenu = false
                                    coroutineScope.launch {
                                        val data = viewModel.getDebugExportData()
                                        clipboardManager.setText(AnnotatedString(data))
                                        snackbarHostState.showSnackbar("Parser info copied to clipboard")
                                    }
                                },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Clear Debug Data") },
                                onClick = {
                                    showDebugMenu = false
                                    viewModel.clearDebugData()
                                },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error
                                )
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Scanned Data") },
                                onClick = {
                                    showDebugMenu = false
                                    viewModel.clearScannedData()
                                },
                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error
                                )
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (pendingReviews.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✅", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "All caught up!",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "No transactions need your review",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        "Swipe right to approve, left to reject",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                items(pendingReviews, key = { it.review.id }) { item ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { dismissValue ->
                            if (processingIds.contains(item.review.id)) return@rememberSwipeToDismissBoxState false

                            when (dismissValue) {
                                SwipeToDismissBoxValue.StartToEnd -> {
                                    processingIds.add(item.review.id)
                                    haptic(HapticType.Success)
                                    viewModel.approveReview(item.review.id)
                                    true
                                }
                                SwipeToDismissBoxValue.EndToStart -> {
                                    processingIds.add(item.review.id)
                                    haptic(HapticType.Error)
                                    viewModel.rejectReview(item.review.id)
                                    true
                                }
                                else -> false
                            }
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            val color = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.StartToEnd -> SemanticColors.SuccessGreen
                                SwipeToDismissBoxValue.EndToStart -> SemanticColors.DangerRed
                                else -> Color.Transparent
                            }
                            val alignment = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                else -> Alignment.Center
                            }
                            val icon = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.StartToEnd -> Icons.Rounded.CheckCircle
                                SwipeToDismissBoxValue.EndToStart -> Icons.Rounded.Delete
                                else -> Icons.AutoMirrored.Rounded.ArrowForward
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(color.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 24.dp),
                                contentAlignment = alignment
                            ) {
                                Icon(icon, null, tint = Color.White)
                            }
                        },
                        content = {
                            ReviewCard(
                                item = item,
                                onApprove = { viewModel.approveReview(item.review.id) },
                                onReject = { viewModel.rejectReview(item.review.id) },
                                onEdit = { editingReview = item.review },
                                onDebug = {
                                    item.receipt?.let { receipt ->
                                        coroutineScope.launch {
                                            debugInfoDialogText = "Loading..."
                                            debugInfoDialogText = viewModel.getReceiptDebugInfo(receipt.id)
                                        }
                                    } ?: run {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("No automated receipt info available")
                                        }
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }

        editingReview?.let { review ->
            EditReviewDialog(
                review = review,
                categories = categories,
                onDismiss = { editingReview = null },
                onSave = { amount, merchant, categoryId ->
                    viewModel.approveReviewWithEdits(
                        reviewId = review.id,
                        finalAmount = amount,
                        finalMerchant = merchant,
                        finalCategoryId = categoryId
                    )
                    editingReview = null
                }
            )
        }

        debugInfoDialogText?.let { info ->
            AlertDialog(
                onDismissRequest = { debugInfoDialogText = null },
                title = { Text("Receipt Debug Info") },
                text = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(info))
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Copied to clipboard")
                            }
                        }
                    ) {
                        Text("Copy")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { debugInfoDialogText = null }) {
                        Text("Close")
                    }
                }
            )
        }

        // Batch processing overlay
        if (isBatchProcessing) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = SemanticColors.PrimaryLight)
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "PROCESSING BATCH...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    batchProgress?.let { (current, total) ->
                        Text(
                            "$current / $total",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { current.toFloat() / total },
                            modifier = Modifier.width(200.dp),
                            color = SemanticColors.PrimaryIndigo
                        )
                    }
                }
            }
        }

        // Debug Viewer Dialog
        if (showDebugViewer) {
            debugData?.let { data ->
                DebugViewerScreen(
                    debugData = data,
                    onClose = { showDebugViewer = false }
                )
            }
        }
    }
}

@Composable
fun ReviewCard(
    item: PendingReviewWithReceipt,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit,
    onDebug: () -> Unit
) {
    val review = item.review
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.getDefault()) }
    var showTrustSignal by remember { mutableStateOf(false) }
    val haptic = rememberHapticFeedback()

    val confidenceColor = when {
        review.confidence >= 0.85f -> SemanticColors.SuccessGreen
        review.confidence >= 0.65f -> SemanticColors.WarningOrange
        else -> SemanticColors.DangerRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = SemanticColors.GlassSurface),
        border = BorderStroke(1.dp, SemanticColors.GlassBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = review.packageName.split(".").lastOrNull()?.uppercase() ?: "SYSTEM",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Surface(
                    color = confidenceColor.copy(alpha = 0.15f),
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${(review.confidence * 100).toInt()}% CONFIDENCE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = confidenceColor,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                // Receipt Thumbnail if available
                if (item.receipt != null) {
                    Card(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        border = BorderStroke(1.dp, SemanticColors.GlassBorder)
                    ) {
                        AsyncImage(
                            model = File(item.receipt.imagePath),
                            contentDescription = "Receipt Thumbnail",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = review.suggestedMerchant,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = dateFormat.format(Instant.ofEpochMilli(review.createdAt).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    AmountText(
                        amount = review.suggestedAmount,
                        style = MaterialTheme.typography.headlineSmall,
                        color = SemanticColors.TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Trust Signal / Detailed Evidence
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        SemanticColors.BaseNavy.copy(alpha = 0.5f),
                        RoundedCornerShape(16.dp)
                    )
                    .border(1.dp, SemanticColors.GlassBorder.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .clickable { 
                        haptic(HapticType.Standard)
                        onDebug() // Tap the evidence area to show debug info instead of expanding
                        // showTrustSignal = !showTrustSignal 
                    }
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "RAW SOURCE EVIDENCE (TAP FOR DEBUG)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextSecondary,
                        letterSpacing = 1.sp
                    )
                    Icon(
                        Icons.Rounded.BugReport, // Changed icon
                        null,
                        tint = SemanticColors.TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                AnimatedVisibility(visible = showTrustSignal) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        Text(
                            text = review.notificationText ?: "No raw data captured.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticColors.TextPrimary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedIconButton(
                    onClick = {
                        haptic(HapticType.Heavy)
                        onEdit()
                    },
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Edit, "Edit", modifier = Modifier.size(20.dp))
                }

                Button(
                    onClick = {
                        haptic(HapticType.Error)
                        onReject()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Reject", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        haptic(HapticType.Success)
                        onApprove()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SemanticColors.SuccessGreen,
                        contentColor = Color.White
                    )
                ) {
                    Text("Approve", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EditReviewDialog(
    review: PendingReview,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (Double?, String?, Long?) -> Unit
) {
    var amount by remember { mutableStateOf(String.format("%.2f", review.suggestedAmount)) }
    var merchant by remember { mutableStateOf(review.suggestedMerchant) }
    var selectedCategoryId by remember { mutableStateOf(review.suggestedCategoryId) }
    val haptic = rememberHapticFeedback()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fix Extraction Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (€)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Text(
                    "Assign Category",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )

                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { category ->
                        Surface(
                            onClick = { 
                                haptic(HapticType.Standard)
                                selectedCategoryId = category.id 
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedCategoryId == category.id)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, if (selectedCategoryId == category.id) MaterialTheme.colorScheme.primary else Color.Transparent)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(category.icon, fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(category.name, style = MaterialTheme.typography.bodyMedium)
                                if (selectedCategoryId == category.id) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    haptic(HapticType.Success)
                    val parsedAmount = amount.replace(",", ".").toDoubleOrNull()
                    val editedAmount = if (parsedAmount != null && kotlin.math.abs(parsedAmount - review.suggestedAmount) > 0.001) parsedAmount else null
                    val editedMerchant = merchant.takeIf { it != review.suggestedMerchant }
                    val editedCategory = selectedCategoryId.takeIf { it != review.suggestedCategoryId }
                    onSave(editedAmount, editedMerchant, editedCategory)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Confirm Fix")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                haptic(HapticType.Standard)
                onDismiss()
            }) {
                Text("Cancel")
            }
        }
    )
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\review\ReviewViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreensreviewreviewviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.model.OperationResult
// ...
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val receiptRepository: com.yourname.expensetracker.data.repository.ReceiptRepository,
    private val debugDataStorage: com.yourname.expensetracker.ui.screens.debug.DebugDataStorage
) : ViewModel() {

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null) // current, total
    val batchProgress = _batchProgress.asStateFlow()

    private val _isBatchProcessing = MutableStateFlow(false)
    val isBatchProcessing = _isBatchProcessing.asStateFlow()

    private val _debugData = MutableStateFlow<com.yourname.expensetracker.ui.screens.debug.DebugData?>(null)
    val debugData = _debugData.asStateFlow()

    init {
        // Load saved debug data on startup
        viewModelScope.launch {
            _debugData.value = debugDataStorage.load()
        }
    }

    private var batchJob: Job? = null

    val pendingReviews: StateFlow<List<PendingReviewWithReceipt>> = repository
        .getPendingReviews()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingCount: StateFlow<Int> = repository
        .getPendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun approveReview(reviewId: Long) {
        viewModelScope.launch {
            val result = repository.approveReview(reviewId)
            handleResult(result, "Failed to approve")
        }
    }

    private fun handleResult(result: OperationResult<Long>, prefix: String) {
        when (result) {
            is OperationResult.Success -> { /* Handled by UI observing DB change */ }
            is OperationResult.Duplicate -> _errorMessage.value = "Duplicate transaction detected"
            is OperationResult.Error -> _errorMessage.value = "$prefix: ${result.message}"
        }
    }

    fun rejectReview(reviewId: Long) {
        viewModelScope.launch {
            repository.rejectReview(reviewId)
        }
    }

    fun approveReviewWithEdits(
        reviewId: Long,
        finalAmount: Double?,
        finalMerchant: String?,
        finalCategoryId: Long?
    ) {
        viewModelScope.launch {
            val result = repository.approveReview(
                reviewId = reviewId,
                finalAmount = finalAmount,
                finalMerchant = finalMerchant,
                finalCategoryId = finalCategoryId
            )
            handleResult(result, "Failed to approve edits")
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun approveAll() {
        viewModelScope.launch {
            try {
                repository.approveAllReview()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to approve all: ${e.message}"
            }
        }
    }

    fun processBatch(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        batchJob?.cancel() // Cancel previous if any
        batchJob = viewModelScope.launch {
            try {
                _isBatchProcessing.value = true
                _batchProgress.value = Pair(0, uris.size)

                val result = receiptRepository.processBatch(uris) { current, total ->
                    _batchProgress.value = Pair(current, total)
                }

                if (result.failureCount > 0) {
                    val firstError = result.errors.firstOrNull()?.let { 
                        if (it.length > 60) it.take(57) + "..." else it 
                    }
                    _errorMessage.value = "Processed ${result.successCount} ok. ${result.failureCount} failed: $firstError"
                } else {
                    _errorMessage.value = "Successfully processed all ${result.successCount} receipts!"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Batch failed: ${e.message}"
            } finally {
                _isBatchProcessing.value = false
                _batchProgress.value = null
            }
        }
    }

    fun cancelBatchProcessing() {
        batchJob?.cancel()
        _isBatchProcessing.value = false
        _batchProgress.value = null
        _errorMessage.value = "Batch processing cancelled."
    }

    fun processStatement(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _isBatchProcessing.value = true // Reuse batch loading state
                _batchProgress.value = Pair(0, 1)

                val result = receiptRepository.processStatement(uri)

                // Store debug data and persist to file
                result.debugData?.let { data ->
                    _debugData.value = data
                    debugDataStorage.save(data)
                }

                if (result.failureCount > 0) {
                    _errorMessage.value = "Failed to parse screenshot: ${result.errors.firstOrNull()}"
                } else {
                    _errorMessage.value = "Imported ${result.successCount} transactions from screenshot!"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Import failed: ${e.message}"
            } finally {
                _isBatchProcessing.value = false
                _batchProgress.value = null
            }
        }
    }

    suspend fun getDebugExportData(): String {
        return receiptRepository.exportParserDebugData()
    }

    suspend fun getReceiptDebugInfo(receiptId: Long): String {
        return receiptRepository.debugReceipt(receiptId)
    }

    fun clearScannedData() {
        viewModelScope.launch {
            receiptRepository.clearAllScannedReceipts()
            _errorMessage.value = "All scanned debug data cleared."
        }
    }

    fun clearDebugData() {
        _debugData.value = null
        debugDataStorage.clear()
        _errorMessage.value = "Debug data cleared."
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionFilter.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreenstransactionstransactionfilterkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.transactions

data class TransactionFilter(
    val categoryId: Long? = null,
    val merchantName: String? = null,
    val dateRange: Pair<Long, Long>? = null,
    val correlationId: Long = System.currentTimeMillis()
)

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsScreen.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreenstransactionstransactionsscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.transactions

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.data.database.model.formattedAmount
import com.yourname.expensetracker.data.database.model.formattedDate
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.ui.screens.transactions.TransactionsViewModel.TransactionTab
import com.yourname.expensetracker.ui.theme.SemanticColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ============================================================
// MAIN SCREEN
// ============================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel = hiltViewModel(),
    initialFilter: TransactionFilter? = null,
    onNavigateToAnalytics: () -> Unit = {}
) {
    val transactions by viewModel.transactions.collectAsState()
    val groupedTransactions by viewModel.groupedTransactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val activeFilter by viewModel.filter.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMoreState.collectAsState()
    val tabCounts by viewModel.tabTransactionCounts.collectAsState()

    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Initial filter application
    LaunchedEffect(initialFilter) {
        if (initialFilter != null) {
            viewModel.applyFilter(initialFilter)
        }
    }

    // Dialog states
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }
    var expenseToCategorize by remember { mutableStateOf<Expense?>(null) }
    var expenseToRecurring by remember { mutableStateOf<Expense?>(null) }
    var expenseToRename by remember { mutableStateOf<Expense?>(null) }
    var showSearch by remember { mutableStateOf(false) }

    // Pull-to-refresh state
    val pullToRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }

    // Error handling
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect error messages
    LaunchedEffect(Unit) {
        viewModel.error.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    // Collect success messages
    LaunchedEffect(Unit) {
        viewModel.successMessage.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
                actionLabel = "OK"
            )
        }
    }

    // Detect when to load more for pagination
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            selectedTab == TransactionTab.ALL && 
            lastVisibleItem >= totalItems - 5 && 
            totalItems > 0 &&
            !isLoadingMore
        }
    }

    // Trigger load more when needed
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadMore()
        }
    }

    // Handle refresh
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            viewModel.refresh()
            isRefreshing = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                // Header with search toggle
                TopAppBar(
                    title = { 
                        AnimatedContent(
                            targetState = showSearch,
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            },
                            label = "SearchToggle"
                        ) { searching ->
                            if (searching) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { viewModel.search(it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("Search transactions...") },
                                    leadingIcon = { 
                                        Icon(Icons.Rounded.Search, contentDescription = null) 
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { 
                                            showSearch = false
                                            viewModel.search("")
                                        }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Close search")
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(28.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = SemanticColors.PrimaryIndigo,
                                        unfocusedBorderColor = SemanticColors.GlassBorder
                                    )
                                )
                            } else {
                                Text(stringResource(R.string.transactions_title))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToAnalytics) {
                            Icon(Icons.Rounded.BarChart, contentDescription = "Advanced Analytics")
                        }
                        if (!showSearch) {
                            IconButton(onClick = { showSearch = true }) {
                                Icon(Icons.Rounded.Search, contentDescription = "Search")
                            }
                        }
                    }
                )

                // Tab row with counts
                ScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                            color = SemanticColors.PrimaryIndigo
                        )
                    },
                    divider = {
                        HorizontalDivider(
                            color = SemanticColors.GlassBorder,
                            thickness = 1.dp
                        )
                    }
                ) {
                    TransactionTab.values().forEach { tab ->
                        val count = tabCounts[tab] ?: 0
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { 
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.selectTab(tab)
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    if (count > 0 && tab != TransactionTab.ALL) {
                                        Badge(
                                            containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f),
                                            contentColor = SemanticColors.PrimaryIndigo
                                        ) {
                                            Text(
                                                text = if (count > 99) "99+" else count.toString(),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                // Active Filter Banner
                AnimatedVisibility(visible = activeFilter != null) {
                    Surface(
                        color = SemanticColors.PrimaryIndigo.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val filterText = buildString {
                                append("Filtered by: ")
                                val parts = mutableListOf<String>()
                                activeFilter?.merchantName?.let { parts.add("Merchant: $it") }
                                activeFilter?.categoryId?.let { id -> 
                                    categories.find { it.id == id }?.name?.let { parts.add("Category: $it") }
                                }
                                append(parts.joinToString(", "))
                            }

                            Text(
                                text = filterText,
                                style = MaterialTheme.typography.labelMedium,
                                color = SemanticColors.PrimaryIndigo,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = { viewModel.clearFilter() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Clear filter",
                                    tint = SemanticColors.PrimaryIndigo,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            state = pullToRefreshState,
            isRefreshing = isRefreshing,
            onRefresh = { 
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                isRefreshing = true 
            },
            modifier = Modifier.padding(padding)
        ) {
            when {
                isLoading && transactions.isEmpty() -> {
                    // Initial loading state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = SemanticColors.PrimaryIndigo
                        )
                    }
                }
                transactions.isEmpty() -> {
                    // Empty state with illustration
                    EmptyTransactionsState(
                        hasSearch = searchQuery.isNotBlank(),
                        onAddClick = { /* Navigate to add expense */ }
                    )
                }
                else -> {
                    // Transaction list with date grouping
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 80.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Group transactions by date
                        groupedTransactions.forEach { (dateString, items) ->
                            // Date header
                            stickyHeader {
                                DateHeader(
                                    date = dateString,
                                    totalAmount = items.sumOf { it.expense.amount },
                                    itemCount = items.size
                                )
                            }

                            // Transactions for this date
                            items(
                                items = items,
                                key = { item -> item.expense.id },
                                contentType = { "transaction" }
                            ) { item ->
                                TransactionItem(
                                    transaction = item,
                                    onDelete = { expenseToDelete = item.expense },
                                    onEditCategory = { expenseToCategorize = item.expense },
                                    onMarkRecurring = { expenseToRecurring = item.expense },
                                    onRename = { expenseToRename = item.expense }
                                )
                            }
                        }

                        // Loading more indicator
                        if (isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = SemanticColors.PrimaryIndigo,
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ============================================================
        // DIALOGS
        // ============================================================

        // Delete confirmation dialog
        if (expenseToDelete != null) {
            DeleteConfirmationDialog(
                expense = expenseToDelete!!,
                onDismiss = { expenseToDelete = null },
                onConfirm = {
                    viewModel.deleteExpense(expenseToDelete!!)
                    expenseToDelete = null
                }
            )
        }

        // Recurrence picker dialog
        if (expenseToRecurring != null) {
            RecurrencePickerDialog(
                onDismiss = { expenseToRecurring = null },
                onFrequencySelected = { frequency ->
                    expenseToRecurring?.let { viewModel.markAsRecurring(it, frequency) }
                    expenseToRecurring = null
                }
            )
        }

        // Category picker dialog
        if (expenseToCategorize != null) {
            CategoryPickerDialog(
                categories = categories,
                currentCategoryId = expenseToCategorize?.categoryId,
                onDismiss = { expenseToCategorize = null },
                onCategorySelected = { categoryId ->
                    expenseToCategorize?.let { viewModel.updateCategory(it, categoryId) }
                    expenseToCategorize = null
                }
            )
        }

        // Rename merchant dialog
        if (expenseToRename != null) {
            RenameMerchantDialog(
                currentName = expenseToRename?.merchant ?: "",
                onDismiss = { expenseToRename = null },
                onConfirm = { newName ->
                    expenseToRename?.let { viewModel.updateMerchant(it, newName) }
                    expenseToRename = null
                }
            )
        }
    }
}

// ============================================================
// EMPTY STATE COMPONENT
// ============================================================

@Composable
private fun EmptyTransactionsState(
    hasSearch: Boolean,
    onAddClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Animated illustration
            val infiniteTransition = rememberInfiniteTransition(label = "float")
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -10f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = EaseInOutQuad),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "floatY"
            )

            Icon(
                imageVector = if (hasSearch) Icons.Rounded.SearchOff else Icons.Rounded.ReceiptLong,
                contentDescription = null,
                modifier = Modifier
                    .size(100.dp)
                    .offset(y = offsetY.dp),
                tint = SemanticColors.TextMuted
            )

            Text(
                text = if (hasSearch) "No results found" else stringResource(R.string.no_transactions_title),
                style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.TextPrimary
            )

            Text(
                text = if (hasSearch) {
                    "Try a different search term"
                } else {
                    stringResource(R.string.no_transactions_subtitle)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TextSecondary,
                textAlign = TextAlign.Center
            )

            if (!hasSearch) {
                FilledTonalButton(
                    onClick = onAddClick,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.2f),
                        contentColor = SemanticColors.PrimaryIndigo
                    )
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add Expense")
                }
            }
        }
    }
}

// ============================================================
// DATE HEADER COMPONENT
// ============================================================

@Composable
private fun DateHeader(
    date: String,
    totalAmount: Double,
    itemCount: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = date,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.TextPrimary
                )
                Text(
                    text = "$itemCount transaction${if (itemCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.TextMuted
                )
            }

            Text(
                text = "€${String.format(Locale.getDefault(), "%.2f", totalAmount)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SemanticColors.PrimaryIndigo
            )
        }
    }
}

// ============================================================
// TRANSACTION ITEM COMPONENT
// ============================================================

@Composable
private fun TransactionItem(
    transaction: ExpenseWithCategory,
    onDelete: () -> Unit,
    onEditCategory: () -> Unit,
    onMarkRecurring: () -> Unit,
    onRename: () -> Unit
) {
    val expense = transaction.expense
    val category = transaction.category

    // Safe color parsing with fallback
    val categoryColor = remember(transaction.categoryColor) {
        try {
            Color(transaction.categoryColor.toInt())
        } catch (e: Exception) {
            SemanticColors.PrimaryIndigo
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category Icon (Clickable)
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                categoryColor,
                                categoryColor.copy(alpha = 0.7f)
                            )
                        ),
                        shape = CircleShape
                    )
                    .clickable { onEditCategory() }
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category?.icon ?: "❓",
                    fontSize = 26.sp
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Transaction Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Merchant row with edit indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = expense.merchant,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickable { onRename() }
                    )

                    // Manual entry indicator
                    if (expense.isManualEntry) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SemanticColors.PrimaryIndigo.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "✏️",
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    // Edit icon hint
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Tap to rename",
                        modifier = Modifier.size(14.dp),
                        tint = SemanticColors.TextMuted.copy(alpha = 0.5f)
                    )
                }

                // Category and payment method row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Payment method icon
                    val (methodIcon, methodDesc) = when (expense.paymentMethod) {
                        PaymentMethod.CASH -> "💵" to "Cash"
                        PaymentMethod.BANK_TRANSFER -> "🏦" to "Bank"
                        PaymentMethod.CARD -> "💳" to "Card"
                        else -> "" to ""
                    }

                    if (methodIcon.isNotEmpty()) {
                        Text(methodIcon, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(2.dp))
                    }

                    Text(
                        text = category?.name ?: stringResource(R.string.uncategorized_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticColors.TextSecondary,
                        modifier = Modifier.clickable { onEditCategory() }
                    )
                }

                // Time
                Text(
                    text = transaction.formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = SemanticColors.TextMuted
                )
            }

            // Amount
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = transaction.formattedAmount,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        fontFeatureSettings = "tnum"
                    ),
                    color = SemanticColors.TextPrimary
                )

                // Action buttons row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Recurring action
                    IconButton(
                        onClick = onMarkRecurring,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Repeat,
                            contentDescription = stringResource(R.string.mark_recurring_content_description),
                            tint = SemanticColors.PrimaryIndigo.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Delete action
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.delete_button),
                            tint = SemanticColors.DangerRed.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// DIALOG COMPONENTS
// ============================================================

@Composable
private fun DeleteConfirmationDialog(
    expense: Expense,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { 
            Icon(
                Icons.Rounded.Warning, 
                contentDescription = null,
                tint = SemanticColors.DangerRed,
                modifier = Modifier.size(32.dp)
            ) 
        },
        title = { Text(stringResource(R.string.delete_transaction_title)) },
        text = { 
            Text(
                stringResource(
                    R.string.delete_transaction_confirmation, 
                    expense.merchant
                )
            ) 
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = SemanticColors.DangerRed
                )
            ) {
                Text(stringResource(R.string.delete_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun RecurrencePickerDialog(
    onDismiss: () -> Unit,
    onFrequencySelected: (RecurrenceFrequency) -> Unit
) {
    val frequencies = RecurrenceFrequency.values()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Repeat, contentDescription = null) },
        title = { Text(stringResource(R.string.select_frequency_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(frequencies) { frequency ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFrequencySelected(frequency) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                    val icon = when (frequency) {
                        RecurrenceFrequency.WEEKLY -> Icons.Rounded.DateRange
                        RecurrenceFrequency.BIWEEKLY -> Icons.Rounded.DateRange
                        RecurrenceFrequency.MONTHLY -> Icons.Rounded.CalendarMonth
                        RecurrenceFrequency.QUARTERLY -> Icons.Rounded.CalendarViewMonth
                        RecurrenceFrequency.SEMI_ANNUALLY -> Icons.Rounded.Event
                        RecurrenceFrequency.ANNUALLY -> Icons.Rounded.Event
                        RecurrenceFrequency.IRREGULAR -> Icons.Rounded.HelpOutline
                    }

                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = SemanticColors.PrimaryIndigo,
                                modifier = Modifier.size(24.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = frequency.name.replace("_", " ").lowercase()
                                    .replaceFirstChar { 
                                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) 
                                        else it.toString() 
                                    },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun CategoryPickerDialog(
    categories: List<Category>,
    currentCategoryId: Long?,
    onDismiss: () -> Unit,
    onCategorySelected: (Long) -> Unit
) {
    var searchText by remember { mutableStateOf("") }

    val filteredCategories = remember(categories, searchText) {
        if (searchText.isBlank()) {
            categories
        } else {
            categories.filter { 
                it.name.contains(searchText, ignoreCase = true) 
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Category, contentDescription = null) },
        title = { Text(stringResource(R.string.select_category_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Search field
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search categories...") },
                    leadingIcon = { 
                        Icon(Icons.Rounded.Search, contentDescription = null) 
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredCategories) { category ->
                        val isSelected = category.id == currentCategoryId

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCategorySelected(category.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                SemanticColors.PrimaryIndigo.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = category.icon, 
                                    fontSize = 24.sp,
                                    modifier = Modifier.padding(end = 12.dp)
                                )

                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )

                                if (isSelected) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = "Selected",
                                        tint = SemanticColors.PrimaryIndigo,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun RenameMerchantDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        title = { Text("Rename Merchant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Assign a brand name helps the app learn. Future transactions from this source will be auto-corrected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it
                        isError = it.isBlank()
                    },
                    label = { Text("Brand Name") },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        { Text("Name cannot be empty") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { 
                        Icon(Icons.Rounded.Store, contentDescription = null) 
                    },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    if (name.isNotBlank() && name != currentName) {
                        onConfirm(name.trim())
                    } else {
                        isError = true
                    }
                },
                enabled = name.isNotBlank() && name != currentName,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SemanticColors.PrimaryIndigo
                )
            ) {
                Text("Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsViewModel.kt <a name="appsrcmainjavacomyournameexpensetrackeruiscreenstransactionstransactionsviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fixed TransactionsViewModel with:
 * - Thread-safe pagination
 * - Search functionality
 * - Proper error handling
 * - Loading states
 * - Date grouping support
 */
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val recurringExpenseDao: com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
) : ViewModel() {

    companion object {
        const val PAGE_SIZE = 50
    }

    // Tab definitions with lazy label computation
    enum class TransactionTab(val label: String, val daysBack: Int? = null) {
        TODAY("Today", 1),
        WEEK("Week", 7),
        MONTH("Month", 30),
        QUARTER("Quarter", 90),
        YEAR("Year", 365),
        ALL("All", null)
    }

    // Categories
    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected tab state
    private val _selectedTab = MutableStateFlow(TransactionTab.MONTH)
    val selectedTab: StateFlow<TransactionTab> = _selectedTab.asStateFlow()

    // Search query state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Pagination state for ALL tab
    private val _currentPage = MutableStateFlow(0)
    private val _pagedExpenses = MutableStateFlow<List<ExpenseWithCategory>>(emptyList())

    // Thread-safe loading flag to prevent race conditions
    private val isLoadingMore = AtomicBoolean(false)

    // Loading states
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMoreState = MutableStateFlow(false)
    val isLoadingMoreState: StateFlow<Boolean> = _isLoadingMoreState.asStateFlow()

    // Error state for UI feedback
    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()

    // Success feedback
    private val _successMessage = MutableSharedFlow<String>()
    val successMessage: SharedFlow<String> = _successMessage.asSharedFlow()

    // Refresh trigger for pull-to-refresh
    private val _refreshTrigger = MutableStateFlow(0)

    /**
     * Main transactions flow with reactive filtering.
     * Combines tab selection, search query, and refresh triggers.
     */
    // Filter state for drill-down
    private val _filter = MutableStateFlow<TransactionFilter?>(null)
    val filter: StateFlow<TransactionFilter?> = _filter.asStateFlow()

    /**
     * Main transactions flow with reactive filtering.
     * Combines tab selection, search query, filter, and refresh triggers.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<ExpenseWithCategory>> = combine(
        _selectedTab,
        _searchQuery,
        _filter,
        _refreshTrigger
    ) { tab, query, filter, _ -> Triple(tab, query, filter) }
        .flatMapLatest { (tab, query, filter) ->
            if (filter != null) {
                // FILTER MODE: Optimized SQL-level filtering
                val (start, end) = filter.dateRange ?: Pair(0L, System.currentTimeMillis())

                repository.getExpensesWithCategoryFiltered(
                    startMs = start,
                    endMs = end,
                    type = TransactionType.PURCHASE,
                    categoryId = filter.categoryId,
                    merchant = filter.merchantName
                ).map { expenses ->
                    if (query.isBlank()) expenses
                    else expenses.filter { matchesSearch(it, query) }
                }
            } else if (tab == TransactionTab.ALL) {
                // For ALL tab, use paged data with optional search filter
                _pagedExpenses.map { expenses ->
                    if (query.isBlank()) expenses
                    else expenses.filter { matchesSearch(it, query) }
                }
            } else {
                // For other tabs, use time-based filtering
                val range = getTimeRangeForTab(tab)
                repository.getExpensesWithCategoryInPeriod(range.first, range.second)
                    .map { expenses ->
                        if (query.isBlank()) expenses
                        else expenses.filter { matchesSearch(it, query) }
                    }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Grouped transactions by date for UI display.
     * Returns a map of date string to list of transactions.
     */
    val groupedTransactions: StateFlow<Map<String, List<ExpenseWithCategory>>> = transactions
        .map { expenseList ->
            groupTransactionsByDate(expenseList)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    /**
     * Transaction counts per tab for badge display.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tabTransactionCounts: StateFlow<Map<TransactionTab, Int>> = _refreshTrigger
        .flatMapLatest {
            flow {
                val counts = mutableMapOf<TransactionTab, Int>()
                TransactionTab.values().forEach { tab ->
                    if (tab != TransactionTab.ALL) {
                        val range = getTimeRangeForTab(tab)
                        val count = repository.getExpenseCountForPeriod(range.first, range.second)
                        counts[tab] = count
                    }
                }
                emit(counts)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(10000),
            initialValue = emptyMap()
        )

    // ============================================================
    // PUBLIC API
    // ============================================================

    // ============================================================
    // PUBLIC API
    // ============================================================

    fun applyFilter(filter: TransactionFilter) {
        _filter.value = filter
        // We might want to switch tab visual state or specific logic here if needed
    }

    fun clearFilter() {
        _filter.value = null
    }

    fun selectTab(tab: TransactionTab) {
        if (_selectedTab.value == tab) return

        _selectedTab.value = tab
        _currentPage.value = 0
        _pagedExpenses.value = emptyList() // Clear to prevent stale data flash
        _searchQuery.value = "" // Reset search on tab change
        _filter.value = null // Clear filter when manually changing tabs

        if (tab == TransactionTab.ALL) {
            loadInitialAll()
        }
    }

    fun search(query: String) {
        _searchQuery.value = query.trim()
    }

    fun refresh() {
        _refreshTrigger.value += 1

        if (_selectedTab.value == TransactionTab.ALL) {
            _currentPage.value = 0
            _pagedExpenses.value = emptyList()
            loadInitialAll()
        }
    }

    fun loadMore() {
        // Guard conditions
        if (_selectedTab.value != TransactionTab.ALL) return
        if (_isLoadingMoreState.value) return

        // Atomic check-and-set to prevent race conditions
        if (!isLoadingMore.compareAndSet(false, true)) return

        viewModelScope.launch {
            _isLoadingMoreState.value = true
            try {
                val nextPage = _currentPage.value + 1
                val offset = nextPage * PAGE_SIZE

                val nextItems = withContext(Dispatchers.IO) {
                    repository.getExpensesPaged(PAGE_SIZE, offset)
                }

                if (nextItems.isNotEmpty()) {
                    // Use thread-safe list concatenation
                    _pagedExpenses.update { current ->
                        current + nextItems.distinctBy { it.expense.id }
                    }
                    _currentPage.value = nextPage
                }
            } catch (e: Exception) {
                _error.emit("Failed to load more transactions: ${e.message}")
            } finally {
                _isLoadingMoreState.value = false
                isLoadingMore.set(false)
            }
        }
    }

    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.deleteExpense(expense)
                _successMessage.emit("Transaction deleted")

                // Refresh data
                refresh()
            } catch (e: Exception) {
                _error.emit("Failed to delete transaction: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateCategory(expense: Expense, categoryId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.updateExpenseCategory(expense, categoryId)
                _successMessage.emit("Category updated")
            } catch (e: Exception) {
                _error.emit("Failed to update category: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateMerchant(expense: Expense, newMerchant: String) {
        val trimmedName = newMerchant.trim()
        if (trimmedName.isBlank()) {
            viewModelScope.launch { _error.emit("Merchant name cannot be empty") }
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.updateExpenseMerchant(expense, trimmedName)
                _successMessage.emit("Merchant renamed to $trimmedName")
            } catch (e: Exception) {
                _error.emit("Failed to update merchant: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun markAsRecurring(
        expense: Expense, 
        frequency: com.yourname.expensetracker.domain.model.RecurrenceFrequency
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val nextDate = System.currentTimeMillis() + frequency.intervalInMs
                val rule = com.yourname.expensetracker.data.database.entity.ManualRecurringExpense(
                    merchant = expense.merchant,
                    amount = expense.amount,
                    frequency = frequency,
                    nextDate = nextDate
                )
                recurringExpenseDao.insert(rule)
                _successMessage.emit("Marked as recurring (${frequency.name.lowercase().replace("_", " ")})")
            } catch (e: Exception) {
                _error.emit("Failed to mark as recurring: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ============================================================
    // PRIVATE HELPERS
    // ============================================================

    private fun loadInitialAll() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val initial = withContext(Dispatchers.IO) {
                    repository.getExpensesPaged(PAGE_SIZE, 0)
                }
                _pagedExpenses.value = initial
                _currentPage.value = 0
            } catch (e: Exception) {
                _error.emit("Failed to load transactions: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Optimized time range calculation using TimePeriodUtils.
     */
    private fun getTimeRangeForTab(tab: TransactionTab): Pair<Long, Long> {
        val now = System.currentTimeMillis()

        return when (tab) {
            TransactionTab.TODAY -> {
                val startOfDay = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
                Pair(startOfDay, now)
            }
            TransactionTab.WEEK -> {
                val startOfWeek = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfWeek(now)
                Pair(startOfWeek, now)
            }
            TransactionTab.MONTH -> {
                val startOfMonth = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(now)
                Pair(startOfMonth, now)
            }
            TransactionTab.QUARTER -> {
                val startOfQuarter = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfQuarter(now)
                Pair(startOfQuarter, now)
            }
            TransactionTab.YEAR -> {
                val startOfYear = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfYear(now)
                Pair(startOfYear, now)
            }
            TransactionTab.ALL -> Pair(0L, now)
        }
    }

    /**
     * Search matching with null-safety and performance optimization.
     */
    private fun matchesSearch(item: ExpenseWithCategory, query: String): Boolean {
        // Use the extension property we defined (or compute it here if extension not visible)
        // Since we are in ViewModel, we can just access properties directly
        val lowerQuery = query.lowercase()

        return item.expense.merchant.lowercase().contains(lowerQuery) ||
                item.category?.name?.lowercase()?.contains(lowerQuery) == true
                // Note: formattedAmount logic is in UI/Extension, duplicating basic check here:
                // item.expense.amount.toString().contains(lowerQuery) 
    }

    /**
     * Groups transactions by formatted date string.
     * Uses sorted map to maintain date order (newest first).
     */
    private fun groupTransactionsByDate(
        expenses: List<ExpenseWithCategory>
    ): Map<String, List<ExpenseWithCategory>> {
        if (expenses.isEmpty()) return emptyMap()

        val dateFormat = java.text.SimpleDateFormat(
            "EEEE, MMMM d, yyyy", 
            java.util.Locale.getDefault()
        )

        return expenses
            .sortedByDescending { it.expense.date }
            .groupBy { item ->
                dateFormat.format(java.util.Date(item.expense.date))
            }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\ui\theme\Theme.kt <a name="appsrcmainjavacomyournameexpensetrackeruithemethemekt"></a>
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
    val TextMuted = Color(0x9994A3B8) // 60% alpha

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

private val LightColorScheme = DarkColorScheme // Focusing on the Midnight Theme as requested

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

---

## app\src\main\java\com\yourname\expensetracker\ui\util\HapticFeedback.kt <a name="appsrcmainjavacomyournameexpensetrackeruiutilhapticfeedbackkt"></a>
```kotlin
package com.yourname.expensetracker.ui.util

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

object AppHaptics {
    fun performStandard(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun performSuccess(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    fun performError(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    fun performHeavy(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

@Composable
fun rememberHapticFeedback(): (HapticType) -> Unit {
    val view = LocalView.current
    return { type ->
        when (type) {
            HapticType.Standard -> AppHaptics.performStandard(view)
            HapticType.Success -> AppHaptics.performSuccess(view)
            HapticType.Error -> AppHaptics.performError(view)
            HapticType.Heavy -> AppHaptics.performHeavy(view)
        }
    }
}

enum class HapticType {
    Standard, Success, Error, Heavy
}

```

---

