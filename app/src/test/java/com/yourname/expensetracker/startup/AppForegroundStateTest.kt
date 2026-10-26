package com.yourname.expensetracker.startup

import androidx.lifecycle.LifecycleOwner
import com.yourname.expensetracker.domain.ai.service.AppForegroundState
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForegroundStateTest {

    private val owner = mockk<LifecycleOwner>(relaxed = true)

    @Test
    fun `defaults to not foreground before any lifecycle event`() {
        val state = ProcessAppForegroundState()

        assertFalse(state.isAppInForeground())
    }

    @Test
    fun `onStart marks the app as foreground`() {
        val state = ProcessAppForegroundState()

        state.onStart(owner)

        assertTrue(state.isAppInForeground())
    }

    @Test
    fun `onStop after onStart marks the app as not foreground`() {
        val state = ProcessAppForegroundState()
        state.onStart(owner)

        state.onStop(owner)

        assertFalse(state.isAppInForeground())
    }

    @Test
    fun `onStop before any onStart stays not foreground`() {
        val state = ProcessAppForegroundState()

        state.onStop(owner)

        assertFalse(state.isAppInForeground())
    }

    @Test
    fun `repeated onStart events are idempotent`() {
        val state = ProcessAppForegroundState()

        state.onStart(owner)
        state.onStart(owner)

        assertTrue(state.isAppInForeground())
        state.onStop(owner)
        assertFalse(state.isAppInForeground())
    }

    @Test
    fun `repeated onStop events are idempotent`() {
        val state = ProcessAppForegroundState()
        state.onStart(owner)

        state.onStop(owner)
        state.onStop(owner)

        assertFalse(state.isAppInForeground())
        state.onStart(owner)
        assertTrue(state.isAppInForeground())
    }

    @Test
    fun `is usable through the domain port`() {
        val impl = ProcessAppForegroundState()
        val port: AppForegroundState = impl

        assertFalse(port.isAppInForeground())
        impl.onStart(owner)
        assertTrue(port.isAppInForeground())
    }
}
