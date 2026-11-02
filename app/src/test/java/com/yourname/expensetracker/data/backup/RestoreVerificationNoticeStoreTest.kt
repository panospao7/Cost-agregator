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
}
