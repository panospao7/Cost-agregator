package com.yourname.expensetracker.domain.installment.model

/** Plan-level triggers (domain spec 3.1). */
enum class PlanTrigger {
    /** Stop: ACTIVE -> CANCELLED. */
    STOP,

    /** Refund / return: ACTIVE or COMPLETED -> REFUNDED. */
    REFUND,

    /** Early payoff: ACTIVE -> PAID_OFF. */
    PAY_OFF,

    /** The last SCHEDULED entry resolved: ACTIVE -> COMPLETED (INV-21). */
    AUTO_COMPLETE,

    /** An entry returned to SCHEDULED: COMPLETED -> ACTIVE (the only exit from a terminal-looking state). */
    REOPEN,

    /** Delete (undo declaration): the plan and its entries are removed. */
    DELETE
}

/** Outcome of a plan-level transition check. */
sealed interface PlanTransitionResult {
    /** The plan moves to [to]; [reason] is the bounded plan `statusReason` to store (null clears it). */
    data class Move(val to: InstallmentPlanStatus, val reason: InstallmentStatusReason?) : PlanTransitionResult

    /** The plan and its entries are removed. */
    data object Delete : PlanTransitionResult

    /** The plan is already in the target state; callers treat this as success. */
    data class AlreadyInState(val status: InstallmentPlanStatus) : PlanTransitionResult

    /** Refused with a bounded code; nothing is written. */
    data class Illegal(val code: InstallmentRefusalCode) : PlanTransitionResult
}

/** Plan-ending operations that act on entries (domain spec 7.1 - 7.4). */
enum class PlanEntryOperation {
    STOP,
    REFUND,
    PAY_OFF,
    SHORTEN
}

/**
 * The effect a plan-ending operation has on one entry.
 *
 * @property flagExpenseReversed true when the entry's linked expense must be flagged
 *   INSTALLMENT_REVERSED through the transaction lifecycle coordinator in the same transaction
 *   (refund of a PAID entry, INV-2).
 */
data class EntryEffect(
    val to: InstallmentEntryStatus,
    val reason: InstallmentStatusReason?,
    val flagExpenseReversed: Boolean = false
)

/** Entry-level triggers (domain spec 3.2). */
enum class EntryTrigger {
    /** Catch-up auto-record creates the expense: SCHEDULED -> PAID. */
    AUTO_RECORD,

    /** "Mark paid" creates the expense: SCHEDULED -> PAID. */
    CONFIRM,

    /** The user links an existing expense: SCHEDULED -> PAID. */
    LINK,

    /** The user unlinks the expense: PAID -> SCHEDULED. */
    UNLINK,

    /**
     * The linked expense was deleted, or its currency or type changed (reconcile / side effect):
     * PAID -> SCHEDULED in an ACTIVE or COMPLETED plan, PAID -> CANCELLED(EXPENSE_REMOVED) in a
     * CANCELLED or PAID_OFF plan (spec 3.2: one bounded reason for every detach cause).
     */
    EXPENSE_REMOVED,

    SKIP,
    UNSKIP,
    ADJUST,
    MARK_PAID_BEFORE_TRACKING,
    REVERT_PAID_BEFORE_TRACKING,

    /** The expense of a REVERSED entry was deleted: REVERSED -> CANCELLED(EXPENSE_REMOVED). */
    REVERSED_EXPENSE_REMOVED
}

/**
 * Facts about one entry and its plan that decide an entry transition. Fields with defaults are
 * only needed by some triggers; when a needed one is missing the transition fails closed.
 *
 * @property todayEpochDay civil "today" supplied by the caller (this package never reads a
 *   clock). Required for AUTO_RECORD and CONFIRM (INV-14: a CONFIRM never records a future charge).
 * @property fulfilmentMode the plan's mode. Required for AUTO_RECORD.
 * @property skipReason for SKIP only: one of the bounded USER_SKIP_* codes (OQ-20). A null or
 *   non-user reason refuses the skip.
 */
