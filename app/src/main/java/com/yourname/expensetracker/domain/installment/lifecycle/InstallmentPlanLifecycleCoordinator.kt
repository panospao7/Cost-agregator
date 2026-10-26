package com.yourname.expensetracker.domain.installment.lifecycle

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.model.AutoRecordDecision
import com.yourname.expensetracker.domain.installment.model.BulkMarkResult
import com.yourname.expensetracker.domain.installment.model.EntryTransitionContext
import com.yourname.expensetracker.domain.installment.model.EntryTransitionResult
import com.yourname.expensetracker.domain.installment.model.EntryTrigger
import com.yourname.expensetracker.domain.installment.model.InstallmentAdjustReason
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentDeferralReason
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
import com.yourname.expensetracker.domain.installment.model.PlanEntryOperation
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
    private val timeProvider: TimeProvider,
    /** Bounded boolean for the auto-record candidate deferral (AE-17); never exposes expense money. */
    private val candidateChargeProbe: InstallmentCandidateChargeProbe
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
        if (!InstallmentPlanTransitions.isPlanNameUsable(terms.displayName)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_VALUE)
        }
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
        if (!InstallmentPlanTransitions.isPlanNameUsable(terms.displayName)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_VALUE)
        }
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
        if (!InstallmentPlanTransitions.isPlanNameUsable(terms.displayName)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_VALUE)
        }
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
        if (!InstallmentPlanTransitions.isPlanNameUsable(plan.displayName)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.PLAN_NAME_REQUIRED)
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

    // ------------------------------------------------------------------ end and edit operations (S6b)

    /**
     * Stop (spec 7.1, INV-20): ACTIVE -> CANCELLED(USER_STOP). SCHEDULED entries (overdue ones
     * included) become CANCELLED(PLAN_STOPPED); PAID, PAID_BEFORE_TRACKING and SKIPPED entries and
     * every expense are untouched. A retry on a CANCELLED plan is [InstallmentOpResult.AlreadyInState].
     */
    suspend fun stop(planId: Long): InstallmentOpResult<InstallmentPlanRef> =
        writeTxn("InstallmentPlanLifecycleCoordinator.stop") {
            endPlan(planId, PlanTrigger.STOP, PlanEntryOperation.STOP)
        }

    /**
     * Refund / return (spec 7.2). [refundAlreadyRecorded] = true means the bank credit was captured
     * as its own transaction, so reversing the installments too would double count: the answer
     * runs the [stop] body instead (ACTIVE -> CANCELLED). A COMPLETED plan has nothing left to
     * stop and has already ended, so that answer is [InstallmentOpResult.AlreadyInState] there,
     * never an illegal transition. Otherwise ACTIVE or COMPLETED -> REFUNDED(USER_REFUND):
     * SCHEDULED entries become CANCELLED(PLAN_REFUNDED), PAID entries become REVERSED and each
     * linked expense is flagged INSTALLMENT_REVERSED through TLC in the same transaction (INV-2).
     * No expense is deleted (INV-23). The flag batches are returned in
     * [InstallmentOpResult.Done.postCommit].
     */
    suspend fun refund(planId: Long, refundAlreadyRecorded: Boolean): InstallmentOpResult<InstallmentPlanRef> =
        writeTxn("InstallmentPlanLifecycleCoordinator.refund") {
            if (refundAlreadyRecorded) {
                endPlan(planId, PlanTrigger.STOP, PlanEntryOperation.STOP, completedIsAlreadyEnded = true)
            } else {
                endPlan(planId, PlanTrigger.REFUND, PlanEntryOperation.REFUND)
            }
        }

    /**
     * Early payoff (spec 7.3): ACTIVE -> PAID_OFF(USER_PAYOFF). [amount] null means the default,
     * the sum of the SCHEDULED entries; an explicit amount is normalised to minor units (HALF_UP,
     * 2 dp) and must be positive (a discount or a fee is the user's choice). [payoffEpochDay] must
     * be a supported day, not after [todayEpochDay] and not before the latest due date among PAID
     * and PAID_BEFORE_TRACKING entries (otherwise INVALID_DATE). SCHEDULED entries become
     * CANCELLED(PAID_OFF_EARLY), the payoff expense is created through TLC with the STRICT key
     * `installment:<planUuid>:PAYOFF:0:0` at 12:00 [zone] on the payoff date, and one PAYOFF entry
     * (seq 0) is inserted PAID and linked to it, all in one transaction.
     */
    suspend fun payOff(
        planId: Long,
        amount: Double?,
        payoffEpochDay: Long,
        todayEpochDay: Long,
        zone: ZoneId
    ): InstallmentOpResult<InstallmentPlanRef> = writeTxn("InstallmentPlanLifecycleCoordinator.payOff") {
        val now = timeProvider.now()
        val plan = loadPlan(planId)
        val goneOnPayOff = purchaseGoneRefusal(plan)
        if (goneOnPayOff != null) return@writeTxn goneOnPayOff
        val move = when (val t = InstallmentPlanTransitions.planTransition(planStatusOf(plan), PlanTrigger.PAY_OFF)) {
            is PlanTransitionResult.AlreadyInState ->
                return@writeTxn InstallmentOpResult.AlreadyInState(InstallmentPlanRef(plan.id, t.status))
            is PlanTransitionResult.Illegal -> return@writeTxn InstallmentOpResult.Refused(t.code)
            PlanTransitionResult.Delete ->
                return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
            is PlanTransitionResult.Move -> t
        }
        val entries = planDao.getEntriesForPlan(plan.id)
        val scheduledMinor = scheduledRegular(entries).sumOf { minorOf(it) }
        val payoffMinor = if (amount == null) scheduledMinor else InstallmentMinorUnits.toMinorOrNull(amount)
        if (payoffMinor == null || payoffMinor <= 0L) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_AMOUNT)
        }
        val latestSettledDue = entries
            .filter {
                it.status == InstallmentEntryStatus.PAID.name ||
                    it.status == InstallmentEntryStatus.PAID_BEFORE_TRACKING.name
            }
            .maxOfOrNull { it.dueDate }
        val dateOk = InstallmentScheduleCalculator.isSupportedDay(payoffEpochDay) &&
            payoffEpochDay <= todayEpochDay &&
            (latestSettledDue == null || payoffEpochDay >= latestSettledDue)
        if (!dateOk) return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE)
        if (!InstallmentPlanTransitions.isPlanNameUsable(plan.displayName)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.PLAN_NAME_REQUIRED)
        }

        for (entry in entries) {
            val effect = InstallmentPlanTransitions.entryEffect(PlanEntryOperation.PAY_OFF, entryStatusOf(entry)) ?: continue
            val reason = effect.reason ?: throw conflict()
            cancelScheduledEntry(entry, reason, now)
        }
        val payoffEntry = InstallmentEntry(
            planId = plan.id,
            seq = 0,
            kind = InstallmentEntryKind.PAYOFF.name,
            dueDate = payoffEpochDay,
            scheduledAmount = InstallmentMinorUnits.fromMinor(payoffMinor),
            status = InstallmentEntryStatus.PAID.name,
            statusChangedAt = now
        )
        val recorded = recordEntryExpense(plan, payoffEntry, zone)
        insertEntryRow(
            payoffEntry.copy(
                linkedExpenseId = recorded.expenseId,
                linkOrigin = InstallmentLinkOrigin.USER_CONFIRMED.name
            )
        )
        moveEndedPlan(plan, move.to, move.reason, now)
        InstallmentOpResult.Done(InstallmentPlanRef(plan.id, move.to), listOf(recorded.batch))
    }

    /**
     * Edit of the plan details (spec 7.4 E3): display name, lender label, category, fulfilment mode
     * and, while no entry is PAID or REVERSED, the currency (otherwise CURRENCY_LOCKED, INV-16).
     * Every argument is the full new value (pass the current one to leave it). An ACTIVE plan
     * accepts every field. A COMPLETED plan accepts the display name, lender label and category
     * only: a currency or fulfilment mode change on it is ILLEGAL_TRANSITION. Any other status is
     * ILLEGAL_TRANSITION. Switching the mode from CONFIRM to AUTO_RECORD is refused
     * (ILLEGAL_TRANSITION) while a SCHEDULED entry is due on or before [todayEpochDay]: the user
     * confirms or skips those first, so the later catch-up never records an entry the user never
     * confirmed. Recorded expenses are unchanged; the new values apply to expenses created
     * afterwards. A STANDALONE plan needs a category (INV-33); a given category must exist.
     * Changing nothing is [InstallmentOpResult.AlreadyInState].
     */
    suspend fun editDetails(
        planId: Long,
        displayName: String,
        lenderLabel: String?,
        categoryId: Long?,
        fulfilmentMode: InstallmentFulfilmentMode,
        currency: String,
        todayEpochDay: Long
    ): InstallmentOpResult<InstallmentPlanRef> = writeTxn("InstallmentPlanLifecycleCoordinator.editDetails") {
        if (!InstallmentPlanTransitions.isPlanNameUsable(displayName)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_VALUE)
        }
        val now = timeProvider.now()
        val plan = loadPlan(planId)
        val goneOnEdit = purchaseGoneRefusal(plan)
        if (goneOnEdit != null) return@writeTxn goneOnEdit
        val status = planStatusOf(plan)
        if (status != InstallmentPlanStatus.ACTIVE && status != InstallmentPlanStatus.COMPLETED) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        }
        val target = InstallmentCurrency.fromCodeOrNull(currency)
            ?: return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.UNSUPPORTED_CURRENCY)
        val modeChanged = fulfilmentMode.name != plan.fulfilmentMode
        if (status == InstallmentPlanStatus.COMPLETED && (modeChanged || target.code != plan.currency)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        }
        if (categoryId == null && plan.kind == InstallmentPlanKind.STANDALONE.name) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.CATEGORY_REQUIRED)
        }
        if (categoryId != null && !factsReader.categoryExists(categoryId)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.CATEGORY_NOT_FOUND)
        }
        if (target.code != plan.currency) {
            val paidCount = planDao.countEntriesForPlanByStatus(plan.id, InstallmentEntryStatus.PAID.name)
            val reversedCount = planDao.countEntriesForPlanByStatus(plan.id, InstallmentEntryStatus.REVERSED.name)
            if (paidCount + reversedCount > 0) {
                return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.CURRENCY_LOCKED)
            }
        }
        if (modeChanged && fulfilmentMode == InstallmentFulfilmentMode.AUTO_RECORD) {
            val planEntries = planDao.getEntriesForPlan(plan.id)
            val hasDueUnconfirmed = planEntries.any {
                it.status == InstallmentEntryStatus.SCHEDULED.name && it.dueDate <= todayEpochDay
            }
            if (hasDueUnconfirmed) {
                return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
            }
        }
        val unchanged = displayName == plan.displayName &&
            lenderLabel == plan.lenderLabel &&
            categoryId == plan.categoryId &&
            !modeChanged &&
            target.code == plan.currency
        if (unchanged) return@writeTxn InstallmentOpResult.AlreadyInState(InstallmentPlanRef(plan.id, status))
        val rows = planDao.updatePlanDetailsIfEditable(
            plan.id, displayName, lenderLabel, categoryId, fulfilmentMode.name, target.code, now
        )
        if (rows != 1) throw conflict()
        InstallmentOpResult.Done(InstallmentPlanRef(plan.id, status))
    }

    /**
     * Extend (spec 7.4 E2): appends [additionalCount] REGULAR SCHEDULED entries after the last
     * REGULAR seq. Their due dates continue monthly from the last non-cancelled REGULAR entry
     * (no gap after a shorten): the entry at position p among the non-cancelled REGULAR entries is
     * due on the plan's anchor day in the month p-1 months after the first due date, clamped to
     * the month end. A FIXED_PAYMENT plan appends the fixed payment and needs [respread] = false. An
     * EQUAL_SPLIT plan needs [respread] = true: the sum of the SCHEDULED entries is split again
     * over the old plus new count (INV-9, the plan's residual placement), so the total never
     * changes; the re-spread entries keep their first amount in `originalScheduledAmount`. Only an
     * ACTIVE plan is extendable, and the REGULAR count stays within 2..480.
     */
    suspend fun extend(
        planId: Long,
        additionalCount: Int,
        respread: Boolean
    ): InstallmentOpResult<InstallmentPlanRef> = writeTxn("InstallmentPlanLifecycleCoordinator.extend") {
        val now = timeProvider.now()
        val plan = loadPlan(planId)
        val goneOnExtend = purchaseGoneRefusal(plan)
        if (goneOnExtend != null) return@writeTxn goneOnExtend
        if (planStatusOf(plan) != InstallmentPlanStatus.ACTIVE) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        }
        val equalSplit = plan.splitMethod == InstallmentSplitMethod.EQUAL_SPLIT.name
        if (equalSplit != respread) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        }
        val entries = planDao.getEntriesForPlan(plan.id)
        val regular = entries.filter { it.kind == InstallmentEntryKind.REGULAR.name }
        val lastSeq = regular.maxOfOrNull { it.seq } ?: 0
        // Dates continue from the last non-cancelled REGULAR entry: a cancelled tail (after a
        // shorten) leaves no gap. The seq still continues after the highest seq of any status.
        val livePositions = regular.count { it.status != InstallmentEntryStatus.CANCELLED.name }
        val maxCount = InstallmentScheduleCalculator.MAX_COUNT
        if (additionalCount < 1 || additionalCount > maxCount || lastSeq + additionalCount > maxCount) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_COUNT)
        }
        requireSchedulableAnchors(plan)
        val newDues = List(additionalCount) { index ->
            InstallmentScheduleCalculator.dueDateEpochDay(plan.firstDueDate, plan.anchorDayOfMonth, livePositions + index)
        }
        if (newDues.any { !InstallmentScheduleCalculator.isSupportedDay(it) }) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE)
        }
        val scheduled = scheduledRegular(entries)
        val newAmounts: List<Long>
        if (equalSplit) {
            val total = scheduled.sumOf { minorOf(it) }
            val split = InstallmentScheduleCalculator.splitEqually(
                total, scheduled.size + additionalCount, residualPlacementOf(plan)
            ) ?: return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT)
            for ((index, entry) in scheduled.withIndex()) {
                if (split[index] != minorOf(entry)) {
                    rescheduleEntry(entry, split[index], entry.dueDate, InstallmentAdjustReason.RESPREAD, now)
                }
            }
            newAmounts = split.drop(scheduled.size)
        } else {
            val fixedMinor = InstallmentMinorUnits.toMinorOrNull(plan.fixedPaymentAmount ?: throw conflict())
            if (fixedMinor == null || fixedMinor <= 0L) throw conflict()
            newAmounts = List(additionalCount) { fixedMinor }
        }
        for (index in 0 until additionalCount) {
            insertEntryRow(
                InstallmentEntry(
                    planId = plan.id,
                    seq = lastSeq + index + 1,
                    kind = InstallmentEntryKind.REGULAR.name,
                    dueDate = newDues[index],
                    scheduledAmount = InstallmentMinorUnits.fromMinor(newAmounts[index]),
                    status = InstallmentEntryStatus.SCHEDULED.name,
                    statusChangedAt = now
                )
            )
        }
        updatePlanShape(plan, lastSeq + additionalCount, plan.anchorDayOfMonth, now)
        InstallmentOpResult.Done(InstallmentPlanRef(plan.id, InstallmentPlanStatus.ACTIVE))
    }

    /**
     * Shorten (spec 7.4 E2): keeps the first [remainingCount] SCHEDULED REGULAR entries (by seq)
     * and cancels the trailing ones as CANCELLED(SHORTENED). With [respread] (EQUAL_SPLIT only) the
     * sum of all SCHEDULED entries before the cut is split again over [remainingCount] (INV-9, the
     * plan's residual placement), so the total is conserved (AE-21). Without it the kept entries
     * keep their amounts. [remainingCount] must be at least 1 (ending the plan is [stop]) and at
     * most the SCHEDULED count. `installmentCount` is unchanged: cancelled entries stay as rows.
     * Only an ACTIVE plan can be shortened. Nothing to change is [InstallmentOpResult.AlreadyInState].
     */
    suspend fun shorten(
        planId: Long,
        remainingCount: Int,
        respread: Boolean
    ): InstallmentOpResult<InstallmentPlanRef> = writeTxn("InstallmentPlanLifecycleCoordinator.shorten") {
        val now = timeProvider.now()
        val plan = loadPlan(planId)
        val goneOnShorten = purchaseGoneRefusal(plan)
        if (goneOnShorten != null) return@writeTxn goneOnShorten
        if (planStatusOf(plan) != InstallmentPlanStatus.ACTIVE) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        }
        if (respread && plan.splitMethod != InstallmentSplitMethod.EQUAL_SPLIT.name) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        }
        val entries = planDao.getEntriesForPlan(plan.id)
        val scheduled = scheduledRegular(entries)
        if (remainingCount < 1 || remainingCount > scheduled.size) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_COUNT)
        }
        val kept = scheduled.take(remainingCount)
        val dropped = scheduled.drop(remainingCount)
        val split: List<Long>? = if (respread) {
            InstallmentScheduleCalculator.splitEqually(
                scheduled.sumOf { minorOf(it) }, remainingCount, residualPlacementOf(plan)
            ) ?: return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.AMOUNT_TOO_SMALL_FOR_COUNT)
        } else {
            null
        }
        var changed = 0
        for (entry in dropped) {
            val effect = InstallmentPlanTransitions.entryEffect(PlanEntryOperation.SHORTEN, entryStatusOf(entry)) ?: continue
            val reason = effect.reason ?: throw conflict()
            cancelScheduledEntry(entry, reason, now)
            changed++
        }
        if (split != null) {
            for ((index, entry) in kept.withIndex()) {
                if (split[index] != minorOf(entry)) {
                    rescheduleEntry(entry, split[index], entry.dueDate, InstallmentAdjustReason.RESPREAD, now)
                    changed++
                }
            }
        }
        val ref = InstallmentPlanRef(plan.id, InstallmentPlanStatus.ACTIVE)
        if (changed == 0) InstallmentOpResult.AlreadyInState(ref) else InstallmentOpResult.Done(ref)
    }

    /**
     * Change the due day (spec 7.4 E4). Each SCHEDULED REGULAR entry is recomputed from the plan's
     * first due date by its position p among the non-cancelled REGULAR entries (the same rule as
     * [extend]): it is due [newDayOfMonth] (clamped to the month end) in the month p-1 months
     * after the first due date. PAID, SKIPPED, PAID_BEFORE_TRACKING, CANCELLED and REVERSED
     * entries are untouched (INV-19). The plan's `anchorDayOfMonth` is updated. Only an ACTIVE
     * plan. If any recomputed date falls before the plan's declaration date, or outside the
     * supported range, nothing is written (INVALID_DATE). A result that changes nothing is
     * [InstallmentOpResult.AlreadyInState].
     */
    suspend fun changeDueDay(
        planId: Long,
        newDayOfMonth: Int
    ): InstallmentOpResult<InstallmentPlanRef> = writeTxn("InstallmentPlanLifecycleCoordinator.changeDueDay") {
        val now = timeProvider.now()
        val plan = loadPlan(planId)
        val goneOnDueDay = purchaseGoneRefusal(plan)
        if (goneOnDueDay != null) return@writeTxn goneOnDueDay
        if (planStatusOf(plan) != InstallmentPlanStatus.ACTIVE) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
        }
        if (newDayOfMonth !in 1..31) return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE)
        val entries = planDao.getEntriesForPlan(plan.id)
        val scheduled = scheduledRegular(entries)
        val ref = InstallmentPlanRef(plan.id, InstallmentPlanStatus.ACTIVE)
        if (scheduled.isEmpty()) return@writeTxn InstallmentOpResult.AlreadyInState(ref)
        requireSchedulableAnchors(plan)
        val positionById = entries
            .filter { it.kind == InstallmentEntryKind.REGULAR.name && it.status != InstallmentEntryStatus.CANCELLED.name }
            .sortedBy { it.seq }
            .withIndex()
            .associate { (index, entry) -> entry.id to index }
        val newDues = scheduled.map {
            val position = positionById[it.id] ?: throw conflict()
            InstallmentScheduleCalculator.dueDateEpochDay(plan.firstDueDate, newDayOfMonth, position)
        }
        if (newDues.any { !InstallmentScheduleCalculator.isSupportedDay(it) || it < plan.declaredOnDate }) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE)
        }
        var changed = 0
        for ((index, entry) in scheduled.withIndex()) {
            if (newDues[index] != entry.dueDate) {
                rescheduleEntry(entry, minorOf(entry), newDues[index], InstallmentAdjustReason.DUE_DAY_CHANGED, now)
                changed++
            }
        }
        if (changed == 0 && newDayOfMonth == plan.anchorDayOfMonth) {
            return@writeTxn InstallmentOpResult.AlreadyInState(ref)
        }
        updatePlanShape(plan, plan.installmentCount, newDayOfMonth, now)
        InstallmentOpResult.Done(ref)
    }

    /**
     * Shared body of [stop] and [refund]: pure plan transition, then the entry effects of the
     * plan-ending operation, then the plan move, all inside the caller's transaction. A lost
     * compare-and-set anywhere rolls the whole operation back (CONFLICT). A refunded PAID entry
     * whose expense row is gone aborts with EXPENSE_NOT_FOUND (reconcile repairs it first).
     * [completedIsAlreadyEnded] answers a COMPLETED plan with AlreadyInState instead of an illegal
     * transition (the stop answer of a refund whose credit was captured separately).
     */
    private suspend fun endPlan(
        planId: Long,
        trigger: PlanTrigger,
        operation: PlanEntryOperation,
        completedIsAlreadyEnded: Boolean = false
    ): InstallmentOpResult<InstallmentPlanRef> {
        val now = timeProvider.now()
        val plan = loadPlan(planId)
        val gone = purchaseGoneRefusal(plan)
        if (gone != null) return gone
        if (completedIsAlreadyEnded && planStatusOf(plan) == InstallmentPlanStatus.COMPLETED) {
            return InstallmentOpResult.AlreadyInState(InstallmentPlanRef(plan.id, InstallmentPlanStatus.COMPLETED))
        }
        val move = when (val t = InstallmentPlanTransitions.planTransition(planStatusOf(plan), trigger)) {
            is PlanTransitionResult.AlreadyInState ->
                return InstallmentOpResult.AlreadyInState(InstallmentPlanRef(plan.id, t.status))
            is PlanTransitionResult.Illegal -> return InstallmentOpResult.Refused(t.code)
            PlanTransitionResult.Delete -> return InstallmentOpResult.Refused(InstallmentRefusalCode.ILLEGAL_TRANSITION)
            is PlanTransitionResult.Move -> t
        }
        val batches = ArrayList<PostCommitActionBatch>()
        val entries = planDao.getEntriesForPlan(plan.id)
        for (entry in entries) {
            val effect = InstallmentPlanTransitions.entryEffect(operation, entryStatusOf(entry)) ?: continue
            when (effect.to) {
                InstallmentEntryStatus.CANCELLED -> {
                    val reason = effect.reason ?: throw conflict()
                    cancelScheduledEntry(entry, reason, now)
                }
                InstallmentEntryStatus.REVERSED -> {
                    val linked = entry.linkedExpenseId ?: throw conflict()
                    reverseSettledEntry(entry, linked, now)
                    if (effect.flagExpenseReversed) {
                        val flagBatch = setPurchaseFlag(linked, SpendingExclusionReason.INSTALLMENT_REVERSED, abortIfMissing = true)
                        if (flagBatch != null) batches += flagBatch
                    }
                }
                else -> throw conflict()
            }
        }
        moveEndedPlan(plan, move.to, move.reason, now)
        return InstallmentOpResult.Done(InstallmentPlanRef(plan.id, move.to), batches)
    }

    /** SCHEDULED -> CANCELLED with a plan-operation reason (stop, refund, payoff, shorten), compare-and-set. */
    private suspend fun cancelScheduledEntry(entry: InstallmentEntry, reason: InstallmentStatusReason, now: Long) {
        val rows = planDao.updateEntryStatusIfCurrent(
            entry.id,
            InstallmentEntryStatus.SCHEDULED.name,
            InstallmentEntryStatus.CANCELLED.name,
            reason.name,
            now
        )
        if (rows != 1) throw conflict()
    }

    /** PAID -> REVERSED (refund), compare-and-set on the entry still being linked to [expenseId]. */
    private suspend fun reverseSettledEntry(entry: InstallmentEntry, expenseId: Long, now: Long) {
        val rows = planDao.updatePaidEntryToReversedIfLinkedTo(entry.id, expenseId, now)
        if (rows != 1) throw conflict()
    }

    /** Plan to a terminal status (CANCELLED, REFUNDED, PAID_OFF), compare-and-set on the status read in the transaction. */
    private suspend fun moveEndedPlan(
        plan: InstallmentPlan,
        to: InstallmentPlanStatus,
        reason: InstallmentStatusReason?,
        now: Long
    ) {
        val rows = planDao.updatePlanStatusIfCurrent(
            planId = plan.id,
            expectedStatus = plan.status,
            newStatus = to.name,
            statusReason = reason?.name,
            terminalAt = now,
            now = now
        )
        if (rows != 1) throw conflict()
    }

    /** Inserts one entry row (payoff entry, extension entry); a unique-key hit surfaces as CONSTRAINT_VIOLATION. */
    private suspend fun insertEntryRow(entry: InstallmentEntry) {
        val ids = planDao.insertEntries(listOf(entry))
        if (ids.size != 1) throw conflict()
    }

    /** New amount (exact 2 dp from minor units) and due date of one SCHEDULED entry, compare-and-set. */
    private suspend fun rescheduleEntry(
        entry: InstallmentEntry,
        amountMinor: Long,
        dueEpochDay: Long,
        reason: InstallmentAdjustReason,
        now: Long
    ) {
        val rows = planDao.updateEntryScheduleIfScheduled(
            entry.id, InstallmentMinorUnits.fromMinor(amountMinor), dueEpochDay, reason.name, now
        )
        if (rows != 1) throw conflict()
    }

    /** Count and anchor day of an ACTIVE plan after extend or a due-day change, compare-and-set. */
    private suspend fun updatePlanShape(plan: InstallmentPlan, installmentCount: Int, anchorDayOfMonth: Int, now: Long) {
        val rows = planDao.updatePlanShapeIfActive(plan.id, installmentCount, anchorDayOfMonth, now)
        if (rows != 1) throw conflict()
    }

    private fun scheduledRegular(entries: List<InstallmentEntry>): List<InstallmentEntry> =
        entries
            .filter {
                it.kind == InstallmentEntryKind.REGULAR.name && it.status == InstallmentEntryStatus.SCHEDULED.name
            }
            .sortedBy { it.seq }

    // A stored amount that does not convert fails closed as a conflict; nothing is guessed.
    private fun minorOf(entry: InstallmentEntry): Long =
        InstallmentMinorUnits.toMinorOrNull(entry.scheduledAmount) ?: throw conflict()

    // A stored first due date or anchor day that dueDateEpochDay would reject fails closed as a
    // conflict, so its require() can never escape the transaction.
    private fun requireSchedulableAnchors(plan: InstallmentPlan) {
        if (!InstallmentScheduleCalculator.isSupportedDay(plan.firstDueDate) || plan.anchorDayOfMonth !in 1..31) {
            throw conflict()
        }
    }

    private fun residualPlacementOf(plan: InstallmentPlan): InstallmentResidualPlacement =
        InstallmentResidualPlacement.entries.firstOrNull { it.name == plan.residualPlacement } ?: throw conflict()

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

    // ------------------------------------------------------------------ due-entry recording (S7a-1)

    /**
     * Auto-record catch-up (domain spec 5.2, INV-12, INV-13, INV-14, INV-34, AE-1, AE-17, AE-31).
     * Runs [reconcile] first (D-18) and carries its report and batches into the result. Then, for
     * every ACTIVE AUTO_RECORD plan, each SCHEDULED, unlinked, unconsumed REGULAR entry with
     * `declaredOn <= due <= today` is handled in its own transaction:
     * - a blank plan name counts each such entry as [InstallmentRefusalCode.PLAN_NAME_REQUIRED] and
     *   writes nothing (INV-34);
     * - inside the transaction the plan and entry are re-read and the candidate fact is read from
     *   [InstallmentCandidateChargeProbe]; [InstallmentPlanTransitions.autoRecordDecision] then
     *   decides (the D-c no-bypass rule);
     * - a candidate charge defers the entry (INV-13); otherwise the expense is created through TLC
     *   with no user-present rate flag, the entry is claimed AUTO_RECORDED (this latches
     *   `autoRecordConsumed`, INV-12) and the plan status is settled.
     *
     * A failed, refused or throwing entry rolls back that entry only and is counted by enum; the
     * run goes on. The batches of committed entries are returned in [InstallmentOpResult.Done.postCommit]
     * for the caller to dispatch after commit. Cancellation is rethrown and discards the in-memory batches.
     * A closed write barrier ([DatabaseAccessBlockedException]) stops the run without throwing
     * (owner approval 39(a)): the report carries [InstallmentDueRunReport.stoppedByBarrier], both
     * loops end, the remaining entries are not touched and are picked up by the next run, and the
     * result is still `Done(report, batches)` with ALL batches collected so far (reconcile's and
     * those of entries committed earlier in the run), so follow-up work of committed entries is
     * never dropped. Callers map the marker to a retry. Read failures of the run-level reads
     * (`getPlansByStatus`, `getEntriesForPlan`) are not contained here and abort the run.
     */
    suspend fun recordDueEntries(
        todayEpochDay: Long,
        zone: ZoneId
    ): InstallmentOpResult<InstallmentDueRunReport> {
        val acc = DueRunAccumulator()
        val reconciled = reconcile()
        if (reconciled is InstallmentOpResult.Done) {
            acc.report = acc.report.copy(reconcile = reconciled.value)
            acc.batches += reconciled.postCommit
        }
        val activePlans = planDao.getPlansByStatus(InstallmentPlanStatus.ACTIVE.name)
        val autoRecordPlans = activePlans.filter { it.fulfilmentMode == InstallmentFulfilmentMode.AUTO_RECORD.name }
        for (plan in autoRecordPlans) {
            recordDueEntriesOfPlan(plan, todayEpochDay, zone, acc)
            if (acc.report.stoppedByBarrier) break
        }
        logDueRun(acc)
        return InstallmentOpResult.Done(acc.report, acc.batches)
    }

    /** Counts, post-commit batches and the first contained exception class of one due-entry run. */
    private class DueRunAccumulator {
        var report = InstallmentDueRunReport()
        val batches = ArrayList<PostCommitActionBatch>()
        var failureClass: String? = null

        fun noteFailure(e: Exception) {
            if (failureClass == null) failureClass = e.javaClass.simpleName
        }
    }

    /** What one due-entry transaction did: recorded, deferred for a reason, or neither (not applicable). */
    private class DueEntryOutcome(val recorded: Boolean, val deferral: InstallmentDeferralReason?)

    private suspend fun recordDueEntriesOfPlan(
        plan: InstallmentPlan,
        todayEpochDay: Long,
        zone: ZoneId,
        acc: DueRunAccumulator
    ) {
        val entries = planDao.getEntriesForPlan(plan.id)
        val dueEntries = entries.filter { isDueAutoRecordCandidate(plan, it, todayEpochDay) }
        if (dueEntries.isEmpty()) return
        // INV-34: the name check runs before any TLC call; a blank name writes nothing.
        if (!InstallmentPlanTransitions.isPlanNameUsable(plan.displayName)) {
            repeat(dueEntries.size) {
                acc.report = acc.report.withRefused(InstallmentRefusalCode.PLAN_NAME_REQUIRED)
            }
            return
        }
        for (entry in dueEntries) {
            recordOneDueEntry(plan.id, entry.id, todayEpochDay, zone, acc)
            if (acc.report.stoppedByBarrier) return
        }
    }

    // Cheap pre-filter on stored values only, using the same pure rule as the in-transaction decision.
    // It builds its inputs without context() (which throws AbortSignal outside writeTxn): a stored
    // code that does not parse is simply not a candidate.
    private fun isDueAutoRecordCandidate(plan: InstallmentPlan, entry: InstallmentEntry, todayEpochDay: Long): Boolean {
        val planStatus = InstallmentPlanStatus.fromCodeOrNull(plan.status) ?: return false
        val entryStatus = InstallmentEntryStatus.fromCodeOrNull(entry.status) ?: return false
        val entryKind = InstallmentEntryKind.fromCodeOrNull(entry.kind) ?: return false
        return InstallmentPlanTransitions.isAutoRecordApplicable(
            planStatus = planStatus,
            fulfilmentMode = InstallmentFulfilmentMode.fromCodeOrNull(plan.fulfilmentMode),
            entryStatus = entryStatus,
            entryKind = entryKind,
            hasLinkedExpense = entry.linkedExpenseId != null,
            autoRecordConsumed = entry.autoRecordConsumed,
            dueEpochDay = entry.dueDate,
            declaredOnEpochDay = plan.declaredOnDate,
            todayEpochDay = todayEpochDay
        )
    }

    /**
     * One entry, one transaction. A failure, refusal or unmapped exception is counted by enum and
     * the caller's loop continues; the committed batch of a recorded entry is kept in [acc].
     * A closed write barrier ([DatabaseAccessBlockedException]: restore, reset or maintenance) is
     * not a per-entry failure: it sets [InstallmentDueRunReport.stoppedByBarrier] (owner approval
     * 39(a)) and the callers leave both loops. Nothing is thrown, so the batches of entries
     * committed earlier in the run stay in [acc] and are returned. Cancellation is still rethrown.
     */
    private suspend fun recordOneDueEntry(
        planId: Long,
        entryId: Long,
        todayEpochDay: Long,
        zone: ZoneId,
        acc: DueRunAccumulator
    ) {
        val result = try {
            recordDueEntry(planId, entryId, todayEpochDay, zone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DatabaseAccessBlockedException) {
            acc.report = acc.report.copy(stoppedByBarrier = true)
            return
        } catch (e: Exception) {
            acc.noteFailure(e)
            acc.report = acc.report.withFailed(InstallmentOpFailure.RECORD_FAILED)
            return
        }
        when (result) {
            is InstallmentOpResult.Done -> {
                val outcome = result.value
                if (outcome.recorded) {
                    acc.report = acc.report.withRecorded()
                    acc.batches += result.postCommit
                }
                val reason = outcome.deferral
                if (reason != null) acc.report = acc.report.withDeferred(reason)
            }
            is InstallmentOpResult.AlreadyInState -> Unit
            is InstallmentOpResult.Refused -> acc.report = acc.report.withRefused(result.code)
            is InstallmentOpResult.RateRefused ->
                acc.report = acc.report.withFailed(InstallmentOpFailure.RECORD_FAILED)
            is InstallmentOpResult.Failed -> acc.report = acc.report.withFailed(result.failure)
        }
    }

    private suspend fun recordDueEntry(
        planId: Long,
        entryId: Long,
        todayEpochDay: Long,
        zone: ZoneId
    ): InstallmentOpResult<DueEntryOutcome> = writeTxn("InstallmentPlanLifecycleCoordinator.recordDueEntry") {
        val now = timeProvider.now()
        val plan = loadPlan(planId)
        val entry = loadEntry(entryId)
        val gone = purchaseGoneRefusal(plan)
        if (gone != null) return@writeTxn gone
        if (!InstallmentPlanTransitions.isPlanNameUsable(plan.displayName)) {
            return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.PLAN_NAME_REQUIRED)
        }
        val ctx = context(plan, entry, todayEpochDay)
        val notApplicable = InstallmentOpResult.AlreadyInState(DueEntryOutcome(recorded = false, deferral = null))
        if (InstallmentPlanTransitions.autoRecordDecision(ctx, hasCandidateCharge = false) == AutoRecordDecision.NotApplicable) {
            return@writeTxn notApplicable
        }
        // The candidate read happens inside this transaction, so it sees the same snapshot as the claim.
        val hasCandidateCharge = candidateChargeProbe.hasCandidate(plan, entry, zone)
        when (val decision = InstallmentPlanTransitions.autoRecordDecision(ctx, hasCandidateCharge)) {
            AutoRecordDecision.NotApplicable -> notApplicable
            is AutoRecordDecision.Defer ->
                InstallmentOpResult.Done(DueEntryOutcome(recorded = false, deferral = decision.reason))
            AutoRecordDecision.Record -> {
                if (!InstallmentScheduleCalculator.isSupportedDay(entry.dueDate)) {
                    return@writeTxn InstallmentOpResult.Refused(InstallmentRefusalCode.INVALID_DATE)
                }
                val recorded = recordEntryExpense(plan, entry, zone)
                claimEntry(entry, recorded.expenseId, InstallmentLinkOrigin.AUTO_RECORDED, now)
                settlePlanStatus(plan.id, now)
                InstallmentOpResult.Done(DueEntryOutcome(recorded = true, deferral = null), listOf(recorded.batch))
            }
        }
    }

    /** Integer counts and, when an exception was contained, its class simple name only (INV-31). */
    private fun logDueRun(acc: DueRunAccumulator) {
        val report = acc.report
        val deferred = report.deferred.values.sum()
        val refused = report.refused.values.sum()
        val failed = report.failed.values.sum()
        if (report.recorded + deferred + refused + failed == 0 && !report.stoppedByBarrier) return
        Timber.i(
            "InstallmentDueRun recorded=%d deferred=%d refused=%d failed=%d stoppedByBarrier=%b cause=%s",
            report.recorded, deferred, refused, failed, report.stoppedByBarrier, acc.failureClass ?: "none"
        )
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

    /**
     * SCHEDULED -> PAID with the link, compare-and-set; a lost race rolls the operation back. Every
     * claim, whatever the [origin] (AUTO_RECORDED, USER_CONFIRMED, USER_LINKED), latches
     * `autoRecordConsumed` (INV-12, owner approval 39(b)): once an entry has been paid or linked,
     * auto-record never re-creates it after the user deletes that expense. The signature and the
     * DAO policy row stay unchanged.
     */
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
            markAutoRecordConsumed = true,
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
