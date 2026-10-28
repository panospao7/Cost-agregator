package com.yourname.expensetracker.data.repository

import android.content.Context
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.projection.InstallmentUpcomingProjection
import com.yourname.expensetracker.domain.installment.projection.InstallmentUpcomingRow
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.ConfirmedOccurrence
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.RiskLevel
import com.yourname.expensetracker.domain.model.UpcomingItem
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository as DomainSavingsGoalRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Home-only installment-aware weather (loan-installments S8b-3, plan amendment A15 option A).
 *
 * [weather] is a full [FinancialWeather] computed with the installment projections as planned
 * inputs. [installmentRows] is the bounded 31-day installment list, carried separately in plan
 * currency (never converted, never summed across currencies, INV-16). This type is Home-only: it is
 * never a field of `FinancialWeather`, `DashboardData` or `ProcessedDashboardData`.
 */
data class HomeInstallmentWeather(
    val weather: FinancialWeather,
    val installmentRows: List<InstallmentUpcomingRow>
)

/**
 * The single merge-point source of the installment-aware Home weather. It is deliberately OFF the
 * cloud briefing chain: `FinancialWeatherRepository`, `ForecastInputAssembler`, `SynthesisEngine`,
 * `NarrativeGenerator` and `DashboardDataProvider` stay installment-free (installment_cloud_reachability
 * guard). Only `HomeViewModel` consumes this class, and it REPLACES the single baseline weather widget
 * with [HomeInstallmentWeather.weather] after `ComputeDashboardWidgetsUseCase.compute()`.
 *
 * Pipeline (same inputs and order as `FinancialWeatherRepository.getFinancialWeather`):
 * expenses -> snapshots -> detected patterns -> `ForecastInputAssembler.assemble` ->
 * `SynthesisEngine.synthesize` -> `NarrativeGenerator.generate` -> `FinancialWeather`.
 * The three engines are the UNMODIFIED singletons. The differences are exactly two, and both apply only
 * while the projection port returns at least one entry in the 31-day window; otherwise [observe] emits
 * null and Home keeps the baseline widget:
 *
 *  1. INV-29 (single owner `RecurringInstallmentEligibility.excludedSnapshotIds`): installment-linked and
 *     plan-purchase expenses are removed from the snapshots before the patterns and the assembly.
 *     Scope is GLOBAL-WHILE-PROJECTED (owner-approvals 53a): while any entry is projected the whole
 *     aware view excludes them. The result REPLACES the baseline view and is never added to it.
 *  2. INV-15 / INV-16: every projected entry becomes one MUST [PlannedExpense] (negative id, plan
 *     currency, plan amount) appended to the planned inputs. The assembler's existing planned-expense
 *     currency normalisation converts foreign-currency entries (LATEST_AVAILABLE) or omits them and
 *     marks the forecast partial (never 0, never raw). `SynthesisEngine` counts MUST items due in the
 *     rest of the month as committed.
 *
 * `upcomingItems` is built from the forecast components with the projected planned items removed, so
 * it equals the baseline list: projections never become `UpcomingItem.Planned`.
 *
 * Read-only: no writes, no DAO access. Every input flow degrades to an empty list exactly as the
 * baseline does. On any failure the flow emits null (Home keeps the baseline widget, fail closed);
 * cancellation propagates through the flow operators and is never swallowed here.
 *
 * Invalidation: the 6th input is [InstallmentPlanReadRepository.observeChanges] (PE-C), which re-emits
 * on every installment_plans / installment_entries row change (it combines `observeAllPlans()` and
 * `observeAllEntries()` without loading or computing an overview), so a status change of an entry
 * re-runs the pipeline. The emitted value carries no data.
 *
 * Fallback label (PE-C, owner-approvals 60d-2): a projected entry whose plan has a blank display
 * name gets the localized string resource `installment_plan_fallback_label`, resolved from the
 * application context on every computation (so a locale change is picked up). The label is only the
 * description of the internal MUST planned input; the Home narrative itself (`NarrativeGenerator`)
 * is English-only and does not use it.
 */
