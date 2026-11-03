package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.SavingsContributionLedgerDao
import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.data.database.entity.SavingsContributionLedgerEntry
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.savings.ManualContributionResult
import com.yourname.expensetracker.domain.savings.ManualContributionSnapshot
import com.yourname.expensetracker.domain.savings.SAVINGS_GOAL_DELETE_UNAVAILABLE
import com.yourname.expensetracker.domain.savings.SavingsGoalDeleteEligibility
import com.yourname.expensetracker.domain.savings.SavingsGoalDeleteResult
import com.yourname.expensetracker.domain.savings.SavingsGoalDeletionDecision
import com.yourname.expensetracker.domain.savings.SavingsGoalDeletionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

@Singleton
class SavingsGoalRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val savingsGoalDao: SavingsGoalDao,
    private val database: AppDatabase,
    private val savingsContributionHistoryRepository: SavingsContributionHistoryRepository,
    private val savingsContributionLedgerDao: SavingsContributionLedgerDao
) : com.yourname.expensetracker.domain.savings.SavingsGoalRepository {
    override fun observeSavingsGoals(): Flow<List<com.yourname.expensetracker.domain.model.SavingsGoal>> {
        return savingsGoalDao.getAllGoals().map { entities ->
            entities.map { entity ->
                entity.toDomain()
            }
        }
    }

    override suspend fun getSavingsGoals(): List<com.yourname.expensetracker.domain.model.SavingsGoal> {
        return savingsGoalDao.getAllGoals().first().map { it.toDomain() }
    }

    override suspend fun createSavingsGoal(goal: com.yourname.expensetracker.domain.model.SavingsGoal): Long {
        writeBarrier.checkWritesAllowed("SavingsGoalRepository.createSavingsGoal")
        return savingsGoalDao.insertGoal(goal.toEntity())
    }

    /**
     * Deletes the goal only when it is empty (zero balance, no ledger rows, no contribution events).
     *
     * Only [goal]'s id is used: the passed amounts and name are ignored (INV-2). The decision and the
     * delete run in one Room transaction against the row re-read inside it, never against [goal].
     */
    override suspend fun deleteSavingsGoal(goal: com.yourname.expensetracker.domain.model.SavingsGoal): SavingsGoalDeleteResult {
        writeBarrier.checkWritesAllowed("SavingsGoalRepository.deleteSavingsGoal")
        val goalId = goal.id
        return try {
            val historyFact = readHistoryFact(goalId)
            database.withTransaction {
                val facts = gatherDeletionFacts(goalId)
                    ?: return@withTransaction SavingsGoalDeleteResult.AlreadyAbsent
                val decision = SavingsGoalDeletionPolicy.evaluate(
                    balance = facts.goal.currentAmount,
                    hasHistory = facts.hasLedgerRows || historyFact == HistoryFact.PRESENT,
                    historyUnavailable = historyFact == HistoryFact.UNAVAILABLE
                )
                when (decision) {
                    is SavingsGoalDeletionDecision.Rejected ->
                        SavingsGoalDeleteResult.Rejected(decision.reasonCode)
                    SavingsGoalDeletionDecision.Deletable -> {
                        savingsGoalDao.deleteGoal(facts.goal)
                        SavingsGoalDeleteResult.Deleted
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: DatabaseAccessBlockedException) {
            throw error
        } catch (_: Exception) {
            SavingsGoalDeleteResult.Rejected(SAVINGS_GOAL_DELETE_UNAVAILABLE)
        }
    }

    /** Read-only: same facts and policy as [deleteSavingsGoal], nothing is written. */
    override suspend fun getSavingsGoalDeletionEligibility(goalId: Long): SavingsGoalDeleteEligibility {
        return try {
            val historyFact = readHistoryFact(goalId)
            database.withTransaction {
                val facts = gatherDeletionFacts(goalId)
                    ?: return@withTransaction SavingsGoalDeleteEligibility.AlreadyAbsent
                val decision = SavingsGoalDeletionPolicy.evaluate(
                    balance = facts.goal.currentAmount,
                    hasHistory = facts.hasLedgerRows || historyFact == HistoryFact.PRESENT,
                    historyUnavailable = historyFact == HistoryFact.UNAVAILABLE
                )
                when (decision) {
                    is SavingsGoalDeletionDecision.Rejected ->
                        SavingsGoalDeleteEligibility.Rejected(decision.reasonCode)
                    SavingsGoalDeletionDecision.Deletable -> SavingsGoalDeleteEligibility.Deletable
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: DatabaseAccessBlockedException) {
            throw error
        } catch (_: Exception) {
            SavingsGoalDeleteEligibility.Rejected(SAVINGS_GOAL_DELETE_UNAVAILABLE)
        }
    }

    /**
     * Reads the contribution history OUTSIDE any Room transaction (INV-17a). Unreadable history is
     * captured as a fact, never as "no history" (INV-10). Other failures propagate to the caller.
     */
    private suspend fun readHistoryFact(goalId: Long): HistoryFact {
        return try {
            val events = savingsContributionHistoryRepository.getAllContributions()
            if (events.any { it.goalId == goalId }) HistoryFact.PRESENT else HistoryFact.ABSENT
        } catch (_: SavingsContributionHistoryUnavailableException) {
            HistoryFact.UNAVAILABLE
        }
    }

    /**
     * Reads only. Must run inside the caller's transaction (INV-17b/d): the goal row (authoritative
     * balance witness) and whether any ledger row names it. Returns null when the goal is absent.
     */
    private suspend fun gatherDeletionFacts(goalId: Long): DeletionFacts? {
        val goalRow = savingsGoalDao.getById(goalId) ?: return null
        val hasLedgerRows = savingsContributionLedgerDao.getAll().any { it.goalId == goalId }
        return DeletionFacts(goal = goalRow, hasLedgerRows = hasLedgerRows)
    }

    private enum class HistoryFact { ABSENT, PRESENT, UNAVAILABLE }

    private class DeletionFacts(val goal: SavingsGoal, val hasLedgerRows: Boolean)

    override suspend fun incrementSavingsGoalAmount(goalId: Long, delta: Double): Boolean {
        writeBarrier.checkWritesAllowed("SavingsGoalRepository.incrementSavingsGoalAmount")
        return savingsGoalDao.addToGoalAmount(goalId, delta) > 0
    }

    /**
     * Balance and history in one Room transaction (same barrier and transaction pattern as
     * SavingsSweepAcceptanceCoordinator.accept). A snapshot mismatch writes nothing; a failed ledger
     * insert rolls the balance back. A repeated [idempotencyKey] is a typed no-op, never a second add.
     */
    override suspend fun recordManualContribution(
        goalId: Long,
        delta: Double,
        expectedSnapshot: ManualContributionSnapshot,
        idempotencyKey: String,
        timestamp: Long
    ): ManualContributionResult {
        writeBarrier.checkWritesAllowed("SavingsGoalRepository.recordManualContribution")
        val currency = expectedSnapshot.currency.trim().uppercase()
        val currencyCode = CurrencyCode.parse(currency)
        if (goalId <= 0L || !delta.isFinite() || delta <= 0.0 || timestamp <= 0L ||
            idempotencyKey.isBlank() ||
            !expectedSnapshot.currentAmount.isFinite() || !expectedSnapshot.targetAmount.isFinite() ||
            currencyCode == null || currencyCode !in CurrencyCode.ALL_SUPPORTED
        ) {
            return ManualContributionResult.Rejected(MANUAL_CONTRIBUTION_INVALID)
        }
        return try {
            database.withTransaction {
                val existing: SavingsContributionLedgerEntry? =
                    savingsContributionLedgerDao.getByIdempotencyKey(idempotencyKey)
                if (existing != null) {
                    return@withTransaction if (
                        existing.goalId == goalId && existing.amount == delta &&
                        existing.source == MANUAL_LEDGER_SOURCE
                    ) {
                        ManualContributionResult.AlreadyRecorded
                    } else {
                        ManualContributionResult.Rejected(MANUAL_CONTRIBUTION_IDEMPOTENCY_CONFLICT)
                    }
                }
                val updatedGoals: Int = savingsGoalDao.addToGoalAmountIfSnapshot(
                    goalId = goalId,
                    expectedCurrentAmount = expectedSnapshot.currentAmount,
                    expectedTargetAmount = expectedSnapshot.targetAmount,
                    currency = expectedSnapshot.currency,
                    delta = delta
                )
                if (updatedGoals != 1) return@withTransaction ManualContributionResult.SnapshotMismatch
                val ledgerId: Long = savingsContributionLedgerDao.insert(
                    SavingsContributionLedgerEntry(
                        idempotencyKey = idempotencyKey,
                        goalId = goalId,
                        amount = delta,
                        currency = currency,
                        timestamp = timestamp,
                        source = MANUAL_LEDGER_SOURCE,
                        sweepPlanId = null
                    )
                )
                // IGNORE returns -1 on a lost key race: throwing rolls the balance update back.
                if (ledgerId <= 0L) throw ManualContributionRollback()
                ManualContributionResult.Recorded
            }
        } catch (error: Exception) {
            if (error is CancellationException || error is DatabaseAccessBlockedException) throw error
            ManualContributionResult.Rejected(
                if (error is ManualContributionRollback) {
                    MANUAL_CONTRIBUTION_IDEMPOTENCY_CONFLICT
                } else {
                    MANUAL_CONTRIBUTION_UNAVAILABLE
                }
            )
        }
    }

    private class ManualContributionRollback : RuntimeException()

    private companion object {
        const val MANUAL_LEDGER_SOURCE = "manual"
        const val MANUAL_CONTRIBUTION_INVALID = "SAVINGS_MANUAL_CONTRIBUTION_INVALID"
        const val MANUAL_CONTRIBUTION_IDEMPOTENCY_CONFLICT = "SAVINGS_MANUAL_CONTRIBUTION_IDEMPOTENCY_CONFLICT"
        const val MANUAL_CONTRIBUTION_UNAVAILABLE = "SAVINGS_MANUAL_CONTRIBUTION_UNAVAILABLE"
    }

    @Deprecated("Use observeSavingsGoals() for domain-safe access")
    fun getAllGoalEntities(): Flow<List<SavingsGoal>> {
        return savingsGoalDao.getAllGoals()
    }

    @Deprecated("Use observeSavingsGoals() for domain-safe access")
    fun getAllGoals(): Flow<List<SavingsGoal>> {
        return getAllGoalEntities()
    }

    @Deprecated("Use createSavingsGoal() with domain model")
    suspend fun addGoal(goal: SavingsGoal): Long {
        writeBarrier.checkWritesAllowed("SavingsGoalRepository.addGoal")
        return savingsGoalDao.insertGoal(goal)
    }

    private fun com.yourname.expensetracker.data.database.entity.SavingsGoal.toDomain(): com.yourname.expensetracker.domain.model.SavingsGoal {
        return com.yourname.expensetracker.domain.model.SavingsGoal(
            id = id,
            name = name,
            targetAmount = targetAmount,
            currentAmount = currentAmount,
            targetDate = targetDate,
            currency = currency,
            currencyAssumption = currencyAssumption,
            protectionLevel = when (protectionLevel) {
                com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.STRICT -> GoalProtectionLevel.STRICT
                com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.WARNING -> GoalProtectionLevel.WARNING
                com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.TRACKING -> GoalProtectionLevel.TRACKING
            },
            createdAt = createdAt
        )
    }

    private fun com.yourname.expensetracker.domain.model.SavingsGoal.toEntity(): com.yourname.expensetracker.data.database.entity.SavingsGoal {
        return com.yourname.expensetracker.data.database.entity.SavingsGoal(
            id = id,
            name = name,
            targetAmount = targetAmount,
            currentAmount = currentAmount,
            targetDate = targetDate,
            currency = currency,
            currencyAssumption = currencyAssumption,
            protectionLevel = when (protectionLevel) {
                GoalProtectionLevel.STRICT -> com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.STRICT
                GoalProtectionLevel.WARNING -> com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.WARNING
                GoalProtectionLevel.TRACKING -> com.yourname.expensetracker.data.database.entity.GoalProtectionLevel.TRACKING
            },
            createdAt = createdAt
        )
    }
}
