package com.yourname.expensetracker.domain.recurring.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.RecurringLinkTypeEligibility
import com.yourname.expensetracker.domain.recurring.RecurringNextDatePolicy
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringOccurrenceMaterializer.MaterializationResult
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.util.CancellationSafe
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

sealed interface ReminderActionResult {
    data object Updated : ReminderActionResult
    data class NoOp(val reason: String) : ReminderActionResult
    data object NotFound : ReminderActionResult
}

/**
 * R21 S1: outcome of [RecurringLifecycleCoordinator.linkExpenseToSpecificOccurrence].
 *
 * - [AlreadyLinkedToThis]: the expense is already linked to the requested occurrence.
 * - [AlreadyLinkedElsewhere]: the EXPENSE is already linked to a DIFFERENT occurrence.
 * - [NotOpen]: the requested occurrence cannot accept a link (missing, terminal status,
 *   or already held by another expense).
 */
enum class SpecificLinkResult {
    Linked,
    AlreadyLinkedToThis,
    AlreadyLinkedElsewhere,
    NotOpen,
    ExpenseNotEligible
}

/**
 * DR-11: how many calendar days before ([earlyDays]) and after ([lateDays]) its due day an expense may
 * still auto-link to a rule occurrence. Built by [RecurringLifecycleCoordinator.autoLinkWindow].
 */
internal data class AutoLinkWindow(val earlyDays: Int, val lateDays: Int) {
    companion object {
        /** Same calendar day only (every non-rule source). */
        val SAME_DAY = AutoLinkWindow(0, 0)
    }
}

/**
 * Orchestrates the full lifecycle of recurring-expense occurrences:
 * expansion, conflict resolution, materialization, and linkage.
 *
 * This is the primary entry point for generating and managing recurring
 * occurrences from manual recurring rules.
 */
