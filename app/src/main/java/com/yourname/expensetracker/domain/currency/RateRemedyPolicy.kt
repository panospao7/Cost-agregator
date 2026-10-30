package com.yourname.expensetracker.domain.currency

import java.time.LocalDate

/** The one network remedy offered for a blocked user-present entry (INV-1b-5). */
enum class RateRemedy {
    NONE,
    REFRESH_LATEST,
    DOWNLOAD_HISTORY
}

/** Why [RateRemedy.NONE] was chosen for a blocked entry that no remedy can fix. Bounded. */
enum class NoRemedyReason {
    /** The entry date is before `today - HISTORY_WINDOW_DAYS`; the history file cannot cover it. */
    OUTSIDE_HISTORY_WINDOW,

    /** A history download already succeeded in this entry session. */
    HISTORY_ALREADY_DOWNLOADED
}

/**
 * Result of [RateRemedyPolicy.remedy]. [noRemedyReason] is non-null only when the entry is blocked
 * (`Stale`/`None`) and [remedy] is [RateRemedy.NONE].
 */
data class RateRemedyDecision(
    val remedy: RateRemedy,
    val noRemedyReason: NoRemedyReason? = null
)

/**
 * Sole owner of the remedy choice for a blocked user-present entry when the download setting is On
 * (INV-1b-5, D-1b-1: refresh first). Pure: no clock, no store, no network, no logging.
 */
object RateRemedyPolicy {

    /** Calendar days before `today` that the ECB history file is assumed to cover. */
    const val HISTORY_WINDOW_DAYS: Long = 90L

    /**
     * @param eligibility the evaluator result for the entry.
     * @param localDate the entry's calendar date in the entry zone.
     * @param latestStoredPairDate UTC date of the newest stored direct pair, or null if none.
     * @param today the caller's clock date in the entry zone.
     * @param historyDownloadedThisSession true after a successful history download in this session.
     */
    fun remedy(
        eligibility: RateEligibility,
        localDate: LocalDate,
        latestStoredPairDate: LocalDate?,
        today: LocalDate,
        historyDownloadedThisSession: Boolean
    ): RateRemedyDecision {
        when (eligibility) {
            RateEligibility.Home,
            is RateEligibility.Eligible,
            is RateEligibility.Unsupported -> return RateRemedyDecision(RateRemedy.NONE)
            is RateEligibility.Stale,
            RateEligibility.None -> Unit
        }
        // The daily file can still add a date that helps (first use, or nothing newer than the entry).
        if (latestStoredPairDate == null || !latestStoredPairDate.isAfter(localDate)) {
            return RateRemedyDecision(RateRemedy.REFRESH_LATEST)
        }
        if (localDate.isBefore(today.minusDays(HISTORY_WINDOW_DAYS))) {
            return RateRemedyDecision(RateRemedy.NONE, NoRemedyReason.OUTSIDE_HISTORY_WINDOW)
        }
        if (historyDownloadedThisSession) {
            return RateRemedyDecision(RateRemedy.NONE, NoRemedyReason.HISTORY_ALREADY_DOWNLOADED)
        }
        return RateRemedyDecision(RateRemedy.DOWNLOAD_HISTORY)
    }
}
