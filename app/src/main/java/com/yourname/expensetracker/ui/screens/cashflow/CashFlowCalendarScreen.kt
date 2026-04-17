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
import com.yourname.expensetracker.domain.cashflow.DailyCashFlow
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.ui.theme.SemanticColors
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashFlowCalendarScreen(
    onNavigateBack: () -> Unit,
    viewModel: CashFlowCalendarViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val selectedDateFormat = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }
    val dailyCashFlowByDate = remember(state.dailyCashFlows) {
        state.dailyCashFlows.associateBy { normalizeDateKey(it.date) }
    }
    val selectedCashFlow = state.selectedDate?.let { selectedDate ->
        dailyCashFlowByDate[normalizeDateKey(selectedDate)]
    }
    
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
                    val cashFlow = dailyCashFlowByDate[normalizeDateKey(date)]
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
                                    normalizeDateKey(selected) == normalizeDateKey(dayCell.date)
                                } ?: false,
                                onClick = { viewModel.selectDate(dayCell.date) }
                            )
                        } else {
                            Box(modifier = Modifier.aspectRatio(1f))
                        }
                    }
                }
            }

            state.selectedDate?.let { selectedDate ->
                ModalBottomSheet(
                    onDismissRequest = { viewModel.selectDate(null) }
                ) {
                    DailyCashFlowDetails(
                        selectedDateLabel = selectedDateFormat.format(selectedDate),
                        cashFlow = selectedCashFlow
                    )
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
                    text = CurrencyFormatter.format(dayCell.cashFlow.endingBalance, showCents = false),
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

private fun normalizeDateKey(date: Date): Long {
    return Calendar.getInstance().run {
        time = date
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }
}

@Composable
private fun DailyCashFlowDetails(
    selectedDateLabel: String,
    cashFlow: DailyCashFlow?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = selectedDateLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (cashFlow == null) {
            Text(
                text = "No cash flow details for this day.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            return
        }

        val incomeTotal = cashFlow.income.sumOf { abs(it.amount) }
        val expensesTotal = cashFlow.expenses.sumOf { it.amount }
        val recurringTotal = cashFlow.predictedRecurring.sumOf { it.averageAmount }

        Text(
            text = "Ending balance: ${CurrencyFormatter.format(cashFlow.endingBalance)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Income: ${CurrencyFormatter.format(incomeTotal)}",
            style = MaterialTheme.typography.bodyMedium,
            color = SemanticColors.StatusGreen
        )
        Text(
            text = "Expenses: ${CurrencyFormatter.format(expensesTotal)}",
            style = MaterialTheme.typography.bodyMedium,
            color = SemanticColors.StatusRed
        )
        Text(
            text = "Recurring: ${CurrencyFormatter.format(recurringTotal)}",
            style = MaterialTheme.typography.bodyMedium,
            color = SemanticColors.StatusYellow
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (cashFlow.income.isNotEmpty()) {
            Text(text = "Income items", fontWeight = FontWeight.SemiBold)
            cashFlow.income.take(3).forEach { income ->
                Text(
                    text = "• ${income.merchant}: +${CurrencyFormatter.format(abs(income.amount))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (cashFlow.expenses.isNotEmpty()) {
            Text(text = "Expense items", fontWeight = FontWeight.SemiBold)
            cashFlow.expenses.take(3).forEach { expense ->
                Text(
                    text = "• ${expense.merchant}: -${CurrencyFormatter.format(expense.amount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (cashFlow.predictedRecurring.isNotEmpty()) {
            Text(text = "Recurring items", fontWeight = FontWeight.SemiBold)
            cashFlow.predictedRecurring.take(3).forEach { recurring ->
                Text(
                    text = "• ${recurring.merchantName}: -${CurrencyFormatter.format(recurring.averageAmount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