data class EntryTransitionContext(
    val planStatus: InstallmentPlanStatus,
    val entryKind: InstallmentEntryKind,
    val entryStatus: InstallmentEntryStatus,
    val hasLinkedExpense: Boolean,
    val dueEpochDay: Long,
    val declaredOnEpochDay: Long,
    val autoRecordConsumed: Boolean = false,
    val todayEpochDay: Long? = null,
    val fulfilmentMode: InstallmentFulfilmentMode? = null,
    val skipReason: InstallmentStatusReason? = null
)

/** Outcome of an entry-level transition check. */
sealed interface EntryTransitionResult {
    /**
     * The entry moves to [to].
     *
     * @property recordGenerationDelta added to `recordGeneration` (1 when an entry returns to
     *   SCHEDULED from PAID, otherwise 0).
     * @property reason the bounded entry `statusReason` to store (null clears it).
     * @property setsAutoRecordConsumed true when `autoRecordConsumed` becomes true (auto-record only).
     *
     * After applying a move the caller recomputes the plan status with
     * [InstallmentPlanTransitions.planStatusAfterEntryChange] in the same transaction.
     */
    data class Move(
        val to: InstallmentEntryStatus,
        val recordGenerationDelta: Int = 0,
        val reason: InstallmentStatusReason? = null,
        val setsAutoRecordConsumed: Boolean = false
    ) : EntryTransitionResult

    /** The entry is already in the target state; callers treat this as success. */
    data class AlreadyInState(val status: InstallmentEntryStatus) : EntryTransitionResult

    /** Refused with a bounded code; nothing is written. */
    data class Illegal(val code: InstallmentRefusalCode) : EntryTransitionResult
}

/** Decision for one SCHEDULED entry on a catch-up run (INV-12, INV-13). */
sealed interface AutoRecordDecision {
    /** Create the expense now. */
    data object Record : AutoRecordDecision

    /** Do not create it on this run; the entry stays SCHEDULED. */
    data class Defer(val reason: InstallmentDeferralReason) : AutoRecordDecision

    /** Auto-record does not apply to this entry at all. */
    data object NotApplicable : AutoRecordDecision
}

/** One selected entry of a bulk "mark paid before tracking" request. */
data class PaidBeforeTrackingCandidate(
    val entryId: Long,
    val entryKind: InstallmentEntryKind,
    val entryStatus: InstallmentEntryStatus,
    val hasLinkedExpense: Boolean,
    val dueEpochDay: Long
)

/** Result of a bulk mark request: all or nothing (domain spec 3.2). */
sealed interface BulkMarkResult {
    /** Every selected entry is eligible or already marked. [toMark] must be claimed by compare-and-set. */
    data class Allowed(val toMark: List<Long>, val alreadyMarked: List<Long>) : BulkMarkResult

    data class Refused(val code: InstallmentRefusalCode) : BulkMarkResult
}

