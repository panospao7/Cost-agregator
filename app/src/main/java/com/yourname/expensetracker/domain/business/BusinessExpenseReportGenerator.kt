package com.yourname.expensetracker.domain.business

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MileageTracking
import com.yourname.expensetracker.data.repository.BusinessExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class BusinessExpenseReport(
    val startDate: Long,
    val endDate: Long,
    val generatedAt: Long,
    val totalExpenses: Double,
    val totalDeductibleExpenses: Double,
    val expensesByCategory: Map<String, Double>,
    val expensesByProject: Map<String, Double>,
    val mileageReport: MileageReport,
    val expensesMissingReceipts: List<Expense>,
    val topExpenses: List<Expense>,
    val formattedReport: String
)

data class MileageReport(
    val totalDistanceKm: Double,
    val totalDeduction: Double,
    val deductionRatePerKm: Double,
    val tripCount: Int,
    val trips: List<MileageTracking>
)

@Singleton
class BusinessExpenseReportGenerator @Inject constructor(
    private val businessExpenseRepository: BusinessExpenseRepository,
    private val timeProvider: TimeProvider
) {
    
    /**
     * Generate a comprehensive business expense report for tax purposes.
     */
    suspend fun generateReport(
        startDate: Long,
        endDate: Long,
        includeMileage: Boolean = true
    ): BusinessExpenseReport = withContext(Dispatchers.IO) {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        
        // Get all business expenses
        val expenses = businessExpenseRepository.getBusinessExpenses(startDate, endDate)
        
        // Calculate totals
        var totalExpenses = 0.0
        for (expense in expenses) {
            totalExpenses += expense.amount
        }
        
        // Group by category
        val categoryTotals = businessExpenseRepository.getExpensesByCategory(startDate, endDate)
        val expensesByCategory = mutableMapOf<String, Double>()
        for (total in categoryTotals) {
            expensesByCategory[total.businessCategory] = total.total
        }
        
        // Group by project
        val projectTotals = businessExpenseRepository.getExpensesByProject(startDate, endDate)
        val expensesByProject = mutableMapOf<String, Double>()
        for (total in projectTotals) {
            expensesByProject[total.businessProject] = total.total
        }
        
        // Get expenses missing receipts
        val missingReceipts = businessExpenseRepository.getExpensesMissingReceipts(startDate, endDate)
        
        // Get top expenses (top 10)
        val sortedExpenses = expenses.sortedByDescending { it.amount }
        val topExpenses = if (sortedExpenses.size > 10) {
            sortedExpenses.subList(0, 10)
        } else sortedExpenses
        
        // Get mileage report if requested
        val mileageReport = if (includeMileage) {
            generateMileageReport(startDate, endDate)
        } else {
            MileageReport(0.0, 0.0, 0.0, 0, emptyList())
        }
        
        // Calculate total deductible (expenses + mileage)
        val totalDeductible = totalExpenses + mileageReport.totalDeduction
        
        // Generate formatted report
        val formattedReport = buildString {
            append("\n")
            append("========================================\n")
            append("BUSINESS EXPENSE REPORT\n")
            append("========================================\n")
            append("Period: ${dateFormat.format(Date(startDate))} - ${dateFormat.format(Date(endDate))}\n")
            append("Generated: ${dateFormat.format(Date(timeProvider.now()))}\n")
            append("\n")
            append("SUMMARY\n")
            append("----------------------------------------\n")
            append("Total Business Expenses: €${String.format("%.2f", totalExpenses)}\n")
            append("Total Mileage Deduction: €${String.format("%.2f", mileageReport.totalDeduction)}\n")
            append("Total Deductible Amount: €${String.format("%.2f", totalDeductible)}\n")
            append("\n")
            
            if (expensesByCategory.isNotEmpty()) {
                append("EXPENSES BY CATEGORY\n")
                append("----------------------------------------\n")
                val sortedCategories = expensesByCategory.toList().sortedByDescending { it.second }
                for ((category, amount) in sortedCategories) {
                    append("${category}: €${String.format("%.2f", amount)}\n")
                }
                append("\n")
            }
            
            if (expensesByProject.isNotEmpty()) {
                append("EXPENSES BY PROJECT\n")
                append("----------------------------------------\n")
                val sortedProjects = expensesByProject.toList().sortedByDescending { it.second }
                for ((project, amount) in sortedProjects) {
                    append("${project}: €${String.format("%.2f", amount)}\n")
                }
                append("\n")
            }
            
            if (includeMileage && mileageReport.totalDistanceKm > 0) {
                append("MILEAGE DEDUCTION\n")
                append("----------------------------------------\n")
                append("Total Distance: ${String.format("%.1f", mileageReport.totalDistanceKm)} km\n")
                append("Deduction Rate: €${String.format("%.2f", mileageReport.deductionRatePerKm)}/km\n")
                append("Total Mileage Deduction: €${String.format("%.2f", mileageReport.totalDeduction)}\n")
                append("Number of Trips: ${mileageReport.tripCount}\n")
                append("\n")
            }
            
            if (topExpenses.isNotEmpty()) {
                append("TOP 10 EXPENSES\n")
                append("----------------------------------------\n")
                for ((index, expense) in topExpenses.withIndex()) {
                    val date = dateFormat.format(Date(expense.date))
                    val merchant = expense.merchant.take(25)
                    val purpose = expense.businessPurpose?.let { " - $it" } ?: ""
                    append("${index + 1}. ${date} - €${String.format("%.2f", expense.amount)} - ${merchant}${purpose}\n")
                }
                append("\n")
            }
            
            if (missingReceipts.isNotEmpty()) {
                append("⚠️ EXPENSES MISSING RECEIPTS\n")
                append("----------------------------------------\n")
                for (expense in missingReceipts) {
                    val date = dateFormat.format(Date(expense.date))
                    append("${date} - €${String.format("%.2f", expense.amount)} - ${expense.merchant}\n")
                }
                append("\n")
            }
            
            append("========================================\n")
            append("End of Report\n")
            append("========================================\n")
        }
        
        BusinessExpenseReport(
            startDate = startDate,
            endDate = endDate,
            generatedAt = timeProvider.now(),
            totalExpenses = totalExpenses,
            totalDeductibleExpenses = totalDeductible,
            expensesByCategory = expensesByCategory,
            expensesByProject = expensesByProject,
            mileageReport = mileageReport,
            expensesMissingReceipts = missingReceipts,
            topExpenses = topExpenses,
            formattedReport = formattedReport
        )
    }
    
    /**
     * Generate a mileage report for a period.
     */
    private suspend fun generateMileageReport(startDate: Long, endDate: Long): MileageReport {
        val trips = businessExpenseRepository.getBusinessMileageBetween(startDate, endDate)
        
        var totalDistance = 0.0
        var totalDeduction = 0.0
        
        for (trip in trips) {
            totalDistance += trip.distanceKm
            totalDeduction += trip.calculatedDeduction ?: (trip.distanceKm * trip.deductionRatePerKm)
        }
        
        val rate = if (trips.isNotEmpty()) trips.first().deductionRatePerKm else 0.30
        
        return MileageReport(
            totalDistanceKm = totalDistance,
            totalDeduction = totalDeduction,
            deductionRatePerKm = rate,
            tripCount = trips.size,
            trips = trips
        )
    }
    
    /**
     * Generate a CSV export of business expenses for tax filing.
     */
    suspend fun generateCSVExport(
        startDate: Long,
        endDate: Long,
        includeMileage: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val csv = StringBuilder()
        
        // Header
        csv.append("Date,Merchant,Amount,Currency,Business Category,Business Purpose,Project,Requires Receipt,Notes\n")
        
        // Expenses
        val expenses = businessExpenseRepository.getBusinessExpenses(startDate, endDate)
        for (expense in expenses) {
            val date = dateFormat.format(Date(expense.date))
            val merchant = escapeCSV(expense.merchant)
            val category = escapeCSV(expense.businessCategory ?: "")
            val purpose = escapeCSV(expense.businessPurpose ?: "")
            val project = escapeCSV(expense.businessProject ?: "")
            val requiresReceipt = if (expense.requiresReceipt) "Yes" else "No"
            val notes = escapeCSV(expense.notes ?: "")
            
            csv.append("${date},${merchant},${expense.amount},${expense.currency},${category},${purpose},${project},${requiresReceipt},${notes}\n")
        }
        
        // Mileage if requested
        if (includeMileage) {
            csv.append("\n")
            csv.append("Date,Distance (km),Start Location,End Location,Purpose,Project,Deduction Amount\n")
            
            val trips = businessExpenseRepository.getBusinessMileageBetween(startDate, endDate)
            for (trip in trips) {
                val date = dateFormat.format(Date(trip.date))
                val startLoc = escapeCSV(trip.startLocation ?: "")
                val endLoc = escapeCSV(trip.endLocation ?: "")
                val purpose = escapeCSV(trip.tripPurpose)
                val project = escapeCSV(trip.businessProject ?: "")
                val deduction = trip.calculatedDeduction ?: (trip.distanceKm * trip.deductionRatePerKm)
                
                csv.append("${date},${trip.distanceKm},${startLoc},${endLoc},${purpose},${project},${deduction}\n")
            }
        }
        
        csv.toString()
    }
    
    /**
     * Escape CSV values to handle commas and quotes.
     */
    private fun escapeCSV(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }
}
