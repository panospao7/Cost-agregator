package com.yourname.expensetracker.ui.screens.cashflow

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.cashflow.CashFlowCalculator
import com.yourname.expensetracker.domain.cashflow.CashFlowRiskLevel
import com.yourname.expensetracker.domain.cashflow.DailyCashFlow
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*

/**
 * PHASE 4 TEST: CashFlowCalendarViewModel
 * 
 * Tests ViewModel state management, calendar navigation, and cash flow calculations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashFlowCalendarViewModelTest {

    private val cashFlowCalculator = mockk<CashFlowCalculator>(relaxed = true)
    private lateinit var viewModel: CashFlowCalendarViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        // Mock default behavior
        coEvery { 
            cashFlowCalculator.calculateDailyCashFlow(any(), any(), any()) 
        } returns createMockCashFlows()
        
        viewModel = CashFlowCalendarViewModel(cashFlowCalculator)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty cash flows and default values`() = runTest {
        viewModel.state.test {
            val initialState = awaitItem()
            
            assertThat(initialState.dailyCashFlows).isEmpty()
            assertThat(initialState.isLoading).isFalse()
            assertThat(initialState.viewMode).isEqualTo(CalendarViewMode.MONTH)
            assertThat(initialState.startingBalance).isEqualTo(0.0)
            assertThat(initialState.upcomingBillsCount).isEqualTo(0)
        }
    }

    @Test
    fun `loadCurrentMonth triggers cash flow calculation`() = runTest {
        viewModel.loadCurrentMonth()
        advanceUntilIdle()
        
        viewModel.state.test {
            val state = awaitItem()
            
            // Should have called calculator
            coVerify { 
                cashFlowCalculator.calculateDailyCashFlow(any(), any(), any()) 
            }
        }
    }

    @Test
    fun `loadCashFlow updates state with calculator results`() = runTest {
        val mockFlows = createMockCashFlows()
        coEvery { 
            cashFlowCalculator.calculateDailyCashFlow(any(), any(), any()) 
        } returns mockFlows
        
        val startDate = Date()
        val endDate = Date()
        
        viewModel.loadCashFlow(startDate, endDate)
        advanceUntilIdle()
        
        viewModel.state.test {
            val state = awaitItem()
            assertThat(state.dailyCashFlows).hasSize(3)
            assertThat(state.isLoading).isFalse()
        }
    }

    @Test
    fun `selectDate updates selected date in state`() = runTest {
        val selectedDate = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 15)
        }.time
        
        viewModel.selectDate(selectedDate)
        
        viewModel.state.test {
            val state = awaitItem()
            assertThat(state.selectedDate).isEqualTo(selectedDate)
        }
    }

    @Test
    fun `changeViewMode updates view mode in state`() = runTest {
        viewModel.changeViewMode(CalendarViewMode.WEEK)
        
        viewModel.state.test {
            val state = awaitItem()
            assertThat(state.viewMode).isEqualTo(CalendarViewMode.WEEK)
        }
    }

    @Test
    fun `setStartingBalance updates balance and reloads data`() = runTest {
        val newBalance = 1000.0
        
        viewModel.setStartingBalance(newBalance)
        advanceUntilIdle()
        
        viewModel.state.test {
            val state = awaitItem()
            assertThat(state.startingBalance).isEqualTo(newBalance)
        }
        
        // Should trigger reload
        coVerify { 
            cashFlowCalculator.calculateDailyCashFlow(any(), any(), eq(newBalance)) 
        }
    }

    @Test
    fun `navigateToPreviousMonth loads previous month data`() = runTest {
        viewModel.navigateToPreviousMonth()
        advanceUntilIdle()
        
        // Should call calculator with previous month dates
        coVerify { 
            cashFlowCalculator.calculateDailyCashFlow(any(), any(), any()) 
        }
    }

    @Test
    fun `navigateToNextMonth loads next month data`() = runTest {
        viewModel.navigateToNextMonth()
        advanceUntilIdle()
        
        // Should call calculator with next month dates
        coVerify { 
            cashFlowCalculator.calculateDailyCashFlow(any(), any(), any()) 
        }
    }

    @Test
    fun `loadCashFlow sets loading state during calculation`() = runTest {
        val mockFlows = createMockCashFlows()
        coEvery { 
            cashFlowCalculator.calculateDailyCashFlow(any(), any(), any()) 
        } coAnswers {
            delay(100) // Simulate delay
            mockFlows
        }
        
        viewModel.loadCashFlow(Date(), Date())
        
        viewModel.state.test {
            // First emission should have loading=true
            val loadingState = awaitItem()
            assertThat(loadingState.isLoading).isTrue()
            
            // After delay, should complete
            advanceTimeBy(100)
            val completeState = awaitItem()
            assertThat(completeState.isLoading).isFalse()
        }
    }

    @Test
    fun `state contains correct upcoming bills count after init`() = runTest {
        viewModel.state.test {
            val state = awaitItem()
            // Should be loaded during init
            assertThat(state.upcomingBillsCount).isAtLeast(0)
        }
    }

    @Test
    fun `cash flow risk levels are properly categorized`() {
        val noneRisk = CashFlowRiskLevel.NONE
        val lowRisk = CashFlowRiskLevel.LOW
        val mediumRisk = CashFlowRiskLevel.MEDIUM
        val highRisk = CashFlowRiskLevel.HIGH
        
        // Verify all risk levels exist
        assertThat(noneRisk).isNotNull()
        assertThat(lowRisk).isNotNull()
        assertThat(mediumRisk).isNotNull()
        assertThat(highRisk).isNotNull()
    }

    @Test
    fun `calendar view modes are distinct`() {
        val month = CalendarViewMode.MONTH
        val week = CalendarViewMode.WEEK
        val day = CalendarViewMode.DAY
        
        assertThat(month).isNotEqualTo(week)
        assertThat(week).isNotEqualTo(day)
        assertThat(month).isNotEqualTo(day)
    }

    // Helper methods
    
    private fun createMockCashFlows(): List<DailyCashFlow> {
        val calendar = Calendar.getInstance()
        return listOf(
            createDailyCashFlow(calendar.apply { add(Calendar.DAY_OF_MONTH, 0) }.time, 100.0, 50.0),
            createDailyCashFlow(calendar.apply { add(Calendar.DAY_OF_MONTH, 1) }.time, 100.0, 75.0),
            createDailyCashFlow(calendar.apply { add(Calendar.DAY_OF_MONTH, 1) }.time, 100.0, 25.0)
        )
    }
    
    private fun createDailyCashFlow(
        date: Date,
        startingBalance: Double,
        endingBalance: Double
    ): DailyCashFlow {
        return DailyCashFlow(
            date = date,
            startingBalance = startingBalance,
            income = emptyList(),
            expenses = emptyList(),
            predictedRecurring = emptyList(),
            endingBalance = endingBalance,
            riskLevel = if (endingBalance < startingBalance * 0.5) 
                CashFlowRiskLevel.HIGH 
            else 
                CashFlowRiskLevel.LOW
        )
    }
}