package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.model.DomainTransactionType
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryInsightEngine @Inject constructor() {

    companion object {
        private const val MAX_MISSING_CATEGORY_TRACKED = 100

        private val FALLBACK_CATEGORY = Category(
            id = -1,
            name = "Uncategorized",
            icon = "❓",
            color = "#9E9E9E",
            isDefault = true
        )
    }

    private val missingCategoryHitCount = ConcurrentHashMap<Long, Int>()

    fun calculate(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod?,
        categoryMap: Map<Long, Category>,
        allExpenses: List<Expense>
    ): List<CategoryInsight> {
        val currentExpenses = allExpenses.filter { 
            it.date != null &&
            it.date >= currentMonth.startMs && 
            it.date < currentMonth.endMs &&
            it.transactionType.toDomain().isSpending && 
            !it.isNotMine 
        }
        
        val previousExpenses = previousMonth?.let { pm ->
            allExpenses.filter { 
                it.date != null &&
                it.date >= pm.startMs && 
                it.date < pm.endMs &&
                it.transactionType.toDomain().isSpending && 
                !it.isNotMine 
            }
        }
        
        val totalCurrent = currentExpenses.sumOf { it.effectiveAmount }

        val missingCategoryUsage = currentExpenses
            .asSequence()
            .mapNotNull { it.categoryId }
            .filter { !categoryMap.containsKey(it) }
            .groupingBy { it }
            .eachCount()

        if (missingCategoryUsage.isNotEmpty()) {
            val affectedTransactions = missingCategoryUsage.values.sum()
            missingCategoryUsage.forEach { (categoryId, count) ->
                missingCategoryHitCount.merge(categoryId, count, Int::plus)
            }
            pruneMissingCategoryHitsIfNeeded()
            val sample = missingCategoryUsage.entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString { "${it.key}:${it.value}" }
            val cumulativeSample = missingCategoryHitCount.entries
                .sortedByDescending { it.value }
                .take(5)
                .joinToString { "${it.key}:${it.value}" }
            Timber.tag("CategoryInsightEngine").w(
                "Missing category mappings detected; fallback used for %d txs (%s), cumulative (%s)",
                affectedTransactions,
                sample,
                cumulativeSample
            )
        }
        
        val categoryTotals = currentExpenses.groupBy { it.categoryId }
        
        return categoryTotals.map { (categoryId, expenses) ->
            val category = categoryId?.let { categoryMap[it] } ?: FALLBACK_CATEGORY
            val currentTotal = expenses.sumOf { it.effectiveAmount }
            val currentCount = expenses.size
            
            val previousCategoryExpenses = previousExpenses?.filter { it.categoryId == categoryId }
            val previousTotal = previousCategoryExpenses?.sumOf { it.effectiveAmount }
            val previousCount = previousCategoryExpenses?.size
            
            val changeFromPrevious = if (previousTotal != null && previousTotal > 0) {
                ((currentTotal - previousTotal) / previousTotal * 100).toFloat()
            } else null
            
            val percentageOfTotal = if (totalCurrent > 0) {
                (currentTotal / totalCurrent * 100).toFloat()
            } else 0f
            
            CategoryInsight(
                category = category,
                currentTotal = currentTotal,
                currentCount = currentCount,
                previousTotal = previousTotal,
                previousCount = previousCount,
                averageOverMonths = null,
                monthsOfData = 1,
                percentageOfTotal = percentageOfTotal,
                changeFromPrevious = changeFromPrevious,
                changeFromAverage = null
            )
        }.sortedByDescending { it.currentTotal }
    }

    private fun pruneMissingCategoryHitsIfNeeded() {
        if (missingCategoryHitCount.size <= MAX_MISSING_CATEGORY_TRACKED) return

        val retainedKeys = missingCategoryHitCount.entries
            .sortedByDescending { it.value }
            .take(MAX_MISSING_CATEGORY_TRACKED)
            .map { it.key }
            .toHashSet()

        missingCategoryHitCount.keys.forEach { key ->
            if (!retainedKeys.contains(key)) {
                missingCategoryHitCount.remove(key)
            }
        }
    }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }
}
