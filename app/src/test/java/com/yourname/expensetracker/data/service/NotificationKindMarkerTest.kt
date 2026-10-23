package com.yourname.expensetracker.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S5a: [NotificationKindMarker] (TECH-12 option a; INV-12, INV-13 post side; AE-112, AE-121).
 * The tokens and the key are literals on purpose: they are what earlier releases wrote into active
 * notifications, so a change must fail here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationKindMarkerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("title")
            .setContentText("text")

    // --- pinned literals ---------------------------------------------------------------------

    @Test
    fun `INV-12 the six kind tokens are pinned as literals`() {
        assertEquals("BILL", NotificationKind.BILL.stableToken)
        assertEquals("ANOMALY", NotificationKind.ANOMALY.stableToken)
        assertEquals("BUDGET", NotificationKind.BUDGET.stableToken)
        assertEquals("WARRANTY", NotificationKind.WARRANTY.stableToken)
        assertEquals("BRIEFING", NotificationKind.BRIEFING.stableToken)
        assertEquals("RECEIPT_MATCH", NotificationKind.RECEIPT_MATCH.stableToken)
        assertEquals(6, NotificationKind.entries.size)
        assertEquals(6, NotificationKind.entries.map { it.stableToken }.toSet().size)
    }

    @Test
    fun `TECH-12 the extras key is pinned as a literal`() {
        assertEquals("com.yourname.expensetracker.extra.NOTIFICATION_KIND", NotificationKindMarker.EXTRA_KEY)
    }

    @Test
    fun `TECH-12 the extras key is not a platform text extra and is app namespaced`() {
        val platformTextExtras = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_TEXT_LINES,
            Notification.EXTRA_CONVERSATION_TITLE,
            Notification.EXTRA_MESSAGES
        )

        platformTextExtras.forEach { assertNotEquals(it, NotificationKindMarker.EXTRA_KEY) }
        assertFalse(NotificationKindMarker.EXTRA_KEY.startsWith("android."))
        assertTrue(NotificationKindMarker.EXTRA_KEY.startsWith("com.yourname.expensetracker."))
    }

    // --- stamp and read ------------------------------------------------------------------------

    @Test
    fun `AE-112 stamping stores exactly the literal token for every kind`() {
        NotificationKind.entries.forEach { kind ->
            val notification = NotificationKindMarker.stamp(baseBuilder(), kind).build()

            assertEquals(kind.stableToken, notification.extras.getString(NotificationKindMarker.EXTRA_KEY))
            assertEquals(kind.stableToken, NotificationKindMarker.read(notification))
            assertEquals(kind.stableToken, NotificationKindMarker.read(notification.extras))
        }
    }

    @Test
    fun `AE-121 an unstamped notification has no marker`() {
        val notification = baseBuilder().build()

        assertNull(NotificationKindMarker.read(notification))
        assertFalse(notification.extras.containsKey(NotificationKindMarker.EXTRA_KEY))
    }

    @Test
    fun `TECH-12 stamping returns the same builder and leaves visible text untouched`() {
        val builder = baseBuilder()

        val returned = NotificationKindMarker.stamp(builder, NotificationKind.BILL)
        val notification = returned.build()

        assertTrue(returned === builder)
        assertEquals("title", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("text", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(notification.tickerText)
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
    }

    @Test
    fun `TECH-12 the marker value is the token only and no other app extra is added`() {
        val plain = baseBuilder().build()
        val stamped = NotificationKindMarker.stamp(baseBuilder(), NotificationKind.WARRANTY).build()

        val added = stamped.extras.keySet() - plain.extras.keySet()
        assertEquals(setOf(NotificationKindMarker.EXTRA_KEY), added)
        assertEquals("WARRANTY", stamped.extras.getString(NotificationKindMarker.EXTRA_KEY))
    }

    // --- read is total and unvalidated ----------------------------------------------------------

    @Test
    fun `read returns null for a null notification`() {
        assertNull(NotificationKindMarker.read(null as Notification?))
    }

    @Test
    fun `read returns null for a null bundle`() {
        assertNull(NotificationKindMarker.read(null as Bundle?))
    }

    @Test
    fun `read returns null for a non string value without throwing`() {
        assertNull(NotificationKindMarker.read(Bundle().apply { putInt(NotificationKindMarker.EXTRA_KEY, 7) }))
        assertNull(NotificationKindMarker.read(Bundle().apply { putBoolean(NotificationKindMarker.EXTRA_KEY, true) }))
        assertNull(
            NotificationKindMarker.read(
                Bundle().apply { putStringArray(NotificationKindMarker.EXTRA_KEY, arrayOf("BILL")) }
            )
        )
        assertNull(NotificationKindMarker.read(Bundle().apply { putBundle(NotificationKindMarker.EXTRA_KEY, Bundle()) }))
    }

    @Test
    fun `read returns null for an empty bundle`() {
        assertNull(NotificationKindMarker.read(Bundle()))
    }

    @Test
    fun `read returns the raw stored string without validating it`() {
        val untrusted = Bundle().apply { putString(NotificationKindMarker.EXTRA_KEY, "NOT_A_KIND") }

        assertEquals("NOT_A_KIND", NotificationKindMarker.read(untrusted))
    }

    // --- stamping does not change id or tag --------------------------------------------------------

    @Test
    fun `TECH-12 stamping does not change the posted id or tag`() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "test", NotificationManager.IMPORTANCE_DEFAULT)
        )

        manager.notify(TAG, 20042, NotificationKindMarker.stamp(baseBuilder(), NotificationKind.RECEIPT_MATCH).build())
        manager.notify(TAG, 7, baseBuilder().build())

        val active = manager.activeNotifications.associateBy { it.id }
        assertEquals(setOf(20042, 7), active.keys)
        assertEquals(TAG, active.getValue(20042).tag)
        assertEquals(TAG, active.getValue(7).tag)
        assertEquals(
            "RECEIPT_MATCH",
            NotificationKindMarker.read(active.getValue(20042).notification)
        )
        assertNull(NotificationKindMarker.read(active.getValue(7).notification))
    }

    @Test
    fun `TECH-12 re-posting the same id replaces rather than duplicates after stamping`() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "test", NotificationManager.IMPORTANCE_DEFAULT)
        )

        manager.notify(10003, baseBuilder().build())
        manager.notify(10003, NotificationKindMarker.stamp(baseBuilder(), NotificationKind.WARRANTY).build())

        val active = manager.activeNotifications.filter { it.id == 10003 }
        assertEquals(1, active.size)
        assertEquals("WARRANTY", NotificationKindMarker.read(active.single().notification))
    }

    private companion object {
        const val CHANNEL_ID = "marker_test_channel"
        const val TAG = "marker_test_tag"
    }
}
