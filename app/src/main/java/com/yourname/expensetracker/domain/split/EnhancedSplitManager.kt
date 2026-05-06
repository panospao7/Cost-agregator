package com.yourname.expensetracker.domain.split

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.SplitItemAssignmentDao
import com.yourname.expensetracker.data.database.dao.SplitTemplateDao
import com.yourname.expensetracker.data.database.entity.SplitItemAssignment
import com.yourname.expensetracker.data.database.entity.SplitShare
import com.yourname.expensetracker.data.database.entity.SplitTemplate
import com.yourname.expensetracker.domain.util.Money
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.sum
import com.yourname.expensetracker.domain.util.toMoney
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HIGH FIX (HIGH-2): Updated to use Money class for precise calculations.
 * 
 * Replaces Double arithmetic with BigDecimal-based Money class to prevent
 * rounding errors in split calculations.
 */
@Singleton
class EnhancedSplitManager @Inject constructor(
    private val splitTemplateDao: SplitTemplateDao,
    private val splitItemAssignmentDao: SplitItemAssignmentDao,
    private val gson: Gson,
    private val timeProvider: TimeProvider,
    /** SHR-13: Required for transactional item assignment. */
    private val database: AppDatabase
) {
    
    /**
     * Get all split templates as a flow.
     */
    fun getAllTemplates(): Flow<List<SplitTemplate>> {
        return splitTemplateDao.getAllTemplates()
    }
    
    /**
     * HIGH FIX: Equal split calculation using Money for precision.
     * Ensures sum of all shares equals total amount exactly.
     */
    fun calculateEqualSplit(totalAmount: Double, numParticipants: Int): List<Double> {
        val total = totalAmount.toMoney()
        val baseShare = total.divide(numParticipants)
        
        // All shares get the same base amount
        val shares = List(numParticipants) { baseShare }
        
        // Calculate rounding remainder
        val sumOfShares = shares.sum()
        val remainder = total - sumOfShares
        
        // Adjust first share to account for remainder
        return if (!remainder.isZero() && shares.isNotEmpty()) {
            val adjusted = shares.toMutableList()
            adjusted[0] = adjusted[0] + remainder
            adjusted.map { it.toDouble() }
        } else {
            shares.map { it.toDouble() }
        }
    }
    
    /**
     * HIGH FIX: Percentage split using Money for precision.
     */
    fun calculatePercentageSplit(totalAmount: Double, percentages: List<Double>): List<Double> {
        val total = totalAmount.toMoney()
        return percentages.map { percent ->
            total.percentage(percent).toDouble()
        }
    }
    
    /**
     * HIGH FIX: Custom split sum using Money.
     */
    fun calculateCustomSplit(amounts: List<Double>): Double {
        return amounts.map { it.toMoney() }.sum().toDouble()
    }
    
    /**
     * HIGH FIX: Visual split data generation using Money.
     */
    fun generateVisualSplitData(
        totalAmount: Double,
        shares: List<SplitShare>,
        splitType: SplitTemplate.SplitType
    ): VisualSplitData {
        val total = totalAmount.toMoney()
        
        val calculatedAmounts = when (splitType) {
            SplitTemplate.SplitType.EQUAL -> {
                val equalShares = calculateEqualSplit(totalAmount, shares.size)
                shares.mapIndexed { index, share ->
                    share.participantName to (equalShares.getOrNull(index) ?: 0.0)
                }
            }
            SplitTemplate.SplitType.PERCENTAGE -> {
                shares.map { share ->
                    val amount = share.percentage?.let { 
                        total.percentage(it).toDouble() 
                    } ?: 0.0
                    share.participantName to amount
                }
            }
            SplitTemplate.SplitType.CUSTOM_AMOUNT, SplitTemplate.SplitType.UNEQUAL -> {
                shares.map { share ->
                    share.participantName to (share.amount ?: 0.0)
                }
            }
        }
        
        val totalAssigned = calculatedAmounts.sumOf { it.second }.toMoney()
        val remaining = total - totalAssigned
        
        return VisualSplitData(
            totalAmount = totalAmount,
            assignedAmount = totalAssigned.toDouble(),
            remainingAmount = remaining.toDouble(),
            segments = calculatedAmounts.mapIndexed { index, (name, amount) ->
                SplitSegment(
                    participantName = name,
                    amount = amount,
                    percentage = if (totalAmount > 0) (amount / totalAmount * 100) else 0.0,
                    color = shares.getOrNull(index)?.color ?: getDefaultColor(index),
                    index = index
                )
            }
        )
    }
    
    /**
     * Create a new split template with validation (SHR-14).
     *
     * @throws IllegalArgumentException if the template data is invalid.
     */
    suspend fun createTemplate(
        name: String,
        totalSplits: Int,
        splitType: SplitTemplate.SplitType,
        shares: List<SplitShare>
    ): Long {
        validateTemplate(name, totalSplits, splitType, shares)
        val template = SplitTemplate(
            name = name,
            totalSplits = totalSplits,
            splitType = splitType,
            shares = gson.toJson(shares),
            isDefault = false,
            useCount = 0
        )
        return splitTemplateDao.insertTemplate(template)
    }

    /**
     * SHR-14: Validates split template parameters. Throws [IllegalArgumentException]
     * if any field violates business rules.
     */
    private fun validateTemplate(
        name: String,
        totalSplits: Int,
        splitType: SplitTemplate.SplitType,
        shares: List<SplitShare>
    ) {
        require(name.isNotBlank()) { "Template name must not be blank" }
        require(name.length <= 100) { "Template name must not exceed 100 characters" }
        require(totalSplits >= 2) { "Template must have at least 2 splits" }
        require(totalSplits <= 50) { "Template must not exceed 50 splits" }
        require(shares.size == totalSplits) {
            "Number of shares ($shares.size) must match totalSplits ($totalSplits)"
        }

        when (splitType) {
            SplitTemplate.SplitType.EQUAL -> {
                // No specific share validation needed for equal split
            }
            SplitTemplate.SplitType.PERCENTAGE -> {
                val totalPercentage = shares.sumOf { it.percentage ?: 0.0 }
                require(totalPercentage in 99.0..101.0) {
                    "Percentages must sum to ~100% (got $totalPercentage%)"
                }
                shares.forEachIndexed { index, share ->
                    val pct = share.percentage ?: 0.0
                    require(pct in 0.0..100.0) {
                        "Share $index percentage $pct must be between 0 and 100"
                    }
                }
            }
            SplitTemplate.SplitType.CUSTOM_AMOUNT, SplitTemplate.SplitType.UNEQUAL -> {
                shares.forEachIndexed { index, share ->
                    val amount = share.amount ?: 0.0
                    require(amount >= 0.0) {
                        "Share $index amount $amount must be non-negative"
                    }
                }
            }
        }

        shares.forEachIndexed { index, share ->
            require(share.participantName.isNotBlank()) {
                "Share $index participant name must not be blank"
            }
        }
    }
    
    /**
     * Get a specific template by ID.
     */
    suspend fun getTemplateById(templateId: Long): SplitTemplate? {
        return splitTemplateDao.getTemplateById(templateId)
    }
    
    suspend fun updateTemplate(template: SplitTemplate) {
        splitTemplateDao.updateTemplate(template.copy(updatedAt = timeProvider.now()))
    }
    
    suspend fun deleteTemplate(template: SplitTemplate) {
        splitTemplateDao.deleteTemplate(template)
    }
    
    suspend fun setDefaultTemplate(templateId: Long) {
        splitTemplateDao.clearDefaultTemplate()
        splitTemplateDao.setDefaultTemplate(templateId)
    }
    
    suspend fun useTemplate(templateId: Long) {
        splitTemplateDao.incrementUseCount(templateId, timeProvider.now())
    }
    
    fun parseShares(template: SplitTemplate): List<SplitShare> {
        val type = object : TypeToken<List<SplitShare>>() {}.type
        return gson.fromJson(template.shares, type) ?: emptyList()
    }
    
    private fun getDefaultColor(index: Int): String {
        val colors = listOf(
            "#FF6B6B", "#4ECDC4", "#45B7D1", "#FFA07A", "#98D8C8",
            "#F7DC6F", "#BB8FCE", "#85C1E2", "#F8C471", "#82E0AA"
        )
        return colors.getOrElse(index) { colors[0] }
    }
    
    // Item Assignment for Receipt Splitting
    /**
     * SHR-13: Wrapped in a Room transaction to ensure atomicity.
     * If the insertion fails after clearing old assignments, the entire
     * operation rolls back, preventing orphaned expense items.
     */
    suspend fun assignItemsToParticipants(
        expenseId: Long,
        assignments: List<ItemAssignment>
    ) {
        database.withTransaction {
            // Clear existing assignments
            splitItemAssignmentDao.deleteAllForExpense(expenseId)

            // Create new assignments
            val entities = assignments.mapIndexed { index, assignment ->
                SplitItemAssignment(
                    expenseId = expenseId,
                    receiptItemId = assignment.receiptItemId,
                    participantName = assignment.participantName,
                    participantIndex = index,
                    assignedAmount = assignment.amount,
                    isPaid = false
                )
            }

            splitItemAssignmentDao.insertAssignments(entities)
        }
        Timber.d("assignItemsToParticipants: assigned %d items to participants for expense %d (transactional)", assignments.size, expenseId)
    }
    
    suspend fun getAssignmentsForExpense(expenseId: Long): List<SplitItemAssignment> {
        return splitItemAssignmentDao.getAssignmentsForExpenseSync(expenseId)
    }
    
    suspend fun getParticipantTotals(expenseId: Long): List<SplitItemAssignmentDao.ParticipantTotal> {
        return splitItemAssignmentDao.getParticipantTotals(expenseId)
    }
    
    suspend fun markAssignmentAsPaid(assignmentId: Long) {
        splitItemAssignmentDao.markAsPaid(assignmentId, timeProvider.now())
    }
    
    data class VisualSplitData(
        val totalAmount: Double,
        val assignedAmount: Double,
        val remainingAmount: Double,
        val segments: List<SplitSegment>
    )
    
    data class SplitSegment(
        val participantName: String,
        val amount: Double,
        val percentage: Double,
        val color: String,
        val index: Int
    )
    
    data class ItemAssignment(
        val receiptItemId: Long?,
        val participantName: String,
        val amount: Double
    )
}