/**
 * The pure transition tables of the installment plan and its entries (domain spec 3.1 and 3.2,
 * INV-20, INV-21, INV-32). Nothing here reads state, a clock or a store: the plan lifecycle
 * coordinator passes the facts in, performs the compare-and-set writes inside its transaction,
 * and maps a result to its own return type. A coroutine cancellation or a process death never
 * reaches this layer.
 *
 * ## Plan table (3.1)
 * | From | Trigger | Result |
 * |---|---|---|
 * | ACTIVE | STOP | CANCELLED (USER_STOP) |
 * | ACTIVE, COMPLETED | REFUND | REFUNDED (USER_REFUND) |
 * | ACTIVE | PAY_OFF | PAID_OFF (USER_PAYOFF) |
 * | ACTIVE | AUTO_COMPLETE | COMPLETED (AUTO_COMPLETED) |
 * | COMPLETED | REOPEN | ACTIVE |
 * | ACTIVE, CANCELLED | DELETE | removed, unless a PAID or REVERSED entry exists (PLAN_HAS_PAYMENTS) |
 *
 * Repeating a trigger whose target the plan is already in is `AlreadyInState`. Anything else is
 * `Illegal(ILLEGAL_TRANSITION)`: in particular every transition out of CANCELLED, REFUNDED and
 * PAID_OFF except delete from CANCELLED, PAY_OFF or STOP from COMPLETED, and DELETE from
 * COMPLETED, REFUNDED or PAID_OFF (spec 7.5: only ACTIVE, or CANCELLED without payments). A
 * payments flag is checked first, so DELETE with payments is `PLAN_HAS_PAYMENTS` from every status.
 *
 * ## Entry table (3.2)
 * | From | Trigger | To | Plan must be |
 * |---|---|---|---|
 * | SCHEDULED | AUTO_RECORD | PAID | ACTIVE, mode AUTO_RECORD, declaredOn <= due <= today, not consumed |
 * | SCHEDULED | CONFIRM | PAID | ACTIVE, today known, due <= today (INV-14) |
 * | SCHEDULED | LINK | PAID | ACTIVE |
 * | PAID | UNLINK | SCHEDULED (+1 generation) | ACTIVE or COMPLETED |
 * | PAID | EXPENSE_REMOVED | SCHEDULED (+1) / CANCELLED(EXPENSE_REMOVED) | ACTIVE, COMPLETED / CANCELLED, PAID_OFF |
 * | SCHEDULED | SKIP | SKIPPED (reason = the supplied USER_SKIP_*) | ACTIVE; skipReason is a USER_SKIP_* code |
 * | SKIPPED | UNSKIP | SCHEDULED | ACTIVE or COMPLETED |
 * | SCHEDULED | ADJUST | SCHEDULED | ACTIVE |
 * | REVERSED | REVERSED_EXPENSE_REMOVED | CANCELLED(EXPENSE_REMOVED) | REFUNDED |
 * | SCHEDULED | MARK_PAID_BEFORE_TRACKING | PAID_BEFORE_TRACKING | ACTIVE; REGULAR, unlinked, due < declaredOn |
 * | PAID_BEFORE_TRACKING | REVERT_PAID_BEFORE_TRACKING | SCHEDULED | ACTIVE or COMPLETED |
 *
 * Plan-ending operations act on entries through [entryEffect]: STOP, REFUND, PAY_OFF and SHORTEN
 * (SCHEDULED -> CANCELLED with PLAN_STOPPED / PLAN_REFUNDED / PAID_OFF_EARLY / SHORTENED, refund
 * also PAID -> REVERSED). PAID_BEFORE_TRACKING, SKIPPED, CANCELLED and REVERSED entries are untouched.
 *
 * Illegal for entries: PAID -> SKIPPED (unlink first); a second link on a PAID entry; any change
 * of a PAID entry's amount; auto-record when `autoRecordConsumed`; and for PAID_BEFORE_TRACKING:
 * direct moves to PAID, SKIPPED or CANCELLED, link or confirm, amount or date changes, PAYOFF
 * entries and entries with `dueDate >= declaredOnDate`. Every PAID_BEFORE_TRACKING mark or
 * revert on a CANCELLED, REFUNDED or PAID_OFF plan is `Illegal(ILLEGAL_TRANSITION)`, checked
 * before the entry state.
 *
 * Idempotency: an entry already in the target state is `AlreadyInState`, checked before the
 * plan state except that a terminal plan (CANCELLED, REFUNDED, PAID_OFF) refuses every
 * PAID_BEFORE_TRACKING mark or revert. So a retry after the plan completed still reports success,
 * including a mark of an already marked entry in a COMPLETED plan. An actual move to
 * PAID_BEFORE_TRACKING still requires an ACTIVE plan. LINK on a PAID entry is `Illegal`:
 * re-linking the same expense is made idempotent by the schedule link policy
 * (AlreadyLinkedToTarget), not here.
 */
object InstallmentPlanTransitions {

    // ------------------------------------------------------------------ plan name (INV-34)

