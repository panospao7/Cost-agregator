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
import com.google.gson.Gson
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget as BudgetEntity
import com.yourname.expensetracker.data.database.entity.SplitShare
import com.yourname.expensetracker.data.database.entity.SplitTemplate
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
import com.yourname.expensetracker.ui.navigation.NavigationResult
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
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var aiRuntimeDiagnostics: AiRuntimeDiagnostics

    @Inject
    lateinit var aiEngagementRepository: AiEngagementRepository

    @Inject
    lateinit var actionRegistry: ContextualActionRegistry

    @Inject
    lateinit var expenseDao: ExpenseDao

    @Inject
    lateinit var gson: Gson

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
                        MainScreen(
                            mainViewModel = mainViewModel,
                            actionRegistry = actionRegistry,
                            expenseDao = expenseDao,
                            gson = gson
                        )
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
                mainViewModel.navigateTo(NavigationDestination.Home)
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
            "activity" -> {
                val expenseId = data.getQueryParameter("expenseId")?.toLongOrNull()
                if (expenseId != null) {
                    lifecycleScope.launch {
                        val expense = expenseDao.getById(expenseId)
                        if (expense != null) {
                            val calendar = java.util.Calendar.getInstance().apply {
                                timeInMillis = expense.date
                                set(java.util.Calendar.HOUR_OF_DAY, 0)
                                set(java.util.Calendar.MINUTE, 0)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }
                            val startOfDay = calendar.timeInMillis
                            calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
                            mainViewModel.navigateToTransactions(
                                TransactionFilter(dateRange = startOfDay to calendar.timeInMillis)
                            )
                        } else {
                            mainViewModel.navigateTo(NavigationDestination.Transactions(initialExpenseId = expenseId))
                        }
                    }
                } else {
                    mainViewModel.navigateTo(NavigationDestination.Transactions())
                }
            }
            "review" -> mainViewModel.navigateTo(NavigationDestination.Review)
            "plan" -> mainViewModel.navigateTo(NavigationDestination.Budget)
            "add" -> {
                mainViewModel.triggerAddExpense()
            }
            "analytics" -> {
                mainViewModel.navigateTo(
                    NavigationDestination.Analytics(
                        initialPeriod = data.getQueryParameter("period")
                    )
                )
            }
            "map" -> {
                mainViewModel.navigateTo(
                    NavigationDestination.SpendingMap(
                        initialLocationQuery = data.getQueryParameter("location")
                    )
                )
            }
            else -> {
                Timber.w("Ignoring unsupported deep link host: ${data.host}")
                mainViewModel.navigateTo(NavigationDestination.Home)
            }
        }

        return true
    }
}

private data class PersistedVisualSplit(
    val splitType: SplitTemplate.SplitType,
    val shares: List<SplitShare>
)

