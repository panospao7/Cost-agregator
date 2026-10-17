package com.yourname.expensetracker.data.backup

import android.content.Context
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Hilt-free, read-only peek at the persisted [RestoreMaintenanceMode] state (R25/R27).
 *
 * Used by the gated Room open helper, which is constructed before (and without) the DI graph.
 * It reads the same SharedPreferences and critical sentinel as [RestoreMaintenanceMode] and decodes
 * them with the shared pure [RestoreMaintenanceMode.decodePersistedState], so the two can never
 * disagree on what a persisted state means. It never writes.
 */
internal object RestoreMaintenanceModePeek {

    /** Effective persisted mode, or null when the state is unreadable or invalid. */
    fun effectiveMode(context: Context): RestoreMaintenanceMode.Mode? {
        val sentinelExists: Boolean? = try {
            File(context.noBackupFilesDir, RestoreMaintenanceMode.CRITICAL_SENTINEL_FILE).exists()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        val preferences = try {
            context.getSharedPreferences(RestoreMaintenanceMode.PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        val snapshot: Map<String, *>? = if (sentinelExists == false && preferences != null) {
            try {
                preferences.all.toMap()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        return RestoreMaintenanceMode
            .decodePersistedState(sentinelExists, snapshot, prefsAvailable = preferences != null)
            .state
            ?.effectiveMode
    }

    /** True only when the persisted effective mode is [RestoreMaintenanceMode.Mode.RESTORE_SWAPPING]. */
    fun isSwapping(context: Context): Boolean =
        effectiveMode(context) == RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
}
