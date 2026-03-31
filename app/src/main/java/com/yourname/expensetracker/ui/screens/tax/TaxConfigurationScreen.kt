@file:OptIn(ExperimentalMaterial3Api::class)

package com.yourname.expensetracker.ui.screens.tax

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
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.tax.TaxBracket
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaxConfigurationScreen(
    onNavigateBack: () -> Unit,
    viewModel: TaxConfigurationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var sampleIncome by remember { mutableStateOf("50000") }
    
    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Tax Configuration",
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
                        onRetry = { viewModel.loadTaxConfiguration() }
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Country Selection
                        item {
                            CountrySelectionCard(
                                selectedCountry = uiState.selectedCountry,
                                supportedCountries = uiState.supportedCountries,
                                onCountrySelected = { viewModel.selectCountry(it) }
                            )
                        }
                        
                        // VAT Rate Card
                        item {
                            VatRateCard(vatRate = uiState.vatRate)
                        }
                        
                        // Tax Brackets Header
                        item {
                            Text(
                                text = "Income Tax Brackets",
                                style = MaterialTheme.typography.titleMedium,
                                color = SemanticColors.TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        // Tax Brackets List
                        items(uiState.taxBrackets) { bracket ->
                            TaxBracketCard(
                                bracket = bracket,
                                currency = uiState.currency
                            )
                        }
                        
                        // Sample Calculator
                        item {
                            SampleCalculatorCard(
                                sampleIncome = sampleIncome,
                                onIncomeChange = { 
                                    sampleIncome = it
                                    it.toDoubleOrNull()?.let { income ->
                                        viewModel.calculateSampleEstimate(income)
                                    }
                                },
                                estimate = uiState.sampleEstimate,
                                currency = uiState.currency
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountrySelectionCard(
    selectedCountry: String,
    supportedCountries: List<CountryInfo>,
    onCountrySelected: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Tax Region",
                style = MaterialTheme.typography.labelMedium,
                color = SemanticColors.TextSecondary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            var expanded by remember { mutableStateOf(false) }
            val selected = supportedCountries.find { it.code == selectedCountry }
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = "${selected?.flag ?: ""} ${selected?.name ?: selectedCountry}",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = SemanticColors.TextSecondary.copy(alpha = 0.5f)
                    )
                )
                
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    supportedCountries.forEach { country ->
                        DropdownMenuItem(
                            text = { 
                                Text("${country.flag} ${country.name} (${country.currency})")
                            },
                            onClick = {
                                onCountrySelected(country.code)
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
private fun VatRateCard(vatRate: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.PrimaryIndigo.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "VAT Rate",
                    style = MaterialTheme.typography.labelMedium,
                    color = SemanticColors.TextSecondary
                )
                Text(
                    text = "${(vatRate * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    color = SemanticColors.PrimaryIndigo,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Icon(
                imageVector = Icons.Rounded.Receipt,
                contentDescription = null,
                tint = SemanticColors.PrimaryIndigo,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
private fun TaxBracketCard(bracket: TaxBracket, currency: String) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.SurfaceLight.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = bracket.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SemanticColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = "${(bracket.rate * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = SemanticColors.PrimaryIndigo,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            val rangeText = when {
                bracket.maxIncome == null -> "Above ${currencyFormat.format(bracket.minIncome)}"
                bracket.minIncome == 0.0 -> "Up to ${currencyFormat.format(bracket.maxIncome)}"
                else -> "${currencyFormat.format(bracket.minIncome)} - ${currencyFormat.format(bracket.maxIncome)}"
            }
            
            Text(
                text = rangeText,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticColors.TextSecondary
            )
        }
    }
}

@Composable
private fun SampleCalculatorCard(
    sampleIncome: String,
    onIncomeChange: (String) -> Unit,
    estimate: com.yourname.expensetracker.domain.tax.TaxEstimate?,
    currency: String
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
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
            Text(
                text = "Tax Calculator",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF4CAF50),
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = sampleIncome,
                onValueChange = onIncomeChange,
                label = { Text("Annual Income ($currency)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            
            estimate?.let {
                Spacer(modifier = Modifier.height(16.dp))
                
                // Tax breakdown
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Estimated Income",
                            color = SemanticColors.TextSecondary
                        )
                        Text(
                            text = currencyFormat.format(it.estimatedIncome),
                            color = SemanticColors.TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Taxable Income",
                            color = SemanticColors.TextSecondary
                        )
                        Text(
                            text = currencyFormat.format(it.taxableIncome),
                            color = SemanticColors.TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Deductible Expenses",
                            color = SemanticColors.TextSecondary
                        )
                        Text(
                            text = currencyFormat.format(it.deductibleExpenses),
                            color = SemanticColors.TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Estimated Income Tax",
                            color = Color(0xFFF44336),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = currencyFormat.format(it.estimatedIncomeTax),
                            color = Color(0xFFF44336),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Estimated VAT Paid",
                            color = Color(0xFFF44336),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = currencyFormat.format(it.estimatedVatPaid),
                            color = Color(0xFFF44336),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Effective Tax Rate",
                            color = SemanticColors.TextSecondary
                        )
                        Text(
                            text = "${String.format("%.1f", it.effectiveTaxRate)}%",
                            color = SemanticColors.TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    val netIncome = it.estimatedIncome - it.estimatedIncomeTax - it.estimatedVatPaid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Net Income (Est.)",
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = currencyFormat.format(netIncome),
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
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