    /**
     * The single INV-34 predicate: a plan name is usable when it has at least one non-whitespace
     * character. Used by `recordDueEntries`, confirm and payoff (recording preconditions, refused
     * with PLAN_NAME_REQUIRED before any expense create) and by the declare, create-standalone and
     * edit-details input checks (refused with INVALID_VALUE before any write).
     */
    fun isPlanNameUsable(displayName: String): Boolean = displayName.isNotBlank()

    // ------------------------------------------------------------------ plan level

    /**
     * Plan-level transition check.
     *
     * @param hasPaidOrReversedEntries whether the plan has any PAID or REVERSED entry (PAYOFF
     *   included). PAID_BEFORE_TRACKING entries are not payments and never count here (INV-20).
     */
    fun planTransition(
        from: InstallmentPlanStatus,
        trigger: PlanTrigger,
        hasPaidOrReversedEntries: Boolean = false
    ): PlanTransitionResult = when (trigger) {
        PlanTrigger.STOP -> when (from) {
            InstallmentPlanStatus.ACTIVE -> move(InstallmentPlanStatus.CANCELLED, InstallmentStatusReason.USER_STOP)
            InstallmentPlanStatus.CANCELLED -> PlanTransitionResult.AlreadyInState(from)
            else -> illegalPlan()
        }

        PlanTrigger.REFUND -> when (from) {
            InstallmentPlanStatus.ACTIVE,
            InstallmentPlanStatus.COMPLETED -> move(InstallmentPlanStatus.REFUNDED, InstallmentStatusReason.USER_REFUND)

            InstallmentPlanStatus.REFUNDED -> PlanTransitionResult.AlreadyInState(from)
            else -> illegalPlan()
        }

        PlanTrigger.PAY_OFF -> when (from) {
            InstallmentPlanStatus.ACTIVE -> move(InstallmentPlanStatus.PAID_OFF, InstallmentStatusReason.USER_PAYOFF)
            InstallmentPlanStatus.PAID_OFF -> PlanTransitionResult.AlreadyInState(from)
            else -> illegalPlan()
        }

        PlanTrigger.AUTO_COMPLETE -> when (from) {
            InstallmentPlanStatus.ACTIVE -> move(InstallmentPlanStatus.COMPLETED, InstallmentStatusReason.AUTO_COMPLETED)
            InstallmentPlanStatus.COMPLETED -> PlanTransitionResult.AlreadyInState(from)
            else -> illegalPlan()
        }

        PlanTrigger.REOPEN -> when (from) {
            InstallmentPlanStatus.COMPLETED -> move(InstallmentPlanStatus.ACTIVE, null)
            InstallmentPlanStatus.ACTIVE -> PlanTransitionResult.AlreadyInState(from)
            else -> illegalPlan()
        }

        // Spec 7.5: allowed only from ACTIVE, or CANCELLED, and only without PAID or REVERSED entries.
        // Payments are checked first so the user gets the actionable PLAN_HAS_PAYMENTS refusal.
        PlanTrigger.DELETE -> when {
            hasPaidOrReversedEntries -> PlanTransitionResult.Illegal(InstallmentRefusalCode.PLAN_HAS_PAYMENTS)
            from == InstallmentPlanStatus.ACTIVE || from == InstallmentPlanStatus.CANCELLED -> PlanTransitionResult.Delete
            else -> illegalPlan()
        }
    }

    /**
     * INV-21 and the COMPLETED -> ACTIVE reopen, evaluated after an entry change inside the same
     * transaction. An ACTIVE plan with zero SCHEDULED entries becomes COMPLETED (PAID_BEFORE_TRACKING
     * counts as non-SCHEDULED); a COMPLETED plan with at least one SCHEDULED entry becomes ACTIVE.
     * CANCELLED, REFUNDED and PAID_OFF plans never change here.
     */
    fun planStatusAfterEntryChange(planStatus: InstallmentPlanStatus, scheduledEntryCount: Int): InstallmentPlanStatus =
        when {
            planStatus == InstallmentPlanStatus.ACTIVE && scheduledEntryCount <= 0 -> InstallmentPlanStatus.COMPLETED
            planStatus == InstallmentPlanStatus.COMPLETED && scheduledEntryCount > 0 -> InstallmentPlanStatus.ACTIVE
            else -> planStatus
        }

