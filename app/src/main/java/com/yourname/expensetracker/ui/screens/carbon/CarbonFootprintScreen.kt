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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.domain.carbon.CarbonFootprintCalculator
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.components.common.EmptyStateType
import com.yourname.expensetracker.ui.components.common.EnhancedEmptyState
import com.yourname.expensetracker.ui.components.common.ErrorState
import com.yourname.expensetracker.ui.components.common.ErrorType
import com.yourname.expensetracker.ui.components.common.InlineErrorBanner
import com.yourname.expensetracker.ui.components.emptystate.ContextualActionRegistry
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateAction
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateActionType
import com.yourname.expensetracker.ui.components.emptystate.EmptyStateScreenKeys
import com.yourname.expensetracker.ui.theme.SemanticColors
import kotlin.math.abs

internal enum class CarbonFootprintContentState {
    FULL_SCREEN_LOADING,
    FULL_SCREEN_ERROR,
    CONTENT
}

internal fun resolveCarbonFootprintContentState(
    hasReport: Boolean,
    isLoading: Boolean,
    hasError: Boolean
): CarbonFootprintContentState {
    if (isLoading && !hasReport && !hasError) {
        return CarbonFootprintContentState.FULL_SCREEN_LOADING
    }

    if (hasError && !hasReport) {
        return CarbonFootprintContentState.FULL_SCREEN_ERROR
    }

    return CarbonFootprintContentState.CONTENT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarbonFootprintScreen(
    onNavigateBack: () -> Unit,
    onViewOffsetOptions: () -> Unit = {},
    viewModel: CarbonFootprintViewModel = hiltViewModel(),
    actionRegistry: ContextualActionRegistry
) {
    val report by viewModel.report.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val homeCurrency by viewModel.homeCurrency.collectAsState(initial = "")
    val completedActionKeys by actionRegistry.completedActions.collectAsState()
    var selectedPeriod by rememberSaveable { mutableIntStateOf(30) }
    
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
        val hasReport = report != null
        val hasError = !error.isNullOrBlank()
        when (
            resolveCarbonFootprintContentState(
                hasReport = hasReport,
                isLoading = isLoading,
                hasError = hasError
            )
        ) {
            CarbonFootprintContentState.FULL_SCREEN_LOADING -> {
                Box(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            CarbonFootprintContentState.FULL_SCREEN_ERROR -> {
                ErrorState(
                    type = ErrorType.UNKNOWN,
                    message = stringResource(R.string.error_load_failed),
                    isRetrying = isLoading,
                    onRetry = { viewModel.loadReport(selectedPeriod) },
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                )
            }

            CarbonFootprintContentState.CONTENT -> {
                report?.let { data ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (isLoading) {
                            item {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }

                    if (hasError) {
                        item {
                            InlineErrorBanner(
                                message = stringResource(R.string.error_load_failed),
                                onRetry = { viewModel.loadReport(selectedPeriod) },
                                isRetrying = isLoading,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

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
                        OffsetCard(report = data, onViewOffsetOptions = onViewOffsetOptions, homeCurrency = homeCurrency)
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
                            AlternativeCard(alternative = alternative, homeCurrency = homeCurrency)
                        }
                    }
                    }
                } ?: run {
                    // Enhanced empty state with contextual actions
                    val emptyStateActions by remember(completedActionKeys) {
                        derivedStateOf {
                            actionRegistry.getActions(EmptyStateScreenKeys.CARBON)
                        }
                    }

                    EnhancedEmptyState(
                        type = EmptyStateType.GENERIC,
                        title = stringResource(R.string.carbon_no_data),
                        message = stringResource(R.string.carbon_add_transactions_hint),
                        actions = emptyStateActions,
                        onActionClick = { action ->
                            when (val actionType = action.action) {
                                is EmptyStateActionType.NavigateToDestination -> {
                                    // Handle navigation
                                }
                                is EmptyStateActionType.ExecuteAction -> actionType.action.invoke()
                                is EmptyStateActionType.OpenFeature -> {
                                    when (actionType.feature) {
                                        "carbon_offset" -> onViewOffsetOptions()
                                    }
                                }
                            }
                        },
                        onDismissAction = { actionId ->
                            actionRegistry.markCompleted(EmptyStateScreenKeys.CARBON, actionId)
                        },
                        actionLabel = stringResource(R.string.carbon_calculate_button),
                        onPrimaryClick = { viewModel.loadReport(selectedPeriod) },
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun CarbonScoreCard(report: CarbonFootprintCalculator.CarbonFootprintReport) {
    val scoreColor = when {
        report.sustainabilityScore >= 70 -> SemanticColors.StatusGreenAlt
        report.sustainabilityScore >= 50 -> SemanticColors.StatusOrangeAlt
        else -> SemanticColors.StatusRedAlt
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
                    stringResource(R.string.carbon_below_target_format, abs(gap))
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
    onViewOffsetOptions: () -> Unit,
    homeCurrency: String
) {
    
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
                    text = stringResource(R.string.carbon_offset_format, report.totalEmissionsKg, CurrencyFormatter.format(report.offsetCost, homeCurrency)),
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
fun AlternativeCard(alternative: CarbonFootprintCalculator.SustainableAlternative, homeCurrency: String) {
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
                Text(
                    text = stringResource(R.string.carbon_reduction_format, alternative.co2Reduction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = stringResource(R.string.carbon_savings_format, CurrencyFormatter.format(alternative.costSavings, homeCurrency)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun formatCategoryName(category: String): String {
    return category.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
}
