package com.yourname.expensetracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yourname.expensetracker.ui.permissions.NotificationPromptPolicy
import com.yourname.expensetracker.ui.permissions.NotificationPromptStore
import com.yourname.expensetracker.ui.permissions.findHostActivity
import com.yourname.expensetracker.ui.permissions.openAppNotificationSettings
import androidx.activity.viewModels
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.backup.AppOperationalState
import com.yourname.expensetracker.data.backup.RestoreRestartSignal
import com.yourname.expensetracker.data.database.DatabaseOpenGate
import com.yourname.expensetracker.ui.unsupported.UnsupportedDatabaseActivity
import com.yourname.expensetracker.domain.privacy.applock.AppLockController
import com.yourname.expensetracker.ui.applock.AppLockDeepLinkRouter
import com.yourname.expensetracker.ui.applock.AppLockLifecycleBinder
import com.yourname.expensetracker.ui.applock.AppLockPromptHost
import com.yourname.expensetracker.ui.applock.AppLockShell
import com.yourname.expensetracker.ui.applock.NotificationPermissionPromptOnce
import com.yourname.expensetracker.ui.applock.SecureWindowController
import kotlinx.coroutines.flow.first
import com.yourname.expensetracker.data.database.entity.Budget as BudgetEntity
import com.yourname.expensetracker.data.repository.ExpenseRepository
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
import com.yourname.expensetracker.ui.screens.home.HomeRoute
import com.yourname.expensetracker.ui.screens.investment.InvestmentPortfolioScreen
import com.yourname.expensetracker.ui.screens.lifestyle.LifestyleInflationScreen
import com.yourname.expensetracker.ui.screens.map.SpendingMapScreen
import com.yourname.expensetracker.ui.screens.negotiation.BillNegotiationScreen
import com.yourname.expensetracker.ui.screens.naturallanguage.NaturalLanguageSearchScreen
import com.yourname.expensetracker.ui.screens.price.PriceProtectionScreen
import com.yourname.expensetracker.ui.screens.receiptmatching.ReceiptMatchingRoute
import com.yourname.expensetracker.ui.screens.reminder.BillRemindersScreen
import com.yourname.expensetracker.ui.screens.review.ReviewScreen
import com.yourname.expensetracker.ui.screens.savings.SavingsGoalsScreen
import com.yourname.expensetracker.ui.screens.split.SplitTemplatesScreen
import com.yourname.expensetracker.ui.screens.split.VisualSplitEditorScreen
import com.yourname.expensetracker.ui.screens.transactions.TransactionsScreen
import com.yourname.expensetracker.ui.screens.warranty.WarrantyTrackerScreen
import com.yourname.expensetracker.ui.screens.currency.CurrencyManagementScreen
import com.yourname.expensetracker.ui.screens.backup.BackupRestoreScreen
import com.yourname.expensetracker.ui.screens.export.ExportOptionsScreen
import com.yourname.expensetracker.ui.screens.groups.SharedExpenseGroupsScreen
import com.yourname.expensetracker.ui.screens.recurring.RecurringExpensesScreen
import com.yourname.expensetracker.ui.screens.recurringmanual.ManualRecurringExpenseScreen
import com.yourname.expensetracker.ui.screens.installments.detail.InstallmentPlanDetailScreen
import com.yourname.expensetracker.ui.screens.installments.history.InstallmentHistoryScreen
import com.yourname.expensetracker.ui.screens.installments.overview.InstallmentOverviewScreen
import com.yourname.expensetracker.ui.navigation.InstallmentsFeature
import com.yourname.expensetracker.ui.screens.subscription.SubscriptionManagementScreen
import com.yourname.expensetracker.ui.screens.tax.TaxConfigurationScreen
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.navigation.DeepLinkPolicy
import com.yourname.expensetracker.ui.navigation.DeepLinkPolicyResult
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import com.yourname.expensetracker.ui.navigation.NavigationResult
import com.yourname.expensetracker.ui.navigation.ProvideNavigationController
import com.yourname.expensetracker.ui.navigation.LocalNavigationController
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import com.yourname.expensetracker.ui.util.ClipboardAmountParser
import com.yourname.expensetracker.ui.util.HapticType
import com.yourname.expensetracker.ui.util.rememberHapticFeedback
import com.yourname.expensetracker.ui.util.restartApp
import dagger.hilt.android.AndroidEntryPoint
import com.yourname.expensetracker.domain.transaction.lifecycle.GroupLinkedExpenseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    /** S21-b: routes validated deep links through the app-lock controller (INV-14..INV-22, PRV-16); unscoped, one per Activity. */
    @Inject
    lateinit var deepLinkRouter: AppLockDeepLinkRouter

    @Inject
    lateinit var actionRegistry: ContextualActionRegistry

    @Inject
    lateinit var expenseRepository: ExpenseRepository

    @Inject
    lateinit var gson: Gson

    /** R27-S3: in-memory (non-persisted) automatic-restart request after an in-process restore. */
    @Inject
    lateinit var restoreRestartSignal: RestoreRestartSignal

    /** S20-c: the process-wide app-lock controller (state and notice feed the shell). */
    @Inject
    lateinit var appLockController: AppLockController

    /** S20-c: sole INV-25 lifecycle signal and the owner of `resolve()`; installed once per process. */
    @Inject
    lateinit var appLockLifecycleBinder: AppLockLifecycleBinder

    /** S20-c: [AppLockPromptHost] is unscoped (one per Activity instance), so it is injected as a provider. */
    @Inject
    lateinit var appLockPromptHostProvider: Provider<AppLockPromptHost>

    /** S20-c: the single FLAG_SECURE writer. */
    @Inject
    lateinit var secureWindowController: SecureWindowController

    /** S20-c: caps the POST_NOTIFICATIONS auto-prompt to once per process (AE-39). */
    @Inject
    lateinit var notificationPromptOnce: NotificationPermissionPromptOnce

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // S20-c / INV-29: the window is secure before anything else can draw (synchronous add; never cleared here).
        secureWindowController.applyInitial(window)
        // R25 B2: a blocked live database (too old / too new / unreadable) must never reach the
        // normal UI; redirect to the plain export-only screen before any intent handling.
        if (DatabaseOpenGate.blockedReasonOrNull(applicationContext) != null) {
            startActivity(
                Intent(this, UnsupportedDatabaseActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }
        // S20-c: install the lifecycle binder before any other in-process Activity can start (S20-a F4; Activities
        // started before this point are not counted), attach this Activity's prompt host, then bind the secure
        // window once for this Activity instance (never from onNewIntent).
        appLockLifecycleBinder.install(application)
        appLockPromptHostProvider.get().attach(this)
        secureWindowController.bind(this)
        deepLinkRouter.startReleasing(this, mainViewModel)
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
                        AppLockShell(
                            controller = appLockController,
                            operationalState = mainViewModel.operationalState,
                            navigateTo = mainViewModel::navigateTo
                        ) {
                            MainScreen(
                                mainViewModel = mainViewModel,
                                actionRegistry = actionRegistry,
                                expenseRepository = expenseRepository,
                                gson = gson,
                                restoreRestartSignal = restoreRestartSignal,
                                notificationPromptOnce = notificationPromptOnce
                            )
                        }
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

    /**
     * Deep-link entry point (INV-14). Every `expensetracker:` intent, internal notification taps
     * included, is untrusted: it is evaluated only by [DeepLinkPolicy] (pure, no IO) and acted on only
     * by the deep-link applier. This function never parses or logs the link, so no raw URI, host or
     * parameter value can reach a log or a diagnostic (INV-18; closes advisory C4).
     *
     * PRV-16 (S21-b): the link goes only through [AppLockDeepLinkRouter]. While the app is locked, resolving or
     * waiting for a prompt, the app-lock controller holds the link in memory and the router releases it once the
     * app is open again (spec 5.5); only an open app applies it now.
     *
     * @return true when the intent carried an `expensetracker:` link (even a rejected one), so the
     * caller consumes the data one-shot; false when it was not a link at all.
     */
    private fun handleIntent(intent: Intent?): Boolean {
        val result = DeepLinkPolicy.evaluate(intent?.action, intent?.dataString)
        if (result is DeepLinkPolicyResult.NotADeepLink) return false

        deepLinkRouter.route(result, lifecycleScope, mainViewModel)
        return true
    }
}

private data class PersistedVisualSplit(
    val splitType: SplitTemplate.SplitType,
    val shares: List<SplitShare>
)

private suspend fun applyVisualSplitToExpense(
    expenseRepository: ExpenseRepository,
    gson: Gson,
    expenseId: Long,
    shares: List<SplitShare>,
    splitType: SplitTemplate.SplitType,
    templateId: Long?
): Boolean {
    val expense = expenseRepository.getExpenseById(expenseId) ?: return false
    val sanitizedShares = shares
        .map { it.copy(participantName = it.participantName.trim()) }
        .sortedBy { it.participantIndex }

    if (sanitizedShares.isEmpty()) return false

    val myShare = sanitizedShares.first()
    val sharedWithName = sanitizedShares
        .drop(1)
        .joinToString(", ") { it.participantName }
        .takeIf { it.isNotBlank() }

    // G02-FIXED: Route shared ownership writes through normalizeOwnership()
    // to enforce mutual exclusivity (isNotMine vs isSharedExpense).
    val updatedExpense = expense.copy(
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
    ).normalizeOwnership()  // G02: enforce ownership invariants before persistence

    expenseRepository.updateExpense(updatedExpense)
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    actionRegistry: ContextualActionRegistry,
    expenseRepository: ExpenseRepository,
    gson: Gson,
    restoreRestartSignal: RestoreRestartSignal,
    notificationPromptOnce: NotificationPermissionPromptOnce
) {
    // RP-17 17-A / D1: availability resolved here (same seam as
    // NavigationController/HomeScreen) — the Activity's injected instance is
    // not in scope inside this top-level composable.
    val bankSyncAvailable = remember { com.yourname.expensetracker.domain.bank.BankFeatureAvailability().isBankSyncAvailable }
    // R19 (H-1 hide): Tax / Investment / Challenges are hidden in release builds.
    val deferredFeaturesAvailable = com.yourname.expensetracker.ui.navigation.DeferredFeatureGate.available
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
    val visualSplitDestination = currentDestination as? NavigationDestination.VisualSplitEditor
    var restoredVisualSplitExpense by remember(visualSplitDestination?.resolvedExpenseId) {
        mutableStateOf(visualSplitDestination?.expense)
    }

    LaunchedEffect(visualSplitDestination?.resolvedExpenseId, visualSplitDestination?.expense) {
        restoredVisualSplitExpense = visualSplitDestination?.expense
            ?: visualSplitDestination?.resolvedExpenseId?.let { expenseRepository.getExpenseById(it) }
    }

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
    
    // ── Global operational lock (restore / critical recovery) ───────
    val operationalState by mainViewModel.operationalState.collectAsState()
    when (val state = operationalState) {
        is AppOperationalState.RestartRequiredAfterRestore -> {
            // R27-S3: automatic relaunch only for a restore that completed in THIS process
            // (in-memory signal). A cold start that merely reads the persisted
            // restart-required mode sees the flag false -> no restart loop. Waiting on the
            // StateFlow (instead of a one-time check) also covers the lock screen being
            // composed just before the restore result marks the signal.
            LaunchedEffect(Unit) {
                restoreRestartSignal.requested.first { it }
                restartApp(context)
            }
            AppOperationalLockScreen(
                title = "Restart Required",
                message = stringResource(R.string.restart_required_database_operation),
                actionLabel = "Restart Now",
                onAction = { restartApp(context) }
            )
            return
        }
        is AppOperationalState.CriticalRecoveryRequired -> {
            val msg = buildString {
                append("The database is in an unrecoverable state.")
                if (state.reason != null) append("\n\nReason: ${state.reason}")
                append("\n\nPlease restart the app. If the problem persists, contact support.")
            }
            AppOperationalLockScreen(
                title = "Critical Recovery Required",
                message = msg,
                actionLabel = "OK",
                onAction = { /* no-op: user must intervene */ }
            )
            return
        }
        is AppOperationalState.RestoreInProgress -> {
            AppOperationalLockScreen(
                title = "Restore In Progress",
                message = "A database restore is in progress (${state.mode.label}). Please wait…",
                actionLabel = "Please Wait",
                onAction = { /* non-dismissable */ }
            )
            return
        }
        else -> { /* Normal / BackupExporting — app continues */ }
    }

    // R24b: ask once per install (max 2 dialogs, no loop). If the system request is denied and the
    // system will not (or no longer will) show its dialog, the in-app dialog is shown ONE more time
    // in "open settings" mode; that mode's button only opens settings and never relaunches the request.
    var notificationOpenSettings by rememberSaveable { mutableStateOf(false) }
    var notificationSettingsOfferUsed by rememberSaveable { mutableStateOf(false) }
    // Default true (conservative): if lost across process death, no settings offer is made.
    var notificationRationaleBefore by rememberSaveable { mutableStateOf(true) }
    val notificationPromptStore = remember(context) {
        NotificationPromptStore(context.applicationContext)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val rationaleAfter = notificationRationaleNow(context)
        if (!notificationSettingsOfferUsed &&
            NotificationPromptPolicy.shouldOfferSettingsAfterDenial(
                rationaleBefore = notificationRationaleBefore,
                rationaleAfter = rationaleAfter,
                granted = isGranted
            )
        ) {
            notificationSettingsOfferUsed = true
            notificationOpenSettings = true
            showNotificationPermissionDialog = true
        }
    }

    LaunchedEffect(Unit) {
        // S20-c / AE-39: at most one auto-prompt decision per process, however often the shell re-enters.
        if (!notificationPromptOnce.tryClaim()) return@LaunchedEffect
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (NotificationPromptPolicy.shouldAutoPrompt(
                granted = granted,
                sdkInt = Build.VERSION.SDK_INT,
                alreadyAsked = notificationPromptStore.hasPrompted()
            )
        ) {
            // Flag is set when the prompt is shown, so it can never be shown automatically twice.
            notificationPromptStore.markPrompted()
            showNotificationPermissionDialog = true
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

    // Loan S13-a (C1): an installments deep link never navigates by itself. MainScreen is composed inside the
    // app-lock shell and the request is posted only after the release, so this is never visible while locked.
    // The body names no plan, amount or link content: the link is untrusted.
    val pendingDeepLink by mainViewModel.pendingDeepLinkConfirmation.collectAsState()
    if (pendingDeepLink != null) {
        AlertDialog(
            onDismissRequest = { mainViewModel.dismissPendingDeepLink() },
            title = { Text(stringResource(R.string.deeplink_confirm_installments_title)) },
            text = { Text(stringResource(R.string.deeplink_confirm_installments_body)) },
            confirmButton = {
                TextButton(onClick = { mainViewModel.confirmPendingDeepLink() }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { mainViewModel.dismissPendingDeepLink() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    NotificationPermissionDialog(
        showDialog = showNotificationPermissionDialog,
        onDismiss = { showNotificationPermissionDialog = false },
        onEnable = {
            if (notificationOpenSettings) {
                openAppNotificationSettings(context)
            } else {
                notificationRationaleBefore = notificationRationaleNow(context)
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        openSettings = notificationOpenSettings
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
                    0 -> HomeRoute(
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
                        // Internal tap: no deep-link confirmation.
                        onOpenInstallmentPlan = { navigation.navigateTo(NavigationDestination.InstallmentPlanDetail(it)) },
                        highlightedExpenseId = (currentDestination as? NavigationDestination.Transactions)?.initialExpenseId,
                        initialFilter = activeTransactionFilter
                    )
                    2 -> ReviewScreen()
                    3 -> BudgetScreen(
                        initialCategoryId = (currentDestination as? NavigationDestination.BudgetDetail)?.categoryId,
                        initialCategoryName = (currentDestination as? NavigationDestination.BudgetDetail)?.categoryName,
                        // S8-007: BudgetCreate opens the add dialog immediately
                        initialOpenCreateDialog = currentDestination is NavigationDestination.BudgetCreate,
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
                        initialAmount = initialAmount,
                        onOpenExchangeRateSetting = { navigation.navigateTo(NavigationDestination.PrivacySettings) }
                    )
                }
                is NavigationDestination.ScanReceipt -> {
                    com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanRoute(
                        onDismiss = { navigation.navigateBack() },
                        onOpenExchangeRateSetting = { navigation.navigateTo(NavigationDestination.PrivacySettings) }
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
                // loan-installments S10a/S13-b: gated by InstallmentsFeature.ENABLED (the single switch).
                // If it is false none of the installment destinations is rendered; they redirect Home.
                is NavigationDestination.Installments -> {
                    if (InstallmentsFeature.shouldRedirectHome(currentDestination)) {
                        androidx.compose.runtime.LaunchedEffect(Unit) { navigation.navigateHome() }
                    } else {
                        InstallmentOverviewScreen(
                            onNavigateBack = { navigation.navigateBack() },
                            onOpenPlan = { planId ->
                                navigation.navigateTo(NavigationDestination.InstallmentPlanDetail(planId))
                            },
                            onOpenHistory = { navigation.navigateTo(NavigationDestination.InstallmentHistory) }
                        )
                    }
                }
                // Plan detail and history (S10a-2): same gate helper as the overview, so all three
                // redirect Home if InstallmentsFeature.ENABLED is false.
                is NavigationDestination.InstallmentPlanDetail -> {
                    if (InstallmentsFeature.shouldRedirectHome(currentDestination)) {
                        androidx.compose.runtime.LaunchedEffect(Unit) { navigation.navigateHome() }
                    } else {
                        InstallmentPlanDetailScreen(
                            planId = currentDestination.planId,
                            onNavigateBack = { navigation.navigateBack() }
                        )
                    }
                }
                is NavigationDestination.InstallmentHistory -> {
                    if (InstallmentsFeature.shouldRedirectHome(currentDestination)) {
                        androidx.compose.runtime.LaunchedEffect(Unit) { navigation.navigateHome() }
                    } else {
                        InstallmentHistoryScreen(
                            onNavigateBack = { navigation.navigateBack() },
                            onOpenPlan = { planId ->
                                navigation.navigateTo(NavigationDestination.InstallmentPlanDetail(planId))
                            }
                        )
                    }
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
                is NavigationDestination.PrivacySettings -> {
                    com.yourname.expensetracker.ui.screens.privacysettings.PrivacySettingsScreen(
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
                    ReceiptMatchingRoute(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.InvestmentPortfolio -> {
                    // R19 (H-1 hide): deferred feature is never rendered in release.
                    if (deferredFeaturesAvailable) {
                        InvestmentPortfolioScreen(
                            onNavigateBack = { navigation.navigateBack() },
                            onAddInvestment = {
                                // Show "Coming soon" since add investment flow not implemented
                                navigation.navigateBack()
                            }
                        )
                    } else {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            navigation.navigateHome()
                        }
                    }
                }
                is NavigationDestination.BankConnections -> {
                    // RP-17 17-A: release builds never render the bank screen, never
                    // silently render empty, and never enter integration — the gated
                    // destination is replaced by navigating back to the previous
                    // screen (restore path already maps the token to Home).
                    if (bankSyncAvailable) {
                        BankConnectionsScreen(
                            onNavigateBack = { navigation.navigateBack() },
                            onAddConnection = {
                                // Show "Coming soon" since add bank connection flow not implemented
                                navigation.navigateBack()
                            }
                        )
                    } else {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            navigation.navigateHome()
                        }
                    }
                }
                is NavigationDestination.BillReminders -> {
                    BillRemindersScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.SpendingChallenges -> {
                    // R19 (H-1 hide): deferred feature is never rendered in release.
                    if (deferredFeaturesAvailable) {
                        SpendingChallengesScreen(
                            initialShowCreateDialog = currentDestination.showCreateDialog,
                            onNavigateBack = { navigation.navigateBack() },
                            onCreateChallenge = {
                                navigation.navigateTo(NavigationDestination.SpendingChallenges(showCreateDialog = true))
                            },
                            actionRegistry = actionRegistry
                        )
                    } else {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            navigation.navigateHome()
                        }
                    }
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
                    val editorExpense = currentDestination.expense ?: restoredVisualSplitExpense
                    VisualSplitEditorScreen(
                        totalAmount = editorExpense?.amount ?: currentDestination.resolvedExpenseAmount ?: 0.0,
                        currencyCode = editorExpense?.currency ?: currentDestination.resolvedExpenseCurrency ?: "EUR",
                        expenseId = currentDestination.resolvedExpenseId,
                        templateId = currentDestination.templateId,
                        onSplitComplete = { shares, splitType ->
                            val targetExpenseId = currentDestination.resolvedExpenseId
                            coroutineScope.launch {
                                // R18: a group-linked expense's share split is a money edit and is
                                // blocked by the lifecycle coordinator; show the bounded guidance.
                                var blockedGroupLinked = false
                                val applied = if (targetExpenseId == null) {
                                    true
                                } else {
                                    try {
                                        applyVisualSplitToExpense(
                                            expenseRepository = expenseRepository,
                                            gson = gson,
                                            expenseId = targetExpenseId,
                                            shares = shares,
                                            splitType = splitType,
                                            templateId = currentDestination.templateId
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (error: GroupLinkedExpenseException) {
                                        blockedGroupLinked = true
                                        Timber.w("Visual split blocked code=%s", error.code)
                                        false
                                    } catch (error: Exception) {
                                        Timber.e(error, "Failed to apply visual split for expenseId=%s", targetExpenseId)
                                        false
                                    }
                                }

                                if (!applied) {
                                    if (blockedGroupLinked) {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.expense_edit_blocked_group_linked)
                                        )
                                        return@launch
                                    }
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
                    // R19 (H-1 hide): deferred feature is never rendered in release.
                    if (deferredFeaturesAvailable) {
                        TaxConfigurationScreen(
                            onNavigateBack = { navigation.navigateBack() }
                        )
                    } else {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            navigation.navigateHome()
                        }
                    }
                }
                is NavigationDestination.ExportOptions -> {
                    ExportOptionsScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }
                is NavigationDestination.BackupRestore -> {
                    BackupRestoreScreen(
                        onNavigateBack = { navigation.navigateBack() },
                        onRestartRequired = { restartApp(context) }
                    )
                }
                is NavigationDestination.SharedExpenseGroups -> {
                    SharedExpenseGroupsScreen(
                        onNavigateBack = { navigation.navigateBack() }
                    )
                }

                is NavigationDestination.Debug -> {
                    if (com.yourname.expensetracker.BuildConfig.DEBUG) {
                        com.yourname.expensetracker.ui.screens.debug.DebugScreen(
                            onDismiss = { navigation.navigateBack() }
                        )
                    } else {
                        androidx.compose.runtime.LaunchedEffect(Unit) { navigation.navigateBack() }
                    }
                }
                
                // Main tabs handled by AnimatedContent above
                is NavigationDestination.Home,
                is NavigationDestination.Transactions,
                is NavigationDestination.Review,
                is NavigationDestination.Budget,
                is NavigationDestination.BudgetCreate,
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
    
    // WRK-13: The DisposableEffect key ensures the observer is added once
    // per lifecycleOwner instance. Compose guarantees DisposableEffect's
    // onDispose runs when the effect leaves composition or the key changes,
    // so the observer is properly cleaned up. No additional initialized guard
    // is needed because DisposableEffect itself is idempotent — re-running
    // with the same key is a no-op in the Compose runtime.
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

private fun notificationRationaleNow(context: android.content.Context): Boolean =
    context.findHostActivity()?.let {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS)
    } ?: false

@Composable
private fun AppOperationalLockScreen(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AlertDialog(
            onDismissRequest = { /* intentionally non-dismissable */ },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        )
    }
}
