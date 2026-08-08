package com.yourname.expensetracker.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Focused contract tests for [SystemMonotonicTimeProvider].
 *
 * The production provider delegates to `kotlin.time.TimeSource.Monotonic`
 * (`System.nanoTime()` on Android/JVM), whose resolution, granularity, and
 * rollover behavior are platform-specific. We deliberately do NOT assert
 * exact elapsed values or nanosecond precision — that would be flaky and
 * would overclaim platform timing guarantees. Only the stable monotonic
 * contract is verified here:
 *
 *  - readings are integer Long-like values;
 *  - successive readings are non-decreasing.
 *
 * Deterministic elapsed-duration behavior (e.g. ViewModel timing
 * diagnostics) is covered through [FakeMonotonicTimeProvider], which tests
 * advance explicitly instead of depending on real platform timing.
 */
class SystemMonotonicTimeProviderTest {

    @Test
    fun `nowNanos returns an integer Long-like value`() {
        val provider = SystemMonotonicTimeProvider()

        val value = provider.nowNanos()

        assertThat(value).isInstanceOf(Long::class.javaObjectType)
    }

    @Test
    fun `successive readings are non-decreasing`() {
        val provider = SystemMonotonicTimeProvider()

        var previous = provider.nowNanos()
        repeat(100) {
            val current = provider.nowNanos()
            assertThat(current).isAtLeast(previous)
            previous = current
        }
    }

    @Test
    fun `fake provider yields deterministic controlled readings`() {
        val fake = FakeMonotonicTimeProvider(1_000_000L)

        assertThat(fake.nowNanos()).isEqualTo(1_000_000L)

        fake.advanceMillis(5)
        assertThat(fake.nowNanos()).isEqualTo(6_000_000L)

        fake.advanceNanos(250L)
        assertThat(fake.nowNanos()).isEqualTo(6_000_250L)

        fake.setNanos(42L)
        assertThat(fake.nowNanos()).isEqualTo(42L)
    }
}
