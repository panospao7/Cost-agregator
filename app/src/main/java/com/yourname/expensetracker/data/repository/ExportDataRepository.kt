package com.yourname.expensetracker.data.repository

import android.content.Context
import com.yourname.expensetracker.data.database.entity.Expense
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportDataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val deterministicExpenseExportPager: DeterministicExpenseExportPager
) {
    suspend fun getExpensesBetween(startDate: Long, endDate: Long): List<Expense> =
        expenseRepository.getExpensesBetween(startDate, endDate)

    suspend fun getExpensesBetweenForExport(startDate: Long, endDate: Long): List<Expense> =
        deterministicExpenseExportPager.fetchAllBetween(startDate, endDate)

    suspend fun countExpensesBetween(startDate: Long, endDate: Long): Int =
        expenseRepository.countExpensesBetween(startDate, endDate)

    fun createExportFile(extension: String, timestampMs: Long): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(exportDir, "expenses_${timestampMs}.$extension")
    }

    suspend fun getCategoryNameMap(): Map<Long, String> =
        categoryRepository.getAll().associate { it.id to it.name }
}
