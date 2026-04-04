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
import com.yourname.expensetracker.domain.debug.DebugData
import com.yourname.expensetracker.domain.debug.DebugIssue
import com.yourname.expensetracker.domain.debug.IssueSeverity
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.parser.ParsedTransaction

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
                text = stringResource(R.string.debug_characters_lines_format, debugData.rawText.length, debugData.rawText.lines().size),
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
    return DateFormatterUtils.shortDateWithTime().format(Date(timestamp))
}

