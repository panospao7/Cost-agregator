package com.yourname.expensetracker.domain.util

import org.junit.rules.ExternalResource
import java.util.TimeZone

/**
 * Shared, parameterised rule that pins the JVM default time zone to [zoneId] for the scope it
 * is applied to (a test via `@get:Rule`, or a whole class via `@ClassRule` on a `@JvmField` in
 * a companion) and holds [GlobalTimeZoneTestLock] while doing so. Production code under test
 * (for example SpendingPaceCalculator, BudgetCalculator and TimePeriodUtils) reads
 * `ZoneId.systemDefault()`, so pinning here removes any dependence on the host zone.
 *
 * If anything after acquiring the lock throws in [before] (for example an invalid state while
 * switching the zone), the zone is restored and the lock is released, because JUnit does not
 * call [after] when [before] fails.
 *
 * The lock is reentrant, so tests that pin this rule can still nest
 * [GlobalTimeZoneTestLock.withLock] to temporarily switch to another zone.
 */
open class PinnedTimeZoneRule(private val zoneId: String) : ExternalResource() {
    private var original: TimeZone? = null

    override fun before() {
        GlobalTimeZoneTestLock.acquire()
        try {
            original = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
        } catch (t: Throwable) {
            try {
                original?.let { TimeZone.setDefault(it) }
            } finally {
                GlobalTimeZoneTestLock.release()
            }
            throw t
        }
    }

    override fun after() {
        try {
            original?.let { TimeZone.setDefault(it) }
        } finally {
            GlobalTimeZoneTestLock.release()
        }
    }
}