@Singleton
class HomeInstallmentWeatherSource @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val budgetRepository: BudgetRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val mergedRecurringPatternsProvider: MergedRecurringPatternsProvider,
    private val plannedExpenseRepository: PlannedExpenseRepository,
    private val savingsGoalRepository: DomainSavingsGoalRepository,
    private val forecastInputAssembler: ForecastInputAssembler,
    private val synthesisEngine: SynthesisEngine,
    private val narrativeGenerator: NarrativeGenerator,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val timeProvider: TimeProvider,
    private val installmentProjectionPort: InstallmentProjectionPort,
    private val recurringInstallmentEligibility: RecurringInstallmentEligibility,
    private val installmentPlanReadRepository: InstallmentPlanReadRepository,
    @ApplicationContext private val context: Context
) {

    /** Emits the aware weather, or null when the port has no entry in the 31-day window (or on failure). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<HomeInstallmentWeather?> {
        val inputs = combine(
            expenseRepository.getAllExpenses().catch { emit(emptyList()) },
            budgetRepository.getBudgetStatuses().catch { emit(emptyList()) },
            recurringExpenseRepository.getAllFlow().catch { emit(emptyList()) },
            plannedExpenseRepository.getAllPlannedExpenses().catch { emit(emptyList()) },
            savingsGoalRepository.observeSavingsGoals().catch { emit(emptyList()) }
        ) { expenses, budgetStatuses, recurringEntities, plannedEntities, savingsGoals ->
            WeatherInputs(expenses, budgetStatuses, recurringEntities, plannedEntities, savingsGoals)
        }
        // observeChanges is only an invalidation trigger (no overview is loaded); a failure of its flow
        // must not stop the weather.
        val trigger = installmentPlanReadRepository.observeChanges()
            .catch { emit(Unit) }
        return combine(inputs, trigger) { weatherInputs, _ -> weatherInputs }
            // One isolated flow per emission: a failed computation yields null for that emission only
            // and a newer input cancels a stale computation.
            .flatMapLatest { weatherInputs ->
                flow { emit(compute(weatherInputs)) }
                    .catch { error ->
                        // Flow.catch never intercepts cancellation, so a CancellationException still
                        // propagates. Bounded reason code only: no throwable text, no payload.
                        Timber.w("HomeInstallmentWeatherSource: aware weather unavailable (%s)", error::class.java.simpleName)
                        emit(null)
                    }
            }
    }

    private class WeatherInputs(
        val expenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
        val budgetStatuses: List<com.yourname.expensetracker.domain.budget.BudgetStatus>,
        val recurringEntities: List<com.yourname.expensetracker.data.database.entity.ManualRecurringExpense>,
        val plannedEntities: List<com.yourname.expensetracker.data.database.entity.PlannedExpense>,
        val savingsGoals: List<com.yourname.expensetracker.domain.model.SavingsGoal>
    )

    private suspend fun compute(inputs: WeatherInputs): HomeInstallmentWeather? {
        val now = timeProvider.now()
        val zone = ZoneId.systemDefault()
        val todayEpochDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()

        val rows = InstallmentUpcomingProjection.load(installmentProjectionPort, todayEpochDay)
        // No entry in the window: nothing to project, nothing to exclude, so Home keeps the baseline.
        if (rows.isEmpty()) return null
        // Resolved per computation so the label follows the current app locale.
        val fallbackLabel = context.getString(R.string.installment_plan_fallback_label)
        val projectedPlanned = rows.map { toPlannedExpense(it, zone, fallbackLabel) }

        // INV-29 (single owner RecurringInstallmentEligibility): linked history leaves the baseline
        // while entries are projected, so the plan is counted by its projection and not by its history.
        // The exclusion applies ONLY to history BEFORE the current month (owner-approvals 60b): those are
        // the baselines (average month, previous month, typical daily discretionary). Current-month
        // actual installment payments stay in month-to-date, so a paid installment is counted once
        // (as an actual) and never zero times; its next entry is projected, not yet paid.
        val allSnapshots = forecastInputAssembler.mapExpenseSnapshots(inputs.expenses)
        val excludedIds = recurringInstallmentEligibility.excludedSnapshotIds(allSnapshots)
        val startOfMonth = TimePeriodUtils.getStartOfMonth(now)
        val expenseSnapshots =
            if (excludedIds.isEmpty()) {
                allSnapshots
            } else {
                allSnapshots.filterNot { it.id in excludedIds && it.date < startOfMonth }
            }

        val detectedPatterns = mergedRecurringPatternsProvider.getPatternsFromSnapshots(
            expenseSnapshots = expenseSnapshots,
            manualRecurring = emptyList() // detected-only; manual passed separately below
        )
        val assembledInput = forecastInputAssembler.assemble(
            expenses = expenseSnapshots,
            manualRecurringEntities = inputs.recurringEntities,
            detectedRecurringPatterns = detectedPatterns,
            plannedExpenses = forecastInputAssembler.mapPlannedExpenses(inputs.plannedEntities) + projectedPlanned,
            savingsGoals = forecastInputAssembler.mapSavingsGoals(inputs.savingsGoals),
            budgetStatuses = forecastInputAssembler.mapBudgetSnapshots(inputs.budgetStatuses)
        )
        val forecast = synthesisEngine.synthesize(assembledInput)

        // Same typed home-currency resolution as the baseline: fail closed, never a hard EUR fallback.
        val homeCurrency = when (val resolution = currencySettingsRepository.resolveHomeCurrency()) {
            is HomeCurrencyResolution.Resolved -> resolution.currency.code
            is HomeCurrencyResolution.FirstRunDefault -> resolution.currency.code
            is HomeCurrencyResolution.Failed ->
                throw IllegalStateException("Home currency unavailable for weather narrative: ${resolution.reason}")
        }
        val narrative = narrativeGenerator.generate(forecast, assembledInput.budgetStatuses, homeCurrency)

        val projectedIds = projectedPlanned.mapTo(HashSet()) { it.id }
        val weather = FinancialWeather(
            state = narrative.state,
            headline = narrative.headline,
            summary = narrative.summary,
            icon = narrative.icon,
            riskLevel = when (forecast.components.riskLevel) {
                RiskLevel.LOW -> 10
                RiskLevel.MEDIUM -> 40
                RiskLevel.HIGH -> 70
                RiskLevel.CRITICAL -> 100
            },
            totalCommitted = forecast.components.totalCommitted,
            totalLikely = forecast.components.totalLikely,
            predictedDiscretionary = forecast.components.predictedDiscretionary,
            discretionaryBudget = forecast.components.discretionaryBudget,
            pastSpendingPoints = forecast.components.pastSpendingPoints,
            projectedSpendingPoints = forecast.components.projectedSpendingPoints,
            upcomingItems = buildUpcomingItems(
                forecast.components.recurringExpenses,
                forecast.components.plannedExpenses.filterNot { it.id in projectedIds },
                forecast.components.confirmedOccurrences
            ),
            totalRecurringCount = assembledInput.recurringPatterns.size,
            details = narrative.details,
            isPartial = forecast.isPartial,
            qualityWarnings = forecast.qualityWarnings,
            excludedCount = forecast.excludedCount,
            goalReservesPartial = forecast.components.goalReservesPartial
        )
        return HomeInstallmentWeather(weather = weather, installmentRows = rows)
    }

    /**
     * One projected entry as a MUST planned input in PLAN currency (the assembler converts it).
     * Negative id: real planned-expense ids are positive, so the two can never collide and the
     * projected items can be told apart. Rows are guaranteed positive by [InstallmentUpcomingProjection.load].
     */
    private fun toPlannedExpense(row: InstallmentUpcomingRow, zone: ZoneId, fallbackLabel: String): PlannedExpense {
        return PlannedExpense(
            id = -row.entryId,
            description = row.displayName.ifBlank { fallbackLabel },
            amount = InstallmentMinorUnits.fromMinor(row.amountMinor),
            currency = row.currency.code,
            date = LocalDate.ofEpochDay(row.dueEpochDay).atStartOfDay(zone).toInstant().toEpochMilli(),
            categoryId = null,
            isRecurring = false,
            priority = PlannedExpensePriority.MUST,
            sourceOccurrenceKey = null
        )
    }

    // Same 31-day list as FinancialWeatherRepository.buildUpcomingItems (kept in this file so the
    // cloud-surface repository is not touched). Planned items here are the real ones only.
    private fun buildUpcomingItems(
        recurring: List<RecurringPattern>,
        planned: List<PlannedExpense>,
        confirmedOccurrences: List<ConfirmedOccurrence>
    ): List<UpcomingItem> {
        val now = timeProvider.now()
        val startOfToday = TimePeriodUtils.getStartOfDay(now)
        val horizon = TimePeriodUtils.addDays(startOfToday, 31) // Exclusive

        val items = mutableListOf<UpcomingItem>()

        confirmedOccurrences
            .filter { it.dueDate >= startOfToday && it.dueDate < horizon }
            .forEach { items.add(UpcomingItem.Occurrence(it)) }

        recurring
            .filter { it.id == null }
            .filter { it.nextExpectedDate >= startOfToday && it.nextExpectedDate < horizon }
            .forEach { items.add(UpcomingItem.Recurring(it)) }

        for (pattern in recurring.filter { it.id != null }) {
            val alreadyMatched = confirmedOccurrences.any { occ ->
                occ.merchant == pattern.merchantName &&
                    kotlin.math.abs(occ.expectedAmount - pattern.averageAmount) < 0.01
            }
            if (!alreadyMatched &&
                pattern.nextExpectedDate >= startOfToday && pattern.nextExpectedDate < horizon
            ) {
                items.add(UpcomingItem.Recurring(pattern))
            }
        }

        planned.filter { it.date >= startOfToday && it.date < horizon }
            .forEach { items.add(UpcomingItem.Planned(it)) }

        return items.sortedBy { it.date }
    }
}
