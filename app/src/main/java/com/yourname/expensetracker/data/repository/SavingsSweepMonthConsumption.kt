package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.SavingsContributionLedgerDao
import com.yourname.expensetracker.data.database.dao.SavingsSweepMonthAcceptanceDao
import com.yourname.expensetracker.data.database.dao.SavingsSweepPlanDao
import com.yourname.expensetracker.data.database.entity.SweepPlanStatus
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import javax.inject.Inject

/**
 * Single authority for "has this calendar month's savings sweep already been consumed".
 *
 * Shared by the acceptance coordinator (inside its transaction) and the sweep
 * recommendation producer so both agree on the same rule.
 */
class SavingsSweepMonthConsumption @Inject constructor(
    private val monthAcceptanceDao: SavingsSweepMonthAcceptanceDao,
    private val savingsSweepPlanDao: SavingsSweepPlanDao,
    private val contributionLedgerDao: SavingsContributionLedgerDao
) {
    suspend fun isMonthConsumed(monthEnd: Long): Boolean {
        val monthStart = TimePeriodUtils.getStartOfMonth(monthEnd)
        val nextMonthStart = TimePeriodUtils.getEndOfMonth(monthEnd)
        // The independent month marker survives deletion of every goal.
        // Retain legacy plan/ledger evidence for defensive compatibility.
        return monthAcceptanceDao.hasAcceptanceBetween(monthStart, nextMonthStart) ||
            savingsSweepPlanDao.getPlansForMonth(monthEnd)
                .any { it.status == SweepPlanStatus.ACCEPTED } ||
            contributionLedgerDao.hasSweepBetween(monthStart, nextMonthStart)
    }
}
