package com.yourname.expensetracker.ui.screens.debug

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.BuildConfig
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.model.routeDisplayText
import com.yourname.expensetracker.domain.ai.model.toRuntimeStatusMessage
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.R
import com.yourname.expensetracker.service.debug.MigrationResult
import kotlinx.coroutines.launch
import java.util.*

private enum class DebugDestructiveAction {
    CLEAR_ALL,
    RESET_EXPENSES,
    RESET_BUDGETS,
    RESET_SOURCE_STATS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onDismiss: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel()
) {
    if (!BuildConfig.DEBUG) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.debug_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.debug_disabled_in_release),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val notifications by viewModel.filteredNotifications.collectAsState()
    val count by viewModel.notificationCount.collectAsState()
    val packages by viewModel.packages.collectAsState()
    val selectedFilter by viewModel.selectedPackageFilter.collectAsState()
    val aiRuntimeStatuses by viewModel.aiRuntimeStatuses.collectAsState()
    val aiRuntimeMeta by viewModel.aiRuntimeMeta.collectAsState()
    val aiRuntimeEvents by viewModel.aiRuntimeEvents.collectAsState()
    val aiEngagementState by viewModel.aiEngagementState.collectAsState()
    val aiSettings by viewModel.aiSettings.collectAsState()
    
    var expandedNotificationId by remember { mutableStateOf<Long?>(null) }
    var diagnosticsStats by remember { mutableStateOf(viewModel.getServiceDiagnostics()) }
    var showCategorizationDebug by remember { mutableStateOf(false) }
    var pendingConfirmationAction by remember { mutableStateOf<DebugDestructiveAction?>(null) }

    if (showCategorizationDebug) {
        CategorizationDebugScreen(onNavigateBack = { showCategorizationDebug = false })
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title_format, count)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { pendingConfirmationAction = DebugDestructiveAction.CLEAR_ALL }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_clear))
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
                    Text(stringResource(R.string.debug_open_notification_settings))
                }
            }

            // Database Management Section
            item {
                DatabaseManagementSection(viewModel)
            }

            // 1.5 Service Diagnostics
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.debug_section_service_diagnostics),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Row {
                                TextButton(
                                    onClick = { diagnosticsStats = viewModel.getServiceDiagnostics() }
                                ) {
                                    Text(stringResource(R.string.debug_refresh), fontSize = 14.sp)
                                }
                                TextButton(
                                    onClick = { 
                                        viewModel.resetServiceDiagnostics()
                                        diagnosticsStats = viewModel.getServiceDiagnostics()
                                    }
                                ) {
                                    Text(stringResource(R.string.debug_reset), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${diagnosticsStats.startCount}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50)
                                )
                                Text(stringResource(R.string.debug_status_starts), style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${diagnosticsStats.disconnectCount}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFFC107)
                                )
                                Text(stringResource(R.string.debug_status_disconnects), style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${diagnosticsStats.killedCount}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF44336)
                                )
                                Text(stringResource(R.string.debug_status_killed), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        
                        if (diagnosticsStats.lastRestartTime > 0 || diagnosticsStats.lastKillTime > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    stringResource(R.string.debug_last_start, 
                                        if (diagnosticsStats.lastRestartTime > 0) 
                                            DateFormatterUtils.formatTimestampJavaTime(diagnosticsStats.lastRestartTime, "HH:mm:ss dd/MM")
                                        else stringResource(R.string.debug_status_active)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp
                                )
                                Text(
                                    stringResource(R.string.debug_last_kill,
                                        if (diagnosticsStats.lastKillTime > 0) 
                                            DateFormatterUtils.formatTimestampJavaTime(diagnosticsStats.lastKillTime, "HH:mm:ss dd/MM")
                                        else stringResource(R.string.debug_status_active)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    color = if (diagnosticsStats.lastKillTime > diagnosticsStats.lastRestartTime) 
                                        Color(0xFFF44336) else Color.Unspecified
                                )
                            }
                        }
                    }
                }
            }

            // 1.6 AI Runtime Diagnostics
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        Text(
                            stringResource(R.string.debug_section_ai_runtime),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = viewModel::refreshAiRuntimeStatuses) {
                            Text(stringResource(R.string.debug_refresh))
                        }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            stringResource(R.string.debug_network_available),
                            style = MaterialTheme.typography.bodySmall
                        )
                        DebugRuntimeGuidance(aiSettings, aiRuntimeStatuses.values.any { it != OnDeviceModelStatus.AVAILABLE })
                        Text(
                            stringResource(R.string.debug_network_connected),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (aiRuntimeMeta.lastRefreshedAt > 0L) {
                            Text(
                                stringResource(R.string.debug_last_refreshed, DateFormatterUtils.formatTimestampJavaTime(aiRuntimeMeta.lastRefreshedAt, "HH:mm:ss dd/MM")),
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 10.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        aiRuntimeStatuses.forEach { (capability, status) ->
                            val runtime = aiRuntimeMeta.capabilities.firstOrNull { it.capability == capability }
                            val runtimeMessage = runtime?.message ?: status.toRuntimeStatusMessage(capability.debugRuntimeLabel())
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(capability.name, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = status.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (status) {
                                        OnDeviceModelStatus.AVAILABLE -> Color(0xFF4CAF50)
                                        OnDeviceModelStatus.DOWNLOADING -> Color(0xFFFFC107)
                                        OnDeviceModelStatus.NOT_INSTALLED,
                                        OnDeviceModelStatus.UNAVAILABLE,
                                        OnDeviceModelStatus.UNSUPPORTED_DEVICE,
                                        OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION,
                                        OnDeviceModelStatus.DISABLED_BY_POLICY,
                                        OnDeviceModelStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            runtime?.routeDisplayText()?.let { routeText ->
                                Text(
                                    text = routeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            runtimeMessage?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                debugCloudFallbackHint(aiSettings, capability, status, runtime)?.let { hint ->
                                    Text(
                                        text = hint,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }

                        if (aiRuntimeEvents.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.debug_recent_ai_events),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            aiRuntimeEvents.take(8).forEach { event ->
                                Text(
                                    text = "${DateFormatterUtils.formatTimestampJavaTime(event.timestamp, "HH:mm:ss dd/MM")} • ${event.type} • ${event.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }

                        if (aiSettings.proactiveBriefingsEnabled ||
                            aiEngagementState.lastDeliveredDashboardBriefingKey != null ||
                            aiEngagementState.lastOpenedDashboardBriefingKey != null
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.debug_phase_4a_rollout),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = buildPhase4aDebugSummary(aiSettings, aiEngagementState),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
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
                            stringResource(R.string.debug_section_mass_simulation),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(stringResource(R.string.debug_quantity_format, simulationCount.toInt()))
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
                                Text(stringResource(R.string.debug_generate_transactions, simulationCount.toInt()))
                            }
                        }
                    }
                }
            }

            // 3. Test & Sync Buttons
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Button(
                        onClick = { showCategorizationDebug = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.debug_categorization_pipeline))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.simulateTestNotification() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text(stringResource(R.string.debug_simulate_purchase))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.simulateDepositNotification() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(stringResource(R.string.debug_simulate_deposit))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.triggerManualSync(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text(stringResource(R.string.debug_sync_notifications))
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { pendingConfirmationAction = DebugDestructiveAction.RESET_EXPENSES },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.debug_reset_expenses))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { pendingConfirmationAction = DebugDestructiveAction.RESET_BUDGETS },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    ) {
                        Text(stringResource(R.string.debug_reset_budgets))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { pendingConfirmationAction = DebugDestructiveAction.RESET_SOURCE_STATS },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(stringResource(R.string.debug_reset_trust_scores))
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
                                label = { Text(stringResource(R.string.debug_filter_all)) }
                            )
                        }
                        items(packages, key = { it }) { pkg ->
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
                        text = stringResource(R.string.debug_blocked_apps),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(blockedApps, key = { it.packageName }) { blocked ->
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
                                    contentDescription = stringResource(R.string.debug_notification_unblock),
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
                            Text(stringResource(R.string.debug_notification_no_notifications))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.debug_notification_access_required),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.debug_captured_notifications_count, notifications.size),
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

    if (pendingConfirmationAction != null) {
        val action = pendingConfirmationAction!!
        val title = when (action) {
            DebugDestructiveAction.CLEAR_ALL -> stringResource(R.string.debug_confirm_clear_all_title)
            DebugDestructiveAction.RESET_EXPENSES -> stringResource(R.string.debug_confirm_reset_expenses_title)
            DebugDestructiveAction.RESET_BUDGETS -> stringResource(R.string.debug_confirm_reset_budgets_title)
            DebugDestructiveAction.RESET_SOURCE_STATS -> stringResource(R.string.debug_confirm_reset_trust_scores_title)
        }
        val message = when (action) {
            DebugDestructiveAction.CLEAR_ALL -> stringResource(R.string.debug_confirm_clear_all_message)
            DebugDestructiveAction.RESET_EXPENSES -> stringResource(R.string.debug_confirm_reset_expenses_message)
            DebugDestructiveAction.RESET_BUDGETS -> stringResource(R.string.debug_confirm_reset_budgets_message)
            DebugDestructiveAction.RESET_SOURCE_STATS -> stringResource(R.string.debug_confirm_reset_trust_scores_message)
        }

        AlertDialog(
            onDismissRequest = { pendingConfirmationAction = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingConfirmationAction = null
                        scope.launch {
                            when (action) {
                                DebugDestructiveAction.CLEAR_ALL -> {
                                    val hadData = viewModel.clearAllWithUndoSupport()
                                    if (hadData) {
                                        val result = snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.debug_action_clear_all_done),
                                            actionLabel = context.getString(R.string.action_undo),
                                            withDismissAction = true,
                                            duration = SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.undoClearAll()
                                        }
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.debug_action_no_data_to_clear))
                                    }
                                }
                                DebugDestructiveAction.RESET_EXPENSES -> {
                                    val hadData = viewModel.resetExpensesWithUndoSupport()
                                    if (hadData) {
                                        val result = snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.debug_action_reset_expenses_done),
                                            actionLabel = context.getString(R.string.action_undo),
                                            withDismissAction = true,
                                            duration = SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.undoResetExpenses()
                                        }
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.debug_action_no_expenses_to_reset))
                                    }
                                }
                                DebugDestructiveAction.RESET_BUDGETS -> {
                                    val hadData = viewModel.resetBudgetsWithUndoSupport()
                                    if (hadData) {
                                        val result = snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.debug_action_reset_budgets_done),
                                            actionLabel = context.getString(R.string.action_undo),
                                            withDismissAction = true,
                                            duration = SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.undoResetBudgets()
                                        }
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.debug_action_no_budgets_to_reset))
                                    }
                                }
                                DebugDestructiveAction.RESET_SOURCE_STATS -> {
                                    val hadData = viewModel.resetSourceStatsWithUndoSupport()
                                    if (hadData) {
                                        val result = snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.debug_action_reset_trust_scores_done),
                                            actionLabel = context.getString(R.string.action_undo),
                                            withDismissAction = true,
                                            duration = SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.undoResetSourceStats()
                                        }
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.debug_action_no_trust_scores_to_reset))
                                    }
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmationAction = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun DebugRuntimeGuidance(
    aiSettings: AiSettings,
    hasRuntimeAttention: Boolean
) {
    val message = debugRuntimeGuidanceText(aiSettings, hasRuntimeAttention)
    if (message != null) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

internal fun debugRuntimeGuidanceText(
    aiSettings: AiSettings,
    hasRuntimeAttention: Boolean
): String? = when {
    aiSettings.aiEnabled && aiSettings.allowCloudAi && hasRuntimeAttention -> {
        "Cloud AI is enabled, so advisory features can still run when on-device AI is unavailable."
    }
    aiSettings.aiEnabled && aiSettings.allowCloudAi -> {
        "Cloud AI is enabled for advisory features."
    }
    else -> null
}

internal fun debugCloudFallbackHint(
    aiSettings: AiSettings,
    capability: AiCapability,
    status: OnDeviceModelStatus,
    runtime: com.yourname.expensetracker.domain.ai.model.AiCapabilityRuntimeStatus? = null
): String? {
    if (!aiSettings.aiEnabled || !aiSettings.allowCloudAi || status == OnDeviceModelStatus.AVAILABLE) {
        return null
    }
    if (!capability.supportsCloudFallback()) {
        return null
    }
    if (runtime?.route == com.yourname.expensetracker.domain.ai.model.AiRoute.CLOUD) {
        return null
    }
    return "Cloud fallback available for advisory AI"
}

internal fun buildPhase4aDebugSummary(
    aiSettings: AiSettings,
    engagementState: com.yourname.expensetracker.domain.ai.model.AiEngagementState
): String = buildString {
    appendLine("proactiveBriefingsEnabled=${aiSettings.proactiveBriefingsEnabled}")
    appendLine("receiptQuickSaveEnabled=${aiSettings.receiptQuickSaveEnabled}")
    appendLine("reviewQuickApproveEnabled=${aiSettings.reviewQuickApproveEnabled}")
    appendLine("lastDeliveredBriefing=${engagementState.lastDeliveredDashboardBriefingKey ?: "none"}")
    append("lastOpenedBriefing=${engagementState.lastOpenedDashboardBriefingKey ?: "none"}")
}

private fun AiCapability.supportsCloudFallback(): Boolean = when (this) {
    AiCapability.DASHBOARD_BRIEFING,
    AiCapability.REVIEW_EXPLANATION,
    AiCapability.QUERY_INTERPRETATION,
    AiCapability.RECEIPT_EXTRACTION,
    AiCapability.WARRANTY_EXTRACTION,
    AiCapability.RECEIPT_ITEM_CATEGORIZATION,
    AiCapability.CATEGORIZATION_FALLBACK,
    AiCapability.DEDUPE_JUDGE -> true
    AiCapability.LOCATION_SUMMARY,
    AiCapability.NOTIFICATION_PARSE -> false // On-device only
    AiCapability.REVIEW_PRIORITIZATION -> false // On-device only
    AiCapability.SEMANTIC_DEDUPE -> false // On-device only
}

private fun AiCapability.debugRuntimeLabel(): String = when (this) {
    AiCapability.DASHBOARD_BRIEFING -> "briefing"
    AiCapability.REVIEW_EXPLANATION -> "review explanations"
    AiCapability.QUERY_INTERPRETATION -> "AI"
    AiCapability.RECEIPT_EXTRACTION -> "receipt assist"
    AiCapability.WARRANTY_EXTRACTION -> "warranty extraction"
    AiCapability.CATEGORIZATION_FALLBACK -> "categorization"
    AiCapability.DEDUPE_JUDGE -> "duplicate detection"
    AiCapability.LOCATION_SUMMARY -> "location summaries"
    AiCapability.NOTIFICATION_PARSE -> "notification parsing"
    AiCapability.REVIEW_PRIORITIZATION -> "review prioritization"
    AiCapability.SEMANTIC_DEDUPE -> "semantic duplicate detection"
    AiCapability.RECEIPT_ITEM_CATEGORIZATION -> "receipt item categorization"
}

@Composable
fun NotificationCard(
    notification: RawNotification,
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
                    text = DateFormatterUtils.formatTimestampJavaTime(notification.capturedAt, "HH:mm:ss dd/MM"),
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
                    text = stringResource(R.string.debug_notification_package, notification.packageName),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )
                
                // SubText if present
                notification.subText?.let {
                    Text(
                        text = stringResource(R.string.debug_notification_subtext, it),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                
                // Extras JSON
                notification.extrasJson?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.debug_notification_extras),
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
                        label = { Text(stringResource(R.string.debug_notification_expense), fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                        }
                    )
                    AssistChip(
                        onClick = onMarkIrrelevant,
                        label = { Text(stringResource(R.string.debug_notification_ignore), fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                        }
                    )
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    AssistChip(
                        onClick = onBlockPackage,
                        label = { Text(stringResource(R.string.debug_notification_block_app), fontSize = 11.sp) },
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
                stringResource(R.string.debug_section_ml_classifier),
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
                            stringResource(
                                R.string.debug_status_format,
                                stringResource(R.string.label_status),
                                if (classifierStats.isReady) stringResource(R.string.debug_status_active) else stringResource(R.string.debug_status_training)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(R.string.debug_label_positive_samples, classifierStats.totalPositive),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(R.string.debug_label_negative_samples, classifierStats.totalNegative),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(R.string.debug_label_vocabulary, classifierStats.vocabularySize),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(
                        onClick = onRetrain,
                        enabled = classifierStats.totalPositive + classifierStats.totalNegative >= 20
                    ) {
                        Text(stringResource(R.string.debug_retrain), fontSize = 12.sp)
                    }
                }

            // Source trust scores
            if (sourceStats.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.debug_section_source_trust),
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

@Composable
private fun DatabaseManagementSection(viewModel: DebugViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val databaseStats by viewModel.databaseStats.collectAsState()
    val exportResult by viewModel.databaseExportResult.collectAsState()
    val importResult by viewModel.databaseImportResult.collectAsState()
    
    // Load stats when section becomes visible
    LaunchedEffect(Unit) {
        viewModel.loadDatabaseStats()
    }
    
    // Handle export/import results
    LaunchedEffect(exportResult) {
        when (exportResult) {
            is com.yourname.expensetracker.domain.backup.DatabaseExportResult.Success -> {
                val result = exportResult as com.yourname.expensetracker.domain.backup.DatabaseExportResult.Success
                val path = result.filePath
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.debug_toast_export_success, path),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                result.warning?.let { warning ->
                    android.widget.Toast.makeText(
                        context,
                        warning,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                viewModel.clearExportResult()
            }
            is com.yourname.expensetracker.domain.backup.DatabaseExportResult.Error -> {
                val message = (exportResult as com.yourname.expensetracker.domain.backup.DatabaseExportResult.Error).message
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.debug_toast_export_failed, message),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                viewModel.clearExportResult()
            }
            else -> {}
        }
    }
    
    LaunchedEffect(importResult) {
        when (importResult) {
            is com.yourname.expensetracker.domain.backup.DatabaseImportResult.Success -> {
                val summary = (importResult as com.yourname.expensetracker.domain.backup.DatabaseImportResult.Success).summary
                val isEmptyData = summary.transactionCount == 0 && summary.categoryCount == 0
                
                val message = if (isEmptyData) {
                    buildString {
                        append("⚠️ Import completed but no data found!")
                        append("\n• ${summary.transactionCount} transactions")
                        append("\n• ${summary.categoryCount} categories")
                        append("\n\nThe backup may be corrupted or from a very old version.")
                    }
                } else {
                    buildString {
                        append("✅ Import verified!")
                        if (summary.transactionCount > 0) {
                            append("\n📊 Imported ${summary.transactionCount} transactions")
                        }
                        if (summary.categoryCount > 0) {
                            append("\n📂 ${summary.categoryCount} categories")
                        }
                        if (summary.merchantCount > 0) {
                            append("\n🏪 ${summary.merchantCount} merchants")
                        }
                        if (summary.budgetCount > 0) {
                            append("\n💰 ${summary.budgetCount} budgets")
                        }
                        if (summary.pendingReviewCount > 0) {
                            append("\n⏳ ${summary.pendingReviewCount} pending reviews")
                        }
                        append("\n\nRestart app to use all data.")
                    }
                }
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                viewModel.clearImportResult()
                viewModel.loadDatabaseStats() // Refresh stats
            }
            is com.yourname.expensetracker.domain.backup.DatabaseImportResult.SuccessNeedsRestart -> {
                android.widget.Toast.makeText(context, "✅ Database imported successfully!\n\nPlease restart the app to access all imported data.", android.widget.Toast.LENGTH_LONG).show()
                viewModel.clearImportResult()
                viewModel.loadDatabaseStats() // Refresh stats
            }
            is com.yourname.expensetracker.domain.backup.DatabaseImportResult.Error -> {
                val message = (importResult as com.yourname.expensetracker.domain.backup.DatabaseImportResult.Error).message
                // Show full error message with detailed explanation
                android.widget.Toast.makeText(context, "❌ Import blocked:\n$message", android.widget.Toast.LENGTH_LONG).show()
                viewModel.clearImportResult()
            }
            else -> {}
        }
    }
    
    var showImportDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showCsvImportDialog by remember { mutableStateOf(false) }
    var showLegacyMigrationDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.debug_section_database_management),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Database Stats
            databaseStats?.let { stats ->
                Text(
                    stringResource(R.string.debug_current_data),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    stringResource(R.string.debug_transactions_count, stats.transactionCount),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.debug_categories_count, stats.categoryCount),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.debug_merchants_count, stats.merchantCount),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.debug_pending_reviews_count, stats.pendingReviewCount),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Export Button
                Button(
                    onClick = { viewModel.exportDatabase() },
                    modifier = Modifier.weight(1f),
                    enabled = exportResult !is com.yourname.expensetracker.domain.backup.DatabaseExportResult.Loading
                ) {
                    if (exportResult is com.yourname.expensetracker.domain.backup.DatabaseExportResult.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.debug_export), fontSize = 12.sp)
                    }
                }
                
                // Import Button
                Button(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = importResult !is com.yourname.expensetracker.domain.backup.DatabaseImportResult.Loading
                ) {
                    if (importResult is com.yourname.expensetracker.domain.backup.DatabaseImportResult.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.debug_import), fontSize = 12.sp)
                    }
                }
                
                // Reset Button
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.debug_reset), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            
                Spacer(modifier = Modifier.height(8.dp))
                
                // CSV Import Button (for migrating from old versions)
                OutlinedButton(
                    onClick = { showCsvImportDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.debug_import_csv_button), fontSize = 12.sp)
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Legacy Database Migration Button
                OutlinedButton(
                    onClick = { showLegacyMigrationDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.debug_migrate_legacy_db), fontSize = 12.sp)
                }
            }
        }
    
    // Import Dialog
    if (showImportDialog) {
        ImportDatabaseDialog(
            context = context,
            onDismiss = { showImportDialog = false },
            onImport = { uri ->
                viewModel.importDatabase(uri, context)
                showImportDialog = false
            }
        )
    }
    
    // Reset Confirmation Dialog (BAK-10: typed confirmation required)
    if (showResetDialog) {
        val resetSuccessMessage = stringResource(R.string.debug_toast_reset_success)
        var typedConfirmation by remember { mutableStateOf("") }
        val isConfirmed = typedConfirmation.equals("DELETE", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
        title = { Text(stringResource(R.string.debug_dialog_reset_database_title)) },
        text = { 
            Column {
                Text(stringResource(R.string.debug_dialog_reset_database_message))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = typedConfirmation,
                    onValueChange = { typedConfirmation = it },
                    label = { Text("Type DELETE to confirm") },
                    singleLine = true,
                    isError = typedConfirmation.isNotEmpty() && !isConfirmed,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (isConfirmed) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                        cursorColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (viewModel.resetDatabase(typedConfirmation)) {
                        showResetDialog = false
                        android.widget.Toast.makeText(context, resetSuccessMessage, android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(context, "Reset cancelled: type DELETE to confirm", android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                enabled = isConfirmed
            ) {
                Text(stringResource(R.string.debug_reset))
            }
        },
        dismissButton = {
            TextButton(onClick = { showResetDialog = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        }
        )
    }
    
    // Legacy Database Migration Result Handling
    val migrationResult by viewModel.migrationResult.collectAsState()
    LaunchedEffect(migrationResult) {
        migrationResult?.let { result ->
            val message = buildString {
                appendLine("✅ Migration complete!")
                appendLine("Categories: ${result.categories.imported} imported, ${result.categories.skipped} skipped, ${result.categories.failed} failed")
                appendLine("Expenses: ${result.expenses.imported} imported, ${result.expenses.skipped} skipped, ${result.expenses.failed} failed")
                appendLine("Budgets: ${result.budgets.imported} imported, ${result.budgets.failed} failed")
                appendLine("Recurring: ${result.recurringRules.imported} imported, ${result.recurringRules.failed} failed")
                appendLine("Planned: ${result.plannedExpenses.imported} imported, ${result.plannedExpenses.failed} failed")
                appendLine("Savings: ${result.savingsGoals.imported} imported, ${result.savingsGoals.failed} failed")
            }
            android.widget.Toast.makeText(context, message.trimEnd(), android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearMigrationResult()
            viewModel.loadDatabaseStats()
        }
    }

    // Legacy Migration File Picker Dialog
    if (showLegacyMigrationDialog) {
        LegacyMigrationDialog(
            context = context,
            onDismiss = { showLegacyMigrationDialog = false },
            onMigrate = { uri ->
                viewModel.migrateLegacyDatabase(uri)
                showLegacyMigrationDialog = false
            }
        )
    }

    // CSV Import Dialog
    if (showCsvImportDialog) {
        CsvImportDialog(
            context = context,
            onDismiss = { showCsvImportDialog = false },
            onImport = { uri ->
                // Read CSV content and import
                val csvContent = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                }
                
                csvContent?.let { content ->
                    scope.launch {
                        val importer = viewModel.csvExpenseImporter
                        val result = importer.importFromContent(content) { progress, total ->
                            // Could update UI with progress here
                        }

                        when (result) {
                            is com.yourname.expensetracker.util.CsvExpenseImporter.ImportResult.Success -> {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.debug_import_success_format, result.imported, result.errors),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            is com.yourname.expensetracker.util.CsvExpenseImporter.ImportResult.Error -> {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.debug_import_failed_format, result.message),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                showCsvImportDialog = false
            }
        )
    }
}

@Composable
private fun ImportDatabaseDialog(
    context: Context,
    onDismiss: () -> Unit,
    onImport: (android.net.Uri) -> Unit
) {
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        uri?.let { selectedUri = it }
        selectedUri?.let { uri ->
            // Get filename from URI
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    selectedFileName = cursor.getString(nameIndex)
                }
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.debug_dialog_import_database_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.debug_dialog_select_file),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                // File picker button
                OutlinedButton(
                    onClick = { launcher.launch(arrayOf("application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (selectedFileName == null) stringResource(R.string.debug_dialog_select_file) else stringResource(R.string.debug_dialog_change_file))
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Show selected file info
                if (selectedFileName != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.debug_dialog_selected),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                selectedFileName!!,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.debug_import_no_file),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    stringResource(R.string.debug_dialog_import_replace_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    selectedUri?.let { onImport(it) }
                },
                enabled = selectedUri != null
            ) {
                Text(stringResource(R.string.debug_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun CsvImportDialog(
    context: Context,
    onDismiss: () -> Unit,
    onImport: (android.net.Uri) -> Unit
) {
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var importProgress by remember { mutableStateOf(0) }
    var isImporting by remember { mutableStateOf(false) }
    
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        uri?.let { selectedUri = it }
        selectedUri?.let { uri ->
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        selectedFileName = cursor.getString(nameIndex)
                    }
                }
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text(stringResource(R.string.debug_dialog_import_csv_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.debug_import_csv_format_description),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedButton(
                    onClick = { launcher.launch(arrayOf("text/csv", "text/plain", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting
                ) {
                    Text(if (selectedFileName == null) stringResource(R.string.debug_select_csv_file) else stringResource(R.string.debug_dialog_change_file))
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (selectedFileName != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.debug_dialog_selected),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                selectedFileName!!,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    if (isImporting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { importProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            stringResource(R.string.debug_importing_progress, importProgress),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.debug_import_no_file),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    stringResource(R.string.debug_import_replace_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    selectedUri?.let { onImport(it) }
                },
                enabled = selectedUri != null && !isImporting
            ) {
                Text(stringResource(R.string.debug_import_csv))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun LegacyMigrationDialog(
    context: Context,
    onDismiss: () -> Unit,
    onMigrate: (android.net.Uri) -> Unit
) {
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        uri?.let { selectedUri = it }
        selectedUri?.let { uri ->
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        selectedFileName = cursor.getString(nameIndex)
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Migrate Legacy Database") },
        text = {
            Column {
                Text(
                    text = "Select an old backup database file (.db) to migrate data into the current app.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Categories, expenses, budgets, recurring rules, planned expenses, and savings goals will be imported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Duplicate items (by name or dedup key) will be skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = { launcher.launch(arrayOf("application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (selectedFileName == null) "Select database file" else "Change file")
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (selectedFileName != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Selected:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                selectedFileName!!,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Text(
                        "No file selected. Choose a .db file from your backup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Existing data with the same name/keys will be preserved. No data will be overwritten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    selectedUri?.let { onMigrate(it) }
                },
                enabled = selectedUri != null
            ) {
                Text("Migrate Data")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
