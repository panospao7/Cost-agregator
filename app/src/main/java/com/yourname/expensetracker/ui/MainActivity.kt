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
                        onNavigateToRecurring = { showRecurringExpenses = true }
                    )
                    1 -> TransactionsScreen()
                    2 -> ReviewScreen()
                    3 -> BudgetScreen()
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
                    onNavigateBack = { showRecurringExpenses = false }
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
