package com.yourname.expensetracker.ui.permissions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationPromptStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(NotificationPromptStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `fresh install has not been prompted`() {
        assertFalse(NotificationPromptStore(context).hasPrompted())
    }

    @Test
    fun `prompted flag persists across store instances and drives the policy to no`() {
        NotificationPromptStore(context).markPrompted()

        val reopened = NotificationPromptStore(context)
        assertTrue(reopened.hasPrompted())
        assertFalse(
            NotificationPromptPolicy.shouldAutoPrompt(
                granted = false,
                sdkInt = 33,
                alreadyAsked = reopened.hasPrompted()
            )
        )
    }
}
