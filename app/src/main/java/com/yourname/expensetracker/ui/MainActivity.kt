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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.components.AppNavigationBar
import com.yourname.expensetracker.ui.components.NotificationPermissionDialog
import com.yourname.expensetracker.ui.screens.assistant.AssistantSheet
import com.yourname.expensetracker.ui.screens.analytics.AdvancedAnalyticsScreen
import com.yourname.expensetracker.ui.screens.analytics.AnalyticsScreen
import com.yourname.expensetracker.ui.screens.assistant.AssistantSheet
import com.yourname.expensetracker.ui.screens.bank.BankConnectionsScreen
import com.yourname.expensetracker.ui.screens.budget.BudgetForecastingScreen
import com.yourname.expensetracker.ui.screens.budget.BudgetScreen
import com.yourname.expensetracker.ui.screens.carbon.CarbonFootprintScreen
import com.yourname.expensetracker.ui.screens.cashflow.CashFlowCalendarScreen
import com.yourname.expensetracker.ui.screens.challenge.SpendingChallengesScreen
import com.yourname.expensetracker.ui.screens.home.HomeScreen
import com.yourname.expensetracker.ui.screens.investment.InvestmentPortfolioScreen
import com.yourname.expensetracker.ui.screens.lifestyle.LifestyleInflationScreen
import com.yourname.expensetracker.ui.screens.map.SpendingMapScreen
import com.yourname.expensetracker.ui.screens.negotiation.BillNegotiationScreen
import com.yourname.expensetracker.ui.screens.naturallanguage.NaturalLanguageSearchScreen
import com.yourname.expensetracker.ui.screens.price.PriceProtectionScreen
import com.yourname.expensetracker.ui.screens.receiptmatching.ReceiptMatchingScreen
import com.yourname.expensetracker.ui.screens.reminder.BillRemindersScreen
import com.yourname.expensetracker.ui.screens.review.ReviewScreen
import com.yourname.expensetracker.ui.screens.savings.SavingsGoalsScreen
import com.yourname.expensetracker.ui.screens.split.SplitTemplatesScreen
import com.yourname.expensetracker.ui.screens.split.VisualSplitEditorScreen
import com.yourname.expensetracker.ui.screens.transactions.TransactionsScreen
import com.yourname.expensetracker.ui.screens.warranty.WarrantyTrackerScreen
import com.yourname.expensetracker.ui.screens.currency.CurrencyManagementScreen
import com.yourname.expensetracker.ui.screens.export.ExportOptionsScreen
import com.yourname.expensetracker.ui.screens.groups.SharedExpenseGroupsScreen
import com.yourname.expensetracker.ui.screens.recurringmanual.ManualRecurringExpenseScreen
import com.yourname.expensetracker.ui.screens.subscription.SubscriptionManagementScreen
import com.yourname.expensetracker.ui.screens.tax.TaxConfigurationScreen
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import com.yourname.expensetracker.ui.navigation.NavigationController
import com.yourname.expensetracker.ui.navigation.ProvideNavigationController
import com.yourname.expensetracker.ui.navigation.LocalNavigationController
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
                ProvideNavigationController(
                    initialDestination = NavigationDestination.Home
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen(mainViewModel)
                    }
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
    
    // Budget Forecasting State (requires data parameter)
    var showBudgetForecasting by rememberSaveable { mutableStateOf(false) }
    var selectedBudgetForForecast by rememberSaveable { mutableStateOf<com.yourname.expensetracker.data.database.entity.Budget?>(null) }

    // Navigation Controller - Single source of truth for feature screens
    val navigation = LocalNavigationController.current
    val currentDestination = navigation.destination

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
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = stringResource(R.string.a11y_open_assistant))
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
                        // Feature navigation now handled via NavigationController in FeaturesMenu
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
            
            // Feature Screens - Render based on NavigationDestination
            when (currentDestination) {
                is NavigationDestination.SavingsGoals -> {
                    SavingsGoalsScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.CarbonFootprint -> {
                    CarbonFootprintScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.WarrantyTracker -> {
                    WarrantyTrackerScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.PriceProtection -> {
                    PriceProtectionScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.BillNegotiation -> {
                    BillNegotiationScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.SmartSearch -> {
                    NaturalLanguageSearchScreen(
                        onNavigateBack = { navigation.navigateBack() },
                        onViewTransaction = { transactionId ->
                            // Navigate to transaction details
                            navigation.navigateBack()
                        }
                    )
                }
                is NavigationDestination.ReceiptMatching -> {
                    ReceiptMatchingScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.InvestmentPortfolio -> {
                    InvestmentPortfolioScreen(
                        onNavigateBack = { navigation.navigateBack() },
                        onAddInvestment = { 
                            // Handle add investment
                            navigation.navigateBack() 
                        }
                    )
                }
                is NavigationDestination.BankConnections -> {
                    BankConnectionsScreen(
                        onNavigateBack = { navigation.navigateBack() },
                        onAddConnection = { 
                            // Handle add bank connection
                            navigation.navigateBack() 
                        }
                    )
                }
                is NavigationDestination.BillReminders -> {
                    BillRemindersScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.SpendingChallenges -> {
                    SpendingChallengesScreen(
                        onNavigateBack = { navigation.navigateBack() },
                        onCreateChallenge = { 
                            // Handle create challenge
                            navigation.navigateBack() 
                        }
                    )
                }
                is NavigationDestination.AdvancedAnalytics -> {
                    AdvancedAnalyticsScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.CashFlowCalendar -> {
                    CashFlowCalendarScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.LifestyleInflation -> {
                    LifestyleInflationScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.SplitTemplates -> {
                    SplitTemplatesScreen(
                        onNavigateBack = { navigation.navigateBack() },
                        onCreateTemplate = {
                            // Navigate to split editor to create template
                            navigation.navigateTo(NavigationDestination.VisualSplitEditor())
                        },
                        onEditTemplate = { template ->
                            // Navigate to editor with template ID for editing
                            navigation.navigateTo(NavigationDestination.VisualSplitEditor(templateId = template.id))
                        }
                    )
                }
                is NavigationDestination.VisualSplitEditor -> {
                    VisualSplitEditorScreen(
                        totalAmount = currentDestination.expense?.amount ?: 0.0,
                        currencyCode = currentDestination.expense?.currency ?: "EUR",
                        expenseId = currentDestination.expense?.id,
                        templateId = currentDestination.templateId,
                        onSplitComplete = { shares, splitType ->
                            // Handle split completion
                            navigation.navigateBack()
                        },
                        onSaveAsTemplate = { name, shares, splitType ->
                            // Handle save as template
                            navigation.navigateBack()
                        },
                        onNavigateBack = { 
                            navigation.navigateBack()
                        }
                    )
                }
                is NavigationDestination.CurrencyManagement -> {
                    CurrencyManagementScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.SubscriptionManagement -> {
                    SubscriptionManagementScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.TaxConfiguration -> {
                    TaxConfigurationScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.ExportOptions -> {
                    ExportOptionsScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.ManualRecurringExpense -> {
                    ManualRecurringExpenseScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.SharedExpenseGroups -> {
                    SharedExpenseGroupsScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                else -> { /* Main tabs handled by AnimatedContent */ }
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
                        Text(stringResource(R.string.add_expense_scan_receipt))
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
                        Text(stringResource(R.string.add_expense_manual))
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
