package com.yourname.expensetracker.domain.installment.lifecycle

import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import java.time.ZoneId

/**
 * Read-only port that answers one bounded question for the plan coordinator: does an unlinked,
 * eligible charge already sit in the candidate window of this entry (INV-13, domain spec 8.2)?
 *
 * The plan coordinator uses it to defer the auto-record of an entry that may already have been
 * paid by a captured expense (AE-17: candidate deferral only). It returns a boolean and nothing
 * else: no amount, no expense id and no expense text ever cross this port, so the coordinator
 * decides on the answer without reading expense money (the coordinator money-read ban stays).
 *
 * One owner: the implementation in the data layer is the single scan behind this port and behind
 * the read repository's suggestions. It applies the pure candidate rule for window, currency and
 * the one-minor-unit amount match, then the expense eligibility (not a plan purchase record, not
 * linked to an entry or an occurrence, a spending type). It performs no writes. A read joins the
 * caller's Room transaction when one is open.
 */
interface InstallmentCandidateChargeProbe {

    /**
     * True when a candidate charge exists for [entry] of [plan], or when the stored plan or entry
     * values cannot be interpreted (unknown mode, currency, kind or status code, an amount outside
     * the minor-unit range). Unreadable input fails closed: the caller must not auto-record then.
     *
     * False when the entry is not a SCHEDULED, unlinked REGULAR entry, or when no eligible charge
     * matches. The entry due date is converted to the candidate window in [zone].
     */
    suspend fun hasCandidate(plan: InstallmentPlan, entry: InstallmentEntry, zone: ZoneId): Boolean
}
