package com.yourname.expensetracker.domain.privacy.applock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S18 part (ii-a), F4: the domain-owned [AppLockSettingsWriteSerializer] is the ONE lock shared by the settings
 * ViewModel's writers (WRITE 1 and WRITE 2, simulated here by a holder that takes the serializer directly) and every
 * path of [AppLockSettingsOperations]. Each gated test completes its gate in a `finally` so a regression fails fast
 * instead of hanging `runBlocking`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockSettingsWriteSerializerSharingTest {

    private fun disabledRig(serializer: AppLockSettingsWriteSerializer): SettingsOperationsRig {
        val (h, controller) = disabledControllerFor()
        h.emitted.clear()
        h.emittedMetadata.clear()
        return SettingsOperationsRig(h, controller, serializer = serializer)
    }

    private fun stored(rig: SettingsOperationsRig): AppLockPrivacySettings =
        (rig.h.repository.loadState as AppLockSettingsLoadState.Loaded).settings

    @Test
    fun `a WRITE 1 style holder blocks the enable pre-read, so the trigger gets the committed before value`() = runBlocking {
        val serializer = AppLockSettingsWriteSerializer()
        val rig = disabledRig(serializer)
        val h = rig.h
        // Stored: lock off, discreet ON. The holder (the ViewModel's discreet-off write) takes the serializer first.
        h.repository.loadState = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = true))
        val gate = CompletableDeferred<Unit>()
        try {
            val holder = async(start = CoroutineStart.UNDISPATCHED) {
                serializer.withExclusiveWrite {
                    gate.await()
                    h.repository.loadState = AppLockSettingsLoadState.Loaded(stored(rig).copy(discreetNotifications = false))
                }
            }

            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestEnable())
            h.settle()
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            assertTrue("the apply must queue behind the holder and not read or write yet", h.repository.lockWrites.isEmpty())

            gate.complete(Unit)
            holder.await()
            h.settle()

            assertEquals(listOf(true), h.repository.lockWrites)
            assertTrue(rig.triggerCalls.contains(false to true))
            assertFalse("a stale (true, true) read would skip the sweep: ${rig.triggerCalls}", rig.triggerCalls.contains(true to true))
            assertEquals(AppLockState.Unlocked, rig.controller.state.value)
            assertTrue(rig.uncaught.isEmpty())
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `a review-save style holder and a lock apply serialise, in both directions`() = runBlocking {
        val serializer = AppLockSettingsWriteSerializer()

        // Direction 1: the holder (review-save) is inside; the apply waits, then runs after it.
        val rig = disabledRig(serializer)
        val h = rig.h
        val gate = CompletableDeferred<Unit>()
        try {
            val holder = async(start = CoroutineStart.UNDISPATCHED) { serializer.withExclusiveWrite { gate.await() } }
            assertEquals(SettingsRequestResult.STARTED, rig.ops.requestEnable())
            h.settle()
            h.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            assertTrue("the lock apply waits for the review-save holder", h.repository.lockWrites.isEmpty())

            gate.complete(Unit)
            holder.await()
            h.settle()
            assertEquals(listOf(true), h.repository.lockWrites)
            assertEquals(AppLockState.Unlocked, rig.controller.state.value)
        } finally {
            gate.complete(Unit)
        }

        // Direction 2: the apply is inside its durable write; a review-save style writer waits for it.
        val rig2 = disabledRig(serializer)
        val h2 = rig2.h
        val writeGate = CompletableDeferred<Unit>()
        h2.repository.lockWriteGate = writeGate
        val order = mutableListOf<String>()
        try {
            assertEquals(SettingsRequestResult.STARTED, rig2.ops.requestEnable())
            h2.settle()
            h2.authenticator.deliver(DeviceAuthOutcome.Succeeded)
            assertEquals("the apply is inside the serializer, at its write", listOf(true), h2.repository.lockWrites)

            val review = async(start = CoroutineStart.UNDISPATCHED) { serializer.withExclusiveWrite { order += "review" } }
            assertTrue("the review-save waits for the apply", order.isEmpty())

            writeGate.complete(Unit)
            review.await()
            h2.settle()
            assertEquals(listOf("review"), order)
            assertEquals(AppLockState.Unlocked, rig2.controller.state.value)
        } finally {
            writeGate.complete(Unit)
        }
    }
}
