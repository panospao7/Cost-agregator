package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectedEntry
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionRule
import com.yourname.expensetracker.domain.installment.projection.ProjectionEntryInput
import com.yourname.expensetracker.domain.installment.projection.ProjectionPlanInput
import com.yourname.expensetracker.domain.util.TimeProvider
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only [InstallmentProjectionPort]. Reads only ACTIVE plans and their entries from
 * [InstallmentPlanDao]; "today" is the civil date of the injected [TimeProvider] in the device
 * zone. Rows with an unknown stored code or an unconvertible amount are skipped (never zero).
 * Nothing is logged and nothing is written.
 */
@Singleton
class InstallmentProjectionPortImpl @Inject constructor(
    private val planDao: InstallmentPlanDao,
    private val timeProvider: TimeProvider
) : InstallmentProjectionPort {

    override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> {
        val today = Instant.ofEpochMilli(timeProvider.now()).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        val plans = ArrayList<ProjectionPlanInput>()
        val activePlans = planDao.getPlansByStatus(InstallmentPlanStatus.ACTIVE.name)
        for (plan in activePlans) {
            val currency = InstallmentCurrency.fromCodeOrNull(plan.currency) ?: continue
            val entries = planDao.getEntriesForPlan(plan.id).mapNotNull { entry ->
                val kind = InstallmentEntryKind.fromCodeOrNull(entry.kind) ?: return@mapNotNull null
                val status = InstallmentEntryStatus.fromCodeOrNull(entry.status) ?: return@mapNotNull null
                val minor = InstallmentMinorUnits.toMinorOrNull(entry.scheduledAmount) ?: return@mapNotNull null
                ProjectionEntryInput(entry.id, entry.seq, kind, status, entry.dueDate, minor, entry.recordGeneration)
            }
            plans += ProjectionPlanInput(plan.id, plan.planUuid, plan.displayName, currency, InstallmentPlanStatus.ACTIVE, entries)
        }
        return InstallmentProjectionRule.project(plans, today, horizonEndEpochDay)
    }
}
