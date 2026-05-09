package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.BusinessCategoryTotal
import com.yourname.expensetracker.data.database.dao.BusinessProjectTotal
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MileageTrackingDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MileageTracking
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.DomainTransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

@Singleton
class BusinessExpenseRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val expenseDao: ExpenseDao,
    private val mileageDao: MileageTrackingDao
) {
    
    /**
     * Get all business expenses between two dates.
     * Enforces purchase-only semantics as a safety net over the DAO filter.
     */
    suspend fun getBusinessExpenses(startDate: Long, endDate: Long): List<Expense> {
        return expenseDao.getBusinessExpensesBetween(startDate, endDate)
            .filter { it.transactionType.toDomain().isSpending }
    }
    
    /**
     * Get business expenses as Flow for real-time updates.
     * Enforces purchase-only semantics as a safety net over the DAO filter.
     */
    fun getBusinessExpensesFlow(startDate: Long, endDate: Long): Flow<List<Expense>> {
        return expenseDao.getBusinessExpensesBetweenFlow(startDate, endDate)
            .map { list -> list.filter { it.transactionType.toDomain().isSpending } }
    }
    
    /**
     * Get total business expenses for a period.
     * Delegates to the DAO aggregate query which already enforces PURCHASE-only
     * via the canonical SPENDING_TYPE_SQL predicate.
     */
    suspend fun getTotalBusinessExpenses(startDate: Long, endDate: Long): Double {
        return expenseDao.getTotalBusinessExpensesBetween(startDate, endDate) ?: 0.0
    }
    
    /**
     * Get business expenses grouped by category.
     * Delegates to the DAO aggregate query which already enforces PURCHASE-only
     * via the canonical SPENDING_TYPE_SQL predicate.
     */
    suspend fun getExpensesByCategory(startDate: Long, endDate: Long): List<BusinessCategoryTotal> {
        return expenseDao.getBusinessExpensesByCategory(startDate, endDate)
    }
    
    /**
     * Get business expenses grouped by project.
     * Delegates to the DAO aggregate query which already enforces PURCHASE-only
     * via the canonical SPENDING_TYPE_SQL predicate.
     */
    suspend fun getExpensesByProject(startDate: Long, endDate: Long): List<BusinessProjectTotal> {
        return expenseDao.getBusinessExpensesByProject(startDate, endDate)
    }
    
    /**
     * Get business expenses that are missing receipts (need for tax purposes).
     * Enforces purchase-only semantics as a safety net over the DAO filter.
     */
    suspend fun getExpensesMissingReceipts(startDate: Long, endDate: Long): List<Expense> {
        return expenseDao.getBusinessExpensesMissingReceipts(startDate, endDate)
            .filter { it.transactionType.toDomain().isSpending }
    }
    
    /**
     * Add mileage tracking entry.
     */
    suspend fun addMileage(mileage: MileageTracking): Long {
        writeBarrier.checkWritesAllowed("BusinessExpenseRepository.addMileage")
        validateMileageForInsert(mileage)
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
     *
     * PR-T3: Per-row fallback — if [MileageTracking.calculatedDeduction] is null,
     * computes [MileageTracking.distanceKm] * [MileageTracking.deductionRatePerKm].
     * Filters business trips only (isBusinessTrip = 1), positive distance,
     * and non-negative rate.
     */
    suspend fun getTotalMileageDeduction(startDate: Long, endDate: Long): Double {
        return getBusinessMileageBetween(startDate, endDate)
            .filter { it.distanceKm > 0.0 && it.deductionRatePerKm >= 0.0 }
            .sumOf { it.calculatedDeduction ?: (it.distanceKm * it.deductionRatePerKm) }
    }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }

    private fun validateMileageForInsert(mileage: MileageTracking) {
        require(mileage.date > 0L) { "MileageTracking.date must be > 0" }
        require(mileage.distanceKm.isFinite() && mileage.distanceKm > 0.0) {
            "MileageTracking.distanceKm must be finite and > 0"
        }
        require(mileage.deductionRatePerKm.isFinite() && mileage.deductionRatePerKm > 0.0) {
            "MileageTracking.deductionRatePerKm must be finite and > 0"
        }
        require(mileage.createdAt > 0L) { "MileageTracking.createdAt must be > 0" }

        mileage.startOdometer?.let { start ->
            require(start.isFinite() && start >= 0.0) {
                "MileageTracking.startOdometer must be finite and >= 0 when provided"
            }
        }
        mileage.endOdometer?.let { end ->
            require(end.isFinite() && end >= 0.0) {
                "MileageTracking.endOdometer must be finite and >= 0 when provided"
            }
        }
        if (mileage.startOdometer != null && mileage.endOdometer != null) {
            require(mileage.endOdometer >= mileage.startOdometer) {
                "MileageTracking.endOdometer must be >= startOdometer"
            }
        }

        mileage.calculatedDeduction?.let { deduction ->
            require(deduction.isFinite() && deduction >= 0.0) {
                "MileageTracking.calculatedDeduction must be finite and >= 0 when provided"
            }
        }
        mileage.fuelCost?.let { fuelCost ->
            require(fuelCost.isFinite() && fuelCost >= 0.0) {
                "MileageTracking.fuelCost must be finite and >= 0 when provided"
            }
        }
    }
}
