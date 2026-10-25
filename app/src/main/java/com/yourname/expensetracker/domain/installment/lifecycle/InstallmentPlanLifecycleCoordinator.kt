package com.yourname.expensetracker.domain.installment.lifecycle

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.model.BulkMarkResult
import com.yourname.expensetracker.domain.installment.model.EntryTransitionContext
import com.yourname.expensetracker.domain.installment.model.EntryTransitionResult
import com.yourname.expensetracker.domain.installment.model.EntryTrigger
import com.yourname.expensetracker.domain.installment.model.InstallmentAdjustReason
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFrequency
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentLinkOrigin
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanTransitions
import com.yourname.expensetracker.domain.installment.model.InstallmentRefusalCode
import com.yourname.expensetracker.domain.installment.model.InstallmentResidualPlacement
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.installment.model.InstallmentStatusReason
import com.yourname.expensetracker.domain.installment.model.PaidBeforeTrackingCandidate
import com.yourname.expensetracker.domain.installment.model.PlanTransitionResult
import com.yourname.expensetracker.domain.installment.model.PlanTrigger
import com.yourname.expensetracker.domain.installment.schedule.GeneratedSchedule
import com.yourname.expensetracker.domain.installment.schedule.InstallmentScheduleCalculator
import com.yourname.expensetracker.domain.installment.schedule.InstallmentScheduleRequest
import com.yourname.expensetracker.domain.installment.schedule.ScheduleGenerationResult
import com.yourname.expensetracker.domain.schedule.ExpenseScheduleLinkPolicy
import com.yourname.expensetracker.domain.schedule.ExpenseScheduleRelations
import com.yourname.expensetracker.domain.schedule.PurchaseDeclarationFacts
import com.yourname.expensetracker.domain.schedule.ScheduleLinkDecision
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.transaction.UserPresentRateRequirement
import com.yourname.expensetracker.domain.transaction.lifecycle.SpendingInclusionResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-chosen terms of a plan (domain spec 2.1, 7.0). Civil dates are epochDay values supplied by
 * the caller; the coordinator never reads a clock or a default zone for them.
 *
 * @property planUuid generated once per declare form session; makes a retry idempotent.
 * @property paidBeforeTrackingCount "payments already made: k" (INV-32).
 */
data class InstallmentPlanTerms(
    val planUuid: String,
    val displayName: String,
    val lenderLabel: String?,
    val currency: String,
    val splitMethod: InstallmentSplitMethod,
    val declaredTotal: Double?,
    val fixedPaymentAmount: Double?,
    val installmentCount: Int,
    val firstDueEpochDay: Long,
    val residualPlacement: InstallmentResidualPlacement,
    val fulfilmentMode: InstallmentFulfilmentMode,
    val paidBeforeTrackingCount: Int,
    val declaredOnEpochDay: Long
)

/**
 * The single writer of `installment_plans` and `installment_entries` (domain spec 5.1, INV-25).
 *
 * ## Legal path
 * Every write op: `writeBarrier.checkWritesAllowed` on entry, then
 * `writeBarrier.runWrite { database.withTransaction { ... } }`. Expense rows are created and the
 * purchase flag is set only through the transaction lifecycle coordinator DbOnly ops inside the
 * same transaction (INV-24); the post-commit batches they plan are returned in
 * [InstallmentOpResult.Done] and dispatched by the caller after commit. This class never touches
 * the expense store, never deletes an expense (INV-23) and reads expenses only through
 * [InstallmentExpenseFactsReader].
 *
 * ## Transaction rules
 * - Every state change is a compare-and-set; a lost race rolls the whole operation back
 *   ([InstallmentOpFailure.CONFLICT]). The plan status is re-read in the transaction before an
 *   entry claim.
 * - Pure rules come from the transition tables, the schedule calculator and the schedule link
 *   policy. Nothing is re-implemented here.
 * - A refusal made before any write is a normal return. A failure after a write throws a private
 *   signal out of the transaction so everything rolls back, then maps to a typed result. The one
 *   exception is the TLC rate refusal of [declareWithNewExpense]: it is a normal return, so TLC's
 *   own refusal events commit (G2-12) while no purchase, plan or entry row exists.
 * - `CancellationException` is never caught. Only the signal and `SQLiteConstraintException`
 *   are caught, and the constraint message is dropped (INV-31).
 *
 * ## Diagnostics
 * Bounded enum names and integer counts only (INV-31). No ids, names, amounts or dates.
 */
