package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.BusinessCategoryTotal
import com.yourname.expensetracker.data.database.dao.BusinessProjectTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MileageTrackingDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MileageTracking
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BusinessExpenseRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val mileageDao: MileageTrackingDao
) {
    
    /**
     * Get all business expenses between two dates.
     */
    suspend fun getBusinessExpenses(startDate: Long, endDate: Long): List<Expense> {
        return expenseDao.getBusinessExpensesBetween(startDate, endDate)
    }
    
    /**
     * Get business expenses as Flow for real-time updates.
     */
    fun getBusinessExpensesFlow(startDate: Long, endDate: Long): Flow<List<Expense>> {
        return expenseDao.getBusinessExpensesBetweenFlow(startDate, endDate)
    }
    
    /**
     * Get total business expenses for a period.
     */
    suspend fun getTotalBusinessExpenses(startDate: Long, endDate: Long): Double {
        return expenseDao.getTotalBusinessExpensesBetween(startDate, endDate) ?: 0.0
    }
    
    /**
     * Get business expenses grouped by category.
     */
    suspend fun getExpensesByCategory(startDate: Long, endDate: Long): List<BusinessCategoryTotal> {
        return expenseDao.getBusinessExpensesByCategory(startDate, endDate)
    }
    
    /**
     * Get business expenses grouped by project.
     */
    suspend fun getExpensesByProject(startDate: Long, endDate: Long): List<BusinessProjectTotal> {
        return expenseDao.getBusinessExpensesByProject(startDate, endDate)
    }
    
    /**
     * Get business expenses that are missing receipts (need for tax purposes).
     */
    suspend fun getExpensesMissingReceipts(startDate: Long, endDate: Long): List<Expense> {
        return expenseDao.getBusinessExpensesMissingReceipts(startDate, endDate)
    }
    
    /**
     * Add mileage tracking entry.
     */
    suspend fun addMileage(mileage: MileageTracking): Long {
        return mileageDao.insert(mileage)
    }
    
    /**
     * Get all business mileage entries.
     */
    fun getAllMileage(): Flow<List<MileageTracking>> {
        return mileageDao.getAllMileage()
    }
    
    /**
     * Get business mileage for a period.
     */
    suspend fun getBusinessMileageBetween(startDate: Long, endDate: Long): List<MileageTracking> {
        return mileageDao.getBusinessMileageBetween(startDate, endDate)
    }
    
    /**
     * Get total business distance for a period.
     */
    suspend fun getTotalBusinessDistance(startDate: Long, endDate: Long): Double {
        return mileageDao.getTotalBusinessDistanceBetween(startDate, endDate) ?: 0.0
    }
    
    /**
     * Get total mileage deduction for a period.
     */
    suspend fun getTotalMileageDeduction(startDate: Long, endDate: Long): Double {
        return mileageDao.getTotalDeductionBetween(startDate, endDate) ?: 0.0
    }
}