    /**
     * What a plan-ending operation does to an entry currently in [entryStatus], or null when the
     * entry is left untouched (PAID, SKIPPED, CANCELLED, REVERSED and PAID_BEFORE_TRACKING, except
     * PAID under REFUND).
     */
    fun entryEffect(operation: PlanEntryOperation, entryStatus: InstallmentEntryStatus): EntryEffect? =
        when (operation) {
            PlanEntryOperation.STOP -> scheduledTo(entryStatus, InstallmentStatusReason.PLAN_STOPPED)
            PlanEntryOperation.PAY_OFF -> scheduledTo(entryStatus, InstallmentStatusReason.PAID_OFF_EARLY)
            PlanEntryOperation.SHORTEN -> scheduledTo(entryStatus, InstallmentStatusReason.SHORTENED)
            PlanEntryOperation.REFUND -> when (entryStatus) {
                InstallmentEntryStatus.SCHEDULED ->
                    EntryEffect(InstallmentEntryStatus.CANCELLED, InstallmentStatusReason.PLAN_REFUNDED)

                InstallmentEntryStatus.PAID ->
                    EntryEffect(InstallmentEntryStatus.REVERSED, reason = null, flagExpenseReversed = true)

                else -> null
            }
        }

    // ------------------------------------------------------------------ entry level

