package com.yourname.expensetracker.ui.screens.cashflow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.cashflow.CashFlowRiskLevel
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashFlowCalendarScreen(
    onNavigateBack: () -> Unit,
    viewModel: CashFlowCalendarViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val dayFormat = remember { SimpleDateFormat("d", Locale.getDefault()) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_cash_flow_calendar)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadCurrentMonth() }) {
                        Icon(Icons.Default.Today, contentDescription = stringResource(R.string.calendar_nav_today))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Month Navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.navigateToPreviousMonth() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.calendar_nav_previous))
                }
                
                Text(
                    text = dateFormat.format(state.currentMonth),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(onClick = { viewModel.navigateToNextMonth() }) {
                    Icon(Icons.Default.ArrowForward, contentDescription = stringResource(R.string.calendar_nav_next))
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Upcoming Bills Alert
            if (state.upcomingBillsCount > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.calendar_alert_bills_due, state.upcomingBillsCount),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Starting Balance Input
            OutlinedTextField(
                value = state.startingBalance.toString(),
                onValueChange = { 
                    it.toDoubleOrNull()?.let { balance ->
                        viewModel.setStartingBalance(balance)
                    }
                },
                label = { Text(stringResource(R.string.calendar_label_starting_balance)) },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Weekday Headers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf(
                    stringResource(R.string.calendar_weekday_sun),
                    stringResource(R.string.calendar_weekday_mon),
                    stringResource(R.string.calendar_weekday_tue),
                    stringResource(R.string.calendar_weekday_wed),
                    stringResource(R.string.calendar_weekday_thu),
                    stringResource(R.string.calendar_weekday_fri),
                    stringResource(R.string.calendar_weekday_sat)
                ).forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Calendar Grid
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val calendar = Calendar.getInstance()
                calendar.time = state.currentMonth
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                
                // Get first day of week
                val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                
                // Create list with empty cells for alignment
                val days = mutableListOf<DayCell?>()
                
                // Add empty cells for days before start of month
                repeat(firstDayOfWeek - 1) { days.add(null) }
                
                // Add actual days
                repeat(daysInMonth) { day ->
                    calendar.set(Calendar.DAY_OF_MONTH, day + 1)
                    val date = calendar.time
                    val cashFlow = state.dailyCashFlows.find { 
                        val cashFlowCal = Calendar.getInstance().apply { time = it.date }
                        cashFlowCal.get(Calendar.DAY_OF_MONTH) == day + 1
                    }
                    days.add(DayCell(day + 1, date, cashFlow))
                }
                
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(days) { dayCell ->
                        if (dayCell != null) {
                            DayCellView(
                                dayCell = dayCell,
                                isSelected = state.selectedDate?.let { selected ->
                                    val selCal = Calendar.getInstance().apply { time = selected }
                                    selCal.get(Calendar.DAY_OF_MONTH) == dayCell.day
                                } ?: false,
                                onClick = { viewModel.selectDate(dayCell.date) }
                            )
                        } else {
                            Box(modifier = Modifier.aspectRatio(1f))
                        }
                    }
                }
            }
        }
    }
}

data class DayCell(
    val day: Int,
    val date: Date,
    val cashFlow: com.yourname.expensetracker.domain.cashflow.DailyCashFlow?
)

@Composable
private fun DayCellView(
    dayCell: DayCell,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        dayCell.cashFlow == null -> MaterialTheme.colorScheme.surface
        else -> getRiskColor(dayCell.cashFlow.riskLevel)
    }
    
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(backgroundColor, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = dayCell.day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            
            if (dayCell.cashFlow != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "€${String.format("%.0f", dayCell.cashFlow.endingBalance)}",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                
                // Mini indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (dayCell.cashFlow.income.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(SemanticColors.StatusGreen, MaterialTheme.shapes.extraSmall)
                        )
                    }
                    if (dayCell.cashFlow.expenses.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(SemanticColors.StatusRed, MaterialTheme.shapes.extraSmall)
                        )
                    }
                    if (dayCell.cashFlow.predictedRecurring.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(SemanticColors.StatusYellow, MaterialTheme.shapes.extraSmall)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun getRiskColor(riskLevel: CashFlowRiskLevel): Color {
    return when (riskLevel) {
        CashFlowRiskLevel.NONE -> SemanticColors.StatusGreenLight
        CashFlowRiskLevel.LOW -> SemanticColors.StatusYellowLight
        CashFlowRiskLevel.MEDIUM -> SemanticColors.StatusOrangeLight
        CashFlowRiskLevel.HIGH -> SemanticColors.StatusRed.copy(alpha = 0.2f)
    }
}
