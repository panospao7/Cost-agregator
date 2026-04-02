package com.yourname.expensetracker.ui.screens.negotiation

import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.negotiation.SmartBillNegotiationEngine
import java.text.NumberFormat
import java.util.Locale

enum class NegotiationOutcome(@StringRes val displayNameRes: Int) {
    SUCCESS(R.string.negotiation_outcome_success),
    PARTIAL(R.string.negotiation_outcome_partial),
    FAILED(R.string.negotiation_outcome_failed),
    CANCELLED(R.string.negotiation_outcome_cancelled),
    PENDING(R.string.negotiation_outcome_pending)
}

enum class NegotiationPower(@StringRes val displayNameRes: Int) {
    STRONG(R.string.negotiation_power_strong),
    MODERATE(R.string.negotiation_power_moderate),
    WEAK(R.string.negotiation_power_weak),
    POOR(R.string.negotiation_power_poor)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillNegotiationScreen(
    onNavigateBack: () -> Unit,
    viewModel: BillNegotiationViewModel = hiltViewModel()
) {
    val opportunities by viewModel.opportunities.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedOpportunity by remember { mutableStateOf<SmartBillNegotiationEngine.NegotiationOpportunity?>(null) }
    var showScriptDialog by remember { mutableStateOf(false) }
    var showOutcomeDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        viewModel.loadOpportunities()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bill_negotiation_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.bill_negotiation_potential_savings),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            
                            val totalYearlySavings = opportunities.sumOf { it.potentialYearlySavings }
                            val numberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
                            
                            Text(
                                text = numberFormat.format(totalYearlySavings),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            
                            Text(
                                text = stringResource(R.string.bill_negotiation_savings_subtitle_format, opportunities.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                
                // Opportunities
                if (opportunities.isEmpty()) {
                    item {
                        EmptyNegotiationState()
                    }
                } else {
                    items(opportunities) { opportunity ->
                        NegotiationOpportunityCard(
                            opportunity = opportunity,
                            onViewScript = {
                                selectedOpportunity = opportunity
                                showScriptDialog = true
                            },
                            onRecordOutcome = {
                                selectedOpportunity = opportunity
                                showOutcomeDialog = true
                            }
                        )
                    }
                }
            }
        }
    }
    
    // Script Dialog
    if (showScriptDialog && selectedOpportunity != null) {
        NegotiationScriptDialog(
            opportunity = selectedOpportunity!!,
            onDismiss = { showScriptDialog = false }
        )
    }
    
    // Outcome Recording Dialog
    if (showOutcomeDialog && selectedOpportunity != null) {
        OutcomeRecordingDialog(
            opportunity = selectedOpportunity!!,
            onDismiss = { showOutcomeDialog = false },
            onSave = { outcome, savings, notes ->
                viewModel.recordOutcome(
                    opportunity = selectedOpportunity!!,
                    outcome = outcome,
                    actualSavings = savings,
                    notes = notes
                )
                showOutcomeDialog = false
            }
        )
    }
}

@Composable
fun NegotiationOpportunityCard(
    opportunity: SmartBillNegotiationEngine.NegotiationOpportunity,
    onViewScript: () -> Unit,
    onRecordOutcome: () -> Unit
) {
    val numberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
    val powerColor = when (opportunity.negotiationPower) {
        SmartBillNegotiationEngine.NegotiationPower.STRONG -> MaterialTheme.colorScheme.primary
        SmartBillNegotiationEngine.NegotiationPower.MODERATE -> MaterialTheme.colorScheme.tertiary
        SmartBillNegotiationEngine.NegotiationPower.WEAK -> MaterialTheme.colorScheme.error
        SmartBillNegotiationEngine.NegotiationPower.POOR -> MaterialTheme.colorScheme.outline
    }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ServiceTypeIcon(opportunity.serviceType)
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column {
                        Text(
                            text = opportunity.serviceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = opportunity.currentProvider,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Power indicator
                val powerLabel = when (opportunity.negotiationPower) {
                    SmartBillNegotiationEngine.NegotiationPower.STRONG -> stringResource(R.string.negotiation_power_strong)
                    SmartBillNegotiationEngine.NegotiationPower.MODERATE -> stringResource(R.string.negotiation_power_moderate)
                    SmartBillNegotiationEngine.NegotiationPower.WEAK -> stringResource(R.string.negotiation_power_weak)
                    SmartBillNegotiationEngine.NegotiationPower.POOR -> stringResource(R.string.negotiation_power_poor)
                }
                Surface(
                    color = powerColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = powerLabel,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = powerColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Pricing comparison
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PriceColumn(
                    label = stringResource(R.string.bill_negotiation_current_label),
                    price = opportunity.currentPrice,
                    isStrikethrough = true
                )
                
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                
                PriceColumn(
                    label = stringResource(R.string.bill_negotiation_target_label),
                    price = opportunity.competitivePrice,
                    isHighlight = true
                )
                
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = stringResource(R.string.bill_negotiation_save_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = numberFormat.format(opportunity.potentialMonthlySavings),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.bill_negotiation_per_month),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Success probability
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.bill_negotiation_success_probability_format, opportunity.successProbability),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                
                LinearProgressIndicator(
                    progress = { opportunity.successProbability / 100f },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        opportunity.successProbability >= 70 -> MaterialTheme.colorScheme.primary
                        opportunity.successProbability >= 50 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
            
            // Alternative providers
            if (opportunity.alternativeProviders.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = stringResource(R.string.bill_negotiation_alternatives_format, opportunity.alternativeProviders.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onViewScript,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Chat, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.bill_negotiation_view_script))
                }
                
                Button(
                    onClick = onRecordOutcome,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.bill_negotiation_record_outcome))
                }
            }
        }
    }
}

