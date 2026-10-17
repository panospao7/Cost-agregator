package com.yourname.expensetracker.domain.recurring.usecase

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ManualRecurringExpenseRepository
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringOccurrenceStatus
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator
import com.yourname.expensetracker.domain.recurring.lifecycle.SpecificLinkResult
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.runBestEffortAfterCommit
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of [MarkRecurringBillPaidUseCase]. Never carries raw exception text;
 * [Failed.code] is one of [MarkPaidFailureCodes].
 */
sealed interface MarkPaidResult {
    /** An expense was created (or an idempotent retry resolved to it) and linked to the occurrence. */
    data class Paid(val expenseId: Long) : MarkPaidResult

    /** The rule had already moved on (double tap / stale UI row); nothing was written. */
    data object AlreadyHandled : MarkPaidResult

    /**
     * No expense was created, but the rule's next date really moved forward (nothing open was due
     * on the seen day, or an idempotent retry found it already handled and finished the advance).
     */
    data object AdvancedOnly : MarkPaidResult

    /** Nothing to pay and nothing to advance: no expense was created and the next date is unchanged. */
    data object NoChange : MarkPaidResult

    /** The open occurrence due on the seen day was marked SKIPPED ("already recorded") and the rule advanced. */
    data class Skipped(val occurrenceId: Long) : MarkPaidResult

    data class Failed(val code: String) : MarkPaidResult
}

/** Bounded, controlled failure codes. */
object MarkPaidFailureCodes {
    const val RULE_NOT_FOUND = "MARK_PAID_RULE_NOT_FOUND"
    const val RULE_INACTIVE = "MARK_PAID_RULE_INACTIVE"
    const val CREATE_FAILED = "MARK_PAID_CREATE_FAILED"
    /**
     * The explicit link was refused after a `Created` expense (narrow race: the occurrence stopped
     * being open between the target read and the link). The unlinked RECURRING_GENERATED expense
     * may remain; the user can delete it.
     */
    const val LINK_REJECTED = "MARK_PAID_LINK_REJECTED"
    const val SKIP_FAILED = "MARK_PAID_SKIP_FAILED"
    const val INTERNAL = "MARK_PAID_INTERNAL"
}

/**
 * R21 S2: "mark recurring bill paid" / "already recorded" use case.
 *
 * Legal path: expense creation goes ONLY through
 * [TransactionLifecycleCoordinator.createExpenseDbOnlyV2]; the link goes through
 * [RecurringLifecycleCoordinator.linkExpenseToSpecificOccurrence]; the rule date moves
 * through [RecurringRuleLifecycleCoordinator.advanceNextDate].
 *
 * The created expense is dated on the TARGET OCCURRENCE'S DUE DATE (not the current time),
 * so the explicit link stays valid when the expense is later edited without changing its
 * date (same-day matching in `reconcileExpenseLinkAfterUpdate`).
 *
 * Targeting: ONLY occurrences whose due day equals the day of the seen `nextDate` are considered
 * (any status). An open (PLANNED, unlinked) one is the target. If the on-day occurrence exists but
 * is not open (PAID/SKIPPED/linked) no expense is ever created - only the rule date is advanced
 * (this is the idempotent retry path after a failure that happened after the link). If none exists
 * even after one horizon top-up, only the date is advanced. There is NO fallback to stale
 * (earlier) PLANNED rows: pre-R21 stale rows are left untouched.
 *
 * Next date: the first open occurrence due on/after the end of the handled day and of the rule's
 * current `nextDate` day; if none exists after a top-up, [RecurrenceCalculator] is used only when
 * it yields a strictly later date. `nextDate` is strictly increasing or unchanged, never decreasing.
 *
 * Guarantees: one [Mutex] per rule serialises the read-check-write sequence, and the `nextDate`
 * snapshot the UI saw ([seenNextDate]) is re-validated under the lock. An expense is created only
 * for an open on-day occurrence, with a STRICT_EXTERNAL_ID key derived from that occurrence, so
 * retries of the same occurrence resolve to the same expense, and once the occurrence is linked
 * (PAID) no further expense is created for it. Repeated taps therefore create at most one expense
 * per occurrence. Narrow exception: see [MarkPaidFailureCodes.LINK_REJECTED] (documented in
 * LEGAL_PATHS.md as a known limitation).
 *
 * Side effects: the mutation's planned post-commit batch runs once, only for a freshly
 * `Created` expense, after the explicit link. The link and the batch run in a `NonCancellable`
 * section once the expense is committed, so scope cancellation (leaving the screen) cannot drop
 * them. The remaining crash window is process death only: if the process dies between the expense
 * commit and the batch, the idempotent retry resolves to `DuplicateSkipped` and the batch is not
 * re-run (there is no recovery API for create-time side effects).
 */
