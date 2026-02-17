package com.yourname.expensetracker.domain.budget

import android.app.NotificationManager
import android.content.Context
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.BudgetRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetMonitorTest {

    private val context = mockk<Context>(relaxed = true)
    private val budgetDao = mockk<BudgetDao>(relaxed = true)
    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    
    private lateinit var monitor: BudgetMonitor
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        // Mock NotificationManager to avoid ClassCastException
        val notificationManager = mockk<android.app.NotificationManager>(relaxed = true)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        
        monitor = BudgetMonitor(context, budgetDao, budgetRepository, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `checkBudgets triggers warning notification when threshold exceeded`() = runTest {
        val budget = Budget(
            id = 1,
            amount = 100.0,
            categoryId = 1,
            period = BudgetPeriod.MONTHLY,
            startDate = System.currentTimeMillis(),
            notifyAtWarning = 0.5f, // 50%
            notifyAtCritical = 0.9f,
            lastWarningNotifiedAt = null
        )
        
        val status = BudgetStatus(
            budget = budget,
            category = Category(id=1, name="Groceries", icon="", color=""),
            spentAmount = 60.0, // 60%
            remainingAmount = 40.0,
            percentUsed = 0.6f,
            healthStatus = BudgetHealthStatus.WARNING,
            periodStart = 0L,
            periodEnd = 0L
        )

        // Mock repository returning the calculated status
        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))
        
        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Verify notification fired (DAO update called)
        coVerify(timeout = 1000) { 
            budgetDao.updateWarningNotification(1, any()) 
        }
    }

    @Test
    fun `checkBudgets does NOT notify if cooldown is active`() = runTest {
        val now = System.currentTimeMillis()
        val recentReset = now - (1 * 60 * 60 * 1000) // 1 hour ago
        
        val budget = Budget(
            id = 1,
            amount = 100.0,
            categoryId = 1,
            period = BudgetPeriod.MONTHLY,
            startDate = now,
            notifyAtWarning = 0.5f,
            lastWarningNotifiedAt = recentReset // Cooldown active!
        )
        
        val status = BudgetStatus(
            budget = budget,
            category = Category(id=1, name="Groceries", icon="", color=""),
            spentAmount = 60.0, // 60%
            remainingAmount = 40.0,
            percentUsed = 0.6f,
            healthStatus = BudgetHealthStatus.WARNING,
            periodStart = 0L,
            periodEnd = 0L
        )

        coEvery { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))
        
        monitor.checkBudgets()
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Should NOT update notification time
        coVerify(exactly = 0) { 
            budgetDao.updateWarningNotification(any(), any()) 
        }
    }
}
