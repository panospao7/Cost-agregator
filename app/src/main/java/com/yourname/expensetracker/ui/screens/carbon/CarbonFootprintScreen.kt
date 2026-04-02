package com.yourname.expensetracker.ui.screens.carbon

import androidx.compose.animation.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.carbon.CarbonFootprintCalculator
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.asString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarbonFootprintScreen(
    onNavigateBack: () -> Unit,
    onViewOffsetOptions: () -> Unit = {},
    viewModel: CarbonFootprintViewModel = hiltViewModel()
) {
    val report by viewModel.report.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedPeriod by remember { mutableIntStateOf(30) }
    
    LaunchedEffect(selectedPeriod) {
        viewModel.loadReport(selectedPeriod)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.carbon_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.carbon_back_cd))
                    }
                },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(stringResource(R.string.carbon_days_format, selectedPeriod))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            listOf(7, 30, 90, 365).forEach { days ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.carbon_days_format, days)) },
                                    onClick = {
                                        selectedPeriod = days
                                        expanded = false
                                    }
                                )
                            }
                        }
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
            report?.let { data ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header with score
                    item {
                        CarbonScoreCard(report = data)
                    }
                    
                    // Total emissions
                    item {
                        TotalEmissionsCard(report = data)
                    }
                    
                    // Category breakdown
                    item {
                        Text(
                            text = stringResource(R.string.carbon_emissions_by_category),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    items(data.categoryBreakdown) { category ->
                        CategoryEmissionCard(category = category)
                    }
                    
                    // Benchmarks
                    item {
                        BenchmarkCard(report = data)
                    }
                    
                    // Offset option
                    item {
                        OffsetCard(report = data, onViewOffsetOptions = onViewOffsetOptions)
                    }
                    
                    // Recommendations
                    if (data.recommendations.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.carbon_sustainability_tips),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        
                        items(data.recommendations.filter { !it.isOffset }) { recommendation ->
                            RecommendationCard(recommendation = recommendation)
                        }
                    }
                    
                    // Alternatives
                    if (data.alternatives.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.carbon_sustainable_alternatives),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        
                        items(data.alternatives) { alternative ->
                            AlternativeCard(alternative = alternative)
                        }
                    }
                }
            } ?: run {
                EmptyCarbonState(
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { viewModel.loadReport(selectedPeriod) }
                )
            }
        }
    }
}