    /** Entry-level transition check; see the class documentation for the full table. */
    fun entryTransition(context: EntryTransitionContext, trigger: EntryTrigger): EntryTransitionResult {
        val c = context
        val planActive = c.planStatus == InstallmentPlanStatus.ACTIVE
        val planActiveOrCompleted = planActive || c.planStatus == InstallmentPlanStatus.COMPLETED
        return when (trigger) {
            EntryTrigger.AUTO_RECORD -> when {
                c.entryStatus == InstallmentEntryStatus.PAID -> EntryTransitionResult.AlreadyInState(c.entryStatus)
                autoRecordDecision(c, hasCandidateCharge = false) == AutoRecordDecision.Record ->
                    EntryTransitionResult.Move(
                        to = InstallmentEntryStatus.PAID,
                        setsAutoRecordConsumed = true
                    )

                else -> illegalEntry()
            }

            EntryTrigger.CONFIRM -> when {
                c.entryStatus == InstallmentEntryStatus.PAID -> EntryTransitionResult.AlreadyInState(c.entryStatus)
                // INV-14: a CONFIRM never records a future charge; an unknown today fails closed.
                canBecomePaid(c, planActive) && c.todayEpochDay != null && c.dueEpochDay <= c.todayEpochDay ->
                    EntryTransitionResult.Move(InstallmentEntryStatus.PAID)

                else -> illegalEntry()
            }

            EntryTrigger.LINK -> when {
                canBecomePaid(c, planActive) -> EntryTransitionResult.Move(InstallmentEntryStatus.PAID)
                else -> illegalEntry()
            }

            EntryTrigger.UNLINK -> when {
                c.entryStatus == InstallmentEntryStatus.SCHEDULED -> EntryTransitionResult.AlreadyInState(c.entryStatus)
                isUnlinkable(c.entryStatus, c.entryKind, c.planStatus) ->
                    EntryTransitionResult.Move(InstallmentEntryStatus.SCHEDULED, recordGenerationDelta = 1)

                else -> illegalEntry()
            }

            EntryTrigger.EXPENSE_REMOVED -> expenseRemoved(c, planActiveOrCompleted)

            EntryTrigger.SKIP -> when {
                c.entryStatus == InstallmentEntryStatus.SKIPPED -> EntryTransitionResult.AlreadyInState(c.entryStatus)
                planActive &&
                    c.entryStatus == InstallmentEntryStatus.SCHEDULED &&
                    c.entryKind == InstallmentEntryKind.REGULAR &&
                    !c.hasLinkedExpense &&
                    c.skipReason != null &&
                    c.skipReason.isUserSkipReason ->
                    EntryTransitionResult.Move(InstallmentEntryStatus.SKIPPED, reason = c.skipReason)

                else -> illegalEntry()
            }

            EntryTrigger.UNSKIP -> when {
                c.entryStatus == InstallmentEntryStatus.SCHEDULED -> EntryTransitionResult.AlreadyInState(c.entryStatus)
                c.entryStatus == InstallmentEntryStatus.SKIPPED && planActiveOrCompleted ->
                    EntryTransitionResult.Move(InstallmentEntryStatus.SCHEDULED)

                else -> illegalEntry()
            }

            EntryTrigger.ADJUST -> when {
                planActive &&
                    c.entryStatus == InstallmentEntryStatus.SCHEDULED &&
                    c.entryKind == InstallmentEntryKind.REGULAR &&
                    !c.hasLinkedExpense -> EntryTransitionResult.Move(InstallmentEntryStatus.SCHEDULED)

                else -> illegalEntry()
            }

            EntryTrigger.MARK_PAID_BEFORE_TRACKING -> when {
                // Any mark on a CANCELLED, REFUNDED or PAID_OFF plan is illegal, checked first.
                c.planStatus.isTerminal -> illegalEntry()
                // A retry after the plan completed still reports success (idempotency).
                c.entryStatus == InstallmentEntryStatus.PAID_BEFORE_TRACKING ->
                    EntryTransitionResult.AlreadyInState(c.entryStatus)

                // An actual move requires an ACTIVE plan.
                !planActive -> illegalEntry()
                c.entryStatus == InstallmentEntryStatus.SCHEDULED &&
                    c.entryKind == InstallmentEntryKind.REGULAR &&
                    !c.hasLinkedExpense &&
                    c.dueEpochDay < c.declaredOnEpochDay ->
                    EntryTransitionResult.Move(InstallmentEntryStatus.PAID_BEFORE_TRACKING)

                else -> EntryTransitionResult.Illegal(InstallmentRefusalCode.INVALID_PAID_BEFORE_TRACKING)
            }

            EntryTrigger.REVERT_PAID_BEFORE_TRACKING -> when {
                !planActiveOrCompleted -> illegalEntry()
                c.entryStatus == InstallmentEntryStatus.SCHEDULED -> EntryTransitionResult.AlreadyInState(c.entryStatus)
                c.entryStatus == InstallmentEntryStatus.PAID_BEFORE_TRACKING ->
                    // recordGeneration and autoRecordConsumed stay unchanged.
                    EntryTransitionResult.Move(InstallmentEntryStatus.SCHEDULED)

                else -> illegalEntry()
            }

            EntryTrigger.REVERSED_EXPENSE_REMOVED -> when {
                c.entryStatus == InstallmentEntryStatus.CANCELLED -> EntryTransitionResult.AlreadyInState(c.entryStatus)
                c.entryStatus == InstallmentEntryStatus.REVERSED && c.planStatus == InstallmentPlanStatus.REFUNDED ->
                    EntryTransitionResult.Move(
                        InstallmentEntryStatus.CANCELLED,
                        reason = InstallmentStatusReason.EXPENSE_REMOVED
                    )

                else -> illegalEntry()
            }
        }
    }

