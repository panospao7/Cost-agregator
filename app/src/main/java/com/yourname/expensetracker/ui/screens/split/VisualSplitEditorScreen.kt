@file:OptIn(ExperimentalLayoutApi::class)

package com.yourname.expensetracker.ui.screens.split

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.SplitShare
import com.yourname.expensetracker.data.database.entity.SplitTemplate
import com.yourname.expensetracker.domain.split.EnhancedSplitManager
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualSplitEditorScreen(
    totalAmount: Double,
    currencyCode: String = "EUR",
    expenseId: Long? = null,
    templateId: Long? = null,
    onSplitComplete: (List<SplitShare>, SplitTemplate.SplitType) -> Unit,
    onSaveAsTemplate: ((String, List<SplitShare>, SplitTemplate.SplitType) -> Unit)? = null,
    onNavigateBack: () -> Unit,
    viewModel: VisualSplitViewModel = hiltViewModel()
) {
    val templates by viewModel.templates.collectAsState()
    val currentSplit by viewModel.currentSplit.collectAsState()
    
    var splitType by remember { mutableStateOf(SplitTemplate.SplitType.EQUAL) }
    var participants by remember { mutableStateOf(listOf(
        SplitShare(0, "You", color = "#FF6B6B"),
        SplitShare(1, "Person 2", color = "#4ECDC4")
    )) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showSaveTemplateDialog by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf<SplitTemplate?>(null) }
    
    val currency = remember(currencyCode) {
        Currency.getInstance(currencyCode)
    }
    val numberFormat = remember { NumberFormat.getCurrencyInstance(Locale.getDefault()) }
    
    // Load template if templateId is provided
    LaunchedEffect(templateId) {
        templateId?.let { id ->
            viewModel.loadTemplate(id)?.let { template ->
                selectedTemplate = template
                splitType = template.splitType
                // Parse template shares into participants
                val shares = viewModel.parseTemplateShares(template)
                if (shares.isNotEmpty()) {
                    participants = shares
                }
            }
        }
    }
    
    LaunchedEffect(totalAmount, participants, splitType) {
        viewModel.calculateSplit(totalAmount, participants, splitType)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split Expense") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showTemplateDialog = true }) {
                        Icon(Icons.Default.Bookmark, "Templates")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    currentSplit?.let { split ->
                        if (split.remainingAmount != 0.0) {
                            Text(
                                text = "Remaining: ${numberFormat.format(split.remainingAmount)}",
                                color = if (split.remainingAmount > 0) MaterialTheme.colorScheme.primary 
                                        else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showSaveTemplateDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save Template")
                        }
                        
                        Button(
                            onClick = {
                                onSplitComplete(participants, splitType)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = currentSplit?.remainingAmount == 0.0
                        ) {
                            Text("Apply Split")
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Total Amount Header
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Total Amount",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = numberFormat.format(totalAmount),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            // Split Type Selector
            item {
                Text(
                    text = "Split Type",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SplitTemplate.SplitType.values().forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = splitType == type,
                            onClick = { splitType = type },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = SplitTemplate.SplitType.values().size
                            )
                        ) {
                            Text(
                                when (type) {
                                    SplitTemplate.SplitType.EQUAL -> "Equal"
                                    SplitTemplate.SplitType.PERCENTAGE -> "%"
                                    SplitTemplate.SplitType.CUSTOM_AMOUNT -> "Amount"
                                    SplitTemplate.SplitType.UNEQUAL -> "Custom"
                                }
                            )
                        }
                    }
                }
            }
            
            // Visual Split Chart
            item {
                currentSplit?.let { split ->
                    VisualSplitChart(
                        splitData = split,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                }
            }
            
            // Participants List
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Participants (${participants.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    TextButton(
                        onClick = {
                            if (participants.size < 10) {
                                participants = participants + SplitShare(
                                    participantIndex = participants.size,
                                    participantName = "Person ${participants.size + 1}",
                                    color = getRandomColor(participants.size)
                                )
                            }
                        },
                        enabled = participants.size < 10
                    ) {
                        Icon(Icons.Default.Add, null)
                        Text("Add")
                    }
                }
            }
            
            items(participants) { participant ->
                ParticipantSplitCard(
                    participant = participant,
                    splitType = splitType,
                    assignedAmount = currentSplit?.segments?.find { 
                        it.participantName == participant.participantName 
                    }?.amount ?: 0.0,
                    percentage = currentSplit?.segments?.find { 
                        it.participantName == participant.participantName 
                    }?.percentage ?: 0.0,
                    onNameChange = { newName ->
                        participants = participants.map {
                            if (it.participantIndex == participant.participantIndex) {
                                it.copy(participantName = newName)
                            } else it
                        }
                    },
                    onPercentageChange = { newPercentage ->
                        participants = participants.map {
                            if (it.participantIndex == participant.participantIndex) {
                                it.copy(percentage = newPercentage)
                            } else it
                        }
                    },
                    onAmountChange = { newAmount ->
                        participants = participants.map {
                            if (it.participantIndex == participant.participantIndex) {
                                it.copy(amount = newAmount)
                            } else it
                        }
                    },
                    onRemove = {
                        if (participants.size > 2) {
                            participants = participants.filter { 
                                it.participantIndex != participant.participantIndex 
                            }.mapIndexed { index, share ->
                                share.copy(participantIndex = index)
                            }
                        }
                    },
                    canRemove = participants.size > 2
                )
            }
        }
    }
    
    // Template Selection Dialog
    if (showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text("Select Template") },
            text = {
                LazyColumn {
                    items(templates) { template ->
                        ListItem(
                            headlineContent = { Text(template.name) },
                            supportingContent = { 
                                Text("${template.totalSplits} people • ${template.splitType.name}")
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Bookmark,
                                    null,
                                    tint = if (template.isDefault) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.onSurface
                                )
                            },
                            modifier = Modifier.clickable {
                                selectedTemplate = template
                                participants = viewModel.parseTemplateShares(template)
                                splitType = template.splitType
                                showTemplateDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTemplateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Save Template Dialog
    if (showSaveTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showSaveTemplateDialog = false },
            title = { Text("Save as Template") },
            text = {
                OutlinedTextField(
                    value = templateName,
                    onValueChange = { templateName = it },
                    label = { Text("Template Name") },
                    placeholder = { Text("e.g., Dinner with Friends") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (templateName.isNotBlank()) {
                            onSaveAsTemplate?.invoke(templateName, participants, splitType)
                            viewModel.createTemplate(templateName, participants, splitType)
                            showSaveTemplateDialog = false
                            templateName = ""
                        }
                    },
                    enabled = templateName.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveTemplateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun VisualSplitChart(
    splitData: EnhancedSplitManager.VisualSplitData,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Horizontal stacked bar chart
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                splitData.segments.forEach { segment ->
                    val color = Color(android.graphics.Color.parseColor(segment.color))
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(segment.percentage.toFloat().coerceAtLeast(0.01f))
                            .background(color)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Legend
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                splitData.segments.forEach { segment ->
                    val color = Color(android.graphics.Color.parseColor(segment.color))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${segment.participantName}: ${String.format("%.1f", segment.percentage)}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParticipantSplitCard(
    participant: SplitShare,
    splitType: SplitTemplate.SplitType,
    assignedAmount: Double,
    percentage: Double,
    onNameChange: (String) -> Unit,
    onPercentageChange: (Double) -> Unit,
    onAmountChange: (Double) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean
) {
    val numberFormat = remember { NumberFormat.getCurrencyInstance(Locale.getDefault()) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Color indicator
                val color = Color(android.graphics.Color.parseColor(participant.color ?: "#FF6B6B"))
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                // Name field
                OutlinedTextField(
                    value = participant.participantName,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Close, "Remove", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Split input based on type
            when (splitType) {
                SplitTemplate.SplitType.EQUAL -> {
                    Text(
                        text = "Equal split: ${numberFormat.format(assignedAmount)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SplitTemplate.SplitType.PERCENTAGE -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = (participant.percentage ?: percentage).toString(),
                            onValueChange = { 
                                it.toDoubleOrNull()?.let { onPercentageChange(it) }
                            },
                            label = { Text("Percentage") },
                            suffix = { Text("%") },
                            modifier = Modifier.width(120.dp),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "= ${numberFormat.format(assignedAmount)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                SplitTemplate.SplitType.CUSTOM_AMOUNT, SplitTemplate.SplitType.UNEQUAL -> {
                    OutlinedTextField(
                        value = (participant.amount ?: assignedAmount).toString(),
                        onValueChange = { 
                            it.toDoubleOrNull()?.let { onAmountChange(it) }
                        },
                        label = { Text("Amount") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }
    }
}

fun getRandomColor(index: Int): String {
    val colors = listOf(
        "#FF6B6B", "#4ECDC4", "#45B7D1", "#FFA07A", "#98D8C8",
        "#F7DC6F", "#BB8FCE", "#85C1E2", "#F8C471", "#82E0AA"
    )
    return colors.getOrElse(index) { colors[0] }
}
