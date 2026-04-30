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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R

private const val DEFAULT_SPLIT_COLOR = "#FF6B6B"

private val SplitTextFieldStateSaver: Saver<SplitTextFieldState, Any> = listSaver(
    save = { state ->
        listOf(state.text, state.lastCommittedValue)
    },
    restore = { saved ->
        val text = saved.getOrNull(0) as? String ?: return@listSaver null
        val lastCommittedValue = saved.getOrNull(1) as? Double ?: return@listSaver null
        SplitTextFieldState(text = text, lastCommittedValue = lastCommittedValue)
    }
)

internal data class SplitTextFieldState(
    val text: String,
    val lastCommittedValue: Double
) {
    companion object {
        fun initial(externalValue: Double): SplitTextFieldState {
            return SplitTextFieldState(
                text = externalValue.toString(),
                lastCommittedValue = externalValue
            )
        }
    }

    fun onUserInput(newText: String, onParsed: (Double) -> Unit = {}): SplitTextFieldState {
        val parsed = newText.toDoubleOrNull()
        return if (parsed != null && parsed.isFinite()) {
            onParsed(parsed)
            copy(text = newText, lastCommittedValue = parsed)
        } else {
            copy(text = newText)
        }
    }

    fun onExternalValueChange(externalValue: Double): SplitTextFieldState {
        return if (externalValue != lastCommittedValue) {
            copy(text = externalValue.toString(), lastCommittedValue = externalValue)
        } else {
            this
        }
    }
}

