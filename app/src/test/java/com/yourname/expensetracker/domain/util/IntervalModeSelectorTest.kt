package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BL-08: tie-break rule of the shared interval-mode selector. */
class IntervalModeSelectorTest {

    private val monthTolerance: (Int) -> Double = { if (it in 28..31) 3.0 else (it * 0.2).coerceAtLeast(1.0) }

    @Test
    fun `empty list gives null`() {
        assertNull(IntervalModeSelector.selectMode(emptyList(), monthTolerance))
    }

    @Test
    fun `single interval is its own mode`() {
        assertEquals(30, IntervalModeSelector.selectMode(listOf(30), monthTolerance))
    }

    @Test
    fun `unique most frequent value wins without tie-break`() {
        assertEquals(7, IntervalModeSelector.selectMode(listOf(7, 7, 30), monthTolerance))
    }

    @Test
    fun `tie prefers candidate with most intervals within tolerance`() {
        assertEquals(30, IntervalModeSelector.selectMode(listOf(24, 30, 31), monthTolerance))
        assertEquals(30, IntervalModeSelector.selectMode(listOf(31, 30, 24), monthTolerance))
    }

    @Test
    fun `tie on tolerance count prefers value closest to median`() {
        // Each value covers only itself; the median is 19.
        assertEquals(19, IntervalModeSelector.selectMode(listOf(3, 19, 47), monthTolerance))
    }

    @Test
    fun `full tie falls back to the smaller value deterministically`() {
        assertEquals(10, IntervalModeSelector.selectMode(listOf(10, 20), { 1.0 }))
        assertEquals(10, IntervalModeSelector.selectMode(listOf(20, 10), { 1.0 }))
    }
}
