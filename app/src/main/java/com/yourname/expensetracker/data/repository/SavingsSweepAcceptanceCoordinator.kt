package com.yourname.expensetracker.data.repository

import dagger.hilt.android.scopes.ViewModelScoped
import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.SavingsContributionLedgerDao
import com.yourname.expensetracker.data.database.dao.SavingsSweepMonthAcceptanceDao
import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.data.database.dao.SavingsSweepPlanDao
import com.yourname.expensetracker.data.database.entity.SavingsContributionLedgerEntry
import com.yourname.expensetracker.data.database.entity.SavingsSweepMonthAcceptance
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.data.database.entity.SavingsSweepPlan
import com.yourname.expensetracker.data.database.entity.SweepPlanStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.savings.SavingsGoalCurrencyPolicy
import com.yourname.expensetracker.domain.usecase.savings.GoalAllocation
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepRecommendation
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

sealed interface SavingsSweepAcceptanceResult {
    data object Accepted : SavingsSweepAcceptanceResult
    data object AlreadyAccepted : SavingsSweepAcceptanceResult
    data class Rejected(val reasonCode: String) : SavingsSweepAcceptanceResult
}

/**
 * Commits a savings-sweep recommendation through one authoritative Room transaction.
 */
@ViewModelScoped
class SavingsSweepAcceptanceCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val savingsGoalDao: SavingsGoalDao,
    private val savingsSweepPlanDao: SavingsSweepPlanDao,
    private val contributionLedgerDao: SavingsContributionLedgerDao,
    private val monthAcceptanceDao: SavingsSweepMonthAcceptanceDao,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider
) {
    suspend fun accept(
        recommendation: SavingsSweepRecommendation
    ): SavingsSweepAcceptanceResult {
        return try {
            writeBarrier.checkWritesAllowed("SavingsSweepAcceptanceCoordinator.accept")
            if (recommendation.goalAllocations.isEmpty()) {
                return SavingsSweepAcceptanceResult.Rejected(SWEEP_EMPTY)
            }
            validateRecommendation(recommendation)

            database.withTransaction {
                val actionedAt = timeProvider.now()
                rejectUnless(actionedAt > 0L, TIME_UNAVAILABLE)
                val monthStart = TimePeriodUtils.getStartOfMonth(recommendation.monthEnd)
                val nextMonthStart = TimePeriodUtils.getEndOfMonth(recommendation.monthEnd)
                // The independent month marker survives deletion of every goal.
                // Retain legacy plan/ledger evidence for defensive compatibility.
                val monthAlreadyAccepted = monthAcceptanceDao.hasAcceptanceBetween(monthStart, nextMonthStart) ||
                    savingsSweepPlanDao.getPlansForMonth(recommendation.monthEnd)
                    .any { it.status == SweepPlanStatus.ACCEPTED } ||
                    contributionLedgerDao.hasSweepBetween(monthStart, nextMonthStart)
                var newlyAccepted = false

                recommendation.goalAllocations.forEach { allocation ->
                    val acceptanceKey = acceptanceKey(recommendation, allocation)
                    val ledgerEntry = contributionLedgerDao.getByIdempotencyKey(acceptanceKey)
                    val existingPlan = savingsSweepPlanDao.getByAcceptanceKey(acceptanceKey)

                    if (ledgerEntry != null) {
                        // The authoritative ledger outlives optional plan cleanup.
                        rejectUnless(
                            matchesLedger(ledgerEntry, recommendation, allocation),
                            IDEMPOTENCY_CONFLICT
                        )
                        rejectUnless(
                            ledgerEntry.sweepPlanId != null && ledgerEntry.sweepPlanId > 0L,
                            INCOMPLETE_STATE
                        )
                        if (existingPlan != null) {
                            rejectUnless(existingPlan.status == SweepPlanStatus.ACCEPTED, INCOMPLETE_STATE)
                            rejectUnless(existingPlan.id == ledgerEntry.sweepPlanId, INCOMPLETE_STATE)
                            rejectUnless(
                                matchesPlan(existingPlan, recommendation, allocation, acceptanceKey),
                                IDEMPOTENCY_CONFLICT
                            )
                        }
                        return@forEach
                    }

                    rejectUnless(!monthAlreadyAccepted, MONTH_ALREADY_ACCEPTED)
                    rejectUnless(
                        actionedAt in monthStart until nextMonthStart &&
                            recommendation.computedAt in monthStart until nextMonthStart,
                        SNAPSHOT_MISMATCH
                    )

                    if (existingPlan != null) {
                        rejectUnless(
                            matchesPlan(existingPlan, recommendation, allocation, acceptanceKey),
                            IDEMPOTENCY_CONFLICT
                        )
                        rejectUnless(existingPlan.status == SweepPlanStatus.PENDING, PLAN_ALREADY_ACTIONED)
                    }

                    val goal = savingsGoalDao.getById(allocation.goalId)
                        ?: reject(GOAL_NOT_FOUND)
                    validateAllocation(goal, recommendation, allocation)

                    val plan = existingPlan ?: SavingsSweepPlan(
                        goalId = allocation.goalId,
                        monthEnd = recommendation.monthEnd,
                        totalUnderspend = recommendation.totalUnderspend,
                        riskBuffer = recommendation.riskBuffer,
                        safeSweepAmount = recommendation.safeSweepAmount,
                        allocatedAmount = allocation.suggestedAllocation,
                        allocationPercentage = allocation.allocationPercentage,
                        confidence = recommendation.confidence,
                        currency = recommendation.currency,
                        computedAt = recommendation.computedAt,
                        acceptanceKey = acceptanceKey
                    )
                    val planId = if (existingPlan == null) {
                        savingsSweepPlanDao.insert(plan).also { insertedId ->
                            rejectUnless(insertedId > 0L, IDEMPOTENCY_CONFLICT)
                        }
                    } else {
                        existingPlan.id
                    }

                    val updatedGoals = savingsGoalDao.addToGoalAmountIfSnapshot(
                        goalId = allocation.goalId,
                        expectedCurrentAmount = allocation.currentProgress,
                        expectedTargetAmount = allocation.targetAmount,
                        currency = recommendation.currency,
                        delta = allocation.suggestedAllocation
                    )
                    rejectUnless(updatedGoals == 1, SNAPSHOT_MISMATCH)

                    val ledgerId = contributionLedgerDao.insert(
                        SavingsContributionLedgerEntry(
                            idempotencyKey = acceptanceKey,
                            goalId = allocation.goalId,
                            amount = allocation.suggestedAllocation,
                            currency = recommendation.currency,
                            timestamp = actionedAt,
                            source = LEDGER_SOURCE,
                            sweepPlanId = planId
                        )
                    )
                    rejectUnless(ledgerId > 0L, IDEMPOTENCY_CONFLICT)

                    val acceptedPlans = savingsSweepPlanDao.acceptPendingPlan(planId, actionedAt)
                    rejectUnless(acceptedPlans == 1, PLAN_STATE_CONFLICT)
                    newlyAccepted = true
                }

                if (newlyAccepted) {
                    val markerId = monthAcceptanceDao.insert(
                        SavingsSweepMonthAcceptance(
                            monthStart = monthStart,
                            monthEndExclusive = nextMonthStart,
                            actionedAt = actionedAt,
                            reasonCode = SavingsSweepMonthAcceptance.ACCEPTED
                        )
                    )
                    rejectUnless(markerId > 0L, IDEMPOTENCY_CONFLICT)
                    SavingsSweepAcceptanceResult.Accepted
                } else {
                    SavingsSweepAcceptanceResult.AlreadyAccepted
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RejectedAcceptance) {
            SavingsSweepAcceptanceResult.Rejected(error.reasonCode)
        } catch (_: Exception) {
            SavingsSweepAcceptanceResult.Rejected(ACCEPTANCE_UNAVAILABLE)
        }
    }

    private fun validateRecommendation(recommendation: SavingsSweepRecommendation) {
        val recommendationCurrency = CurrencyCode.parse(recommendation.currency)
        rejectUnless(
            recommendationCurrency != null &&
                recommendationCurrency in CurrencyCode.ALL_SUPPORTED &&
                recommendation.currency == recommendationCurrency.code &&
                recommendation.totalUnderspend.isFinite() &&
                recommendation.totalUnderspend >= 0.0 &&
                recommendation.riskBuffer.isFinite() &&
                recommendation.riskBuffer >= 0.0 &&
                recommendation.safeSweepAmount.isFinite() &&
                recommendation.safeSweepAmount > 0.0 &&
                recommendation.confidence.isFinite() &&
                recommendation.confidence in 0.0..1.0 &&
                recommendation.computedAt > 0L &&
                recommendation.monthEnd > 0L &&
                recommendation.rateTimestamp >= 0L,
            INVALID_RECOMMENDATION
        )

        rejectUnless(
            recommendation.safeSweepAmount <= recommendation.totalUnderspend - recommendation.riskBuffer,
            INVALID_RECOMMENDATION
        )

        val goalIds = recommendation.goalAllocations.map { it.goalId }
        rejectUnless(goalIds.distinct().size == goalIds.size, INVALID_RECOMMENDATION)
        recommendation.goalAllocations.forEach { allocation ->
            rejectUnless(
                allocation.goalId > 0L &&
                    allocation.currentProgress.isFinite() &&
                    allocation.currentProgress >= 0.0 &&
                    allocation.targetAmount.isFinite() &&
                    allocation.targetAmount > 0.0 &&
                    allocation.suggestedAllocation.isFinite() &&
                    allocation.suggestedAllocation > 0.0 &&
                    allocation.allocationPercentage.isFinite() &&
                    allocation.allocationPercentage > 0.0 &&
                    allocation.allocationPercentage <= 1.0 &&
                    allocation.currency == recommendation.currency,
                INVALID_RECOMMENDATION
            )
        }

        val allocatedAmount = recommendation.goalAllocations.fold(BigDecimal.ZERO) { amount, allocation ->
            amount.add(BigDecimal.valueOf(allocation.suggestedAllocation))
        }
        // The allocator can leave a residual. Allow only one Double ULP at the
        // safe amount per allocation for its arithmetic, never a currency-unit
        // rounding allowance or a percentage of the available funds.
        val roundingTolerance = BigDecimal.valueOf(Math.ulp(recommendation.safeSweepAmount))
            .multiply(BigDecimal.valueOf(recommendation.goalAllocations.size.toLong()))
        rejectUnless(
            allocatedAmount.toDouble().isFinite() &&
                allocatedAmount <= BigDecimal.valueOf(recommendation.safeSweepAmount).add(roundingTolerance),
            INVALID_RECOMMENDATION
        )
    }

    private fun validateAllocation(
        goal: SavingsGoal,
        recommendation: SavingsSweepRecommendation,
        allocation: GoalAllocation
    ) {
        val knownGoalCurrency = SavingsGoalCurrencyPolicy.knownCurrencyOrNull(
            goal.currency,
            goal.currencyAssumption
        )
        rejectUnless(
            knownGoalCurrency?.code == recommendation.currency &&
                allocation.currency == recommendation.currency &&
                goal.currentAmount.isFinite() &&
                goal.currentAmount >= 0.0 &&
                goal.targetAmount.isFinite() &&
                goal.targetAmount > 0.0 &&
                goal.currentAmount < goal.targetAmount &&
                (goal.currentAmount + allocation.suggestedAllocation).isFinite() &&
                goal.currentAmount + allocation.suggestedAllocation <= goal.targetAmount &&
                goal.currentAmount == allocation.currentProgress &&
                goal.targetAmount == allocation.targetAmount,
            SNAPSHOT_MISMATCH
        )
    }

    private fun matchesPlan(
        plan: SavingsSweepPlan,
        recommendation: SavingsSweepRecommendation,
        allocation: GoalAllocation,
        acceptanceKey: String
    ): Boolean =
        plan.acceptanceKey == acceptanceKey &&
            plan.goalId == allocation.goalId &&
            plan.monthEnd == recommendation.monthEnd &&
            plan.totalUnderspend == recommendation.totalUnderspend &&
            plan.riskBuffer == recommendation.riskBuffer &&
            plan.safeSweepAmount == recommendation.safeSweepAmount &&
            plan.allocatedAmount == allocation.suggestedAllocation &&
            plan.allocationPercentage == allocation.allocationPercentage &&
            plan.confidence == recommendation.confidence &&
            plan.currency == recommendation.currency &&
            plan.computedAt == recommendation.computedAt

    private fun matchesLedger(
        entry: SavingsContributionLedgerEntry,
        recommendation: SavingsSweepRecommendation,
        allocation: GoalAllocation
    ): Boolean =
        entry.idempotencyKey == acceptanceKey(recommendation, allocation) &&
            entry.goalId == allocation.goalId &&
            entry.amount == allocation.suggestedAllocation &&
            entry.currency == recommendation.currency &&
            entry.source == LEDGER_SOURCE

    private fun acceptanceKey(
        recommendation: SavingsSweepRecommendation,
        allocation: GoalAllocation
    ): String {
        val identity = listOf(
            "sweep-v1",
            recommendation.monthEnd,
            recommendation.computedAt,
            recommendation.totalUnderspend,
            recommendation.riskBuffer,
            recommendation.safeSweepAmount,
            recommendation.confidence,
            recommendation.currency,
            recommendation.rateTimestamp,
            allocation.goalId,
            allocation.currentProgress,
            allocation.targetAmount,
            allocation.suggestedAllocation,
            allocation.allocationPercentage,
            allocation.currency
        ).joinToString(separator = "|")
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun rejectUnless(condition: Boolean, reasonCode: String) {
        if (!condition) reject(reasonCode)
    }

    private fun reject(reasonCode: String): Nothing = throw RejectedAcceptance(reasonCode)

    private class RejectedAcceptance(val reasonCode: String) : RuntimeException()

    private companion object {
        const val LEDGER_SOURCE = "sweep"
        const val SWEEP_EMPTY = "SAVINGS_SWEEP_EMPTY"
        const val INVALID_RECOMMENDATION = "SAVINGS_SWEEP_INVALID_RECOMMENDATION"
        const val TIME_UNAVAILABLE = "SAVINGS_SWEEP_TIME_UNAVAILABLE"
        const val GOAL_NOT_FOUND = "SAVINGS_SWEEP_GOAL_NOT_FOUND"
        const val SNAPSHOT_MISMATCH = "SAVINGS_SWEEP_SNAPSHOT_MISMATCH"
        const val IDEMPOTENCY_CONFLICT = "SAVINGS_SWEEP_IDEMPOTENCY_CONFLICT"
        const val INCOMPLETE_STATE = "SAVINGS_SWEEP_INCOMPLETE_STATE"
        const val PLAN_ALREADY_ACTIONED = "SAVINGS_SWEEP_PLAN_ALREADY_ACTIONED"
        const val PLAN_STATE_CONFLICT = "SAVINGS_SWEEP_PLAN_STATE_CONFLICT"
        const val MONTH_ALREADY_ACCEPTED = "SAVINGS_SWEEP_MONTH_ALREADY_ACCEPTED"
        const val ACCEPTANCE_UNAVAILABLE = "SAVINGS_SWEEP_ACCEPTANCE_UNAVAILABLE"
    }
}
