package com.yourname.expensetracker.domain.reminder

import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectedEntry
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionPort
import com.yourname.expensetracker.domain.logic.RecurrenceCalculator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One installment reminder candidate (S7b). Built only from the read-only
 * [InstallmentProjectionPort], so it is always a SCHEDULED entry of an ACTIVE plan due today or
 * later (INV-15). [displayName] is local user text: [toString] redacts it so it can never reach a
 * log through string templates.
 */
data class InstallmentReminder(
    val planUuid: String,
    val seq: Int,
    val recordGeneration: Int,
    val entryId: Long,
    val displayName: String,
    val currencyCode: String,
    val amountMinor: Long,
    val dueEpochDay: Long,
    val daysUntilDue: Int
) {
    /** Delivery dedupe key `planUuid:seq:recordGeneration`. */
    val dedupeKey: String get() = "$planUuid:$seq:$recordGeneration"

    override fun toString(): String = "InstallmentReminder(redacted)"
}

/** Text of an installment reminder notification; same shape as the recurring bill reminder text. */
data class InstallmentReminderText(val title: String, val body: String) {
    /** The body carries the plan name and amount (user data); never print it (S7b privacy review C2). */
    override fun toString(): String = "InstallmentReminderText(redacted)"
}

data class BillReminder(
    val recurringExpenseId: Long,
    val merchant: String,
    val amount: Double,
    val currency: String,
    val dueDate: Long,
    val daysUntilDue: Int,
    val isOverdue: Boolean,
    val urgency: ReminderUrgency
)

enum class ReminderUrgency {
    INFO,      // Due > 7 days
    WARNING,   // Due in 3-7 days
    URGENT,    // Due in 1-2 days
    CRITICAL   // Due today or overdue
}

/**
 * Manages bill reminders based on recurring expense rules.
 *
 * ## Integration with RecurringLifecycleCoordinator
 *
 * This class's [getNotificationsDue] method filters upcoming reminders by urgency.
 * However, the preferred scheduling path going forward is:
 * 1. Use [RecurringLifecycleCoordinator.getDueReminders] which queries the
 *    `recurring_reminder_deliveries` table for SCHEDULED deliveries whose
 *    `scheduledAt` has passed.
 * 2. A [ReminderDispatchWorker] (WorkManager — to be created in a future PR)
 *    should run periodic checks and call [RecurringLifecycleCoordinator.getDueReminders]
 *    to dispatch notifications.
 *
 * Until that worker exists, [getNotificationsDue] remains active for backward
 * compatibility with the legacy reminder path.
 */
