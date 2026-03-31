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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.ui.components.AppNavigationBar
import com.yourname.expensetracker.ui.components.NotificationPermissionDialog
import com.yourname.expensetracker.ui.screens.assistant.AssistantSheet
import com.yourname.expensetracker.ui.screens.analytics.AnalyticsScreen
import com.yourname.expensetracker.ui.screens.budget.BudgetScreen
import com.yourname.expensetracker.ui.screens.budget.BudgetForecastingScreen
import com.yourname.expensetracker.ui.screens.home.HomeScreen
import com.yourname.expensetracker.ui.screens.map.SpendingMapScreen
import com.yourname.expensetracker.ui.screens.review.ReviewScreen
import com.yourname.expensetracker.ui.screens.transactions.TransactionsScreen
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import com.yourname.expensetracker.ui.util.ClipboardAmountParser
import com.yourname.expensetracker.ui.util.HapticType
import com.yourname.expensetracker.ui.util.rememberHapticFeedback
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics

    @Inject
    lateinit var aiEngagementRepository: AiEngagementRepository

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
                    MainScreen(mainViewModel)
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
                "dashboard" -> {
                    mainViewModel.navigateToTab(0)
                    data.getQueryParameter("briefingKey")?.let { briefingKey ->
                        lifecycleScope.launch {
                            aiEngagementRepository.setLastOpenedDashboardBriefingKey(briefingKey)
                        }
                    }
                    aiRuntimeDiagnostics.recordInteraction(
                        type = "phase4_open",
                        message = "dashboard deep link opened${data.getQueryParameter("briefingKey")?.let { " ($it)" } ?: ""}"
                    )
                }
                "activity" -> mainViewModel.navigateToTab(1)
                "review" -> mainViewModel.navigateToTab(2)
                "plan" -> mainViewModel.navigateToTab(3)
                "add" -> mainViewModel.navigateToTab(0)
                "analytics" -> mainViewModel.navigateToTab(4)
                "map" -> mainViewModel.navigateToTab(5)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(mainViewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    
    val pendingCount by mainViewModel.pendingReviewCount.collectAsState()
    
    val context = LocalContext.current
    
    val reviewViewModel: com.yourname.expensetracker.ui.screens.review.ReviewViewModel = hiltViewModel()
    
    var showNotificationPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var activeTransactionFilter by remember { mutableStateOf<com.yourname.expensetracker.ui.screens.transactions.TransactionFilter?>(null) }
    
    LaunchedEffect(Unit) {
        mainViewModel.navigationRequest.collect { request ->
            when (request) {
                is MainNavigationRequest.Tab -> {
                    selectedTab = request.index
                }
                is MainNavigationRequest.Transactions -> {
                    activeTransactionFilter = request.filter
                    selectedTab = 1
                }
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showNotificationPermissionDialog = true
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != 
                PackageManager.PERMISSION_GRANTED) {
                showNotificationPermissionDialog = true
            }
        }
    }

    val haptic = rememberHapticFeedback()

    var showAddExpense by rememberSaveable { mutableStateOf(false) }
    var showScanReceipt by rememberSaveable { mutableStateOf(false) }
    var showRecurringExpenses by rememberSaveable { mutableStateOf(false) }
    var showAssistant by rememberSaveable { mutableStateOf(false) }
    var isFabExpanded by rememberSaveable { mutableStateOf(false) }
    
    // Feature Screens Navigation State
    var showBudgetForecasting by rememberSaveable { mutableStateOf(false) }
    var selectedBudgetForForecast by rememberSaveable { mutableStateOf<com.yourname.expensetracker.data.database.entity.Budget?>(null) }

    NotificationPermissionDialog(
        showDialog = showNotificationPermissionDialog,
        onDismiss = { showNotificationPermissionDialog = false },
        onEnable = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AppNavigationBar(
                selectedTab = selectedTab,
                onTabSelected = { index ->
                    if (selectedTab != index) haptic(HapticType.Standard)
                    if (index == 1) activeTransactionFilter = null
                    selectedTab = index
                },
                pendingReviewCount = pendingCount
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = {
                        haptic(HapticType.Standard)
                        showAssistant = true
                        isFabExpanded = false
                    },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = "Open assistant")
                }

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
                        },
                        onNavigateToAnalytics = { selectedTab = 4 },
                        onNavigateToMap = { selectedTab = 5 },
                        onNavigateToBudgetDetail = { selectedTab = 3 }
                    )
                    1 -> TransactionsScreen(
                        onNavigateToAnalytics = { selectedTab = 4 },
                        initialFilter = activeTransactionFilter
                    )
                    2 -> ReviewScreen()
                    3 -> BudgetScreen(
                        onNavigateToForecast = { budget ->
                            selectedBudgetForForecast = budget
                            showBudgetForecasting = true
                        }
                    )
                    4 -> com.yourname.expensetracker.ui.screens.analytics.AnalyticsScreen(
                        onNavigateToTransactions = { filter ->
                            activeTransactionFilter = filter
                            selectedTab = 1
                        }
                    )
                    5 -> SpendingMapScreen()
                }
            }

            if (showAddExpense) {
                var initialAmount by remember { mutableStateOf<String?>(null) }
                
                LaunchedEffect(Unit) {
                    val clipboardManager = ClipboardAmountParser.getClipboardManager(context)
                    initialAmount = ClipboardAmountParser.parseAmountFromClipboard(clipboardManager)
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

            if (showAssistant) {
                AssistantSheet(
                    onDismiss = { showAssistant = false },
                    onOpenTransactions = { filter ->
                        activeTransactionFilter = filter
                        selectedTab = 1
                        showAssistant = false
                    }
                )
            }
            
            // Feature Screens
            if (showBudgetForecasting && selectedBudgetForForecast != null) {
                BudgetForecastingScreen(
                    budget = selectedBudgetForForecast!!,
                    onNavigateBack = { 
                        showBudgetForecasting = false
                        selectedBudgetForForecast = null
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
    val context = LocalContext.current
    
    var clipboardAmount by remember { mutableStateOf<String?>(null) }

    fun checkClipboard() {
        val clipboardManager = ClipboardAmountParser.getClipboardManager(context)
        clipboardAmount = ClipboardAmountParser.parseAmountFromClipboard(clipboardManager)
    }

    DisposableEffect(Unit) {
        val clipboardManager = ClipboardAmountParser.getClipboardManager(context)
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            checkClipboard()
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        checkClipboard()
        
        onDispose {
            clipboardManager.removePrimaryClipChangedListener(listener)
        }
    }
    
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
