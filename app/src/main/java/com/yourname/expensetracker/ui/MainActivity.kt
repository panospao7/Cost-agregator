package com.yourname.expensetracker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.listSaver
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
import com.yourname.expensetracker.data.database.entity.Budget as BudgetEntity
import com.yourname.expensetracker.ui.components.AppNavigationBar
import com.yourname.expensetracker.ui.components.NotificationPermissionDialog
import com.yourname.expensetracker.ui.screens.assistant.AssistantSheet
import com.yourname.expensetracker.ui.screens.analytics.AdvancedAnalyticsScreen
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
import com.yourname.expensetracker.ui.screens.recurring.RecurringExpensesScreen
import com.yourname.expensetracker.ui.screens.recurringmanual.ManualRecurringExpenseScreen
import com.yourname.expensetracker.ui.screens.subscription.SubscriptionManagementScreen
import com.yourname.expensetracker.ui.screens.tax.TaxConfigurationScreen
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import com.yourname.expensetracker.ui.navigation.ProvideNavigationController
import com.yourname.expensetracker.ui.navigation.LocalNavigationController
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.ui.util.ClipboardAmountParser
import com.yourname.expensetracker.ui.util.HapticType
import com.yourname.expensetracker.ui.util.rememberHapticFeedback
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics

    @Inject
    lateinit var aiEngagementRepository: AiEngagementRepository

    @Inject
    lateinit var actionRegistry: ContextualActionRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            val consumed = handleIntent(intent)
            if (consumed) {
                // One-shot consume so this deep link is not re-applied on future recreations.
                intent.data = null
                setIntent(intent)
            }
        }
        setContent {
            ExpenseTrackerTheme {
                ProvideNavigationController(
                    initialDestination = NavigationDestination.Home
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen(mainViewModel, actionRegistry)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val consumed = handleIntent(intent)
        if (consumed) {
            // One-shot consume so this deep link is not re-applied on future recreations.
            intent.data = null
            setIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "expensetracker") return false

        when (data.host) {
            "home", "dashboard" -> {
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
            "add" -> {
                mainViewModel.triggerAddExpense()
            }
            "analytics" -> mainViewModel.navigateToTab(4)
            "map" -> mainViewModel.navigateToTab(5)
            else -> {
                Timber.w("Ignoring unsupported deep link host: ${data.host}")
                mainViewModel.navigateToTab(0)
            }
        }

        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    actionRegistry: ContextualActionRegistry
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val transactionFilterSaver = remember {
        listSaver<TransactionFilter?, Any?>(
            save = { filter ->
                if (filter == null) {
                    emptyList<Any?>()
                } else {
                    listOf(
                        filter.categoryId,
                        filter.merchantName,
                        filter.transactionType?.name,
                        filter.dateRange?.first,
                        filter.dateRange?.second,
                        filter.ownership?.name,
                        filter.minAmount,
                        filter.maxAmount,
                        filter.correlationId
                    )
                }
            },
            restore = { saved ->
                if (saved.isEmpty()) {
                    null
                } else {
                    val transactionType = (saved.getOrNull(2) as? String)
                        ?.let { runCatching { com.yourname.expensetracker.data.database.entity.TransactionType.valueOf(it) }.getOrNull() }
                    val rangeStart = saved.getOrNull(3) as? Long
                    val rangeEnd = saved.getOrNull(4) as? Long
                    val ownership = (saved.getOrNull(5) as? String)
                        ?.let { runCatching { com.yourname.expensetracker.data.repository.OwnershipFilter.valueOf(it) }.getOrNull() }

                    TransactionFilter(
                        categoryId = saved.getOrNull(0) as? Long,
                        merchantName = saved.getOrNull(1) as? String,
                        transactionType = transactionType,
                        dateRange = if (rangeStart != null && rangeEnd != null) rangeStart to rangeEnd else null,
                        ownership = ownership,
                        minAmount = saved.getOrNull(6) as? Double,
                        maxAmount = saved.getOrNull(7) as? Double,
                        correlationId = (saved.getOrNull(8) as? Long) ?: System.currentTimeMillis()
                    )
                }
            }
        )
    }
    
    val pendingCount by mainViewModel.pendingReviewCount.collectAsState()
    
    val context = LocalContext.current
    
    val reviewViewModel: com.yourname.expensetracker.ui.screens.review.ReviewViewModel = hiltViewModel()
    
    var showNotificationPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var activeTransactionFilter by rememberSaveable(stateSaver = transactionFilterSaver) {
        mutableStateOf<TransactionFilter?>(null)
    }
    
    // Navigation Controller - Single source of truth for ALL navigation
    val navigation = LocalNavigationController.current
    val currentDestination = navigation.destination

    BackHandler(enabled = navigation.canNavigateBack()) {
        navigation.navigateBack()
    }
    
    var isFabExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        mainViewModel.navigationRequest.collect { request ->
            when (request) {
                is MainNavigationRequest.Tab -> {
                    navigation.navigateToTab(request.index)
                }
                is MainNavigationRequest.Transactions -> {
                    activeTransactionFilter = request.filter
                    navigation.navigateToTab(1)
                }
                is MainNavigationRequest.Destination -> {
                    // All navigation now goes through NavigationDestination sealed class
                    navigation.navigateTo(request.destination)
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

    // Sync: Keep selectedTab in sync with NavigationController
    LaunchedEffect(currentDestination) {
        navigation.getCurrentTabIndex()?.let { tabIndex ->
            if (selectedTab != tabIndex) {
                selectedTab = tabIndex
            }
        }
    }

    NotificationPermissionDialog(
        showDialog = showNotificationPermissionDialog,
        onDismiss = { showNotificationPermissionDialog = false },
        onEnable = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Only show bottom bar when on main tabs (not feature screens)
            if (navigation.isOnMainTab()) {
                AppNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = { index ->
                        if (selectedTab != index) haptic(HapticType.Standard)
                        if (index == 1) activeTransactionFilter = null
                        navigation.navigateToTab(index)
                    },
                    pendingReviewCount = pendingCount
                )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = {
                        haptic(HapticType.Standard)
                        navigation.navigateTo(NavigationDestination.Assistant)
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
                        navigation.navigateTo(NavigationDestination.AddExpense)
                        isFabExpanded = false
                    },
                    onScanReceipt = {
                        navigation.navigateTo(NavigationDestination.ScanReceipt)
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
                        onNavigateToReview = { navigation.navigateToTab(2) },
                        onNavigateToRecurring = { navigation.navigateTo(NavigationDestination.RecurringExpenses) },
                        onNavigateToTransactions = { filter ->
                            activeTransactionFilter = filter
                            navigation.navigateToTab(1)
                        },
                        onNavigateToAnalytics = { navigation.navigateToTab(4) },
                        onNavigateToMap = { navigation.navigateToTab(5) },
                        onNavigateToBudgetDetail = { category ->
                            navigation.navigateTo(
                                NavigationDestination.BudgetDetail(
                                    categoryId = category.toLongOrNull(),
                                    categoryName = category.takeIf { it.isNotBlank() }
                                )
                            )
                        },
                        // Config-driven feature navigation - handles all 22 features
                        onNavigateToFeature = { destination -> navigation.navigateTo(destination) }
                    )
                    1 -> TransactionsScreen(
                        onNavigateToAnalytics = { navigation.navigateToTab(4) },
                        onAddExpense = { navigation.navigateTo(NavigationDestination.AddExpense) },
                        initialFilter = activeTransactionFilter
                    )
                    2 -> ReviewScreen()
                    3 -> BudgetScreen(
                        initialCategoryId = (currentDestination as? NavigationDestination.BudgetDetail)?.categoryId,
                        initialCategoryName = (currentDestination as? NavigationDestination.BudgetDetail)?.categoryName,
                        onNavigateToForecast = { budget: BudgetEntity ->
                            navigation.navigateTo(NavigationDestination.BudgetForecasting(budget))
                        }
                    )
                    4 -> com.yourname.expensetracker.ui.screens.analytics.AnalyticsScreen(
                        onNavigateToTransactions = { filter ->
                            activeTransactionFilter = filter
                            navigation.navigateToTab(1)
                        }
                    )
                    5 -> SpendingMapScreen()
                }
            }

            // All screens rendered via NavigationDestination sealed class
            when (currentDestination) {
                // Overlays that were previously boolean flags
                is NavigationDestination.AddExpense -> {
                    var initialAmount by remember { mutableStateOf<String?>(null) }
                    
                    LaunchedEffect(Unit) {
                        val clipboardManager = ClipboardAmountParser.getClipboardManager(context)
                        initialAmount = ClipboardAmountParser.parseAmountFromClipboard(clipboardManager)
                    }

                    com.yourname.expensetracker.ui.screens.addexpense.AddExpenseSheet(
                        onDismiss = { navigation.navigateBack() },
                        initialAmount = initialAmount
                    )
                }
                is NavigationDestination.ScanReceipt -> {
                    com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen(
                        onDismiss = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.RecurringExpenses -> {
                    RecurringExpensesScreen(
                        onNavigateBack = { navigation.navigateBack() },
                        onNavigateToTransactions = { filter ->
                            activeTransactionFilter = filter
                            navigation.navigateToTab(1)
                        }
                    )
                }
                is NavigationDestination.ManualRecurringExpense -> {
                    ManualRecurringExpenseScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.Assistant -> {
                    AssistantSheet(
                        onDismiss = { navigation.navigateBack() },
                        onOpenTransactions = { filter ->
                            activeTransactionFilter = filter
                            // Just navigate to transactions - assistant overlay will be closed naturally
                            // when the user interacts with the transactions screen
                            navigation.navigateToTab(1)
                        }
                    )
                }
                is NavigationDestination.BudgetForecasting -> {
                    currentDestination.budget?.let { budget: BudgetEntity ->
                        BudgetForecastingScreen(
                            budget = budget,
                            onNavigateBack = { navigation.navigateBack() }
                        )
                    } ?: run {
                        // No budget provided, go back
                        LaunchedEffect(Unit) { navigation.navigateBack() }
                    }
                }
                
                // Settings / Management Screens (previously orphaned, now in Features Menu)
                is NavigationDestination.AiSettings -> {
                    com.yourname.expensetracker.ui.screens.aisettings.AiSettingsScreen(
                        onDismiss = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.CategoryManagement -> {
                    com.yourname.expensetracker.ui.screens.categories.CategoryScreen(
                        onDismiss = { navigation.navigateBack() }
                    )
                }
                
                // Feature Screens
                is NavigationDestination.SavingsGoals -> {
                    SavingsGoalsScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.CarbonFootprint -> {
                    CarbonFootprintScreen(
                        onNavigateBack = { navigation.navigateBack() },
                        actionRegistry = actionRegistry
                    )
                }
                is NavigationDestination.WarrantyTracker -> {
                    WarrantyTrackerScreen(
                        onNavigateBack = { navigation.navigateBack() },
                        actionRegistry = actionRegistry
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
                            // Navigate to Transactions tab (transaction detail view coming soon)
                            navigation.navigateToTab(1)
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
                            // Show "Coming soon" since add investment flow not implemented
                            navigation.navigateBack()
                        }
                    )
                }
                is NavigationDestination.BankConnections -> {
                    BankConnectionsScreen(
                        onNavigateBack = { navigation.navigateBack() },
                        onAddConnection = { 
                            // Show "Coming soon" since add bank connection flow not implemented
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
                            // Show "Coming soon" since create challenge flow not implemented
                            navigation.navigateBack()
                        },
                        actionRegistry = actionRegistry
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
                        onNavigateBack = { navigation.navigateBack() },
                        actionRegistry = actionRegistry
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
                        totalAmount = currentDestination.expenseAmount ?: currentDestination.expense?.amount ?: 0.0,
                        currencyCode = currentDestination.expenseCurrency ?: currentDestination.expense?.currency ?: "EUR",
                        expenseId = currentDestination.expenseId ?: currentDestination.expense?.id,
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
                        onNavigateBack = { navigation.navigateBack() },
                        actionRegistry = actionRegistry
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
                is NavigationDestination.SharedExpenseGroups -> {
                    SharedExpenseGroupsScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                
                // Main tabs handled by AnimatedContent above
                is NavigationDestination.Home,
                is NavigationDestination.Transactions,
                is NavigationDestination.Review,
                is NavigationDestination.Budget,
                is NavigationDestination.BudgetDetail,
                is NavigationDestination.Analytics,
                is NavigationDestination.SpendingMap -> { /* Handled by AnimatedContent */ }
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
    
    val (icon, labelRes) = when (selectedTab) {
        2 -> Pair(Icons.Rounded.CheckCircle, R.string.label_approve_all)
        else -> {
            if (clipboardAmount != null) {
                Pair(Icons.Rounded.ContentPaste, R.string.label_add_amount_format)
            } else {
                Pair(Icons.Rounded.Add, R.string.add_expense_title)
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
                    contentDescription = if (isExpanded && selectedTab != 2) stringResource(R.string.label_close) else stringResource(labelRes)
                ) 
            },
            text = { 
                val amount = clipboardAmount
                Text(
                    if (isExpanded && selectedTab != 2) {
                        stringResource(R.string.label_close)
                    } else if (amount != null && selectedTab != 2) {
                        stringResource(R.string.label_add_amount_format, amount)
                    } else {
                        stringResource(labelRes)
                    }
                ) 
            },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
