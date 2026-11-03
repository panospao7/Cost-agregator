package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import com.yourname.expensetracker.service.NotificationFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-1.0.4 follow-up (FL-02): the SMS amount-ambiguity review rule keys off
 * [SmsParser.SMS_PACKAGES]. [NotificationFilter] must list exactly the same SMS packages
 * (plus only the known non-SMS communication apps), so a package added to one list but not
 * the other cannot silently skip the review rule.
 */
class NotificationFilterSmsPackagesPinTest {

    /** Communication packages that are not SMS apps and are not covered by the SMS review rule. */
    private val nonSmsCommunicationPackages = setOf(
        "com.viber.voip",
        "com.google.android.gm"
    )

    @Test
    fun `every SmsParser SMS package is a NotificationFilter communication package`() {
        assertTrue(
            "SMS_PACKAGES missing from NotificationFilter.COMMUNICATION_PACKAGES",
            NotificationFilter.COMMUNICATION_PACKAGES.containsAll(SmsParser.SMS_PACKAGES)
        )
    }

    @Test
    fun `NotificationFilter lists no SMS package that SmsParser does not know`() {
        val filterSmsPackages = NotificationFilter.COMMUNICATION_PACKAGES - nonSmsCommunicationPackages
        assertEquals(SmsParser.SMS_PACKAGES, filterSmsPackages)
    }

    @Test
    fun `SMS packages are monitored and never ignored`() {
        assertTrue(NotificationFilter.MONITORED_PACKAGES.containsAll(SmsParser.SMS_PACKAGES))
        assertTrue(NotificationFilter.IGNORED_PACKAGES.intersect(SmsParser.SMS_PACKAGES).isEmpty())
    }
}