@Composable
fun CarbonScoreCard(report: CarbonFootprintCalculator.CarbonFootprintReport) {
    val scoreColor = when {
        report.sustainabilityScore >= 70 -> Color(0xFF4CAF50)
        report.sustainabilityScore >= 50 -> Color(0xFFFFA726)
        else -> Color(0xFFEF5350)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scoreColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Score circle
            Surface(
                shape = CircleShape,
                color = scoreColor,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${report.sustainabilityScore}",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = stringResource(R.string.carbon_sustainability_score),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                val scoreText = when {
                    report.sustainabilityScore >= 70 -> stringResource(R.string.carbon_score_excellent)
                    report.sustainabilityScore >= 50 -> stringResource(R.string.carbon_score_good)
                    else -> stringResource(R.string.carbon_score_high)
                }
                Text(
                    text = scoreText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun TotalEmissionsCard(report: CarbonFootprintCalculator.CarbonFootprintReport) {
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
                text = stringResource(R.string.carbon_total_emissions),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "${String.format("%.1f", report.totalEmissionsKg)} kg",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            Text(
                text = stringResource(R.string.carbon_co2_equivalent),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${String.format("%.1f", report.dailyAverageKg)} kg",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.carbon_per_day),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${String.format("%.1f", report.dailyAverageKg * 365 / 1000)} tonnes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.carbon_per_year),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryEmissionCard(category: CarbonFootprintCalculator.CategoryEmission) {
    val categoryIcon = when (category.category) {
        "FUEL" -> Icons.Rounded.LocalGasStation
        "FLIGHT" -> Icons.Rounded.Flight
        "PUBLIC_TRANSPORT" -> Icons.Rounded.Train
        "TAXI" -> Icons.Rounded.LocalTaxi
        "GROCERY" -> Icons.Rounded.ShoppingCart
        "RESTAURANT", "FAST_FOOD" -> Icons.Rounded.Restaurant
        "CLOTHING" -> Icons.Rounded.Checkroom
        "ELECTRONICS" -> Icons.Rounded.Devices
        "ELECTRICITY" -> Icons.Rounded.ElectricBolt
        "STREAMING" -> Icons.Rounded.PlayCircle
        "GYM" -> Icons.Rounded.FitnessCenter
        "PHARMACY" -> Icons.Rounded.LocalPharmacy
        else -> Icons.Rounded.Category
    }
    
    val categoryColor = when (category.category) {
        "FUEL", "FLIGHT" -> MaterialTheme.colorScheme.error
        "ELECTRICITY" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = categoryColor.copy(alpha = 0.2f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = categoryIcon,
                        contentDescription = null,
                        tint = categoryColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatCategoryName(category.category),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.carbon_transactions_format, category.transactionCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${String.format("%.1f", category.emissionsKg)} kg",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${category.percentage}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun BenchmarkCard(report: CarbonFootprintCalculator.CarbonFootprintReport) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.carbon_comparisons),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            BenchmarkRow(
                label = stringResource(R.string.carbon_vs_greek_average),
                comparison = report.comparisonToNationalAverage,
                averageValue = 10.0
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            BenchmarkRow(
                label = stringResource(R.string.carbon_vs_global_average),
                comparison = report.comparisonToGlobalAverage,
                averageValue = 12.0
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Paris Agreement target
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.carbon_paris_target),
                    style = MaterialTheme.typography.bodyMedium
                )
                
                val gap = report.parisAgreementGap
                val targetText = if (gap > 0) 
                    stringResource(R.string.carbon_above_target_format, gap) 
                else 
                    stringResource(R.string.carbon_below_target_format, gap)
                Text(
                    text = targetText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (gap > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            
            if (report.parisAgreementGap > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                )
                
                Text(
                    text = stringResource(R.string.carbon_paris_target_description),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun BenchmarkRow(label: String, comparison: Int, averageValue: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        
        val isAbove = comparison > 0
        Surface(
            color = if (isAbove) 
                MaterialTheme.colorScheme.errorContainer 
            else 
                MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "${if (isAbove) "+" else ""}$comparison%",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (isAbove) 
                    MaterialTheme.colorScheme.onErrorContainer 
                else 
                    MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun OffsetCard(
    report: CarbonFootprintCalculator.CarbonFootprintReport,
    onViewOffsetOptions: () -> Unit
) {
    val numberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Forest,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(48.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.carbon_offset_emissions),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                
                Text(
                    text = stringResource(R.string.carbon_offset_format, report.totalEmissionsKg, numberFormat.format(report.offsetCost)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }
            
            Button(
                onClick = onViewOffsetOptions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Text(stringResource(R.string.carbon_offset_button))
            }
        }
    }
}

@Composable
fun RecommendationCard(recommendation: CarbonFootprintCalculator.SustainabilityRecommendation) {
    val difficultyColor = when (recommendation.difficulty) {
        CarbonFootprintCalculator.Difficulty.EASY -> MaterialTheme.colorScheme.primary
        CarbonFootprintCalculator.Difficulty.MEDIUM -> MaterialTheme.colorScheme.tertiary
        CarbonFootprintCalculator.Difficulty.HARD -> MaterialTheme.colorScheme.error
    }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = difficultyColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = recommendation.difficulty.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = difficultyColor
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = recommendation.title.asString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = recommendation.description,
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.carbon_impact_format, recommendation.potentialImpact),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = stringResource(R.string.carbon_save_format, recommendation.savings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AlternativeCard(alternative: CarbonFootprintCalculator.SustainableAlternative) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.carbon_instead_of_format, alternative.currentBehavior),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = stringResource(R.string.carbon_try_format, alternative.alternative),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val numberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
                
                Text(
                    text = stringResource(R.string.carbon_reduction_format, alternative.co2Reduction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = stringResource(R.string.carbon_savings_format, numberFormat.format(alternative.costSavings)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun EmptyCarbonState(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Eco,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = stringResource(R.string.carbon_no_data),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.carbon_add_transactions_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(onClick = onRetry) {
            Text(stringResource(R.string.carbon_calculate_button))
        }
    }
}

private fun formatCategoryName(category: String): String {
    return category.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
}