private suspend fun applyVisualSplitToExpense(
    expenseDao: ExpenseDao,
    gson: Gson,
    expenseId: Long,
    shares: List<SplitShare>,
    splitType: SplitTemplate.SplitType,
    templateId: Long?
): Boolean {
    val expense = expenseDao.getById(expenseId) ?: return false
    val sanitizedShares = shares
        .map { it.copy(participantName = it.participantName.trim()) }
        .sortedBy { it.participantIndex }

    if (sanitizedShares.isEmpty()) return false

    val myShare = sanitizedShares.first()
    val sharedWithName = sanitizedShares
        .drop(1)
        .joinToString(", ") { it.participantName }
        .takeIf { it.isNotBlank() }

    expenseDao.insertAll(
        listOf(
            expense.copy(
                isNotMine = false,
                ownerName = null,
                isSharedExpense = sanitizedShares.size > 1,
                sharedWithName = sharedWithName,
                mySharePercentage = myShare.percentage
                    ?.takeIf { it.isFinite() }
                    ?.roundToInt()
                    ?.coerceIn(0, 100),
                myShareAmount = myShare.amount?.takeIf { it.isFinite() },
                splitTemplateId = templateId,
                splitVisualization = gson.toJson(
                    PersistedVisualSplit(
                        splitType = splitType,
                        shares = sanitizedShares
                    )
                )
            )
        )
    )

    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    actionRegistry: ContextualActionRegistry,
    expenseDao: ExpenseDao,
    gson: Gson
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
                        correlationId = (saved.getOrNull(8) as? Long) ?: 0L
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
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(navigation) {
        navigation.navigationResults.collect { result ->
            when (result) {
                is NavigationResult.VisualSplitApplied -> {
                    snackbarHostState.showSnackbar("Split applied")
                }
            }
        }
    }
    
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
                            navigation.navigateTo(NavigationDestination.Transactions())
                        },
                        onNavigateToAnalytics = { period ->
                            navigation.navigateTo(NavigationDestination.Analytics(initialPeriod = period))
                        },
                        onNavigateToMap = { location ->
                            navigation.navigateTo(NavigationDestination.SpendingMap(initialLocationQuery = location))
                        },
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
                        onNavigateToAnalytics = { navigation.navigateTo(NavigationDestination.Analytics()) },
                        onAddExpense = { navigation.navigateTo(NavigationDestination.AddExpense) },
                        onOpenVisualSplit = { expense ->
                            navigation.navigateTo(NavigationDestination.VisualSplitEditor.forExpense(expense))
                        },
                        highlightedExpenseId = (currentDestination as? NavigationDestination.Transactions)?.initialExpenseId,
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
                        initialPeriod = (currentDestination as? NavigationDestination.Analytics)?.initialPeriod,
                        onNavigateToTransactions = { filter ->
                            activeTransactionFilter = filter
                            navigation.navigateTo(NavigationDestination.Transactions())
                        }
                    )
                    5 -> SpendingMapScreen(
                        initialLocationQuery = (currentDestination as? NavigationDestination.SpendingMap)?.initialLocationQuery
                    )
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
                        initialShowCreateDialog = currentDestination.showCreateDialog,
                        onNavigateBack = { navigation.navigateBack() },
                        onCreateChallenge = { 
                            navigation.navigateTo(NavigationDestination.SpendingChallenges(showCreateDialog = true))
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
                            navigation.navigateTo(NavigationDestination.VisualSplitEditor.forTemplateCreation())
                        },
                        onEditTemplate = { template ->
                            // Navigate to editor with template ID for editing
                            navigation.navigateTo(NavigationDestination.VisualSplitEditor.forTemplateEdit(template.id))
                        }
                    )
                }
                is NavigationDestination.VisualSplitEditor -> {
                    VisualSplitEditorScreen(
                        totalAmount = currentDestination.resolvedExpenseAmount ?: 0.0,
                        currencyCode = currentDestination.resolvedExpenseCurrency ?: "EUR",
                        expenseId = currentDestination.resolvedExpenseId,
                        templateId = currentDestination.templateId,
                        onSplitComplete = { shares, splitType ->
                            val targetExpenseId = currentDestination.resolvedExpenseId
                            coroutineScope.launch {
                                val applied = if (targetExpenseId == null) {
                                    true
                                } else {
                                    runCatching {
                                        applyVisualSplitToExpense(
                                            expenseDao = expenseDao,
                                            gson = gson,
                                            expenseId = targetExpenseId,
                                            shares = shares,
                                            splitType = splitType,
                                            templateId = currentDestination.templateId
                                        )
                                    }.onFailure { error ->
                                        Timber.e(error, "Failed to apply visual split for expenseId=%s", targetExpenseId)
                                    }.getOrDefault(false)
                                }

                                if (!applied) {
                                    snackbarHostState.showSnackbar("Unable to apply split")
                                    return@launch
                                }

                                navigation.deliverResult(
                                    NavigationResult.VisualSplitApplied(
                                        expenseId = targetExpenseId,
                                        shares = shares,
                                        splitType = splitType
                                    )
                                )
                                navigation.navigateBack()
                            }
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