@Singleton
class InstallmentPlanLifecycleCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val planDao: InstallmentPlanDao,
    private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
    private val factsReader: InstallmentExpenseFactsReader,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider
) {

    // ------------------------------------------------------------------ declare / create

    /**
     * Declares a plan on an existing purchase (domain spec 5.2). [zone] converts the purchase
     * timestamp to its civil date for the FIRST_DUE_BEFORE_PURCHASE rule. A retry with the same
     * `planUuid` and purchase reports [InstallmentOpResult.AlreadyInState].
     */
    suspend fun declareOnExpense(
        expenseId: Long,
        terms: InstallmentPlanTerms,
        zone: ZoneId
    ): InstallmentOpResult<InstallmentPlanRef> = writeTxn("InstallmentPlanLifecycleCoordinator.declareOnExpense") {
        val now = timeProvider.now()
        planDao.getPlanByUuid(terms.planUuid)?.let { existing ->
            return@writeTxn if (existing.purchaseExpenseId == expenseId) {
                InstallmentOpResult.AlreadyInState(InstallmentPlanRef(existing.id, planStatusOf(existing)))
            } else {
                InstallmentOpResult.Refused(InstallmentRefusalCode.ALREADY_DECLARED)
            }
        }
        val facts = factsReader.findExpenseFacts(expenseId)
            ?: return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.PURCHASE_NOT_FOUND)
        val relations = ExpenseScheduleRelations(
            linkedInstallmentEntryId = planDao.getEntryByLinkedExpenseId(expenseId)?.id,
            linkedRecurringOccurrenceId = facts.linkedRecurringOccurrenceId,
            isPlanPurchaseRecord = planDao.getPlanByPurchaseExpenseId(expenseId) != null
        )
        val decision = ExpenseScheduleLinkPolicy.evaluateDeclaration(
            PurchaseDeclarationFacts(
                isSpendingType = facts.isSpendingType,
                isNotMine = facts.isNotMine,
                isSharedExpense = facts.isSharedExpense,
                hasSplit = facts.hasSplit,
                spendingExclusionReason = facts.spendingExclusionReason,
                relations = relations
            )
        )
        if (decision is ScheduleLinkDecision.Refused) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.fromScheduleRefusal(decision.reason))
        }
        val purchaseDay = InstallmentScheduleCalculator.civilEpochDay(facts.dateMillis, zone)
        val schedule = when (val generated = generate(terms, purchaseDay)) {
            is ScheduleGenerationResult.Refused -> return@writeTxn InstallmentOpResult.Refused(generated.code)
            is ScheduleGenerationResult.Success -> generated.schedule
        }
        val ref = insertPlanWithEntries(
            terms, InstallmentPlanKind.PURCHASE_ATTACHED, expenseId, facts.categoryId, facts.paymentMethod, schedule, now
        )
        val flagBatch = setPurchaseFlag(expenseId, SpendingExclusionReason.INSTALLMENT_PURCHASE, abortIfMissing = true)
        InstallmentOpResult.Done(ref, listOfNotNull(flagBatch))
    }

    /**
     * Creates the purchase through TLC and declares a plan on it, atomically (MEC INV-18).
     * The purchase request is flagged with [UserPresentRateRequirement] built from [entryZone]; this
     * is the only place the flag is set for the loan feature. [entryZone] is never persisted or
     * logged. A TLC rate refusal returns [InstallmentOpResult.RateRefused] before any plan row is
     * inserted, as a normal return (TLC's refusal events commit, nothing else is written).
     */
    suspend fun declareWithNewExpense(
        purchaseRequest: CreateExpenseRequest,
        entryZone: ZoneId,
        terms: InstallmentPlanTerms
    ): InstallmentOpResult<InstallmentPlanRef> = writeTxn("InstallmentPlanLifecycleCoordinator.declareWithNewExpense") {
        val now = timeProvider.now()
        planDao.getPlanByUuid(terms.planUuid)?.let { existing ->
            return@writeTxn InstallmentOpResult.AlreadyInState(InstallmentPlanRef(existing.id, planStatusOf(existing)))
        }
        // Pure checks first: nothing is created when the declaration cannot succeed.
        val decision = ExpenseScheduleLinkPolicy.evaluateDeclaration(
            PurchaseDeclarationFacts(
                isSpendingType = InstallmentExpenseFacts.isSpendingType(purchaseRequest.transactionType),
                isNotMine = purchaseRequest.isNotMine,
                isSharedExpense = purchaseRequest.isSharedExpense,
                hasSplit = InstallmentExpenseFacts.hasSplit(purchaseRequest.splitTemplateId, purchaseRequest.splitVisualization),
                spendingExclusionReason = null
            )
        )
        if (decision is ScheduleLinkDecision.Refused) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.fromScheduleRefusal(decision.reason))
        }
        val purchaseDay = InstallmentScheduleCalculator.civilEpochDay(purchaseRequest.date, entryZone)
        val schedule = when (val generated = generate(terms, purchaseDay)) {
            is ScheduleGenerationResult.Refused -> return@writeTxn InstallmentOpResult.Refused(generated.code)
            is ScheduleGenerationResult.Success -> generated.schedule
        }

        val created = transactionLifecycleCoordinator.createExpenseDbOnlyV2(
            purchaseRequest.copy(userPresentRate = UserPresentRateRequirement(entryZone))
        )
        val purchaseId = when (val result = created.value) {
            is CreateExpenseResult.Created -> result.expenseId
            is CreateExpenseResult.ValidationFailed -> {
                val rateRefusal = result.rateRefusal
                if (rateRefusal != null) {
                    // Normal return: no throw, no rollback signal, empty batch (G2-12).
                    return@writeTxn InstallmentOpResult.RateRefused(rateRefusal.reason)
                }
                throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.PURCHASE_CREATE_FAILED))
            }
            is CreateExpenseResult.DuplicateSkipped ->
                return@writeTxn InstallmentOpResult.Failed(InstallmentOpFailure.PURCHASE_DUPLICATE_SKIPPED)
            is CreateExpenseResult.InsertConflict ->
                throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.PURCHASE_INSERT_CONFLICT))
            is CreateExpenseResult.Error -> {
                if (result.exception is CancellationException) throw result.exception
                throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.PURCHASE_CREATE_FAILED))
            }
        }
        // Copy category and payment method from the stored row so they match the purchase exactly (INV-33).
        val facts = factsReader.findExpenseFacts(purchaseId)
            ?: throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.EXPENSE_NOT_FOUND))
        // Re-evaluate on the stored row: if TLC normalised the purchase into something the
        // declaration rules refuse, abort and roll the created purchase back (F10).
        val storedDecision = ExpenseScheduleLinkPolicy.evaluateDeclaration(
            PurchaseDeclarationFacts(
                isSpendingType = facts.isSpendingType,
                isNotMine = facts.isNotMine,
                isSharedExpense = facts.isSharedExpense,
                hasSplit = facts.hasSplit,
                spendingExclusionReason = facts.spendingExclusionReason,
                relations = ExpenseScheduleRelations(
                    linkedInstallmentEntryId = null,
                    linkedRecurringOccurrenceId = facts.linkedRecurringOccurrenceId,
                    isPlanPurchaseRecord = false
                )
            )
        )
        if (storedDecision is ScheduleLinkDecision.Refused) {
            throw AbortSignal(
                InstallmentOpResult.Refused(InstallmentRefusalCode.fromScheduleRefusal(storedDecision.reason))
            )
        }
        val ref = insertPlanWithEntries(
            terms, InstallmentPlanKind.PURCHASE_ATTACHED, purchaseId, facts.categoryId, facts.paymentMethod, schedule, now
        )
        val flagBatch = setPurchaseFlag(purchaseId, SpendingExclusionReason.INSTALLMENT_PURCHASE, abortIfMissing = true)
        InstallmentOpResult.Done(ref, listOfNotNull(created.postCommitActions, flagBatch))
    }

    /**
     * Creates a standalone plan (no purchase). Always FIXED_PAYMENT (spec 2.1): [terms] is forced to
     * that split method with no declared total. The category is required and must exist (INV-33).
     */
    suspend fun createStandalone(
        terms: InstallmentPlanTerms,
        categoryId: Long?,
        paymentMethod: PaymentMethod
    ): InstallmentOpResult<InstallmentPlanRef> = writeTxn("InstallmentPlanLifecycleCoordinator.createStandalone") {
        val now = timeProvider.now()
        planDao.getPlanByUuid(terms.planUuid)?.let { existing ->
            return@writeTxn InstallmentOpResult.AlreadyInState(InstallmentPlanRef(existing.id, planStatusOf(existing)))
        }
        val standalone = terms.copy(splitMethod = InstallmentSplitMethod.FIXED_PAYMENT, declaredTotal = null)
        val schedule = when (val generated = generate(standalone, purchaseEpochDay = null)) {
            is ScheduleGenerationResult.Refused -> return@writeTxn InstallmentOpResult.Refused(generated.code)
            is ScheduleGenerationResult.Success -> generated.schedule
        }
        if (categoryId == null) return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.CATEGORY_REQUIRED)
        if (!factsReader.categoryExists(categoryId)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND)
        }
        val ref = insertPlanWithEntries(
            standalone, InstallmentPlanKind.STANDALONE, null, categoryId, paymentMethod, schedule, now
        )
        InstallmentOpResult.Done(ref)
    }

    // ------------------------------------------------------------------ entry operations

    /**
     * "Mark paid": creates the installment expense through TLC (STRICT_EXTERNAL_ID) and claims the
     * entry in the same transaction (INV-14: the entry must be due, [todayEpochDay] is required).
     * [zone] places the expense at 12:00 local on the due date.
     */
    suspend fun confirmEntry(
        entryId: Long,
        todayEpochDay: Long,
        zone: ZoneId
    ): InstallmentOpResult<InstallmentEntryRef> = writeTxn("InstallmentPlanLifecycleCoordinator.confirmEntry") {
        val now = timeProvider.now()
        val entry = loadEntry(entryId)
        val plan = loadPlan(entry.planId)
        val goneOnConfirm = purchaseGoneRefusal(plan)
        if (goneOnConfirm != null) return@writeTxn goneOnConfirm
        when (val t = InstallmentPlanTransitions.entryTransition(context(plan, entry, todayEpochDay), EntryTrigger.CONFIRM)) {
            is EntryTransitionResult.AlreadyInState ->
                return@writeTxn InstallmentOpResult.AlreadyInState(entryRef(entry, planStatusOf(plan)))
            is EntryTransitionResult.Illegal -> return@writeTxn InstallmentOpResult.Refused(t.code)
            is EntryTransitionResult.Move -> Unit
        }
        if (!InstallmentScheduleCalculator.isSupportedDay(entry.dueDate)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE)
        }
        val recorded = recordEntryExpense(plan, entry, zone)
        claimEntry(entry, recorded.expenseId, InstallmentLinkOrigin.USER_CONFIRMED, now)
        val planStatus = settlePlanStatus(plan.id, now)
        InstallmentOpResult.Done(
            entryRef(entry, planStatus, InstallmentEntryStatus.PAID, recorded.expenseId),
            listOf(recorded.batch)
        )
    }

    /** Links an existing expense to a SCHEDULED entry (policy check, then compare-and-set). */
    suspend fun linkExpense(
        entryId: Long,
        expenseId: Long
    ): InstallmentOpResult<InstallmentEntryRef> = writeTxn("InstallmentPlanLifecycleCoordinator.linkExpense") {
        val now = timeProvider.now()
        val entry = loadEntry(entryId)
        val plan = loadPlan(entry.planId)
        val goneOnLink = purchaseGoneRefusal(plan)
        if (goneOnLink != null) return@writeTxn goneOnLink
        val facts = factsReader.findExpenseFacts(expenseId)
            ?: return@writeTxn InstallmentOpResult.Failed(InstallmentOpFailure.EXPENSE_NOT_FOUND)
        // Links of any entry status (PAID and REVERSED included) count for exclusivity.
        val relations = InstallmentLinkRelations.forExistingExpense(
            linkedInstallmentEntryId = planDao.getEntryByLinkedExpenseId(expenseId)?.id,
            planPurchaseRecordExists = planDao.getPlanByPurchaseExpenseId(expenseId) != null,
            facts = facts
        )
        when (val d = ExpenseScheduleLinkPolicy.evaluateInstallmentLink(
            plan.currency, facts.currency, facts.isSpendingType, entry.id, relations
        )) {
            ScheduleLinkDecision.AlreadyLinkedToTarget ->
                return@writeTxn InstallmentOpResult.AlreadyInState(entryRef(entry, planStatusOf(plan)))
            is ScheduleLinkDecision.Refused ->
                return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.fromScheduleRefusal(d.reason))
            ScheduleLinkDecision.Allowed -> Unit
        }
        when (val t = InstallmentPlanTransitions.entryTransition(context(plan, entry), EntryTrigger.LINK)) {
            is EntryTransitionResult.AlreadyInState ->
                return@writeTxn InstallmentOpResult.AlreadyInState(entryRef(entry, planStatusOf(plan)))
            is EntryTransitionResult.Illegal -> return@writeTxn InstallmentOpResult.Refused(t.code)
            is EntryTransitionResult.Move -> Unit
        }
        claimEntry(entry, expenseId, InstallmentLinkOrigin.USER_LINKED, now)
        val planStatus = settlePlanStatus(plan.id, now)
        InstallmentOpResult.Done(entryRef(entry, planStatus, InstallmentEntryStatus.PAID, expenseId))
    }

    /** Unlinks the expense of a PAID entry: SCHEDULED again, `recordGeneration` + 1. The expense is untouched. */
    suspend fun unlinkExpense(
        entryId: Long
    ): InstallmentOpResult<InstallmentEntryRef> = writeTxn("InstallmentPlanLifecycleCoordinator.unlinkExpense") {
        val now = timeProvider.now()
        val entry = loadEntry(entryId)
        val plan = loadPlan(entry.planId)
        val goneOnUnlink = purchaseGoneRefusal(plan)
        if (goneOnUnlink != null) return@writeTxn goneOnUnlink
        when (val t = InstallmentPlanTransitions.entryTransition(context(plan, entry), EntryTrigger.UNLINK)) {
            is EntryTransitionResult.AlreadyInState ->
                return@writeTxn InstallmentOpResult.AlreadyInState(entryRef(entry, planStatusOf(plan)))
            is EntryTransitionResult.Illegal -> return@writeTxn InstallmentOpResult.Refused(t.code)
            is EntryTransitionResult.Move -> Unit
        }
        val linked = entry.linkedExpenseId
            ?: return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        if (planDao.releaseEntryLink(entry.id, linked, null, now) != 1) throw conflict()
        val planStatus = settlePlanStatus(plan.id, now)
        InstallmentOpResult.Done(entryRef(entry, planStatus, InstallmentEntryStatus.SCHEDULED, null))
    }

    /** Skips a SCHEDULED entry with one of the bounded USER_SKIP_* reasons (OQ-20). */
    suspend fun skip(
        entryId: Long,
        reason: InstallmentStatusReason
    ): InstallmentOpResult<InstallmentEntryRef> = writeTxn("InstallmentPlanLifecycleCoordinator.skip") {
        plainEntryMove(entryId, EntryTrigger.SKIP, skipReason = reason)
    }

    /** Returns a SKIPPED entry to SCHEDULED; a COMPLETED plan reopens in the same transaction. */
    suspend fun unskip(
        entryId: Long
    ): InstallmentOpResult<InstallmentEntryRef> = writeTxn("InstallmentPlanLifecycleCoordinator.unskip") {
        plainEntryMove(entryId, EntryTrigger.UNSKIP, skipReason = null)
    }

    /**
     * Marks a bulk selection of past entries as paid before tracking (INV-32), all or nothing.
     * No TLC call and no expense is touched. A retry with every entry already marked is
     * [InstallmentOpResult.AlreadyInState].
     */
    suspend fun markPaidBeforeTracking(
        planId: Long,
        entryIds: List<Long>
    ): InstallmentOpResult<InstallmentPlanRef> = writeTxn("InstallmentPlanLifecycleCoordinator.markPaidBeforeTracking") {
        val now = timeProvider.now()
        val plan = loadPlan(planId)
        val goneOnMark = purchaseGoneRefusal(plan)
        if (goneOnMark != null) return@writeTxn goneOnMark
        val byId = planDao.getEntriesForPlan(planId).associateBy { it.id }
        val candidates = ArrayList<PaidBeforeTrackingCandidate>(entryIds.size)
        for (id in entryIds) {
            val entry = byId[id] ?: return@writeTxn InstallmentOpResult.Failed(InstallmentOpFailure.ENTRY_NOT_FOUND)
            candidates += PaidBeforeTrackingCandidate(
                entryId = id,
                entryKind = entryKindOf(entry),
                entryStatus = entryStatusOf(entry),
                hasLinkedExpense = entry.linkedExpenseId != null,
                dueEpochDay = entry.dueDate
            )
        }
        when (val r = InstallmentPlanTransitions.bulkMarkPaidBeforeTracking(planStatusOf(plan), plan.declaredOnDate, candidates)) {
            is BulkMarkResult.Refused -> InstallmentOpResult.Refused(r.code)
            is BulkMarkResult.Allowed -> {
                for (id in r.toMark) {
                    val rows = planDao.updateEntryStatusIfCurrent(
                        id,
                        InstallmentEntryStatus.SCHEDULED.name,
                        InstallmentEntryStatus.PAID_BEFORE_TRACKING.name,
                        null,
                        now
                    )
                    if (rows != 1) throw conflict()
                }
                val status = settlePlanStatus(planId, now)
                val ref = InstallmentPlanRef(planId, status)
                if (r.toMark.isEmpty()) InstallmentOpResult.AlreadyInState(ref) else InstallmentOpResult.Done(ref)
            }
        }
    }

    /** Returns one PAID_BEFORE_TRACKING entry to SCHEDULED (generation and consumed flag unchanged). */
    suspend fun revertPaidBeforeTracking(
        entryId: Long
    ): InstallmentOpResult<InstallmentEntryRef> = writeTxn("InstallmentPlanLifecycleCoordinator.revertPaidBeforeTracking") {
        plainEntryMove(entryId, EntryTrigger.REVERT_PAID_BEFORE_TRACKING, skipReason = null)
    }

    /**
     * Adjusts the amount, the due date or both of one unlinked SCHEDULED entry (spec 7.4 E1,
     * INV-19). The amount is normalised to minor units (HALF_UP, 2 dp) and stored back as an exact
     * 2 dp value. `originalScheduledAmount` keeps the first pre-edit amount. The adjust reason is
     * derived from what changed.
     */
    suspend fun adjustEntry(
        entryId: Long,
        newAmount: Double?,
        newDueEpochDay: Long?
    ): InstallmentOpResult<InstallmentEntryRef> = writeTxn("InstallmentPlanLifecycleCoordinator.adjustEntry") {
        val now = timeProvider.now()
        val entry = loadEntry(entryId)
        val plan = loadPlan(entry.planId)
        val goneOnAdjust = purchaseGoneRefusal(plan)
        if (goneOnAdjust != null) return@writeTxn goneOnAdjust
        if (newAmount == null && newDueEpochDay == null) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_AMOUNT)
        }
        val amount = if (newAmount == null) {
            entry.scheduledAmount
        } else {
            val minor = InstallmentMinorUnits.toMinorOrNull(newAmount)
            if (minor == null || minor <= 0L) {
                return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_AMOUNT)
            }
            InstallmentMinorUnits.fromMinor(minor)
        }
        val due = newDueEpochDay ?: entry.dueDate
        if (!InstallmentScheduleCalculator.isSupportedDay(due)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE)
        }
        when (val t = InstallmentPlanTransitions.entryTransition(context(plan, entry), EntryTrigger.ADJUST)) {
            is EntryTransitionResult.AlreadyInState ->
                return@writeTxn InstallmentOpResult.AlreadyInState(entryRef(entry, planStatusOf(plan)))
            is EntryTransitionResult.Illegal -> return@writeTxn InstallmentOpResult.Refused(t.code)
            is EntryTransitionResult.Move -> Unit
        }
        // No effective change (same amount in minor units, same due date): nothing is written, so
        // the audit fields stay untouched.
        val currentMinor = InstallmentMinorUnits.toMinorOrNull(entry.scheduledAmount)
        val requestedMinor = InstallmentMinorUnits.toMinorOrNull(amount)
        if (due == entry.dueDate && currentMinor != null && currentMinor == requestedMinor) {
            return@writeTxn InstallmentOpResult.AlreadyInState(entryRef(entry, planStatusOf(plan)))
        }
        val reason = when {
            newAmount != null && newDueEpochDay != null -> InstallmentAdjustReason.USER_AMOUNT_AND_DATE
            newAmount != null -> InstallmentAdjustReason.USER_AMOUNT
            else -> InstallmentAdjustReason.USER_DATE
        }
        if (planDao.updateEntryScheduleIfScheduled(entry.id, amount, due, reason.name, now) != 1) throw conflict()
        InstallmentOpResult.Done(entryRef(entry, planStatusOf(plan)))
    }

    // ------------------------------------------------------------------ plan delete

    /**
     * Undo declaration (spec 7.5): allowed from ACTIVE or CANCELLED without PAID or REVERSED entries.
     * Clears the purchase flag through TLC and removes the plan and its entries in the same
     * transaction. Never deletes an expense (INV-23).
     */
    suspend fun delete(planId: Long): InstallmentOpResult<InstallmentPlanRef> =
        writeTxn("InstallmentPlanLifecycleCoordinator.delete") {
            val plan = planDao.getPlanById(planId)
                ?: return@writeTxn InstallmentOpResult.Failed(InstallmentOpFailure.PLAN_NOT_FOUND)
            val status = planStatusOf(plan)
            val hasPayments = planDao.countEntriesForPlanByStatus(planId, InstallmentEntryStatus.PAID.name) +
                planDao.countEntriesForPlanByStatus(planId, InstallmentEntryStatus.REVERSED.name) > 0
            when (val t = InstallmentPlanTransitions.planTransition(status, PlanTrigger.DELETE, hasPayments)) {
                is PlanTransitionResult.Illegal -> InstallmentOpResult.Refused(t.code)
                PlanTransitionResult.Delete -> {
                    // Payments are checked first (above); a purchase-gone plan is refused here.
                    val gone = purchaseGoneRefusal(plan)
                    if (gone != null) return@writeTxn gone
                    val batch = plan.purchaseExpenseId?.let { setPurchaseFlag(it, null, abortIfMissing = false) }
                    if (planDao.deletePlanById(planId) != 1) throw conflict()
                    InstallmentOpResult.Done(InstallmentPlanRef(planId, status), listOfNotNull(batch))
                }
                is PlanTransitionResult.Move, is PlanTransitionResult.AlreadyInState ->
                    InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
            }
        }

    // ------------------------------------------------------------------ reactions and reconcile

    /**
     * Reaction to an expense update (TLC post-commit hook, wired in a later slice): re-checks every
     * plan the expense belongs to with the reconcile rules (R1, R3, R4, R6).
     */
    suspend fun onExpenseChanged(expenseId: Long): InstallmentOpResult<InstallmentReconcileReport> =
        reconcileForExpense(expenseId)

    /**
     * Reaction to an expense delete (TLC post-commit hook, wired in a later slice): reopens or
     * cancels the linked entry (R2) and detaches a plan whose purchase is gone (R5).
     */
    suspend fun onExpenseDeleted(expenseId: Long): InstallmentOpResult<InstallmentReconcileReport> =
        reconcileForExpense(expenseId)

    /**
     * Integrity reconcile R1 to R6 over every plan, one transaction per plan (spec 5.4), then the
     * orphan-flag scan (the second halves of R1 and R3): an expense flagged INSTALLMENT_PURCHASE or
     * INSTALLMENT_REVERSED that is not referenced for that reason gets its flag cleared through
     * TLC, one transaction per expense with the references re-checked inside it. The rule is
     * reason-specific: an INSTALLMENT_PURCHASE flag is kept only if a plan has the expense as its
     * purchase; an INSTALLMENT_REVERSED flag is kept only if the linked entry is REVERSED; any
     * other reason is kept. Safe to repeat.
     *
     * A failed, refused or throwing plan is counted in [InstallmentReconcileReport.plansFailed], a
     * failed orphan candidate in [InstallmentReconcileReport.orphanFlagsFailed]; the reconcile
     * continues with the next one. Any exception of one plan's (or orphan's) transaction other than
     * cancellation is contained: it rolled that transaction back, only its exception class simple
     * name is logged (no message, no stack) and the run goes on.
     *
     * Committed batches are never dropped: they are returned only in
     * [InstallmentOpResult.Done.postCommit]. Cancellation propagates and discards the in-memory
     * batches of the run (the caller never receives them).
     */
    suspend fun reconcile(): InstallmentOpResult<InstallmentReconcileReport> {
        writeBarrier.checkWritesAllowed("InstallmentPlanLifecycleCoordinator.reconcile")
        val acc = ReconcileAccumulator()
        reconcilePlans(planDao.getAllPlans().map { it.id }, acc)
        clearOrphanFlags(acc)
        logReconcile(acc)
        return InstallmentOpResult.Done(acc.report, acc.batches)
    }

    private suspend fun reconcileForExpense(expenseId: Long): InstallmentOpResult<InstallmentReconcileReport> {
        writeBarrier.checkWritesAllowed("InstallmentPlanLifecycleCoordinator.reconcileForExpense")
        val planIds = listOfNotNull(
            planDao.getEntryByLinkedExpenseId(expenseId)?.planId,
            planDao.getPlanByPurchaseExpenseId(expenseId)?.id
        ).distinct()
        val acc = ReconcileAccumulator()
        reconcilePlans(planIds, acc)
        logReconcile(acc)
        return InstallmentOpResult.Done(acc.report, acc.batches)
    }

    /**
     * Counts and committed post-commit batches of one reconcile run. [failureClass] is the simple
     * class name of the first contained exception (bounded diagnostic, never a message).
     */
    private class ReconcileAccumulator {
        var report = InstallmentReconcileReport()
        val batches = ArrayList<PostCommitActionBatch>()
        var failureClass: String? = null

        fun noteFailure(e: Exception) {
            if (failureClass == null) failureClass = e.javaClass.simpleName
        }
    }

    /**
     * One transaction per plan. A plan that fails, is refused or throws is counted and skipped;
     * the batches of plans that committed are kept (their transactions are already durable).
     * Cancellation is rethrown, so the in-memory batches of the run are discarded with it.
     */
    private suspend fun reconcilePlans(planIds: List<Long>, acc: ReconcileAccumulator) {
        for (planId in planIds) {
            val result = try {
                reconcilePlan(planId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                acc.noteFailure(e)
                acc.report += InstallmentReconcileReport(plansFailed = 1)
                continue
            }
            when (result) {
                is InstallmentOpResult.Done -> {
                    acc.report += result.value
                    acc.batches += result.postCommit
                }
                is InstallmentOpResult.AlreadyInState -> Unit
                is InstallmentOpResult.Refused,
                is InstallmentOpResult.RateRefused,
                is InstallmentOpResult.Failed -> acc.report += InstallmentReconcileReport(plansFailed = 1)
            }
        }
    }

    /**
     * Orphan-flag scan. The candidate list is read once; every candidate is then re-checked and
     * cleared in its own transaction, so a concurrent declare or link wins over the repair.
     */
    private suspend fun clearOrphanFlags(acc: ReconcileAccumulator) {
        val installmentFlags = setOf(
            SpendingExclusionReason.INSTALLMENT_PURCHASE.name,
            SpendingExclusionReason.INSTALLMENT_REVERSED.name
        )
        val candidates = factsReader.findFlaggedExpenses().filter { it.reason in installmentFlags }
        for (candidate in candidates) {
            val result = try {
                clearOrphanFlag(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                acc.noteFailure(e)
                acc.report += InstallmentReconcileReport(orphanFlagsFailed = 1)
                continue
            }
            when (result) {
                is InstallmentOpResult.Done -> {
                    acc.report += InstallmentReconcileReport(orphanFlagsCleared = 1)
                    acc.batches += result.postCommit
                }
                is InstallmentOpResult.AlreadyInState -> Unit
                is InstallmentOpResult.Refused,
                is InstallmentOpResult.RateRefused,
                is InstallmentOpResult.Failed -> acc.report += InstallmentReconcileReport(orphanFlagsFailed = 1)
            }
        }
    }

    /**
     * Clears the flag of one orphan candidate through TLC. Inside the transaction the expense
     * must still carry the same flag and must not be referenced for that reason: an
     * INSTALLMENT_PURCHASE flag is referenced only by a plan purchase, an INSTALLMENT_REVERSED
     * flag only by a REVERSED entry link. Otherwise nothing is written
     * ([InstallmentOpResult.AlreadyInState]).
     */
    private suspend fun clearOrphanFlag(candidate: InstallmentFlaggedExpense): InstallmentOpResult<Unit> =
        writeTxn("InstallmentPlanLifecycleCoordinator.clearOrphanFlag") {
            val facts = factsReader.findExpenseFacts(candidate.expenseId)
                ?: return@writeTxn InstallmentOpResult.AlreadyInState(Unit)
            if (facts.spendingExclusionReason != candidate.reason) {
                return@writeTxn InstallmentOpResult.AlreadyInState(Unit)
            }
            // Reason-specific references (spec 5.4 R1, R3): a purchase flag is referenced only by a
            // plan purchase; a reversed flag only by a REVERSED entry link. Any other link does not
            // keep the flag alive.
            val purchasePlan = planDao.getPlanByPurchaseExpenseId(candidate.expenseId)
            val linkedEntry = planDao.getEntryByLinkedExpenseId(candidate.expenseId)
            val referenced = when (candidate.reason) {
                SpendingExclusionReason.INSTALLMENT_PURCHASE.name -> purchasePlan != null
                SpendingExclusionReason.INSTALLMENT_REVERSED.name ->
                    linkedEntry?.status == InstallmentEntryStatus.REVERSED.name
                else -> true
            }
            if (referenced) return@writeTxn InstallmentOpResult.AlreadyInState(Unit)
            val batch = setPurchaseFlag(candidate.expenseId, null, abortIfMissing = false)
            InstallmentOpResult.Done(Unit, listOfNotNull(batch))
        }

    private suspend fun reconcilePlan(planId: Long): InstallmentOpResult<InstallmentReconcileReport> =
        writeTxn("InstallmentPlanLifecycleCoordinator.reconcilePlan") {
            val now = timeProvider.now()
            val plan = planDao.getPlanById(planId)
                ?: return@writeTxn InstallmentOpResult.AlreadyInState(InstallmentReconcileReport())
            val statusBefore = planStatusOf(plan)
            val batches = ArrayList<PostCommitActionBatch>()
            var r1 = 0
            var r2 = 0
            var r3 = 0
            var r4 = 0
            var r5 = 0

            // R5 (purchase gone: detach) and R1 (purchase present: flag must be INSTALLMENT_PURCHASE).
            val purchaseId = plan.purchaseExpenseId
            if (purchaseId != null && plan.kind == InstallmentPlanKind.PURCHASE_ATTACHED.name) {
                val facts = factsReader.findExpenseFacts(purchaseId)
                if (facts == null) {
                    if (planDao.updatePlanDetachedFromPurchaseIfLinkedTo(plan.id, purchaseId, now) == 1) r5++
                } else if (facts.spendingExclusionReason != SpendingExclusionReason.INSTALLMENT_PURCHASE.name) {
                    setPurchaseFlag(purchaseId, SpendingExclusionReason.INSTALLMENT_PURCHASE, abortIfMissing = false)
                        ?.let { batches += it }
                    r1++
                }
            }

            // R2, R3, R4 over the entries that carry an expense link.
            val planEntries = planDao.getEntriesForPlan(plan.id)
            for (entry in planEntries) {
                val linked = entry.linkedExpenseId ?: continue
                when (entryStatusOf(entry)) {
                    InstallmentEntryStatus.PAID -> {
                        val facts = factsReader.findExpenseFacts(linked)
                        val missing = facts == null
                        val drifted = facts != null && (facts.currency != plan.currency || !facts.isSpendingType)
                        if ((missing || drifted) && applyExpenseRemoved(plan, entry, now)) {
                            if (missing) r2++ else r4++
                        }
                    }
                    InstallmentEntryStatus.REVERSED -> {
                        val facts = factsReader.findExpenseFacts(linked)
                        if (facts == null) {
                            if (applyReversedExpenseRemoved(plan, entry, now)) r2++
                        } else if (facts.spendingExclusionReason != SpendingExclusionReason.INSTALLMENT_REVERSED.name) {
                            setPurchaseFlag(linked, SpendingExclusionReason.INSTALLMENT_REVERSED, abortIfMissing = false)
                                ?.let { batches += it }
                            r3++
                        }
                    }
                    else -> Unit
                }
            }

            // R6 and the COMPLETED -> ACTIVE reopen after a PAID entry returned to SCHEDULED.
            val statusAfter = settlePlanStatus(plan.id, now)
            val r6 = if (statusBefore == InstallmentPlanStatus.ACTIVE && statusAfter == InstallmentPlanStatus.COMPLETED) 1 else 0

            val report = InstallmentReconcileReport(r1, r2, r3, r4, r5, r6)
            InstallmentOpResult.Done(report, batches)
        }

    // ------------------------------------------------------------------ transaction scaffolding

    /** Thrown inside a transaction to roll everything back, then mapped to its typed result. */
    private class AbortSignal(val result: InstallmentOpResult<Nothing>) : RuntimeException()

    private fun conflict(): AbortSignal = AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.CONFLICT))

    /**
     * Barrier check, then `runWrite { withTransaction { block } }`. `CancellationException` is not
     * caught (neither catch below matches it), so cancellation always propagates and rolls back.
     * A constraint exception is mapped to a bounded failure and its message is discarded.
     */
    private suspend fun <T> writeTxn(
        operation: String,
        block: suspend () -> InstallmentOpResult<T>
    ): InstallmentOpResult<T> {
        writeBarrier.checkWritesAllowed(operation)
        return try {
            writeBarrier.runWrite(DatabaseAccessOperation(operation)) {
                database.withTransaction { block() }
            }
        } catch (signal: AbortSignal) {
            signal.result
        } catch (constraint: SQLiteConstraintException) {
            // The exception (and its SQL message) is deliberately dropped: bounded code only.
            Timber.w("InstallmentPlan write failed: %s", InstallmentOpFailure.CONSTRAINT_VIOLATION.name)
            InstallmentOpResult.Failed(InstallmentOpFailure.CONSTRAINT_VIOLATION)
        }
    }

    /** SKIP, UNSKIP and REVERT_PAID_BEFORE_TRACKING: a status move with no expense link. */
    private suspend fun plainEntryMove(
        entryId: Long,
        trigger: EntryTrigger,
        skipReason: InstallmentStatusReason?
    ): InstallmentOpResult<InstallmentEntryRef> {
        val now = timeProvider.now()
        val entry = loadEntry(entryId)
        val plan = loadPlan(entry.planId)
        val goneOnMove = purchaseGoneRefusal(plan)
        if (goneOnMove != null) return goneOnMove
        val move = when (val t = InstallmentPlanTransitions.entryTransition(
            context(plan, entry, skipReason = skipReason), trigger
        )) {
            is EntryTransitionResult.AlreadyInState ->
                return InstallmentOpResult.AlreadyInState(entryRef(entry, planStatusOf(plan)))
            is EntryTransitionResult.Illegal -> return InstallmentOpResult.Refused(t.code)
            is EntryTransitionResult.Move -> t
        }
        val rows = planDao.updateEntryStatusIfCurrent(
            entry.id, entry.status, move.to.name, move.reason?.name, now
        )
        if (rows != 1) throw conflict()
        val planStatus = settlePlanStatus(plan.id, now)
        return InstallmentOpResult.Done(entryRef(entry, planStatus, move.to, null))
    }

    private fun generate(terms: InstallmentPlanTerms, purchaseEpochDay: Long?): ScheduleGenerationResult =
        InstallmentScheduleCalculator.generate(
            InstallmentScheduleRequest(
                splitMethod = terms.splitMethod,
                currency = terms.currency,
                installmentCount = terms.installmentCount,
                declaredTotal = terms.declaredTotal,
                fixedPaymentAmount = terms.fixedPaymentAmount,
                firstDueEpochDay = terms.firstDueEpochDay,
                residualPlacement = terms.residualPlacement,
                declaredOnEpochDay = terms.declaredOnEpochDay,
                purchaseEpochDay = purchaseEpochDay,
                paidBeforeTrackingCount = terms.paidBeforeTrackingCount
            )
        )

    /**
     * Inserts the plan and its REGULAR entries (INV-27). Entries are SCHEDULED and unlinked, except
     * the first k which are PAID_BEFORE_TRACKING and unlinked (INV-32). The plan stores the exact
     * 2 dp values derived from minor units, never the raw input (INV-11). A schedule with no
     * SCHEDULED entry commits the plan as COMPLETED (INV-21).
     */
    private suspend fun insertPlanWithEntries(
        terms: InstallmentPlanTerms,
        kind: InstallmentPlanKind,
        purchaseExpenseId: Long?,
        categoryId: Long?,
        paymentMethod: PaymentMethod,
        schedule: GeneratedSchedule,
        now: Long
    ): InstallmentPlanRef {
        val completed = schedule.completesImmediately
        val status = if (completed) InstallmentPlanStatus.COMPLETED else InstallmentPlanStatus.ACTIVE
        val equalSplit = terms.splitMethod == InstallmentSplitMethod.EQUAL_SPLIT
        val plan = InstallmentPlan(
            planUuid = terms.planUuid,
            kind = kind.name,
            purchaseExpenseId = purchaseExpenseId,
            displayName = terms.displayName,
            lenderLabel = terms.lenderLabel,
            currency = schedule.currency.code,
            splitMethod = terms.splitMethod.name,
            declaredTotal = if (equalSplit) InstallmentMinorUnits.fromMinor(schedule.totalMinor) else null,
            fixedPaymentAmount = if (equalSplit) null else InstallmentMinorUnits.fromMinor(schedule.entries.first().amountMinor),
            installmentCount = terms.installmentCount,
            frequency = InstallmentFrequency.MONTHLY.name,
            firstDueDate = terms.firstDueEpochDay,
            anchorDayOfMonth = schedule.anchorDayOfMonth,
            residualPlacement = terms.residualPlacement.name,
            categoryId = categoryId,
            paymentMethod = paymentMethod,
            fulfilmentMode = terms.fulfilmentMode.name,
            declaredOnDate = terms.declaredOnEpochDay,
            status = status.name,
            statusReason = if (completed) InstallmentStatusReason.AUTO_COMPLETED.name else null,
            createdAt = now,
            updatedAt = now,
            terminalAt = if (completed) now else null
        )
        val planId = planDao.insertPlan(plan)
        planDao.insertEntries(
            schedule.entries.map { generated ->
                InstallmentEntry(
                    planId = planId,
                    seq = generated.seq,
                    kind = InstallmentEntryKind.REGULAR.name,
                    dueDate = generated.dueEpochDay,
                    scheduledAmount = generated.amount,
                    status = generated.status.name,
                    statusChangedAt = now
                )
            }
        )
        return InstallmentPlanRef(planId, status)
    }

    /**
     * The only caller of the TLC flag op. A missing expense either aborts the transaction
     * ([abortIfMissing], declare) or is ignored (repair paths where the row may be gone).
     * Returns the post-commit batch, or null when nothing changed.
     */
    private suspend fun setPurchaseFlag(
        expenseId: Long,
        reason: SpendingExclusionReason?,
        abortIfMissing: Boolean
    ): PostCommitActionBatch? {
        val result = transactionLifecycleCoordinator.setSpendingExclusionDbOnly(expenseId, reason)
        return when (result.value) {
            SpendingInclusionResult.NotFound -> {
                if (abortIfMissing) {
                    throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.EXPENSE_NOT_FOUND))
                }
                null
            }
            SpendingInclusionResult.NoOp -> null
            is SpendingInclusionResult.Updated -> result.postCommitActions
        }
    }

    private class RecordedExpense(val expenseId: Long, val batch: PostCommitActionBatch)

    /**
     * Creates the installment expense of [entry] through TLC (STRICT_EXTERNAL_ID, merchant =
     * display name, no notes, plan category and payment method, never a user-present rate flag)
     * and returns its id. A STRICT replay is adopted only when the existing row is a plausible
     * payment of this entry; any other non-created result aborts the transaction with a bounded
     * failure. Shared by confirm and the later due-entry recorder.
     */
    private suspend fun recordEntryExpense(
        plan: InstallmentPlan,
        entry: InstallmentEntry,
        zone: ZoneId
    ): RecordedExpense {
        val request = CreateExpenseRequest(
            merchant = plan.displayName,
            amount = entry.scheduledAmount,
            currency = plan.currency,
            date = InstallmentScheduleCalculator.recordTimestampMillis(entry.dueDate, zone),
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.INSTALLMENT_PLAN,
            categoryId = plan.categoryId,
            notes = null,
            paymentMethod = plan.paymentMethod,
            isManualEntry = false,
            deduplicationMode = DeduplicationMode.STRICT_EXTERNAL_ID,
            idempotencyKey = idempotencyKey(plan.planUuid, entry),
            userPresentRate = null
        )
        val created = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
        val expenseId = when (val result = created.value) {
            is CreateExpenseResult.Created -> result.expenseId
            is CreateExpenseResult.DuplicateSkipped ->
                adoptableReplay(result.existingExpenseId, plan, entry)
                    ?: throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.RECORD_DUPLICATE_NOT_ADOPTABLE))
            is CreateExpenseResult.InsertConflict ->
                throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.RECORD_INSERT_CONFLICT))
            is CreateExpenseResult.ValidationFailed ->
                throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.RECORD_FAILED))
            is CreateExpenseResult.Error -> {
                if (result.exception is CancellationException) throw result.exception
                throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.RECORD_FAILED))
            }
        }
        return RecordedExpense(expenseId, created.postCommitActions)
    }

    private suspend fun adoptableReplay(existingId: Long, plan: InstallmentPlan, entry: InstallmentEntry): Long? {
        val facts = factsReader.findExpenseFacts(existingId) ?: return null
        val linkedTo = planDao.getEntryByLinkedExpenseId(existingId)?.id
        val belongsToThisEntry = facts.isSpendingType &&
            facts.currency == plan.currency &&
            facts.spendingExclusionReason == null &&
            facts.linkedRecurringOccurrenceId == null &&
            (linkedTo == null || linkedTo == entry.id)
        return if (belongsToThisEntry) existingId else null
    }

    private fun idempotencyKey(planUuid: String, entry: InstallmentEntry): String =
        "installment:$planUuid:${entry.kind}:${entry.seq}:${entry.recordGeneration}"

    /** SCHEDULED -> PAID with the link, compare-and-set; a lost race rolls the operation back. */
    private suspend fun claimEntry(
        entry: InstallmentEntry,
        expenseId: Long,
        origin: InstallmentLinkOrigin,
        now: Long
    ) {
        val rows = planDao.claimEntryForExpense(
            entryId = entry.id,
            expenseId = expenseId,
            linkOrigin = origin.name,
            markAutoRecordConsumed = false,
            now = now
        )
        if (rows != 1) throw conflict()
    }

    /**
     * PAID entry whose expense is gone or no longer qualifies (R2, R4): SCHEDULED + generation 1 in
     * an ACTIVE or COMPLETED plan, CANCELLED(EXPENSE_REMOVED) with the link cleared in a CANCELLED
     * or PAID_OFF plan. The CANCELLED target is two compare-and-sets (release the link, then
     * cancel); the extra generation bump on a cancelled entry is harmless. Returns whether it applied.
     */
    private suspend fun applyExpenseRemoved(plan: InstallmentPlan, entry: InstallmentEntry, now: Long): Boolean {
        val move = InstallmentPlanTransitions.entryTransition(context(plan, entry), EntryTrigger.EXPENSE_REMOVED)
        if (move !is EntryTransitionResult.Move) return false
        val linked = entry.linkedExpenseId ?: return false
        if (planDao.releaseEntryLink(entry.id, linked, null, now) != 1) throw conflict()
        if (move.to == InstallmentEntryStatus.CANCELLED) {
            val rows = planDao.updateEntryStatusIfCurrent(
                entry.id,
                InstallmentEntryStatus.SCHEDULED.name,
                InstallmentEntryStatus.CANCELLED.name,
                move.reason?.name,
                now
            )
            if (rows != 1) throw conflict()
        }
        return true
    }

    /** REVERSED entry whose refunded expense is gone (R2): CANCELLED(EXPENSE_REMOVED), link cleared. */
    private suspend fun applyReversedExpenseRemoved(plan: InstallmentPlan, entry: InstallmentEntry, now: Long): Boolean {
        val move = InstallmentPlanTransitions.entryTransition(context(plan, entry), EntryTrigger.REVERSED_EXPENSE_REMOVED)
        if (move !is EntryTransitionResult.Move) return false
        val linked = entry.linkedExpenseId ?: return false
        val rows = planDao.updateReversedEntryToCancelledIfLinkedTo(entry.id, linked, move.reason?.name, now)
        if (rows != 1) throw conflict()
        return true
    }

    /**
     * INV-21 and the COMPLETED -> ACTIVE reopen, in the same transaction as the entry change. The
     * plan is re-read here so the compare-and-set uses the status as it is now. COMPLETED records
     * a terminal timestamp and AUTO_COMPLETED; reopening clears both.
     */
    private suspend fun settlePlanStatus(planId: Long, now: Long): InstallmentPlanStatus {
        val plan = planDao.getPlanById(planId)
            ?: throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.PLAN_NOT_FOUND))
        val current = planStatusOf(plan)
        val scheduled = planDao.countEntriesForPlanByStatus(planId, InstallmentEntryStatus.SCHEDULED.name)
        val target = InstallmentPlanTransitions.planStatusAfterEntryChange(current, scheduled)
        if (target == current) return current
        val trigger = if (target == InstallmentPlanStatus.COMPLETED) PlanTrigger.AUTO_COMPLETE else PlanTrigger.REOPEN
        val move = InstallmentPlanTransitions.planTransition(current, trigger) as? PlanTransitionResult.Move
            ?: throw conflict()
        val rows = planDao.updatePlanStatusIfCurrent(
            planId = planId,
            expectedStatus = current.name,
            newStatus = move.to.name,
            statusReason = move.reason?.name,
            terminalAt = if (move.to == InstallmentPlanStatus.COMPLETED) now else null,
            now = now
        )
        if (rows != 1) throw conflict()
        return move.to
    }

    /**
     * Spec 3.1: any transition on a plan whose purchase has been deleted while its kind is still
     * PURCHASE_ATTACHED is illegal (reconcile detaches it first and is the only exemption). One
     * shared in-transaction check; returns the refusal, or null when the plan may proceed.
     */
    private suspend fun purchaseGoneRefusal(plan: InstallmentPlan): InstallmentOpResult.Refused? {
        val purchaseId = plan.purchaseExpenseId ?: return null
        if (plan.kind != InstallmentPlanKind.PURCHASE_ATTACHED.name) return null
        val facts = factsReader.findExpenseFacts(purchaseId)
        return if (facts == null) InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION) else null
    }

    private suspend fun loadEntry(entryId: Long): InstallmentEntry =
        planDao.getEntryById(entryId)
            ?: throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.ENTRY_NOT_FOUND))

    private suspend fun loadPlan(planId: Long): InstallmentPlan =
        planDao.getPlanById(planId)
            ?: throw AbortSignal(InstallmentOpResult.Failed(InstallmentOpFailure.PLAN_NOT_FOUND))

    // Stored codes that do not parse fail closed as a conflict; nothing guesses a status.
    private fun planStatusOf(plan: InstallmentPlan): InstallmentPlanStatus =
        InstallmentPlanStatus.fromCodeOrNull(plan.status) ?: throw conflict()

    private fun entryStatusOf(entry: InstallmentEntry): InstallmentEntryStatus =
        InstallmentEntryStatus.fromCodeOrNull(entry.status) ?: throw conflict()

    private fun entryKindOf(entry: InstallmentEntry): InstallmentEntryKind =
        InstallmentEntryKind.fromCodeOrNull(entry.kind) ?: throw conflict()

    private fun context(
        plan: InstallmentPlan,
        entry: InstallmentEntry,
        todayEpochDay: Long? = null,
        skipReason: InstallmentStatusReason? = null
    ): EntryTransitionContext = EntryTransitionContext(
        planStatus = planStatusOf(plan),
        entryKind = entryKindOf(entry),
        entryStatus = entryStatusOf(entry),
        hasLinkedExpense = entry.linkedExpenseId != null,
        dueEpochDay = entry.dueDate,
        declaredOnEpochDay = plan.declaredOnDate,
        autoRecordConsumed = entry.autoRecordConsumed,
        todayEpochDay = todayEpochDay,
        fulfilmentMode = InstallmentFulfilmentMode.fromCodeOrNull(plan.fulfilmentMode),
        skipReason = skipReason
    )

    private fun entryRef(
        entry: InstallmentEntry,
        planStatus: InstallmentPlanStatus,
        entryStatus: InstallmentEntryStatus = entryStatusOf(entry),
        linkedExpenseId: Long? = entry.linkedExpenseId
    ): InstallmentEntryRef = InstallmentEntryRef(entry.id, entry.planId, entryStatus, planStatus, linkedExpenseId)

    /**
     * Integer counts per rule plus, when an exception was contained, its class simple name only
     * (INV-31: no message, no stack, no ids). Nothing is logged when nothing happened.
     */
    private fun logReconcile(acc: ReconcileAccumulator) {
        val report = acc.report
        val total = report.r1Reflagged + report.r2Reopened + report.r3Reflagged +
            report.r4Unlinked + report.r5Detached + report.r6Completed +
            report.orphanFlagsCleared + report.plansFailed + report.orphanFlagsFailed
        if (total == 0) return
        Timber.i(
            "InstallmentReconcile r1=%d r2=%d r3=%d r4=%d r5=%d r6=%d orphans=%d plansFailed=%d orphansFailed=%d cause=%s",
            report.r1Reflagged, report.r2Reopened, report.r3Reflagged,
            report.r4Unlinked, report.r5Detached, report.r6Completed,
            report.orphanFlagsCleared, report.plansFailed, report.orphanFlagsFailed,
            acc.failureClass ?: "none"
        )
    }
}