    /**
     * Whether auto-record may create the expense for an entry on this run (INV-12, INV-13,
     * INV-14, INV-32). Applicable only when: plan ACTIVE, mode AUTO_RECORD, entry SCHEDULED +
     * REGULAR + unlinked, `autoRecordConsumed` false, and `declaredOn <= due <= today` (an entry
     * due before the declaration date or after today is never auto-recorded). An unknown `today`
     * or mode fails closed to [AutoRecordDecision.NotApplicable]. If a candidate charge exists
     * ([hasCandidateCharge], INV-13) the run defers instead of recording.
     */
    fun autoRecordDecision(context: EntryTransitionContext, hasCandidateCharge: Boolean): AutoRecordDecision {
        val c = context
        val applicable = isAutoRecordApplicable(
            planStatus = c.planStatus,
            fulfilmentMode = c.fulfilmentMode,
            entryStatus = c.entryStatus,
            entryKind = c.entryKind,
            hasLinkedExpense = c.hasLinkedExpense,
            autoRecordConsumed = c.autoRecordConsumed,
            dueEpochDay = c.dueEpochDay,
            declaredOnEpochDay = c.declaredOnEpochDay,
            todayEpochDay = c.todayEpochDay
        )
        return when {
            !applicable -> AutoRecordDecision.NotApplicable
            hasCandidateCharge -> AutoRecordDecision.Defer(InstallmentDeferralReason.AUTO_RECORD_DEFERRED_CANDIDATE)
            else -> AutoRecordDecision.Record
        }
    }

    /**
     * The one statement of the auto-record applicability rule (INV-12, INV-14, INV-32): plan
     * ACTIVE, mode AUTO_RECORD, entry SCHEDULED + REGULAR + unlinked, `autoRecordConsumed` false,
     * and `declaredOn <= due <= today`. An unknown [todayEpochDay] or [fulfilmentMode] fails closed
     * to false. Pure and total: it never throws, so a caller may use it as a pre-filter on stored
     * values as well as inside a transaction.
     */
    fun isAutoRecordApplicable(
        planStatus: InstallmentPlanStatus,
        fulfilmentMode: InstallmentFulfilmentMode?,
        entryStatus: InstallmentEntryStatus,
        entryKind: InstallmentEntryKind,
        hasLinkedExpense: Boolean,
        autoRecordConsumed: Boolean,
        dueEpochDay: Long,
        declaredOnEpochDay: Long,
        todayEpochDay: Long?
    ): Boolean {
        val today = todayEpochDay ?: return false
        return planStatus == InstallmentPlanStatus.ACTIVE &&
            fulfilmentMode == InstallmentFulfilmentMode.AUTO_RECORD &&
            entryStatus == InstallmentEntryStatus.SCHEDULED &&
            entryKind == InstallmentEntryKind.REGULAR &&
            !hasLinkedExpense &&
            !autoRecordConsumed &&
            dueEpochDay >= declaredOnEpochDay &&
            dueEpochDay <= today
    }

    /**
     * Bulk "mark paid before tracking" (spec 3.2, INV-32): all or nothing. A terminal plan
     * (CANCELLED, REFUNDED, PAID_OFF) is refused with `ILLEGAL_TRANSITION`. Every selected entry
     * must be eligible or already marked; one ineligible entry, an empty selection, or a
     * duplicate id refuses the whole request. Entries already in the target state are reported in
     * `alreadyMarked` and need no write, so a retry on a COMPLETED plan whose selected entries
     * are all already marked is `Allowed(toMark = [], alreadyMarked = [...])`, while any entry
     * that would need a move in a COMPLETED plan refuses the whole request with
     * `ILLEGAL_TRANSITION`.
     */
    fun bulkMarkPaidBeforeTracking(
        planStatus: InstallmentPlanStatus,
        declaredOnEpochDay: Long,
        candidates: List<PaidBeforeTrackingCandidate>
    ): BulkMarkResult {
        if (planStatus.isTerminal) {
            return BulkMarkResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        }
        if (candidates.isEmpty() || candidates.map { it.entryId }.distinct().size != candidates.size) {
            return BulkMarkResult.Refused(InstallmentRefusalCode.INVALID_PAID_BEFORE_TRACKING)
        }
        val toMark = ArrayList<Long>()
        val alreadyMarked = ArrayList<Long>()
        for (candidate in candidates) {
            val result = entryTransition(
                EntryTransitionContext(
                    planStatus = planStatus,
                    entryKind = candidate.entryKind,
                    entryStatus = candidate.entryStatus,
                    hasLinkedExpense = candidate.hasLinkedExpense,
                    dueEpochDay = candidate.dueEpochDay,
                    declaredOnEpochDay = declaredOnEpochDay
                ),
                EntryTrigger.MARK_PAID_BEFORE_TRACKING
            )
            when (result) {
                is EntryTransitionResult.Move -> toMark += candidate.entryId
                is EntryTransitionResult.AlreadyInState -> alreadyMarked += candidate.entryId
                is EntryTransitionResult.Illegal -> return BulkMarkResult.Refused(result.code)
            }
        }
        return BulkMarkResult.Allowed(toMark = toMark, alreadyMarked = alreadyMarked)
    }

