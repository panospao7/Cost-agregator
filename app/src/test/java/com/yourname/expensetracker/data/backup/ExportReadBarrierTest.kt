package com.yourname.expensetracker.data.backup

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ExportReadBarrierTest {

    private val maintenanceMode = mockk<RestoreMaintenanceMode>()
    private lateinit var readBarrier: DatabaseReadBarrier

    @Before
    fun setup() {
        readBarrier = DatabaseReadBarrier(maintenanceMode)
    }

    private fun setMode(mode: RestoreMaintenanceMode.Mode) {
        every { maintenanceMode.currentMode() } returns mode
    }

    // The per-mode / per-policy read-barrier cases (export generation, restart-required,
    // snapshot-only export policy, normal reads) live in the read-barrier table in
    // DatabaseBarrierTest. This class keeps the Flow-helper tests (known-hanging-tests HANG-003).

    // ── guardedDatabaseRead Flow helper ───────────────────────────

    @Test
    fun guardedDatabaseRead_passes_in_NORMAL() = runTest {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        val flow = kotlinx.coroutines.flow.flowOf(1, 2, 3)
            .guardedDatabaseRead(readBarrier, "test")
        assertEquals(listOf(1, 2, 3), flow.toList())
    }

    @Test
    fun guardedDatabaseRead_throws_in_restore() = runTest {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        val flow = kotlinx.coroutines.flow.flowOf(1, 2, 3)
            .guardedDatabaseRead(readBarrier, "test")
        val exception = runCatching { flow.toList() }.exceptionOrNull()
        assertNotNull("Collection must fail while reads are blocked", exception)
        assertEquals(DatabaseAccessBlockedException::class, exception!!::class)
    }

    // ── blockedDuringRestore Flow helper ──────────────────────────

    @Test
    fun blockedDuringRestore_emits_in_NORMAL() = runTest {
        val modeFlow = MutableStateFlow(RestoreMaintenanceMode.Mode.NORMAL)
        val flow = kotlinx.coroutines.flow.flowOf(42)
            .blockedDuringRestore(modeFlow, "test")
        // The modeFlow StateFlow never completes, so bounded collection is required:
        // take the first emission from the upstream and stop.
        val first = withTimeout(5_000) { flow.firstOrNull() }
        assertNotNull("Upstream value must be emitted while mode is NORMAL", first)
        assertEquals(42, first)
    }

    @Test
    fun blockedDuringRestore_emits_nothing_in_restore() = runTest {
        val modeFlow = MutableStateFlow(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        val flow = kotlinx.coroutines.flow.flowOf(42)
            .blockedDuringRestore(modeFlow, "test")
        // flatMapLatest switches to emptyFlow() in non-NORMAL mode, so no value is
        // ever emitted. Bounded wait proves the upstream value is not emitted
        // (collection stays suspended until the timeout elapses).
        val first = withTimeoutOrNull(5_000) { flow.firstOrNull() }
        assertNull("No value must be emitted while mode is non-NORMAL", first)
    }
}
