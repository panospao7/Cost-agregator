package com.yourname.expensetracker.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreVerificationNoticeStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(RestoreVerificationNoticeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun newStore() = RestoreVerificationNoticeStore(context, Dispatchers.Unconfined)

    @Test
    fun `consume with nothing recorded is false`() = runBlocking {
        assertFalse(newStore().consume())
    }

    @Test
    fun `record true is consumed exactly once across store instances`() = runBlocking {
        newStore().record(true)

        val reopened = newStore()
        assertTrue(reopened.consume())
        assertFalse(reopened.consume())
        assertFalse(newStore().consume())
    }

    @Test
    fun `record false clears a previously recorded notice`() = runBlocking {
        val store = newStore()
        store.record(true)
        store.record(false)

        assertFalse(store.consume())
    }

    @Test
    fun `consume removes the key and the file holds only the one boolean`() = runBlocking {
        val store = newStore()
        store.record(true)
        val prefs = context.getSharedPreferences(RestoreVerificationNoticeStore.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(setOf(RestoreVerificationNoticeStore.KEY_PENDING), prefs.all.keys)

        store.consume()

        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `J-11-2 notification unreadable not recorded consumes false`() = runBlocking {
        assertFalse(newStore().consumeNotificationUnreadable())
    }

    @Test
    fun `J-11-2 notification unreadable recorded is consumed true once then false across instances`() = runBlocking {
        newStore().recordNotificationUnreadable(true)

        val reopened = newStore()
        assertTrue(reopened.consumeNotificationUnreadable())
        assertFalse(reopened.consumeNotificationUnreadable())
        assertFalse(newStore().consumeNotificationUnreadable())
    }

    @Test
    fun `J-11-2 notification unreadable flag does not disturb the not-verifiable flag`() = runBlocking {
        val store = newStore()
        store.record(true)
        store.recordNotificationUnreadable(true)

        assertTrue(store.consumeNotificationUnreadable())
        // The not-verifiable flag is still pending after the other flag was consumed.
        assertTrue(store.consume())
        assertFalse(store.consume())

        store.recordNotificationUnreadable(true)
        assertFalse("consuming the old flag must not consume the new one", store.consume())
        assertTrue(store.consumeNotificationUnreadable())
    }
}
