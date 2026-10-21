package com.yourname.expensetracker.ui.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R27-S3: restartApp relaunches the launcher activity in a new task, then exits once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppProcessRestarterTest {

    private val packageManager = mockk<PackageManager>()
    private val context = mockk<Context>(relaxed = true).also {
        every { it.packageName } returns "com.yourname.expensetracker"
        every { it.packageManager } returns packageManager
    }

    @Test
    fun `restartApp starts the launcher restart task then exits exactly once`() {
        val component = ComponentName("com.yourname.expensetracker", "com.yourname.expensetracker.ui.MainActivity")
        every { packageManager.getLaunchIntentForPackage("com.yourname.expensetracker") } returns
            Intent(Intent.ACTION_MAIN).setComponent(component)
        val started = slot<Intent>()
        every { context.startActivity(capture(started)) } returns Unit
        var exitCalls = 0

        restartApp(context) { exitCalls++ }

        verify(exactly = 1) { context.startActivity(any()) }
        assertEquals(component, started.captured.component)
        assertTrue(started.captured.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(started.captured.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertEquals(1, exitCalls)
    }

    @Test
    fun `restartApp still exits once when no launch intent resolves`() {
        every { packageManager.getLaunchIntentForPackage(any()) } returns null
        var exitCalls = 0

        restartApp(context) { exitCalls++ }

        verify(exactly = 0) { context.startActivity(any()) }
        assertEquals(1, exitCalls)
    }
}