@Composable
fun PriceColumn(
    label: String,
    price: Double,
    isStrikethrough: Boolean = false,
    isHighlight: Boolean = false
) {
    val numberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = numberFormat.format(price),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textDecoration = if (isStrikethrough) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            color = if (isHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ServiceTypeIcon(serviceType: SmartBillNegotiationEngine.ServiceType) {
    val icon = when (serviceType) {
        SmartBillNegotiationEngine.ServiceType.INTERNET -> Icons.Rounded.Wifi
        SmartBillNegotiationEngine.ServiceType.MOBILE -> Icons.Rounded.PhoneAndroid
        SmartBillNegotiationEngine.ServiceType.STREAMING -> Icons.Rounded.PlayCircle
        SmartBillNegotiationEngine.ServiceType.INSURANCE -> Icons.Rounded.Shield
        SmartBillNegotiationEngine.ServiceType.ENERGY -> Icons.Rounded.Bolt
        SmartBillNegotiationEngine.ServiceType.WATER -> Icons.Rounded.WaterDrop
        SmartBillNegotiationEngine.ServiceType.GYM -> Icons.Rounded.FitnessCenter
        SmartBillNegotiationEngine.ServiceType.SOFTWARE -> Icons.Rounded.Cloud
        SmartBillNegotiationEngine.ServiceType.OTHER -> Icons.Rounded.Receipt
    }
    
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun NegotiationScriptDialog(
    opportunity: SmartBillNegotiationEngine.NegotiationOpportunity,
    onDismiss: () -> Unit
) {
    val script = opportunity.negotiationScript
    var showAlternatives by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Column {
                Text(stringResource(R.string.bill_negotiation_script_title))
                Text(
                    text = opportunity.serviceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Opening
                ScriptSection(
                    title = stringResource(R.string.bill_negotiation_opening),
                    content = script.opening,
                    isHighlight = true
                )
                
                // Talking Points
                ScriptSection(
                    title = stringResource(R.string.bill_negotiation_key_talking_points),
                    content = script.talkingPoints.joinToString("\n• ", prefix = "• ")
                )
                
                // Retention Offers
                if (opportunity.retentionOffers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = stringResource(R.string.bill_negotiation_mention_offers),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    opportunity.retentionOffers.forEach { offer ->
                        RetentionOfferCard(offer = offer)
                    }
                }
                
                // Close
                ScriptSection(
                    title = stringResource(R.string.bill_negotiation_closing_statement),
                    content = script.close,
                    isHighlight = true
                )
                
                // Fallback
                ScriptSection(
                    title = stringResource(R.string.bill_negotiation_fallback_ask),
                    content = script.fallbackAsk
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
fun ScriptSection(
    title: String,
    content: String,
    isHighlight: Boolean = false
) {
    Spacer(modifier = Modifier.height(16.dp))
    
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = if (isHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    )
    
    Spacer(modifier = Modifier.height(4.dp))
    
    Surface(
        color = if (isHighlight) 
            MaterialTheme.colorScheme.primaryContainer 
        else 
            MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = content,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun RetentionOfferCard(offer: SmartBillNegotiationEngine.RetentionOffer) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.LocalOffer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = offer.description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.bill_negotiation_duration_format, offer.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            offer.discount?.let {
                val numberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
                Text(
                    text = numberFormat.format(it),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            } ?: offer.discountPercent?.let {
                Text(
                    text = stringResource(R.string.bill_negotiation_discount_percent_format, it),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun OutcomeRecordingDialog(
    opportunity: SmartBillNegotiationEngine.NegotiationOpportunity,
    onDismiss: () -> Unit,
    onSave: (outcome: NegotiationOutcome, savings: Double?, notes: String) -> Unit
) {
    var selectedOutcome by remember { mutableStateOf(NegotiationOutcome.SUCCESS) }
    var savingsAmount by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val numberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bill_negotiation_record_outcome_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.bill_negotiation_service_label_format, opportunity.serviceName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                
                // Outcome selection
                Text(
                    text = stringResource(R.string.bill_negotiation_outcome_label),
                    style = MaterialTheme.typography.labelMedium
                )
                
                Column {
                    NegotiationOutcome.values().forEach { outcome ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedOutcome = outcome },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedOutcome == outcome,
                                onClick = { selectedOutcome = outcome }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(outcome.displayNameRes),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                // Savings amount (only for SUCCESS)
                if (selectedOutcome == NegotiationOutcome.SUCCESS) {
                    OutlinedTextField(
                        value = savingsAmount,
                        onValueChange = { savingsAmount = it },
                        label = { Text(stringResource(R.string.bill_negotiation_actual_savings_label)) },
                        placeholder = { Text(stringResource(R.string.bill_negotiation_savings_placeholder)) },
                        prefix = { Text(numberFormat.currency.symbol) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                // Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.bill_negotiation_notes_label)) },
                    placeholder = { Text(stringResource(R.string.bill_negotiation_notes_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val savings = if (selectedOutcome == NegotiationOutcome.SUCCESS) {
                        savingsAmount.toDoubleOrNull()
                    } else null
                    onSave(selectedOutcome, savings, notes)
                }
            ) {
                Text(stringResource(R.string.action_save))
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
fun EmptyNegotiationState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.ThumbUp,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = stringResource(R.string.bill_negotiation_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.bill_negotiation_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
