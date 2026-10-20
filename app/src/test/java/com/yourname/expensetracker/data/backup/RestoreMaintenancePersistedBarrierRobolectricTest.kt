package com.yourname.expensetracker.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric-only barrier case split out of DatabaseBarrierTest: it needs real
 * SharedPreferences and the noBackupFilesDir critical sentinel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreMaintenancePersistedBarrierRobolectricTest {

    @Test
    fun persisted_resume_pending_blocks_current_and_fresh_write_barriers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val criticalSentinel = File(context.noBackupFilesDir, "restore_maintenance_critical")
        criticalSentinel.delete()
        val prefs = context.getSharedPreferences("restore_maintenance_mode", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString("current_mode", RestoreMaintenanceMode.Mode.NORMAL.name)
            .putBoolean("worker_resume_pending", true)
            .commit()
        try {
            val current = RestoreMaintenanceMode(context, FakeTimeProvider(1716163200000L))
            val fresh = RestoreMaintenanceMode(context, FakeTimeProvider(1716163200000L))

            assertThrows(DatabaseAccessBlockedException::class.java) {
                DatabaseWriteBarrier(current).checkWritesAllowed("current_pending")
            }
            assertThrows(DatabaseAccessBlockedException::class.java) {
                DatabaseWriteBarrier(fresh).checkWritesAllowed("fresh_pending")
            }
        } finally {
            prefs.edit().clear().commit()
            criticalSentinel.delete()
        }
    }
}
