package com.yourname.expensetracker.ui.screens.negotiation

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.negotiation.SmartBillNegotiationEngine
import java.text.NumberFormat
import java.util.Locale

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
    
    LaunchedEffect(Unit) {
        viewModel.loadOpportunities()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bill Negotiation") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
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
                                text = "Potential Savings",
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
                                text = "per year from ${opportunities.size} negotiable bills",
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
                                // TODO: Implement outcome recording
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
                Surface(
                    color = powerColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = opportunity.negotiationPower.name,
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
                    label = "Current",
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
                    label = "Target",
                    price = opportunity.competitivePrice,
                    isHighlight = true
                )
                
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "Save",
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
                        text = "/month",
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
                    text = "Success Probability: ${opportunity.successProbability}%",
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
                    text = "Alternatives: ${opportunity.alternativeProviders.joinToString(", ")}",
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
                    Text("View Script")
                }
                
                Button(
                    onClick = onRecordOutcome,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Record Outcome")
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
                Text("Negotiation Script")
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
                    title = "Opening",
                    content = script.opening,
                    isHighlight = true
                )
                
                // Talking Points
                ScriptSection(
                    title = "Key Talking Points",
                    content = script.talkingPoints.joinToString("\n• ", prefix = "• ")
                )
                
                // Retention Offers
                if (opportunity.retentionOffers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Mention These Offers:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    opportunity.retentionOffers.forEach { offer ->
                        RetentionOfferCard(offer = offer)
                    }
                }
                
                // Close
                ScriptSection(
                    title = "Closing Statement",
                    content = script.close,
                    isHighlight = true
                )
                
                // Fallback
                ScriptSection(
                    title = "If They Say No",
                    content = script.fallbackAsk
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
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
                    text = "Duration: ${offer.duration}",
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
                    text = "${String.format("%.0f", it)}% off",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
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
            text = "No Negotiation Opportunities",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Your current bills are already at competitive rates. Great job managing your subscriptions!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