internal fun buildCompletedSplitShares(
    participants: List<SplitShare>,
    splitData: EnhancedSplitManager.VisualSplitData
): List<SplitShare> {
    val segmentsByIndex = splitData.segments.associateBy { it.index }

    return participants.map { participant ->
        val segment = segmentsByIndex[participant.participantIndex]
        participant.copy(
            percentage = segment?.percentage ?: participant.percentage,
            amount = segment?.amount ?: participant.amount,
            color = segment?.color ?: participant.color
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualSplitEditorScreen(
    totalAmount: Double,
    currencyCode: String,
    expenseId: Long? = null,
    templateId: Long? = null,
    onSplitComplete: (List<SplitShare>, SplitTemplate.SplitType) -> Unit,
    onSaveAsTemplate: ((String, List<SplitShare>, SplitTemplate.SplitType) -> Unit)? = null,
    onNavigateBack: () -> Unit,
    viewModel: VisualSplitViewModel = hiltViewModel()
) {
    val templates by viewModel.templates.collectAsState()
    val currentSplit by viewModel.currentSplit.collectAsState()

    val participantsSaver = remember {
        listSaver<List<SplitShare>, Any?>(
            save = { participants ->
                participants.flatMap { share ->
                    listOf(
                        share.participantIndex,
                        share.participantName,
                        share.percentage,
                        share.amount,
                        share.color
                    )
                }
            },
            restore = { flat ->
                flat.chunked(5).mapNotNull { chunk ->
                    val index = chunk.getOrNull(0) as? Int ?: return@mapNotNull null
                    val name = chunk.getOrNull(1) as? String ?: return@mapNotNull null
                    SplitShare(
                        participantIndex = index,
                        participantName = name,
                        percentage = chunk.getOrNull(2) as? Double,
                        amount = chunk.getOrNull(3) as? Double,
                        color = chunk.getOrNull(4) as? String
                    )
                }
            }
        )
    }

    var splitType by rememberSaveable { mutableStateOf(SplitTemplate.SplitType.EQUAL) }
    val youLabel = stringResource(R.string.split_you)
    val person2Label = stringResource(R.string.visual_split_person_format, 2)
    var participants by rememberSaveable(stateSaver = participantsSaver) {
        mutableStateOf(
            listOf(
                SplitShare(0, youLabel, color = "#FF6B6B"),
                SplitShare(1, person2Label, color = "#4ECDC4")
            )
        )
    }
    var rowIds by rememberSaveable { mutableStateOf(listOf(0, 1)) }
    var nextRowId by rememberSaveable { mutableIntStateOf(2) }
    var showTemplateDialog by rememberSaveable { mutableStateOf(false) }
    var showSaveTemplateDialog by rememberSaveable { mutableStateOf(false) }
    var templateName by rememberSaveable { mutableStateOf("") }
    var selectedTemplateId by rememberSaveable { mutableStateOf<Long?>(null) }
    val canApplyToExpense = expenseId != null
    
    // Load template if templateId is provided
    LaunchedEffect(templateId) {
        templateId?.let { id ->
            viewModel.loadTemplate(id)?.let { template ->
                selectedTemplateId = template.id
                splitType = template.splitType
                // Parse template shares into participants
                val shares = viewModel.parseTemplateShares(template)
                if (shares.isNotEmpty()) {
                    participants = shares.mapIndexed { index, share ->
                        share.copy(color = sanitizeColorHex(share.color, getRandomColor(index)))
                    }
                    rowIds = shares.indices.map { nextRowId + it }
                    nextRowId += shares.size
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
                title = { Text(stringResource(R.string.split_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.visual_split_back_cd))
                    }
                },
                actions = {
                    IconButton(onClick = { showTemplateDialog = true }) {
                        Icon(Icons.Default.Bookmark, stringResource(R.string.visual_split_templates_cd))
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
                            text = stringResource(R.string.visual_split_remaining_format, CurrencyFormatter.format(split.remainingAmount, currencyCode)),
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
                            Text(stringResource(R.string.visual_split_save_template))
                        }
                        
                        Button(
                            onClick = {
                                if (!canApplyToExpense) return@Button
                                currentSplit?.let { splitData ->
                                    onSplitComplete(
                                        buildCompletedSplitShares(participants, splitData),
                                        splitType
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = canApplyToExpense && currentSplit?.remainingAmount == 0.0
                        ) {
                            Text(stringResource(R.string.visual_split_apply_split))
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
                            text = stringResource(R.string.visual_split_total_amount),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = CurrencyFormatter.format(totalAmount, currencyCode),
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
                    text = stringResource(R.string.visual_split_split_type),
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
                                    SplitTemplate.SplitType.EQUAL -> stringResource(R.string.visual_split_type_equal)
                                    SplitTemplate.SplitType.PERCENTAGE -> stringResource(R.string.visual_split_type_percentage)
                                    SplitTemplate.SplitType.CUSTOM_AMOUNT -> stringResource(R.string.visual_split_type_amount)
                                    SplitTemplate.SplitType.UNEQUAL -> stringResource(R.string.visual_split_type_custom)
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
                        text = stringResource(R.string.visual_split_participants_format, participants.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    val personLabel = stringResource(R.string.visual_split_person_format, participants.size + 1)
                    TextButton(
                        onClick = {
                            if (participants.size < 10) {
                                participants = participants + SplitShare(
                                    participantIndex = participants.size,
                                    participantName = personLabel,
                                    color = getRandomColor(participants.size)
                                )
                                rowIds = rowIds + nextRowId
                                nextRowId++
                            }
                        },
                        enabled = participants.size < 10
                    ) {
                        Icon(Icons.Default.Add, stringResource(R.string.visual_split_add_cd))
                        Text(stringResource(R.string.visual_split_add))
                    }
                }
            }
            
            val segmentsByIndex = currentSplit?.segments?.associateBy { it.index }.orEmpty()
            items(participants.zip(rowIds), key = { it.second }) { (participant, _) ->
                val segment = segmentsByIndex[participant.participantIndex]
                ParticipantSplitCard(
                    participant = participant,
                    splitType = splitType,
                    assignedAmount = segment?.amount ?: 0.0,
                    percentage = segment?.percentage ?: 0.0,
                    currencyCode = currencyCode,
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
                            val removeIndex = participants.indexOfFirst {
                                it.participantIndex == participant.participantIndex
                            }
                            if (removeIndex >= 0) {
                                participants = participants.filterIndexed { index, _ ->
                                    index != removeIndex
                                }.mapIndexed { index, share ->
                                    share.copy(participantIndex = index)
                                }
                                rowIds = rowIds.filterIndexed { index, _ -> index != removeIndex }
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
            title = { Text(stringResource(R.string.visual_split_select_template)) },
            text = {
                LazyColumn {
                    items(templates) { template ->
                        ListItem(
                            headlineContent = { Text(template.name) },
                            supportingContent = { 
                                val splitTypeLabel = when (template.splitType) {
                                    SplitTemplate.SplitType.EQUAL -> stringResource(R.string.split_type_equal)
                                    SplitTemplate.SplitType.PERCENTAGE -> stringResource(R.string.split_type_percentage)
                                    SplitTemplate.SplitType.CUSTOM_AMOUNT -> stringResource(R.string.split_type_custom_amount)
                                    SplitTemplate.SplitType.UNEQUAL -> stringResource(R.string.split_type_custom)
                                }
                                Text(stringResource(R.string.visual_split_template_people_type_format, template.totalSplits, splitTypeLabel))
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
                                selectedTemplateId = template.id
                                val shares = viewModel.parseTemplateShares(template)
                                participants = shares.mapIndexed { index, share ->
                                    share.copy(color = sanitizeColorHex(share.color, getRandomColor(index)))
                                }
                                rowIds = shares.indices.map { nextRowId + it }
                                nextRowId += shares.size
                                splitType = template.splitType
                                showTemplateDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTemplateDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Save Template Dialog
    if (showSaveTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showSaveTemplateDialog = false },
            title = { Text(stringResource(R.string.visual_split_save_as_template)) },
            text = {
                OutlinedTextField(
                    value = templateName,
                    onValueChange = { templateName = it },
                    label = { Text(stringResource(R.string.visual_split_template_name_label)) },
                    placeholder = { Text(stringResource(R.string.visual_split_template_name_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (templateName.isNotBlank()) {
                            val sanitizedParticipants = participants.mapIndexed { index, share ->
                                share.copy(color = sanitizeColorHex(share.color, getRandomColor(index)))
                            }

                            onSaveAsTemplate?.invoke(templateName, sanitizedParticipants, splitType)
                            viewModel.createTemplate(templateName, sanitizedParticipants, splitType)
                            showSaveTemplateDialog = false
                            templateName = ""
                        }
                    },
                    enabled = templateName.isNotBlank()
                ) {
                    Text(stringResource(R.string.visual_split_action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveTemplateDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
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
                    val color = safeParseColor(segment.color)
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
                    val color = safeParseColor(segment.color)
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
                            text = stringResource(R.string.visual_split_segment_format, segment.participantName, String.format("%.1f", segment.percentage)),
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
    currencyCode: String,
    onNameChange: (String) -> Unit,
    onPercentageChange: (Double) -> Unit,
    onAmountChange: (Double) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean
) {
    var percentageState by rememberSaveable(stateSaver = SplitTextFieldStateSaver) {
        mutableStateOf(SplitTextFieldState.initial(participant.percentage ?: percentage))
    }
    var amountState by rememberSaveable(stateSaver = SplitTextFieldStateSaver) {
        mutableStateOf(SplitTextFieldState.initial(participant.amount ?: assignedAmount))
    }

    val currentExternalPercentage = participant.percentage ?: percentage
    val currentExternalAmount = participant.amount ?: assignedAmount

    LaunchedEffect(currentExternalPercentage) {
        percentageState = percentageState.onExternalValueChange(currentExternalPercentage)
    }

    LaunchedEffect(currentExternalAmount) {
        amountState = amountState.onExternalValueChange(currentExternalAmount)
    }
    
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
                val color = safeParseColor(participant.color)
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
                    label = { Text(stringResource(R.string.visual_split_participant_name_label)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Close, stringResource(R.string.visual_split_remove_cd), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Split input based on type
            when (splitType) {
                SplitTemplate.SplitType.EQUAL -> {
                    Text(
                        text = stringResource(R.string.visual_split_equal_split_format, CurrencyFormatter.format(assignedAmount, currencyCode)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SplitTemplate.SplitType.PERCENTAGE -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = percentageState.text,
                            onValueChange = { newText ->
                                percentageState = percentageState.onUserInput(newText) { parsed ->
                                    onPercentageChange(parsed)
                                }
                            },
                            label = { Text(stringResource(R.string.visual_split_percentage_label)) },
                            suffix = { Text(stringResource(R.string.visual_split_percentage_suffix)) },
                            modifier = Modifier.width(120.dp),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        val amountPrefix = stringResource(R.string.visual_split_amount_prefix_format, CurrencyFormatter.format(assignedAmount, currencyCode))
                        Text(
                            text = amountPrefix,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                SplitTemplate.SplitType.CUSTOM_AMOUNT, SplitTemplate.SplitType.UNEQUAL -> {
                    OutlinedTextField(
                        value = amountState.text,
                        onValueChange = { newText ->
                            amountState = amountState.onUserInput(newText) { parsed ->
                                onAmountChange(parsed)
                            }
                        },
                        label = { Text(stringResource(R.string.visual_split_amount_label)) },
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

private fun safeParseColor(rawColor: String?): Color {
    return runCatching {
        Color(android.graphics.Color.parseColor(sanitizeColorHex(rawColor, DEFAULT_SPLIT_COLOR)))
    }.getOrDefault(Color(0xFF6B7280))
}

private fun sanitizeColorHex(rawColor: String?, fallback: String = DEFAULT_SPLIT_COLOR): String {
    if (rawColor.isNullOrBlank()) return fallback

    val normalized = if (rawColor.startsWith("#")) rawColor else "#$rawColor"
    val validLength = normalized.length == 7 || normalized.length == 9
    val validChars = normalized.drop(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    return if (validLength && validChars) normalized else fallback
}