@Singleton
class MarkRecurringBillPaidUseCase @Inject constructor(
    private val ruleRepository: ManualRecurringExpenseRepository,
    private val occurrenceCoordinator: RecurringLifecycleCoordinator,
    private val ruleCoordinator: RecurringRuleLifecycleCoordinator,
    private val transactionCoordinator: TransactionLifecycleCoordinator,
    private val postCommitActionRunner: PostCommitActionRunner,
    private val timeProvider: TimeProvider
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    private fun lockFor(ruleId: Long): Mutex = locks.computeIfAbsent(ruleId) { Mutex() }

    suspend fun invoke(ruleId: Long, seenNextDate: Long): MarkPaidResult =
        lockFor(ruleId).withLock {
            try {
                markPaidLocked(ruleId, seenNextDate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MarkPaidResult.Failed(MarkPaidFailureCodes.INTERNAL)
            }
        }

    /** "Already recorded": SKIPs the open occurrence due on the seen day (no expense) and advances the rule. */
    suspend fun skip(ruleId: Long, seenNextDate: Long): MarkPaidResult =
        lockFor(ruleId).withLock {
            try {
                skipLocked(ruleId, seenNextDate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MarkPaidResult.Failed(MarkPaidFailureCodes.SKIP_FAILED)
            }
        }

    private suspend fun markPaidLocked(ruleId: Long, seenNextDate: Long): MarkPaidResult {
        val rule = ruleRepository.getById(ruleId)
            ?: return MarkPaidResult.Failed(MarkPaidFailureCodes.RULE_NOT_FOUND)
        if (rule.nextDate != seenNextDate) return MarkPaidResult.AlreadyHandled
        if (!rule.isActive) return MarkPaidResult.Failed(MarkPaidFailureCodes.RULE_INACTIVE)

        val target = findTarget(rule)
        if (target == null) return advanceOnlyResult(rule)

        // Defensive re-read: a no longer open / linked occurrence only advances the rule.
        val fresh = occurrenceCoordinator.getOccurrenceById(target.id)
        if (fresh == null || !isOpen(fresh)) return advanceOnlyResult(rule)

        val request = CreateExpenseRequest(
            merchant = rule.merchant,
            amount = rule.amount,
            currency = rule.currency,
            // The expense is dated on the occurrence's due date (not "now") so the specific link
            // survives later expense edits: reconcileExpenseLinkAfterUpdate keeps a link only
            // while the expense still falls on the occurrence's due day.
            date = target.dueDate,
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.RECURRING_GENERATED,
            categoryId = rule.categoryId,
            deduplicationMode = DeduplicationMode.STRICT_EXTERNAL_ID,
            idempotencyKey = "$IDEMPOTENCY_PREFIX${target.occurrenceKey.ifBlank { target.id.toString() }}"
        )
        val mutation = transactionCoordinator.createExpenseDbOnlyV2(request)
        val createdNow: Boolean
        val expenseId = when (val created = mutation.value) {
            is CreateExpenseResult.Created -> {
                createdNow = true
                created.expenseId
            }
            is CreateExpenseResult.DuplicateSkipped -> {
                // Idempotent retry: the first attempt owns the side effects (see class KDoc).
                createdNow = false
                created.existingExpenseId
            }
            else -> return MarkPaidResult.Failed(MarkPaidFailureCodes.CREATE_FAILED)
        }

        var linkResult: SpecificLinkResult? = null
        var linkError: Exception? = null
        // The expense is already committed: from here the link and the post-commit batch run in a
        // NonCancellable section so leaving the screen (scope cancellation) cannot drop them.
        // Cancellation BEFORE the create (above) still aborts normally.
        withContext(NonCancellable) {
            try {
                linkResult = occurrenceCoordinator.linkExpenseToSpecificOccurrence(
                    target.id,
                    expenseId,
                    RecurringLifecycleCoordinator.SOURCE_DIRECT_EXPENSE_LINK
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                linkError = e
            }

            // Full planned post-commit batch (budget check, anomaly, merchant stats, recurring matching,
            // category learning) exactly once, ONLY for a freshly created expense and AFTER the explicit
            // link so automatic recurring matching sees the link and no-ops. Runs even when the link was
            // rejected/failed so a committed expense is never left without its side effects.
            if (createdNow) {
                postCommitActionRunner.runBestEffortAfterCommit(
                    mutation.postCommitActions,
                    "MarkRecurringBillPaid: post-commit side effects failed",
                    expenseId
                )
            }
        }

        // Rethrown only after the batch ran (order preserved).
        linkError?.let { throw it }
        when (linkResult) {
            SpecificLinkResult.Linked,
            SpecificLinkResult.AlreadyLinkedToThis -> Unit
            else -> return MarkPaidResult.Failed(MarkPaidFailureCodes.LINK_REJECTED)
        }

        advanceNextDate(rule)
        return MarkPaidResult.Paid(expenseId)
    }

    private suspend fun skipLocked(ruleId: Long, seenNextDate: Long): MarkPaidResult {
        val rule = ruleRepository.getById(ruleId)
            ?: return MarkPaidResult.Failed(MarkPaidFailureCodes.RULE_NOT_FOUND)
        if (rule.nextDate != seenNextDate) return MarkPaidResult.AlreadyHandled
        if (!rule.isActive) return MarkPaidResult.Failed(MarkPaidFailureCodes.RULE_INACTIVE)

        val target = findTarget(rule)
        if (target == null) return advanceOnlyResult(rule)
        occurrenceCoordinator.skipOccurrence(target.id)
        advanceNextDate(rule)
        return MarkPaidResult.Skipped(target.id)
    }

    private fun isOpen(occurrence: RecurringOccurrence): Boolean =
        occurrence.status == RecurringOccurrenceStatus.PLANNED.dbValue && occurrence.linkedExpenseId == null

    private val occurrenceOrder = compareBy<RecurringOccurrence>({ it.dueDate }, { it.id })

    /** No target: only advance the date (never an expense) and report whether it really moved. */
    private suspend fun advanceOnlyResult(rule: ManualRecurringExpense): MarkPaidResult =
        if (advanceNextDate(rule)) MarkPaidResult.AdvancedOnly else MarkPaidResult.NoChange

    /**
     * Target = the OPEN occurrence due on the seen next-date's DAY (end bound is exclusive). When no
     * occurrence at all exists on that day, one horizon top-up is attempted and the day is re-checked.
     * An on-day occurrence that is not open (PAID/SKIPPED/linked) yields no target. No stale fallback.
     */
    private suspend fun findTarget(rule: ManualRecurringExpense): RecurringOccurrence? {
        val dayStart = TimePeriodUtils.getStartOfDay(rule.nextDate)
        val dayEndExclusive = TimePeriodUtils.getEndOfDay(rule.nextDate)
        suspend fun onDay(): List<RecurringOccurrence> =
            occurrenceCoordinator.getOccurrences(rule.id)
                .filter { it.dueDate >= dayStart && it.dueDate < dayEndExclusive }

        var onDay = onDay()
        if (onDay.isEmpty()) {
            ruleCoordinator.topUpHorizon(rule.id, timeProvider.now())
            onDay = onDay()
        }
        return onDay.filter { isOpen(it) }.minWithOrNull(occurrenceOrder)
    }

    /**
     * Moves the rule to the due date of the first open occurrence due on/after the end of the
     * handled (seen) day and of the rule's current `nextDate` day. When none exists even after a
     * horizon top-up, [RecurrenceCalculator] is used only if it yields a strictly later date.
     * `nextDate` is strictly increasing or unchanged. Returns true when it actually moved.
     */
    private suspend fun advanceNextDate(rule: ManualRecurringExpense): Boolean {
        val threshold = TimePeriodUtils.getEndOfDay(rule.nextDate) // exclusive end of the handled day
        suspend fun nextOpen(): RecurringOccurrence? =
            occurrenceCoordinator.getOccurrences(rule.id)
                .filter { isOpen(it) && it.dueDate >= threshold }
                .minWithOrNull(occurrenceOrder)

        var next = nextOpen()
        if (next == null) {
            ruleCoordinator.topUpHorizon(rule.id, timeProvider.now())
            next = nextOpen()
        }
        val candidate = next?.dueDate ?: RecurrenceCalculator
            .calculateNextDate(rule.nextDate, rule.frequency)
            .takeIf { it >= threshold }
        if (candidate == null || candidate <= rule.nextDate) return false
        ruleCoordinator.advanceNextDate(rule.id, candidate)
        return true
    }

    private companion object {
        const val IDEMPOTENCY_PREFIX = "recurring_paid:"
    }
}