    /**
     * The one statement of the Unlink rule (spec 3.2, owner-approvals 56): only a PAID REGULAR entry of
     * an ACTIVE or COMPLETED plan moves back to SCHEDULED. Pure and total, so the plan-detail UI can use
     * it to decide whether to offer Unlink. This is a table question only: the coordinator still
     * re-checks inside its transaction, and a SCHEDULED entry answers `AlreadyInState`, not a move.
     */
    fun isUnlinkable(
        entryStatus: InstallmentEntryStatus,
        entryKind: InstallmentEntryKind,
        planStatus: InstallmentPlanStatus
    ): Boolean =
        entryStatus == InstallmentEntryStatus.PAID &&
            entryKind == InstallmentEntryKind.REGULAR &&
            (planStatus == InstallmentPlanStatus.ACTIVE || planStatus == InstallmentPlanStatus.COMPLETED)

    // ------------------------------------------------------------------ helpers

    private fun move(to: InstallmentPlanStatus, reason: InstallmentStatusReason?): PlanTransitionResult =
        PlanTransitionResult.Move(to, reason)

    private fun illegalPlan(): PlanTransitionResult =
        PlanTransitionResult.Illegal(InstallmentRefusalCode.ILLEGAL_TRANSITION)

    private fun illegalEntry(): EntryTransitionResult =
        EntryTransitionResult.Illegal(InstallmentRefusalCode.ILLEGAL_TRANSITION)

    private fun scheduledTo(status: InstallmentEntryStatus, reason: InstallmentStatusReason): EntryEffect? =
        if (status == InstallmentEntryStatus.SCHEDULED) EntryEffect(InstallmentEntryStatus.CANCELLED, reason) else null

    private fun canBecomePaid(c: EntryTransitionContext, planActive: Boolean): Boolean =
        planActive &&
            c.entryStatus == InstallmentEntryStatus.SCHEDULED &&
            c.entryKind == InstallmentEntryKind.REGULAR &&
            !c.hasLinkedExpense

    private fun expenseRemoved(c: EntryTransitionContext, planActiveOrCompleted: Boolean): EntryTransitionResult {
        // Spec 3.2: every detach cause (deleted, currency changed, type changed) uses EXPENSE_REMOVED.
        val reason = InstallmentStatusReason.EXPENSE_REMOVED
        return when {
            c.entryStatus == InstallmentEntryStatus.SCHEDULED || c.entryStatus == InstallmentEntryStatus.CANCELLED ->
                EntryTransitionResult.AlreadyInState(c.entryStatus)

            c.entryStatus != InstallmentEntryStatus.PAID -> illegalEntry()

            c.entryKind == InstallmentEntryKind.REGULAR && planActiveOrCompleted ->
                // The reopened SCHEDULED entry carries no reason (same as UNLINK); only the CANCELLED
                // target below records EXPENSE_REMOVED.
                EntryTransitionResult.Move(InstallmentEntryStatus.SCHEDULED, recordGenerationDelta = 1, reason = null)

            c.planStatus == InstallmentPlanStatus.CANCELLED || c.planStatus == InstallmentPlanStatus.PAID_OFF ->
                EntryTransitionResult.Move(InstallmentEntryStatus.CANCELLED, reason = reason)

            else -> illegalEntry()
        }
    }
}