@Singleton
class RecurringLifecycleCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val expander: RecurringOccurrenceExpander,
    private val resolver: OccurrenceConflictResolver,
    private val materializer: RecurringOccurrenceMaterializer,
    private val occurrenceDao: RecurringOccurrenceDao,
    private val expenseDao: ExpenseDao,
    private val timeProvider: TimeProvider,
    private val manualRecurringExpenseDao: ManualRecurringExpenseDao,
    private val reminderDeliveryDao: RecurringReminderDeliveryDao,
    private val lifecycleEventDao: RecurringLifecycleEventDao,
    private val eventWriter: RecurringLifecycleEventWriter,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val writeBarrier: DatabaseWriteBarrier,
    // FC-PAID-UNLINKED INV-11: findPaymentLinkSuggestions is fail-closed while a restore runs.
    private val readBarrier: DatabaseReadBarrier,
    private val plannedExpenseDao: PlannedExpenseDao,
    private val transactionRunner: DomainTransactionRunner,
    // The installment half of the link gate (INV-5). It holds the plan reader as a Lazy, so
    // constructing this coordinator never builds the reader (A13 common rule).
    private val installmentEligibility: RecurringInstallmentEligibility,
    // DR-03: the auto-link advances the rule through its single writer. Lazy breaks the cycle with
    // RecurringRuleLifecycleCoordinator, which already holds this coordinator as a Lazy.
    private val ruleCoordinator: dagger.Lazy<RecurringRuleLifecycleCoordinator>
) {
    companion object {
        /** Source type used for manual recurring rules. */
        const val SOURCE_TYPE_RECURRING_RULE = "RECURRING_RULE"

        /** DR-11: upper bounds of the auto-link window (days before / after the due day). */
        internal const val MAX_AUTO_LINK_EARLY_DAYS = 1
        internal const val MAX_AUTO_LINK_LATE_DAYS = 3

        /**
         * DR-11: the auto-link window for a rule whose period is [periodDays]. Starts at
         * [MAX_AUTO_LINK_EARLY_DAYS] / [MAX_AUTO_LINK_LATE_DAYS] and shrinks (late first, then early)
         * until `2*(early+late) < periodDays`, so two neighbouring windows never overlap:
         * weekly gives 1/2, daily (1 day) gives 0/0. A period of 0 or less gives 0/0.
         */
        internal fun autoLinkWindow(periodDays: Int): AutoLinkWindow {
            var early = MAX_AUTO_LINK_EARLY_DAYS
            var late = MAX_AUTO_LINK_LATE_DAYS
            while ((early > 0 || late > 0) && 2 * (early + late) >= periodDays) {
                if (late > 0) late-- else early--
            }
            return AutoLinkWindow(early, late)
        }

        /** Default reminder windows applied when no explicit windows are provided and reminders are enabled. */
        val DEFAULT_REMINDER_WINDOWS = listOf("3_DAYS_BEFORE", "DUE_DAY", "OVERDUE")

        /**
         * Statuses from which dismiss/snooze are no-ops. RP-04 P4-006 adds the
         * historical `FAILED_PERMISSION` literal: the live path no longer emits it
         * (permission denial routes to CANCELLED / PERMISSION_REVOKED), but rows
         * written before that change must be treated as terminal — no new live or
         * retry path is created for them.
         */
        val TERMINAL_STATUSES =
            setOf("DISMISSED", "CANCELLED", "FAILED_FINAL", "FAILED_PERMISSION")

        /**
         * RP-04 P4-006: controlled reason code for reminder deliveries cancelled
         * because notification permission was revoked. Always a controlled
         * constant — never raw exception text.
         */
        const val REASON_PERMISSION_REVOKED = "PERMISSION_REVOKED"

        /** Stale claim threshold in ms (5 minutes). */
        const val STALE_CLAIM_THRESHOLD_MS = 300_000L

        /** R21 S1: provenance source code for the automatic (matching) expense link. */
        const val SOURCE_DIRECT_EXPENSE_LINK = "direct_expense_link"

        /** FC-PAID-UNLINKED: provenance source of a link the user confirmed from a suggestion. */
        const val SOURCE_USER_CONFIRMED_MATCH = "user_confirmed_match"

        /** FC-PAID-UNLINKED: provenance source of a "Not this bill" decline event. */
        const val SOURCE_USER_DECLINED_MATCH = "user_declined_match"

        /** FC-PAID-UNLINKED INV-9: read scope, in days around today. */
        private const val SUGGESTION_TARGET_LOOKBACK_DAYS = 31
        private const val SUGGESTION_TARGET_LOOKAHEAD_DAYS = 7
        private const val SUGGESTION_EXPENSE_LOOKBACK_DAYS = 38
        private const val SUGGESTION_BLOCKER_LOOKBACK_DAYS = 45
        private const val SUGGESTION_BLOCKER_LOOKAHEAD_DAYS = 8

        /** R21 S1: controlled cancel reasons used by [collapseStaleDueDeliveries]. */
        const val REASON_STALE_WINDOW_SUPERSEDED = "STALE_WINDOW_SUPERSEDED"
        const val REASON_STALE_WINDOW_EXPIRED = "STALE_WINDOW_EXPIRED"

        /** Max ids per IN (...) binding (SQLite variable limit headroom). */
        private const val DELIVERY_ID_CHUNK = 900

        /** R21 S1: fixed fallback written when a provenance source code is not a bounded code. */
        const val SOURCE_UNKNOWN = "unknown_source"

        private val BOUNDED_SOURCE_PATTERN = Regex("^[A-Za-z0-9_:.\\-]{1,64}$")

        /**
         * R21 S1: provenance `source` is persisted into event metadata, so only a bounded
         * code (letters, digits, `_ : . -`, 1..64 chars, no whitespace) is accepted; anything
         * else (free text, spaces, blank, oversize) is replaced by [SOURCE_UNKNOWN]. Never throws.
         */
        internal fun sanitizeProvenanceSource(source: String): String =
            if (BOUNDED_SOURCE_PATTERN.matches(source)) source else SOURCE_UNKNOWN
    }

    /**
     * Expands, resolves, and materializes occurrences for a recurring rule
     * within a date range.
     *
     * @param ruleId The ID of the [ManualRecurringExpense] rule.
     * @param startDate Start of the range (inclusive, epoch ms).
     * @param endDate End of the range (exclusive, epoch ms).
     * @param options Controls whether reminder deliveries are created and from which source.
     * @return The [MaterializationResult] from persisting.
     * @throws IllegalArgumentException if the rule is not found.
     */
    suspend fun generateOccurrences(
        ruleId: Long,
        startDate: Long,
        endDate: Long,
        options: OccurrenceGenerationOptions
    ): MaterializationResult {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.generateOccurrences")

        // MIT-043: Wrap rule read + expense read + materialize in a single
        // transaction to prevent read-write skew. If a concurrent mutation
        // (e.g. rule deactivation, expense insert) occurs between reads and
        // write, the transaction will detect it via snapshot isolation.
        return database.withTransaction {
            val rule = manualRecurringExpenseDao.getById(ruleId)
                ?: throw IllegalArgumentException("Recurring rule not found: id=$ruleId")

            // P4-CURRENT-010: Inactive rules must not generate occurrences
            if (!rule.isActive) return@withTransaction MaterializationResult(0, 0, 0, 0)

            // Use rule.nextDate as the expansion anchor. If it's before startDate,
            // advance it by the frequency until it falls within the range.
            // RP-04 P4-005: derive the anchor day ONCE from the operation's anchorDate
            // (rule.nextDate) so the catch-up loop advances from the FIXED anchor day
            // (Jan 31 → Feb 28/29 → Mar 31) without cumulative drift, in agreement
            // with expand(). Already-drifted nextDates necessarily anchor on their
            // drifted day (no original-anchor restoration; Option A, no-schema).
            val anchorDayOfMonth = TimePeriodUtils.getDayOfMonth(
                TimePeriodUtils.getStartOfDay(rule.nextDate)
            )
            var anchorDate = rule.nextDate
            var advanceIterations = 0
            while (anchorDate < startDate && rule.frequency != RecurrenceFrequency.IRREGULAR) {
                if (++advanceIterations > 1000) {
                    Timber.w("Anchor advance loop exceeded 1000 iterations for ruleId=%d, breaking", ruleId)
                    break
                }
                anchorDate = expander.advanceDate(anchorDate, rule.frequency, anchorDayOfMonth)
            }

            val request = RecurringOccurrenceExpander.ExpandRequest(
                merchant = rule.merchant,
                amount = rule.amount,
                currency = rule.currency,
                frequency = rule.frequency,
                categoryId = rule.categoryId, // P4-CURRENT-014: Preserve rule category
                startDate = startDate,
                endDate = endDate,
                anchorDate = anchorDate,
                sourceType = SOURCE_TYPE_RECURRING_RULE,
                sourceId = rule.id,
                anchorDayOfMonth = anchorDayOfMonth
            )

            val candidates = expander.expand(request)
            val actualExpenses = expenseDao.getExpensesBetween(startDate, endDate)
            val resolved = resolver.resolve(candidates, actualExpenses)

            val windowsForMaterializer = if (options.createReminderDeliveries) {
                options.reminderWindows.ifEmpty { DEFAULT_REMINDER_WINDOWS }
            } else {
                emptyList()
            }

            // materialize() opens its own inner transaction — Room nested
            // transactions are savepoints, so a failure in the materializer
            // rolls back to this point, preserving the outer read snapshot.
            materializer.materialize(
                resolved = resolved,
                options = RecurringOccurrenceMaterializer.MaterializationOptions(
                    createReminderDeliveries = options.createReminderDeliveries,
                    reminderWindows = windowsForMaterializer,
                    generationSource = options.generationSource.name,
                    allowPastDueReminderDeliveries = options.allowPastDueReminderDeliveries
                )
            )
        }
    }

    /**
     * P6-CURRENT-024: Pure, read-only projection of occurrences for a rule.
     *
     * Computes occurrences IN MEMORY using the exact same expand → resolve
     * pipeline as [generateOccurrences], but **never** materializes rows, never
     * inserts, never writes lifecycle events, and never calls the write barrier.
     * This is the read-safe entry point for forecast / cashflow / stress paths,
     * which must not mutate the database while merely reading projections.
     *
     * The returned [RecurringOccurrence] objects are transient (id = 0, not
     * persisted). Their [RecurringOccurrence.occurrenceKey] is identical to the
     * key the materializer would assign, so callers can dedupe projected rows
     * against already-materialized rows by key with no divergence or
     * double-counting.
     *
     * Resolution against actual expenses mirrors the materializer path exactly:
     * candidates matched to an eligible expense are returned as PAID, the rest
     * as PLANNED. User-driven terminal overrides (SKIPPED/CANCELLED/IGNORED/
     * MISSED) live only in materialized rows and are therefore intentionally not
     * reconstructed here — callers honor those by reading materialized rows and
     * deduping by [RecurringOccurrence.occurrenceKey].
     *
     * @param ruleId The ID of the [ManualRecurringExpense] rule.
     * @param startDate Start of the range (inclusive, epoch ms).
     * @param endDate End of the range (exclusive, epoch ms).
     * @return Transient (unsaved) occurrences for the window. Empty if the rule
     *   is inactive.
     * @throws IllegalArgumentException if the rule is not found.
     */
    suspend fun projectOccurrences(
        ruleId: Long,
        startDate: Long,
        endDate: Long,
        alreadyClaimedExpenseIds: Set<Long> = emptySet()
    ): List<RecurringOccurrence> {
        // NOTE: deliberately NO writeBarrier.checkWritesAllowed — this is a
        // read-only projection and must succeed even when writes are blocked.
        val rule = manualRecurringExpenseDao.getById(ruleId)
            ?: throw IllegalArgumentException("Recurring rule not found: id=$ruleId")

        // P4-CURRENT-010 parity: Inactive rules project nothing.
        if (!rule.isActive) return emptyList()

        // Anchor advancement — identical to generateOccurrences so projected
        // dueDates/keys match what materialization would produce.
        // RP-04 P4-005: anchor day derived ONCE from rule.nextDate so the
        // catch-up loop stays fixed-anchor (no cumulative drift), in agreement
        // with generateOccurrences and expand().
        val anchorDayOfMonth = TimePeriodUtils.getDayOfMonth(
            TimePeriodUtils.getStartOfDay(rule.nextDate)
        )
        var anchorDate = rule.nextDate
        var advanceIterations = 0
        while (anchorDate < startDate && rule.frequency != RecurrenceFrequency.IRREGULAR) {
            if (++advanceIterations > 1000) {
                Timber.w("Anchor advance loop exceeded 1000 iterations for ruleId=%d, breaking", ruleId)
                break
            }
            anchorDate = expander.advanceDate(anchorDate, rule.frequency, anchorDayOfMonth)
        }

        val request = RecurringOccurrenceExpander.ExpandRequest(
            merchant = rule.merchant,
            amount = rule.amount,
            currency = rule.currency,
            frequency = rule.frequency,
            categoryId = rule.categoryId,
            startDate = startDate,
            endDate = endDate,
            anchorDate = anchorDate,
            sourceType = SOURCE_TYPE_RECURRING_RULE,
            sourceId = rule.id,
            anchorDayOfMonth = anchorDayOfMonth
        )

        val candidates = expander.expand(request)
        // Read-only: resolver needs actual expenses to mark PAID vs PLANNED.
        val actualExpenses = expenseDao.getExpensesBetween(startDate, endDate)
        val resolved = resolver.resolve(candidates, actualExpenses, alreadyClaimedExpenseIds)

        val now = timeProvider.now()
        return resolved.map { r -> buildTransientOccurrence(r, now) }
    }

    /**
     * Builds an in-memory [RecurringOccurrence] from a resolved candidate.
     * Mirrors the materializer's entity mapping but is never persisted
     * (id stays 0). Used by [projectOccurrences].
     */
    private fun buildTransientOccurrence(
        resolved: OccurrenceConflictResolver.ResolvedOccurrence,
        now: Long
    ): RecurringOccurrence {
        val candidate = resolved.candidate
        return RecurringOccurrence(
            sourceType = candidate.sourceType,
            sourceId = candidate.sourceId,
            occurrenceKey = candidate.occurrenceKey,
            dueDate = candidate.dueDate,
            status = resolved.status,
            linkedExpenseId = resolved.linkedExpenseId,
            expectedAmount = candidate.expectedAmount,
            expectedCurrency = candidate.expectedCurrency,
            paidAt = if (resolved.status == "PAID") now else null,
            paidAmount = resolved.paidAmount,
            paidCurrency = resolved.paidCurrency,
            frequency = candidate.frequency,
            merchant = candidate.merchant,
            categoryId = candidate.categoryId,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * Links an actual expense to a planned occurrence (marking it PAID).
     *
     * Matching rules (all must hold):
     * 1. The nearest owner N(E) is PLANNED, not yet linked. An inactive rule
     *    does not block an auto-link (R21 pin: link, PAID, no advance), but an inactive rule still
     *    yields no suggestion.
     *    N(E) is the nearest matching occurrence of ANY status inside the suggestion window
     *    S(P) (FC-PAID-UNLINKED D-3); a PAID, SKIPPED or MISSED nearest owner blocks the link,
     *    and a pair the user declined is not a candidate. Ranking: [RecurringPaymentLinkMatcher].
     * 2. Same calendar day for every source; for a [SOURCE_TYPE_RECURRING_RULE] occurrence the
     *    expense may also fall up to [autoLinkWindow]`.earlyDays` before or `.lateDays` after the
     *    due day (at most 1 early and 3 late, shrunk until `2*(early+late) < periodDays`, so weekly
     *    is 1/2 and daily is 0/0).
     * 3. Merchant key matches (case-insensitive, via [MerchantKeyGenerator]).
     * 4. Amount within ±10% tolerance of the expected amount.
     * 5. Same currency (case-insensitive).
     * 6. Expense is not excluded: isNotMine, TRANSFER, DEPOSIT, and UNKNOWN are skipped, through
     *    [RecurringLinkTypeEligibility.mayCreateLink] (owner item 37(b)).
     *    Installment-linked expenses and installment plan purchases are also
     *    refused, via [RecurringInstallmentEligibility] (INV-5).
     *
     * All database operations (read + write) execute inside a single transaction
     * to ensure atomicity: the occurrence lookup and the claim happen as one
     * atomic unit, preventing race conditions with concurrent workers.
     *
     * @param expenseId The ID of the expense to link.
     * @return `true` if a matching occurrence was found and linked.
     */
    suspend fun linkExpenseToOccurrence(expenseId: Long): Boolean {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.linkExpenseToOccurrence")

        val expense = expenseDao.getById(expenseId) ?: return false

        // Exclude non-ownership and non-spending types
        if (!RecurringLinkTypeEligibility.mayCreateLink(expense.transactionType, expense.isNotMine)) return false

        val expenseDayStart = TimePeriodUtils.getStartOfDay(expense.date)
        val expenseDayEnd = TimePeriodUtils.getEndOfDay(expense.date)
        val expenseMerchantKey = MerchantKeyGenerator.generate(expense.merchant)

        // P4-NEW-003: the occurrence lookup runs INSIDE the helper's transaction
        // (selectTarget is invoked in-transaction) — read + claim are atomic.
        val result = claimLinkSuppressInWrite(expenseId, SOURCE_DIRECT_EXPENSE_LINK) {
            // FC-PAID-UNLINKED INV-7: widen the read to the largest suggestion window (7 days each
            // side, any status) so M(E) is complete. The end bound is exclusive.
            val occurrences = occurrenceDao.getByDateRange(
                TimePeriodUtils.addDays(expenseDayStart, -RecurringPaymentLinkMatcher.MAX_SUGGESTION_WINDOW_DAYS),
                TimePeriodUtils.addDays(expenseDayEnd, RecurringPaymentLinkMatcher.MAX_SUGGESTION_WINDOW_DAYS)
            )
            val windows = HashMap<Long, LinkWindows>()
            // M(E): occurrences of any rule where the pair matches inside S(P). D-3 clarified:
            // CANCELLED and IGNORED rows (e.g. left by a deleted or replaced rule) are not
            // candidates and never own a payment; only PAID, SKIPPED, MISSED and PLANNED rows rank.
            val matches = ArrayList<RecurringOccurrence>()
            for (occ in occurrences) {
                if (occ.status == RecurringOccurrenceStatus.CANCELLED.dbValue ||
                    occ.status == RecurringOccurrenceStatus.IGNORED.dbValue
                ) continue
                val occWindows = resolveLinkWindows(occ, windows)
                val occMerchantKey = MerchantKeyGenerator.generate(occ.merchant.orEmpty())
                if (RecurringPaymentLinkMatcher.pairMatches(expense, occ, expenseMerchantKey, occMerchantKey, occWindows.suggestion)) {
                    matches.add(occ)
                }
            }

            // N(E): the nearest owner by (|d|, amount closeness, dueDate, id). A declined pair has
            // left M(E), so the first non-declined entry in rank order is the owner.
            val ranked = RecurringPaymentLinkMatcher.rankByNearest(expense, matches)
            for (candidate in ranked) {
                val events = lifecycleEventDao.getEventsForOccurrence(candidate.id)
                if (RecurringPaymentLinkMatcher.isDeclined(events, expenseId)) continue

                // D-3: claim only when N(E) is open and d is inside A(P). Any other owner means no
                // auto link (a PAID, SKIPPED or MISSED neighbour blocks). An inactive rule does not
                // block (INV-6 amended): the occurrence links and becomes PAID; the rule is not advanced.
                if (candidate.status != "PLANNED" || candidate.linkedExpenseId != null) return@claimLinkSuppressInWrite null
                val candidateWindows = resolveLinkWindows(candidate, windows)
                val delta = RecurringPaymentLinkMatcher.dayDelta(expense, candidate)
                if (!RecurringPaymentLinkMatcher.withinWindow(delta, candidateWindows.auto)) return@claimLinkSuppressInWrite null
                return@claimLinkSuppressInWrite candidate.id
            }
            null
        }

        if (result.outcome != ClaimLinkOutcome.CLAIMED) return false

        Timber.d(
            "Recurring: expense %d linked to occurrence %d (occurrenceKey=%s) — PAID, planned fulfilled, reminders suppressed",
            expenseId, result.occurrenceId, result.occurrenceKey
        )
        advanceRuleAfterAutoLink(result.occurrenceId)
        return true
    }

    /**
     * DR-03: an automatic link just made the occurrence due on the rule's current `nextDate` day
     * PAID, so the rule must move on (otherwise "Mark paid" is offered again and the savings sweep
     * drops the following bill). Runs AFTER the claim transaction committed, through the rule
     * coordinator's forward-only [RecurringRuleLifecycleCoordinator.advanceNextDate]; the candidate
     * comes from the shared [RecurringNextDatePolicy]. Auto-link only: the explicit
     * [linkExpenseToSpecificOccurrence] path leaves the advance to its caller.
     *
     * Best effort: the link already committed, so a non-cancellation failure here is logged with a
     * bounded code and never turns the link into a failure.
     */
    private suspend fun advanceRuleAfterAutoLink(
        occurrenceId: Long,
        failureCode: String = "AUTO_LINK_ADVANCE_FAILED"
    ) {
        try {
            val occurrence = occurrenceDao.getById(occurrenceId) ?: return
            if (occurrence.sourceType != SOURCE_TYPE_RECURRING_RULE) return
            val rule = manualRecurringExpenseDao.getById(occurrence.sourceId) ?: return
            if (!rule.isActive) return
            if (TimePeriodUtils.getStartOfDay(occurrence.dueDate) != TimePeriodUtils.getStartOfDay(rule.nextDate)) return

            val threshold = RecurringNextDatePolicy.threshold(rule.nextDate)
            suspend fun nextOpen() = RecurringNextDatePolicy.earliestOpenFrom(
                occurrenceDao.getBySource(SOURCE_TYPE_RECURRING_RULE, rule.id),
                threshold
            )
            var earliestOpen = nextOpen()
            if (earliestOpen == null) {
                // Mirror mark-paid: top up the horizon so the next occurrence exists and keeps the
                // anchor day (otherwise the calculator fallback clamps Jan 31 to Feb 28).
                ruleCoordinator.get().topUpHorizon(rule.id, timeProvider.now())
                earliestOpen = nextOpen()
            }
            val candidate = RecurringNextDatePolicy.candidate(earliestOpen, rule.nextDate, rule.frequency)
                ?: return
            ruleCoordinator.get().advanceNextDate(rule.id, candidate)
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            Timber.w("Recurring: next-date advance after link failed (code=%s)", failureCode)
        }
    }

    /**
     * R21 S1: links [expenseId] to one explicitly chosen occurrence (user/UI-driven
     * "this payment covers that bill"). Shares the exact claim/fulfil/suppress
     * write path with [linkExpenseToOccurrence] so a specific link followed by an
     * automatic link can never produce two PAID occurrences for one expense.
     *
     * @param source bounded controlled reason code for provenance events (never raw text).
     */
    suspend fun linkExpenseToSpecificOccurrence(
        occurrenceId: Long,
        expenseId: Long,
        source: String
    ): SpecificLinkResult {
        val result = claimLinkSuppressInWrite(expenseId, source) { occurrenceId }
        return when (result.outcome) {
            ClaimLinkOutcome.CLAIMED -> SpecificLinkResult.Linked
            ClaimLinkOutcome.ALREADY_LINKED_TO_THIS -> SpecificLinkResult.AlreadyLinkedToThis
            ClaimLinkOutcome.ALREADY_LINKED_ELSEWHERE -> SpecificLinkResult.AlreadyLinkedElsewhere
            ClaimLinkOutcome.EXPENSE_NOT_ELIGIBLE -> SpecificLinkResult.ExpenseNotEligible
            ClaimLinkOutcome.NO_TARGET,
            ClaimLinkOutcome.NOT_OPEN -> SpecificLinkResult.NotOpen
        }
    }

    /**
     * FC-PAID-UNLINKED INV-8 / INV-9 / INV-10 / INV-11: read-only suggestions of payments that may
     * already cover an open rule bill. Never writes, never materializes, never tops up the horizon,
     * and is never read by a forecast, budget, savings or Radar consumer (INV-1).
     *
     * The pair rule, the windows and the nearest-owner ranking all come from
     * [RecurringPaymentLinkMatcher]; this method only reads and composes. For each link-eligible
     * expense E (type gate, installment gate, not linked to any occurrence in range, dated up to
     * the end of today) it takes N(E), the nearest non-declined matching occurrence of any status
     * but CANCELLED and IGNORED, and suggests E for N(E) only when N(E) is an open, active-rule,
     * materialized RECURRING_RULE row due in [today - 31 days, today + 7 days]. Per occurrence only
     * the best expense by `(|d|, |amount diff|, E.date, E.id)` is shown. The result is ordered by
     * due date, then occurrence id.
     *
     * Reads per call: one occurrence range read, one expense range read, one rule batch read, the
     * installment batch read, and one lifecycle event read per occurrence that has a pair.
     *
     * Fail closed: a blocked read barrier gives [PaymentLinkSuggestions.Unavailable] with
     * [PaymentLinkUnavailableReason.READ_BLOCKED], any other read error gives READ_FAILED, never an
     * empty [PaymentLinkSuggestions.Available]. [kotlinx.coroutines.CancellationException] is rethrown.
     */
    suspend fun findPaymentLinkSuggestions(now: Long): PaymentLinkSuggestions {
        return try {
            readBarrier.checkReadAllowed("RecurringLifecycleCoordinator.findPaymentLinkSuggestions")
            val items = computePaymentLinkSuggestions(now)
            Timber.d("Recurring: payment link suggestions read (suggestionCount=%d)", items.size)
            PaymentLinkSuggestions.Available(items)
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            val reason = if (e is DatabaseAccessBlockedException) {
                PaymentLinkUnavailableReason.READ_BLOCKED
            } else {
                PaymentLinkUnavailableReason.READ_FAILED
            }
            Timber.w("Recurring: payment link suggestions unavailable (code=PAYMENT_LINK_SUGGESTIONS_UNAVAILABLE, reason=%s)", reason.name)
            PaymentLinkSuggestions.Unavailable(reason)
        }
    }

    private suspend fun computePaymentLinkSuggestions(now: Long): List<PaymentLinkSuggestion> {
        val today = TimePeriodUtils.getStartOfDay(now)
        val endOfToday = TimePeriodUtils.getEndOfDay(now)

        // Targets and blockers share one read; targets are the narrower due-date range (INV-9).
        val occurrences = occurrenceDao.getByDateRange(
            TimePeriodUtils.addDays(today, -SUGGESTION_BLOCKER_LOOKBACK_DAYS),
            TimePeriodUtils.addDays(today, SUGGESTION_BLOCKER_LOOKAHEAD_DAYS)
        )
        if (occurrences.isEmpty()) return emptyList()
        val targetStart = TimePeriodUtils.addDays(today, -SUGGESTION_TARGET_LOOKBACK_DAYS)
        val targetEndExclusive = TimePeriodUtils.addDays(today, SUGGESTION_TARGET_LOOKAHEAD_DAYS + 1)

        val expenses = expenseDao.getExpensesBetween(
            TimePeriodUtils.addDays(today, -SUGGESTION_EXPENSE_LOOKBACK_DAYS),
            endOfToday
        )
        if (expenses.isEmpty()) return emptyList()

        val linkedExpenseIds = HashSet<Long>()
        for (occurrence in occurrences) occurrence.linkedExpenseId?.let { linkedExpenseIds.add(it) }
        val typeEligible = expenses.filter {
            it.id !in linkedExpenseIds && RecurringLinkTypeEligibility.mayCreateLink(it.transactionType, it.isNotMine)
        }
        val installmentExcluded = installmentEligibility.excludedExpenseIds(typeEligible)
        val candidates = typeEligible.filter { it.id !in installmentExcluded }
        if (candidates.isEmpty()) return emptyList()

        val rulesById = manualRecurringExpenseDao.getAll().associateBy { it.id }
        val windowsByRule = HashMap<Long, LinkWindows>()
        fun windowsFor(occurrence: RecurringOccurrence): LinkWindows =
            if (occurrence.sourceType != SOURCE_TYPE_RECURRING_RULE) {
                LinkWindows.SAME_DAY
            } else {
                windowsByRule.getOrPut(occurrence.sourceId) {
                    linkWindowsFor(rulesById[occurrence.sourceId]?.frequency, occurrence)
                }
            }
        fun isSuggestibleTarget(occurrence: RecurringOccurrence): Boolean =
            RecurringNextDatePolicy.isOpen(occurrence) &&
                occurrence.sourceType == SOURCE_TYPE_RECURRING_RULE &&
                rulesById[occurrence.sourceId]?.isActive == true

        // D-3: CANCELLED and IGNORED rows never own a payment (same rule as the auto tier).
        val ranking = occurrences.filter {
            it.status != RecurringOccurrenceStatus.CANCELLED.dbValue &&
                it.status != RecurringOccurrenceStatus.IGNORED.dbValue
        }
        val occurrenceMerchantKeys = HashMap<Long, String>()
        for (occurrence in ranking) {
            occurrenceMerchantKeys[occurrence.id] = MerchantKeyGenerator.generate(occurrence.merchant.orEmpty())
        }
        val eventsByOccurrence = HashMap<Long, List<RecurringLifecycleEvent>>()
        suspend fun isDeclined(occurrence: RecurringOccurrence, expenseId: Long): Boolean {
            val events = eventsByOccurrence.getOrPut(occurrence.id) {
                lifecycleEventDao.getEventsForOccurrence(occurrence.id)
            }
            return RecurringPaymentLinkMatcher.isDeclined(events, expenseId)
        }

        val bestByOccurrence = HashMap<Long, Pair<RecurringOccurrence, OwnedExpense>>()
        for (expense in candidates) {
            val expenseMerchantKey = MerchantKeyGenerator.generate(expense.merchant)
            val matches = ranking.filter {
                RecurringPaymentLinkMatcher.pairMatches(
                    expense, it, expenseMerchantKey, occurrenceMerchantKeys.getValue(it.id), windowsFor(it).suggestion
                )
            }
            if (matches.isEmpty()) continue

            // A declined pair has left M(E): the first non-declined entry in rank order is N(E).
            val nonDeclined = RecurringPaymentLinkMatcher.rankByNearest(expense, matches)
                .filter { !isDeclined(it, expense.id) }
            val owner = nonDeclined.firstOrNull() ?: continue
            if (!isSuggestibleTarget(owner)) continue
            if (owner.dueDate < targetStart || owner.dueDate >= targetEndExclusive) continue

            val alternatives = nonDeclined.drop(1).filter { isSuggestibleTarget(it) }.map { it.id }
            val current = bestByOccurrence[owner.id]
            if (current == null || isBetterOwnedExpense(expense, current.second.expense, owner)) {
                bestByOccurrence[owner.id] = owner to OwnedExpense(expense, alternatives)
            }
        }

        return bestByOccurrence.values
            .sortedWith(compareBy<Pair<RecurringOccurrence, OwnedExpense>>({ it.first.dueDate }, { it.first.id }))
            .map { (occurrence, owned) ->
                PaymentLinkSuggestion(
                    occurrenceId = occurrence.id,
                    ruleId = occurrence.sourceId,
                    occurrenceKey = occurrence.occurrenceKey,
                    dueDate = occurrence.dueDate,
                    expectedAmount = occurrence.expectedAmount,
                    expectedCurrency = occurrence.expectedCurrency,
                    expenseId = owned.expense.id,
                    expenseDate = owned.expense.date,
                    paidAmount = owned.expense.amount,
                    paidCurrency = owned.expense.currency,
                    dayDelta = RecurringPaymentLinkMatcher.dayDelta(owned.expense, occurrence),
                    alternatives = owned.alternatives
                )
            }
    }

    /**
     * INV-8: among the expenses whose nearest owner is [occurrence], the one shown is the minimum by
     * `(|d|, |amount diff|, E.date, E.id)`. True when [candidate] ranks before [current].
     */
    private fun isBetterOwnedExpense(
        candidate: Expense,
        current: Expense,
        occurrence: RecurringOccurrence
    ): Boolean {
        val order = compareBy<Expense>(
            { abs(RecurringPaymentLinkMatcher.dayDelta(it, occurrence)) },
            { abs(it.amount - occurrence.expectedAmount) },
            { it.date },
            { it.id }
        )
        return order.compare(candidate, current) < 0
    }

    /** The expense chosen for one suggested occurrence, with the other open pair-eligible bills. */
    private class OwnedExpense(val expense: Expense, val alternatives: List<Long>)

    /**
     * FC-PAID-UNLINKED INV-12 / INV-13 / INV-16: the user confirmed that [expenseId] pays
     * [occurrenceId]. The link goes through [claimLinkSuppressInWrite] with the provenance source
     * [SOURCE_USER_CONFIRMED_MATCH]; inside its transaction the pair is checked again (the occurrence
     * exists, is open, belongs to an active rule, the pair matches inside S(P) and is not declined,
     * and the expense is link-eligible). A failed check gives [PaymentLinkConfirmResult.NoLongerValid]
     * and nothing is written. A confirm may target any open, pair-eligible occurrence, not only
     * N(E) (the "Different bill" choice).
     *
     * Idempotent: a retry after the commit finds the expense already linked to the occurrence and
     * returns [PaymentLinkConfirmResult.AlreadyLinkedToThis] without a second event (AE-19).
     *
     * [ruleId] is the rule the caller believes owns [occurrenceId]; the ownership check runs inside the
     * transaction (behind the write barrier), so a mismatch is [PaymentLinkConfirmResult.NoLongerValid]
     * and a restore window is [PaymentLinkFailureCodes.WRITE_BLOCKED], never an out-of-transaction read.
     *
     * Cancellation: a caller that is already cancelled stops before anything is written. Once the
     * claim starts, the claim AND the DR-03 rule advance (INV-13) run together in [NonCancellable], so a
     * cancellation landing while the Room transaction finishes can never leave the rule's `nextDate`
     * on a PAID bill. The advance is best effort; its failure never turns the committed link into a
     * failure.
     */
    suspend fun confirmPaymentLink(ruleId: Long, expenseId: Long, occurrenceId: Long): PaymentLinkConfirmResult {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val result = try {
                claimLinkSuppressInWrite(expenseId, SOURCE_USER_CONFIRMED_MATCH) {
                    selectConfirmTarget(ruleId, expenseId, occurrenceId)
                }
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                val code = if (e is DatabaseAccessBlockedException) {
                    PaymentLinkFailureCodes.WRITE_BLOCKED
                } else {
                    PaymentLinkFailureCodes.INTERNAL
                }
                Timber.w("Recurring: payment link confirm failed (code=%s)", code)
                return@withContext PaymentLinkConfirmResult.Failed(code)
            }

            when (result.outcome) {
                ClaimLinkOutcome.CLAIMED -> {
                    Timber.d(
                        "Recurring: expense %d linked to occurrence %d (code=PAYMENT_LINK_CONFIRMED)",
                        expenseId, result.occurrenceId
                    )
                    advanceRuleAfterAutoLink(result.occurrenceId, "PAYMENT_LINK_ADVANCE_FAILED")
                    PaymentLinkConfirmResult.Linked
                }
                ClaimLinkOutcome.ALREADY_LINKED_TO_THIS -> PaymentLinkConfirmResult.AlreadyLinkedToThis
                ClaimLinkOutcome.ALREADY_LINKED_ELSEWHERE -> PaymentLinkConfirmResult.ExpenseLinkedElsewhere
                ClaimLinkOutcome.NO_TARGET,
                ClaimLinkOutcome.NOT_OPEN,
                ClaimLinkOutcome.EXPENSE_NOT_ELIGIBLE -> {
                    Timber.d("Recurring: payment link confirm refused (code=PAYMENT_LINK_NO_LONGER_VALID)")
                    PaymentLinkConfirmResult.NoLongerValid
                }
            }
        }
    }

    /**
     * The in-transaction revalidation of [confirmPaymentLink] (INV-12). Returns the occurrence id to
     * claim, or null when the pair no longer holds (including an occurrence that is not a rule
     * occurrence of [ruleId]). An occurrence already linked to [expenseId] is returned as is so the
     * claim reports the idempotent [ClaimLinkOutcome.ALREADY_LINKED_TO_THIS].
     */
    private suspend fun selectConfirmTarget(ruleId: Long, expenseId: Long, occurrenceId: Long): Long? {
        val occurrence = occurrenceDao.getById(occurrenceId) ?: return null
        if (occurrence.sourceType != SOURCE_TYPE_RECURRING_RULE || occurrence.sourceId != ruleId) return null
        if (occurrence.linkedExpenseId == expenseId) return occurrence.id
        if (!RecurringNextDatePolicy.isOpen(occurrence)) return null
        val rule = manualRecurringExpenseDao.getById(occurrence.sourceId) ?: return null
        if (!rule.isActive) return null
        val expense = expenseDao.getById(expenseId) ?: return null

        val windows = linkWindowsFor(rule.frequency, occurrence)
        val matches = RecurringPaymentLinkMatcher.pairMatches(
            expense,
            occurrence,
            MerchantKeyGenerator.generate(expense.merchant),
            MerchantKeyGenerator.generate(occurrence.merchant.orEmpty()),
            windows.suggestion
        )
        if (!matches) return null
        if (RecurringPaymentLinkMatcher.isDeclined(lifecycleEventDao.getEventsForOccurrence(occurrence.id), expenseId)) {
            return null
        }
        return occurrence.id
    }

    /**
     * FC-PAID-UNLINKED INV-14 / INV-16: "Not this bill". Writes one
     * [RecurringPaymentLinkMatcher.LINK_SUGGESTION_DECLINED] event for the pair, in one write-barrier
     * transaction; the occurrence status is not changed. A pair that already has a decline is a no-op
     * ([PaymentLinkDeclineResult.AlreadyDeclined]); a missing occurrence or expense, an occurrence
     * that is no longer open, or one that is not an occurrence of rule [ruleId] (checked inside the
     * transaction, behind the write barrier), gives [PaymentLinkDeclineResult.NoLongerValid]. The
     * metadata holds ids and a bounded source code only.
     */
    suspend fun declinePaymentLink(ruleId: Long, expenseId: Long, occurrenceId: Long): PaymentLinkDeclineResult {
        val now = timeProvider.now()
        return try {
            writeBarrier.runWrite(
                DatabaseAccessOperation("RecurringLifecycleCoordinator.declinePaymentLink")
            ) {
                database.withTransaction {
                    val occurrence = occurrenceDao.getById(occurrenceId)
                    if (occurrence == null ||
                        occurrence.sourceType != SOURCE_TYPE_RECURRING_RULE ||
                        occurrence.sourceId != ruleId ||
                        !RecurringNextDatePolicy.isOpen(occurrence) ||
                        expenseDao.getById(expenseId) == null
                    ) {
                        return@withTransaction PaymentLinkDeclineResult.NoLongerValid
                    }
                    val events = lifecycleEventDao.getEventsForOccurrence(occurrenceId)
                    if (RecurringPaymentLinkMatcher.isDeclined(events, expenseId)) {
                        return@withTransaction PaymentLinkDeclineResult.AlreadyDeclined
                    }
                    eventWriter.writeCritical(
                        occurrenceId = occurrenceId,
                        eventType = RecurringPaymentLinkMatcher.LINK_SUGGESTION_DECLINED,
                        metadata = JSONObject().apply {
                            put("expenseId", expenseId)
                            put("source", SOURCE_USER_DECLINED_MATCH)
                        }.toString(),
                        occurredAt = now
                    )
                    PaymentLinkDeclineResult.Declined
                }
            }
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            val code = if (e is DatabaseAccessBlockedException) {
                PaymentLinkFailureCodes.WRITE_BLOCKED
            } else {
                PaymentLinkFailureCodes.INTERNAL
            }
            Timber.w("Recurring: payment link decline failed (code=%s)", code)
            PaymentLinkDeclineResult.Failed(code)
        }
    }

    /**
     * R21 S1: read-only. Earliest open (PLANNED, unlinked) occurrence of the rule,
     * optionally restricted to `dueDate <= dueOnOrBefore`.
     */
    suspend fun findEarliestOpenOccurrence(ruleId: Long, dueOnOrBefore: Long?): RecurringOccurrence? =
        occurrenceDao.findEarliestOpenBySource(SOURCE_TYPE_RECURRING_RULE, ruleId, dueOnOrBefore)

    /**
     * R21 S1: collapses stale DUE reminder deliveries so a long-offline device does
     * not fire one notification per missed window / per missed occurrence.
     *
     * Only SCHEDULED / SNOOZED / FAILED_TRANSIENT deliveries of PLANNED occurrences
     * that are currently due are considered; CLAIMED (in-flight) rows are never
     * touched (the DAO guard also enforces this). Two passes, one transaction:
     * 1. per occurrence keep only the latest-scheduled window, cancel the rest with
     *    [REASON_STALE_WINDOW_SUPERSEDED];
     * 2. per rule, among occurrences already due (dueDate <= now) keep only the
     *    newest occurrence's delivery, cancel the rest with [REASON_STALE_WINDOW_EXPIRED].
     * Upcoming (dueDate > now) occurrences are never collapsed.
     *
     * @return number of deliveries cancelled.
     */
    suspend fun collapseStaleDueDeliveries(now: Long): Int =
        writeBarrier.runWrite(
            DatabaseAccessOperation("RecurringLifecycleCoordinator.collapseStaleDueDeliveries")
        ) {
            database.withTransaction {
                val due = reminderDeliveryDao.getPendingDeliveriesForPlannedOccurrences(now)
                if (due.isEmpty()) return@withTransaction 0

                // Pass 1: latest window per occurrence wins.
                val supersededIds = mutableListOf<Long>()
                val keptPerOccurrence = mutableListOf<RecurringReminderDelivery>()
                for ((_, group) in due.groupBy { it.occurrenceId }) {
                    val keep = group.maxWith(
                        compareBy<RecurringReminderDelivery>({ it.scheduledAt }, { it.id })
                    )
                    keptPerOccurrence += keep
                    group.filter { it.id != keep.id }.mapTo(supersededIds) { it.id }
                }

                // Pass 2: per rule, only the newest already-due occurrence keeps a delivery.
                val occurrencesById = keptPerOccurrence.map { it.occurrenceId }.distinct()
                    .chunked(DELIVERY_ID_CHUNK)
                    .flatMap { occurrenceDao.getByIds(it) }
                    .associateBy { it.id }
                val expiredIds = mutableListOf<Long>()
                val dueByRule = HashMap<Pair<String, Long>, MutableList<Pair<RecurringOccurrence, RecurringReminderDelivery>>>()
                for (delivery in keptPerOccurrence) {
                    val occ = occurrencesById[delivery.occurrenceId] ?: continue
                    if (occ.dueDate > now) continue
                    dueByRule.getOrPut(occ.sourceType to occ.sourceId) { mutableListOf() } += (occ to delivery)
                }
                for ((_, entries) in dueByRule) {
                    if (entries.size < 2) continue
                    val newest = entries.maxWith(
                        compareBy<Pair<RecurringOccurrence, RecurringReminderDelivery>>(
                            { it.first.dueDate }, { it.first.id }
                        )
                    )
                    entries.filter { it.second.id != newest.second.id }.mapTo(expiredIds) { it.second.id }
                }

                var supersededCancelled = 0
                for (chunk in supersededIds.chunked(DELIVERY_ID_CHUNK)) {
                    supersededCancelled += reminderDeliveryDao.cancelOpenDueDeliveriesByIds(
                        chunk, REASON_STALE_WINDOW_SUPERSEDED, now
                    )
                }
                var expiredCancelled = 0
                for (chunk in expiredIds.chunked(DELIVERY_ID_CHUNK)) {
                    expiredCancelled += reminderDeliveryDao.cancelOpenDueDeliveriesByIds(
                        chunk, REASON_STALE_WINDOW_EXPIRED, now
                    )
                }
                val cancelled = supersededCancelled + expiredCancelled
                if (cancelled > 0) {
                    // Aggregate provenance event: bounded counts only (no ids, no text).
                    eventWriter.writeCritical(
                        occurrenceId = null,
                        eventType = "STALE_DUE_DELIVERIES_COLLAPSED",
                        metadata = JSONObject().apply {
                            put("supersededCount", supersededCancelled)
                            put("expiredCount", expiredCancelled)
                        }.toString(),
                        occurredAt = now
                    )
                }
                cancelled
            }
        }

    /**
     * Single legal claim/fulfil/suppress write path for expense -> occurrence links.
     * Owns the write scope + transaction; [selectTarget] is invoked INSIDE the
     * transaction so the target read and the conditional claim are atomic.
     */
    private suspend fun claimLinkSuppressInWrite(
        expenseId: Long,
        rawSource: String,
        selectTarget: suspend () -> Long?
    ): ClaimLinkResult {
        val now = timeProvider.now()
        val source = sanitizeProvenanceSource(rawSource)
        // GR-14p: canonical direct scope — the mutations' proof is local
        // to the legal writer, independent of caller context.
        return writeBarrier.runWrite(
            DatabaseAccessOperation("RecurringLifecycleCoordinator.claimLinkSuppressInWrite")
        ) {
            database.withTransaction {
                val expense = expenseDao.getById(expenseId)
                if (expense == null || !isExpenseEligibleForRecurring(expense, existingLink = false)) {
                    return@withTransaction ClaimLinkResult(ClaimLinkOutcome.EXPENSE_NOT_ELIGIBLE)
                }

                val targetId = selectTarget()

                // An expense is linked to at most one occurrence: if one already
                // references it this call is a no-op (never a second PAID row).
                val alreadyLinked = occurrenceDao.getByLinkedExpenseId(expenseId)
                if (alreadyLinked != null) {
                    return@withTransaction ClaimLinkResult(
                        if (targetId != null && alreadyLinked.id == targetId) {
                            ClaimLinkOutcome.ALREADY_LINKED_TO_THIS
                        } else {
                            ClaimLinkOutcome.ALREADY_LINKED_ELSEWHERE
                        }
                    )
                }

                if (targetId == null) return@withTransaction ClaimLinkResult(ClaimLinkOutcome.NO_TARGET)
                val match = occurrenceDao.getById(targetId)
                    ?: return@withTransaction ClaimLinkResult(ClaimLinkOutcome.NOT_OPEN)

                // P4-CURRENT-001: Use conditional DAO method to atomically claim the occurrence.
                // If another thread already claimed it, claimForExpense returns 0.
                val rows = occurrenceDao.claimForExpense(
                    occurrenceId = match.id,
                    expenseId = expenseId,
                    amount = expense.amount,
                    currency = expense.currency,
                    paidAt = now
                )
                if (rows == 0) {
                    // Not claimable — abort without side effects, classify why.
                    val current = occurrenceDao.getById(match.id)
                    val outcome = when {
                        current == null -> ClaimLinkOutcome.NOT_OPEN
                        current.linkedExpenseId == expenseId -> ClaimLinkOutcome.ALREADY_LINKED_TO_THIS
                        // Held by ANOTHER expense (or terminal/other status): the occurrence is not open.
                        else -> ClaimLinkOutcome.NOT_OPEN
                    }
                    return@withTransaction ClaimLinkResult(outcome)
                }

                // P4-NEW-009: JSONObject.put() auto-escapes user-provided strings (currency)
                eventWriter.writeCritical(
                    occurrenceId = match.id,
                    eventType = "OCCURRENCE_PAID",
                    oldStatus = "PLANNED",
                    newStatus = "PAID",
                    metadata = JSONObject().apply {
                        put("expenseId", expenseId)
                        put("amount", expense.amount)
                        put("currency", expense.currency)
                    }.toString()
                )

                val planned = plannedExpenseDao.getBySourceOccurrenceKey(match.occurrenceKey)
                if (planned != null) {
                    plannedExpenseDao.linkToActualExpense(planned.id, expenseId, now)
                    // Write PLANNED_FULFILLED event for provenance tracking
                    eventWriter.writeCritical(
                        occurrenceId = match.id,
                        eventType = "PLANNED_FULFILLED",
                        oldStatus = "PLANNED",
                        newStatus = "FULFILLED",
                        metadata = JSONObject().apply {
                            put("plannedExpenseId", planned.id)
                            put("occurrenceKey", match.occurrenceKey)
                            put("expenseId", expenseId)
                            put("source", source)
                        }.toString()
                    )
                }

                val suppressed = reminderDeliveryDao.suppressOpenDeliveriesForOccurrence(match.id, now, "expense_linked:$expenseId")
                // Write REMINDER_SUPPRESSED_PAID event when reminders are cancelled due to payment
                eventWriter.writeCritical(
                    occurrenceId = match.id,
                    eventType = if (suppressed > 0) "REMINDER_SUPPRESSED_PAID" else "REMINDER_SUPPRESSION_NOOP",
                    newStatus = if (suppressed > 0) "CANCELLED" else null,
                    metadata = JSONObject().apply {
                        put("expenseId", expenseId)
                        put("suppressedCount", suppressed)
                        put("source", source)
                    }.toString()
                )

                ClaimLinkResult(ClaimLinkOutcome.CLAIMED, match.id, match.occurrenceKey)
            }
        }
    }

    private enum class ClaimLinkOutcome {
        CLAIMED,
        NO_TARGET,
        EXPENSE_NOT_ELIGIBLE,
        ALREADY_LINKED_TO_THIS,
        ALREADY_LINKED_ELSEWHERE,
        NOT_OPEN
    }

    private class ClaimLinkResult(
        val outcome: ClaimLinkOutcome,
        val occurrenceId: Long = 0L,
        val occurrenceKey: String = ""
    )

    /**
     * Detailed version of [linkExpenseToOccurrence] returning structured result with real ids.
     */
    suspend fun linkExpenseToOccurrenceDetailed(
        expenseId: Long,
        reason: String = "expense_created_or_reconciled"
    ): RecurringExpenseReconcileResult {
        val result = linkExpenseToOccurrence(expenseId)
        if (!result) {
            val expense = expenseDao.getById(expenseId)
            if (expense == null) return RecurringExpenseReconcileResult.Skipped(expenseId, "expense_missing")
            if (!isExpenseEligibleForRecurring(expense, existingLink = false))
                return RecurringExpenseReconcileResult.Skipped(expenseId, "expense_not_eligible")
            // Distinct bounded reason when the expense already holds an occurrence (it is
            // never linked twice) so it is not misreported as "no match".
            if (occurrenceDao.getByLinkedExpenseId(expenseId) != null) {
                return RecurringExpenseReconcileResult.NoMatch(expenseId, "expense_already_linked")
            }
            return RecurringExpenseReconcileResult.NoMatch(expenseId, "no_matching_planned_occurrence")
        }
        // Find the linked occurrence to get real ids
        val linked = occurrenceDao.getByLinkedExpenseId(expenseId)
            ?: run {
                Timber.w("Impossible state: linkExpenseToOccurrence returned true but no linked occurrence found for expenseId=%d", expenseId)
                return RecurringExpenseReconcileResult.Error(expenseId, "linked_occurrence_missing_after_successful_claim")
            }
        val occurrenceId = linked.id
        val planned = linked.occurrenceKey.let { plannedExpenseDao.getBySourceOccurrenceKey(it) }
        return RecurringExpenseReconcileResult.Linked(expenseId, occurrenceId, planned?.id)
    }

    /**
     * Reconciles an expense's recurring link after an update.
     *
     * Instead of blindly unlinking and re-linking (which can reopen fulfilled
     * planned rows and lose provenance), this method:
     * 1. If expense was linked AND still matches → update payment snapshot
     * 2. If expense was linked but no longer matches → unlink old, try to link new
     * 3. If expense was not linked → try to link
     *
     * @return Structured result indicating what happened.
     */
    suspend fun reconcileExpenseLinkAfterUpdate(
        expenseId: Long,
        reason: String = "expense_updated"
    ): RecurringExpenseReconcileResult {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.reconcileExpenseLinkAfterUpdate")

        val expense = expenseDao.getById(expenseId)
            ?: return RecurringExpenseReconcileResult.Skipped(expenseId, "expense_missing")

        val linked = occurrenceDao.getByLinkedExpenseId(expenseId)

        // If still matches the same occurrence, update payment snapshot
        if (linked != null) {
            // Keep-check of an EXISTING link (owner item 37(b), R2): an UNKNOWN expense keeps its link.
            if (isExpenseEligibleForRecurring(expense, existingLink = true) && expenseMatchesOccurrence(expense, linked)) {
                val now = timeProvider.now()

                // MIT-043: Wrap snapshot update + event in a single transaction
                // so paid amount update and event never diverge.

                // GR-14p: canonical direct scope — the mutations' proof is local
                // to the legal writer, independent of caller context.
                writeBarrier.runWrite(
                    DatabaseAccessOperation("RecurringLifecycleCoordinator.reconcileExpenseLinkAfterUpdate")
                ) {
                    database.withTransaction {
                    val rows = occurrenceDao.updateLinkedPaymentSnapshot(
                        occurrenceId = linked.id,
                        expenseId = expenseId,
                        amount = expense.amount,
                        currency = expense.currency,
                        paidAt = expense.date,
                        updatedAt = now
                    )
                    if (rows > 0) {
                        // P4-NEW-009: JSONObject.put() auto-escapes user-provided strings (currency)
                        lifecycleEventDao.insert(
                            RecurringLifecycleEvent(
                                occurrenceId = linked.id,
                                eventType = "OCCURRENCE_PAID_SNAPSHOT_UPDATED",
                                occurredAt = now,
                                oldStatus = "PAID",
                                newStatus = "PAID",
                                metadata = JSONObject().apply {
                                    put("expenseId", expenseId)
                                    put("amount", expense.amount)
                                    put("currency", expense.currency)
                                }.toString()
                            )
                        )
                    }
                    }
                }
                return RecurringExpenseReconcileResult.UpdatedLinkedSnapshot(expenseId, linked.id)
            }
            // No longer matches — unlink from the old occurrence
            unlinkExpenseFromOccurrence(expenseId, "no_longer_matches_after_update")
            val oldOccurrenceId = linked.id

            // Try to re-link if still eligible. The old link was just removed, so this is a NEW link.
            if (isExpenseEligibleForRecurring(expense, existingLink = false)) {
                val relinkResult = linkExpenseToOccurrenceDetailed(expenseId, "expense_update_relink")
                if (relinkResult is RecurringExpenseReconcileResult.Linked) {
                    return RecurringExpenseReconcileResult.Relinked(expenseId, oldOccurrenceId, relinkResult.occurrenceId)
                }
            }
            return RecurringExpenseReconcileResult.Unlinked(expenseId, oldOccurrenceId, "no_new_match_after_update")
        }

        // Not linked — try to link if eligible (a NEW link)
        if (!isExpenseEligibleForRecurring(expense, existingLink = false)) {
            return RecurringExpenseReconcileResult.Skipped(expenseId, "expense_not_eligible")
        }

        return linkExpenseToOccurrenceDetailed(expenseId, "expense_update_unlinked_try_match")
    }

    /**
     * Reconciles all expenses linked to occurrences after a bulk update.
     * Returns structured counts so the planner can surface accurate outcomes.
     */
    suspend fun reconcileAllLinkedExpensesAfterBulkUpdate(source: String): BulkRecurringReconcileResult {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.reconcileAllLinkedExpensesAfterBulkUpdate")
        val occurrences = occurrenceDao.getByStatus("PAID")
        val expenseIds = occurrences.mapNotNull { it.linkedExpenseId }.distinct()
        var linked = 0; var unlinked = 0; var relinked = 0
        var updatedSnapshots = 0; var noMatch = 0; var skipped = 0; var failed = 0
        for (expenseId in expenseIds) {
            try {
                when (val result = reconcileExpenseLinkAfterUpdate(expenseId, "bulk_update:$source")) {
                    is RecurringExpenseReconcileResult.Linked -> linked++
                    is RecurringExpenseReconcileResult.Unlinked -> unlinked++
                    is RecurringExpenseReconcileResult.Relinked -> relinked++
                    is RecurringExpenseReconcileResult.UpdatedLinkedSnapshot -> updatedSnapshots++
                    is RecurringExpenseReconcileResult.NoMatch -> noMatch++
                    is RecurringExpenseReconcileResult.Skipped -> skipped++
                    is RecurringExpenseReconcileResult.Error -> { failed++; Timber.w("Reconcile error: %s", result.reason) }
                }
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                failed++
            }
        }
        Timber.d("Bulk recurring reconcile: l=%d u=%d r=%d s=%d n=%d k=%d f=%d",
            linked, unlinked, relinked, updatedSnapshots, noMatch, skipped, failed)
        return BulkRecurringReconcileResult(expenseIds.size, linked, unlinked, relinked, updatedSnapshots, noMatch, skipped, failed)
    }

    /** Structured result for bulk recurring reconciliation. */
    data class BulkRecurringReconcileResult(
        val total: Int, val linked: Int, val unlinked: Int, val relinked: Int,
        val updatedSnapshots: Int, val noMatch: Int, val skipped: Int, val failed: Int
    ) {
        val meaningfulMutations: Int get() = linked + unlinked + relinked + updatedSnapshots
        val hasFailures: Boolean get() = failed > 0
    }

    /**
     * The eligibility gate for the coordinator's link paths: the auto-link, the manual link and the
     * post-update reconcile all go through it. The occurrence generation path (create / activate /
     * top-up / update and [projectOccurrences] through [OccurrenceConflictResolver]) consults the
     * same installment decision in batch form (S6c-2b); both forms live in
     * [RecurringInstallmentEligibility], so the decision exists once.
     *
     * Two layers, in order:
     * 1. Recurring's own type/ownership rules, owned by [RecurringLinkTypeEligibility] (owner item
     *    37(b)): a NEW link ([existingLink] false, [RecurringLinkTypeEligibility.mayCreateLink]) refuses
     *    a not-mine expense, a TRANSFER, a DEPOSIT and an UNKNOWN expense. Re-validating an EXISTING
     *    link ([existingLink] true, [RecurringLinkTypeEligibility.mayKeepLink], the reconcile
     *    "still matches" check that the bulk-update reconcile also runs) refuses only not-mine,
     *    TRANSFER and DEPOSIT, so an existing link on an UNKNOWN expense is kept and a bulk edit never
     *    reverts PAID to PLANNED (decision 46(a), R2).
     * 2. INV-5 schedule exclusivity, delegated to [RecurringInstallmentEligibility]: an expense that
     *    pays an installment entry, or is a plan purchase record ("plan row OR flag", including an
     *    orphaned INSTALLMENT_PURCHASE / INSTALLMENT_REVERSED flag), is refused.
     *
     * A refusal is reported as "expense not eligible", the existing ineligible outcome. An existing
     * link of the expense to a recurring occurrence is not a refusal reason here: uniqueness between
     * occurrences stays with [claimLinkSuppressInWrite], so a link to the same occurrence still passes.
     *
     * When called inside [claimLinkSuppressInWrite] the plan reads join that Room transaction.
     */
    private suspend fun isExpenseEligibleForRecurring(
        expense: com.yourname.expensetracker.data.database.entity.Expense,
        existingLink: Boolean
    ): Boolean {
        val layerOneOk = if (existingLink) {
            RecurringLinkTypeEligibility.mayKeepLink(expense.transactionType, expense.isNotMine)
        } else {
            RecurringLinkTypeEligibility.mayCreateLink(expense.transactionType, expense.isNotMine)
        }
        if (!layerOneOk) return false

        return installmentEligibility.mayLinkToRecurringOccurrence(expense)
    }

    /**
     * The auto window A(P) and the suggestion window S(P) for [occ]. A rule
     * occurrence ([SOURCE_TYPE_RECURRING_RULE]) uses the windows of its rule's period; every other
     * source stays same-day on both and never reads the rule. The result is cached per rule in
     * [windows] so the rule is read once.
     *
     * The rule's period comes from its [RecurrenceFrequency] (the rule row, read in the caller's
     * transaction; the occurrence's own frequency column is the fallback), through
     * [RecurringPaymentLinkMatcher.resolvePeriodDays]. A missing rule row does not block (the
     * occurrence still links, as before); an inactive rule does not block either.
     */
    private suspend fun resolveLinkWindows(
        occ: RecurringOccurrence,
        windows: MutableMap<Long, LinkWindows>
    ): LinkWindows {
        if (occ.sourceType != SOURCE_TYPE_RECURRING_RULE) return LinkWindows.SAME_DAY
        windows[occ.sourceId]?.let { return it }
        val rule = manualRecurringExpenseDao.getById(occ.sourceId)
        val resolved = linkWindowsFor(rule?.frequency, occ)
        windows[occ.sourceId] = resolved
        return resolved
    }

    /**
     * A(P) and S(P) from the rule's [ruleFrequency] (the occurrence's own frequency column is the
     * fallback when the rule row is missing). Pure: shared by the per-rule read of
     * [resolveLinkWindows] and the batch rule read of [findPaymentLinkSuggestions].
     */
    private fun linkWindowsFor(ruleFrequency: RecurrenceFrequency?, occ: RecurringOccurrence): LinkWindows {
        val frequency = ruleFrequency
            ?: try {
                RecurrenceFrequency.valueOf(occ.frequency)
            } catch (e: IllegalArgumentException) {
                null
            }
        val periodDays = RecurringPaymentLinkMatcher.resolvePeriodDays(frequency)
        return LinkWindows(
            auto = autoLinkWindow(periodDays),
            suggestion = RecurringPaymentLinkMatcher.suggestionWindow(periodDays)
        )
    }

    /** A(P) and S(P) for one occurrence (see [resolveLinkWindows]). */
    private class LinkWindows(
        val auto: AutoLinkWindow,
        val suggestion: AutoLinkWindow
    ) {
        companion object {
            val SAME_DAY = LinkWindows(AutoLinkWindow.SAME_DAY, AutoLinkWindow.SAME_DAY)
        }
    }

    /**
     * INV-15 (D-4): the keep check of an existing link. The merchant keys, amount (±10%), currency
     * and a day delta inside S(P), not A(P), must all still hold, so a link paid early survives an
     * edit. An existing link is never re-ranked or displaced.
     */
    private suspend fun expenseMatchesOccurrence(
        expense: com.yourname.expensetracker.data.database.entity.Expense,
        occurrence: RecurringOccurrence
    ): Boolean {
        val windows = resolveLinkWindows(occurrence, HashMap())
        val expenseMerchantKey = MerchantKeyGenerator.generate(expense.merchant)
        val occMerchantKey = MerchantKeyGenerator.generate(occurrence.merchant.orEmpty())
        return RecurringPaymentLinkMatcher.pairMatches(expense, occurrence, expenseMerchantKey, occMerchantKey, windows.suggestion)
    }

    /**
     * Regenerates future reminder deliveries for an occurrence that has been
     * reopened to PLANNED (e.g., after unlinking a deleted expense).
     *
     * - Reopens CANCELLED/FAILED_TRANSIENT deliveries for each window
     * - Inserts new deliveries if no row exists for the window
     * - Skips past-due windows by default (no notification for historical dates)
     * - Does NOT reopen SENT rows (user already saw the notification)
     */
    private suspend fun regenerateReminderDeliveriesForOccurrence(
        occurrence: RecurringOccurrence,
        now: Long,
        windows: List<String> = DEFAULT_REMINDER_WINDOWS
    ): Int {
        var restored = 0
        for (window in windows) {
            val scheduledAt = computeReminderScheduledAt(occurrence.dueDate, window)
            // Skip past-due reminders
            if (scheduledAt < now) {
                try {
                    // GR-14p: canonical direct scope — the mutations' proof is
                    // local to the legal writer, independent of caller context.
                    writeBarrier.runWrite(
                        DatabaseAccessOperation("RecurringLifecycleCoordinator.regenerateReminderDeliveriesForOccurrence")
                    ) {
                        database.withTransaction {
                        lifecycleEventDao.insert(
                            RecurringLifecycleEvent(
                                occurrenceId = occurrence.id,
                                eventType = "REMINDER_REGENERATION_SKIPPED",
                                occurredAt = now,
                                oldStatus = null,
                                newStatus = null,
                                metadata = """{"window":"$window","scheduledAt":$scheduledAt,"reason":"past_due"}"""
                            )
                        )
                        }
                    }
                } catch (e: Exception) {
                    CancellationSafe.rethrowIfCancellation(e)
                    // Non-cancellation event write failure is isolated; continue to next window
                }
                continue
            }

            var reopenedInWindow = 0
            try {
                // GR-14p: canonical direct scope — the mutations' proof is
                // local to the legal writer, independent of caller context.
                reopenedInWindow = writeBarrier.runWrite(
                    DatabaseAccessOperation("RecurringLifecycleCoordinator.regenerateReminderDeliveriesForOccurrence")
                ) {
                    database.withTransaction {
                    val count = reminderDeliveryDao.reopenDeliveryForOccurrenceWindow(
                        occurrenceId = occurrence.id,
                        window = window,
                        scheduledAt = scheduledAt,
                        now = now
                    )
                    if (count > 0) {
                        lifecycleEventDao.insert(
                            RecurringLifecycleEvent(
                                occurrenceId = occurrence.id,
                                eventType = "REMINDER_REOPENED_AFTER_UNLINK",
                                occurredAt = now,
                                oldStatus = "CANCELLED",
                                newStatus = "SCHEDULED",
                                metadata = """{"window":"$window","scheduledAt":$scheduledAt}"""
                            )
                        )
                    }
                    count
                    }
                }
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                Timber.w(e, "Skipped reminder regeneration for window $window (occurrence ${occurrence.id})")
            }
            if (reopenedInWindow > 0) {
                restored += reopenedInWindow
                continue
            }

            val existing = reminderDeliveryDao.getByOccurrenceAndWindow(occurrence.id, window)
            if (existing == null) {
                var inserted = 0L
                try {
                    // GR-14p: canonical direct scope — the mutations' proof is
                    // local to the legal writer, independent of caller context.
                    inserted = writeBarrier.runWrite(
                        DatabaseAccessOperation("RecurringLifecycleCoordinator.regenerateReminderDeliveriesForOccurrence")
                    ) {
                        database.withTransaction {
                        val deliveryId = reminderDeliveryDao.insert(
                            RecurringReminderDelivery(
                                occurrenceId = occurrence.id,
                                reminderWindow = window,
                                scheduledAt = scheduledAt,
                                status = "SCHEDULED",
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                        if (deliveryId > 0) {
                            lifecycleEventDao.insert(
                                RecurringLifecycleEvent(
                                    occurrenceId = occurrence.id,
                                    eventType = "REMINDER_SCHEDULED_AFTER_UNLINK",
                                    occurredAt = now,
                                    oldStatus = null,
                                    newStatus = "SCHEDULED",
                                    metadata = """{"window":"$window","scheduledAt":$scheduledAt}"""
                                )
                            )
                            deliveryId
                        } else {
                            0L
                        }
                        }
                    }
                } catch (e: Exception) {
                    CancellationSafe.rethrowIfCancellation(e)
                    Timber.w(e, "Skipped reminder regeneration for window $window (occurrence ${occurrence.id})")
                }
                if (inserted > 0) restored++
            }
        }
        return restored
    }

    /**
     * Computes the scheduled-at timestamp for a reminder delivery based on the
     * occurrence due date and the reminder window name.
     */
    private fun computeReminderScheduledAt(dueDate: Long, window: String): Long {
        return when {
            window == "DUE_DAY" -> dueDate
            window == "OVERDUE" -> TimePeriodUtils.addDays(dueDate, 1)
            window.endsWith("_DAYS_BEFORE") -> {
                val days = window.removeSuffix("_DAYS_BEFORE").toIntOrNull()
                if (days != null && days > 0) TimePeriodUtils.addDays(dueDate, -days) else dueDate
            }
            else -> dueDate
        }
    }

    /**
     * Unlinks an expense from its linked occurrence, resetting it back to PLANNED.
     *
     * Called when an expense is deleted so that the recurring occurrence is no
     * longer considered PAID. Looks back up to 1 year and forward 1 week for
     * an occurrence linked to the given expense ID.
     *
     * @param expenseId The ID of the expense being deleted.
     * @param reason Why the expense was unlinked (e.g. "expense_deleted", "manual_unlink").
     *               Defaults to "expense_deleted".
     */
    suspend fun unlinkExpenseFromOccurrence(expenseId: Long, reason: String = "expense_deleted") {
        unlinkExpenseFromOccurrenceDetailed(expenseId, reason)
    }

    /**
     * Detailed version of [unlinkExpenseFromOccurrence] returning structured result.
     */
    suspend fun unlinkExpenseFromOccurrenceDetailed(
        expenseId: Long,
        reason: String = "expense_deleted"
    ): RecurringExpenseReconcileResult {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.unlinkExpenseFromOccurrence")

        val now = timeProvider.now()

        // Direct lookup by linkedExpenseId — handles any date range (historical, backdated, restored)
        val linked = occurrenceDao.getByLinkedExpenseId(expenseId)
            ?: return RecurringExpenseReconcileResult.Skipped(expenseId, "no_linked_occurrence")

        // GR-14p: canonical direct scope — the mutations' proof is local
        // to the legal writer, independent of caller context.
        writeBarrier.runWrite(
            DatabaseAccessOperation("RecurringLifecycleCoordinator.unlinkExpenseFromOccurrenceDetailed")
        ) {
            database.withTransaction {
            // Reset to PLANNED — the recurring bill is not yet paid
            occurrenceDao.update(
                linked.copy(
                    status = "PLANNED",
                    linkedExpenseId = null,
                    paidAmount = null,
                    paidCurrency = null,
                    paidAt = null,
                    updatedAt = now
                )
            )

            // P4-CURRENT-002: Use atomic unlinkActualExpense to avoid corrupting
            // planned expense with linkedActualExpenseId=0.
            val planned = plannedExpenseDao.getBySourceOccurrenceKey(linked.occurrenceKey)
            if (planned != null) {
                plannedExpenseDao.unlinkActualExpense(planned.id, now)
            }

            // Regenerate reminder deliveries for the unlinked occurrence.
            // The occurrence is now PLANNED again, so future reminders should be restored.
            regenerateReminderDeliveriesForOccurrence(
                occurrence = linked.copy(
                    status = "PLANNED",
                    linkedExpenseId = null,
                    paidAmount = null,
                    paidCurrency = null,
                    paidAt = null,
                    updatedAt = now
                ),
                now = now
            )

            // Write lifecycle event
            // P4-NEW-009: JSONObject.put() auto-escapes user-provided reason string
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = linked.id,
                    eventType = "OCCURRENCE_UNLINKED",
                    occurredAt = now,
                    oldStatus = "PAID",
                    newStatus = "PLANNED",
                    metadata = JSONObject().apply {
                        put("expenseId", expenseId)
                        put("reason", reason)
                    }.toString()
                )
            )
            }
        }

        return RecurringExpenseReconcileResult.Unlinked(expenseId, linked.id, reason)
    }

    /**
     * Gets all occurrences for a given source/rule.
     *
     * @param ruleId The ID of the recurring rule.
     * @return The list of occurrences, ordered by due date.
     */
    suspend fun getOccurrences(ruleId: Long): List<RecurringOccurrence> {
        return occurrenceDao.getBySource(SOURCE_TYPE_RECURRING_RULE, ruleId)
    }

    suspend fun getOccurrenceById(occurrenceId: Long): RecurringOccurrence? {
        return occurrenceDao.getById(occurrenceId)
    }

    /**
     * Marks an occurrence as SKIPPED, MISSED, or CANCELLED using the typed status API.
     *
     * @param occurrenceId The ID of the occurrence to update.
     * @param newStatus The typed new status.
     * @param reason The transition reason for policy validation.
     */
    suspend fun updateOccurrenceStatus(
        occurrenceId: Long,
        newStatus: RecurringOccurrenceStatus,
        reason: RecurringOccurrenceTransitionReason
    ) {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.updateOccurrenceStatus")

        val now = timeProvider.now()

        // MIT-043: Wrap read + status update + event in a single transaction
        // so the occurrence state never diverges from its lifecycle event.
        // R21 S1: runWrite scope counts this write as in-flight for backup drains.
        writeBarrier.runWrite(
            DatabaseAccessOperation("RecurringLifecycleCoordinator.updateOccurrenceStatus")
        ) {
            database.withTransaction {
                val occurrence = occurrenceDao.getById(occurrenceId) ?: return@withTransaction

                val oldStatus = RecurringOccurrenceStatus.fromDb(occurrence.status)
                RecurringOccurrenceTransitionPolicy.requireAllowed(oldStatus, newStatus, reason)

                occurrenceDao.updateStatus(listOf(occurrenceId), newStatus.dbValue, now)

                if (newStatus == RecurringOccurrenceStatus.SKIPPED) {
                    // R21 review: a skipped ("already recorded") occurrence must leave no open
                    // planned projection and no open reminders, atomically with the status change.
                    val planned = plannedExpenseDao.getBySourceOccurrenceKey(occurrence.occurrenceKey)
                    if (planned != null && planned.status == "PLANNED") {
                        plannedExpenseDao.updateStatus(planned.id, "CANCELLED", now)
                    }
                    reminderDeliveryDao.suppressOpenDeliveriesForOccurrence(
                        occurrenceId, now, "occurrence_skipped"
                    )
                }

                // Write lifecycle event
                val eventType = when (newStatus) {
                    RecurringOccurrenceStatus.SKIPPED -> "OCCURRENCE_SKIPPED"
                    RecurringOccurrenceStatus.CANCELLED -> "OCCURRENCE_CANCELLED"
                    RecurringOccurrenceStatus.MISSED -> "OCCURRENCE_MISSED"
                    else -> null
                }
                if (eventType != null) {
                    lifecycleEventDao.insert(
                        RecurringLifecycleEvent(
                            occurrenceId = occurrenceId,
                            eventType = eventType,
                            occurredAt = now,
                            oldStatus = oldStatus.dbValue,
                            newStatus = newStatus.dbValue,
                            metadata = """{"reason":"${reason.name}"}"""
                        )
                    )
                }
            }
        }
    }

    /** Convenience: skip an occurrence. */
    suspend fun skipOccurrence(occurrenceId: Long) {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.skipOccurrence")
        updateOccurrenceStatus(occurrenceId, RecurringOccurrenceStatus.SKIPPED, RecurringOccurrenceTransitionReason.USER_SKIPPED)
    }

    /**
     * P-04-2: skip the occurrence identified by [occurrenceKey] (the planned row's
     * `sourceOccurrenceKey`). Resolves the id with a read and delegates to the existing
     * atomic [updateOccurrenceStatus] path, which cancels the open planned row and
     * suppresses open reminders in one transaction.
     *
     * Only PLANNED (and idempotently SKIPPED) occurrences are skippable under the transition
     * policy. Any other status (PAID, CANCELLED, MISSED, IGNORED, ...) is left untouched.
     *
     * @return `true` when an occurrence with the key exists in PLANNED or SKIPPED status and the
     *   skip was applied; `false` (nothing written) when no occurrence has this key or the
     *   occurrence is in any other status, so the caller may delete the planned row directly.
     */
    suspend fun skipOccurrenceByKey(occurrenceKey: String): Boolean {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.skipOccurrenceByKey")
        val occurrence = occurrenceDao.getByKey(occurrenceKey) ?: return false
        if (occurrence.status != RecurringOccurrenceStatus.PLANNED.dbValue &&
            occurrence.status != RecurringOccurrenceStatus.SKIPPED.dbValue
        ) {
            return false
        }
        updateOccurrenceStatus(
            occurrence.id,
            RecurringOccurrenceStatus.SKIPPED,
            RecurringOccurrenceTransitionReason.USER_SKIPPED
        )
        return true
    }

    /** Convenience: cancel an occurrence. */
    suspend fun cancelOccurrence(occurrenceId: Long) {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.cancelOccurrence")
        updateOccurrenceStatus(occurrenceId, RecurringOccurrenceStatus.CANCELLED, RecurringOccurrenceTransitionReason.USER_CANCELLED)
    }

    /** Convenience: mark an occurrence missed. */
    suspend fun markOccurrenceMissed(occurrenceId: Long) {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.markOccurrenceMissed")
        updateOccurrenceStatus(occurrenceId, RecurringOccurrenceStatus.MISSED, RecurringOccurrenceTransitionReason.SYSTEM_MARKED_MISSED)
    }

    /**
     * Returns all reminder deliveries whose scheduled time has passed and
     * whose status is still SCHEDULED (i.e. they are due to be dispatched).
     *
     * This is intended to be called by a [ReminderDispatchWorker] (WorkManager)
     * that runs periodically to check for and dispatch due reminders.
     *
     * This is a PURE READ — it does NOT recover stale deliveries. Callers that
     * need recovery should use [recoverAndGetDueReminders] instead, or call
     * [recoverStaleClaimedDeliveries] explicitly before this method.
     *
     * @return The list of pending [RecurringReminderDelivery] items, ordered by scheduledAt.
     */
    suspend fun getDueReminders(): List<RecurringReminderDelivery> {
        return reminderDeliveryDao.getPendingDeliveriesForPlannedOccurrences(timeProvider.now())
    }

    /**
     * MIT-043: Explicit combined method — recovers stale CLAIMED deliveries then
     * returns all pending deliveries. This method makes the write side-effect
     * visible in its name and is the preferred entry point for workers that
     * need recovery + dispatch in a single call.
     *
     * @return The list of pending [RecurringReminderDelivery] items, ordered by scheduledAt.
     */
    suspend fun recoverAndGetDueReminders(): List<RecurringReminderDelivery> {
        recoverStaleClaimedDeliveries()
        return getDueReminders()
    }

    /**
     * P4-CURRENT-005: Resets CLAIMED deliveries older than [staleThresholdMs] back to SCHEDULED.
     * Prevents deliveries from being stuck forever if a worker crashes after claiming.
     *
     * Uses claimedAt (not scheduledAt) to determine staleness, so freshly claimed overdue
     * reminders are not immediately recovered by another worker.
     *
     * Made public in PR 7 so callers can explicitly recover stale deliveries before
     * querying due reminders.
     */
    suspend fun recoverStaleClaimedDeliveries(
        correlationId: String = java.util.UUID.randomUUID().toString(),
        actor: String = "system"
    ): Int {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.recoverStaleClaimedDeliveries")
        return transactionRunner.runInTransaction(
            correlationId = correlationId,
            operationId = "recurring.recover_stale_claimed_deliveries",
            source = "RecurringLifecycleCoordinator"
        ) { context ->
            val now = context.occurredAt
            val staleClaimThreshold = now - STALE_CLAIM_THRESHOLD_MS
            val recovered = reminderDeliveryDao.recoverStaleClaimedDeliveries(
                staleClaimThreshold = staleClaimThreshold,
                now = now
            )
            if (recovered > 0) {
                lifecycleEventDao.insert(
                    RecurringLifecycleEvent(
                        occurrenceId = 0L,  // aggregate event, no single occurrence
                        eventType = "STALE_DELIVERIES_RECOVERED",
                        occurredAt = context.occurredAt,
                        oldStatus = null,
                        newStatus = null,
                        metadata = """{"recoveredCount":$recovered}"""
                    )
                )
            }
            recovered
        }
    }

    /**
     * Atomically claim a reminder delivery for processing.
     * Returns true if the claim succeeded, false if another worker already claimed it.
     * The caller should only dispatch a notification after a successful claim.
     */
    suspend fun claimReminderDelivery(deliveryId: Long): Boolean {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.claimReminderDelivery")
        return reminderDeliveryDao.claimDelivery(deliveryId, timeProvider.now()) > 0
    }

    /**
     * Unlinks an expense from its linked occurrence, resetting it back to PLANNED.
     * is still dispatchable before sending a notification.
     */
    data class ReminderDispatchSnapshot(
        val delivery: RecurringReminderDelivery,
        val occurrence: RecurringOccurrence
    )

    /**
     * Returns a dispatch snapshot if the claimed delivery is still dispatchable:
     * - delivery status is CLAIMED
     * - occurrence status is PLANNED
     *
     * Returns null if the delivery should not be dispatched (cancel and skip).
     * Must be called after a successful claim and immediately before notify.
     */
    suspend fun getDispatchableClaimedReminder(deliveryId: Long): ReminderDispatchSnapshot? {
        val delivery = reminderDeliveryDao.getById(deliveryId) ?: return null
        if (delivery.status != "CLAIMED") return null
        val occurrence = occurrenceDao.getById(delivery.occurrenceId) ?: return null
        if (occurrence.status != "PLANNED") return null
        return ReminderDispatchSnapshot(delivery, occurrence)
    }

    /**
     * Cancels a CLAIMED reminder delivery when its occurrence is no longer PLANNED.
     * Called by [BillReminderWorker] when it claims a delivery but the occurrence
     * has been paid/cancelled in the meantime (race between claim and notification).
     *
     * @return true if the delivery was successfully cancelled.
     */
    suspend fun cancelClaimedReminderDelivery(deliveryId: Long, reason: String): Boolean {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.cancelClaimedReminderDelivery")
        val now = timeProvider.now()

        // MIT-043: Wrap delivery cancel + event in a single transaction.
        return database.withTransaction {
            val delivery = reminderDeliveryDao.getById(deliveryId) ?: return@withTransaction false
            val rows = reminderDeliveryDao.cancelClaimedDelivery(deliveryId, reason, now)
            if (rows > 0) {
                lifecycleEventDao.insert(
                    RecurringLifecycleEvent(
                        occurrenceId = delivery.occurrenceId,
                        eventType = "REMINDER_CANCELLED_NO_LONGER_PLANNED",
                        occurredAt = now,
                        oldStatus = "CLAIMED",
                        newStatus = "CANCELLED",
                        metadata = JSONObject().apply {
                            put("deliveryId", deliveryId)
                            put("reason", reason)
                        }.toString()
                    )
                )
            }
            rows > 0
        }
    }

    /**
     * P-04-1: gives a CLAIMED reminder delivery back to SCHEDULED after the post was denied for
     * the notification permission, so a later run can retry once the permission is restored.
     * Bounded by [maxAttempts] (the claim already incremented `attemptCount`); returns false when
     * the budget is spent or the delivery is no longer CLAIMED, and the caller cancels instead.
     * The release and its provenance event share one transaction.
     */
    suspend fun releaseClaimedReminderDelivery(deliveryId: Long, maxAttempts: Int): Boolean {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.releaseClaimedReminderDelivery")
        val now = timeProvider.now()

        return database.withTransaction {
            val delivery = reminderDeliveryDao.getById(deliveryId) ?: return@withTransaction false
            val rows = reminderDeliveryDao.releaseClaimedDelivery(deliveryId, maxAttempts, now)
            if (rows > 0) {
                lifecycleEventDao.insert(
                    RecurringLifecycleEvent(
                        occurrenceId = delivery.occurrenceId,
                        eventType = "REMINDER_RELEASED_PERMISSION_RETRY",
                        occurredAt = now,
                        oldStatus = "CLAIMED",
                        newStatus = "SCHEDULED",
                        metadata = JSONObject().apply {
                            put("deliveryId", deliveryId)
                            put("reason", "notification_permission_denied")
                        }.toString()
                    )
                )
            }
            rows > 0
        }
    }

    /**
     * Marks a reminder delivery as SENT, persisting the notificationId.
     * Only succeeds if the delivery is currently CLAIMED — returns false
     * if payment suppression cancelled the claim before this call.
     * Called by [BillReminderWorker] after dispatching the notification.
     *
     * @return true if the delivery was successfully marked SENT, false otherwise.
     */
    suspend fun markReminderSent(deliveryId: Long, notificationId: Int): Boolean {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.markReminderSent")

        val now = timeProvider.now()

        // MIT-043: Wrap markSent + event in a single transaction.
        return database.withTransaction {
            val existing = reminderDeliveryDao.getById(deliveryId) ?: return@withTransaction false

            val rows = reminderDeliveryDao.markSentFromClaimed(deliveryId, notificationId, now)
            if (rows > 0) {
                lifecycleEventDao.insert(
                    RecurringLifecycleEvent(
                        occurrenceId = existing.occurrenceId,
                        eventType = "REMINDER_SENT",
                        occurredAt = now,
                        oldStatus = "CLAIMED",
                        newStatus = "SENT",
                        metadata = """{"deliveryId":$deliveryId,"notificationId":$notificationId}"""
                    )
                )
            }
            rows > 0
        }
    }

    /**
     * Marks a CLAIMED reminder delivery as failed for a non-permission reason.
     *
     * RP-04 P4-006: permission denial is NOT a recurring-rule failure and must
     * never produce a `FAILED_PERMISSION` status. If a caller passes a
     * permission-flavored [reason], the delivery is transitioned to the terminal
     * `CANCELLED` status with the controlled reason code
     * [REASON_PERMISSION_REVOKED] and a sanitized diagnostic instead. Non-permission
     * failures write `FAILED_TRANSIENT`. The [reason] argument is persisted into
     * the delivery's `failureReason` column by the DAO and echoed into the event
     * metadata via the caller-supplied controlled constant — callers must pass
     * controlled reason codes, never raw exception text.
     */
    suspend fun markReminderFailed(deliveryId: Long, reason: String): Boolean {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.markReminderFailed")

        val now = timeProvider.now()

        // RP-04 P4-006: defensive routing — permission reasons cancel (terminal),
        // they never mark the recurring delivery as FAILED_PERMISSION.
        if (reason.contains("permission", ignoreCase = true)) {
            Timber.w(
                "Reminder delivery permission denied — routing to CANCELLED (deliveryId=%d)",
                deliveryId
            )
            return database.withTransaction {
                val existing = reminderDeliveryDao.getById(deliveryId) ?: return@withTransaction false
                val rows = reminderDeliveryDao.cancelClaimedDelivery(
                    deliveryId, REASON_PERMISSION_REVOKED, now
                )
                if (rows > 0) {
                    lifecycleEventDao.insert(
                        RecurringLifecycleEvent(
                            occurrenceId = existing.occurrenceId,
                            eventType = "REMINDER_DELIVERY_CANCELLED_PERMISSION",
                            occurredAt = now,
                            oldStatus = existing.status,
                            newStatus = "CANCELLED",
                            metadata = JSONObject().apply {
                                put("deliveryId", deliveryId)
                                put("reason", REASON_PERMISSION_REVOKED)
                            }.toString()
                        )
                    )
                }
                rows > 0
            }
        }

        val status = "FAILED_TRANSIENT"

        // MIT-043: Wrap markFailed + event in a single transaction.
        return database.withTransaction {
            val existing = reminderDeliveryDao.getById(deliveryId) ?: return@withTransaction false
            val rows = reminderDeliveryDao.markFailedFromClaimed(deliveryId, status, reason, now)
            if (rows > 0) {
                lifecycleEventDao.insert(
                    RecurringLifecycleEvent(
                        occurrenceId = existing.occurrenceId,
                        eventType = "REMINDER_DELIVERY_FAILED",
                        occurredAt = now,
                        oldStatus = existing.status,
                        newStatus = status,
                        metadata = JSONObject().apply {
                            put("deliveryId", deliveryId)
                            put("reason", reason)
                        }.toString()
                    )
                )
            }
            rows > 0
        }
    }

    /**
     * Dismisses a reminder delivery. Called from [DismissReminderReceiver] via goAsync.
     * Checks write barrier inside the transaction.
     */
    suspend fun dismissReminderDelivery(deliveryId: Long): ReminderActionResult {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.dismissReminderDelivery")
        val now = timeProvider.now()
        return database.withTransaction {
            writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.dismissReminderDelivery.tx")
            val delivery = reminderDeliveryDao.getById(deliveryId)
                ?: return@withTransaction ReminderActionResult.NotFound

            // Only transition from non-terminal states
            if (delivery.status in TERMINAL_STATUSES) {
                return@withTransaction ReminderActionResult.NoOp("Already in terminal state: ${delivery.status}")
            }

            reminderDeliveryDao.update(delivery.copy(status = "DISMISSED", dismissedAt = now, updatedAt = now))
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = delivery.occurrenceId,
                    eventType = "REMINDER_DISMISSED",
                    occurredAt = now,
                    oldStatus = delivery.status,
                    newStatus = "DISMISSED",
                    metadata = """{"deliveryId":$deliveryId}"""
                )
            )
            ReminderActionResult.Updated
        }
    }

    /**
     * Snoozes a reminder delivery for [snoozeMs] milliseconds. Called from [SnoozeReminderReceiver] via goAsync.
     * Checks write barrier inside the transaction.
     */
    suspend fun snoozeReminderDelivery(
        deliveryId: Long,
        snoozeMs: Long = 24L * 60L * 60L * 1000L
    ): ReminderActionResult {
        writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.snoozeReminderDelivery")
        val now = timeProvider.now()
        val snoozedUntil = now + snoozeMs
        return database.withTransaction {
            writeBarrier.checkWritesAllowed("RecurringLifecycleCoordinator.snoozeReminderDelivery.tx")
            val delivery = reminderDeliveryDao.getById(deliveryId)
                ?: return@withTransaction ReminderActionResult.NotFound

            if (delivery.status in TERMINAL_STATUSES) {
                return@withTransaction ReminderActionResult.NoOp("Already in terminal state: ${delivery.status}")
            }

            reminderDeliveryDao.update(delivery.copy(status = "SNOOZED", snoozedUntil = snoozedUntil, updatedAt = now))
            lifecycleEventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = delivery.occurrenceId,
                    eventType = "REMINDER_SNOOZED",
                    occurredAt = now,
                    oldStatus = delivery.status,
                    newStatus = "SNOOZED",
                    metadata = """{"deliveryId":$deliveryId,"snoozedUntil":$snoozedUntil}"""
                )
            )
            ReminderActionResult.Updated
        }
    }

    /**
     * Planned-vs-Actual reconciliation report for a recurring rule.
     *
     * @param totalPlanned Sum of expected amounts for all generated occurrences.
     * @param totalActual Sum of actual paid amounts for matched occurrences.
     * @param drift Difference: totalActual - totalPlanned (positive = overspend).
     * @param driftPercent Percentage drift relative to totalPlanned.
     * @param matchedCount Number of occurrences that were paid.
     * @param unmatchedCount Number of occurrences still PLANNED (no actual expense linked).
     * @param overBudgetCount Number of PAID occurrences where paidAmount > expectedAmount.
     */
    data class ReconciliationReport(
        val totalPlanned: Double,
        val totalActual: Double,
        val drift: Double,
        val driftPercent: Double,
        val matchedCount: Int,
        val unmatchedCount: Int,
        val overBudgetCount: Int
    )

    /**
     * Ensures occurrences are generated for the given rule and period.
     * This is the EXPLICIT WRITE command — callers must do this before getting a report.
     *
     * P4-NEW-008: Extracted from reconcilePlannedVsActual to separate write from read.
     *
     * RP-04 P4-006 dead-code disposition (2026-09-19): RETAINED — its only caller is
     * the `@Deprecated(DeprecationLevel.ERROR)` [reconcilePlannedVsActual] below
     * (compile-error guarded, no live production or test callers). It is not a
     * second production generation path; deletion is deferred until that
     * deprecated method is removed (RP-20 candidate), since removing it now would
     * break the deprecated method's compilation.
     */
    suspend fun ensureOccurrencesGeneratedForReconciliation(ruleId: Long, monthsBack: Int = 3) {
        val now = timeProvider.now()
        val endDate = TimePeriodUtils.getStartOfDay(now)
        val startDate = TimePeriodUtils.getStartOfMonth(
            TimePeriodUtils.addMonths(now, -monthsBack)
        )

        database.withTransaction {
            generateOccurrences(
                ruleId = ruleId,
                startDate = startDate,
                endDate = endDate,
                options = OccurrenceGenerationOptions(
                    createReminderDeliveries = false,
                    generationSource = OccurrenceGenerationSource.RECONCILIATION_REPORT
                )
            )
        }
    }

    /**
     * Calculates planned vs actual spending for a recurring rule over the past N months.
     *
     * P4-NEW-008: PURE READ — no DB writes. Callers must call
     * [ensureOccurrencesGeneratedForReconciliation] first if fresh generation is needed.
     */
    suspend fun calculatePlannedVsActualReport(ruleId: Long, monthsBack: Int = 3): ReconciliationReport {
        val now = timeProvider.now()
        val endDate = TimePeriodUtils.getStartOfDay(now)
        val startDate = TimePeriodUtils.getStartOfMonth(
            TimePeriodUtils.addMonths(now, -monthsBack)
        )

        val occurrences = occurrenceDao.getByDateRange(startDate, endDate)
            .filter { it.sourceType == SOURCE_TYPE_RECURRING_RULE && it.sourceId == ruleId }

        var totalPlanned = 0.0
        var totalActual = 0.0
        var matchedCount = 0
        var unmatchedCount = 0
        var overBudgetCount = 0

        for (occ in occurrences) {
            when (occ.status) {
                "PAID" -> {
                    totalPlanned += occ.expectedAmount
                    val paid = occ.paidAmount ?: 0.0
                    totalActual += paid
                    matchedCount++
                    if (paid > occ.expectedAmount) {
                        overBudgetCount++
                    }
                }
                "PLANNED" -> {
                    totalPlanned += occ.expectedAmount
                    unmatchedCount++
                }
                else -> { /* excluded */ }
            }
        }

        val drift = totalActual - totalPlanned
        val driftPercent = if (totalPlanned > 0.0) {
            (drift / totalPlanned) * 100.0
        } else {
            0.0
        }

        return ReconciliationReport(
            totalPlanned = totalPlanned,
            totalActual = totalActual,
            drift = drift,
            driftPercent = driftPercent,
            matchedCount = matchedCount,
            unmatchedCount = unmatchedCount,
            overBudgetCount = overBudgetCount
        )
    }

    /**
     * Compares planned vs actual spending for a recurring rule over the past N months.
     *
     * **Note:** This method has write side-effects — it calls [generateOccurrences]
     * internally to ensure the database is up-to-date before computing the report.
     * Despite the query-like name, this is NOT a pure read-only method.
     *
     * @deprecated Split into [ensureOccurrencesGeneratedForReconciliation] (explicit write)
     *             + [calculatePlannedVsActualReport] (pure read).
     *             Use those two methods instead of this combined one.
     *
     * Logic:
     * 1. Generate occurrences for the past N months via [generateOccurrences].
     * 2. Load all occurrences in that range.
     * 3. Compare PAID occurrences (expectedAmount vs paidAmount).
     * 4. Count PLANNED occurrences that have no linked expense.
     * 5. Return a [ReconciliationReport] with drift analysis.
     *
     * @param ruleId The ID of the recurring rule.
     * @param monthsBack Number of months to look back (default 3).
     */
    @Deprecated(
        message = "Use ensureOccurrencesGeneratedForReconciliation() + calculatePlannedVsActualReport()",
        replaceWith = ReplaceWith("this.ensureOccurrencesGeneratedForReconciliation(ruleId, monthsBack); this.calculatePlannedVsActualReport(ruleId, monthsBack)"),
        level = DeprecationLevel.ERROR
    )
    suspend fun reconcilePlannedVsActual(ruleId: Long, monthsBack: Int = 3): ReconciliationReport {
        ensureOccurrencesGeneratedForReconciliation(ruleId, monthsBack)
        return calculatePlannedVsActualReport(ruleId, monthsBack)
    }
}
