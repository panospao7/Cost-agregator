package com.yourname.expensetracker.domain.installment.projection

/**
 * S8b-2 test helpers for engines that gained an [InstallmentProjectionPort] constructor dependency.
 *
 * [emptyInstallmentProjectionPort] makes a consumer behave exactly as before (no plans). The
 * [fakeInstallmentProjectionPort] runs the REAL projection rule ([InstallmentProjectionRule.project])
 * over the given plans, so a test exercises the INV-15 bounds and not a copy of them. "Today" is a
 * civil epoch day chosen by the test; the production port derives it from the time provider.
 */
internal fun emptyInstallmentProjectionPort(): InstallmentProjectionPort = object : InstallmentProjectionPort {
    override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> = emptyList()
}

internal fun fakeInstallmentProjectionPort(
    plans: List<ProjectionPlanInput>,
    todayEpochDay: Long
): InstallmentProjectionPort = object : InstallmentProjectionPort {
    override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> =
        InstallmentProjectionRule.project(plans, todayEpochDay, horizonEndEpochDay)
}
