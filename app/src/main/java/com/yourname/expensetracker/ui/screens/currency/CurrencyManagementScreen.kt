@file:OptIn(ExperimentalMaterial3Api::class)

package com.yourname.expensetracker.ui.screens.currency

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: CurrencyManagementViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showConversionDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Currency Management",
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshRates() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh Rates",
                            tint = SemanticColors.TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SemanticColors.PrimaryIndigo)
                    }
                }
                uiState.error != null -> {
                    ErrorState(
                        message = uiState.error!!,
                        onRetry = { viewModel.refreshRates() }
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Home Currency Card
                        item {
                            HomeCurrencyCard(
                                homeCurrency = uiState.homeCurrency,
                                supportedCurrencies = uiState.supportedCurrencies,
                                onCurrencySelected = { code ->
                                    viewModel.setHomeCurrency(code)
                                }
                            )
                        }
                        
                        // Offline/Stale Warning
                        if (uiState.isOffline || uiState.isRatesStale) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (uiState.isOffline) 
                                            MaterialTheme.colorScheme.errorContainer 
                                        else 
                                            MaterialTheme.colorScheme.tertiaryContainer
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (uiState.isOffline) 
                                                Icons.Rounded.CloudOff 
                                            else 
                                                Icons.Rounded.Warning,
                                            contentDescription = null,
                                            tint = if (uiState.isOffline) 
                                                MaterialTheme.colorScheme.error 
                                            else 
                                                MaterialTheme.colorScheme.tertiary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = if (uiState.isOffline) 
                                                    "Offline Mode" 
                                                else 
                                                    "Rates May Be Outdated",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = if (uiState.isOffline) 
                                                    "Using last known rates. Connect to internet for latest rates." 
                                                else 
                                                    "Exchange rates are older than 24 hours. Tap refresh to update.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Quick Conversion Button
                        item {
                            Button(
                                onClick = { showConversionDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SemanticColors.PrimaryIndigo
                                )
                            ) {
                                Icon(Icons.Rounded.CurrencyExchange, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Quick Conversion")
                            }
                        }
                        
                        // Conversion Result
                        uiState.conversionResult?.let { result ->
                            item {
                                ConversionResultCard(
                                    result = result,
                                    onClear = { viewModel.clearConversion() }
                                )
                            }
                        }
                        
                        // Exchange Rates Header
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Exchange Rates",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SemanticColors.TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                
                                uiState.lastUpdated?.let { timestamp ->
                                    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                                    Text(
                                        text = "Updated: ${dateFormat.format(Date(timestamp))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SemanticColors.TextSecondary
                                    )
                                }
                            }
                        }
                        
                        // Exchange Rates List
                        items(uiState.exchangeRates) { rate ->
                            ExchangeRateCard(rate = rate)
                        }
                        
                        // Supported Currencies Header
                        item {
                            Text(
                                text = "Supported Currencies",
                                style = MaterialTheme.typography.titleMedium,
                                color = SemanticColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        
                        // Supported Currencies Grid
                        items(uiState.supportedCurrencies.chunked(2)) { rowCurrencies ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                rowCurrencies.forEach { currency ->
                                    CurrencyCard(
                                        currency = currency,
                                        isHomeCurrency = currency.code == uiState.homeCurrency,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            viewModel.setHomeCurrency(currency.code)
                                        }
                                    )
                                }
                                if (rowCurrencies.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Conversion Dialog
        if (showConversionDialog) {
            ConversionDialog(
                supportedCurrencies = uiState.supportedCurrencies,
                onDismiss = { showConversionDialog = false },
                onConvert = { amount, from, to ->
                    viewModel.convert(amount, from, to)
                    showConversionDialog = false
                }
            )
        }
    }
}

@Composable
private fun HomeCurrencyCard(
    homeCurrency: String,
    supportedCurrencies: List<CurrencyInfo>,
    onCurrencySelected: (String) -> Unit
) {
    val currentCurrency = supportedCurrencies.find { it.code == homeCurrency }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Home Currency",
                style = MaterialTheme.typography.labelMedium,
                color = SemanticColors.TextSecondary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentCurrency?.flag ?: "💱",
                    fontSize = 32.sp
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Text(
                        text = homeCurrency,
                        style = MaterialTheme.typography.headlineSmall,
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = currentCurrency?.name ?: "Unknown",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SemanticColors.TextSecondary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            var expanded by remember { mutableStateOf(false) }
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = "Change Home Currency",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { 
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = SemanticColors.TextSecondary.copy(alpha = 0.5f),
                        unfocusedTextColor = SemanticColors.TextSecondary
                    )
                )
                
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    supportedCurrencies.forEach { currency ->
                        DropdownMenuItem(
                            text = { 
                                Row {
                                    Text("${currency.flag} ${currency.code} - ${currency.name}")
                                }
                            },
                            onClick = {
                                onCurrencySelected(currency.code)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExchangeRateCard(rate: ExchangeRateInfo) {
    val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault())
    numberFormat.maximumFractionDigits = 4
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rate.fromCurrency,
                    style = MaterialTheme.typography.titleMedium,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Icon(
                    imageVector = Icons.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = SemanticColors.TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = rate.toCurrency,
                    style = MaterialTheme.typography.titleMedium,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Text(
                text = numberFormat.format(rate.rate),
                style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.PrimaryIndigo,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CurrencyCard(
    currency: CurrencyInfo,
    isHomeCurrency: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHomeCurrency) {
                SemanticColors.PrimaryIndigo.copy(alpha = 0.2f)
            } else {
                SemanticColors.SurfaceLight.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currency.flag,
                fontSize = 24.sp
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currency.code,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = currency.symbol,
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticColors.TextSecondary
                )
            }
            
            if (isHomeCurrency) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Home Currency",
                    tint = SemanticColors.PrimaryIndigo,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ConversionResultCard(
    result: ConversionResult,
    onClear: () -> Unit
) {
    val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault())
    numberFormat.maximumFractionDigits = 2
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Conversion Result",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear",
                        tint = SemanticColors.TextSecondary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = numberFormat.format(result.originalAmount),
                        style = MaterialTheme.typography.headlineSmall,
                        color = SemanticColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = result.originalCurrency,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SemanticColors.TextSecondary
                    )
                }
                
                Icon(
                    imageVector = Icons.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = SemanticColors.PrimaryIndigo
                )
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = numberFormat.format(result.convertedAmount),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = result.targetCurrency,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SemanticColors.TextSecondary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Rate: 1 ${result.originalCurrency} = ${numberFormat.format(result.rateUsed)} ${result.targetCurrency}",
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ConversionDialog(
    supportedCurrencies: List<CurrencyInfo>,
    onDismiss: () -> Unit,
    onConvert: (Double, String, String) -> Unit
) {
    var amount by remember { mutableStateOf("100") }
    var fromCurrency by remember { mutableStateOf("EUR") }
    var toCurrency by remember { mutableStateOf("USD") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "Currency Conversion",
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Amount input
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                
                // From Currency
                var fromExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = fromExpanded,
                    onExpandedChange = { fromExpanded = it }
                ) {
                    OutlinedTextField(
                        value = fromCurrency,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("From") },
                        trailingIcon = { 
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = fromExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = fromExpanded,
                        onDismissRequest = { fromExpanded = false }
                    ) {
                        supportedCurrencies.forEach { currency ->
                            DropdownMenuItem(
                                text = { 
                                    Text("${currency.flag} ${currency.code} - ${currency.name}")
                                },
                                onClick = {
                                    fromCurrency = currency.code
                                    fromExpanded = false
                                }
                            )
                        }
                    }
                }
                
                // To Currency
                var toExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = toExpanded,
                    onExpandedChange = { toExpanded = it }
                ) {
                    OutlinedTextField(
                        value = toCurrency,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("To") },
                        trailingIcon = { 
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = toExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = toExpanded,
                        onDismissRequest = { toExpanded = false }
                    ) {
                        supportedCurrencies.forEach { currency ->
                            DropdownMenuItem(
                                text = { 
                                    Text("${currency.flag} ${currency.code} - ${currency.name}")
                                },
                                onClick = {
                                    toCurrency = currency.code
                                    toExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    amount.toDoubleOrNull()?.let { amt ->
                        onConvert(amt, fromCurrency, toCurrency)
                    }
                }
            ) {
                Text("Convert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = SemanticColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = SemanticColors.PrimaryIndigo
            )
        ) {
            Text("Try Again")
        }
    }
}