package com.yourname.expensetracker.ui.screens.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.categorization.CategorizationDebugTrace
import com.yourname.expensetracker.domain.categorization.LayerDebugResult
import com.yourname.expensetracker.domain.categorization.MatchType
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlinx.coroutines.launch

/**
 * Debug screen for testing the categorization pipeline.
 *
 * Note: The "Amount" label uses a generic label without currency symbol.
 * TODO: If currency-specific display is needed, inject homeCurrency from
 *       CurrencySettingsRepository and use it in the label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorizationDebugScreen(
    initialMerchant: String? = null,
    initialAmount: Double? = null,
    initialTimestamp: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: CategorizationDebugViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var rawMerchant by remember { mutableStateOf(initialMerchant ?: "Sklavenitis Lagka") }
    var rawAmount by remember { mutableStateOf(initialAmount?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "45.50") }
    
    val initialTimeString = remember(initialTimestamp) {
        if (initialTimestamp != null) {
            DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(initialTimestamp).atZone(ZoneId.systemDefault()))
        } else {
            DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(java.time.LocalTime.now())
        }
    }
    var timeString by remember { mutableStateOf(initialTimeString) }
    
    val trace by viewModel.debugTrace.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()

    // Auto-run if initialized with external data
    LaunchedEffect(initialMerchant) {
        if (initialMerchant != null) {
            val amount = rawAmount.replace(",", ".").toDoubleOrNull() ?: 0.0
            // G-TIME-01: "now" comes from the ViewModel's injected TimeProvider.
            val timestamp = initialTimestamp ?: viewModel.referenceNowMillis()
            viewModel.testCategorization(rawMerchant, amount, timestamp)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_categorization_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    trace?.let { currentTrace ->
                        IconButton(onClick = {
                            val json = viewModel.exportTraceToJson(currentTrace)
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(json))
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Trace copied to clipboard")
                            }
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.action_copy), tint = MaterialTheme.colorScheme.primary)
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
        ) {
            // Input Controls
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = rawMerchant,
                        onValueChange = { rawMerchant = it },
                        label = { Text("Merchant Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = rawAmount,
                            onValueChange = { rawAmount = it },
                            label = { Text("Amount") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        
                        OutlinedTextField(
                            value = timeString,
                            onValueChange = { timeString = it },
                            label = { Text("Time (HH:mm)") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        onClick = {
                            val amount = rawAmount.toDoubleOrNull() ?: 0.0
                            val timestamp = parseTimeStrToTimestamp(timeString, viewModel.referenceNowMillis())
                            viewModel.testCategorization(rawMerchant, amount, timestamp)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isProcessing && rawMerchant.isNotBlank()
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Test Categorization Pipeline")
                        }
                    }
                }
            }
            
            // Output Trace
            AnimatedVisibility(visible = trace != null, enter = fadeIn(), exit = fadeOut()) {
                trace?.let { TraceView(it) }
            }
        }
    }
}

@Composable
private fun TraceView(trace: CategorizationDebugTrace) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "${stringResource(R.string.debug_preprocessing)} (Phases 2 & 3)",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Input: ${trace.inputMerchant}", fontFamily = FontFamily.Monospace)
                    Text("Normalized: ${trace.normalizedMerchant}", fontFamily = FontFamily.Monospace)
                    Text("Canonical: ${trace.canonicalMerchant}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    if (trace.strippedParts.isNotEmpty()) {
                        Text("Stripped Suffixes/Prefixes: ${trace.strippedParts.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        
        item {
            Text(
                "Pipeline Execution Trace",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
        }

        items(trace.layerResults) { result ->
            LayerResultCard(result)
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (trace.finalResult.matchType != MatchType.UNKNOWN) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.debug_postprocessing), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (trace.finalResult.matchType != MatchType.UNKNOWN) {
                        Text(
                            text = trace.finalResult.categoryName ?: "Unknown",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text("${stringResource(R.string.debug_match)}: ${trace.finalResult.matchType}")
                        Text(
                            "${stringResource(R.string.debug_confidence)}: ${(trace.finalResult.confidence * 100).toInt()}%",
                            fontWeight = FontWeight.Bold
                        )
                        Text("Explanation: ${trace.finalResult.explanation}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            text = stringResource(R.string.debug_categorization_uncategorized),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text("${stringResource(R.string.debug_mismatch)}: All layers failed to find a match.")
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerResultCard(result: LayerDebugResult) {
    val bgColor = if (result.matchFound) Color(0xFFE8F5E9) else Color(0xFFFAFAFA)
    val iconTint = if (result.matchFound) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
    val contentCol = if (result.matchFound) Color(0xFF1B5E20) else Color(0xFF424242)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (result.matchFound) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = result.layerName,
                    fontWeight = FontWeight.Bold,
                    color = contentCol
                )
                if (result.matchFound) {
                    Text("${stringResource(R.string.debug_category_predicted)}: ${result.categoryName}", color = contentCol)
                    Text("${stringResource(R.string.debug_confidence)}: ${(result.confidence * 100).toInt()}%", color = contentCol)
                    if (result.details.isNotBlank()) {
                        Text(result.details, style = MaterialTheme.typography.bodySmall, color = contentCol.copy(alpha = 0.8f))
                    }
                } else {
                    Text(
                        "${stringResource(R.string.debug_category_actual)}: ${stringResource(R.string.debug_mismatch)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentCol.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * Parses an "HH:mm" string into a timestamp on the day of [nowMillis].
 *
 * G-TIME-01: the date comes from the caller-supplied reference instant
 * (ViewModel-injected TimeProvider), not the wall clock. The lenient
 * Calendar normalization of the former implementation is preserved via
 * local-time arithmetic (e.g. "25:00" rolls into the next day).
 */
private fun parseTimeStrToTimestamp(timeStr: String, nowMillis: Long): Long {
    return try {
        val parts = timeStr.split(":")
        val hours = parts[0].toIntOrNull() ?: 12
        val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0

        val zone = ZoneId.systemDefault()
        val todayMidnight = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone)
        todayMidnight.plusHours(hours.toLong()).plusMinutes(minutes.toLong())
            .toInstant().toEpochMilli()
    } catch (e: Exception) {
        nowMillis
    }
}
