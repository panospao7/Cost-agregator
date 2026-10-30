package com.yourname.expensetracker.domain.currency

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * manual-entry-currency 1b S1: [RateRemedyPolicy] (INV-1b-5). Every branch plus the window and
 * latest-vs-entry-date boundaries. Dates are synthetic.
 */
class RateRemedyPolicyTest {

    private val today: LocalDate = LocalDate.of(2026, 6, 30)
    private val stale = RateEligibility.Stale(LocalDate.of(2026, 6, 1), 10)
    private val eligible = RateEligibility.Eligible(
        rate = 1.1,
        rateDate = today,
        ageDays = 0,
        path = RateEligibilityPath.DIRECT,
        sourceLabel = RateSourceLabel.ECB
    )

    private fun decide(
        eligibility: RateEligibility,
        localDate: LocalDate,
        latest: LocalDate?,
        downloaded: Boolean = false
    ): RateRemedyDecision = RateRemedyPolicy.remedy(eligibility, localDate, latest, today, downloaded)

    // ---- (1) not blocked -> NONE without a reason ----

    @Test
    fun `INV-1b-5 Home Eligible and Unsupported never offer a remedy`() {
        val none = RateRemedyDecision(RateRemedy.NONE)
        val entry = today.minusDays(5)
        for (downloaded in listOf(false, true)) {
            assertEquals(none, decide(RateEligibility.Home, entry, null, downloaded))
            assertEquals(none, decide(eligible, entry, today.minusDays(1), downloaded))
            assertEquals(
                none,
                decide(RateEligibility.Unsupported(RateUnsupportedReason.NOT_SELECTABLE), entry, null, downloaded)
            )
            assertEquals(
                none,
                decide(RateEligibility.Unsupported(RateUnsupportedReason.NOT_IN_CATALOG), entry, today, downloaded)
            )
        }
    }

    // ---- (2) REFRESH_LATEST ----

    @Test
    fun `INV-1b-5 no stored pair at all refreshes the latest rates (None and Stale)`() {
        val entry = today.minusDays(5)
        assertEquals(RateRemedyDecision(RateRemedy.REFRESH_LATEST), decide(RateEligibility.None, entry, null))
        assertEquals(RateRemedyDecision(RateRemedy.REFRESH_LATEST), decide(stale, entry, null))
    }

    @Test
    fun `INV-1b-5 latest stored older than the entry date refreshes`() {
        val entry = today.minusDays(5)
        assertEquals(
            RateRemedyDecision(RateRemedy.REFRESH_LATEST),
            decide(RateEligibility.None, entry, entry.minusDays(1))
        )
    }

    @Test
    fun `INV-1b-5 latest stored equal to the entry date still refreshes (boundary)`() {
        val entry = today.minusDays(5)
        assertEquals(RateRemedyDecision(RateRemedy.REFRESH_LATEST), decide(RateEligibility.None, entry, entry))
        assertEquals(RateRemedyDecision(RateRemedy.REFRESH_LATEST), decide(stale, entry, entry))
    }

    @Test
    fun `INV-1b-5 refresh wins over the window and the session flag when nothing newer is stored`() {
        val oldEntry = today.minusDays(200)
        assertEquals(
            RateRemedyDecision(RateRemedy.REFRESH_LATEST),
            decide(RateEligibility.None, oldEntry, null, downloaded = true)
        )
        assertEquals(
            RateRemedyDecision(RateRemedy.REFRESH_LATEST),
            decide(RateEligibility.None, oldEntry, oldEntry, downloaded = true)
        )
    }

    // ---- (3) DOWNLOAD_HISTORY ----

    @Test
    fun `INV-1b-5 latest one day after the entry date downloads history (boundary)`() {
        val entry = today.minusDays(5)
        assertEquals(
            RateRemedyDecision(RateRemedy.DOWNLOAD_HISTORY),
            decide(RateEligibility.None, entry, entry.plusDays(1))
        )
        assertEquals(
            RateRemedyDecision(RateRemedy.DOWNLOAD_HISTORY),
            decide(stale, entry, today)
        )
    }

    @Test
    fun `INV-1b-5 entry exactly today minus 90 is inside the window and downloads history`() {
        val entry = today.minusDays(90)
        assertEquals(90L, RateRemedyPolicy.HISTORY_WINDOW_DAYS)
        assertEquals(
            RateRemedyDecision(RateRemedy.DOWNLOAD_HISTORY),
            decide(RateEligibility.None, entry, today)
        )
    }

    // ---- (4) NONE with a reason ----

    @Test
    fun `INV-1b-5 entry today minus 91 is outside the window`() {
        val entry = today.minusDays(91)
        assertEquals(
            RateRemedyDecision(RateRemedy.NONE, NoRemedyReason.OUTSIDE_HISTORY_WINDOW),
            decide(RateEligibility.None, entry, today)
        )
        assertEquals(
            RateRemedyDecision(RateRemedy.NONE, NoRemedyReason.OUTSIDE_HISTORY_WINDOW),
            decide(stale, entry, entry.plusDays(1))
        )
    }

    @Test
    fun `INV-1b-5 the window is checked before the session flag`() {
        val entry = today.minusDays(91)
        assertEquals(
            RateRemedyDecision(RateRemedy.NONE, NoRemedyReason.OUTSIDE_HISTORY_WINDOW),
            decide(RateEligibility.None, entry, today, downloaded = true)
        )
    }

    @Test
    fun `INV-1b-5 inside the window a completed session download offers no second download`() {
        val entry = today.minusDays(5)
        assertEquals(
            RateRemedyDecision(RateRemedy.NONE, NoRemedyReason.HISTORY_ALREADY_DOWNLOADED),
            decide(RateEligibility.None, entry, today, downloaded = true)
        )
        assertEquals(
            RateRemedyDecision(RateRemedy.NONE, NoRemedyReason.HISTORY_ALREADY_DOWNLOADED),
            decide(stale, today.minusDays(90), entry, downloaded = true)
        )
    }

    @Test
    fun `INV-1b-5 a future entry date with a nothing-newer pair refreshes, never downloads history`() {
        val entry = today.plusDays(3)
        assertEquals(
            RateRemedyDecision(RateRemedy.REFRESH_LATEST),
            decide(RateEligibility.None, entry, today)
        )
    }
}
