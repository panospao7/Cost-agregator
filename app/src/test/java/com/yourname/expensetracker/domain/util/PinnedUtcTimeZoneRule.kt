package com.yourname.expensetracker.domain.util

/**
 * Class-level rule that pins the JVM default time zone to UTC for the whole test class and
 * holds [GlobalTimeZoneTestLock] while doing so. Thin specialisation of [PinnedTimeZoneRule]
 * kept so existing `@ClassRule` users keep compiling; the lock/restore-on-failure logic lives
 * in the shared base rule. Use via `@ClassRule` on a `@JvmField` in a companion.
 */
class PinnedUtcTimeZoneRule : PinnedTimeZoneRule("UTC")