@Singleton
class BillReminderManager @Inject constructor(
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val timeProvider: TimeProvider,
    private val multiCurrencyRepository: MultiCurrencyRepository,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val installmentProjectionPort: InstallmentProjectionPort,
    private val installmentReminderLedger: InstallmentReminderLedger,
    private val reminderSettingsRepository: BillReminderSettingsRepository
) {
    companion object {
        const val DEFAULT_REMINDER_DAYS = 3 // Days before due date

        /** Title shared with recurring bill reminders (the worker's existing "Bill due"). */
        const val INSTALLMENT_REMINDER_TITLE = "Bill due"

        /** Generic name used when the plan name is blank (same fallback as a recurring bill without merchant). */
        const val INSTALLMENT_REMINDER_NAME_FALLBACK = "Bill"

        /**
         * Scheduling note: future reminder scheduling should use
         * [WorkerSpecScheduler] (e.g. [WorkerSpecScheduler.scheduleFromSpec]
         * or [WorkerSpecScheduler.scheduleAtMidnight]) to register
         * recurring [WorkManager] workers for periodic dispatch.
         * The existing [getNotificationsDue] is a pull-based fallback and
         * does NOT self-schedule.
         */
    }
    
    /**
     * Get all upcoming bill reminders.
     */
    suspend fun getUpcomingReminders(daysAhead: Int = 14): List<BillReminder> = withContext(Dispatchers.IO) {
        val now = timeProvider.now()
        val cutoff = TimePeriodUtils.addDays(now, daysAhead)
        
        val recurring = recurringExpenseRepository.getAll()
        val reminders = mutableListOf<BillReminder>()
        
        for (expense in recurring) {
            if (!expense.isActive) continue
            
            val nextDate = expense.nextDate
            if (nextDate > cutoff) continue
            
            val daysUntil = TimePeriodUtils.daysBetween(now, nextDate)
            
            val urgency = when {
                nextDate <= now -> ReminderUrgency.CRITICAL
                daysUntil <= 2 -> ReminderUrgency.URGENT
                daysUntil <= 7 -> ReminderUrgency.WARNING
                else -> ReminderUrgency.INFO
            }
            
            reminders.add(
                BillReminder(
                    recurringExpenseId = expense.id,
                    merchant = expense.merchant,
                    amount = expense.amount,
                    currency = expense.currency,
                    dueDate = nextDate,
                    daysUntilDue = daysUntil,
                    isOverdue = nextDate < now,
                    urgency = urgency
                )
            )
        }
        
        reminders.sortedBy { it.dueDate }
    }
    
    /**
     * Get reminders that need notification now.
     *
     * ## Coordinator path (preferred)
     *
     * Use [com.yourname.expensetracker.domain.recurring.RecurringLifecycleCoordinator.getDueReminders]
     * which queries the `recurring_reminder_deliveries` table for SCHEDULED deliveries whose
     * `scheduledAt` has passed, and dispatch via a [BillReminderWorker] (WorkManager).
     */
    @Deprecated(
        message = "Use RecurringLifecycleCoordinator.getDueReminders() and BillReminderWorker",
        replaceWith = ReplaceWith(
            "RecurringLifecycleCoordinator.getDueReminders()",
            "com.yourname.expensetracker.domain.recurring.RecurringLifecycleCoordinator"
        )
    )
    suspend fun getNotificationsDue(): List<BillReminder> = withContext(Dispatchers.IO) {
        val reminders = getUpcomingReminders(7)
        
        reminders.filter { reminder ->
            when (reminder.urgency) {
                ReminderUrgency.CRITICAL -> true
                ReminderUrgency.URGENT -> reminder.daysUntilDue in 1..2
                ReminderUrgency.WARNING -> reminder.daysUntilDue == DEFAULT_REMINDER_DAYS
                ReminderUrgency.INFO -> false
            }
        }
    }
    
    /**
     * Mark a bill as paid. **Removed** — must use actual expense lifecycle.
     *
     * Marking a bill as paid without an actual expense breaks the provenance model:
     * the occurrence would be PAID without `linkedExpenseId`, and the planned
     * expense would be FULFILLED without `linkedActualExpenseId`.
     *
     * **Replacement:** Create an actual expense through the transaction lifecycle,
     * then call `RecurringLifecycleCoordinator.linkExpenseToOccurrence(expenseId)`.
     *
     * @deprecated Removed. Use actual expense creation + linkExpenseToOccurrence.
     */
    @Deprecated(
        message = "Removed. Create an actual expense and use RecurringLifecycleCoordinator.linkExpenseToOccurrence(expenseId)",
        level = DeprecationLevel.ERROR
    )
    @Suppress("UNUSED_PARAMETER")
    suspend fun markBillPaid(recurringExpenseId: Long): Nothing {
        error("Legacy markBillPaid removed. Create an actual expense and use linkExpenseToOccurrence(expenseId).")
    }
    
    /**
     * Get the total expected bills for the current month in the resolved home currency.
     *
     * Each active rule is normalized to its monthly source amount before conversion. The
     * repository performs currency validation and aggregation, preserving partial and
     * unavailable states instead of fabricating a zero or relabeling mixed currencies.
     */
    suspend fun getMonthlyBillsTotal(): MoneyAggregateResult = withContext(Dispatchers.IO) {
        val targetCurrency = resolveDisplayCurrency()
            ?: return@withContext MoneyAggregateResult.Unavailable(
                reason = MoneyDisplayUnavailableReasonCode.HOME_CURRENCY_UNAVAILABLE.name,
                requestedRateBasis = RateBasis.LATEST_AVAILABLE,
                warningMessage = MoneyDisplayUnavailableReasonCode.HOME_CURRENCY_UNAVAILABLE.name
            )

        val activeRules = recurringExpenseRepository.getAll().filter { it.isActive }
        val monthlyAmounts = activeRules.map { expense ->
            RecurrenceCalculator.toMonthlyAmount(expense.amount, expense.frequency) to expense.currency
        }

        multiCurrencyRepository.aggregateDisplayAmounts(
            amounts = monthlyAmounts,
            transactionCounts = List(monthlyAmounts.size) { 1 },
            targetCurrency = targetCurrency
        )
    }

    /**
     * Installment reminders that should be delivered now (S7b, INV-15). Reads only through the
     * read-only [InstallmentProjectionPort], so entries of stopped (AE-7) or completed (AE-11)
     * plans, and entries that are not SCHEDULED or are overdue, never appear. Returns an empty
     * list when reminders are disabled or inside quiet hours (the same settings the recurring
     * bill reminders use). Entries already recorded in the dedupe ledger are left out.
     */
    suspend fun getDueInstallmentReminders(): List<InstallmentReminder> = withContext(Dispatchers.IO) {
        val today = todayEpochDay()
        // Bounded growth: forget old records on every read, not only when a claim is made.
        installmentReminderLedger.prune(today)
        if (!installmentRemindersAllowedNow()) return@withContext emptyList()
        val horizon = today + DEFAULT_REMINDER_DAYS
        val candidates = installmentProjectionPort.upcomingEntries(horizon)
            .mapNotNull { it.toInstallmentReminderOrNull(today) }
        val nowMillis = timeProvider.now()
        val due = ArrayList<InstallmentReminder>(candidates.size)
        for (candidate in candidates) {
            if (!installmentReminderLedger.isClaimed(candidate.dedupeKey, candidate.dueEpochDay, nowMillis)) {
                due += candidate
            }
        }
        due
    }

    /**
     * Claims [reminder] for delivery and revalidates its entry state after the claim, so a reminder
     * is never posted for an entry that was paid, linked, skipped, cancelled or whose plan was
     * stopped or completed between listing and posting. Returns the freshly read reminder to post,
     * or null (claim released, nothing to post). A throw during the revalidation (including
     * cancellation) releases the claim under [NonCancellable] and rethrows. After a successful
     * post the caller calls [markInstallmentReminderSent]; after a failed post it calls
     * [releaseInstallmentReminder].
     */
    suspend fun claimInstallmentReminder(reminder: InstallmentReminder): InstallmentReminder? =
        withContext(Dispatchers.IO) {
            val today = todayEpochDay()
            val key = reminder.dedupeKey
            if (!installmentReminderLedger.tryClaim(key, reminder.dueEpochDay, today, timeProvider.now())) {
                return@withContext null
            }
            val fresh = try {
                revalidateInstallmentReminder(reminder, today)
            } catch (t: Throwable) {
                // A failing release must not hide the original failure (or a cancellation).
                try {
                    withContext(NonCancellable) { installmentReminderLedger.release(key) }
                } catch (r: CancellationException) {
                    t.addSuppressed(r)
                } catch (r: Exception) {
                    t.addSuppressed(r)
                }
                throw t
            }
            // A due day that moved between listing and the claim: give the claim back so the next
            // run lists (and claims) the entry under its new due day.
            if (fresh == null || fresh.dueEpochDay != reminder.dueEpochDay) {
                withContext(NonCancellable) { installmentReminderLedger.release(key) }
                return@withContext null
            }
            fresh
        }

    /**
     * Phase 2 of the ledger: records the reminder as delivered so it is never reminded again for
     * this due day. Returns false when the write failed (the notification is already posted).
     */
    suspend fun markInstallmentReminderSent(reminder: InstallmentReminder): Boolean =
        installmentReminderLedger.markSent(reminder.dedupeKey, reminder.dueEpochDay, timeProvider.now())

    /** Gives the unsent dedupe claim back when nothing was delivered (post failed or permission revoked). */
    suspend fun releaseInstallmentReminder(reminder: InstallmentReminder) {
        withContext(NonCancellable) { installmentReminderLedger.release(reminder.dedupeKey) }
    }

    /**
     * Notification text. It follows the recurring bill reminder exactly: the generic title and a
     * body of "name due: amount currency". The plan name is shown the way a bill merchant is; a
     * blank name (redacted restore, INV-34) falls back to the generic [INSTALLMENT_REMINDER_NAME_FALLBACK]
     * so the title and body are never empty and nothing else is revealed. The lender, the n/N
     * counter, the schedule and the plan status are never part of the text.
     */
    fun buildInstallmentReminderText(reminder: InstallmentReminder): InstallmentReminderText {
        val name = reminder.displayName.trim().ifEmpty { INSTALLMENT_REMINDER_NAME_FALLBACK }
        // Same as the bill reminder: "%.2f" in the default locale. The BigDecimal is exact (no Double);
        // SCALE (2) is the currency scale because only currencies with exactly that scale are projected.
        val amount = "%.2f".format(BigDecimal.valueOf(reminder.amountMinor, InstallmentMinorUnits.SCALE))
        return InstallmentReminderText(
            title = INSTALLMENT_REMINDER_TITLE,
            body = "$name due: $amount ${reminder.currencyCode}"
        )
    }

    private suspend fun revalidateInstallmentReminder(
        claimed: InstallmentReminder,
        today: Long
    ): InstallmentReminder? {
        if (!installmentRemindersAllowedNow()) return null
        val current = installmentProjectionPort.upcomingEntries(today + DEFAULT_REMINDER_DAYS)
            .firstOrNull {
                it.planUuid == claimed.planUuid &&
                    it.seq == claimed.seq &&
                    it.recordGeneration == claimed.recordGeneration
            } ?: return null
        return current.toInstallmentReminderOrNull(today)
    }

    private fun InstallmentProjectedEntry.toInstallmentReminderOrNull(today: Long): InstallmentReminder? {
        if (dueEpochDay < today) return null
        if (!InstallmentMinorUnits.isSupportedScale(currency)) return null
        return InstallmentReminder(
            planUuid = planUuid,
            seq = seq,
            recordGeneration = recordGeneration,
            entryId = entryId,
            displayName = displayName,
            currencyCode = currency.code,
            amountMinor = amountMinor,
            dueEpochDay = dueEpochDay,
            daysUntilDue = (dueEpochDay - today).toInt()
        )
    }

    private suspend fun installmentRemindersAllowedNow(): Boolean {
        val settings = reminderSettingsRepository.getSnapshot()
        return settings.billRemindersEnabled && !settings.isWithinQuietHours(timeProvider.now())
    }

    private fun todayEpochDay(): Long =
        Instant.ofEpochMilli(timeProvider.now()).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private suspend fun resolveDisplayCurrency(): String? {
        val resolved = when (val resolution = currencySettingsRepository.resolveHomeCurrency()) {
            is HomeCurrencyResolution.Resolved -> resolution.currency.code
            is HomeCurrencyResolution.FirstRunDefault -> resolution.currency.code
            is HomeCurrencyResolution.Failed -> return null
        }
        return SupportedCurrency.fromActiveCode(resolved)?.code
    }
}
